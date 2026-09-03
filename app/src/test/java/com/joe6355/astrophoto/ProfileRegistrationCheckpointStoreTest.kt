package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.registration.ProfileRegistrationCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.joe6355.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRegistrationCheckpointStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun registrationDiagnosticsSurviveReopen() {
        val frame = sessionFrame()
        val diagnostics = diagnostics()
        openStore(frame).write(diagnostics)

        val restored = openStore(frame).read()

        assertEquals(diagnostics.registrations, restored?.registrations)
        assertEquals(diagnostics.model, restored?.model)
        assertEquals(diagnostics.verification, restored?.verification)
    }

    @Test
    fun corruptionInvalidatesWholeRegistrationCheckpoint() {
        val frame = sessionFrame()
        val store = openStore(frame)
        store.write(diagnostics())
        val checkpoint = temporaryFolder.root.walkTopDown().first { it.name == "registration.bin" }
        checkpoint.writeBytes(byteArrayOf(1, 2, 3))

        assertNull(store.read())
        assertFalse(checkpoint.exists())
    }

    private fun diagnostics() = SequenceAwareRegistrationEngine().register(
        frames = (0..2).map { index -> TemporalFeatureFrame("f$index", index, stars()) },
        referenceFrameId = "f1",
        imageWidth = 120,
        imageHeight = 90
    )

    private fun stars() = listOf(
        star(12f, 12f), star(55f, 12f), star(98f, 12f),
        star(12f, 70f), star(55f, 70f), star(98f, 70f)
    )

    private fun star(x: Float, y: Float) = DetectedStar(
        x, y, 100f, 4f, 20f, 1.5f, 0.1f, 0.9f
    )

    private fun openStore(frame: SessionFrame) = ProfileRegistrationCheckpointStore.open(
        filesRoot = temporaryFolder.root,
        sessionFolder = "session",
        profile = AstroProcessingProfile.DEEP_SKY,
        frames = listOf(frame),
        selectedFrameKeys = listOf("f0", "f1", "f2"),
        analysisWidth = 120,
        analysisHeight = 90,
        forTesting = true
    )

    private fun sessionFrame() = SessionFrame(
        key = "frame-1",
        fileName = "frame-1.jpg",
        category = SessionFrameCategory.LIGHTS_JPEG,
        sizeBytes = 100L,
        createdAtMillis = 200L,
        displayPath = "frame-1.jpg",
        contentUri = null,
        filePath = null
    )
}
