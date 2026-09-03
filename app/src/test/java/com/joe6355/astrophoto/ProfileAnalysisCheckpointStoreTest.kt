package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ProfileAnalysisCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorCandidateObservation
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorFrameObservation
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMaskResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileAnalysisCheckpointStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun completedFrameAnalysisSurvivesStoreReopen() {
        val frame = frame(size = 1200L)
        val store = openStore(frame)
        val star = DetectedStar(2f, 3f, 20f, 4f, 16f, 1.8f, 0.1f, 0.9f)
        val analysis = FrameAnalysis(
            frame.key, frame.fileName, 4, 3, listOf(star), 1,
            16f, 1.8f, 0.1f, 2f, 0f, 0.8f, true, 0.9f, 0.8f, false
        )
        val mask = SkyMaskResult(
            SkyMask(4, 3, BooleanArray(12) { it % 2 == 0 }),
            0.8f,
            false
        )
        val observation = PersistentSensorFrameObservation(
            frame.key,
            1,
            4,
            3,
            listOf(PersistentSensorCandidateObservation(star, 20, 21, 22, 4, 5, 6, 3))
        )
        store.write(frame, 1, analysis, mask, observation)

        val restored = openStore(frame).read(frame, 1)

        assertEquals(analysis, restored?.analysis)
        assertEquals(mask.mask.copyPixels().toList(), restored?.skyMask?.mask?.copyPixels()?.toList())
        assertEquals(observation, restored?.sensorObservation)
    }

    @Test
    fun changedInputMetadataInvalidatesCheckpoint() {
        val frame = frame(size = 1200L)
        val store = openStore(frame)
        val invalid = FrameAnalysis.invalid(frame.key, frame.fileName)
        store.write(
            frame,
            1,
            invalid,
            SkyMaskResult(SkyMask.empty(4, 3), 0f, true),
            PersistentSensorFrameObservation(frame.key, 1, 4, 3, emptyList())
        )

        assertNull(store.read(frame.copy(sizeBytes = 1201L), 1))
    }

    @Test
    fun corruptFrameInvalidatesWholeCheckpointDirectory() {
        val frame = frame(size = 1200L)
        val store = openStore(frame)
        store.write(
            frame,
            1,
            FrameAnalysis.invalid(frame.key, frame.fileName),
            SkyMaskResult(SkyMask.empty(4, 3), 0f, true),
            PersistentSensorFrameObservation(frame.key, 1, 4, 3, emptyList())
        )
        val checkpoint = temporaryFolder.root.walkTopDown().first { it.name == "frame-0001.bin" }
        checkpoint.writeBytes(byteArrayOf(1, 2, 3))

        assertNull(store.read(frame, 1))
        assertFalse(checkpoint.exists())
    }

    private fun openStore(frame: SessionFrame) = ProfileAnalysisCheckpointStore.open(
        filesRoot = temporaryFolder.root,
        sessionFolder = "session",
        profile = AstroProcessingProfile.DEEP_SKY,
        frames = listOf(frame),
        analysisWidth = 4,
        analysisHeight = 3,
        forTesting = true
    )

    private fun frame(size: Long) = SessionFrame(
        key = "frame-1",
        fileName = "frame-1.jpg",
        category = SessionFrameCategory.LIGHTS_JPEG,
        sizeBytes = size,
        createdAtMillis = 123L,
        displayPath = "frame-1.jpg",
        contentUri = null,
        filePath = null
    )
}
