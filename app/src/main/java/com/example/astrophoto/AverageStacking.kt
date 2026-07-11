package com.example.astrophoto

data class AveragePixelFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

fun averageArgbFrames(frames: List<AveragePixelFrame>): AveragePixelFrame {
    require(frames.isNotEmpty()) { "Average stacking requires at least one frame" }

    val first = frames.first()
    validateAverageFrame(first)
    frames.drop(1).forEach { frame ->
        validateAverageFrame(frame)
        require(frame.width == first.width && frame.height == first.height) {
            "Average stacking requires equal frame dimensions"
        }
    }

    val output = first.pixels.copyOf()
    output.indices.forEach { index ->
        output[index] = OPAQUE_ALPHA or (output[index] and RGB_MASK)
    }
    frames.drop(1).forEachIndexed { index, frame ->
        updateRunningAverageArgb(
            averagePixels = output,
            nextPixels = frame.pixels,
            frameNumber = index + 2,
            pixelCount = output.size
        )
    }
    return AveragePixelFrame(first.width, first.height, output)
}

internal fun updateRunningAverageArgb(
    averagePixels: IntArray,
    nextPixels: IntArray,
    frameNumber: Int,
    pixelCount: Int
) {
    require(frameNumber >= 2) { "Running Average frame number must be at least 2" }
    require(pixelCount >= 0) { "Pixel count must not be negative" }
    require(averagePixels.size >= pixelCount && nextPixels.size >= pixelCount) {
        "Average pixel arrays are shorter than the requested pixel count"
    }

    for (pixelIndex in 0 until pixelCount) {
        val oldColor = averagePixels[pixelIndex]
        val newColor = nextPixels[pixelIndex]
        val red = runningAverageChannel(
            old = oldColor ushr 16 and 0xFF,
            next = newColor ushr 16 and 0xFF,
            frameNumber = frameNumber
        )
        val green = runningAverageChannel(
            old = oldColor ushr 8 and 0xFF,
            next = newColor ushr 8 and 0xFF,
            frameNumber = frameNumber
        )
        val blue = runningAverageChannel(
            old = oldColor and 0xFF,
            next = newColor and 0xFF,
            frameNumber = frameNumber
        )
        averagePixels[pixelIndex] =
            OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
    }
}

private fun validateAverageFrame(frame: AveragePixelFrame) {
    require(frame.width > 0 && frame.height > 0) {
        "Average frame dimensions must be positive"
    }
    val expectedSize = frame.width.toLong() * frame.height.toLong()
    require(expectedSize <= Int.MAX_VALUE && frame.pixels.size == expectedSize.toInt()) {
        "Average frame pixel count does not match its dimensions"
    }
}

private fun runningAverageChannel(old: Int, next: Int, frameNumber: Int): Int {
    val numerator =
        old.toLong() * (frameNumber - 1).toLong() + next + frameNumber / 2L
    return (numerator / frameNumber.toLong()).toInt().coerceIn(0, 255)
}

private const val OPAQUE_ALPHA = 0xFF000000.toInt()
private const val RGB_MASK = 0x00FFFFFF
