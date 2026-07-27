package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.example.astrophoto.processing.jpeg.v2.quality.ResultQualityAnalyzer
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Frozen before the Stage 1 real replay. This file is test/replay-only. */
internal object ReplayLocalCleanupThresholds {
    const val VERSION = "replay-local-trail-cleanup/1"
    const val CORE_RADIUS = 1.50
    const val FEATHER_RADIUS = 2.50
    const val BACKGROUND_INNER_RADIUS = 3.50
    const val BACKGROUND_OUTER_RADIUS = 6.50
    const val BACKGROUND_LONGITUDINAL_RADIUS = 2.0
    const val MIN_BACKGROUND_SAMPLES_PER_SIDE = 8
    const val MIN_BACKGROUND_RETAINED_FRACTION = 0.70
    const val OUTLIER_MAD_MULTIPLIER = 3.0
    const val OUTLIER_MIN_CODES = 2
    const val SIDE_LUMINANCE_MAD_MULTIPLIER = 3.0
    const val SIDE_LUMINANCE_MIN_CODES = 3
    const val SIDE_CHROMA_MAX_CODES = 3
    const val STAR_VETO_DILATION_RADIUS = 2
    const val COMPACT_MAX_ELLIPTICITY = 0.45
    const val COMPACT_MIN_WIDTH = 0.60
    const val COMPACT_MAX_WIDTH = 8.0
    const val LUMINANCE_SUPPORT_MIN_CODES = 1
    const val LUMINANCE_SUPPORT_MIN_PIXELS = 3
    const val LUMINANCE_SUPPORT_MIN_MAJOR_AXIS = 3.0
    const val LUMINANCE_SUPPORT_MIN_ELONGATION = 1.50
    const val LUMINANCE_SUPPORT_MAX_ORIENTATION_DIFFERENCE_DEGREES = 30.0
    const val MAX_TRAIL_ENERGY_RATIO = 0.20
    const val ENERGY_FLOOR_PER_PIXEL = 1e-5
    const val MAX_BACKGROUND_WORSENING = 0.05
    const val MIN_STAR_CONTRAST_RETENTION = 0.95
    const val MAX_STAR_GEOMETRY_CHANGE = 0.03
    const val MAX_STAR_CENTROID_SHIFT = 0.25
    const val INNER_EDGE_MIN_RADIUS = 1.50
    const val INNER_EDGE_MAX_RADIUS = 2.00
    const val OUTER_EDGE_MIN_RADIUS = 2.00
    const val OUTER_EDGE_MAX_RADIUS = 2.50
    const val OUTER_REFERENCE_MAX_RADIUS = 3.50
    const val SEAM_NOISE_MULTIPLIER = 3.0
    const val SEAM_MIN_CODE_STEPS = 2.0
    const val SEAM_RELATIVE_LIMIT = 1.50
    const val SMOOTHNESS_MIN_SAMPLE_COUNT = 16
    const val SMOOTHNESS_MIN_NOISE_CODE_STEPS = 2.0
    const val SMOOTHNESS_MIN_RATIO = 0.60
    const val CROP_MARGIN = 20
    const val CONTROL_STAR_CROP_SIZE = 48
}

internal object ReplayManualReviewPolicy {
    const val VERSION = "replay-local-trail-cleanup/manual-review-luminance-080-v1"
    const val DIFFERENCE_SCALE = 32
    const val TRAIL_CROP_MARGIN = 48
    const val CONTROL_STAR_CROP_SIZE = 64
    val REQUESTED_REPAIRS = listOf("manual-02", "manual-03", "manual-05")
    val ENERGY_OVERRIDE_REPAIRS = listOf("manual-03", "manual-05")
    val STAR_VETO_CONTROLS = listOf("manual-01", "manual-04")
    const val OUTSIDE_SAFE_REGION_CONTROL = "manual-06"
    val ENERGY_ONLY_REASONS = setOf(
        "luminance_trail_energy_reduction_below_80_percent",
        "chroma_trail_energy_reduction_below_80_percent"
    )

    fun allowsEnergyOverride(metric: ReplayTrailRepairMetric): Boolean {
        val boundary = metric.boundary ?: return false
        return metric.status == ReplayTrailRepairStatus.REJECTED_TRAIL_ENERGY &&
            metric.reasons.isNotEmpty() &&
            metric.reasons.all { it in ENERGY_ONLY_REASONS } &&
            !boundary.seamDetected &&
            !boundary.haloDetected &&
            !boundary.smoothStripeDetected
    }
}

internal enum class ReplayTrailRepairStrength(
    val fileLabel: String,
    val luminanceSuppression: Double,
    val productionPreferenceEligible: Boolean
) {
    CHROMA_ONLY("chroma-only", 0.0, true),
    LUMINANCE_50("chroma-luma-050", 0.50, true),
    LUMINANCE_80("chroma-luma-080", 0.80, true),
    FULL_RECONSTRUCTION("full-reconstruction", 1.0, false)
}

internal enum class ReplayTrailRepairStatus {
    ACCEPTED,
    REJECTED_BACKGROUND,
    REJECTED_CONFIRMED_STAR,
    REJECTED_COMPACT_SOURCE,
    REJECTED_TRAIL_ENERGY,
    REJECTED_BOUNDARY_SEAM,
    REJECTED_HALO,
    REJECTED_SMOOTH_STRIPE,
    REJECTED_GAMUT,
    SKIPPED_OUTSIDE_SAFE_REGION
}

internal enum class ReplayManualReviewDecision {
    APPLIED_STRICT_STAGE1_RESULT,
    APPLIED_BELOW_STRICT_ENERGY_THRESHOLD,
    UNCHANGED_STAR_VETO,
    UNCHANGED_OUTSIDE_SAFE_REGION,
    UNCHANGED_NOT_REQUESTED,
    UNCHANGED_SAFETY_REJECTION
}

internal data class ReplayBoundaryMetrics(
    val innerLuminanceP95: Double,
    val outerLuminanceP95: Double,
    val innerChromaP95: Double,
    val outerChromaP95: Double,
    val luminanceGradientDeltaP95: Double,
    val chromaGradientDeltaP95: Double,
    val nearbyLuminanceNoise: Double,
    val nearbyChromaNoise: Double,
    val haloOvershoot: Double,
    val baselineCoreTextureMad: Double,
    val candidateCoreTextureMad: Double,
    val nearbyTextureMad: Double,
    val smoothnessMeasurable: Boolean,
    val seamDetected: Boolean,
    val haloDetected: Boolean,
    val smoothStripeDetected: Boolean
)

internal data class ReplayTrailRepairMetric(
    val trailId: String,
    val status: ReplayTrailRepairStatus,
    val applied: Boolean,
    val reasons: List<String>,
    val baselineLuminanceEnergy: Double,
    val proposedLuminanceEnergy: Double,
    val luminanceEnergyRatio: Double,
    val baselineChromaEnergy: Double,
    val proposedChromaEnergy: Double,
    val chromaEnergyRatio: Double,
    val baselineCombinedEnergy: Double,
    val proposedCombinedEnergy: Double,
    val combinedEnergyRatio: Double,
    val luminanceSupportPixels: Int,
    val chromaSupportPixels: Int,
    val proposedChangedPixels: Int,
    val boundary: ReplayBoundaryMetrics?
)

internal data class ReplayLocalCleanupCandidate(
    val strength: ReplayTrailRepairStrength,
    val image: ArgbPixelImage,
    val trailMetrics: List<ReplayTrailRepairMetric>,
    val quality: ResultQualityMetrics,
    val skyMadRatio: Double,
    val bandingRatio: Double,
    val gradientRatio: Double,
    val foregroundMaximumDifference: Int,
    val outsideMaskMaximumDifference: Int,
    val starContrastMinimumRatio: Double,
    val starMaximumCentroidShift: Double,
    val starMaximumWidthChange: Double,
    val starMaximumEllipticityChange: Double,
    val lineArtifactAccepted: Boolean,
    val globalAccepted: Boolean,
    val globalRejectionReasons: List<String>,
    val preferred: Boolean = false
)

internal data class ReplayManualReviewTrailResult(
    val trailId: String,
    val decision: ReplayManualReviewDecision,
    val applied: Boolean,
    val formalStatus: ReplayTrailRepairStatus,
    val formalReasons: List<String>,
    val belowStrictEnergyThreshold: Boolean,
    val luminanceEnergyRatio: Double,
    val chromaEnergyRatio: Double,
    val proposedChangedPixels: Int,
    val appliedChangedPixels: Int,
    val boundary: ReplayBoundaryMetrics?
)

internal data class ReplayManualReviewCandidate(
    val image: ArgbPixelImage,
    val imagePixelHash: String,
    val sourceStrength: ReplayTrailRepairStrength,
    val trailResults: List<ReplayManualReviewTrailResult>,
    val changedPixelCount: Int,
    val outsideMaskMaximumDifference: Int,
    val foregroundMaximumDifference: Int,
    val confirmedStarSupportMaximumDifference: Int,
    val starContrastMinimumRatio: Double,
    val starMaximumCentroidShift: Double,
    val starMaximumWidthChange: Double,
    val starMaximumEllipticityChange: Double,
    val skyMadRatio: Double,
    val bandingRatio: Double,
    val gradientRatio: Double,
    val formalStage1ResultsUnchanged: Boolean,
    val validationPassed: Boolean,
    val validationReasons: List<String>,
    val manifest: String,
    val manifestHash: String
)

internal data class ReplayLocalTrailCleanupBundle(
    val manifest: String,
    val manifestHash: String,
    val baselinePixelHashBefore: String,
    val baselinePixelHashAfter: String,
    val baselineUnchanged: Boolean,
    val safeSkyMask: BooleanArray,
    val safeSkyMaskHash: String,
    val confirmedStarVeto: BooleanArray,
    val compactSourceVeto: BooleanArray,
    val foregroundVeto: BooleanArray,
    val confirmedStarCenters: List<ReplayPoint>,
    val compactSourceCenters: List<ReplayPoint>,
    val trailMaskIndices: Map<String, IntArray>,
    val leftBackgroundSampleIndices: Map<String, IntArray>,
    val rightBackgroundSampleIndices: Map<String, IntArray>,
    val candidates: List<ReplayLocalCleanupCandidate>,
    val preferredStrength: ReplayTrailRepairStrength?,
    val manualReview: ReplayManualReviewCandidate
)

private data class ReplayLinearColor(
    val red: Double,
    val green: Double,
    val blue: Double
) {
    val luminance: Double get() = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    val chromaRed: Double get() = red - luminance
    val chromaGreen: Double get() = green - luminance
    val chromaBlue: Double get() = blue - luminance
}

private data class ReplayPathProjection(
    val distance: Double,
    val signedDistance: Double,
    val arcLength: Double,
    val endpointOvershoot: Double
)

private data class ReplaySideSample(
    val index: Int,
    val arcLength: Double,
    val color: ReplayLinearColor,
    val valid: Boolean
)

private data class ReplayRepairPixel(
    val index: Int,
    val distance: Double,
    val blend: Double,
    val arcLength: Double,
    val background: ReplayLinearColor,
    val suppressLuminance: Boolean = false,
    val repairChroma: Boolean = false
)

private data class ReplayPreparedTrail(
    val annotation: ReplayManualTrailAnnotation,
    val status: ReplayTrailRepairStatus?,
    val reasons: List<String>,
    val maskIndices: IntArray,
    val coreIndices: IntArray,
    val leftSampleIndices: IntArray,
    val rightSampleIndices: IntArray,
    val repairPixels: List<ReplayRepairPixel>
)

private data class ReplayProposedTrailRepair(
    val pixels: Map<Int, Int>,
    val gamutViolation: Boolean,
    val metric: ReplayTrailRepairMetric
)

private data class ReplayEnergy(
    val luminance: Double,
    val chroma: Double
)

private enum class ReplaySignalDomain { LUMINANCE, CHROMA }

internal class ReplayLocalTrailCleanupDiagnosticRunner(
    private val qualityAnalyzer: ResultQualityAnalyzer = ResultQualityAnalyzer(),
    private val lineArtifactDetector: LineArtifactDetector = LineArtifactDetector(),
    private val starDetector: JpegStarDetector = JpegStarDetector()
) {
    fun run(
        baseline: ArgbPixelImage,
        reference: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        annotations: List<ReplayManualTrailAnnotation>,
        confirmedStars: List<DetectedStar>,
        outputRoot: Path
    ): ReplayLocalTrailCleanupBundle {
        require(baseline.width == reference.width && baseline.height == reference.height)
        require(baseline.width == effectiveSkyAlpha.width && baseline.height == effectiveSkyAlpha.height)
        require(annotations.map { it.id }.distinct().size == annotations.size)
        val baselineHashBefore = decodedPixelHash(baseline)
        val baselineSnapshot = baseline.pixels.copyOf()
        val safeSky = ReplayDefectMath.erodedSkyMask(effectiveSkyAlpha)
        val foregroundVeto = BooleanArray(safeSky.size) { !safeSky[it] }
        val fixedSupports = ReplayFixedStarSupportFactory.create(
            baseline.width,
            baseline.height,
            confirmedStars
        )
        val confirmedVeto = dilatedSupportMask(
            baseline.width,
            baseline.height,
            fixedSupports,
            ReplayLocalCleanupThresholds.STAR_VETO_DILATION_RADIUS
        )
        val compactStars = starDetector.detect(
            baseline,
            SkyMask(baseline.width, baseline.height, safeSky.copyOf())
        ).stars.filter {
            it.ellipticity <= ReplayLocalCleanupThresholds.COMPACT_MAX_ELLIPTICITY &&
                it.width >= ReplayLocalCleanupThresholds.COMPACT_MIN_WIDTH &&
                it.width <= ReplayLocalCleanupThresholds.COMPACT_MAX_WIDTH
        }
        val compactSupports = ReplayFixedStarSupportFactory.create(
            baseline.width,
            baseline.height,
            compactStars
        )
        val compactVeto = dilatedSupportMask(
            baseline.width,
            baseline.height,
            compactSupports,
            ReplayLocalCleanupThresholds.STAR_VETO_DILATION_RADIUS
        )
        val geometry = annotations.associateWith { annotation ->
            maskGeometry(annotation, baseline.width, baseline.height)
        }
        val allRepairMasks = BooleanArray(baseline.pixels.size)
        geometry.values.forEach { value -> value.first.forEach { allRepairMasks[it] = true } }
        val prepared = annotations.map { annotation ->
            prepareTrail(
                baseline,
                annotation,
                geometry.getValue(annotation),
                safeSky,
                confirmedVeto,
                compactVeto,
                allRepairMasks
            )
        }
        val baselineQuality = qualityAnalyzer.analyze(baseline, reference, effectiveSkyAlpha)
        val baselineStars = ReplayFixedStarMeasurer.measure(baseline, fixedSupports)
        val generated = ReplayTrailRepairStrength.entries.map { strength ->
            buildCandidate(
                baseline,
                reference,
                effectiveSkyAlpha,
                prepared,
                fixedSupports,
                baselineStars,
                baselineQuality,
                strength
            )
        }
        val preferenceEligible = generated.filter {
            it.strength.productionPreferenceEligible && it.globalAccepted
        }
        val maximumValidatedRepairs = preferenceEligible.maxOfOrNull { candidate ->
            candidate.trailMetrics.count { it.applied }
        } ?: 0
        val preferredStrength = preferenceEligible.firstOrNull { candidate ->
            maximumValidatedRepairs > 0 &&
                candidate.trailMetrics.count { it.applied } == maximumValidatedRepairs
        }?.strength
        val candidates = generated.map { candidate ->
            candidate.copy(preferred = candidate.strength == preferredStrength)
        }
        val formalPixelSnapshots = candidates.associate { candidate ->
            candidate.strength to candidate.image.pixels.copyOf()
        }
        val formalMetricSnapshots = candidates.associate { candidate ->
            candidate.strength to candidate.trailMetrics
        }
        val manualReviewDraft = buildManualReviewCandidate(
            baseline = baseline,
            reference = reference,
            effectiveSkyAlpha = effectiveSkyAlpha,
            trails = prepared,
            fixedSupports = fixedSupports,
            baselineStars = baselineStars,
            baselineQuality = baselineQuality,
            formalLuminance80 = candidates.single {
                it.strength == ReplayTrailRepairStrength.LUMINANCE_80
            }
        )
        val formalResultsUnchanged = candidates.all { candidate ->
            formalPixelSnapshots.getValue(candidate.strength).contentEquals(candidate.image.pixels) &&
                formalMetricSnapshots.getValue(candidate.strength) == candidate.trailMetrics
        }
        val manualReview = manualReviewDraft.copy(
            formalStage1ResultsUnchanged = formalResultsUnchanged
        )
        val manifest = manifest(
            baseline.width,
            baseline.height,
            ReplayDefectMath.hashBits(safeSky),
            annotations
        )
        val bundle = ReplayLocalTrailCleanupBundle(
            manifest = manifest,
            manifestHash = sha256(manifest.toByteArray(StandardCharsets.UTF_8)),
            baselinePixelHashBefore = baselineHashBefore,
            baselinePixelHashAfter = decodedPixelHash(baseline),
            baselineUnchanged = baselineSnapshot.contentEquals(baseline.pixels),
            safeSkyMask = safeSky,
            safeSkyMaskHash = ReplayDefectMath.hashBits(safeSky),
            confirmedStarVeto = confirmedVeto,
            compactSourceVeto = compactVeto,
            foregroundVeto = foregroundVeto,
            confirmedStarCenters = fixedSupports.map { ReplayPoint(it.baselineX, it.baselineY) },
            compactSourceCenters = compactSupports.map { ReplayPoint(it.baselineX, it.baselineY) },
            trailMaskIndices = prepared.associate { it.annotation.id to it.maskIndices.copyOf() },
            leftBackgroundSampleIndices = prepared.associate {
                it.annotation.id to it.leftSampleIndices.copyOf()
            },
            rightBackgroundSampleIndices = prepared.associate {
                it.annotation.id to it.rightSampleIndices.copyOf()
            },
            candidates = candidates,
            preferredStrength = preferredStrength,
            manualReview = manualReview
        )
        check(bundle.baselineUnchanged) { "Stage 1 local cleanup mutated the RecoveredStars baseline" }
        check(bundle.manualReview.formalStage1ResultsUnchanged) {
            "Manual-review generation changed formal Stage 1 candidates"
        }
        writeOutputs(bundle, baseline, annotations, prepared, fixedSupports, outputRoot)
        return bundle
    }

    private fun prepareTrail(
        baseline: ArgbPixelImage,
        annotation: ReplayManualTrailAnnotation,
        geometry: Pair<IntArray, IntArray>,
        safeSky: BooleanArray,
        confirmedVeto: BooleanArray,
        compactVeto: BooleanArray,
        allRepairMasks: BooleanArray
    ): ReplayPreparedTrail {
        val maskIndices = geometry.first
        val coreIndices = geometry.second
        fun skipped(status: ReplayTrailRepairStatus, reason: String) = ReplayPreparedTrail(
            annotation,
            status,
            listOf(reason),
            maskIndices,
            coreIndices,
            IntArray(0),
            IntArray(0),
            emptyList()
        )
        if (maskIndices.any { !safeSky[it] }) {
            return skipped(
                ReplayTrailRepairStatus.SKIPPED_OUTSIDE_SAFE_REGION,
                "repair_mask_or_feather_outside_frozen_safe_sky"
            )
        }
        if (maskIndices.any { confirmedVeto[it] }) {
            return skipped(
                ReplayTrailRepairStatus.REJECTED_CONFIRMED_STAR,
                "repair_mask_overlaps_confirmed_star_support"
            )
        }
        if (maskIndices.any { compactVeto[it] }) {
            return skipped(
                ReplayTrailRepairStatus.REJECTED_COMPACT_SOURCE,
                "repair_mask_overlaps_baseline_compact_source"
            )
        }
        val sideSamples = backgroundSideSamples(
            baseline,
            annotation,
            safeSky,
            confirmedVeto,
            compactVeto,
            allRepairMasks
        )
        val left = sideSamples.first
        val right = sideSamples.second
        val repairs = mutableListOf<ReplayRepairPixel>()
        for (index in maskIndices) {
            val x = index % baseline.width
            val y = index / baseline.width
            val projection = projectToPolyline(ReplayPoint(x.toDouble(), y.toDouble()), annotation.centerline)
            val leftWindowAll = left.filter {
                abs(it.arcLength - projection.arcLength) <=
                    ReplayLocalCleanupThresholds.BACKGROUND_LONGITUDINAL_RADIUS
            }
            val rightWindowAll = right.filter {
                abs(it.arcLength - projection.arcLength) <=
                    ReplayLocalCleanupThresholds.BACKGROUND_LONGITUDINAL_RADIUS
            }
            val leftWindow = leftWindowAll.filter { it.valid }
            val rightWindow = rightWindowAll.filter { it.valid }
            val leftBackground = robustBackground(leftWindow)
            val rightBackground = robustBackground(rightWindow)
            if (leftBackground == null || rightBackground == null ||
                leftWindow.size.toDouble() / leftWindowAll.size.coerceAtLeast(1) <
                ReplayLocalCleanupThresholds.MIN_BACKGROUND_RETAINED_FRACTION ||
                rightWindow.size.toDouble() / rightWindowAll.size.coerceAtLeast(1) <
                ReplayLocalCleanupThresholds.MIN_BACKGROUND_RETAINED_FRACTION ||
                !sideBackgroundsAgree(leftBackground, rightBackground, leftWindow, rightWindow)
            ) {
                return skipped(
                    ReplayTrailRepairStatus.REJECTED_BACKGROUND,
                    "insufficient_or_inconsistent_two_sided_background"
                ).copy(
                    leftSampleIndices = left.filter { it.valid }.map { it.index }.distinct().toIntArray(),
                    rightSampleIndices = right.filter { it.valid }.map { it.index }.distinct().toIntArray()
                )
            }
            val cross = (
                (projection.signedDistance + ReplayLocalCleanupThresholds.BACKGROUND_OUTER_RADIUS) /
                    (2.0 * ReplayLocalCleanupThresholds.BACKGROUND_OUTER_RADIUS)
                ).coerceIn(0.0, 1.0)
            val background = mix(leftBackground, rightBackground, cross)
            repairs += ReplayRepairPixel(
                index = index,
                distance = projection.distance,
                blend = featherWeight(projection.distance),
                arcLength = projection.arcLength,
                background = background
            )
        }
        val luminanceSupport = frozenElongatedSignalSupport(
            baseline,
            annotation,
            repairs,
            coreIndices,
            ReplaySignalDomain.LUMINANCE
        )
        val chromaSupport = frozenElongatedSignalSupport(
            baseline,
            annotation,
            repairs,
            coreIndices,
            ReplaySignalDomain.CHROMA
        )
        return ReplayPreparedTrail(
            annotation = annotation,
            status = null,
            reasons = emptyList(),
            maskIndices = maskIndices,
            coreIndices = coreIndices,
            leftSampleIndices = left.filter { it.valid }.map { it.index }.distinct().sorted().toIntArray(),
            rightSampleIndices = right.filter { it.valid }.map { it.index }.distinct().sorted().toIntArray(),
            repairPixels = repairs.map { repair ->
                repair.copy(
                    suppressLuminance = repair.index in luminanceSupport,
                    repairChroma = repair.index in chromaSupport
                )
            }
        )
    }

    private fun buildCandidate(
        baseline: ArgbPixelImage,
        reference: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        trails: List<ReplayPreparedTrail>,
        fixedSupports: List<ReplayFixedStarSupport>,
        baselineStars: List<ReplayFixedStarMetrics>,
        baselineQuality: ResultQualityMetrics,
        strength: ReplayTrailRepairStrength
    ): ReplayLocalCleanupCandidate {
        val output = baseline.pixels.copyOf()
        val metrics = mutableListOf<ReplayTrailRepairMetric>()
        val allowedRepairMask = BooleanArray(output.size)
        trails.forEach { trail ->
            if (trail.status != null) {
                metrics += ReplayTrailRepairMetric(
                    trail.annotation.id,
                    trail.status,
                    false,
                    trail.reasons,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    1.0,
                    0,
                    0,
                    0,
                    null
                )
                return@forEach
            }
            val proposal = proposeRepair(baseline, trail, strength)
            metrics += proposal.metric
            if (proposal.metric.applied) {
                trail.maskIndices.forEach { allowedRepairMask[it] = true }
                proposal.pixels.forEach { (index, color) ->
                    output[index] = color
                }
            }
        }
        val image = ArgbPixelImage(baseline.width, baseline.height, output)
        val quality = qualityAnalyzer.analyze(image, reference, effectiveSkyAlpha)
        val skyMadRatio = ratio(quality.skyMad.toDouble(), baselineQuality.skyMad.toDouble())
        val bandingRatio = ratio(
            quality.banding.combinedScore.toDouble(),
            baselineQuality.banding.combinedScore.toDouble()
        )
        val gradientRatio = ratio(
            quality.gradientResidual.toDouble(),
            baselineQuality.gradientResidual.toDouble()
        )
        val foregroundMaximum = maximumDifferenceWhere(image, baseline) { index ->
            val x = index % image.width
            val y = index / image.width
            effectiveSkyAlpha.alphaAt(x, y) <= 0.001f
        }
        val outsideMaximum = maximumDifferenceWhere(image, baseline) { index -> !allowedRepairMask[index] }
        val stars = starChanges(
            baselineStars,
            ReplayFixedStarMeasurer.measure(image, fixedSupports)
        )
        val lines = lineArtifactDetector.compare(baseline, image, effectiveSkyAlpha)
        val globalReasons = buildList {
            if (metrics.none { it.applied }) add("no_validated_repairs")
            if (skyMadRatio > 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING) {
                add("sky_mad_worsened_over_5_percent")
            }
            if (bandingRatio > 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING) {
                add("banding_worsened_over_5_percent")
            }
            if (gradientRatio > 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING) {
                add("gradient_worsened_over_5_percent")
            }
            if (foregroundMaximum != 0) add("foreground_changed")
            if (outsideMaximum != 0) add("pixels_changed_outside_applied_masks")
            if (stars.minimumContrastRatio < ReplayLocalCleanupThresholds.MIN_STAR_CONTRAST_RETENTION) {
                add("confirmed_star_contrast_regressed")
            }
            if (stars.maximumCentroidShift > ReplayLocalCleanupThresholds.MAX_STAR_CENTROID_SHIFT) {
                add("confirmed_star_centroid_changed")
            }
            if (stars.maximumWidthChange > ReplayLocalCleanupThresholds.MAX_STAR_GEOMETRY_CHANGE) {
                add("confirmed_star_width_changed")
            }
            if (stars.maximumEllipticityChange > ReplayLocalCleanupThresholds.MAX_STAR_GEOMETRY_CHANGE) {
                add("confirmed_star_ellipticity_changed")
            }
            if (!lines.accepted) addAll(lines.hardFailureReasons)
        }
        return ReplayLocalCleanupCandidate(
            strength = strength,
            image = image,
            trailMetrics = metrics,
            quality = quality,
            skyMadRatio = skyMadRatio,
            bandingRatio = bandingRatio,
            gradientRatio = gradientRatio,
            foregroundMaximumDifference = foregroundMaximum,
            outsideMaskMaximumDifference = outsideMaximum,
            starContrastMinimumRatio = stars.minimumContrastRatio,
            starMaximumCentroidShift = stars.maximumCentroidShift,
            starMaximumWidthChange = stars.maximumWidthChange,
            starMaximumEllipticityChange = stars.maximumEllipticityChange,
            lineArtifactAccepted = lines.accepted,
            globalAccepted = globalReasons.isEmpty(),
            globalRejectionReasons = globalReasons
        )
    }

    private fun buildManualReviewCandidate(
        baseline: ArgbPixelImage,
        reference: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        trails: List<ReplayPreparedTrail>,
        fixedSupports: List<ReplayFixedStarSupport>,
        baselineStars: List<ReplayFixedStarMetrics>,
        baselineQuality: ResultQualityMetrics,
        formalLuminance80: ReplayLocalCleanupCandidate
    ): ReplayManualReviewCandidate {
        val output = baseline.pixels.copyOf()
        val allowedRepairMask = BooleanArray(output.size)
        val formalByTrail = formalLuminance80.trailMetrics.associateBy { it.trailId }
        val results = trails.map { trail ->
            val id = trail.annotation.id
            val formal = formalByTrail.getValue(id)
            val proposal = if (trail.status == null) {
                proposeRepair(baseline, trail, ReplayTrailRepairStrength.LUMINANCE_80).also {
                    check(it.metric == formal) {
                        "Manual-review proposal differs from formal LUMINANCE_80 metric for $id"
                    }
                }
            } else {
                null
            }
            val apply = when (id) {
                "manual-02" ->
                    formal.status == ReplayTrailRepairStatus.ACCEPTED && formal.applied
                in ReplayManualReviewPolicy.ENERGY_OVERRIDE_REPAIRS ->
                    proposal != null && ReplayManualReviewPolicy.allowsEnergyOverride(formal)
                else -> false
            }
            if (apply) {
                trail.maskIndices.forEach { allowedRepairMask[it] = true }
                checkNotNull(proposal).pixels.forEach { (index, color) -> output[index] = color }
            }
            val decision = when {
                apply && formal.applied ->
                    ReplayManualReviewDecision.APPLIED_STRICT_STAGE1_RESULT
                apply ->
                    ReplayManualReviewDecision.APPLIED_BELOW_STRICT_ENERGY_THRESHOLD
                id in ReplayManualReviewPolicy.STAR_VETO_CONTROLS &&
                    formal.status == ReplayTrailRepairStatus.REJECTED_CONFIRMED_STAR ->
                    ReplayManualReviewDecision.UNCHANGED_STAR_VETO
                id == ReplayManualReviewPolicy.OUTSIDE_SAFE_REGION_CONTROL &&
                    formal.status == ReplayTrailRepairStatus.SKIPPED_OUTSIDE_SAFE_REGION ->
                    ReplayManualReviewDecision.UNCHANGED_OUTSIDE_SAFE_REGION
                id in ReplayManualReviewPolicy.REQUESTED_REPAIRS ->
                    ReplayManualReviewDecision.UNCHANGED_SAFETY_REJECTION
                else ->
                    ReplayManualReviewDecision.UNCHANGED_NOT_REQUESTED
            }
            ReplayManualReviewTrailResult(
                trailId = id,
                decision = decision,
                applied = apply,
                formalStatus = formal.status,
                formalReasons = formal.reasons,
                belowStrictEnergyThreshold =
                    decision == ReplayManualReviewDecision.APPLIED_BELOW_STRICT_ENERGY_THRESHOLD,
                luminanceEnergyRatio = formal.luminanceEnergyRatio,
                chromaEnergyRatio = formal.chromaEnergyRatio,
                proposedChangedPixels = formal.proposedChangedPixels,
                appliedChangedPixels = if (apply) formal.proposedChangedPixels else 0,
                boundary = formal.boundary
            )
        }
        val image = ArgbPixelImage(baseline.width, baseline.height, output)
        val quality = qualityAnalyzer.analyze(image, reference, effectiveSkyAlpha)
        val skyMadRatio = ratio(quality.skyMad.toDouble(), baselineQuality.skyMad.toDouble())
        val bandingRatio = ratio(
            quality.banding.combinedScore.toDouble(),
            baselineQuality.banding.combinedScore.toDouble()
        )
        val gradientRatio = ratio(
            quality.gradientResidual.toDouble(),
            baselineQuality.gradientResidual.toDouble()
        )
        val outsideMaximum = maximumDifferenceWhere(image, baseline) { index ->
            !allowedRepairMask[index]
        }
        val foregroundMaximum = maximumDifferenceWhere(image, baseline) { index ->
            val x = index % image.width
            val y = index / image.width
            effectiveSkyAlpha.alphaAt(x, y) <= 0.001f
        }
        val confirmedSupportMask = BooleanArray(image.pixels.size)
        fixedSupports.forEach { support ->
            support.measurementIndices.forEach { confirmedSupportMask[it] = true }
        }
        val confirmedSupportMaximum = maximumDifferenceWhere(image, baseline) { index ->
            confirmedSupportMask[index]
        }
        val stars = starChanges(
            baselineStars,
            ReplayFixedStarMeasurer.measure(image, fixedSupports)
        )
        val changedPixels = image.pixels.indices.count { index ->
            image.pixels[index] != baseline.pixels[index]
        }
        val validationReasons = buildList {
            ReplayManualReviewPolicy.REQUESTED_REPAIRS
                .filter { requested -> trails.any { it.annotation.id == requested } }
                .forEach { requested ->
                    if (results.none { it.trailId == requested && it.applied }) {
                        add("requested_${requested}_not_applied")
                    }
                }
            if (changedPixels == 0) add("no_pixels_changed")
            if (outsideMaximum != 0) add("pixels_changed_outside_manual_review_masks")
            if (foregroundMaximum != 0) add("foreground_changed")
            if (confirmedSupportMaximum != 0) add("confirmed_star_support_changed")
            if (abs(stars.minimumContrastRatio - 1.0) > 1e-12) {
                add("confirmed_star_contrast_changed")
            }
            if (stars.maximumCentroidShift > 1e-12) add("confirmed_star_centroid_changed")
            if (stars.maximumWidthChange > 1e-12) add("confirmed_star_width_changed")
            if (stars.maximumEllipticityChange > 1e-12) {
                add("confirmed_star_ellipticity_changed")
            }
            results.filter { it.applied }.forEach { result ->
                val boundary = result.boundary
                if (boundary == null) {
                    add("${result.trailId}_missing_boundary_metrics")
                } else {
                    if (boundary.seamDetected) add("${result.trailId}_boundary_seam")
                    if (boundary.haloDetected) add("${result.trailId}_halo")
                    if (boundary.smoothStripeDetected) add("${result.trailId}_smooth_stripe")
                }
            }
            if (skyMadRatio > 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING) {
                add("sky_mad_worsened_over_5_percent")
            }
            if (bandingRatio > 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING) {
                add("banding_worsened_over_5_percent")
            }
            if (gradientRatio > 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING) {
                add("gradient_worsened_over_5_percent")
            }
        }
        val manifest = manualReviewManifest()
        return ReplayManualReviewCandidate(
            image = image,
            imagePixelHash = decodedPixelHash(image),
            sourceStrength = ReplayTrailRepairStrength.LUMINANCE_80,
            trailResults = results,
            changedPixelCount = changedPixels,
            outsideMaskMaximumDifference = outsideMaximum,
            foregroundMaximumDifference = foregroundMaximum,
            confirmedStarSupportMaximumDifference = confirmedSupportMaximum,
            starContrastMinimumRatio = stars.minimumContrastRatio,
            starMaximumCentroidShift = stars.maximumCentroidShift,
            starMaximumWidthChange = stars.maximumWidthChange,
            starMaximumEllipticityChange = stars.maximumEllipticityChange,
            skyMadRatio = skyMadRatio,
            bandingRatio = bandingRatio,
            gradientRatio = gradientRatio,
            formalStage1ResultsUnchanged = true,
            validationPassed = validationReasons.isEmpty(),
            validationReasons = validationReasons,
            manifest = manifest,
            manifestHash = sha256(manifest.toByteArray(StandardCharsets.UTF_8))
        )
    }

    private fun proposeRepair(
        baseline: ArgbPixelImage,
        trail: ReplayPreparedTrail,
        strength: ReplayTrailRepairStrength
    ): ReplayProposedTrailRepair {
        val proposed = linkedMapOf<Int, Int>()
        var gamutViolation = false
        trail.repairPixels.forEach { pixel ->
            val sourceArgb = baseline.pixels[pixel.index]
            val source = decode(sourceArgb)
            val sourceY = source.luminance
            val backgroundY = pixel.background.luminance
            val outY = sourceY -
                pixel.blend *
                strength.luminanceSuppression *
                (if (pixel.suppressLuminance) 1.0 else 0.0) *
                (sourceY - backgroundY).coerceAtLeast(0.0)
            val targetRedChroma = pixel.background.chromaRed
            val targetGreenChroma = pixel.background.chromaGreen
            val targetBlueChroma = pixel.background.chromaBlue
            val chromaBlend = pixel.blend * if (pixel.repairChroma) 1.0 else 0.0
            val redChroma = source.chromaRed + chromaBlend * (targetRedChroma - source.chromaRed)
            val greenChroma = source.chromaGreen + chromaBlend * (targetGreenChroma - source.chromaGreen)
            val blueChroma = source.chromaBlue + chromaBlend * (targetBlueChroma - source.chromaBlue)
            val red = outY + redChroma
            val green = outY + greenChroma
            val blue = outY + blueChroma
            if (!red.isFinite() || !green.isFinite() || !blue.isFinite() ||
                red !in 0.0..1.0 || green !in 0.0..1.0 || blue !in 0.0..1.0
            ) {
                gamutViolation = true
            }
            proposed[pixel.index] = encode(
                sourceArgb ushr 24 and 0xFF,
                red.coerceIn(0.0, 1.0),
                green.coerceIn(0.0, 1.0),
                blue.coerceIn(0.0, 1.0)
            )
        }
        val proposedImage = baseline.pixels.copyOf().also { pixels ->
            proposed.forEach { (index, color) -> pixels[index] = color }
        }.let { ArgbPixelImage(baseline.width, baseline.height, it) }
        val luminanceSupportPixels = trail.repairPixels.count { it.suppressLuminance }
        val chromaSupportPixels = trail.repairPixels.count { it.repairChroma }
        val hasLuminanceSupport = luminanceSupportPixels > 0
        val hasChromaSupport = chromaSupportPixels > 0
        val hasFrozenSignalSupport = hasLuminanceSupport || hasChromaSupport
        val baselineEnergy = trailEnergy(baseline, trail)
        val candidateEnergy = trailEnergy(proposedImage, trail)
        val lumaRatio = energyRatio(
            candidateEnergy.luminance,
            baselineEnergy.luminance,
            luminanceSupportPixels
        )
        val chromaRatio = energyRatio(
            candidateEnergy.chroma,
            baselineEnergy.chroma,
            chromaSupportPixels
        )
        val baselineCombined = baselineEnergy.luminance + baselineEnergy.chroma
        val candidateCombined = candidateEnergy.luminance + candidateEnergy.chroma
        val combinedRatio = energyRatio(
            candidateCombined,
            baselineCombined,
            trail.coreIndices.size
        )
        val boundary = boundaryMetrics(baseline, proposedImage, trail)
        val luminanceEnergyFailed =
            hasLuminanceSupport &&
                lumaRatio > ReplayLocalCleanupThresholds.MAX_TRAIL_ENERGY_RATIO
        val chromaEnergyFailed =
            hasChromaSupport &&
                chromaRatio > ReplayLocalCleanupThresholds.MAX_TRAIL_ENERGY_RATIO
        val reasons = buildList {
            if (gamutViolation) add("linear_rgb_out_of_gamut")
            if (!hasFrozenSignalSupport) add("no_baseline_elongated_signal_support")
            if (luminanceEnergyFailed) {
                add("luminance_trail_energy_reduction_below_80_percent")
            }
            if (chromaEnergyFailed) {
                add("chroma_trail_energy_reduction_below_80_percent")
            }
            if (boundary.seamDetected) add("detectable_boundary_seam")
            if (boundary.haloDetected) add("feather_halo_or_overshoot")
            if (boundary.smoothStripeDetected) add("unusually_smooth_repair_stripe")
        }
        val status = when {
            gamutViolation -> ReplayTrailRepairStatus.REJECTED_GAMUT
            !hasFrozenSignalSupport -> ReplayTrailRepairStatus.REJECTED_TRAIL_ENERGY
            luminanceEnergyFailed || chromaEnergyFailed ->
                ReplayTrailRepairStatus.REJECTED_TRAIL_ENERGY
            boundary.seamDetected -> ReplayTrailRepairStatus.REJECTED_BOUNDARY_SEAM
            boundary.haloDetected -> ReplayTrailRepairStatus.REJECTED_HALO
            boundary.smoothStripeDetected -> ReplayTrailRepairStatus.REJECTED_SMOOTH_STRIPE
            else -> ReplayTrailRepairStatus.ACCEPTED
        }
        return ReplayProposedTrailRepair(
            pixels = proposed,
            gamutViolation = gamutViolation,
            metric = ReplayTrailRepairMetric(
                trailId = trail.annotation.id,
                status = status,
                applied = status == ReplayTrailRepairStatus.ACCEPTED,
                reasons = reasons,
                baselineLuminanceEnergy = baselineEnergy.luminance,
                proposedLuminanceEnergy = candidateEnergy.luminance,
                luminanceEnergyRatio = lumaRatio,
                baselineChromaEnergy = baselineEnergy.chroma,
                proposedChromaEnergy = candidateEnergy.chroma,
                chromaEnergyRatio = chromaRatio,
                baselineCombinedEnergy = baselineCombined,
                proposedCombinedEnergy = candidateCombined,
                combinedEnergyRatio = combinedRatio,
                luminanceSupportPixels = luminanceSupportPixels,
                chromaSupportPixels = chromaSupportPixels,
                proposedChangedPixels = proposed.count { (index, color) ->
                    color != baseline.pixels[index]
                },
                boundary = boundary
            )
        )
    }

    private fun trailEnergy(image: ArgbPixelImage, trail: ReplayPreparedTrail): ReplayEnergy {
        val repairByIndex = trail.repairPixels.associateBy { it.index }
        var luminance = 0.0
        var chroma = 0.0
        trail.coreIndices.forEach { index ->
            val background = repairByIndex.getValue(index).background
            val color = decode(image.pixels[index])
            val repair = repairByIndex.getValue(index)
            if (repair.suppressLuminance) {
                luminance += (color.luminance - background.luminance).coerceAtLeast(0.0)
            }
            if (repair.repairChroma) {
                val dr = color.chromaRed - background.chromaRed
                val dg = color.chromaGreen - background.chromaGreen
                val db = color.chromaBlue - background.chromaBlue
                chroma += sqrt((dr * dr + dg * dg + db * db).coerceAtLeast(0.0))
            }
        }
        return ReplayEnergy(luminance, chroma)
    }

    private fun boundaryMetrics(
        baseline: ArgbPixelImage,
        candidate: ArgbPixelImage,
        trail: ReplayPreparedTrail
    ): ReplayBoundaryMetrics {
        val distanceCache = HashMap<Int, Double>()
        fun distance(index: Int): Double = distanceCache.getOrPut(index) {
            projectToPolyline(
                ReplayPoint((index % baseline.width).toDouble(), (index / baseline.width).toDouble()),
                trail.annotation.centerline
            ).distance
        }
        val innerBaselineLuma = mutableListOf<Double>()
        val innerCandidateLuma = mutableListOf<Double>()
        val innerBaselineChroma = mutableListOf<Double>()
        val innerCandidateChroma = mutableListOf<Double>()
        val outerBaselineLuma = mutableListOf<Double>()
        val outerCandidateLuma = mutableListOf<Double>()
        val outerBaselineChroma = mutableListOf<Double>()
        val outerCandidateChroma = mutableListOf<Double>()
        val box = boundingBox(
            trail.annotation,
            baseline.width,
            baseline.height,
            ReplayLocalCleanupThresholds.OUTER_REFERENCE_MAX_RADIUS + 1.0
        )
        for (y in box.top..box.bottom) for (x in box.left..box.right) {
            val firstIndex = y * baseline.width + x
            for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, -1 to 1)) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until baseline.width || ny !in 0 until baseline.height) continue
                val secondIndex = ny * baseline.width + nx
                val firstDistance = distance(firstIndex)
                val secondDistance = distance(secondIndex)
                val step = hypot(dx.toDouble(), dy.toDouble())
                val innerEdge = crossesBand(
                    firstDistance,
                    secondDistance,
                    ReplayLocalCleanupThresholds.INNER_EDGE_MIN_RADIUS,
                    ReplayLocalCleanupThresholds.INNER_EDGE_MAX_RADIUS
                )
                val outerEdge = crossesOuterBoundary(firstDistance, secondDistance)
                if (!innerEdge && !outerEdge) continue
                val baseGradient = componentGradient(
                    decode(baseline.pixels[firstIndex]),
                    decode(baseline.pixels[secondIndex]),
                    step
                )
                val candidateGradient = componentGradient(
                    decode(candidate.pixels[firstIndex]),
                    decode(candidate.pixels[secondIndex]),
                    step
                )
                if (innerEdge) {
                    innerBaselineLuma += baseGradient.first
                    innerCandidateLuma += candidateGradient.first
                    innerBaselineChroma += baseGradient.second
                    innerCandidateChroma += candidateGradient.second
                }
                if (outerEdge) {
                    outerBaselineLuma += baseGradient.first
                    outerCandidateLuma += candidateGradient.first
                    outerBaselineChroma += baseGradient.second
                    outerCandidateChroma += candidateGradient.second
                }
            }
        }
        val sampleIndices = (trail.leftSampleIndices + trail.rightSampleIndices).distinct()
        val nearbyLumaResiduals = sampleIndices.map { localHighFrequency(baseline, it).first }
        val nearbyChromaResiduals = sampleIndices.map { localHighFrequency(baseline, it).second }
        val nearbyLumaNoise = robustSigma(nearbyLumaResiduals)
        val nearbyChromaNoise = robustSigma(nearbyChromaResiduals)
        val lumaCodeStep = quantizationFloor(baseline, sampleIndices, luminanceOnly = true)
        val chromaCodeStep = quantizationFloor(baseline, sampleIndices, luminanceOnly = false)
        val innerLumaP95 = percentile(innerCandidateLuma, 0.95)
        val outerLumaP95 = percentile(outerCandidateLuma, 0.95)
        val innerChromaP95 = percentile(innerCandidateChroma, 0.95)
        val outerChromaP95 = percentile(outerCandidateChroma, 0.95)
        val lumaDeltaP95 = max(
            pairedDeltaP95(innerCandidateLuma, innerBaselineLuma),
            pairedDeltaP95(outerCandidateLuma, outerBaselineLuma)
        )
        val chromaDeltaP95 = max(
            pairedDeltaP95(innerCandidateChroma, innerBaselineChroma),
            pairedDeltaP95(outerCandidateChroma, outerBaselineChroma)
        )
        val baselineLumaReference = max(
            percentile(innerBaselineLuma + outerBaselineLuma, 0.95),
            nearbyLumaNoise
        )
        val baselineChromaReference = max(
            percentile(innerBaselineChroma + outerBaselineChroma, 0.95),
            nearbyChromaNoise
        )
        val lumaDetectable = lumaDeltaP95 >
            max(
                ReplayLocalCleanupThresholds.SEAM_MIN_CODE_STEPS * lumaCodeStep,
                ReplayLocalCleanupThresholds.SEAM_NOISE_MULTIPLIER * nearbyLumaNoise
            )
        val chromaDetectable = chromaDeltaP95 >
            max(
                ReplayLocalCleanupThresholds.SEAM_MIN_CODE_STEPS * chromaCodeStep,
                ReplayLocalCleanupThresholds.SEAM_NOISE_MULTIPLIER * nearbyChromaNoise
            )
        val seam = (
            lumaDetectable &&
                max(innerLumaP95, outerLumaP95) >
                ReplayLocalCleanupThresholds.SEAM_RELATIVE_LIMIT *
                max(baselineLumaReference, lumaCodeStep)
            ) || (
            chromaDetectable &&
                max(innerChromaP95, outerChromaP95) >
                ReplayLocalCleanupThresholds.SEAM_RELATIVE_LIMIT *
                max(baselineChromaReference, chromaCodeStep)
            )
        val haloOvershoot = haloOvershoot(baseline, candidate, trail)
        val coreBaselineTexture = trail.coreIndices.map { localHighFrequency(baseline, it).first }
        val coreCandidateTexture = trail.coreIndices.map { localHighFrequency(candidate, it).first }
        val baselineCoreMad = robustSigma(coreBaselineTexture)
        val candidateCoreMad = robustSigma(coreCandidateTexture)
        val nearbyTextureMad = nearbyLumaNoise
        val baselineCoreEncodedTexture = trail.coreIndices.map {
            encodedTextureResidual(baseline, it)
        }
        val candidateCoreEncodedTexture = trail.coreIndices.map {
            encodedTextureResidual(candidate, it)
        }
        val nearbyEncodedTexture = sampleIndices.map {
            encodedTextureResidual(baseline, it)
        }
        val baselineEncodedP90 = percentile(baselineCoreEncodedTexture, 0.90)
        val candidateEncodedP90 = percentile(candidateCoreEncodedTexture, 0.90)
        val nearbyEncodedP90 = percentile(nearbyEncodedTexture, 0.90)
        val enoughTextureSamples =
            coreCandidateTexture.size >= ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_SAMPLE_COUNT &&
                nearbyLumaResiduals.size >= ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_SAMPLE_COUNT
        val quantizedTextureMeasurable = enoughTextureSamples &&
            nearbyEncodedP90 >= 1.0 &&
            baselineEncodedP90 >= 1.0
        val smoothnessMeasurable =
            enoughTextureSamples && (
                nearbyTextureMad >
                    ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_NOISE_CODE_STEPS * lumaCodeStep ||
                    quantizedTextureMeasurable
                )
        val smoothStripe = smoothnessMeasurable && (
            candidateCoreMad < ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_RATIO * nearbyTextureMad &&
                candidateCoreMad < ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_RATIO * baselineCoreMad ||
                quantizedTextureMeasurable &&
                candidateEncodedP90 <
                ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_RATIO * nearbyEncodedP90 &&
                candidateEncodedP90 <
                ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_RATIO * baselineEncodedP90
            )
        return ReplayBoundaryMetrics(
            innerLuminanceP95 = innerLumaP95,
            outerLuminanceP95 = outerLumaP95,
            innerChromaP95 = innerChromaP95,
            outerChromaP95 = outerChromaP95,
            luminanceGradientDeltaP95 = lumaDeltaP95,
            chromaGradientDeltaP95 = chromaDeltaP95,
            nearbyLuminanceNoise = nearbyLumaNoise,
            nearbyChromaNoise = nearbyChromaNoise,
            haloOvershoot = haloOvershoot,
            baselineCoreTextureMad = baselineCoreMad,
            candidateCoreTextureMad = candidateCoreMad,
            nearbyTextureMad = nearbyTextureMad,
            smoothnessMeasurable = smoothnessMeasurable,
            seamDetected = seam,
            haloDetected = haloOvershoot > max(2.0 * max(lumaCodeStep, chromaCodeStep), 3.0 * max(nearbyLumaNoise, nearbyChromaNoise)),
            smoothStripeDetected = smoothStripe
        )
    }

    private fun haloOvershoot(
        baseline: ArgbPixelImage,
        candidate: ArgbPixelImage,
        trail: ReplayPreparedTrail
    ): Double {
        var maximum = 0.0
        trail.repairPixels.forEach { pixel ->
            if (pixel.blend <= 0.0) return@forEach
            val source = decode(baseline.pixels[pixel.index])
            val result = decode(candidate.pixels[pixel.index])
            val background = pixel.background
            fun overshoot(value: Double, first: Double, second: Double): Double =
                max(
                    (min(first, second) - value).coerceAtLeast(0.0),
                    (value - max(first, second)).coerceAtLeast(0.0)
                )
            maximum = max(maximum, overshoot(result.luminance, source.luminance, background.luminance))
            maximum = max(maximum, overshoot(result.chromaRed, source.chromaRed, background.chromaRed))
            maximum = max(maximum, overshoot(result.chromaGreen, source.chromaGreen, background.chromaGreen))
            maximum = max(maximum, overshoot(result.chromaBlue, source.chromaBlue, background.chromaBlue))
        }
        return maximum
    }

    private fun backgroundSideSamples(
        baseline: ArgbPixelImage,
        annotation: ReplayManualTrailAnnotation,
        safeSky: BooleanArray,
        confirmedVeto: BooleanArray,
        compactVeto: BooleanArray,
        allRepairMasks: BooleanArray
    ): Pair<List<ReplaySideSample>, List<ReplaySideSample>> {
        val box = boundingBox(
            annotation,
            baseline.width,
            baseline.height,
            ReplayLocalCleanupThresholds.BACKGROUND_OUTER_RADIUS + 1.0
        )
        val left = mutableListOf<ReplaySideSample>()
        val right = mutableListOf<ReplaySideSample>()
        for (y in box.top..box.bottom) for (x in box.left..box.right) {
            val index = y * baseline.width + x
            val projection = projectToPolyline(ReplayPoint(x.toDouble(), y.toDouble()), annotation.centerline)
            if (projection.distance !in
                ReplayLocalCleanupThresholds.BACKGROUND_INNER_RADIUS..
                    ReplayLocalCleanupThresholds.BACKGROUND_OUTER_RADIUS
            ) continue
            if (projection.endpointOvershoot >
                ReplayLocalCleanupThresholds.BACKGROUND_LONGITUDINAL_RADIUS ||
                abs(projection.signedDistance) < 1e-6
            ) continue
            val valid = safeSky[index] &&
                !confirmedVeto[index] &&
                !compactVeto[index] &&
                !allRepairMasks[index]
            val sample = ReplaySideSample(
                index,
                projection.arcLength,
                decode(baseline.pixels[index]),
                valid
            )
            if (projection.signedDistance < 0.0) left += sample else right += sample
        }
        return left to right
    }

    private fun robustBackground(samples: List<ReplaySideSample>): ReplayLinearColor? {
        val unique = samples.distinctBy { it.index }
        if (unique.size < ReplayLocalCleanupThresholds.MIN_BACKGROUND_SAMPLES_PER_SIDE) return null
        val first = medianColor(unique.map { it.color })
        val deviations = unique.map { sample ->
            maxOf(
                abs(sample.color.red - first.red),
                abs(sample.color.green - first.green),
                abs(sample.color.blue - first.blue)
            )
        }
        val deviationMad = median(deviations.map { abs(it - median(deviations)) })
        val codeFloor = localCodeStep(first, ReplayLocalCleanupThresholds.OUTLIER_MIN_CODES)
        val threshold = max(
            ReplayLocalCleanupThresholds.OUTLIER_MAD_MULTIPLIER * deviationMad,
            codeFloor
        )
        val retained = unique.filter { sample ->
            maxOf(
                abs(sample.color.red - first.red),
                abs(sample.color.green - first.green),
                abs(sample.color.blue - first.blue)
            ) <= threshold
        }
        if (retained.size < ReplayLocalCleanupThresholds.MIN_BACKGROUND_SAMPLES_PER_SIDE ||
            retained.size.toDouble() / unique.size <
            ReplayLocalCleanupThresholds.MIN_BACKGROUND_RETAINED_FRACTION
        ) return null
        return medianColor(retained.map { it.color })
    }

    private fun sideBackgroundsAgree(
        left: ReplayLinearColor,
        right: ReplayLinearColor,
        leftSamples: List<ReplaySideSample>,
        rightSamples: List<ReplaySideSample>
    ): Boolean {
        val lumaMad = max(
            robustSigma(leftSamples.map { it.color.luminance }),
            robustSigma(rightSamples.map { it.color.luminance })
        )
        val lumaFloor = max(
            localCodeStep(left, ReplayLocalCleanupThresholds.SIDE_LUMINANCE_MIN_CODES),
            localCodeStep(right, ReplayLocalCleanupThresholds.SIDE_LUMINANCE_MIN_CODES)
        )
        val lumaLimit = max(
            lumaFloor,
            ReplayLocalCleanupThresholds.SIDE_LUMINANCE_MAD_MULTIPLIER * lumaMad
        )
        val chromaLimit = max(
            localCodeStep(left, ReplayLocalCleanupThresholds.SIDE_CHROMA_MAX_CODES),
            localCodeStep(right, ReplayLocalCleanupThresholds.SIDE_CHROMA_MAX_CODES)
        ) * sqrt(3.0)
        val chromaDistance = sqrt(
            (left.chromaRed - right.chromaRed) * (left.chromaRed - right.chromaRed) +
                (left.chromaGreen - right.chromaGreen) * (left.chromaGreen - right.chromaGreen) +
                (left.chromaBlue - right.chromaBlue) * (left.chromaBlue - right.chromaBlue)
        )
        return abs(left.luminance - right.luminance) <= lumaLimit &&
            chromaDistance <= chromaLimit
    }

    private data class ReplayStarChangeSummary(
        val minimumContrastRatio: Double,
        val maximumCentroidShift: Double,
        val maximumWidthChange: Double,
        val maximumEllipticityChange: Double
    )

    private fun starChanges(
        baseline: List<ReplayFixedStarMetrics>,
        candidate: List<ReplayFixedStarMetrics>
    ): ReplayStarChangeSummary {
        if (baseline.isEmpty()) return ReplayStarChangeSummary(1.0, 0.0, 0.0, 0.0)
        val contrastRatios = baseline.indices.map { index ->
            ratio(candidate[index].contrast, baseline[index].contrast)
        }
        val centroid = baseline.indices.map { index ->
            hypot(
                candidate[index].centroidX - baseline[index].centroidX,
                candidate[index].centroidY - baseline[index].centroidY
            )
        }
        val widths = baseline.indices.map { index ->
            relativeChange(candidate[index].width, baseline[index].width)
        }
        val ellipticities = baseline.indices.map { index ->
            relativeChange(candidate[index].ellipticity, baseline[index].ellipticity)
        }
        return ReplayStarChangeSummary(
            contrastRatios.minOrNull() ?: 1.0,
            centroid.maxOrNull() ?: 0.0,
            widths.maxOrNull() ?: 0.0,
            ellipticities.maxOrNull() ?: 0.0
        )
    }

    private fun writeOutputs(
        bundle: ReplayLocalTrailCleanupBundle,
        baseline: ArgbPixelImage,
        annotations: List<ReplayManualTrailAnnotation>,
        prepared: List<ReplayPreparedTrail>,
        starSupports: List<ReplayFixedStarSupport>,
        outputRoot: Path
    ) {
        Files.createDirectories(outputRoot)
        Files.writeString(outputRoot.resolve("frozen-threshold-manifest.json"), bundle.manifest)
        Files.writeString(outputRoot.resolve("report.txt"), report(bundle))
        Files.writeString(outputRoot.resolve("per-trail-results.tsv"), trailTable(bundle))
        ReplayDiagnosticImageIo.writePng(outputRoot.resolve("recovered-stars-baseline.png"), baseline)
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("frozen-safe-sky-mask.png"),
            ReplayDiagnosticImageIo.booleanMask(baseline.width, baseline.height, bundle.safeSkyMask, 0xFFFFFFFF.toInt())
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("confirmed-star-veto.png"),
            ReplayDiagnosticImageIo.booleanMask(
                baseline.width,
                baseline.height,
                bundle.confirmedStarVeto,
                0xFFFF0000.toInt()
            )
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("compact-source-veto.png"),
            ReplayDiagnosticImageIo.booleanMask(
                baseline.width,
                baseline.height,
                bundle.compactSourceVeto,
                0xFFFFFF00.toInt()
            )
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("foreground-veto.png"),
            ReplayDiagnosticImageIo.booleanMask(
                baseline.width,
                baseline.height,
                bundle.foregroundVeto,
                0xFFFFFFFF.toInt()
            )
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("veto-overlay.png"),
            vetoOverlay(baseline, bundle, prepared)
        )
        prepared.forEach { trail ->
            val mask = BooleanArray(baseline.pixels.size)
            trail.maskIndices.forEach { mask[it] = true }
            ReplayDiagnosticImageIo.writePng(
                outputRoot.resolve("masks").resolve("${trail.annotation.id}-repair-mask.png"),
                ReplayDiagnosticImageIo.booleanMask(baseline.width, baseline.height, mask, 0xFFFFFFFF.toInt())
            )
            val samples = IntArray(baseline.pixels.size) { 0xFF000000.toInt() }
            trail.leftSampleIndices.forEach { samples[it] = 0xFF00FFFF.toInt() }
            trail.rightSampleIndices.forEach { samples[it] = 0xFFFF00FF.toInt() }
            ReplayDiagnosticImageIo.writePng(
                outputRoot.resolve("masks").resolve("${trail.annotation.id}-background-left-right.png"),
                ArgbPixelImage(baseline.width, baseline.height, samples)
            )
        }
        bundle.candidates.forEach { candidate ->
            val root = outputRoot.resolve(candidate.strength.fileLabel)
            ReplayDiagnosticImageIo.writePng(root.resolve("candidate.png"), candidate.image)
            ReplayDiagnosticImageIo.writePng(
                root.resolve("applied-repair-mask.png"),
                ReplayDiagnosticImageIo.booleanMask(
                    baseline.width,
                    baseline.height,
                    BooleanArray(baseline.pixels.size) { index ->
                        baseline.pixels[index] != candidate.image.pixels[index]
                    },
                    0xFFFFFFFF.toInt()
                )
            )
            ReplayDiagnosticImageIo.writeDifference(
                root.resolve("difference-x8.png"),
                baseline,
                candidate.image,
                8
            )
            annotations.forEach { annotation ->
                val crop = cropBounds(
                    annotation,
                    baseline.width,
                    baseline.height,
                    ReplayLocalCleanupThresholds.CROP_MARGIN
                )
                ReplayDiagnosticImageIo.writePng(
                    root.resolve("trails").resolve("${annotation.id}-before.png"),
                    ReplayDiagnosticImageIo.crop(baseline, crop.left, crop.top, crop.width, crop.height)
                )
                ReplayDiagnosticImageIo.writePng(
                    root.resolve("trails").resolve("${annotation.id}-after.png"),
                    ReplayDiagnosticImageIo.crop(candidate.image, crop.left, crop.top, crop.width, crop.height)
                )
                ReplayDiagnosticImageIo.writeDifference(
                    root.resolve("trails").resolve("${annotation.id}-difference-x8.png"),
                    ReplayDiagnosticImageIo.crop(baseline, crop.left, crop.top, crop.width, crop.height),
                    ReplayDiagnosticImageIo.crop(candidate.image, crop.left, crop.top, crop.width, crop.height),
                    8
                )
            }
            starSupports.forEachIndexed { index, support ->
                val centerX = support.baselineX.roundToInt()
                val centerY = support.baselineY.roundToInt()
                ReplayDiagnosticImageIo.writePng(
                    root.resolve("control-stars").resolve("star-${index.toString().padStart(2, '0')}-before.png"),
                    ReplayDiagnosticImageIo.cropAround(
                        baseline,
                        centerX,
                        centerY,
                        ReplayLocalCleanupThresholds.CONTROL_STAR_CROP_SIZE
                    )
                )
                ReplayDiagnosticImageIo.writePng(
                    root.resolve("control-stars").resolve("star-${index.toString().padStart(2, '0')}-after.png"),
                    ReplayDiagnosticImageIo.cropAround(
                        candidate.image,
                        centerX,
                        centerY,
                        ReplayLocalCleanupThresholds.CONTROL_STAR_CROP_SIZE
                    )
                )
            }
        }
        writeManualReviewOutputs(
            bundle = bundle,
            baseline = baseline,
            annotations = annotations,
            starSupports = starSupports,
            outputRoot = outputRoot.resolve("manual-review-luminance-080")
        )
    }

    private fun writeManualReviewOutputs(
        bundle: ReplayLocalTrailCleanupBundle,
        baseline: ArgbPixelImage,
        annotations: List<ReplayManualTrailAnnotation>,
        starSupports: List<ReplayFixedStarSupport>,
        outputRoot: Path
    ) {
        val review = bundle.manualReview
        Files.createDirectories(outputRoot)
        Files.writeString(outputRoot.resolve("manual-review-manifest.json"), review.manifest)
        Files.writeString(outputRoot.resolve("manual-review-report.txt"), manualReviewReport(bundle))
        Files.writeString(outputRoot.resolve("manual-review-trails.tsv"), manualReviewTrailTable(review))
        ReplayDiagnosticImageIo.writePng(outputRoot.resolve("baseline.png"), baseline)
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("manual-review-candidate.png"),
            review.image
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("manual-review-L80-phone-open.png"),
            review.image
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("baseline-vs-candidate.png"),
            ReplayDiagnosticImageIo.sideBySide(baseline, review.image)
        )
        ReplayDiagnosticImageIo.writeDifference(
            outputRoot.resolve("difference-x${ReplayManualReviewPolicy.DIFFERENCE_SCALE}.png"),
            baseline,
            review.image,
            ReplayManualReviewPolicy.DIFFERENCE_SCALE
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("applied-repair-mask.png"),
            ReplayDiagnosticImageIo.booleanMask(
                baseline.width,
                baseline.height,
                BooleanArray(baseline.pixels.size) { index ->
                    baseline.pixels[index] != review.image.pixels[index]
                },
                0xFFFFFFFF.toInt()
            )
        )
        val annotationsById = annotations.associateBy { it.id }
        ReplayManualReviewPolicy.REQUESTED_REPAIRS.forEach { id ->
            val annotation = annotationsById[id] ?: return@forEach
            writeManualReviewTrailCrops(
                outputRoot.resolve("repair-crops"),
                annotation,
                baseline,
                review.image
            )
        }
        (
            ReplayManualReviewPolicy.STAR_VETO_CONTROLS +
                ReplayManualReviewPolicy.OUTSIDE_SAFE_REGION_CONTROL
            ).forEach { id ->
            val annotation = annotationsById[id] ?: return@forEach
            writeManualReviewTrailCrops(
                outputRoot.resolve("control-crops").resolve("trails"),
                annotation,
                baseline,
                review.image
            )
        }
        starSupports.forEachIndexed { index, support ->
            val centerX = support.baselineX.roundToInt()
            val centerY = support.baselineY.roundToInt()
            val before = ReplayDiagnosticImageIo.cropAround(
                baseline,
                centerX,
                centerY,
                ReplayManualReviewPolicy.CONTROL_STAR_CROP_SIZE
            )
            val after = ReplayDiagnosticImageIo.cropAround(
                review.image,
                centerX,
                centerY,
                ReplayManualReviewPolicy.CONTROL_STAR_CROP_SIZE
            )
            val label = "star-${index.toString().padStart(2, '0')}"
            val root = outputRoot.resolve("control-crops").resolve("confirmed-stars")
            ReplayDiagnosticImageIo.writePng(root.resolve("$label-before.png"), before)
            ReplayDiagnosticImageIo.writePng(root.resolve("$label-after.png"), after)
            ReplayDiagnosticImageIo.writeDifference(
                root.resolve("$label-difference-x${ReplayManualReviewPolicy.DIFFERENCE_SCALE}.png"),
                before,
                after,
                ReplayManualReviewPolicy.DIFFERENCE_SCALE
            )
        }
    }

    private fun writeManualReviewTrailCrops(
        outputRoot: Path,
        annotation: ReplayManualTrailAnnotation,
        baseline: ArgbPixelImage,
        candidate: ArgbPixelImage
    ) {
        val crop = cropBounds(
            annotation,
            baseline.width,
            baseline.height,
            ReplayManualReviewPolicy.TRAIL_CROP_MARGIN
        )
        val before = ReplayDiagnosticImageIo.crop(
            baseline,
            crop.left,
            crop.top,
            crop.width,
            crop.height
        )
        val after = ReplayDiagnosticImageIo.crop(
            candidate,
            crop.left,
            crop.top,
            crop.width,
            crop.height
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("${annotation.id}-before.png"),
            before
        )
        ReplayDiagnosticImageIo.writePng(
            outputRoot.resolve("${annotation.id}-after.png"),
            after
        )
        ReplayDiagnosticImageIo.writeDifference(
            outputRoot.resolve(
                "${annotation.id}-difference-x${ReplayManualReviewPolicy.DIFFERENCE_SCALE}.png"
            ),
            before,
            after,
            ReplayManualReviewPolicy.DIFFERENCE_SCALE
        )
    }

    private fun manualReviewReport(bundle: ReplayLocalTrailCleanupBundle): String = buildString {
        val review = bundle.manualReview
        appendLine("mode=replay_only_manual_visual_review")
        appendLine("productionCodeChanged=false")
        appendLine("enhancedPublished=false")
        appendLine("automaticDetectionEnabled=false")
        appendLine("formalStage1ResultsUnchanged=${review.formalStage1ResultsUnchanged}")
        appendLine("sourceStrength=${review.sourceStrength}")
        appendLine("baselinePixelHashBefore=${bundle.baselinePixelHashBefore}")
        appendLine("baselinePixelHashAfter=${bundle.baselinePixelHashAfter}")
        appendLine("baselineUnchanged=${bundle.baselineUnchanged}")
        appendLine("candidatePixelHash=${review.imagePixelHash}")
        appendLine("manifestHash=${review.manifestHash}")
        appendLine("changedPixelCount=${review.changedPixelCount}")
        appendLine("outsideMaskMaximumDifference=${review.outsideMaskMaximumDifference}")
        appendLine("foregroundMaximumDifference=${review.foregroundMaximumDifference}")
        appendLine(
            "confirmedStarSupportMaximumDifference=" +
                review.confirmedStarSupportMaximumDifference
        )
        appendLine("starContrastMinimumRatio=${review.starContrastMinimumRatio}")
        appendLine("starMaximumCentroidShift=${review.starMaximumCentroidShift}")
        appendLine("starMaximumWidthChange=${review.starMaximumWidthChange}")
        appendLine("starMaximumEllipticityChange=${review.starMaximumEllipticityChange}")
        appendLine("skyMadRatio=${review.skyMadRatio}")
        appendLine("bandingRatio=${review.bandingRatio}")
        appendLine("gradientRatio=${review.gradientRatio}")
        appendLine("validationPassed=${review.validationPassed}")
        appendLine("validationReasons=${review.validationReasons.joinToString("|")}")
        appendLine("phoneOpenFile=manual-review-L80-phone-open.png")
        review.trailResults.forEach { result ->
            val prefix = "trail.${result.trailId}"
            appendLine("$prefix.decision=${result.decision}")
            appendLine("$prefix.applied=${result.applied}")
            appendLine("$prefix.formalStatus=${result.formalStatus}")
            appendLine("$prefix.formalReasons=${result.formalReasons.joinToString("|")}")
            appendLine(
                "$prefix.belowStrictEnergyThreshold=" +
                    result.belowStrictEnergyThreshold
            )
            appendLine("$prefix.luminanceEnergyRatio=${result.luminanceEnergyRatio}")
            appendLine("$prefix.chromaEnergyRatio=${result.chromaEnergyRatio}")
            appendLine("$prefix.proposedChangedPixels=${result.proposedChangedPixels}")
            appendLine("$prefix.appliedChangedPixels=${result.appliedChangedPixels}")
            result.boundary?.let { boundary ->
                appendLine("$prefix.seamDetected=${boundary.seamDetected}")
                appendLine("$prefix.haloDetected=${boundary.haloDetected}")
                appendLine("$prefix.smoothStripeDetected=${boundary.smoothStripeDetected}")
            }
        }
    }

    private fun manualReviewTrailTable(review: ReplayManualReviewCandidate): String = buildString {
        appendLine(
            "trailId\tdecision\tapplied\tformalStatus\tformalReasons\t" +
                "belowStrictEnergyThreshold\tluminanceEnergyRatio\tchromaEnergyRatio\t" +
                "proposedChangedPixels\tappliedChangedPixels\tseam\thalo\tsmoothStripe"
        )
        review.trailResults.forEach { result ->
            appendLine(
                listOf(
                    result.trailId,
                    result.decision,
                    result.applied,
                    result.formalStatus,
                    result.formalReasons.joinToString("|"),
                    result.belowStrictEnergyThreshold,
                    result.luminanceEnergyRatio,
                    result.chromaEnergyRatio,
                    result.proposedChangedPixels,
                    result.appliedChangedPixels,
                    result.boundary?.seamDetected ?: false,
                    result.boundary?.haloDetected ?: false,
                    result.boundary?.smoothStripeDetected ?: false
                ).joinToString("\t")
            )
        }
    }

    private fun report(bundle: ReplayLocalTrailCleanupBundle): String = buildString {
        appendLine("mode=replay_only_local_trail_cleanup")
        appendLine("productionCodeChanged=false")
        appendLine("automaticDetectionEnabled=false")
        appendLine("enhancedPublished=false")
        appendLine("baselinePixelHashBefore=${bundle.baselinePixelHashBefore}")
        appendLine("baselinePixelHashAfter=${bundle.baselinePixelHashAfter}")
        appendLine("baselineUnchanged=${bundle.baselineUnchanged}")
        appendLine("safeSkyMaskHash=${bundle.safeSkyMaskHash}")
        appendLine("manifestHash=${bundle.manifestHash}")
        appendLine(
            "confirmedStarCenters=" +
                bundle.confirmedStarCenters.joinToString("|") { "${it.x},${it.y}" }
        )
        appendLine(
            "compactSourceCenters=" +
                bundle.compactSourceCenters.joinToString("|") { "${it.x},${it.y}" }
        )
        appendLine("preferredStrength=${bundle.preferredStrength ?: "none"}")
        bundle.candidates.forEach { candidate ->
            val prefix = "candidate.${candidate.strength.fileLabel}"
            appendLine("$prefix.preferred=${candidate.preferred}")
            appendLine("$prefix.globalAccepted=${candidate.globalAccepted}")
            appendLine("$prefix.globalRejections=${candidate.globalRejectionReasons.joinToString("|")}")
            appendLine("$prefix.skyMadRatio=${candidate.skyMadRatio}")
            appendLine("$prefix.bandingRatio=${candidate.bandingRatio}")
            appendLine("$prefix.gradientRatio=${candidate.gradientRatio}")
            appendLine("$prefix.foregroundMaximumDifference=${candidate.foregroundMaximumDifference}")
            appendLine("$prefix.outsideMaskMaximumDifference=${candidate.outsideMaskMaximumDifference}")
            appendLine("$prefix.starContrastMinimumRatio=${candidate.starContrastMinimumRatio}")
            appendLine("$prefix.starMaximumCentroidShift=${candidate.starMaximumCentroidShift}")
            appendLine("$prefix.starMaximumWidthChange=${candidate.starMaximumWidthChange}")
            appendLine("$prefix.starMaximumEllipticityChange=${candidate.starMaximumEllipticityChange}")
            candidate.trailMetrics.forEach { metric ->
                val trail = "$prefix.trail.${metric.trailId}"
                appendLine("$trail.status=${metric.status}")
                appendLine("$trail.applied=${metric.applied}")
                appendLine("$trail.reasons=${metric.reasons.joinToString("|")}")
                appendLine("$trail.luminanceEnergyRatio=${metric.luminanceEnergyRatio}")
                appendLine("$trail.chromaEnergyRatio=${metric.chromaEnergyRatio}")
                appendLine("$trail.combinedEnergyRatio=${metric.combinedEnergyRatio}")
                appendLine("$trail.luminanceSupportPixels=${metric.luminanceSupportPixels}")
                appendLine("$trail.chromaSupportPixels=${metric.chromaSupportPixels}")
                appendLine("$trail.proposedChangedPixels=${metric.proposedChangedPixels}")
                appendLine(
                    "$trail.appliedChangedPixels=" +
                        if (metric.applied) metric.proposedChangedPixels else 0
                )
                metric.boundary?.let { boundary ->
                    appendLine("$trail.boundary.innerLuminanceP95=${boundary.innerLuminanceP95}")
                    appendLine("$trail.boundary.outerLuminanceP95=${boundary.outerLuminanceP95}")
                    appendLine("$trail.boundary.innerChromaP95=${boundary.innerChromaP95}")
                    appendLine("$trail.boundary.outerChromaP95=${boundary.outerChromaP95}")
                    appendLine("$trail.boundary.luminanceDeltaP95=${boundary.luminanceGradientDeltaP95}")
                    appendLine("$trail.boundary.chromaDeltaP95=${boundary.chromaGradientDeltaP95}")
                    appendLine("$trail.boundary.haloOvershoot=${boundary.haloOvershoot}")
                    appendLine("$trail.boundary.smoothnessMeasurable=${boundary.smoothnessMeasurable}")
                    appendLine("$trail.boundary.seamDetected=${boundary.seamDetected}")
                    appendLine("$trail.boundary.haloDetected=${boundary.haloDetected}")
                    appendLine("$trail.boundary.smoothStripeDetected=${boundary.smoothStripeDetected}")
                }
            }
        }
    }

    private fun trailTable(bundle: ReplayLocalTrailCleanupBundle): String = buildString {
        appendLine(
            "candidate\ttrailId\tstatus\tapplied\treasons\tbaselineLuminanceEnergy\t" +
                "proposedLuminanceEnergy\tluminanceRatio\tbaselineChromaEnergy\t" +
            "proposedChromaEnergy\tchromaRatio\tbaselineCombinedEnergy\tproposedCombinedEnergy\t" +
                "combinedRatio\tluminanceSupportPixels\tchromaSupportPixels\t" +
                "proposedChangedPixels\tappliedChangedPixels\tseam\thalo\tsmoothStripe"
        )
        bundle.candidates.forEach { candidate ->
            candidate.trailMetrics.forEach { metric ->
                appendLine(
                    listOf(
                        candidate.strength.fileLabel,
                        metric.trailId,
                        metric.status,
                        metric.applied,
                        metric.reasons.joinToString("|"),
                        metric.baselineLuminanceEnergy,
                        metric.proposedLuminanceEnergy,
                        metric.luminanceEnergyRatio,
                        metric.baselineChromaEnergy,
                        metric.proposedChromaEnergy,
                        metric.chromaEnergyRatio,
                        metric.baselineCombinedEnergy,
                        metric.proposedCombinedEnergy,
                        metric.combinedEnergyRatio,
                        metric.luminanceSupportPixels,
                        metric.chromaSupportPixels,
                        metric.proposedChangedPixels,
                        if (metric.applied) metric.proposedChangedPixels else 0,
                        metric.boundary?.seamDetected ?: false,
                        metric.boundary?.haloDetected ?: false,
                        metric.boundary?.smoothStripeDetected ?: false
                    ).joinToString("\t")
                )
            }
        }
    }

    private fun vetoOverlay(
        baseline: ArgbPixelImage,
        bundle: ReplayLocalTrailCleanupBundle,
        prepared: List<ReplayPreparedTrail>
    ): ArgbPixelImage {
        val pixels = baseline.pixels.copyOf()
        bundle.compactSourceVeto.indices.forEach { index ->
            if (bundle.compactSourceVeto[index]) pixels[index] = blendEncoded(pixels[index], 0xFFFFFF00.toInt(), 0.65)
        }
        bundle.confirmedStarVeto.indices.forEach { index ->
            if (bundle.confirmedStarVeto[index]) pixels[index] = blendEncoded(pixels[index], 0xFFFF0000.toInt(), 0.75)
        }
        prepared.forEach { trail ->
            val color = if (trail.status == ReplayTrailRepairStatus.SKIPPED_OUTSIDE_SAFE_REGION) {
                0xFFFF00FF.toInt()
            } else {
                0xFF00FFFF.toInt()
            }
            trail.maskIndices.forEach { index ->
                pixels[index] = blendEncoded(pixels[index], color, 0.70)
            }
        }
        return ArgbPixelImage(baseline.width, baseline.height, pixels)
    }

    private fun blendEncoded(first: Int, second: Int, amount: Double): Int {
        fun channel(shift: Int): Int = (
            ((first ushr shift) and 0xFF) * (1.0 - amount) +
                ((second ushr shift) and 0xFF) * amount
            ).roundToInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }

    private fun manualReviewManifest(): String {
        val values = sortedMapOf(
            "automaticDetectionEnabled" to "false",
            "differenceScale" to ReplayManualReviewPolicy.DIFFERENCE_SCALE.toString(),
            "energyOverrideRepairs" to
                ReplayManualReviewPolicy.ENERGY_OVERRIDE_REPAIRS.joinToString("|"),
            "formalStage1MetricsMutable" to "false",
            "outsideSafeRegionControl" to ReplayManualReviewPolicy.OUTSIDE_SAFE_REGION_CONTROL,
            "productionSavingEnabled" to "false",
            "requestedRepairs" to ReplayManualReviewPolicy.REQUESTED_REPAIRS.joinToString("|"),
            "sourceStrength" to ReplayTrailRepairStrength.LUMINANCE_80.name,
            "starVetoControls" to ReplayManualReviewPolicy.STAR_VETO_CONTROLS.joinToString("|"),
            "strictEnergyRatio" to ReplayLocalCleanupThresholds.MAX_TRAIL_ENERGY_RATIO.toString(),
            "trailCropMargin" to ReplayManualReviewPolicy.TRAIL_CROP_MARGIN.toString(),
            "version" to ReplayManualReviewPolicy.VERSION
        )
        return values.entries.joinToString(
            prefix = "{\n",
            postfix = "\n}\n",
            separator = ",\n"
        ) {
            "  \"${it.key}\":\"${it.value}\""
        }
    }

    private fun manifest(
        width: Int,
        height: Int,
        skyHash: String,
        annotations: List<ReplayManualTrailAnnotation>
    ): String {
        val values = sortedMapOf(
            "background.endpointOvershootMax" to
                ReplayLocalCleanupThresholds.BACKGROUND_LONGITUDINAL_RADIUS.toString(),
            "background.innerRadius" to ReplayLocalCleanupThresholds.BACKGROUND_INNER_RADIUS.toString(),
            "background.longitudinalRadius" to ReplayLocalCleanupThresholds.BACKGROUND_LONGITUDINAL_RADIUS.toString(),
            "background.minRetainedFraction" to ReplayLocalCleanupThresholds.MIN_BACKGROUND_RETAINED_FRACTION.toString(),
            "background.minSamplesPerSide" to ReplayLocalCleanupThresholds.MIN_BACKGROUND_SAMPLES_PER_SIDE.toString(),
            "background.outerRadius" to ReplayLocalCleanupThresholds.BACKGROUND_OUTER_RADIUS.toString(),
            "background.outlierMadMultiplier" to ReplayLocalCleanupThresholds.OUTLIER_MAD_MULTIPLIER.toString(),
            "background.outlierMinCodes" to ReplayLocalCleanupThresholds.OUTLIER_MIN_CODES.toString(),
            "background.overlayColors" to "left_cyan|right_magenta",
            "background.sideChromaMaxCodes" to ReplayLocalCleanupThresholds.SIDE_CHROMA_MAX_CODES.toString(),
            "background.sideLuminanceMadMultiplier" to
                ReplayLocalCleanupThresholds.SIDE_LUMINANCE_MAD_MULTIPLIER.toString(),
            "background.sideLuminanceMinCodes" to
                ReplayLocalCleanupThresholds.SIDE_LUMINANCE_MIN_CODES.toString(),
            "boundary.haloRule" to "outside_source_background_range_by_noise_or_2_codes",
            "boundary.innerMaxRadius" to ReplayLocalCleanupThresholds.INNER_EDGE_MAX_RADIUS.toString(),
            "boundary.innerMinRadius" to ReplayLocalCleanupThresholds.INNER_EDGE_MIN_RADIUS.toString(),
            "boundary.outerMaxRadius" to ReplayLocalCleanupThresholds.OUTER_EDGE_MAX_RADIUS.toString(),
            "boundary.outerMinRadius" to ReplayLocalCleanupThresholds.OUTER_EDGE_MIN_RADIUS.toString(),
            "boundary.outerReferenceMaxRadius" to
                ReplayLocalCleanupThresholds.OUTER_REFERENCE_MAX_RADIUS.toString(),
            "boundary.seamMinCodeSteps" to ReplayLocalCleanupThresholds.SEAM_MIN_CODE_STEPS.toString(),
            "boundary.seamNoiseMultiplier" to ReplayLocalCleanupThresholds.SEAM_NOISE_MULTIPLIER.toString(),
            "boundary.seamRelativeLimit" to ReplayLocalCleanupThresholds.SEAM_RELATIVE_LIMIT.toString(),
            "boundary.smoothnessMinNoiseCodeSteps" to
                ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_NOISE_CODE_STEPS.toString(),
            "boundary.smoothnessMinRatio" to ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_RATIO.toString(),
            "boundary.smoothnessMinSamples" to
                ReplayLocalCleanupThresholds.SMOOTHNESS_MIN_SAMPLE_COUNT.toString(),
            "candidate.strengths" to ReplayTrailRepairStrength.entries.joinToString("|") {
                "${it.fileLabel}:${it.luminanceSuppression}"
            },
            "compact.maxEllipticity" to ReplayLocalCleanupThresholds.COMPACT_MAX_ELLIPTICITY.toString(),
            "compact.maxWidth" to ReplayLocalCleanupThresholds.COMPACT_MAX_WIDTH.toString(),
            "compact.minWidth" to ReplayLocalCleanupThresholds.COMPACT_MIN_WIDTH.toString(),
            "energy.chromaMaxRatio" to ReplayLocalCleanupThresholds.MAX_TRAIL_ENERGY_RATIO.toString(),
            "energy.floorPerPixel" to ReplayLocalCleanupThresholds.ENERGY_FLOOR_PER_PIXEL.toString(),
            "energy.luminanceMaxRatio" to ReplayLocalCleanupThresholds.MAX_TRAIL_ENERGY_RATIO.toString(),
            "energy.rule" to
                "luminance_and_chroma_validated_independently_on_respective_frozen_supports",
            "foreground.definition" to "complement_of_frozen_eroded_safe_sky",
            "height" to height.toString(),
            "manualAnnotationHash" to sha256(
                annotations.joinToString("\n") { annotation ->
                    "${annotation.id}:${annotation.centerline.joinToString("|") { "${it.x},${it.y}" }}"
                }.toByteArray(StandardCharsets.UTF_8)
            ),
            "mask.coreRadius" to ReplayLocalCleanupThresholds.CORE_RADIUS.toString(),
            "mask.feather" to "one_minus_smoothstep",
            "mask.featherRadius" to ReplayLocalCleanupThresholds.FEATHER_RADIUS.toString(),
            "mask.inputs" to "unchanged_recovered_stars_baseline_only",
            "mask.safeSkyHash" to skyHash,
            "mask.safeSkyVersion" to ReplayDefectThresholds.SKY_MASK_VERSION,
            "quality.maxBackgroundWorsening" to
                ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING.toString(),
            "quality.maxStarCentroidShift" to
                ReplayLocalCleanupThresholds.MAX_STAR_CENTROID_SHIFT.toString(),
            "quality.maxStarGeometryChange" to
                ReplayLocalCleanupThresholds.MAX_STAR_GEOMETRY_CHANGE.toString(),
            "quality.minStarContrastRetention" to
                ReplayLocalCleanupThresholds.MIN_STAR_CONTRAST_RETENTION.toString(),
            "repair.colorSpace" to "exact_iec_srgb_to_linear_rec709_luminance_plus_opponent_chroma",
            "repair.policy" to
                "baseline_frozen_elongated_support_chroma_first_then_positive_luminance_only",
            "signalSupport.connectivity" to "8",
            "signalSupport.dilationRadius" to "1",
            "signalSupport.maxOrientationDifferenceDegrees" to
                ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MAX_ORIENTATION_DIFFERENCE_DEGREES.toString(),
            "signalSupport.minEncodedCodes" to
                ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MIN_CODES.toString(),
            "signalSupport.minElongation" to
                ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MIN_ELONGATION.toString(),
            "signalSupport.minMajorAxis" to
                ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MIN_MAJOR_AXIS.toString(),
            "signalSupport.minPixels" to
                ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MIN_PIXELS.toString(),
            "starVeto.dilationRadius" to ReplayLocalCleanupThresholds.STAR_VETO_DILATION_RADIUS.toString(),
            "version" to ReplayLocalCleanupThresholds.VERSION,
            "width" to width.toString()
        )
        return values.entries.joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") {
            "  \"${it.key}\":\"${it.value}\""
        }
    }

    private data class ReplayMaskGeometry(
        val mask: IntArray,
        val core: IntArray
    )

    private fun maskGeometry(
        annotation: ReplayManualTrailAnnotation,
        width: Int,
        height: Int
    ): Pair<IntArray, IntArray> {
        val box = boundingBox(
            annotation,
            width,
            height,
            ReplayLocalCleanupThresholds.FEATHER_RADIUS + 1.0
        )
        val mask = mutableListOf<Int>()
        val core = mutableListOf<Int>()
        for (y in box.top..box.bottom) for (x in box.left..box.right) {
            val distance = projectToPolyline(
                ReplayPoint(x.toDouble(), y.toDouble()),
                annotation.centerline
            ).distance
            if (distance <= ReplayLocalCleanupThresholds.FEATHER_RADIUS) {
                val index = y * width + x
                mask += index
                if (distance <= ReplayLocalCleanupThresholds.CORE_RADIUS) core += index
            }
        }
        return mask.toIntArray() to core.toIntArray()
    }

    private data class ReplayBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private fun boundingBox(
        annotation: ReplayManualTrailAnnotation,
        width: Int,
        height: Int,
        margin: Double
    ): ReplayBounds = ReplayBounds(
        floor(annotation.centerline.minOf { it.x } - margin).toInt().coerceAtLeast(0),
        floor(annotation.centerline.minOf { it.y } - margin).toInt().coerceAtLeast(0),
        ceil(annotation.centerline.maxOf { it.x } + margin).toInt().coerceAtMost(width - 1),
        ceil(annotation.centerline.maxOf { it.y } + margin).toInt().coerceAtMost(height - 1)
    )

    private fun cropBounds(
        annotation: ReplayManualTrailAnnotation,
        width: Int,
        height: Int,
        margin: Int
    ): ReplayBounds = boundingBox(annotation, width, height, margin.toDouble())

    private fun projectToPolyline(point: ReplayPoint, line: List<ReplayPoint>): ReplayPathProjection {
        require(line.isNotEmpty())
        if (line.size == 1) {
            return ReplayPathProjection(
                hypot(point.x - line[0].x, point.y - line[0].y),
                point.y - line[0].y,
                0.0,
                hypot(point.x - line[0].x, point.y - line[0].y)
            )
        }
        var cumulative = 0.0
        var best: ReplayPathProjection? = null
        line.zipWithNext().forEach { (first, second) ->
            val dx = second.x - first.x
            val dy = second.y - first.y
            val length = hypot(dx, dy)
            if (length <= 1e-12) return@forEach
            val tangentX = dx / length
            val tangentY = dy / length
            val normalX = -tangentY
            val normalY = tangentX
            val rawAlong = (point.x - first.x) * tangentX + (point.y - first.y) * tangentY
            val along = rawAlong.coerceIn(0.0, length)
            val projectedX = first.x + tangentX * along
            val projectedY = first.y + tangentY * along
            val offsetX = point.x - projectedX
            val offsetY = point.y - projectedY
            val candidate = ReplayPathProjection(
                distance = hypot(offsetX, offsetY),
                signedDistance = offsetX * normalX + offsetY * normalY,
                arcLength = cumulative + along,
                endpointOvershoot = abs(rawAlong - along)
            )
            if (best == null || candidate.distance < checkNotNull(best).distance) best = candidate
            cumulative += length
        }
        return best ?: ReplayPathProjection(
            hypot(point.x - line.first().x, point.y - line.first().y),
            point.y - line.first().y,
            0.0,
            hypot(point.x - line.first().x, point.y - line.first().y)
        )
    }

    private fun featherWeight(distance: Double): Double {
        if (distance <= ReplayLocalCleanupThresholds.CORE_RADIUS) return 1.0
        if (distance >= ReplayLocalCleanupThresholds.FEATHER_RADIUS) return 0.0
        val value = (
            (distance - ReplayLocalCleanupThresholds.CORE_RADIUS) /
                (ReplayLocalCleanupThresholds.FEATHER_RADIUS - ReplayLocalCleanupThresholds.CORE_RADIUS)
            ).coerceIn(0.0, 1.0)
        val smooth = value * value * (3.0 - 2.0 * value)
        return 1.0 - smooth
    }

    private fun frozenElongatedSignalSupport(
        baseline: ArgbPixelImage,
        annotation: ReplayManualTrailAnnotation,
        repairs: List<ReplayRepairPixel>,
        coreIndices: IntArray,
        domain: ReplaySignalDomain
    ): Set<Int> {
        val byIndex = repairs.associateBy { it.index }
        val positive = coreIndices.filter { index ->
            val repair = byIndex[index] ?: return@filter false
            val source = decode(baseline.pixels[index])
            val threshold = localCodeStep(
                repair.background,
                ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MIN_CODES
            )
            when (domain) {
                ReplaySignalDomain.LUMINANCE ->
                    source.luminance - repair.background.luminance >= threshold
                ReplaySignalDomain.CHROMA -> {
                    val dr = source.chromaRed - repair.background.chromaRed
                    val dg = source.chromaGreen - repair.background.chromaGreen
                    val db = source.chromaBlue - repair.background.chromaBlue
                    sqrt(dr * dr + dg * dg + db * db) >= threshold * sqrt(3.0)
                }
            }
        }.toSet()
        if (positive.isEmpty()) return emptySet()
        val visited = mutableSetOf<Int>()
        val accepted = mutableSetOf<Int>()
        val lineStart = annotation.centerline.first()
        val lineEnd = annotation.centerline.last()
        val lineOrientation = atan2(lineEnd.y - lineStart.y, lineEnd.x - lineStart.x)
        positive.sorted().forEach { start ->
            if (!visited.add(start)) return@forEach
            val queue = ArrayDeque<Int>()
            val component = mutableListOf<Int>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                component += index
                val x = index % baseline.width
                val y = index / baseline.width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until baseline.width || ny !in 0 until baseline.height) continue
                    val next = ny * baseline.width + nx
                    if (next in positive && visited.add(next)) queue.add(next)
                }
            }
            val points = component.map { index ->
                ReplayPoint((index % baseline.width).toDouble(), (index / baseline.width).toDouble())
            }
            val geometry = ReplayDefectMath.pca(points)
            val orientationDifference = ReplayDefectMath.orientationDifferenceDegrees(
                geometry.orientationRadians,
                lineOrientation
            )
            if (component.size >= ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MIN_PIXELS &&
                geometry.majorAxisLength >= ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MIN_MAJOR_AXIS &&
                geometry.elongation >= ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MIN_ELONGATION &&
                orientationDifference != null &&
                orientationDifference <=
                ReplayLocalCleanupThresholds.LUMINANCE_SUPPORT_MAX_ORIENTATION_DIFFERENCE_DEGREES
            ) {
                accepted += component
            }
        }
        if (accepted.isEmpty()) return emptySet()
        val repairIndices = repairs.map { it.index }.toSet()
        val dilated = accepted.toMutableSet()
        accepted.forEach { index ->
            val x = index % baseline.width
            val y = index / baseline.width
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until baseline.width || ny !in 0 until baseline.height) continue
                val next = ny * baseline.width + nx
                if (next in repairIndices) dilated += next
            }
        }
        return dilated
    }

    private fun dilatedSupportMask(
        width: Int,
        height: Int,
        supports: List<ReplayFixedStarSupport>,
        radius: Int
    ): BooleanArray {
        val mask = BooleanArray(width * height)
        supports.forEach { support ->
            support.measurementIndices.forEach { index ->
                val x = index % width
                val y = index / width
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    if (dx * dx + dy * dy > radius * radius) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height) mask[ny * width + nx] = true
                }
            }
        }
        return mask
    }

    private fun decode(color: Int): ReplayLinearColor = ReplayLinearColor(
        ReplayIecSrgbTransfer.decode8(color ushr 16 and 0xFF),
        ReplayIecSrgbTransfer.decode8(color ushr 8 and 0xFF),
        ReplayIecSrgbTransfer.decode8(color and 0xFF)
    )

    private fun encode(alpha: Int, red: Double, green: Double, blue: Double): Int =
        (alpha shl 24) or
            (ReplayIecSrgbTransfer.encode8(red) shl 16) or
            (ReplayIecSrgbTransfer.encode8(green) shl 8) or
            ReplayIecSrgbTransfer.encode8(blue)

    private fun mix(first: ReplayLinearColor, second: ReplayLinearColor, amount: Double) =
        ReplayLinearColor(
            first.red * (1.0 - amount) + second.red * amount,
            first.green * (1.0 - amount) + second.green * amount,
            first.blue * (1.0 - amount) + second.blue * amount
        )

    private fun medianColor(values: List<ReplayLinearColor>) = ReplayLinearColor(
        median(values.map { it.red }),
        median(values.map { it.green }),
        median(values.map { it.blue })
    )

    private fun localCodeStep(color: ReplayLinearColor, codes: Int): Double {
        fun step(value: Double): Double {
            val encoded = (ReplayIecSrgbTransfer.encode(value) * 255.0).roundToInt()
            return abs(
                ReplayIecSrgbTransfer.decode8((encoded + codes).coerceAtMost(255)) -
                    ReplayIecSrgbTransfer.decode8(encoded)
            )
        }
        return maxOf(step(color.red), step(color.green), step(color.blue), 1e-8)
    }

    private fun componentGradient(
        first: ReplayLinearColor,
        second: ReplayLinearColor,
        distance: Double
    ): Pair<Double, Double> {
        val luminance = abs(first.luminance - second.luminance) / distance
        val chroma = sqrt(
            (first.chromaRed - second.chromaRed) * (first.chromaRed - second.chromaRed) +
                (first.chromaGreen - second.chromaGreen) * (first.chromaGreen - second.chromaGreen) +
                (first.chromaBlue - second.chromaBlue) * (first.chromaBlue - second.chromaBlue)
        ) / distance
        return luminance to chroma
    }

    private fun localHighFrequency(image: ArgbPixelImage, index: Int): Pair<Double, Double> {
        val x = index % image.width
        val y = index / image.width
        if (x !in 1 until image.width - 1 || y !in 1 until image.height - 1) return 0.0 to 0.0
        val neighbors = mutableListOf<ReplayLinearColor>()
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            neighbors += decode(image.pixels[(y + dy) * image.width + x + dx])
        }
        val background = medianColor(neighbors)
        val center = decode(image.pixels[index])
        return abs(center.luminance - background.luminance) to sqrt(
            (center.chromaRed - background.chromaRed) * (center.chromaRed - background.chromaRed) +
                (center.chromaGreen - background.chromaGreen) * (center.chromaGreen - background.chromaGreen) +
                (center.chromaBlue - background.chromaBlue) * (center.chromaBlue - background.chromaBlue)
        )
    }

    private fun encodedTextureResidual(image: ArgbPixelImage, index: Int): Double {
        val x = index % image.width
        val y = index / image.width
        if (x !in 1 until image.width - 1 || y !in 1 until image.height - 1) return 0.0
        val neighbours = mutableListOf<Double>()
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            neighbours += encodedLuminance(image.pixels[(y + dy) * image.width + x + dx])
        }
        return abs(encodedLuminance(image.pixels[index]) - median(neighbours))
    }

    private fun encodedLuminance(color: Int): Double =
        0.2126 * (color ushr 16 and 0xFF) +
            0.7152 * (color ushr 8 and 0xFF) +
            0.0722 * (color and 0xFF)

    private fun quantizationFloor(
        image: ArgbPixelImage,
        indices: List<Int>,
        luminanceOnly: Boolean
    ): Double {
        if (indices.isEmpty()) return ReplayIecSrgbTransfer.decode8(1)
        val values = indices.map { index ->
            val color = image.pixels[index]
            val base = decode(color)
            val stepped = ReplayLinearColor(
                ReplayIecSrgbTransfer.decode8(((color ushr 16 and 0xFF) + 1).coerceAtMost(255)),
                ReplayIecSrgbTransfer.decode8(((color ushr 8 and 0xFF) + 1).coerceAtMost(255)),
                ReplayIecSrgbTransfer.decode8(((color and 0xFF) + 1).coerceAtMost(255))
            )
            if (luminanceOnly) abs(stepped.luminance - base.luminance)
            else componentGradient(base, stepped, 1.0).second
        }
        return median(values).coerceAtLeast(1e-8)
    }

    private fun crossesBand(first: Double, second: Double, inner: Double, outer: Double): Boolean =
        (first <= inner && second in inner..outer) ||
            (second <= inner && first in inner..outer)

    private fun crossesOuterBoundary(first: Double, second: Double): Boolean {
        fun feather(value: Double) =
            value > ReplayLocalCleanupThresholds.OUTER_EDGE_MIN_RADIUS &&
                value < ReplayLocalCleanupThresholds.OUTER_EDGE_MAX_RADIUS
        fun outside(value: Double) =
            value >= ReplayLocalCleanupThresholds.OUTER_EDGE_MAX_RADIUS &&
                value <= ReplayLocalCleanupThresholds.OUTER_REFERENCE_MAX_RADIUS
        return feather(first) && outside(second) || feather(second) && outside(first)
    }

    private fun pairedDeltaP95(first: List<Double>, second: List<Double>): Double =
        percentile(first.indices.map { index -> abs(first[index] - second.getOrElse(index) { 0.0 }) }, 0.95)

    private fun energyRatio(candidate: Double, baseline: Double, pixels: Int): Double {
        val floor = ReplayLocalCleanupThresholds.ENERGY_FLOOR_PER_PIXEL * pixels.coerceAtLeast(1)
        return if (baseline <= floor) {
            if (candidate <= floor) 0.0 else candidate / floor
        } else {
            candidate / baseline
        }
    }

    private fun maximumDifferenceWhere(
        candidate: ArgbPixelImage,
        baseline: ArgbPixelImage,
        include: (Int) -> Boolean
    ): Int {
        var maximum = 0
        candidate.pixels.indices.forEach { index ->
            if (!include(index)) return@forEach
            maximum = max(maximum, maximumChannelDifference(candidate.pixels[index], baseline.pixels[index]))
        }
        return maximum
    }

    private fun maximumChannelDifference(first: Int, second: Int): Int = maxOf(
        abs((first ushr 24 and 0xFF) - (second ushr 24 and 0xFF)),
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    )

    private fun relativeChange(candidate: Double, baseline: Double): Double =
        if (abs(baseline) <= 1e-9) {
            if (abs(candidate) <= 1e-9) 0.0 else Double.POSITIVE_INFINITY
        } else {
            abs(candidate / baseline - 1.0)
        }

    private fun ratio(candidate: Double, baseline: Double): Double =
        if (abs(baseline) <= 1e-12) {
            if (abs(candidate) <= 1e-12) 1.0 else Double.POSITIVE_INFINITY
        } else {
            candidate / baseline
        }

    private fun robustSigma(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val center = median(values)
        return 1.4826 * median(values.map { abs(it - center) })
    }

    private fun percentile(values: List<Double>, percentile: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val position = ((sorted.size - 1) * percentile).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
        val amount = position - lower
        return sorted[lower] * (1.0 - amount) + sorted[upper] * amount
    }

    private fun median(values: List<Double>): Double = percentile(values, 0.50)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

internal object ReplayDiagnosticImageIo {
    fun writePng(path: Path, image: ArgbPixelImage) {
        Files.createDirectories(path.parent)
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        check(ImageIO.write(buffered, "png", path.toFile()))
        buffered.flush()
    }

    fun writeDifference(path: Path, baseline: ArgbPixelImage, candidate: ArgbPixelImage, scale: Int) {
        require(baseline.width == candidate.width && baseline.height == candidate.height)
        val pixels = IntArray(baseline.pixels.size) { index ->
            val first = baseline.pixels[index]
            val second = candidate.pixels[index]
            fun channel(shift: Int): Int {
                val delta = ((second ushr shift) and 0xFF) - ((first ushr shift) and 0xFF)
                return (128 + delta * scale).coerceIn(0, 255)
            }
            0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        writePng(path, ArgbPixelImage(baseline.width, baseline.height, pixels))
    }

    fun booleanMask(width: Int, height: Int, mask: BooleanArray, color: Int): ArgbPixelImage {
        require(mask.size == width * height)
        return ArgbPixelImage(
            width,
            height,
            IntArray(mask.size) { index -> if (mask[index]) color else 0xFF000000.toInt() }
        )
    }

    fun sideBySide(first: ArgbPixelImage, second: ArgbPixelImage): ArgbPixelImage {
        require(first.height == second.height)
        val width = first.width + second.width
        val pixels = IntArray(width * first.height) { 0xFF000000.toInt() }
        for (y in 0 until first.height) {
            System.arraycopy(
                first.pixels,
                y * first.width,
                pixels,
                y * width,
                first.width
            )
            System.arraycopy(
                second.pixels,
                y * second.width,
                pixels,
                y * width + first.width,
                second.width
            )
        }
        return ArgbPixelImage(width, first.height, pixels)
    }

    fun crop(image: ArgbPixelImage, left: Int, top: Int, width: Int, height: Int): ArgbPixelImage {
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        require(left + width <= image.width && top + height <= image.height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(image.pixels, (top + y) * image.width + left, pixels, y * width, width)
        }
        return ArgbPixelImage(width, height, pixels)
    }

    fun cropAround(image: ArgbPixelImage, centerX: Int, centerY: Int, size: Int): ArgbPixelImage {
        val actualWidth = min(size, image.width)
        val actualHeight = min(size, image.height)
        val left = (centerX - actualWidth / 2).coerceIn(0, image.width - actualWidth)
        val top = (centerY - actualHeight / 2).coerceIn(0, image.height - actualHeight)
        return crop(image, left, top, actualWidth, actualHeight)
    }
}
