package com.example.astrophoto.processing.jpeg.v2.model

enum class IntegrationMode {
    LINEAR_WEIGHTED_AVERAGE,
    LINEAR_WEIGHTED_REPEATABILITY_ROBUST
}

data class IntegrationDiagnostics(
    val outputWidth: Int,
    val outputHeight: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val acceptedFrames: Int,
    val mode: IntegrationMode,
    val robustModeEnabled: Boolean,
    val validCoveragePercent: Float,
    val minimumAccumulatedWeight: Float,
    val maximumAccumulatedWeight: Float,
    val processingDurationMillis: Long,
    val estimatedPeakWorkingMemoryBytes: Long,
    val resolutionChanged: Boolean,
    val robustModeReason: String = if (robustModeEnabled) {
        "legacy_repeatability_mode"
    } else {
        "plain_weighted_average"
    }
)
