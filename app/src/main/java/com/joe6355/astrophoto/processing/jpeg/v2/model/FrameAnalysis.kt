package com.joe6355.astrophoto.processing.jpeg.v2.model

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
    val skyMaskUsedFallback: Boolean,
    val backgroundLevel: Float = Float.NaN
) {
    val medianStarSnr: Float
        get() = if (medianStarContrast.isFinite() && backgroundNoise.isFinite() && backgroundNoise > 0f) {
            medianStarContrast / backgroundNoise
        } else {
            0f
        }

    val hardInvalidReason: String?
        get() = when {
            !decodeValid || width <= 0 || height <= 0 -> "unreadable"
            clippingPercent.isFinite() && clippingPercent >= CRITICAL_CLIPPING_PERCENT -> "critical_clipping"
            backgroundLevel.isFinite() && backgroundLevel < BLACK_LEVEL && reliableStarCount == 0 -> "black"
            !backgroundNoise.isFinite() || !clippingPercent.isFinite() -> "invalid_metrics"
            else -> null
        }

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
            skyMaskUsedFallback = true,
            backgroundLevel = 0f
        )

        private const val CRITICAL_CLIPPING_PERCENT = 35f
        private const val BLACK_LEVEL = 1f
    }
}
