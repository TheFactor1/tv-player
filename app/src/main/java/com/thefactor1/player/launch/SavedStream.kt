package com.thefactor1.player.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MimeTypes
import com.thefactor1.player.playback.PlayerActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * The stream a title was last played from, kept so the home screen can start it again without the
 * source app. Only web streams are kept: a content:// link was lent to this app for one launch and
 * won't open again. A debrid or addon link can still expire, which the player shows like any other
 * stream that won't load.
 */
data class SavedStream(
    val uri: String,
    val mimeHint: String?,
    val title: String?,
    val fileName: String?,
    val headers: Map<String, String>,
    val subtitles: List<Subtitle>,
) {
    data class Subtitle(val uri: String, val name: String?, val mimeType: String, val selected: Boolean)

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        putOpt("mime", mimeHint)
        putOpt("title", title)
        putOpt("file", fileName)
        put("headers", JSONObject(headers as Map<*, *>))
        put("subs", JSONArray(subtitles.map { sub ->
            JSONObject().put("uri", sub.uri).putOpt("name", sub.name).put("mime", sub.mimeType).put("selected", sub.selected)
        }))
    }

    /**
     * Opens the stream in the player as the source app did, with [imdbId] alongside so the title is
     * recognised straight away. The saved position is found by the stream itself.
     */
    fun intent(context: Context, imdbId: String?): Intent =
        Intent(Intent.ACTION_VIEW).setClass(context, PlayerActivity::class.java).apply {
            setDataAndType(Uri.parse(uri), mimeHint ?: "video/*")
            title?.let { putExtra(IntentContract.EXTRA_TITLE, it) }
            fileName?.let { putExtra(IntentContract.EXTRA_FILENAME, it) }
            imdbId?.let { putExtra(EXTRA_IMDB_ID, it) }
            if (headers.isNotEmpty()) {
                putExtra(IntentContract.EXTRA_HEADERS, headers.flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
            }
            if (subtitles.isNotEmpty()) {
                putExtra(IntentContract.EXTRA_SUBS, subtitles.map { Uri.parse(it.uri) }.toTypedArray())
                putExtra(IntentContract.EXTRA_SUBS_NAME, subtitles.map { it.name.orEmpty() }.toTypedArray())
                // The player reads the format back from a file name's extension.
                putExtra(IntentContract.EXTRA_SUBS_FILENAME, subtitles.map { "subtitle" + extension(it.mimeType) }.toTypedArray())
                putExtra(IntentContract.EXTRA_SUBS_ENABLE, subtitles.filter { it.selected }.map { Uri.parse(it.uri) }.toTypedArray())
            }
        }

    companion object {
        /** Not one the source apps send; it only has to be a text extra for TitleKeys to find the id. */
        const val EXTRA_IMDB_ID = "imdb_id"

        fun from(request: LaunchRequest): SavedStream? {
            if (!isWeb(request.uri)) return null
            return SavedStream(
                uri = request.uri.toString(),
                mimeHint = request.mimeHint,
                title = request.title,
                fileName = request.fileName,
                headers = request.headers,
                subtitles = request.subtitles.filter { isWeb(it.uri) }.map { Subtitle(it.uri.toString(), it.name, it.mimeType, it.selected) },
            )
        }

        fun from(json: JSONObject?): SavedStream? {
            json ?: return null
            val uri = json.optString("uri").ifEmpty { return null }
            val headers = LinkedHashMap<String, String>()
            json.optJSONObject("headers")?.let { h -> h.keys().forEach { key -> headers[key] = h.optString(key) } }
            val subs = json.optJSONArray("subs")
            val subtitles = (0 until (subs?.length() ?: 0)).mapNotNull { i ->
                val sub = subs?.optJSONObject(i) ?: return@mapNotNull null
                val subUri = sub.optString("uri").ifEmpty { return@mapNotNull null }
                Subtitle(subUri, sub.text("name"), sub.optString("mime").ifEmpty { MimeTypes.APPLICATION_SUBRIP }, sub.optBoolean("selected"))
            }
            return SavedStream(uri, json.text("mime"), json.text("title"), json.text("file"), headers, subtitles)
        }

        private fun isWeb(uri: Uri): Boolean = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)

        private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }

        private fun extension(mimeType: String): String = when (mimeType) {
            MimeTypes.TEXT_SSA -> ".ass"
            MimeTypes.TEXT_VTT -> ".vtt"
            MimeTypes.APPLICATION_TTML -> ".ttml"
            else -> ".srt"
        }
    }
}
