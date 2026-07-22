package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTrailReverseProvenanceTest {
    @Test fun transientFrameEnergyIsAttributedWithoutChangingTheStack() {
        val width = 32
        val height = 32
        val annotation = ReplayManualTrailAnnotation(
            "transient",
            listOf(ReplayPoint(14.0, 16.0), ReplayPoint(18.0, 16.0)),
            "synthetic transient"
        )
        val frames = (0 until 4).map {
            ReplayProvenanceFrame("f$it", it, ReferenceToSourceTransform.Identity, 1f)
        }
        val images = frames.associate { frame ->
            frame.id to uniform(width, height, 10).also { image ->
                if (frame.captureIndex == 2) for (x in 14..18) image.pixels[16 * width + x] = rgb(48, 45, 43)
            }
        }
        val root = Files.createTempDirectory("reverse-provenance-transient")
        try {
            val bundle = ReplayTrailReverseProvenanceRunner().run(
                uniform(width, height, 10),
                uniform(width, height, 10),
                AlphaMask.full(width, height),
                listOf(annotation),
                frames,
                root
            ) { images.getValue(it.id) }
            val result = bundle.trails.single()
            assertEquals(ReplayTrailProvenanceClass.TRANSIENT_FRAME_OUTLIER, result.classification)
            assertEquals(listOf("f2"), result.supportingFrameIds)
            assertTrue(result.frames.single { it.frameId == "f2" }.signalEnergyContributionPercent > 99.0)
            assertTrue(result.frames.all { kotlin.math.abs(it.actualCleanStackContributionPercent - 25.0) < 1e-6 })
            assertTrue(Files.isRegularFile(root.resolve("transient/source-frame-montage.png")))
            assertTrue(Files.isRegularFile(root.resolve("stage1c-frame-evidence.tsv")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun repeatedCameraCoordinateIsClassifiedAsFixedDefect() {
        val width = 48
        val height = 48
        val frames = (0 until 10).map {
            ReplayProvenanceFrame("f$it", it, ReferenceToSourceTransform(dx = it.toFloat(), dy = 0f), 1f)
        }
        val images = frames.associate { frame ->
            frame.id to uniform(width, height, 10).also { image -> image.pixels[24 * width + 24] = rgb(55, 12, 12) }
        }
        val annotation = ReplayManualTrailAnnotation(
            "camera-fixed",
            listOf(ReplayPoint(15.0, 24.0), ReplayPoint(24.0, 24.0)),
            "fixed source coordinate projected across output"
        )
        val root = Files.createTempDirectory("reverse-provenance-camera")
        try {
            val result = ReplayTrailReverseProvenanceRunner().run(
                uniform(width, height, 10),
                uniform(width, height, 10),
                AlphaMask.full(width, height),
                listOf(annotation),
                frames,
                root
            ) { images.getValue(it.id) }.trails.single()
            assertEquals(ReplayTrailProvenanceClass.CAMERA_FIXED_DEFECT, result.classification)
            assertEquals(10, result.supportingFrameIds.size)
            assertTrue(result.cameraPositionRms <= 0.75)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun uniform(width: Int, height: Int, value: Int): ArgbPixelImage =
        ArgbPixelImage(width, height, IntArray(width * height) { rgb(value, value, value) })

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}
