package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.*
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CaptureTimedRegistrationTest {
    private val times = listOf(0f, 1f, 2f, 3f, 12f, 13f, 14f, 15f)

    @Test fun actualElapsedTimeRecoversCoherentTracksAndTheNonzeroSequenceModelAcrossAPause() {
        val random = Random(316)
        val stars = (0 until 48).map { index ->
            DetectedStar(60f + (index % 8) * 112f + random.nextFloat() * 10f,
                60f + (index / 8) * 110f + random.nextFloat() * 10f,
                100f, 4f, 20f, 1.5f, 0.1f, 0.95f)
        }
        val frames = times.mapIndexed { index, time -> TemporalFeatureFrame("f$index", index,
            stars.map { it.copy(x = it.x - 0.8f * time, y = it.y + 0.65f * time) }, time) }
        val result = SequenceAwareRegistrationEngine().register(frames, "f3", 960, 720)
        assertTrue(result.model.toString(), result.model.motionObservable)
        assertTrue(result.trackAnalysis.toString(), result.trackAnalysis.movingTrackCount >= 40)
        assertEquals(-0.8f, result.model.velocityX, 0.01f)
        assertEquals(0.65f, result.model.velocityY, 0.01f)
        assertEquals(times.size, result.registrations.count { it.value.isReliable })
        times.forEachIndexed { index, time ->
            val predicted = result.model.predictedTransform(index)
            assertEquals(-0.8f * (time - times[3]), predicted.dx, 0.02f)
            assertEquals(0.65f * (time - times[3]), predicted.dy, 0.02f)
        }
        val ordinal = SequenceAwareRegistrationEngine().register(
            frames.map { it.copy(captureTimeCoordinate = null) }, "f3", 960, 720)
        val wrongPrediction = ordinal.model.predictedTransform(7)
        assertTrue(hypot(wrongPrediction.dx + 0.8f * 12f, wrongPrediction.dy - 0.65f * 12f) > 2f)
    }

    @Test fun sequenceValidationUsesElapsedTimeForTranslationAndRotationWithoutRewritingTransforms() {
        val entries = times.mapIndexed { index, time -> entry(index, time) }
        val result = validate(entries)
        assertTrue(result.rejectedFrameIds.toString(), result.rejectedFrameIds.isEmpty())
        assertTrue(result.score.toString(), result.score > 0.99f)
        entries.zip(result.registrations).forEach { (original, checked) ->
            assertEquals(original.registration.dx, checked.registration.dx, 0f)
            assertEquals(original.registration.dy, checked.registration.dy, 0f)
            assertEquals(original.registration.rotationRadians, checked.registration.rotationRadians, 0f)
        }
    }

    @Test fun selectedFramesCanSkipDifferentNumbersOfIntervals() {
        val entries = listOf(0, 2, 3, 4, 7).map { entry(it, times[it]) }
        val result = validate(entries)
        assertTrue(result.rejectedFrameIds.toString(), result.rejectedFrameIds.isEmpty())
        assertTrue(result.score.toString(), result.score > 0.99f)
    }

    @Test fun ordinalFallbackAlsoMeasuresRatesAcrossMissingFrameNumbers() {
        val entries = listOf(0, 1, 2, 12, 14, 17).map { entry(it, it.toFloat()) }
        val result = TransformSequenceValidator().validate(entries, comparisonPoint = TransformPoint(480f, 360f))
        assertTrue(result.rejectedFrameIds.toString(), result.rejectedFrameIds.isEmpty())
        assertTrue(result.score.toString(), result.score > 0.99f)
    }

    @Test fun actualTranslationJumpStillFailsWithCorrectCaptureTimes() {
        val result = validate(times.mapIndexed { index, time -> entry(index, time).let {
            if (index == 4) it.copy(registration = it.registration.copy(dx = it.registration.dx + 9f)) else it
        } })
        assertFalse(result.registrations.single { it.frameId == "f4" }.registration.isReliable)
        assertEquals("transform_sequence_motion_prior_disagreement",
            result.registrations.single { it.frameId == "f4" }.registration.rejectionReason)
    }

    @Test fun actualRotationJumpStillFailsWhenCenterTranslationAgrees() {
        val result = validate(times.mapIndexed { index, time -> entry(index, time,
            rotationJump = if (index == 4) 0.02f else 0f) })
        assertFalse(result.registrations.single { it.frameId == "f4" }.registration.isReliable)
        assertEquals("transform_sequence_rotation_jump",
            result.registrations.single { it.frameId == "f4" }.registration.rejectionReason)
    }

    private fun validate(entries: List<OrderedRegistration>) = TransformSequenceValidator().validate(entries,
        ExpectedSequenceMotionModel(0.7f, -0.45f, 3, 0.05f, true, 1f,
            times.mapIndexed { index, time -> index to time }.toMap()), TransformPoint(480f, 360f))

    private fun entry(index: Int, time: Float, rotationJump: Float = 0f): OrderedRegistration {
        val delta = time - times[3]
        val theta = delta * 0.0018f + rotationJump
        val dx = delta * 0.7f + 480f - (cos(theta) * 480f - sin(theta) * 360f)
        val dy = delta * -0.45f + 360f - (sin(theta) * 480f + cos(theta) * 360f)
        val registration = RegistrationResult(dx, dy, theta, 1f, 48, 48, 48, 0.05f,
            0.95f, true, null, referenceStars = 48, scaleFixed = true, rotationAllowed = theta != 0f)
        return OrderedRegistration("f$index", index, index == 3, registration)
    }
}
