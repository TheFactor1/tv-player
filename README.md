# Player

A premium external video player for Android TV. Stremio, Nuvio and similar apps hand it a stream;
it plays it at the best quality the TV supports (Dolby Vision, HDR10+, Atmos passthrough) and hands
the watched position back.

Distributed as a sideloaded APK.

## Status

Step 3 of 4. The app registers as an external player, accepts the stream, title, start position,
HTTP headers and subtitles from the source app, and returns the position on exit. It bitstreams Dolby
and DTS audio when the TV or receiver supports it, decodes the rest with FFmpeg, switches the display
to the video's frame rate before the first frame, and has a stats overlay (long-press OK, or Info).
The Cinema controls are remote-first: with controls hidden, OK pauses, Left/Right skip 10 s back /
30 s forward, Up shows the controls, Down opens subtitles, and Back closes the controls or panel, or
returns to the source app when nothing is showing. Side panels pick
audio and subtitle tracks, subtitle size, picture mode and frame-rate matching.

When playback fails it falls back before giving up: network drops reconnect up to three times
(1, 2, 4 s apart) from the same position; Dolby Vision the device can't decode plays as its HDR10
base layer; audio output that fails switches from passthrough to decoded PCM. If nothing works, the
error card offers Try again and Open in another player (with the same headers, subtitles and position).

| Step | Scope |
| --- | --- |
| 1 | Project skeleton and handoff from Stremio/Nuvio |
| 2 | Dolby Vision / HDR10+ / Atmos / DTS passthrough, FFmpeg audio fallback, frame-rate matching, stats overlay |
| 3 | Cinema-style remote-first controls, track panels, subtitle styling (this) |
| 4 | Buffer tuning for 4K remuxes, device testing, signed release |

## Install

Every push builds APKs in GitHub Actions. Open the latest **Build** run, download `player-release`,
and sideload the `player-<version>.apk` inside (for example with `adb install`).

Release builds are signed with the key in the repository secrets `SIGNING_KEYSTORE_BASE64`,
`SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Keep a backup of that
key: Android only installs updates signed with the same key.

Then in Stremio: Settings → Player → External player, and pick **Player**. In Nuvio, choose it as the
external player in its playback settings.

## Intent contract

Follows the MX Player / Just Player convention.

| Extra | Type | Meaning |
| --- | --- | --- |
| data URI | `Uri` | The stream (`http`, `https`, `file`, `content`) |
| `title` | String | Shown in the player |
| `position` | int, long or numeric string, ms | Start position (`startfrom`, Vimu's key, also works) |
| `from_start` | boolean | Ignore any saved position (VLC's switch) |
| `duration` / `extra_duration` | int or long, ms | Length, if the app already knows it |
| `filename` | String | Release file name, used to recognise the title |
| `headers` | String[] | `key, value, key, value` sent with every request |
| `android.media.intent.extra.HTTP_HEADERS` | Bundle | Alternative header form |
| `subs` / `subs.name` / `subs.filename` / `subs.enable` | Uri[] / String[] / String[] / Uri[] | External subtitles |

On exit the result intent (`com.mxtech.intent.result.VIEW`, with the stream as its data) carries
both result styles apps copy: MX Player's Int `position` and `duration` with `end_by` (`user` or
`playback_completion`), and VLC's Long `extra_position` and `extra_duration`. A finished video reports
its full length as the position, because some apps drop a result without one.

The player is `singleTop`, not `singleTask`: Android cancels a `startActivityForResult` into a
`singleTask` activity straight away, and the source app would never see the position.

## Resume

The source app's position always wins. When it sends none, the player uses its own memory of the
title, kept on the device and saved every 10 s and on exit. A position is filed under several keys,
strongest first: the stream URL without its token, an IMDb id (with season and episode) if one
appears in the extras or URL, the title the app sent, and the release name in the file. So a
different addon, debrid link or release of the same episode still resumes. A remembered position is
dropped if the new stream's length differs by more than 5% (at least a minute), so a different cut
or episode starts from the top. Positions under 30 s, and anything within the last 8% or final
minute, are forgotten.

After a resume a note shows "Resumed at 42:10" for a few seconds; OK starts over, any other key
dismisses it.

## Build locally

Needs JDK 17 and the Android SDK (compile SDK 36).

```
./gradlew testDebugUnitTest assembleRelease
```
