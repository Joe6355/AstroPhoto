package com.example.astrophoto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AstroRawFormatTest {
    @Test
    fun compressedRoundTripPreservesRawSamplesAndMetadata() {
        val metadata = metadata()
        val expected = ShortArray(metadata.pixelCount) { index -> ((64 + index) shl 6).toShort() }
        val raw = ByteBuffer.allocate(expected.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        expected.forEach(raw::putShort)
        raw.flip()
        val output = ByteArrayOutputStream()

        writeAstroRaw(output, metadata, raw)
        val restored = readAstroRaw(ByteArrayInputStream(output.toByteArray()))

        assertEquals(metadata, restored.metadata)
        assertArrayEquals(expected, restored.samples)
        assertEquals(64, restored.sampleAt(0, 0))
    }

    @Test
    fun writerRespectsCurrentBufferPosition() {
        val metadata = metadata(width = 2, height = 2)
        val raw = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        raw.putInt(0x12345678)
        repeat(4) { raw.putShort(((100 + it) shl 6).toShort()) }
        raw.flip()
        raw.position(4)
        val output = ByteArrayOutputStream()

        writeAstroRaw(output, metadata, raw)
        val restored = readAstroRaw(ByteArrayInputStream(output.toByteArray()))

        assertEquals(listOf(100, 101, 102, 103), restored.samples.map { (it.toInt() and 0xFFFF) ushr 6 })
    }

    @Test
    fun writerRemovesRawPlaneRowPadding() {
        val metadata = metadata(width = 2, height = 2)
        val raw = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1)
            putShort(2)
            putInt(0x12345678)
            putShort(3)
            putShort(4)
            flip()
        }
        val output = ByteArrayOutputStream()

        writeAstroRaw(output, metadata, raw, rowStride = 8, pixelStride = 2)
        val restored = readAstroRaw(ByteArrayInputStream(output.toByteArray()))

        assertArrayEquals(shortArrayOf(1, 2, 3, 4), restored.samples)
    }

    @Test
    fun invalidMagicAndShortBuffersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            readAstroRaw(ByteArrayInputStream(ByteArray(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            writeAstroRaw(ByteArrayOutputStream(), metadata(), ByteBuffer.allocate(4))
        }
    }

    @Test
    fun sampleShiftTracksSensorWhiteLevel() {
        assertEquals(6, rawSampleShiftForWhiteLevel(1023))
        assertEquals(4, rawSampleShiftForWhiteLevel(4095))
        assertEquals(2, rawSampleShiftForWhiteLevel(16383))
    }

    @Test
    fun sampleAlignmentIsDetectedInsteadOfAssumed() {
        val rightAligned = rawBuffer(shortArrayOf(64, 128, 512, 1023))
        val leftAligned = rawBuffer(
            intArrayOf(64 shl 6, 128 shl 6, 512 shl 6, 1023 shl 6)
                .map(Int::toShort)
                .toShortArray()
        )

        assertEquals(0, detectRawSampleShift(rightAligned, 2, 2, 4, 2, 1023))
        assertEquals(6, detectRawSampleShift(leftAligned, 2, 2, 4, 2, 1023))
    }

    private fun rawBuffer(values: ShortArray): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach(::putShort)
            flip()
        }

    private fun metadata(width: Int = 4, height: Int = 3) = AstroRawMetadata(
        width = width,
        height = height,
        sampleShift = 6,
        cfaArrangement = 2,
        whiteLevel = 1023,
        blackLevels = listOf(63f, 64f, 64f, 63f),
        colorGains = listOf(2f, 1f, 1f, 1.7f),
        colorTransform = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        exposureTimeNs = 30_000_000_000L,
        sensitivityIso = 800,
        postRawSensitivityBoost = 100,
        sensorOrientation = 90
    )
}
