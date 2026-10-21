package com.thefactor1.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer

/** The viewer's subtitle timing correction for this video, shared with the text renderer so a change applies at once. */
class SubtitleDelay {
    /** Positive shows subtitles later, negative earlier. */
    @Volatile var offsetUs: Long = 0
}

/**
 * Renders subtitles as if playback were [SubtitleDelay.offsetUs] behind (or ahead), which moves
 * every line later (or earlier) without touching the file. Lines ahead of playback are already
 * buffered, so showing them early works as long as the buffer holds them.
 */
@OptIn(UnstableApi::class)
class DelayedTextRenderer(renderer: Renderer, private val delay: SubtitleDelay) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - delay.offsetUs, elapsedRealtimeUs)
    }
}
