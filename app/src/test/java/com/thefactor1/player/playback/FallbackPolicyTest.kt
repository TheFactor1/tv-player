package com.thefactor1.player.playback

import com.thefactor1.player.playback.FallbackPolicy.Action
import com.thefactor1.player.playback.FallbackPolicy.Failure
import com.thefactor1.player.playback.FallbackPolicy.Tried
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackPolicyTest {

    @Test
    fun networkRetriesBackOffThenGiveUp() {
        assertEquals(Action.Retry(1_000, 1), FallbackPolicy.next(Failure.NETWORK, false, Tried()))
        assertEquals(Action.Retry(2_000, 2), FallbackPolicy.next(Failure.NETWORK, false, Tried(networkRetries = 1)))
        assertEquals(Action.Retry(4_000, 3), FallbackPolicy.next(Failure.NETWORK, false, Tried(networkRetries = 2)))
        assertEquals(Action.GiveUp, FallbackPolicy.next(Failure.NETWORK, false, Tried(networkRetries = 3)))
    }

    @Test
    fun dolbyVisionFallsBackToHdr10Once() {
        assertEquals(Action.PlayDolbyVisionAsHdr10, FallbackPolicy.next(Failure.VIDEO_DECODER, true, Tried()))
        assertEquals(Action.GiveUp, FallbackPolicy.next(Failure.VIDEO_DECODER, true, Tried(dolbyVisionAsHdr10 = true)))
    }

    @Test
    fun nonDolbyVisionDecoderFailureGivesUp() {
        assertEquals(Action.GiveUp, FallbackPolicy.next(Failure.VIDEO_DECODER, false, Tried()))
    }

    @Test
    fun audioFallsBackToPcmOnce() {
        assertEquals(Action.DecodeAudioToPcm, FallbackPolicy.next(Failure.AUDIO_OUTPUT, false, Tried()))
        assertEquals(Action.GiveUp, FallbackPolicy.next(Failure.AUDIO_OUTPUT, false, Tried(pcmAudio = true)))
    }

    @Test
    fun liveStreamsRejoinAndUnknownFailuresGiveUp() {
        assertEquals(Action.RejoinLive, FallbackPolicy.next(Failure.BEHIND_LIVE_WINDOW, false, Tried()))
        assertEquals(Action.GiveUp, FallbackPolicy.next(Failure.OTHER, true, Tried()))
    }
}
