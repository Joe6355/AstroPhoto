package com.example.astrophoto

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MedianStackingTest {
    @Test
    fun emptyInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            medianArgbFrames(emptyList())
        }
    }

    @Test
    fun oneFramePreservesRgb() {
        val output = medianArgbFrames(listOf(frame(1, 1, argb(12, 10, 20, 30))))

        assertEquals(rgb(10, 20, 30), output.pixels.single())
    }

    @Test
    fun oddFrameCountUsesMiddleValue() {
        val output = median(10, 30, 20)

        assertEquals(rgb(20, 20, 20), output)
    }

    @Test
    fun twoFrameMedianUsesFloorMidpoint() {
        val output = median(0, 255)

        assertEquals(rgb(127, 127, 127), output)
    }

    @Test
    fun fourFrameMedianUsesMiddlePair() {
        val output = median(30, 0, 20, 10)

        assertEquals(rgb(15, 15, 15), output)
    }

    @Test
    fun unsortedValuesAreHandled() {
        val output = median(90, 10, 70, 30, 50)

        assertEquals(rgb(50, 50, 50), output)
    }

    @Test
    fun rgbChannelsAreIndependent() {
        val output = medianArgbFrames(
            listOf(
                frame(1, 1, rgb(0, 100, 200)),
                frame(1, 1, rgb(50, 0, 100)),
                frame(1, 1, rgb(100, 200, 0))
            )
        )

        assertEquals(rgb(50, 100, 100), output.pixels.single())
    }

    @Test
    fun blackAndWhiteUseCurrentEvenRule() {
        assertEquals(rgb(127, 127, 127), median(0, 255))
    }

    @Test
    fun alphaIsAlwaysOpaque() {
        val output = medianArgbFrames(
            listOf(frame(1, 1, argb(0, 10, 20, 30)), frame(1, 1, argb(64, 30, 40, 50)))
        )

        assertEquals(255, output.pixels.single() ushr 24 and 0xFF)
    }

    @Test
    fun mismatchedDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            medianArgbFrames(
                listOf(
                    frame(1, 2, rgb(1, 2, 3), rgb(4, 5, 6)),
                    frame(2, 1, rgb(1, 2, 3), rgb(4, 5, 6))
                )
            )
        }
    }

    @Test
    fun invalidPixelArrayLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            medianArgbFrames(listOf(frame(2, 1, rgb(1, 2, 3))))
        }
    }

    @Test
    fun inputArraysRemainUnchanged() {
        val first = intArrayOf(argb(12, 10, 20, 30))
        val second = intArrayOf(argb(34, 30, 40, 50))
        val firstSnapshot = first.copyOf()
        val secondSnapshot = second.copyOf()

        medianArgbFrames(
            listOf(AveragePixelFrame(1, 1, first), AveragePixelFrame(1, 1, second))
        )

        assertArrayEquals(firstSnapshot, first)
        assertArrayEquals(secondSnapshot, second)
    }

    @Test
    fun outputDoesNotReuseInputArray() {
        val input = frame(1, 1, rgb(10, 20, 30))

        val output = medianArgbFrames(listOf(input))

        assertNotSame(input.pixels, output.pixels)
    }

    @Test
    fun deterministicFramesMatchIndependentReference() {
        val random = Random(707L)
        val frames = List(7) {
            frame(3, 2, *IntArray(6) { rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) })
        }

        val output = medianArgbFrames(frames)

        assertArrayEquals(referenceMedian(frames).pixels, output.pixels)
    }

    @Test
    fun brightOutlierDoesNotControlMedian() {
        assertEquals(rgb(11, 11, 11), median(10, 11, 12, 255))
    }

    @Test
    fun darkOutlierDoesNotControlMedian() {
        assertEquals(rgb(100, 100, 100), median(0, 100, 101))
    }

    private fun median(vararg values: Int): Int = medianArgbFrames(
        values.map { frame(1, 1, rgb(it, it, it)) }
    ).pixels.single()

    private fun referenceMedian(frames: List<AveragePixelFrame>): AveragePixelFrame {
        val output = IntArray(frames.first().pixels.size)
        output.indices.forEach { pixelIndex ->
            val colors = frames.map { it.pixels[pixelIndex] }
            val red = referenceMedianChannel(colors.map { it ushr 16 and 0xFF })
            val green = referenceMedianChannel(colors.map { it ushr 8 and 0xFF })
            val blue = referenceMedianChannel(colors.map { it and 0xFF })
            output[pixelIndex] = rgb(red, green, blue)
        }
        return AveragePixelFrame(frames.first().width, frames.first().height, output)
    }

    private fun referenceMedianChannel(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    private fun frame(width: Int, height: Int, vararg pixels: Int) =
        AveragePixelFrame(width, height, pixels)

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        argb(255, red, green, blue)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
