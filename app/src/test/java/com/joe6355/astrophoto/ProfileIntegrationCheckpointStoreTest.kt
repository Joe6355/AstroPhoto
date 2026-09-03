package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.joe6355.astrophoto.processing.jpeg.v2.integration.IntegrationCheckpointFrameSignature
import com.joe6355.astrophoto.processing.jpeg.v2.integration.ProfileIntegrationCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.integration.ProfileIntegrationRun
import com.joe6355.astrophoto.processing.jpeg.v2.model.IntegrationDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.model.IntegrationMode
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileIntegrationCheckpointStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun integrationOutputsSurviveReopenAndRestoreIntoNewRun() {
        val sourceRun = TemporaryPipelineFiles.create(temporaryFolder.newFolder("source-cache"))
        val expectedStack = ByteArray(16) { (it + 1).toByte() }
        val expectedCoverage = ByteArray(16) { (it + 21).toByte() }
        val source = integrationRun(sourceRun, expectedStack, expectedCoverage)
        openStore().write(source)
        sourceRun.close()
        val restoredRunFiles = TemporaryPipelineFiles.create(
            temporaryFolder.newFolder("restored-cache")
        )

        val restored = openStore().readInto(restoredRunFiles)

        assertEquals(source.diagnostics, restored?.diagnostics)
        assertEquals(source.sensorDefectFiltering, restored?.sensorDefectFiltering)
        assertArrayEquals(expectedStack, restored?.stackedSky?.file?.readBytes())
        assertArrayEquals(expectedCoverage, restored?.validCoverage?.file?.readBytes())
        restoredRunFiles.close()
    }

    @Test
    fun damagedManifestInvalidatesWholeIntegrationCheckpoint() {
        val sourceRun = TemporaryPipelineFiles.create(temporaryFolder.newFolder("source-cache"))
        openStore().write(integrationRun(sourceRun, ByteArray(16), ByteArray(16)))
        val manifest = temporaryFolder.root.walkTopDown().first { it.name == "manifest.bin" }
        manifest.writeBytes(byteArrayOf(1, 2, 3))
        val restoredRunFiles = TemporaryPipelineFiles.create(
            temporaryFolder.newFolder("restored-cache")
        )

        assertNull(openStore().readInto(restoredRunFiles))
        assertFalse(manifest.exists())
        sourceRun.close()
        restoredRunFiles.close()
    }

    private fun integrationRun(
        files: TemporaryPipelineFiles,
        stackBytes: ByteArray,
        coverageBytes: ByteArray
    ): ProfileIntegrationRun {
        val stackFile = files.file("stack.argb").also { it.writeBytes(stackBytes) }
        val coverageFile = files.file("coverage.f32").also { it.writeBytes(coverageBytes) }
        return ProfileIntegrationRun(
            diagnostics = IntegrationDiagnostics(
                outputWidth = 2,
                outputHeight = 2,
                tileWidth = 2,
                tileHeight = 2,
                acceptedFrames = 1,
                mode = IntegrationMode.LINEAR_WEIGHTED_AVERAGE,
                robustModeEnabled = false,
                validCoveragePercent = 100f,
                minimumAccumulatedWeight = 1f,
                maximumAccumulatedWeight = 1f,
                processingDurationMillis = 10L,
                estimatedPeakWorkingMemoryBytes = 64L,
                resolutionChanged = false
            ),
            stackedSky = FileBackedImage(stackFile, 2, 2),
            validCoverage = FileBackedFloatPlane(coverageFile, 2, 2),
            sensorDefectAffectedOutput = null,
            sensorDefectFiltering = SensorDefectFilteringReport(),
            totalDurationMillis = 10L
        )
    }

    private fun openStore() = ProfileIntegrationCheckpointStore.open(
        filesRoot = temporaryFolder.root,
        sessionFolder = "session",
        profile = AstroProcessingProfile.DEEP_SKY,
        width = 2,
        height = 2,
        frames = listOf(
            IntegrationCheckpointFrameSignature(
                frameId = "frame-1",
                fileName = "frame-1.jpg",
                sizeBytes = 100L,
                createdAtMillis = 200L,
                registration = RegistrationResult(
                    dx = 0f,
                    dy = 0f,
                    rotationRadians = 0f,
                    scale = 1f,
                    detectedStars = 10,
                    matchedStars = 10,
                    inlierStars = 10,
                    residualError = 0f,
                    confidence = 1f,
                    isReliable = true,
                    rejectionReason = null
                ),
                normalizedWeight = 1f
            )
        ),
        sensorDefectMask = SensorDefectMask.empty(2, 2),
        integrationSkyMask = SkyMask.full(2, 2),
        forTesting = true
    )
}
