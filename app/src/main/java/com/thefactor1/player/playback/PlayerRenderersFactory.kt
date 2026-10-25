package com.thefactor1.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener

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
        // Platform decoders and passthrough come first; FFmpeg decodes audio the device can't (TrueHD
        // on TVs without it, and DTS always) to multichannel PCM.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setMediaCodecSelector(decoderSelector(dolbyVisionAsHdr10))
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            // Float output would skip the processors, so night mode always gets 16-bit PCM.
            .setAudioProcessors(arrayOf(AudioEnhancerProcessor(enhancement)))
            .build()
        return AudioDelaySink(DecodeSwitchSink(sink, decodeSwitch, alwaysDecode = pcmAudioOnly), audioDelay)
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

    /**
     * The usual video renderer, except that once video effects are set (PlayerActivity does, for HDR on an SDR screen) its frames
     * go through OpenGL and come out tone mapped to SDR. Without effects nothing changes: frames
     * go straight from the decoder to the screen.
     */
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
            allowedVideoJoiningTimeMs, built,
        )
        val index = built.indexOfFirst { it is MediaCodecVideoRenderer }
        if (index >= 0) {
            built[index] = ToneMappingVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(getCodecAdapterFactory())
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
            )
        }
        out.addAll(built)
    }

    private class ToneMappingVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
        override fun createPlaybackVideoGraphWrapper(
            context: Context,
            videoFrameReleaseControl: VideoFrameReleaseControl,
        ): PlaybackVideoGraphWrapper =
            super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl).apply { setRequestOpenGlToneMapping(true) }
    }

    private companion object {
        /**
         * DTS is never decoded by the device: FFmpeg does it instead. TV boxes without a DTS licence
         * (the onn 4K Plus among them) still list a DTS decoder, and it plays silence. Passthrough to
         * a receiver that takes DTS doesn't go through a decoder, so it is unaffected.
         */
        val FFMPEG_ONLY = setOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD)

        fun decoderSelector(dolbyVisionAsHdr10: Boolean) = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            when {
                mimeType in FFMPEG_ONLY -> emptyList()
                dolbyVisionAsHdr10 && mimeType == MimeTypes.VIDEO_DOLBY_VISION ->
                    MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, requiresSecure, requiresTunneling)
                else -> MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            }
        }
    }
}
