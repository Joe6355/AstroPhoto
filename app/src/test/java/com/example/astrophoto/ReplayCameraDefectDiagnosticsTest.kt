package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import java.nio.file.Files
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayCameraDefectDiagnosticsTest {
    @Test fun transformRoundTripDefinesDirectionSemantically() {
        val transform = ReferenceToSourceTransform(
            dx = 7.5f,
            dy = -4.25f,
            rotationRadians = 0.17f,
            scale = 1.03f,
            rotationCenterX = 24f,
            rotationCenterY = 19f
        )
        val error = ReplayTransformSemantics.maximumRoundTripError(
            transform,
            listOf(ReplayPoint(3.0, 5.0), ReplayPoint(24.0, 19.0), ReplayPoint(41.0, 33.0))
        )
        assertTrue("round-trip error=$error", error <= 0.01)
    }

    @Test fun fixedRingContainsOnlyOuterSixteenOffsets() {
        assertEquals(16, ReplayDefectMath.ringOffsets.size)
        assertTrue(ReplayDefectMath.ringOffsets.all { (x, y) -> maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) == 2 })
        assertFalse(0 to 0 in ReplayDefectMath.ringOffsets)
        assertFalse(1 to 1 in ReplayDefectMath.ringOffsets)
    }

    @Test fun skyMaskUsesFrozenDiskThreeErosionAndStableHash() {
        val alpha = AlphaMask.full(9, 9)
        val first = ReplayDefectMath.erodedSkyMask(alpha)
        val second = ReplayDefectMath.erodedSkyMask(alpha)
        assertTrue(first[4 * 9 + 4])
        assertFalse(first[2 * 9 + 4])
        assertEquals(ReplayDefectMath.hashBits(first), ReplayDefectMath.hashBits(second))
    }

    @Test fun candidateThresholdsSeparateChromaAndConsistentLuminance() {
        assertEquals(ReplayDefectKind.CHROMA, ReplayDefectMath.classifyResidual(9.0, 2.0, 1.0))
        assertEquals(ReplayDefectKind.LUMINANCE, ReplayDefectMath.classifyResidual(10.0, 9.0, 8.0))
        assertEquals(null, ReplayDefectMath.classifyResidual(7.0, 5.0, 4.5))
    }

    @Test fun positionRmsAndPcaLengthUseExplicitDefinitions() {
        assertEquals(0.0, ReplayDefectMath.positionRms(List(5) { 4.0 to 7.0 }, 4.0, 7.0), 0.0)
        val geometry = ReplayDefectMath.pca((0..4).map { ReplayPoint(it.toDouble(), it.toDouble()) })
        assertEquals(4.0 * sqrt(2.0), geometry.majorAxisLength, 1e-6)
        assertTrue(geometry.orientationRadians != null)
    }

    @Test fun coverageBoundariesMatchFrozenPolicy() {
        assertEquals(ReplayMapCoverageStatus.NORMAL, ReplayDefectMath.coverageStatus(0.005))
        assertEquals(ReplayMapCoverageStatus.MANUAL_REVIEW, ReplayDefectMath.coverageStatus(0.005001))
        assertEquals(ReplayMapCoverageStatus.MANUAL_REVIEW, ReplayDefectMath.coverageStatus(0.02))
        assertEquals(ReplayMapCoverageStatus.HARD_REJECTION, ReplayDefectMath.coverageStatus(0.020001))
    }

    @Test fun manualAnnotationsAreScopedToTheExactSuppliedReplay() {
        val expectedIds = (1..30).map {
            "AstroSeries_20260714_022705_${it.toString().padStart(3, '0')}.jpg"
        }.toSet()
        assertEquals(6, ReplayStage1BAnnotations.forCurrentReplay(1440, 1920, expectedIds).size)
        assertTrue(ReplayStage1BAnnotations.forCurrentReplay(1440, 1920, expectedIds - expectedIds.first()).isEmpty())
        assertTrue(ReplayStage1BAnnotations.forCurrentReplay(1080, 1920, expectedIds).isEmpty())
    }

    @Test fun realRunnerUsesEveryEligibleFrameAndMarksWeakObservationInferred() {
        val width = 48
        val height = 48
        val sourceX = 30
        val sourceY = 24
        val frames = (0 until 10).map { index ->
            ReplayDefectFrame("f$index", index, ReferenceToSourceTransform(index * 0.75f, index * -0.50f))
        }
        val images = frames.associate { frame ->
            val observed = frame.captureIndex != 6
            frame.id to uniform(width, height, 10).also { image ->
                image.pixels[sourceY * width + sourceX] = if (observed) rgb(32, 10, 10) else rgb(14, 10, 10)
                if (frame.captureIndex == 0) image.pixels[sourceY * width + sourceX + 1] = rgb(80, 10, 10)
            }
        }
        val baseline = uniform(width, height, 10)
        frames.forEach { frame ->
            val output = ReplayTransformSemantics.sourceToOutput(frame.transform, sourceX.toDouble(), sourceY.toDouble())
            baseline.pixels[output.y.toInt() * width + output.x.toInt()] = rgb(22, 10, 10)
        }
        val manualTrail = ReplayManualTrailAnnotation(
            "synthetic-trail",
            listOf(
                ReplayTransformSemantics.sourceToOutput(frames.first().transform, sourceX.toDouble(), sourceY.toDouble()),
                ReplayTransformSemantics.sourceToOutput(frames.last().transform, sourceX.toDouble(), sourceY.toDouble())
            ),
            "synthetic full eligible-frame path"
        )
        val root = Files.createTempDirectory("camera-defect-diagnostic")
        try {
            val result = ReplayCameraDefectDiagnosticRunner().run(
                baseline,
                AlphaMask.full(width, height),
                frames,
                frames.map { it.id }.toSet() + "rejected",
                emptyList(),
                root,
                listOf(manualTrail)
            ) { images.getValue(it.id) }
            val defect = result.confirmedDefects.single { it.coreX == sourceX && it.coreY == sourceY }
            assertEquals(10, defect.eligiblePath.size)
            assertEquals(9, defect.eligiblePath.count { it.observed })
            assertEquals(1, defect.eligiblePath.count { !it.observed })
            assertTrue("expected one 8-connected trail", result.trails.isNotEmpty())
            assertEquals(setOf("rejected"), result.rejectedFrameIds)
            assertFalse(1 to 0 in defect.footprintOffsets)
            assertEquals(1, result.defectComponents.size)
            assertEquals(ReplayManualTrailStatus.EXPLAINED, result.manualCorrespondences.single().status)
            assertTrue(Files.isRegularFile(root.resolve("threshold-manifest.json")))
            assertTrue(Files.isRegularFile(root.resolve("stage1b-strong-stretch.png")))
            assertTrue(Files.isRegularFile(root.resolve("stage1b-manual-correspondence.tsv")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun adjacentPersistentCoresConsolidateIntoOneCameraComponent() {
        val width = 40
        val height = 40
        val frames = (0 until 10).map { ReplayDefectFrame("f$it", it, ReferenceToSourceTransform.Identity) }
        val images = frames.associate { frame ->
            frame.id to uniform(width, height, 10).also { image ->
                image.pixels[20 * width + 20] = rgb(34, 10, 10)
                image.pixels[20 * width + 21] = rgb(31, 10, 10)
            }
        }
        val root = Files.createTempDirectory("camera-defect-components")
        try {
            val result = ReplayCameraDefectDiagnosticRunner().run(
                uniform(width, height, 10),
                AlphaMask.full(width, height),
                frames,
                frames.map { it.id }.toSet(),
                emptyList(),
                root
            ) { images.getValue(it.id) }
            assertEquals(2, result.confirmedDefects.count { it.coreY == 20 && it.coreX in 20..21 })
            assertEquals(1, result.defectComponents.count { component ->
                component.cores.any { it.coreX == 20 && it.coreY == 20 } &&
                    component.cores.any { it.coreX == 21 && it.coreY == 20 }
            })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun coherentReferenceStarRejectsSupportedCandidate() {
        val width = 32
        val height = 32
        val frames = (0 until 10).map { ReplayDefectFrame("f$it", it, ReferenceToSourceTransform.Identity) }
        val images = frames.associate { frame ->
            frame.id to uniform(width, height, 10).also { it.pixels[16 * width + 16] = rgb(35, 10, 10) }
        }
        val star = FullResolutionStarPatch(
            16f, 16f, 1f, 20f, 1.5f, 0.1f, 4,
            TemporalMotionCluster.COHERENT_MOVING_SKY, 1f
        )
        val root = Files.createTempDirectory("camera-defect-star-rejection")
        try {
            val result = ReplayCameraDefectDiagnosticRunner().run(
                uniform(width, height, 10),
                AlphaMask.full(width, height),
                frames,
                frames.map { it.id }.toSet(),
                listOf(star),
                root
            ) { images.getValue(it.id) }
            assertTrue(result.rejectedStarLikeCandidates.any {
                it.coreX == 16 && it.coreY == 16 && it.rejectionReason == "sky_track"
            })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun uniform(width: Int, height: Int, value: Int): ArgbPixelImage =
        ArgbPixelImage(width, height, IntArray(width * height) { rgb(value, value, value) })

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}
