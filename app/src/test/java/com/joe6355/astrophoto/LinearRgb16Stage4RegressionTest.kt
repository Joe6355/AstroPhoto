package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.StarEnhancementDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedPixelFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** New precision is tested independently of the frozen, per-stage-quantized ARGB8 replay. */
class LinearRgb16Stage4RegressionTest {
    @Test fun urbanWindow30Rgb16IsTileInvariantAndKeepsQualityGates() = runBlocking {
        val runner = SkyMaskReplayDiagnosticRunner()
        val baseline = runner.analyze(UrbanWindow30ReplayFixture.fixture)
        val mask = SkyMask(baseline.cleanStack.width, baseline.cleanStack.height,
            BooleanArray(baseline.cleanStack.pixels.size) { index ->
                baseline.effectiveAlpha.alphaAt(index % baseline.cleanStack.width,
                    index / baseline.cleanStack.width) >= 0.98f
            })
        val stars = JpegStarDetector().detect(baseline.cleanStack, mask).stars
        val roomy = JpegMemoryBudget(RuntimeHeapSnapshot(512 * MIB, 128 * MIB, 96 * MIB))
        val constrained = JpegMemoryBudget(
            RuntimeHeapSnapshot(128 * MIB, 128 * MIB, 32 * MIB + 512 * 1024), reserveBytes = 32 * MIB)
        for (profile in listOf(AstroProcessingProfile.DEEP_SKY, AstroProcessingProfile.EXPERIMENTAL_STARS)) {
            val hashes = mutableListOf<String>()
            val tileSizes = mutableListOf<Map<String, String>>()
            val diagnostics = mutableListOf<StarEnhancementDiagnostics>()
            val outputs = listOf(roomy, constrained).map { budget ->
                runner.runActiveFileBackedAdaptive(
                    baseline.cleanStack, baseline.reference, baseline.effectiveAlpha, profile,
                    baseline.acceptedOriginalFrameIndices.size, stars,
                    sensorDefectAffectedOutput = baseline.sensorDefectAffectedOutput,
                    pixelFormat = FileBackedPixelFormat.LINEAR_RGB_16,
                    memoryBudget = budget, onPixelHash = { hashes += it },
                    onTileSizes = { tileSizes += it }, onStarDiagnostics = { diagnostics += it })
            }
            assertNotEquals("Exercise different tile boundaries for $profile", tileSizes[0], tileSizes[1])
            assertEquals("Every RGB16 bit must be independent of tile size: $profile", hashes[0], hashes[1])
            assertArrayEquals(outputs[0].pixels, outputs[1].pixels)
            assertEquals(diagnostics[0], diagnostics[1])
            val output = outputs.first()
            assertEquals(baseline.reference.width, output.width)
            assertEquals(baseline.reference.height, output.height)
            output.pixels.indices.forEach { index ->
                if (baseline.effectiveAlpha.alphaAt(index % output.width, index / output.width) == 0f) {
                    assertEquals("Foreground changed: $profile at $index",
                        baseline.reference.pixels[index], output.pixels[index])
                }
            }
            val selected = selectReplayCandidate(baseline.reference, baseline.cleanComposed, output,
                baseline.effectiveAlpha, baseline.validCoverage, stars, baseline.alignmentModelScore,
                baseline.acceptedOriginalFrameIndices.size, profile)
            val legacy = runner.runActiveFileBackedAdaptive(baseline.cleanStack, baseline.reference,
                baseline.effectiveAlpha, profile, baseline.acceptedOriginalFrameIndices.size, stars,
                sensorDefectAffectedOutput = baseline.sensorDefectAffectedOutput,
                pixelFormat = FileBackedPixelFormat.ARGB_8888)
            val legacyDecision = selectReplayCandidate(baseline.reference, baseline.cleanComposed, legacy,
                baseline.effectiveAlpha, baseline.validCoverage, stars, baseline.alignmentModelScore,
                baseline.acceptedOriginalFrameIndices.size, profile)
            // A Stage 4 test must not require this fixture's Stage 3 evidence to pass a different profile.
            // It must introduce no new hard failures, and must preserve rejection/fallback semantics.
            assertTrue("RGB16 introduced quality failures for $profile: ${selected.processedRejectionReasons}; " +
                "legacy=${legacyDecision.processedRejectionReasons}",
                legacyDecision.processedRejectionReasons.containsAll(selected.processedRejectionReasons))
            if (legacyDecision.processedAccepted) assertTrue(selected.processedAccepted)
            if (!selected.processedAccepted) {
                assertNotEquals(com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidateType.PROCESSED,
                    selected.type)
            }
            println("RGB16 $profile rawHash=${hashes.first()} tiles=$tileSizes selected=${selected.type} " +
                "processedAccepted=${selected.processedAccepted} reasons=${selected.processedRejectionReasons} " +
                "legacyReasons=${legacyDecision.processedRejectionReasons}")
        }
    }

    private companion object { const val MIB = 1024L * 1024L }
}
