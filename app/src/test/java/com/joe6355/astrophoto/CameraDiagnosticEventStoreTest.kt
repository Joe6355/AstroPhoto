package com.joe6355.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CameraDiagnosticEventStoreTest {
    @Test
    fun eventsAreNewestFirstDeduplicatedAndBounded() {
        val events = listOf(
            CameraDiagnosticEvent(1, "camera", "offline"),
            CameraDiagnosticEvent(3, "camera", "offline"),
            CameraDiagnosticEvent(2, "series", "save failed")
        )
        val bounded = boundedCameraDiagnosticEvents(events, 2)
        assertEquals(listOf(3L, 2L), bounded.map { it.timestampMillis })
    }

    @Test
    fun emptyDiagnosticLogHasExplicitText() {
        assertEquals("Нет зарегистрированных ошибок", formatCameraDiagnosticEvents(emptyList()))
        assertFalse(formatCameraDiagnosticEvents(listOf(
            CameraDiagnosticEvent(1, "camera", "offline")
        )).isBlank())
    }
}
