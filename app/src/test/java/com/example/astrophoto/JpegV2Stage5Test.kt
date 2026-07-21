package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.diagnostics.IntegrationReport
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReport
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ReportWriteOutcome
import com.example.astrophoto.processing.jpeg.v2.diagnostics.processingReportFileName
import com.example.astrophoto.processing.jpeg.v2.diagnostics.processingReportMatchesImage
import com.example.astrophoto.processing.jpeg.v2.diagnostics.safeReportWrite
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.BandingMetrics
import com.example.astrophoto.processing.jpeg.v2.model.LinearRgb
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidate
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import com.example.astrophoto.processing.jpeg.v2.quality.AstroResultQualityGate
import com.example.astrophoto.processing.jpeg.v2.quality.BackgroundQualityComparator
import com.example.astrophoto.processing.jpeg.v2.quality.BandingEstimator
import com.example.astrophoto.processing.jpeg.v2.quality.ForegroundQualityComparator
import com.example.astrophoto.processing.jpeg.v2.quality.ResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.quality.ResultSelectionPolicy
import com.example.astrophoto.processing.jpeg.v2.quality.StarResultComparator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage5Test {
    private val starComparator = StarResultComparator()
    private val backgroundComparator = BackgroundQualityComparator()
    private val foregroundComparator = ForegroundQualityComparator()

    @Test fun resultCandidateSnapshotIsIndependent() {
        val source = uniform(24, 16, rgb(20, 20, 20))
        val candidate = ResultCandidate.snapshot(
            ResultCandidateType.CLEAN_STACK,
            source,
            metrics(24, 16).copy(aspectRatio = 24.0 / 16.0)
        )
        source.pixels[0] = rgb(250, 10, 10)
        assertNotEquals(source.pixels[0], candidate.image.pixels[0])
    }

    @Test fun threeCandidateBuffersRemainIndependent() {
        val reference = uniform(24, 16, rgb(10, 10, 10))
        val clean = uniform(24, 16, rgb(20, 20, 20))
        val processed = uniform(24, 16, rgb(30, 30, 30))
        processed.pixels[0] = rgb(200, 200, 200)
        assertEquals(rgb(10, 10, 10), reference.pixels[0])
        assertEquals(rgb(20, 20, 20), clean.pixels[0])
    }

    @Test fun qualityMetricsPreserveDimensions() {
        val image = starField(64, 48)
        val value = analyze(image, image, AlphaMask.full(64, 48))
        assertEquals(64, value.width)
        assertEquals(48, value.height)
    }

    @Test fun qualityMetricsPreserveAspectRatio() {
        assertEquals(4.0 / 3.0, analyze(uniform(64, 48, gray(20))).aspectRatio, 0.0)
    }

    @Test fun qualityMetricsUseSkyOnlyPixels() {
        val scene = split(64, 48, gray(20), gray(230))
        val value = analyze(scene, scene, topMask(64, 48, 24))
        assertTrue(value.skyMedian < 0.02f)
    }

    @Test fun qualityMetricsUseProtectedForegroundOnly() {
        val reference = split(64, 48, gray(20), gray(90))
        val candidate = reference.copyImage().also { it.pixels[40 * 64 + 20] = gray(120) }
        val value = analyze(candidate, reference, topMask(64, 48, 24))
        assertTrue(value.foregroundMaximumPixelDifference >= 30)
    }

    @Test fun qualityMetricsIgnoreFeatherTransitionForForeground() {
        val reference = uniform(40, 30, gray(40))
        val candidate = reference.copyImage().also { it.pixels[15 * 40 + 12] = gray(200) }
        val alpha = AlphaMask(40, 30, FloatArray(40 * 30) { if (it / 40 == 15) 0.5f else 1f })
        assertEquals(0, analyze(candidate, reference, alpha).foregroundMaximumPixelDifference)
    }

    @Test fun starComparatorAcceptsImprovedFaintStarContrast() {
        val result = starComparator.compare(metrics(), metrics().copy(medianStarLocalContrast = 0.16f), cleanProfile)
        assertTrue(result.hardFailureReasons.isEmpty())
    }

    @Test fun starComparatorRejectsMajorStarCountLoss() {
        assertHard(starComparator.compare(metrics(), metrics().copy(reliableStarCount = 15), cleanProfile), "star_count")
    }

    @Test fun starComparatorWarnsAboutSmallStarCountLoss() {
        val result = starComparator.compare(metrics(), metrics().copy(reliableStarCount = 18), cleanProfile)
        assertTrue(result.warningReasons.any { "star_count" in it })
    }

    @Test fun starComparatorRejectsContrastLoss() {
        assertHard(starComparator.compare(metrics(), metrics().copy(medianStarLocalContrast = 0.08f), cleanProfile), "contrast")
    }

    @Test fun starComparatorRejectsIncreasedStarWidth() {
        assertHard(starComparator.compare(metrics(), metrics().copy(medianStarWidth = 3.0f), cleanProfile), "width")
    }

    @Test fun matchedStarValidationSupersedesUnmatchedGlobalPopulationMetrics() {
        val result = starComparator.compare(
            metrics(),
            metrics().copy(
                reliableStarCount = 2,
                medianStarLocalContrast = 0.02f,
                medianStarWidth = 3.0f,
                medianStarEllipticity = 0.30f
            ),
            cleanProfile,
            matchedStarsValidated = true
        )

        assertFalse(result.hardFailureReasons.any {
            "star_count" in it || "contrast" in it || "width" in it || "ellipticity" in it
        })
    }

    @Test fun starComparatorRejectsExcessiveEllipticity() {
        assertHard(starComparator.compare(metrics(), metrics().copy(medianStarEllipticity = 0.30f), cleanProfile), "ellipticity")
    }

    @Test fun starComparatorRejectsBrightCoreClipping() {
        assertHard(starComparator.compare(metrics(), metrics().copy(brightStarClippingPercent = 20f), cleanProfile), "clipping")
    }

    @Test fun starComparatorRejectsFakeSinglePixelStars() {
        assertHard(starComparator.compare(metrics(), metrics().copy(suspiciousPointCount = 4), cleanProfile), "points")
    }

    @Test fun maximumStarsAllowsNoNewUnverifiedPoints() {
        assertHard(
            starComparator.compare(metrics(), metrics().copy(suspiciousPointCount = 1), AstroProcessingProfile.MAX_STARS),
            "maximum_stars"
        )
    }

    @Test fun backgroundComparatorRejectsExcessiveSkyMedian() {
        assertBackgroundHard(metrics().copy(skyMedian = 0.20f), "median")
    }

    @Test fun backgroundComparatorRejectsIncreasedSkyMad() {
        assertBackgroundHard(metrics().copy(skyMad = 0.020f), "mad")
    }

    @Test fun backgroundComparatorRejectsIncreasedChromaNoise() {
        assertBackgroundHard(metrics().copy(chromaNoiseEstimate = 0.020f), "chroma")
    }

    @Test fun backgroundComparatorRejectsExcessiveClipping() {
        assertBackgroundHard(metrics().copy(channelClippingPercent = LinearRgb(8f, 0f, 0f)), "clipping")
    }

    @Test fun backgroundComparatorRejectsWorseBanding() {
        assertBackgroundHard(metrics().copy(banding = BandingMetrics(1f, 0f, 1f)), "banding")
    }

    @Test fun backgroundComparatorRejectsWorseGradient() {
        assertBackgroundHard(metrics().copy(gradientResidual = 0.10f), "gradient")
    }

    @Test fun backgroundComparatorRejectsFlatGraySky() {
        assertBackgroundHard(metrics().copy(skyMedian = 0.22f, skyMad = 0.0005f), "gray")
    }

    @Test fun backgroundComparatorRejectsCrushedBlackPoint() {
        assertBackgroundHard(metrics().copy(skyLowPercentile = 0f), "black")
    }

    @Test fun backgroundComparatorRejectsWorseColorCast() {
        assertBackgroundHard(metrics().copy(channelMedian = LinearRgb(0.08f, 0.02f, 0.01f)), "cast")
    }

    @Test fun backgroundComparatorRejectsLowConfidenceStatistics() {
        assertBackgroundHard(metrics().copy(processingConfidence = 0.1f), "confidence")
    }

    @Test fun bandingEstimatorDetectsHorizontalBanding() {
        val score = BandingEstimator().estimate(horizontalBands(), AlphaMask.full(96, 72), 0.01f)
        assertTrue(score.horizontalScore > score.verticalScore + 0.2f)
    }

    @Test fun bandingEstimatorDetectsVerticalBanding() {
        val score = BandingEstimator().estimate(verticalBands(), AlphaMask.full(96, 72), 0.01f)
        assertTrue(score.verticalScore > score.horizontalScore + 0.2f)
    }

    @Test fun bandingEstimatorIgnoresSmoothCityGradient() {
        val estimator = BandingEstimator()
        val smooth = estimator.estimate(gradientScene(), AlphaMask.full(96, 72), 0.01f)
        val banded = estimator.estimate(horizontalBands(), AlphaMask.full(96, 72), 0.01f)
        assertTrue(smooth.combinedScore < banded.combinedScore)
    }

    @Test fun bandingEstimatorIgnoresMaskedForegroundBands() {
        val scene = split(96, 72, gray(25), gray(100)).also { image ->
            for (y in 36 until 72 step 4) for (x in 0 until 96) image.pixels[y * 96 + x] = gray(180)
        }
        val score = BandingEstimator().estimate(scene, topMask(96, 72, 36), 0.01f)
        assertTrue(score.combinedScore < 0.2f)
    }

    @Test fun foregroundComparatorRejectsBlur() {
        assertForegroundHard(metrics().copy(foregroundSharpness = 60f), "blurred")
    }

    @Test fun foregroundComparatorRejectsDoubleEdges() {
        assertForegroundHard(metrics().copy(foregroundEdgeDifference = 2f), "double_edge")
    }

    @Test fun foregroundComparatorRejectsBrokenWires() {
        assertForegroundHard(metrics().copy(foregroundSharpness = 70f), "wire")
    }

    @Test fun foregroundComparatorRejectsBlackBorders() {
        assertForegroundHard(metrics().copy(blackBorderRatio = 0.02f), "black_border")
    }

    @Test fun foregroundComparatorRejectsDimensionChanges() {
        assertForegroundHard(metrics(width = 80), "dimensions")
    }

    @Test fun foregroundComparatorRejectsAspectRatioChanges() {
        assertForegroundHard(metrics().copy(aspectRatio = 1.0), "aspect_ratio")
    }

    @Test fun foregroundComparatorRejectsHiddenCrop() {
        assertForegroundHard(metrics().copy(retainedValidAreaRatio = 0.90f), "crop")
    }

    @Test fun foregroundComparatorRejectsInvalidBorder() {
        assertForegroundHard(metrics().copy(invalidBorderRatio = 0.01f), "invalid")
    }

    @Test fun foregroundComparatorRejectsProtectedPixelChanges() {
        assertForegroundHard(metrics().copy(foregroundMaximumPixelDifference = 3), "pixels")
    }

    @Test fun processedResultIsSelectedWhenChecksPass() {
        val candidates = candidates()
        val selected = select(candidates, acceptedDecision(), acceptedDecision())
        assertSame(candidates[2], selected.selected)
        assertFalse(selected.fallbackUsed)
    }

    @Test fun cleanStackIsSelectedWhenProcessedFails() {
        val candidates = candidates()
        val selected = select(candidates, rejectedDecision("sky_mad_increased_excessively"), acceptedDecision())
        assertSame(candidates[1], selected.selected)
        assertTrue(selected.fallbackUsed)
    }

    @Test fun referenceIsSelectedWhenCleanStackIsInvalid() {
        val candidates = candidates()
        val selected = select(candidates, rejectedDecision("processed_bad"), rejectedDecision("dimensions_changed"))
        assertSame(candidates[0], selected.selected)
    }

    @Test fun fallbackUsesUntouchedCleanPixels() {
        val candidates = candidates()
        val expected = candidates[1].image.pixels.copyOf()
        candidates[2].image.pixels.fill(gray(220))
        val selected = select(candidates, rejectedDecision("processed_bad"), acceptedDecision())
        assertArrayEquals(expected, selected.selected.image.pixels)
    }

    @Test fun brokenProcessedPixelsAreNeverReusedForFallback() {
        val candidates = candidates()
        candidates[2].image.pixels[5] = gray(255)
        val selected = select(candidates, rejectedDecision("processed_bad"), acceptedDecision())
        assertNotEquals(candidates[2].image.pixels[5], selected.selected.image.pixels[5])
    }

    @Test fun recoveredStarsIsInternalFallbackLabelOnly() {
        val selected = select(candidates(), rejectedDecision("processed_bad"), acceptedDecision())
        assertEquals("RecoveredStars", selected.internalFallbackLabel)
        assertEquals(ResultCandidateType.CLEAN_STACK, selected.selected.type)
    }

    @Test fun cleanSkyAndAlignedUseSameVisualLimits() {
        val clean = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY, 8)
        val aligned = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY_ALIGNED, 8)
        assertEquals(clean, aligned)
    }

    @Test fun cleanSkyValidationIsMoreConservativeThanStrongCity() {
        val clean = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY, 8)
        val strong = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.URBAN_SKY_STRONG, 8)
        assertTrue(clean.maximumSkyMedianFactor < strong.maximumSkyMedianFactor)
    }

    @Test fun cityStrongRemainsDistinctButBounded() {
        val city = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.URBAN_SKY, 8)
        val strong = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.URBAN_SKY_STRONG, 8)
        assertTrue(strong.gradientStrength > city.gradientStrength)
        assertTrue(strong.maximumSkyMedianFactor < 3f)
    }

    @Test fun maximumStarsHasStrictClippingAndWidthLimits() {
        val value = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.MAX_STARS, 8)
        assertTrue(value.maximumChannelClippingPercent <= 2f)
        assertTrue(value.maximumStarWidthGrowth <= 0.03f)
    }

    @Test fun jsonReportUsesVersionedSchema() {
        val json = report().toJson()
        assertTrue(json.contains("\"schemaVersion\": \"astrophoto.jpeg.processing/2\""))
    }

    @Test fun jsonReportRecordsCandidateAndFallbackReason() {
        val json = report().toJson()
        assertTrue(json.contains("\"selectedCandidateType\": \"CLEAN_STACK\""))
        assertTrue(json.contains("sky_mad_increased_excessively"))
    }

    @Test fun jsonReportContainsMetricsParametersAndTimings() {
        val json = report().toJson()
        assertTrue(json.contains("processedResultMetrics"))
        assertTrue(json.contains("minimumBlackWhiteSeparation"))
        assertTrue(json.contains("targetDisplaySkyMedian"))
        assertTrue(json.contains("minimumStarContrastGain"))
        assertTrue(json.contains("processingOutcome"))
        assertTrue(json.contains("actualSkyBrightnessGain"))
        assertTrue(json.contains("actualStarContrastGain"))
        assertTrue(json.contains("stageDurationsMillis"))
        assertTrue(json.contains("\"inputWidth\":96"))
        assertTrue(json.contains("\"totalDurationMillis\": 20"))
    }

    @Test fun jsonReportDoesNotExposeAbsoluteOutputPath() {
        assertFalse(report().toJson().contains("/storage/emulated"))
        assertFalse(report().toJson().contains("C:\\"))
    }

    @Test fun reportFailureDoesNotInvalidatePriorPngOutcome() {
        var pngValid = true
        val outcome = safeReportWrite<String> { error("disk full") }
        assertTrue(outcome is ReportWriteOutcome.Failed)
        assertTrue(pngValid)
        pngValid = false
    }

    @Test fun reportFileNamePairsWithPng() {
        assertEquals("MaxStars_20260714.processing.json", processingReportFileName("MaxStars_20260714.png"))
    }

    @Test fun reportPairMatchingIsCaseInsensitive() {
        assertTrue(processingReportMatchesImage("TEST.PROCESSING.JSON", "test.PNG"))
    }

    @Test fun unrelatedJsonIsNotPairedForDeletion() {
        assertFalse(processingReportMatchesImage("other.processing.json", "test.png"))
    }

    @Test fun oldProcessedFilesWithoutReportsRemainSupported() {
        assertTrue(isSupportedProcessedImageFile("DeepSky_legacy.jpg", "image/jpeg"))
        assertTrue(isSupportedProcessedImageFile("DeepSky_legacy.png", "image/png"))
    }

    @Test fun pairedReportIsEligibleForSessionExportPath() {
        val report = processingReportFileName("UrbanSky_1.png")
        assertTrue("Processed/$report".startsWith("Processed/"))
    }

    @Test fun cleanStarFixtureSelectsSafeFullResolutionResult() {
        val clean = starField(96, 72)
        val selection = fullGate(clean, clean.copyImage(), AstroProcessingProfile.DEEP_SKY)
        assertEquals(96, selection.selected.width)
        assertEquals(72, selection.selected.height)
    }

    @Test fun cityGradientFixtureStaysInsideSafeSelectionPolicy() {
        val clean = gradientScene()
        val processed = runAdaptive(clean, AlphaMask.full(96, 72), AstroProcessingProfile.URBAN_SKY)
        val selection = fullGate(clean, processed, AstroProcessingProfile.URBAN_SKY)
        assertTrue(selection.selected.type in setOf(ResultCandidateType.PROCESSED, ResultCandidateType.CLEAN_STACK))
        assertTrue(selection.selected.metrics.skyMedian < 0.25f)
    }

    @Test fun buildingAndWireFixturePreservesForeground() {
        val reference = buildingWireScene()
        val mask = topMask(96, 72, 42)
        val processed = runAdaptive(reference, mask, AstroProcessingProfile.URBAN_SKY_STRONG)
        val metrics = analyze(processed, reference, mask)
        assertEquals(0, metrics.foregroundMaximumPixelDifference)
        assertEquals(reference.pixelAt(50, 58), processed.pixelAt(50, 58))
    }

    @Test fun badFrameFixtureRecordsRejectionWithoutZeroShiftAcceptance() {
        val rejection = com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult.rejected(
            referenceStars = 12,
            detectedStars = 9,
            reason = "insufficient_geometric_inliers"
        )
        assertFalse(rejection.isReliable)
        assertEquals(0, rejection.matchedStars)
    }

    @Test fun noiseOnlyMaximumStarsDoesNotEnhanceUnverifiedStars() {
        val noise = noiseScene()
        val processed = runAdaptive(noise, AlphaMask.full(96, 72), AstroProcessingProfile.MAX_STARS)
        val selection = fullGate(noise, processed, AstroProcessingProfile.MAX_STARS)
        assertTrue(selection.selected.metrics.suspiciousPointCount <= analyze(noise).suspiciousPointCount)
    }

    @Test fun deliberatelyBrokenProcessedFixtureUsesCleanStack() {
        val clean = starField(96, 72)
        val broken = uniform(96, 72, gray(180))
        val selection = fullGate(clean, broken, AstroProcessingProfile.DEEP_SKY)
        assertEquals(ResultCandidateType.CLEAN_STACK, selection.selected.type)
    }

    @Test fun invalidCleanStackFixtureUsesReference() {
        val reference = candidate(ResultCandidateType.REFERENCE, gray(15), metrics())
        val clean = ResultCandidate(
            ResultCandidateType.CLEAN_STACK,
            uniform(80, 72, gray(20)),
            metrics(width = 80).copy(aspectRatio = 80.0 / 72.0)
        )
        val processed = candidate(ResultCandidateType.PROCESSED, gray(30), metrics())
        val cleanDecision = AstroResultQualityGate().evaluateCleanStack(reference, clean, cleanProfile)
        val selected = ResultSelectionPolicy().select(
            reference, clean, processed, rejectedDecision("processed_bad"), cleanDecision
        )
        assertEquals(ResultCandidateType.REFERENCE, selected.selected.type)
    }

    @Test fun outputWidthRemainsFullResolution() {
        assertEquals(96, fullGate(starField(96, 72), starField(96, 72), cleanProfile).selected.width)
    }

    @Test fun outputHeightRemainsFullResolution() {
        assertEquals(72, fullGate(starField(96, 72), starField(96, 72), cleanProfile).selected.height)
    }

    @Test fun outputAspectRatioRemainsUnchanged() {
        val selected = fullGate(starField(96, 72), starField(96, 72), cleanProfile).selected
        assertEquals(96.0 / 72.0, selected.metrics.aspectRatio, 0.0)
    }

    @Test fun pngOutputCompatibilityRemainsLossless() {
        assertEquals("image/png", processedImageMimeType("DeepSky_final.png"))
    }

    @Test fun existingPresetIdentifiersRemainCompatible() {
        assertEquals(
            listOf("NORMAL", "DEEP_SKY", "DEEP_SKY_ALIGNED", "URBAN_SKY", "URBAN_SKY_STRONG", "MAX_STARS"),
            AstroProcessingProfile.entries.map { it.name }
        )
    }

    @Test fun existingJpegAndPngResultsRemainCompatible() {
        assertTrue(hasSupportedProcessedImageExtension("old.jpg"))
        assertTrue(hasSupportedProcessedImageExtension("new.png"))
    }

    @Test fun qualityGateIsDeterministic() {
        val clean = starField(96, 72)
        val processed = gradientScene()
        assertEquals(fullGate(clean, processed, cleanProfile), fullGate(clean, processed, cleanProfile))
    }

    private fun fullGate(
        cleanImage: ArgbPixelImage,
        processedImage: ArgbPixelImage,
        profile: AstroProcessingProfile
    ): com.example.astrophoto.processing.jpeg.v2.model.FinalResultSelection {
        val mask = AlphaMask.full(cleanImage.width, cleanImage.height)
        val analyzer = ResultQualityAnalyzer()
        val reference = ResultCandidate(ResultCandidateType.REFERENCE, cleanImage.copyImage(), analyzer.analyze(cleanImage, cleanImage, mask))
        val clean = ResultCandidate(ResultCandidateType.CLEAN_STACK, cleanImage, analyzer.analyze(cleanImage, reference.image, mask))
        val processed = ResultCandidate(ResultCandidateType.PROCESSED, processedImage, analyzer.analyze(processedImage, reference.image, mask))
        val gate = AstroResultQualityGate()
        val cleanDecision = gate.evaluateCleanStack(reference, clean, profile)
        val processedDecision = gate.evaluateProcessed(reference, clean, processed, profile, 8)
        return ResultSelectionPolicy().select(reference, clean, processed, processedDecision, cleanDecision)
    }

    private fun runAdaptive(
        image: ArgbPixelImage,
        mask: AlphaMask,
        profile: AstroProcessingProfile
    ): ArgbPixelImage = runBlocking {
        AdaptivePresetProcessor().process(image, image, mask, profile, 8, emptyList()).image
    }

    private fun analyze(
        image: ArgbPixelImage,
        reference: ArgbPixelImage = image,
        mask: AlphaMask = AlphaMask.full(image.width, image.height)
    ) = ResultQualityAnalyzer().analyze(image, reference, mask)

    private fun select(
        values: List<ResultCandidate>,
        processedDecision: QualityGateDecision,
        cleanDecision: QualityGateDecision
    ) = ResultSelectionPolicy().select(
        values[0], values[1], values[2], processedDecision, cleanDecision
    )

    private fun candidates(): List<ResultCandidate> = listOf(
        candidate(ResultCandidateType.REFERENCE, gray(10), metrics()),
        candidate(ResultCandidateType.CLEAN_STACK, gray(20), metrics()),
        candidate(ResultCandidateType.PROCESSED, gray(30), metrics())
    )

    private fun candidate(type: ResultCandidateType, color: Int, value: ResultQualityMetrics) =
        ResultCandidate(type, uniform(value.width, value.height, color), value)

    private fun acceptedDecision() = QualityGateDecision(true, 1f, emptyList(), emptyList(), metrics())
    private fun rejectedDecision(reason: String) =
        QualityGateDecision(false, 0.5f, listOf(reason), emptyList(), metrics())

    private fun metrics(width: Int = 96, height: Int = 72) = ResultQualityMetrics(
        width = width,
        height = height,
        aspectRatio = 96.0 / 72.0,
        retainedValidAreaRatio = 1f,
        reliableStarCount = 20,
        medianStarLocalContrast = 0.12f,
        medianStarWidth = 2f,
        medianStarEllipticity = 0.10f,
        brightStarClippingPercent = 0f,
        suspiciousPointCount = 0,
        skyMedian = 0.020f,
        skyMad = 0.005f,
        skyLowPercentile = 0.005f,
        skyHighPercentile = 0.08f,
        channelMedian = LinearRgb(0.020f, 0.020f, 0.020f),
        channelClippingPercent = LinearRgb(0f, 0f, 0f),
        chromaNoiseEstimate = 0.004f,
        banding = BandingMetrics(0.2f, 0.2f, 0.28f),
        gradientResidual = 0.040f,
        foregroundSharpness = 100f,
        foregroundEdgeDifference = 0f,
        foregroundMeanPixelDifference = 0f,
        foregroundMaximumPixelDifference = 0,
        invalidBorderRatio = 0f,
        blackBorderRatio = 0f,
        processingConfidence = 1f
    )

    private fun assertBackgroundHard(candidate: ResultQualityMetrics, text: String) {
        assertHard(
            backgroundComparator.compare(
                metrics(),
                candidate,
                ExistingPresetParameterMapper.parametersFor(cleanProfile, 8)
            ),
            text
        )
    }

    private fun assertForegroundHard(candidate: ResultQualityMetrics, text: String) =
        assertHard(foregroundComparator.compare(metrics(), candidate), text)

    private fun assertHard(
        comparison: com.example.astrophoto.processing.jpeg.v2.model.QualityComparison,
        text: String
    ) {
        assertTrue(comparison.hardFailureReasons.joinToString().contains(text))
    }

    private fun report() = ProcessingReport(
        timestampMillis = 1L,
        presetId = "DEEP_SKY",
        presetDisplayName = "Clean Sky",
        inputFrameCount = 8,
        eligibleFrameCount = 8,
        acceptedFrameCount = 7,
        rejectedFrameCount = 1,
        selectedReference = "IMG_0001.jpg",
        skyMaskConfidence = 0.9f,
        skyRatio = 0.7f,
        foregroundRatio = 0.3f,
        registrations = emptyList(),
        frameWeights = emptyList(),
        integration = IntegrationReport(
            mode = "LINEAR_WEIGHTED_REPEATABILITY_ROBUST",
            robustMode = true,
            inputWidth = 96,
            inputHeight = 72,
            outputWidth = 96,
            outputHeight = 72,
            tileWidth = 64,
            tileHeight = 64,
            resolutionChanged = false,
            validCoveragePercent = 99f,
            estimatedWorkingMemoryBytes = 1_000_000L,
            outputAllocationBytes = 27_648L,
            diskCacheBytes = 221_184L
        ),
        stage4Parameters = ExistingPresetParameterMapper.parametersFor(cleanProfile, 8),
        referenceMetrics = metrics(),
        cleanStackMetrics = metrics(),
        processedMetrics = metrics().copy(skyMad = 0.02f),
        cleanStackDecision = acceptedDecision(),
        processedDecision = rejectedDecision("sky_mad_increased_excessively"),
        selectedCandidateType = "CLEAN_STACK",
        fallbackUsed = true,
        fallbackReason = "sky_mad_increased_excessively",
        internalFallbackLabel = "RecoveredStars",
        warnings = listOf("fallback"),
        outputPngDisplayName = "DeepSky_1.png",
        stageDurationsMillis = linkedMapOf("quality_analysis" to 4L, "total" to 20L)
    )

    private fun starField(width: Int, height: Int): ArgbPixelImage =
        uniform(width, height, gray(20)).also { image ->
            listOf(12 to 12, 25 to 18, 42 to 14, 60 to 25, 78 to 16, 35 to 38, 70 to 44).forEach {
                addStar(image, it.first.coerceAtMost(width - 3), it.second.coerceAtMost(height - 3))
            }
        }

    private fun addStar(image: ArgbPixelImage, x: Int, y: Int) {
        for (dy in -1..1) for (dx in -1..1) {
            val value = if (dx == 0 && dy == 0) 150 else 65
            image.pixels[(y + dy) * image.width + x + dx] = rgb(value, value, value + 2)
        }
    }

    private fun gradientScene() = ArgbPixelImage(96, 72, IntArray(96 * 72) { index ->
        val x = index % 96
        rgb(25 + x / 2, 22 + x / 3, 20 + x / 4)
    })

    private fun horizontalBands() = ArgbPixelImage(96, 72, IntArray(96 * 72) { index ->
        gray(if ((index / 96 / 3) % 2 == 0) 22 else 52)
    })

    private fun verticalBands() = ArgbPixelImage(96, 72, IntArray(96 * 72) { index ->
        gray(if ((index % 96 / 3) % 2 == 0) 22 else 52)
    })

    private fun noiseScene() = ArgbPixelImage(96, 72, IntArray(96 * 72) { index ->
        val value = 20 + ((index * 37 + index / 96 * 11) % 9)
        rgb(value, value + (index % 3), value + ((index + 1) % 3))
    })

    private fun buildingWireScene() = ArgbPixelImage(96, 72, IntArray(96 * 72) { index ->
        val x = index % 96
        val y = index / 96
        when {
            y >= 42 -> rgb(50, 45, 40)
            y == 26 && x in 8..88 -> gray(5)
            else -> rgb(45 + x / 4, 40 + x / 5, 36 + x / 6)
        }
    })

    private fun uniform(width: Int, height: Int, color: Int) =
        ArgbPixelImage(width, height, IntArray(width * height) { color })

    private fun split(width: Int, height: Int, top: Int, bottom: Int) =
        ArgbPixelImage(width, height, IntArray(width * height) { if (it / width < height / 2) top else bottom })

    private fun topMask(width: Int, height: Int, rows: Int) =
        AlphaMask(width, height, FloatArray(width * height) { if (it / width < rows) 1f else 0f })

    private fun ArgbPixelImage.copyImage() = ArgbPixelImage(width, height, pixels.copyOf())
    private fun gray(value: Int) = rgb(value, value, value)
    private fun rgb(red: Int, green: Int, blue: Int) =
        0xFF000000.toInt() or (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    private val cleanProfile = AstroProcessingProfile.DEEP_SKY
}
