package com.thefactor1.player.playback

/**
 * Decides what to try when playback fails, before giving up and showing an error.
 * Each fallback is tried at most once per stream, and network retries back off.
 */
object FallbackPolicy {

    enum class Failure { NETWORK, BEHIND_LIVE_WINDOW, VIDEO_DECODER, AUDIO_OUTPUT, OTHER }

    /** What has already been tried for this stream. */
    data class Tried(
        val networkRetries: Int = 0,
        val dolbyVisionAsHdr10: Boolean = false,
        val pcmAudio: Boolean = false,
    )

    sealed interface Action {
        /** Prepare the same player again after [delayMs], keeping the position. */
        data class Retry(val delayMs: Long, val attempt: Int) : Action

        /** Jump back to the live edge and prepare again. */
        data object RejoinLive : Action

        /** Rebuild the player decoding Dolby Vision's HDR10 base layer with a plain HEVC decoder. */
        data object PlayDolbyVisionAsHdr10 : Action

        /** Rebuild the player with passthrough off, decoding all audio to PCM. */
        data object DecodeAudioToPcm : Action

        /** Nothing left to try. */
        data object GiveUp : Action
    }

    const val MAX_NETWORK_RETRIES = 3
    private const val FIRST_RETRY_DELAY_MS = 1_000L

    fun next(failure: Failure, isDolbyVision: Boolean, tried: Tried): Action = when (failure) {
        Failure.NETWORK ->
            if (tried.networkRetries < MAX_NETWORK_RETRIES) {
                Action.Retry(FIRST_RETRY_DELAY_MS shl tried.networkRetries, tried.networkRetries + 1)
            } else {
                Action.GiveUp
            }
        Failure.BEHIND_LIVE_WINDOW -> Action.RejoinLive
        Failure.VIDEO_DECODER ->
            if (isDolbyVision && !tried.dolbyVisionAsHdr10) Action.PlayDolbyVisionAsHdr10 else Action.GiveUp
        Failure.AUDIO_OUTPUT -> if (!tried.pcmAudio) Action.DecodeAudioToPcm else Action.GiveUp
        Failure.OTHER -> Action.GiveUp
    }
}
