// Xtream apps need numeric ids; Stremio uses strings like "tt0111161" or "tt0903747:1:1".
//
// IMDb ids map straight to their digits (tt0111161 -> 111161), so they stay the same forever
// without any storage. Everything else (other id schemes, episodes, categories) gets a number
// from a counter that is saved to disk, so favourites and watch history in the IPTV app survive
// a restart.

import fs from 'node:fs';
import path from 'node:path';

const IMDB = /^tt(\d{1,9})$/;
const COUNTER_START = 1_000_000_000; // above any IMDb number, below 2^31 for apps that use int

export class IdStore {
  constructor(dataDir) {
    this.file = dataDir ? path.join(dataDir, 'ids.json') : null;
    this.state = { next: {}, toNum: {}, toKey: {} };
    this._timer = null;
    if (this.file && fs.existsSync(this.file)) {
      try {
        this.state = { next: {}, toNum: {}, toKey: {}, ...JSON.parse(fs.readFileSync(this.file, 'utf8')) };
      } catch (err) {
        console.warn(`Could not read ${this.file}, starting with fresh ids: ${err.message}`);
      }
    }
  }

  /** Number for a Stremio id within a namespace ("movie", "series", "episode", "category"). */
  num(ns, key) {
    const imdb = ns !== 'episode' && ns !== 'category' ? IMDB.exec(key) : null;
    if (imdb) return Number(imdb[1]);
    const nsMap = (this.state.toNum[ns] ||= {});
    if (nsMap[key] !== undefined) return nsMap[key];
    const n = this.state.next[ns] || COUNTER_START;
    this.state.next[ns] = n + 1;
    nsMap[key] = n;
    (this.state.toKey[ns] ||= {})[n] = key;
    this._save();
    return n;
  }

  /** Stremio id for a number, or null if it was never handed out. */
  key(ns, num) {
    const n = Number(num);
    if (!Number.isInteger(n) || n <= 0) return null;
    if (ns !== 'episode' && ns !== 'category' && n < COUNTER_START) return `tt${String(n).padStart(7, '0')}`;
    return this.state.toKey[ns]?.[n] ?? null;
  }

  _save() {
    if (!this.file || this._timer) return;
    this._timer = setTimeout(() => this.flush(), 500);
    this._timer.unref?.();
  }

  flush() {
    clearTimeout(this._timer);
    this._timer = null;
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.state));
    fs.renameSync(tmp, this.file);
  }
}
