package com.example.astrophoto

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSequenceRejectedFrameFilterTest {
    @Test fun fixturePlanPreservesPerFrameRegistrationDecisionAndDiagnostics() {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        val plan = requireNotNull(
            planManualSequenceAlignment(
                frames = fixture.frames,
                outputWidth = fixture.frames.first().width,
                outputHeight = fixture.frames.first().height
            )
        )

        assertEquals(30, plan.frames.size)
        assertEquals(EXPECTED_REJECTED, plan.frames.filterNot { it.accepted }.map {
            it.originalFrameNumber
        })
        plan.frames.forEachIndexed { index, decision ->
            assertEquals(index, decision.originalFrameIndex)
            assertEquals("manual-${index + 1}", decision.frameId)
            assertNotNull(decision.registrationConfidence)
            if (!decision.accepted) assertTrue(decision.rejectionReason?.isNotBlank() == true)
        }
        assertTrue(plan.frames[plan.referenceFrameIndex].accepted)
        assertTrue(plan.frames[plan.referenceFrameIndex].shift.isZero)
    }

    @Test fun everyAlignedModeUsesOnlyAcceptedOriginalIndices() {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        val plan = requireNotNull(
            planManualSequenceAlignment(
                fixture.frames,
                fixture.frames.first().width,
                fixture.frames.first().height
            )
        )
        val expectedAccepted = (1..30).filterNot { it in EXPECTED_REJECTED }

        ManualAlignedStackMode.entries.forEach { mode ->
            val work = manualSequenceFrameWork((1..30).toList(), plan, mode)
            assertEquals("$mode original indices", expectedAccepted, work.map {
                it.originalFrameNumber
            })
            assertEquals("$mode compact indices", (1..expectedAccepted.size).toList(), work.map {
                it.compactFrameNumber
            })
            assertFalse("$mode leaked rejected frame", work.any {
                it.originalFrameNumber in EXPECTED_REJECTED
            })
            work.forEach { item ->
                assertEquals(plan.frames[item.originalFrameIndex].shift, item.shift)
            }
            assertTrue(work.any { it.originalFrameIndex == plan.referenceFrameIndex })

            val report = requireNotNull(
                manualSequenceIntegrationReport(
                    plan,
                    mode,
                    work.map { it.originalFrameIndex }
                )
            )
            assertEquals(30, report.inputFrameCount)
            assertEquals(22, report.acceptedFrameCount)
            assertEquals(EXPECTED_REJECTED, report.rejectedFrames.map {
                it.originalFrameNumber
            })
            assertEquals(expectedAccepted, report.integratedOriginalFrameIndices.map { it + 1 })
        }
    }

    @Test fun transformLookupNeverUsesCompactedIndex() {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        val plan = requireNotNull(
            planManualSequenceAlignment(
                fixture.frames,
                fixture.frames.first().width,
                fixture.frames.first().height
            )
        )
        val work = manualSequenceFrameWork(
            (1..30).toList(),
            plan,
            ManualAlignedStackMode.AVERAGE
        )
        val afterGap = work.first { it.originalFrameNumber == 25 }

        assertEquals(25, afterGap.originalFrameNumber)
        assertEquals(22, afterGap.compactFrameNumber)
        assertEquals(plan.frames[24].shift, afterGap.shift)
        assertNotEquals(plan.frames[21].shift, afterGap.shift)
        assertTrue(abs(checkNotNull(afterGap.shift).dx) >= abs(plan.frames[21].shift.dx))
    }

    @Test fun averageNormalizationUsesAcceptedCompactFrameCountOnly() {
        val plan = ManualSequenceAlignmentPlan(
            referenceFrameIndex = 0,
            frames = listOf(
                decision(0, accepted = true),
                decision(1, accepted = false),
                decision(2, accepted = true)
            ),
            modelScore = 0.8f,
            modelResidualPx = 0.2f,
            stationaryArtifactCount = 0
        )
        val inputs = listOf(gray(10), gray(255), gray(30))
        val work = manualSequenceFrameWork(inputs, plan, ManualAlignedStackMode.AVERAGE)
        val average = intArrayOf(work.first().value)
        val accumulator = ArgbAverageAccumulator(
            pixelCount = 1,
            maximumFrameCount = work.size
        )
        work.drop(1).forEach { item ->
            updateRunningAverageArgb(
                accumulator = accumulator,
                averagePixels = average,
                nextPixels = intArrayOf(item.value),
                frameNumber = item.compactFrameNumber,
                pixelCount = 1
            )
        }

        assertEquals(listOf(1, 3), work.map { it.originalFrameNumber })
        assertEquals(listOf(1, 2), work.map { it.compactFrameNumber })
        assertEquals(gray(20), average.single())
    }

    @Test fun tooFewAcceptedFramesFailWithoutRestoringRejectedFrames() {
        val plan = ManualSequenceAlignmentPlan(
            referenceFrameIndex = 0,
            frames = (0 until 8).map { index ->
                ManualSequenceFrameDecision(
                    originalFrameIndex = index,
                    frameId = "frame-$index",
                    accepted = index == 0,
                    rejectionReason = if (index == 0) null else "test_rejected",
                    shift = AlignmentShift(dx = index, dy = -index),
                    registrationResidualPx = if (index == 0) 0f else null,
                    registrationConfidence = if (index == 0) 1f else 0f
                )
            },
            modelScore = 0.8f,
            modelResidualPx = 0.2f,
            stationaryArtifactCount = 0
        )

        ManualAlignedStackMode.entries.forEach { mode ->
            val error = runCatching {
                manualSequenceFrameWork((0 until 8).toList(), plan, mode)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("Недостаточно принятых кадров"))
            assertTrue(error?.message.orEmpty().contains("не будут возвращены"))
        }

        val planningFailure = ManualSequenceAlignmentPlanningResult.InsufficientAcceptedFrames(
            acceptedFrameCount = 1,
            requiredFrameCount = 4,
            rejectedFrames = plan.frames.filterNot { it.accepted }.map {
                ManualSequenceRejectedFrame(
                    originalFrameIndex = it.originalFrameIndex,
                    frameId = it.frameId,
                    reason = checkNotNull(it.rejectionReason)
                )
            }
        )
        val planningError = runCatching {
            resolveManualSequencePlanningResult(planningFailure)
        }.exceptionOrNull()
        assertTrue(planningError is ManualSequenceInsufficientFramesException)
        assertTrue(planningError?.message.orEmpty().contains("1 из требуемых 4"))
    }

    private fun fixtureDirectory(): File {
        val resource = requireNotNull(
            requireNotNull(javaClass.classLoader)
                .getResource("jpeg-stage6/urban-window-30/manifest.properties")
        )
        return requireNotNull(File(resource.toURI()).parentFile)
    }

    private fun decision(index: Int, accepted: Boolean) = ManualSequenceFrameDecision(
        originalFrameIndex = index,
        frameId = "frame-$index",
        accepted = accepted,
        rejectionReason = if (accepted) null else "test_rejected",
        shift = AlignmentShift(dx = index, dy = -index),
        registrationResidualPx = if (accepted) 0.1f else null,
        registrationConfidence = if (accepted) 0.9f else 0f
    )

    private fun gray(value: Int): Int =
        0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

    companion object {
        private val EXPECTED_REJECTED = listOf(22, 23, 24, 26, 27, 28, 29, 30)
    }
}
