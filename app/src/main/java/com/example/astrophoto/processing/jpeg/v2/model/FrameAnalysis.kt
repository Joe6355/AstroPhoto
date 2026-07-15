package com.example.astrophoto.processing.jpeg.v2.model

data class FrameAnalysis(
    val id: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val stars: List<DetectedStar>,
    val reliableStarCount: Int,
    val medianStarContrast: Float,
    val medianStarWidth: Float,
    val medianStarEllipticity: Float,
    val backgroundNoise: Float,
    val clippingPercent: Float,
    val exposureSuitability: Float,
    val decodeValid: Boolean,
    val alignmentSuitability: Float,
    val skyMaskConfidence: Float,
    val skyMaskUsedFallback: Boolean
) {
    companion object {
        fun invalid(id: String, fileName: String) = FrameAnalysis(
            id = id,
            fileName = fileName,
            width = 0,
            height = 0,
            stars = emptyList(),
            reliableStarCount = 0,
            medianStarContrast = 0f,
            medianStarWidth = Float.POSITIVE_INFINITY,
            medianStarEllipticity = 1f,
            backgroundNoise = Float.POSITIVE_INFINITY,
            clippingPercent = 100f,
            exposureSuitability = 0f,
            decodeValid = false,
            alignmentSuitability = 0f,
            skyMaskConfidence = 0f,
            skyMaskUsedFallback = true
        )
    }
}
