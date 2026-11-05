// Checks that a stream is for the title that was asked for, using its file name.
//
// AIOStreams already filters by title, season and episode. This is a second check, so a mislabelled
// result can't play the wrong episode or a different film with the same name. A stream is only
// rejected when its name clearly says something else. A name with no season, episode or year
// is kept.

const IMDB_EPISODE = /^(tt\d+):(\d+):(\d+)$/;

/** "tt0903747:1:2" -> { imdb, season, episode }, or null for other id schemes. */
export function parseEpisodeId(id) {
  const m = IMDB_EPISODE.exec(id || '');
  return m ? { imdb: m[1], season: Number(m[2]), episode: Number(m[3]) } : null;
}

/** IMDb id for a meta or meta preview, if it has one. */
export function imdbOf(meta) {
  for (const v of [meta?.imdb_id, meta?.imdbId, meta?.id]) {
    if (typeof v === 'string' && /^tt\d+$/.test(v)) return v;
  }
  return null;
}

export function streamName(stream) {
  return stream?.behaviorHints?.filename || '';
}

/** Text that may describe the file: the file name, then the addon's own labels. */
function streamText(stream) {
  return [streamName(stream), stream?.title, stream?.description].filter(Boolean).join('\n');
}

/**
 * Season/episode markers in a release name.
 * Returns { season, episodes: [..] } for "S01E02", "S01E02-E03", "S01E02E03", "1x02";
 * { season, episodes: null } for a season pack ("S01", "Season 1"); or null when there's none.
 */
export function parseEpisodeMarker(name) {
  const text = String(name || '');
  let m = /\bS(\d{1,2})[ ._-]?E(\d{1,4})(?:(?:[ ._-]?-?[ ._-]?E|-)(\d{1,4})(?![\dpi]))*/i.exec(text);
  if (m) {
    const season = Number(m[1]);
    const nums = [...m[0].matchAll(/(?:E|-)(\d{1,4})/gi)].map((x) => Number(x[1]));
    const first = nums[0];
    const last = nums[nums.length - 1];
    const range = /-/.test(m[0].slice(m[0].search(/E/i))) && last > first && last - first < 100;
    const episodes = range ? Array.from({ length: last - first + 1 }, (_, i) => first + i) : [...new Set(nums)];
    return { season, episodes };
  }
  m = /(?:^|[^\dx])(\d{1,2})x(\d{2,3})(?![\dp])/i.exec(text);
  if (m) return { season: Number(m[1]), episodes: [Number(m[2])] };
  m = /\b(?:S(\d{1,2})(?![\dE])|Season[ ._-]?(\d{1,2}))\b/i.exec(text);
  if (m) return { season: Number(m[1] || m[2]), episodes: null };
  return null;
}

/** The release year in a file name: the last 19xx/20xx that isn't part of the title itself. */
export function parseYear(name, title) {
  const titleNumbers = new Set(String(title || '').match(/\d{4}/g) || []);
  const years = (String(name || '').match(/(?<![\d])(19\d{2}|20\d{2})(?![\dp])/g) || []).filter((y) => !titleNumbers.has(y));
  return years.length ? Number(years[years.length - 1]) : null;
}

export function metaYear(meta) {
  const m = /(19|20)\d{2}/.exec(String(meta?.year || meta?.releaseInfo || meta?.released || ''));
  return m ? Number(m[0]) : null;
}

/**
 * Why this stream is wrong for the request, or null if nothing contradicts it.
 * `want` is { type: 'movie', title, year } or { type: 'series', season, episode }.
 */
export function mismatch(stream, want) {
  if (want.type === 'series' && want.season !== undefined) {
    // Labels are checked too: AIOStreams puts the file name in the description when the addon
    // gives no behaviorHints.filename. Only the first marker is used, so a pack listing many
    // episodes in its label is judged by the file actually picked.
    const marker = parseEpisodeMarker(streamName(stream)) || parseEpisodeMarker(streamText(stream));
    if (!marker) return null;
    if (marker.season !== want.season) {
      return `season ${marker.season}, wanted ${want.season}`;
    }
    if (marker.episodes && !marker.episodes.includes(want.episode)) {
      return `episode ${marker.episodes.join('-')}, wanted ${want.episode}`;
    }
    return null;
  }
  if (want.type === 'movie' && want.year) {
    // Years only from the file name: labels often contain other dates.
    const year = parseYear(streamName(stream), want.title);
    if (year && Math.abs(year - want.year) > 1) return `year ${year}, wanted ${want.year}`;
  }
  return null;
}
