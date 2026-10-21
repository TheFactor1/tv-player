# Player

A premium external video player for Android TV. Stremio, Nuvio and similar apps hand it a stream;
it plays it at the best quality the TV supports (Dolby Vision, HDR10+, Atmos passthrough) and hands
the watched position back.

Distributed as a sideloaded APK.

## Status

Step 1 of 4: the app registers as an external player, accepts the stream, title, start position,
HTTP headers and subtitles from the source app, plays it with Media3, and returns the position on exit.

| Step | Scope |
| --- | --- |
| 1 | Project skeleton and handoff from Stremio/Nuvio (this) |
| 2 | Dolby Vision / HDR10+ / Atmos / DTS passthrough, FFmpeg audio fallback, frame-rate matching, stats overlay |
| 3 | Cinema-style remote-first controls, track panels, subtitle styling |
| 4 | Buffer tuning for 4K remuxes, device testing, signed release |

## Install

Every push builds APKs in GitHub Actions. Open the latest **Build** run, download `player-apks`, and
sideload `app-release.apk` (for example with `adb install` or the Downloader app).

Then in Stremio: Settings → Player → External player, and pick **Player**. In Nuvio, choose it as the
external player in its playback settings.

## Intent contract

Follows the MX Player / Just Player convention.

| Extra | Type | Meaning |
| --- | --- | --- |
| data URI | `Uri` | The stream (`http`, `https`, `file`, `content`) |
| `title` | String | Shown in the player |
| `position` | int or long, ms | Start position |
| `headers` | String[] | `key, value, key, value` sent with every request |
| `android.media.intent.extra.HTTP_HEADERS` | Bundle | Alternative header form |
| `subs` / `subs.name` / `subs.filename` / `subs.enable` | Uri[] / String[] / String[] / Uri[] | External subtitles |

On exit the result intent (`com.mxtech.intent.result.VIEW`) carries `position` (only if stopped early),
`duration` and `end_by` (`user` or `playback_completion`).

## Build locally

Needs JDK 17 and the Android SDK (compile SDK 36).

```
./gradlew testDebugUnitTest assembleRelease
```
