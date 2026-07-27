package com.example.astrophoto.processing.jpeg.v2.output

import com.example.astrophoto.findUniqueProcessedResultName
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnhancedPngStorageHardeningTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validatedTemporaryIsPublishedWithoutLeavingTemporaryFile() {
        val target = File(temporaryFolder.root, "Enhanced_20260727_120000.png")
        val temporary = File(temporaryFolder.root, "${target.name}.tmp")

        val encodedBytes = CrashSafePngFilePublisher.publish(
            source = source(),
            target = target,
            temporary = temporary
        )

        assertTrue(target.isFile)
        assertFalse(temporary.exists())
        assertEquals(target.length(), encodedBytes)
        val validated = PngStructureValidator.validate(target, 3, 2)
        assertEquals(3, validated.width)
        assertEquals(2, validated.height)
    }

    @Test
    fun encodingFailureRemovesOnlyTemporaryAndNeverPublishesTarget() {
        val target = File(temporaryFolder.root, "Enhanced_failure.png")
        val temporary = File(temporaryFolder.root, "${target.name}.tmp")
        val failing = object : PngImageSource {
            override val width = 3
            override val height = 2

            override fun readArgbRow(y: Int, destination: IntArray) {
                if (y == 1) error("simulated_row_failure")
                destination.fill(0xFF102030.toInt())
            }
        }

        val error = assertThrows(IllegalStateException::class.java) {
            CrashSafePngFilePublisher.publish(failing, target, temporary)
        }

        assertEquals("simulated_row_failure", error.message)
        assertFalse(target.exists())
        assertFalse(temporary.exists())
    }

    @Test
    fun existingFinalFileIsNeverOverwritten() {
        val target = File(temporaryFolder.root, "Enhanced_existing.png")
        val original = byteArrayOf(7, 8, 9)
        target.writeBytes(original)
        val temporary = File(temporaryFolder.root, "${target.name}.tmp")

        assertThrows(IllegalArgumentException::class.java) {
            CrashSafePngFilePublisher.publish(source(), target, temporary)
        }

        assertArrayEquals(original, target.readBytes())
        assertFalse(temporary.exists())
    }

    @Test
    fun truncatedEnhancedPngIsRejectedBeforePublicationValidation() {
        val validBytes = java.io.ByteArrayOutputStream().use { output ->
            PngStreamEncoder.encode(source(), output)
            output.toByteArray()
        }
        val truncated = validBytes.copyOf(validBytes.size - 5)

        assertThrows(Exception::class.java) {
            PngStructureValidator.validate(
                ByteArrayInputStream(truncated),
                expectedWidth = 3,
                expectedHeight = 2
            )
        }
    }

    @Test
    fun crcValidButCorruptCompressedImageDataIsRejectedAsUnreadable() {
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            PngStreamEncoder.encode(source(), output)
            output.toByteArray()
        }
        corruptFirstImageDataChunkAndRepairPngCrc(bytes)

        assertThrows(Exception::class.java) {
            PngStructureValidator.validate(
                ByteArrayInputStream(bytes),
                expectedWidth = 3,
                expectedHeight = 2
            )
        }
    }

    @Test
    fun multiChunkFullImageStreamIsInflatedAndValidated() {
        val width = 257
        val height = 131
        val random = Random(0x504E4756414C4944L)
        val encoded = java.io.ByteArrayOutputStream().use { output ->
            PngStreamEncoder.encode(
                ArgbArrayPngSource(
                    width = width,
                    height = height,
                    pixels = IntArray(width * height) { random.nextInt() }
                ),
                output
            )
            output.toByteArray()
        }

        val result = PngStructureValidator.validate(
            ByteArrayInputStream(encoded),
            expectedWidth = width,
            expectedHeight = height
        )

        assertTrue(result.chunkCount > 3)
    }

    @Test
    fun malformedDimensionsAreRejectedBeforeAnyPixelAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidPngDimensions(Int.MAX_VALUE, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireValidPngDimensions(32_768, 32_768)
        }
    }

    @Test
    fun enhancedFilenameCollisionIsResolvedDeterministically() {
        val occupied = setOf(
            "Enhanced_20260727_120000.png",
            "Enhanced_20260727_120000_01.png"
        )

        val selected = findUniqueProcessedResultName(
            "Enhanced_20260727_120000.png"
        ) { it in occupied }

        assertEquals("Enhanced_20260727_120000_02.png", selected)
    }

    private fun source(): PngImageSource = ArgbArrayPngSource(
        width = 3,
        height = 2,
        pixels = intArrayOf(
            0xFF000000.toInt(),
            0xFF102030.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFABCDEF.toInt(),
            0xFF010203.toInt(),
            0xFF804020.toInt()
        )
    )

    private fun corruptFirstImageDataChunkAndRepairPngCrc(png: ByteArray) {
        var offset = 8
        while (offset + 12 <= png.size) {
            val length = readInt(png, offset)
            val typeStart = offset + 4
            val dataStart = typeStart + 4
            val crcStart = dataStart + length
            val type = png.copyOfRange(typeStart, typeStart + 4)
                .toString(Charsets.US_ASCII)
            if (type == "IDAT" && length > 2) {
                png[dataStart + length / 2] =
                    (png[dataStart + length / 2].toInt() xor 0x01).toByte()
                val crc = CRC32().apply {
                    update(png, typeStart, 4 + length)
                }.value.toInt()
                writeInt(png, crcStart, crc)
                return
            }
            offset = crcStart + 4
        }
        error("No IDAT chunk found")
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
