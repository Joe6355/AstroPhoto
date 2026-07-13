package com.example.astrophoto

data class ProfileSanityMetrics(
    val width: Int,
    val height: Int,
    val stars: Int,
    val averageStarContrast: Float,
    val blackPercent: Float,
    val whitePercent: Float,
    val background: Int,
    val dynamicRange: Int,
    val channelsValid: Boolean,
    val medianStarContrast: Float = averageStarContrast,
    val lowPercentile: Int = background,
    val highPercentile: Int = background,
    val backgroundSpread: Int = dynamicRange,
    val flatGrayPercent: Float = 0f,
    val largeScaleBanding: Float = 0f
) {
    val medianLuminance: Int
        get() = background
}

sealed interface ProfileSanityResult {
    data class Passed(val metrics: ProfileSanityMetrics) : ProfileSanityResult
    data class Failed(
        val reason: String,
        val metrics: ProfileSanityMetrics
    ) : ProfileSanityResult
}

internal fun <T> rejectedProfileSanityResult(reason: String): Result<T> =
    Result.failure(
        IllegalStateException(
            when (reason) {
                "output collapsed to black" ->
                    "Профильная обработка остановлена: результат стал почти полностью чёрным."
                "output collapsed to white" ->
                    "Профильная обработка остановлена: результат стал почти полностью белым."
                else ->
                    "Профильная обработка остановлена: результат не прошёл проверку качества."
            }
        )
    )

internal fun analyzeProfileImage(
    image: ArgbPixelImage,
    roi: AstroRoi,
    sensitivity: StarDetectionSensitivity
): ProfileSanityMetrics {
    val histogram = IntArray(256)
    var black = 0
    var white = 0
    var minimum = 255
    var maximum = 0
    var channelsValid = true
    image.pixels.forEach { color ->
        val red = color ushr 16 and 0xFF
        val green = color ushr 8 and 0xFF
        val blue = color and 0xFF
        channelsValid = channelsValid && red in 0..255 && green in 0..255 && blue in 0..255
        val luminance = pixelLuminance(color)
        histogram[luminance]++
        if (luminance <= 2) black++
        if (luminance >= 253) white++
        minimum = minOf(minimum, luminance)
        maximum = maxOf(maximum, luminance)
    }
    val detection = StarDetector().detect(image, roi, sensitivity)
    val starContrasts = detection.stars.map { it.localContrast }.sorted()
    val averageContrast = starContrasts.average()
        .takeIf(Double::isFinite)?.toFloat() ?: 0f
    val medianContrast = when {
        starContrasts.isEmpty() -> 0f
        starContrasts.size % 2 == 1 -> starContrasts[starContrasts.size / 2]
        else -> {
            val upper = starContrasts.size / 2
            (starContrasts[upper - 1] + starContrasts[upper]) / 2f
        }
    }
    val total = image.pixels.size.toFloat()
    val median = StarDetector.percentile(histogram, image.pixels.size.toLong(), 0.50)
    val flatGray = image.pixels.count {
        kotlin.math.abs(pixelLuminance(it) - median) <= ProfileSanityThresholds.FLAT_GRAY_RADIUS
    }
    return ProfileSanityMetrics(
        image.width,
        image.height,
        detection.stars.size,
        averageContrast,
        black * 100f / total,
        white * 100f / total,
        median,
        maximum - minimum,
        channelsValid,
        medianContrast,
        StarDetector.percentile(histogram, image.pixels.size.toLong(), 0.05),
        StarDetector.percentile(histogram, image.pixels.size.toLong(), 0.95),
        StarDetector.percentile(histogram, image.pixels.size.toLong(), 0.90) -
            StarDetector.percentile(histogram, image.pixels.size.toLong(), 0.10),
        flatGray * 100f / total,
        calculateLargeScaleBanding(image)
    )
}

internal fun evaluateProfileSanity(
    before: ProfileSanityMetrics,
    after: ProfileSanityMetrics
): ProfileSanityResult {
    val reason = when {
        after.width <= 0 || after.height <= 0 -> "invalid output dimensions"
        after.width != before.width || after.height != before.height -> "output dimensions changed"
        !after.channelsValid || !after.averageStarContrast.isFinite() ||
            !after.medianStarContrast.isFinite() || !after.flatGrayPercent.isFinite() ||
            !after.largeScaleBanding.isFinite() -> "invalid output channels or arithmetic"
        after.blackPercent >= ProfileSanityThresholds.COLLAPSE_PERCENT -> "output collapsed to black"
        after.whitePercent >= ProfileSanityThresholds.COLLAPSE_PERCENT -> "output collapsed to white"
        after.medianLuminance > before.medianLuminance +
            ProfileSanityThresholds.MAX_MEDIAN_LIFT_ABSOLUTE ->
            "extreme background brightness lift"
        after.medianLuminance >= ProfileSanityThresholds.DARK_STACK_MAX_MEDIAN &&
            after.medianLuminance >
            (before.medianLuminance + 1) * ProfileSanityThresholds.MAX_MEDIAN_LIFT_FACTOR ->
            "extreme background brightness factor"
        before.flatGrayPercent < ProfileSanityThresholds.BASELINE_FLAT_GRAY_PERCENT &&
            after.flatGrayPercent >= ProfileSanityThresholds.MAX_FLAT_GRAY_PERCENT &&
            after.medianLuminance >= ProfileSanityThresholds.FLAT_GRAY_MIN_MEDIAN ->
            "large area collapsed to flat grey"
        before.dynamicRange >= ProfileSanityThresholds.MIN_BASELINE_RANGE &&
            after.dynamicRange <= ProfileSanityThresholds.UNIFORM_RANGE -> "output collapsed to uniform tone"
        before.stars >= ProfileSanityThresholds.MIN_STARS_FOR_LOSS_CHECK &&
            after.stars < before.stars * ProfileSanityThresholds.MIN_STAR_FRACTION ->
            "extreme loss of detected stars"
        before.averageStarContrast > 0f &&
            after.averageStarContrast <
            before.averageStarContrast * ProfileSanityThresholds.MIN_CONTRAST_FRACTION ->
            "extreme loss of star contrast"
        before.medianStarContrast > 0f &&
            after.medianStarContrast <
            before.medianStarContrast * ProfileSanityThresholds.MIN_MEDIAN_CONTRAST_FRACTION ->
            "extreme loss of median star contrast"
        before.blackPercent < ProfileSanityThresholds.BASELINE_CLIPPING_PERCENT &&
            after.blackPercent >= ProfileSanityThresholds.EXTREME_CLIPPING_PERCENT ->
            "extreme background clipping"
        after.backgroundSpread >= ProfileSanityThresholds.MIN_SEVERE_BACKGROUND_SPREAD &&
            after.backgroundSpread > before.backgroundSpread *
            ProfileSanityThresholds.MAX_BACKGROUND_SPREAD_FACTOR +
            ProfileSanityThresholds.BACKGROUND_SPREAD_ALLOWANCE ->
            "extreme background spread"
        after.largeScaleBanding >= ProfileSanityThresholds.MIN_SEVERE_BANDING &&
            after.largeScaleBanding > before.largeScaleBanding *
            ProfileSanityThresholds.MAX_BANDING_FACTOR +
            ProfileSanityThresholds.BANDING_ALLOWANCE ->
            "severe large-scale banding"
        else -> null
    }
    return if (reason == null) ProfileSanityResult.Passed(after)
    else ProfileSanityResult.Failed(reason, after)
}

internal object ProfileSanityThresholds {
    // These bounds reject catastrophic regressions relative to the safe aligned stack,
    // not subjective differences between profile styles.
    const val COLLAPSE_PERCENT = 98f
    const val EXTREME_CLIPPING_PERCENT = 80f
    const val BASELINE_CLIPPING_PERCENT = 20f
    const val MIN_STAR_FRACTION = 0.35f
    const val MIN_CONTRAST_FRACTION = 0.25f
    const val MIN_MEDIAN_CONTRAST_FRACTION = 0.40f
    const val MIN_STARS_FOR_LOSS_CHECK = 6
    const val MIN_BASELINE_RANGE = 8
    const val UNIFORM_RANGE = 1
    const val MAX_MEDIAN_LIFT_ABSOLUTE = 55
    const val MAX_MEDIAN_LIFT_FACTOR = 5
    const val DARK_STACK_MAX_MEDIAN = 48
    const val FLAT_GRAY_RADIUS = 4
    const val BASELINE_FLAT_GRAY_PERCENT = 60f
    const val MAX_FLAT_GRAY_PERCENT = 78f
    const val FLAT_GRAY_MIN_MEDIAN = 36
    const val MIN_SEVERE_BACKGROUND_SPREAD = 48
    const val MAX_BACKGROUND_SPREAD_FACTOR = 2.5f
    const val BACKGROUND_SPREAD_ALLOWANCE = 12f
    const val MIN_SEVERE_BANDING = 8f
    const val MAX_BANDING_FACTOR = 2.5f
    const val BANDING_ALLOWANCE = 4f
}

sealed interface RecoveredStarsResult {
    data class Recovered(
        val image: ArgbPixelImage,
        val sanity: ProfileSanityResult.Passed,
        val originalFailure: String
    ) : RecoveredStarsResult

    data class Failed(val reason: String) : RecoveredStarsResult
}

internal fun recoverStarsFromIntermediate(
    intermediate: ArgbPixelImage,
    roi: AstroRoi,
    sensitivity: StarDetectionSensitivity,
    originalFailure: String,
    applyConservativeStretch: Boolean = true
): RecoveredStarsResult {
    val before = analyzeProfileImage(intermediate, roi, sensitivity)
    val safeCopy = ArgbPixelImage(
        intermediate.width,
        intermediate.height,
        intermediate.pixels.copyOf()
    )
    val safeSanity = evaluateProfileSanity(
        before,
        analyzeProfileImage(safeCopy, roi, sensitivity)
    )
    if (safeSanity is ProfileSanityResult.Failed) {
        return RecoveredStarsResult.Failed("RecoveredStars failed sanity: ${safeSanity.reason}")
    }
    if (applyConservativeStretch && shouldAttemptRecoveryStretch(before)) {
        val stretched = ArgbPixelImage(
            intermediate.width,
            intermediate.height,
            intermediate.pixels.copyOf()
        )
        AstroStretch().applyInPlace(stretched, AstroStretchMode.NATURAL)
        when (val stretchedSanity = evaluateProfileSanity(
            before,
            analyzeProfileImage(stretched, roi, sensitivity)
        )) {
            is ProfileSanityResult.Passed -> return RecoveredStarsResult.Recovered(
                stretched,
                stretchedSanity,
                originalFailure
            )
            is ProfileSanityResult.Failed -> Unit
        }
    }
    return when (safeSanity) {
        is ProfileSanityResult.Passed -> RecoveredStarsResult.Recovered(
            safeCopy,
            safeSanity,
            originalFailure
        )
        is ProfileSanityResult.Failed -> RecoveredStarsResult.Failed(
            "RecoveredStars failed sanity: ${safeSanity.reason}"
        )
    }
}

private fun shouldAttemptRecoveryStretch(metrics: ProfileSanityMetrics): Boolean =
    metrics.medianLuminance <= RECOVERY_MAX_MEDIAN &&
        metrics.highPercentile - metrics.medianLuminance >= RECOVERY_MIN_HIGHLIGHT_HEADROOM

private fun calculateLargeScaleBanding(image: ArgbPixelImage): Float {
    val columns = minOf(8, image.width)
    val rows = minOf(8, image.height)
    if (columns < 3 && rows < 3) return 0f
    val red = Array(rows) { FloatArray(columns) }
    val green = Array(rows) { FloatArray(columns) }
    val blue = Array(rows) { FloatArray(columns) }
    for (cellY in 0 until rows) {
        val top = cellY * image.height / rows
        val bottom = (cellY + 1) * image.height / rows
        for (cellX in 0 until columns) {
            val left = cellX * image.width / columns
            val right = (cellX + 1) * image.width / columns
            var redSum = 0L
            var greenSum = 0L
            var blueSum = 0L
            var count = 0
            for (y in top until bottom) for (x in left until right) {
                val color = image.pixelAt(x, y)
                redSum += color ushr 16 and 0xFF
                greenSum += color ushr 8 and 0xFF
                blueSum += color and 0xFF
                count++
            }
            red[cellY][cellX] = redSum.toFloat() / count.coerceAtLeast(1)
            green[cellY][cellX] = greenSum.toFloat() / count.coerceAtLeast(1)
            blue[cellY][cellX] = blueSum.toFloat() / count.coerceAtLeast(1)
        }
    }
    var curvature = 0f
    var samples = 0
    fun addSecondDifference(a: Float, b: Float, c: Float) {
        curvature += kotlin.math.abs(a - 2f * b + c)
        samples++
    }
    listOf(red, green, blue).forEach { channel ->
        for (y in 0 until rows) for (x in 1 until columns - 1) {
            addSecondDifference(channel[y][x - 1], channel[y][x], channel[y][x + 1])
        }
        for (x in 0 until columns) for (y in 1 until rows - 1) {
            addSecondDifference(channel[y - 1][x], channel[y][x], channel[y + 1][x])
        }
    }
    return curvature / samples.coerceAtLeast(1)
}

private const val RECOVERY_MAX_MEDIAN = 32
private const val RECOVERY_MIN_HIGHLIGHT_HEADROOM = 32
