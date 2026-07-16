package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

data class ReferenceStarRetentionMetrics(
    val referenceReliableStarCount: Int,
    val evaluatedReferenceStarCount: Int,
    val retainedReferenceStarCount: Int,
    val retentionRatio: Float,
    val medianContrastBefore: Float,
    val medianContrastAfter: Float,
    val medianContrastRatio: Float,
    val medianWidthBefore: Float,
    val medianWidthAfter: Float,
    val medianWidthGrowth: Float,
    val medianCentroidShift: Float,
    val lineLikeSmearRate: Float
)

data class ReferenceStarRetentionResult(
    val accepted: Boolean,
    val metrics: ReferenceStarRetentionMetrics,
    val hardFailureReasons: List<String>,
    val warningReasons: List<String>
)

class ReferenceStarRetentionValidator {
    fun validate(
        reference: ArgbPixelImage,
        cleanStack: ArgbPixelImage,
        referenceStars: List<DetectedStar>
    ): ReferenceStarRetentionResult = validateSources(
        IntArrayPixelSource(reference.width, reference.height, reference.pixels),
        IntArrayPixelSource(cleanStack.width, cleanStack.height, cleanStack.pixels),
        referenceStars
    )

    fun validate(
        reference: FileBackedImage,
        cleanStack: FileBackedImage,
        referenceStars: List<DetectedStar>
    ): ReferenceStarRetentionResult = FileBackedImageReader(reference, cachedRows = 20).use { first ->
        FileBackedImageReader(cleanStack, cachedRows = 20).use { second ->
            validateSources(first, second, referenceStars)
        }
    }

    private fun validateSources(
        reference: ArgbPixelSource,
        cleanStack: ArgbPixelSource,
        referenceStars: List<DetectedStar>
    ): ReferenceStarRetentionResult {
        if (reference.width != cleanStack.width || reference.height != cleanStack.height) {
            return failure(referenceStars.size, "reference_star_dimensions_changed")
        }
        val measurements = referenceStars.mapNotNull { star ->
            val before = measure(reference, star.x, star.y, star.width) ?: return@mapNotNull null
            val after = measure(cleanStack, star.x, star.y, star.width) ?: StarMeasurement.MISSING
            StarRetentionMeasurement(before, after)
        }
        val evaluated = measurements.size
        val retained = measurements.count { value ->
            value.after.present &&
                value.after.contrast >= maxOf(MIN_ABSOLUTE_CONTRAST, value.before.contrast * MIN_STAR_CONTRAST_RATIO) &&
                value.after.centroidShiftFrom(value.before) <= MAX_RETAINED_CENTROID_SHIFT &&
                value.after.width <= value.before.width * MAX_RETAINED_WIDTH_FACTOR &&
                !value.after.lineLike
        }
        val retentionRatio = retained.toFloat() / evaluated.coerceAtLeast(1)
        val contrastBefore = median(measurements.map { it.before.contrast })
        val contrastAfter = median(measurements.map { it.after.contrast })
        val contrastRatio = if (contrastBefore > 0f) contrastAfter / contrastBefore else 0f
        val widthBefore = median(measurements.map { it.before.width })
        val widthAfter = median(measurements.map { it.after.width })
        val widthGrowth = if (widthBefore > 0f) widthAfter / widthBefore - 1f else 0f
        val centroidShift = median(measurements.map { it.after.centroidShiftFrom(it.before) })
        val smearRate = measurements.count { it.after.lineLike }.toFloat() / evaluated.coerceAtLeast(1)
        val metrics = ReferenceStarRetentionMetrics(
            referenceStars.size,
            evaluated,
            retained,
            retentionRatio,
            contrastBefore,
            contrastAfter,
            contrastRatio,
            widthBefore,
            widthAfter,
            widthGrowth,
            centroidShift,
            smearRate
        )
        val hard = buildList {
            if (evaluated < MIN_EVALUATED_REFERENCE_STARS) add("insufficient_reference_stars_for_retention_validation")
            if (retentionRatio < MIN_RETENTION_RATIO) add("reference_star_retention_below_90_percent")
            if (contrastRatio < MIN_MEDIAN_CONTRAST_RATIO) add("reference_star_contrast_loss_above_15_percent")
            if (widthGrowth > MAX_MEDIAN_WIDTH_GROWTH) add("reference_star_width_growth_above_20_percent")
            if (centroidShift > MAX_MEDIAN_CENTROID_SHIFT) add("reference_star_centroid_shift_above_0_5_px")
            if (smearRate > MAX_LINE_LIKE_SMEAR_RATE) add("reference_star_line_smear_above_10_percent")
        }
        val warnings = buildList {
            if (retentionRatio in MIN_RETENTION_RATIO..<WARNING_RETENTION_RATIO) add("reference_star_retention_near_limit")
            if (contrastRatio in MIN_MEDIAN_CONTRAST_RATIO..<WARNING_CONTRAST_RATIO) add("reference_star_contrast_near_limit")
        }
        return ReferenceStarRetentionResult(hard.isEmpty(), metrics, hard, warnings)
    }

    private data class StarRetentionMeasurement(
        val before: StarMeasurement,
        val after: StarMeasurement
    )

    private data class StarMeasurement(
        val present: Boolean,
        val contrast: Float,
        val centroidX: Float,
        val centroidY: Float,
        val width: Float,
        val ellipticity: Float,
        val lineLike: Boolean
    ) {
        fun centroidShiftFrom(other: StarMeasurement): Float =
            if (!present) Float.POSITIVE_INFINITY
            else hypot(centroidX - other.centroidX, centroidY - other.centroidY)

        companion object {
            val MISSING = StarMeasurement(false, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 1f, true)
        }
    }

    private fun measure(
        image: ArgbPixelSource,
        expectedX: Float,
        expectedY: Float,
        expectedWidth: Float
    ): StarMeasurement? {
        val centerX = expectedX.toInt()
        val centerY = expectedY.toInt()
        val coreRadius = maxOf(3, ceil(expectedWidth * 1.8f).toInt()).coerceAtMost(7)
        val outerRadius = coreRadius + 3
        if (
            centerX - outerRadius < 0 || centerY - outerRadius < 0 ||
            centerX + outerRadius >= image.width || centerY + outerRadius >= image.height
        ) return null
        val annulus = mutableListOf<Int>()
        for (dy in -outerRadius..outerRadius) for (dx in -outerRadius..outerRadius) {
            val radiusSquared = dx * dx + dy * dy
            if (radiusSquared > coreRadius * coreRadius && radiusSquared <= outerRadius * outerRadius) {
                annulus += pixelLuminance(image.argbAt(centerX + dx, centerY + dy))
            }
        }
        annulus.sort()
        val background = annulus[annulus.size / 2].toFloat()
        var peak = 0f
        var flux = 0f
        var weightedX = 0f
        var weightedY = 0f
        for (dy in -coreRadius..coreRadius) for (dx in -coreRadius..coreRadius) {
            if (dx * dx + dy * dy > coreRadius * coreRadius) continue
            val luminance = pixelLuminance(image.argbAt(centerX + dx, centerY + dy)).toFloat()
            peak = maxOf(peak, luminance)
            val signal = (luminance - background).coerceAtLeast(0f)
            flux += signal
            weightedX += (centerX + dx) * signal
            weightedY += (centerY + dy) * signal
        }
        if (flux <= MIN_ABSOLUTE_CONTRAST) return StarMeasurement.MISSING
        val centroidX = weightedX / flux
        val centroidY = weightedY / flux
        var momentXX = 0f
        var momentYY = 0f
        var momentXY = 0f
        for (dy in -coreRadius..coreRadius) for (dx in -coreRadius..coreRadius) {
            if (dx * dx + dy * dy > coreRadius * coreRadius) continue
            val signal = (
                pixelLuminance(image.argbAt(centerX + dx, centerY + dy)) - background
                ).coerceAtLeast(0f)
            val offsetX = centerX + dx - centroidX
            val offsetY = centerY + dy - centroidY
            momentXX += signal * offsetX * offsetX
            momentYY += signal * offsetY * offsetY
            momentXY += signal * offsetX * offsetY
        }
        momentXX /= flux
        momentYY /= flux
        momentXY /= flux
        val trace = momentXX + momentYY
        val discriminant = sqrt(maxOf(0f, (momentXX - momentYY) * (momentXX - momentYY) + 4f * momentXY * momentXY))
        val major = maxOf(0f, (trace + discriminant) * 0.5f)
        val minor = maxOf(0f, (trace - discriminant) * 0.5f)
        val width = 2.355f * sqrt(((major + minor) * 0.5f).coerceAtLeast(0.0001f))
        val ellipticity = if (major > 0f) (1f - sqrt(minor / major)).coerceIn(0f, 1f) else 1f
        return StarMeasurement(
            true,
            (peak - background).coerceAtLeast(0f),
            centroidX,
            centroidY,
            width,
            ellipticity,
            ellipticity > LINE_LIKE_ELLIPTICITY
        )
    }

    private fun failure(referenceCount: Int, reason: String): ReferenceStarRetentionResult =
        ReferenceStarRetentionResult(
            false,
            ReferenceStarRetentionMetrics(
                referenceCount, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                Float.POSITIVE_INFINITY, 1f
            ),
            listOf(reason),
            emptyList()
        )

    private fun median(values: List<Float>): Float {
        val finite = values.filter { it.isFinite() }.sorted()
        if (finite.isEmpty()) return 0f
        return if (finite.size % 2 == 1) finite[finite.size / 2]
        else (finite[finite.size / 2 - 1] + finite[finite.size / 2]) * 0.5f
    }

    companion object {
        const val MIN_RETENTION_RATIO = 0.90f
        const val MIN_MEDIAN_CONTRAST_RATIO = 0.85f
        const val MAX_MEDIAN_WIDTH_GROWTH = 0.20f
        const val MAX_MEDIAN_CENTROID_SHIFT = 0.50f
        const val MAX_LINE_LIKE_SMEAR_RATE = 0.10f
        private const val MIN_EVALUATED_REFERENCE_STARS = 4
        private const val MIN_ABSOLUTE_CONTRAST = 3f
        private const val MIN_STAR_CONTRAST_RATIO = 0.35f
        private const val MAX_RETAINED_CENTROID_SHIFT = 0.75f
        private const val MAX_RETAINED_WIDTH_FACTOR = 1.50f
        private const val LINE_LIKE_ELLIPTICITY = 0.68f
        private const val WARNING_RETENTION_RATIO = 0.95f
        private const val WARNING_CONTRAST_RATIO = 0.92f
    }
}
