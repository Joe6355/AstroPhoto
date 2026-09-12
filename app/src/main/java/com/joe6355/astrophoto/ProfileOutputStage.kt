package com.joe6355.astrophoto

import androidx.core.net.toUri

import com.joe6355.astrophoto.JpegStacker.Companion.POST_COMPLETION_JOURNAL_TAG

import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.PipelineTimingCollector
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReport
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReportWriter
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ReportWriteOutcome
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.SavedProcessingReport
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingRunJournal
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.AppSpecificProcessingReportStore
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.artifactSessionId
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.EnhancedAncillaryOutcome
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.EnhancedGlobalToneProcessor
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.publishOptionalEnhanced
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar as V2DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.joe6355.astrophoto.processing.jpeg.v2.model.StoredResultCandidate
import com.joe6355.astrophoto.processing.jpeg.v2.output.LosslessProcessedImageWriter
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ResultSelectionPolicy
import com.joe6355.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.quality.withExperimentalSafeFallback
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import com.joe6355.astrophoto.JpegStacker.Companion.POST_COMPLETION_TAG
import com.joe6355.astrophoto.JpegStacker.Companion.EXPERIMENTAL_PUBLICATION_FALLBACK_REASON

internal data class SavedProfileArtifacts(
    val saved: SavedProcessedImage,
    val additionalImageFileName: String?,
    val processingReport: ProcessingReport,
    val reportJson: String,
    val primaryFallbackUsed: Boolean = false,
    val primaryFallbackReason: String? = null
)

internal data class PublishedProfileReport(
    val processingReport: ProcessingReport,
    val reportJson: String,
    val reportFiles: List<String>
)

internal suspend fun JpegStacker.publishProcessingReport(
    session: SessionSummary,
    fileName: String,
    outputContentUri: String?,
    outputFilePath: String?,
    initialReport: ProcessingReport,
    initialReportJson: String,
    temporaryFiles: TemporaryPipelineFiles,
    pipelineTiming: PipelineTimingCollector,
    warnings: MutableList<String>,
    journalRunId: String?,
    runJournal: ProcessingRunJournal
): PublishedProfileReport {
    var processingReport = initialReport
    var reportJson = initialReportJson
    val reportOutcome: ReportWriteOutcome<SavedProcessingReport> = try {
        ReportWriteOutcome.Written(
            ProcessingReportWriter(context).write(session, fileName, reportJson)
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ReportWriteOutcome.Failed(error.message ?: error::class.java.simpleName)
    }
    val reportFiles = when (reportOutcome) {
        is ReportWriteOutcome.Written -> {
            pipelineTiming.record(
                "report_writing",
                reportOutcome.value.writeDurationMillis
            )
            if (reportOutcome.value.fallbackUsed) {
                processingReport = processingReport.copy(
                    reportPublicationMode = reportOutcome.value.publicationMode,
                    reportFallbackUsed = true
                )
                reportJson = processingReport.toJson()
                temporaryFiles.atomicTextFile("final-report.json", reportJson)
                runCatching {
                    AppSpecificProcessingReportStore(context).write(
                        session.folderName,
                        fileName,
                        reportJson
                    )
                }.onSuccess {
                    warnings += "Отчёт обработки сохранён во внутреннее хранилище"
                }.onFailure { error ->
                    warnings += "Не удалось обновить резервный отчёт обработки: " +
                        (error.message ?: error::class.java.simpleName)
                }
            }
            listOf(reportOutcome.value.fileName)
        }
        is ReportWriteOutcome.Failed -> {
            warnings += "Не удалось записать отчёт обработки: ${reportOutcome.reason}"
            val recovery = File(
                context.cacheDir,
                "jpeg-report-recovery-${journalRunId?.take(8) ?: "unknown"}.json"
            )
            runCatching { recovery.writeText(reportJson, Charsets.UTF_8) }
            emptyList()
        }
    }
    val publishedReport = (
        reportOutcome as? ReportWriteOutcome.Written<SavedProcessingReport>
        )?.value
    journalRunId?.let { runId ->
        if (publishedReport != null) {
            runCatching {
                runJournal.updatePublishedArtifacts(
                    runId = runId,
                    artifactSessionId = artifactSessionId(session.folderName),
                    outputFileName = fileName,
                    outputContentUri = outputContentUri,
                    outputFilePath = outputFilePath,
                    reportFileName = publishedReport.fileName,
                    reportContentUri = publishedReport.contentUri,
                    reportFilePath = publishedReport.filePath
                )
            }.onFailure { error ->
                warnings += "Не удалось обновить данные отчёта в журнале обработки"
                Log.e(
                    POST_COMPLETION_JOURNAL_TAG,
                    "artifact identity update failed run=${runId.take(8)} " +
                        "operation=report_published exception=${error::class.java.simpleName}",
                    error
                )
            }
        } else {
            runCatching { runJournal.update(runId, "report_publication_failed") }
                .onFailure { error ->
                    Log.e(
                        POST_COMPLETION_JOURNAL_TAG,
                        "report failure stage update failed run=${runId.take(8)} " +
                            "exception=${error::class.java.simpleName}",
                        error
                    )
                }
        }
    }
    Log.i(
        POST_COMPLETION_TAG,
        "post_completion.report_published run=${journalRunId?.take(8).orEmpty()} " +
            "output=$fileName report=${publishedReport != null}"
    )
    return PublishedProfileReport(processingReport, reportJson, reportFiles)
}

internal data class PrimaryPublication(
    val saved: SavedProcessedImage,
    val selected: StoredResultCandidate,
    val publishedOutputHash: String?,
    val fallbackUsed: Boolean,
    val fallbackReason: String? = null
)

internal suspend fun JpegStacker.savePrimaryAndAncillaryEnhanced(
    selected: StoredResultCandidate,
    referenceImage: FileBackedImage,
    requestedFileName: String,
    effectiveSkyAlpha: FileBackedFloatPlane,
    confirmedStars: List<V2DetectedStar>,
    candidateStore: ResultCandidateStore,
    session: SessionSummary,
    timestamp: String,
    pipelineTiming: PipelineTimingCollector,
    processingReport: ProcessingReport,
    warnings: MutableList<String>,
    temporaryFiles: TemporaryPipelineFiles,
    journalRunId: String?,
    runJournal: ProcessingRunJournal,
    experimentalSafeCandidate: StoredResultCandidate? = null,
    experimentalSafeFileName: String? = null,
    onProgress: suspend (message: String, current: Int, total: Int) -> Unit
): SavedProfileArtifacts {
    withContext(Dispatchers.Main.immediate) {
        onProgress("Сохранение PNG без потерь", 3, 3)
    }
    val pngWritingStarted = System.nanoTime()
    val primary = publishPrimaryWithExperimentalFallback(
        selected = selected,
        requestedFileName = requestedFileName,
        experimentalSafeCandidate = experimentalSafeCandidate,
        experimentalSafeFileName = experimentalSafeFileName,
        session = session,
        warnings = warnings
    )
    pipelineTiming.record(
        "png_writing",
        (System.nanoTime() - pngWritingStarted) / 1_000_000L
    )
    val enhanced = when {
        primary.fallbackUsed -> AncillaryEnhancedPublication(
            attempted = false,
            status = "NOT_ATTEMPTED_SAFE_FALLBACK"
        )
        processingReport.presetId == AstroProcessingProfile.EXPERIMENTAL_STARS.name ->
            AncillaryEnhancedPublication(
                attempted = false,
                status = "NOT_ATTEMPTED_EXPERIMENTAL_STARS"
            )
        else -> publishAncillaryEnhanced(
            selected = primary.selected,
            referenceImage = referenceImage,
            effectiveSkyAlpha = effectiveSkyAlpha,
            confirmedStars = confirmedStars,
            candidateStore = candidateStore,
            session = session,
            timestamp = timestamp,
            pipelineTiming = pipelineTiming
        )
    }
    warnings += enhanced.processingWarnings
    val (userFacingSaved, additionalImageFileName) = selectUserFacingProfileOutput(
        safeBase = primary.saved,
        enhanced = enhanced.saved
    )
    if (enhanced.saved != null) {
        warnings += "Enhanced выбран как основной результат; чистый стек сохранён отдельно"
    }
    val effectiveReport = if (primary.fallbackUsed) {
        val cleanStage = processingReport.sensorDefectFiltering.referenceStarRetentionStages
            .lastOrNull { it.stage == "composed_clean_result" }
        val retentionStages = if (cleanStage == null) {
            processingReport.sensorDefectFiltering.referenceStarRetentionStages
        } else {
            processingReport.sensorDefectFiltering.referenceStarRetentionStages
                .filterNot { it.stage == "selected_candidate" } +
                cleanStage.copy(stage = "selected_candidate")
        }
        processingReport.copy(
            selectedCandidateType = ResultCandidateType.CLEAN_STACK.name,
            fallbackUsed = true,
            fallbackReason = primary.fallbackReason,
            internalFallbackLabel = ResultSelectionPolicy.INTERNAL_FALLBACK_LABEL,
            processingOutcome = JpegProfileProcessingOutcome.CLEAN_FALLBACK.name,
            actualStarContrastGain = 1f,
            sensorDefectFiltering = processingReport.sensorDefectFiltering.copy(
                referenceStarRetentionStages = retentionStages
            )
        )
    } else {
        processingReport
    }
    val publishedOutputHash = if (enhanced.saved != null) {
        publishedOutputHash(userFacingSaved, warnings)
    } else {
        primary.publishedOutputHash
    }
    val selectedRetention = effectiveReport.sensorDefectFiltering.referenceStarRetentionStages
        .lastOrNull { it.stage == "selected_candidate" }
    val outputRetentionStages = buildList {
        addAll(effectiveReport.sensorDefectFiltering.referenceStarRetentionStages)
        selectedRetention?.let { selected ->
            add(
                selected.copy(
                    stage = "encoded_output",
                    measurementBasis = "lossless_png_candidate_lineage"
                )
            )
            if (publishedOutputHash != null && enhanced.saved == null) {
                add(
                    selected.copy(
                        stage = "published_output",
                        measurementBasis = "reopened_published_png_hash_lineage"
                    )
                )
            }
        }
    }
    val updatedReport = effectiveReport.copy(
        outputPngDisplayName = userFacingSaved.fileName,
        sensorDefectFiltering = effectiveReport.sensorDefectFiltering.copy(
            publishedOutputHash = publishedOutputHash,
            referenceStarRetentionStages = outputRetentionStages
        ),
        stageDurationsMillis = pipelineTiming.snapshot(),
        lastCompletedStage = "png_saved",
        warnings = warnings.distinct(),
        enhancedAttempted = enhanced.attempted,
        enhancedCreated = enhanced.fileName != null,
        enhancedValidationStatus = enhanced.status,
        enhancedOutputFileName = enhanced.fileName,
        enhancedGain = enhanced.metrics["gain"] ?: 0f,
        enhancedRejectionReasons = enhanced.reasons,
        enhancedValidationWarnings = enhanced.validationWarnings,
        enhancedValidationMetrics = enhanced.metrics
    )
    val bookkeeping = completeSavedResultBookkeeping(
        report = updatedReport,
        writeCacheReport = { json ->
            temporaryFiles.atomicTextFile("final-report.json", json)
        },
        updateJournal = {
            journalRunId?.let { runJournal.update(it, "png_saved") }
        }
    )
    warnings += bookkeeping.report.warnings
    return SavedProfileArtifacts(
        saved = userFacingSaved,
        additionalImageFileName = additionalImageFileName,
        processingReport = bookkeeping.report,
        reportJson = bookkeeping.reportJson,
        primaryFallbackUsed = primary.fallbackUsed,
        primaryFallbackReason = primary.fallbackReason
    )
}

internal suspend fun JpegStacker.publishPrimaryWithExperimentalFallback(
    selected: StoredResultCandidate,
    requestedFileName: String,
    experimentalSafeCandidate: StoredResultCandidate?,
    experimentalSafeFileName: String?,
    session: SessionSummary,
    warnings: MutableList<String>
): PrimaryPublication = withExperimentalSafeFallback(
    enabled = experimentalSafeCandidate != null,
    primary = {
        val saved = writePrimaryCandidate(selected, requestedFileName, session)
        val hash = publishedOutputHash(saved, warnings)
        if (experimentalSafeCandidate != null && hash == null) {
            val removed = runCatching { deletePublishedOutput(saved) }.getOrDefault(false)
            if (!removed) warnings += "Unverified Experimental Stars output cleanup failed"
            error("Experimental Stars PNG post-save verification failed")
        }
        PrimaryPublication(saved, selected, hash, fallbackUsed = false)
    },
    safeFallback = { primaryFailure ->
        val safe = checkNotNull(experimentalSafeCandidate)
        val safeName = requireNotNull(experimentalSafeFileName)
        warnings += "Experimental Stars publication failed; RecoveredStars saved: " +
            (primaryFailure.message ?: primaryFailure::class.java.simpleName)
        val saved = writePrimaryCandidate(safe, safeName, session)
        PrimaryPublication(
            saved = saved,
            selected = safe,
            publishedOutputHash = publishedOutputHash(saved, warnings),
            fallbackUsed = true,
            fallbackReason = EXPERIMENTAL_PUBLICATION_FALLBACK_REASON
        )
    }
)

internal suspend fun JpegStacker.writePrimaryCandidate(
    selected: StoredResultCandidate,
    requestedFileName: String,
    session: SessionSummary
): SavedProcessedImage = FileBackedImageReader(selected.image).use { selectedReader ->
    LosslessProcessedImageWriter(context).write(session, selectedReader, requestedFileName)
}

internal fun JpegStacker.publishedOutputHash(
    saved: SavedProcessedImage,
    warnings: MutableList<String>
): String? = runCatching { sha256PublishedOutput(saved) }
    .onFailure { error ->
        warnings += "Published PNG hash unavailable: " +
            (error.message ?: error::class.java.simpleName)
    }
    .getOrNull()

internal fun JpegStacker.availableTemporaryBytes(directory: File): Long = runCatching {
    val storageManager = context.getSystemService(StorageManager::class.java)
    val storageUuid = storageManager.getUuidForPath(directory)
    storageManager.getAllocatableBytes(storageUuid)
}.getOrElse {
    directory.usableSpace
}

internal fun JpegStacker.deletePublishedOutput(saved: SavedProcessedImage): Boolean = when {
    saved.contentUri != null -> context.contentResolver.delete(
        saved.contentUri.toUri(),
        null,
        null
    ) > 0
    saved.filePath != null -> File(saved.filePath).delete()
    else -> false
}

internal fun JpegStacker.sha256PublishedOutput(saved: SavedProcessedImage): String {
    val input = when {
        saved.contentUri != null -> context.contentResolver.openInputStream(
            saved.contentUri.toUri()
        )
        saved.filePath != null -> File(saved.filePath).inputStream()
        else -> null
    } ?: error("Published PNG cannot be reopened")
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    input.use { stream ->
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class AncillaryEnhancedPublication(
    val attempted: Boolean,
    val status: String,
    val saved: SavedProcessedImage? = null,
    val reasons: List<String> = emptyList(),
    val validationWarnings: List<String> = emptyList(),
    val metrics: Map<String, Float> = emptyMap(),
    val processingWarnings: List<String> = emptyList()
) {
    val fileName: String? get() = saved?.fileName
}

internal suspend fun JpegStacker.publishAncillaryEnhanced(
    selected: StoredResultCandidate,
    referenceImage: FileBackedImage,
    effectiveSkyAlpha: FileBackedFloatPlane,
    confirmedStars: List<V2DetectedStar>,
    candidateStore: ResultCandidateStore,
    session: SessionSummary,
    timestamp: String,
    pipelineTiming: PipelineTimingCollector
): AncillaryEnhancedPublication {
    if (selected.type != ResultCandidateType.CLEAN_STACK) {
        return AncillaryEnhancedPublication(
            attempted = false,
            status = "NOT_ATTEMPTED"
        )
    }
    val started = System.nanoTime()
    val artifactCheckContext = currentCoroutineContext()
    val outcome = publishOptionalEnhanced(
        createCandidate = {
            val candidate = EnhancedGlobalToneProcessor().createStrongestAcceptedCandidate(
                baseline = selected.image,
                effectiveSkyAlpha = effectiveSkyAlpha,
                confirmedStars = confirmedStars,
                store = candidateStore,
                knownBaselineQuality = selected.metrics
            )
            val artifacts = LineArtifactDetector().compareStarNeighborhoods(
                referenceImage, candidate.generation.image, confirmedStars,
                cancellationCheck = { artifactCheckContext.ensureActive() }
            )
            candidate.copy(validation = candidate.validation.copy(
                accepted = candidate.validation.accepted && artifacts.accepted,
                hardFailureReasons = (candidate.validation.hardFailureReasons + artifacts.hardFailureReasons).distinct(),
                warnings = (candidate.validation.warnings + artifacts.warningReasons).distinct()
            ))
        },
        saveCandidate = { enhancedImage ->
            FileBackedImageReader(enhancedImage).use { enhancedReader ->
                LosslessProcessedImageWriter(context).write(
                    session,
                    enhancedReader,
                    "Enhanced_$timestamp.png"
                )
            }
        },
        releaseCandidate = { enhancedImage ->
            candidateStore.deleteTemporary(enhancedImage)
        }
    )
    pipelineTiming.record(
        "enhanced_global_tone",
        (System.nanoTime() - started) / 1_000_000L
    )
    return when (outcome) {
        is EnhancedAncillaryOutcome.Saved -> AncillaryEnhancedPublication(
            attempted = true,
            status = "SAVED",
            saved = outcome.result,
            validationWarnings = outcome.candidate.validation.warnings,
            metrics = outcome.candidate.validation.metrics.asReportMetrics()
        )
        is EnhancedAncillaryOutcome.Rejected -> {
            val reasons = outcome.candidate.validation.hardFailureReasons
            AncillaryEnhancedPublication(
                attempted = true,
                status = "REJECTED",
                reasons = reasons,
                validationWarnings = outcome.candidate.validation.warnings,
                metrics = outcome.candidate.validation.metrics.asReportMetrics(),
                processingWarnings = listOf(
                    "Enhanced rejected: ${reasons.joinToString("|")}"
                )
            )
        }
        is EnhancedAncillaryOutcome.Failed -> AncillaryEnhancedPublication(
            attempted = true,
            status = "FAILED",
            reasons = listOf(outcome.reason),
            validationWarnings = outcome.candidate?.validation?.warnings.orEmpty(),
            metrics = outcome.candidate?.validation?.metrics?.asReportMetrics().orEmpty(),
            processingWarnings = listOf(
                "Enhanced was not created: ${outcome.reason}"
            )
        )
    }
}
