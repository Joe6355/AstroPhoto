package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterDarkStackingTest {
    @Test
    fun emptyDarkInputHasClearFailure() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            buildMasterDark(emptyList())
        }

        assertTrue(error.message.orEmpty().contains("at least one dark frame"))
    }

    @Test
    fun oneDarkFramePreservesRgbValues() {
        val output = buildMasterDark(
            listOf(frame(2, 1, argb(12, 10, 20, 30), argb(34, 40, 50, 60)))
        )

        assertArrayEquals(
            intArrayOf(rgb(10, 20, 30), rgb(40, 50, 60)),
            output.pixels
        )
    }

    @Test
    fun identicalDarkFramesPreserveRgbValues() {
        val output = buildMasterDark(
            listOf(frame(1, 1, rgb(20, 40, 60)), frame(1, 1, rgb(20, 40, 60)))
        )

        assertEquals(rgb(20, 40, 60), output.pixels.single())
    }

    @Test
    fun differentDarkFramesProduceExpectedMasterDark() {
        val output = buildMasterDark(
            listOf(frame(1, 1, rgb(10, 20, 30)), frame(1, 1, rgb(20, 40, 60)))
        )

        assertEquals(rgb(15, 30, 45), output.pixels.single())
    }

    @Test
    fun threeDarkFramesProduceExpectedMasterDark() {
        val output = buildMasterDark(
            listOf(
                frame(1, 1, rgb(0, 0, 0)),
                frame(1, 1, rgb(10, 20, 30)),
                frame(1, 1, rgb(20, 40, 60))
            )
        )

        assertEquals(rgb(10, 20, 30), output.pixels.single())
    }

    @Test
    fun blackAndWhiteProduceRoundedMidpoint() {
        val output = buildMasterDark(
            listOf(frame(1, 1, rgb(0, 0, 0)), frame(1, 1, rgb(255, 255, 255)))
        )

        assertEquals(rgb(128, 128, 128), output.pixels.single())
    }

    @Test
    fun channelsAreAveragedIndependently() {
        val output = buildMasterDark(
            listOf(frame(1, 1, rgb(200, 0, 40)), frame(1, 1, rgb(0, 100, 60)))
        )

        assertEquals(rgb(100, 50, 50), output.pixels.single())
    }

    @Test
    fun outputChannelsStayInByteRange() {
        val color = buildMasterDark(
            listOf(frame(1, 1, rgb(0, 255, 0)), frame(1, 1, rgb(255, 0, 255)))
        ).pixels.single()

        assertTrue(red(color) in 0..255)
        assertTrue(green(color) in 0..255)
        assertTrue(blue(color) in 0..255)
    }

    @Test
    fun supportedDarkFrameCountDoesNotOverflow() {
        val white = frame(1, 1, rgb(255, 255, 255))

        val output = buildMasterDark(List(100_000) { white })

        assertEquals(rgb(255, 255, 255), output.pixels.single())
    }

    @Test
    fun maximumFrameNumberDoesNotOverflowAccumulator() {
        val average = intArrayOf(rgb(255, 255, 255))
        val accumulator = ArgbAverageAccumulator(
            pixelCount = 1,
            maximumFrameCount = Int.MAX_VALUE
        )

        updateMasterDarkRunningAverageArgb(
            accumulator = accumulator,
            averagePixels = average,
            nextPixels = intArrayOf(rgb(255, 255, 255)),
            frameNumber = Int.MAX_VALUE,
            pixelCount = 1
        )

        assertEquals(rgb(255, 255, 255), average.single())
    }

    @Test
    fun mismatchedDarkDimensionsAreRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            buildMasterDark(
                listOf(
                    frame(1, 2, rgb(1, 2, 3), rgb(4, 5, 6)),
                    frame(2, 1, rgb(1, 2, 3), rgb(4, 5, 6))
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("equal frame dimensions"))
    }

    @Test
    fun invalidDarkPixelArrayLengthIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            buildMasterDark(listOf(frame(2, 1, rgb(1, 2, 3))))
        }

        assertTrue(error.message.orEmpty().contains("pixel count"))
    }

    @Test
    fun inputPixelArraysAreNotModified() {
        val firstPixels = intArrayOf(argb(12, 10, 20, 30))
        val secondPixels = intArrayOf(argb(34, 30, 40, 50))
        val firstSnapshot = firstPixels.copyOf()
        val secondSnapshot = secondPixels.copyOf()

        buildMasterDark(
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

        buildMasterDark(frames)

        assertEquals(snapshot, frames)
    }

    @Test
    fun outputDoesNotReuseMutableInputArray() {
        val input = frame(1, 1, rgb(10, 20, 30))

        val output = buildMasterDark(listOf(input))

        assertNotSame(input.pixels, output.pixels)
    }

    @Test
    fun outputDimensionsMatchDarkFrames() {
        val output = buildMasterDark(
            listOf(frame(2, 1, rgb(10, 20, 30), rgb(40, 50, 60)))
        )

        assertEquals(2, output.width)
        assertEquals(1, output.height)
    }

    @Test
    fun outputAlphaIsAlwaysOpaque() {
        val output = buildMasterDark(
            listOf(frame(1, 1, argb(0, 10, 20, 30)), frame(1, 1, argb(64, 30, 40, 50)))
        )

        assertEquals(255, output.pixels.single() ushr 24 and 0xFF)
    }

    @Test
    fun roundingUsesExactDarkSum() {
        val output = buildMasterDark(
            listOf(
                frame(1, 1, rgb(0, 0, 0)),
                frame(1, 1, rgb(1, 1, 1)),
                frame(1, 1, rgb(0, 0, 0))
            )
        )

        assertEquals(rgb(0, 0, 0), output.pixels.single())
    }

    @Test
    fun frameOrderDoesNotChangeMasterDark() {
        val firstOrder = buildMasterDark(
            listOf(
                frame(1, 1, rgb(0, 0, 0)),
                frame(1, 1, rgb(0, 0, 0)),
                frame(1, 1, rgb(1, 1, 1))
            )
        )
        val secondOrder = buildMasterDark(
            listOf(
                frame(1, 1, rgb(0, 0, 0)),
                frame(1, 1, rgb(1, 1, 1)),
                frame(1, 1, rgb(0, 0, 0))
            )
        )

        assertArrayEquals(firstOrder.pixels, secondOrder.pixels)
        assertEquals(rgb(0, 0, 0), firstOrder.pixels.single())
    }

    @Test
    fun masterDarkOnlyAveragesDarkFramesWithoutSubtraction() {
        val output = buildMasterDark(
            listOf(frame(1, 1, rgb(100, 100, 100)), frame(1, 1, rgb(40, 40, 40)))
        )

        assertEquals(rgb(70, 70, 70), output.pixels.single())
    }

    @Test
    fun manualMasterDarkIntegrationKernelUsesComponentMath() {
        val average = intArrayOf(rgb(10, 20, 30))
        val accumulator = ArgbAverageAccumulator(pixelCount = 1, maximumFrameCount = 2)

        updateMasterDarkRunningAverageArgb(
            accumulator = accumulator,
            averagePixels = average,
            nextPixels = intArrayOf(rgb(20, 40, 60)),
            frameNumber = 2,
            pixelCount = 1
        )

        assertEquals(rgb(15, 30, 45), average.single())
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
}
