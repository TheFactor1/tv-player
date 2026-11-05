import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mismatch, parseEpisodeMarker, parseYear, parseEpisodeId, imdbOf, metaYear } from '../src/match.js';

test('episode markers', () => {
  const cases = {
    'Breaking.Bad.S01E02.1080p.BluRay.x264.mkv': { season: 1, episodes: [2] },
    'Show S02E10 2160p WEB-DL DDP5.1 HDR10 x265': { season: 2, episodes: [10] },
    'Show.S01E01E02.720p.mkv': { season: 1, episodes: [1, 2] },
    'Show.S01E01-E03.720p.mkv': { season: 1, episodes: [1, 2, 3] },
    'Show.S01E04-05.mkv': { season: 1, episodes: [4, 5] },
    'Show.S01E05-720p.mkv': { season: 1, episodes: [5] },
    'Show.s3e7.mkv': { season: 3, episodes: [7] },
    'Show 1x02 HDTV': { season: 1, episodes: [2] },
    'Show.S03.COMPLETE.1080p': { season: 3, episodes: null },
    'Show Season 4 1080p': { season: 4, episodes: null },
    'Movie.2019.1920x1080.mkv': null,
    'Movie.2019.1080p.DTS5.1.x265.mkv': null,
  };
  for (const [name, expected] of Object.entries(cases)) assert.deepEqual(parseEpisodeMarker(name), expected, name);
});

test('release years ignore numbers that are part of the title', () => {
  assert.equal(parseYear('Blade.Runner.2049.2017.2160p.mkv', 'Blade Runner 2049'), 2017);
  assert.equal(parseYear('2001.A.Space.Odyssey.1968.1080p.mkv', '2001: A Space Odyssey'), 1968);
  assert.equal(parseYear('1917.2019.2160p.mkv', '1917'), 2019);
  assert.equal(parseYear('1917.2160p.mkv', '1917'), null);
  assert.equal(parseYear('Dune.Part.Two.2160p.mkv', 'Dune: Part Two'), null);
});

test('wrong episode or season is rejected, unknown is kept', () => {
  const want = { type: 'series', season: 1, episode: 1 };
  const s = (filename, extra = {}) => ({ url: 'http://x', behaviorHints: { filename }, ...extra });
  assert.equal(mismatch(s('Show.S01E01.mkv'), want), null);
  assert.equal(mismatch(s('Show.S01E01E02.mkv'), want), null);
  assert.match(mismatch(s('Show.S01E02.mkv'), want), /episode 2/);
  assert.match(mismatch(s('Show.S02E01.mkv'), want), /season 2/);
  assert.match(mismatch(s('Show.S02.Complete'), want), /season 2/);
  assert.equal(mismatch(s('Show.S01.Complete'), want), null);
  assert.equal(mismatch(s(''), want), null);
  // No file name: the addon's label is used.
  assert.match(mismatch({ url: 'http://x', description: '📁 Show.S01E03.1080p.mkv' }, want), /episode 3/);
});

test('a film from a different year is rejected', () => {
  const want = { type: 'movie', title: 'Dune', year: 2021 };
  const s = (filename) => ({ url: 'http://x', behaviorHints: { filename } });
  assert.equal(mismatch(s('Dune.2021.2160p.mkv'), want), null);
  assert.equal(mismatch(s('Dune.2020.2160p.mkv'), want), null, 'one year either side is allowed');
  assert.match(mismatch(s('Dune.1984.1080p.mkv'), want), /year 1984/);
  assert.equal(mismatch(s('Dune.2160p.mkv'), want), null);
  assert.equal(mismatch({ url: 'http://x', description: 'Added 1999' }, want), null, 'labels are not used for years');
});

test('id helpers', () => {
  assert.deepEqual(parseEpisodeId('tt0903747:1:2'), { imdb: 'tt0903747', season: 1, episode: 2 });
  assert.equal(parseEpisodeId('kitsu:1:5'), null);
  assert.equal(imdbOf({ id: 'tmdb:550', imdb_id: 'tt0137523' }), 'tt0137523');
  assert.equal(imdbOf({ id: 'tmdb:550' }), null);
  assert.equal(metaYear({ releaseInfo: '2008–2013' }), 2008);
});
