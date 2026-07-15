package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactMask
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactRegion
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactType
import com.example.astrophoto.processing.jpeg.v2.diagnostics.IntegrationReport
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReport
import com.example.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.example.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.BandingMetrics
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.IntegrationDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.IntegrationMode
import com.example.astrophoto.processing.jpeg.v2.model.LinearRgb
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidate
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import com.example.astrophoto.processing.jpeg.v2.quality.AstroResultQualityGate
import com.example.astrophoto.processing.jpeg.v2.quality.CleanStackExecutionPolicy
import com.example.astrophoto.processing.jpeg.v2.quality.CleanStackValidationEvidence
import com.example.astrophoto.processing.jpeg.v2.quality.CoverageUniformityResult
import com.example.astrophoto.processing.jpeg.v2.quality.CoverageUniformityMetrics
import com.example.astrophoto.processing.jpeg.v2.quality.CoverageUniformityValidator
import com.example.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.example.astrophoto.processing.jpeg.v2.quality.LineArtifactMetrics
import com.example.astrophoto.processing.jpeg.v2.quality.LineArtifactResult
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionMetrics
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionResult
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.example.astrophoto.processing.jpeg.v2.quality.ResultSelectionPolicy
import com.example.astrophoto.processing.jpeg.v2.registration.OrderedRegistration
import com.example.astrophoto.processing.jpeg.v2.registration.SpatialStarDistributionValidator
import com.example.astrophoto.processing.jpeg.v2.registration.StarSimilarityRegistrar
import com.example.astrophoto.processing.jpeg.v2.registration.TransformSequenceValidator
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage6Test {
    private val registrar = StarSimilarityRegistrar()
    private val broadStars = buildList {
        listOf(10f, 40f, 75f, 105f).forEach { x ->
            listOf(10f, 38f, 68f, 90f).forEach { y -> add(star(x, y)) }
        }
    }

    @Test fun automaticRegistrationAlwaysFixesScaleToOne() {
        val result = registrar.registerAutomatic(broadStars, transform(broadStars, 2f, -1f, scale = 1.01f), 120, 100)
        assertEquals(1f, result.scale, 0f)
        assertTrue(result.scaleFixed)
    }

    @Test fun automaticRegistrationDefaultsToTranslationOnly() {
        val result = registrar.registerAutomatic(broadStars, transform(broadStars, 2.25f, -1.5f), 120, 100)
        assertTrue(result.isReliable)
        assertEquals("TRANSLATION_ONLY", result.registrationModel)
        assertEquals(0f, result.rotationRadians, 0f)
    }

    @Test fun rotationIsRejectedWithTooFewInliers() {
        val reference = broadStars.take(8)
        val result = registrar.registerAutomatic(reference, transform(reference, 2f, -1f, degrees(0.8f)), 120, 100)
        assertFalse(result.rotationAllowed)
        assertTrue(result.rotationRejectionReason.orEmpty().contains("12") || !result.isReliable)
    }

    @Test fun rotationIsRejectedForClusteredInliers() {
        val clustered = buildList {
            listOf(10f, 16f, 22f, 28f).forEach { x ->
                listOf(10f, 17f, 24f).forEach { y -> add(star(x, y)) }
            }
        }
        val result = registrar.registerAutomatic(clustered, transform(clustered, 1f, 1f, degrees(1f)), 120, 100)
        assertFalse(result.rotationAllowed)
        assertTrue(result.rotationRejectionReason.orEmpty().contains("span") ||
            result.rotationRejectionReason.orEmpty().contains("cells"))
    }

    @Test fun rotationRequiresBroadSpatialDistribution() {
        val result = registrar.registerAutomatic(
            broadStars,
            transform(broadStars, 1.5f, -1f, degrees(1.2f)),
            120,
            100,
            expectedRotationRadians = degrees(1.2f)
        )
        assertTrue(result.toString(), result.isReliable)
        assertTrue(result.rotationAllowed)
        assertTrue(result.occupiedDistributionCells >= SpatialStarDistributionValidator.MIN_OCCUPIED_CELLS)
        assertTrue(result.horizontalDistributionSpan >= SpatialStarDistributionValidator.MIN_SPAN)
        assertTrue(result.verticalDistributionSpan >= SpatialStarDistributionValidator.MIN_SPAN)
    }

    @Test fun automaticTranslationRecoveryRemainsAccurate() {
        val result = registrar.registerAutomatic(broadStars, transform(broadStars, 4.2f, -3.1f), 120, 100)
        assertTrue(result.toString(), result.isReliable)
        assertEquals(4.2, result.dx.toDouble(), 0.05)
        assertEquals(-3.1, result.dy.toDouble(), 0.05)
    }

    @Test fun weakRotationFallsBackToTranslationOnly() {
        val reference = broadStars.take(8)
        val result = registrar.registerAutomatic(reference, transform(reference, 2f, -1f, degrees(0.3f)), 120, 100)
        assertTrue(result.toString(), result.isReliable)
        assertEquals("TRANSLATION_ONLY", result.registrationModel)
        assertEquals(0f, result.rotationRadians, 0f)
        assertTrue(result.transformRetryUsed)
    }

    @Test fun failedTranslationRetryRejectsFrame() {
        val result = registrar.registerAutomatic(broadStars, broadStars.take(3), 120, 100, forceTranslationOnly = true)
        assertFalse(result.isReliable)
        assertEquals("TRANSLATION_ONLY", result.registrationModel)
    }

    @Test fun nearZeroAutomaticTransformStillNeedsValidMatches() {
        val valid = registrar.registerAutomatic(broadStars, transform(broadStars, 0.04f, -0.03f), 120, 100)
        val invalid = registrar.registerAutomatic(broadStars, broadStars.take(2), 120, 100)
        assertTrue(valid.isReliable)
        assertTrue(valid.matchedStars >= StarSimilarityRegistrar.MIN_MATCHED_STARS)
        assertFalse(invalid.isReliable)
    }

    @Test fun staticCameraSpaceHotPixelsAreDetected() {
        val frames = (0 until 4).map { index ->
            ArtifactFrameObservation("f$index", listOf(star(20f, 20f, width = 0.7f), star(40f + index, 30f)))
        }
        val mask = StaticArtifactAnalyzer().analyze(frames, 80, 60)
        assertTrue(mask.staticHotPixelCandidates.any { kotlin.math.abs(it.x - 20f) < 0.1f })
    }

    @Test fun staticSingleChannelSpikeMaskExcludesMatchingCandidate() {
        val mask = StaticArtifactMask(
            80, 60,
            listOf(StaticArtifactRegion(20f, 20f, 2f, StaticArtifactType.SINGLE_CHANNEL_SPIKE, 1f, "test")),
            1f, 0.01f
        )
        assertEquals(listOf(star(40f, 30f)), mask.filter(listOf(star(20f, 20f), star(40f, 30f))))
    }

    @Test fun stationaryReflectionPatchIsExcluded() {
        val frames = (0 until 4).map { index ->
            ArtifactFrameObservation("f$index", listOf(star(25f, 24f, width = 3.2f, ellipticity = 0.5f)))
        }
        val mask = StaticArtifactAnalyzer().analyze(frames, 80, 60)
        assertTrue(mask.staticReflectionCandidates.isNotEmpty())
        assertTrue(mask.filter(frames.first().stars).isEmpty())
    }

    @Test fun movingSyntheticStarsAreNotStaticArtifacts() {
        val frames = (0 until 4).map { index ->
            ArtifactFrameObservation("f$index", listOf(star(20f + index, 20f + index, width = 0.7f)))
        }
        assertTrue(StaticArtifactAnalyzer().analyze(frames, 80, 60).regions.isEmpty())
    }

    @Test fun sequenceValidatorAcceptsSmoothMotion() {
        val result = TransformSequenceValidator().validate(sequence(0f, 1f, 2f, 3f, 4f, 5f))
        assertTrue(result.rejectedFrameIds.isEmpty())
        assertTrue(result.score > 0.9f)
    }

    @Test fun sequenceValidatorRejectsIsolatedTranslationJump() {
        val result = TransformSequenceValidator().validate(sequence(0f, 1f, 2f, 20f, 4f, 5f))
        assertTrue(result.rejectedFrameIds.contains("f3"))
        assertFalse(result.registrations.first { it.frameId == "f3" }.registration.isReliable)
    }

    @Test fun sequenceValidatorRejectsIsolatedRotationJump() {
        val rotations = listOf(0f, 0.02f, 0.04f, 1.2f, 0.08f, 0.10f).map(::degrees)
        val result = TransformSequenceValidator().validate(
            rotations.mapIndexed { index, rotation -> ordered(index, index.toFloat(), rotation) }
        )
        assertTrue(result.rejectedFrameIds.contains("f3"))
    }

    @Test fun sequenceValidatorRetriesTranslationOnly() {
        val result = TransformSequenceValidator().validate(
            sequence(0f, 1f, 2f, 20f, 4f, 5f),
            retryTranslationOnly = { entry ->
                if (entry.frameId == "f3") registration(dx = 3f).copy(registrationModel = "TRANSLATION_ONLY") else null
            }
        )
        val retried = result.registrations.first { it.frameId == "f3" }.registration
        assertTrue(retried.isReliable)
        assertTrue(retried.transformRetryUsed)
        assertTrue(result.retryFrameIds.contains("f3"))
    }

    @Test fun automaticIntegrationUsesPlainAverageUpToFifteenFrames() = runBlocking {
        val result = integrate(List(15) { intArrayOf(gray(30)) }, allowRobust = false)
        assertEquals(IntegrationMode.LINEAR_WEIGHTED_AVERAGE, result.first.mode)
        assertFalse(result.first.robustModeEnabled)
    }

    @Test fun automaticIntegrationKeepsRobustClippingDisabledAboveFifteen() = runBlocking {
        val result = integrate(List(18) { intArrayOf(gray(30)) }, allowRobust = false)
        assertFalse(result.first.robustModeEnabled)
        assertEquals("faint_star_preservation", result.first.robustModeReason)
    }

    @Test fun faintRepeatedStarSurvivesAutomaticIntegration() = runBlocking {
        val result = integrate(List(12) { intArrayOf(gray(38)) }, allowRobust = false)
        assertTrue((result.second.single() and 0xFF) >= 37)
    }

    @Test fun retentionValidatorAcceptsRetainedReferenceStars() {
        val field = gaussianField()
        val result = ReferenceStarRetentionValidator().validate(field.first, field.first, field.second)
        assertTrue(result.toString(), result.accepted)
        assertEquals(1f, result.metrics.retentionRatio, 0f)
    }

    @Test fun retentionValidatorDetectsMissingStar() {
        val field = gaussianField()
        val damaged = gaussianImage(96, 80, field.second.dropLast(1), amplitude = 170f)
        val result = ReferenceStarRetentionValidator().validate(field.first, damaged, field.second)
        assertFalse(result.accepted)
        assertTrue(result.metrics.retentionRatio < 0.9f)
    }

    @Test fun retentionValidatorDetectsContrastCollapse() {
        val field = gaussianField()
        val damaged = gaussianImage(96, 80, field.second, amplitude = 100f)
        val result = ReferenceStarRetentionValidator().validate(field.first, damaged, field.second)
        assertFalse(result.accepted)
        assertTrue(result.hardFailureReasons.any { "contrast" in it })
    }

    @Test fun retentionValidatorDetectsWidthGrowth() {
        val field = gaussianField()
        val damaged = gaussianImage(96, 80, field.second, amplitude = 170f, sigmaX = 1.8f, sigmaY = 1.8f)
        val result = ReferenceStarRetentionValidator().validate(field.first, damaged, field.second)
        assertFalse(result.accepted)
        assertTrue(result.hardFailureReasons.any { "width" in it })
    }

    @Test fun retentionValidatorDetectsLineLikeSmearing() {
        val field = gaussianField()
        val damaged = gaussianImage(96, 80, field.second, amplitude = 170f, sigmaX = 3.5f, sigmaY = 0.45f)
        val result = ReferenceStarRetentionValidator().validate(field.first, damaged, field.second)
        assertFalse(result.accepted)
        assertTrue(result.hardFailureReasons.any { "smear" in it } || result.metrics.lineLikeSmearRate > 0.1f)
    }

    @Test fun cleanStackGateRejectsLowRetentionEvidence() {
        val evidence = evidence(
            retention = acceptedRetention().copy(
                accepted = false,
                metrics = acceptedRetention().metrics.copy(retentionRatio = 0.75f),
                hardFailureReasons = listOf("reference_star_retention_below_90_percent")
            )
        )
        val decision = AstroResultQualityGate().evaluateCleanStack(
            candidate(ResultCandidateType.REFERENCE),
            candidate(ResultCandidateType.CLEAN_STACK),
            AstroProcessingProfile.DEEP_SKY,
            evidence
        )
        assertFalse(decision.accepted)
    }

    @Test fun referenceIsStructurallyValidatedBeforeCleanStack() {
        val reference = ResultCandidate(
            ResultCandidateType.REFERENCE,
            uniform(32, 24, 20),
            metrics().copy(retainedValidAreaRatio = 0.8f)
        )
        assertFalse(AstroResultQualityGate().evaluateReference(reference).accepted)
    }

    @Test fun coverageValidatorAcceptsSmoothCoverage() {
        val result = CoverageUniformityValidator().validate(AlphaMask.full(96, 96), AlphaMask.full(96, 96))
        assertTrue(result.toString(), result.accepted)
        assertEquals(1f, result.metrics.uniformityScore, 0.001f)
    }

    @Test fun coverageValidatorRejectsAbruptInternalSector() {
        val values = FloatArray(96 * 96) { index ->
            val x = index % 96
            val y = index / 96
            if (x in 28..68 && y in 20..76) 0.2f else 1f
        }
        val result = CoverageUniformityValidator().validate(AlphaMask(96, 96, values), AlphaMask.full(96, 96))
        assertFalse(result.accepted)
        assertTrue(result.hardFailureReasons.isNotEmpty())
    }

    @Test fun coverageValidatorIgnoresExpectedEdgeFallback() {
        val values = FloatArray(96 * 96) { index ->
            val x = index % 96
            val y = index / 96
            if (x < 5 || y < 5 || x >= 91 || y >= 91) 0.1f else 1f
        }
        val result = CoverageUniformityValidator().validate(AlphaMask(96, 96, values), AlphaMask.full(96, 96))
        assertTrue(result.toString(), result.accepted)
    }

    @Test fun coverageValidatorDetectsWedgeLikeDiscontinuity() {
        val values = FloatArray(96 * 96) { index ->
            val x = index % 96
            val y = index / 96
            if (x > 48 && y in (96 - x)..x) 0.2f else 1f
        }
        val result = CoverageUniformityValidator().validate(AlphaMask(96, 96, values), AlphaMask.full(96, 96))
        assertTrue(result.metrics.wedgeDiscontinuityScore > 0.45f)
        assertTrue(result.hardFailureReasons.contains("wedge_like_coverage_discontinuity"))
    }

    @Test fun lineDetectorFindsNewDiagonalStreak() {
        val reference = uniform(128, 96, 20)
        val candidate = reference.copyImage().also { drawLine(it, 8, 12, 116, 82, 230) }
        val result = LineArtifactDetector().compare(reference, candidate, AlphaMask.full(128, 96))
        assertTrue(result.metrics.newLongLineComponents > 0)
        assertFalse(result.accepted)
    }

    @Test fun lineDetectorFindsFanLikeBoundaries() {
        val reference = uniform(160, 120, 20)
        val candidate = reference.copyImage().also {
            drawLine(it, 8, 18, 70, 30, 230)
            drawLine(it, 88, 18, 148, 52, 230)
            drawLine(it, 12, 78, 72, 108, 230)
            drawLine(it, 92, 104, 150, 72, 230)
        }
        val result = LineArtifactDetector().compare(reference, candidate, AlphaMask.full(160, 120))
        assertTrue(result.metrics.fanPatternScore > 0f || result.metrics.newLongLineComponents >= 3)
    }

    @Test fun lineDetectorIgnoresUnchangedReferenceWire() {
        val reference = uniform(128, 96, 20).also { drawLine(it, 8, 12, 116, 82, 230) }
        val result = LineArtifactDetector().compare(reference, reference, AlphaMask.full(128, 96))
        assertTrue(result.accepted)
        assertEquals(0, result.metrics.newLongLineComponents)
    }

    @Test fun cleanStackGateRejectsNewLineArtifacts() {
        val evidence = evidence(
            lines = acceptedLines().copy(
                accepted = false,
                metrics = acceptedLines().metrics.copy(lineArtifactScore = 0.8f),
                hardFailureReasons = listOf("new_strong_line_artifacts_detected")
            )
        )
        val decision = AstroResultQualityGate().evaluateCleanStack(
            candidate(ResultCandidateType.REFERENCE), candidate(ResultCandidateType.CLEAN_STACK),
            AstroProcessingProfile.DEEP_SKY, evidence
        )
        assertFalse(decision.accepted)
    }

    @Test fun rejectedCleanStackPreventsStageFourExecution() {
        val decision = decision(false, "reference_star_retention_below_90_percent")
        assertFalse(CleanStackExecutionPolicy().shouldExecuteStage4(decision))
    }

    @Test fun validProcessedResultIsSelectedAfterValidCleanStack() {
        val reference = candidate(ResultCandidateType.REFERENCE)
        val clean = candidate(ResultCandidateType.CLEAN_STACK)
        val processed = candidate(ResultCandidateType.PROCESSED)
        val selected = ResultSelectionPolicy().select(reference, clean, processed, decision(true), decision(true))
        assertSame(processed, selected.selected)
    }

    @Test fun cleanStackFallbackIsUsedWhenStageFourFails() {
        val reference = candidate(ResultCandidateType.REFERENCE)
        val clean = candidate(ResultCandidateType.CLEAN_STACK)
        val processed = candidate(ResultCandidateType.PROCESSED)
        val selected = ResultSelectionPolicy().select(reference, clean, processed, decision(false, "processed_bad"), decision(true))
        assertSame(clean, selected.selected)
    }

    @Test fun referenceFallbackIsUsedWhenCleanStackFails() {
        val reference = candidate(ResultCandidateType.REFERENCE)
        val selected = ResultSelectionPolicy().select(
            reference,
            candidate(ResultCandidateType.CLEAN_STACK),
            candidate(ResultCandidateType.PROCESSED),
            decision(false, "stage4_skipped_clean_stack_invalid"),
            decision(false, "reference_star_retention_below_90_percent")
        )
        assertSame(reference, selected.selected)
    }

    @Test fun processingReportRecordsStageSixRejectionReasons() {
        val json = report().toJson()
        assertTrue(json.contains("\"cleanStackAccepted\": false"))
        assertTrue(json.contains("reference_star_retention_below_90_percent"))
        assertTrue(json.contains("\"robustModeReason\":\"faint_star_preservation\""))
    }

    @Test fun registrationReportPreservesStageSixDiagnostics() {
        val mapped = registration(dx = 2f).copy(
            rotationAllowed = true,
            occupiedDistributionCells = 5,
            spatialDistributionScore = 0.9f,
            rawDx = 3f,
            transformSequenceScore = 0.8f,
            transformSequenceDeviation = 0.4f,
            neighborTransformDelta = 1.2f,
            transformRetryUsed = true
        ).toReport("frame-001.jpg")
        assertEquals("TRANSLATION_ONLY", mapped.registrationModel)
        assertEquals(5, mapped.occupiedDistributionCells)
        assertEquals(1.2f, mapped.neighborTransformDelta, 0f)
        assertTrue(mapped.transformRetryUsed)
    }

    @Test fun userVisibleReferenceFallbackReasonIsConcise() {
        val warning = qualityFallbackWarning(
            ResultCandidateType.REFERENCE,
            "reference_star_retention_below_90_percent"
        )
        assertTrue(warning.startsWith("Стекинг отклонён:"))
        assertTrue(warning.length < 180)
    }

    @Test fun automaticIntegrationPreservesFullResolutionAndAspectRatio() = runBlocking {
        val pixels = IntArray(12 * 5) { gray(30) }
        val result = integrate(List(2) { pixels }, 12, 5, allowRobust = false).first
        assertEquals(12, result.outputWidth)
        assertEquals(5, result.outputHeight)
        assertEquals(12.0 / 5.0, result.outputWidth.toDouble() / result.outputHeight, 0.0)
    }

    @Test fun fixtureLoaderSupportsAnonymizedRelativeSeries() {
        val directory = Files.createTempDirectory("stage6-fixture").toFile().apply { deleteOnExit() }
        val names = listOf("frame-000.jpg", "frame-001.jpg")
        names.forEachIndexed { index, name ->
            val image = BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB)
            image.setRGB(3, 2, rgb(80 + index, 80 + index, 80 + index))
            ImageIO.write(image, "jpg", directory.resolve(name))
        }
        directory.resolve("manifest.properties").writeText(
            "name=test-fixture\nframes=${names.joinToString(",")}\n" +
                "referenceFrame=${names.first()}\nreferenceStars=reference-stars.csv\n"
        )
        directory.resolve("reference-stars.csv").writeText("3,2,500,20,60,1.8,0.1,0.95\n")
        val fixture = Stage6RegressionFixtureLoader.load(directory)
        assertEquals(2, fixture.frames.size)
        assertEquals(1, fixture.referenceStars.size)
        assertEquals(8, fixture.frames.first().width)
    }

    private fun integrate(
        frames: List<IntArray>,
        width: Int = 1,
        height: Int = 1,
        allowRobust: Boolean
    ): Pair<IntegrationDiagnostics, IntArray> = runBlocking {
        val output = IntArray(width * height)
        val diagnostics = LinearWeightedIntegrator().integrate(
            outputWidth = width,
            outputHeight = height,
            frames = frames.mapIndexed { index, pixels ->
                WeightedIntegrationFrame(index.toString(), pixels, registration(), 1f)
            },
            maximumWorkingMemoryBytes = 64L * 1024L * 1024L,
            openSource = { IntArrayPixelSource(width, height, it) },
            allowRobustClipping = allowRobust,
            writeTile = { tile, pixels ->
                for (row in 0 until tile.height) {
                    pixels.copyInto(
                        output,
                        (tile.top + row) * width + tile.left,
                        row * tile.width,
                        (row + 1) * tile.width
                    )
                }
            }
        )
        diagnostics to output
    }

    private fun sequence(vararg dx: Float) = dx.mapIndexed { index, value -> ordered(index, value, 0f) }

    private fun ordered(index: Int, dx: Float, rotation: Float) = OrderedRegistration(
        frameId = "f$index",
        captureIndex = index,
        isReference = index == 0,
        registration = registration(dx = dx, rotation = rotation)
    )

    private fun registration(
        dx: Float = 0f,
        dy: Float = 0f,
        rotation: Float = 0f,
        reliable: Boolean = true
    ) = RegistrationResult(
        dx, dy, rotation, 1f, 16, 16, 16, 0.1f, 0.95f, reliable,
        if (reliable) null else "rejected", 16,
        registrationModel = "TRANSLATION_ONLY",
        scaleFixed = true
    )

    private fun transform(
        values: List<DetectedStar>,
        dx: Float,
        dy: Float,
        rotation: Float = 0f,
        scale: Float = 1f
    ) = values.map { value ->
        value.copy(
            x = scale * (cos(rotation) * value.x - sin(rotation) * value.y) + dx,
            y = scale * (sin(rotation) * value.x + cos(rotation) * value.y) + dy
        )
    }

    private fun gaussianField(): Pair<ArgbPixelImage, List<DetectedStar>> {
        val stars = listOf(star(16f, 16f), star(40f, 18f), star(72f, 20f), star(22f, 50f), star(52f, 56f))
        return gaussianImage(96, 80, stars, amplitude = 170f) to stars
    }

    private fun gaussianImage(
        width: Int,
        height: Int,
        stars: List<DetectedStar>,
        amplitude: Float,
        sigmaX: Float = 0.9f,
        sigmaY: Float = 0.9f
    ): ArgbPixelImage = ArgbPixelImage(width, height, IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        var value = 20.0
        stars.forEach { star ->
            val offsetX = (x - star.x) / sigmaX
            val offsetY = (y - star.y) / sigmaY
            value += amplitude * exp(-(offsetX * offsetX + offsetY * offsetY) / 2.0)
        }
        gray(value.toInt().coerceIn(0, 250))
    })

    private fun star(
        x: Float,
        y: Float,
        width: Float = 1.8f,
        ellipticity: Float = 0.08f
    ) = DetectedStar(x, y, 500f, 20f, 90f, width, ellipticity, 0.95f)

    private fun evidence(
        retention: ReferenceStarRetentionResult = acceptedRetention(),
        coverage: CoverageUniformityResult = acceptedCoverage(),
        lines: LineArtifactResult = acceptedLines()
    ) = CleanStackValidationEvidence(retention, coverage, lines, 1f, 5, true)

    private fun acceptedRetention() = ReferenceStarRetentionResult(
        true,
        ReferenceStarRetentionMetrics(10, 10, 10, 1f, 80f, 80f, 1f, 2f, 2f, 0f, 0f, 0f),
        emptyList(),
        emptyList()
    )

    private fun acceptedCoverage() = CoverageUniformityResult(
        true,
        CoverageUniformityMetrics(1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f),
        emptyList(),
        emptyList()
    )

    private fun acceptedLines() = LineArtifactResult(
        true,
        LineArtifactMetrics(0, 0f, 0f, 0f, 0f),
        emptyList(),
        emptyList()
    )

    private fun candidate(type: ResultCandidateType) = ResultCandidate(
        type,
        uniform(32, 24, 20),
        metrics()
    )

    private fun decision(accepted: Boolean, reason: String? = null) = QualityGateDecision(
        accepted,
        if (accepted) 1f else 0f,
        reason?.let(::listOf).orEmpty(),
        emptyList(),
        metrics()
    )

    private fun report() = ProcessingReport(
        timestampMillis = 1L,
        presetId = "DEEP_SKY",
        presetDisplayName = "Deep Sky",
        inputFrameCount = 5,
        eligibleFrameCount = 5,
        acceptedFrameCount = 4,
        rejectedFrameCount = 1,
        selectedReference = "frame-000.jpg",
        skyMaskConfidence = 0.9f,
        skyRatio = 0.8f,
        foregroundRatio = 0.2f,
        registrations = emptyList(),
        frameWeights = emptyList(),
        integration = IntegrationReport(
            "LINEAR_WEIGHTED_AVERAGE", false, 32, 24, 32, 24, 32, 24,
            false, 100f, 10_000L, 3_072L, 12_288L, "faint_star_preservation"
        ),
        stage4Parameters = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY, 4),
        referenceMetrics = metrics(),
        cleanStackMetrics = metrics(),
        processedMetrics = metrics(),
        cleanStackDecision = decision(false, "reference_star_retention_below_90_percent"),
        processedDecision = decision(false, "stage4_skipped_clean_stack_invalid"),
        selectedCandidateType = "REFERENCE",
        fallbackUsed = true,
        fallbackReason = "reference_star_retention_below_90_percent",
        internalFallbackLabel = "RecoveredStars",
        warnings = emptyList(),
        outputPngDisplayName = "DeepSky.png",
        stageDurationsMillis = mapOf("total" to 10L),
        stage4Executed = false,
        cleanStackAccepted = false,
        cleanStackRejectionReasons = listOf("reference_star_retention_below_90_percent"),
        referenceStarRetentionRatio = 0.75f
    )

    private fun metrics() = ResultQualityMetrics(
        width = 32,
        height = 24,
        aspectRatio = 4.0 / 3.0,
        retainedValidAreaRatio = 1f,
        reliableStarCount = 20,
        medianStarLocalContrast = 0.15f,
        medianStarWidth = 2f,
        medianStarEllipticity = 0.1f,
        brightStarClippingPercent = 0f,
        suspiciousPointCount = 0,
        skyMedian = 0.05f,
        skyMad = 0.01f,
        skyLowPercentile = 0.02f,
        skyHighPercentile = 0.10f,
        channelMedian = LinearRgb(0.05f, 0.05f, 0.05f),
        channelClippingPercent = LinearRgb(0f, 0f, 0f),
        chromaNoiseEstimate = 0.005f,
        banding = BandingMetrics(0f, 0f, 0f),
        gradientResidual = 0.01f,
        foregroundSharpness = 1f,
        foregroundEdgeDifference = 0f,
        foregroundMeanPixelDifference = 0f,
        foregroundMaximumPixelDifference = 0,
        invalidBorderRatio = 0f,
        blackBorderRatio = 0f,
        processingConfidence = 1f
    )

    private fun uniform(width: Int, height: Int, value: Int) =
        ArgbPixelImage(width, height, IntArray(width * height) { gray(value) })

    private fun drawLine(image: ArgbPixelImage, x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)).coerceAtLeast(1)
        for (step in 0..steps) {
            val x = (x0 + (x1 - x0) * step.toFloat() / steps).toInt().coerceIn(1, image.width - 2)
            val y = (y0 + (y1 - y0) * step.toFloat() / steps).toInt().coerceIn(1, image.height - 2)
            image.pixels[y * image.width + x] = gray(value)
        }
    }

    private fun ArgbPixelImage.copyImage() = ArgbPixelImage(width, height, pixels.copyOf())
    private fun degrees(value: Float) = (value * PI / 180.0).toFloat()
    private fun gray(value: Int) = rgb(value, value, value)
    private fun rgb(red: Int, green: Int, blue: Int) =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}
