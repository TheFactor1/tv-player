// A minimal Stremio addon client: manifest, catalog, meta and stream requests.

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** Addon URLs carry the user's config (often secrets), so logs only ever show the host. */
export function redact(url) {
  try {
    return new URL(url).host;
  } catch {
    return 'addon';
  }
}

async function getJson(url, timeoutMs) {
  let res;
  try {
    res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs), headers: { accept: 'application/json' } });
  } catch (err) {
    throw new HttpError(502, `${redact(url)} unreachable: ${err.name === 'TimeoutError' ? 'timed out' : err.message}`);
  }
  if (!res.ok) throw new HttpError(res.status === 404 ? 404 : 502, `${redact(url)} answered ${res.status}`);
  try {
    return await res.json();
  } catch {
    throw new HttpError(502, `${redact(url)} sent invalid JSON`);
  }
}

export class AddonClient {
  constructor(base, { timeoutMs = 20_000, streamTimeoutMs = 60_000 } = {}) {
    this.base = base;
    this.timeoutMs = timeoutMs;
    this.streamTimeoutMs = streamTimeoutMs;
    this._manifest = null;
  }

  get label() {
    return redact(this.base);
  }

  async manifest() {
    if (!this._manifest) {
      this._manifest = getJson(`${this.base}/manifest.json`, this.timeoutMs).catch((err) => {
        this._manifest = null; // retry on the next call
        throw err;
      });
    }
    return this._manifest;
  }

  /** Whether the addon declares `resource` for this type and id, per the manifest rules. */
  async supports(resource, type, id) {
    let m;
    try {
      m = await this.manifest();
    } catch {
      return false;
    }
    for (const r of m.resources || []) {
      const name = typeof r === 'string' ? r : r?.name;
      if (name !== resource) continue;
      const types = (typeof r === 'object' && r.types) || m.types || [];
      const prefixes = (typeof r === 'object' && r.idPrefixes) || m.idPrefixes;
      if (!types.includes(type)) continue;
      if (id && Array.isArray(prefixes) && prefixes.length && !prefixes.some((p) => id.startsWith(p))) continue;
      return true;
    }
    return false;
  }

  /** Catalogs that can be listed without the user typing something (no required search or genre). */
  async browsableCatalogs() {
    const m = await this.manifest();
    return (m.catalogs || []).filter((c) => {
      if (!c || !c.id || !c.type) return false;
      if ((c.extra || []).some((e) => e.isRequired)) return false;
      if ((c.extraRequired || []).length) return false;
      return true;
    });
  }

  static pageable(catalog) {
    return (catalog.extra || []).some((e) => e.name === 'skip') || (catalog.extraSupported || []).includes('skip');
  }

  async catalog(type, id, skip = 0) {
    const extra = skip ? `/skip=${skip}` : '';
    const data = await getJson(`${this.base}/catalog/${type}/${encodeURIComponent(id)}${extra}.json`, this.timeoutMs);
    return Array.isArray(data?.metas) ? data.metas : [];
  }

  async meta(type, id) {
    const data = await getJson(`${this.base}/meta/${type}/${encodeURIComponent(id)}.json`, this.timeoutMs);
    return data?.meta || null;
  }

  async streams(type, id) {
    const data = await getJson(`${this.base}/stream/${type}/${encodeURIComponent(id)}.json`, this.streamTimeoutMs);
    return Array.isArray(data?.streams) ? data.streams : [];
  }
}
