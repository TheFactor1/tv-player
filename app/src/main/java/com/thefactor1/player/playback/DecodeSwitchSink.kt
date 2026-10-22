package com.thefactor1.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** Whether Dolby and DTS must be decoded here instead of passed through, shared with the audio sink. */
class DecodeSwitch {
    @Volatile var decode = false
}

/**
 * Turns passthrough off by telling the audio renderers the sink can't take encoded audio: from the
 * start when [alwaysDecode] is set, or on demand. The renderers ask again whenever the audio track
 * is re-enabled, so flipping [DecodeSwitch.decode] and re-selecting audio moves to a decoder in
 * place, with the video still playing.
 *
 * This is the only way to turn passthrough off: DefaultAudioSink ignores audio capabilities set on
 * its builder when it is given a context, and reads the device's own instead.
 */
@OptIn(UnstableApi::class)
class DecodeSwitchSink(
    sink: AudioSink,
    private val switch: DecodeSwitch,
    private val alwaysDecode: Boolean,
) : ForwardingAudioSink(sink) {

    override fun supportsFormat(format: Format): Boolean =
        if (isBlocked(format)) false else super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (isBlocked(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    private fun isBlocked(format: Format) = (alwaysDecode || switch.decode) && format.sampleMimeType != MimeTypes.AUDIO_RAW
}
