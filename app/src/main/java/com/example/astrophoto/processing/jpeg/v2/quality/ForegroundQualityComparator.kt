package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.processing.jpeg.v2.model.QualityComparison
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import kotlin.math.abs

class ForegroundQualityComparator {
    fun compare(
        reference: ResultQualityMetrics,
        candidate: ResultQualityMetrics
    ): QualityComparison {
        val hard = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (candidate.width != reference.width || candidate.height != reference.height) {
            hard += "dimensions_changed_or_downscaled"
        }
        if (abs(candidate.aspectRatio - reference.aspectRatio) > ASPECT_RATIO_TOLERANCE) {
            hard += "aspect_ratio_changed"
        }
        if (candidate.retainedValidAreaRatio < MIN_RETAINED_VALID_AREA) {
            hard += "valid_area_lost_or_crop_detected"
        }
        if (candidate.invalidBorderRatio > MAX_INVALID_BORDER_RATIO) {
            hard += "invalid_transformed_border_detected"
        }
        if (candidate.blackBorderRatio > reference.blackBorderRatio + MAX_ADDED_BLACK_BORDER_RATIO) {
            hard += "new_black_border_detected"
        }
        if (candidate.foregroundMaximumPixelDifference > MAX_FOREGROUND_CHANNEL_DIFFERENCE ||
            candidate.foregroundMeanPixelDifference > MAX_FOREGROUND_MEAN_DIFFERENCE
        ) {
            hard += "protected_foreground_pixels_changed"
        }
        if (reference.foregroundSharpness > 0f && candidate.foregroundSharpness <
            reference.foregroundSharpness * MIN_FOREGROUND_SHARPNESS_FRACTION
        ) {
            hard += "foreground_blurred_or_wire_broken"
        }
        if (candidate.foregroundEdgeDifference > MAX_FOREGROUND_EDGE_DIFFERENCE) {
            hard += "foreground_double_edge_or_wire_discontinuity"
        } else if (candidate.foregroundEdgeDifference > WARNING_FOREGROUND_EDGE_DIFFERENCE) {
            warnings += "foreground_edge_response_changed"
        }
        return QualityComparison(hard.distinct(), warnings.distinct())
    }

    companion object {
        private const val ASPECT_RATIO_TOLERANCE = 0.000001
        private const val MIN_RETAINED_VALID_AREA = 0.9999f
        private const val MAX_INVALID_BORDER_RATIO = 0f
        private const val MAX_ADDED_BLACK_BORDER_RATIO = 0.002f
        private const val MAX_FOREGROUND_CHANNEL_DIFFERENCE = 1
        private const val MAX_FOREGROUND_MEAN_DIFFERENCE = 0.10f
        private const val MIN_FOREGROUND_SHARPNESS_FRACTION = 0.98f
        private const val MAX_FOREGROUND_EDGE_DIFFERENCE = 1.0f
        private const val WARNING_FOREGROUND_EDGE_DIFFERENCE = 0.25f
    }
}
