package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal object AdaptiveAsinhAblationMath {
    fun cleanStackMetrics(baseline: SkyMaskReplayBundle): SkyMaskPostProcessStageMetrics =
        SkyMaskReplayMath.postProcessStageMetrics(
            stages = listOf(SkyMaskPostProcessStage("clean-stack", baseline.cleanComposed)),
            reference = baseline.reference,
            alpha = baseline.effectiveAlpha,
            refined = baseline.refinedMask,
            windows = baseline.windows
        ).single()

    fun strictStarMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<AdaptiveAsinhAblationVariant>
    ): List<AdaptiveAsinhStrictStarMetric> {
        val stages = buildList {
            add(SkyMaskStarStageInput("clean-stack", baseline.cleanStack, baseline.effectiveAlpha))
            variants.filter { it.available }.forEach { variant ->
                add(SkyMaskStarStageInput(variant.id.stableId, variant.composed, variant.compositionAlpha))
            }
        }
        val measured = SkyMaskReplayMath.strictStarMetricsForStages(
            baseline.fixture,
            baseline.cleanStack,
            stages,
            baseline.refinedMask,
            baseline.foregroundProtection
        )
        val cleanByStar = measured.filter { it.stage == "clean-stack" }.associateBy { it.starId }
        return variants.filter { it.available }.flatMap { variant ->
            measured.filter { it.stage == variant.id.stableId }.map { value ->
                val clean = cleanByStar.getValue(value.starId)
                val contrastRetention = safeRatio(value.localContrast, clean.localContrast)
                AdaptiveAsinhStrictStarMetric(
                    variant = variant.id,
                    starId = value.starId,
                    apertureFluxRetention = value.fluxRetentionFromClean,
                    peakRetention = 1.0 - value.peakAttenuationFromClean,
                    centroidShift = value.centroidShiftFromClean,
                    widthRatio = value.widthRatioFromClean,
                    ellipticityChange = abs(value.ellipticity - clean.ellipticity),
                    localContrast = value.localContrast,
                    localContrastRetention = contrastRetention,
                    chromaResidual = value.chromaResidual,
                    centerAlpha = value.centerAlpha,
                    distanceToBoundary = value.distanceToMaskBoundary,
                    establishedGatePassed = contrastRetention >= MIN_CONTRAST_RETENTION &&
                        value.centroidShiftFromClean <= MAX_CENTROID_SHIFT &&
                        value.widthRatioFromClean <= MAX_WIDTH_RATIO
                ).also(::requireFinite)
            }
        }
    }

    fun boundaryMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<AdaptiveAsinhAblationVariant>
    ): List<AdaptiveAsinhBoundaryMetric> = variants.filter { it.available }.flatMap { variant ->
        SkyMaskReplayMath.windowMetricsForVariant(
            windows = baseline.windows,
            reference = baseline.reference,
            cleanComposed = baseline.cleanComposed,
            processedSky = variant.processedSky,
            output = variant.composed,
            refined = baseline.refinedMask,
            protection = baseline.foregroundProtection,
            alpha = variant.compositionAlpha
        ).map { value ->
            AdaptiveAsinhBoundaryMetric(
                variant = variant.id,
                window = value,
                transitionBandVariance = transitionBandVariance(
                    variant.composed,
                    baseline.cleanComposed,
                    baseline.effectiveAlpha,
                    value.centerX,
                    value.centerY,
                    baseline.windows.single { it.id == value.windowId }.size
                )
            ).also { require(it.transitionBandVariance.isFinite()) }
        }
    }

    fun stageMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<AdaptiveAsinhAblationVariant>
    ): List<AdaptiveAsinhStageMetric> = variants.filter { it.available }.flatMap { variant ->
        SkyMaskReplayMath.postProcessStageMetrics(
            variant.stages.map { SkyMaskPostProcessStage(it.id, it.image) },
            baseline.reference,
            baseline.effectiveAlpha,
            baseline.refinedMask,
            baseline.windows
        ).map { AdaptiveAsinhStageMetric(variant.id, it) }
    }

    fun globalMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<AdaptiveAsinhAblationVariant>,
        strictStars: List<AdaptiveAsinhStrictStarMetric>,
        boundaries: List<AdaptiveAsinhBoundaryMetric>,
        stages: List<AdaptiveAsinhStageMetric>,
        cleanStack: SkyMaskPostProcessStageMetrics
    ): List<AdaptiveAsinhGlobalMetrics> {
        val preliminary = variants.filter { it.available }.map { variant ->
            val composedMetric = stages.single {
                it.variant == variant.id && it.metric.stage == "06-composed"
            }.metric
            val variantBoundaries = boundaries.filter { it.variant == variant.id }
            val boundaryNear = variantBoundaries.filter {
                it.window.distanceToBoundary <= 31.0
            }.ifEmpty { variantBoundaries }
            val skyIndices = variant.composed.pixels.indices.filter { index ->
                baseline.effectiveAlpha.alphaAt(
                    index % variant.composed.width,
                    index / variant.composed.width
                ) >= SKY_ALPHA_THRESHOLD
            }
            val luminances = skyIndices.map { luminance(variant.composed.pixels[it]) }
            val foregroundIndices = variant.composed.pixels.indices.filter { index ->
                baseline.foregroundProtection.contains(
                    index % variant.composed.width,
                    index / variant.composed.width
                ) || baseline.effectiveAlpha.alphaAt(
                    index % variant.composed.width,
                    index / variant.composed.width
                ) <= FOREGROUND_ALPHA_THRESHOLD
            }
            val variantStars = strictStars.filter { it.variant == variant.id }
            AdaptiveAsinhGlobalMetrics(
                variant = variant.id,
                skyMad = composedMetric.skyMad,
                bandingProxy = composedMetric.bandingProxy,
                boundaryEdgeExcess = composedMetric.boundaryEdgeExcess,
                meanHaloScore = boundaryNear.map { it.window.haloScore }.averageOrZero(),
                meanLeakageScore = boundaryNear.map { it.window.leakageScore }.averageOrZero(),
                foregroundMeanChange = foregroundIndices.map { index ->
                    colorDifference(variant.composed.pixels[index], baseline.reference.pixels[index])
                }.averageOrZero(),
                luminanceMean = luminances.averageOrZero(),
                luminanceMedian = percentile(luminances, 0.5),
                clippedLowPixels = skyIndices.count { index ->
                    val color = variant.composed.pixels[index]
                    (color ushr 16 and 0xFF) == 0 || (color ushr 8 and 0xFF) == 0 ||
                        (color and 0xFF) == 0
                },
                clippedHighPixels = skyIndices.count { index ->
                    val color = variant.composed.pixels[index]
                    (color ushr 16 and 0xFF) == 255 || (color ushr 8 and 0xFF) == 255 ||
                        (color and 0xFF) == 255
                },
                chromaResidual = skyIndices.map { chroma(variant.composed.pixels[it]) }.averageOrZero(),
                sensorDefectResidual = sensorDefectResidual(
                    baseline.fixture,
                    baseline.cleanComposed,
                    variant.composed
                ),
                processedAccepted = variant.selection.processedAccepted,
                rejectionReasons = variant.selection.processedRejectionReasons,
                selectedCandidate = variant.selection.type.name,
                strictStarGatePassed = variantStars.size == 6 &&
                    variantStars.all { it.establishedGatePassed },
                acceptableProductionCandidate = false
            ).also(::requireFinite)
        }
        val current = preliminary.single { it.variant == AdaptiveAsinhAblationVariantId.CURRENT }
        return preliminary.map { value ->
            val id = value.variant
            val allowedVariant = id != AdaptiveAsinhAblationVariantId.CURRENT && id.rootCauseEligible
            value.copy(
                acceptableProductionCandidate = allowedVariant &&
                    value.processedAccepted &&
                    value.selectedCandidate == "PROCESSED" &&
                    value.skyMad < cleanStack.skyMad - EPSILON &&
                    value.bandingProxy < cleanStack.bandingProxy - EPSILON &&
                    value.bandingProxy < current.bandingProxy &&
                    value.boundaryEdgeExcess <= current.boundaryEdgeExcess + EPSILON &&
                    value.meanHaloScore <= current.meanHaloScore + EPSILON &&
                    value.meanLeakageScore <= current.meanLeakageScore + EPSILON &&
                    value.foregroundMeanChange <= current.foregroundMeanChange + EPSILON &&
                    value.sensorDefectResidual <= current.sensorDefectResidual + EPSILON &&
                    value.strictStarGatePassed
            )
        }
    }

    fun rootCause(
        metrics: List<AdaptiveAsinhGlobalMetrics>,
        variants: List<AdaptiveAsinhAblationVariant>,
        cleanStack: SkyMaskPostProcessStageMetrics
    ): Triple<AdaptiveAsinhRootCause, String, String?> {
        val current = metrics.single { it.variant == AdaptiveAsinhAblationVariantId.CURRENT }
        val isolated = metrics.filter { it.variant != AdaptiveAsinhAblationVariantId.CURRENT }
        val qualityRecovered = isolated.filter {
            it.processedAccepted &&
                it.selectedCandidate == "PROCESSED" &&
                it.rejectionReasons.none(current.rejectionReasons::contains)
        }
        if (qualityRecovered.isEmpty()) {
            return Triple(
                AdaptiveAsinhRootCause.TARGET_MEDIAN_ESCALATION_NOT_CONFIRMED,
                "Every isolated lower-blend variant remains rejected by the unchanged quality policy; " +
                    "CURRENT failures=${current.rejectionReasons.joinToString("|")}",
                null
            )
        }
        val eligible = qualityRecovered.filter(AdaptiveAsinhGlobalMetrics::acceptableProductionCandidate)
        val selected = eligible.maxByOrNull { metric ->
            variants.single { it.id == metric.variant }.stretchDiagnostics.appliedBlend
        }
        val recoveredSummary = qualityRecovered.joinToString(",") { value ->
            "${value.variant.stableId}@${format(value.bandingProxy)}"
        }
        return Triple(
            AdaptiveAsinhRootCause.TARGET_MEDIAN_ESCALATION_CONFIRMED,
            "Changing only appliedBlend selection removes CURRENT quality failures for $recoveredSummary; " +
                "CURRENT banding=${format(current.bandingProxy)}; clean-stack banding=${format(cleanStack.bandingProxy)}; " +
                "best recovered banding=${format(qualityRecovered.minOf { it.bandingProxy })}",
            selected?.let {
                "${it.variant.stableId}: ${it.variant.appliedBlendDescription}"
            }
        )
    }

    private fun transitionBandVariance(
        output: ArgbPixelImage,
        clean: ArgbPixelImage,
        alpha: AlphaMask,
        centerX: Int,
        centerY: Int,
        size: Int
    ): Double {
        val radius = size / 2
        val values = mutableListOf<Double>()
        for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(output.height - 1)) {
            for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(output.width - 1)) {
                val a = alpha.alphaAt(x, y)
                if (a <= TRANSITION_MIN_ALPHA || a >= TRANSITION_MAX_ALPHA) continue
                val index = y * output.width + x
                values += luminance(output.pixels[index]) - luminance(clean.pixels[index])
            }
        }
        return variance(values)
    }

    private fun sensorDefectResidual(
        fixture: Stage6RegressionFixture,
        clean: ArgbPixelImage,
        output: ArgbPixelImage
    ): Double {
        val values = mutableListOf<Double>()
        fixture.strictSensorDefects.forEach { defect ->
            val centerX = defect.x.toInt()
            val centerY = defect.y.toInt()
            for (dy in -DEFECT_RADIUS..DEFECT_RADIUS) for (dx in -DEFECT_RADIUS..DEFECT_RADIUS) {
                if (dx * dx + dy * dy > DEFECT_RADIUS * DEFECT_RADIUS) continue
                val x = centerX + dx
                val y = centerY + dy
                if (x !in 0 until output.width || y !in 0 until output.height) continue
                val index = y * output.width + x
                values += colorDifference(output.pixels[index], clean.pixels[index])
            }
        }
        return values.averageOrZero()
    }

    private fun luminance(color: Int): Double =
        (color ushr 16 and 0xFF) * 0.2126 +
            (color ushr 8 and 0xFF) * 0.7152 +
            (color and 0xFF) * 0.0722

    private fun chroma(color: Int): Double {
        val red = color ushr 16 and 0xFF
        val green = color ushr 8 and 0xFF
        val blue = color and 0xFF
        return (maxOf(red, green, blue) - minOf(red, green, blue)).toDouble()
    }

    private fun colorDifference(first: Int, second: Int): Double = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    ).toDouble()

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val position = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        val amount = position - lower
        return sorted[lower] * (1.0 - amount) + sorted[upper] * amount
    }

    private fun safeRatio(value: Double, baseline: Double): Double =
        if (abs(baseline) <= 1e-12) 1.0 else value / baseline

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun requireFinite(value: AdaptiveAsinhStrictStarMetric) {
        require(listOf(
            value.apertureFluxRetention,
            value.peakRetention,
            value.centroidShift,
            value.widthRatio,
            value.ellipticityChange,
            value.localContrast,
            value.localContrastRetention,
            value.chromaResidual,
            value.centerAlpha,
            value.distanceToBoundary
        ).all(Double::isFinite))
    }

    private fun requireFinite(value: AdaptiveAsinhGlobalMetrics) {
        require(listOf(
            value.skyMad,
            value.bandingProxy,
            value.boundaryEdgeExcess,
            value.meanHaloScore,
            value.meanLeakageScore,
            value.foregroundMeanChange,
            value.luminanceMean,
            value.luminanceMedian,
            value.chromaResidual,
            value.sensorDefectResidual
        ).all(Double::isFinite))
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.9f", value)

    private const val SKY_ALPHA_THRESHOLD = 0.5f
    private const val FOREGROUND_ALPHA_THRESHOLD = 0.01f
    private const val TRANSITION_MIN_ALPHA = 0.01f
    private const val TRANSITION_MAX_ALPHA = 0.99f
    private const val MIN_CONTRAST_RETENTION = 0.95
    private const val MAX_CENTROID_SHIFT = 0.25
    private const val MAX_WIDTH_RATIO = 1.05
    private const val DEFECT_RADIUS = 4
    private const val EPSILON = 1e-9
}
