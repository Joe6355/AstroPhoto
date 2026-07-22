package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.example.astrophoto.processing.jpeg.v2.postprocessing.SkyStatistics
import com.example.astrophoto.processing.jpeg.v2.quality.BandingEstimator
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object ReplayIecSrgbTransfer {
    fun decode(encoded: Double): Double {
        val value = encoded.coerceIn(0.0, 1.0)
        return if (value <= 0.04045) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
    }

    fun encode(linear: Double): Double {
        val value = linear.coerceIn(0.0, 1.0)
        return if (value <= 0.0031308) {
            12.92 * value
        } else {
            1.055 * value.pow(1.0 / 2.4) - 0.055
        }
    }

    fun decode8(value: Int): Double = decode(value.coerceIn(0, 255) / 255.0)

    fun encode8(value: Double): Int = (encode(value) * 255.0).roundToInt().coerceIn(0, 255)
}

internal data class ReplayToneAnchors(
    val toeStart: Double,
    val toeEnd: Double
) {
    init {
        require(toeStart in 0.0..1.0)
        require(toeEnd in 0.0..1.0)
        require(toeEnd > toeStart)
    }
}

internal data class ReplayToneCandidate(
    val gain: Double,
    val anchors: ReplayToneAnchors,
    val image: ArgbPixelImage,
    val scaleLimited: BooleanArray,
    val maximumLinearChannel: Double
)

/** Replay-only global transform. Pixel output depends exclusively on encoded input RGB and scalars. */
internal class ReplayGlobalToneMapper(
    private val epsilon: Double = EPSILON
) {
    fun apply(
        baseline: ArgbPixelImage,
        anchors: ReplayToneAnchors,
        gain: Double
    ): ReplayToneCandidate {
        require(gain in 0.0..1.0)
        val output = IntArray(baseline.pixels.size)
        val limited = BooleanArray(baseline.pixels.size)
        var maximumLinearChannel = 0.0
        baseline.pixels.forEachIndexed { index, color ->
            val transformed = transformEncodedColor(color, anchors, gain)
            output[index] = transformed.color
            limited[index] = transformed.scaleLimited
            maximumLinearChannel = max(maximumLinearChannel, transformed.maximumLinearChannel)
        }
        return ReplayToneCandidate(
            gain = gain,
            anchors = anchors,
            image = ArgbPixelImage(baseline.width, baseline.height, output),
            scaleLimited = limited,
            maximumLinearChannel = maximumLinearChannel
        )
    }

    fun transformEncodedColor(
        color: Int,
        anchors: ReplayToneAnchors,
        gain: Double
    ): ReplayToneColorResult {
        val red = ReplayIecSrgbTransfer.decode8(color ushr 16 and 0xFF)
        val green = ReplayIecSrgbTransfer.decode8(color ushr 8 and 0xFF)
        val blue = ReplayIecSrgbTransfer.decode8(color and 0xFF)
        val luminance = linearLuminance(red, green, blue)
        if (luminance <= epsilon) {
            return ReplayToneColorResult(color, false, max(red, max(green, blue)))
        }
        val luminancePrime = toneLuminance(luminance, anchors, gain)
        val requestedScale = luminancePrime / max(luminance, epsilon)
        val maximumInputChannel = max(red, max(green, blue))
        val gamutScale = 1.0 / max(maximumInputChannel, epsilon)
        val scale = min(requestedScale, gamutScale)
        val outRed = red * scale
        val outGreen = green * scale
        val outBlue = blue * scale
        val alpha = color ushr 24 and 0xFF
        val encoded = (alpha shl 24) or
            (ReplayIecSrgbTransfer.encode8(outRed) shl 16) or
            (ReplayIecSrgbTransfer.encode8(outGreen) shl 8) or
            ReplayIecSrgbTransfer.encode8(outBlue)
        return ReplayToneColorResult(
            color = encoded,
            scaleLimited = gamutScale + SCALE_LIMIT_TOLERANCE < requestedScale,
            maximumLinearChannel = max(outRed, max(outGreen, outBlue))
        )
    }

    fun toneLuminance(value: Double, anchors: ReplayToneAnchors, gain: Double): Double {
        val luminance = value.coerceIn(0.0, 1.0)
        if (luminance <= epsilon) return luminance
        val toeWeight = smoothStep(anchors.toeStart, anchors.toeEnd, luminance)
        val lift = gain * luminance * (1.0 - luminance).pow(4.0)
        return (luminance + toeWeight * lift).coerceIn(0.0, 1.0)
    }

    fun slopeAt(value: Double, anchors: ReplayToneAnchors, gain: Double): Double {
        val lower = (value - SLOPE_STEP).coerceAtLeast(0.0)
        val upper = (value + SLOPE_STEP).coerceAtMost(1.0)
        if (upper <= lower) return 1.0
        return (toneLuminance(upper, anchors, gain) - toneLuminance(lower, anchors, gain)) /
            (upper - lower)
    }

    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    companion object {
        const val EPSILON = 1e-6
        private const val SCALE_LIMIT_TOLERANCE = 1e-12
        private const val SLOPE_STEP = 1e-6
    }
}

internal data class ReplayToneColorResult(
    val color: Int,
    val scaleLimited: Boolean,
    val maximumLinearChannel: Double
)

internal data class ReplayFixedStarSupport(
    val id: Int,
    val baselineX: Double,
    val baselineY: Double,
    val measurementIndices: IntArray,
    val coreIndices: IntArray,
    val annulusIndices: IntArray
)

internal data class ReplayFixedStarMetrics(
    val contrast: Double,
    val centroidX: Double,
    val centroidY: Double,
    val width: Double,
    val ellipticity: Double
)

internal object ReplayFixedStarSupportFactory {
    fun create(
        width: Int,
        height: Int,
        stars: List<DetectedStar>
    ): List<ReplayFixedStarSupport> = stars.mapIndexedNotNull { id, star ->
        val centerX = star.x.roundToInt()
        val centerY = star.y.roundToInt()
        val coreRadius = ceil(max(1.0, star.width.toDouble() * 0.60)).toInt().coerceIn(1, 3)
        val measurementRadius = ceil(max(3.0, star.width.toDouble() * 1.80)).toInt().coerceIn(3, 7)
        val annulusInner = (coreRadius + 1).coerceAtMost(measurementRadius)
        if (centerX - measurementRadius < 0 || centerY - measurementRadius < 0 ||
            centerX + measurementRadius >= width || centerY + measurementRadius >= height
        ) return@mapIndexedNotNull null
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
        if (core.isEmpty() || annulus.isEmpty()) null else ReplayFixedStarSupport(
            id,
            star.x.toDouble(),
            star.y.toDouble(),
            measurement.toIntArray(),
            core.toIntArray(),
            annulus.toIntArray()
        )
    }
}

internal object ReplayFixedStarMeasurer {
    fun measure(image: ArgbPixelImage, supports: List<ReplayFixedStarSupport>): List<ReplayFixedStarMetrics> =
        supports.map { support -> measureOne(image, support) }

    private fun measureOne(
        image: ArgbPixelImage,
        support: ReplayFixedStarSupport
    ): ReplayFixedStarMetrics {
        val background = median(support.annulusIndices.map { linearLuminance(image.pixels[it]) })
        val coreMean = support.coreIndices.sumOf { linearLuminance(image.pixels[it]) } /
            support.coreIndices.size.coerceAtLeast(1)
        var weight = 0.0
        var centroidX = 0.0
        var centroidY = 0.0
        support.measurementIndices.forEach { index ->
            val signal = (linearLuminance(image.pixels[index]) - background).coerceAtLeast(0.0)
            val x = index % image.width
            val y = index / image.width
            weight += signal
            centroidX += signal * x
            centroidY += signal * y
        }
        if (weight <= ReplayGlobalToneMapper.EPSILON) {
            return ReplayFixedStarMetrics(
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
            val signal = (linearLuminance(image.pixels[index]) - background).coerceAtLeast(0.0)
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
        return ReplayFixedStarMetrics(
            contrast = (coreMean - background).coerceAtLeast(0.0),
            centroidX = centroidX,
            centroidY = centroidY,
            width = sqrt(trace.coerceAtLeast(0.0)),
            ellipticity = if (major <= ReplayGlobalToneMapper.EPSILON) 0.0
            else (1.0 - minor / major).coerceIn(0.0, 1.0)
        )
    }
}

internal data class ReplayScaleLimitingMetrics(
    val total: Int,
    val totalPercent: Double,
    val sky: Int,
    val skyPercent: Double,
    val highlights: Int,
    val highlightPercent: Double,
    val starWindows: Int,
    val starWindowPercent: Double
)

internal data class ReplayToneQualityMetrics(
    val sky: SkyStatisticsResult,
    val banding: Double,
    val suspiciousPointCount: Int,
    val starContrastMedianRatio: Double,
    val starContrastLowerQuartileRatio: Double,
    val maximumCentroidShift: Double,
    val maximumWidthRelativeChange: Double,
    val maximumEllipticityRelativeChange: Double,
    val curveSlopeAtSkyMedian: Double,
    val normalizedSkyMadRatio: Double,
    val normalizedBandingRatio: Double,
    val normalizedGradientRatio: Double,
    val scaleLimiting: ReplayScaleLimitingMetrics,
    val accepted: Boolean,
    val rejectionReasons: List<String>,
    val warnings: List<String>
)

internal data class ReplayToneDiagnosticResult(
    val candidate: ReplayToneCandidate,
    val metrics: ReplayToneQualityMetrics
)

internal data class ReplayToneDiagnosticBundle(
    val anchors: ReplayToneAnchors,
    val baselinePixelHashBefore: String,
    val baselinePixelHashAfter: String,
    val baselineSky: SkyStatisticsResult,
    val baselineBanding: Double,
    val baselineSuspiciousPointCount: Int,
    val supports: List<ReplayFixedStarSupport>,
    val baselineStarMetrics: List<ReplayFixedStarMetrics>,
    val results: List<ReplayToneDiagnosticResult>
) {
    val baselineUnchanged: Boolean get() = baselinePixelHashBefore == baselinePixelHashAfter

    fun reportText(): String = buildString {
        appendLine("mode=replay_only")
        appendLine("productionOutputChanged=false")
        appendLine("luminanceSpace=linear_rec709")
        appendLine("encoding=IEC_61966_2_1_sRGB")
        appendLine("baselinePixelSha256Before=$baselinePixelHashBefore")
        appendLine("baselinePixelSha256After=$baselinePixelHashAfter")
        appendLine("baselineUnchanged=$baselineUnchanged")
        appendLine("toeStartLinear=${anchors.toeStart}")
        appendLine("toeEndLinear=${anchors.toeEnd}")
        appendLine("fixedStarSupportCount=${supports.size}")
        appendLine("baseline.skyMedianLinear=${baselineSky.luminanceMedian}")
        appendLine("baseline.skyMedianSrgb=${ReplayIecSrgbTransfer.encode(baselineSky.luminanceMedian.toDouble())}")
        appendLine("baseline.skyMadLinear=${baselineSky.luminanceMad}")
        appendLine("baseline.banding=$baselineBanding")
        appendLine("baseline.gradientResidualLinear=${baselineSky.largeScaleGradientStrength}")
        appendLine("baseline.suspiciousPointCount=$baselineSuspiciousPointCount")
        appendLine("baseline.channelClippingRedPercent=${baselineSky.channelClippingPercent.red}")
        appendLine("baseline.channelClippingGreenPercent=${baselineSky.channelClippingPercent.green}")
        appendLine("baseline.channelClippingBluePercent=${baselineSky.channelClippingPercent.blue}")
        results.forEach { result ->
            val prefix = "gain.${gainLabel(result.candidate.gain)}"
            val metrics = result.metrics
            appendLine("$prefix.accepted=${metrics.accepted}")
            appendLine("$prefix.rejectionReasons=${metrics.rejectionReasons.joinToString("|")}")
            appendLine("$prefix.warnings=${metrics.warnings.joinToString("|")}")
            appendLine("$prefix.skyMedianLinear=${metrics.sky.luminanceMedian}")
            appendLine("$prefix.skyMedianSrgb=${ReplayIecSrgbTransfer.encode(metrics.sky.luminanceMedian.toDouble())}")
            appendLine("$prefix.skyMadLinear=${metrics.sky.luminanceMad}")
            appendLine("$prefix.banding=${metrics.banding}")
            appendLine("$prefix.gradientResidualLinear=${metrics.sky.largeScaleGradientStrength}")
            appendLine("$prefix.suspiciousPointCount=${metrics.suspiciousPointCount}")
            appendLine("$prefix.channelClippingRedPercent=${metrics.sky.channelClippingPercent.red}")
            appendLine("$prefix.channelClippingGreenPercent=${metrics.sky.channelClippingPercent.green}")
            appendLine("$prefix.channelClippingBluePercent=${metrics.sky.channelClippingPercent.blue}")
            appendLine("$prefix.starContrastMedianRatio=${metrics.starContrastMedianRatio}")
            appendLine("$prefix.starContrastLowerQuartileRatio=${metrics.starContrastLowerQuartileRatio}")
            appendLine("$prefix.maximumCentroidShift=${metrics.maximumCentroidShift}")
            appendLine("$prefix.maximumWidthRelativeChange=${metrics.maximumWidthRelativeChange}")
            appendLine("$prefix.maximumEllipticityRelativeChange=${metrics.maximumEllipticityRelativeChange}")
            appendLine("$prefix.curveSlopeAtSkyMedian=${metrics.curveSlopeAtSkyMedian}")
            appendLine("$prefix.normalizedSkyMadRatio=${metrics.normalizedSkyMadRatio}")
            appendLine("$prefix.normalizedBandingRatio=${metrics.normalizedBandingRatio}")
            appendLine("$prefix.normalizedGradientRatio=${metrics.normalizedGradientRatio}")
            appendLine("$prefix.scaleLimitedTotal=${metrics.scaleLimiting.total}")
            appendLine("$prefix.scaleLimitedTotalPercent=${metrics.scaleLimiting.totalPercent}")
            appendLine("$prefix.scaleLimitedSky=${metrics.scaleLimiting.sky}")
            appendLine("$prefix.scaleLimitedSkyPercent=${metrics.scaleLimiting.skyPercent}")
            appendLine("$prefix.scaleLimitedHighlights=${metrics.scaleLimiting.highlights}")
            appendLine("$prefix.scaleLimitedHighlightPercent=${metrics.scaleLimiting.highlightPercent}")
            appendLine("$prefix.scaleLimitedStarWindows=${metrics.scaleLimiting.starWindows}")
            appendLine("$prefix.scaleLimitedStarWindowPercent=${metrics.scaleLimiting.starWindowPercent}")
            appendLine("$prefix.maximumLinearChannel=${result.candidate.maximumLinearChannel}")
        }
    }
}

internal class ReplayGlobalToneDiagnosticRunner(
    private val mapper: ReplayGlobalToneMapper = ReplayGlobalToneMapper(),
    private val skyStatistics: SkyStatistics = SkyStatistics(),
    private val bandingEstimator: BandingEstimator = BandingEstimator()
) {
    fun run(
        baseline: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        confirmedStars: List<DetectedStar>,
        gains: List<Double> = listOf(0.25, 0.50, 0.75)
    ): ReplayToneDiagnosticBundle {
        require(gains == listOf(0.25, 0.50, 0.75))
        val baselineHashBefore = decodedPixelHash(baseline)
        val baselineSky = skyStatistics.calculate(baseline, effectiveSkyAlpha, confirmedStars)
        val anchors = ReplayToneAnchors(
            toeStart = baselineSky.lowPercentile.toDouble().coerceIn(0.0, 1.0 - ReplayGlobalToneMapper.EPSILON),
            toeEnd = max(
                baselineSky.luminanceMedian.toDouble(),
                baselineSky.lowPercentile.toDouble() + ReplayGlobalToneMapper.EPSILON
            ).coerceAtMost(1.0)
        )
        val supports = ReplayFixedStarSupportFactory.create(baseline.width, baseline.height, confirmedStars)
        require(supports.isNotEmpty()) { "No fixed confirmed-star supports" }
        val baselineStarMetrics = ReplayFixedStarMeasurer.measure(baseline, supports)
        val baselineBanding = bandingEstimator.estimate(
            baseline,
            effectiveSkyAlpha,
            baselineSky.luminanceMad
        ).combinedScore.toDouble()
        val regions = fixedRegions(baseline, effectiveSkyAlpha, supports)
        val baselineSuspicious = suspiciousPointCount(
            baseline,
            regions.sky,
            regions.starWindows,
            baselineSky.luminanceMedian.toDouble(),
            baselineSky.luminanceMad.toDouble()
        )
        val results = gains.map { gain ->
            val candidate = mapper.apply(baseline, anchors, gain)
            val candidateSky = skyStatistics.calculate(candidate.image, effectiveSkyAlpha, confirmedStars)
            val candidateBanding = bandingEstimator.estimate(
                candidate.image,
                effectiveSkyAlpha,
                candidateSky.luminanceMad
            ).combinedScore.toDouble()
            val candidateStarMetrics = ReplayFixedStarMeasurer.measure(candidate.image, supports)
            val limiting = scaleLimiting(candidate.scaleLimited, regions)
            val candidateSuspicious = suspiciousPointCount(
                candidate.image,
                regions.sky,
                regions.starWindows,
                baselineSky.luminanceMedian.toDouble(),
                baselineSky.luminanceMad.toDouble()
            )
            val metrics = validate(
                baselineSky,
                candidateSky,
                baselineBanding,
                candidateBanding,
                baselineSuspicious,
                candidateSuspicious,
                baselineStarMetrics,
                candidateStarMetrics,
                limiting,
                anchors,
                gain
            )
            ReplayToneDiagnosticResult(candidate, metrics)
        }
        return ReplayToneDiagnosticBundle(
            anchors,
            baselineHashBefore,
            decodedPixelHash(baseline),
            baselineSky,
            baselineBanding,
            baselineSuspicious,
            supports,
            baselineStarMetrics,
            results
        )
    }

    private fun validate(
        baselineSky: SkyStatisticsResult,
        candidateSky: SkyStatisticsResult,
        baselineBanding: Double,
        candidateBanding: Double,
        baselineSuspicious: Int,
        candidateSuspicious: Int,
        baselineStars: List<ReplayFixedStarMetrics>,
        candidateStars: List<ReplayFixedStarMetrics>,
        limiting: ReplayScaleLimitingMetrics,
        anchors: ReplayToneAnchors,
        gain: Double
    ): ReplayToneQualityMetrics {
        val contrastRatios = baselineStars.indices.mapNotNull { index ->
            baselineStars[index].contrast.takeIf { it > MIN_STAR_CONTRAST }?.let {
                candidateStars[index].contrast / it
            }
        }
        val centroidShifts = baselineStars.indices.map { index ->
            hypot(
                candidateStars[index].centroidX - baselineStars[index].centroidX,
                candidateStars[index].centroidY - baselineStars[index].centroidY
            )
        }
        val widthChanges = baselineStars.indices.mapNotNull { index ->
            baselineStars[index].width.takeIf { it > MIN_STAR_WIDTH }?.let {
                abs(candidateStars[index].width / it - 1.0)
            }
        }
        val ellipticityChanges = baselineStars.indices.map { index ->
            abs(candidateStars[index].ellipticity - baselineStars[index].ellipticity) /
                max(baselineStars[index].ellipticity, MIN_ELLIPTICITY_REFERENCE)
        }
        val slope = mapper.slopeAt(baselineSky.luminanceMedian.toDouble(), anchors, gain)
            .coerceAtLeast(ReplayGlobalToneMapper.EPSILON)
        val normalizedMadRatio = normalizedRatio(
            candidateSky.luminanceMad.toDouble(),
            baselineSky.luminanceMad.toDouble(),
            slope,
            LINEAR_METRIC_ALLOWANCE
        )
        val normalizedBandingRatio = normalizedRatio(
            candidateBanding,
            baselineBanding,
            slope,
            BANDING_ALLOWANCE
        )
        val normalizedGradientRatio = normalizedRatio(
            candidateSky.largeScaleGradientStrength.toDouble(),
            baselineSky.largeScaleGradientStrength.toDouble(),
            slope,
            LINEAR_METRIC_ALLOWANCE
        )
        val medianContrastRatio = percentile(contrastRatios, 0.50)
        val lowerQuartileContrastRatio = percentile(contrastRatios, 0.25)
        val maximumCentroidShift = centroidShifts.maxOrNull() ?: 0.0
        val maximumWidthChange = widthChanges.maxOrNull() ?: 0.0
        val maximumEllipticityChange = ellipticityChanges.maxOrNull() ?: 0.0
        val clippingIncrease = maxOf(
            candidateSky.channelClippingPercent.red - baselineSky.channelClippingPercent.red,
            candidateSky.channelClippingPercent.green - baselineSky.channelClippingPercent.green,
            candidateSky.channelClippingPercent.blue - baselineSky.channelClippingPercent.blue
        )
        val hard = buildList {
            if (contrastRatios.isEmpty()) add("no_confirmed_star_contrast_samples")
            if (medianContrastRatio < MIN_CONTRAST_RATIO) add("matched_star_median_contrast_regressed")
            if (lowerQuartileContrastRatio < MIN_CONTRAST_RATIO) add("matched_star_lower_quartile_regressed")
            if (maximumCentroidShift > MAX_CENTROID_SHIFT) add("fixed_support_centroid_changed")
            if (maximumWidthChange > MAX_GEOMETRY_CHANGE) add("fixed_support_width_changed")
            if (maximumEllipticityChange > MAX_GEOMETRY_CHANGE) add("fixed_support_ellipticity_changed")
            if (clippingIncrease > MAX_CLIPPING_INCREASE_PERCENT) add("channel_clipping_increased")
            if (normalizedMadRatio > MAX_NORMALIZED_BACKGROUND_RATIO) add("normalized_sky_mad_increased")
            if (normalizedBandingRatio > MAX_NORMALIZED_BACKGROUND_RATIO) add("normalized_banding_increased")
            if (normalizedGradientRatio > MAX_NORMALIZED_BACKGROUND_RATIO) add("normalized_gradient_increased")
            if (candidateSuspicious > baselineSuspicious) add("new_suspicious_points")
        }
        val warnings = buildList {
            val outsideHighlights = limiting.total - limiting.highlights
            if (outsideHighlights > 0) add("scale_limiting_outside_baseline_highlights")
            if (limiting.sky > 0) add("scale_limiting_inside_sky")
            if (limiting.starWindows > 0) add("scale_limiting_inside_star_windows")
        }
        return ReplayToneQualityMetrics(
            sky = candidateSky,
            banding = candidateBanding,
            suspiciousPointCount = candidateSuspicious,
            starContrastMedianRatio = medianContrastRatio,
            starContrastLowerQuartileRatio = lowerQuartileContrastRatio,
            maximumCentroidShift = maximumCentroidShift,
            maximumWidthRelativeChange = maximumWidthChange,
            maximumEllipticityRelativeChange = maximumEllipticityChange,
            curveSlopeAtSkyMedian = slope,
            normalizedSkyMadRatio = normalizedMadRatio,
            normalizedBandingRatio = normalizedBandingRatio,
            normalizedGradientRatio = normalizedGradientRatio,
            scaleLimiting = limiting,
            accepted = hard.isEmpty(),
            rejectionReasons = hard,
            warnings = warnings
        )
    }

    private fun fixedRegions(
        baseline: ArgbPixelImage,
        alpha: AlphaMask,
        supports: List<ReplayFixedStarSupport>
    ): FixedRegions {
        val sky = BooleanArray(baseline.pixels.size)
        val highlights = BooleanArray(baseline.pixels.size)
        val starWindows = BooleanArray(baseline.pixels.size)
        baseline.pixels.indices.forEach { index ->
            val x = index % baseline.width
            val y = index / baseline.width
            sky[index] = alpha.alphaAt(x, y) >= SKY_ALPHA_THRESHOLD
            val color = baseline.pixels[index]
            highlights[index] = maxOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF) >=
                HIGHLIGHT_ENCODED_THRESHOLD
        }
        supports.forEach { support -> support.measurementIndices.forEach { starWindows[it] = true } }
        return FixedRegions(sky, highlights, starWindows)
    }

    private fun scaleLimiting(limited: BooleanArray, regions: FixedRegions): ReplayScaleLimitingMetrics {
        val total = limited.count { it }
        val sky = limited.indices.count { limited[it] && regions.sky[it] }
        val highlights = limited.indices.count { limited[it] && regions.highlights[it] }
        val stars = limited.indices.count { limited[it] && regions.starWindows[it] }
        return ReplayScaleLimitingMetrics(
            total,
            percent(total, limited.size),
            sky,
            percent(sky, regions.sky.count { it }),
            highlights,
            percent(highlights, regions.highlights.count { it }),
            stars,
            percent(stars, regions.starWindows.count { it })
        )
    }

    private fun suspiciousPointCount(
        image: ArgbPixelImage,
        sky: BooleanArray,
        starWindows: BooleanArray,
        baselineMedian: Double,
        baselineMad: Double
    ): Int {
        val threshold = baselineMedian + max(baselineMad * 8.0, MIN_SUSPICIOUS_DETAIL)
        var count = 0
        for (y in 1 until image.height - 1) for (x in 1 until image.width - 1) {
            val index = y * image.width + x
            if (!sky[index] || starWindows[index]) continue
            val center = linearLuminance(image.pixels[index])
            if (center < threshold) continue
            var localMaximum = true
            var support = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val neighbor = linearLuminance(image.pixels[(y + dy) * image.width + x + dx])
                if (neighbor > center) localMaximum = false
                if (neighbor - baselineMedian >= (center - baselineMedian) * MIN_POINT_SUPPORT_FRACTION) support++
            }
            if (!localMaximum) continue
            val color = image.pixels[index]
            val deltas = doubleArrayOf(
                ReplayIecSrgbTransfer.decode8(color ushr 16 and 0xFF) - baselineMedian,
                ReplayIecSrgbTransfer.decode8(color ushr 8 and 0xFF) - baselineMedian,
                ReplayIecSrgbTransfer.decode8(color and 0xFF) - baselineMedian
            ).sortedDescending()
            val singleChannel = deltas[0] > MIN_SINGLE_CHANNEL_DETAIL &&
                deltas[1] < deltas[0] * MAX_SECOND_CHANNEL_FRACTION
            if (support == 0 || singleChannel) count++
        }
        return count
    }

    private data class FixedRegions(
        val sky: BooleanArray,
        val highlights: BooleanArray,
        val starWindows: BooleanArray
    )

    companion object {
        private const val SKY_ALPHA_THRESHOLD = 0.98f
        private const val HIGHLIGHT_ENCODED_THRESHOLD = 250
        private const val MIN_STAR_CONTRAST = 1e-6
        private const val MIN_STAR_WIDTH = 1e-6
        private const val MIN_ELLIPTICITY_REFERENCE = 0.05
        private const val MIN_CONTRAST_RATIO = 0.999
        private const val MAX_CENTROID_SHIFT = 0.25
        private const val MAX_GEOMETRY_CHANGE = 0.05
        private const val MAX_CLIPPING_INCREASE_PERCENT = 0.1f
        private const val MAX_NORMALIZED_BACKGROUND_RATIO = 1.10
        private const val LINEAR_METRIC_ALLOWANCE = 1.0 / 4095.0
        private const val BANDING_ALLOWANCE = 0.01
        private const val MIN_SUSPICIOUS_DETAIL = 0.008
        private const val MIN_POINT_SUPPORT_FRACTION = 0.22
        private const val MIN_SINGLE_CHANNEL_DETAIL = 0.04
        private const val MAX_SECOND_CHANNEL_FRACTION = 0.30
    }
}

internal fun linearLuminance(color: Int): Double = linearLuminance(
    ReplayIecSrgbTransfer.decode8(color ushr 16 and 0xFF),
    ReplayIecSrgbTransfer.decode8(color ushr 8 and 0xFF),
    ReplayIecSrgbTransfer.decode8(color and 0xFF)
)

private fun linearLuminance(red: Double, green: Double, blue: Double): Double =
    0.2126 * red + 0.7152 * green + 0.0722 * blue

internal fun decodedPixelHash(image: ArgbPixelImage): String {
    val digest = MessageDigest.getInstance("SHA-256")
    image.pixels.forEach { color ->
        digest.update((color ushr 24).toByte())
        digest.update((color ushr 16).toByte())
        digest.update((color ushr 8).toByte())
        digest.update(color.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun gainLabel(gain: Double): String = (gain * 100.0).roundToInt().toString().padStart(3, '0')

private fun normalizedRatio(candidate: Double, baseline: Double, slope: Double, allowance: Double): Double =
    (candidate / slope) / max(baseline, allowance)

private fun percentile(values: List<Double>, percentile: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val position = ((sorted.size - 1) * percentile).coerceIn(0.0, (sorted.size - 1).toDouble())
    val lower = position.toInt()
    val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
    val fraction = position - lower
    return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
}

private fun median(values: List<Double>): Double = percentile(values, 0.5)

private fun percent(count: Int, total: Int): Double = count * 100.0 / total.coerceAtLeast(1)
