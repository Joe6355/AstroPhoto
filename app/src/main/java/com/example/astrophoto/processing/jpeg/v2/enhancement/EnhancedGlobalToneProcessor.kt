package com.example.astrophoto.processing.jpeg.v2.enhancement

import com.example.astrophoto.SavedProcessedImage
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.FileBackedSkyStatistics
import com.example.astrophoto.processing.jpeg.v2.quality.FileBackedResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

data class EnhancedGlobalToneGeneration(
    val image: FileBackedImage,
    val anchors: GlobalToneAnchors,
    val scaleLimitedPixelCount: Int,
    val maximumLinearChannel: Double
)

data class EnhancedGlobalToneValidationMetrics(
    val evaluatedStarCount: Int,
    val confirmedStarContrastMedianRatio: Double,
    val confirmedStarContrastMinimumRatio: Double,
    val confirmedStarLostCount: Int,
    val confirmedStarWeakenedCount: Int,
    val medianStarWidthRelativeChange: Double,
    val maximumStarWidthRelativeChange: Double,
    val medianStarEllipticityRelativeChange: Double,
    val maximumStarEllipticityRelativeChange: Double,
    val baselineVisibleStarCount: Int,
    val candidateVisibleStarCount: Int,
    val normalizedSkyMadRatio: Double,
    val normalizedBandingRatio: Double,
    val normalizedGradientRatio: Double,
    val baselineSuspiciousPointCount: Int,
    val candidateSuspiciousPointCount: Int,
    val fixedHighlightPixelCount: Int,
    val baselineClippedHighlightCount: Int,
    val candidateClippedHighlightCount: Int,
    val highlightClippingIncreasePercentagePoints: Double,
    val foregroundStrongEdgeRetention: Double,
    val foregroundEdgeSignAgreement: Double,
    val foregroundEdgeCosineSimilarity: Double,
    val newLongLineComponents: Int,
    val lineArtifactScore: Double,
    val fanPatternScore: Double,
    val newStrongColorPatchCount: Int,
    val largestStrongColorPatchSamples: Int,
    val scaleLimitedPixelCount: Int,
    val scaleLimitedSkyPixelCount: Int,
    val scaleLimitedHighlightPixelCount: Int,
    val scaleLimitedStarWindowPixelCount: Int,
    val maximumLinearChannel: Double
) {
    fun asReportMetrics(): Map<String, Float> = linkedMapOf(
        "gain" to GlobalToneTransform.APPROVED_GAIN.toFloat(),
        "evaluatedStarCount" to evaluatedStarCount.toFloat(),
        "fixedSupportEvaluatedStarCount" to evaluatedStarCount.toFloat(),
        "starContrastMedianRatio" to confirmedStarContrastMedianRatio.finiteFloat(),
        "starContrastMinimumRatio" to confirmedStarContrastMinimumRatio.finiteFloat(),
        "starLostCount" to confirmedStarLostCount.toFloat(),
        "starWeakenedCount" to confirmedStarWeakenedCount.toFloat(),
        "medianStarWidthRelativeChange" to medianStarWidthRelativeChange.finiteFloat(),
        "maximumStarWidthRelativeChange" to maximumStarWidthRelativeChange.finiteFloat(),
        "medianStarEllipticityRelativeChange" to medianStarEllipticityRelativeChange.finiteFloat(),
        "maximumStarEllipticityRelativeChange" to maximumStarEllipticityRelativeChange.finiteFloat(),
        "baselineVisibleStarCount" to baselineVisibleStarCount.toFloat(),
        "candidateVisibleStarCount" to candidateVisibleStarCount.toFloat(),
        "baselineIndependentDetectorVisibleStarCount" to
            baselineVisibleStarCount.toFloat(),
        "candidateIndependentDetectorVisibleStarCount" to
            candidateVisibleStarCount.toFloat(),
        "normalizedSkyMadRatio" to normalizedSkyMadRatio.finiteFloat(),
        "normalizedBandingRatio" to normalizedBandingRatio.finiteFloat(),
        "normalizedGradientRatio" to normalizedGradientRatio.finiteFloat(),
        "baselineSuspiciousPointCount" to baselineSuspiciousPointCount.toFloat(),
        "candidateSuspiciousPointCount" to candidateSuspiciousPointCount.toFloat(),
        "fixedHighlightPixelCount" to fixedHighlightPixelCount.toFloat(),
        "baselineClippedHighlightCount" to baselineClippedHighlightCount.toFloat(),
        "candidateClippedHighlightCount" to candidateClippedHighlightCount.toFloat(),
        "highlightClippingIncreasePercentagePoints" to
            highlightClippingIncreasePercentagePoints.finiteFloat(),
        "foregroundStrongEdgeRetention" to foregroundStrongEdgeRetention.finiteFloat(),
        "foregroundEdgeSignAgreement" to foregroundEdgeSignAgreement.finiteFloat(),
        "foregroundEdgeCosineSimilarity" to foregroundEdgeCosineSimilarity.finiteFloat(),
        "newLongLineComponents" to newLongLineComponents.toFloat(),
        "lineArtifactScore" to lineArtifactScore.finiteFloat(),
        "fanPatternScore" to fanPatternScore.finiteFloat(),
        "newStrongColorPatchCount" to newStrongColorPatchCount.toFloat(),
        "largestStrongColorPatchSamples" to largestStrongColorPatchSamples.toFloat(),
        "scaleLimitedPixelCount" to scaleLimitedPixelCount.toFloat(),
        "scaleLimitedSkyPixelCount" to scaleLimitedSkyPixelCount.toFloat(),
        "scaleLimitedHighlightPixelCount" to scaleLimitedHighlightPixelCount.toFloat(),
        "scaleLimitedStarWindowPixelCount" to scaleLimitedStarWindowPixelCount.toFloat(),
        "maximumLinearChannel" to maximumLinearChannel.finiteFloat()
    )
}

data class EnhancedGlobalToneValidation(
    val accepted: Boolean,
    val hardFailureReasons: List<String>,
    val warnings: List<String>,
    val metrics: EnhancedGlobalToneValidationMetrics
)

data class EnhancedGlobalToneCandidate(
    val generation: EnhancedGlobalToneGeneration,
    val validation: EnhancedGlobalToneValidation,
    val baselinePixelHashBefore: String,
    val baselinePixelHashAfter: String
)

class FileBackedGlobalToneTransformer(
    private val transform: GlobalToneTransform = GlobalToneTransform()
) {
    fun transform(
        baseline: FileBackedImage,
        writer: FileBackedImageWriter,
        anchors: GlobalToneAnchors,
        gain: Double = GlobalToneTransform.APPROVED_GAIN
    ): EnhancedGlobalToneGeneration {
        require(writer.image.width == baseline.width && writer.image.height == baseline.height)
        val sourceRow = IntArray(baseline.width)
        val outputRow = IntArray(baseline.width)
        var scaleLimitedPixelCount = 0
        var maximumLinearChannel = 0.0
        FileBackedImageReader(baseline).use { reader ->
            for (y in 0 until baseline.height) {
                reader.readArgbRow(y, sourceRow)
                val rowMetrics = transform.transformRow(
                    source = sourceRow,
                    destination = outputRow,
                    anchors = anchors,
                    gain = gain,
                    pixelCount = baseline.width
                )
                writer.writeRow(y, outputRow)
                scaleLimitedPixelCount += rowMetrics.scaleLimitedPixelCount
                maximumLinearChannel = max(
                    maximumLinearChannel,
                    rowMetrics.maximumLinearChannel
                )
            }
        }
        return EnhancedGlobalToneGeneration(
            image = writer.finish(),
            anchors = anchors,
            scaleLimitedPixelCount = scaleLimitedPixelCount,
            maximumLinearChannel = maximumLinearChannel
        )
    }
}

class EnhancedGlobalToneProcessor(
    private val skyStatistics: FileBackedSkyStatistics = FileBackedSkyStatistics(),
    private val transformer: FileBackedGlobalToneTransformer = FileBackedGlobalToneTransformer(),
    private val validator: EnhancedGlobalToneValidator = EnhancedGlobalToneValidator()
) {
    fun createCandidate(
        baseline: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane,
        confirmedStars: List<DetectedStar>,
        store: ResultCandidateStore
    ): EnhancedGlobalToneCandidate {
        require(baseline.width == effectiveSkyAlpha.width && baseline.height == effectiveSkyAlpha.height)
        val baselineHashBefore = fileBackedPixelHash(baseline)
        val sky = FileBackedImageReader(baseline).use { image ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha).use { alpha ->
                skyStatistics.calculate(image, alpha, confirmedStars)
            }
        }
        val toeStart = sky.lowPercentile.toDouble()
            .coerceIn(0.0, 1.0 - GlobalToneTransform.EPSILON)
        val anchors = GlobalToneAnchors(
            toeStart = toeStart,
            toeEnd = max(
                sky.luminanceMedian.toDouble(),
                toeStart + GlobalToneTransform.EPSILON
            ).coerceAtMost(1.0)
        )
        val writer = store.createTemporaryWriter(
            label = "enhanced-global-tone",
            width = baseline.width,
            height = baseline.height
        )
        val generation = try {
            transformer.transform(
                baseline = baseline,
                writer = writer,
                anchors = anchors
            )
        } catch (error: Throwable) {
            runCatching { writer.close() }
            runCatching { store.deleteTemporary(writer.image) }
            throw error
        }
        return try {
            val baselineHashAfter = fileBackedPixelHash(baseline)
            val measured = validator.validate(
                baseline = baseline,
                candidate = generation.image,
                effectiveSkyAlpha = effectiveSkyAlpha,
                confirmedStars = confirmedStars,
                anchors = anchors,
                generatedScaleLimitedPixelCount = generation.scaleLimitedPixelCount,
                maximumLinearChannel = generation.maximumLinearChannel
            )
            val validation = if (baselineHashBefore == baselineHashAfter) {
                measured
            } else {
                measured.copy(
                    accepted = false,
                    hardFailureReasons = (
                        measured.hardFailureReasons + "recovered_stars_baseline_changed"
                        ).distinct()
                )
            }
            EnhancedGlobalToneCandidate(
                generation = generation,
                validation = validation,
                baselinePixelHashBefore = baselineHashBefore,
                baselinePixelHashAfter = baselineHashAfter
            )
        } catch (error: Throwable) {
            runCatching { store.deleteTemporary(generation.image) }
            throw error
        }
    }
}

open class EnhancedGlobalToneValidator(
    private val transform: GlobalToneTransform = GlobalToneTransform(),
    private val qualityAnalyzer: FileBackedResultQualityAnalyzer = FileBackedResultQualityAnalyzer(),
    private val lineArtifactDetector: LineArtifactDetector = LineArtifactDetector()
) {
    open fun validate(
        baseline: FileBackedImage,
        candidate: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane,
        confirmedStars: List<DetectedStar>,
        anchors: GlobalToneAnchors,
        generatedScaleLimitedPixelCount: Int,
        maximumLinearChannel: Double
    ): EnhancedGlobalToneValidation {
        require(baseline.width == candidate.width && baseline.height == candidate.height)
        require(baseline.width == effectiveSkyAlpha.width && baseline.height == effectiveSkyAlpha.height)
        val baselineQuality = qualityAnalyzer.analyze(baseline, baseline, effectiveSkyAlpha)
        val candidateQuality = qualityAnalyzer.analyze(candidate, baseline, effectiveSkyAlpha)
        val lineArtifacts = lineArtifactDetector.compare(baseline, candidate, effectiveSkyAlpha)
        return FileBackedImageReader(baseline, cachedRows = 20).use { baselineReader ->
            FileBackedImageReader(candidate, cachedRows = 20).use { candidateReader ->
                FileBackedFloatPlaneReader(effectiveSkyAlpha, cachedRows = 5).use { alpha ->
                    validateSources(
                        baseline = baselineReader,
                        candidate = candidateReader,
                        alpha = alpha,
                        confirmedStars = confirmedStars,
                        anchors = anchors,
                        generatedScaleLimitedPixelCount = generatedScaleLimitedPixelCount,
                        maximumLinearChannel = maximumLinearChannel,
                        baselineQuality = baselineQuality,
                        candidateQuality = candidateQuality,
                        newLongLineComponents = lineArtifacts.metrics.newLongLineComponents,
                        lineArtifactScore = lineArtifacts.metrics.lineArtifactScore.toDouble(),
                        fanPatternScore = lineArtifacts.metrics.fanPatternScore.toDouble(),
                        lineHardFailures = lineArtifacts.hardFailureReasons,
                        lineWarnings = lineArtifacts.warningReasons
                    )
                }
            }
        }
    }

    private fun validateSources(
        baseline: ArgbPixelSource,
        candidate: ArgbPixelSource,
        alpha: AlphaPixelSource,
        confirmedStars: List<DetectedStar>,
        anchors: GlobalToneAnchors,
        generatedScaleLimitedPixelCount: Int,
        maximumLinearChannel: Double,
        baselineQuality: ResultQualityMetrics,
        candidateQuality: ResultQualityMetrics,
        newLongLineComponents: Int,
        lineArtifactScore: Double,
        fanPatternScore: Double,
        lineHardFailures: List<String>,
        lineWarnings: List<String>
    ): EnhancedGlobalToneValidation {
        val supports = FixedStarSupportFactory.create(
            baseline.width,
            baseline.height,
            confirmedStars
        )
        val baselineStars = FixedStarMeasurer.measure(baseline, supports)
        val candidateStars = FixedStarMeasurer.measure(candidate, supports)
        val measurable = baselineStars.indices.filter {
            baselineStars[it].contrast > MIN_MEASURABLE_STAR_CONTRAST &&
                baselineStars[it].width > MIN_MEASURABLE_STAR_WIDTH
        }
        val contrastRatios = measurable.map { index ->
            safeRatio(candidateStars[index].contrast, baselineStars[index].contrast)
        }
        val widthChanges = measurable.map { index ->
            relativeChange(candidateStars[index].width, baselineStars[index].width)
        }
        val ellipticityChanges = measurable.map { index ->
            abs(candidateStars[index].ellipticity - baselineStars[index].ellipticity) /
                max(baselineStars[index].ellipticity, MIN_ELLIPTICITY_REFERENCE)
        }
        val lostStars = measurable.count { index ->
            candidateStars[index].contrast <= MIN_MEASURABLE_STAR_CONTRAST
        }
        val weakenedStars = contrastRatios.count { it < MIN_CONFIRMED_STAR_CONTRAST_RATIO }
        val medianWidthChange = percentile(widthChanges, 0.50)
        val maximumWidthChange = widthChanges.maxOrNull() ?: 0.0
        val medianEllipticityChange = percentile(ellipticityChanges, 0.50)
        val maximumEllipticityChange = ellipticityChanges.maxOrNull() ?: 0.0
        val fixedStarIndices = supports.flatMapTo(hashSetOf()) {
            it.measurementIndices.asIterable()
        }
        val imageMetrics = imageMetrics(
            baseline,
            candidate,
            alpha,
            fixedStarIndices,
            anchors
        )
        val colorPatches = strongColorPatchMetrics(
            baseline,
            candidate,
            alpha,
            confirmedStars
        )
        val slope = transform.slopeAt(
            baselineQuality.skyMedian.toDouble(),
            anchors
        ).coerceAtLeast(MIN_NORMALIZATION_SLOPE)
        val normalizedSkyMadRatio = normalizedRatio(
            candidateQuality.skyMad.toDouble(),
            baselineQuality.skyMad.toDouble(),
            slope,
            LINEAR_METRIC_ALLOWANCE
        )
        val normalizedBandingRatio = normalizedRatio(
            candidateQuality.banding.combinedScore.toDouble(),
            baselineQuality.banding.combinedScore.toDouble(),
            slope,
            BANDING_ALLOWANCE
        )
        val normalizedGradientRatio = normalizedRatio(
            candidateQuality.gradientResidual.toDouble(),
            baselineQuality.gradientResidual.toDouble(),
            slope,
            LINEAR_METRIC_ALLOWANCE
        )
        val requiredStarCount = min(MIN_FIXED_STAR_SUPPORTS, confirmedStars.size)
        val hardFailures = buildList {
            if (confirmedStars.isEmpty() || measurable.size < requiredStarCount) {
                add("insufficient_confirmed_stars_for_enhanced_validation")
            }
            if (lostStars > 0) add("confirmed_star_lost")
            if (weakenedStars > 0) add("confirmed_star_weakened")
            if (medianWidthChange > MAX_MEDIAN_GEOMETRY_CHANGE) {
                add("median_star_width_changed_over_3_percent")
            }
            if (maximumWidthChange > MAX_GEOMETRY_CHANGE) {
                add("maximum_star_width_changed_over_5_percent")
            }
            if (medianEllipticityChange > MAX_MEDIAN_GEOMETRY_CHANGE) {
                add("median_star_ellipticity_changed_over_3_percent")
            }
            if (maximumEllipticityChange > MAX_GEOMETRY_CHANGE) {
                add("maximum_star_ellipticity_changed_over_5_percent")
            }
            if (
                imageMetrics.highlightClippingIncreasePercentagePoints >
                MATERIAL_CLIPPING_INCREASE_PERCENT &&
                imageMetrics.candidateClippedHighlightCount -
                imageMetrics.baselineClippedHighlightCount >
                MATERIAL_CLIPPING_PIXEL_COUNT
            ) add("material_window_highlight_clipping")
            if (
                imageMetrics.foregroundStrongEdgeRetention < MIN_STRONG_EDGE_RETENTION ||
                imageMetrics.foregroundEdgeSignAgreement < MIN_EDGE_SIGN_AGREEMENT ||
                imageMetrics.foregroundEdgeCosineSimilarity < MIN_EDGE_COSINE_SIMILARITY
            ) add("foreground_edge_geometry_changed")
            if (
                normalizedBandingRatio > STRONG_BACKGROUND_NORMALIZED_RATIO &&
                candidateQuality.banding.combinedScore -
                baselineQuality.banding.combinedScore >
                STRONG_BANDING_ABSOLUTE_INCREASE
            ) add("strong_banding_appeared")
            if (
                normalizedGradientRatio > STRONG_BACKGROUND_NORMALIZED_RATIO &&
                candidateQuality.gradientResidual - baselineQuality.gradientResidual >
                STRONG_GRADIENT_ABSOLUTE_INCREASE
            ) add("strong_gradient_or_contour_appeared")
            if (colorPatches.componentCount > 0) add("new_strong_color_patch")
            if (maximumLinearChannel > 1.0 + LINEAR_CHANNEL_TOLERANCE) {
                add("linear_channel_overflow")
            }
            if (imageMetrics.recomputedScaleLimitedPixelCount != generatedScaleLimitedPixelCount) {
                add("scale_limit_accounting_mismatch")
            }
            addAll(lineHardFailures)
        }.distinct()
        val warnings = buildList {
            if (normalizedSkyMadRatio > 1.0) add("sky_mad_increased_visual_finish")
            if (normalizedBandingRatio > 1.0) add("banding_increased_visual_finish")
            if (normalizedGradientRatio > 1.0) add("gradient_increased_visual_finish")
            if (
                candidateQuality.suspiciousPointCount >
                baselineQuality.suspiciousPointCount
            ) add("suspicious_point_count_increased")
            if (imageMetrics.scaleLimitedSkyPixelCount > 0) {
                add("scale_limiting_outside_highlights")
            }
            addAll(lineWarnings)
        }.distinct()
        val metrics = EnhancedGlobalToneValidationMetrics(
            evaluatedStarCount = measurable.size,
            confirmedStarContrastMedianRatio = percentile(contrastRatios, 0.50),
            confirmedStarContrastMinimumRatio = contrastRatios.minOrNull() ?: 0.0,
            confirmedStarLostCount = lostStars,
            confirmedStarWeakenedCount = weakenedStars,
            medianStarWidthRelativeChange = medianWidthChange,
            maximumStarWidthRelativeChange = maximumWidthChange,
            medianStarEllipticityRelativeChange = medianEllipticityChange,
            maximumStarEllipticityRelativeChange = maximumEllipticityChange,
            baselineVisibleStarCount = baselineQuality.reliableStarCount,
            candidateVisibleStarCount = candidateQuality.reliableStarCount,
            normalizedSkyMadRatio = normalizedSkyMadRatio,
            normalizedBandingRatio = normalizedBandingRatio,
            normalizedGradientRatio = normalizedGradientRatio,
            baselineSuspiciousPointCount = baselineQuality.suspiciousPointCount,
            candidateSuspiciousPointCount = candidateQuality.suspiciousPointCount,
            fixedHighlightPixelCount = imageMetrics.fixedHighlightPixelCount,
            baselineClippedHighlightCount = imageMetrics.baselineClippedHighlightCount,
            candidateClippedHighlightCount = imageMetrics.candidateClippedHighlightCount,
            highlightClippingIncreasePercentagePoints =
                imageMetrics.highlightClippingIncreasePercentagePoints,
            foregroundStrongEdgeRetention = imageMetrics.foregroundStrongEdgeRetention,
            foregroundEdgeSignAgreement = imageMetrics.foregroundEdgeSignAgreement,
            foregroundEdgeCosineSimilarity = imageMetrics.foregroundEdgeCosineSimilarity,
            newLongLineComponents = newLongLineComponents,
            lineArtifactScore = lineArtifactScore,
            fanPatternScore = fanPatternScore,
            newStrongColorPatchCount = colorPatches.componentCount,
            largestStrongColorPatchSamples = colorPatches.largestComponent,
            scaleLimitedPixelCount = generatedScaleLimitedPixelCount,
            scaleLimitedSkyPixelCount = imageMetrics.scaleLimitedSkyPixelCount,
            scaleLimitedHighlightPixelCount = imageMetrics.scaleLimitedHighlightPixelCount,
            scaleLimitedStarWindowPixelCount = imageMetrics.scaleLimitedStarWindowPixelCount,
            maximumLinearChannel = maximumLinearChannel
        )
        return EnhancedGlobalToneValidation(
            accepted = hardFailures.isEmpty(),
            hardFailureReasons = hardFailures,
            warnings = warnings,
            metrics = metrics
        )
    }

    private data class ImageMetrics(
        val fixedHighlightPixelCount: Int,
        val baselineClippedHighlightCount: Int,
        val candidateClippedHighlightCount: Int,
        val highlightClippingIncreasePercentagePoints: Double,
        val foregroundStrongEdgeRetention: Double,
        val foregroundEdgeSignAgreement: Double,
        val foregroundEdgeCosineSimilarity: Double,
        val recomputedScaleLimitedPixelCount: Int,
        val scaleLimitedSkyPixelCount: Int,
        val scaleLimitedHighlightPixelCount: Int,
        val scaleLimitedStarWindowPixelCount: Int
    )

    private fun imageMetrics(
        baseline: ArgbPixelSource,
        candidate: ArgbPixelSource,
        alpha: AlphaPixelSource,
        fixedStarIndices: Set<Int>,
        anchors: GlobalToneAnchors
    ): ImageMetrics {
        var fixedHighlights = 0
        var baselineClipped = 0
        var candidateClipped = 0
        var strongEdges = 0
        var retainedEdges = 0
        var signAgreed = 0
        var dot = 0.0
        var baselineEnergy = 0.0
        var candidateEnergy = 0.0
        var recomputedLimited = 0
        var skyLimited = 0
        var highlightLimited = 0
        var starLimited = 0
        fun edge(firstX: Int, firstY: Int, secondX: Int, secondY: Int) {
            if (
                alpha.alphaAt(firstX, firstY) > FOREGROUND_ALPHA_THRESHOLD ||
                alpha.alphaAt(secondX, secondY) > FOREGROUND_ALPHA_THRESHOLD
            ) return
            val baselineGradient = linearLuminance(baseline.argbAt(secondX, secondY)) -
                linearLuminance(baseline.argbAt(firstX, firstY))
            val candidateGradient = linearLuminance(candidate.argbAt(secondX, secondY)) -
                linearLuminance(candidate.argbAt(firstX, firstY))
            val baselineMagnitude = abs(baselineGradient)
            if (baselineMagnitude < STRONG_EDGE_MINIMUM_LINEAR) return
            strongEdges++
            if (abs(candidateGradient) >= baselineMagnitude * EDGE_RETENTION_FRACTION) {
                retainedEdges++
            }
            if (baselineGradient * candidateGradient > 0.0) signAgreed++
            dot += baselineGradient * candidateGradient
            baselineEnergy += baselineGradient * baselineGradient
            candidateEnergy += candidateGradient * candidateGradient
        }
        for (y in 0 until baseline.height) for (x in 0 until baseline.width) {
            val index = y * baseline.width + x
            val baselineColor = baseline.argbAt(x, y)
            val candidateColor = candidate.argbAt(x, y)
            val alphaValue = alpha.alphaAt(x, y)
            val highlight = alphaValue <= FOREGROUND_ALPHA_THRESHOLD &&
                maximumEncodedChannel(baselineColor) >= HIGHLIGHT_ENCODED_THRESHOLD
            if (highlight) {
                fixedHighlights++
                if (maximumEncodedChannel(baselineColor) >= 255) baselineClipped++
                if (maximumEncodedChannel(candidateColor) >= 255) candidateClipped++
            }
            if (transform.transformArgb(baselineColor, anchors).scaleLimited) {
                recomputedLimited++
                if (alphaValue >= SKY_ALPHA_THRESHOLD) skyLimited++
                if (highlight) highlightLimited++
                if (index in fixedStarIndices) starLimited++
            }
            if (x + 1 < baseline.width) edge(x, y, x + 1, y)
            if (y + 1 < baseline.height) edge(x, y, x, y + 1)
        }
        val baselinePercent = baselineClipped * 100.0 / fixedHighlights.coerceAtLeast(1)
        val candidatePercent = candidateClipped * 100.0 / fixedHighlights.coerceAtLeast(1)
        val cosine = if (baselineEnergy <= 1e-18 || candidateEnergy <= 1e-18) {
            1.0
        } else {
            (dot / sqrt(baselineEnergy * candidateEnergy)).coerceIn(-1.0, 1.0)
        }
        return ImageMetrics(
            fixedHighlightPixelCount = fixedHighlights,
            baselineClippedHighlightCount = baselineClipped,
            candidateClippedHighlightCount = candidateClipped,
            highlightClippingIncreasePercentagePoints = candidatePercent - baselinePercent,
            foregroundStrongEdgeRetention =
                retainedEdges.toDouble() / strongEdges.coerceAtLeast(1),
            foregroundEdgeSignAgreement =
                signAgreed.toDouble() / strongEdges.coerceAtLeast(1),
            foregroundEdgeCosineSimilarity = cosine,
            recomputedScaleLimitedPixelCount = recomputedLimited,
            scaleLimitedSkyPixelCount = skyLimited,
            scaleLimitedHighlightPixelCount = highlightLimited,
            scaleLimitedStarWindowPixelCount = starLimited
        )
    }

    private data class ColorPatchMetrics(val componentCount: Int, val largestComponent: Int)

    private fun strongColorPatchMetrics(
        baseline: ArgbPixelSource,
        candidate: ArgbPixelSource,
        alpha: AlphaPixelSource,
        stars: List<DetectedStar>
    ): ColorPatchMetrics {
        val scale = min(1.0, COLOR_ANALYSIS_MAX_DIMENSION.toDouble() /
            max(baseline.width, baseline.height))
        val width = max(3, (baseline.width * scale).roundToInt())
        val height = max(3, (baseline.height * scale).roundToInt())
        val changed = BooleanArray(width * height)
        fun nearStar(sourceX: Int, sourceY: Int): Boolean = stars.any { star ->
            val radius = max(2.0, star.width.toDouble() * 1.8).coerceAtMost(8.0)
            val dx = sourceX.toDouble() - star.x
            val dy = sourceY.toDouble() - star.y
            dx * dx + dy * dy <= radius * radius
        }
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val sourceX = (x.toLong() * baseline.width / width).toInt()
                .coerceIn(0, baseline.width - 1)
            val sourceY = (y.toLong() * baseline.height / height).toInt()
                .coerceIn(0, baseline.height - 1)
            if (alpha.alphaAt(sourceX, sourceY) < SKY_ALPHA_THRESHOLD ||
                nearStar(sourceX, sourceY)
            ) continue
            val before = linearChromaticity(baseline.argbAt(sourceX, sourceY)) ?: continue
            val after = linearChromaticity(candidate.argbAt(sourceX, sourceY)) ?: continue
            val shift = max(
                abs(after.first - before.first),
                max(abs(after.second - before.second), abs(after.third - before.third))
            )
            changed[y * width + x] = shift >= STRONG_CHROMATICITY_SHIFT
        }
        val visited = BooleanArray(changed.size)
        val queue = IntArray(changed.size)
        var components = 0
        var largest = 0
        changed.indices.forEach { start ->
            if (!changed[start] || visited[start]) return@forEach
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (changed[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            if (tail >= MIN_STRONG_COLOR_PATCH_SAMPLES) {
                components++
                largest = max(largest, tail)
            }
        }
        return ColorPatchMetrics(components, largest)
    }

    private data class Chromaticity(val first: Double, val second: Double, val third: Double)

    private fun linearChromaticity(color: Int): Chromaticity? {
        val red = LINEAR_CHANNEL_LUT[color ushr 16 and 0xFF]
        val green = LINEAR_CHANNEL_LUT[color ushr 8 and 0xFF]
        val blue = LINEAR_CHANNEL_LUT[color and 0xFF]
        val sum = red + green + blue
        if (sum < MIN_CHROMATICITY_SIGNAL) return null
        return Chromaticity(red / sum, green / sum, blue / sum)
    }

    private fun linearLuminance(color: Int): Double =
        REC_709_RED * LINEAR_CHANNEL_LUT[color ushr 16 and 0xFF] +
            REC_709_GREEN * LINEAR_CHANNEL_LUT[color ushr 8 and 0xFF] +
            REC_709_BLUE * LINEAR_CHANNEL_LUT[color and 0xFF]

    private fun maximumEncodedChannel(color: Int): Int =
        max(color ushr 16 and 0xFF, max(color ushr 8 and 0xFF, color and 0xFF))

    companion object {
        private const val MIN_FIXED_STAR_SUPPORTS = 4
        private const val MIN_MEASURABLE_STAR_CONTRAST = 1e-6
        private const val MIN_MEASURABLE_STAR_WIDTH = 1e-6
        private const val MIN_ELLIPTICITY_REFERENCE = 0.05
        private const val MIN_CONFIRMED_STAR_CONTRAST_RATIO = 0.999
        private const val MAX_MEDIAN_GEOMETRY_CHANGE = 0.03
        private const val MAX_GEOMETRY_CHANGE = 0.05
        private const val SKY_ALPHA_THRESHOLD = 0.98f
        private const val FOREGROUND_ALPHA_THRESHOLD = 0.001f
        private const val HIGHLIGHT_ENCODED_THRESHOLD = 220
        private const val MATERIAL_CLIPPING_INCREASE_PERCENT = 1.0
        private const val MATERIAL_CLIPPING_PIXEL_COUNT = 64
        private const val STRONG_EDGE_MINIMUM_LINEAR = 0.010
        private const val EDGE_RETENTION_FRACTION = 0.50
        private const val MIN_STRONG_EDGE_RETENTION = 0.98
        private const val MIN_EDGE_SIGN_AGREEMENT = 0.995
        private const val MIN_EDGE_COSINE_SIMILARITY = 0.98
        private const val STRONG_BACKGROUND_NORMALIZED_RATIO = 3.0
        private const val STRONG_BANDING_ABSOLUTE_INCREASE = 0.05
        private const val STRONG_GRADIENT_ABSOLUTE_INCREASE = 0.02
        private const val LINEAR_METRIC_ALLOWANCE = 1.0 / 4095.0
        private const val BANDING_ALLOWANCE = 0.01
        private const val MIN_NORMALIZATION_SLOPE = 1e-6
        private const val LINEAR_CHANNEL_TOLERANCE = 1e-12
        private const val COLOR_ANALYSIS_MAX_DIMENSION = 960
        private const val MIN_CHROMATICITY_SIGNAL = 0.003
        private const val STRONG_CHROMATICITY_SHIFT = 0.08
        private const val MIN_STRONG_COLOR_PATCH_SAMPLES = 4
        private const val REC_709_RED = 0.2126
        private const val REC_709_GREEN = 0.7152
        private const val REC_709_BLUE = 0.0722
        private val LINEAR_CHANNEL_LUT = DoubleArray(256) { encoded ->
            val value = encoded / 255.0
            if (value <= 0.04045) value / 12.92
            else ((value + 0.055) / 1.055).pow(2.4)
        }
    }
}

private data class FixedStarSupport(
    val baselineX: Double,
    val baselineY: Double,
    val measurementIndices: IntArray,
    val coreIndices: IntArray,
    val annulusIndices: IntArray
)

private data class FixedStarMeasurement(
    val contrast: Double,
    val centroidX: Double,
    val centroidY: Double,
    val width: Double,
    val ellipticity: Double
)

private object FixedStarSupportFactory {
    fun create(
        width: Int,
        height: Int,
        stars: List<DetectedStar>
    ): List<FixedStarSupport> = stars.mapNotNull { star ->
        val centerX = star.x.roundToInt()
        val centerY = star.y.roundToInt()
        val coreRadius = ceil(max(1.0, star.width.toDouble() * 0.60)).toInt()
            .coerceIn(1, 3)
        val measurementRadius = ceil(max(3.0, star.width.toDouble() * 1.80)).toInt()
            .coerceIn(3, 7)
        val annulusInner = (coreRadius + 1).coerceAtMost(measurementRadius)
        if (
            centerX - measurementRadius < 0 || centerY - measurementRadius < 0 ||
            centerX + measurementRadius >= width || centerY + measurementRadius >= height
        ) return@mapNotNull null
        val measurement = mutableListOf<Int>()
        val core = mutableListOf<Int>()
        val annulus = mutableListOf<Int>()
        for (dy in -measurementRadius..measurementRadius) {
            for (dx in -measurementRadius..measurementRadius) {
                val radiusSquared = dx * dx + dy * dy
                if (radiusSquared > measurementRadius * measurementRadius) continue
                val index = (centerY + dy) * width + centerX + dx
                measurement += index
                if (radiusSquared <= coreRadius * coreRadius) core += index
                if (radiusSquared >= annulusInner * annulusInner) annulus += index
            }
        }
        if (core.isEmpty() || annulus.isEmpty()) null else FixedStarSupport(
            baselineX = star.x.toDouble(),
            baselineY = star.y.toDouble(),
            measurementIndices = measurement.toIntArray(),
            coreIndices = core.toIntArray(),
            annulusIndices = annulus.toIntArray()
        )
    }
}

private object FixedStarMeasurer {
    fun measure(
        image: ArgbPixelSource,
        supports: List<FixedStarSupport>
    ): List<FixedStarMeasurement> = supports.map { measureOne(image, it) }

    private fun measureOne(
        image: ArgbPixelSource,
        support: FixedStarSupport
    ): FixedStarMeasurement {
        fun luminance(index: Int): Double {
            val color = image.argbAt(index % image.width, index / image.width)
            val red = linearChannel(color ushr 16 and 0xFF)
            val green = linearChannel(color ushr 8 and 0xFF)
            val blue = linearChannel(color and 0xFF)
            return 0.2126 * red + 0.7152 * green + 0.0722 * blue
        }
        val background = percentile(support.annulusIndices.map(::luminance), 0.50)
        val coreMean = support.coreIndices.sumOf(::luminance) /
            support.coreIndices.size.coerceAtLeast(1)
        var weight = 0.0
        var centroidX = 0.0
        var centroidY = 0.0
        support.measurementIndices.forEach { index ->
            val signal = (luminance(index) - background).coerceAtLeast(0.0)
            val x = index % image.width
            val y = index / image.width
            weight += signal
            centroidX += signal * x
            centroidY += signal * y
        }
        if (weight <= GlobalToneTransform.EPSILON) {
            return FixedStarMeasurement(
                contrast = (coreMean - background).coerceAtLeast(0.0),
                centroidX = support.baselineX,
                centroidY = support.baselineY,
                width = 0.0,
                ellipticity = 0.0
            )
        }
        centroidX /= weight
        centroidY /= weight
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        support.measurementIndices.forEach { index ->
            val signal = (luminance(index) - background).coerceAtLeast(0.0)
            val x = index % image.width
            val y = index / image.width
            val dx = x - centroidX
            val dy = y - centroidY
            xx += signal * dx * dx
            yy += signal * dy * dy
            xy += signal * dx * dy
        }
        xx /= weight
        yy /= weight
        xy /= weight
        val trace = xx + yy
        val root = sqrt(((xx - yy) * (xx - yy) + 4.0 * xy * xy).coerceAtLeast(0.0))
        val major = sqrt(((trace + root) * 0.5).coerceAtLeast(0.0))
        val minor = sqrt(((trace - root) * 0.5).coerceAtLeast(0.0))
        return FixedStarMeasurement(
            contrast = (coreMean - background).coerceAtLeast(0.0),
            centroidX = centroidX,
            centroidY = centroidY,
            width = sqrt(trace.coerceAtLeast(0.0)),
            ellipticity = if (major <= GlobalToneTransform.EPSILON) {
                0.0
            } else {
                (1.0 - minor / major).coerceIn(0.0, 1.0)
            }
        )
    }

    private fun linearChannel(encoded: Int): Double {
        val value = encoded / 255.0
        return if (value <= 0.04045) value / 12.92
        else ((value + 0.055) / 1.055).pow(2.4)
    }
}

internal sealed interface EnhancedAncillaryOutcome {
    data class Saved(
        val result: SavedProcessedImage,
        val candidate: EnhancedGlobalToneCandidate
    ) : EnhancedAncillaryOutcome

    data class Rejected(val candidate: EnhancedGlobalToneCandidate) : EnhancedAncillaryOutcome
    data class Failed(
        val reason: String,
        val candidate: EnhancedGlobalToneCandidate? = null
    ) : EnhancedAncillaryOutcome
}

internal suspend fun publishOptionalEnhanced(
    createCandidate: () -> EnhancedGlobalToneCandidate,
    saveCandidate: suspend (FileBackedImage) -> SavedProcessedImage,
    releaseCandidate: (FileBackedImage) -> Unit
): EnhancedAncillaryOutcome {
    var candidate: EnhancedGlobalToneCandidate? = null
    return try {
        val created = createCandidate().also { candidate = it }
        if (!created.validation.accepted) {
            EnhancedAncillaryOutcome.Rejected(created)
        } else {
            EnhancedAncillaryOutcome.Saved(
                result = saveCandidate(created.generation.image),
                candidate = created
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: OutOfMemoryError) {
        EnhancedAncillaryOutcome.Failed("out_of_memory", candidate)
    } catch (error: Exception) {
        EnhancedAncillaryOutcome.Failed(
            error.message ?: error::class.java.simpleName,
            candidate
        )
    } finally {
        candidate?.generation?.image?.let { image ->
            runCatching { releaseCandidate(image) }
        }
    }
}

internal fun fileBackedPixelHash(image: FileBackedImage): String {
    image.validate()
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    FileInputStream(image.file).use { input ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun normalizedRatio(
    candidate: Double,
    baseline: Double,
    slope: Double,
    allowance: Double
): Double = (candidate / slope) / max(baseline, allowance)

private fun relativeChange(candidate: Double, baseline: Double): Double =
    abs(candidate - baseline) / max(baseline, GlobalToneTransform.EPSILON)

private fun safeRatio(candidate: Double, baseline: Double): Double =
    candidate / max(baseline, GlobalToneTransform.EPSILON)

private fun percentile(values: List<Double>, fraction: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val position = ((sorted.size - 1) * fraction)
        .coerceIn(0.0, (sorted.size - 1).toDouble())
    val lower = position.toInt()
    val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
    val blend = position - lower
    return sorted[lower] * (1.0 - blend) + sorted[upper] * blend
}

private fun Double.finiteFloat(): Float =
    if (isFinite()) coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
    else 0f
