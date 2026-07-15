package com.example.astrophoto.processing.jpeg.v2.model

import kotlin.math.cos
import kotlin.math.sin

data class RegistrationResult(
    val dx: Float,
    val dy: Float,
    val rotationRadians: Float,
    val scale: Float,
    val detectedStars: Int,
    val matchedStars: Int,
    val inlierStars: Int,
    val residualError: Float,
    val confidence: Float,
    val isReliable: Boolean,
    val rejectionReason: String?,
    val referenceStars: Int = 0
) {
    fun transform(x: Float, y: Float): Pair<Float, Float> {
        val cosine = cos(rotationRadians)
        val sine = sin(rotationRadians)
        return (
            scale * (cosine * x - sine * y) + dx
            ) to (
            scale * (sine * x + cosine * y) + dy
            )
    }

    companion object {
        fun rejected(
            referenceStars: Int,
            detectedStars: Int,
            reason: String,
            matchedStars: Int = 0,
            inlierStars: Int = 0,
            residualError: Float = Float.POSITIVE_INFINITY,
            confidence: Float = 0f
        ) = RegistrationResult(
            dx = 0f,
            dy = 0f,
            rotationRadians = 0f,
            scale = 1f,
            detectedStars = detectedStars,
            matchedStars = matchedStars,
            inlierStars = inlierStars,
            residualError = residualError,
            confidence = confidence,
            isReliable = false,
            rejectionReason = reason,
            referenceStars = referenceStars
        )
    }
}
