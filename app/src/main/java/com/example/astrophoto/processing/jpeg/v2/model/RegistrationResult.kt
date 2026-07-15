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
    val referenceStars: Int = 0,
    val registrationModel: String = "LEGACY_SIMILARITY",
    val scaleFixed: Boolean = false,
    val rotationAllowed: Boolean = false,
    val rotationRejectionReason: String? = null,
    val occupiedDistributionCells: Int = 0,
    val horizontalDistributionSpan: Float = 0f,
    val verticalDistributionSpan: Float = 0f,
    val spatialDistributionScore: Float = 0f,
    val transformRetryUsed: Boolean = false,
    val transformSequenceScore: Float = 1f,
    val transformSequenceDeviation: Float = 0f,
    val neighborTransformDelta: Float = 0f,
    val rawDx: Float = dx,
    val rawDy: Float = dy,
    val rawRotationRadians: Float = rotationRadians
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
            referenceStars = referenceStars,
            registrationModel = "REJECTED",
            scaleFixed = true
        )
    }
}
