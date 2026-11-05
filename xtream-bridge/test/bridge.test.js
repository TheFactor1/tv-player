// End-to-end tests: a fake AIOStreams addon and a fake video host, with the bridge in between.

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { loadConfig } from '../src/config.js';
import { IdStore } from '../src/ids.js';
import { Library } from '../src/library.js';
import { createServer } from '../src/server.js';

process.env.LOG_REQUESTS = 'false';

const VIDEO = Buffer.from('0123456789abcdefghij');
let streamCalls = 0;
let addon, video, bridge, dataDir, base;

function listen(server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${server.address().port}`)));
}

const json = (res, body) => {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
};

before(async () => {
  video = http.createServer((req, res) => {
    if (req.url === '/wrong') {
      res.writeHead(404);
      return res.end();
    }
    if (req.url === '/needs-header' && req.headers['x-token'] !== 'secret') {
      res.writeHead(403);
      return res.end();
    }
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    if (m) {
      const start = Number(m[1]);
      const end = m[2] ? Number(m[2]) : VIDEO.length - 1;
      res.writeHead(206, {
        'content-type': 'video/mp4',
        'content-range': `bytes ${start}-${end}/${VIDEO.length}`,
        'content-length': end - start + 1,
        'accept-ranges': 'bytes',
      });
      return res.end(VIDEO.subarray(start, end + 1));
    }
    res.writeHead(200, { 'content-type': 'video/mp4', 'content-length': VIDEO.length });
    res.end(VIDEO);
  });
  const videoBase = await listen(video);

  addon = http.createServer((req, res) => {
    const url = decodeURIComponent(req.url);
    if (url === '/cfg/manifest.json') {
      return json(res, {
        id: 'aiostreams',
        resources: ['stream', { name: 'meta', types: ['series'], idPrefixes: ['tt'] }, 'catalog'],
        types: ['movie', 'series'],
        catalogs: [
          { type: 'movie', id: 'popular', name: 'Popular Movies', extra: [{ name: 'skip' }] },
          { type: 'movie', id: 'new', name: 'New Movies' },
          { type: 'movie', id: 'search', name: 'Search', extra: [{ name: 'search', isRequired: true }] },
          { type: 'series', id: 'shows', name: 'Top Shows' },
        ],
      });
    }
    if (url === '/meta/manifest.json') {
      return json(res, { id: 'meta', resources: ['meta'], types: ['movie', 'series'], idPrefixes: ['tt'], catalogs: [] });
    }
    if (url === '/meta/meta/movie/tt0111161.json') {
      return json(res, { meta: { id: 'tt0111161', name: 'The Shawshank Redemption', runtime: '2h 22min', cast: ['Tim Robbins', 'Morgan Freeman'], description: 'Two imprisoned men bond.' } });
    }
    if (url === '/cfg/catalog/movie/popular.json') {
      return json(res, { metas: [
        { id: 'tt0111161', type: 'movie', name: 'The Shawshank Redemption', poster: 'p1', imdbRating: '9.3' },
        { id: 'tmdb:550', type: 'movie', name: 'Fight Club', poster: 'p2' },
        { id: 'tmdb:238', imdb_id: 'tt0068646', type: 'movie', name: 'The Godfather' },
      ] });
    }
    if (url === '/cfg/catalog/movie/popular/skip=3.json') {
      return json(res, { metas: [{ id: 'tt0068646', type: 'movie', name: 'The Godfather' }] });
    }
    if (url === '/cfg/catalog/movie/new.json') {
      return json(res, { metas: [
        { id: 'tt0111161', type: 'movie', name: 'The Shawshank Redemption' },
        { id: 'tt1375666', type: 'movie', name: 'Inception', releaseInfo: '2010' },
      ] });
    }
    if (url === '/cfg/catalog/series/shows.json') {
      return json(res, { metas: [{ id: 'tt0903747', type: 'series', name: 'Breaking Bad', poster: 'bb' }] });
    }
    if (url === '/cfg/meta/series/tt0903747.json') {
      return json(res, { meta: {
        id: 'tt0903747', type: 'series', name: 'Breaking Bad', runtime: '47 min',
        videos: [
          { id: 'tmdb:1396:1:2', season: 1, episode: 2, name: "Cat's in the Bag..." },
          { id: 'tmdb:1396:1:1', season: 1, episode: 1, name: 'Pilot', released: '2008-01-20T00:00:00.000Z' },
          { id: 'tt0903747:0:1', season: 0, episode: 1, name: 'Special' },
          { id: 'tt0903747:2:1', season: 2, episode: 1, name: 'Seven Thirty-Seven' },
        ],
      } });
    }
    if (url === '/cfg/stream/movie/tt0111161.json') {
      streamCalls++;
      return json(res, { streams: [
        { name: 'torrent only', infoHash: 'abc' },
        { name: '[RD+] 4K', url: `${videoBase}/shawshank.mkv` },
        { name: '1080p', url: `${videoBase}/other.mkv` },
      ] });
    }
    if (url === '/cfg/stream/movie/tt1375666.json') {
      return json(res, { streams: [
        { url: `${videoBase}/wrong`, behaviorHints: { filename: 'Inception.1999.1080p.mkv' } },
        { url: `${videoBase}/inception.mkv`, behaviorHints: { filename: 'Inception.2010.2160p.mkv' } },
      ] });
    }
    if (url === '/cfg/stream/series/tt0903747:1:1.json') {
      return json(res, { streams: [
        { url: `${videoBase}/wrong`, behaviorHints: { filename: 'Breaking.Bad.S01E02.1080p.mkv' } },
        {
        url: `${videoBase}/needs-header`,
        behaviorHints: { filename: 'Breaking.Bad.S01E01.Pilot.1080p.mkv', proxyHeaders: { request: { 'x-token': 'secret' } } },
      }] });
    }
    if (url.startsWith('/cfg/stream/')) return json(res, { streams: [] });
    res.writeHead(404);
    res.end();
  });
  const addonBase = await listen(addon);

  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-'));
  const config = loadConfig({
    AIOSTREAMS_URL: `${addonBase}/cfg/manifest.json`,
    META_ADDON: `${addonBase}/meta/manifest.json`,
    XTREAM_USER: 'matt',
    XTREAM_PASS: 'p@ss word',
    DATA_DIR: dataDir,
  });
  bridge = createServer(config, new Library(config, new IdStore(dataDir)));
  base = await listen(bridge);
});

after(() => {
  for (const s of [addon, video, bridge]) s.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

const api = (q) => fetch(`${base}/player_api.php?username=matt&password=${encodeURIComponent('p@ss word')}${q}`);
const apiJson = async (q) => (await api(q)).json();

test('login returns an active account', async () => {
  const body = await apiJson('');
  assert.equal(body.user_info.auth, 1);
  assert.equal(body.user_info.status, 'Active');
  assert.equal(body.server_info.server_protocol, 'http');
});

test('wrong password is refused', async () => {
  const res = await fetch(`${base}/player_api.php?username=matt&password=nope`);
  assert.deepEqual(await res.json(), { user_info: { auth: 0 } });
  const action = await fetch(`${base}/player_api.php?username=matt&password=nope&action=get_vod_streams`);
  assert.equal(action.status, 401);
  const playRes = await fetch(`${base}/movie/matt/nope/111161.mp4`, { redirect: 'manual' });
  assert.equal(playRes.status, 401);
});

test('POST login works too', async () => {
  const res = await fetch(`${base}/player_api.php`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: 'matt', password: 'p@ss word' }),
  });
  assert.equal((await res.json()).user_info.auth, 1);
});

test('movie categories skip catalogs that need a search', async () => {
  const cats = await apiJson('&action=get_vod_categories');
  assert.deepEqual(cats.map((c) => c.category_name), ['Popular Movies', 'New Movies']);
});

test('movie list pages through catalogs and lists each movie once', async () => {
  const cats = await apiJson('&action=get_vod_categories');
  const movies = await apiJson('&action=get_vod_streams');
  assert.deepEqual(movies.map((m) => m.name), ['The Shawshank Redemption', 'Fight Club', 'The Godfather', 'Inception']);
  assert.equal(movies[2].stream_id, 68646, 'TMDB catalog items with an IMDb id use it, and are listed once');
  const shawshank = movies[0];
  assert.equal(shawshank.stream_id, 111161);
  assert.deepEqual(shawshank.category_ids, cats.map((c) => Number(c.category_id)));
  assert.ok(movies[1].stream_id >= 1_000_000_000, 'non-IMDb ids get a counter id');

  const newOnly = await apiJson(`&action=get_vod_streams&category_id=${cats[1].category_id}`);
  assert.deepEqual(newOnly.map((m) => m.name), ['The Shawshank Redemption', 'Inception']);
  assert.equal(newOnly[0].category_id, cats[1].category_id);
});

test('movie play redirects to the first stream with a URL, and caches it', async () => {
  const before = streamCalls;
  for (let i = 0; i < 2; i++) {
    const res = await fetch(`${base}/movie/matt/${encodeURIComponent('p@ss word')}/111161.mp4`, { redirect: 'manual' });
    assert.equal(res.status, 302);
    assert.match(res.headers.get('location'), /\/shawshank\.mkv$/);
  }
  assert.equal(streamCalls - before, 1);
});

test('a stream for a film from another year is skipped', async () => {
  const res = await fetch(`${base}/movie/matt/${encodeURIComponent('p@ss word')}/1375666.mp4`, { redirect: 'manual' });
  assert.equal(res.status, 302);
  assert.match(res.headers.get('location'), /\/inception\.mkv$/);
});

test('no stream gives a 404', async () => {
  const res = await fetch(`${base}/movie/matt/${encodeURIComponent('p@ss word')}/68646.mp4`, { redirect: 'manual' });
  assert.equal(res.status, 404);
});

test('series info groups episodes by season with specials last', async () => {
  const shows = await apiJson('&action=get_series');
  assert.equal(shows[0].name, 'Breaking Bad');
  assert.equal(shows[0].series_id, 903747);
  const info = await apiJson(`&action=get_series_info&series_id=${shows[0].series_id}`);
  assert.deepEqual(info.seasons.map((s) => s.season_number), [1, 2, 0]);
  assert.deepEqual(info.episodes['1'].map((e) => e.title), ['Pilot', "Cat's in the Bag..."]);
  assert.equal(info.episodes['1'][0].info.duration_secs, 47 * 60);
});

test('episode play skips a stream for the wrong episode, and proxies when headers are needed', async () => {
  const shows = await apiJson('&action=get_series');
  const info = await apiJson(`&action=get_series_info&series_id=${shows[0].series_id}`);
  const pilot = info.episodes['1'][0];
  const res = await fetch(`${base}/series/matt/${encodeURIComponent('p@ss word')}/${pilot.id}.mp4`, {
    headers: { range: 'bytes=5-9' },
  });
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 5-9/20');
  assert.equal(await res.text(), '56789');
});

test('movie info comes from the fallback meta addon when AIOStreams has none', async () => {
  const info = await apiJson('&action=get_vod_info&vod_id=111161');
  assert.equal(info.info.name, 'The Shawshank Redemption');
  assert.equal(info.info.cast, 'Tim Robbins, Morgan Freeman');
  assert.equal(info.info.duration, '02:22:00');
  assert.equal(info.movie_data.stream_id, 111161);
  const missing = await api('&action=get_vod_info&vod_id=68646');
  assert.equal(missing.status, 404);
});

test('M3U playlist lists the movies with play links', async () => {
  const res = await fetch(`${base}/get.php?username=matt&password=${encodeURIComponent('p@ss word')}&type=m3u_plus`);
  const text = await res.text();
  assert.ok(text.startsWith('#EXTM3U'));
  assert.match(text, /group-title="Popular Movies",The Shawshank Redemption\nhttp:\/\/127\.0\.0\.1:\d+\/movie\/matt\/p%40ss%20word\/111161\.mp4/);
});

test('ids survive a restart', async () => {
  const movies = await apiJson('&action=get_vod_streams');
  const fightClub = movies.find((m) => m.name === 'Fight Club');
  // Let the debounced save run, then read the file with a new store.
  await new Promise((r) => setTimeout(r, 700));
  const store = new IdStore(dataDir);
  assert.equal(store.key('movie', fightClub.stream_id), 'tmdb:550');
  assert.equal(store.key('movie', 111161), 'tt0111161');
});
