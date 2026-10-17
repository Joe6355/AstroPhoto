package com.joe6355.astrophoto

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ProcessedResultZoomTest {
    @Test
    fun portraitPhotoCannotBePannedIntoEmptySpaceAtFit() {
        val view = PhotoViewport(IntSize(1440, 1920), IntSize(1000, 500),
            focus = Offset(-10f, 20f))
        assertEquals(Offset(0.5f, 0.5f), view.boundedFocus)
        assertEquals(Rect(0f, 0f, 1440f, 1920f), view.visibleSource())
    }

    @Test
    fun pinchKeepsTheSourcePixelUnderTheFingers() {
        val before = PhotoViewport(IntSize(4000, 3000), IntSize(1000, 750))
        val finger = Offset(350f, 200f)
        val after = before.transform(finger, Offset.Zero, 4f)
        val source = (finger - before.translation) / before.scale
        val actual = source * after.scale + after.translation
        assertEquals(finger.x, actual.x, 0.001f)
        assertEquals(finger.y, actual.y, 0.001f)
        assertEquals(1f, after.scale, 0.00001f)
    }

    @Test
    fun panningStopsAtTheActualImageEdgeIncludingLetterbox() {
        val before = PhotoViewport(IntSize(4000, 1000), IntSize(1000, 1000), zoom = 2f)
        val after = before.transform(Offset(500f, 500f), Offset(10000f, -10000f), 1f)
        assertEquals(0f, after.visibleSource().left, 0.001f)
        assertEquals(0f, after.visibleSource().top, 0.001f)
        assertEquals(1000f, after.visibleSource().bottom, 0.001f)
    }

    @Test fun nativeAndHigherZoomAlwaysRequestOriginalPixels() {
        for (scale in listOf(1f, 2f, 8f)) {
            val region = photoRegionRequest(Rect(1000f, 1200f, 1600f, 1800f), 8000, 6000, scale)
            assertEquals(1, region.sample)
            assertTrue(region.left <= 1000 && region.top <= 1200)
            assertTrue(region.right >= 1600 && region.bottom >= 1800)
        }
    }

    @Test fun fitDoesNotUndersampleTheScreen() {
        val region = photoRegionRequest(Rect(0f, 0f, 8000f, 6000f), 8000, 6000, 0.12f)
        assertEquals(8, region.sample)
        assertTrue(region.sample * 0.12f <= 1f)
    }

    @Test fun hugeRegionIsBoundedWithoutIntegerOverflow() {
        val region = photoRegionRequest(Rect(0f, 0f, 100000f, 80000f), 100000, 80000, 1f)
        assertTrue(100000L * 80000L / region.sample / region.sample <= MAX_PHOTO_REGION_PIXELS)
    }

    @Test fun smallImagesAreNotEnlargedToFillTheScreen() {
        val view = PhotoViewport(IntSize(200, 100), IntSize(1000, 1000))
        assertEquals(1f, view.fitScale, 0f)
        assertEquals(Offset(400f, 450f), view.translation)
    }

    @Test fun leavingZoomResetsFocusAndImageFitsAgain() {
        val before = PhotoViewport(IntSize(4000, 3000), IntSize(1000, 750), 4f, Offset(0.2f, 0.8f))
        val after = before.transform(Offset(500f, 375f), Offset.Zero, 0.1f)
        assertEquals(1f, after.zoom, 0f)
        assertEquals(Offset(0.5f, 0.5f), after.boundedFocus)
    }
}
