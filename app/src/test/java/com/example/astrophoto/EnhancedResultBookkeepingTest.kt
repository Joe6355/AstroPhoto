package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.diagnostics.IntegrationReport
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReport
import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingParameters
import com.example.astrophoto.processing.jpeg.v2.model.BandingMetrics
import com.example.astrophoto.processing.jpeg.v2.model.LinearRgb
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedResultBookkeepingTest {
    @Test
    fun reportAndJournalFailuresAfterImageSaveRemainNonFatal() {
        val result = completeSavedResultBookkeeping(
            report = report(),
            writeCacheReport = { error("cache_unavailable") },
            updateJournal = { error("journal_unavailable") }
        )

        assertTrue(result.report.warnings.any { it.contains("cache_unavailable") })
        assertTrue(result.report.warnings.any { it.contains("journal_unavailable") })
        assertTrue(result.reportJson.contains("cache_unavailable"))
        assertTrue(result.reportJson.contains("journal_unavailable"))
    }

    @Test
    fun journalFailureRewritesPreviouslySavedCacheWithFinalWarning() {
        val writes = mutableListOf<String>()

        val result = completeSavedResultBookkeeping(
            report = report(),
            writeCacheReport = { writes += it },
            updateJournal = { error("journal_unavailable") }
        )

        assertEquals(2, writes.size)
        assertEquals(result.reportJson, writes.last())
        assertTrue(writes.last().contains("journal_unavailable"))
    }

    @Test
    fun explicitCancellationIsNeverConvertedIntoAWarning() {
        assertThrows(CancellationException::class.java) {
            completeSavedResultBookkeeping(
                report = report(),
                writeCacheReport = { throw CancellationException("stop") },
                updateJournal = {}
            )
        }
    }

    private fun report(): ProcessingReport {
        val metrics = metrics()
        val decision = QualityGateDecision(
            accepted = true,
            score = 1f,
            hardFailureReasons = emptyList(),
            warningReasons = emptyList(),
            metrics = metrics
        )
        return ProcessingReport(
            timestampMillis = 1L,
            presetId = "DEEP_SKY",
            presetDisplayName = "Clean Sky",
            inputFrameCount = 30,
            eligibleFrameCount = 30,
            acceptedFrameCount = 30,
            rejectedFrameCount = 0,
            selectedReference = "AstroSeries_009.jpg",
            skyMaskConfidence = 1f,
            skyRatio = 0.7f,
            foregroundRatio = 0.3f,
            registrations = emptyList(),
            frameWeights = emptyList(),
            integration = IntegrationReport(
                mode = "LINEAR_WEIGHTED",
                robustMode = false,
                inputWidth = 4,
                inputHeight = 4,
                outputWidth = 4,
                outputHeight = 4,
                tileWidth = 4,
                tileHeight = 4,
                resolutionChanged = false,
                validCoveragePercent = 100f,
                estimatedWorkingMemoryBytes = 0L,
                outputAllocationBytes = 64L,
                diskCacheBytes = 64L
            ),
            stage4Parameters = AdaptiveProcessingParameters(
                gradientStrength = 0f,
                neutralizationStrength = 0f,
                stretchBlend = 0f,
                asinhStrength = 0f,
                highlightProtection = 0f,
                chromaNoiseStrength = 0f,
                starContrastStrength = 0f,
                maximumSkyMedianFactor = 1f,
                maximumChannelClippingPercent = 0f,
                minimumBlackWhiteSeparation = 0.01f,
                maximumGradientCorrection = 0f,
                maximumNeutralizationCorrection = 0f,
                maximumStarDetailGain = 1f,
                maximumChromaRadius = 0,
                maximumStarWidthGrowth = 0f
            ),
            referenceMetrics = metrics,
            cleanStackMetrics = metrics,
            processedMetrics = metrics,
            cleanStackDecision = decision,
            processedDecision = decision,
            selectedCandidateType = "CLEAN_STACK",
            fallbackUsed = true,
            fallbackReason = null,
            internalFallbackLabel = "RecoveredStars",
            warnings = emptyList(),
            outputPngDisplayName = "RecoveredStars_1.png",
            stageDurationsMillis = emptyMap()
        )
    }

    private fun metrics() = ResultQualityMetrics(
        width = 4,
        height = 4,
        aspectRatio = 1.0,
        retainedValidAreaRatio = 1f,
        reliableStarCount = 4,
        medianStarLocalContrast = 0.1f,
        medianStarWidth = 1f,
        medianStarEllipticity = 0.1f,
        brightStarClippingPercent = 0f,
        suspiciousPointCount = 0,
        skyMedian = 0.02f,
        skyMad = 0.001f,
        skyLowPercentile = 0.005f,
        skyHighPercentile = 0.05f,
        channelMedian = LinearRgb(0.02f, 0.02f, 0.02f),
        channelClippingPercent = LinearRgb(0f, 0f, 0f),
        chromaNoiseEstimate = 0f,
        banding = BandingMetrics(0f, 0f, 0f),
        gradientResidual = 0f,
        foregroundSharpness = 1f,
        foregroundEdgeDifference = 0f,
        foregroundMeanPixelDifference = 0f,
        foregroundMaximumPixelDifference = 0,
        invalidBorderRatio = 0f,
        blackBorderRatio = 0f,
        processingConfidence = 1f
    )
}
