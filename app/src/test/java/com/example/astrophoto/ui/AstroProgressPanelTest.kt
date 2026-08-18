package com.example.astrophoto.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AstroProgressPanelTest {
    @Test
    fun formatProcessingElapsed_formatsMinutesAndHours() {
        assertEquals("00:00", formatProcessingElapsed(0))
        assertEquals("01:05", formatProcessingElapsed(65))
        assertEquals("1:01:01", formatProcessingElapsed(3_661))
    }

    @Test
    fun formatProcessingElapsed_clampsNegativeValues() {
        assertEquals("00:00", formatProcessingElapsed(-1))
    }
}
