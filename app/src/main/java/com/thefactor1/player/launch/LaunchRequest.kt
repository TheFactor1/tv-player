package com.thefactor1.player.launch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.core.content.IntentCompat

data class SubtitleTrack(
    val uri: Uri,
    val name: String?,
    val mimeType: String,
    val selected: Boolean,
)

/** Everything the source app told us about the stream it wants played. */
data class LaunchRequest(
    val uri: Uri,
    val mimeHint: String?,
    val title: String?,
    /** Where the source app wants playback to start; 0 when it sent nothing. */
    val startPositionMs: Long,
    /** The source app asked to ignore any saved position. */
    val fromStart: Boolean,
    /** The length the source app already knows, or 0. */
    val durationHintMs: Long,
    /** The file name the source app sent, if any, which names the release better than the URL. */
    val fileName: String?,
    /** Every text extra, for spotting an IMDb id the source app tucked in somewhere. */
    val textExtras: List<String>,
    val headers: Map<String, String>,
    val subtitles: List<SubtitleTrack>,
) {
    companion object {
        fun from(intent: Intent): LaunchRequest? {
            val uri = intent.data ?: return null
            val extras = intent.extras ?: Bundle.EMPTY

            // MX/Just Player's key first, then Vimu's and VLC's.
            val position = extras.time(IntentContract.EXTRA_POSITION).takeIf { it > 0 }
                ?: extras.time(IntentContract.EXTRA_START_FROM)
            val duration = extras.time(IntentContract.EXTRA_DURATION).takeIf { it > 0 }
                ?: extras.time(IntentContract.EXTRA_VLC_DURATION)
            @Suppress("DEPRECATION")
            val textExtras = extras.keySet().mapNotNull { extras.get(it) as? String }

            val headers = LinkedHashMap<String, String>()
            headers += IntentContract.headersFromArray(intent.getStringArrayExtra(IntentContract.EXTRA_HEADERS))
            extras.getBundle(IntentContract.EXTRA_HTTP_HEADERS_BUNDLE)?.let { bundle ->
                for (key in bundle.keySet()) bundle.getString(key)?.let { headers[key] = it }
            }

            return LaunchRequest(
                uri = uri,
                mimeHint = IntentContract.mediaMimeHint(intent.type),
                title = intent.getStringExtra(IntentContract.EXTRA_TITLE) ?: uri.lastPathSegment,
                startPositionMs = position,
                fromStart = extras.getBoolean(IntentContract.EXTRA_FROM_START, false),
                durationHintMs = duration,
                fileName = intent.getStringExtra(IntentContract.EXTRA_FILENAME),
                textExtras = textExtras,
                headers = headers,
                subtitles = subtitlesFrom(intent),
            )
        }

        @Suppress("DEPRECATION")
        private fun Bundle.time(key: String): Long = IntentContract.positionFrom(get(key))

        private fun subtitlesFrom(intent: Intent): List<SubtitleTrack> {
            val uris = uriArray(intent, IntentContract.EXTRA_SUBS)
            if (uris.isEmpty()) return emptyList()
            val names = intent.getStringArrayExtra(IntentContract.EXTRA_SUBS_NAME)
            val fileNames = intent.getStringArrayExtra(IntentContract.EXTRA_SUBS_FILENAME)
            val enabled = uriArray(intent, IntentContract.EXTRA_SUBS_ENABLE).toSet()
            return uris.mapIndexed { i, uri ->
                val name = names?.getOrNull(i)
                SubtitleTrack(
                    uri = uri,
                    name = name,
                    mimeType = IntentContract.subtitleMimeType(fileNames?.getOrNull(i) ?: uri.path ?: name),
                    selected = uri in enabled,
                )
            }
        }

        private fun uriArray(intent: Intent, key: String): List<Uri> =
            IntentCompat.getParcelableArrayExtra(intent, key, Uri::class.java)
                ?.mapNotNull { it as? Uri }
                .orEmpty()
    }
}
