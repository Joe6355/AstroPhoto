package com.example.astrophoto

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveAsinhAblationTest {
    @Test fun currentIsPixelIdenticalToCanonicalAndConfigurableReplay() {
        val value = analyze()
        val current = value.variants.single { it.id == AdaptiveAsinhAblationVariantId.CURRENT }
        assertArrayEquals(value.baseline.composedCurrent.pixels, current.composed.pixels)
        assertArrayEquals(value.baseline.processedSky.pixels, current.processedSky.pixels)
        assertEquals(0, value.baseline.activeFileBackedMaximumChannelDifference)
        assertEquals(0, value.baseline.activeFileBackedDifferentPixelCount)
        assertEquals(0, value.configurableCurrentMaximumChannelDifference)
        assertEquals(0, value.configurableCurrentDifferentPixelCount)
        assertFalse(value.productionSourceChanged)
        assertEquals("CLEAN_STACK", current.selection.type.name)
        assertFalse(current.selection.processedAccepted)
        assertEquals(
            listOf("sky_mad_increased_excessively", "banding_increased_excessively"),
            current.selection.processedRejectionReasons
        )
        assertEquals(
            "c52a0100c241a01a0d39535eec16d242fc9d14cea18d75887d7755c6ed65c98d",
            value.baselineHashes.cleanInputArgbSha256
        )
        assertEquals(
            "21c81eb44bb8710bcb59ffab8fb9aa5f60e5f3c40dd483278a8e525bb0bb8adf",
            value.baselineHashes.currentComposedArgbSha256
        )
        assertEquals(
            "786052b443af8fca5484beafa5482fcfa53430a4cb685b89a2e7a12d1551daef",
            value.baselineHashes.currentSelectedFinalArgbSha256
        )
        assertEquals(
            "984cb0f5f9ce0e611830c894e8d59580367ca98871e59299d4c9fedd26820f51",
            value.baselineHashes.effectiveAlphaFloat32LeSha256
        )
    }

    @Test fun contractsIsolateOnlyTheDeclaredVariables() {
        val value = analyze()
        assertEquals(AdaptiveAsinhAblationVariantId.entries, value.variants.map { it.id })
        assertEquals(AdaptiveAsinhAblationVariantId.entries, value.contracts.map { it.variant })
        assertEquals(7, value.variants.size)
        assertTrue(value.contracts.all { it.available && it.unavailableReason == null })
        assertEquals(0, value.contracts.single {
            it.variant == AdaptiveAsinhAblationVariantId.CURRENT
        }.variant.changedVariables)
        AdaptiveAsinhAblationVariantId.entries.drop(1).forEach { id ->
            assertEquals(1, value.contracts.single { it.variant == id }.variant.changedVariables)
            assertTrue(value.contracts.single { it.variant == id }.variant.rootCauseEligible)
        }
        value.contracts.forEach { contract ->
            assertEquals(value.baselineHashes.backgroundNeutralizedArgbSha256, contract.sharedInputArgbSha256)
            assertEquals(value.baselineHashes.initialMaskSha256, contract.initialMaskSha256)
            assertEquals(value.baselineHashes.refinedMaskSha256, contract.refinedMaskSha256)
            assertEquals(value.baselineHashes.effectiveAlphaFloat32LeSha256,
                contract.effectiveAlphaFloat32LeSha256)
            assertEquals((1..21).toList() + 25, contract.acceptedOriginalIndices)
            assertEquals(listOf(22, 23, 24, 26, 27, 28, 29, 30), contract.rejectedOriginalIndices)
            assertEquals(value.baseline.alignmentTransformFingerprint, contract.alignmentTransformFingerprint)
            assertTrue(contract.qualityPolicy.contains("ResultSelectionPolicy"))
        }
        val currentAlpha = value.baseline.effectiveAlpha
        value.variants.forEach { variant ->
            assertAlphaEquals(currentAlpha, variant.compositionAlpha)
            assertEquals(variant.id.blendMode, variant.blendMode)
            if (variant.id == AdaptiveAsinhAblationVariantId.CURRENT) {
                assertEquals(ReplayStretchOperationMode.PRODUCTION_CURRENT, variant.operationMode)
            } else {
                assertEquals(ReplayStretchOperationMode.SQRT_ALPHA, variant.operationMode)
            }
        }
        fun applied(id: AdaptiveAsinhAblationVariantId): Float = value.variants.single {
            it.id == id
        }.stretchDiagnostics.appliedBlend
        assertEquals(value.blendFormula.currentAppliedBlend,
            applied(AdaptiveAsinhAblationVariantId.CURRENT), 0f)
        assertEquals(0.25f, applied(AdaptiveAsinhAblationVariantId.HONEST_BLEND), 0f)
        assertEquals(0.25f, applied(AdaptiveAsinhAblationVariantId.CAPPED_BLEND_025), 0f)
        assertEquals(0.35f, applied(AdaptiveAsinhAblationVariantId.CAPPED_BLEND_035), 0f)
        assertEquals(0.50f, applied(AdaptiveAsinhAblationVariantId.CAPPED_BLEND_050), 0f)
        assertEquals(0.75f, applied(AdaptiveAsinhAblationVariantId.CAPPED_BLEND_075), 0f)
        assertEquals(value.blendFormula.configuredContribution,
            applied(AdaptiveAsinhAblationVariantId.TARGET_MEDIAN_DISABLED), 0f)
        assertEquals(1f, value.blendFormula.targetBlend, 0f)
        assertTrue(value.blendFormula.rawTargetBlend > 1f)
        assertTrue(value.blendFormula.targetMedianContribution > value.blendFormula.configuredContribution)
        assertArrayEquals(
            value.variants.single { it.id == AdaptiveAsinhAblationVariantId.HONEST_BLEND }.composed.pixels,
            value.variants.single { it.id == AdaptiveAsinhAblationVariantId.CAPPED_BLEND_025 }.composed.pixels
        )
    }

    @Test fun metricsUseExactlySixStrictStarsAndRemainFinite() {
        val value = analyze()
        val ids = value.baseline.fixture.strictReferenceStarLabels.map { it.id }
        assertEquals(6, ids.size)
        assertEquals(6 * value.variants.size, value.strictStarMetrics.size)
        value.variants.forEach { variant ->
            assertEquals(ids, value.strictStarMetrics.filter { it.variant == variant.id }.map { it.starId })
            assertEquals(
                listOf(
                    "00-background-neutralized", "01-adaptive-stretch", "02-chroma-reduction",
                    "03-star-enhancement", "04-final-safety", "05-background-match",
                    "06-composed", "07-selected-or-rejected"
                ),
                variant.stages.map { it.id }
            )
        }
        assertFalse(value.baseline.fixture.scoredGroundTruth.any {
            it.classification == ProvisionalSourceClass.UNCERTAIN
        })
        assertTrue(value.strictStarMetrics.all { metric ->
            listOf(
                metric.apertureFluxRetention, metric.peakRetention, metric.centroidShift,
                metric.widthRatio, metric.ellipticityChange, metric.localContrast,
                metric.localContrastRetention, metric.chromaResidual, metric.centerAlpha,
                metric.distanceToBoundary
            ).all(Double::isFinite)
        })
        assertTrue(value.globalMetrics.all { metric ->
            listOf(
                metric.skyMad, metric.bandingProxy, metric.boundaryEdgeExcess,
                metric.meanHaloScore, metric.meanLeakageScore, metric.foregroundMeanChange,
                metric.luminanceMean, metric.luminanceMedian, metric.chromaResidual,
                metric.sensorDefectResidual
            ).all(Double::isFinite)
        })
        assertTrue(value.boundaryMetrics.all {
            it.transitionBandVariance.isFinite() && it.window.haloScore.isFinite() &&
                it.window.leakageScore.isFinite()
        })
        assertTrue(value.rootCause in AdaptiveAsinhRootCause.entries)
        assertEquals(
            AdaptiveAsinhRootCause.TARGET_MEDIAN_ESCALATION_CONFIRMED,
            value.rootCause
        )
        assertTrue(value.productionCandidate == null)
        assertFalse(value.globalMetrics.any { it.acceptableProductionCandidate })
        assertEquals(
            listOf(
                AdaptiveAsinhAblationVariantId.HONEST_BLEND,
                AdaptiveAsinhAblationVariantId.CAPPED_BLEND_025,
                AdaptiveAsinhAblationVariantId.TARGET_MEDIAN_DISABLED
            ),
            value.globalMetrics.filter { it.processedAccepted }.map { it.variant }
        )
        assertTrue(value.globalMetrics.filter { it.processedAccepted }.all {
            it.bandingProxy > value.cleanStackMetrics.bandingProxy
        })
        value.productionCandidate?.let { candidate ->
            assertTrue(value.globalMetrics.any {
                it.acceptableProductionCandidate && candidate.isNotBlank()
            })
        }
    }

    @Test fun generatedReportIsCompleteAndSecondRunByteIdentical() = runBlocking {
        val configured = (System.getProperty(OUTPUT_PROPERTY) ?: System.getenv(OUTPUT_ENVIRONMENT))
            ?.takeIf(String::isNotBlank)
        val temporaryPrimary = configured == null
        val primary = configured?.let(Path::of) ?: Files.createTempDirectory("adaptive-asinh-a")
        val secondary = Files.createTempDirectory("adaptive-asinh-b")
        val groundTruth = UrbanWindow30ReplayFixture.directory.toPath().resolve("ground-truth.csv")
        val groundTruthBefore = Files.readAllBytes(groundTruth)
        val mainSourceBefore = treeHash(Path.of("src/main"))
        val fixtureHashesBefore = fixture.frames.map(ReplayDiagnosticHashing::sha256Argb)
        try {
            var firstBundle: AdaptiveAsinhAblationBundle? =
                AdaptiveAsinhAblationDiagnosticRunner().analyze(fixture)
            val firstResult = AdaptiveAsinhAblationReportWriter.write(
                requireNotNull(firstBundle),
                primary
            )
            firstBundle = null
            System.gc()
            val secondBundle = AdaptiveAsinhAblationDiagnosticRunner().analyze(fixture)
            val secondResult = AdaptiveAsinhAblationReportWriter.write(secondBundle, secondary)
            assertEquals(firstResult, secondResult)
            assertTreesByteIdentical(primary, secondary)
            assertArrayEquals(groundTruthBefore, Files.readAllBytes(groundTruth))
            assertEquals(mainSourceBefore, treeHash(Path.of("src/main")))
            assertEquals(fixtureHashesBefore, fixture.frames.map(ReplayDiagnosticHashing::sha256Argb))
            listOf(
                "baseline-hashes.json", "clean-stack-metrics.json", "current-formula.md",
                "ablation-contract.json",
                "ablation-summary.csv", "ablation-stage-metrics.csv",
                "strict-star-ablation.csv", "boundary-ablation.csv",
                "quality-policy-results.csv", "root-cause.json", "report-summary.md",
                "index.html", "sha256-manifest.txt", "determinism.json"
            ).forEach { name -> assertTrue("Missing $name", Files.isRegularFile(primary.resolve(name))) }
            secondBundle.variants.forEach { variant ->
                val directory = primary.resolve(variant.id.stableId)
                assertTrue(Files.isDirectory(directory))
                variant.stages.forEach { stage ->
                    assertTrue(Files.isRegularFile(directory.resolve("${stage.id}.png")))
                    assertTrue(Files.isRegularFile(directory.resolve("${stage.id}.argb32be")))
                }
                assertTrue(Files.isRegularFile(directory.resolve("composition-alpha.f32le")))
                assertTrue(Files.isRegularFile(directory.resolve("variant.json")))
            }
            val files = regularFiles(primary)
            assertEquals(firstResult.fileCount, files.size)
            val html = Files.readString(primary.resolve("index.html"))
            assertTrue(html.contains("TEST-ONLY ABLATION — PRODUCTION PROCESSING UNCHANGED"))
            assertFalse(html.contains("http://"))
            assertFalse(html.contains("https://"))
            assertFalse(files.any { path ->
                path.fileName.toString().lowercase().let { name ->
                    name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".apk") ||
                        name.endsWith(".aab") || name.endsWith(".zip") || name.endsWith(".dng")
                }
            })
        } finally {
            secondary.toFile().deleteRecursively()
            if (temporaryPrimary) primary.toFile().deleteRecursively()
        }
    }

    companion object {
        const val OUTPUT_PROPERTY = "astrophoto.adaptiveAsinhAblationOutputDir"
        const val OUTPUT_ENVIRONMENT = "ASTROPHOTO_ADAPTIVE_ASINH_ABLATION_OUTPUT_DIR"

        private val fixture: Stage6RegressionFixture
            get() = UrbanWindow30ReplayFixture.fixture
        private fun analyze(): AdaptiveAsinhAblationBundle =
            runBlocking { AdaptiveAsinhAblationDiagnosticRunner().analyze(fixture) }

        private fun assertAlphaEquals(first: com.example.astrophoto.processing.jpeg.v2.model.AlphaMask,
                                      second: com.example.astrophoto.processing.jpeg.v2.model.AlphaMask) {
            assertEquals(first.width, second.width)
            assertEquals(first.height, second.height)
            for (y in 0 until first.height) for (x in 0 until first.width) {
                assertEquals(first.alphaAt(x, y), second.alphaAt(x, y), 0f)
            }
        }

        private fun regularFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile).sorted().toList()
        }

        private fun assertTreesByteIdentical(first: Path, second: Path) {
            val firstFiles = regularFiles(first).associateBy {
                first.relativize(it).toString().replace('\\', '/')
            }
            val secondFiles = regularFiles(second).associateBy {
                second.relativize(it).toString().replace('\\', '/')
            }
            assertEquals(firstFiles.keys, secondFiles.keys)
            firstFiles.forEach { (relative, path) ->
                assertArrayEquals(
                    relative,
                    Files.readAllBytes(path),
                    Files.readAllBytes(secondFiles.getValue(relative))
                )
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
