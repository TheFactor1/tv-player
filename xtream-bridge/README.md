# AIOStreams Xtream bridge

Lets IPTV apps that take an Xtream Codes login (TiviMate, IPTV Smarters, iMPlayer, XCIPTV and
others) browse and play your [AIOStreams](https://github.com/Viren070/AIOStreams) setup.

- **Movies** and **Series** sections come from the catalogs in your AIOStreams setup, plus any extra
  catalog addons you list. If none are configured, it uses Cinemeta's lists.
- **Details** (plot, cast, seasons, episodes) come from AIOStreams if it serves metadata, otherwise
  from Cinemeta.
- **Playback**: when you press play, the bridge asks AIOStreams for streams, takes the first one it
  ranks that has a direct link, and redirects the app to it. Links are worked out at play time, so
  expiring debrid links don't go stale. Each result is reused for 30 minutes so seeking and resuming
  don't start a new search.
- **Live TV** is empty, because AIOStreams doesn't serve channels.

It is separate from the Android player in this repository. It needs Node 20 or later and has no
dependencies.

## Run it

### Docker (recommended)

```sh
cd xtream-bridge
cp .env.example .env        # then edit .env
docker compose up -d --build
```

### Node

```sh
cd xtream-bridge
AIOSTREAMS_URL='https://…/manifest.json' XTREAM_USER=me XTREAM_PASS='a real password' npm start
```

## Add it to your IPTV app

Choose **Xtream Codes** (sometimes called "Xtream login" or "XC API") and enter:

| Field | Value |
| --- | --- |
| Server / URL | `http://<the bridge's address>:8080` |
| Username | your `XTREAM_USER` |
| Password | your `XTREAM_PASS` |

For an app that only takes a playlist link, there is also an M3U of the movies (series can't be
listed in a flat playlist):

```
http://<address>:8080/get.php?username=<user>&password=<pass>&type=m3u_plus
```

## Settings

| Variable | Default | What it does |
| --- | --- | --- |
| `AIOSTREAMS_URL` | *required* | Your AIOStreams manifest URL. `stremio://` links work too. |
| `XTREAM_PASS` | *required* | Password the IPTV app logs in with. |
| `XTREAM_USER` | `user` | Username the IPTV app logs in with. |
| `PORT` / `HOST` | `8080` / `0.0.0.0` | Where the server listens. |
| `PUBLIC_URL` | from the request | The address apps reach the bridge at, e.g. `https://bridge.example.com`. Set it when behind a reverse proxy. |
| `CATALOG_ADDONS` | none | Extra catalog addon manifest URLs, comma separated (Trakt, TMDB, MDBList and so on). |
| `META_ADDON` | Cinemeta | Addon used for details when AIOStreams has none. |
| `CATALOG_PAGES` | `2` | Pages fetched per catalog (usually 50–100 titles a page). |
| `CATALOG_TTL_HOURS` | `6` | How long catalog lists and details are cached. |
| `STREAM_CACHE_MINUTES` | `30` | How long a resolved stream link is reused. |
| `STREAM_TIMEOUT_SECONDS` | `60` | How long to wait for AIOStreams to search. |
| `PROXY_STREAMS` | `auto` | `auto` sends video through the bridge only when a stream needs special headers; `always` always does; `never` always redirects. |
| `CONTAINER_EXT` | `mp4` | File extension shown to the app. The real format is whatever the stream is. |
| `DATA_DIR` | `./data` | Where id numbers are saved, so favourites and watch history survive restarts. |
| `LOG_REQUESTS` | `true` | Set to `false` to stop logging each request. |

## Security

Anyone with the server address, username and password can stream through your debrid account, so:

- Use a real password. The bridge won't start without one.
- Run it on your home network if you can. If you put it on the internet, put it behind HTTPS
  (Caddy, Nginx, Cloudflare Tunnel), because Xtream apps send the password in every URL.
- Logs never show your password or the AIOStreams URL, which contains your config.

## How it makes sure the right title plays

- **One IMDb id per title.** Catalog items that carry an IMDb id (most TMDB and Trakt catalogs do)
  are listed under it, so the same film from two catalogs shows once. Streams are always requested
  by IMDb id when one is known, because that's what Torrentio, debrid addons and AIOStreams' own
  matching use.
- **Episodes use `tt…:season:episode`**, the numbering stream addons use. If the details came from
  an addon with TMDB or TVDB ids, they're converted using the show's IMDb id.
- **Each stream is checked before it plays.** AIOStreams already filters by title, season and
  episode. On top of that, the bridge reads each stream's file name and skips any that clearly
  name a different episode or season (`S01E02` when you asked for `S01E01`, or an `S02` pack), or,
  for films, a release year more than one year off (the 1984 *Dune* when you asked for 2021). The
  next stream in AIOStreams' order is used instead, and skipped streams are logged.
- A stream whose name gives no season, episode or year is kept, because most correct streams
  look like that too. So the check catches clear mix-ups, not every possible one. It can't catch
  a file that is named correctly but has the wrong contents.

## How ids work

Xtream apps need numbers. IMDb ids map to their digits (`tt0111161` is `111161`). Other ids,
episodes and categories get numbers that are saved in `DATA_DIR/ids.json`. Deleting that file
resets them, which can break favourites in the app.

## Limits

- The bridge plays AIOStreams' top-ranked direct link that passes the check above. Torrent-only results (no debrid) and
  YouTube or external links are skipped, and there's no way to choose another source from the IPTV
  app. Change the sort order in AIOStreams to change what gets picked.
- If the chosen link fails in the app, the bridge keeps handing out the same link until
  `STREAM_CACHE_MINUTES` runs out (it can only notice failures when it is proxying the video).
- The first press of play takes as long as an AIOStreams search, often 5–15 seconds.

## Tests

```sh
npm test
```

The tests run the bridge against a fake addon and a fake video host.
