package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.ProjectiveTransform
import com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import java.util.concurrent.CancellationException
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PerspectiveRotationRegistrationTest {
    private val geometry = checkNotNull(PerspectiveRotationGeometry.from35mmEquivalent(WIDTH, HEIGHT, 28.0))
    private val focal = geometry.focalPixels
    private val angle = 0.012
    private val warp = rotation(angle)
    private val prior = ReferenceToSourceTransform((focal * kotlin.math.tan(angle)).toFloat(), 0f)
    private val points = (0 until 6).flatMap { row -> (0 until 8).map { column ->
        (100.2f + column * 175f + row * 0.1f) to (200.4f + row * 300f + column * 0.1f)
    } }

    @Test fun predictsHeldOutStarsWhenPerspectiveExceedsTheArbitraryPlanarScaleCap() {
        assertNull(ProjectiveStarRegistrar().register(matches(), prior, WIDTH, HEIGHT, 5f))
        val result = checkNotNull(register(matches()))
        assertEquals(48, result.inliers.size)
        assertTrue(result.validationMedian < 0.05f)
        assertTrue(result.validationP90 < 0.05f)
        // Positions between the measured stars are never used to fit the model.
        for (row in 0..7) for (column in 0..7) {
            val x = 130f + column * 155f; val y = 220f + row * 210f
            val expected = warp.map(x, y); val actual = result.transform.mapOutputToSource(x, y)
            assertTrue(hypot(actual.x - expected.x, actual.y - expected.y) < 0.05f)
        }
    }

    @Test fun keepsOneToOneLabelsRobustOutliersAndSpatialRequirements() {
        val pairs = matches()
        val noisy = pairs.mapIndexed { i, match -> if (i % 6 == 0)
            match.copy(candidate = match.candidate.copy(x = match.candidate.x + 3f, y = match.candidate.y - 2f)) else match }
        assertEquals(40, checkNotNull(register(noisy)).inliers.size)
        assertNull(register(pairs.take(20)))
        assertNull(register(List(48) { pairs.first() }))
        assertNull(register(pairs.map { it.copy(candidate = pairs.first().candidate) }))
        assertNull(register(pairs.mapIndexed { i, match -> match.copy(candidate = pairs[(i + 1) % 48].candidate) }))
        assertNull(register(matches(points.map { (x, y) -> x * 0.1f + 500f to y * 0.1f + 500f })))
    }

    @Test fun rejectsFreeZoomReflectionWrongLensAndOutOfSearchCorrections() {
        val zoom = ProjectiveTransform(1.03, 0.0, -0.03 * CX, 0.0, 1.03, -0.03 * CY, 0.0, 0.0)
        assertNull(register(matches(model = zoom), ReferenceToSourceTransform.Identity, 50f))
        val reflection = ProjectiveTransform(-1.0, 0.0, WIDTH.toDouble(), 0.0, 1.0, 0.0, 0.0, 0.0)
        assertNull(register(matches(model = reflection), ReferenceToSourceTransform.Identity, 2000f))
        val wrong = checkNotNull(PerspectiveRotationGeometry.from35mmEquivalent(WIDTH, HEIGHT, 70.0))
        assertNull(ProjectiveStarRegistrar(wrong).register(matches(), prior, WIDTH, HEIGHT, 5f))
        assertNull(register(matches(), prior.copy(dx = prior.dx + 20f), 5f))
        assertNull(ProjectiveStarRegistrar(geometry).register(matches(), prior.copy(rotationRadians = 0.02f), WIDTH, HEIGHT, 50f))
        assertNull(register(matches(model = rotation(0.04)), prior, 100f))
    }

    @Test fun missingOrInvalidLensMetadataCannotEnableTheFallback() {
        for (value in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(PerspectiveRotationGeometry.from35mmEquivalent(WIDTH, HEIGHT, value))
        }
        assertNull(PerspectiveRotationGeometry.from35mmEquivalent(0, HEIGHT, 28.0))
        val landscape = checkNotNull(PerspectiveRotationGeometry.from35mmEquivalent(HEIGHT, WIDTH, 28.0))
        assertEquals(1553.160549, focal, 0.000001)
        assertEquals(focal, landscape.focalPixels, 0.0)
        assertNull(ProjectiveStarRegistrar(landscape).register(matches(), prior, WIDTH, HEIGHT, 5f))
    }

    @Test fun cancellationPropagatesInsideTheNonlinearFit() {
        var calls = 0
        try {
            ProjectiveStarRegistrar(geometry).register(matches(), prior, WIDTH, HEIGHT, 5f) {
                if (++calls == 3) throw CancellationException("cancel fit")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(3, calls) }
    }

    @Test fun imageRefinementRescuesGeometryWithoutBypassingPhotometry() {
        val reference = render(points)
        val mapped = points.map { (x, y) -> warp.map(x, y).let { it.x to it.y } }
        val candidate = render(mapped)
        val old = refine(reference, candidate, null)
        assertFalse(old.rejectionReason, old.accepted)
        val result = refine(reference, candidate, focal)
        assertTrue(result.rejectionReason, result.accepted)
        assertTrue(result.perspectiveRotationUsed)
        assertTrue(result.projectiveValidationP90 < 0.15f)
        assertTrue(result.acceptedStarCount >= 40)
        assertFalse(refine(reference, render(mapped, amplitude = 0.15f), focal).accepted)
        assertFalse(refine(reference, render(mapped, sigma = 2.3f), focal).accepted)
    }

    @Test fun anAlreadyAcceptedRigidFitIsUnchangedWhenLensMetadataIsAvailable() {
        val reference = render(points)
        val candidate = render(points.map { (x, y) -> x + prior.dx to y })
        val before = refine(reference, candidate, null)
        val after = refine(reference, candidate, focal)
        assertTrue(before.rejectionReason, before.accepted)
        assertEquals(before, after)
        assertFalse(after.perspectiveRotationUsed)
    }

    private fun register(pairs: List<StellarCentroidMatch>, initial: ReferenceToSourceTransform = prior, radius: Float = 5f) =
        ProjectiveStarRegistrar(geometry).register(pairs, initial, WIDTH, HEIGHT, radius)

    private fun matches(stars: List<Pair<Float, Float>> = points, model: ProjectiveTransform = warp) =
        stars.mapIndexed { index, (x, y) ->
            val predicted = model.map(x, y)
            val reference = measurement(x, y)
            val candidate = measurement(predicted.x + (index % 3 - 1) * 0.005f,
                predicted.y + (index % 5 - 2) * 0.004f)
            StellarCentroidMatch(patch(x, y), reference, candidate, candidate.x - x, candidate.y - y,
                0f, true, null, 0.9f, 1f)
        }

    // Independent analytic projection for a yaw about the optical center, not the production fitter.
    private fun rotation(radians: Double): ProjectiveTransform {
        val c = cos(radians); val s = sin(radians); val d = c + s * CX / focal
        return ProjectiveTransform((c - CX * s / focal) / d, 0.0, (focal * s + CX * CX * s / focal) / d,
            -CY * s / focal / d, 1 / d, (CY * (c - 1) + CY * CX * s / focal) / d, -s / focal / d, 0.0)
    }

    private fun measurement(x: Float, y: Float) = StellarCentroidMeasurement(
        x, y, 0.03f, 0.002f, 0.6f, 3f, 3f, 3f, 0f, 30f, 0f, 0.1f, 0.9f
    )

    private fun patch(x: Float, y: Float) = FullResolutionStarPatch(x, y, 0.9f, 0.55f, 3f, 0f,
        (y / HEIGHT * 3).toInt().coerceIn(0, 2) * 3 + (x / WIDTH * 3).toInt().coerceIn(0, 2),
        TemporalMotionCluster.COHERENT_MOVING_SKY, 1f)

    private fun refine(reference: IntArrayPixelSource, candidate: IntArrayPixelSource, lens: Double?) =
        StellarCentroidFrameRefiner().refine("lens-perspective", false, reference, candidate,
            prior, prior, points.map { (x, y) -> patch(x, y) }, 1f, 2f, 2f, 0.8f, focalLengthPixels = lens)

    private fun render(stars: List<Pair<Float, Float>>, amplitude: Float = 0.55f, sigma: Float = 1.35f): IntArrayPixelSource {
        val pixels = FloatArray(WIDTH * HEIGHT) { 0.03f }
        for ((x, y) in stars) for (dy in -9..9) for (dx in -9..9) {
            val ix = x.toInt() + dx; val iy = y.toInt() + dy
            if (ix !in 0 until WIDTH || iy !in 0 until HEIGHT) continue
            val rx = ix - x; val ry = iy - y
            pixels[iy * WIDTH + ix] += amplitude * exp((-(rx * rx + ry * ry) / (2 * sigma * sigma)).toDouble()).toFloat()
        }
        return IntArrayPixelSource(WIDTH, HEIGHT, IntArray(pixels.size) { i ->
            val value = (pixels[i].coerceIn(0f, 1f) * 255).toInt()
            0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        })
    }

    companion object {
        private const val WIDTH = 1440
        private const val HEIGHT = 1920
        private const val CX = (WIDTH - 1) * 0.5
        private const val CY = (HEIGHT - 1) * 0.5
    }
}
