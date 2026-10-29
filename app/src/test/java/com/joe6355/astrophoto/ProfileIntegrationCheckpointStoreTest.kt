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
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedPixelFormat
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import com.joe6355.astrophoto.processing.jpeg.v2.model.*
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptiveProcessingResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileIntegrationCheckpointStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun perspectiveCoefficientsInvalidateCachedPixelsEvenWhenRigidMetadataIsIdentical() {
        val first = ProjectiveTransform(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1e-6, 0.0)
        val second = first.copy(px = 2e-6)
        TemporaryPipelineFiles.create(temporaryFolder.newFolder("projective-source")).use { source ->
            openStore(first).write(integrationRun(source, ByteArray(16) { 42 }, ByteArray(16)))
            TemporaryPipelineFiles.create(temporaryFolder.newFolder("projective-restored")).use { restored ->
                assertNull(openStore(second).readInto(restored))
                assertArrayEquals(ByteArray(16) { 42 }, openStore(first).readInto(restored)?.stackedSky?.file?.readBytes())
            }
        }
    }

    @Test fun rgb16FormatAndSubByteDataSurviveCheckpointRestore() {
        TemporaryPipelineFiles.create(temporaryFolder.newFolder("rgb16-source")).use { source ->
            val precise = FileBackedImageWriter(source.file("precise.rgb16"), 2, 2,
                FileBackedPixelFormat.LINEAR_RGB_16).use { writer ->
                writer.writeLinearRow(0, longArrayOf(0x001100220033L, 0x001200230034L))
                writer.writeLinearRow(1, longArrayOf(0x112233445566L, 0x112333455567L))
                writer.finish()
            }
            val run = integrationRun(source, ByteArray(16), ByteArray(16)).copy(stackedSky = precise)
            val store = openStore()
            store.write(run)
            store.writePostProcessing("rgb16-inputs", postProcessed(precise))
            TemporaryPipelineFiles.create(temporaryFolder.newFolder("rgb16-restored")).use { files ->
                val restored = requireNotNull(store.readInto(files))
                assertEquals(FileBackedPixelFormat.LINEAR_RGB_16, restored.stackedSky.pixelFormat)
                assertArrayEquals(precise.file.readBytes(), restored.stackedSky.file.readBytes())
                val processed = requireNotNull(store.readPostProcessing("rgb16-inputs", files))
                assertEquals(FileBackedPixelFormat.LINEAR_RGB_16, processed.image.pixelFormat)
                assertArrayEquals(precise.file.readBytes(), processed.image.file.readBytes())
            }
        }
    }

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

    @Test fun sameSizePixelCorruptionInvalidatesAllArtifacts() {
        TemporaryPipelineFiles.create(temporaryFolder.newFolder("source")).use { source ->
            val store = openStore()
            store.write(integrationRun(source, ByteArray(16), ByteArray(16)))
            val pixels = temporaryFolder.root.walkTopDown().first { it.name == "stacked.argb" }
            pixels.writeBytes(ByteArray(16) { 1 })
            TemporaryPipelineFiles.create(temporaryFolder.newFolder("restored")).use { restored ->
                assertNull(store.readInto(restored))
                assertFalse(checkNotNull(pixels.parentFile).exists())
                assertFalse(restored.file("checkpoint-integrated-sky.argb").exists())
            }
        }
    }

    @Test fun affectedPlaneIsRestoredAndItsCorruptionRemovesPartialCopies() {
        TemporaryPipelineFiles.create(temporaryFolder.newFolder("source")).use { source ->
            val store = openStore()
            val affected = source.file("sensor.f32").also { it.writeBytes(ByteArray(16) { 42 }) }
            store.write(integrationRun(source, ByteArray(16), ByteArray(16)).copy(
                sensorDefectAffectedOutput = FileBackedFloatPlane(affected, 2, 2)))
            TemporaryPipelineFiles.create(temporaryFolder.newFolder("restored")).use { restored ->
                assertArrayEquals(affected.readBytes(), store.readInto(restored)?.sensorDefectAffectedOutput?.file?.readBytes())
                val saved = temporaryFolder.root.walkTopDown().first { it.name == "affected.f32" }
                saved.writeBytes(ByteArray(16) { 43 })
                assertNull(store.readInto(restored))
                assertFalse(restored.file("checkpoint-integrated-sky.argb").exists())
                assertFalse(restored.file("checkpoint-valid-coverage.f32").exists())
                assertFalse(checkNotNull(saved.parentFile).exists())
            }
        }
    }

    @Test fun postProcessingRoundTripPreservesPixelsAndNestedDiagnostics() {
        TemporaryPipelineFiles.create(temporaryFolder.newFolder("source")).use { source ->
            val store = openStore()
            val run = integrationRun(source, ByteArray(16) { it.toByte() }, ByteArray(16))
            store.write(run)
            val expected = postProcessed(run.stackedSky)
            store.writePostProcessing("inputs", expected)
            repeat(2) { attempt ->
                TemporaryPipelineFiles.create(temporaryFolder.newFolder("restore-$attempt")).use { restoredFiles ->
                    val restored = openStore().readPostProcessing("inputs", restoredFiles)
                    assertEquals(expected.diagnostics, restored?.diagnostics)
                    assertArrayEquals(expected.image.file.readBytes(), restored?.image?.file?.readBytes())
                }
            }
            assertTrue(temporaryFolder.root.walkTopDown().any { it.name == "postprocessed.argb" })
        }
    }

    @Test fun postProcessingCorruptionOrChangedInputsInvalidatesWholeBundle() {
        TemporaryPipelineFiles.create(temporaryFolder.newFolder("source")).use { source ->
            val run = integrationRun(source, ByteArray(16), ByteArray(16))
            listOf("pixels", "manifest", "signature").forEach { scenario ->
                val store = openStore()
                store.write(run)
                store.writePostProcessing("inputs", postProcessed(run.stackedSky))
                val pixels = temporaryFolder.root.walkTopDown().first { it.name == "postprocessed.argb" }
                when (scenario) {
                    "pixels" -> pixels.writeBytes(ByteArray(16) { 1 })
                    "manifest" -> temporaryFolder.root.walkTopDown().first { it.name == "postprocessing.bin" }
                        .let { file -> file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }) }
                }
                TemporaryPipelineFiles.create(temporaryFolder.newFolder(scenario)).use { restored ->
                    assertNull(store.readPostProcessing(if (scenario == "signature") "changed" else "inputs", restored))
                    assertFalse(checkNotNull(pixels.parentFile).exists())
                    assertFalse(restored.file("checkpoint-postprocessed.argb").exists())
                }
            }
        }
    }

    @Test fun destinationCopyFailureKeepsValidIntegrationCheckpointForRetry() {
        val targets = listOf("checkpoint-integrated-sky.argb", "checkpoint-valid-coverage.f32",
            "checkpoint-sensor-defect-affected.f32")
        targets.forEachIndexed { index, targetName ->
            TemporaryPipelineFiles.create(temporaryFolder.newFolder("io-source-$index")).use { source ->
                val store = openStore()
                val affected = source.file("affected.f32").apply { writeBytes(ByteArray(16) { 37 }) }
                val run = integrationRun(source, ByteArray(16) { 11 }, ByteArray(16) { 23 }).copy(
                    sensorDefectAffectedOutput = FileBackedFloatPlane(affected, 2, 2))
                store.write(run)
                val checkpoint = temporaryFolder.root.walkTopDown().first { it.name == "manifest.bin" }.parentFile!!
                val before = checkpointBytes(checkpoint)
                TemporaryPipelineFiles.create(temporaryFolder.newFolder("io-target-$index")).use { restored ->
                    // A real destination I/O failure, without consuming free storage or changing permissions.
                    val blocked = restored.file(targetName).apply { check(mkdir()) }
                    val sentinel = java.io.File(blocked,"keep.txt").apply { writeText("owned test blocker") }
                    val failure = assertThrows(java.io.IOException::class.java) { store.readInto(restored) }
                    assertTrue(failure.message!!.contains("Прогресс сохранён"))
                    assertEquals(before,checkpointBytes(checkpoint))
                    targets.filterNot { it == targetName }.forEach { name ->
                        assertFalse(restored.file(name).exists())
                    }
                    assertTrue(sentinel.isFile)
                    assertTrue(sentinel.delete())
                    assertTrue(blocked.delete())
                    val retry = requireNotNull(store.readInto(restored))
                    assertArrayEquals(run.stackedSky.file.readBytes(),retry.stackedSky.file.readBytes())
                    assertArrayEquals(run.validCoverage.file.readBytes(),retry.validCoverage.file.readBytes())
                    assertArrayEquals(affected.readBytes(),retry.sensorDefectAffectedOutput!!.file.readBytes())
                }
            }
        }
    }

    @Test fun destinationCopyFailureKeepsPostProcessingAndIntegrationForRetry() {
        TemporaryPipelineFiles.create(temporaryFolder.newFolder("post-io-source")).use { source ->
            val store = openStore()
            val run = integrationRun(source,ByteArray(16) { 11 },ByteArray(16) { 23 })
            store.write(run)
            store.writePostProcessing("inputs",postProcessed(run.stackedSky))
            val checkpoint = temporaryFolder.root.walkTopDown().first { it.name == "manifest.bin" }.parentFile!!
            val before = checkpointBytes(checkpoint)
            TemporaryPipelineFiles.create(temporaryFolder.newFolder("post-io-target")).use { restored ->
                val blocked = restored.file("checkpoint-postprocessed.argb").apply { check(mkdir()) }
                val sentinel = java.io.File(blocked,"keep.txt").apply { writeText("owned test blocker") }
                val failure = assertThrows(java.io.IOException::class.java) {
                    store.readPostProcessing("inputs",restored)
                }
                assertTrue(failure.message!!.contains("Прогресс сохранён"))
                assertEquals(before,checkpointBytes(checkpoint))
                assertFalse(restored.file("checkpoint-postprocessed.argb.tmp").exists())
                assertTrue(sentinel.isFile)
                assertTrue(sentinel.delete())
                assertTrue(blocked.delete())
                val retry = requireNotNull(store.readPostProcessing("inputs",restored))
                assertArrayEquals(run.stackedSky.file.readBytes(),retry.image.file.readBytes())
                assertArrayEquals(run.stackedSky.file.readBytes(),store.readInto(restored)!!.stackedSky.file.readBytes())
            }
        }
    }

    private fun checkpointBytes(directory: java.io.File): Map<String,List<Byte>> =
        directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).path to it.readBytes().toList()
        }

    @Test fun noSpaceAfterPartialCopyPreservesBothCheckpointsAndAllowsRetry() {
        listOf(false,true).forEach { postProcessing ->
            TemporaryPipelineFiles.create(temporaryFolder.newFolder("space-source-$postProcessing")).use { source ->
                val store = openStore()
                val run = integrationRun(source,ByteArray(16) { 11 },ByteArray(16) { 23 })
                store.write(run)
                store.writePostProcessing("inputs",postProcessed(run.stackedSky))
                val checkpoint = temporaryFolder.root.walkTopDown().first { it.name == "manifest.bin" }.parentFile!!
                val before = checkpointBytes(checkpoint)
                TemporaryPipelineFiles.create(temporaryFolder.newFolder("space-target-$postProcessing")).use { restored ->
                    val noSpaceCopy: (java.io.File,java.io.File,Long) -> Unit = { input,output,_ ->
                        output.writeBytes(input.readBytes().copyOf(3))
                        throw java.io.IOException("write failed: ENOSPC (No space left on device)")
                    }
                    val failure = assertThrows(java.io.IOException::class.java) {
                        if (postProcessing) store.readPostProcessing("inputs",restored,noSpaceCopy)
                        else store.readInto(restored,noSpaceCopy)
                    }
                    assertTrue(failure.cause!!.message!!.contains("ENOSPC"))
                    assertEquals(before,checkpointBytes(checkpoint))
                    assertFalse(restored.directory.listFiles()!!.any { it.name.startsWith("checkpoint-") })
                    assertArrayEquals(run.stackedSky.file.readBytes(),store.readInto(restored)!!.stackedSky.file.readBytes())
                    assertArrayEquals(run.stackedSky.file.readBytes(),
                        store.readPostProcessing("inputs",restored)!!.image.file.readBytes())
                }
            }
        }
    }

    private fun postProcessed(image: FileBackedImage) = FileBackedAdaptiveProcessingResult(image,
        AdaptiveProcessingDiagnostics("DEEP_SKY", SkyStatisticsResult.EMPTY,
            SkyStatisticsResult.EMPTY.copy(reliableStarCount = 8),
            GradientRemovalDiagnostics(0.8f, 2, 2, 4, 0.1f),
            NeutralizationDiagnostics(LinearRgb(0.01f, 0.02f, 0.03f)),
            StretchDiagnostics(0.01f, 0.9f, 3f, 0.2f, 0.4f, 1f),
            ChromaNoiseDiagnostics(0.2f, 1), StarEnhancementDiagnostics(0.1f, 8, 6, 2, 0f),
            0, 1_000, mapOf("background" to 500L, "stretch" to 500L)))

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

    private fun openStore(projective: ProjectiveTransform? = null) = ProfileIntegrationCheckpointStore.open(
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
                    rejectionReason = null,
                    projective = projective
                ),
                normalizedWeight = 1f
            )
        ),
        sensorDefectMask = SensorDefectMask.empty(2, 2),
        integrationSkyMask = SkyMask.full(2, 2),
        forTesting = true
    )
}
