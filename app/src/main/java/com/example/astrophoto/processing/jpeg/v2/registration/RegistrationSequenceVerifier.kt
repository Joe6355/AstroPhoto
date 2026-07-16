package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bounded verification over the detections produced from analysis-resolution thumbnails. */
class RegistrationSequenceVerifier {
    fun verify(
        reference: TemporalFeatureFrame,
        frames: List<TemporalFeatureFrame>,
        selected: Map<String, TranslationHypothesis>,
        zero: Map<String, TranslationHypothesis>,
        tracks: TemporalTrackAnalysis
    ): RegistrationSequenceVerification {
        val identityTransforms = frames.associate { frame ->
            frame.frameId to TranslationHypothesis(0f, 0f, 0, 0f, 0f, 0, 0, 0)
        }
        val identityMetrics = measure(reference, frames, identityTransforms, tracks)
        val zeroMetrics = measure(reference, frames, zero.ifEmpty { identityTransforms }, tracks)
        val selectedMetrics = measure(reference, frames, selected, tracks)
        val accepted = if (tracks.motionObservable) {
            selectedMetrics.score >= zeroMetrics.score + MIN_VERIFICATION_ADVANTAGE &&
                selectedMetrics.referenceRetention >= MIN_REFERENCE_RETENTION &&
                selectedMetrics.smearRate <= MAX_SMEAR_RATE
        } else {
            selectedMetrics.score + MIN_VERIFICATION_ADVANTAGE >= zeroMetrics.score
        }
        return RegistrationSequenceVerification(
            identity = identityMetrics,
            zeroModel = zeroMetrics,
            selectedModel = selectedMetrics,
            selectedModelAccepted = accepted
        )
    }

    fun measure(
        reference: TemporalFeatureFrame,
        frames: List<TemporalFeatureFrame>,
        transforms: Map<String, TranslationHypothesis>,
        tracks: TemporalTrackAnalysis
    ): RegistrationVerificationMetrics {
        val referenceStars = reference.stars.filter {
            tracks.clusterAt(reference.frameId, it) != TemporalMotionCluster.STATIONARY_CAMERA_SPACE
        }.take(MAX_REFERENCE_STARS)
        if (referenceStars.isEmpty()) return emptyMetrics()
        val requiredFrames = (transforms.size * MIN_FRAME_PRESENCE).toInt().coerceAtLeast(2)
        var retained = 0
        var contrastRatioSum = 0f
        var widthGrowthSum = 0f
        var smearCount = 0
        referenceStars.forEach { referenceStar ->
            val matches = frames.mapNotNull { frame ->
                val transform = transforms[frame.frameId] ?: return@mapNotNull null
                nearest(
                    frame.stars,
                    referenceStar.x + transform.dx,
                    referenceStar.y + transform.dy,
                    MATCH_TOLERANCE
                )?.let { matched ->
                    MatchedObservation(
                        matched,
                        matched.x - referenceStar.x - transform.dx,
                        matched.y - referenceStar.y - transform.dy
                    )
                }
            }
            if (matches.size >= requiredFrames) {
                retained++
                val contrastBefore = referenceStar.localContrast.coerceAtLeast(MIN_DENOMINATOR)
                contrastRatioSum += median(matches.map { it.star.localContrast }) / contrastBefore
                val widthBefore = referenceStar.width.coerceAtLeast(MIN_DENOMINATOR)
                widthGrowthSum += (median(matches.map { it.star.width }) / widthBefore - 1f).coerceAtLeast(0f)
                val spreadX = standardDeviation(matches.map { it.residualX })
                val spreadY = standardDeviation(matches.map { it.residualY })
                val major = maxOf(spreadX, spreadY)
                val minor = minOf(spreadX, spreadY).coerceAtLeast(0.05f)
                if (major > SMEAR_SPREAD_THRESHOLD && major / minor > SMEAR_ANISOTROPY_THRESHOLD) {
                    smearCount++
                }
            }
        }
        val retention = retained.toFloat() / referenceStars.size
        val detectionContrastRatio = if (retained == 0) 0f else contrastRatioSum / retained
        val detectionWidthGrowth = if (retained == 0) 1f else widthGrowthSum / retained
        val detectionSmearRate = if (retained == 0) 1f else smearCount.toFloat() / retained
        val sparseStack = buildSparseSkyStack(referenceStars, frames, transforms, tracks)
        val contrastRatio = minOf(detectionContrastRatio, sparseStack.contrastRatio * 1.10f)
        val widthGrowth = maxOf(detectionWidthGrowth, sparseStack.widthGrowth)
        val smearRate = maxOf(detectionSmearRate, sparseStack.smearRate)
        val stationaryEvidence = transforms.values.sumOf { it.stationaryTrackSupport }.toFloat() /
            transforms.values.sumOf { it.support.coerceAtLeast(0) }.coerceAtLeast(1)
        val score = (
            retention * 0.50f +
                contrastRatio.coerceIn(0f, 1.2f) / 1.2f * 0.20f +
                (1f - widthGrowth).coerceIn(0f, 1f) * 0.15f +
                (1f - smearRate).coerceIn(0f, 1f) * 0.15f -
                stationaryEvidence.coerceIn(0f, 1f) * 0.10f
            ).coerceIn(0f, 1f)
        return RegistrationVerificationMetrics(
            referenceRetention = retention,
            contrastRatio = contrastRatio,
            widthGrowth = widthGrowth,
            smearRate = smearRate,
            reliableStarCount = retained,
            backgroundNoise = sparseStack.backgroundNoise,
            stationaryArtifactStreakEvidence = stationaryEvidence,
            score = score
        )
    }

    private fun nearest(
        stars: List<DetectedStar>,
        x: Float,
        y: Float,
        tolerance: Float
    ): DetectedStar? = stars.asSequence().map { it to hypot(it.x - x, it.y - y) }
        .filter { it.second <= tolerance }
        .minWithOrNull(compareBy<Pair<DetectedStar, Float>> { it.second }.thenBy { it.first.y }.thenBy { it.first.x })
        ?.first

    /**
     * Builds a temporary sparse sky-only thumbnail stack. It uses the same source transform
     * direction as full-resolution sampling and is capped to a 256x256 primitive float plane.
     */
    private fun buildSparseSkyStack(
        referenceStars: List<DetectedStar>,
        frames: List<TemporalFeatureFrame>,
        transforms: Map<String, TranslationHypothesis>,
        tracks: TemporalTrackAnalysis
    ): SparseStackMetrics {
        if (referenceStars.isEmpty() || transforms.isEmpty()) return SparseStackMetrics.empty()
        val sourceWidth = maxOf(
            1f,
            frames.flatMap { it.stars }.maxOfOrNull { it.x } ?: 1f
        )
        val sourceHeight = maxOf(
            1f,
            frames.flatMap { it.stars }.maxOfOrNull { it.y } ?: 1f
        )
        val scale = minOf(1f, MAX_STACK_DIMENSION / maxOf(sourceWidth, sourceHeight))
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, MAX_STACK_DIMENSION.toInt()) + 1
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, MAX_STACK_DIMENSION.toInt()) + 1
        val pixels = FloatArray(width * height)
        var stackedFrames = 0
        frames.forEach { frame ->
            val transform = transforms[frame.frameId] ?: return@forEach
            stackedFrames++
            frame.stars.forEach { star ->
                if (
                    tracks.clusterAt(frame.frameId, star) ==
                    TemporalMotionCluster.STATIONARY_CAMERA_SPACE
                ) return@forEach
                val outputX = ((star.x - transform.dx) * scale).roundToInt()
                val outputY = ((star.y - transform.dy) * scale).roundToInt()
                if (outputX in 0 until width && outputY in 0 until height) {
                    pixels[outputY * width + outputX] +=
                        star.localContrast.coerceAtLeast(0f) * star.confidence.coerceIn(0f, 1f)
                }
            }
        }
        if (stackedFrames == 0) return SparseStackMetrics.empty()
        pixels.indices.forEach { pixels[it] /= stackedFrames }
        val contrastRatios = mutableListOf<Float>()
        var widthGrowthSum = 0f
        var smeared = 0
        referenceStars.forEach { star ->
            val centerX = (star.x * scale).roundToInt().coerceIn(0, width - 1)
            val centerY = (star.y * scale).roundToInt().coerceIn(0, height - 1)
            val expected = star.localContrast.coerceAtLeast(MIN_DENOMINATOR) *
                star.confidence.coerceIn(MIN_DENOMINATOR, 1f)
            contrastRatios += pixels[centerY * width + centerX] / expected
            var energy = 0f
            var momentX = 0f
            var momentY = 0f
            for (offsetY in -STACK_MOMENT_RADIUS..STACK_MOMENT_RADIUS) {
                val y = centerY + offsetY
                if (y !in 0 until height) continue
                for (offsetX in -STACK_MOMENT_RADIUS..STACK_MOMENT_RADIUS) {
                    val x = centerX + offsetX
                    if (x !in 0 until width) continue
                    val value = pixels[y * width + x]
                    energy += value
                    momentX += value * offsetX * offsetX
                    momentY += value * offsetY * offsetY
                }
            }
            if (energy > MIN_DENOMINATOR) {
                val spreadX = sqrt(momentX / energy)
                val spreadY = sqrt(momentY / energy)
                val major = maxOf(spreadX, spreadY)
                val minor = minOf(spreadX, spreadY).coerceAtLeast(0.05f)
                widthGrowthSum += (
                    sqrt(spreadX * spreadX + spreadY * spreadY) /
                        (star.width * scale).coerceAtLeast(0.5f) - 1f
                    ).coerceAtLeast(0f)
                if (
                    major > STACK_SMEAR_SPREAD_THRESHOLD &&
                    major / minor > STACK_SMEAR_ANISOTROPY_THRESHOLD
                ) {
                    smeared++
                }
            }
        }
        val background = pixels.filter { it > 0f }.sorted()
        val backgroundNoise = if (background.size < 2) 0f else {
            val backgroundMedian = median(background)
            median(background.map { abs(it - backgroundMedian) })
        }
        return SparseStackMetrics(
            contrastRatio = median(contrastRatios).coerceAtLeast(0f),
            widthGrowth = widthGrowthSum / referenceStars.size,
            smearRate = smeared.toFloat() / referenceStars.size,
            backgroundNoise = backgroundNoise
        )
    }

    private fun standardDeviation(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        return sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / values.size)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private fun emptyMetrics() = RegistrationVerificationMetrics(
        referenceRetention = 0f,
        contrastRatio = 0f,
        widthGrowth = 1f,
        smearRate = 1f,
        reliableStarCount = 0,
        backgroundNoise = 0f,
        stationaryArtifactStreakEvidence = 1f,
        score = 0f
    )

    private data class MatchedObservation(
        val star: DetectedStar,
        val residualX: Float,
        val residualY: Float
    )

    private data class SparseStackMetrics(
        val contrastRatio: Float,
        val widthGrowth: Float,
        val smearRate: Float,
        val backgroundNoise: Float
    ) {
        companion object {
            fun empty() = SparseStackMetrics(0f, 1f, 1f, 0f)
        }
    }

    companion object {
        private const val MAX_REFERENCE_STARS = 96
        private const val MAX_STACK_DIMENSION = 256f
        private const val STACK_MOMENT_RADIUS = 3
        private const val MATCH_TOLERANCE = 1.6f
        private const val MIN_FRAME_PRESENCE = 0.35f
        private const val MIN_DENOMINATOR = 0.001f
        private const val SMEAR_SPREAD_THRESHOLD = 0.55f
        private const val SMEAR_ANISOTROPY_THRESHOLD = 2.4f
        private const val STACK_SMEAR_SPREAD_THRESHOLD = 1.5f
        private const val STACK_SMEAR_ANISOTROPY_THRESHOLD = 3f
        private const val MIN_VERIFICATION_ADVANTAGE = 0.025f
        private const val MIN_REFERENCE_RETENTION = 0.80f
        private const val MAX_SMEAR_RATE = 0.15f
    }
}
