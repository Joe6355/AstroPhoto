package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import org.junit.Assert.*
import org.junit.Test

class IrregularCaptureOrdinalTest {
    @Test fun aLongGapInSelectedFramesDoesNotTurnConstantVelocityIntoATranslationJump() {
        val entries = entries { index -> 0.7f * (index - 2) to 0f }
        val prior = ExpectedSequenceMotionModel(0.7f, 0f, 2, 0.01f, true, 1f)
        val result = TransformSequenceValidator().validate(entries, prior)
        assertTrue(result.rejectedFrameIds.toString(), result.rejectedFrameIds.isEmpty())
        assertTrue(result.score.toString(), result.score > 0.999f)
    }

    @Test fun aLongGapInSelectedFramesDoesNotTurnConstantAngularVelocityIntoARotationJump() {
        val entries = entries { index -> 0f to 0.0018f * (index - 2) }
        val result = TransformSequenceValidator().validate(entries)
        assertTrue(result.rejectedFrameIds.toString(), result.rejectedFrameIds.isEmpty())
    }

    private fun entries(motion: (Int) -> Pair<Float, Float>) = listOf(0, 1, 2, 3, 20).map { index ->
        val (dx, theta) = motion(index)
        OrderedRegistration("f$index", index, index == 2,
            RegistrationResult(dx, 0f, theta, 1f, 48, 48, 48, 0.05f,
                0.95f, true, null, referenceStars = 48, scaleFixed = true))
    }
}
