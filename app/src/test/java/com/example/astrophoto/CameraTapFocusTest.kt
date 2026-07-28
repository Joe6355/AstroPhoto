package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTapFocusTest {
    @Test fun centerTapMapsToCenterOfPreviewSensorCropForEveryRotation() {
        val active = CameraSensorRect(0, 0, 4000, 3000)

        listOf(0, 90, 180, 270).forEach { rotation ->
            val portrait = rotation == 90 || rotation == 270
            val rect = calculateTapToFocusRect(
                viewWidth = if (portrait) 1080 else 1920,
                viewHeight = if (portrait) 1920 else 1080,
                touchX = if (portrait) 540f else 960f,
                touchY = if (portrait) 960f else 540f,
                previewWidth = 1920,
                previewHeight = 1080,
                activeArray = active,
                relativeRotationDegrees = rotation
            )

            assertEquals("rotation=$rotation centerX", 2000, rect.centerX())
            assertEquals("rotation=$rotation centerY", 1500, rect.centerY())
        }
    }

    @Test fun portraitTapUsesInverseSensorRotation() {
        val rect = calculateTapToFocusRect(
            viewWidth = 1080,
            viewHeight = 1920,
            touchX = 0f,
            touchY = 0f,
            previewWidth = 1920,
            previewHeight = 1080,
            activeArray = CameraSensorRect(0, 0, 4000, 3000),
            relativeRotationDegrees = 90,
            meteringFraction = 0.02f
        )

        assertEquals(0, rect.left)
        assertTrue(rect.centerY() in 2600..2650)
    }

    @Test fun fillCenterCropDoesNotMapHiddenPreviewPixels() {
        val rect = calculateTapToFocusRect(
            viewWidth = 1000,
            viewHeight = 1000,
            touchX = 500f,
            touchY = 0f,
            previewWidth = 1920,
            previewHeight = 1080,
            activeArray = CameraSensorRect(0, 0, 4000, 3000),
            relativeRotationDegrees = 90,
            meteringFraction = 0.02f
        )

        assertTrue(rect.centerX() in 850..900)
        assertTrue(rect.centerY() in 1480..1520)
    }

    @Test fun activeArrayOffsetAndEdgeClampingArePreserved() {
        val active = CameraSensorRect(100, 200, 4100, 3200)
        val rect = calculateTapToFocusRect(
            viewWidth = 1920,
            viewHeight = 1080,
            touchX = 1920f,
            touchY = 1080f,
            previewWidth = 1920,
            previewHeight = 1080,
            activeArray = active,
            relativeRotationDegrees = 0
        )

        assertTrue(rect.left >= active.left)
        assertTrue(rect.top >= active.top)
        assertTrue(rect.right <= active.right)
        assertTrue(rect.bottom <= active.bottom)
        assertEquals(active.right, rect.right)
    }

    private fun CameraSensorRect.centerX(): Int = (left + right) / 2
    private fun CameraSensorRect.centerY(): Int = (top + bottom) / 2
}
