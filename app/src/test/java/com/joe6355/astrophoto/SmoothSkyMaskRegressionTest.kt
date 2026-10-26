package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.masking.SkyMaskRefiner
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import java.util.Random
import kotlin.math.exp
import kotlin.math.roundToInt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothSkyMaskRegressionTest {
    private val width = 280
    private val height = 360

    @Test fun horizontalSkyGradientDoesNotTurnIntoSeparateVerticalStrips() {
        val refined = refine(scene { x, _ -> rgb(10 + x / 5, 14 + x / 6, 20 + x / 7) })
        for (x in 20 until width - 20 step 20) assertTrue("Sky column $x was discarded", refined.contains(x, 120))
    }

    @Test fun verticalSkyGradientStaysContinuousBelowThePrimaryColorComponent() {
        val refined = refine(scene { _, y -> rgb(10 + y / 6, 14 + y / 7, 20 + y / 8) })
        for (y in 20 until height - 20 step 20) assertTrue("Sky row $y was discarded", refined.contains(140, y))
    }

    @Test fun smoothDiffuseGlowDoesNotProduceAnUnprocessedHole() {
        val image = scene { x, y ->
            val glow = (12 * exp(-((x - 170.0) * (x - 170) + (y - 190.0) * (y - 190)) / 7200)).roundToInt()
            rgb(12 + x / 12 + glow, 16 + y / 12 + glow, 24 + y / 10 + glow)
        }
        val refined = refine(image)
        for (y in 40..300 step 20) for (x in 40..240 step 20) {
            assertTrue("Diffuse sky at $x,$y was cut out", refined.contains(x, y))
        }
    }

    @Test fun aNoisyGradientStillKeepsTheSkyConnected() {
        val random = Random(41026)
        val image = scene { x, y -> rgb(12 + x / 6 + random.nextInt(5) - 2,
            17 + y / 7 + random.nextInt(5) - 2, 24 + x / 8 + y / 10 + random.nextInt(5) - 2) }
        val refined = refine(image)
        val samples = (40..300 step 20).flatMap { y -> (40..240 step 20).map { x -> refined.contains(x, y) } }
        assertTrue("Noise must not split a gradual sky into islands", samples.count { it } >= samples.size * .95)
    }

    @Test fun abruptSubtleBuildingBoundariesRemainProtectedAtEveryBlockPhase() {
        for (phase in 0..9) {
            val edge = 70 + phase
            val refined = refine(scene { x, _ -> if (x < edge) rgb(8, 9, 6) else rgb(12, 15, 18) })
            assertFalse("Building joined sky at phase $phase", refined.contains(30, 120))
            assertTrue(refined.contains(180, 120))
        }
    }

    @Test fun aDiagonalBuildingEdgeCannotMasqueradeAsAnAveragedColorGradient() {
        val refined = refine(scene { x, y -> if (x < 45 + y / 3) rgb(8, 9, 6) else rgb(12, 15, 18) })
        assertFalse(refined.contains(30, 180))
        assertFalse(refined.contains(55, 220))
        assertTrue(refined.contains(220, 180))
    }

    @Test fun aDarkWallBelowTheSkyRemainsExcluded() {
        val refined = refine(scene { _, y -> if (y < 180) rgb(20, 26, 38) else rgb(7, 7, 7) })
        assertTrue(refined.contains(140, 80))
        assertFalse(refined.contains(140, 260))
    }

    @Test fun continuityNeverExpandsBeyondTheInitialSkyMask() {
        val initial = SkyMask(width, height, BooleanArray(width * height) { it / width < 180 })
        val refined = refine(scene { x, y -> rgb(10 + x / 6, 14 + y / 7, 20 + x / 8) }, initial)
        assertTrue(refined.contains(140, 80))
        assertFalse(refined.contains(140, 200))
    }

    @Test fun aDetectedPointOnASeparateFacadeCannotSeedExpansionAcrossItsGradient() {
        val image = scene { x, y ->
            if (x < 80) rgb(6 + y / 5, 7 + y / 5, 5 + y / 5) else rgb(30, 40, 60)
        }
        val point = DetectedStar(30f, 100f, 200f, 4f, 100f, 2f, 0f, 1f)
        val refined = refine(image, stars = listOf(point))
        assertTrue(refined.contains(180, 120))
        assertFalse("A point on a color-incompatible facade is not evidence of sky", refined.contains(30, 100))
        assertFalse("A secondary point must not admit the rest of the facade", refined.contains(30, 300))
    }

    @Test fun aDarkerSecondarySkyRegionWithTheSameRelativeColorKeepsItsStar() {
        val image = scene { x, _ -> if (x < 80) rgb(4, 6, 10) else rgb(12, 18, 30) }
        val point = DetectedStar(30f, 100f, 200f, 4f, 100f, 2f, 0f, 1f)
        val refined = refine(image, stars = listOf(point))
        assertTrue(refined.contains(30, 100))
        assertTrue(refined.contains(180, 120))
    }

    @Test fun aCompatibleSecondaryPointCannotExpandAnOtherwiseDisconnectedGradient() {
        val image = scene { x, y -> if (x < 80) rgb(6 + y / 5, 6 + y / 5, 6 + y / 5) else rgb(180, 180, 180) }
        val point = DetectedStar(30f, 100f, 200f, 4f, 100f, 2f, 0f, 1f)
        val refined = refine(image, stars = listOf(point))
        assertTrue(refined.contains(30, 100))
        assertTrue(refined.contains(180, 120))
        assertFalse(refined.contains(30, 300))
    }

    private fun scene(color: (Int, Int) -> Int) = ArgbPixelImage(width, height,
        IntArray(width * height) { color(it % width, it / width) })

    private fun refine(image: ArgbPixelImage, initial: SkyMask = SkyMask.full(width, height), stars: List<DetectedStar> = emptyList()) =
        SkyMaskRefiner().refine(initial, image, stars, 1f, false, 1f).binaryMask

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}
