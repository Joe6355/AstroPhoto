package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.completion.automaticProfileSuccessStatus
import com.example.astrophoto.processing.jpeg.v2.quality.withExperimentalSafeFallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegProfileOutcomeTest {
    @Test fun oneOfThirtyIsFailureAndCannotCreateProfileOutput() {
        val error = runCatching {
            requireMinimumRegisteredFrames(1, 30, AstroProcessingProfile.DEEP_SKY)
        }.exceptionOrNull()

        assertTrue(error is JpegProfileProcessingException)
        val profileError = error as JpegProfileProcessingException
        assertEquals(
            JpegProfileProcessingOutcome.FAILED_REGISTRATION,
            profileError.outcome
        )
        assertTrue(error.message.orEmpty().contains("1/30"))
        assertTrue(error.message.orEmpty().contains("Файл профиля не создан"))
        assertTrue(profileError.canContinueWithUserApproval)
        assertEquals(1, profileError.acceptedFrames)
        assertEquals(30, profileError.totalFrames)
    }

    @Test fun userApprovalAllowsProcessingWithOneAcceptedFrame() {
        requireMinimumRegisteredFrames(
            acceptedFrames = 1,
            totalFrames = 30,
            profile = AstroProcessingProfile.DEEP_SKY,
            userApprovedInsufficientFrames = true
        )
    }

    @Test fun userApprovalCannotContinueWithoutAnyAcceptedFrame() {
        val error = runCatching {
            requireMinimumRegisteredFrames(
                acceptedFrames = 0,
                totalFrames = 30,
                profile = AstroProcessingProfile.DEEP_SKY,
                userApprovedInsufficientFrames = true
            )
        }.exceptionOrNull() as JpegProfileProcessingException

        assertTrue(!error.canContinueWithUserApproval)
    }

    @Test fun wrappedContinuableFailureIsRecoveredForConfirmationDialog() {
        val cause = runCatching {
            requireMinimumRegisteredFrames(1, 30, AstroProcessingProfile.DEEP_SKY)
        }.exceptionOrNull()!!
        val wrapped = IllegalStateException("stage failed", cause)

        assertEquals(cause, wrapped.userContinuableProfileFailure())
    }

    @Test fun referenceOnlySelectionIsFailure() {
        val error = runCatching {
            jpegProfileOutputPlan(
                AstroProcessingProfile.URBAN_SKY,
                ResultCandidateType.REFERENCE,
                4,
                30,
                "clean_stack_invalid"
            )
        }.exceptionOrNull()

        assertTrue(error is JpegProfileProcessingException)
        assertEquals(
            JpegProfileProcessingOutcome.FAILED_REGISTRATION,
            (error as JpegProfileProcessingException).outcome
        )
    }

    @Test fun cleanFallbackUsesRecoveredStarsName() {
        val plan = jpegProfileOutputPlan(
            AstroProcessingProfile.URBAN_SKY_STRONG,
            ResultCandidateType.CLEAN_STACK,
            20,
            30,
            "processed_rejected"
        )

        assertEquals(JpegProfileProcessingOutcome.CLEAN_FALLBACK, plan.outcome)
        assertEquals("RecoveredStars", plan.filePrefix)
    }

    @Test fun experimentalOutputIsIsolatedAndCleanFallbackKeepsRecoveredStarsName() {
        val processed = jpegProfileOutputPlan(
            AstroProcessingProfile.EXPERIMENTAL_STARS,
            ResultCandidateType.PROCESSED,
            20,
            30,
            null
        )
        val fallback = jpegProfileOutputPlan(
            AstroProcessingProfile.EXPERIMENTAL_STARS,
            ResultCandidateType.CLEAN_STACK,
            20,
            30,
            "experimental_validation_failed"
        )

        assertEquals(JpegProfileProcessingOutcome.PROCESSED, processed.outcome)
        assertEquals("ExperimentalStars", processed.filePrefix)
        assertEquals(JpegProfileProcessingOutcome.CLEAN_FALLBACK, fallback.outcome)
        assertEquals("RecoveredStars", fallback.filePrefix)
    }

    @Test fun experimentalSaveFailurePublishesSafeResult() = runBlocking {
        var primaryFailure = ""
        val result = withExperimentalSafeFallback(
            enabled = true,
            primary = { throw IllegalStateException("simulated_save_failure") },
            safeFallback = { error ->
                primaryFailure = error.message.orEmpty()
                "RecoveredStars_001.png"
            }
        )

        assertEquals("RecoveredStars_001.png", result)
        assertEquals("simulated_save_failure", primaryFailure)
    }

    @Test fun experimentalCancellationNeverPublishesFallback() = runBlocking {
        var fallbackCalled = false
        val error = runCatching {
            withExperimentalSafeFallback(
                enabled = true,
                primary = { throw CancellationException("cancelled") },
                safeFallback = {
                    fallbackCalled = true
                    "unexpected"
                }
            )
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertTrue(!fallbackCalled)
    }

    @Test fun completionStatusShowsFramesCandidatePostprocessingAndFallbackReason() {
        val status = automaticProfileSuccessStatus(
            JpegStackResult(
                fileName = "RecoveredStars_001.png",
                displayPath = "RecoveredStars_001.png",
                contentUri = null,
                filePath = null,
                frameCount = 20,
                sessionInfoUpdated = true,
                selectedResultType = ResultCandidateType.CLEAN_STACK.name,
                fallbackUsed = true,
                fallbackReason = "processed_rejected",
                postProcessingExecuted = true
            ),
            30,
            "чистый стек"
        )

        assertTrue(status.contains("Использовано: 20/30"))
        assertTrue(status.contains("Выбран результат: чистый стек"))
        assertTrue(status.contains("Postprocessing: запущен"))
        assertTrue(status.contains("processed_rejected"))
    }
}
