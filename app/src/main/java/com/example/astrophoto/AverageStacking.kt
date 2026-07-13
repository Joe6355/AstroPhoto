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
    val accumulator = ArgbAverageAccumulator(
        pixelCount = output.size,
        maximumFrameCount = frames.size
    )
    frames.drop(1).forEachIndexed { index, frame ->
        updateRunningAverageArgb(
            accumulator = accumulator,
            averagePixels = output,
            nextPixels = frame.pixels,
            frameNumber = index + 2,
            pixelCount = output.size
        )
    }
    return AveragePixelFrame(first.width, first.height, output)
}

internal class ArgbAverageAccumulator(
    private val pixelCount: Int,
    private val maximumFrameCount: Int
) {
    private val validatedPixelCount = pixelCount.also {
        require(it >= 0) { "Average accumulator pixel count must not be negative" }
    }
    private val remainderBits = signedRemainderBits(maximumFrameCount)
    private val remainderMask = if (remainderBits <= MAX_PACKED_REMAINDER_BITS) {
        (1 shl remainderBits) - 1
    } else {
        0
    }
    private val remainderSignBit = if (remainderBits <= MAX_PACKED_REMAINDER_BITS) {
        1 shl (remainderBits - 1)
    } else {
        0
    }
    private val packedRemainders = if (remainderBits <= MAX_PACKED_REMAINDER_BITS) {
        IntArray(validatedPixelCount)
    } else {
        null
    }
    private val redRemainders = if (packedRemainders == null) IntArray(validatedPixelCount) else null
    private val greenRemainders = if (packedRemainders == null) IntArray(validatedPixelCount) else null
    private val blueRemainders = if (packedRemainders == null) IntArray(validatedPixelCount) else null

    init {
        require(maximumFrameCount >= 1) {
            "Average accumulator frame count must be positive"
        }
    }

    fun add(
        averagePixels: IntArray,
        nextPixels: IntArray,
        frameNumber: Int,
        accumulatorOffset: Int,
        pixelCount: Int
    ) {
        require(frameNumber in 2..maximumFrameCount) {
            "Running Average frame number is outside the accumulator range"
        }
        require(accumulatorOffset >= 0 && pixelCount >= 0) {
            "Average accumulator range must not be negative"
        }
        require(accumulatorOffset.toLong() + pixelCount <= this.pixelCount.toLong()) {
            "Average accumulator range exceeds its pixel count"
        }
        require(averagePixels.size >= pixelCount && nextPixels.size >= pixelCount) {
            "Average pixel arrays are shorter than the requested pixel count"
        }

        val packed = packedRemainders
        if (packed != null) {
            addPacked(
                packed = packed,
                averagePixels = averagePixels,
                nextPixels = nextPixels,
                frameNumber = frameNumber,
                accumulatorOffset = accumulatorOffset,
                pixelCount = pixelCount
            )
        } else {
            addUnpacked(
                redRemainders = checkNotNull(redRemainders),
                greenRemainders = checkNotNull(greenRemainders),
                blueRemainders = checkNotNull(blueRemainders),
                averagePixels = averagePixels,
                nextPixels = nextPixels,
                frameNumber = frameNumber,
                accumulatorOffset = accumulatorOffset,
                pixelCount = pixelCount
            )
        }
    }

    private fun addPacked(
        packed: IntArray,
        averagePixels: IntArray,
        nextPixels: IntArray,
        frameNumber: Int,
        accumulatorOffset: Int,
        pixelCount: Int
    ) {
        for (pixelIndex in 0 until pixelCount) {
            val accumulatorIndex = accumulatorOffset + pixelIndex
            val remainders = packed[accumulatorIndex]
            val oldColor = averagePixels[pixelIndex]
            val newColor = nextPixels[pixelIndex]
            val redTotal = exactChannelTotal(
                oldColor ushr 16 and 0xFF,
                unpackRemainder(remainders, RED_REMAINDER_SHIFT),
                newColor ushr 16 and 0xFF,
                frameNumber
            )
            val greenTotal = exactChannelTotal(
                oldColor ushr 8 and 0xFF,
                unpackRemainder(remainders, remainderBits),
                newColor ushr 8 and 0xFF,
                frameNumber
            )
            val blueTotal = exactChannelTotal(
                oldColor and 0xFF,
                unpackRemainder(remainders, remainderBits * 2),
                newColor and 0xFF,
                frameNumber
            )
            val red = roundedAverageChannel(redTotal, frameNumber)
            val green = roundedAverageChannel(greenTotal, frameNumber)
            val blue = roundedAverageChannel(blueTotal, frameNumber)
            averagePixels[pixelIndex] =
                OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
            packed[accumulatorIndex] =
                packRemainder(redTotal, red, frameNumber, RED_REMAINDER_SHIFT) or
                packRemainder(greenTotal, green, frameNumber, remainderBits) or
                packRemainder(blueTotal, blue, frameNumber, remainderBits * 2)
        }
    }

    private fun addUnpacked(
        redRemainders: IntArray,
        greenRemainders: IntArray,
        blueRemainders: IntArray,
        averagePixels: IntArray,
        nextPixels: IntArray,
        frameNumber: Int,
        accumulatorOffset: Int,
        pixelCount: Int
    ) {
        for (pixelIndex in 0 until pixelCount) {
            val accumulatorIndex = accumulatorOffset + pixelIndex
            val oldColor = averagePixels[pixelIndex]
            val newColor = nextPixels[pixelIndex]
            val redTotal = exactChannelTotal(
                oldColor ushr 16 and 0xFF,
                redRemainders[accumulatorIndex],
                newColor ushr 16 and 0xFF,
                frameNumber
            )
            val greenTotal = exactChannelTotal(
                oldColor ushr 8 and 0xFF,
                greenRemainders[accumulatorIndex],
                newColor ushr 8 and 0xFF,
                frameNumber
            )
            val blueTotal = exactChannelTotal(
                oldColor and 0xFF,
                blueRemainders[accumulatorIndex],
                newColor and 0xFF,
                frameNumber
            )
            val red = roundedAverageChannel(redTotal, frameNumber)
            val green = roundedAverageChannel(greenTotal, frameNumber)
            val blue = roundedAverageChannel(blueTotal, frameNumber)
            averagePixels[pixelIndex] =
                OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
            redRemainders[accumulatorIndex] = exactRemainder(redTotal, red, frameNumber)
            greenRemainders[accumulatorIndex] = exactRemainder(greenTotal, green, frameNumber)
            blueRemainders[accumulatorIndex] = exactRemainder(blueTotal, blue, frameNumber)
        }
    }

    private fun unpackRemainder(packed: Int, shift: Int): Int {
        val encoded = (packed ushr shift) and remainderMask
        return if (encoded and remainderSignBit == 0) {
            encoded
        } else {
            encoded or (-1 shl remainderBits)
        }
    }

    private fun packRemainder(
        total: Long,
        roundedAverage: Int,
        frameNumber: Int,
        shift: Int
    ): Int = (exactRemainder(total, roundedAverage, frameNumber) and remainderMask) shl shift

    private companion object {
        const val MAX_PACKED_REMAINDER_BITS = 10
        const val RED_REMAINDER_SHIFT = 0
    }
}

internal fun updateRunningAverageArgb(
    accumulator: ArgbAverageAccumulator,
    averagePixels: IntArray,
    nextPixels: IntArray,
    frameNumber: Int,
    pixelCount: Int,
    accumulatorOffset: Int = 0
) = accumulator.add(
    averagePixels = averagePixels,
    nextPixels = nextPixels,
    frameNumber = frameNumber,
    accumulatorOffset = accumulatorOffset,
    pixelCount = pixelCount
)

private fun validateAverageFrame(frame: AveragePixelFrame) {
    require(frame.width > 0 && frame.height > 0) {
        "Average frame dimensions must be positive"
    }
    val expectedSize = frame.width.toLong() * frame.height.toLong()
    require(expectedSize <= Int.MAX_VALUE && frame.pixels.size == expectedSize.toInt()) {
        "Average frame pixel count does not match its dimensions"
    }
}

private fun exactChannelTotal(
    roundedAverage: Int,
    remainder: Int,
    next: Int,
    frameNumber: Int
): Long = roundedAverage.toLong() * (frameNumber - 1).toLong() + remainder + next

private fun roundedAverageChannel(total: Long, frameNumber: Int): Int =
    ((total + frameNumber / 2L) / frameNumber.toLong()).toInt().coerceIn(0, 255)

private fun exactRemainder(total: Long, roundedAverage: Int, frameNumber: Int): Int =
    (total - roundedAverage.toLong() * frameNumber.toLong()).toInt()

private fun signedRemainderBits(maximumFrameCount: Int): Int {
    require(maximumFrameCount >= 1)
    val maximumNegativeMagnitude = maximumFrameCount / 2L
    val maximumPositiveRemainder = (maximumFrameCount - 1L) / 2L
    var bits = 1
    while (
        (1L shl (bits - 1)) < maximumNegativeMagnitude ||
        (1L shl (bits - 1)) - 1L < maximumPositiveRemainder
    ) {
        bits++
    }
    return bits
}

private const val OPAQUE_ALPHA = 0xFF000000.toInt()
private const val RGB_MASK = 0x00FFFFFF
