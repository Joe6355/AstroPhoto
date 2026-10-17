package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorFrameObservation
import com.joe6355.astrophoto.processing.jpeg.v2.model.*
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class ProfileRotatingSequenceTest {
    @Test
    fun profileKeepsSmoothCenterMotionWhenSomeFramesUseRotationAndOthersTranslation() {
        val fixture = fixture { index -> if (index == 1) 0f else (index - 2) * 0.002f }
        val result = prepareProvisionalProfileRegistration(fixture.selection, fixture.diagnostics, fixture.indices)
        assertEquals(result.allRegistrationsByKey.toString(), 5, result.acceptedFrames.size)
        assertTrue(result.sequenceScore.toString(), result.sequenceScore > 0.99f)
        result.acceptedFrames.forEach { frame ->
            val original = fixture.diagnostics.registrations.getValue(frame.frame.key)
            assertEquals(original.rotationRadians, frame.registration.rotationRadians, 0f)
            assertEquals(original.dx * 2f, frame.registration.dx, 0f)
            assertEquals(original.dy * 2f, frame.registration.dy, 0f)
        }
    }

    @Test
    fun profileStillRejectsARealCenterTranslationJump() {
        val fixture = fixture(centerJump = 9f) { index -> (index - 2) * 0.002f }
        val result = prepareProvisionalProfileRegistration(fixture.selection, fixture.diagnostics, fixture.indices)
        assertFalse(result.allRegistrationsByKey.getValue("f3").isReliable)
        assertEquals("transform_sequence_motion_prior_disagreement",
            result.allRegistrationsByKey.getValue("f3").rejectionReason)
        assertTrue(result.allRegistrationsByKey.getValue("f2").isReliable)
    }

    @Test
    fun profileStillRejectsAnIsolatedRotationJumpWithTheSameCenterMotion() {
        val fixture = fixture { index ->
            (index - 2) * 0.002f + if (index == 3) 0.02f else 0f
        }
        val result = prepareProvisionalProfileRegistration(fixture.selection, fixture.diagnostics, fixture.indices)
        assertFalse(result.allRegistrationsByKey.getValue("f3").isReliable)
        assertEquals("transform_sequence_rotation_jump",
            result.allRegistrationsByKey.getValue("f3").rejectionReason)
    }

    private fun fixture(centerJump: Float = 0f, angle: (Int) -> Float): Fixture {
        val stars = listOf(60f to 80f, 350f to 80f, 650f to 80f,
            60f to 800f, 350f to 800f, 650f to 800f).map { (x, y) ->
            DetectedStar(x, y, 100f, 4f, 20f, 1.5f, 0.1f, 0.9f)
        }
        val analyzed = (0..4).map { index ->
            val analysis = FrameAnalysis(id = "f$index", fileName = "$index.jpg", width = 720, height = 960,
                stars = stars, reliableStarCount = stars.size, medianStarContrast = 20f,
                medianStarWidth = 2f, medianStarEllipticity = 0.2f, backgroundNoise = 2f,
                clippingPercent = 0f, exposureSuitability = 0.8f, decodeValid = true,
                alignmentSuitability = 0.8f, skyMaskConfidence = 0.9f,
                skyMaskUsedFallback = false, backgroundLevel = 20f)
            ProfileAnalyzedFrame(SessionFrame(analysis.id, analysis.fileName, SessionFrameCategory.LIGHTS_JPEG,
                100L, 200L, analysis.fileName, null, null), analysis,
                SkyMaskResult(SkyMask.full(720, 960), 1f, false),
                PersistentSensorFrameObservation(analysis.id, index, 720, 960, emptyList()))
        }
        val indices = analyzed.mapIndexed { index, frame -> frame.frame.key to index }.toMap()
        val dimensions = indices.mapValues { 1440 to 1920 }
        val selected = ProfileFrameSelectionCoordinator.prepare(analyzed, analyzed.map { it.frame },
            indices, dimensions, 30)
        val selection = ProfileFrameSelectionCoordinator.withReference(selected, "f2", dimensions)
        val baseline = SequenceAwareRegistrationEngine().register(
            analyzed.map { TemporalFeatureFrame(it.frame.key, indices.getValue(it.frame.key), stars) },
            "f2", 720, 960)
        val registrations = indices.mapValues { (key, index) ->
            val theta = angle(index)
            val delta = (index - 2).toFloat()
            // Express rotation about the image center in the canonical origin-based transform.
            val dx = delta * 2.5f + 360f - (cos(theta) * 360f - sin(theta) * 480f) +
                if (index == 3) centerJump else 0f
            val dy = delta * 2f + 480f - (sin(theta) * 360f + cos(theta) * 480f)
            baseline.registrations.getValue(key).copy(dx = dx, dy = dy, rotationRadians = theta,
                isReliable = true, rejectionReason = null, confidence = 0.95f,
                rotationAllowed = theta != 0f, scaleFixed = true,
                registrationModel = if (theta != 0f) "FIXED_SCALE_RIGID" else "SEQUENCE_TRANSLATION")
        }
        val diagnostics = baseline.copy(registrations = registrations,
            model = baseline.model.copy(velocityX = 2.5f, velocityY = 2f,
                referenceIndex = 2, residual = 0.1f, motionObservable = true, score = 0.95f))
        return Fixture(selection, diagnostics, indices)
    }

    private data class Fixture(val selection: ProfileFrameSelectionPreparation,
        val diagnostics: SequenceAwareRegistrationDiagnostics, val indices: Map<String, Int>)
}
