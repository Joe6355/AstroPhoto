package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.ProjectiveTransform
import com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.model.TransformPoint
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.PreparedReferenceToSourceTransform
import com.joe6355.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.joe6355.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ProjectiveStarRegistrationTest {
    private val warp = ProjectiveTransform(1.001242, 0.00794, -3.53, -0.00671, 1.004224, -3.03, -1.247e-6, 3.83e-6)
    private val prior = ReferenceToSourceTransform(-2.72f, -2.06f, -0.0063f)
    private val stars = (0 until 6).flatMap { row -> (0 until 8).map { column ->
        (75.2f + column * 120f + row * 0.31f) to (100.4f + row * 235f + column * 0.17f)
    } }

    @Test fun predictsIndependentStarsWithPerspectiveAndKeepsOneToOneLabels() {
        val pairs = matches()
        val result = checkNotNull(register(pairs))
        assertTrue(result.validationMedian < 0.1f)
        assertTrue(result.validationP90 < 0.1f)
        assertEquals(48, result.inliers.size)
        for (row in 0..8) for (column in 0..8) {
            val x = 100f + column * 100f; val y = 100f + row * 150f
            val expected = warp.map(x, y)
            val actual = result.transform.mapOutputToSource(x, y)
            assertTrue(hypot(actual.x - expected.x, actual.y - expected.y) < 0.1f)
        }
        val wrong = pairs.mapIndexed { index, match -> match.copy(candidate = pairs[(index + 1) % pairs.size].candidate) }
        assertNull(register(wrong))
    }

    @Test fun excludesOutliersWithoutRematchingAndRejectsSparseOrRepeatedSupport() {
        val pairs = matches().mapIndexed { index, match -> if (index % 6 == 0)
            match.copy(candidate = match.candidate.copy(x = match.candidate.x + 3f,
                y = match.candidate.y - index * 0.1f - 2f)) else match }
        val result = checkNotNull(register(pairs))
        assertEquals(40, result.inliers.size)
        assertNull(register(matches().take(20)))
        assertNull(register(List(48) { matches().first() }))
        assertNull(register(matches().map { it.copy(candidate = matches().first().candidate) }))
    }

    @Test fun concentratedOrNoisyStarsCannotAuthorizeAFlexibleModel() {
        val concentrated = stars.map { (x, y) -> (x * 0.1f + 300f) to (y * 0.1f + 400f) }
        assertNull(register(matches(concentrated)))
        val random = Random(8179)
        assertNull(register(matches().map { match -> match.copy(candidate = match.candidate.copy(
            x = match.candidate.x + random.nextFloat() * 4f - 2f,
            y = match.candidate.y + random.nextFloat() * 4f - 2f)) }))
    }

    @Test fun rejectsExcessiveZoomReflectionAndAnIncompatibleMotionPrior() {
        val zoom = ProjectiveTransform(1.03, 0.0, -3.0, 0.0, 1.03, -2.0, 0.0, 0.0)
        assertNull(register(matches(model = zoom)))
        val reflection = ProjectiveTransform(-1.0, 0.0, WIDTH.toDouble(), 0.0, 1.0, 0.0, 0.0, 0.0)
        assertNull(register(matches(model = reflection)))
        assertNull(ProjectiveStarRegistrar().register(matches(), prior.copy(rotationRadians = 0.01f),
            WIDTH, HEIGHT, 6f))
    }

    @Test fun cancellationIsCheckedDuringHypothesisSearch() {
        var checks = 0
        try {
            ProjectiveStarRegistrar().register(matches(), prior, WIDTH, HEIGHT, 5f) {
                if (++checks == 3) throw CancellationException("cancel test")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(3, checks)
        }
    }

    @Test fun fullResolutionFallbackRecoversPerspectiveWithoutWeakeningQualityGates() {
        val reference = render(stars)
        val candidateStars = stars.map { (x, y) -> warp.map(x, y).let { it.x to it.y } }
        val result = refine(reference, render(candidateStars))
        assertTrue(result.toString(), result.accepted)
        assertNotNull(result.refinedTransform.projective)
        assertTrue(result.projectiveValidationP90 < 0.35f)
        assertTrue(result.acceptedStarCount >= 40)
        assertTrue(result.percentile90Residual < 0.35f)
        for ((x, y) in stars) {
            val actual = result.refinedTransform.mapOutputToSource(x, y); val expected = warp.map(x, y)
            assertTrue(hypot(actual.x - expected.x, actual.y - expected.y) < 0.15f)
        }
        assertFalse(refine(reference, render(candidateStars, amplitude = 0.2f)).accepted)
        assertFalse(refine(reference, render(candidateStars, sigma = 2.3f)).accepted)
    }

    @Test fun acceptedRigidRefinementDoesNotInvokeProjectiveFallback() {
        val candidateStars = stars.map { (x, y) -> prior.mapOutputToSource(x, y).let { it.x to it.y } }
        val result = refine(render(stars), render(candidateStars))
        assertTrue(result.rejectionReason, result.accepted)
        assertFalse(result.projectiveRefinementAttempted)
        assertNull(result.refinedTransform.projective)
    }

    @Test fun acceptedRigidModelCanImproveAcrossTheFieldOnIndependentStars() {
        val diagnostics = mutableListOf<String>()
        val reference = render(stars)
        val candidateStars = stars.map { (x, y) ->
            val a = prior.mapOutputToSource(x, y); val b = warp.map(x, y)
            (a.x + (b.x - a.x) * 0.35f) to (a.y + (b.y - a.y) * 0.35f)
        }
        val result = StellarCentroidFrameRefiner(rotationDiagnostic = diagnostics::add).refine(
            "accepted-perspective", false, reference, render(candidateStars),
            prior.copy(dx = prior.dx + 0.7f, dy = prior.dy + 0.4f, rotationRadians = prior.rotationRadians + 0.0015f),
            prior.copy(dx = prior.dx + 0.7f, dy = prior.dy + 0.4f, rotationRadians = prior.rotationRadians + 0.0015f),
            stars.map { (x, y) -> patch(x, y) }, 0f, 0f, 0f, 1f)
        assertTrue("${result.rejectionReason}\n${diagnostics.joinToString("\n")}", diagnostics.any {
            "fullResolutionProjectiveComparison" in it && "selected=true" in it
        })
        assertTrue(result.rejectionReason, result.accepted)
        assertNotNull(result.refinedTransform.projective)
        stars.zip(candidateStars).forEach { (referenceStar, expected) ->
            val actual = result.refinedTransform.mapOutputToSource(referenceStar.first, referenceStar.second)
            assertTrue(hypot(actual.x - expected.first, actual.y - expected.second) < 0.15f)
        }
    }

    @Test fun heldOutComparisonKeepsAccurateRigidMotionAndRequiresAMaterialGain() {
        val perspective = checkNotNull(register(matches()))
        assertTrue(perspective.compareTo(prior).materiallyImproves)
        assertFalse(perspective.compareTo(perspective.transform).materiallyImproves)
        val rigid = ProjectiveTransform(kotlin.math.cos(prior.rotationRadians.toDouble()),
            -kotlin.math.sin(prior.rotationRadians.toDouble()), prior.dx.toDouble(),
            kotlin.math.sin(prior.rotationRadians.toDouble()), kotlin.math.cos(prior.rotationRadians.toDouble()),
            prior.dy.toDouble(), 0.0, 0.0)
        val noisyRigid = checkNotNull(register(matches(model = rigid)))
        assertFalse(noisyRigid.compareTo(prior).materiallyImproves)
    }

    @Test fun comparisonCannotDiscardStarsThatWereAlignedByThePrimaryModel() {
        val mixed = matches().mapIndexed { index, match ->
            if (index % 6 != 0) match else {
                val point = prior.mapOutputToSource(match.reference.x, match.reference.y)
                match.copy(candidate = measurement(point.x, point.y))
            }
        }
        val projective = register(mixed)
        assertFalse(projective?.compareTo(prior)?.materiallyImproves == true)
    }

    @Test fun preparedSamplingInverseCompositionAndResolutionScalingUseTheSameMatrix() {
        val transform = prior.copy(projective = warp)
        for ((x, y) in stars) {
            val mapped = transform.mapOutputToSource(x, y)
            for (preserveOrder in listOf(false, true)) {
                val prepared = PreparedReferenceToSourceTransform(transform, preserveOrder)
                assertEquals(mapped.x, prepared.sourceX(x, y), 0f)
                assertEquals(mapped.y, prepared.sourceY(x, y), 0f)
            }
            val restored = transform.inverse().mapSourceToOutput(mapped.x, mapped.y)
            assertEquals(x, restored.x, 0.0002f); assertEquals(y, restored.y, 0.0002f)
            val inverse = transform.inverse().asReferenceToSourceTransform().mapOutputToSource(mapped.x, mapped.y)
            assertEquals(x, inverse.x, 0.0002f); assertEquals(y, inverse.y, 0.0002f)
            val twice = transform.appliedTwice().mapOutputToSource(x, y)
            val direct = transform.mapOutputToSource(mapped.x, mapped.y)
            assertEquals(direct.x, twice.x, 0.0003f); assertEquals(direct.y, twice.y, 0.0003f)
            val full = transform.scaledToFullResolution(1.5f, 1.5f).mapOutputToSource(x * 1.5f, y * 1.5f)
            assertEquals(mapped.x * 1.5f, full.x, 0.0003f); assertEquals(mapped.y * 1.5f, full.y, 0.0003f)
            val registration = RegistrationResult(0f, 0f, 0f, 1f, 48, 48, 48, 0.1f, 1f, true, null)
                .withTransform(transform)
            val scaledRegistration = registration.scaledToFullResolution(1.5f, 1.5f)
            assertFalse(scaledRegistration.scaleFixed)
            val roundTrip = scaledRegistration.scaledToAnalysisResolution(1.5f, 1.5f)
                .referenceToSourceTransform().mapOutputToSource(x, y)
            assertEquals(mapped.x, roundTrip.x, 0.0003f); assertEquals(mapped.y, roundTrip.y, 0.0003f)
        }
    }

    @Test fun sequenceValidationMeasuresPerspectiveMotionEvenWithZeroRollAndOriginShift() {
        val values = (0..2).map { index ->
            val matrix = ProjectiveTransform(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, index * 1e-6, 0.0)
            OrderedRegistration("frame-$index", index, index == 0,
                RegistrationResult(0f, 0f, 0f, 1f, 48, 48, 48, 0.1f, 1f, true, null, projective = matrix))
        }
        val result = TransformSequenceValidator().validate(values, comparisonPoint = TransformPoint(500f, 500f))
        assertTrue(result.registrations.all { it.registration.isReliable })
        assertTrue(result.registrations.last().registration.neighborTransformDelta > 0.3f)
        val registration = values.last().registration
        assertEquals(registration.projective, registration.withTransform(registration.referenceToSourceTransform()).projective)
        assertEquals(registration.projective?.coefficients(), registration.toReport("matrix.jpg").projectiveMatrix)
    }

    @Test fun integrationSamplesTheProjectiveCoordinatesInOriginalResolution() = runBlocking {
        val source = IntArrayPixelSource(128, 128, IntArray(128 * 128) { i ->
            val x = i % 128; val y = i / 128
            0xff000000.toInt() or (x * 2 shl 16) or (y * 2 shl 8) or (x + y)
        })
        val matrix = ProjectiveTransform(1.001, 0.001, 0.3, 0.002, 1.001, -0.2, 1e-5, -1e-5)
        val registration = RegistrationResult(0f, 0f, 0f, 1f, 48, 48, 48, 0.1f, 1f, true, null, projective = matrix)
        val output = LongArray(64 * 64)
        LinearWeightedIntegrator().integrate(64, 64,
            listOf(WeightedIntegrationFrame("perspective", source, registration, 1f)),
            maximumWorkingMemoryBytes = 4L * 1024 * 1024, openSource = { it }, allowRobustClipping = false,
            writeLinearTile = { tile, pixels ->
                for (y in 0 until tile.height) pixels.copyInto(output,
                    (tile.top + y) * 64 + tile.left, y * tile.width, (y + 1) * tile.width)
            })
        for (y in 10..50 step 10) for (x in 10..50 step 10) {
            val w = 1 + 1e-5 * x - 1e-5 * y
            val sx = ((1.001 * x + 0.001 * y + 0.3) / w).toFloat()
            val sy = ((0.002 * x + 1.001 * y - 0.2) / w).toFloat()
            val x0 = kotlin.math.floor(sx).toInt()
            val y0 = kotlin.math.floor(sy).toInt()
            val fx = (sx - x0).toDouble()
            val fy = (sy - y0).toDouble()
            // Independent four-tap light sum at the analytically projected position.
            // Decoding the interpolated byte-gradient would reproduce the old gamma error.
            fun expectedLight(code: (Int, Int) -> Int): Double =
                (0..1).sumOf { oy -> (0..1).sumOf { ox ->
                    SrgbTransfer.srgbToLinear(code(x0 + ox, y0 + oy) / 255.0) *
                        (if (ox == 0) 1 - fx else fx) * (if (oy == 0) 1 - fy else fy)
                } }
            val actual = output[y * 64 + x]
            val tolerance = .5 / 65535 + .0000001
            assertEquals(expectedLight { px, _ -> px * 2 }, LinearRgb16.red(actual).toDouble(), tolerance)
            assertEquals(expectedLight { _, py -> py * 2 }, LinearRgb16.green(actual).toDouble(), tolerance)
            assertEquals(expectedLight { px, py -> px + py }, LinearRgb16.blue(actual).toDouble(), tolerance)
        }
    }

    private fun register(matches: List<StellarCentroidMatch>) =
        ProjectiveStarRegistrar().register(matches, prior, WIDTH, HEIGHT, 5f)

    private fun matches(points: List<Pair<Float, Float>> = stars, model: ProjectiveTransform = warp) =
        points.mapIndexed { index, (x, y) ->
            val reference = measurement(x, y)
            val mapped = model.map(x, y)
            // Independent deterministic measurement noise, not a perfectly interpolable field.
            val candidate = measurement(mapped.x + ((index * 17 % 7) - 3) * 0.008f,
                mapped.y + ((index * 23 % 11) - 5) * 0.006f)
            StellarCentroidMatch(patch(x, y), reference, candidate,
                candidate.x - x, candidate.y - y, 0f, true, null, 0.9f, 1f)
        }

    private fun measurement(x: Float, y: Float) = StellarCentroidMeasurement(
        x, y, 0.03f, 0.002f, 0.6f, 3f, 3f, 3f, 0f, 30f, 0f, 0.1f, 0.9f
    )

    private fun patch(x: Float, y: Float) = FullResolutionStarPatch(
        x, y, 0.9f, 0.55f, 3f, 0f,
        (y / HEIGHT * 3).toInt().coerceIn(0, 2) * 3 + (x / WIDTH * 3).toInt().coerceIn(0, 2),
        TemporalMotionCluster.COHERENT_MOVING_SKY, 1f
    )

    private fun refine(reference: IntArrayPixelSource, candidate: IntArrayPixelSource) =
        StellarCentroidFrameRefiner().refine("synthetic-perspective", false, reference, candidate,
            prior, prior, stars.map { (x, y) -> patch(x, y) }, 0f, 0f, 0f, 1f)

    private fun render(points: List<Pair<Float, Float>>, amplitude: Float = 0.55f, sigma: Float = 1.35f): IntArrayPixelSource {
        val values = FloatArray(WIDTH * HEIGHT) { 0.03f }
        for ((x, y) in points) for (dy in -9..9) for (dx in -9..9) {
            val ix = x.toInt() + dx; val iy = y.toInt() + dy
            if (ix !in 0 until WIDTH || iy !in 0 until HEIGHT) continue
            val rx = ix - x; val ry = iy - y
            values[iy * WIDTH + ix] += amplitude * exp((-(rx * rx + ry * ry) / (2 * sigma * sigma)).toDouble()).toFloat()
        }
        return IntArrayPixelSource(WIDTH, HEIGHT, IntArray(values.size) { index ->
            val c = (values[index].coerceIn(0f, 1f) * 255f).toInt()
            0xff000000.toInt() or (c shl 16) or (c shl 8) or c
        })
    }

    companion object { private const val WIDTH = 1000; private const val HEIGHT = 1400 }
}
