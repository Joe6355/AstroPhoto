package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.ProjectiveTransform
import com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class ProjectiveSearchSupportTest {
    private val prior = ReferenceToSourceTransform(7f, 6f)
    private val points = (0 until 6).flatMap { row -> (0 until 8).map { column ->
        (350f + column * 106f) to (450f + row * 202f)
    } }

    @Test fun localSearchBoundsMeasuredStarsWithoutTreatingEmptyCornersAsMeasurements() {
        val model = rotation(0.006)
        val pairs = matches(model)
        assertTrue(pairs.all { match ->
            val expected = prior.mapOutputToSource(match.reference.x, match.reference.y)
            kotlin.math.abs(match.candidate.x - expected.x) < 4f &&
                kotlin.math.abs(match.candidate.y - expected.y) < 4f
        })
        assertTrue(kotlin.math.abs(model.map(0f, 0f).x - prior.dx) > 4f)
        val result = ProjectiveStarRegistrar().register(pairs, prior, WIDTH, HEIGHT, 4f)
        assertNotNull("A measured, cross-validated field rotation is not a local translation at an empty corner", result)
        assertTrue(checkNotNull(result).validationP90 < 0.1f)
        for ((x, y) in listOf(0f to 0f, 1439f to 1919f, 720f to 960f)) {
            val expected = model.map(x, y)
            val actual = result.transform.mapOutputToSource(x, y)
            assertTrue(kotlin.math.hypot(actual.x - expected.x, actual.y - expected.y) < 0.1f)
        }
    }

    @Test fun measuredCorrectionsOutsideLocalSearchRemainRejected() {
        assertNull(ProjectiveStarRegistrar().register(matches(rotation(0.010)), prior, WIDTH, HEIGHT, 4f))
    }

    @Test fun centerTranslationOutsideLocalSearchRemainsRejected() {
        val model = ProjectiveTransform(1.0, 0.0, 12.0, 0.0, 1.0, 6.0, 0.0, 0.0)
        assertNull(ProjectiveStarRegistrar().register(matches(model), prior, WIDTH, HEIGHT, 4f))
    }

    @Test fun cornerJacobianStillRejectsExcessiveZoom() {
        val model = ProjectiveTransform(1.03, 0.0, 7.0 - 0.03 * 720,
            0.0, 1.03, 6.0 - 0.03 * 960, 0.0, 0.0)
        assertNull(ProjectiveStarRegistrar().register(matches(model), prior, WIDTH, HEIGHT, 20f))
    }

    private fun rotation(angle: Double): ProjectiveTransform {
        val c = cos(angle); val s = sin(angle)
        return ProjectiveTransform(c, -s, 7 + (1 - c) * 720 + s * 960,
            s, c, 6 - s * 720 + (1 - c) * 960, 0.0, 0.0)
    }

    private fun matches(model: ProjectiveTransform) = points.mapIndexed { index, (x, y) ->
        val predicted = model.map(x, y)
        val reference = measurement(x, y)
        val candidate = measurement(predicted.x + (index % 3 - 1) * 0.005f,
            predicted.y + (index % 5 - 2) * 0.004f)
        val patch = FullResolutionStarPatch(x, y, 0.9f, 0.55f, 3f, 0f,
            (y / HEIGHT * 3).toInt() * 3 + (x / WIDTH * 3).toInt(),
            TemporalMotionCluster.COHERENT_MOVING_SKY, 1f)
        StellarCentroidMatch(patch, reference, candidate, candidate.x - x, candidate.y - y,
            0f, true, null, 0.9f, 1f)
    }

    private fun measurement(x: Float, y: Float) = StellarCentroidMeasurement(
        x, y, 0.03f, 0.002f, 0.6f, 3f, 3f, 3f, 0f, 30f, 0f, 0.1f, 0.9f
    )

    companion object { private const val WIDTH = 1440; private const val HEIGHT = 1920 }
}
