package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.ReplayGlobalToneMapper
import com.joe6355.astrophoto.ReplayToneAnchors
import com.joe6355.astrophoto.SavedProcessedImage
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneWriter
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import java.io.File
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnhancedGlobalToneProductionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun approvedProductionTransformMatchesReplayPixelExactly() {
        val production = GlobalToneTransform()
        val replay = ReplayGlobalToneMapper()
        val productionAnchors = GlobalToneAnchors(
            toeStart = 0.00125,
            toeEnd = 0.01875
        )
        val replayAnchors = ReplayToneAnchors(
            toeStart = productionAnchors.toeStart,
            toeEnd = productionAnchors.toeEnd
        )
        val colors = buildList {
            for (value in 0..255) {
                add((0xFF shl 24) or (value shl 16) or (value shl 8) or value)
            }
            val random = Random(0x454E48414E434544L)
            repeat(4_096) {
                add(random.nextInt())
            }
        }

        colors.forEach { color ->
            val expected = replay.transformEncodedColor(
                color = color,
                anchors = replayAnchors,
                gain = GlobalToneTransform.APPROVED_GAIN
            )
            val actual = production.transformArgb(
                argb = color,
                anchors = productionAnchors
            )

            assertEquals("ARGB mismatch for ${color.toUInt().toString(16)}", expected.color, actual.argb)
            assertEquals(
                "Scale-limit mismatch for ${color.toUInt().toString(16)}",
                expected.scaleLimited,
                actual.scaleLimited
            )
            assertEquals(
                "Linear maximum mismatch for ${color.toUInt().toString(16)}",
                expected.maximumLinearChannel,
                actual.maximumLinearChannel,
                1e-15
            )
        }
    }

    @Test
    fun fileBackedTransformLeavesRecoveredStarsBaselineByteIdentical() {
        val baselinePixels = arrayOf(
            intArrayOf(0xFF000000.toInt(), 0xFF070B13.toInt(), 0xFF151B25.toInt(), 0xFFFFFFFF.toInt()),
            intArrayOf(0xFF100805.toInt(), 0xFF253A51.toInt(), 0xFF617B91.toInt(), 0xFFF9E8C8.toInt())
        )
        val baselineWriter = FileBackedImageWriter(
            file = File(temporaryFolder.root, "recovered-stars.argb"),
            width = baselinePixels.first().size,
            height = baselinePixels.size
        )
        baselinePixels.forEachIndexed { y, row -> baselineWriter.writeRow(y, row) }
        val baseline = baselineWriter.finish()
        val baselineBytesBefore = baseline.file.readBytes()
        val baselineHashBefore = fileBackedPixelHash(baseline)

        val candidateWriter = FileBackedImageWriter(
            file = File(temporaryFolder.root, "enhanced.argb"),
            width = baseline.width,
            height = baseline.height
        )
        val generated = FileBackedGlobalToneTransformer().transform(
            baseline = baseline,
            writer = candidateWriter,
            anchors = GlobalToneAnchors(toeStart = 0.0005, toeEnd = 0.02)
        )

        assertArrayEquals(baselineBytesBefore, baseline.file.readBytes())
        assertEquals(baselineHashBefore, fileBackedPixelHash(baseline))
        assertNotEquals(baselineHashBefore, fileBackedPixelHash(generated.image))
        assertFalse(baseline.file.absolutePath == generated.image.file.absolutePath)
    }

    @Test
    fun enhancedSaveFailureIsNonFatalAndReleasesCandidate() = runBlocking {
        val image = FileBackedImage(
            file = File(temporaryFolder.root, "accepted-candidate.argb"),
            width = 1,
            height = 1
        )
        image.file.writeBytes(byteArrayOf(0, 0, 0, 0))
        val candidate = acceptedCandidate(image)
        var released: FileBackedImage? = null

        val outcome = publishOptionalEnhanced(
            createCandidate = { candidate },
            saveCandidate = { throw IllegalStateException("simulated_save_failure") },
            releaseCandidate = {
                released = it
                it.file.delete()
            }
        )

        assertTrue(outcome is EnhancedAncillaryOutcome.Failed)
        outcome as EnhancedAncillaryOutcome.Failed
        assertEquals("simulated_save_failure", outcome.reason)
        assertSame(candidate, outcome.candidate)
        assertSame(image, released)
        assertFalse(image.file.exists())
    }

    @Test
    fun acceptedEnhancedIsSavedAndCandidateReleased() = runBlocking {
        val image = FileBackedImage(
            file = File(temporaryFolder.root, "saved-candidate.argb"),
            width = 1,
            height = 1
        )
        val candidate = acceptedCandidate(image)
        val saved = SavedProcessedImage(
            fileName = "Enhanced_20260727_120000.png",
            displayPath = "Processed/Enhanced_20260727_120000.png",
            contentUri = "content://images/42",
            filePath = null
        )
        var saveCalls = 0
        var released = false

        val outcome = publishOptionalEnhanced(
            createCandidate = { candidate },
            saveCandidate = {
                saveCalls++
                assertSame(image, it)
                saved
            },
            releaseCandidate = {
                assertSame(image, it)
                released = true
            }
        )

        assertTrue(outcome is EnhancedAncillaryOutcome.Saved)
        outcome as EnhancedAncillaryOutcome.Saved
        assertSame(saved, outcome.result)
        assertEquals(1, saveCalls)
        assertTrue(released)
    }

    @Test
    fun rejectedEnhancedIsNotSavedAndCandidateIsReleased() = runBlocking {
        val image = FileBackedImage(
            file = File(temporaryFolder.root, "rejected-candidate.argb"),
            width = 1,
            height = 1
        )
        val accepted = acceptedCandidate(image)
        val rejected = accepted.copy(
            validation = accepted.validation.copy(
                accepted = false,
                hardFailureReasons = listOf("confirmed_star_lost")
            )
        )
        var saveCalls = 0
        var released = false

        val outcome = publishOptionalEnhanced(
            createCandidate = { rejected },
            saveCandidate = {
                saveCalls++
                error("must_not_save_rejected_candidate")
            },
            releaseCandidate = { released = true }
        )

        assertTrue(outcome is EnhancedAncillaryOutcome.Rejected)
        assertEquals(0, saveCalls)
        assertTrue(released)
    }

    @Test
    fun enhancedCreationExceptionIsNonFatal() = runBlocking {
        val outcome = publishOptionalEnhanced(
            createCandidate = { error("simulated_creation_failure") },
            saveCandidate = { error("must_not_save") },
            releaseCandidate = { error("must_not_release") }
        )

        assertTrue(outcome is EnhancedAncillaryOutcome.Failed)
        outcome as EnhancedAncillaryOutcome.Failed
        assertEquals("simulated_creation_failure", outcome.reason)
        assertEquals(null, outcome.candidate)
    }

    @Test
    fun explicitCancellationStillCancelsInsteadOfPublishingFallbackState() = runBlocking {
        val image = FileBackedImage(
            file = File(temporaryFolder.root, "cancelled-candidate.argb"),
            width = 1,
            height = 1
        )
        val candidate = acceptedCandidate(image)
        var released = false
        var cancelled = false

        try {
            publishOptionalEnhanced(
                createCandidate = { candidate },
                saveCandidate = { throw CancellationException("stop") },
                releaseCandidate = { released = true }
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertTrue(released)
    }

    @Test
    fun reportLabelsSeparateFixedSupportAndIndependentDetectorCounts() {
        val metrics = acceptedCandidate(
            FileBackedImage(
                file = File(temporaryFolder.root, "metric-candidate.argb"),
                width = 1,
                height = 1
            )
        ).validation.metrics.copy(
            evaluatedStarCount = 4,
            baselineVisibleStarCount = 2,
            candidateVisibleStarCount = 2
        ).asReportMetrics()

        assertEquals(4f, metrics.getValue("fixedSupportEvaluatedStarCount"))
        assertEquals(
            2f,
            metrics.getValue("baselineIndependentDetectorVisibleStarCount")
        )
        assertEquals(
            2f,
            metrics.getValue("candidateIndependentDetectorVisibleStarCount")
        )
    }

    @Test
    fun pointwiseToneMappingGeometryChangesAreWarningsNotRejections() {
        val warnings = enhancedGlobalToneGeometryWarnings(
            medianWidthChange = 0.04,
            maximumWidthChange = 0.14,
            medianEllipticityChange = 0.06,
            maximumEllipticityChange = 0.58
        )

        assertEquals(4, warnings.size)
        assertTrue(warnings.all { it.endsWith("after_tone_mapping") })
    }

    @Test
    fun productionTriesVisibleGainBeforeConservativeFallback() {
        assertEquals(
            listOf(0.80, 0.60, GlobalToneTransform.APPROVED_GAIN),
            EnhancedGlobalToneProcessor.PRODUCTION_GAINS
        )
        assertTrue(
            EnhancedGlobalToneProcessor.PRODUCTION_GAINS.zipWithNext()
                .all { (stronger, weaker) -> stronger > weaker }
        )
    }

    @Test
    fun protectedLuminanceDenoiseReducesBackgroundVariationWithoutTouchingStarOrForeground() {
        val size = 25
        val baselineWriter = FileBackedImageWriter(
            file = File(temporaryFolder.root, "denoise-baseline.argb"),
            width = size,
            height = size
        )
        repeat(size) { y ->
            baselineWriter.writeRow(
                y,
                IntArray(size) { x ->
                    when {
                        x == 12 && y == 12 -> 0xFFE0E0E0.toInt()
                        (x - 12) * (x - 12) + (y - 12) * (y - 12) <= 4 ->
                            0xFF707070.toInt()
                        (x * 17 + y * 31) % 3 == 0 -> 0xFF242424.toInt()
                        else -> 0xFF101010.toInt()
                    }
                }
            )
        }
        val baseline = baselineWriter.finish()
        val alphaWriter = FileBackedFloatPlaneWriter(
            file = File(temporaryFolder.root, "denoise-alpha.f32"),
            width = size,
            height = size
        )
        repeat(size) { y ->
            alphaWriter.writeRow(y, FloatArray(size) { if (y >= 22) 0f else 1f })
        }
        val alpha = alphaWriter.finish()
        val stars = listOf(
            DetectedStar(
                x = 12f,
                y = 12f,
                flux = 1f,
                localBackground = 0.01f,
                localContrast = 0.5f,
                width = 2f,
                ellipticity = 0.1f,
                confidence = 1f
            )
        )
        val pipelineFiles = TemporaryPipelineFiles.create(
            temporaryFolder.newFolder("denoise-pipeline")
        )
        try {
            val store = ResultCandidateStore(pipelineFiles)
            val denoiser = ProtectedLuminanceDenoiser()
            val prepared = denoiser.prepareNoiseMap(baseline, alpha, stars, store)
            val result = try {
                denoiser.apply(baseline, alpha, stars, prepared, store)
            } finally {
                store.deleteTemporary(prepared.plane)
            }

            assertTrue(result.metrics.eligiblePixelCount > 0)
            assertTrue(result.metrics.changedPixelCount > 0)
            FileBackedImageReader(baseline).use { before ->
                FileBackedImageReader(result.image).use { after ->
                    assertEquals(before.argbAt(12, 12), after.argbAt(12, 12))
                    assertEquals(before.argbAt(5, 23), after.argbAt(5, 23))
                    assertTrue(backgroundRoughness(after) < backgroundRoughness(before))
                }
            }
        } finally {
            pipelineFiles.close()
        }
    }

    @Test
    fun protectedFaintStarEnhancementRaisesCoreWithoutChangingBackground() {
        val size = 25
        val baselineWriter = FileBackedImageWriter(
            file = File(temporaryFolder.root, "faint-star-baseline.argb"),
            width = size,
            height = size
        )
        repeat(size) { y ->
            baselineWriter.writeRow(
                y,
                IntArray(size) { x ->
                    when {
                        x == 12 && y == 12 -> 0xFF484848.toInt()
                        (x - 12) * (x - 12) + (y - 12) * (y - 12) <= 4 ->
                            0xFF303030.toInt()
                        else -> 0xFF101010.toInt()
                    }
                }
            )
        }
        val baseline = baselineWriter.finish()
        val alphaWriter = FileBackedFloatPlaneWriter(
            file = File(temporaryFolder.root, "faint-star-alpha.f32"),
            width = size,
            height = size
        )
        repeat(size) { y -> alphaWriter.writeRow(y, FloatArray(size) { if (y >= 22) 0f else 1f }) }
        val alpha = alphaWriter.finish()
        val stars = listOf(
            DetectedStar(12f, 12f, 1f, 0.005f, 0.05f, 2f, 0.1f, 1f)
        )
        val pipelineFiles = TemporaryPipelineFiles.create(
            temporaryFolder.newFolder("faint-star-pipeline")
        )
        try {
            val store = ResultCandidateStore(pipelineFiles)
            val result = ProtectedFaintStarEnhancer().apply(baseline, alpha, stars, store)
            assertEquals(1, result.metrics.enhancedStarCount)
            assertTrue(result.metrics.changedPixelCount > 0)
            FileBackedImageReader(baseline).use { before ->
                FileBackedImageReader(result.image).use { after ->
                    assertTrue((after.argbAt(12, 12) and 0xFF) > (before.argbAt(12, 12) and 0xFF))
                    assertEquals(before.argbAt(3, 3), after.argbAt(3, 3))
                    assertEquals(before.argbAt(5, 23), after.argbAt(5, 23))
                }
            }
        } finally {
            pipelineFiles.close()
        }
    }

    @Test
    fun noOpEnhancementStagesRetainTheirInputAndCoordinatorKeepsTheCandidate() {
        TemporaryPipelineFiles.create(temporaryFolder.root).use { files ->
            val store = ResultCandidateStore(files)
            val baseline = store.createTemporaryWriter("baseline", 8, 8).use { writer ->
                repeat(8) { writer.writeRow(it, IntArray(8) { 0xFF101418.toInt() }) }
                writer.finish()
            }
            val alpha = store.createFloatPlaneWriter("alpha", 8, 8).use { writer ->
                repeat(8) { writer.writeRow(it, FloatArray(8)) }
                writer.finish()
            }
            val originalHash = fileBackedPixelHash(baseline)
            val denoiser = ProtectedLuminanceDenoiser()
            val noise = denoiser.prepareNoiseMap(baseline, alpha, emptyList(), store)
            val filesBefore = files.directory.listFiles().orEmpty().map { it.name }.sorted()
            assertEquals(0, noise.metrics.eligiblePixelCount)
            assertSame(baseline, denoiser.apply(baseline, alpha, emptyList(), noise, store).image)
            assertSame(baseline, ProtectedFaintStarEnhancer().apply(baseline, alpha, emptyList(), store).image)
            assertEquals(filesBefore, files.directory.listFiles().orEmpty().map { it.name }.sorted())
            store.deleteTemporary(noise.plane)

            val validator = object : EnhancedGlobalToneValidator() {
                override fun validate(
                    baseline: FileBackedImage,
                    candidate: FileBackedImage,
                    effectiveSkyAlpha: FileBackedFloatPlane,
                    confirmedStars: List<DetectedStar>,
                    anchors: GlobalToneAnchors,
                    generatedScaleLimitedPixelCount: Int,
                    maximumLinearChannel: Double,
                    gain: Double
                ): EnhancedGlobalToneValidation {
                    candidate.validate()
                    return acceptedCandidate(candidate).validation
                }
            }
            val result = EnhancedGlobalToneProcessor(validator = validator).createCandidate(
                baseline, alpha, emptyList(), store
            )
            assertTrue(result.generation.image.file.isFile)
            assertEquals(0, result.generation.luminanceDenoise.changedPixelCount)
            assertEquals(0, result.generation.faintStarEnhancement.changedPixelCount)
            assertEquals(originalHash, fileBackedPixelHash(baseline))
        }
    }

    @Test
    fun validationFailureImmediatelyRemovesGeneratedCandidate() {
        val baselineWriter = FileBackedImageWriter(
            file = File(temporaryFolder.root, "validation-baseline.argb"),
            width = 8,
            height = 8
        )
        repeat(8) { y ->
            baselineWriter.writeRow(y, IntArray(8) { 0xFF101418.toInt() })
        }
        val baseline = baselineWriter.finish()
        val alphaWriter = FileBackedFloatPlaneWriter(
            file = File(temporaryFolder.root, "validation-alpha.f32"),
            width = 8,
            height = 8
        )
        repeat(8) { y -> alphaWriter.writeRow(y, FloatArray(8) { 1f }) }
        val alpha = alphaWriter.finish()
        val pipelineFiles = TemporaryPipelineFiles.create(
            temporaryFolder.newFolder("validation-pipeline")
        )
        val store = ResultCandidateStore(pipelineFiles)
        val throwingValidator = object : EnhancedGlobalToneValidator() {
            override fun validate(
                baseline: FileBackedImage,
                candidate: FileBackedImage,
                effectiveSkyAlpha: FileBackedFloatPlane,
                confirmedStars: List<DetectedStar>,
                anchors: GlobalToneAnchors,
                generatedScaleLimitedPixelCount: Int,
                maximumLinearChannel: Double,
                gain: Double
            ): EnhancedGlobalToneValidation = error("simulated_validation_failure")
        }

        try {
            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                EnhancedGlobalToneProcessor(validator = throwingValidator).createCandidate(
                    baseline = baseline,
                    effectiveSkyAlpha = alpha,
                    confirmedStars = emptyList(),
                    store = store
                )
            }

            assertEquals("simulated_validation_failure", error.message)
            assertFalse(
                pipelineFiles.directory.listFiles().orEmpty().any {
                    it.name.contains("enhanced-global-tone")
                }
            )
        } finally {
            pipelineFiles.close()
        }
    }

    private fun acceptedCandidate(image: FileBackedImage): EnhancedGlobalToneCandidate =
        EnhancedGlobalToneCandidate(
            generation = EnhancedGlobalToneGeneration(
                image = image,
                anchors = GlobalToneAnchors(toeStart = 0.001, toeEnd = 0.02),
                scaleLimitedPixelCount = 0,
                maximumLinearChannel = 0.9
            ),
            validation = EnhancedGlobalToneValidation(
                accepted = true,
                hardFailureReasons = emptyList(),
                warnings = emptyList(),
                metrics = EnhancedGlobalToneValidationMetrics(
                    evaluatedStarCount = 1,
                    confirmedStarContrastMedianRatio = 1.0,
                    confirmedStarContrastMinimumRatio = 1.0,
                    confirmedStarLostCount = 0,
                    confirmedStarWeakenedCount = 0,
                    medianStarWidthRelativeChange = 0.0,
                    maximumStarWidthRelativeChange = 0.0,
                    medianStarEllipticityRelativeChange = 0.0,
                    maximumStarEllipticityRelativeChange = 0.0,
                    baselineVisibleStarCount = 1,
                    candidateVisibleStarCount = 1,
                    normalizedSkyMadRatio = 1.0,
                    normalizedBandingRatio = 1.0,
                    normalizedGradientRatio = 1.0,
                    baselineSuspiciousPointCount = 0,
                    candidateSuspiciousPointCount = 0,
                    fixedHighlightPixelCount = 0,
                    baselineClippedHighlightCount = 0,
                    candidateClippedHighlightCount = 0,
                    highlightClippingIncreasePercentagePoints = 0.0,
                    foregroundStrongEdgeRetention = 1.0,
                    foregroundEdgeSignAgreement = 1.0,
                    foregroundEdgeCosineSimilarity = 1.0,
                    newLongLineComponents = 0,
                    lineArtifactScore = 0.0,
                    fanPatternScore = 0.0,
                    newStrongColorPatchCount = 0,
                    largestStrongColorPatchSamples = 0,
                    scaleLimitedPixelCount = 0,
                    scaleLimitedSkyPixelCount = 0,
                    scaleLimitedHighlightPixelCount = 0,
                    scaleLimitedStarWindowPixelCount = 0,
                    maximumLinearChannel = 0.9
                )
            ),
            baselinePixelHashBefore = "baseline",
            baselinePixelHashAfter = "baseline"
        )

    private fun backgroundRoughness(image: FileBackedImageReader): Long {
        var total = 0L
        for (y in 2 until 21) for (x in 2 until 21) {
            if ((x - 12) * (x - 12) + (y - 12) * (y - 12) <= 49) continue
            val first = image.argbAt(x, y) and 0xFF
            val second = image.argbAt(x + 1, y) and 0xFF
            total += kotlin.math.abs(first - second)
        }
        return total
    }
}
