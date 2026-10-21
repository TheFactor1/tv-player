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
    val startPositionMs: Long,
    val headers: Map<String, String>,
    val subtitles: List<SubtitleTrack>,
) {
    companion object {
        fun from(intent: Intent): LaunchRequest? {
            val uri = intent.data ?: return null
            val extras = intent.extras ?: Bundle.EMPTY

            @Suppress("DEPRECATION")
            val position = IntentContract.positionFrom(extras.get(IntentContract.EXTRA_POSITION))

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
                headers = headers,
                subtitles = subtitlesFrom(intent),
            )
        }

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
