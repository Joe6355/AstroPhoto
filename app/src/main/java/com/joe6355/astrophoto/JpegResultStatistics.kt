package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult

data class JpegStackResult(
    val fileName: String,
    val displayPath: String,
    val contentUri: String?,
    val filePath: String?,
    val frameCount: Int,
    val sessionInfoUpdated: Boolean,
    val darkFrameCount: Int = 0,
    val shadowOffset: Int? = null,
    val masterDarkFileName: String? = null,
    val masterDarkDisplayPath: String? = null,
    val alignmentEnabled: Boolean = false,
    val astroStretchApplied: Boolean = false,
    val downscaled: Boolean = false,
    val profile: AstroProcessingProfile? = null,
    val selectedResultType: String? = null,
    val starsBefore: Int? = null,
    val starCount: Int? = null,
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null,
    val warnings: List<String> = emptyList(),
    val additionalFiles: List<String> = emptyList(),
    val processingRunId: String? = null,
    val processingOutcome: JpegProfileProcessingOutcome? = null,
    val postProcessingExecuted: Boolean = false,
    val manualAlignmentSummary: String? = null,
    val statistics: JpegResultStatistics? = null
)

internal fun selectUserFacingProfileOutput(
    safeBase: SavedProcessedImage,
    enhanced: SavedProcessedImage?
): Pair<SavedProcessedImage, String?> =
    if (enhanced != null) enhanced to safeBase.fileName else safeBase to null

data class JpegResultStatistics(
    val inputFrames: Int,
    val usedFrames: Int,
    val droppedFrames: Int,
    val totalExposureNs: Long?,
    val medianFwhm: Float?,
    val meanAlignmentConfidence: Float?,
    val blurredFrames: Int,
    val clippedFrames: Int
)

internal object JpegResultStatisticsCalculator {
    fun calculate(
        sessionInfo: String,
        inputFrames: Int,
        analyses: List<FrameAnalysis>,
        registrations: List<RegistrationResult>
    ): JpegResultStatistics {
        val widths = analyses.mapNotNull { it.medianStarWidth.takeIf(Float::isFinite) }.sorted()
        val medianFwhm = median(widths)
        val exposurePerFrame = Regex("(?m)^exposureTimeNs:\\s*(\\d+)\\s*$")
            .find(sessionInfo)?.groupValues?.get(1)?.toLongOrNull()
        return JpegResultStatistics(
            inputFrames = inputFrames,
            usedFrames = analyses.size,
            droppedFrames = (inputFrames - analyses.size).coerceAtLeast(0),
            totalExposureNs = exposurePerFrame?.let { exposure ->
                runCatching { Math.multiplyExact(exposure, analyses.size.toLong()) }.getOrNull()
            },
            medianFwhm = medianFwhm,
            meanAlignmentConfidence = registrations.map { it.confidence }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            blurredFrames = medianFwhm?.let { median ->
                analyses.count { it.medianStarWidth.isFinite() && it.medianStarWidth > maxOf(3f, median * 1.35f) }
            } ?: 0,
            clippedFrames = analyses.count { it.clippingPercent >= NOTICEABLE_CLIPPING_PERCENT }
        )
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) * 0.5f
    }

    private const val NOTICEABLE_CLIPPING_PERCENT = 5f
}

internal fun formatTotalExposure(exposureNs: Long): String {
    val seconds = exposureNs / 1_000_000_000.0
    return if (seconds >= 60.0) {
        String.format(java.util.Locale.getDefault(), "%.1f мин", seconds / 60.0)
    } else {
        String.format(java.util.Locale.getDefault(), "%.1f с", seconds)
    }
}
