package com.thefactor1.player.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackResultTest {
    @Test
    fun stoppedEarlyReportsWhereTheViewerStopped() {
        assertEquals(1_234_000L, PlaybackResult.reportedPosition(1_234_000, 7_200_000, completed = false))
    }

    @Test
    fun finishedReportsTheFullLength() {
        assertEquals(7_200_000L, PlaybackResult.reportedPosition(7_199_000, 7_200_000, completed = true))
    }

    @Test
    fun finishedWithUnknownLengthFallsBackToPosition() {
        assertEquals(5_000L, PlaybackResult.reportedPosition(5_000, 0, completed = true))
    }
}
