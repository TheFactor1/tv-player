package com.thefactor1.player.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.hub.HubResume
import com.thefactor1.player.hub.NextEpisode
import com.thefactor1.player.hub.PlayInfo
import com.thefactor1.player.launch.IntentContract
import com.thefactor1.player.playback.PlayerActivity
import com.thefactor1.player.resume.TitleKeys

/** Starting the player from Home the way another app would, so the player itself needs nothing new. */
object HomePlay {
    /** A text extra carrying Stremio's video id ("tt0944947:1:2"), which the player's resume keys already recognise. */
    private const val EXTRA_VIDEO_ID = "video_id"

    /** The episode Play means: the hub's next one, or the first for a show nobody has started. */
    fun episodeFor(item: HubItem): NextEpisode? =
        if (!item.isSeries) null else item.nextEpisode ?: NextEpisode(1, 1, null)

    /** The key the player files this title's position under on the box (see TitleKeys), cut included. */
    fun localKey(item: HubItem, episode: NextEpisode?, cut: String?): String {
        if (episode != null) return "imdb:${item.id}:s${episode.season}e${episode.episode}"
        val tag = cut?.let(TitleKeys::normalize)?.ifEmpty { null }
        return if (tag == null) "imdb:${item.id}" else "imdb:${item.id}:cut=$tag"
    }

    /**
     * The hub's position when it is newer than what this box saved, else null so the player
     * resumes from its own memory as usual.
     */
    fun startFrom(shared: HubResume?, localSavedAt: Long?): Long? =
        shared?.takeIf { it.positionMs > 0 && (localSavedAt == null || it.updatedAt > localSavedAt) }?.positionMs

    fun intent(context: Context, item: HubItem, episode: NextEpisode?, info: PlayInfo, startMs: Long?): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(info.url), context, PlayerActivity::class.java).apply {
            // "Crystal Lake S01E03" is how the player's title parsing finds the episode.
            val title = if (episode == null) item.title else "%s S%02dE%02d".format(item.title, episode.season, episode.episode)
            putExtra(IntentContract.EXTRA_TITLE, title)
            putExtra(EXTRA_VIDEO_ID, if (episode == null) item.id else "${item.id}:${episode.season}:${episode.episode}")
            if (info.headers.isNotEmpty()) {
                putExtra(IntentContract.EXTRA_HEADERS, info.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            }
            startMs?.let { putExtra(IntentContract.EXTRA_POSITION, it) }
            info.badges.cut?.let { putExtra(IntentContract.EXTRA_CUT, it) }
        }
}
