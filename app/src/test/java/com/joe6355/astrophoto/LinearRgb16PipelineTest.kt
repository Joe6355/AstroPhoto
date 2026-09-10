package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.FileBackedGlobalToneTransformer
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.GlobalToneAnchors
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.fileBackedDisplayPixelHash
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.fileBackedPixelHash
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import com.joe6355.astrophoto.processing.jpeg.v2.composition.FileBackedSkyForegroundComposer
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinearRgb16PipelineTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun compositionMatchesLinearBlendWithinHalfA16BitStepAndIsTileInvariant() {
        val width = 131
        val height = 67
        val count = width * height
        val reference = LongArray(count) { i -> LinearRgb16.pack(
            0.004f + i % 131 * 0.000021f, 0.013f + i % 29 * 0.000017f, 0.023f + i % 7 * 0.000019f) }
        val stacked = LongArray(count) { i -> LinearRgb16.pack(
            0.007f + i % 73 * 0.000033f, 0.037f + i % 17 * 0.000023f, 0.057f + i % 11 * 0.000041f) }
        fun input(name: String, pixels: LongArray) =
            FileBackedImageWriter(File(folder.root, name), width, height, FileBackedPixelFormat.LINEAR_RGB_16).use {
                for (y in 0 until height) it.writeLinearRow(y, pixels, y * width)
                it.finish()
            }
        val referenceImage = input("reference.rgb16", reference)
        val stackedImage = input("stacked.rgb16", stacked)
        val alpha = object : AlphaPixelSource {
            override val width = referenceImage.width
            override val height = referenceImage.height
            override fun alphaAt(x: Int, y: Int) = (x + y) % 5 / 4f
        }
        val coverage = object : AlphaPixelSource {
            override val width = referenceImage.width
            override val height = referenceImage.height
            override fun alphaAt(x: Int, y: Int) = 1f
        }
        val results = listOf(16L * 1024 * 1024, 64L * 1024).mapIndexed { index, available ->
            val reserve = 32L * 1024 * 1024
            FileBackedSkyForegroundComposer().compose(
                stackedImage, referenceImage, alpha, coverage,
                FileBackedImageWriter(File(folder.root, "composed-$index.rgb16"), width, height,
                    FileBackedPixelFormat.LINEAR_RGB_16),
                FileBackedFloatPlaneWriter(File(folder.root, "alpha-$index.f32"), width, height),
                JpegMemoryBudget(RuntimeHeapSnapshot(128L * 1024 * 1024, 128L * 1024 * 1024,
                    reserve + available), reserveBytes = reserve))
        }
        assertNotEquals(results[0].tileWidth, results[1].tileWidth)
        assertEquals(fileBackedPixelHash(results[0].image), fileBackedPixelHash(results[1].image))
        FileBackedImageReader(results[0].image).use { reader ->
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                val weight = alpha.alphaAt(x, y)
                val result = reader.linearRgbAt(x, y)
                if (weight == 0f) assertEquals(reference[i], result)
                if (weight == 1f) assertEquals(stacked[i], result)
                for (channel in listOf(LinearRgb16::red, LinearRgb16::green, LinearRgb16::blue)) {
                    val expected = channel(reference[i]).toDouble() * (1.0 - weight) + channel(stacked[i]) * weight
                    assertEquals(expected, channel(result).toDouble(), 0.5 / 65535 + 0.0000001)
                }
            }
        }
    }

    @Test fun rgb16TilesKeepSubByteDifferencesAcrossToneMapping() {
        val first = LinearRgb16.pack(0.0200f, 0.0200f, 0.0200f)
        val second = LinearRgb16.pack(0.0201f, 0.0201f, 0.0201f)
        assertEquals(LinearRgb16.toArgb(first), LinearRgb16.toArgb(second))
        assertNotEquals(first, second)
        val baseline = FileBackedImageWriter(File(folder.root, "input.rgb16"), 2, 2,
            FileBackedPixelFormat.LINEAR_RGB_16).use { writer ->
            writer.writeLinearTile(0, 0, 1, 2, longArrayOf(first, second))
            writer.writeLinearTile(1, 0, 1, 2, longArrayOf(second, first))
            writer.finish()
        }
        assertEquals(24L, baseline.file.length())
        val before = fileBackedPixelHash(baseline)
        val result = FileBackedImageWriter(File(folder.root, "tone.rgb16"), 2, 2,
            FileBackedPixelFormat.LINEAR_RGB_16).use { writer ->
            FileBackedGlobalToneTransformer().transform(baseline, writer, GlobalToneAnchors(0.0, 0.01))
        }
        FileBackedImageReader(result.image).use { reader ->
            assertTrue(LinearRgb16.red(reader.linearRgbAt(1, 0)) > LinearRgb16.red(reader.linearRgbAt(0, 0)))
        }
        assertEquals(before, fileBackedPixelHash(baseline))
    }

    @Test fun rawHashDetectsChangesHiddenByDisplayQuantization() {
        fun image(name: String, level: Float): FileBackedImage =
            FileBackedImageWriter(File(folder.root, name), 1, 1, FileBackedPixelFormat.LINEAR_RGB_16).use {
                it.writeLinearRow(0, longArrayOf(LinearRgb16.pack(level, level, level)))
                it.finish()
            }
        val first = image("first.rgb16", 0.0200f)
        val second = image("second.rgb16", 0.0201f)
        assertEquals(fileBackedDisplayPixelHash(first), fileBackedDisplayPixelHash(second))
        assertNotEquals(fileBackedPixelHash(first), fileBackedPixelHash(second))
    }

    @Test fun legacyArgbRowsPreserveAlphaAndEveryChannelCode() {
        val colors = IntArray(256) { (it shl 24) or (it shl 16) or ((255 - it) shl 8) or it }
        val image = FileBackedImageWriter(File(folder.root, "legacy.argb"), colors.size, 1).use {
            it.writeRow(0, colors)
            it.finish()
        }
        FileBackedImageReader(image).use { reader ->
            val row = IntArray(colors.size)
            reader.readArgbRow(0, row)
            assertArrayEquals(colors, row)
        }
        for (color in colors) assertEquals(color or (255 shl 24), LinearRgb16.toArgb(LinearRgb16.fromArgb(color)))
    }

    @Test fun orientedCoordinatesCoverEachDestinationOnce() {
        val expected = listOf(
            listOf(0, 1, 2, 3, 4, 5), listOf(2, 1, 0, 5, 4, 3),
            listOf(5, 4, 3, 2, 1, 0), listOf(3, 4, 5, 0, 1, 2),
            listOf(0, 3, 1, 4, 2, 5), listOf(3, 0, 4, 1, 5, 2),
            listOf(5, 2, 4, 1, 3, 0), listOf(2, 5, 1, 4, 0, 3)
        )
        JpegOrientation.entries.forEachIndexed { index, orientation ->
            val (width, height) = orientedDimensions(3, 2, orientation)
            val output = IntArray(width * height) { -1 }
            for (y in 0 until 2) for (x in 0 until 3) {
                val (ox, oy) = orientedPixel(x, y, 3, 2, orientation)
                assertEquals(-1, output[oy * width + ox])
                output[oy * width + ox] = y * 3 + x
            }
            assertEquals(expected[index], output.toList())
        }
    }
}
