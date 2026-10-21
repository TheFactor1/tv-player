package com.thefactor1.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** The viewer's lip-sync correction, shared with the audio sink so a change applies at once. */
class AudioDelay {
    /** Positive when sound arrives late (video waits for it), negative when it arrives early. */
    @Volatile var offsetUs: Long = 0
}

/**
 * Shifts the audio clock that video frames are timed against. Reporting an earlier position holds
 * frames back so they line up with sound that a receiver or projector delivers late. It works the
 * same for passthrough, where the audio itself can't be delayed.
 */
@OptIn(UnstableApi::class)
class AudioDelaySink(sink: AudioSink, private val delay: AudioDelay) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        return (position - delay.offsetUs).coerceAtLeast(0)
    }
}
