// Turns Stremio catalogs, metadata and streams into the shapes the Xtream Codes API uses.

import { AddonClient, HttpError } from './addon.js';
import { imdbOf, metaYear, mismatch, parseEpisodeId } from './match.js';

const unix = () => Math.floor(Date.now() / 1000);

function minutes(runtime) {
  const m = /(\d+)\s*h(?:ours?)?\s*(\d+)?/i.exec(runtime || '');
  if (m) return Number(m[1]) * 60 + Number(m[2] || 0);
  const n = parseInt(runtime, 10);
  return Number.isFinite(n) ? n : 0;
}

function hms(totalMinutes) {
  const s = totalMinutes * 60;
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor(s / 60) % 60)}:00`;
}

function people(value) {
  return Array.isArray(value) ? value.join(', ') : value || '';
}

function trailer(meta) {
  return meta.trailers?.[0]?.source || meta.trailerStreams?.[0]?.ytId || '';
}

function date(meta) {
  return (meta.released || '').slice(0, 10) || String(meta.releaseInfo || meta.year || '').slice(0, 4);
}

function rating(meta) {
  const r = parseFloat(meta.imdbRating);
  return Number.isFinite(r) ? r : 0;
}

/** Cache that keeps serving the last good value if a refresh fails. */
class Cache {
  constructor(ttlMs) {
    this.ttlMs = ttlMs;
    this.map = new Map();
  }

  async get(key, load) {
    const hit = this.map.get(key);
    if (hit && Date.now() - hit.at < this.ttlMs) return hit.value;
    if (hit?.pending) return hit.pending;
    const pending = load().then(
      (value) => {
        this.map.set(key, { value, at: Date.now() });
        return value;
      },
      (err) => {
        if (hit && 'value' in hit) {
          this.map.set(key, { ...hit, pending: null });
          console.warn(`Refresh failed, serving cached copy: ${err.message}`);
          return hit.value;
        }
        this.map.delete(key);
        throw err;
      },
    );
    this.map.set(key, { ...(hit || {}), pending });
    return pending;
  }

  delete(key) {
    this.map.delete(key);
  }
}

export class Library {
  constructor(config, ids) {
    this.config = config;
    this.ids = ids;
    const opts = { timeoutMs: config.requestTimeoutMs, streamTimeoutMs: config.streamTimeoutMs };
    this.aio = new AddonClient(config.aioBase, opts);
    this.catalogSources = [this.aio, ...config.catalogAddons.map((b) => new AddonClient(b, opts))];
    this.metaSources = [...this.catalogSources, new AddonClient(config.metaFallback, opts)];
    this.cinemeta = new AddonClient(config.cinemetaBase, opts);
    this.catalogCache = new Cache(config.catalogTtlMs);
    this.metaCache = new Cache(config.catalogTtlMs);
    this.streamCache = new Cache(config.streamTtlMs);
    this.previews = new Map(); // stremio id -> catalog preview, for the year check at play time
  }

  // ---- categories -------------------------------------------------------------------------

  /** Every browsable catalog of this type across the sources. Falls back to Cinemeta if none. */
  async catalogs(type) {
    return this.catalogCache.get(`catalogs:${type}`, async () => {
      const found = [];
      let failure = null;
      for (const source of this.catalogSources) {
        try {
          for (const c of await source.browsableCatalogs()) {
            if (c.type === type) found.push({ source, catalog: c });
          }
        } catch (err) {
          failure = err;
          console.warn(`Skipping catalogs from ${source.label}: ${err.message}`);
        }
      }
      // Don't cache an empty list just because an addon was briefly down; try again next request.
      if (!found.length && failure) throw failure;
      if (!found.length) {
        const fallback = await this.cinemeta.browsableCatalogs().catch(() => []);
        for (const c of fallback) if (c.type === type) found.push({ source: this.cinemeta, catalog: c });
      }
      return found.map(({ source, catalog }) => ({
        source,
        catalog,
        categoryId: this.ids.num('category', `${source.base}|${type}|${catalog.id}`),
        name: catalog.name || catalog.id,
      }));
    });
  }

  async categories(type) {
    return (await this.catalogs(type)).map((c) => ({
      category_id: String(c.categoryId),
      category_name: c.name,
      parent_id: 0,
    }));
  }

  async catalogItems(entry) {
    const { source, catalog } = entry;
    return this.catalogCache.get(`items:${entry.categoryId}`, async () => {
      const metas = [];
      const pages = AddonClient.pageable(catalog) ? this.config.catalogPages : 1;
      let skip = 0;
      for (let page = 0; page < pages; page++) {
        const batch = await source.catalog(catalog.type, catalog.id, skip);
        metas.push(...batch.filter((m) => m && m.id && m.name));
        if (!batch.length) break;
        skip += batch.length;
      }
      return metas;
    });
  }

  /** Items for one category, or all categories merged (each item listed once, with every category it is in). */
  async items(type, categoryId) {
    const all = await this.catalogs(type);
    const wanted = categoryId ? all.filter((c) => String(c.categoryId) === String(categoryId)) : all;
    const results = await Promise.all(
      wanted.map((entry) =>
        this.catalogItems(entry).then(
          (metas) => ({ entry, metas }),
          (err) => {
            console.warn(`Catalog "${entry.name}" from ${entry.source.label} failed: ${err.message}`);
            return { entry, metas: [] };
          },
        ),
      ),
    );

    const byId = new Map();
    const now = unix();
    let num = 0;
    for (const { entry, metas } of results) {
      metas.forEach((preview, index) => {
        // One id per title: the IMDb id when the catalog gives one, so the same film from a TMDB
        // catalog and an IMDb catalog is listed once, and streams are looked up by the id every
        // stream addon understands.
        const meta = { ...preview, id: imdbOf(preview) || preview.id };
        if (!this.previews.has(meta.id)) this.previews.set(meta.id, meta);
        const existing = byId.get(meta.id);
        if (existing) {
          existing.categories.push(entry.categoryId);
          return;
        }
        // Spacing "added" by catalog position keeps the catalog's order under "recently added" sorts.
        byId.set(meta.id, { meta, categories: [entry.categoryId], added: now - num * 60 - index, num: ++num });
      });
    }
    return [...byId.values()];
  }

  async vodStreams(categoryId) {
    const ext = this.config.containerExt;
    return (await this.items('movie', categoryId)).map(({ meta, categories, added, num }) => ({
      num,
      name: meta.name,
      title: meta.name,
      stream_type: 'movie',
      stream_id: this.ids.num('movie', meta.id),
      stream_icon: meta.poster || '',
      rating: rating(meta) || '',
      rating_5based: rating(meta) / 2,
      added: String(added),
      is_adult: 0,
      category_id: String(categoryId || categories[0]),
      category_ids: categories,
      container_extension: ext,
      custom_sid: '',
      direct_source: '',
    }));
  }

  seriesEntry(meta, extra = {}) {
    const runtime = minutes(meta.runtime);
    return {
      name: meta.name,
      title: meta.name,
      series_id: this.ids.num('series', meta.id),
      cover: meta.poster || '',
      plot: meta.description || '',
      cast: people(meta.cast),
      director: people(meta.director),
      genre: people(meta.genres || meta.genre),
      releaseDate: date(meta),
      release_date: date(meta),
      last_modified: String(unix()),
      rating: rating(meta) || '',
      rating_5based: rating(meta) / 2,
      backdrop_path: meta.background ? [meta.background] : [],
      youtube_trailer: trailer(meta),
      episode_run_time: runtime ? String(runtime) : '',
      ...extra,
    };
  }

  async series(categoryId) {
    return (await this.items('series', categoryId)).map(({ meta, categories, added, num }) =>
      this.seriesEntry(meta, {
        num,
        last_modified: String(added),
        category_id: String(categoryId || categories[0]),
        category_ids: categories,
      }),
    );
  }

  // ---- details ----------------------------------------------------------------------------

  async meta(type, id) {
    return this.metaCache.get(`${type}:${id}`, async () => {
      let lastError = null;
      for (const source of this.metaSources) {
        if (!(await source.supports('meta', type, id))) continue;
        try {
          const meta = await source.meta(type, id);
          if (meta) return meta;
        } catch (err) {
          lastError = err;
        }
      }
      throw lastError || new HttpError(404, `No details found for ${type} ${id}`);
    });
  }

  async vodInfo(vodId) {
    const id = this.ids.key('movie', vodId);
    if (!id) throw new HttpError(404, `Unknown movie ${vodId}`);
    const meta = await this.meta('movie', id);
    const runtime = minutes(meta.runtime);
    return {
      info: {
        name: meta.name,
        o_name: meta.name,
        cover_big: meta.poster || '',
        movie_image: meta.poster || '',
        releasedate: date(meta),
        episode_run_time: runtime,
        youtube_trailer: trailer(meta),
        director: people(meta.director),
        actors: people(meta.cast),
        cast: people(meta.cast),
        description: meta.description || '',
        plot: meta.description || '',
        age: '',
        country: people(meta.country),
        genre: people(meta.genres || meta.genre),
        backdrop_path: meta.background ? [meta.background] : [],
        duration_secs: runtime * 60,
        duration: hms(runtime),
        rating: rating(meta) || '',
        tmdb_id: meta.moviedb_id ? String(meta.moviedb_id) : '',
      },
      movie_data: {
        stream_id: Number(vodId),
        name: meta.name,
        title: meta.name,
        added: String(unix()),
        category_id: '',
        container_extension: this.config.containerExt,
        custom_sid: '',
        direct_source: '',
      },
    };
  }

  async seriesInfo(seriesId) {
    const id = this.ids.key('series', seriesId);
    if (!id) throw new HttpError(404, `Unknown series ${seriesId}`);
    const meta = await this.meta('series', id);
    const ext = this.config.containerExt;

    const episodes = {};
    const counters = {};
    for (const v of meta.videos || []) {
      const season = Number.isFinite(Number(v.season)) ? Number(v.season) : 1;
      counters[season] = (counters[season] || 0) + 1;
      const number = Number(v.episode ?? v.number) || counters[season];
      const stremioId = this.episodeStreamId(v, meta, id, season, number);
      const title = v.name || v.title || `Episode ${number}`;
      const runtime = minutes(v.runtime || meta.runtime);
      (episodes[season] ||= []).push({
        id: String(this.ids.num('episode', stremioId)),
        episode_num: number,
        title,
        container_extension: ext,
        info: {
          name: title,
          movie_image: v.thumbnail || meta.background || '',
          plot: v.overview || v.description || '',
          releasedate: (v.released || v.firstAired || '').slice(0, 10),
          duration_secs: runtime * 60,
          duration: hms(runtime),
          rating: '',
        },
        custom_sid: '',
        added: String(unix()),
        season,
        direct_source: '',
      });
    }

    const seasonNumbers = Object.keys(episodes)
      .map(Number)
      .sort((a, b) => (a === 0) - (b === 0) || a - b); // specials (season 0) last
    for (const s of seasonNumbers) episodes[s].sort((a, b) => a.episode_num - b.episode_num);

    return {
      seasons: seasonNumbers.map((s) => ({
        id: s,
        season_number: s,
        name: s === 0 ? 'Specials' : `Season ${s}`,
        episode_count: episodes[s].length,
        air_date: episodes[s][0]?.info.releasedate || '',
        overview: '',
        cover: meta.poster || '',
        cover_big: meta.poster || '',
      })),
      info: this.seriesEntry(meta, { category_id: '' }),
      episodes: Object.fromEntries(seasonNumbers.map((s) => [String(s), episodes[s]])),
    };
  }

  /**
   * The id stream addons expect for an episode: "tt…:season:episode". Metadata from addons that
   * use other ids (TMDB, TVDB) is converted when the show's IMDb id is known, because that is what
   * Torrentio, debrid addons and AIOStreams' own matching key on.
   */
  episodeStreamId(video, meta, seriesId, season, number) {
    if (parseEpisodeId(video.id)) return video.id;
    const imdb = imdbOf(meta) || imdbOf({ id: seriesId });
    if (imdb) return `${imdb}:${season}:${number}`;
    return video.id || `${seriesId}:${season}:${number}`;
  }

  // ---- playback ---------------------------------------------------------------------------

  /** What a correct stream has to match, from the request id and the title's details. */
  async expectation(type, id) {
    if (type === 'series') {
      const ep = parseEpisodeId(id);
      return ep ? { type, season: ep.season, episode: ep.episode } : { type };
    }
    let meta = this.previews.get(id);
    if (!metaYear(meta)) meta = (await this.meta('movie', id).catch(() => null)) || meta;
    return { type, title: meta?.name, year: metaYear(meta), imdb: imdbOf(meta) };
  }

  /** The stream AIOStreams ranks highest that a plain HTTP player can open and that is for this title. */
  async resolve(kind, num) {
    const ns = kind === 'movie' ? 'movie' : 'episode';
    const id = this.ids.key(ns, num);
    if (!id) throw new HttpError(404, `Unknown ${ns} ${num}`);
    const type = kind === 'movie' ? 'movie' : 'series';
    return this.streamCache.get(`${type}:${id}`, async () => {
      const want = await this.expectation(type, id);
      // Ask by IMDb id where possible; fall back to the original id if that finds nothing.
      const tries = [...new Set([want.imdb, id].filter(Boolean))];
      let playable = [];
      for (const streamId of tries) {
        const streams = await this.aio.streams(type, streamId);
        playable = streams.filter((s) => typeof s?.url === 'string' && /^https?:\/\//i.test(s.url));
        if (playable.length) break;
      }
      if (!playable.length) throw new HttpError(404, `AIOStreams found no playable stream for ${id}`);

      const rejected = [];
      const pick = playable.find((s) => {
        const reason = mismatch(s, want);
        if (reason) rejected.push(reason);
        return !reason;
      });
      if (rejected.length) console.log(`${id}: skipped ${rejected.length} stream(s) for the wrong title (${rejected.join('; ')})`);
      if (!pick) throw new HttpError(404, `Every stream AIOStreams found for ${id} is for a different ${type === 'movie' ? 'film' : 'episode'}`);

      const headers = pick.behaviorHints?.proxyHeaders?.request;
      return { id, url: pick.url, headers: headers && Object.keys(headers).length ? headers : null };
    });
  }

  forget(kind, id) {
    this.streamCache.delete(`${kind === 'movie' ? 'movie' : 'series'}:${id}`);
  }
}
