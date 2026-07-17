package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.completion.automaticProfileSuccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegProfileOutcomeTest {
    @Test fun oneOfThirtyIsFailureAndCannotCreateProfileOutput() {
        val error = runCatching {
            requireMinimumRegisteredFrames(1, 30, AstroProcessingProfile.DEEP_SKY)
        }.exceptionOrNull()

        assertTrue(error is JpegProfileProcessingException)
        assertEquals(
            JpegProfileProcessingOutcome.FAILED_REGISTRATION,
            (error as JpegProfileProcessingException).outcome
        )
        assertTrue(error.message.orEmpty().contains("1/30"))
        assertTrue(error.message.orEmpty().contains("Файл профиля не создан"))
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
