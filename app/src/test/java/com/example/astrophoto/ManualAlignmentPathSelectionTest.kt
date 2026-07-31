package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManualAlignmentPathSelectionTest {
    @Test fun shortSequenceUsesOnlyTheTypedAllowedLegacyPath() = runBlocking {
        var plannerCalls = 0
        val selection = selectManualAlignmentPath(
            inputFrameCount = MIN_MANUAL_SEQUENCE_FRAMES - 1,
            mode = ManualAlignedStackMode.AVERAGE
        ) {
            plannerCalls++
            ManualSequenceAlignmentPlanningResult.Ready(sequencePlan())
        }

        assertEquals(0, plannerCalls)
        assertNull(selection.sequencePlan)
        assertEquals(ManualAlignmentPath.LEGACY, selection.report.manualAlignmentPath)
        assertEquals(
            ManualAlignmentPathReason.LEGACY_SHORT_SEQUENCE,
            selection.report.manualAlignmentPathReason
        )
        assertTrue(selection.report.legacyFallbackAllowed)
        assertTrue(selection.report.legacyFallbackUsed)

        val legacyFrames = (0 until selection.inputFrameCount).toList()
        val legacyWork = manualSequenceFrameWork(
            legacyFrames,
            selection.sequencePlan,
            ManualAlignedStackMode.AVERAGE
        )
        assertEquals(legacyFrames, legacyWork.map { it.value })
        assertEquals(legacyFrames.indices.toList(), legacyWork.map { it.originalFrameIndex })
        val report = requireNotNull(
            manualSequenceIntegrationReport(
                alignmentSelection = selection,
                mode = ManualAlignedStackMode.AVERAGE,
                integratedOriginalFrameIndices = legacyFrames
            )
        ).publishedSuccessfully()
        assertEquals(legacyFrames, report.integratedOriginalFrameIndices)
        assertEquals(legacyFrames.size, report.acceptedFrameCount)
        assertTrue(report.alignmentPathReport.outputPublished)
        assertEquals(
            ManualAlignmentProcessingOutcome.SUCCESS,
            report.alignmentPathReport.processingOutcome
        )
    }

    @Test fun expectedPlannerUnavailabilityUsesOnlyEnumeratedLegacyReasons() = runBlocking {
        val allowedReasons = listOf(
            ManualAlignmentPathReason.LEGACY_TOO_FEW_REFERENCE_STARS,
            ManualAlignmentPathReason.LEGACY_SEQUENCE_QUALITY_GATE,
            ManualAlignmentPathReason.LEGACY_SEQUENCE_RESIDUAL_GATE,
            ManualAlignmentPathReason.LEGACY_DYNAMIC_SHIFT_GATE
        )

        allowedReasons.forEach { reason ->
            val selection = selectManualAlignmentPath(
                inputFrameCount = MIN_MANUAL_SEQUENCE_FRAMES,
                mode = ManualAlignedStackMode.AVERAGE
            ) {
                ManualSequenceAlignmentPlanningResult.Unavailable(reason)
            }
            assertEquals(reason, selection.report.manualAlignmentPathReason)
            assertEquals(ManualAlignmentPath.LEGACY, selection.report.manualAlignmentPath)
            assertTrue(selection.report.legacyFallbackAllowed)
            assertTrue(selection.report.legacyFallbackUsed)
        }
    }

    @Test fun successfulSequenceSelectionPreservesTheExactPlanAndDiagnostics() = runBlocking {
        val plan = sequencePlan()
        val selection = selectManualAlignmentPath(
            inputFrameCount = plan.frames.size,
            mode = ManualAlignedStackMode.SIGMA
        ) {
            ManualSequenceAlignmentPlanningResult.Ready(plan)
        }

        assertSame(plan, selection.sequencePlan)
        assertEquals(ManualAlignmentPath.SEQUENCE_AWARE, selection.report.manualAlignmentPath)
        assertEquals(
            ManualAlignmentPathReason.SEQUENCE_AWARE_SELECTED,
            selection.report.manualAlignmentPathReason
        )
        assertFalse(selection.report.legacyFallbackUsed)
        assertEquals(plan.shifts, checkNotNull(selection.sequencePlan).shifts)
        assertEquals(
            plan.frames.map { it.registrationResidualPx },
            checkNotNull(selection.sequencePlan).frames.map { it.registrationResidualPx }
        )
        assertEquals(
            plan.frames.map { it.registrationConfidence },
            checkNotNull(selection.sequencePlan).frames.map { it.registrationConfidence }
        )

        val accepted = plan.frames.filter { it.accepted }.map { it.originalFrameIndex }
        val report = requireNotNull(
            manualSequenceIntegrationReport(
                alignmentSelection = selection,
                mode = ManualAlignedStackMode.SIGMA,
                integratedOriginalFrameIndices = accepted
            )
        )
        assertEquals(accepted, report.integratedOriginalFrameIndices)
        assertEquals(
            plan.frames.filterNot { it.accepted }.map { it.originalFrameIndex },
            report.rejectedFrames.map { it.originalFrameIndex }
        )
    }

    @Test fun unsupportedInputAndInvalidReferenceFailWithoutLegacy() = runBlocking {
        listOf(
            ManualAlignmentPathReason.SEQUENCE_PLANNER_UNSUPPORTED_INPUT,
            ManualAlignmentPathReason.SEQUENCE_PLANNER_INVALID_REFERENCE
        ).forEach { reason ->
            val error = try {
                selectManualAlignmentPath(
                    inputFrameCount = MIN_MANUAL_SEQUENCE_FRAMES,
                    mode = ManualAlignedStackMode.MEDIAN
                ) {
                    ManualSequenceAlignmentPlanningResult.Unavailable(reason)
                }
                fail("Expected controlled alignment failure")
                null
            } catch (actual: ManualAlignmentProcessingException) {
                actual
            }
            val report = requireNotNull(error).alignmentReport
            assertEquals(reason, report.manualAlignmentPathReason)
            assertFalse(report.legacyFallbackAllowed)
            assertFalse(report.legacyFallbackUsed)
            assertFalse(report.outputPublished)
        }
    }

    @Test fun unexpectedFailureBeforePlannerNeverIntegratesOrPublishes() = runBlocking {
        verifyInjectedFailure(
            point = ManualAlignmentFailureInjectionPoint.BEFORE_SEQUENCE_PLANNER,
            mode = ManualAlignedStackMode.AVERAGE,
            expectedPlannerCalls = 0
        )
    }

    @Test fun unexpectedFailureAfterPlannerResultDiscardsTheResult() = runBlocking {
        verifyInjectedFailure(
            point = ManualAlignmentFailureInjectionPoint.AFTER_SEQUENCE_PLANNER_RESULT,
            mode = ManualAlignedStackMode.MEDIAN,
            expectedPlannerCalls = 1
        )
    }

    @Test fun everyManualAlignedModeFailsBeforeIntegrationAndPublication() = runBlocking {
        ManualAlignedStackMode.entries.forEach { mode ->
            verifyInjectedFailure(
                point = ManualAlignmentFailureInjectionPoint.BEFORE_INTEGRATION,
                mode = mode,
                expectedPlannerCalls = 1
            )
        }
    }

    @Test fun insufficientAcceptedFramesRemainAControlledNonLegacyFailure() = runBlocking {
        var integrated = false
        var published = false
        var captured: ManualAlignmentPathReport? = null
        val result = runManualStackingOperation(
            onAlignmentFailure = { captured = it }
        ) {
            selectManualAlignmentPath(
                inputFrameCount = MIN_MANUAL_SEQUENCE_FRAMES,
                mode = ManualAlignedStackMode.DARK_SUBTRACTED_AVERAGE
            ) {
                ManualSequenceAlignmentPlanningResult.InsufficientAcceptedFrames(
                    acceptedFrameCount = 1,
                    requiredFrameCount = 4,
                    rejectedFrames = (1 until MIN_MANUAL_SEQUENCE_FRAMES).map { index ->
                        ManualSequenceRejectedFrame(index, "frame-$index", "test_rejected")
                    }
                )
            }
            integrated = true
            published = true
        }

        assertTrue(result.exceptionOrNull() is ManualSequenceInsufficientFramesException)
        assertFalse(integrated)
        assertFalse(published)
        assertEquals(
            ManualAlignmentPathReason.SEQUENCE_PLANNER_INSUFFICIENT_ACCEPTED_FRAMES,
            requireNotNull(captured).manualAlignmentPathReason
        )
        assertFalse(requireNotNull(captured).legacyFallbackUsed)
        assertTrue(requireNotNull(captured).cleanupCompleted)
    }

    @Test fun cancellationIsRethrownWithoutFailureReportOrLegacy() = runBlocking {
        var reports = 0
        var integrated = false
        val expected = CancellationException("cancel manual alignment")
        val injector = ManualAlignmentFailureInjector { point, _ ->
            if (point == ManualAlignmentFailureInjectionPoint.AFTER_SEQUENCE_PLANNER_RESULT) {
                throw expected
            }
        }

        val actual = try {
            runManualStackingOperation(
                onAlignmentFailure = { reports++ }
            ) {
                selectManualAlignmentPath(
                    inputFrameCount = MIN_MANUAL_SEQUENCE_FRAMES,
                    mode = ManualAlignedStackMode.AVERAGE,
                    failureInjector = injector
                ) {
                    ManualSequenceAlignmentPlanningResult.Ready(sequencePlan())
                }
                integrated = true
            }
            fail("Cancellation must be rethrown")
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(expected, actual)
        assertEquals(0, reports)
        assertFalse(integrated)
    }

    @Test fun manualReportFieldsAreStableAndUnknownEnumsAreSafe() {
        val report = ManualAlignmentPathReport(
            manualAlignmentPath = ManualAlignmentPath.SEQUENCE_AWARE,
            manualAlignmentPathReason = ManualAlignmentPathReason.SEQUENCE_PLANNER_INTERNAL_ERROR,
            manualAlignmentAttempted = true,
            legacyFallbackAllowed = false,
            legacyFallbackUsed = false,
            sequencePlannerFailureType = "java.lang.IllegalStateException",
            sequencePlannerFailureMessage = "invariant failed",
            processingOutcome = ManualAlignmentProcessingOutcome.FAILED,
            outputPublished = false,
            cleanupCompleted = true
        )
        val fields = report.toStableFields()
        val json = manualAlignmentPathReportJson(ManualAlignedStackMode.AVERAGE, report)

        assertEquals("astrophoto.manual.alignment/1", fields["manualAlignmentReportSchema"])
        assertTrue(json.contains("\"schemaVersion\": \"astrophoto.manual.alignment/1\""))
        assertTrue(json.contains("\"manualAlignmentPath\": \"SEQUENCE_AWARE\""))
        assertTrue(json.contains("\"legacyFallbackUsed\": false"))
        assertTrue(json.contains("\"outputPublished\": false"))
        assertFalse(json.contains(",\n}\n"))
        assertEquals(report, manualAlignmentPathReportFromFields(fields))
        assertNull(manualAlignmentPathReportFromFields(emptyMap()))

        val unknown = manualAlignmentPathReportFromFields(
            fields + mapOf(
                "manualAlignmentPath" to "FUTURE_PATH",
                "manualAlignmentPathReason" to "FUTURE_REASON",
                "processingOutcome" to "FUTURE_OUTCOME"
            )
        )
        assertEquals(ManualAlignmentPath.UNKNOWN, requireNotNull(unknown).manualAlignmentPath)
        assertEquals(
            ManualAlignmentPathReason.UNKNOWN,
            requireNotNull(unknown).manualAlignmentPathReason
        )
        assertEquals(
            ManualAlignmentProcessingOutcome.UNKNOWN,
            requireNotNull(unknown).processingOutcome
        )
        assertEquals("astrophoto.jpeg.processing/3", ProcessingReport.SCHEMA_VERSION)
    }

    @Test fun productionWiresTheGuardedSelectorIntoEveryManualAlignedMode() {
        val source = Files.readString(
            Path.of("src/main/java/com/example/astrophoto/JpegStacker.kt")
        )
        assertEquals(4, Regex("runManualStackingOperation\\(").findAll(source).count())
        assertEquals(
            4,
            Regex("prepareManualSequenceAlignmentSelection\\(").findAll(source).count() - 1
        )
        ManualAlignedStackMode.entries.forEach { mode ->
            assertTrue(
                "$mode is not wired into the typed selector",
                source.contains("mode = ManualAlignedStackMode.${mode.name}")
            )
        }
        val plannerStart = source.indexOf("private suspend fun prepareManualSequenceAlignmentSelection")
        val plannerEnd = source.indexOf("private suspend fun reportManualSequenceShift", plannerStart)
        val planner = source.substring(plannerStart, plannerEnd)
        assertFalse(planner.contains("catch (error: Exception)"))
        assertFalse(planner.contains("method=legacyFallback reason=${'$'}{error"))
        val failureReportStart = source.indexOf(
            "private suspend fun appendManualAlignmentFailureSessionInfo"
        )
        val failureReportEnd = source.indexOf("private fun appendSessionInfo", failureReportStart)
        val failureReport = source.substring(failureReportStart, failureReportEnd)
        assertTrue(failureReport.contains("ProcessingReportWriter(context).write("))
        assertTrue(failureReport.contains("manualAlignmentPathReportJson"))
        assertFalse(failureReport.contains("saveBitmap("))
    }

    private suspend fun verifyInjectedFailure(
        point: ManualAlignmentFailureInjectionPoint,
        mode: ManualAlignedStackMode,
        expectedPlannerCalls: Int
    ) {
        var plannerCalls = 0
        var integrationCalls = 0
        var publicationCalls = 0
        var captured: ManualAlignmentPathReport? = null
        val injector = ManualAlignmentFailureInjector { actual, actualMode ->
            assertEquals(mode, actualMode)
            if (actual == point) error("injected-$point")
        }

        val result = runManualStackingOperation(
            onAlignmentFailure = { captured = it }
        ) {
            selectManualAlignmentPath(
                inputFrameCount = MIN_MANUAL_SEQUENCE_FRAMES,
                mode = mode,
                failureInjector = injector
            ) {
                plannerCalls++
                ManualSequenceAlignmentPlanningResult.Ready(sequencePlan())
            }
            integrationCalls++
            publicationCalls++
        }

        assertTrue(result.exceptionOrNull() is ManualAlignmentProcessingException)
        assertEquals(expectedPlannerCalls, plannerCalls)
        assertEquals(0, integrationCalls)
        assertEquals(0, publicationCalls)
        val report = requireNotNull(captured)
        assertEquals(
            ManualAlignmentPathReason.SEQUENCE_PLANNER_INTERNAL_ERROR,
            report.manualAlignmentPathReason
        )
        assertFalse(report.legacyFallbackAllowed)
        assertFalse(report.legacyFallbackUsed)
        assertFalse(report.outputPublished)
        assertTrue(report.cleanupCompleted)
        assertEquals(ManualAlignmentProcessingOutcome.FAILED, report.processingOutcome)
        assertTrue(report.sequencePlannerFailureType.orEmpty().contains("IllegalStateException"))
        assertEquals("injected-$point", report.sequencePlannerFailureMessage)
    }

    private fun sequencePlan(): ManualSequenceAlignmentPlan = ManualSequenceAlignmentPlan(
        referenceFrameIndex = 0,
        frames = (0 until MIN_MANUAL_SEQUENCE_FRAMES).map { index ->
            val accepted = index != 5
            ManualSequenceFrameDecision(
                originalFrameIndex = index,
                frameId = "frame-$index",
                accepted = accepted,
                rejectionReason = if (accepted) null else "test_rejected",
                shift = AlignmentShift(index, -index, 0.9),
                registrationResidualPx = if (accepted) index / 10f else 1.5f,
                registrationConfidence = if (accepted) 0.9f else 0.2f
            )
        },
        modelScore = 0.8f,
        modelResidualPx = 0.2f,
        stationaryArtifactCount = 2
    )
}
