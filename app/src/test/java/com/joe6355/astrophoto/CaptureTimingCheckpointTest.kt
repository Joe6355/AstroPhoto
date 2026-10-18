package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureTimingCheckpointTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val timing = mapOf("f0" to 0f, "f1" to 1f, "f2" to 10f)

    @Test fun identicalTimingRestoresTheCompletedRegistrationWithItsTimeAxis() {
        val diagnostics = diagnostics()
        open(timing).write(diagnostics)
        val restored = open(timing.entries.reversed().associate { it.toPair() }).read()
        assertNotNull(restored)
        assertEquals(diagnostics.model, restored!!.model)
        assertEquals(mapOf(0 to 0f, 1 to 1f, 2 to 10f), restored.model.timeCoordinateByCaptureIndex)
    }

    @Test fun changingOnlyCaptureTimingCannotReuseAnOlderRegistrationDecision() {
        open(timing).write(diagnostics())
        assertNull(open(timing + ("f2" to 2f)).read())
        assertNotNull(open(timing).read())
    }

    @Test fun ordinalAndExifRegistrationCachesAreIsolated() {
        open(emptyMap()).write(diagnostics())
        assertNull(open(timing).read())
    }

    private fun open(axis: Map<String, Float>) = ProfileRegistrationCheckpointStore.open(
        filesRoot = temporaryFolder.root, sessionFolder = "timing-test",
        profile = AstroProcessingProfile.DEEP_SKY,
        frames = (0..2).map { index -> SessionFrame("f$index", "$index.jpg", SessionFrameCategory.LIGHTS_JPEG,
            100L, 200L, "$index.jpg", null, null) },
        selectedFrameKeys = listOf("f0", "f1", "f2"), analysisWidth = 120, analysisHeight = 90,
        forTesting = true, captureTimeByFrameKey = axis)

    private fun diagnostics(): SequenceAwareRegistrationDiagnostics {
        val stars = listOf(12f to 12f, 55f to 12f, 98f to 12f,
            12f to 70f, 55f to 70f, 98f to 70f).map { (x,y) ->
            DetectedStar(x, y, 100f, 4f, 20f, 1.5f, 0.1f, 0.9f)
        }
        return SequenceAwareRegistrationEngine().register((0..2).map { index ->
            TemporalFeatureFrame("f$index", index, stars, timing.getValue("f$index"))
        }, "f1", 120, 90)
    }
}
