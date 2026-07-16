package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.model.TransformPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bounded verification over detections produced from analysis-resolution thumbnails. */
class RegistrationSequenceVerifier {
    fun verify(
        reference: TemporalFeatureFrame,
        frames: List<TemporalFeatureFrame>,
        selected: Map<String, TranslationHypothesis>,
        zero: Map<String, TranslationHypothesis>,
        tracks: TemporalTrackAnalysis
    ): RegistrationSequenceVerification {
        val identityTransforms = frames.associate { frame ->
            frame.frameId to ReferenceToSourceTransform.Identity
        }
        val selectedTransforms = selected.mapValues { it.value.referenceToSourceTransform() }
        val zeroTransforms = zero.mapValues { it.value.referenceToSourceTransform() }
            .ifEmpty { identityTransforms }
        val inverseTransforms = selectedTransforms.mapValues {
            it.value.inverse().asReferenceToSourceTransform()
        }
        val doubleTransforms = selectedTransforms.mapValues { it.value.appliedTwice() }
        val identityMetrics = measureCanonical(reference, frames, identityTransforms, tracks)
        val zeroMetrics = measureCanonical(reference, frames, zeroTransforms, tracks)
        val selectedMetrics = measureCanonical(reference, frames, selectedTransforms, tracks)
        val inverseMetrics = measureCanonical(reference, frames, inverseTransforms, tracks)
        val doubleMetrics = measureCanonical(reference, frames, doubleTransforms, tracks)
        val accepted = if (tracks.motionObservable) {
            selectedMetrics.score >= zeroMetrics.score + MIN_VERIFICATION_ADVANTAGE &&
                selectedMetrics.score >= inverseMetrics.score + MIN_VERIFICATION_ADVANTAGE &&
                selectedMetrics.referenceRetention >= MIN_REFERENCE_RETENTION &&
                selectedMetrics.smearRate <= MAX_SMEAR_RATE
        } else {
            selectedMetrics.score + MIN_VERIFICATION_ADVANTAGE >= zeroMetrics.score
        }
        val perFrame = linkedMapOf<String, RegistrationVerificationMetrics>()
        val perFrameAccepted = linkedMapOf<String, Boolean>()
        frames.forEach { frame ->
            val transform = selectedTransforms[frame.frameId] ?: return@forEach
            val metrics = measureCanonical(
                reference,
                listOf(frame),
                mapOf(frame.frameId to transform),
                tracks,
                requiredMatchingFrames = 1
            )
            perFrame[frame.frameId] = metrics
            perFrameAccepted[frame.frameId] = frame.frameId == reference.frameId ||
                isFrameAccepted(metrics)
        }
        return RegistrationSequenceVerification(
            identity = identityMetrics,
            zeroModel = zeroMetrics,
            selectedModel = selectedMetrics,
            selectedModelAccepted = accepted,
            inverseModel = inverseMetrics,
            doubleAppliedModel = doubleMetrics,
            perFrame = perFrame,
            perFrameAccepted = perFrameAccepted
        )
    }

    fun measure(
        reference: TemporalFeatureFrame,
        frames: List<TemporalFeatureFrame>,
        transforms: Map<String, TranslationHypothesis>,
        tracks: TemporalTrackAnalysis
    ): RegistrationVerificationMetrics = measureCanonical(
        reference,
        frames,
        transforms.mapValues { it.value.referenceToSourceTransform() },
        tracks
    )

    fun measureCanonical(
        reference: TemporalFeatureFrame,
        frames: List<TemporalFeatureFrame>,
        transforms: Map<String, ReferenceToSourceTransform>,
        tracks: TemporalTrackAnalysis,
        requiredMatchingFrames: Int? = null
    ): RegistrationVerificationMetrics {
        val referenceStars = verificationReferenceStars(reference, tracks)
        if (referenceStars.isEmpty()) return emptyMetrics()
        val requiredFrames = requiredMatchingFrames ?: (transforms.size * MIN_FRAME_PRESENCE)
            .toInt().coerceAtLeast(2)
        var retained = 0
        var contrastRatioSum = 0f
        var widthGrowthSum = 0f
        var smearCount = 0
        referenceStars.forEach { referenceStar ->
            val matches = frames.mapNotNull { frame ->
                val transform = transforms[frame.frameId] ?: return@mapNotNull null
                val expectedSource = transform.mapOutputToSource(referenceStar.x, referenceStar.y)
                nearest(frame.stars, expectedSource.x, expectedSource.y, MATCH_TOLERANCE)
                    ?.let { matched ->
                        MatchedObservation(
                            matched,
                            matched.x - expectedSource.x,
                            matched.y - expectedSource.y
                        )
                    }
            }
            if (matches.size >= requiredFrames) {
                retained++
                val contrastBefore = referenceStar.localContrast.coerceAtLeast(MIN_DENOMINATOR)
                contrastRatioSum += median(matches.map { it.star.localContrast }) / contrastBefore
                val widthBefore = referenceStar.width.coerceAtLeast(MIN_DENOMINATOR)
                widthGrowthSum += (median(matches.map { it.star.width }) / widthBefore - 1f)
                    .coerceAtLeast(0f)
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
        val score = (
            retention * 0.55f +
                contrastRatio.coerceIn(0f, 1.2f) / 1.2f * 0.20f +
                (1f - widthGrowth).coerceIn(0f, 1f) * 0.10f +
                (1f - smearRate).coerceIn(0f, 1f) * 0.15f
            ).coerceIn(0f, 1f)
        return RegistrationVerificationMetrics(
            referenceRetention = retention,
            contrastRatio = contrastRatio,
            widthGrowth = widthGrowth,
            smearRate = smearRate,
            reliableStarCount = retained,
            backgroundNoise = sparseStack.backgroundNoise,
            stationaryArtifactStreakEvidence = sparseStack.stationaryArtifactEvidence,
            score = score
        )
    }

    private fun verificationReferenceStars(
        reference: TemporalFeatureFrame,
        tracks: TemporalTrackAnalysis
    ): List<DetectedStar> {
        val moving = reference.stars.filter {
            tracks.clusterAt(reference.frameId, it) == TemporalMotionCluster.COHERENT_MOVING_SKY
        }
        val selected = if (tracks.motionObservable && moving.size >= MIN_MOVING_REFERENCE_STARS) {
            moving
        } else {
            reference.stars.filter {
                tracks.clusterAt(reference.frameId, it) !=
                    TemporalMotionCluster.STATIONARY_CAMERA_SPACE
            }
        }
        return selected.take(MAX_REFERENCE_STARS)
    }

    private fun isFrameAccepted(metrics: RegistrationVerificationMetrics): Boolean =
        metrics.reliableStarCount >= MIN_PER_FRAME_STARS &&
            metrics.referenceRetention >= MIN_PER_FRAME_RETENTION &&
            metrics.contrastRatio >= MIN_PER_FRAME_CONTRAST_RATIO &&
            metrics.smearRate <= MAX_PER_FRAME_SMEAR_RATE &&
            metrics.score >= MIN_PER_FRAME_SCORE

    private fun nearest(
        stars: List<DetectedStar>,
        x: Float,
        y: Float,
        tolerance: Float
    ): DetectedStar? = stars.asSequence().map { it to hypot(it.x - x, it.y - y) }
        .filter { it.second <= tolerance }
        .minWithOrNull(
            compareBy<Pair<DetectedStar, Float>> { it.second }
                .thenBy { it.first.y }
                .thenBy { it.first.x }
        )?.first

    private fun buildSparseSkyStack(
        referenceStars: List<DetectedStar>,
        frames: List<TemporalFeatureFrame>,
        transforms: Map<String, ReferenceToSourceTransform>,
        tracks: TemporalTrackAnalysis
    ): SparseStackMetrics {
        if (referenceStars.isEmpty() || transforms.isEmpty()) return SparseStackMetrics.empty()
        val sourceWidth = maxOf(1f, frames.flatMap { it.stars }.maxOfOrNull { it.x } ?: 1f)
        val sourceHeight = maxOf(1f, frames.flatMap { it.stars }.maxOfOrNull { it.y } ?: 1f)
        val scale = minOf(1f, MAX_STACK_DIMENSION / maxOf(sourceWidth, sourceHeight))
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, MAX_STACK_DIMENSION.toInt()) + 1
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, MAX_STACK_DIMENSION.toInt()) + 1
        val pixels = FloatArray(width * height)
        var stackedFrames = 0
        var stationaryIncluded = 0
        var included = 0
        frames.forEach { frame ->
            val transform = transforms[frame.frameId] ?: return@forEach
            stackedFrames++
            frame.stars.forEach { star ->
                val cluster = tracks.clusterAt(frame.frameId, star)
                if (cluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE) {
                    stationaryIncluded++
                    return@forEach
                }
                included++
                val output = transform.inverse().mapSourceToOutput(star.x, star.y)
                splat(pixels, width, height, output, scale, star)
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
            val moment = localMoment(pixels, width, height, centerX, centerY)
            if (moment.energy > MIN_DENOMINATOR) {
                widthGrowthSum += (
                    hypot(moment.spreadX, moment.spreadY) /
                        (star.width * scale).coerceAtLeast(0.5f) - 1f
                    ).coerceAtLeast(0f)
                val major = maxOf(moment.spreadX, moment.spreadY)
                val minor = minOf(moment.spreadX, moment.spreadY).coerceAtLeast(0.05f)
                if (
                    major > STACK_SMEAR_SPREAD_THRESHOLD &&
                    major / minor > STACK_SMEAR_ANISOTROPY_THRESHOLD
                ) smeared++
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
            backgroundNoise = backgroundNoise,
            stationaryArtifactEvidence = stationaryIncluded.toFloat() /
                (stationaryIncluded + included).coerceAtLeast(1)
        )
    }

    private fun splat(
        pixels: FloatArray,
        width: Int,
        height: Int,
        output: TransformPoint,
        coordinateScale: Float,
        star: DetectedStar
    ) {
        val x = (output.x * coordinateScale).roundToInt()
        val y = (output.y * coordinateScale).roundToInt()
        if (x in 0 until width && y in 0 until height) {
            pixels[y * width + x] +=
                star.localContrast.coerceAtLeast(0f) * star.confidence.coerceIn(0f, 1f)
        }
    }

    private fun localMoment(
        pixels: FloatArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int
    ): LocalMoment {
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
        return LocalMoment(
            energy,
            if (energy > 0f) sqrt(momentX / energy) else 0f,
            if (energy > 0f) sqrt(momentY / energy) else 0f
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

    private fun emptyMetrics() = RegistrationVerificationMetrics(0f, 0f, 1f, 1f, 0, 0f, 1f, 0f)

    private data class MatchedObservation(
        val star: DetectedStar,
        val residualX: Float,
        val residualY: Float
    )

    private data class LocalMoment(val energy: Float, val spreadX: Float, val spreadY: Float)

    private data class SparseStackMetrics(
        val contrastRatio: Float,
        val widthGrowth: Float,
        val smearRate: Float,
        val backgroundNoise: Float,
        val stationaryArtifactEvidence: Float
    ) {
        companion object {
            fun empty() = SparseStackMetrics(0f, 1f, 1f, 0f, 1f)
        }
    }

    companion object {
        private const val MAX_REFERENCE_STARS = 96
        private const val MIN_MOVING_REFERENCE_STARS = 4
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
        private const val MIN_PER_FRAME_STARS = 4
        private const val MIN_PER_FRAME_RETENTION = 0.40f
        private const val MIN_PER_FRAME_CONTRAST_RATIO = 0.35f
        private const val MAX_PER_FRAME_SMEAR_RATE = 0.50f
        private const val MIN_PER_FRAME_SCORE = 0.45f
    }
}
