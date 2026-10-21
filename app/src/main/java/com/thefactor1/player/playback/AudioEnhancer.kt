package com.thefactor1.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/** The viewer's night mode and dialogue boost switches, shared with the audio processor so a change applies at once. */
class AudioEnhancement {
    @Volatile var nightMode = false
    @Volatile var dialogueBoost = false
}

/**
 * Night mode and dialogue boost, for decoded audio only: passthrough never reaches an audio
 * processor. The audio sink hands it 16-bit PCM, which it turns into floats one frame at a time.
 */
@OptIn(UnstableApi::class)
class AudioEnhancerProcessor(private val settings: AudioEnhancement) : BaseAudioProcessor() {
    private var dsp: AudioEnhancerDsp? = null
    private var dspFormat: AudioProcessor.AudioFormat? = null
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val format = inputAudioFormat
        val processor = dsp?.takeIf { dspFormat == format }
            ?: AudioEnhancerDsp(format.sampleRate, format.channelCount).also {
                dsp = it
                dspFormat = format
                frame = FloatArray(format.channelCount)
            }
        val output = replaceOutputBuffer(size)
        val night = settings.nightMode
        val dialogue = settings.dialogueBoost
        if (processor.isIdle(night, dialogue)) {
            output.put(inputBuffer)
        } else {
            val bytesPerFrame = frame.size * 2
            while (inputBuffer.remaining() >= bytesPerFrame) {
                for (c in frame.indices) frame[c] = inputBuffer.getShort() / 32768f
                processor.process(frame, night, dialogue)
                for (c in frame.indices) output.putShort((frame[c] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            // A partial frame never arrives from the sink, but never leave bytes behind either.
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        }
        output.flip()
    }

    override fun onReset() {
        dsp = null
        dspFormat = null
    }
}

/**
 * The sound shaping behind night mode and dialogue boost, on one frame (a sample per channel, -1..1)
 * at a time.
 *
 * Dialogue boost lifts the centre channel, where films put the voices, over the rest of the mix; a
 * stereo mix has no centre, so it lifts what both speakers share and narrows the rest. Night mode
 * is a gentle compressor: loud passages come down and everything gets some makeup gain, so quiet
 * lines are audible without the explosions waking the house. A soft limiter keeps the extra gain
 * from clipping. Switching either on or off fades over a moment instead of clicking.
 */
class AudioEnhancerDsp(sampleRate: Int, private val channels: Int) {
    private val attack = smoothing(sampleRate, ATTACK_MS)
    private val release = smoothing(sampleRate, RELEASE_MS)
    private val mixStep = 1f / (sampleRate * FADE_MS / 1000f)
    private var envelope = 0f
    private var nightMix = 0f
    private var dialogueMix = 0f

    /** Nothing switched on and nothing still fading out: the audio can pass straight through. */
    fun isIdle(nightMode: Boolean, dialogueBoost: Boolean) =
        !nightMode && !dialogueBoost && nightMix == 0f && dialogueMix == 0f

    fun process(frame: FloatArray, nightMode: Boolean, dialogueBoost: Boolean) {
        nightMix = approach(nightMix, if (nightMode) 1f else 0f)
        dialogueMix = approach(dialogueMix, if (dialogueBoost) 1f else 0f)
        if (dialogueMix > 0f) boostDialogue(frame, dialogueMix)
        compress(frame, nightMix)
        for (i in frame.indices) frame[i] = softLimit(frame[i])
    }

    private fun boostDialogue(frame: FloatArray, mix: Float) {
        when {
            channels in CENTRE_LAYOUTS -> {
                val others = lerp(1f, SURROUND_GAIN, mix)
                for (i in 0 until channels) {
                    frame[i] *= when {
                        i == CENTRE -> lerp(1f, CENTRE_GAIN, mix)
                        i == LFE && channels >= 6 -> 1f
                        else -> others
                    }
                }
            }
            channels >= 2 -> {
                // Voices sit in the middle of a stereo mix: what left and right have in common.
                val mid = (frame[0] + frame[1]) / 2f * lerp(1f, MID_GAIN, mix)
                val side = (frame[0] - frame[1]) / 2f * lerp(1f, SIDE_GAIN, mix)
                frame[0] = mid + side
                frame[1] = mid - side
            }
        }
    }

    private fun compress(frame: FloatArray, mix: Float) {
        // One envelope for all channels, so the stereo image doesn't wander as levels change.
        var peak = 0f
        for (sample in frame) peak = max(peak, abs(sample))
        val coefficient = if (peak > envelope) attack else release
        envelope = coefficient * envelope + (1f - coefficient) * peak
        if (mix == 0f) return
        val gain = dbToGain(gainDb(envelope) * mix)
        for (i in frame.indices) frame[i] *= gain
    }

    private fun approach(value: Float, target: Float): Float = when {
        value < target -> minOf(target, value + mixStep)
        value > target -> maxOf(target, value - mixStep)
        else -> value
    }

    companion object {
        const val THRESHOLD_DB = -30f
        const val RATIO = 3f
        const val MAKEUP_DB = 8f
        const val ATTACK_MS = 10f
        const val RELEASE_MS = 250f
        const val FADE_MS = 300f
        const val CENTRE_GAIN = 1.8f
        const val SURROUND_GAIN = 0.8f
        const val MID_GAIN = 1.25f
        const val SIDE_GAIN = 0.5f
        const val LIMIT_KNEE = 0.89f
        /** Where the limiter tops out, short of full scale (float tanh reaches 1 for large overs). */
        const val LIMIT_CEILING = 0.98f

        /** Android's channel order puts the centre third (L, R, C, ...) in these layouts; quad has none. */
        private val CENTRE_LAYOUTS = setOf(3, 5, 6, 7, 8)
        private const val CENTRE = 2
        private const val LFE = 3

        /** Night mode's gain, in dB, for a signal whose envelope is at [level] (linear, 0..1). */
        fun gainDb(level: Float): Float {
            val levelDb = 20f * log10(max(level, 1e-6f))
            val over = levelDb - THRESHOLD_DB
            val reduction = if (over > 0f) over * (1f - 1f / RATIO) else 0f
            return MAKEUP_DB - reduction
        }

        /** Untouched below the knee, then eased so nothing ever passes full scale. */
        fun softLimit(sample: Float): Float {
            val size = abs(sample)
            if (size <= LIMIT_KNEE) return sample
            val headroom = LIMIT_CEILING - LIMIT_KNEE
            val limited = LIMIT_KNEE + headroom * tanh((size - LIMIT_KNEE) / headroom)
            return if (sample < 0f) -limited else limited
        }

        private fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

        private fun lerp(from: Float, to: Float, amount: Float) = from + (to - from) * amount

        private fun smoothing(sampleRate: Int, ms: Float): Float =
            kotlin.math.exp(-1.0 / (sampleRate * ms / 1000.0)).toFloat()
    }
}
