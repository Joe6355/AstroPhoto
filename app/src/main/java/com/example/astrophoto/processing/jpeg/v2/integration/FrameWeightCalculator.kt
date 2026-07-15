package com.example.astrophoto.processing.jpeg.v2.integration

import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.example.astrophoto.processing.jpeg.v2.model.FrameWeight
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult

data class FrameWeightInput(
    val analysis: FrameAnalysis,
    val registration: RegistrationResult,
    val isReference: Boolean = false
)

class FrameWeightCalculator {
    fun calculate(inputs: List<FrameWeightInput>): List<FrameWeight> {
        val accepted = inputs.filter { it.registration.isReliable && it.analysis.decodeValid }
        if (accepted.isEmpty()) return emptyList()
        val reference = accepted.firstOrNull { it.isReference } ?: accepted.first()
        val medianNoise = median(
            accepted.map { it.analysis.backgroundNoise }.filter { it.isFinite() && it > 0f }
        ).coerceAtLeast(0.5f)
        val raw = accepted.map { input ->
            val analysis = input.analysis
            val registration = input.registration
            val inlierRatio = registration.inlierStars.toFloat() /
                registration.matchedStars.coerceAtLeast(1)
            val residual = registration.residualError.takeIf { it.isFinite() } ?: 100f
            val residualPenalty = 1f / (1f + square(residual / RESIDUAL_SCALE))
            val registrationWeight = (
                square(registration.confidence.coerceIn(0f, 1f)) *
                    inlierRatio.coerceIn(0f, 1f) * residualPenalty
                ).coerceIn(MIN_COMPONENT_WEIGHT, 1f)
            val referenceWidth = reference.analysis.medianStarWidth
            val sharpnessWeight = if (
                referenceWidth.isFinite() && referenceWidth > 0f &&
                analysis.medianStarWidth.isFinite() && analysis.medianStarWidth > 0f
            ) {
                (referenceWidth / analysis.medianStarWidth).coerceIn(
                    MIN_SHARPNESS_WEIGHT,
                    MAX_SHARPNESS_WEIGHT
                )
            } else {
                MIN_SHARPNESS_WEIGHT
            }
            val trailWeight = (1f - analysis.medianStarEllipticity.coerceIn(0f, 1f) * 0.8f)
                .coerceIn(MIN_TRAIL_WEIGHT, 1f)
            val noiseWeight = if (analysis.backgroundNoise.isFinite() && analysis.backgroundNoise > 0f) {
                (medianNoise / analysis.backgroundNoise).coerceIn(MIN_NOISE_WEIGHT, MAX_NOISE_WEIGHT)
            } else {
                MIN_NOISE_WEIGHT
            }
            val clippingPenalty = (1f - analysis.clippingPercent / 12f).coerceIn(0f, 1f)
            val exposureWeight = (analysis.exposureSuitability * clippingPenalty)
                .coerceIn(MIN_EXPOSURE_WEIGHT, 1f)
            val rawWeight = (
                registrationWeight * sharpnessWeight * trailWeight * noiseWeight * exposureWeight
                ).coerceIn(MIN_RAW_WEIGHT, MAX_RAW_WEIGHT)
            RawFrameWeight(
                input = input,
                registrationWeight = registrationWeight,
                sharpnessWeight = sharpnessWeight,
                trailWeight = trailWeight,
                noiseWeight = noiseWeight,
                exposureWeight = exposureWeight,
                rawWeight = rawWeight
            )
        }
        val normalization = raw.map { it.rawWeight }.average().toFloat().coerceAtLeast(MIN_RAW_WEIGHT)
        return raw.map { value ->
            FrameWeight(
                frameId = value.input.analysis.id,
                registrationWeight = value.registrationWeight,
                sharpnessWeight = value.sharpnessWeight,
                trailWeight = value.trailWeight,
                noiseWeight = value.noiseWeight,
                exposureWeight = value.exposureWeight,
                rawWeight = value.rawWeight,
                normalizedWeight = (value.rawWeight / normalization).coerceIn(
                    MIN_NORMALIZED_WEIGHT,
                    MAX_NORMALIZED_WEIGHT
                )
            )
        }
    }

    private data class RawFrameWeight(
        val input: FrameWeightInput,
        val registrationWeight: Float,
        val sharpnessWeight: Float,
        val trailWeight: Float,
        val noiseWeight: Float,
        val exposureWeight: Float,
        val rawWeight: Float
    )

    private fun square(value: Float): Float = value * value

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 1f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    companion object {
        const val MIN_NORMALIZED_WEIGHT = 0.35f
        const val MAX_NORMALIZED_WEIGHT = 1.75f
        private const val MIN_RAW_WEIGHT = 0.05f
        private const val MAX_RAW_WEIGHT = 1.5f
        private const val MIN_COMPONENT_WEIGHT = 0.05f
        private const val RESIDUAL_SCALE = 1.5f
        private const val MIN_SHARPNESS_WEIGHT = 0.60f
        private const val MAX_SHARPNESS_WEIGHT = 1.25f
        private const val MIN_TRAIL_WEIGHT = 0.45f
        private const val MIN_NOISE_WEIGHT = 0.50f
        private const val MAX_NOISE_WEIGHT = 1.25f
        private const val MIN_EXPOSURE_WEIGHT = 0.30f
    }
}
