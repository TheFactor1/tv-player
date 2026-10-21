package com.thefactor1.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Renderers with the two switches the fallback chain can flip: Dolby Vision decoded as its HDR10
 * base layer by a plain HEVC decoder, and audio always decoded to PCM instead of passed through.
 */
@OptIn(UnstableApi::class)
class PlayerRenderersFactory(
    context: Context,
    dolbyVisionAsHdr10: Boolean,
    private val pcmAudioOnly: Boolean,
    private val audioDelay: AudioDelay,
) : DefaultRenderersFactory(context) {

    init {
        // If a hardware decoder fails to start, try the next one before giving up.
        setEnableDecoderFallback(true)
        // Platform decoders and passthrough come first; FFmpeg only decodes audio the device can't
        // (TrueHD, DTS-HD and friends on TVs without them) to multichannel PCM.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        if (dolbyVisionAsHdr10) setMediaCodecSelector(HEVC_FOR_DOLBY_VISION)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val sink = if (!pcmAudioOnly) {
            super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        } else {
            // Reporting plain stereo PCM capabilities turns passthrough off; every track gets decoded.
            @Suppress("DEPRECATION")
            DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
        return AudioDelaySink(sink, audioDelay)
    }

    private companion object {
        val HEVC_FOR_DOLBY_VISION = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val mime = if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) MimeTypes.VIDEO_H265 else mimeType
            MediaCodecUtil.getDecoderInfos(mime, requiresSecure, requiresTunneling)
        }
    }
}
