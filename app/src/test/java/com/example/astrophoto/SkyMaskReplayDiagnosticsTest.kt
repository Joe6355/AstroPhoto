package com.example.astrophoto

import java.awt.image.DataBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyMaskReplayDiagnosticsTest {
    @Test fun pipelineOrderingCoordinateSpaceAndMaskRangesAreExplicit() {
        val value = analyze()
        val expected = listOf(
            "fixture_decode",
            "initial_sky_mask",
            "sequence_registration_and_sensor_mask",
            "masked_clean_integration",
            "refined_sky_mask",
            "foreground_protection",
            "mask_feather",
            "reference_star_preservation",
            "clean_composition_and_effective_alpha",
            "processed_sky",
            "processed_composition",
            "final_selection"
        )
        var previous = -1
        expected.forEach { id ->
            val next = value.pipelineManifestJson.indexOf("\"id\":\"$id\"")
            assertTrue("Missing or unordered pipeline stage $id", next > previous)
            previous = next
        }
        assertEquals(720, value.reference.width)
        assertEquals(960, value.reference.height)
        listOf(value.initialMask, value.refinedMask, value.foregroundProtection, value.skySelection)
            .forEach {
                assertEquals(value.reference.width, it.width)
                assertEquals(value.reference.height, it.height)
            }
        for (y in 0 until value.effectiveAlpha.height) for (x in 0 until value.effectiveAlpha.width) {
            val alpha = value.effectiveAlpha.alphaAt(x, y)
            assertTrue(alpha.isFinite() && alpha in 0f..1f)
        }
        assertTrue(value.adaptiveReplayMatchesProductionPixels)
        assertTrue(value.activeFileBackedMaximumChannelDifference <= 2)
        assertTrue(value.cleanCandidateAccepted)
        assertFalse(value.processedCandidateAccepted)
        assertEquals("CLEAN_STACK", value.selectedCandidateType)
        assertEquals((1..30).toList(),
            (value.acceptedOriginalFrameIndices + value.rejectedOriginalFrameIndices).sorted())
    }

    @Test fun boundaryExtractionAndWindowSelectionAreDeterministic() {
        val value = analyze()
        val first = SkyMaskReplayMath.boundaryPixels(
            value.refinedMask.copyPixels(), value.reference.width, value.reference.height
        )
        val second = SkyMaskReplayMath.boundaryPixels(
            value.refinedMask.copyPixels(), value.reference.width, value.reference.height
        )
        assertArrayEquals(first, second)
        assertEquals(value.windows.map { it.id }, value.windows.map { it.id })
        assertEquals(value.windows.map { it.id }.distinct().size, value.windows.size)
        assertTrue(value.windows.all { it.size % 2 == 1 })
        assertTrue(value.windows.any { it.id == "candidate-x56925-y74428" })
        listOf(
            "maximum-alpha-gradient",
            "maximum-composition-difference",
            "maximum-local-discontinuity",
            "maximum-halo-score",
            "maximum-edge-leakage"
        ).forEach { id -> assertTrue(value.windows.any { it.id == id }) }
    }

    @Test fun strictStarDenominatorAndMetricsRemainExactAndFinite() {
        val value = analyze()
        val strictIds = value.fixture.strictReferenceStarLabels.map { it.id }
        assertEquals(
            listOf(
                "star-01",
                "star-02",
                "candidate-x55810-y22220",
                "candidate-x57000-y36200",
                "candidate-x42300-y43500",
                "candidate-x51700-y52400"
            ),
            strictIds
        )
        assertEquals(6, strictIds.size)
        val expectedStages = setOf(
            "clean-stack", "processed-sky", "composed-current", "final-current",
            "no-mask", "hard-mask", "no-refine", "no-protection", "no-postprocess"
        )
        strictIds.forEach { id ->
            assertEquals(expectedStages, value.strictStarMetrics.filter { it.starId == id }.map { it.stage }.toSet())
        }
        assertFalse(value.fixture.scoredGroundTruth.any {
            it.classification == ProvisionalSourceClass.UNCERTAIN
        })
        assertTrue(value.strictStarMetrics.all {
            listOf(
                it.centroidX, it.centroidY, it.peakLuminance, it.apertureFlux,
                it.localBackground, it.localContrast, it.robustWidth, it.ellipticity,
                it.chromaResidual, it.distanceToMaskBoundary, it.centerAlpha,
                it.fluxRetentionFromClean, it.centroidShiftFromClean
            ).all(Double::isFinite)
        })
        assertTrue(value.windowMetrics.all { metric ->
            listOf(
                metric.distanceToBoundary, metric.brightRim, metric.darkRim,
                metric.haloScore, metric.luminanceJump, metric.leakageScore
            ).all(Double::isFinite)
        })
        assertEquals(
            listOf(
                "00-clean-input", "01-gradient-removal", "02-background-neutralization",
                "03-adaptive-stretch", "04-chroma-reduction", "05-star-enhancement",
                "06-final-safety", "07-background-match"
            ),
            value.postProcessingStages.map { it.id }
        )
        assertTrue(value.postProcessingStageMetrics.all { metric ->
            listOf(
                metric.skyMad, metric.bandingProxy, metric.boundaryEdgeExcess,
                metric.meanAbsoluteChangeFromClean
            ).all(Double::isFinite)
        })
    }

    @Test fun ablationVariantsDisableOnlyTheirNamedStage() {
        val value = analyze()
        assertEquals(SkyMaskReplayVariantId.entries, value.variants.map { it.id })
        val current = value.variants.single { it.id == SkyMaskReplayVariantId.CURRENT }
        val noMask = value.variants.single { it.id == SkyMaskReplayVariantId.NO_MASK }
        val hard = value.variants.single { it.id == SkyMaskReplayVariantId.HARD_MASK }
        val noRefine = value.variants.single { it.id == SkyMaskReplayVariantId.NO_REFINE }
        val noProtection = value.variants.single { it.id == SkyMaskReplayVariantId.NO_PROTECTION }
        val noPost = value.variants.single { it.id == SkyMaskReplayVariantId.NO_POSTPROCESS }
        for (y in 0 until noMask.alpha.height) for (x in 0 until noMask.alpha.width) {
            assertEquals(1f, noMask.alpha.alphaAt(x, y), 0f)
            assertTrue(hard.alpha.alphaAt(x, y) == 0f || hard.alpha.alphaAt(x, y) == 1f)
        }
        assertFalse(noMask.initialMaskEnabled)
        assertFalse(noMask.refinementEnabled)
        assertFalse(noMask.foregroundProtectionEnabled)
        assertTrue(noMask.postProcessingEnabled)
        assertEquals(current.initialMaskEnabled, noRefine.initialMaskEnabled)
        assertFalse(noRefine.refinementEnabled)
        assertEquals(current.foregroundProtectionEnabled, noRefine.foregroundProtectionEnabled)
        assertEquals(current.postProcessingEnabled, noRefine.postProcessingEnabled)
        assertEquals(current.initialMaskEnabled, noProtection.initialMaskEnabled)
        assertEquals(current.refinementEnabled, noProtection.refinementEnabled)
        assertFalse(noProtection.foregroundProtectionEnabled)
        assertEquals(current.postProcessingEnabled, noProtection.postProcessingEnabled)
        assertEquals(current.initialMaskEnabled, noPost.initialMaskEnabled)
        assertEquals(current.refinementEnabled, noPost.refinementEnabled)
        assertEquals(current.foregroundProtectionEnabled, noPost.foregroundProtectionEnabled)
        assertFalse(noPost.postProcessingEnabled)
    }

    @Test fun generatedReportIsCompleteAndSecondRunByteIdentical() = runBlocking {
        val configured = (System.getProperty(OUTPUT_PROPERTY) ?: System.getenv(OUTPUT_ENVIRONMENT))
            ?.takeIf(String::isNotBlank)
        val temporaryPrimary = configured == null
        val primary = configured?.let(Path::of) ?: Files.createTempDirectory("sky-mask-replay-a")
        val secondary = Files.createTempDirectory("sky-mask-replay-b")
        val groundTruth = UrbanWindow30ReplayFixture.directory.toPath().resolve("ground-truth.csv")
        val groundTruthBefore = Files.readAllBytes(groundTruth)
        val mainSourceBefore = treeHash(Path.of("src/main"))
        val fixture = UrbanWindow30ReplayFixture.fixture
        val fixtureHashesBefore = fixture.frames.map(ReplayDiagnosticHashing::sha256Argb)
        try {
            var firstBundle: SkyMaskReplayBundle? = SkyMaskReplayDiagnosticRunner().analyze(fixture)
            val firstResult = SkyMaskReplayReportWriter.write(requireNotNull(firstBundle), primary)
            firstBundle = null
            System.gc()
            val secondBundle = SkyMaskReplayDiagnosticRunner().analyze(fixture)
            val secondResult = SkyMaskReplayReportWriter.write(secondBundle, secondary)
            assertEquals(firstResult, secondResult)
            assertTreesByteIdentical(primary, secondary)
            assertArrayEquals(groundTruthBefore, Files.readAllBytes(groundTruth))
            assertEquals(mainSourceBefore, treeHash(Path.of("src/main")))
            assertEquals(fixtureHashesBefore, fixture.frames.map(ReplayDiagnosticHashing::sha256Argb))
            listOf(
                "reference.png", "clean-stack.png", "processed-sky.png",
                "composed-current.png", "final-current.png", "initial-mask.png",
                "refined-mask.png", "effective-alpha.png", "effective-alpha.f32le",
                "foreground-protection.png", "sky-selection.png",
                "composition-no-mask.png", "composition-hard-mask.png",
                "composition-current-alpha.png", "composition-no-foreground-protection.png",
                "composition-no-postprocess.png", "diff-current-vs-no-mask.png",
                "diff-current-vs-hard-mask.png", "diff-current-vs-no-protection.png",
                "diff-current-vs-no-postprocess.png", "initial-boundary.png",
                "refined-boundary.png", "effective-alpha-transition-band.png",
                "foreground-boundary.png", "pipeline-manifest.json", "index.html",
                "strict-star-metrics.csv", "strict-star-summary.md",
                "strict-star-contact-sheet.png", "boundary-metrics.csv",
                "halo-ranking.csv", "leakage-ranking.csv", "star-clipping-ranking.csv",
                "postprocess-stage-metrics.csv", "postprocess/01-gradient-removal.png",
                "sha256-manifest.txt", "determinism.json"
            ).forEach { name -> assertTrue("Missing $name", Files.isRegularFile(primary.resolve(name))) }
            val files = regularFiles(primary)
            assertEquals(firstResult.fileCount, files.size)
            val alphaPng = requireNotNull(
                javax.imageio.ImageIO.read(primary.resolve("effective-alpha.png").toFile())
            )
            try {
                assertEquals(DataBuffer.TYPE_USHORT, alphaPng.raster.dataBuffer.dataType)
            } finally {
                alphaPng.flush()
            }
            val html = Files.readString(primary.resolve("index.html"))
            assertFalse(html.contains("http://"))
            assertFalse(html.contains("https://"))
            assertFalse(files.any {
                it.fileName.toString().lowercase().let { name ->
                    name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".apk") || name.endsWith(".zip") || name.endsWith(".dng")
                }
            })
        } finally {
            secondary.toFile().deleteRecursively()
            if (temporaryPrimary) primary.toFile().deleteRecursively()
        }
    }

    companion object {
        const val OUTPUT_PROPERTY = "astrophoto.skyMaskDiagnosticsOutputDir"
        const val OUTPUT_ENVIRONMENT = "ASTROPHOTO_SKY_MASK_DIAGNOSTICS_OUTPUT_DIR"

        private fun analyze(): SkyMaskReplayBundle = runBlocking {
            SkyMaskReplayDiagnosticRunner().analyze(UrbanWindow30ReplayFixture.fixture)
        }

        private fun regularFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile).sorted().toList()
        }

        private fun assertTreesByteIdentical(first: Path, second: Path) {
            val firstFiles = regularFiles(first).associateBy { first.relativize(it).toString().replace('\\', '/') }
            val secondFiles = regularFiles(second).associateBy { second.relativize(it).toString().replace('\\', '/') }
            assertEquals(firstFiles.keys, secondFiles.keys)
            firstFiles.forEach { (relative, path) ->
                assertArrayEquals(relative, Files.readAllBytes(path), Files.readAllBytes(secondFiles.getValue(relative)))
            }
        }

        private fun treeHash(root: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            regularFiles(root).forEach { path ->
                digest.update(root.relativize(path).toString().replace('\\', '/').toByteArray())
                digest.update(0)
                digest.update(Files.readAllBytes(path))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
