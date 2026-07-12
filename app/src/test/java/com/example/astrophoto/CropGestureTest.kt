package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGestureTest {
    private val bounds = PreviewBounds(100f, 50f, 400f, 200f)
    private val crop = NormalizedCropRect(0.2f, 0.2f, 0.8f, 0.8f)

    @Test
    fun cornerHasFortyEightPixelTouchTarget() {
        assertEquals(
            CropDragHandle.TOP_LEFT,
            cropHandle(100f + 80f + 23f, 50f + 40f + 23f, bounds, crop, 24f)
        )
    }

    @Test
    fun cornerDragChangesBothAxesOnFirstEvent() {
        val changed = dragCrop(crop, CropDragHandle.TOP_LEFT, 0.05f, 0.08f, 0.1f, 0.1f)
        assertNotEquals(crop, changed)
        assertEquals(0.25f, changed.left, 0.0001f)
        assertEquals(0.28f, changed.top, 0.0001f)
    }

    @Test
    fun edgeDragChangesOnlyOneAxis() {
        val changed = dragCrop(crop, CropDragHandle.RIGHT, -0.1f, 0.3f, 0.1f, 0.1f)
        assertEquals(0.7f, changed.right, 0.0001f)
        assertEquals(crop.top, changed.top, 0f)
        assertEquals(crop.bottom, changed.bottom, 0f)
    }

    @Test
    fun draggingInsideMovesWholeRectangle() {
        assertEquals(
            CropDragHandle.MOVE,
            cropHandle(300f, 150f, bounds, crop, 24f)
        )
        val changed = dragCrop(crop, CropDragHandle.MOVE, 0.1f, -0.1f, 0.1f, 0.1f)
        assertEquals(crop.right - crop.left, changed.right - changed.left, 0.0001f)
        assertEquals(crop.bottom - crop.top, changed.bottom - changed.top, 0.0001f)
    }

    @Test
    fun scaledPreviewCoordinatesSelectCorrectHandle() {
        val fitted = previewBounds(1_000f, 600f, 4_000, 3_000)
        assertEquals(800f, fitted.width, 0.001f)
        assertEquals(600f, fitted.height, 0.001f)
        val right = fitted.left + crop.right * fitted.width
        val middleY = fitted.top + (crop.top + crop.bottom) / 2f * fitted.height
        assertEquals(CropDragHandle.RIGHT, cropHandle(right, middleY, fitted, crop, 24f))
    }

    @Test
    fun dragIsClampedInsideImage() {
        val changed = dragCrop(crop, CropDragHandle.MOVE, 2f, -2f, 0.1f, 0.1f)
        assertEquals(1f, changed.right, 0f)
        assertEquals(0f, changed.top, 0f)
        assertTrue(changed.left >= 0f && changed.bottom <= 1f)
    }

    @Test
    fun resizeEnforcesMinimumSize() {
        val changed = dragCrop(crop, CropDragHandle.LEFT, 1f, 0f, 0.2f, 0.2f)
        assertEquals(0.2f, changed.right - changed.left, 0.0001f)
    }

    @Test
    fun pressOutsideRectangleAndHandlesDoesNothing() {
        assertEquals(CropDragHandle.NONE, cropHandle(105f, 55f, bounds, crop, 24f))
        assertEquals(crop, dragCrop(crop, CropDragHandle.NONE, 0.1f, 0.1f, 0.1f, 0.1f))
    }
}
