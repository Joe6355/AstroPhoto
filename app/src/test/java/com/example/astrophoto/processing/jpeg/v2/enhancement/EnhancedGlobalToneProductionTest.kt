package com.example.astrophoto.processing.jpeg.v2.enhancement

import com.example.astrophoto.ReplayGlobalToneMapper
import com.example.astrophoto.ReplayToneAnchors
import com.example.astrophoto.SavedProcessedImage
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneWriter
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.example.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
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
                maximumLinearChannel: Double
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
}
