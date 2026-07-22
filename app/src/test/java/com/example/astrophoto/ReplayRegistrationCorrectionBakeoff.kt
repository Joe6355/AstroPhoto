package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.composition.SkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.quality.ResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Frozen before the real replay. This remains test/replay-only. */
internal object ReplayStage1EThresholds {
    const val VERSION = "replay-registration-correction-bakeoff/1"
    const val EXPECTED_MANIFEST_SHA256 = "fe7cd36862c92a4791bb4f470c71b1253e1986f7ee5a53c7ffd9dcbc6a24be59"
    const val MIN_RELIABLE_TRAILS = 4
    const val MODEL_CONDITION_LIMIT = 50.0
    const val MIN_LOO_MEDIAN_IMPROVEMENT = 0.10
    const val LOO_WORSEN_ABSOLUTE_TOLERANCE = 0.05
    const val COMPLEX_MODEL_MIN_ADDITIONAL_IMPROVEMENT = 0.10
    const val OBJECT_SPECIFIC_BLOCKING_FRACTION = 0.50
    const val WEIGHT_RESIDUAL_SCALE_PIXELS = 1.50
    const val MIN_RESIDUAL_WEIGHT_FACTOR = 0.35
    const val TRAIL_SUPPORT_RADIUS = 1.50
    const val TRAIL_BACKGROUND_INNER_RADIUS = 6.0
    const val TRAIL_BACKGROUND_OUTER_RADIUS = 10.0
    const val TRAIL_SIGNAL_MIN_CODES = 1.0
    const val STAR_MIN_CONTRAST_RETENTION = 0.50
    const val STAR_MAX_CENTROID_SHIFT = 1.50
    const val MIN_STAR_BASELINE_VALUE = 1e-8
    const val SUCCESS_TRAIL_ENERGY_REDUCTION = 0.60
    const val MAX_INDIVIDUAL_TRAIL_WORSENING = 0.10
    const val MIN_STAR_RETENTION = 0.95
    const val MAX_MEDIAN_STAR_SHAPE_CHANGE = 0.03
    const val MAX_STAR_SHAPE_CHANGE = 0.05
    const val MAX_BACKGROUND_WORSENING = 0.10
    const val FOREGROUND_ALPHA_LIMIT = 0.001
}

internal enum class ReplayCorrectionModelType { IDENTITY, TRANSLATION, SIMILARITY, AFFINE }

/** Maps a desired candidate output point to the coordinate in the unchanged baseline output. */
internal data class ReplayAffineCorrection(
    val xx: Double,
    val xy: Double,
    val yx: Double,
    val yy: Double,
    val tx: Double,
    val ty: Double,
    val type: ReplayCorrectionModelType
) {
    fun map(point: ReplayPoint): ReplayPoint = ReplayPoint(
        xx * point.x + xy * point.y + tx,
        yx * point.x + yy * point.y + ty
    )

    fun inverseMap(point: ReplayPoint): ReplayPoint? {
        val determinant = xx * yy - xy * yx
        if (!determinant.isFinite() || abs(determinant) <= 1e-10) return null
        val x = point.x - tx
        val y = point.y - ty
        return ReplayPoint(
            (yy * x - xy * y) / determinant,
            (-yx * x + xx * y) / determinant
        )
    }

    companion object {
        val Identity = ReplayAffineCorrection(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, ReplayCorrectionModelType.IDENTITY)
    }
}

internal data class ReplayModelPair(
    val trailId: String,
    val desired: ReplayPoint,
    val observed: ReplayPoint
)

internal data class ReplayModelValidation(
    val frameId: String,
    val captureIndex: Int,
    val type: ReplayCorrectionModelType,
    val reliableTrailCount: Int,
    val baselineHeldOutMedian: Double,
    val correctedHeldOutMedian: Double,
    val correctedToBaselineRatio: Double,
    val worstHeldOutWorsening: Double,
    val conditionNumber: Double,
    val accepted: Boolean,
    val rejectionReasons: List<String>,
    val fullModel: ReplayAffineCorrection?
)

internal data class ReplayCandidatePlan(
    val id: String,
    val weights: Map<String, Float>,
    val corrections: Map<String, ReplayAffineCorrection>,
    val generated: Boolean,
    val generationReason: String,
    val correctedFrameCount: Int,
    val correctedTemporalThirds: Int
)

internal data class ReplayTrailCandidateMetric(
    val trailId: String,
    val energy: Double,
    val pcaLength: Double,
    val outputCentroidRms: Double,
    val outputCentroidSpan: Double,
    val energyRatio: Double
)

internal data class ReplayStarCandidateMetrics(
    val supportCount: Int,
    val retainedCount: Int,
    val retentionFraction: Double,
    val medianWidthChange: Double,
    val maximumWidthChange: Double,
    val medianEllipticityChange: Double,
    val maximumEllipticityChange: Double,
    val maximumCentroidShift: Double
)

internal data class ReplayCandidateMetrics(
    val id: String,
    val generated: Boolean,
    val generationReason: String,
    val trailMetrics: List<ReplayTrailCandidateMetric>,
    val medianTrailEnergyReduction: Double,
    val maximumTrailEnergyWorsening: Double,
    val stars: ReplayStarCandidateMetrics,
    val quality: ResultQualityMetrics?,
    val skyMadRatio: Double,
    val bandingRatio: Double,
    val gradientRatio: Double,
    val foregroundMaximumDifference: Int,
    val suspiciousPointDelta: Int,
    val processingDurationMillis: Long,
    val acceptedFrameCount: Int,
    val effectiveTotalWeight: Double,
    val success: Boolean,
    val rejectionReasons: List<String>,
    val image: ArgbPixelImage?
)

internal data class ReplayStage1EBundle(
    val manifest: String,
    val manifestHash: String,
    val baselinePixelHashBefore: String,
    val baselinePixelHashAfter: String,
    val modelValidations: List<ReplayModelValidation>,
    val plans: List<ReplayCandidatePlan>,
    val candidates: List<ReplayCandidateMetrics>,
    val objectSpecificFraction: Double,
    val correctionModelsBlockedByObjectSpecificEvidence: Boolean
) {
    val baselineUnchanged: Boolean get() = baselinePixelHashBefore == baselinePixelHashAfter
}

internal object ReplayStage1EModelFitter {
    data class Fit(val model: ReplayAffineCorrection, val conditionNumber: Double)

    fun fit(type: ReplayCorrectionModelType, pairs: List<ReplayModelPair>): Fit? = when (type) {
        ReplayCorrectionModelType.TRANSLATION -> fitTranslation(pairs)
        ReplayCorrectionModelType.SIMILARITY -> fitSimilarity(pairs)
        ReplayCorrectionModelType.AFFINE -> fitAffine(pairs)
        ReplayCorrectionModelType.IDENTITY -> Fit(ReplayAffineCorrection.Identity, 1.0)
    }

    fun validate(
        frameId: String,
        captureIndex: Int,
        type: ReplayCorrectionModelType,
        pairs: List<ReplayModelPair>
    ): ReplayModelValidation {
        val reasons = mutableListOf<String>()
        if (pairs.size < ReplayStage1EThresholds.MIN_RELIABLE_TRAILS) reasons += "fewer_than_four_reliable_trails"
        val baselineErrors = mutableListOf<Double>()
        val correctedErrors = mutableListOf<Double>()
        var worstWorsening = Double.NEGATIVE_INFINITY
        var maximumCondition = 1.0
        if (reasons.isEmpty()) {
            pairs.forEach { heldOut ->
                val fit = fit(type, pairs.filterNot { it.trailId == heldOut.trailId })
                if (fit == null) {
                    reasons += "loo_fit_failed:${heldOut.trailId}"
                    return@forEach
                }
                maximumCondition = max(maximumCondition, fit.conditionNumber)
                if (fit.conditionNumber > ReplayStage1EThresholds.MODEL_CONDITION_LIMIT) {
                    reasons += "loo_ill_conditioned:${heldOut.trailId}"
                    return@forEach
                }
                val corrected = fit.model.inverseMap(heldOut.observed)
                if (corrected == null) {
                    reasons += "loo_non_invertible:${heldOut.trailId}"
                    return@forEach
                }
                val baseline = distance(heldOut.observed, heldOut.desired)
                val error = distance(corrected, heldOut.desired)
                baselineErrors += baseline
                correctedErrors += error
                worstWorsening = max(worstWorsening, error - baseline)
                if (error > baseline + ReplayStage1EThresholds.LOO_WORSEN_ABSOLUTE_TOLERANCE) {
                    reasons += "held_out_worsened:${heldOut.trailId}"
                }
            }
        }
        val baselineMedian = median(baselineErrors)
        val correctedMedian = median(correctedErrors)
        val ratio = if (baselineMedian > 1e-9) correctedMedian / baselineMedian else Double.POSITIVE_INFINITY
        if (baselineErrors.isNotEmpty() && ratio > 1.0 - ReplayStage1EThresholds.MIN_LOO_MEDIAN_IMPROVEMENT) {
            reasons += "insufficient_held_out_improvement"
        }
        val full = if (reasons.isEmpty()) fit(type, pairs) else null
        if (full == null && reasons.isEmpty()) reasons += "full_fit_failed"
        if (full != null && full.conditionNumber > ReplayStage1EThresholds.MODEL_CONDITION_LIMIT) reasons += "full_fit_ill_conditioned"
        maximumCondition = max(maximumCondition, full?.conditionNumber ?: 0.0)
        return ReplayModelValidation(
            frameId,
            captureIndex,
            type,
            pairs.size,
            baselineMedian,
            correctedMedian,
            ratio,
            if (worstWorsening.isFinite()) worstWorsening else 0.0,
            maximumCondition,
            reasons.isEmpty(),
            reasons.distinct(),
            full?.model
        )
    }

    private fun fitTranslation(pairs: List<ReplayModelPair>): Fit? {
        if (pairs.isEmpty()) return null
        val dx = pairs.map { it.observed.x - it.desired.x }.average()
        val dy = pairs.map { it.observed.y - it.desired.y }.average()
        return Fit(ReplayAffineCorrection(1.0, 0.0, 0.0, 1.0, dx, dy, ReplayCorrectionModelType.TRANSLATION), 1.0)
    }

    private fun fitSimilarity(pairs: List<ReplayModelPair>): Fit? {
        if (pairs.size < 2) return null
        val qx = pairs.map { it.desired.x }.average()
        val qy = pairs.map { it.desired.y }.average()
        val px = pairs.map { it.observed.x }.average()
        val py = pairs.map { it.observed.y }.average()
        var denominator = 0.0
        var aNumerator = 0.0
        var bNumerator = 0.0
        pairs.forEach { pair ->
            val x = pair.desired.x - qx
            val y = pair.desired.y - qy
            val u = pair.observed.x - px
            val v = pair.observed.y - py
            denominator += x * x + y * y
            aNumerator += x * u + y * v
            bNumerator += x * v - y * u
        }
        if (denominator <= 1e-9) return null
        val condition = spatialCondition(pairs.map { it.desired }) ?: return null
        val a = aNumerator / denominator
        val b = bNumerator / denominator
        if (a * a + b * b <= 1e-10) return null
        val tx = px - a * qx + b * qy
        val ty = py - b * qx - a * qy
        return Fit(ReplayAffineCorrection(a, -b, b, a, tx, ty, ReplayCorrectionModelType.SIMILARITY), condition)
    }

    private fun fitAffine(pairs: List<ReplayModelPair>): Fit? {
        if (pairs.size < 3) return null
        val qx = pairs.map { it.desired.x }.average()
        val qy = pairs.map { it.desired.y }.average()
        val px = pairs.map { it.observed.x }.average()
        val py = pairs.map { it.observed.y }.average()
        var qxx = 0.0
        var qxy = 0.0
        var qyy = 0.0
        var pxxx = 0.0
        var pxxy = 0.0
        var pyxx = 0.0
        var pyxy = 0.0
        pairs.forEach { pair ->
            val x = pair.desired.x - qx
            val y = pair.desired.y - qy
            val u = pair.observed.x - px
            val v = pair.observed.y - py
            qxx += x * x
            qxy += x * y
            qyy += y * y
            pxxx += u * x
            pxxy += u * y
            pyxx += v * x
            pyxy += v * y
        }
        val determinant = qxx * qyy - qxy * qxy
        if (abs(determinant) <= 1e-9) return null
        val condition = spatialCondition(pairs.map { it.desired }) ?: return null
        val inverseXX = qyy / determinant
        val inverseXY = -qxy / determinant
        val inverseYY = qxx / determinant
        val xx = pxxx * inverseXX + pxxy * inverseXY
        val xy = pxxx * inverseXY + pxxy * inverseYY
        val yx = pyxx * inverseXX + pyxy * inverseXY
        val yy = pyxx * inverseXY + pyxy * inverseYY
        val tx = px - xx * qx - xy * qy
        val ty = py - yx * qx - yy * qy
        val model = ReplayAffineCorrection(xx, xy, yx, yy, tx, ty, ReplayCorrectionModelType.AFFINE)
        return if (model.inverseMap(ReplayPoint(px, py)) == null) null else Fit(model, condition)
    }

    private fun spatialCondition(points: List<ReplayPoint>): Double? {
        if (points.size < 2) return null
        val meanX = points.map { it.x }.average()
        val meanY = points.map { it.y }.average()
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        points.forEach {
            val dx = it.x - meanX
            val dy = it.y - meanY
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        val trace = xx + yy
        val root = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4.0 * xy * xy))
        val major = (trace + root) * 0.5
        val minor = (trace - root) * 0.5
        if (minor <= 1e-9) return null
        return sqrt(major / minor)
    }

    private fun distance(first: ReplayPoint, second: ReplayPoint): Double = hypot(first.x - second.x, first.y - second.y)
    private fun median(values: List<Double>): Double = ReplayDefectMath.median(values)
}

internal class ReplayRegistrationCorrectionBakeoffRunner(
    private val sampler: TransformedBitmapSampler = TransformedBitmapSampler(),
    private val qualityAnalyzer: ResultQualityAnalyzer = ResultQualityAnalyzer()
) {
    fun run(
        baseline: ArgbPixelImage,
        normalReferenceWeightCandidate: ArgbPixelImage,
        reference: ArgbPixelImage,
        initialSkyMask: SkyMask,
        effectiveSkyAlpha: AlphaMask,
        annotations: List<ReplayManualTrailAnnotation>,
        morphology: ReplayStage1DBundle,
        frames: List<ReplayProvenanceFrame>,
        confirmedStars: List<DetectedStar>,
        referenceFrameId: String,
        baselineProcessingDurationMillis: Long,
        normalReferenceProcessingDurationMillis: Long,
        outputRoot: Path,
        imageLoader: (ReplayProvenanceFrame) -> ArgbPixelImage
    ): ReplayStage1EBundle {
        require(frames.isNotEmpty())
        require(annotations.map { it.id } == morphology.trails.map { it.annotation.id })
        require(baseline.width == reference.width && baseline.height == reference.height)
        require(initialSkyMask.width == baseline.width && initialSkyMask.height == baseline.height)
        val manifest = manifest()
        val manifestHash = sha256(manifest.toByteArray(StandardCharsets.UTF_8))
        check(manifestHash == ReplayStage1EThresholds.EXPECTED_MANIFEST_SHA256) {
            "Stage 1E frozen threshold manifest changed: $manifestHash"
        }
        val baselineHashBefore = decodedPixelHash(baseline)
        val orderedFrames = frames.sortedBy { it.captureIndex }
        val trackPairs = modelPairs(morphology, orderedFrames)
        val validations = orderedFrames.flatMap { frame ->
            val pairs = trackPairs[frame.id].orEmpty()
            listOf(
                ReplayStage1EModelFitter.validate(frame.id, frame.captureIndex, ReplayCorrectionModelType.TRANSLATION, pairs),
                ReplayStage1EModelFitter.validate(frame.id, frame.captureIndex, ReplayCorrectionModelType.SIMILARITY, pairs),
                ReplayStage1EModelFitter.validate(frame.id, frame.captureIndex, ReplayCorrectionModelType.AFFINE, pairs)
            )
        }
        val commonRelevant = morphology.commonMode.filter { common -> orderedFrames.any { it.captureIndex == common.captureIndex } }
        val objectSpecificFraction = commonRelevant.count { it.status == "object_specific_residual" }.toDouble() /
            commonRelevant.size.coerceAtLeast(1)
        val correctionBlocked = objectSpecificFraction > ReplayStage1EThresholds.OBJECT_SPECIFIC_BLOCKING_FRACTION
        val originalWeights = orderedFrames.associate { it.id to it.normalizedWeight }
        val normalWeight = median(orderedFrames.filter { it.id != referenceFrameId }.map { it.normalizedWeight.toDouble() }).toFloat()
        val plans = listOf(
            ReplayCandidatePlan("baseline", originalWeights, emptyMap(), true, "unchanged_clean_stack", 0, 0),
            ReplayCandidatePlan(
                "reference-009-normal-weight",
                originalWeights + (referenceFrameId to normalWeight),
                emptyMap(),
                true,
                "reference_009_reduced_to_median_normal_weight",
                0,
                0
            ),
            residualWeightPlan(orderedFrames, trackPairs),
            correctionPlan(
                "translation-correction",
                ReplayCorrectionModelType.TRANSLATION,
                orderedFrames,
                originalWeights,
                validations,
                correctionBlocked
            ),
            spatialCorrectionPlan(orderedFrames, originalWeights, validations, correctionBlocked)
        )
        val supports = ReplayFixedStarSupportFactory.create(baseline.width, baseline.height, confirmedStars)
        require(supports.isNotEmpty()) { "No fixed confirmed-star supports for Stage 1E" }
        val baselineStars = ReplayFixedStarMeasurer.measure(baseline, supports)
        val baselineQuality = qualityAnalyzer.analyze(baseline, reference, effectiveSkyAlpha)
        val baselineTrailMetrics = annotations.map { annotation ->
            val shape = outputTrailMetric(annotation, baseline)
            val drift = correctedDrift(morphology.trails.single { it.annotation.id == annotation.id }, emptyMap())
            ReplayTrailCandidateMetric(annotation.id, shape.first, shape.second, drift.first, drift.second, 1.0)
        }
        val candidateMetrics = plans.map { plan ->
            when (plan.id) {
                "baseline" -> evaluate(
                    plan,
                    baseline,
                    baselineProcessingDurationMillis,
                    baseline,
                    reference,
                    effectiveSkyAlpha,
                    annotations,
                    morphology,
                    supports,
                    baselineStars,
                    baselineQuality,
                    baselineTrailMetrics,
                    orderedFrames
                )
                "reference-009-normal-weight" -> evaluate(
                    plan,
                    normalReferenceWeightCandidate,
                    normalReferenceProcessingDurationMillis,
                    baseline,
                    reference,
                    effectiveSkyAlpha,
                    annotations,
                    morphology,
                    supports,
                    baselineStars,
                    baselineQuality,
                    baselineTrailMetrics,
                    orderedFrames
                )
                else -> if (!plan.generated) {
                    ReplayCandidateMetrics(
                        plan.id, false, plan.generationReason, emptyList(), 0.0, 0.0,
                        emptyStarMetrics(supports.size), null, 1.0, 1.0, 1.0, 0, 0,
                        0L, orderedFrames.size, plan.weights.values.sumOf { it.toDouble() },
                        false, listOf(plan.generationReason), null
                    )
                } else {
                    val integrated = integrate(
                        baseline.width,
                        baseline.height,
                        initialSkyMask,
                        effectiveSkyAlpha,
                        reference,
                        orderedFrames,
                        plan.weights,
                        plan.corrections,
                        imageLoader
                    )
                    evaluate(
                        plan,
                        integrated.first,
                        integrated.second,
                        baseline,
                        reference,
                        effectiveSkyAlpha,
                        annotations,
                        morphology,
                        supports,
                        baselineStars,
                        baselineQuality,
                        baselineTrailMetrics,
                        orderedFrames
                    )
                }
            }
        }
        check(decodedPixelHash(baseline) == baselineHashBefore) { "Stage 1E mutated the clean-stack baseline" }
        val bundle = ReplayStage1EBundle(
            manifest,
            manifestHash,
            baselineHashBefore,
            decodedPixelHash(baseline),
            validations,
            plans,
            candidateMetrics,
            objectSpecificFraction,
            correctionBlocked
        )
        writeOutputs(bundle, baseline, annotations, outputRoot)
        return bundle
    }

    private fun modelPairs(
        morphology: ReplayStage1DBundle,
        frames: List<ReplayProvenanceFrame>
    ): Map<String, List<ReplayModelPair>> {
        val usableTrails = morphology.trails.filter {
            !it.searchInconclusive &&
                it.classification in setOf(
                    ReplayTrailMorphologyClass.INTER_FRAME_MISREGISTRATION,
                    ReplayTrailMorphologyClass.MIXED
                )
        }
        val centers = usableTrails.associate { trail ->
            trail.annotation.id to ReplayPoint(
                median(trail.skyTrack.observations.values.mapNotNull { observation ->
                    observation.output?.centroid?.x?.takeIf { observation.reliable && !observation.boundaryContact }
                }),
                median(trail.skyTrack.observations.values.mapNotNull { observation ->
                    observation.output?.centroid?.y?.takeIf { observation.reliable && !observation.boundaryContact }
                })
            )
        }
        return frames.associate { frame ->
            frame.id to usableTrails.mapNotNull { trail ->
                val observation = trail.skyTrack.observations[frame.id]
                    ?.takeIf { it.reliable && !it.boundaryContact }
                    ?.output
                    ?: return@mapNotNull null
                val desired = centers.getValue(trail.annotation.id)
                ReplayModelPair(trail.annotation.id, desired, observation.centroid)
            }
        }
    }

    private fun residualWeightPlan(
        frames: List<ReplayProvenanceFrame>,
        pairs: Map<String, List<ReplayModelPair>>
    ): ReplayCandidatePlan {
        val weights = frames.associate { frame ->
            val evidence = pairs[frame.id].orEmpty()
            val factor = if (evidence.size < ReplayStage1EThresholds.MIN_RELIABLE_TRAILS) {
                1.0
            } else {
                val residual = median(evidence.map { hypot(it.observed.x - it.desired.x, it.observed.y - it.desired.y) })
                (1.0 / (1.0 + residual * residual /
                    (ReplayStage1EThresholds.WEIGHT_RESIDUAL_SCALE_PIXELS * ReplayStage1EThresholds.WEIGHT_RESIDUAL_SCALE_PIXELS)))
                    .coerceIn(ReplayStage1EThresholds.MIN_RESIDUAL_WEIGHT_FACTOR, 1.0)
            }
            frame.id to (frame.normalizedWeight * factor.toFloat())
        }
        return ReplayCandidatePlan(
            "residual-aware-weighting",
            weights,
            emptyMap(),
            true,
            "fixed_residual_weight_curve_without_transform_changes",
            0,
            0
        )
    }

    private fun correctionPlan(
        id: String,
        type: ReplayCorrectionModelType,
        frames: List<ReplayProvenanceFrame>,
        weights: Map<String, Float>,
        validations: List<ReplayModelValidation>,
        objectSpecificBlocked: Boolean
    ): ReplayCandidatePlan {
        if (objectSpecificBlocked) return ReplayCandidatePlan(
            id, weights, emptyMap(), false, "predominantly_object_specific_residuals", 0, 0
        )
        val accepted = validations.filter { it.type == type && it.accepted && it.fullModel != null }
        val corrections = accepted.associate { it.frameId to checkNotNull(it.fullModel) }
        return finalizeCorrectionPlan(id, frames, weights, corrections)
    }

    private fun spatialCorrectionPlan(
        frames: List<ReplayProvenanceFrame>,
        weights: Map<String, Float>,
        validations: List<ReplayModelValidation>,
        objectSpecificBlocked: Boolean
    ): ReplayCandidatePlan {
        if (objectSpecificBlocked) return ReplayCandidatePlan(
            "similarity-affine-correction", weights, emptyMap(), false,
            "predominantly_object_specific_residuals", 0, 0
        )
        val byFrame = validations.groupBy { it.frameId }
        val corrections = frames.mapNotNull { frame ->
            val values = byFrame[frame.id].orEmpty()
            val similarity = values.singleOrNull { it.type == ReplayCorrectionModelType.SIMILARITY && it.accepted }
            val affine = values.singleOrNull { it.type == ReplayCorrectionModelType.AFFINE && it.accepted }
            val chosen = when {
                affine != null && similarity != null &&
                    affine.correctedHeldOutMedian <= similarity.correctedHeldOutMedian *
                    (1.0 - ReplayStage1EThresholds.COMPLEX_MODEL_MIN_ADDITIONAL_IMPROVEMENT) -> affine
                similarity != null -> similarity
                affine != null -> affine
                else -> null
            }
            chosen?.fullModel?.let { frame.id to it }
        }.toMap()
        return finalizeCorrectionPlan("similarity-affine-correction", frames, weights, corrections)
    }

    private fun finalizeCorrectionPlan(
        id: String,
        frames: List<ReplayProvenanceFrame>,
        weights: Map<String, Float>,
        corrections: Map<String, ReplayAffineCorrection>
    ): ReplayCandidatePlan {
        val thirds = corrections.keys.mapNotNull { idValue -> frames.singleOrNull { it.id == idValue } }
            .map { temporalThird(it.captureIndex, frames) }.distinct().size
        val generated = corrections.isNotEmpty()
        return ReplayCandidatePlan(
            id,
            weights,
            corrections,
            generated,
            if (generated) "leave_one_trail_out_validated" else "no_cross_validated_frames",
            corrections.size,
            thirds
        )
    }

    private fun integrate(
        width: Int,
        height: Int,
        initialSkyMask: SkyMask,
        effectiveSkyAlpha: AlphaMask,
        reference: ArgbPixelImage,
        frames: List<ReplayProvenanceFrame>,
        weights: Map<String, Float>,
        corrections: Map<String, ReplayAffineCorrection>,
        imageLoader: (ReplayProvenanceFrame) -> ArgbPixelImage
    ): Pair<ArgbPixelImage, Long> {
        val started = System.nanoTime()
        val pixelCount = width * height
        val red = FloatArray(pixelCount)
        val green = FloatArray(pixelCount)
        val blue = FloatArray(pixelCount)
        val weightSum = FloatArray(pixelCount)
        val included = IntArray(initialSkyMask.copyPixels().count { it })
        var cursor = 0
        for (index in 0 until pixelCount) {
            if (initialSkyMask.contains(index % width, index / width)) included[cursor++] = index
        }
        frames.forEach { frame ->
            val weight = weights.getValue(frame.id)
            require(weight > 0f && weight.isFinite())
            val correction = corrections[frame.id] ?: ReplayAffineCorrection.Identity
            val image = imageLoader(frame)
            require(image.width == width && image.height == height)
            val source = IntArrayPixelSource(width, height, image.pixels)
            included.forEach { index ->
                val output = ReplayPoint((index % width).toDouble(), (index / width).toDouble())
                val baselineOutput = correction.map(output)
                val sourcePoint = ReplayTransformSemantics.outputToSource(
                    frame.transform,
                    baselineOutput.x,
                    baselineOutput.y
                )
                val sample = sampler.sampleAt(source, sourcePoint.x.toFloat(), sourcePoint.y.toFloat())
                    ?: return@forEach
                red[index] += SrgbTransfer.srgbToLinear(sample.red) * weight
                green[index] += SrgbTransfer.srgbToLinear(sample.green) * weight
                blue[index] += SrgbTransfer.srgbToLinear(sample.blue) * weight
                weightSum[index] += weight
            }
        }
        val stack = IntArray(pixelCount) { index ->
            val weight = weightSum[index]
            if (weight <= 0f) OPAQUE_BLACK else linearArgb(red[index] / weight, green[index] / weight, blue[index] / weight)
        }
        val candidate = SkyForegroundComposer().compose(
            ArgbPixelImage(width, height, stack),
            reference,
            effectiveSkyAlpha,
            AlphaMask.full(width, height),
            precomputedEffectiveSkyAlpha = effectiveSkyAlpha
        ).image
        return candidate to ((System.nanoTime() - started) / 1_000_000L)
    }

    private fun evaluate(
        plan: ReplayCandidatePlan,
        image: ArgbPixelImage,
        durationMillis: Long,
        baseline: ArgbPixelImage,
        reference: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        annotations: List<ReplayManualTrailAnnotation>,
        morphology: ReplayStage1DBundle,
        supports: List<ReplayFixedStarSupport>,
        baselineStars: List<ReplayFixedStarMetrics>,
        baselineQuality: ResultQualityMetrics,
        baselineTrails: List<ReplayTrailCandidateMetric>,
        frames: List<ReplayProvenanceFrame>
    ): ReplayCandidateMetrics {
        val trailMetrics = annotations.map { annotation ->
            val shape = outputTrailMetric(annotation, image)
            val drift = correctedDrift(
                morphology.trails.single { it.annotation.id == annotation.id },
                plan.corrections
            )
            val baselineEnergy = baselineTrails.single { it.trailId == annotation.id }.energy
            ReplayTrailCandidateMetric(
                annotation.id,
                shape.first,
                shape.second,
                drift.first,
                drift.second,
                ratio(shape.first, baselineEnergy)
            )
        }
        val explainedIds = morphology.trails.filter {
            it.classification in setOf(
                ReplayTrailMorphologyClass.INTER_FRAME_MISREGISTRATION,
                ReplayTrailMorphologyClass.MIXED
            ) && !it.searchInconclusive
        }.map { it.annotation.id }.toSet()
        val explainedRatios = trailMetrics.filter { it.trailId in explainedIds }.map { it.energyRatio }
        val medianReduction = 1.0 - median(explainedRatios)
        val maximumWorsening = (explainedRatios.maxOrNull() ?: 1.0) - 1.0
        val starValues = starMetrics(baselineStars, ReplayFixedStarMeasurer.measure(image, supports))
        val quality = qualityAnalyzer.analyze(image, reference, effectiveSkyAlpha)
        val skyMadRatio = ratio(quality.skyMad.toDouble(), baselineQuality.skyMad.toDouble())
        val bandingRatio = ratio(
            quality.banding.combinedScore.toDouble(),
            baselineQuality.banding.combinedScore.toDouble()
        )
        val gradientRatio = ratio(quality.gradientResidual.toDouble(), baselineQuality.gradientResidual.toDouble())
        val foregroundMaximum = foregroundMaximumDifference(image, baseline, effectiveSkyAlpha)
        val suspiciousDelta = quality.suspiciousPointCount - baselineQuality.suspiciousPointCount
        val reasons = mutableListOf<String>()
        if (plan.id != "baseline") {
            if (medianReduction < ReplayStage1EThresholds.SUCCESS_TRAIL_ENERGY_REDUCTION) reasons += "trail_energy_reduction_below_60_percent"
            if (maximumWorsening > ReplayStage1EThresholds.MAX_INDIVIDUAL_TRAIL_WORSENING) reasons += "individual_trail_worsened_over_10_percent"
            if (starValues.retentionFraction < ReplayStage1EThresholds.MIN_STAR_RETENTION) reasons += "confirmed_star_retention_below_95_percent"
            if (max(starValues.medianWidthChange, starValues.medianEllipticityChange) >
                ReplayStage1EThresholds.MAX_MEDIAN_STAR_SHAPE_CHANGE
            ) reasons += "median_star_shape_changed_over_3_percent"
            if (max(starValues.maximumWidthChange, starValues.maximumEllipticityChange) >
                ReplayStage1EThresholds.MAX_STAR_SHAPE_CHANGE
            ) reasons += "maximum_star_shape_changed_over_5_percent"
            if (skyMadRatio > 1.0 + ReplayStage1EThresholds.MAX_BACKGROUND_WORSENING) reasons += "sky_mad_worsened_over_10_percent"
            if (bandingRatio > 1.0 + ReplayStage1EThresholds.MAX_BACKGROUND_WORSENING) reasons += "banding_worsened_over_10_percent"
            if (gradientRatio > 1.0 + ReplayStage1EThresholds.MAX_BACKGROUND_WORSENING) reasons += "gradient_worsened_over_10_percent"
            if (foregroundMaximum != 0) reasons += "foreground_changed"
            if (suspiciousDelta > 0) reasons += "new_suspicious_points"
        }
        return ReplayCandidateMetrics(
            plan.id,
            true,
            plan.generationReason,
            trailMetrics,
            medianReduction,
            maximumWorsening,
            starValues,
            quality,
            skyMadRatio,
            bandingRatio,
            gradientRatio,
            foregroundMaximum,
            suspiciousDelta,
            durationMillis,
            frames.size,
            plan.weights.values.sumOf { it.toDouble() },
            reasons.isEmpty(),
            reasons,
            image
        )
    }

    private fun starMetrics(
        baseline: List<ReplayFixedStarMetrics>,
        candidate: List<ReplayFixedStarMetrics>
    ): ReplayStarCandidateMetrics {
        require(baseline.size == candidate.size)
        val retained = baseline.indices.count { index ->
            val first = baseline[index]
            val second = candidate[index]
            val contrastOkay = first.contrast <= ReplayStage1EThresholds.MIN_STAR_BASELINE_VALUE ||
                second.contrast >= first.contrast * ReplayStage1EThresholds.STAR_MIN_CONTRAST_RETENTION
            val centroidOkay = hypot(second.centroidX - first.centroidX, second.centroidY - first.centroidY) <=
                ReplayStage1EThresholds.STAR_MAX_CENTROID_SHIFT
            contrastOkay && centroidOkay
        }
        val widthChanges = baseline.indices.map { index -> relativeChange(baseline[index].width, candidate[index].width) }
        val ellipticityChanges = baseline.indices.map { index ->
            relativeChange(baseline[index].ellipticity, candidate[index].ellipticity)
        }
        val centroidShifts = baseline.indices.map { index ->
            hypot(candidate[index].centroidX - baseline[index].centroidX, candidate[index].centroidY - baseline[index].centroidY)
        }
        return ReplayStarCandidateMetrics(
            baseline.size,
            retained,
            retained.toDouble() / baseline.size.coerceAtLeast(1),
            median(widthChanges),
            widthChanges.maxOrNull() ?: 0.0,
            median(ellipticityChanges),
            ellipticityChanges.maxOrNull() ?: 0.0,
            centroidShifts.maxOrNull() ?: 0.0
        )
    }

    private fun correctedDrift(
        trail: ReplayTrailMorphologyResult,
        corrections: Map<String, ReplayAffineCorrection>
    ): Pair<Double, Double> {
        val points = trail.skyTrack.observations.values.mapNotNull { observation ->
            val output = observation.output?.centroid?.takeIf { observation.reliable && !observation.boundaryContact }
                ?: return@mapNotNull null
            (corrections[observation.frameId] ?: ReplayAffineCorrection.Identity).inverseMap(output)
        }
        if (points.isEmpty()) return 0.0 to 0.0
        val center = ReplayPoint(median(points.map { it.x }), median(points.map { it.y }))
        val rms = sqrt(points.sumOf { (it.x - center.x) * (it.x - center.x) + (it.y - center.y) * (it.y - center.y) } / points.size)
        var span = 0.0
        points.forEachIndexed { index, first ->
            for (second in points.drop(index + 1)) span = max(span, hypot(first.x - second.x, first.y - second.y))
        }
        return rms to span
    }

    private fun outputTrailMetric(annotation: ReplayManualTrailAnnotation, image: ArgbPixelImage): Pair<Double, Double> {
        val line = annotation.centerline
        val left = floor(line.minOf { it.x } - ReplayStage1EThresholds.TRAIL_BACKGROUND_OUTER_RADIUS).toInt().coerceAtLeast(0)
        val right = ceil(line.maxOf { it.x } + ReplayStage1EThresholds.TRAIL_BACKGROUND_OUTER_RADIUS).toInt().coerceAtMost(image.width - 1)
        val top = floor(line.minOf { it.y } - ReplayStage1EThresholds.TRAIL_BACKGROUND_OUTER_RADIUS).toInt().coerceAtLeast(0)
        val bottom = ceil(line.maxOf { it.y } + ReplayStage1EThresholds.TRAIL_BACKGROUND_OUTER_RADIUS).toInt().coerceAtMost(image.height - 1)
        val ring = mutableListOf<Double>()
        for (y in top..bottom) for (x in left..right) {
            val distance = distanceToPolyline(ReplayPoint(x.toDouble(), y.toDouble()), line)
            if (distance in ReplayStage1EThresholds.TRAIL_BACKGROUND_INNER_RADIUS..
                ReplayStage1EThresholds.TRAIL_BACKGROUND_OUTER_RADIUS
            ) ring += encodedLuminance(image.pixels[y * image.width + x])
        }
        val background = median(ring)
        val signalPoints = mutableListOf<ReplayPoint>()
        var energy = 0.0
        for (y in top..bottom) for (x in left..right) {
            if (distanceToPolyline(ReplayPoint(x.toDouble(), y.toDouble()), line) >
                ReplayStage1EThresholds.TRAIL_SUPPORT_RADIUS
            ) continue
            val residual = (encodedLuminance(image.pixels[y * image.width + x]) - background).coerceAtLeast(0.0)
            energy += residual
            if (residual >= ReplayStage1EThresholds.TRAIL_SIGNAL_MIN_CODES) {
                signalPoints += ReplayPoint(x.toDouble(), y.toDouble())
            }
        }
        return energy to ReplayDefectMath.pca(signalPoints).majorAxisLength
    }

    private fun writeOutputs(
        bundle: ReplayStage1EBundle,
        baseline: ArgbPixelImage,
        annotations: List<ReplayManualTrailAnnotation>,
        outputRoot: Path
    ) {
        Files.createDirectories(outputRoot)
        Files.writeString(outputRoot.resolve("frozen-threshold-manifest.json"), bundle.manifest)
        Files.writeString(outputRoot.resolve("model-validation.csv"), buildString {
            appendLine("frameId,captureIndex,model,reliableTrails,baselineHeldOutMedian,correctedHeldOutMedian,ratio,worstWorsening,condition,accepted,reasons")
            bundle.modelValidations.forEach { value ->
                appendLine(listOf(
                    value.frameId,
                    value.captureIndex,
                    value.type,
                    value.reliableTrailCount,
                    value.baselineHeldOutMedian,
                    value.correctedHeldOutMedian,
                    value.correctedToBaselineRatio,
                    value.worstHeldOutWorsening,
                    value.conditionNumber,
                    value.accepted,
                    value.rejectionReasons.joinToString("|")
                ).joinToString(","))
            }
        })
        Files.writeString(outputRoot.resolve("report.txt"), report(bundle))
        writePng(outputRoot.resolve("baseline-clean-stack.png"), baseline)
        bundle.candidates.filter { it.generated && it.id != "baseline" }.forEach { candidate ->
            val image = candidate.image ?: return@forEach
            writePng(outputRoot.resolve("${candidate.id}.png"), image)
            writeDifferencePng(outputRoot.resolve("${candidate.id}-difference-x8.png"), baseline, image)
            writeTrailCrops(outputRoot.resolve("crops").resolve(candidate.id), baseline, image, annotations)
        }
    }

    private fun report(bundle: ReplayStage1EBundle): String = buildString {
        appendLine("mode=replay_only")
        appendLine("productionCodeChanged=false")
        appendLine("toneGainApplied=false")
        appendLine("baselinePixelHashBefore=${bundle.baselinePixelHashBefore}")
        appendLine("baselinePixelHashAfter=${bundle.baselinePixelHashAfter}")
        appendLine("baselineUnchanged=${bundle.baselineUnchanged}")
        appendLine("manifestSha256=${bundle.manifestHash}")
        appendLine("objectSpecificFraction=${bundle.objectSpecificFraction}")
        appendLine("correctionModelsBlockedByObjectSpecificEvidence=${bundle.correctionModelsBlockedByObjectSpecificEvidence}")
        bundle.plans.forEach { plan ->
            appendLine("plan.${plan.id}.generated=${plan.generated}")
            appendLine("plan.${plan.id}.reason=${plan.generationReason}")
            appendLine("plan.${plan.id}.correctedFrames=${plan.correctedFrameCount}")
            appendLine("plan.${plan.id}.correctedTemporalThirds=${plan.correctedTemporalThirds}")
            appendLine("plan.${plan.id}.effectiveTotalWeight=${plan.weights.values.sumOf { it.toDouble() }}")
        }
        bundle.candidates.forEach { candidate ->
            val prefix = "candidate.${candidate.id}"
            appendLine("$prefix.generated=${candidate.generated}")
            appendLine("$prefix.reason=${candidate.generationReason}")
            appendLine("$prefix.success=${candidate.success}")
            appendLine("$prefix.rejections=${candidate.rejectionReasons.joinToString("|")}")
            appendLine("$prefix.medianTrailEnergyReduction=${candidate.medianTrailEnergyReduction}")
            appendLine("$prefix.maximumTrailEnergyWorsening=${candidate.maximumTrailEnergyWorsening}")
            appendLine("$prefix.starRetention=${candidate.stars.retentionFraction}")
            appendLine("$prefix.medianStarWidthChange=${candidate.stars.medianWidthChange}")
            appendLine("$prefix.maximumStarWidthChange=${candidate.stars.maximumWidthChange}")
            appendLine("$prefix.medianStarEllipticityChange=${candidate.stars.medianEllipticityChange}")
            appendLine("$prefix.maximumStarEllipticityChange=${candidate.stars.maximumEllipticityChange}")
            appendLine("$prefix.maximumStarCentroidShift=${candidate.stars.maximumCentroidShift}")
            appendLine("$prefix.skyMadRatio=${candidate.skyMadRatio}")
            appendLine("$prefix.bandingRatio=${candidate.bandingRatio}")
            appendLine("$prefix.gradientRatio=${candidate.gradientRatio}")
            appendLine("$prefix.foregroundMaximumDifference=${candidate.foregroundMaximumDifference}")
            candidate.quality?.let { quality ->
                appendLine("$prefix.foregroundSharpness=${quality.foregroundSharpness}")
                appendLine("$prefix.foregroundEdgeDifference=${quality.foregroundEdgeDifference}")
                appendLine("$prefix.foregroundMeanPixelDifference=${quality.foregroundMeanPixelDifference}")
                appendLine("$prefix.foregroundAnalyzerMaximumPixelDifference=${quality.foregroundMaximumPixelDifference}")
            }
            appendLine("$prefix.suspiciousPointDelta=${candidate.suspiciousPointDelta}")
            appendLine("$prefix.processingDurationMillis=${candidate.processingDurationMillis}")
            appendLine("$prefix.acceptedFrameCount=${candidate.acceptedFrameCount}")
            appendLine("$prefix.effectiveTotalWeight=${candidate.effectiveTotalWeight}")
            candidate.trailMetrics.forEach { trail ->
                appendLine("$prefix.trail.${trail.trailId}.energy=${trail.energy}")
                appendLine("$prefix.trail.${trail.trailId}.energyRatio=${trail.energyRatio}")
                appendLine("$prefix.trail.${trail.trailId}.length=${trail.pcaLength}")
                appendLine("$prefix.trail.${trail.trailId}.centroidRms=${trail.outputCentroidRms}")
                appendLine("$prefix.trail.${trail.trailId}.centroidSpan=${trail.outputCentroidSpan}")
            }
        }
    }

    private fun writeTrailCrops(
        root: Path,
        baseline: ArgbPixelImage,
        candidate: ArgbPixelImage,
        annotations: List<ReplayManualTrailAnnotation>
    ) {
        Files.createDirectories(root)
        annotations.forEach { annotation ->
            val left = floor(annotation.centerline.minOf { it.x } - 12).toInt().coerceAtLeast(0)
            val right = ceil(annotation.centerline.maxOf { it.x } + 12).toInt().coerceAtMost(baseline.width - 1)
            val top = floor(annotation.centerline.minOf { it.y } - 12).toInt().coerceAtLeast(0)
            val bottom = ceil(annotation.centerline.maxOf { it.y } + 12).toInt().coerceAtMost(baseline.height - 1)
            writePng(root.resolve("${annotation.id}-baseline.png"), crop(baseline, left, top, right - left + 1, bottom - top + 1))
            writePng(root.resolve("${annotation.id}-candidate.png"), crop(candidate, left, top, right - left + 1, bottom - top + 1))
        }
    }

    private fun writeDifferencePng(path: Path, baseline: ArgbPixelImage, candidate: ArgbPixelImage) {
        val pixels = IntArray(baseline.pixels.size) { index ->
            val first = baseline.pixels[index]
            val second = candidate.pixels[index]
            fun channel(shift: Int) = (128 + (((second ushr shift) and 0xFF) - ((first ushr shift) and 0xFF)) * 8).coerceIn(0, 255)
            OPAQUE_ALPHA or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        writePng(path, ArgbPixelImage(baseline.width, baseline.height, pixels))
    }

    private fun writePng(path: Path, image: ArgbPixelImage) {
        Files.createDirectories(path.parent)
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        output.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        check(ImageIO.write(output, "png", path.toFile()))
        output.flush()
    }

    private fun crop(image: ArgbPixelImage, left: Int, top: Int, width: Int, height: Int): ArgbPixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(image.pixels, (top + y) * image.width + left, pixels, y * width, width)
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun foregroundMaximumDifference(
        candidate: ArgbPixelImage,
        baseline: ArgbPixelImage,
        alpha: AlphaMask
    ): Int {
        var maximum = 0
        candidate.pixels.indices.forEach { index ->
            val x = index % candidate.width
            val y = index / candidate.width
            if (alpha.alphaAt(x, y) > ReplayStage1EThresholds.FOREGROUND_ALPHA_LIMIT) return@forEach
            val first = candidate.pixels[index]
            val second = baseline.pixels[index]
            maximum = max(
                maximum,
                max(
                    abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
                    max(
                        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
                        abs((first and 0xFF) - (second and 0xFF))
                    )
                )
            )
        }
        return maximum
    }

    private fun emptyStarMetrics(count: Int) = ReplayStarCandidateMetrics(count, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun relativeChange(first: Double, second: Double): Double =
        if (abs(first) <= ReplayStage1EThresholds.MIN_STAR_BASELINE_VALUE) 0.0 else abs(second / first - 1.0)

    private fun ratio(value: Double, baseline: Double): Double =
        if (abs(baseline) <= 1e-12) if (abs(value) <= 1e-12) 1.0 else Double.POSITIVE_INFINITY else value / baseline

    private fun temporalThird(captureIndex: Int, frames: List<ReplayProvenanceFrame>): Int {
        val ordered = frames.sortedBy { it.captureIndex }
        val position = ordered.indexOfFirst { it.captureIndex == captureIndex }.coerceAtLeast(0)
        return min(2, position * 3 / ordered.size.coerceAtLeast(1))
    }

    private fun distanceToPolyline(point: ReplayPoint, line: List<ReplayPoint>): Double {
        if (line.size == 1) return hypot(point.x - line[0].x, point.y - line[0].y)
        return line.zipWithNext().minOf { (first, second) ->
            val dx = second.x - first.x
            val dy = second.y - first.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= 1e-12) hypot(point.x - first.x, point.y - first.y) else {
                val t = (((point.x - first.x) * dx + (point.y - first.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
                hypot(point.x - first.x - t * dx, point.y - first.y - t * dy)
            }
        }
    }

    private fun encodedLuminance(argb: Int): Double =
        0.2126 * (argb ushr 16 and 0xFF) + 0.7152 * (argb ushr 8 and 0xFF) + 0.0722 * (argb and 0xFF)

    private fun linearArgb(red: Float, green: Float, blue: Float): Int {
        fun encode(value: Float): Int = (SrgbTransfer.linearToSrgb(value.coerceAtLeast(0f)) * 255f)
            .roundToInt().coerceIn(0, 255)
        return OPAQUE_ALPHA or (encode(red) shl 16) or (encode(green) shl 8) or encode(blue)
    }

    private fun median(values: List<Double>): Double = ReplayDefectMath.median(values)

    private fun decodedPixelHash(image: ArgbPixelImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        image.pixels.forEach { pixel ->
            digest.update((pixel ushr 24).toByte())
            digest.update((pixel ushr 16).toByte())
            digest.update((pixel ushr 8).toByte())
            digest.update(pixel.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(): String = """{"background.maxWorsening":0.1,"candidate.affineSelectionAdditionalImprovement":0.1,"candidate.generation":"one_or_more_cross_validated_frames","foreground.alphaLimit":0.001,"integration.allowRobustClipping":false,"integration.correctionDirection":"candidate-output-to-baseline-output","model.conditionLimit":50.0,"model.leaveOneTrailOut":true,"model.looAbsoluteWorseningTolerance":0.05,"model.minHeldOutMedianImprovement":0.1,"model.minReliableTrails":4,"model.types":["translation","similarity","affine"],"objectSpecific.blockingFractionExclusive":0.5,"star.maxCentroidShift":1.5,"star.maxIndividualShapeChange":0.05,"star.maxMedianShapeChange":0.03,"star.minContrastRetention":0.5,"star.minRetention":0.95,"toneGainApplied":false,"trail.backgroundInnerRadius":6.0,"trail.backgroundOuterRadius":10.0,"trail.maxIndividualWorsening":0.1,"trail.minEnergyReduction":0.6,"trail.signalMinCodes":1.0,"trail.supportRadius":1.5,"version":"${ReplayStage1EThresholds.VERSION}","weight.minFactor":0.35,"weight.residualScalePixels":1.5}"""

    companion object {
        private const val OPAQUE_ALPHA = 0xFF000000.toInt()
        private const val OPAQUE_BLACK = OPAQUE_ALPHA
    }
}
