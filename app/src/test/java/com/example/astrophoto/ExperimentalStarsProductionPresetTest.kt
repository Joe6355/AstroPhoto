package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.model.StarEnhancementDiagnostics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.ExperimentalStarStrengthVariant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal data class ExperimentalStrengthEvaluation(
    val variant: ExperimentalStarStrengthVariant,
    val image: ArgbPixelImage,
    val diagnostics: StarEnhancementDiagnostics
)

class ExperimentalStarsProductionPresetTest {
    @Test fun urbanWindow30PresetIsDeterministicStrongerAndProtected() = runBlocking {
        val runner = SkyMaskReplayDiagnosticRunner()
        val baseline = runner.analyze(UrbanWindow30ReplayFixture.fixture)
        assertEquals(EXISTING_SAFE_ARGB_SHA256, ReplayDiagnosticHashing.sha256Argb(baseline.cleanComposed))
        assertEquals(EXISTING_SAFE_ARGB_SHA256, ReplayDiagnosticHashing.sha256Argb(baseline.finalCurrent))
        assertEquals(0, baseline.activeFileBackedMaximumChannelDifference)
        val productionSkyMask = SkyMask(
            baseline.cleanStack.width,
            baseline.cleanStack.height,
            BooleanArray(baseline.cleanStack.pixels.size) { index ->
                baseline.effectiveAlpha.alphaAt(
                    index % baseline.cleanStack.width,
                    index / baseline.cleanStack.width
                ) >= 0.98f
            }
        )
        val productionStars = JpegStarDetector().detect(
            baseline.cleanStack,
            productionSkyMask
        ).stars
        assertEquals(
            listOf(
                ExperimentalStarStrengthVariant.CURRENT,
                ExperimentalStarStrengthVariant.MEDIUM,
                ExperimentalStarStrengthVariant.STRONG
            ),
            ExperimentalStarStrengthVariant.entries
        )
        val first = evaluateVariants(runner, baseline, productionStars)
        val second = evaluateVariants(runner, baseline, productionStars)
        first.zip(second).forEach { (firstVariant, secondVariant) ->
            assertEquals(firstVariant.variant, secondVariant.variant)
            assertEquals(firstVariant.diagnostics, secondVariant.diagnostics)
            assertArrayEquals(firstVariant.image.pixels, secondVariant.image.pixels)
        }
        assertEquals(
            EXISTING_EXPERIMENTAL_ARGB_SHA256,
            ReplayDiagnosticHashing.sha256Argb(
                first.single { it.variant == ExperimentalStarStrengthVariant.CURRENT }.image
            )
        )
        assertEquals(
            EXPECTED_VARIANT_ARGB_SHA256,
            first.associate { it.variant to ReplayDiagnosticHashing.sha256Argb(it.image) }
        )

        val safetyByVariant = first.associate { evaluation ->
            evaluation.variant to evaluateSafety(baseline, evaluation.image, productionStars)
        }
        safetyByVariant.forEach { (variant, safety) ->
            println("Experimental Stars $variant safety=$safety")
        }
        val selected = first.lastOrNull { safetyByVariant.getValue(it.variant).safe }
        checkNotNull(selected) { "No fixed Experimental Stars strength variant passed hard safety" }
        assertEquals(ExperimentalStarStrengthVariant.PRODUCTION_SELECTED, selected.variant)
        val selectedSafety = safetyByVariant.getValue(selected.variant)
        assertEquals(0, selectedSafety.newDetections)
        assertEquals(0, selectedSafety.protectedChanges)
        assertEquals(0.0, selectedSafety.defectMaximum, 0.0)
        assertTrue(selectedSafety.maximumCentroidShift <= 0.75)
        assertTrue(selectedSafety.maximumWidthRatio <= 1.25)
        assertTrue("Selected production candidate was rejected: ${selectedSafety.rejectionReasons}", selectedSafety.processedAccepted)

        val current = first.single { it.variant == ExperimentalStarStrengthVariant.CURRENT }
        assertTrue(changedPixels(baseline.cleanComposed, selected.image) > changedPixels(baseline.cleanComposed, current.image))
        assertTrue(selected.diagnostics.enhanced > 0)
        assertEquals(current.diagnostics.considered, selected.diagnostics.considered)
        val configuredOutput = (
            System.getProperty(OUTPUT_PROPERTY) ?: System.getenv(OUTPUT_ENVIRONMENT)
        )?.takeIf(String::isNotBlank)
        configuredOutput?.let { configured ->
            val primary = Path.of(configured)
            val secondary = Files.createTempDirectory("experimental-stars-review-determinism")
            try {
                val firstWrite = ExperimentalStarsReviewWriter.write(baseline, first, selected.variant, primary)
                val secondWrite = ExperimentalStarsReviewWriter.write(baseline, second, selected.variant, secondary)
                assertEquals(firstWrite, secondWrite)
                assertTreesByteIdentical(primary, secondary)
            } finally {
                secondary.toFile().deleteRecursively()
            }
        }
        println("Experimental Stars selected=${selected.variant} changed=${changedPixels(baseline.cleanComposed, selected.image)} " +
            "accepted=${selected.diagnostics.enhanced}/${selected.diagnostics.considered} " +
            "hash=${ReplayDiagnosticHashing.sha256Argb(selected.image)}")
    }

    private suspend fun evaluateVariants(
        runner: SkyMaskReplayDiagnosticRunner,
        baseline: SkyMaskReplayBundle,
        stars: List<com.example.astrophoto.processing.jpeg.v2.model.DetectedStar>
    ): List<ExperimentalStrengthEvaluation> = ExperimentalStarStrengthVariant.entries.map { variant ->
        var diagnostics: StarEnhancementDiagnostics? = null
        val image = runner.runActiveFileBackedAdaptive(
            stackedSky = baseline.cleanStack,
            reference = baseline.reference,
            alpha = baseline.effectiveAlpha,
            profile = AstroProcessingProfile.EXPERIMENTAL_STARS,
            frameCount = baseline.acceptedOriginalFrameIndices.size,
            stars = stars,
            sensorDefectAffectedOutput = baseline.sensorDefectAffectedOutput,
            experimentalStrengthVariant = variant,
            onStarDiagnostics = { diagnostics = it }
        )
        ExperimentalStrengthEvaluation(variant, image, checkNotNull(diagnostics))
    }

    private data class VariantSafety(
        val safe: Boolean,
        val newDetections: Int,
        val protectedChanges: Int,
        val defectMaximum: Double,
        val maximumCentroidShift: Double,
        val maximumWidthRatio: Double,
        val maximumEllipticity: Double,
        val processedAccepted: Boolean,
        val rejectionReasons: List<String>
    )

    private fun evaluateSafety(
        baseline: SkyMaskReplayBundle,
        output: ArgbPixelImage,
        stars: List<com.example.astrophoto.processing.jpeg.v2.model.DetectedStar>
    ): VariantSafety {
        val starMetrics = SkyMaskReplayMath.strictStarMetricsForStages(
            fixture = baseline.fixture,
            cleanStack = baseline.cleanComposed,
            stages = listOf(SkyMaskStarStageInput("variant", output, baseline.effectiveAlpha)),
            refined = baseline.refinedMask,
            protection = baseline.foregroundProtection
        )
        val defects = baseline.fixture.strictSensorDefects.map { defect ->
            localResidual(baseline.cleanComposed, output, defect.x.toInt(), defect.y.toInt(), 4)
        }
        val newDetections = newDetections(baseline.cleanComposed, output, baseline.refinedMask).size
        val protectedChanges = protectedPixelChanges(baseline, output)
        val selection = selectReplayCandidate(
            reference = baseline.reference,
            clean = baseline.cleanComposed,
            processed = output,
            alpha = baseline.effectiveAlpha,
            coverage = baseline.validCoverage,
            stars = stars,
            modelScore = baseline.alignmentModelScore,
            acceptedFrames = baseline.acceptedOriginalFrameIndices.size,
            profile = AstroProcessingProfile.EXPERIMENTAL_STARS
        )
        val defectMaximum = defects.maxOf { it.maximum }
        val maximumCentroidShift = starMetrics.maxOf { it.centroidShiftFromClean }
        val maximumWidthRatio = starMetrics.maxOf { it.widthRatioFromClean }
        val maximumEllipticity = starMetrics.maxOf { it.ellipticity }
        val safe = newDetections == 0 && protectedChanges == 0 && defectMaximum == 0.0 &&
            maximumCentroidShift <= 0.75 && maximumWidthRatio <= 1.25 &&
            selection.processedAccepted
        return VariantSafety(
            safe,
            newDetections,
            protectedChanges,
            defectMaximum,
            maximumCentroidShift,
            maximumWidthRatio,
            maximumEllipticity,
            selection.processedAccepted,
            selection.processedRejectionReasons
        )
    }

    private data class Residual(val mean: Double, val maximum: Double)

    private fun localResidual(
        clean: ArgbPixelImage,
        output: ArgbPixelImage,
        centerX: Int,
        centerY: Int,
        radius: Int
    ): Residual {
        val values = mutableListOf<Double>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy > radius * radius) continue
            val x = centerX + dx
            val y = centerY + dy
            if (x !in 0 until clean.width || y !in 0 until clean.height) continue
            values += channelDifference(clean.pixelAt(x, y), output.pixelAt(x, y)).toDouble()
        }
        return Residual(values.average(), values.maxOrNull() ?: 0.0)
    }

    private fun protectedPixelChanges(baseline: SkyMaskReplayBundle, output: ArgbPixelImage): Int =
        output.pixels.indices.count { index ->
            val x = index % output.width
            val y = index / output.width
            val protected = baseline.effectiveAlpha.alphaAt(x, y) <= 0.0001f ||
                baseline.foregroundProtection.contains(x, y)
            protected && output.pixels[index] != baseline.cleanComposed.pixels[index]
        }

    private fun newDetections(
        clean: ArgbPixelImage,
        output: ArgbPixelImage,
        refinedMask: SkyMask
    ) = JpegStarDetector().detect(output, refinedMask).stars.filter { candidate ->
        JpegStarDetector().detect(clean, refinedMask).stars.none { known ->
            val radius = maxOf(2f, candidate.width * 0.75f)
            hypot((candidate.x - known.x).toDouble(), (candidate.y - known.y).toDouble()) < radius
        }
    }

    private fun changedPixels(first: ArgbPixelImage, second: ArgbPixelImage): Int =
        first.pixels.indices.count { first.pixels[it] != second.pixels[it] }

    private fun channelDifference(first: Int, second: Int): Int = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    )

    private fun assertTreesByteIdentical(first: Path, second: Path) {
        fun files(root: Path): Map<String, Path> = Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile).toList().associateBy {
                root.relativize(it).toString().replace('\\', '/')
            }
        }
        val firstFiles = files(first)
        val secondFiles = files(second)
        assertEquals(firstFiles.keys, secondFiles.keys)
        firstFiles.forEach { (relative, path) ->
            assertArrayEquals(relative, Files.readAllBytes(path), Files.readAllBytes(secondFiles.getValue(relative)))
        }
    }

    companion object {
        const val OUTPUT_PROPERTY = "astrophoto.experimentalStarsReviewOutputDir"
        const val OUTPUT_ENVIRONMENT = "ASTROPHOTO_EXPERIMENTAL_STARS_REVIEW_OUTPUT_DIR"
        private const val EXISTING_SAFE_ARGB_SHA256 =
            "786052b443af8fca5484beafa5482fcfa53430a4cb685b89a2e7a12d1551daef"
        private const val EXISTING_EXPERIMENTAL_ARGB_SHA256 =
            "81940611c51893f47dd2077696dc816c926e4ec8c66267c3a9982cae6358dce5"
        private val EXPECTED_VARIANT_ARGB_SHA256 = mapOf(
            ExperimentalStarStrengthVariant.CURRENT to
                "81940611c51893f47dd2077696dc816c926e4ec8c66267c3a9982cae6358dce5",
            ExperimentalStarStrengthVariant.MEDIUM to
                "70144606bb93c99d7969a4c41d9101a295efdab0b96086527a7267ec0113c347",
            ExperimentalStarStrengthVariant.STRONG to
                "9475f586800e006d0818ca953002284e9e8f5208beb54c88c7a23db47d9c5e4b"
        )
    }
}
