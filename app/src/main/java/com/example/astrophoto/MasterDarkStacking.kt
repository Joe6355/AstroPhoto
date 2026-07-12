package com.example.astrophoto

fun buildMasterDark(frames: List<AveragePixelFrame>): AveragePixelFrame {
    require(frames.isNotEmpty()) { "Master Dark requires at least one dark frame" }
    return averageArgbFrames(frames)
}

internal fun updateMasterDarkRunningAverageArgb(
    averagePixels: IntArray,
    nextPixels: IntArray,
    frameNumber: Int,
    pixelCount: Int
) {
    updateRunningAverageArgb(
        averagePixels = averagePixels,
        nextPixels = nextPixels,
        frameNumber = frameNumber,
        pixelCount = pixelCount
    )
}
