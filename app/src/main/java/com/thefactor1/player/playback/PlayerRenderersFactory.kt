package com.thefactor1.player.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * Renderers with the two switches the fallback chain can flip: Dolby Vision decoded as its HDR10
 * base layer by a plain HEVC decoder, and audio always decoded to PCM instead of passed through.
 * Decoded audio goes through night mode and dialogue boost; subtitles can be shifted in time.
 */
@OptIn(UnstableApi::class)
class PlayerRenderersFactory(
    context: Context,
    dolbyVisionAsHdr10: Boolean,
    private val pcmAudioOnly: Boolean,
    private val audioDelay: AudioDelay,
    private val enhancement: AudioEnhancement,
    private val subtitleDelay: SubtitleDelay,
    private val decodeSwitch: DecodeSwitch,
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
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            // Float output would skip the processors, so night mode always gets 16-bit PCM.
            .setAudioProcessors(arrayOf(AudioEnhancerProcessor(enhancement)))
        if (pcmAudioOnly) {
            // Reporting plain stereo PCM capabilities turns passthrough off; every track gets decoded.
            @Suppress("DEPRECATION")
            builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        }
        val sink = builder.build()
        return AudioDelaySink(DecodeSwitchSink(sink, decodeSwitch), audioDelay)
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(DelayedTextRenderer(TextRenderer(output, outputLooper), subtitleDelay))
    }

    private companion object {
        val HEVC_FOR_DOLBY_VISION = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val mime = if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) MimeTypes.VIDEO_H265 else mimeType
            MediaCodecUtil.getDecoderInfos(mime, requiresSecure, requiresTunneling)
        }
    }
}
