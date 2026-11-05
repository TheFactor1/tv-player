// The HTTP side: the Xtream Codes endpoints IPTV apps call, plus the play URLs.

import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { Readable } from 'node:stream';
import { HttpError, redact } from './addon.js';

const PASSTHROUGH_HEADERS = ['content-type', 'content-length', 'content-range', 'accept-ranges', 'last-modified', 'etag'];

function same(a, b) {
  const x = Buffer.from(String(a ?? ''));
  const y = Buffer.from(String(b ?? ''));
  return x.length === y.length && timingSafeEqual(x, y);
}

function send(res, status, body, type = 'application/json; charset=utf-8') {
  const data = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store' });
  res.end(data);
}

async function readForm(req) {
  if (req.method !== 'POST') return {};
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 64 * 1024) throw new HttpError(413, 'Request body too large');
    chunks.push(chunk);
  }
  return Object.fromEntries(new URLSearchParams(Buffer.concat(chunks).toString('utf8')));
}

function m3uEscape(s) {
  return String(s || '').replace(/[\r\n"]/g, ' ');
}

export function createServer(config, library) {
  const authorised = (u, p) => same(u, config.username) && same(p, config.password);

  function baseUrl(req) {
    if (config.publicUrl) return config.publicUrl;
    const proto = req.headers['x-forwarded-proto']?.split(',')[0] || 'http';
    return `${proto}://${req.headers.host || `localhost:${config.port}`}`;
  }

  function accountInfo(req) {
    const base = new URL(baseUrl(req));
    const https = base.protocol === 'https:';
    const port = base.port || (https ? '443' : '80');
    const now = Math.floor(Date.now() / 1000);
    return {
      user_info: {
        username: config.username,
        password: config.password,
        message: 'AIOStreams bridge',
        auth: 1,
        status: 'Active',
        exp_date: '4102444800',
        is_trial: '0',
        active_cons: '0',
        created_at: String(now),
        max_connections: '1',
        allowed_output_formats: ['m3u8', 'ts'],
      },
      server_info: {
        url: base.hostname,
        port: https ? '80' : port,
        https_port: https ? port : '443',
        server_protocol: https ? 'https' : 'http',
        rtmp_port: '0',
        timezone: 'UTC',
        timestamp_now: now,
        time_now: new Date(now * 1000).toISOString().replace('T', ' ').slice(0, 19),
        process: true,
      },
    };
  }

  async function playerApi(req, res, params) {
    if (!authorised(params.username, params.password)) {
      return send(res, params.action ? 401 : 200, params.action ? [] : { user_info: { auth: 0 } });
    }
    const category = params.category_id || null;
    switch (params.action || '') {
      case '':
        return send(res, 200, accountInfo(req));
      case 'get_live_categories':
      case 'get_live_streams':
        return send(res, 200, []);
      case 'get_vod_categories':
        return send(res, 200, await library.categories('movie'));
      case 'get_vod_streams':
        return send(res, 200, await library.vodStreams(category));
      case 'get_vod_info':
        return send(res, 200, await library.vodInfo(params.vod_id));
      case 'get_series_categories':
        return send(res, 200, await library.categories('series'));
      case 'get_series':
        return send(res, 200, await library.series(category));
      case 'get_series_info':
        return send(res, 200, await library.seriesInfo(params.series_id));
      case 'get_short_epg':
      case 'get_simple_data_table':
        return send(res, 200, { epg_listings: [] });
      default:
        return send(res, 400, { error: `Unsupported action ${params.action}` });
    }
  }

  /** M3U of the movies, for players that only take a playlist link. Series need an Xtream login. */
  async function playlist(req, res, params) {
    if (!authorised(params.username, params.password)) return send(res, 401, 'Wrong username or password\n', 'text/plain');
    const base = baseUrl(req);
    const [categories, movies] = await Promise.all([library.categories('movie'), library.vodStreams(null)]);
    const names = Object.fromEntries(categories.map((c) => [c.category_id, c.category_name]));
    const u = encodeURIComponent(config.username);
    const p = encodeURIComponent(config.password);
    const lines = ['#EXTM3U'];
    for (const m of movies) {
      lines.push(
        `#EXTINF:-1 tvg-id="" tvg-name="${m3uEscape(m.name)}" tvg-logo="${m3uEscape(m.stream_icon)}" group-title="${m3uEscape(names[m.category_id])}",${m3uEscape(m.name)}`,
        `${base}/movie/${u}/${p}/${m.stream_id}.${m.container_extension}`,
      );
    }
    send(res, 200, lines.join('\n') + '\n', 'audio/x-mpegurl; charset=utf-8');
  }

  async function play(req, res, kind, num) {
    const stream = await library.resolve(kind, num);
    const proxy = config.proxyStreams === 'always' || (config.proxyStreams === 'auto' && stream.headers);
    if (!proxy) {
      res.writeHead(302, { location: stream.url, 'cache-control': 'no-store' });
      return res.end();
    }

    const abort = new AbortController();
    res.on('close', () => abort.abort());
    const headers = { ...(stream.headers || {}) };
    if (req.headers.range) headers.range = req.headers.range;
    let upstream;
    try {
      upstream = await fetch(stream.url, { method: req.method === 'HEAD' ? 'HEAD' : 'GET', headers, signal: abort.signal });
    } catch (err) {
      library.forget(kind, stream.id);
      throw new HttpError(502, `Stream host ${redact(stream.url)} unreachable: ${err.message}`);
    }
    if (upstream.status >= 400) {
      library.forget(kind, stream.id); // a fresh lookup may find a working link next time
      await upstream.body?.cancel();
      throw new HttpError(502, `Stream host ${redact(stream.url)} answered ${upstream.status}`);
    }
    const out = {};
    for (const h of PASSTHROUGH_HEADERS) {
      const v = upstream.headers.get(h);
      if (v) out[h] = v;
    }
    res.writeHead(upstream.status, out);
    if (!upstream.body || req.method === 'HEAD') return res.end();
    Readable.fromWeb(upstream.body)
      .on('error', () => res.destroy())
      .pipe(res);
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://bridge');
    const route = url.pathname.replace(/\/+$/, '') || '/';

    if (route === '/health') return send(res, 200, { ok: true });
    if (route === '/') {
      return send(res, 200, 'AIOStreams Xtream bridge is running. Add this address to your IPTV app as an Xtream Codes server.\n', 'text/plain; charset=utf-8');
    }

    if (route === '/player_api.php' || route === '/get.php' || route === '/xmltv.php' || route === '/panel_api.php') {
      const params = { ...Object.fromEntries(url.searchParams), ...(await readForm(req)) };
      if (route === '/get.php') return playlist(req, res, params);
      if (route === '/xmltv.php') {
        if (!authorised(params.username, params.password)) return send(res, 401, '', 'text/plain');
        return send(res, 200, '<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="aiostreams-xtream-bridge"></tv>\n', 'application/xml');
      }
      return playerApi(req, res, params);
    }

    // /movie/<user>/<pass>/<id>.<ext>, /series/<user>/<pass>/<id>.<ext>
    const m = /^\/(movie|series|live)\/([^/]+)\/([^/]+)\/(\d+)(?:\.[A-Za-z0-9]+)?$/.exec(url.pathname);
    if (m) {
      const [, kind, u, p, num] = m;
      if (!authorised(decodeURIComponent(u), decodeURIComponent(p))) return send(res, 401, 'Wrong username or password\n', 'text/plain');
      if (kind === 'live') return send(res, 404, 'No live channels\n', 'text/plain');
      return play(req, res, kind, num);
    }

    send(res, 404, { error: 'Not found' });
  }

  return http.createServer((req, res) => {
    const started = Date.now();
    const path = req.url.replace(/(\/(?:movie|series|live)\/[^/]+\/)[^/]+/, '$1***').replace(/password=[^&]*/, 'password=***');
    if (process.env.LOG_REQUESTS !== 'false') {
      res.once('finish', () => console.log(`${req.method} ${path} ${res.statusCode} ${Date.now() - started}ms`));
    }
    handle(req, res).catch((err) => {
      const status = err instanceof HttpError ? err.status : 500;
      if (status >= 500) console.error(`${req.method} ${path}: ${err.message}`);
      if (!res.headersSent) send(res, status, { error: err.message });
      else res.destroy();
    });
  });
}
