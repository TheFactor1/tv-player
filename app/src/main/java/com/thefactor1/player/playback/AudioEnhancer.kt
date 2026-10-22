package com.thefactor1.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** The viewer's night mode and dialogue boost switches, shared with the audio processor so a change applies at once. */
class AudioEnhancement {
    @Volatile var nightMode = false
    @Volatile var dialogueBoost = false

    /** Channels the processor last saw decoded audio in, 0 before any: shown in the stats. */
    @Volatile var channelsShaped = 0
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

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        settings.channelsShaped = inputAudioFormat.channelCount
        return inputAudioFormat
    }

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
 * at a time. It runs on the decoded channels before Android folds them down to the speakers, so a
 * 5.1 or 7.1 film still has its centre channel to work with.
 *
 * Dialogue boost lifts the centre channel, where films put the voices, and adds a presence lift
 * across the speech band (about 700 Hz to 5 kHz) while the rest of the mix steps back. A stereo
 * mix has no centre, so the same is done to what both speakers share, and the sides are narrowed.
 *
 * Night mode is a loudness leveller: it measures the mix the way a loudness meter does (power
 * summed over the speakers, the LFE left out) and pulls everything towards one level at 4:1, so
 * dialogue comes up several dB, whispers come up as much as 15 dB, and explosions come down
 * 10 to 15 dB. It also halves the LFE, the rumble that carries through walls. Gain drops quickly
 * and recovers slowly, the way broadcast levellers do, so it doesn't pump.
 *
 * A peak limiter then keeps the extra gain from clipping. Switching either on or off fades over a
 * moment instead of clicking.
 */
class AudioEnhancerDsp(sampleRate: Int, private val channels: Int) {
    private val hasCentre = channels in CENTRE_LAYOUTS
    private val lfe = if (channels >= 6) LFE else -1
    private val detector = smoothing(sampleRate, DETECTOR_MS)
    private val attack = smoothing(sampleRate, ATTACK_MS)
    private val release = smoothing(sampleRate, RELEASE_MS)
    private val limiterRelease = smoothing(sampleRate, LIMITER_RELEASE_MS)
    private val mixStep = 1f / (sampleRate * FADE_MS / 1000f)
    private val speech = Bandpass(sampleRate, SPEECH_HZ, SPEECH_Q)
    private var power = 0f
    private var gainDb = 0f
    private var limiterGain = 1f
    private var nightMix = 0f
    private var dialogueMix = 0f

    /** Nothing switched on and nothing still fading out: the audio can pass straight through. */
    fun isIdle(nightMode: Boolean, dialogueBoost: Boolean) =
        !nightMode && !dialogueBoost && nightMix == 0f && dialogueMix == 0f

    fun process(frame: FloatArray, nightMode: Boolean, dialogueBoost: Boolean) {
        nightMix = approach(nightMix, if (nightMode) 1f else 0f)
        dialogueMix = approach(dialogueMix, if (dialogueBoost) 1f else 0f)
        if (dialogueMix > 0f) boostDialogue(frame, dialogueMix)
        level(frame, nightMix)
        limit(frame)
    }

    private fun boostDialogue(frame: FloatArray, mix: Float) {
        when {
            hasCentre -> {
                val centre = frame[CENTRE]
                val presence = speech.process(centre) * PRESENCE_GAIN * mix
                val others = lerp(1f, SURROUND_GAIN, mix)
                for (i in 0 until channels) {
                    frame[i] = when (i) {
                        CENTRE -> centre * lerp(1f, CENTRE_GAIN, mix) + presence
                        lfe -> frame[i] * lerp(1f, LFE_DIALOGUE_GAIN, mix)
                        else -> frame[i] * others
                    }
                }
            }
            channels >= 2 -> {
                // Voices sit in the middle of a stereo mix: what left and right have in common.
                var mid = (frame[0] + frame[1]) / 2f
                mid = mid * lerp(1f, MID_GAIN, mix) + speech.process(mid) * PRESENCE_GAIN * mix
                val side = (frame[0] - frame[1]) / 2f * lerp(1f, SIDE_GAIN, mix)
                frame[0] = mid + side
                frame[1] = mid - side
            }
            channels == 1 -> frame[0] += speech.process(frame[0]) * PRESENCE_GAIN * mix
        }
    }

    private fun level(frame: FloatArray, mix: Float) {
        // Summed power, as a loudness meter sums its channels: one measure for all of them, so the
        // picture of the mix doesn't wander as levels change.
        var sum = 0f
        for (i in frame.indices) if (i != lfe) sum += frame[i] * frame[i]
        power = detector * power + (1f - detector) * sum
        val target = targetGainDb(power)
        val coefficient = if (target < gainDb) attack else release
        gainDb = coefficient * gainDb + (1f - coefficient) * target
        if (mix == 0f) return
        val gain = dbToGain(gainDb * mix)
        for (i in frame.indices) frame[i] *= gain
        if (lfe >= 0) frame[lfe] *= lerp(1f, LFE_NIGHT_GAIN, mix)
    }

    private fun limit(frame: FloatArray) {
        var peak = 0f
        for (sample in frame) peak = max(peak, abs(sample))
        limiterGain = limiterRelease * limiterGain + (1f - limiterRelease)
        if (peak * limiterGain > LIMIT_KNEE) limiterGain = LIMIT_KNEE / peak
        for (i in frame.indices) frame[i] = softLimit(frame[i] * limiterGain)
    }

    private fun approach(value: Float, target: Float): Float = when {
        value < target -> minOf(target, value + mixStep)
        value > target -> maxOf(target, value - mixStep)
        else -> value
    }

    /** A constant-gain band-pass filter (RBJ biquad), 0 dB at its centre frequency. */
    private class Bandpass(sampleRate: Int, hz: Float, q: Float) {
        private val b0: Float
        private val a1: Float
        private val a2: Float
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        init {
            val w = 2.0 * PI * hz / sampleRate
            val alpha = sin(w) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = (alpha / a0).toFloat()
            a1 = (-2.0 * cos(w) / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun process(x: Float): Float {
            // b1 is 0 and b2 is -b0 for this filter.
            val y = b0 * (x - x2) - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    companion object {
        /** Night mode pulls the mix towards this level (summed RMS, dBFS)... */
        const val TARGET_DB = -24f
        /** ...at this ratio, both from below and from above. */
        const val RATIO = 4f
        /** The most night mode lifts the quietest passages. */
        const val MAX_BOOST_DB = 15f
        /** Below this, it's silence or hiss, and the lift fades out instead of raising the noise floor. */
        const val FLOOR_DB = -65f
        const val FLOOR_FADE_DB = 15f
        const val DETECTOR_MS = 30f
        const val ATTACK_MS = 15f
        const val RELEASE_MS = 600f
        const val LFE_NIGHT_GAIN = 0.5f
        const val LIMITER_RELEASE_MS = 80f
        const val FADE_MS = 300f

        const val CENTRE_GAIN = 1.6f
        const val SURROUND_GAIN = 0.5f
        const val LFE_DIALOGUE_GAIN = 0.7f
        const val MID_GAIN = 1.25f
        const val SIDE_GAIN = 0.5f
        /** How much of the speech band is added back on top: 1 is +6 dB at [SPEECH_HZ]. */
        const val PRESENCE_GAIN = 1f
        const val SPEECH_HZ = 2_000f
        const val SPEECH_Q = 0.7f

        /** The limiter holds peaks here (about -1 dBFS); the soft clip above it is only a backstop. */
        const val LIMIT_KNEE = 0.89f
        /** Where the soft clip tops out, short of full scale (float tanh reaches 1 for large overs). */
        const val LIMIT_CEILING = 0.98f

        /** Android's channel order puts the centre third (L, R, C, ...) in these layouts; quad has none. */
        private val CENTRE_LAYOUTS = setOf(3, 5, 6, 7, 8)
        private const val CENTRE = 2
        private const val LFE = 3

        /** Night mode's gain, in dB, for a mix whose summed power (mean square) is [power]. */
        fun targetGainDb(power: Float): Float {
            val levelDb = 10f * log10(max(power, 1e-12f))
            val gain = (TARGET_DB - levelDb) * (1f - 1f / RATIO)
            if (gain <= 0f) return gain
            val floorFade = ((levelDb - (FLOOR_DB - FLOOR_FADE_DB)) / FLOOR_FADE_DB).coerceIn(0f, 1f)
            return minOf(gain, MAX_BOOST_DB) * floorFade
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
