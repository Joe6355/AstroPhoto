package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import org.junit.Assert.*
import org.junit.Test

class ProfileRegistrationInputAdviceTest {
    private fun frame(background: Float = 0f, stars: Int = 2): FrameAnalysis =
        FrameAnalysis.invalid("frame", "frame.jpg").copy(
            width = 720, height = 960, decodeValid = true, backgroundLevel = background,
            backgroundNoise = 1.5f, clippingPercent = 0f,
            stars = List(stars) { DetectedStar(20f + it * 20, 30f, 100f, background, 20f, 2f, 0f, .8f) },
            reliableStarCount = stars
        )

    @Test fun predominantlyDarkSparseSeriesGetsActionableInputAdvice() {
        val advice = registrationInputAdvice(List(4) { frame() } + frame(20f, 25))
        assertTrue(advice.orEmpty().contains("кадры очень тёмные"))
        assertTrue(advice.orEmpty().contains("Проверьте исходники и экспозицию"))
    }

    @Test fun aMixedSeriesDoesNotGetMislabelledAsMostlyDark() {
        assertNull(registrationInputAdvice(List(3) { frame() } + List(2) { frame(20f, 25) }))
    }

    @Test fun aDarkSkyWithEnoughStarsDoesNotGetTheSparseInputAdvice() {
        assertNull(registrationInputAdvice(List(5) { frame(0f, 20) }))
    }

    @Test fun brightSparseOrUnknownInputsDoNotGetTheDarkInputAdvice() {
        assertNull(registrationInputAdvice(List(5) { frame(20f, 2) }))
        assertNull(registrationInputAdvice(List(5) { frame(Float.NaN, 2) }))
    }

    @Test fun unreadableOrTooFewInputsAreNotReportedAsDarkPhotos() {
        assertNull(registrationInputAdvice(List(5) { FrameAnalysis.invalid("bad", "bad.jpg") }))
        assertNull(registrationInputAdvice(List(2) { frame() }))
        assertNull(registrationInputAdvice(List(2) { frame() } + List(5) { FrameAnalysis.invalid("bad", "bad.jpg") }))
    }

    @Test fun failedRegistrationKeepsItsCountsAndAddsTheExplanation() {
        val advice = registrationInputAdvice(List(30) { frame() })
        val error = runCatching {
            requireMinimumRegisteredFrames(1, 20, AstroProcessingProfile.DEEP_SKY, inputAdvice = advice)
        }.exceptionOrNull() as JpegProfileProcessingException
        assertEquals(JpegProfileProcessingOutcome.FAILED_REGISTRATION, error.outcome)
        assertEquals(1, error.acceptedFrames)
        assertEquals(20, error.totalFrames)
        assertEquals(4, error.minimumFrames)
        assertTrue(error.canContinueWithUserApproval)
        assertTrue(error.message.orEmpty().contains("использовано 1/20"))
        assertTrue(error.message.orEmpty().contains("Файл профиля не создан"))
        assertTrue(error.message.orEmpty().contains(requireNotNull(advice)))
        assertEquals(advice, error.inputAdvice)
    }

    @Test fun validRegistrationAndExplicitUserApprovalKeepTheirPreviousBehaviour() {
        val advice = registrationInputAdvice(List(5) { frame() })
        requireMinimumRegisteredFrames(4, 20, AstroProcessingProfile.DEEP_SKY, inputAdvice = advice)
        requireMinimumRegisteredFrames(1, 20, AstroProcessingProfile.DEEP_SKY,
            userApprovedInsufficientFrames = true, inputAdvice = advice)
    }

    @Test fun adviceCannotAllowProcessingWithoutAnAcceptedFrame() {
        val error = runCatching {
            requireMinimumRegisteredFrames(0, 20, AstroProcessingProfile.DEEP_SKY,
                userApprovedInsufficientFrames = true, inputAdvice = registrationInputAdvice(List(5) { frame() }))
        }.exceptionOrNull() as JpegProfileProcessingException
        assertFalse(error.canContinueWithUserApproval)
        assertEquals(0, error.acceptedFrames)
    }

    @Test fun continuationDialogRetainsAdviceAcrossTheWrappedFailure() {
        val advice = registrationInputAdvice(List(5) { frame() })
        val failed = runCatching {
            requireMinimumRegisteredFrames(1, 20, AstroProcessingProfile.DEEP_SKY, inputAdvice = advice)
        }.exceptionOrNull()!!
        val recovered = requireNotNull(IllegalStateException("stage failed", failed).userContinuableProfileFailure())
        val displayed = profileContinuationMessage(AstroProcessingProfile.DEEP_SKY,
            requireNotNull(recovered.acceptedFrames), requireNotNull(recovered.totalFrames),
            requireNotNull(recovered.minimumFrames), recovered.inputAdvice)
        assertTrue(displayed.contains("1 из 20"))
        assertTrue(displayed.contains("Отклонённые кадры не будут использованы"))
        assertTrue(displayed.contains(requireNotNull(advice)))
        assertFalse(profileContinuationMessage(AstroProcessingProfile.DEEP_SKY, 3, 20, 4, null)
            .contains("кадры очень тёмные"))
    }
}
