package com.example.astrophoto

fun buildMasterDark(frames: List<AveragePixelFrame>): AveragePixelFrame {
    require(frames.isNotEmpty()) { "Master Dark requires at least one dark frame" }
    return averageArgbFrames(frames)
}

internal fun updateMasterDarkRunningAverageArgb(
    accumulator: ArgbAverageAccumulator,
    averagePixels: IntArray,
    nextPixels: IntArray,
    frameNumber: Int,
    pixelCount: Int,
    accumulatorOffset: Int = 0
) {
    updateRunningAverageArgb(
        accumulator = accumulator,
        averagePixels = averagePixels,
        nextPixels = nextPixels,
        frameNumber = frameNumber,
        pixelCount = pixelCount,
        accumulatorOffset = accumulatorOffset
    )
}
