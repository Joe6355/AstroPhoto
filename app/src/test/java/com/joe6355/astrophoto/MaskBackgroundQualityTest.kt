package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.quality.BackgroundQualityComparator
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import org.junit.Assert.*
import org.junit.Test

class MaskBackgroundQualityTest {
    private val width = 240
    private val height = 192
    private val alpha = object : AlphaPixelSource {
        override val width = this@MaskBackgroundQualityTest.width
        override val height = this@MaskBackgroundQualityTest.height
        override fun alphaAt(x: Int, y: Int): Float = ((x - 100f) / 40f).coerceIn(0f, 1f)
    }
    private fun scene(lift: Int, building: Boolean = false, grain: Boolean = false) =
        IntArrayPixelSource(width, height, IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val background = if (building && x < 120) 80 else 15 + y / 40
            val noise = if (grain) (x * 17 + y * 23) % 3 - 1 else 0
            val value = (background + lift * alpha.alphaAt(x, y) + noise).toInt()
            0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        })

    @Test fun rejectsMaskShapedProcessingLiftOnAnOtherwiseSmoothSky() {
        val result = BackgroundQualityComparator().compareBoundaryBackground(scene(0), scene(12), alpha)
        assertEquals(listOf("mask_shaped_background_lift"), result.hardFailureReasons)
    }

    @Test fun unchangedSkyAndFineGrainDoNotBecomeMaskDefects() {
        assertTrue(BackgroundQualityComparator().compareBoundaryBackground(scene(0), scene(0), alpha)
            .hardFailureReasons.isEmpty())
        assertTrue(BackgroundQualityComparator().compareBoundaryBackground(scene(0), scene(0, grain = true), alpha)
            .hardFailureReasons.isEmpty())
    }

    @Test fun aRealForegroundBoundaryDoesNotCountAsAnArtificialSkyDiscontinuity() {
        assertTrue(BackgroundQualityComparator().compareBoundaryBackground(scene(0, building = true),
            scene(12, building = true), alpha).hardFailureReasons.isEmpty())
    }
}
