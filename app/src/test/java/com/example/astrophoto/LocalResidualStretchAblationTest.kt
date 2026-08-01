package com.example.astrophoto

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResidualStretchAblationTest {
    @Test fun matrixKeepsProductionCurrentAndIsolatesOnlyResidualStrength() {
        val value = analyzeCached()
        assertEquals(LocalResidualStretchVariantId.entries, value.variants.map { it.id })
        val current = value.variants.single { it.id == LocalResidualStretchVariantId.CURRENT }
        assertArrayEquals(value.baseline.composedCurrent.pixels, current.output.pixels)
        assertArrayEquals(value.baseline.processedSky.pixels, current.processedSky.pixels)
        assertEquals(0, value.baseline.activeFileBackedMaximumChannelDifference)
        assertEquals(0, value.baseline.activeFileBackedDifferentPixelCount)
        assertFalse(value.productionSourceChanged)

        val residuals = value.variants.filter { it.id.productionCandidateEligible }
        assertEquals(listOf(0.12f, 0.24f, 0.36f), residuals.map { it.id.strength })
        val fixed = residuals.map { requireNotNull(it.localDiagnostics).parameters.copy(strength = 0f) }
        assertEquals(1, fixed.distinct().size)
        assertTrue(residuals.all {
            val diagnostics = requireNotNull(it.localDiagnostics)
            diagnostics.backgroundChangedPixels == 0 &&
                diagnostics.negativeResidualChangedPixels == 0 &&
                diagnostics.supportedPixels > 0 && diagnostics.changedPixels > 0
        })
        assertEquals(
            value.preparedInput.inputArgbSha256,
            ReplayDiagnosticHashing.sha256Argb(value.baseline.postProcessingStages.single {
                it.id == "02-background-neutralization"
            }.image)
        )
    }

    @Test fun metricsCoverSixStarsTwoDefectsAndEveryAcceptanceCondition() {
        val value = analyzeCached()
        assertEquals(6 * value.variants.size, value.strictStarMetrics.size)
        assertEquals(2 * value.variants.size, value.sensorDefectMetrics.size)
        assertEquals(value.variants.size, value.detectionMetrics.size)
        assertEquals(value.variants.size, value.globalMetrics.size)
        value.variants.forEach { variant ->
            assertEquals(6, value.strictStarMetrics.count { it.variant == variant.id })
            assertEquals(2, value.sensorDefectMetrics.count { it.variant == variant.id })
            assertEquals(1, value.detectionMetrics.count { it.variant == variant.id })
        }
        assertTrue(value.strictStarMetrics.all { metric ->
            listOf(
                metric.baselineLocalContrast, metric.apertureFluxRetention, metric.peakRetention,
                metric.centroidShift, metric.widthRatio, metric.ellipticityChange,
                metric.localContrast, metric.localContrastRetention, metric.chromaResidual
            ).all(Double::isFinite)
        })
        assertTrue(value.globalMetrics.all { metric ->
            listOf(
                metric.skyMad, metric.bandingProxy, metric.boundaryEdgeExcess,
                metric.meanHaloScore, metric.meanLeakageScore, metric.foregroundMeanChange,
                metric.luminanceMean, metric.luminanceMedian, metric.chromaResidual,
                metric.sensorDefectResidual, metric.weakStarMedianContrastGain,
                metric.maximumStrictStarWidthRatio
            ).all(Double::isFinite)
        })
        val eligible = value.globalMetrics.filter { it.acceptableProductionCandidate }
        if (eligible.isEmpty()) {
            assertEquals(LocalResidualStretchDecision.LOCAL_RESIDUAL_STRETCH_REJECTED, value.decision)
            assertEquals(null, value.productionCandidate)
        } else {
            assertEquals(LocalResidualStretchDecision.LOCAL_RESIDUAL_STRETCH_CANDIDATE_FOUND, value.decision)
            assertNotNull(value.productionCandidate)
        }
    }

    @Test fun residualOperationPreservesBackgroundNegativeResidualAndLinearChromaticity() {
        val value = analyzeCached()
        value.variants.filter { it.id.productionCandidateEligible }.forEach { variant ->
            val diagnostics = requireNotNull(variant.localDiagnostics)
            assertEquals(0, diagnostics.backgroundChangedPixels)
            assertEquals(0, diagnostics.negativeResidualChangedPixels)
            assertTrue(diagnostics.meanPositiveLuminanceDelta > 0.0)
            assertTrue(diagnostics.maximumPositiveLuminanceDelta > 0.0)
            assertTrue(diagnostics.meanLinearChromaticityShift.isFinite())
            assertTrue(diagnostics.maximumLinearChromaticityShift.isFinite())
        }
    }

    @Test fun generatedReportIsCompleteAndSecondRunByteIdentical() = runBlocking {
        val configured = (System.getProperty(OUTPUT_PROPERTY) ?: System.getenv(OUTPUT_ENVIRONMENT))
            ?.takeIf(String::isNotBlank)
        val temporaryPrimary = configured == null
        val primary = configured?.let(Path::of) ?: Files.createTempDirectory("local-residual-a")
        val secondary = Files.createTempDirectory("local-residual-b")
        val groundTruth = UrbanWindow30ReplayFixture.directory.toPath().resolve("ground-truth.csv")
        val groundTruthBefore = Files.readAllBytes(groundTruth)
        val mainSourceBefore = treeHash(Path.of("src/main"))
        val fixtureHashesBefore = fixture.frames.map(ReplayDiagnosticHashing::sha256Argb)
        try {
            var firstBundle: LocalResidualStretchAblationBundle? = takeCachedOrAnalyze()
            val firstResult = LocalResidualStretchAblationReportWriter.write(
                requireNotNull(firstBundle), primary
            )
            firstBundle = null
            cached = null
            System.gc()
            val secondBundle = LocalResidualStretchAblationDiagnosticRunner().analyze(fixture)
            val secondResult = LocalResidualStretchAblationReportWriter.write(secondBundle, secondary)
            assertEquals(firstResult, secondResult)
            assertTreesByteIdentical(primary, secondary)
            assertArrayEquals(groundTruthBefore, Files.readAllBytes(groundTruth))
            assertEquals(mainSourceBefore, treeHash(Path.of("src/main")))
            assertEquals(fixtureHashesBefore, fixture.frames.map(ReplayDiagnosticHashing::sha256Argb))
            listOf(
                "algorithm-and-parameters.md", "baseline-hashes.json", "ablation-summary.csv",
                "strict-star-metrics.csv", "sensor-defect-metrics.csv", "boundary-metrics.csv",
                "detection-metrics.csv", "quality-policy-results.csv", "decision.json",
                "report-summary.md", "index.html", "sha256-manifest.txt", "determinism.json"
            ).forEach { name -> assertTrue("Missing $name", Files.isRegularFile(primary.resolve(name))) }
            secondBundle.variants.forEach { variant ->
                val directory = primary.resolve(variant.id.stableId)
                assertTrue(Files.isRegularFile(directory.resolve("full-resolution.png")))
                assertTrue(Files.isRegularFile(directory.resolve("full-resolution.argb32be")))
                assertTrue(Files.isRegularFile(directory.resolve("minus-clean-stack-x8.png")))
                assertTrue(Files.isRegularFile(directory.resolve("stretch-operation.png")))
                assertTrue(Files.isRegularFile(directory.resolve("stretch-operation.argb32be")))
                assertTrue(Files.isRegularFile(directory.resolve("variant.json")))
            }
            val files = regularFiles(primary)
            assertEquals(firstResult.fileCount, files.size)
            val html = Files.readString(primary.resolve("index.html"))
            assertTrue(html.contains("TEST-ONLY ABLATION - PRODUCTION PROCESSING UNCHANGED"))
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
        const val OUTPUT_PROPERTY = "astrophoto.localResidualStretchAblationOutputDir"
        const val OUTPUT_ENVIRONMENT = "ASTROPHOTO_LOCAL_RESIDUAL_STRETCH_ABLATION_OUTPUT_DIR"

        private var cached: LocalResidualStretchAblationBundle? = null
        private val fixture: Stage6RegressionFixture
            get() = UrbanWindow30ReplayFixture.fixture

        private fun analyzeCached(): LocalResidualStretchAblationBundle =
            cached ?: runBlocking { LocalResidualStretchAblationDiagnosticRunner().analyze(fixture) }
                .also { cached = it }

        private suspend fun takeCachedOrAnalyze(): LocalResidualStretchAblationBundle =
            cached ?: LocalResidualStretchAblationDiagnosticRunner().analyze(fixture)

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
