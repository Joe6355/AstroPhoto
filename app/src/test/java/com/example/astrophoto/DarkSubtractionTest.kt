package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DarkSubtractionTest {
    @Test
    fun zeroMasterDarkPreservesLightRgb() {
        val output = subtractMasterDark(
            light = frame(1, 1, rgb(10, 20, 30)),
            masterDark = frame(1, 1, rgb(0, 0, 0)),
            neutralOffset = 0
        )

        assertEquals(rgb(10, 20, 30), output.pixels.single())
    }

    @Test
    fun equalLightAndDarkPreserveCurrentStrength() {
        val output = subtractMasterDark(
            light = frame(1, 1, rgb(100, 100, 100)),
            masterDark = frame(1, 1, rgb(100, 100, 100)),
            neutralOffset = 0
        )

        assertEquals(rgb(35, 35, 35), output.pixels.single())
    }

    @Test
    fun neutralOffsetIsAddedAfterSubtraction() {
        val output = subtractMasterDark(
            light = frame(1, 1, rgb(100, 100, 100)),
            masterDark = frame(1, 1, rgb(100, 100, 100)),
            neutralOffset = 16
        )

        assertEquals(rgb(51, 51, 51), output.pixels.single())
    }

    @Test
    fun lowerBoundIsClipped() {
        val output = subtractMasterDark(
            light = frame(1, 1, rgb(10, 10, 10)),
            masterDark = frame(1, 1, rgb(255, 255, 255)),
            neutralOffset = 0
        )

        assertEquals(rgb(0, 0, 0), output.pixels.single())
    }

    @Test
    fun upperBoundIsClipped() {
        val output = subtractMasterDark(
            light = frame(1, 1, rgb(250, 250, 250)),
            masterDark = frame(1, 1, rgb(0, 0, 0)),
            neutralOffset = 16
        )

        assertEquals(rgb(255, 255, 255), output.pixels.single())
    }

    @Test
    fun channelsAreSubtractedIndependently() {
        val output = subtractMasterDark(
            light = frame(1, 1, rgb(200, 100, 50)),
            masterDark = frame(1, 1, rgb(100, 20, 80)),
            neutralOffset = 8
        )

        assertEquals(rgb(143, 95, 6), output.pixels.single())
    }

    @Test
    fun currentDarkRoundingIsPreserved() {
        val output = subtractMasterDark(
            light = frame(1, 1, rgb(10, 10, 10)),
            masterDark = frame(1, 1, rgb(1, 1, 1)),
            neutralOffset = 0
        )

        assertEquals(rgb(9, 9, 9), output.pixels.single())
    }

    @Test
    fun outputAlphaIsAlwaysOpaque() {
        val output = subtractMasterDark(
            light = frame(1, 1, argb(64, 100, 100, 100)),
            masterDark = frame(1, 1, argb(12, 10, 10, 10)),
            neutralOffset = 0
        )

        assertEquals(255, output.pixels.single() ushr 24 and 0xFF)
    }

    @Test
    fun dimensionMismatchIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            subtractMasterDark(
                light = frame(1, 2, rgb(10, 10, 10), rgb(20, 20, 20)),
                masterDark = frame(2, 1, rgb(0, 0, 0), rgb(0, 0, 0)),
                neutralOffset = 0
            )
        }
    }

    @Test
    fun invalidLightPixelArrayLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            subtractMasterDark(
                light = frame(2, 1, rgb(10, 10, 10)),
                masterDark = frame(2, 1, rgb(0, 0, 0), rgb(0, 0, 0)),
                neutralOffset = 0
            )
        }
    }

    @Test
    fun invalidMasterDarkPixelArrayLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            subtractMasterDark(
                light = frame(2, 1, rgb(10, 10, 10), rgb(20, 20, 20)),
                masterDark = frame(2, 1, rgb(0, 0, 0)),
                neutralOffset = 0
            )
        }
    }

    @Test
    fun lightInputIsNotModified() {
        val pixels = intArrayOf(argb(12, 100, 100, 100))
        val snapshot = pixels.copyOf()

        subtractMasterDark(
            light = AveragePixelFrame(1, 1, pixels),
            masterDark = frame(1, 1, rgb(10, 10, 10)),
            neutralOffset = 0
        )

        assertArrayEquals(snapshot, pixels)
    }

    @Test
    fun masterDarkInputIsNotModified() {
        val pixels = intArrayOf(argb(12, 10, 10, 10))
        val snapshot = pixels.copyOf()

        subtractMasterDark(
            light = frame(1, 1, rgb(100, 100, 100)),
            masterDark = AveragePixelFrame(1, 1, pixels),
            neutralOffset = 0
        )

        assertArrayEquals(snapshot, pixels)
    }

    private fun frame(width: Int, height: Int, vararg pixels: Int) =
        AveragePixelFrame(width, height, pixels)

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        argb(255, red, green, blue)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
