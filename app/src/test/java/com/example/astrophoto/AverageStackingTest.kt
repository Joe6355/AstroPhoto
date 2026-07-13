package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AverageStackingTest {
    @Test
    fun emptyInputHasClearFailure() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            averageArgbFrames(emptyList())
        }

        assertTrue(error.message.orEmpty().contains("at least one frame"))
    }

    @Test
    fun oneFramePreservesRgbValues() {
        val input = frame(2, 1, argb(12, 10, 20, 30), argb(34, 40, 50, 60))

        val output = averageArgbFrames(listOf(input))

        assertArrayEquals(
            intArrayOf(argb(255, 10, 20, 30), argb(255, 40, 50, 60)),
            output.pixels
        )
    }

    @Test
    fun identicalFramesPreserveRgbValues() {
        val first = frame(1, 1, rgb(20, 40, 60))
        val second = frame(1, 1, rgb(20, 40, 60))

        val output = averageArgbFrames(listOf(first, second))

        assertEquals(rgb(20, 40, 60), output.pixels.single())
    }

    @Test
    fun twoDifferentFramesAverageEachChannel() {
        val first = frame(1, 1, rgb(10, 20, 30))
        val second = frame(1, 1, rgb(20, 40, 60))

        val output = averageArgbFrames(listOf(first, second))

        assertEquals(rgb(15, 30, 45), output.pixels.single())
    }

    @Test
    fun threeFramesProduceExpectedAverage() {
        val frames = listOf(
            frame(1, 1, rgb(0, 0, 0)),
            frame(1, 1, rgb(10, 20, 30)),
            frame(1, 1, rgb(20, 40, 60))
        )

        val output = averageArgbFrames(frames)

        assertEquals(rgb(10, 20, 30), output.pixels.single())
    }

    @Test
    fun blackAndWhiteProduceRoundedMidpoint() {
        val output = averageArgbFrames(
            listOf(frame(1, 1, rgb(0, 0, 0)), frame(1, 1, rgb(255, 255, 255)))
        )

        assertEquals(rgb(128, 128, 128), output.pixels.single())
    }

    @Test
    fun channelsAreAveragedIndependently() {
        val output = averageArgbFrames(
            listOf(frame(1, 1, rgb(200, 0, 40)), frame(1, 1, rgb(0, 100, 60)))
        )

        assertEquals(rgb(100, 50, 50), output.pixels.single())
    }

    @Test
    fun outputChannelsStayInByteRange() {
        val output = averageArgbFrames(
            listOf(frame(1, 1, rgb(0, 255, 0)), frame(1, 1, rgb(255, 0, 255)))
        )
        val color = output.pixels.single()

        assertTrue(red(color) in 0..255)
        assertTrue(green(color) in 0..255)
        assertTrue(blue(color) in 0..255)
    }

    @Test
    fun largeSupportedFrameCountDoesNotOverflow() {
        val white = frame(1, 1, rgb(255, 255, 255))

        val output = averageArgbFrames(List(100_000) { white })

        assertEquals(rgb(255, 255, 255), output.pixels.single())
    }

    @Test
    fun maximumFrameNumberDoesNotOverflowChannelAccumulator() {
        val average = intArrayOf(rgb(255, 255, 255))
        val accumulator = ArgbAverageAccumulator(
            pixelCount = 1,
            maximumFrameCount = Int.MAX_VALUE
        )

        updateRunningAverageArgb(
            accumulator = accumulator,
            averagePixels = average,
            nextPixels = intArrayOf(rgb(255, 255, 255)),
            frameNumber = Int.MAX_VALUE,
            pixelCount = 1
        )

        assertEquals(rgb(255, 255, 255), average.single())
    }

    @Test
    fun mismatchedDimensionsAreRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            averageArgbFrames(
                listOf(
                    frame(1, 2, rgb(1, 2, 3), rgb(4, 5, 6)),
                    frame(2, 1, rgb(1, 2, 3), rgb(4, 5, 6))
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("equal frame dimensions"))
    }

    @Test
    fun mismatchedPixelArrayLengthIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            averageArgbFrames(listOf(frame(2, 1, rgb(1, 2, 3))))
        }

        assertTrue(error.message.orEmpty().contains("pixel count"))
    }

    @Test
    fun inputPixelArraysAreNotModified() {
        val firstPixels = intArrayOf(argb(12, 10, 20, 30))
        val secondPixels = intArrayOf(argb(34, 30, 40, 50))
        val firstSnapshot = firstPixels.copyOf()
        val secondSnapshot = secondPixels.copyOf()

        averageArgbFrames(
            listOf(
                AveragePixelFrame(1, 1, firstPixels),
                AveragePixelFrame(1, 1, secondPixels)
            )
        )

        assertArrayEquals(firstSnapshot, firstPixels)
        assertArrayEquals(secondSnapshot, secondPixels)
    }

    @Test
    fun inputFrameListIsNotModified() {
        val frames = mutableListOf(frame(1, 1, rgb(10, 20, 30)))
        val snapshot = frames.toList()

        averageArgbFrames(frames)

        assertEquals(snapshot, frames)
    }

    @Test
    fun outputDoesNotReuseInputPixelArray() {
        val input = frame(1, 1, rgb(10, 20, 30))

        val output = averageArgbFrames(listOf(input))

        assertNotSame(input.pixels, output.pixels)
    }

    @Test
    fun outputDimensionsMatchSourceDimensions() {
        val output = averageArgbFrames(
            listOf(frame(2, 1, rgb(10, 20, 30), rgb(40, 50, 60)))
        )

        assertEquals(2, output.width)
        assertEquals(1, output.height)
    }

    @Test
    fun roundingUsesExactSumInsteadOfRoundedIntermediate() {
        val frames = listOf(
            frame(1, 1, rgb(0, 0, 0)),
            frame(1, 1, rgb(1, 1, 1)),
            frame(1, 1, rgb(0, 0, 0))
        )

        val output = averageArgbFrames(frames)

        assertEquals(rgb(0, 0, 0), output.pixels.single())
    }

    @Test
    fun frameOrderDoesNotChangeRoundedAverage() {
        val firstOrder = listOf(
            frame(1, 1, rgb(0, 2, 1)),
            frame(1, 1, rgb(0, 0, 1)),
            frame(1, 1, rgb(1, 0, 0))
        )
        val secondOrder = listOf(firstOrder[2], firstOrder[0], firstOrder[1])

        assertArrayEquals(
            averageArgbFrames(firstOrder).pixels,
            averageArgbFrames(secondOrder).pixels
        )
        assertEquals(rgb(0, 1, 1), averageArgbFrames(firstOrder).pixels.single())
    }

    @Test
    fun accumulatorOffsetsKeepIndependentExactRemainders() {
        val accumulator = ArgbAverageAccumulator(pixelCount = 2, maximumFrameCount = 3)
        val firstPixel = intArrayOf(rgb(0, 0, 0))
        val secondPixel = intArrayOf(rgb(1, 1, 1))

        updateRunningAverageArgb(
            accumulator,
            firstPixel,
            intArrayOf(rgb(1, 1, 1)),
            frameNumber = 2,
            pixelCount = 1,
            accumulatorOffset = 0
        )
        updateRunningAverageArgb(
            accumulator,
            secondPixel,
            intArrayOf(rgb(0, 0, 0)),
            frameNumber = 2,
            pixelCount = 1,
            accumulatorOffset = 1
        )
        updateRunningAverageArgb(
            accumulator,
            firstPixel,
            intArrayOf(rgb(0, 0, 0)),
            frameNumber = 3,
            pixelCount = 1,
            accumulatorOffset = 0
        )
        updateRunningAverageArgb(
            accumulator,
            secondPixel,
            intArrayOf(rgb(1, 1, 1)),
            frameNumber = 3,
            pixelCount = 1,
            accumulatorOffset = 1
        )

        assertEquals(rgb(0, 0, 0), firstPixel.single())
        assertEquals(rgb(1, 1, 1), secondPixel.single())
    }

    @Test
    fun largeSeriesFallbackRemainsExactAndOrderIndependent() {
        val frames = (0 until 1_025).map { index ->
            frame(
                1,
                1,
                rgb(index % 256, (index * 3) % 256, (index * 7) % 256)
            )
        }
        val forward = averageArgbFrames(frames)
        val reversed = averageArgbFrames(frames.reversed())

        assertArrayEquals(forward.pixels, reversed.pixels)
        assertEquals(
            rgb(
                roundedMean(frames) { red(it) },
                roundedMean(frames) { green(it) },
                roundedMean(frames) { blue(it) }
            ),
            forward.pixels.single()
        )
    }

    @Test
    fun outputAlphaIsOpaque() {
        val output = averageArgbFrames(
            listOf(frame(1, 1, argb(0, 10, 20, 30)), frame(1, 1, argb(64, 30, 40, 50)))
        )

        assertEquals(255, output.pixels.single() ushr 24 and 0xFF)
    }

    private fun frame(width: Int, height: Int, vararg pixels: Int) =
        AveragePixelFrame(width, height, pixels)

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        argb(255, red, green, blue)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun red(color: Int): Int = color ushr 16 and 0xFF
    private fun green(color: Int): Int = color ushr 8 and 0xFF
    private fun blue(color: Int): Int = color and 0xFF

    private fun roundedMean(
        frames: List<AveragePixelFrame>,
        channel: (Int) -> Int
    ): Int {
        val sum = frames.sumOf { channel(it.pixels.single()).toLong() }
        return ((sum + frames.size / 2L) / frames.size).toInt()
    }
}
