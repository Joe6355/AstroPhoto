package com.example.astrophoto.processing.jpeg.v2.integration

data class AccumulatedLinearPixel(
    val red: Float,
    val green: Float,
    val blue: Float,
    val accumulatedWeight: Float,
    val removedTransient: Boolean
)

/**
 * Mild repeatability rule: with at least eight valid samples, remove only the single brightest
 * sample when its linear luminance exceeds the second brightest by >= 0.18 and by >= 80%.
 * Two repeated bright/faint-star samples therefore protect each other from removal.
 */
class RobustSampleAccumulator(
    pixelCount: Int,
    private val robustMode: Boolean
) {
    private val redSum = FloatArray(pixelCount)
    private val greenSum = FloatArray(pixelCount)
    private val blueSum = FloatArray(pixelCount)
    private val weightSum = FloatArray(pixelCount)
    private val sampleCount = ByteArray(pixelCount)
    private val brightest = if (robustMode) FloatArray(pixelCount) { -1f } else null
    private val secondBrightest = if (robustMode) FloatArray(pixelCount) { -1f } else null
    private val brightestRed = if (robustMode) FloatArray(pixelCount) else null
    private val brightestGreen = if (robustMode) FloatArray(pixelCount) else null
    private val brightestBlue = if (robustMode) FloatArray(pixelCount) else null
    private val brightestWeight = if (robustMode) FloatArray(pixelCount) else null

    init {
        require(pixelCount > 0)
    }

    fun add(index: Int, red: Float, green: Float, blue: Float, weight: Float) {
        require(index in redSum.indices)
        if (weight <= 0f || !weight.isFinite()) return
        val safeRed = red.coerceAtLeast(0f)
        val safeGreen = green.coerceAtLeast(0f)
        val safeBlue = blue.coerceAtLeast(0f)
        redSum[index] += safeRed * weight
        greenSum[index] += safeGreen * weight
        blueSum[index] += safeBlue * weight
        weightSum[index] += weight
        sampleCount[index] = (sampleCount[index].toInt() + 1).coerceAtMost(127).toByte()
        val maxValues = brightest ?: return
        val secondValues = checkNotNull(secondBrightest)
        val luminance = safeRed * 0.2126f + safeGreen * 0.7152f + safeBlue * 0.0722f
        if (luminance > maxValues[index]) {
            secondValues[index] = maxValues[index]
            maxValues[index] = luminance
            checkNotNull(brightestRed)[index] = safeRed
            checkNotNull(brightestGreen)[index] = safeGreen
            checkNotNull(brightestBlue)[index] = safeBlue
            checkNotNull(brightestWeight)[index] = weight
        } else if (luminance > secondValues[index]) {
            secondValues[index] = luminance
        }
    }

    fun finish(index: Int): AccumulatedLinearPixel? {
        require(index in redSum.indices)
        var weight = weightSum[index]
        if (weight <= 0f) return null
        var red = redSum[index]
        var green = greenSum[index]
        var blue = blueSum[index]
        var removed = false
        if (robustMode && sampleCount[index].toInt() >= MIN_ROBUST_SAMPLES) {
            val maximum = checkNotNull(brightest)[index]
            val second = checkNotNull(secondBrightest)[index].coerceAtLeast(0f)
            val maximumWeight = checkNotNull(brightestWeight)[index]
            if (
                maximum - second >= ISOLATED_LINEAR_GAP &&
                maximum >= maxOf(MIN_TRANSIENT_LUMINANCE, second * ISOLATED_RATIO) &&
                weight - maximumWeight > 0f
            ) {
                red -= checkNotNull(brightestRed)[index] * maximumWeight
                green -= checkNotNull(brightestGreen)[index] * maximumWeight
                blue -= checkNotNull(brightestBlue)[index] * maximumWeight
                weight -= maximumWeight
                removed = true
            }
        }
        return AccumulatedLinearPixel(
            red = (red / weight).coerceAtLeast(0f),
            green = (green / weight).coerceAtLeast(0f),
            blue = (blue / weight).coerceAtLeast(0f),
            accumulatedWeight = weight,
            removedTransient = removed
        )
    }

    companion object {
        const val MIN_ROBUST_SAMPLES = 8
        const val ISOLATED_LINEAR_GAP = 0.18f
        const val ISOLATED_RATIO = 1.8f
        private const val MIN_TRANSIENT_LUMINANCE = 0.25f
    }
}
