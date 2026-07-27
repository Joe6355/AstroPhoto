package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object ReplayToneVisualFinishPolicy {
    const val VERSION = "replay-global-tone-visual-finish/fixed-030-040-050-v1"
    val GAINS = listOf(0.30, 0.40, 0.50)
    const val PROVISIONAL_PREFERENCE_GAIN = 0.40
    const val SKY_ALPHA_THRESHOLD = 0.98f
    const val FOREGROUND_ALPHA_THRESHOLD = 0.001f
    const val HIGHLIGHT_ENCODED_THRESHOLD = 220
    const val MATERIAL_CLIPPING_INCREASE_PERCENT = 1.0
    const val MATERIAL_CLIPPING_PIXEL_COUNT = 64
    const val LOST_STAR_CONTRAST_RATIO = 0.50
    const val WEAKENED_STAR_CONTRAST_RATIO = 0.95
    const val MAX_MEDIAN_GEOMETRY_CHANGE = 0.03
    const val MAX_GEOMETRY_CHANGE = 0.05
    const val STRONG_EDGE_MINIMUM_LINEAR = 0.010
    const val EDGE_RETENTION_FRACTION = 0.50
    const val MIN_STRONG_EDGE_RETENTION = 0.98
    const val MIN_EDGE_SIGN_AGREEMENT = 0.995
    const val MIN_EDGE_COSINE_SIMILARITY = 0.98
    const val STRONG_BANDING_NORMALIZED_RATIO = 3.0
    const val STRONG_BANDING_ABSOLUTE_INCREASE = 0.05
    const val COLOR_RESIDUAL_CODES = 3.0
    const val COLOR_TRAIL_MIN_AREA = 3
    const val COLOR_TRAIL_MIN_MAJOR_AXIS = 5.0
    const val COLOR_TRAIL_MIN_ELONGATION = 2.0
    const val COLOR_PATCH_MIN_AREA = 4
    const val DIFFERENCE_SCALE_LOW = 8
    const val DIFFERENCE_SCALE_HIGH = 32
    const val SKY_CROP_SIZE = 192
    const val DETAIL_CROP_SIZE = 256
    const val STAR_CROP_SIZE = 96
}

internal data class ReplayToneHighlightMetrics(
    val fixedHighlightPixelCount: Int,
    val baselineClippedPixelCount: Int,
    val candidateClippedPixelCount: Int,
    val baselineClippedPercent: Double,
    val candidateClippedPercent: Double,
    val clippingIncreasePercent: Double
)

internal data class ReplayToneForegroundEdgeMetrics(
    val strongEdgeCount: Int,
    val retainedStrongEdgeCount: Int,
    val strongEdgeRetention: Double,
    val edgeSignAgreement: Double,
    val edgeCosineSimilarity: Double,
    val newlyStrongEdgeCount: Int
)

internal data class ReplayToneColorArtifactMetrics(
    val baselinePointCount: Int,
    val candidatePointCount: Int,
    val newlyVisiblePointCount: Int,
    val baselineTrailCount: Int,
    val candidateTrailCount: Int,
    val newlyVisibleTrailCount: Int,
    val baselinePatchCount: Int,
    val candidatePatchCount: Int,
    val newlyVisiblePatchCount: Int
)

internal data class ReplayToneVisualFinishMetrics(
    val gain: Double,
    val confirmedStarContrastMedianRatio: Double,
    val confirmedStarContrastMinimumRatio: Double,
    val confirmedStarLostCount: Int,
    val confirmedStarWeakenedCount: Int,
    val medianStarWidthRelativeChange: Double,
    val maximumStarWidthRelativeChange: Double,
    val medianStarEllipticityRelativeChange: Double,
    val maximumStarEllipticityRelativeChange: Double,
    val baselineVisibleStarCount: Int,
    val visibleStarCount: Int,
    val visibleStarCountDelta: Int,
    val skyMadRatio: Double,
    val bandingRatio: Double,
    val gradientResidualRatio: Double,
    val normalizedSkyMadRatio: Double,
    val normalizedBandingRatio: Double,
    val normalizedGradientRatio: Double,
    val suspiciousPointCount: Int,
    val suspiciousPointCountDelta: Int,
    val highlights: ReplayToneHighlightMetrics,
    val foregroundEdges: ReplayToneForegroundEdgeMetrics,
    val colorArtifacts: ReplayToneColorArtifactMetrics,
    val hardRejected: Boolean,
    val hardRejectionReasons: List<String>,
    val warnings: List<String>
)

internal data class ReplayToneVisualFinishResult(
    val candidate: ReplayToneCandidate,
    val metrics: ReplayToneVisualFinishMetrics
)

internal data class ReplayToneVisualFinishBundle(
    val baseline: ArgbPixelImage,
    val anchors: ReplayToneAnchors,
    val baselinePixelHashBefore: String,
    val baselinePixelHashAfter: String,
    val results: List<ReplayToneVisualFinishResult>,
    val provisionalPreferenceGain: Double?,
    val outputRoot: Path?
) {
    val baselineUnchanged: Boolean get() = baselinePixelHashBefore == baselinePixelHashAfter

    fun reportText(): String = buildString {
        appendLine("mode=replay_only_global_tonal_finish")
        appendLine("version=${ReplayToneVisualFinishPolicy.VERSION}")
        appendLine("productionOutputChanged=false")
        appendLine("input=unchanged_recovered_stars_clean_stack")
        appendLine("manualTrailCleanupCandidateUsed=false")
        appendLine("luminanceSpace=linear_rec709")
        appendLine("encoding=exact_IEC_61966_2_1_sRGB")
        appendLine("transform=Y+gain*smoothstep(toeStart,toeEnd,Y)*Y*(1-Y)^4")
        appendLine("spatiallyUniform=true")
        appendLine("toeStartLinear=${anchors.toeStart}")
        appendLine("toeEndLinear=${anchors.toeEnd}")
        appendLine("sharedAnchors=${results.all { it.candidate.anchors == anchors }}")
        appendLine("baselinePixelSha256Before=$baselinePixelHashBefore")
        appendLine("baselinePixelSha256After=$baselinePixelHashAfter")
        appendLine("baselineUnchanged=$baselineUnchanged")
        appendLine("fullResolutionPanelOrder=baseline|gain030|gain040|gain050")
        appendLine("skyComparisonMeaning=spatial_nearest_neighbor_zoom")
        appendLine("coloredArtifactBackground=fixed_3x3_outer_ring_mean")
        appendLine("coloredArtifactConnectivity=8")
        appendLine("provisionalPreferencePolicy=gain040_when_hard_valid_else_nearest_hard_valid_gain")
        appendLine("provisionalPreferenceGain=${provisionalPreferenceGain ?: "none"}")
        appendLine("backgroundMetricsAreInformationalUnlessStrongBandingThresholdIsMet=true")
        appendLine("visualArcContourPatchReviewRequired=true")
        appendLine("hardGate.medianStarGeometryChange=${ReplayToneVisualFinishPolicy.MAX_MEDIAN_GEOMETRY_CHANGE}")
        appendLine("hardGate.maximumStarGeometryChange=${ReplayToneVisualFinishPolicy.MAX_GEOMETRY_CHANGE}")
        appendLine("hardGate.materialHighlightClippingIncreasePercent=${ReplayToneVisualFinishPolicy.MATERIAL_CLIPPING_INCREASE_PERCENT}")
        appendLine("hardGate.materialHighlightClippingMinimumPixels=${ReplayToneVisualFinishPolicy.MATERIAL_CLIPPING_PIXEL_COUNT}")
        appendLine("hardGate.strongBandingNormalizedRatio=${ReplayToneVisualFinishPolicy.STRONG_BANDING_NORMALIZED_RATIO}")
        results.forEach { result ->
            val prefix = "gain.${gainLabel(result.candidate.gain)}"
            val value = result.metrics
            appendLine("$prefix.hardRejected=${value.hardRejected}")
            appendLine("$prefix.hardRejectionReasons=${value.hardRejectionReasons.joinToString("|")}")
            appendLine("$prefix.warnings=${value.warnings.joinToString("|")}")
            appendLine("$prefix.confirmedStarContrastMedianRatio=${value.confirmedStarContrastMedianRatio}")
            appendLine("$prefix.confirmedStarContrastMinimumRatio=${value.confirmedStarContrastMinimumRatio}")
            appendLine("$prefix.confirmedStarLostCount=${value.confirmedStarLostCount}")
            appendLine("$prefix.confirmedStarWeakenedCount=${value.confirmedStarWeakenedCount}")
            appendLine("$prefix.medianStarWidthRelativeChange=${value.medianStarWidthRelativeChange}")
            appendLine("$prefix.maximumStarWidthRelativeChange=${value.maximumStarWidthRelativeChange}")
            appendLine("$prefix.medianStarEllipticityRelativeChange=${value.medianStarEllipticityRelativeChange}")
            appendLine("$prefix.maximumStarEllipticityRelativeChange=${value.maximumStarEllipticityRelativeChange}")
            appendLine("$prefix.baselineVisibleStarCount=${value.baselineVisibleStarCount}")
            appendLine("$prefix.visibleStarCount=${value.visibleStarCount}")
            appendLine("$prefix.visibleStarCountDelta=${value.visibleStarCountDelta}")
            appendLine("$prefix.skyMadRatio=${value.skyMadRatio}")
            appendLine("$prefix.bandingRatio=${value.bandingRatio}")
            appendLine("$prefix.gradientResidualRatio=${value.gradientResidualRatio}")
            appendLine("$prefix.normalizedSkyMadRatio=${value.normalizedSkyMadRatio}")
            appendLine("$prefix.normalizedBandingRatio=${value.normalizedBandingRatio}")
            appendLine("$prefix.normalizedGradientRatio=${value.normalizedGradientRatio}")
            appendLine("$prefix.suspiciousPointCount=${value.suspiciousPointCount}")
            appendLine("$prefix.suspiciousPointCountDelta=${value.suspiciousPointCountDelta}")
            appendLine("$prefix.highlight.fixedPixelCount=${value.highlights.fixedHighlightPixelCount}")
            appendLine("$prefix.highlight.baselineClippedPixelCount=${value.highlights.baselineClippedPixelCount}")
            appendLine("$prefix.highlight.candidateClippedPixelCount=${value.highlights.candidateClippedPixelCount}")
            appendLine("$prefix.highlight.baselineClippedPercent=${value.highlights.baselineClippedPercent}")
            appendLine("$prefix.highlight.candidateClippedPercent=${value.highlights.candidateClippedPercent}")
            appendLine("$prefix.highlight.clippingIncreasePercent=${value.highlights.clippingIncreasePercent}")
            appendLine("$prefix.foreground.strongEdgeCount=${value.foregroundEdges.strongEdgeCount}")
            appendLine("$prefix.foreground.strongEdgeRetention=${value.foregroundEdges.strongEdgeRetention}")
            appendLine("$prefix.foreground.edgeSignAgreement=${value.foregroundEdges.edgeSignAgreement}")
            appendLine("$prefix.foreground.edgeCosineSimilarity=${value.foregroundEdges.edgeCosineSimilarity}")
            appendLine("$prefix.foreground.newlyStrongEdgeCount=${value.foregroundEdges.newlyStrongEdgeCount}")
            appendLine("$prefix.color.baselinePointCount=${value.colorArtifacts.baselinePointCount}")
            appendLine("$prefix.color.candidatePointCount=${value.colorArtifacts.candidatePointCount}")
            appendLine("$prefix.color.newlyVisiblePointCount=${value.colorArtifacts.newlyVisiblePointCount}")
            appendLine("$prefix.color.baselineTrailCount=${value.colorArtifacts.baselineTrailCount}")
            appendLine("$prefix.color.candidateTrailCount=${value.colorArtifacts.candidateTrailCount}")
            appendLine("$prefix.color.newlyVisibleTrailCount=${value.colorArtifacts.newlyVisibleTrailCount}")
            appendLine("$prefix.color.baselinePatchCount=${value.colorArtifacts.baselinePatchCount}")
            appendLine("$prefix.color.candidatePatchCount=${value.colorArtifacts.candidatePatchCount}")
            appendLine("$prefix.color.newlyVisiblePatchCount=${value.colorArtifacts.newlyVisiblePatchCount}")
        }
    }
}

internal class ReplayGlobalToneVisualFinishRunner(
    private val diagnosticRunner: ReplayGlobalToneDiagnosticRunner = ReplayGlobalToneDiagnosticRunner(),
    private val starDetector: JpegStarDetector = JpegStarDetector()
) {
    fun run(
        baseline: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        confirmedStars: List<DetectedStar>,
        outputRoot: Path? = null
    ): ReplayToneVisualFinishBundle {
        require(confirmedStars.isNotEmpty())
        val baselineSnapshot = baseline.pixels.copyOf()
        val baselineHashBefore = decodedPixelHash(baseline)
        val base = diagnosticRunner.run(
            baseline = baseline,
            effectiveSkyAlpha = effectiveSkyAlpha,
            confirmedStars = confirmedStars,
            gains = ReplayToneVisualFinishPolicy.GAINS
        )
        val regions = fixedRegions(baseline, effectiveSkyAlpha, base.supports)
        val baselineVisibleStarCount = starDetector.detect(
            baseline,
            SkyMask(baseline.width, baseline.height, regions.sky.copyOf())
        ).stars.size
        val baselineArtifacts = ReplayToneColorArtifactDetector.measure(
            baseline,
            regions.sky,
            regions.starWindows
        )
        val baselineHighlightClipped = clippedHighlightCount(baseline, regions.highlights)
        val results = base.results.map { baseResult ->
            val candidate = baseResult.candidate.image
            val candidateStarMetrics = ReplayFixedStarMeasurer.measure(candidate, base.supports)
            val contrastRatios = base.baselineStarMetrics.indices.map { index ->
                safeRatio(
                    candidateStarMetrics[index].contrast,
                    base.baselineStarMetrics[index].contrast
                )
            }
            val widthChanges = base.baselineStarMetrics.indices.map { index ->
                relativeChange(
                    candidateStarMetrics[index].width,
                    base.baselineStarMetrics[index].width
                )
            }
            val ellipticityChanges = base.baselineStarMetrics.indices.map { index ->
                abs(
                    candidateStarMetrics[index].ellipticity -
                        base.baselineStarMetrics[index].ellipticity
                ) / max(base.baselineStarMetrics[index].ellipticity, 0.05)
            }
            val visibleStarCount = starDetector.detect(
                candidate,
                SkyMask(candidate.width, candidate.height, regions.sky.copyOf())
            ).stars.size
            val candidateArtifacts = ReplayToneColorArtifactDetector.measure(
                candidate,
                regions.sky,
                regions.starWindows
            )
            val colorArtifacts = ReplayToneColorArtifactDetector.compare(
                baselineArtifacts,
                candidateArtifacts,
                baseline.width,
                baseline.height
            )
            val highlightMetrics = highlightMetrics(
                candidate,
                regions.highlights,
                baselineHighlightClipped
            )
            val foregroundEdges = foregroundEdgeMetrics(
                baseline,
                candidate,
                regions.foreground
            )
            val lostStars = contrastRatios.count {
                it < ReplayToneVisualFinishPolicy.LOST_STAR_CONTRAST_RATIO
            }
            val weakenedStars = contrastRatios.count {
                it < ReplayToneVisualFinishPolicy.WEAKENED_STAR_CONTRAST_RATIO
            }
            val medianWidthChange = percentile(widthChanges, 0.50)
            val maximumWidthChange = widthChanges.maxOrNull() ?: 0.0
            val medianEllipticityChange = percentile(ellipticityChanges, 0.50)
            val maximumEllipticityChange = ellipticityChanges.maxOrNull() ?: 0.0
            val skyMadRatio = safeRatio(
                baseResult.metrics.sky.luminanceMad.toDouble(),
                base.baselineSky.luminanceMad.toDouble()
            )
            val bandingRatio = safeRatio(
                baseResult.metrics.banding,
                base.baselineBanding
            )
            val gradientRatio = safeRatio(
                baseResult.metrics.sky.largeScaleGradientStrength.toDouble(),
                base.baselineSky.largeScaleGradientStrength.toDouble()
            )
            val hardReasons = buildList {
                if (lostStars > 0) add("confirmed_star_lost")
                if (medianWidthChange > ReplayToneVisualFinishPolicy.MAX_MEDIAN_GEOMETRY_CHANGE) {
                    add("median_star_width_changed_over_3_percent")
                }
                if (maximumWidthChange > ReplayToneVisualFinishPolicy.MAX_GEOMETRY_CHANGE) {
                    add("maximum_star_width_changed_over_5_percent")
                }
                if (medianEllipticityChange > ReplayToneVisualFinishPolicy.MAX_MEDIAN_GEOMETRY_CHANGE) {
                    add("median_star_ellipticity_changed_over_3_percent")
                }
                if (maximumEllipticityChange > ReplayToneVisualFinishPolicy.MAX_GEOMETRY_CHANGE) {
                    add("maximum_star_ellipticity_changed_over_5_percent")
                }
                if (
                    highlightMetrics.clippingIncreasePercent >
                    ReplayToneVisualFinishPolicy.MATERIAL_CLIPPING_INCREASE_PERCENT &&
                    highlightMetrics.candidateClippedPixelCount -
                    highlightMetrics.baselineClippedPixelCount >
                    ReplayToneVisualFinishPolicy.MATERIAL_CLIPPING_PIXEL_COUNT
                ) add("material_window_highlight_clipping")
                if (
                    foregroundEdges.strongEdgeRetention <
                    ReplayToneVisualFinishPolicy.MIN_STRONG_EDGE_RETENTION ||
                    foregroundEdges.edgeSignAgreement <
                    ReplayToneVisualFinishPolicy.MIN_EDGE_SIGN_AGREEMENT ||
                    foregroundEdges.edgeCosineSimilarity <
                    ReplayToneVisualFinishPolicy.MIN_EDGE_COSINE_SIMILARITY
                ) add("foreground_edge_geometry_changed")
                if (
                    baseResult.metrics.normalizedBandingRatio >
                    ReplayToneVisualFinishPolicy.STRONG_BANDING_NORMALIZED_RATIO &&
                    baseResult.metrics.banding - base.baselineBanding >
                    ReplayToneVisualFinishPolicy.STRONG_BANDING_ABSOLUTE_INCREASE
                ) add("strong_banding_appeared")
                if (colorArtifacts.newlyVisibleTrailCount > 0) add("new_colored_trail")
                if (colorArtifacts.newlyVisiblePatchCount > 0) add("new_color_patch")
            }
            val warnings = buildList {
                if (weakenedStars > 0 && lostStars == 0) add("confirmed_star_weakened_but_not_lost")
                if (baseResult.metrics.normalizedSkyMadRatio > 1.0) add("sky_mad_increased_visual_review")
                if (baseResult.metrics.normalizedBandingRatio > 1.0) add("banding_increased_visual_review")
                if (baseResult.metrics.normalizedGradientRatio > 1.0) add("gradient_increased_visual_review")
                if (colorArtifacts.newlyVisiblePointCount > 0) add("new_colored_points_visible")
                if (baseResult.metrics.suspiciousPointCount > base.baselineSuspiciousPointCount) {
                    add("suspicious_point_count_increased")
                }
            }
            ReplayToneVisualFinishResult(
                candidate = baseResult.candidate,
                metrics = ReplayToneVisualFinishMetrics(
                    gain = baseResult.candidate.gain,
                    confirmedStarContrastMedianRatio = percentile(contrastRatios, 0.50),
                    confirmedStarContrastMinimumRatio = contrastRatios.minOrNull() ?: 0.0,
                    confirmedStarLostCount = lostStars,
                    confirmedStarWeakenedCount = weakenedStars,
                    medianStarWidthRelativeChange = medianWidthChange,
                    maximumStarWidthRelativeChange = maximumWidthChange,
                    medianStarEllipticityRelativeChange = medianEllipticityChange,
                    maximumStarEllipticityRelativeChange = maximumEllipticityChange,
                    baselineVisibleStarCount = baselineVisibleStarCount,
                    visibleStarCount = visibleStarCount,
                    visibleStarCountDelta = visibleStarCount - baselineVisibleStarCount,
                    skyMadRatio = skyMadRatio,
                    bandingRatio = bandingRatio,
                    gradientResidualRatio = gradientRatio,
                    normalizedSkyMadRatio = baseResult.metrics.normalizedSkyMadRatio,
                    normalizedBandingRatio = baseResult.metrics.normalizedBandingRatio,
                    normalizedGradientRatio = baseResult.metrics.normalizedGradientRatio,
                    suspiciousPointCount = baseResult.metrics.suspiciousPointCount,
                    suspiciousPointCountDelta =
                        baseResult.metrics.suspiciousPointCount - base.baselineSuspiciousPointCount,
                    highlights = highlightMetrics,
                    foregroundEdges = foregroundEdges,
                    colorArtifacts = colorArtifacts,
                    hardRejected = hardReasons.isNotEmpty(),
                    hardRejectionReasons = hardReasons,
                    warnings = warnings
                )
            )
        }
        check(baselineSnapshot.contentEquals(baseline.pixels)) {
            "Global tonal finish mutated the RecoveredStars baseline"
        }
        val preferred = provisionalPreference(results)
        val bundle = ReplayToneVisualFinishBundle(
            baseline = baseline,
            anchors = base.anchors,
            baselinePixelHashBefore = baselineHashBefore,
            baselinePixelHashAfter = decodedPixelHash(baseline),
            results = results,
            provisionalPreferenceGain = preferred,
            outputRoot = outputRoot
        )
        outputRoot?.let {
            ReplayToneVisualFinishOutputWriter.write(it, bundle, effectiveSkyAlpha, base.supports)
        }
        return bundle
    }

    private fun provisionalPreference(results: List<ReplayToneVisualFinishResult>): Double? {
        val valid = results.filterNot { it.metrics.hardRejected }
        return valid.minByOrNull {
            abs(it.candidate.gain - ReplayToneVisualFinishPolicy.PROVISIONAL_PREFERENCE_GAIN)
        }?.candidate?.gain
    }

    private fun fixedRegions(
        baseline: ArgbPixelImage,
        alpha: AlphaMask,
        supports: List<ReplayFixedStarSupport>
    ): ReplayToneVisualRegions {
        val sky = BooleanArray(baseline.pixels.size)
        val foreground = BooleanArray(baseline.pixels.size)
        val highlights = BooleanArray(baseline.pixels.size)
        val starWindows = BooleanArray(baseline.pixels.size)
        baseline.pixels.indices.forEach { index ->
            val x = index % baseline.width
            val y = index / baseline.width
            val value = alpha.alphaAt(x, y)
            sky[index] = value >= ReplayToneVisualFinishPolicy.SKY_ALPHA_THRESHOLD
            foreground[index] = value <= ReplayToneVisualFinishPolicy.FOREGROUND_ALPHA_THRESHOLD
            highlights[index] = foreground[index] &&
                maximumEncodedChannel(baseline.pixels[index]) >=
                ReplayToneVisualFinishPolicy.HIGHLIGHT_ENCODED_THRESHOLD
        }
        supports.forEach { support ->
            support.measurementIndices.forEach { starWindows[it] = true }
        }
        return ReplayToneVisualRegions(sky, foreground, highlights, starWindows)
    }

    private fun highlightMetrics(
        candidate: ArgbPixelImage,
        fixedHighlights: BooleanArray,
        baselineClipped: Int
    ): ReplayToneHighlightMetrics {
        val total = fixedHighlights.count { it }
        val candidateClipped = clippedHighlightCount(candidate, fixedHighlights)
        val baselinePercent = percent(baselineClipped, total)
        val candidatePercent = percent(candidateClipped, total)
        return ReplayToneHighlightMetrics(
            fixedHighlightPixelCount = total,
            baselineClippedPixelCount = baselineClipped,
            candidateClippedPixelCount = candidateClipped,
            baselineClippedPercent = baselinePercent,
            candidateClippedPercent = candidatePercent,
            clippingIncreasePercent = candidatePercent - baselinePercent
        )
    }

    private fun clippedHighlightCount(image: ArgbPixelImage, fixedHighlights: BooleanArray): Int =
        fixedHighlights.indices.count {
            fixedHighlights[it] && maximumEncodedChannel(image.pixels[it]) >= 255
        }

    private fun foregroundEdgeMetrics(
        baseline: ArgbPixelImage,
        candidate: ArgbPixelImage,
        foreground: BooleanArray
    ): ReplayToneForegroundEdgeMetrics {
        var strongCount = 0
        var retained = 0
        var signAgreed = 0
        var newlyStrong = 0
        var dot = 0.0
        var baselineEnergy = 0.0
        var candidateEnergy = 0.0
        fun sample(first: Int, second: Int) {
            if (!foreground[first] || !foreground[second]) return
            val baselineGradient = linearLuminance(baseline.pixels[second]) -
                linearLuminance(baseline.pixels[first])
            val candidateGradient = linearLuminance(candidate.pixels[second]) -
                linearLuminance(candidate.pixels[first])
            val baselineMagnitude = abs(baselineGradient)
            val candidateMagnitude = abs(candidateGradient)
            if (
                baselineMagnitude < ReplayToneVisualFinishPolicy.STRONG_EDGE_MINIMUM_LINEAR &&
                candidateMagnitude >= ReplayToneVisualFinishPolicy.STRONG_EDGE_MINIMUM_LINEAR
            ) newlyStrong++
            if (baselineMagnitude < ReplayToneVisualFinishPolicy.STRONG_EDGE_MINIMUM_LINEAR) return
            strongCount++
            if (
                candidateMagnitude >=
                baselineMagnitude * ReplayToneVisualFinishPolicy.EDGE_RETENTION_FRACTION
            ) retained++
            if (baselineGradient * candidateGradient > 0.0) signAgreed++
            dot += baselineGradient * candidateGradient
            baselineEnergy += baselineGradient * baselineGradient
            candidateEnergy += candidateGradient * candidateGradient
        }
        for (y in 0 until baseline.height) for (x in 0 until baseline.width) {
            val index = y * baseline.width + x
            if (x + 1 < baseline.width) sample(index, index + 1)
            if (y + 1 < baseline.height) sample(index, index + baseline.width)
        }
        val cosine = if (baselineEnergy <= 1e-18 || candidateEnergy <= 1e-18) 1.0
        else dot / sqrt(baselineEnergy * candidateEnergy)
        return ReplayToneForegroundEdgeMetrics(
            strongEdgeCount = strongCount,
            retainedStrongEdgeCount = retained,
            strongEdgeRetention = retained.toDouble() / strongCount.coerceAtLeast(1),
            edgeSignAgreement = signAgreed.toDouble() / strongCount.coerceAtLeast(1),
            edgeCosineSimilarity = cosine.coerceIn(-1.0, 1.0),
            newlyStrongEdgeCount = newlyStrong
        )
    }

    private data class ReplayToneVisualRegions(
        val sky: BooleanArray,
        val foreground: BooleanArray,
        val highlights: BooleanArray,
        val starWindows: BooleanArray
    )
}

private data class ReplayToneArtifactComponent(
    val pixels: IntArray,
    val kind: ReplayToneArtifactKind
)

private enum class ReplayToneArtifactKind { POINT, TRAIL, PATCH }

private data class ReplayToneArtifactMeasurement(
    val components: List<ReplayToneArtifactComponent>
)

private object ReplayToneColorArtifactDetector {
    fun measure(
        image: ArgbPixelImage,
        fixedSky: BooleanArray,
        fixedStarWindows: BooleanArray
    ): ReplayToneArtifactMeasurement {
        val candidates = BooleanArray(image.pixels.size)
        for (y in 1 until image.height - 1) for (x in 1 until image.width - 1) {
            val index = y * image.width + x
            if (!fixedSky[index] || fixedStarWindows[index]) continue
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            var ringCount = 0
            var eligible = true
            ring@ for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val sampleIndex = (y + dy) * image.width + x + dx
                    if (!fixedSky[sampleIndex] || fixedStarWindows[sampleIndex]) {
                        eligible = false
                        break@ring
                    }
                    val color = image.pixels[sampleIndex]
                    redSum += color ushr 16 and 0xFF
                    greenSum += color ushr 8 and 0xFF
                    blueSum += color and 0xFF
                    ringCount++
                }
            }
            if (!eligible || ringCount != 8) continue
            val color = image.pixels[index]
            val redResidual = max(0.0, (color ushr 16 and 0xFF) - redSum / 8.0)
            val greenResidual = max(0.0, (color ushr 8 and 0xFF) - greenSum / 8.0)
            val blueResidual = max(0.0, (color and 0xFF) - blueSum / 8.0)
            val largest = max(redResidual, max(greenResidual, blueResidual))
            val smallest = min(redResidual, min(greenResidual, blueResidual))
            val secondLargest = redResidual + greenResidual + blueResidual - largest - smallest
            candidates[index] = largest - secondLargest >=
                ReplayToneVisualFinishPolicy.COLOR_RESIDUAL_CODES
        }
        val visited = BooleanArray(candidates.size)
        val components = mutableListOf<ReplayToneArtifactComponent>()
        candidates.indices.forEach { start ->
            if (!candidates[start] || visited[start]) return@forEach
            val pixels = connectedComponent(candidates, image.width, image.height, start, visited)
            val kind = when {
                pixels.size <= 2 -> ReplayToneArtifactKind.POINT
                else -> {
                    val geometry = ReplayDefectMath.pca(
                        pixels.map {
                            ReplayPoint(
                                (it % image.width).toDouble(),
                                (it / image.width).toDouble()
                            )
                        }
                    )
                    if (
                        pixels.size >= ReplayToneVisualFinishPolicy.COLOR_TRAIL_MIN_AREA &&
                        geometry.majorAxisLength >=
                        ReplayToneVisualFinishPolicy.COLOR_TRAIL_MIN_MAJOR_AXIS &&
                        geometry.elongation >= ReplayToneVisualFinishPolicy.COLOR_TRAIL_MIN_ELONGATION
                    ) ReplayToneArtifactKind.TRAIL
                    else if (pixels.size >= ReplayToneVisualFinishPolicy.COLOR_PATCH_MIN_AREA) {
                        ReplayToneArtifactKind.PATCH
                    } else {
                        ReplayToneArtifactKind.POINT
                    }
                }
            }
            components += ReplayToneArtifactComponent(pixels.toIntArray(), kind)
        }
        return ReplayToneArtifactMeasurement(components)
    }

    fun compare(
        baseline: ReplayToneArtifactMeasurement,
        candidate: ReplayToneArtifactMeasurement,
        width: Int,
        height: Int
    ): ReplayToneColorArtifactMetrics {
        fun count(measurement: ReplayToneArtifactMeasurement, kind: ReplayToneArtifactKind) =
            measurement.components.count { it.kind == kind }
        val baselineNeighborhood = BooleanArray(width * height)
        baseline.components.forEach { component ->
            component.pixels.forEach { index ->
                val x = index % width
                val y = index / width
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height) {
                        baselineNeighborhood[ny * width + nx] = true
                    }
                }
            }
        }
        fun newCount(kind: ReplayToneArtifactKind): Int =
            candidate.components.count { component ->
                component.kind == kind && component.pixels.none { baselineNeighborhood[it] }
            }
        return ReplayToneColorArtifactMetrics(
            baselinePointCount = count(baseline, ReplayToneArtifactKind.POINT),
            candidatePointCount = count(candidate, ReplayToneArtifactKind.POINT),
            newlyVisiblePointCount = newCount(ReplayToneArtifactKind.POINT),
            baselineTrailCount = count(baseline, ReplayToneArtifactKind.TRAIL),
            candidateTrailCount = count(candidate, ReplayToneArtifactKind.TRAIL),
            newlyVisibleTrailCount = newCount(ReplayToneArtifactKind.TRAIL),
            baselinePatchCount = count(baseline, ReplayToneArtifactKind.PATCH),
            candidatePatchCount = count(candidate, ReplayToneArtifactKind.PATCH),
            newlyVisiblePatchCount = newCount(ReplayToneArtifactKind.PATCH)
        )
    }

    private fun connectedComponent(
        mask: BooleanArray,
        width: Int,
        height: Int,
        start: Int,
        visited: BooleanArray
    ): List<Int> {
        val queue = ArrayDeque<Int>()
        val output = mutableListOf<Int>()
        queue += start
        visited[start] = true
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            output += index
            val x = index % width
            val y = index / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (mask[next] && !visited[next]) {
                    visited[next] = true
                    queue += next
                }
            }
        }
        return output
    }
}

private object ReplayToneVisualFinishOutputWriter {
    fun write(
        root: Path,
        bundle: ReplayToneVisualFinishBundle,
        effectiveSkyAlpha: AlphaMask,
        supports: List<ReplayFixedStarSupport>
    ) {
        val fullResolution = root.resolve("full-resolution")
        Files.createDirectories(fullResolution)
        ReplayDiagnosticImageIo.writePng(fullResolution.resolve("baseline-recovered-stars.png"), bundle.baseline)
        bundle.results.forEach { result ->
            ReplayDiagnosticImageIo.writePng(
                fullResolution.resolve("gain-${gainLabel(result.candidate.gain)}.png"),
                result.candidate.image
            )
        }
        val images = listOf(bundle.baseline) + bundle.results.map { it.candidate.image }
        ReplayDiagnosticImageIo.writePng(
            root.resolve("four-way-baseline-gain030-gain040-gain050.png"),
            horizontal(images)
        )
        val skyCenter = skyCenter(bundle.baseline, effectiveSkyAlpha)
        listOf(3, 8).forEach { zoom ->
            val panels = images.map {
                scaleNearest(
                    ReplayDiagnosticImageIo.cropAround(
                        it,
                        skyCenter.first,
                        skyCenter.second,
                        ReplayToneVisualFinishPolicy.SKY_CROP_SIZE
                    ),
                    zoom
                )
            }
            ReplayDiagnosticImageIo.writePng(
                root.resolve("sky-comparison-zoom-${zoom}x.png"),
                horizontal(panels)
            )
        }
        val differences = root.resolve("difference-maps")
        bundle.results.forEach { result ->
            val label = gainLabel(result.candidate.gain)
            ReplayDiagnosticImageIo.writeDifference(
                differences.resolve("gain-$label-difference-x${ReplayToneVisualFinishPolicy.DIFFERENCE_SCALE_LOW}.png"),
                bundle.baseline,
                result.candidate.image,
                ReplayToneVisualFinishPolicy.DIFFERENCE_SCALE_LOW
            )
            ReplayDiagnosticImageIo.writeDifference(
                differences.resolve("gain-$label-difference-x${ReplayToneVisualFinishPolicy.DIFFERENCE_SCALE_HIGH}.png"),
                bundle.baseline,
                result.candidate.image,
                ReplayToneVisualFinishPolicy.DIFFERENCE_SCALE_HIGH
            )
        }
        val cropRoot = root.resolve("crops")
        val windowCenter = brightestForegroundPixel(bundle.baseline, effectiveSkyAlpha)
        val buildingCenter = (
            (bundle.baseline.width * 0.20).roundToInt() to
                (bundle.baseline.height * 0.34).roundToInt()
            )
        writeComparisonCrop(
            cropRoot.resolve("bright-window-four-way.png"),
            images,
            windowCenter,
            ReplayToneVisualFinishPolicy.DETAIL_CROP_SIZE
        )
        writeComparisonCrop(
            cropRoot.resolve("building-edge-four-way.png"),
            images,
            buildingCenter,
            ReplayToneVisualFinishPolicy.DETAIL_CROP_SIZE
        )
        supports.forEachIndexed { index, support ->
            writeComparisonCrop(
                cropRoot.resolve("confirmed-stars").resolve("star-${index.toString().padStart(2, '0')}-four-way.png"),
                images,
                support.baselineX.roundToInt() to support.baselineY.roundToInt(),
                ReplayToneVisualFinishPolicy.STAR_CROP_SIZE
            )
        }
        Files.writeString(root.resolve("metrics-report.txt"), bundle.reportText())
    }

    private fun writeComparisonCrop(
        path: Path,
        images: List<ArgbPixelImage>,
        center: Pair<Int, Int>,
        size: Int
    ) {
        val panels = images.map {
            ReplayDiagnosticImageIo.cropAround(it, center.first, center.second, size)
        }
        ReplayDiagnosticImageIo.writePng(path, horizontal(panels))
    }

    private fun horizontal(images: List<ArgbPixelImage>): ArgbPixelImage {
        require(images.isNotEmpty())
        val height = images.first().height
        require(images.all { it.height == height })
        val width = images.sumOf { it.width }
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        for (y in 0 until height) {
            var left = 0
            images.forEach { image ->
                System.arraycopy(image.pixels, y * image.width, pixels, y * width + left, image.width)
                left += image.width
            }
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun scaleNearest(image: ArgbPixelImage, scale: Int): ArgbPixelImage {
        require(scale > 0)
        val width = image.width * scale
        val height = image.height * scale
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = image.pixels[(y / scale) * image.width + x / scale]
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun skyCenter(image: ArgbPixelImage, alpha: AlphaMask): Pair<Int, Int> =
        closestPixel(
            image,
            (image.width * 0.58).roundToInt(),
            (image.height * 0.24).roundToInt()
        ) { x, y -> alpha.alphaAt(x, y) >= ReplayToneVisualFinishPolicy.SKY_ALPHA_THRESHOLD }

    private fun brightestForegroundPixel(image: ArgbPixelImage, alpha: AlphaMask): Pair<Int, Int> {
        var bestIndex = 0
        var bestValue = -1
        image.pixels.indices.forEach { index ->
            val x = index % image.width
            val y = index / image.width
            if (alpha.alphaAt(x, y) > 0.02f) return@forEach
            val value = maximumEncodedChannel(image.pixels[index])
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex % image.width to bestIndex / image.width
    }

    private fun closestPixel(
        image: ArgbPixelImage,
        targetX: Int,
        targetY: Int,
        included: (Int, Int) -> Boolean
    ): Pair<Int, Int> {
        var bestX = targetX.coerceIn(0, image.width - 1)
        var bestY = targetY.coerceIn(0, image.height - 1)
        var bestDistance = Long.MAX_VALUE
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (!included(x, y)) continue
            val dx = (x - targetX).toLong()
            val dy = (y - targetY).toLong()
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestX = x
                bestY = y
            }
        }
        return bestX to bestY
    }
}

private fun maximumEncodedChannel(color: Int): Int = maxOf(
    color ushr 16 and 0xFF,
    color ushr 8 and 0xFF,
    color and 0xFF
)

private fun relativeChange(candidate: Double, baseline: Double): Double =
    if (baseline <= 1e-12) 0.0 else abs(candidate / baseline - 1.0)

private fun safeRatio(candidate: Double, baseline: Double): Double =
    candidate / max(baseline, 1e-12)

private fun percentile(values: List<Double>, fraction: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val position = ((sorted.size - 1) * fraction).coerceIn(0.0, (sorted.size - 1).toDouble())
    val lower = position.toInt()
    val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
    val weight = position - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
}

private fun percent(count: Int, total: Int): Double = count * 100.0 / total.coerceAtLeast(1)
