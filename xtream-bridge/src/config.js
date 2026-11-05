// Reads settings from environment variables. See README.md for what each one does.

const CINEMETA = 'https://v3-cinemeta.strem.io/manifest.json';

/** Turns a manifest URL (or stremio:// link) into the addon's base URL. */
export function addonBase(url) {
  let u = String(url).trim();
  if (u.startsWith('stremio://')) u = 'https://' + u.slice('stremio://'.length);
  u = u.replace(/\/manifest\.json(\?.*)?$/i, '');
  return u.replace(/\/+$/, '');
}

function int(value, fallback, name) {
  if (value === undefined || value === '') return fallback;
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) throw new Error(`${name} must be a non-negative number, got "${value}"`);
  return n;
}

function list(value) {
  return String(value || '')
    .split(/[\s,]+/)
    .filter(Boolean);
}

export function loadConfig(env = process.env) {
  if (!env.AIOSTREAMS_URL) {
    throw new Error('AIOSTREAMS_URL is required: paste your AIOStreams manifest URL (…/manifest.json).');
  }
  if (!env.XTREAM_PASS) {
    // Without a password anyone who finds the server could stream through your debrid account.
    throw new Error('XTREAM_PASS is required: choose the password your IPTV app will log in with.');
  }

  const proxy = (env.PROXY_STREAMS || 'auto').toLowerCase();
  if (!['auto', 'always', 'never'].includes(proxy)) {
    throw new Error(`PROXY_STREAMS must be auto, always or never, got "${env.PROXY_STREAMS}"`);
  }

  return {
    aioBase: addonBase(env.AIOSTREAMS_URL),
    catalogAddons: list(env.CATALOG_ADDONS).map(addonBase),
    metaFallback: addonBase(env.META_ADDON || CINEMETA),
    cinemetaBase: addonBase(CINEMETA),
    username: env.XTREAM_USER || 'user',
    password: env.XTREAM_PASS,
    host: env.HOST || '0.0.0.0',
    port: int(env.PORT, 8080, 'PORT'),
    publicUrl: env.PUBLIC_URL ? env.PUBLIC_URL.replace(/\/+$/, '') : null,
    catalogPages: Math.max(1, int(env.CATALOG_PAGES, 2, 'CATALOG_PAGES')),
    catalogTtlMs: int(env.CATALOG_TTL_HOURS, 6, 'CATALOG_TTL_HOURS') * 3600_000,
    streamTtlMs: int(env.STREAM_CACHE_MINUTES, 30, 'STREAM_CACHE_MINUTES') * 60_000,
    requestTimeoutMs: int(env.REQUEST_TIMEOUT_SECONDS, 20, 'REQUEST_TIMEOUT_SECONDS') * 1000,
    streamTimeoutMs: int(env.STREAM_TIMEOUT_SECONDS, 60, 'STREAM_TIMEOUT_SECONDS') * 1000,
    proxyStreams: proxy,
    containerExt: (env.CONTAINER_EXT || 'mp4').replace(/^\./, ''),
    dataDir: env.DATA_DIR || './data',
  };
}
