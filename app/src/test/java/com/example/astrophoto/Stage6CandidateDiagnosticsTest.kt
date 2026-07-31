package com.example.astrophoto

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage6CandidateDiagnosticsTest {
    @Test fun candidateGenerationIsDeterministicAndOrdered() {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        val runner = Stage6CandidateDiagnosticRunner()
        val first = runner.analyze(fixture)
        val second = runner.analyze(fixture)

        assertTrue(first.candidates.size >= 20)
        assertEquals(first.candidates.map { it.id }, second.candidates.map { it.id })
        assertEquals(runner.diagnosticsJson(first), runner.diagnosticsJson(second))
        assertEquals(
            first.candidates.sortedWith(
                compareBy<Stage6CandidateDiagnostic> { it.referenceY }
                    .thenBy { it.referenceX }
                    .thenBy { it.id }
            ).map { it.id },
            first.candidates.map { it.id }
        )
        assertTrue(first.candidates.any { "reference_frame" in it.origins })
        assertTrue(first.candidates.any { "aligned_clean_stack" in it.origins })
        assertTrue(first.candidates.any { "manual_aligned_stack" in it.origins })
        assertTrue(first.candidates.any { "enhanced_profile" in it.origins })
        assertTrue(first.candidates.any { "camera_space_temporal_persistence" in it.origins })
        assertTrue(first.candidates.any { "sky_space_temporal_motion" in it.origins })
    }

    @Test fun fixtureSemanticsAndIntegrationWeightsRemainExplicit() {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        val bundle = Stage6CandidateDiagnosticRunner().analyze(fixture)

        assertEquals(30, fixture.frames.size)
        fixture.strictReferenceStarLabels.forEach {
            assertEquals(ProvisionalCoordinateSpace.SKY, it.coordinateSpace)
            assertTrue(it.skyResidualPx != null && it.cameraResidualPx != null)
        }
        fixture.groundTruth.filter { it.id in setOf("star-01", "star-02") }.forEach {
            assertTrue(it.skyResidualPx!! < it.cameraResidualPx!!)
        }
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.SENSOR_DEFECT
        }.forEach {
            assertTrue(it.cameraResidualPx!! < it.skyResidualPx!!)
        }
        assertFalse(fixture.scoredGroundTruth.any {
            it.classification == ProvisionalSourceClass.UNCERTAIN
        })
        val fullResolutionPlan = requireNotNull(
            planManualSequenceAlignment(
                fixture.frames,
                outputWidth = 1440,
                outputHeight = 1920
            )
        )
        assertTrue(abs(fullResolutionPlan.shifts.last().dx) > 30)
        assertTrue(abs(fullResolutionPlan.shifts.last().dy) > 30)
        assertTrue(hypot(bundle.motionXPerFrame, bundle.motionYPerFrame) > 0.5)
        assertTrue(bundle.stationaryArtifactCount >= 2)

        val rejected = bundle.frames.filterNot { it.cleanAccepted }
        assertTrue(rejected.isNotEmpty())
        assertTrue(rejected.all { it.cleanIntegrationWeight == 0.0 })
        assertTrue(rejected.all { it.manualIntegrationWeight == 0.0 })
        bundle.frames.filter { it.cleanAccepted }.forEach { frame ->
            val limit = manualAlignmentShiftLimitPx(
                frame.frameIndex,
                bundle.frames.size,
                bundle.width,
                bundle.height
            )
            assertTrue(abs(frame.cleanTransform.dx) <= limit)
            assertTrue(abs(frame.cleanTransform.dy) <= limit)
        }
    }

    @Test fun requestedDiagnosticArtifactsAreGenerated() {
        val fixtureDirectory = fixtureDirectory()
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory)
        val configured = (
            System.getProperty(OUTPUT_PROPERTY)
                ?: System.getenv(OUTPUT_ENVIRONMENT)
            )?.takeIf(String::isNotBlank)
        val temporary = configured == null
        val output = configured?.let(Path::of) ?: Files.createTempDirectory("stage6-candidates")
        val sourceGroundTruth = configured?.let {
            checkNotNull(output.parent).resolve("ground-truth.csv")
        } ?: fixtureDirectory.toPath().resolve("ground-truth.csv")
        val expandedGroundTruth = if (configured == null) output.resolve("ground-truth.csv") else sourceGroundTruth
        try {
            val runner = Stage6CandidateDiagnosticRunner()
            val bundle = runner.analyze(fixture)
            runner.writeArtifacts(
                bundle,
                output,
                sourceGroundTruth,
                expandedGroundTruth
            )
            listOf(
                "candidate-diagnostics.json",
                "candidate-review.md",
                "candidates-contact-sheet.png",
                "trail-provenance-contact-sheet.png"
            ).forEach {
                assertTrue("Missing $it", Files.isRegularFile(output.resolve(it)))
            }
            assertTrue(Files.isRegularFile(expandedGroundTruth))
            val firstGroundTruth = Files.readAllBytes(expandedGroundTruth)
            runner.writeExpandedGroundTruth(bundle, expandedGroundTruth, expandedGroundTruth)
            assertArrayEquals(firstGroundTruth, Files.readAllBytes(expandedGroundTruth))
        } finally {
            if (temporary) output.toFile().deleteRecursively()
        }
    }

    @Test fun rejectedFrameCameraDefectTrailIsReduced() {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        val bundle = Stage6CandidateDiagnosticRunner().analyze(fixture)
        val defectTrails = bundle.trails.filter {
            it.provenance == "camera_space_defect_smeared_by_sky_alignment"
        }

        assertEquals(2, defectTrails.size)
        defectTrails.forEach { trail ->
            assertTrue(
                "${trail.candidateId}: before=${trail.leakyBaselineRejectedPathContrast}, " +
                    "after=${trail.filteredManualRejectedPathContrast}",
                trail.filteredManualRejectedPathContrast <
                    trail.leakyBaselineRejectedPathContrast
            )
        }
    }

    private fun fixtureDirectory(): File {
        val resource = requireNotNull(
            requireNotNull(javaClass.classLoader)
                .getResource("jpeg-stage6/urban-window-30/manifest.properties")
        )
        return requireNotNull(File(resource.toURI()).parentFile)
    }

    private fun hypot(x: Double, y: Double): Double = kotlin.math.hypot(x, y)

    companion object {
        const val OUTPUT_PROPERTY = "astrophoto.stage6.diagnosticsOutputDir"
        const val OUTPUT_ENVIRONMENT = "ASTROPHOTO_STAGE6_DIAGNOSTICS_OUTPUT_DIR"
    }
}
