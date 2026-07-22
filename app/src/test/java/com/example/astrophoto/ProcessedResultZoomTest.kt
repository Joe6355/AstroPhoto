package com.example.astrophoto

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessedResultZoomTest {
    @Test
    fun offsetIsResetAtBaseScale() {
        assertEquals(
            Offset.Zero,
            clampZoomOffset(Offset(80f, -40f), 1f, IntSize(300, 200))
        )
    }

    @Test
    fun offsetIsLimitedToZoomedViewport() {
        assertEquals(
            Offset(150f, -100f),
            clampZoomOffset(Offset(500f, -500f), 2f, IntSize(300, 200))
        )
    }

    @Test
    fun offsetInsideBoundsIsPreserved() {
        assertEquals(
            Offset(60f, -30f),
            clampZoomOffset(Offset(60f, -30f), 3f, IntSize(300, 200))
        )
    }
}
