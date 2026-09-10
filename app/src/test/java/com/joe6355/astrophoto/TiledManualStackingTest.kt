package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.*
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot
import com.joe6355.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TiledManualStackingTest {
    @get:Rule val folder = TemporaryFolder()
    private val budget = JpegMemoryBudget(RuntimeHeapSnapshot(256L shl 20, 32L shl 20, 16L shl 20))

    @Test fun everyManualModeRetainsFractionalStackValuesAcrossTileBoundaries() = runBlocking {
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val frames = listOf(
                TiledManualFrame(frame(files, "first.argb", 67, 65) { _, _ -> 20 }, AlignmentShift.Zero, 0),
                TiledManualFrame(frame(files, "second.argb", 67, 65) { _, _ -> 21 }, AlignmentShift.Zero, 1)
            )
            for (mode in listOf(ManualAlignedStackMode.AVERAGE, ManualAlignedStackMode.MEDIAN, ManualAlignedStackMode.SIGMA)) {
                val result = TiledManualStacking.integrate(frames, mode, ResultCandidateStore(files), budget) { _, _ -> }
                assertEquals(FileBackedPixelFormat.LINEAR_RGB_16, result.pixelFormat)
                FileBackedImageReader(result).use { reader ->
                    for (y in listOf(0, 31, 32, 63, 64)) {
                        assertEquals(20.5, encodedRed(reader.linearRgbAt(66, y)), 0.04)
                    }
                }
                files.deleteFile(result)
            }
        }
    }

    @Test fun translationUsesCommonCropAndSourceCoordinates() = runBlocking {
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val reference = frame(files, "reference.argb", 67, 65) { x, y -> x + y + 10 }
            val moved = frame(files, "moved.argb", 67, 65) { x, y -> x + y + 9 }
            val frames = listOf(TiledManualFrame(reference, AlignmentShift.Zero, 0),
                TiledManualFrame(moved, AlignmentShift(2, -1), 1))
            val result = TiledManualStacking.integrate(frames, ManualAlignedStackMode.AVERAGE,
                ResultCandidateStore(files), budget) { _, _ -> }
            assertEquals(65, result.width)
            assertEquals(64, result.height)
            FileBackedImageReader(result).use { reader ->
                assertEquals(11.0, encodedRed(reader.linearRgbAt(0, 0)), 0.04)
                assertEquals(138.0, encodedRed(reader.linearRgbAt(64, 63)), 0.04)
            }
        }
    }

    @Test fun darkFallbackCalibratesTheSelectedReferenceUsingOriginalCropCoordinates() = runBlocking {
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val width = 67
            val height = 65
            val mask = SensorDefectMask(width, height, listOf(SensorDefectRegion(
                "defect", 4f, 33f, 0f, 0f, listOf(SensorDefectFootprintPixel(4, 33)),
                2, 2, 0, 1f, PersistentArtifactClassification.SENSOR_DEFECT, "confirmed"
            )), true)
            val coverage = ManualSensorDefectCoveragePlan(mask, PixelRect(0, 0, width, height),
                SensorDefectFilteringReport(sampleLevelFilteringApplied = true))
            val first = frame(files, "first.argb", width, height) { _, _ -> 60 }
            val reference = frame(files, "reference.argb", width, height) { _, _ -> 80 }
            val dark = frame(files, "dark.argb", width + 6, height + 4) { x, _ -> x + 10 }
            val result = TiledManualStacking.integrate(
                listOf(TiledManualFrame(first, AlignmentShift.Zero, 10), TiledManualFrame(reference, AlignmentShift.Zero, 20)),
                ManualAlignedStackMode.DARK_SUBTRACTED_AVERAGE, ResultCandidateStore(files), budget,
                coverage = coverage, referenceOriginalFrameIndex = 20, masterDark = dark,
                darkCrop = PixelRect(3, 2, 3 + width, 2 + height), shadowOffset = 8
            ) { _, _ -> }
            FileBackedImageReader(result).use { reader ->
                // Reference is 80; dark at sensor x=4+3 is 17; 80 - 17*0.65 + 8 = 76.95.
                assertEquals(76.95, encodedRed(reader.linearRgbAt(4, 33)), 0.04)
                assertEquals(66.95, encodedRed(reader.linearRgbAt(4, 32)), 0.04)
            }
        }
    }

    @Test fun cancellationClosesTemporaryOutputAndKeepsInputs() = runBlocking {
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val source = frame(files, "source.argb", 67, 65) { _, _ -> 20 }
            try {
                TiledManualStacking.integrate(listOf(TiledManualFrame(source, AlignmentShift.Zero, 0)),
                    ManualAlignedStackMode.AVERAGE, ResultCandidateStore(files), budget) { _, _ ->
                    throw CancellationException("cancel after first strip")
                }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue(source.file.isFile)
                val partial = files.directory.listFiles().orEmpty().single { it.name.contains("manual-integrated") }
                assertTrue("Output handle must be closed", partial.delete())
            }
        }
    }

    private fun frame(files: TemporaryPipelineFiles, name: String, width: Int, height: Int, value: (Int, Int) -> Int): FileBackedImage =
        FileBackedImageWriter(files.file(name), width, height).use { writer ->
            for (y in 0 until height) writer.writeRow(y, IntArray(width) { x ->
                val level = value(x, y)
                (255 shl 24) or (level shl 16) or (level shl 8) or level
            })
            writer.finish()
        }

    private fun encodedRed(color: Long) = (SrgbTransfer.linearToSrgb(LinearRgb16.red(color)) * 255f).toDouble()
}
