package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import org.junit.Assert.*
import org.junit.Test

class StellarArtifactGateTest {
    private val stars = (0..5).map {
        DetectedStar(48f + (it % 3) * 80, 48f + (it / 3) * 90,
            100f, 10f, 100f, 1.5f, 0f, 1f)
    }
    private fun image(trails: Boolean = false, duplicates: Boolean = false, gain: Int = 1): IntArrayPixelSource {
        val pixels = IntArray(280 * 190) { gray(10 * gain) }
        for (star in stars) {
            val x = star.x.toInt(); val y = star.y.toInt()
            for (dy in -1..1) for (dx in -1..1) pixels[(y + dy) * 280 + x + dx] = gray(100 * gain)
            if (trails) for (dx in 7..21) pixels[y * 280 + x + dx] = gray(30 * gain)
            if (duplicates) for (dy in -1..1) for (dx in -1..1) {
                pixels[(y + dy + 12) * 280 + x + dx + 15] = gray(30 * gain)
            }
        }
        return IntArrayPixelSource(280, 190, pixels)
    }
    private fun gray(value: Int) = (0xff shl 24) or (value shl 16) or (value shl 8) or value

    @Test fun unchangedStarsPass() {
        assertTrue(LineArtifactDetector().compareStarNeighborhoods(image(), image(), stars).accepted)
    }
    @Test fun existingTrailsAndGlobalGainPass() {
        assertTrue(LineArtifactDetector().compareStarNeighborhoods(image(trails = true),
            image(trails = true, gain = 2), stars).accepted)
    }
    @Test fun copiedCoresDoNotHideNewTrails() {
        val result = LineArtifactDetector().compareStarNeighborhoods(image(), image(trails = true), stars)
        assertFalse(result.accepted)
        assertTrue(result.hardFailureReasons.contains("new_stellar_streaks_in_full_resolution"))
    }
    @Test fun repeatedCompactGhostsFail() {
        val result = LineArtifactDetector().compareStarNeighborhoods(image(), image(duplicates = true), stars)
        assertFalse(result.accepted)
        assertTrue(result.hardFailureReasons.contains("repeated_new_stellar_companions_in_full_resolution"))
    }
    @Test fun noStarsIsExplicitlyUnmeasurable() {
        val result = LineArtifactDetector().compareStarNeighborhoods(image(), image(), emptyList())
        assertTrue(result.accepted)
        assertEquals(listOf("insufficient_stars_for_independent_artifact_check"), result.warningReasons)
    }
    @Test fun isolatedStructureIsWarningNotProofOfBadRegistration() {
        val result = LineArtifactDetector().compareStarNeighborhoods(image(), image(trails = true), stars.take(1))
        assertTrue(result.accepted)
        assertTrue(result.warningReasons.contains("isolated_new_stellar_structure"))
    }
    @Test fun dimensionMismatchRejects() {
        assertFalse(LineArtifactDetector().compareStarNeighborhoods(image(),
            IntArrayPixelSource(2, 2, IntArray(4)), stars).accepted)
    }
    @Test fun independentNoiseDoesNotBecomeCoherentArtifacts() {
        val random = java.util.Random(6355)
        fun noisy(): IntArrayPixelSource {
            val original = image()
            return IntArrayPixelSource(280, 190, IntArray(280 * 190) { i ->
                gray(((original.argbAt(i % 280, i / 280) and 255) + random.nextGaussian() * 2)
                    .toInt().coerceIn(0, 255))
            })
        }
        repeat(10) {
            assertTrue(LineArtifactDetector().compareStarNeighborhoods(noisy(), noisy(), stars).accepted)
        }
    }
    @Test fun mergingCannotClearArtifactRejection() {
        val detector = LineArtifactDetector()
        val good = detector.compareStarNeighborhoods(image(), image(), stars)
        val bad = detector.compareStarNeighborhoods(image(), image(trails = true), stars)
        assertFalse(good.combinedWith(bad).accepted)
        assertFalse(bad.combinedWith(good).accepted)
        assertEquals(bad.hardFailureReasons, bad.combinedWith(bad).hardFailureReasons)
    }
    @Test(expected = java.util.concurrent.CancellationException::class)
    fun cancellationPropagates() {
        LineArtifactDetector().compareStarNeighborhoods(image(), image(), stars) {
            throw java.util.concurrent.CancellationException()
        }
    }
}
