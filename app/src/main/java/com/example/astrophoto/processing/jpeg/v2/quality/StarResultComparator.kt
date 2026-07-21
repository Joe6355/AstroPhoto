package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.AstroProcessingProfile
import com.example.astrophoto.processing.jpeg.v2.model.QualityComparison
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics

class StarResultComparator {
    fun compare(
        baseline: ResultQualityMetrics,
        candidate: ResultQualityMetrics,
        profile: AstroProcessingProfile,
        matchedStarsValidated: Boolean = false
    ): QualityComparison {
        val hard = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (!matchedStarsValidated && baseline.reliableStarCount >= MIN_STARS_FOR_RATIO) {
            if (candidate.reliableStarCount < baseline.reliableStarCount * HARD_MIN_STAR_FRACTION) {
                hard += "star_count_drop_gt_20_percent"
            } else if (candidate.reliableStarCount < baseline.reliableStarCount * WARNING_MIN_STAR_FRACTION) {
                warnings += "star_count_reduced"
            }
        }
        if (!matchedStarsValidated && baseline.medianStarLocalContrast > 0f) {
            if (candidate.medianStarLocalContrast <
                baseline.medianStarLocalContrast * HARD_MIN_CONTRAST_FRACTION
            ) {
                hard += "median_star_contrast_drop_gt_15_percent"
            } else if (candidate.medianStarLocalContrast <
                baseline.medianStarLocalContrast * WARNING_MIN_CONTRAST_FRACTION
            ) {
                warnings += "median_star_contrast_reduced"
            }
        }
        if (!matchedStarsValidated && baseline.medianStarWidth > 0f && candidate.medianStarWidth >
            baseline.medianStarWidth * HARD_MAX_WIDTH_FACTOR + WIDTH_ALLOWANCE
        ) {
            hard += "median_star_width_growth_gt_25_percent"
        } else if (!matchedStarsValidated && baseline.medianStarWidth > 0f && candidate.medianStarWidth >
            baseline.medianStarWidth * WARNING_MAX_WIDTH_FACTOR + WIDTH_ALLOWANCE
        ) {
            warnings += "median_star_width_increased"
        }
        if (!matchedStarsValidated && baseline.medianStarEllipticity > 0f && candidate.medianStarEllipticity >
            baseline.medianStarEllipticity * HARD_MAX_ELLIPTICITY_FACTOR + ELLIPTICITY_ALLOWANCE
        ) {
            hard += "median_star_ellipticity_growth_gt_25_percent"
        } else if (!matchedStarsValidated && candidate.medianStarEllipticity >
            baseline.medianStarEllipticity * WARNING_MAX_ELLIPTICITY_FACTOR + ELLIPTICITY_ALLOWANCE
        ) {
            warnings += "median_star_ellipticity_increased"
        }
        if (candidate.brightStarClippingPercent >
            maxOf(MAX_BRIGHT_STAR_CLIPPING_PERCENT, baseline.brightStarClippingPercent + CLIPPING_ALLOWANCE)
        ) {
            hard += "bright_star_clipping_excessive"
        }
        val suspiciousAllowance = if (profile == AstroProcessingProfile.MAX_STARS) 0 else 2
        if (candidate.suspiciousPointCount > baseline.suspiciousPointCount + suspiciousAllowance) {
            hard += if (profile == AstroProcessingProfile.MAX_STARS) {
                "maximum_stars_created_unverified_points"
            } else {
                "new_single_pixel_or_single_channel_points"
            }
        }
        return QualityComparison(hard.distinct(), warnings.distinct())
    }

    companion object {
        private const val MIN_STARS_FOR_RATIO = 5
        private const val HARD_MIN_STAR_FRACTION = 0.80f
        private const val WARNING_MIN_STAR_FRACTION = 0.95f
        private const val HARD_MIN_CONTRAST_FRACTION = 0.85f
        private const val WARNING_MIN_CONTRAST_FRACTION = 0.95f
        private const val HARD_MAX_WIDTH_FACTOR = 1.25f
        private const val WARNING_MAX_WIDTH_FACTOR = 1.10f
        private const val WIDTH_ALLOWANCE = 0.08f
        private const val HARD_MAX_ELLIPTICITY_FACTOR = 1.25f
        private const val WARNING_MAX_ELLIPTICITY_FACTOR = 1.10f
        private const val ELLIPTICITY_ALLOWANCE = 0.04f
        private const val MAX_BRIGHT_STAR_CLIPPING_PERCENT = 12f
        private const val CLIPPING_ALLOWANCE = 5f
    }
}
