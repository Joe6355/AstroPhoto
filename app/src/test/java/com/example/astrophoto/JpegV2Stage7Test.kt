package com.example.astrophoto

import android.app.ApplicationExitInfo
import com.example.astrophoto.processing.jpeg.v2.composition.AlphaMaskPixelSource
import com.example.astrophoto.processing.jpeg.v2.composition.FileBackedSkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.composition.SkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.diagnostics.AppSpecificProcessingReportStore
import com.example.astrophoto.processing.jpeg.v2.diagnostics.PreviousProcessExitKind
import com.example.astrophoto.processing.jpeg.v2.diagnostics.PreviousProcessExitReader
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingRunJournal
import com.example.astrophoto.processing.jpeg.v2.memory.ImageAllocationEstimate
import com.example.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.example.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.example.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.BandingMetrics
import com.example.astrophoto.processing.jpeg.v2.model.LinearRgb
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.model.StoredResultCandidate
import com.example.astrophoto.processing.jpeg.v2.output.PngStreamEncoder
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.quality.FileBackedResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.quality.ResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.quality.ResultSelectionPolicy
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.example.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JpegV2Stage7Test {
    @Test fun memoryBudgetReservesFrameworkAndUiHeadroom() {
        val budget = budget(max = 256L * MIB, total = 96L * MIB, free = 32L * MIB)
        assertTrue(budget.frameworkReserveBytes >= 64L * MIB)
        assertTrue(budget.safeWorkingBudgetBytes < budget.snapshot.availableHeapBytes)
        assertEquals(192L * MIB, budget.snapshot.availableHeapBytes)
    }

    @Test fun memoryBudgetRejectsUnsafeFullImageDuplication() {
        val budget = budget(max = 96L * MIB, total = 80L * MIB, free = 16L * MIB)
        val duplicated = ImageAllocationEstimate("two-images", 1440L * 1920L * 8L)
        assertFalse(budget.permits(duplicated))
    }

    @Test fun memoryPressureReducesTileAndNeverOutputDimensions() {
        val budget = JpegMemoryBudget(
            RuntimeHeapSnapshot(80L * MIB, 40L * MIB, 32L * MIB),
            reserveBytes = 32L * MIB
        )
        val decision = budget.chooseTile(1440, 1920, 1440, 1920, argbBuffers = 5, floatBuffers = 4)
        assertTrue(decision.accepted)
        assertTrue(decision.retryRequired)
        assertTrue(decision.tileWidth < 1440 || decision.tileHeight < 1920)
        assertEquals(1440, decision.outputWidth)
        assertEquals(1920, decision.outputHeight)
    }

    @Test fun pressureRetryIsRecordedAtMostOncePerStageDecision() {
        val tracker = PipelineMemoryTracker()
        val decision = budget().chooseTile(1440, 1920, 1440, 1920, argbBuffers = 8, floatBuffers = 6)
        if (decision.retryRequired) tracker.recordRetry()
        assertTrue(tracker.memoryPressureRetries <= 1)
    }

    @Test fun candidateStoreCreatesIndependentHandlesWithoutPixelArrays() = withRun { _, store ->
        val pixels = fixture(12, 8)
        val reference = writeCandidate(store, ResultCandidateType.REFERENCE, 12, 8, pixels)
        val clean = writeCandidate(store, ResultCandidateType.CLEAN_STACK, 12, 8, pixels.reversedArray())
        val processed = writeCandidate(store, ResultCandidateType.PROCESSED, 12, 8, pixels)
        assertNotEquals(reference.file, clean.file)
        assertNotEquals(clean.file, processed.file)
        assertTrue(FileBackedImage::class.java.declaredFields.none {
            it.type == IntArray::class.java || it.type == FloatArray::class.java
        })
    }

    @Test fun temporaryRunsAreUniqueAndCleanupOnlyOwnValidatedDirectory() {
        val root = Files.createTempDirectory("stage7-cache").toFile()
        val first = TemporaryPipelineFiles.create(root)
        val second = TemporaryPipelineFiles.create(root)
        val unrelated = File(root, "session-photo.jpg").apply { writeText("keep") }
        assertNotEquals(first.directory, second.directory)
        first.close()
        assertFalse(first.directory.exists())
        assertTrue(second.directory.exists())
        assertTrue(unrelated.exists())
        second.close()
        root.deleteRecursively()
    }

    @Test fun staleCleanupRequiresExactPrefixAndValidatedMarker() {
        val root = Files.createTempDirectory("stage7-stale").toFile()
        val session = File(root, "Pictures-AstroPhoto-session").apply { mkdir(); File(this, "photo.jpg").writeText("x") }
        val invalid = File(root, "jpeg-stage7-${"a".repeat(32)}").apply { mkdir(); setLastModified(1L) }
        val valid = TemporaryPipelineFiles.create(root)
        valid.directory.setLastModified(1L)
        assertEquals(1, TemporaryPipelineFiles.cleanupStale(root, 7L * 60L * 60L * 1000L))
        assertTrue(session.exists())
        assertTrue(invalid.exists())
        root.deleteRecursively()
    }

    @Test fun fileBackedRowsAndRandomTilesRoundTripExactArgb() = withRun { _, store ->
        val pixels = fixture(17, 11)
        val image = writeTemporary(store, "roundtrip", 17, 11, pixels)
        FileBackedImageReader(image).use { reader ->
            val row = IntArray(17)
            reader.readArgbRow(7, row)
            assertArrayEquals(pixels.copyOfRange(7 * 17, 8 * 17), row)
            val tile = IntArray(5 * 4)
            reader.readTile(9, 3, 5, 4, tile)
            val expected = IntArray(20) { index ->
                pixels[(3 + index / 5) * 17 + 9 + index % 5]
            }
            assertArrayEquals(expected, tile)
        }
    }

    @Test fun tiledCompositionMatchesLegacyPreservesForegroundAndHasNoSeams() = withRun { _, store ->
        val width = 301
        val height = 279
        val stackedPixels = fixture(width, height)
        val referencePixels = fixture(width, height).mapIndexed { index, color ->
            if (index % 7 == 0) 0xFF102030.toInt() else color xor 0x00070707
        }.toIntArray()
        val maskValues = FloatArray(width * height) { index ->
            val x = index % width
            when {
                x < 40 -> 0f
                x > 260 -> 1f
                else -> (x - 40) / 220f
            }
        }
        val coverageValues = FloatArray(width * height) { index -> if (index % 13 == 0) 0.72f else 1f }
        val legacy = SkyForegroundComposer().compose(
            ArgbPixelImage(width, height, stackedPixels),
            ArgbPixelImage(width, height, referencePixels),
            AlphaMask(width, height, maskValues),
            AlphaMask(width, height, coverageValues)
        )
        val stacked = writeTemporary(store, "compose-sky", width, height, stackedPixels)
        val reference = writeTemporary(store, "compose-ref", width, height, referencePixels)
        val outputWriter = store.createTemporaryWriter("compose-out", width, height)
        val alphaWriter = store.createFloatPlaneWriter("compose-alpha", width, height)
        val actual = FileBackedSkyForegroundComposer().compose(
            stacked,
            reference,
            AlphaMaskPixelSource(AlphaMask(width, height, maskValues)),
            AlphaMaskPixelSource(AlphaMask(width, height, coverageValues)),
            outputWriter,
            alphaWriter,
            budget(),
            PipelineMemoryTracker()
        )
        val actualPixels = readAll(actual.image)
        assertArrayEquals(legacy.image.pixels, actualPixels)
        for (y in 0 until height) for (x in 0 until 40) {
            assertEquals(referencePixels[y * width + x], actualPixels[y * width + x])
        }
        assertTrue(actual.tileWidth < width)
        assertTrue(actual.tileHeight < height)
    }

    @Test fun fileBackedStage4MatchesLegacyOnSmallFixtureIncludingChromaHalo() = withRun { _, store ->
        val width = 300
        val height = 48
        val stackedPixels = fixture(width, height)
        val referencePixels = fixture(width, height).map { it xor 0x00030303 }.toIntArray()
        val alphaValues = FloatArray(width * height) { index -> if (index % width < 5) 0f else 1f }
        val stacked = writeTemporary(store, "stage4-sky", width, height, stackedPixels)
        val reference = writeCandidate(store, ResultCandidateType.REFERENCE, width, height, referencePixels)
        val alpha = writePlane(store, "stage4-alpha", width, height, alphaValues)
        val legacy = runBlocking {
            AdaptivePresetProcessor().process(
                ArgbPixelImage(width, height, stackedPixels),
                ArgbPixelImage(width, height, referencePixels),
                AlphaMask(width, height, alphaValues),
                AstroProcessingProfile.URBAN_SKY,
                6,
                emptyList()
            )
        }
        val fileBacked = runBlocking {
            FileBackedAdaptivePresetProcessor().process(
                stacked,
                reference,
                alpha,
                AstroProcessingProfile.URBAN_SKY,
                6,
                emptyList(),
                store,
                budget(),
                PipelineMemoryTracker()
            )
        }
        val actual = readAll(fileBacked.image)
        legacy.image.pixels.indices.forEach { index ->
            assertTrue("pixel $index", maxChannelDifference(legacy.image.pixels[index], actual[index]) <= 2)
        }
        for (y in 0 until height) {
            val boundary = y * width + 256
            assertTrue("chroma halo boundary row $y", maxChannelDifference(
                legacy.image.pixels[boundary],
                actual[boundary]
            ) <= 2)
        }
    }

    @Test fun fileBackedQualityMatchesInMemoryMetricsOnSmallFixture() = withRun { _, store ->
        val width = 64
        val height = 48
        val pixels = fixture(width, height)
        val alphaValues = FloatArray(width * height) { 1f }
        val image = writeTemporary(store, "quality-image", width, height, pixels)
        val reference = writeTemporary(store, "quality-ref", width, height, pixels)
        val alpha = writePlane(store, "quality-alpha", width, height, alphaValues)
        val legacy = ResultQualityAnalyzer().analyze(
            ArgbPixelImage(width, height, pixels),
            ArgbPixelImage(width, height, pixels),
            AlphaMask(width, height, alphaValues)
        )
        val actual = FileBackedResultQualityAnalyzer().analyze(image, reference, alpha)
        assertEquals(legacy.skyMedian, actual.skyMedian, 1f / 4095f)
        assertEquals(legacy.skyMad, actual.skyMad, 1f / 4095f)
        assertEquals(legacy.reliableStarCount, actual.reliableStarCount)
        assertEquals(legacy.foregroundMaximumPixelDifference, actual.foregroundMaximumPixelDifference)
        assertEquals(legacy.banding.combinedScore, actual.banding.combinedScore, 0.0001f)
    }

    @Test fun selectionKeepsHandleAndNeverMaterializesTwoCandidates() = withRun { _, store ->
        val pixels = fixture(8, 8)
        val reference = StoredResultCandidate(
            ResultCandidateType.REFERENCE,
            writeCandidate(store, ResultCandidateType.REFERENCE, 8, 8, pixels),
            metrics(8, 8)
        )
        val clean = StoredResultCandidate(
            ResultCandidateType.CLEAN_STACK,
            writeCandidate(store, ResultCandidateType.CLEAN_STACK, 8, 8, pixels),
            metrics(8, 8)
        )
        val processed = StoredResultCandidate(
            ResultCandidateType.PROCESSED,
            writeCandidate(store, ResultCandidateType.PROCESSED, 8, 8, pixels),
            metrics(8, 8)
        )
        val accepted = decision(true, processed.metrics)
        val selected = ResultSelectionPolicy().select(reference, clean, processed, accepted, decision(true, clean.metrics))
        assertSame(processed, selected.selected)
        assertSame(processed.image, selected.selected.image)
    }

    @Test fun streamingPngIsLosslessFullResolutionAndNeedsNoBitmap() = withRun { _, store ->
        val width = 1440
        val height = 1920
        val row = IntArray(width) { x -> 0xFF000000.toInt() or ((x and 0xFF) shl 16) or 0x00004020 }
        val writer = store.createTemporaryWriter("regression-1440x1920", width, height)
        repeat(height) { y ->
            row[0] = 0xFF000000.toInt() or (y and 0xFF)
            writer.writeRow(y, row)
        }
        val image = writer.finish()
        val output = ByteArrayOutputStream()
        FileBackedImageReader(image).use { PngStreamEncoder.encode(it, output) }
        val bytes = output.toByteArray()
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), bytes.copyOf(4))
        val decoded: BufferedImage = ImageIO.read(bytes.inputStream())
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertEquals(0xFF000000.toInt() or (1919 and 0xFF), decoded.getRGB(0, 1919))
    }

    @Test fun journalIsAtomicCreatedBeforeHeavyStageAndCompleted() {
        val root = Files.createTempDirectory("stage7-journal").toFile()
        val journal = ProcessingRunJournal(root, true)
        val run = journal.start("session", "URBAN_SKY", 128L * MIB, 20L * MIB, 60L * MIB, 100L)
        assertEquals("journal_created", journal.latestUnfinished()?.lastCompletedStage)
        journal.update(run.runId, "integration_completed", 200L)
        assertEquals("integration_completed", journal.latestUnfinished()?.lastCompletedStage)
        assertTrue(File(root, "jpeg-processing-journal").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        journal.markCompleted(run.runId, nowMillis = 300L)
        assertEquals(null, journal.latestUnfinished())
        root.deleteRecursively()
    }

    @Test fun unfinishedJournalIsDetectedAndCanBeAcknowledged() {
        val root = Files.createTempDirectory("stage7-unfinished").toFile()
        val journal = ProcessingRunJournal(root, true)
        val run = journal.start("session", "DEEP_SKY", 1, 0, 1)
        journal.update(run.runId, "final_candidate_selected")
        assertEquals(run.runId, journal.latestUnfinished()?.runId)
        journal.markRecovered(run.runId)
        assertEquals(null, journal.latestUnfinished())
        root.deleteRecursively()
    }

    @Test fun previousExitReasonsClassifyLowMemoryJavaNativeAnrSystemAndUnknown() {
        assertEquals(PreviousProcessExitKind.LOW_MEMORY,
            PreviousProcessExitReader.classify(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(PreviousProcessExitKind.JAVA_CRASH,
            PreviousProcessExitReader.classify(ApplicationExitInfo.REASON_CRASH))
        assertEquals(PreviousProcessExitKind.NATIVE_CRASH,
            PreviousProcessExitReader.classify(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(PreviousProcessExitKind.ANR,
            PreviousProcessExitReader.classify(ApplicationExitInfo.REASON_ANR))
        assertEquals(PreviousProcessExitKind.SYSTEM_KILL,
            PreviousProcessExitReader.classify(ApplicationExitInfo.REASON_OTHER))
        assertEquals(PreviousProcessExitKind.UNKNOWN, PreviousProcessExitReader.classify(Int.MAX_VALUE))
    }

    @Test fun appSpecificReportFallbackIsIndexedListedAndSafelyDeleted() {
        val root = Files.createTempDirectory("stage7-reports").toFile()
        val store = AppSpecificProcessingReportStore(root, true)
        val saved = store.write("Session_1", "Urban_001.png", "{\"ok\":true}")
        assertTrue(saved.file.isFile)
        assertEquals("Urban_001.processing.json", saved.reportFileName)
        assertEquals(listOf(saved.reportFileName), store.listForSession("Session_1").map { it.reportFileName })
        assertTrue(store.delete("Session_1", "Urban_001.png"))
        assertTrue(store.listForSession("Session_1").isEmpty())
        root.deleteRecursively()
    }

    @Test fun productionProfileStackUsesEveryFileBackedStageAndStreamingSave() {
        val source = source("app/src/main/java/com/example/astrophoto/JpegStacker.kt")
        val profile = source.substring(source.indexOf("suspend fun profileStack("), source.indexOf("suspend fun loadResultPreview("))
        listOf(
            "TemporaryPipelineFiles.create",
            "ResultCandidateStore(",
            "stackedWriter.writeTile",
            "FileBackedSkyForegroundComposer().compose",
            "FileBackedAdaptivePresetProcessor().process",
            "FileBackedResultQualityAnalyzer(",
            "StoredResultCandidate(",
            "FileBackedImageReader(finalSelection.selected.image)",
            "finalBitmapAllocationBytes = 0L",
            "ProcessingRunJournal(context)"
        ).forEach { required -> assertTrue(required, profile.contains(required)) }
        assertFalse(profile.contains("bitmapFromArgbImage"))
        assertFalse(profile.contains("Bitmap.createBitmap"))
        assertFalse(Regex("(?<!Stored)ResultCandidate\\(").containsMatchIn(profile))
    }

    @Test fun reportFallbackZipPairedDeleteAndThumbnailPreviewAreWiredInProduction() {
        val zip = source("app/src/main/java/com/example/astrophoto/SessionZipExporter.kt")
        val results = source("app/src/main/java/com/example/astrophoto/ProcessedResults.kt")
        val stacker = source("app/src/main/java/com/example/astrophoto/JpegStacker.kt")
        assertTrue(zip.contains("appendFallbackProcessingReports"))
        assertTrue(zip.contains("AppSpecificProcessingReportStore(context).listForSession"))
        assertTrue(results.contains("AppSpecificProcessingReportStore(context).delete"))
        assertTrue(stacker.contains("contentResolver.loadThumbnail"))
        assertTrue(stacker.contains("decodeSampledFile"))
    }

    @Test fun reportFailureCannotInvalidateAlreadySavedPngAndCancellationIsRethrown() {
        val source = source("app/src/main/java/com/example/astrophoto/JpegStacker.kt")
        val prepared = source.indexOf("atomicTextFile(\"final-report.json\"")
        val save = source.indexOf("LosslessProcessedImageWriter(context).write")
        val report = source.indexOf("ProcessingReportWriter(context).write", save)
        assertTrue(prepared >= 0 && save > prepared && report > save)
        assertTrue(source.substring(report, report + 700).contains("catch (error: CancellationException)"))
        assertTrue(source.substring(report, report + 1200).contains("ReportWriteOutcome.Failed"))
    }

    @Test fun reportContainsCompleteStage7MemoryAndRecoverySchema() {
        val source = source(
            "app/src/main/java/com/example/astrophoto/processing/jpeg/v2/diagnostics/ProcessingReport.kt"
        )
        listOf(
            "memorySchemaVersion",
            "runtimeMaxHeapBytes",
            "heapUsedAtRunStartBytes",
            "safeWorkingBudgetBytes",
            "peakEstimatedResidentBytes",
            "peakObservedHeapBytes",
            "maximumSimultaneousFullResolutionCandidates",
            "candidateStorageMode",
            "referenceCandidateBytesOnDisk",
            "cleanStackCandidateBytesOnDisk",
            "processedCandidateBytesOnDisk",
            "tileSizePerStage",
            "haloSizePerStage",
            "memoryPressureRetries",
            "finalBitmapAllocationBytes",
            "lastCompletedStage",
            "reportPublicationMode",
            "reportFallbackUsed",
            "staleRunRecoveryInformation"
        ).forEach { field ->
            assertTrue("$field declaration", source.contains("val $field:"))
            assertTrue("$field serialization", source.replace("\\\"", "\"").contains("\"$field\""))
        }
    }

    @Test fun previousProcessFailureIsShownAsDismissibleExistingStyleDialog() {
        val source = source("app/src/main/java/com/example/astrophoto/MainActivity.kt")
        assertTrue(source.contains("PreviousProcessingFailureDetector(this).detect()"))
        assertTrue(source.contains("processingFailure?.let"))
        assertTrue(source.contains("AlertDialog("))
        assertTrue(source.contains(".dismiss(failure)"))
    }

    @Test fun fileBackedQualityNeverLoadsFullReferenceOrTwoFullCandidates() {
        val source = source(
            "app/src/main/java/com/example/astrophoto/processing/jpeg/v2/quality/FileBackedResultQualityAnalyzer.kt"
        )
        assertTrue(source.contains("MAX_STAR_ANALYSIS_DIMENSION = 960"))
        assertFalse(source.contains("IntArray(reference.width * reference.height)"))
        assertFalse(source.contains("ArgbPixelImage(reference.width"))
    }

    @Test fun cleanupScopeCannotReachSessionsProcessedOutputsOrRaw() {
        val storage = source(
            "app/src/main/java/com/example/astrophoto/processing/jpeg/v2/storage/TemporaryPipelineFiles.kt"
        )
        assertTrue(storage.contains("canonicalDirectory.parentFile != canonicalRoot"))
        assertTrue(storage.contains("MARKER_FILE_NAME"))
        assertFalse(storage.contains("Pictures/AstroPhoto"))
        assertFalse(storage.contains("Lights/RAW"))
    }

    private inline fun withRun(block: (TemporaryPipelineFiles, ResultCandidateStore) -> Unit) {
        val root = Files.createTempDirectory("stage7-run").toFile()
        val run = TemporaryPipelineFiles.create(root)
        try {
            block(run, ResultCandidateStore(run))
        } finally {
            run.close()
            root.deleteRecursively()
        }
    }

    private fun writeCandidate(
        store: ResultCandidateStore,
        type: ResultCandidateType,
        width: Int,
        height: Int,
        pixels: IntArray
    ): FileBackedImage {
        val writer = store.createWriter(type, width, height)
        repeat(height) { writer.writeRow(it, pixels, it * width) }
        return store.register(type, writer.finish())
    }

    private fun writeTemporary(
        store: ResultCandidateStore,
        label: String,
        width: Int,
        height: Int,
        pixels: IntArray
    ): FileBackedImage {
        val writer = store.createTemporaryWriter(label, width, height)
        repeat(height) { writer.writeRow(it, pixels, it * width) }
        return writer.finish()
    }

    private fun writePlane(
        store: ResultCandidateStore,
        label: String,
        width: Int,
        height: Int,
        values: FloatArray
    ): FileBackedFloatPlane {
        val writer = store.createFloatPlaneWriter(label, width, height)
        repeat(height) { writer.writeRow(it, values, it * width) }
        return writer.finish()
    }

    private fun readAll(image: FileBackedImage): IntArray {
        val result = IntArray(image.width * image.height)
        FileBackedImageReader(image).use { reader ->
            val row = IntArray(image.width)
            repeat(image.height) { y ->
                reader.readArgbRow(y, row)
                row.copyInto(result, y * image.width)
            }
        }
        return result
    }

    private fun fixture(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val red = (18 + x * 3 + y) and 0xFF
        val green = (22 + x + y * 2) and 0xFF
        val blue = (30 + x * 2 + y * 3) and 0xFF
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
    }

    private fun budget(
        max: Long = 512L * MIB,
        total: Long = 128L * MIB,
        free: Long = 96L * MIB
    ) = JpegMemoryBudget(RuntimeHeapSnapshot(max, total, free), reserveBytes = 64L * MIB)

    private fun metrics(width: Int, height: Int) = ResultQualityMetrics(
        width, height, width.toDouble() / height, 1f, 0, 0f, 0f, 0f, 0f, 0,
        0.1f, 0.01f, 0.02f, 0.3f, LinearRgb(0.1f, 0.1f, 0.1f),
        LinearRgb(0f, 0f, 0f), 0f, BandingMetrics(0f, 0f, 0f), 0f,
        1f, 0f, 0f, 0, 0f, 0f, 1f
    )

    private fun decision(accepted: Boolean, metrics: ResultQualityMetrics) = QualityGateDecision(
        accepted, if (accepted) 1f else 0f, if (accepted) emptyList() else listOf("rejected"), emptyList(), metrics
    )

    private fun maxChannelDifference(first: Int, second: Int): Int = maxOf(
        kotlin.math.abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        kotlin.math.abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        kotlin.math.abs((first and 0xFF) - (second and 0xFF))
    )

    private fun source(path: String): String {
        val direct = File(path)
        val file = if (direct.isFile) {
            direct
        } else {
            generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
                .map { parent -> File(parent, path) }
                .firstOrNull(File::isFile) ?: direct
        }
        assertTrue("Missing source file $path", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    companion object {
        private const val MIB = 1024L * 1024L
    }
}
