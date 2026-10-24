package com.thefactor1.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlin.math.min

/**
 * Retries for a stream that hiccups. Usenet sources build the file from articles as it is read, so
 * they answer "busy" (503, 502, 504, 429) or go quiet for a while, mostly when a stream starts or
 * after a seek. Media3 tries again after 0, 1 and 2 seconds and then stops the film; here a source
 * gets about half a minute, with longer waits between tries.
 */
@OptIn(UnstableApi::class)
class StreamLoadErrors : DefaultLoadErrorHandlingPolicy(RETRIES) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // Broken files, missing links and the like stay final, as Media3 decides.
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        val status = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (status != null && !isTransient(status)) return C.TIME_UNSET
        return delayMs(loadErrorInfo.errorCount)
    }

    companion object {
        const val RETRIES = 6
        private const val FIRST_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 10_000L

        /** 1, 2, 4, 8, 10, 10 seconds: about 35 seconds in all. */
        fun delayMs(errorCount: Int): Long =
            min(FIRST_DELAY_MS shl (errorCount - 1).coerceIn(0, 10), MAX_DELAY_MS)

        /** Answers that mean "not now" rather than "never": timeouts, rate limits and server trouble. */
        fun isTransient(status: Int): Boolean = status == 408 || status == 429 || status in 500..599
    }
}
