package com.joe6355.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPreviewCropTest {
    @Test fun landscapePreviewDoesNotInheritNativeMinimumSize() {
        val crop=CropSettings(5f,7f,10f,20f)
        assertEquals(CropPixels(51,72,13,26,901,89),editorPreviewCropPixels(1024,128,crop))
        assertEquals(CropPixels(205,287,51,102,3604,359),editorCropPixels(4096,512,crop))
    }

    @Test fun portraitPreviewRemainsEditableNearNativeMinimumWidth() {
        val crop=CropSettings(30f,30f,5f,7f)
        assertEquals(CropPixels(38,38,51,72,52,901),editorPreviewCropPixels(128,1024,crop))
        assertEquals(CropPixels(154,154,205,287,204,3604),editorCropPixels(512,4096,crop))
    }

    @Test fun uncroppedNarrowPreviewsKeepEveryDisplayPixel() {
        assertEquals(CropPixels(0,0,0,0,1024,128),editorPreviewCropPixels(1024,128,CropSettings()))
        assertEquals(CropPixels(0,0,0,0,128,1024),editorPreviewCropPixels(128,1024,CropSettings()))
    }

    @Test fun roundingCannotEliminateOneOrTwoPixelPreviewDimensions() {
        val crop=CropSettings(30f,30f,30f,30f)
        for (width in 1..4) for (height in 1..4) {
            val actual=editorPreviewCropPixels(width,height,crop)
            assertTrue(actual.finalWidth>=1 && actual.finalHeight>=1)
            assertEquals(width,actual.left+actual.finalWidth+actual.right)
            assertEquals(height,actual.top+actual.finalHeight+actual.bottom)
        }
    }

    @Test fun ordinaryRoundingAndCoordinatesRemainUnchanged() {
        val crop=CropSettings(13f,7f,11f,5f)
        val expected=CropPixels(133,72,84,38,819,646)
        assertEquals(expected,editorCropPixels(1024,768,crop))
        assertEquals(expected,editorPreviewCropPixels(1024,768,crop))
    }

    @Test fun nativeExportStillRejectsAnActuallyTooSmallCrop() {
        val crop=CropSettings(topPercent=30f,bottomPercent=30f)
        assertEquals(160,editorPreviewCropPixels(800,400,crop).finalHeight)
        assertThrows(IllegalArgumentException::class.java) { editorCropPixels(800,400,crop) }
    }

    @Test fun invalidImageDimensionsAreRejectedByBothPaths() {
        assertThrows(IllegalArgumentException::class.java) { editorPreviewCropPixels(0,400,CropSettings()) }
        assertThrows(IllegalArgumentException::class.java) { editorPreviewCropPixels(400,-1,CropSettings()) }
        assertThrows(IllegalArgumentException::class.java) { editorCropPixels(0,400,CropSettings()) }
    }

    @Test fun percentageClampingIsSharedByPreviewAndExport() {
        val crop=CropSettings(-10f,100f,-20f,200f)
        assertEquals(CropPixels(0,38,0,307,90,717),editorPreviewCropPixels(128,1024,crop))
        assertEquals(CropPixels(0,154,0,1229,358,2867),editorCropPixels(512,4096,crop))
    }
}
