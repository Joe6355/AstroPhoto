package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.completion.AutomaticProfileCompletionCoordinator
import com.example.astrophoto.processing.jpeg.v2.completion.POST_COMPLETION_WARNING
import com.example.astrophoto.processing.jpeg.v2.completion.PostCompletionEvent
import com.example.astrophoto.processing.jpeg.v2.completion.appendUniqueResult
import com.example.astrophoto.processing.jpeg.v2.diagnostics.PreviousRunClassification
import com.example.astrophoto.processing.jpeg.v2.diagnostics.PublishedArtifactVerifier
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingRunJournal
import com.example.astrophoto.processing.jpeg.v2.diagnostics.artifactSessionId
import com.example.astrophoto.processing.jpeg.v2.diagnostics.classifyPreviousRun
import com.example.astrophoto.processing.jpeg.v2.diagnostics.completeJournalWithSingleRetry
import com.example.astrophoto.processing.jpeg.v2.diagnostics.publishedReportIdentityMatches
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JpegPostCompletionHotfixTest {
    @Test fun callbackFailureIsLoggedWarnedAndKeepsSuccessfulResultAndArtifacts() {
        val root = Files.createTempDirectory("post-completion-artifacts").toFile()
        val png = File(root, "Urban_001.png").apply { writeBytes(PNG_SIGNATURE + byteArrayOf(1, 2, 3)) }
        val report = File(root, "Urban_001.processing.json").apply { writeText("{\"ok\":true}") }
        val created = result(filePath = png.absolutePath, processingRunId = "a".repeat(32))
        var results = emptyList<JpegStackResult>()
        var status = ""
        val events = mutableListOf<PostCompletionEvent>()

        val failures = AutomaticProfileCompletionCoordinator(events::add).complete(
            existingResults = results,
            created = created,
            totalInputFrames = 8,
            selectedResultTitle = "обработанный",
            updateResults = { results = it },
            updateStatus = { status = it },
            onStackCompleted = { error("refresh failed") }
        )

        assertEquals(listOf(created), results)
        assertTrue(status.contains(created.fileName))
        assertTrue(status.contains(POST_COMPLETION_WARNING))
        assertEquals(listOf("callback"), failures.map { it.operation })
        assertTrue(events.any {
            it.operation == "callback" && it.phase == "failed" &&
                it.throwable is IllegalStateException
        })
        assertTrue(png.isFile && png.length() > PNG_SIGNATURE.size)
        assertEquals("{\"ok\":true}", report.readText())
        root.deleteRecursively()
    }

    @Test fun journalIsCompletedBeforeExternalCallbackRuns() {
        val root = Files.createTempDirectory("post-completion-journal").toFile()
        val journal = ProcessingRunJournal(root, true)
        val run = journal.start("session", "URBAN_SKY", 1, 0, 1)
        journal.update(run.runId, "report_published")
        journal.markCompleted(run.runId)
        var callbackRan = false

        AutomaticProfileCompletionCoordinator { }.complete(
            existingResults = emptyList(),
            created = result(processingRunId = run.runId),
            totalInputFrames = 8,
            selectedResultTitle = "обработанный",
            updateResults = {},
            updateStatus = {},
            onStackCompleted = {
                assertTrue(journal.read(run.runId)?.completed == true)
                callbackRan = true
            }
        )

        assertTrue(callbackRan)
        root.deleteRecursively()
    }

    @Test fun journalCompletionRetriesOnlyOnceAndReportsFailureWithoutTouchingResult() {
        var attempts = 0
        val png = Files.createTempFile("journal-result", ".png").toFile().apply { writeBytes(PNG_SIGNATURE) }
        val failure = completeJournalWithSingleRetry(
            markCompleted = {
                attempts++
                error("journal unavailable")
            }
        )
        assertEquals(2, attempts)
        assertEquals(2, failure?.attempts)
        assertTrue(png.isFile)
        png.delete()
    }

    @Test fun journalCompletionSucceedsOnSingleRetry() {
        var attempts = 0
        val failure = completeJournalWithSingleRetry(
            markCompleted = {
                attempts++
                if (attempts == 1) error("transient")
            }
        )
        assertEquals(2, attempts)
        assertEquals(null, failure)
    }

    @Test fun cancellationFromCompletionStepIsRethrownUnchanged() {
        val cancellation = CancellationException("cancel")
        try {
            AutomaticProfileCompletionCoordinator { }.complete(
                existingResults = emptyList(),
                created = result(),
                totalInputFrames = 8,
                selectedResultTitle = "обработанный",
                updateResults = {},
                updateStatus = {},
                onStackCompleted = { throw cancellation }
            )
            fail("CancellationException was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun publishedArtifactsRequireMatchingRunSessionAndOutputIdentity() {
        val root = Files.createTempDirectory("artifact-identity").toFile()
        val journal = ProcessingRunJournal(root, true)
        val run = journal.start("Session_1", "DEEP_SKY", 1, 0, 1)
        val sessionId = artifactSessionId("Session_1")
        val png = File(root, "Deep_001.png").apply { writeBytes(PNG_SIGNATURE) }
        val report = File(root, "Deep_001.processing.json")
        val published = journal.updatePublishedArtifacts(
            runId = run.runId,
            artifactSessionId = sessionId,
            outputFileName = "Deep_001.png",
            outputContentUri = null,
            outputFilePath = png.absolutePath,
            reportFileName = "Deep_001.processing.json",
            reportContentUri = null,
            reportFilePath = report.absolutePath
        )
        val json = """{
          "processingRunId": "${run.runId}",
          "artifactSessionId": "$sessionId",
          "outputPngDisplayName": "Deep_001.png"
        }"""
        report.writeText(json)
        val verifier = PublishedArtifactVerifier { _, filePath ->
            filePath?.let(::File)?.takeIf(File::isFile)?.inputStream()
        }
        assertTrue(verifier.verify(published))
        assertTrue(publishedReportIdentityMatches(json, published))
        assertEquals(
            PreviousRunClassification.PROCESSING_COMPLETED_UI_FAILURE,
            classifyPreviousRun(published, publishedArtifactsValid = true)
        )
        assertEquals(
            PreviousRunClassification.INTERRUPTED_PROCESSING,
            classifyPreviousRun(published, publishedArtifactsValid = false)
        )
        assertFalse(publishedReportIdentityMatches(json.replace(run.runId, "b".repeat(32)), published))
        report.delete()
        assertFalse(verifier.verify(published))
        root.deleteRecursively()
    }

    @Test fun recoveredCompletedUiFailureIsNotReportedTwice() {
        val root = Files.createTempDirectory("artifact-recovered-once").toFile()
        val journal = ProcessingRunJournal(root, true)
        val run = journal.start("Session_1", "MAX_STARS", 1, 0, 1)
        journal.update(run.runId, "report_published")
        assertEquals(run.runId, journal.latestUnfinished()?.runId)
        journal.markCompleted(run.runId, "completed_ui_failure_recovered")
        assertEquals(null, journal.latestUnfinished())
        root.deleteRecursively()
    }

    @Test fun duplicateResultIdentityIsNotAddedAcrossRecomposition() {
        val created = result(contentUri = "content://processed/42")
        assertSame(created, appendUniqueResult(listOf(created), created).single())
        assertEquals(1, appendUniqueResult(listOf(created), created.copy(fileName = "renamed.png")).size)
    }

    @Test fun productionOrderCompletesJournalBeforeUiAndHasSafeBoundaryFinally() {
        val source = source("app/src/main/java/com/example/astrophoto/JpegStacker.kt")
        val profile = source.substring(
            source.indexOf("suspend fun profileStack("),
            source.indexOf("suspend fun loadResultPreview(")
        )
        assertOrdered(
            profile,
            "post_completion.report_published",
            "post_completion.session_info.start",
            "post_completion.result_created",
            "post_completion.temp_cleanup.start",
            "post_completion.journal_complete.start",
            "post_completion.profile_returned"
        )
        val automatic = source.substring(
            source.indexOf("fun startProfile(profile:"),
            source.indexOf("    Column(", source.indexOf("fun startProfile(profile:"))
        )
        assertTrue(automatic.contains("AutomaticProfileCompletionCoordinator"))
        assertTrue(automatic.contains("catch (error: CancellationException)"))
        assertTrue(automatic.contains("catch (error: Throwable)"))
        assertTrue(automatic.contains("stacking = false"))
        assertTrue(automatic.contains("processingJob = null"))
        val browser = source("app/src/main/java/com/example/astrophoto/SessionBrowser.kt")
        val refresh = browser.substring(
            browser.indexOf("LaunchedEffect(checkRefreshKey)"),
            browser.indexOf("    if (showingFrames)")
        )
        assertTrue(refresh.contains("post_completion.session.refresh.start"))
        assertTrue(refresh.contains("catch (error: CancellationException)"))
        assertTrue(refresh.contains("catch (error: Throwable)"))
    }

    @Test fun manualAndRawWorkflowRemainOutsideAutomaticCoordinator() {
        val source = source("app/src/main/java/com/example/astrophoto/JpegStacker.kt")
        val manual = source.substring(source.indexOf("fun startStacking()"), source.indexOf("fun startRawStacking()"))
        val raw = source.substring(source.indexOf("fun startRawStacking()"), source.indexOf("fun startProfile(profile:"))
        assertFalse(manual.contains("AutomaticProfileCompletionCoordinator"))
        assertFalse(raw.contains("AutomaticProfileCompletionCoordinator"))
        assertTrue(manual.contains("onStackCompleted()"))
        assertTrue(raw.contains("onStackCompleted()"))
    }

    @Test fun missingImportedSessionInfoDoesNotAttemptForbiddenPicturesInsert() {
        val source = source("app/src/main/java/com/example/astrophoto/JpegStacker.kt")
        val helper = source.substring(
            source.indexOf("private fun appendMediaStoreSessionInfo("),
            source.indexOf("private fun appendLegacySessionInfo(")
        )
        assertTrue(helper.contains("val uri = existingUri ?: return false"))
        assertFalse(helper.contains("resolver.insert("))
    }

    private fun result(
        filePath: String? = null,
        contentUri: String? = null,
        processingRunId: String? = null
    ) = JpegStackResult(
        fileName = "Urban_001.png",
        displayPath = filePath ?: contentUri.orEmpty(),
        contentUri = contentUri,
        filePath = filePath,
        frameCount = 6,
        sessionInfoUpdated = true,
        selectedResultType = "PROCESSED",
        processingRunId = processingRunId
    )

    private fun assertOrdered(source: String, vararg markers: String) {
        var previous = -1
        markers.forEach { marker ->
            val index = source.indexOf(marker)
            assertTrue("Missing or out-of-order marker $marker", index > previous)
            previous = index
        }
    }

    private fun source(path: String): String {
        val direct = File(path)
        val file = if (direct.isFile) direct else generateSequence(
            File(System.getProperty("user.dir") ?: ".")
        ) {
            it.parentFile
        }.map { File(it, path) }.first(File::isFile)
        return file.readText(Charsets.UTF_8)
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
