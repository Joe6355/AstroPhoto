package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPreservingSigmaTest {
    @Test
    fun identicalFramesArePreserved() {
        val frames = List(6) { frame(rgb(20, 40, 60)) }
        assertEquals(rgb(20, 40, 60), signalPreservingSigmaStack(frames, 2.0).pixels.single())
    }

    @Test
    fun isolatedHotPixelIsRejected() {
        val frames = List(5) { frame(rgb(10, 10, 10)) } + frame(rgb(255, 255, 255))
        assertEquals(rgb(10, 10, 10), signalPreservingSigmaStack(frames, 2.0).pixels.single())
    }

    @Test
    fun alignedStarPresentInMostFramesIsRetained() {
        val frames = listOf(160, 160, 160, 160, 10, 10).map { frame(gray(it)) }
        assertTrue(channel(signalPreservingSigmaStack(frames, 2.0).pixels.single()) >= 100)
    }

    @Test
    fun twoConsistentBrightSamplesAreNotErased() {
        val frames = listOf(180, 175, 10, 10, 10, 10).map { frame(gray(it)) }
        assertTrue(channel(signalPreservingSigmaStack(frames, 2.0).pixels.single()) > 10)
    }

    @Test
    fun zeroVarianceIsStable() {
        assertEquals(42, signalPreservingSigmaChannel(intArrayOf(42, 42, 42, 42), 2.5))
    }

    @Test
    fun oneFrameIsPredictable() {
        val input = frame(rgb(5, 100, 240))
        assertArrayEquals(input.pixels, signalPreservingSigmaStack(listOf(input), 2.0).pixels)
    }

    @Test
    fun rgbChannelsAreProcessedIndependently() {
        val frames = listOf(
            frame(rgb(10, 100, 30)), frame(rgb(10, 100, 30)),
            frame(rgb(10, 100, 30)), frame(rgb(250, 100, 30))
        )
        assertEquals(rgb(10, 100, 30), signalPreservingSigmaStack(frames, 2.0).pixels.single())
    }

    @Test
    fun outputAlphaIsOpaque() {
        val frames = listOf(frame(argb(0, 20, 30, 40)), frame(argb(12, 20, 30, 40)))
        assertEquals(255, signalPreservingSigmaStack(frames, 2.0).pixels.single() ushr 24 and 0xFF)
    }

    @Test
    fun invalidInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { signalPreservingSigmaStack(emptyList(), 2.0) }
        assertThrows(IllegalArgumentException::class.java) {
            signalPreservingSigmaStack(listOf(frame(gray(1))), Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signalPreservingSigmaStack(
                listOf(ArgbPixelImage(1, 1, intArrayOf(gray(1))), ArgbPixelImage(2, 1, intArrayOf(gray(1), gray(1)))),
                2.0
            )
        }
    }

    @Test
    fun inputsAreNotModified() {
        val frames = listOf(frame(gray(10)), frame(gray(20)), frame(gray(30)))
        val snapshots = frames.map { it.pixels.copyOf() }
        signalPreservingSigmaStack(frames, 2.0)
        frames.indices.forEach { assertArrayEquals(snapshots[it], frames[it].pixels) }
    }

    @Test
    fun outputIsDeterministicAndChannelsRemainFiniteBytes() {
        val frames = listOf(10, 20, 30, 100, 220, 25).map { frame(gray(it)) }
        val first = signalPreservingSigmaStack(frames, 2.5)
        val second = signalPreservingSigmaStack(frames, 2.5)
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertArrayEquals(first.pixels, second.pixels)
        first.pixels.forEach { color ->
            assertTrue((color ushr 16 and 0xFF) in 0..255)
            assertTrue((color ushr 8 and 0xFF) in 0..255)
            assertTrue((color and 0xFF) in 0..255)
        }
    }

    private fun frame(color: Int) = ArgbPixelImage(1, 1, intArrayOf(color))
    private fun gray(value: Int) = rgb(value, value, value)
    private fun rgb(red: Int, green: Int, blue: Int) = argb(255, red, green, blue)
    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    private fun channel(color: Int) = color and 0xFF
}
