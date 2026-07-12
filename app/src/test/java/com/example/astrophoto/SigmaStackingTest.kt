package com.example.astrophoto

import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SigmaStackingTest {
    @Test
    fun emptyInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            sigmaClipArgbFrames(emptyList(), 2.0)
        }
    }

    @Test
    fun oneFrameIsPreserved() {
        assertEquals(rgb(10, 20, 30), sigma(listOf(rgb(10, 20, 30)), 2.0))
    }

    @Test
    fun identicalFramesArePreserved() {
        assertEquals(rgb(20, 40, 60), sigma(List(5) { rgb(20, 40, 60) }, 2.0))
    }

    @Test
    fun zeroStandardDeviationIsFinite() {
        val output = sigma(List(4) { rgb(80, 80, 80) }, 1.5)

        assertEquals(rgb(80, 80, 80), output)
    }

    @Test
    fun brightOutlierIsRejectedAtConfiguredThreshold() {
        val output = sigma(listOf(10, 10, 10, 10, 255).map(::gray), 1.5)

        assertEquals(gray(10), output)
    }

    @Test
    fun darkOutlierIsRejectedAtConfiguredThreshold() {
        val output = sigma(listOf(0, 200, 200, 200, 200).map(::gray), 1.5)

        assertEquals(gray(200), output)
    }

    @Test
    fun valuesNearCentreRemain() {
        val output = sigma(listOf(10, 11, 12, 13, 200).map(::gray), 1.5)

        assertEquals(gray(12), output)
    }

    @Test
    fun rgbChannelsAreClippedIndependently() {
        val colors = mutableListOf<Int>()
        repeat(4) { colors += rgb(10, 20, 30) }
        colors += rgb(255, 20, 30)
        colors += rgb(10, 255, 30)
        colors += rgb(10, 20, 255)

        assertEquals(rgb(10, 20, 30), sigma(colors, 1.5))
    }

    @Test
    fun multipleIterationsAreDeterministic() {
        val frames = listOf(10, 11, 12, 13, 30, 200).map { frame(1, 1, gray(it)) }

        val first = sigmaClipArgbFrames(frames, 1.5, iterations = 3)
        val second = sigmaClipArgbFrames(frames, 1.5, iterations = 3)

        assertArrayEquals(first.pixels, second.pixels)
    }

    @Test
    fun thresholdBoundaryIsInclusive() {
        val threshold = sqrt(2.0)

        val output = sigma(listOf(0, 0, 10).map(::gray), threshold)

        assertEquals(gray(3), output)
    }

    @Test
    fun emptyAcceptedSetFallsBackToCurrentMean() {
        val output = sigma(listOf(gray(0), gray(255)), 0.01)

        assertEquals(gray(128), output)
    }

    @Test
    fun outputChannelsAreClamped() {
        val color = sigma(listOf(gray(0), gray(255)), 0.01)

        assertTrue((color ushr 16 and 0xFF) in 0..255)
        assertTrue((color ushr 8 and 0xFF) in 0..255)
        assertTrue((color and 0xFF) in 0..255)
    }

    @Test
    fun alphaIsAlwaysOpaque() {
        val frames = listOf(
            frame(1, 1, argb(0, 10, 20, 30)),
            frame(1, 1, argb(64, 30, 40, 50))
        )

        val output = sigmaClipArgbFrames(frames, 2.0)

        assertEquals(255, output.pixels.single() ushr 24 and 0xFF)
    }

    @Test
    fun mismatchedDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            sigmaClipArgbFrames(
                listOf(
                    frame(1, 2, gray(10), gray(20)),
                    frame(2, 1, gray(10), gray(20))
                ),
                2.0
            )
        }
    }

    @Test
    fun invalidPixelArrayLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            sigmaClipArgbFrames(listOf(frame(2, 1, gray(10))), 2.0)
        }
    }

    @Test
    fun inputsRemainUnchanged() {
        val first = intArrayOf(argb(12, 10, 20, 30))
        val second = intArrayOf(argb(34, 30, 40, 50))
        val firstSnapshot = first.copyOf()
        val secondSnapshot = second.copyOf()

        sigmaClipArgbFrames(
            listOf(AveragePixelFrame(1, 1, first), AveragePixelFrame(1, 1, second)),
            2.0
        )

        assertArrayEquals(firstSnapshot, first)
        assertArrayEquals(secondSnapshot, second)
    }

    @Test
    fun deterministicFramesMatchIndependentReference() {
        val random = Random(1707L)
        val frames = List(9) {
            frame(3, 2, *IntArray(6) { rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) })
        }

        val output = sigmaClipArgbFrames(frames, 1.5, iterations = 3)

        assertArrayEquals(referenceSigma(frames, 1.5, 3).pixels, output.pixels)
    }

    @Test
    fun tinyThresholdDoesNotProduceArithmeticFailure() {
        val output = sigma(listOf(gray(0), gray(127), gray(255)), Double.MIN_VALUE)

        assertEquals(gray(127), output)
    }

    private fun sigma(colors: List<Int>, threshold: Double): Int =
        sigmaClipArgbFrames(colors.map { frame(1, 1, it) }, threshold).pixels.single()

    private fun referenceSigma(
        frames: List<AveragePixelFrame>,
        threshold: Double,
        iterations: Int
    ): AveragePixelFrame {
        val output = IntArray(frames.first().pixels.size)
        output.indices.forEach { pixelIndex ->
            val colors = frames.map { it.pixels[pixelIndex] }
            val red = referenceSigmaChannel(colors.map { it ushr 16 and 0xFF }, threshold, iterations)
            val green = referenceSigmaChannel(colors.map { it ushr 8 and 0xFF }, threshold, iterations)
            val blue = referenceSigmaChannel(colors.map { it and 0xFF }, threshold, iterations)
            output[pixelIndex] = rgb(red, green, blue)
        }
        return AveragePixelFrame(frames.first().width, frames.first().height, output)
    }

    private fun referenceSigmaChannel(
        input: List<Int>,
        threshold: Double,
        iterations: Int
    ): Int {
        var accepted = input.map(Int::toDouble)
        repeat(iterations) {
            val mean = accepted.average()
            val variance = accepted.sumOf { value ->
                val difference = value - mean
                difference * difference
            } / accepted.size
            val limit = threshold * sqrt(variance)
            if (limit == 0.0) return mean.roundToInt().coerceIn(0, 255)
            val next = accepted.filter { abs(it - mean) <= limit }
            if (next.isEmpty() || next.size == accepted.size) {
                return mean.roundToInt().coerceIn(0, 255)
            }
            accepted = next
        }
        return accepted.average().roundToInt().coerceIn(0, 255)
    }

    private fun gray(value: Int): Int = rgb(value, value, value)

    private fun frame(width: Int, height: Int, vararg pixels: Int) =
        AveragePixelFrame(width, height, pixels)

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        argb(255, red, green, blue)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
