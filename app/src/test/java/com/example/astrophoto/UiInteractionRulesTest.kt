package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiInteractionRulesTest {
    @Test
    fun profileReasonExplainsWhyActionIsDisabled() {
        val profile = AstroProcessingProfile.entries
            .first { it != AstroProcessingProfile.NORMAL }

        assertEquals(
            "нужно минимум ${profile.minimumFrames} кадров",
            processingProfileUnavailableReason(
                profile = profile,
                availableFrames = profile.minimumFrames - 1,
                sourceError = null,
                loading = false,
                running = false,
                operationsEnabled = true
            )
        )
        assertEquals(
            "Источник недоступен",
            processingProfileUnavailableReason(
                profile = profile,
                availableFrames = profile.minimumFrames,
                sourceError = "Источник недоступен",
                loading = false,
                running = false,
                operationsEnabled = true
            )
        )
        assertNull(
            processingProfileUnavailableReason(
                profile = profile,
                availableFrames = profile.minimumFrames,
                sourceError = null,
                loading = false,
                running = false,
                operationsEnabled = true
            )
        )
    }

    @Test
    fun repeatedProcessTapCannotStartSecondJob() {
        assertTrue(canStartProcessing(running = false, jobActive = false))
        assertFalse(canStartProcessing(running = true, jobActive = false))
        assertFalse(canStartProcessing(running = false, jobActive = true))
    }

    @Test
    fun cropBackConfirmsUnsavedChangesAndBlocksWhileBusy() {
        val initial = NormalizedCropRect.Full
        val changed = NormalizedCropRect(0.1f, 0.1f, 0.9f, 0.9f)

        assertEquals(
            CropDismissAction.DISMISS,
            cropDismissAction(initial, initial, busy = false)
        )
        assertEquals(
            CropDismissAction.CONFIRM,
            cropDismissAction(initial, changed, busy = false)
        )
        assertEquals(
            CropDismissAction.BLOCK,
            cropDismissAction(initial, changed, busy = true)
        )
    }
}
