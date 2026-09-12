@file:Suppress("UseKtx") // KTX bitmap inlining pushes profileStack toward the JVM method-size limit.

package com.joe6355.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ProfileAnalysisCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorCandidateDetector
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.buildAutomaticSensorDefectMask
import com.joe6355.astrophoto.processing.jpeg.v2.completion.PostCompletionEvent
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.FrameRegistrationReport
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.FrameWeightReport
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.IntegrationReport
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.PipelineTimingCollector
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReport
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReportWriter
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingRunJournal
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.artifactSessionId
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.completeJournalWithSingleRetry
import com.joe6355.astrophoto.processing.jpeg.v2.integration.FrameWeightCalculator
import com.joe6355.astrophoto.processing.jpeg.v2.integration.FrameWeightInput
import com.joe6355.astrophoto.processing.jpeg.v2.integration.IntegrationCheckpointFrameSignature
import com.joe6355.astrophoto.processing.jpeg.v2.integration.ProfileIntegrationCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.joe6355.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar as V2DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameWeight
import com.joe6355.astrophoto.processing.jpeg.v2.model.IntegrationDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.joe6355.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.joe6355.astrophoto.processing.jpeg.v2.model.StoredResultCandidate
import com.joe6355.astrophoto.processing.jpeg.v2.model.StoredFinalResultSelection
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import com.joe6355.astrophoto.processing.jpeg.v2.quality.AstroResultQualityGate
import com.joe6355.astrophoto.processing.jpeg.v2.quality.CleanStackValidationEvidence
import com.joe6355.astrophoto.processing.jpeg.v2.quality.CleanStackExecutionPolicy
import com.joe6355.astrophoto.processing.jpeg.v2.quality.CoverageUniformityValidator
import com.joe6355.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.quality.FileBackedResultQualityAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ResultSelectionPolicy
import com.joe6355.astrophoto.processing.jpeg.v2.registration.ProfileRegistrationCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.joe6355.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.joe6355.astrophoto.processing.jpeg.v2.registration.VerificationMetricsAggregator
import com.joe6355.astrophoto.processing.jpeg.v2.registration.restoreOrComputeRegistration
import com.joe6355.astrophoto.processing.jpeg.v2.registration.buildTemporalFeatureFrames
import com.joe6355.astrophoto.processing.jpeg.v2.memory.ImageAllocationEstimate
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import com.joe6355.astrophoto.processing.jpeg.v2.output.LosslessProcessedImageWriter
import com.joe6355.astrophoto.processing.jpeg.v2.output.requireValidPngDimensions

enum class JpegProfileProcessingOutcome {
    PROCESSED,
    CLEAN_FALLBACK,
    FAILED_REGISTRATION
}

class JpegProfileProcessingException(
    val outcome: JpegProfileProcessingOutcome,
    message: String,
    val acceptedFrames: Int? = null,
    val totalFrames: Int? = null,
    val minimumFrames: Int? = null,
    val canContinueWithUserApproval: Boolean = false
) : IllegalStateException(message)

internal data class JpegProfileOutputPlan(
    val outcome: JpegProfileProcessingOutcome,
    val filePrefix: String
)

internal fun shouldRetryAutomaticIntegrationWithoutMask(
    report: SensorDefectFilteringReport
): Boolean =
    report.sampleLevelFilteringApplied &&
        report.insufficientCoveragePixelCount > 0

internal fun requireMinimumRegisteredFrames(
    acceptedFrames: Int,
    totalFrames: Int,
    profile: AstroProcessingProfile,
    userApprovedInsufficientFrames: Boolean = false
) {
    if (acceptedFrames < profile.minimumFrames &&
        (!userApprovedInsufficientFrames || acceptedFrames == 0)
    ) {
        throw JpegProfileProcessingException(
            JpegProfileProcessingOutcome.FAILED_REGISTRATION,
            "Регистрация не удалась: использовано $acceptedFrames/$totalFrames, " +
                "минимум для ${profile.title} — ${profile.minimumFrames}. Файл профиля не создан.",
            acceptedFrames = acceptedFrames,
            totalFrames = totalFrames,
            minimumFrames = profile.minimumFrames,
            canContinueWithUserApproval = acceptedFrames > 0
        )
    }
}

internal fun Throwable.userContinuableProfileFailure(): JpegProfileProcessingException? =
    generateSequence(this) { it.cause }
        .filterIsInstance<JpegProfileProcessingException>()
        .firstOrNull { it.canContinueWithUserApproval }

internal fun jpegProfileOutputPlan(
    profile: AstroProcessingProfile,
    selectedType: ResultCandidateType,
    acceptedFrames: Int,
    totalFrames: Int,
    failureReason: String?
): JpegProfileOutputPlan = when (selectedType) {
    ResultCandidateType.PROCESSED -> JpegProfileOutputPlan(
        JpegProfileProcessingOutcome.PROCESSED,
        profile.filePrefix
    )
    ResultCandidateType.CLEAN_STACK -> JpegProfileOutputPlan(
        JpegProfileProcessingOutcome.CLEAN_FALLBACK,
        ResultSelectionPolicy.INTERNAL_FALLBACK_LABEL
    )
    ResultCandidateType.REFERENCE -> throw JpegProfileProcessingException(
        JpegProfileProcessingOutcome.FAILED_REGISTRATION,
        "Стек отклонён проверкой качества: использовано $acceptedFrames/$totalFrames; " +
            "${failureReason ?: "reference-only result"}. Файл профиля не создан.",
        acceptedFrames = acceptedFrames,
        totalFrames = totalFrames,
        minimumFrames = profile.minimumFrames,
        canContinueWithUserApproval = acceptedFrames > 0
    )
}

internal fun userApprovedProfileSelection(
    selection: StoredFinalResultSelection,
    cleanStack: StoredResultCandidate,
    userApproved: Boolean
): StoredFinalResultSelection = if (
    userApproved && selection.selected.type == ResultCandidateType.REFERENCE
) {
    selection.copy(
        selected = cleanStack,
        fallbackUsed = true,
        fallbackReason = "user_approved_rejected_frames",
        internalFallbackLabel = ResultSelectionPolicy.INTERNAL_FALLBACK_LABEL
    )
} else {
    selection
}

internal fun recordUserApprovedFrameWarning(
    warnings: MutableList<String>,
    userApproved: Boolean,
    acceptedFrames: Int,
    totalFrames: Int,
    minimumFrames: Int
) {
    if (userApproved && acceptedFrames < minimumFrames) {
        warnings += "Продолжено с разрешения пользователя: принято $acceptedFrames/$totalFrames кадров"
    }
}

internal data class SavedResultBookkeeping(
    val report: ProcessingReport,
    val reportJson: String
)

internal fun completeSavedResultBookkeeping(
    report: ProcessingReport,
    writeCacheReport: (String) -> Unit,
    updateJournal: () -> Unit
): SavedResultBookkeeping {
    val failures = mutableListOf<String>()
    val initialJson = report.toJson()
    val cacheWritten = try {
        writeCacheReport(initialJson)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        failures += "Post-save report cache update failed: " +
            (error.message ?: error::class.java.simpleName)
        false
    }
    try {
        updateJournal()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        failures += "Post-save journal update failed: " +
            (error.message ?: error::class.java.simpleName)
    }
    if (failures.isEmpty()) return SavedResultBookkeeping(report, initialJson)
    val finalReport = report.copy(warnings = (report.warnings + failures).distinct())
    val finalJson = finalReport.toJson()
    if (cacheWritten) {
        runCatching { writeCacheReport(finalJson) }
    }
    return SavedResultBookkeeping(finalReport, finalJson)
}

class JpegStacker internal constructor(
    internal val context: Context,
    private val manualAlignmentFailureInjector: ManualAlignmentFailureInjector =
        NoOpManualAlignmentFailureInjector
) {
    private val profileRegistrationCancellation = AtomicBoolean(false)
    private val userApprovedInsufficientProfileFrames = AtomicBoolean(false)

    fun cancelProfileRegistration() {
        profileRegistrationCancellation.set(true)
    }

    fun setUserApprovedInsufficientProfileFrames(approved: Boolean) {
        userApprovedInsufficientProfileFrames.set(approved)
    }

    suspend fun stack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        alignFrames: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit,
        onAlignment: suspend (current: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        alignmentSafe: Boolean = true,
        autoStretch: Boolean = false,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL
    ): Result<JpegStackResult> = runTiledManualStack(
        session, frames, ManualAlignedStackMode.AVERAGE, alignFrames, alignmentSafe, autoStretch, source
    ) { message, current, total ->
        onProgress(current, total)
        onAlignment(current, total, message)
    }

    suspend fun stackWithDarkFrames(
        session: SessionSummary,
        lightFrames: List<SessionFrame>,
        darkFrames: List<SessionFrame>,
        shadowOffset: Int,
        alignFrames: Boolean = false,
        alignmentSafe: Boolean = true,
        autoStretch: Boolean = false,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL,
        darkCrop: CropManifestEntry? = null,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit
    ): Result<JpegStackResult> = runTiledManualStack(
        session, lightFrames, ManualAlignedStackMode.DARK_SUBTRACTED_AVERAGE, alignFrames,
        alignmentSafe, autoStretch, source, darkFrames = darkFrames, shadowOffset = shadowOffset,
        darkCrop = darkCrop, onProgress = onProgress
    )

    suspend fun medianStack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        alignFrames: Boolean,
        alignmentSafe: Boolean = true,
        autoStretch: Boolean = false,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit
    ): Result<JpegStackResult> = runTiledManualStack(
        session, frames, ManualAlignedStackMode.MEDIAN, alignFrames, alignmentSafe,
        autoStretch, source, onProgress = onProgress
    )

    suspend fun sigmaStack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        sigma: Double,
        alignFrames: Boolean,
        alignmentSafe: Boolean = true,
        autoStretch: Boolean = false,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit
    ): Result<JpegStackResult> = runTiledManualStack(
        session, frames, ManualAlignedStackMode.SIGMA, alignFrames, alignmentSafe,
        autoStretch, source, sigma = sigma, onProgress = onProgress
    )

    private suspend fun runTiledManualStack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        mode: ManualAlignedStackMode,
        alignFrames: Boolean,
        alignmentSafe: Boolean,
        autoStretch: Boolean,
        source: ManualStackingSource,
        sigma: Double = 2.0,
        darkFrames: List<SessionFrame> = emptyList(),
        shadowOffset: Int = 0,
        darkCrop: CropManifestEntry? = null,
        onProgress: suspend (String, Int, Int) -> Unit
    ): Result<JpegStackResult> = withContext(Dispatchers.IO) {
        runManualStackingOperation(
            onAlignmentFailure = { appendManualAlignmentFailureSessionInfo(session, mode, it) }
        ) {
            require(frames.size >= 2) { "Недостаточно JPEG кадров" }
            require(frames.all { it.category == SessionFrameCategory.LIGHTS_JPEG }) {
                "Для стеккинга можно использовать только Lights/JPEG"
            }
            require(mode != ManualAlignedStackMode.SIGMA || sigma in SUPPORTED_SIGMA_VALUES)
            suspend fun progress(message: String, current: Int, total: Int) =
                withContext(Dispatchers.Main.immediate) { onProgress(message, current, total) }
            val robust = mode == ManualAlignedStackMode.MEDIAN || mode == ManualAlignedStackMode.SIGMA
            val selected = if (robust) {
                JpegAutoSelector(context).selectForStacking(frames, 30) { current, total ->
                    progress("Звёздный отбор: $current из $total", current, total)
                }.frames
            } else frames
            val dimensions = selected.map { readDimensions(it) ?: error("Не удалось прочитать JPEG: ${it.fileName}") }
            dimensions.forEach { (width, height) -> requireValidPngDimensions(width, height) }
            if (source == ManualStackingSource.CROPPED) require(dimensions.distinct().size == 1) {
                "Selected cropped frames have different dimensions"
            }
            val width = dimensions.minOf { it.first }
            val height = dimensions.minOf { it.second }
            val darkDimensions = darkFrames.map { readDimensions(it) ?: error("Не удалось прочитать dark: ${it.fileName}") }
            darkDimensions.forEach { (width, height) -> requireValidPngDimensions(width, height) }
            if (mode == ManualAlignedStackMode.DARK_SUBTRACTED_AVERAGE) {
                require(darkFrames.isNotEmpty() && shadowOffset in setOf(0, 8, 16, 32))
                require(darkFrames.all { it.category == SessionFrameCategory.DARKS_JPEG })
                if (darkCrop == null) {
                    when (val validation = validateDarkFrames(
                        darkDimensions.map { decodedPixelFrameShape(it.first, it.second) },
                        dimensions.map { decodedPixelFrameShape(it.first, it.second) }
                    )) {
                        is DarkValidationResult.Valid -> Unit
                        is DarkValidationResult.Invalid -> error(validation.message)
                    }
                } else {
                    require(source == ManualStackingSource.CROPPED)
                    require(dimensions.all { it == darkCrop.croppedWidth to darkCrop.croppedHeight })
                    require(darkDimensions.all { it == darkCrop.originalWidth to darkCrop.originalHeight })
                }
            }
            val alignment = if (alignFrames) prepareManualSequenceAlignmentSelection(
                selected, width, height, mode
            ) { current, total, message -> progress(message, current, total) } else null
            val sequencePlan = alignment?.sequencePlan
            val frameWork = manualSequenceFrameWork(selected, sequencePlan, mode)
            val coroutineContext = currentCoroutineContext()
            val budget = JpegMemoryBudget.current()
            budget.requireAllocation(ImageAllocationEstimate("manual-row-caches", maxOf(width,
                darkDimensions.maxOfOrNull { it.first } ?: width) * 256L + 4L * 1024 * 1024))
            val coverage = manualSensorDefectCoveragePlan(sequencePlan, mode, width, height) {
                coroutineContext.ensureActive()
            }
            val legacyReference = if (alignFrames && sequencePlan == null) {
                createAlignmentReference(selected.first(), width, height)
            } else null
            TemporaryPipelineFiles.create(context.cacheDir).use { temporary ->
                val store = ResultCandidateStore(temporary)
                suspend fun cache(frame: SessionFrame, label: String, targetWidth: Int, targetHeight: Int): FileBackedImage {
                    val decoded = cacheOrientedJpeg({ openFrame(frame) }, temporary, label, JpegMemoryBudget.current()) { current, total ->
                        progress("Декодирование ${frame.fileName}", current, total)
                    }
                    val prepared = TiledManualStacking.resize(decoded, targetWidth, targetHeight, store)
                    if (prepared !== decoded) temporary.deleteFile(decoded)
                    return prepared
                }
                val master = if (darkFrames.isNotEmpty()) {
                    val dw = darkDimensions.first().first
                    val dh = darkDimensions.first().second
                    val cached = darkFrames.mapIndexed { index, frame ->
                        progress("Подготовка dark ${index + 1}/${darkFrames.size}", index, darkFrames.size)
                        TiledManualFrame(cache(frame, "manual-dark-$index", dw, dh), AlignmentShift.Zero, index)
                    }
                    TiledManualStacking.integrate(cached, ManualAlignedStackMode.AVERAGE, store, budget) { current, total ->
                        progress("Создание master dark", current, total)
                    }.also { cached.forEach { frame -> temporary.deleteFile(frame.image) } }
                } else null
                val cached = frameWork.map { work ->
                    currentCoroutineContext().ensureActive()
                    val index = work.originalFrameIndex
                    val shift = if (sequencePlan != null) reportManualSequenceShift(
                        sequencePlan, index, width, height, source
                    ) { current, total, message -> progress(message, current, total) }
                    else if (legacyReference != null && index > 0) {
                        val sample = decodeMedianFrame(work.value, legacyReference.image.width, legacyReference.image.height)
                            ?: error("Не удалось прочитать кадр выравнивания")
                        try {
                            findAlignmentOrZero(legacyReference, sample, index + 1, selected.size,
                                alignmentSafe, source, outputWidth = width, outputHeight = height
                            ) { current, total, message -> progress(message, current, total) }
                        } finally { sample.recycle() }
                    } else AlignmentShift.Zero
                    progress("Подготовка JPEG ${work.compactFrameNumber}/${frameWork.size}", work.compactFrameNumber, frameWork.size)
                    TiledManualFrame(cache(work.value, "manual-light-$index", width, height), shift, index)
                }
                var output = TiledManualStacking.integrate(
                    cached, mode, store, budget, coverage, sequencePlan?.referenceFrameIndex,
                    sigma, master, darkCrop?.pixelRect, shadowOffset
                ) { current, total -> progress("Тайловая интеграция: ${mode.reportName}", current, total) }
                cached.forEach { temporary.deleteFile(it.image) }
                if (autoStretch) {
                    progress("Проявление стека", 0, 1)
                    val stretched = TiledManualStacking.stretch(output, store)
                    store.deleteTemporary(output)
                    output = stretched
                }
                var report = manualSequenceIntegrationReport(alignment, mode,
                    frameWork.map { it.originalFrameIndex }, coverage?.report)
                val now = System.currentTimeMillis()
                val type = when (mode) {
                    ManualAlignedStackMode.AVERAGE -> if (alignFrames) ProcessedOutputType.AVERAGE_ALIGNED else ProcessedOutputType.AVERAGE
                    ManualAlignedStackMode.DARK_SUBTRACTED_AVERAGE -> if (alignFrames) ProcessedOutputType.AVERAGE_DARK_ALIGNED else ProcessedOutputType.AVERAGE_DARK
                    ManualAlignedStackMode.MEDIAN -> if (alignFrames) ProcessedOutputType.MEDIAN_ALIGNED else ProcessedOutputType.MEDIAN
                    ManualAlignedStackMode.SIGMA -> if (alignFrames) ProcessedOutputType.SIGMA_ALIGNED else ProcessedOutputType.SIGMA
                }
                suspend fun save(image: FileBackedImage, outputType: ProcessedOutputType): SavedProcessedImage =
                    FileBackedImageReader(image).use { reader ->
                        LosslessProcessedImageWriter(context)
                            .write(session, reader, buildProcessedResultBaseName(outputType, now).substringBeforeLast('.') + ".png")
                    }
                progress("Сохранение PNG", 0, 1)
                val saved = save(output, type)
                var savedMaster: SavedProcessedImage? = null
                try {
                    if (master != null) {
                        try { savedMaster = save(master, ProcessedOutputType.MASTER_DARK) }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) { Log.w("AstroPhotoStack", "Master dark export failed", error) }
                    }
                    currentCoroutineContext().ensureActive()
                } catch (error: CancellationException) {
                    savedMaster?.let(::deleteSavedJpeg)
                    deleteSavedJpeg(saved)
                    throw error
                }
                report = report?.publishedSuccessfully()
                report?.let(::logManualSequenceIntegrationReport)
                val infoUpdated = runCatching {
                    when (mode) {
                        ManualAlignedStackMode.AVERAGE -> appendSessionInfo(session, saved.fileName, cached.size,
                            alignFrames, autoStretch, source, report, now)
                        ManualAlignedStackMode.DARK_SUBTRACTED_AVERAGE -> appendDarkStackSessionInfo(session, saved.fileName,
                            savedMaster?.fileName, cached.size, darkFrames.size, shadowOffset, alignFrames, autoStretch, source, report, now)
                        ManualAlignedStackMode.MEDIAN -> appendMedianSessionInfo(session, saved.fileName, cached.size,
                            alignFrames, false, autoStretch, source, report, now)
                        ManualAlignedStackMode.SIGMA -> appendSigmaSessionInfo(session, saved.fileName, cached.size,
                            sigma, alignFrames, false, autoStretch, source, report, now)
                    }
                }.isSuccess
                val warnings = buildList {
                    if (frames.size > selected.size) add("Звёздный отбор: выбрано ${selected.size}/${frames.size}")
                    if (master != null && savedMaster == null) add("Стек сохранён; отдельный master dark сохранить не удалось")
                }
                JpegStackResult(
                    saved.fileName, saved.displayPath, saved.contentUri, saved.filePath, cached.size, infoUpdated,
                    darkFrameCount = darkFrames.size, shadowOffset = shadowOffset.takeIf { master != null },
                    masterDarkFileName = savedMaster?.fileName, masterDarkDisplayPath = savedMaster?.displayPath,
                    alignmentEnabled = alignFrames, astroStretchApplied = autoStretch,
                    manualAlignmentSummary = report?.let(::manualSequenceReportSummary),
                    warnings = warnings
                )
            }
        }
    }

    suspend fun profileStack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        profile: AstroProcessingProfile,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL,
        framesRejected: Int = 0,
        onProgress: suspend (
            message: String,
            current: Int,
            total: Int
        ) -> Unit
    ): Result<JpegStackResult> = withContext(Dispatchers.IO) {
        val memoryBudget = JpegMemoryBudget.current()
        val memoryTracker = PipelineMemoryTracker(
            eventLogger = { event -> Log.i("AstroPhotoJpegMemory", event) }
        )
        val runJournal = ProcessingRunJournal(context)
        var journalRunId: String? = null
        var pipelineFiles: TemporaryPipelineFiles? = null
        var analysisCheckpointStore: ProfileAnalysisCheckpointStore? = null
        var registrationCheckpointStore: ProfileRegistrationCheckpointStore? = null
        var integrationCheckpointStore: ProfileIntegrationCheckpointStore? = null
        var currentStage = "Подготовка"
        val stackResult = runCatching {
            require(profile != AstroProcessingProfile.NORMAL) {
                "Обычный режим доступен в ручном JPEG stacking"
            }
            require(frames.size >= 2) {
                "Недостаточно JPEG кадров для профильной обработки"
            }
            require(frames.size >= profile.minimumFrames) {
                "Для профиля ${profile.title} рекомендуется минимум ${profile.minimumFrames} кадров"
            }
            require(frames.all { it.category == SessionFrameCategory.LIGHTS_JPEG }) {
                "Профили обработки используют только Lights/JPEG"
            }
            val journalRecord = runJournal.start(
                sessionFolder = session.folderName,
                preset = profile.name,
                runtimeMaxHeapBytes = memoryBudget.snapshot.maxHeapBytes,
                heapUsedAtStartBytes = memoryBudget.snapshot.usedHeapBytes,
                safeWorkingBudgetBytes = memoryBudget.safeWorkingBudgetBytes
            )
            journalRunId = journalRecord.runId
            val staleRunsRecovered = TemporaryPipelineFiles.cleanupStale(context.cacheDir)
            val temporaryFiles = TemporaryPipelineFiles.create(context.cacheDir)
            pipelineFiles = temporaryFiles
            val candidateStore = ResultCandidateStore(temporaryFiles)
            Log.i(
                "AstroPhotoJpegMemory",
                "run=${temporaryFiles.runId.take(8)} maxHeap=${memoryBudget.snapshot.maxHeapBytes} " +
                    "usedHeap=${memoryBudget.snapshot.usedHeapBytes} " +
                    "availableHeap=${memoryBudget.snapshot.availableHeapBytes} " +
                    "safeBudget=${memoryBudget.safeWorkingBudgetBytes} " +
                    "staleRunsRecovered=$staleRunsRecovered"
            )
            currentStage = "Выбор рецепта"
            val recipe = profileRecipe(profile, minOf(frames.size, MAX_PROFILE_FRAMES))
            val pipelineTiming = PipelineTimingCollector()
            Log.i(
                PROFILE_REGISTRATION_TAG,
                "selectedPreset=${profile.name} inputFrameCount=${frames.size}"
            )
            val preparedAnalysis = ProfileFrameAnalysisCoordinator(
                context = context,
                readDimensions = ::readDimensions,
                decodeFrame = ::decodeMedianFrame
            ).prepare(
                sessionFolder = session.folderName,
                frames = frames,
                profile = profile,
                source = source,
                pipelineTiming = pipelineTiming,
                runJournal = runJournal,
                journalRunId = journalRunId,
                onStage = { currentStage = it },
                onProgress = onProgress
            )
            val analysisFrames = preparedAnalysis.frames
            val captureIndexByFrameKey = preparedAnalysis.captureIndexByFrameKey
            val dimensionsByFrameKey = preparedAnalysis.dimensionsByFrameKey
            val commonWidth = preparedAnalysis.commonWidth
            val commonHeight = preparedAnalysis.commonHeight
            val analysisWidth = preparedAnalysis.analysisWidth
            val analysisHeight = preparedAnalysis.analysisHeight
            val rawAnalyzedFrames = preparedAnalysis.analyzedFrames
            analysisCheckpointStore = preparedAnalysis.checkpointStore
            currentStage = "Анализ неподвижных артефактов"
            val staticArtifactStarted = System.nanoTime()
            val staticArtifactAnalyzer = StaticArtifactAnalyzer()
            val staticArtifactMask = staticArtifactAnalyzer.analyze(
                rawAnalyzedFrames.map { analyzed ->
                    ArtifactFrameObservation(analyzed.frame.key, analyzed.analysis.stars)
                },
                analysisWidth,
                analysisHeight
            )
            val analyzedFrames = rawAnalyzedFrames.map { analyzed ->
                analyzed.copy(
                    analysis = staticArtifactAnalyzer.excludeFrom(
                        analyzed.analysis,
                        staticArtifactMask
                    )
                )
            }
            pipelineTiming.record(
                "static_artifact_analysis",
                (System.nanoTime() - staticArtifactStarted) / 1_000_000L
            )
            Log.i(
                PROFILE_REGISTRATION_TAG,
                "staticArtifactCandidates=${staticArtifactMask.regions.size} " +
                    "staticHotPixels=${staticArtifactMask.staticHotPixelCandidates.size} " +
                    "staticReflections=${staticArtifactMask.staticReflectionCandidates.size} " +
                    "staticArtifactMaskRatio=${formatMetric(staticArtifactMask.maskRatio)} " +
                    "staticArtifactConfidence=${formatMetric(staticArtifactMask.confidence)}"
            )
            val referenceSelectionStarted = System.nanoTime()
            val initialSelection = ProfileFrameSelectionCoordinator.prepare(
                analyzedFrames = analyzedFrames,
                analysisFrames = analysisFrames,
                captureIndexByFrameKey = captureIndexByFrameKey,
                dimensionsByFrameKey = dimensionsByFrameKey,
                maxFrames = MAX_PROFILE_FRAMES
            )
            pipelineTiming.record(
                "reference_selection",
                (System.nanoTime() - referenceSelectionStarted) / 1_000_000L
            )
            // The checkpoint fingerprints the deterministic initial selection. Its diagnostics
            // persist the actual reference, including recovery, and are promoted before full-res work.
            registrationCheckpointStore = ProfileRegistrationCheckpointStore.open(
                context = context,
                sessionFolder = session.folderName,
                profile = profile,
                frames = frames,
                selectedFrameKeys = initialSelection.selectedFrames.map { it.key },
                analysisWidth = initialSelection.selectedReference.analysis.width,
                analysisHeight = initialSelection.selectedReference.analysis.height
            )
            currentStage = "Первичное выравнивание"
            val registrationStarted = System.nanoTime()
            withContext(Dispatchers.Main.immediate) {
                onProgress("Выравнивание по звёздам", 0, initialSelection.selectedFrames.size)
            }
            var analysisRegistration = runProfileRegistrationWithCheckpoint(
                store = checkNotNull(registrationCheckpointStore),
                selectedFrames = initialSelection.selectedFrames,
                analysesByFrameKey = initialSelection.analysesByFrameKey,
                captureIndexByFrameKey = captureIndexByFrameKey,
                referenceFrameId = initialSelection.selectedReference.frame.key,
                imageWidth = initialSelection.selectedReference.analysis.width,
                imageHeight = initialSelection.selectedReference.analysis.height,
                minimumFrames = profile.minimumFrames,
                onProgress = onProgress
            )
            val actualReferenceKey = initialSelection.selectedFrames.single {
                captureIndexByFrameKey.getValue(it.key) == analysisRegistration.referenceCaptureIndex
            }.key
            var selection = ProfileFrameSelectionCoordinator.withReference(
                initialSelection, actualReferenceKey, dimensionsByFrameKey
            )
            var selectedReference = selection.selectedReference
            var selectedFrames = selection.selectedFrames
            var targetWidth = selection.targetWidth
            var targetHeight = selection.targetHeight
            Log.i(
                PROFILE_REGISTRATION_TAG,
                "selectedPreset=${profile.name} inputFrameCount=${frames.size} " +
                    "selectedReference=${selectedReference.frame.fileName} " +
                    "stars=${selectedReference.analysis.reliableStarCount} " +
                    "starWidth=${formatMetric(selectedReference.analysis.medianStarWidth)} " +
                    "ellipticity=${formatMetric(selectedReference.analysis.medianStarEllipticity)} " +
                    "noise=${formatMetric(selectedReference.analysis.backgroundNoise)} " +
                    "clipping=${formatMetric(selectedReference.analysis.clippingPercent)} " +
                    "score=${formatMetric(selection.referenceScore)}"
            )
            Log.i(
                PROFILE_REGISTRATION_TAG,
                "frame=${selectedReference.frame.fileName} " +
                    "detectedStars=${selectedReference.analysis.reliableStarCount} " +
                    "matchedStars=${selectedReference.analysis.reliableStarCount} " +
                    "inlierStars=${selectedReference.analysis.reliableStarCount} " +
                    "dx=0.0000 dy=0.0000 rotation=0.0000 scale=1.0000 residual=0.0000 " +
                    "confidence=1.0000 accepted=true rejectionReason=reference"
            )
            val warnings = mutableListOf<String>()
            if (actualReferenceKey != initialSelection.selectedReference.frame.key) {
                warnings += "Опорный кадр заменён после неудачного выравнивания: " +
                    selectedReference.frame.fileName
                Log.i(PROFILE_REGISTRATION_TAG,
                    "referenceRecovery=${initialSelection.selectedReference.frame.fileName}->${selectedReference.frame.fileName} " +
                        "provisionalAccepted=${analysisRegistration.registrations.values.count { it.isReliable }}")
            }
            if (selection.droppedCount > 0) {
                warnings += "Для полноразмерной обработки выбраны лучшие " +
                    "${selectedFrames.size} из ${frames.size} кадров"
            }
            var alignmentApplied = 0
            var alignmentRejected = 0
            var referenceStars = 0
            var finalStars = 0
            var sanityStatus = "passed"
            var fallback = "none"
            var fallbackReason = ""
            var transformSequenceScore = 0f
            var sequenceSmoothnessScore = 0f
            var sequencePriorAgreementScore = 0f
            var sequenceDiagnostics = analysisRegistration
            val registrationReports = mutableListOf<FrameRegistrationReport>()

            try {
                var provisionalRegistration = prepareProvisionalProfileRegistration(
                    selection,
                    analysisRegistration,
                    captureIndexByFrameKey
                )
                var scaleX = provisionalRegistration.scaleX
                var scaleY = provisionalRegistration.scaleY
                var allRegistrationsByKey = provisionalRegistration.allRegistrationsByKey
                    .toMutableMap()
                val acceptedProfileFrames = provisionalRegistration.acceptedFrames.toMutableList()
                transformSequenceScore = provisionalRegistration.sequenceScore
                sequenceSmoothnessScore = provisionalRegistration.sequenceSmoothnessScore
                sequencePriorAgreementScore = provisionalRegistration.sequencePriorAgreementScore
                pipelineTiming.record(
                    "registration",
                    (System.nanoTime() - registrationStarted) / 1_000_000L
                )
                currentStage = "Уточнение выравнивания в полном разрешении"
                val refinementStarted = System.nanoTime()
                val fullResolutionReferencePreparation =
                    prepareFullResolutionWithReferenceRecovery(
                        checkpointStore = registrationCheckpointStore,
                        initialSelection = initialSelection,
                        startingSelection = selection,
                        startingDiagnostics = analysisRegistration,
                        startingProvisional = provisionalRegistration,
                        dimensionsByFrameKey = dimensionsByFrameKey,
                        captureIndexByFrameKey = captureIndexByFrameKey,
                        profile = profile,
                        userApprovedInsufficientFrames =
                            userApprovedInsufficientProfileFrames.get(),
                        temporaryFiles = temporaryFiles,
                        memoryBudget = memoryBudget,
                        memoryTracker = memoryTracker,
                        onProgress = onProgress
                    )
                selection = fullResolutionReferencePreparation.selection
                selectedReference = selection.selectedReference
                selectedFrames = selection.selectedFrames
                targetWidth = selection.targetWidth
                targetHeight = selection.targetHeight
                analysisRegistration = fullResolutionReferencePreparation.diagnostics
                sequenceDiagnostics = analysisRegistration
                provisionalRegistration = fullResolutionReferencePreparation.provisional
                scaleX = provisionalRegistration.scaleX
                scaleY = provisionalRegistration.scaleY
                allRegistrationsByKey = provisionalRegistration.allRegistrationsByKey.toMutableMap()
                acceptedProfileFrames.clear()
                acceptedProfileFrames += provisionalRegistration.acceptedFrames
                transformSequenceScore = provisionalRegistration.sequenceScore
                sequenceSmoothnessScore = provisionalRegistration.sequenceSmoothnessScore
                sequencePriorAgreementScore = provisionalRegistration.sequencePriorAgreementScore
                val fullResolutionPreparation = fullResolutionReferencePreparation.fullResolution
                warnings += fullResolutionReferencePreparation.warnings
                referenceStars = selectedReference.analysis.reliableStarCount
                if (referenceStars < 4) {
                    warnings += "Звёзд найдено мало: $referenceStars"
                }
                logSequenceIdentityVerification(analysisRegistration)
                val provisionalAcceptedFrames = provisionalRegistration.acceptedFrames.size
                val requiredCacheBytes = targetWidth.toLong() * targetHeight *
                    Int.SIZE_BYTES * provisionalAcceptedFrames
                val initialFullResolutionSkyMask = scaleSkyMask(
                    selectedReference.skyMask.mask,
                    targetWidth,
                    targetHeight
                )
                registrationReports.clear()
                selectedFrames.forEach { frame ->
                    val registration = checkNotNull(allRegistrationsByKey[frame.key])
                    registrationReports += registration.toReport(frame.fileName)
                    logProfileRegistration(frame.fileName, registration)
                    logAnalysisRegistration(
                        frameName = frame.fileName,
                        frameKey = frame.key,
                        captureIndex = captureIndexByFrameKey.getValue(frame.key),
                        registration = registration,
                        diagnostics = analysisRegistration,
                        scaleX = scaleX,
                        scaleY = scaleY
                    )
                    if (!registration.isReliable) {
                        warnings += "Кадр ${frame.fileName} отклонён: " +
                            (registration.rejectionReason ?: "registration failed")
                    }
                }
                Log.i(
                    PROFILE_REGISTRATION_TAG,
                    "selectedPreset=${profile.name} provisionalAccepted=$provisionalAcceptedFrames " +
                        "provisionalRejected=${selectedFrames.size - provisionalAcceptedFrames}"
                )
                val sensorMaskConstructionStarted = System.nanoTime()
                val automaticSensorMaskResult = buildAutomaticSensorDefectMask(
                    observations = rawAnalyzedFrames.map { it.persistentSensorObservation },
                    outputWidth = targetWidth,
                    outputHeight = targetHeight,
                    predictedTransform = { originalCaptureIndex ->
                        analysisRegistration.model.predictedTransform(originalCaptureIndex)
                    }
                )
                val automaticSensorMask = automaticSensorMaskResult.mask
                val sensorMaskConstructionDurationMillis =
                    (System.nanoTime() - sensorMaskConstructionStarted) / 1_000_000L
                pipelineTiming.record(
                    "sensor_defect_mask_construction",
                    sensorMaskConstructionDurationMillis
                )
                Log.i(
                    PROFILE_REGISTRATION_TAG,
                    "sensorDefectMaskEnabled=${automaticSensorMask.enabled} " +
                        "sensorDefectRegions=${automaticSensorMask.regions.size} " +
                        "sensorDefectSourcePixels=${automaticSensorMask.maskedPixelCount} " +
                        "sensorDefectSourceFraction=" +
                        formatMetric(automaticSensorMask.maskedSourceFraction) +
                        " sensorDefectMaskReason=" + automaticSensorMask.rejectionReason.orEmpty() +
                        " sensorDefectOriginalIndices=" +
                        automaticSensorMaskResult.originalFrameIndices.joinToString()
                )
                val refinementResultsByKey = fullResolutionPreparation.refinementResultsByKey
                val centroidResultsByKey = fullResolutionPreparation.centroidResultsByKey
                val samplingFidelity = fullResolutionPreparation.samplingFidelity
                fullResolutionPreparation.finalRegistrationsByKey.forEach { (key, registration) ->
                    allRegistrationsByKey[key] = registration
                }
                acceptedProfileFrames.clear()
                acceptedProfileFrames += fullResolutionPreparation.acceptedFrames
                val cachedFrames = fullResolutionPreparation.cachedFrames
                warnings += fullResolutionPreparation.warnings
                val acceptedFrames = acceptedProfileFrames.size
                val fullResolutionRefinementRejectedCount =
                    (provisionalAcceptedFrames - acceptedFrames).coerceAtLeast(0)
                alignmentApplied = (acceptedFrames - 1).coerceAtLeast(0)
                alignmentRejected = (selectedFrames.size - acceptedFrames).coerceAtLeast(0)
                registrationReports.clear()
                selectedFrames.forEach { frame ->
                    registrationReports += allRegistrationsByKey.getValue(frame.key).toReport(frame.fileName)
                }
                // Do not blame full-resolution refinement when the preliminary registration
                // already had too few frames. Keep both counts visible in the failure message.
                if (acceptedFrames < profile.minimumFrames) {
                    currentStage = "Выравнивание по звёздам: предварительно $provisionalAcceptedFrames/" +
                        "${selectedFrames.size}, подтверждено в полном разрешении $acceptedFrames/${selectedFrames.size}"
                }
                requireMinimumRegisteredFrames(
                    acceptedFrames,
                    selectedFrames.size,
                    profile,
                    userApprovedInsufficientProfileFrames.get()
                )
                recordUserApprovedFrameWarning(
                    warnings,
                    userApprovedInsufficientProfileFrames.get(),
                    acceptedFrames,
                    selectedFrames.size,
                    profile.minimumFrames
                )
                pipelineTiming.record(
                    "full_resolution_refinement",
                    (System.nanoTime() - refinementStarted) / 1_000_000L
                )
                Log.i(
                    PROFILE_REGISTRATION_TAG,
                    "selectedPreset=${profile.name} provisionalAccepted=$provisionalAcceptedFrames " +
                        "finalAccepted=$acceptedFrames refinementRejected=$fullResolutionRefinementRejectedCount " +
                        "samplingKernel=${samplingFidelity.kernel} " +
                        "samplingProductionContrast=${formatMetric(samplingFidelity.productionContrastRatio)}"
                )

                currentStage = "Расчёт весов кадров"
                val weightCalculationStarted = System.nanoTime()
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Расчёт весов кадров", 0, acceptedFrames)
                }
                val frameWeights = FrameWeightCalculator().calculate(
                    acceptedProfileFrames.mapIndexed { index, accepted ->
                        FrameWeightInput(
                            accepted.analysis,
                            accepted.registration,
                            isReference = index == 0
                        )
                    }
                )
                val weightsById = frameWeights.associateBy { it.frameId }
                acceptedProfileFrames.forEach { accepted ->
                    val weight = checkNotNull(weightsById[accepted.analysis.id])
                    Log.i(
                        PROFILE_REGISTRATION_TAG,
                        "frame=${accepted.frame.fileName} registrationWeight=${formatMetric(weight.registrationWeight)} " +
                            "sharpnessWeight=${formatMetric(weight.sharpnessWeight)} " +
                            "trailWeight=${formatMetric(weight.trailWeight)} " +
                            "noiseWeight=${formatMetric(weight.noiseWeight)} " +
                            "exposureWeight=${formatMetric(weight.exposureWeight)} " +
                            "rawWeight=${formatMetric(weight.rawWeight)} " +
                            "normalizedWeight=${formatMetric(weight.normalizedWeight)}"
                    )
                }
                pipelineTiming.record(
                    "weight_calculation",
                    (System.nanoTime() - weightCalculationStarted) / 1_000_000L
                )
                currentStage = "Сложение изображения в полном разрешении"
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Сложение изображения в полном разрешении", 0, 1)
                }
                journalRunId?.let { runJournal.update(it, "registration_completed") }
                val integrationMaskEstimate = ImageAllocationEstimate.booleanMask(
                    targetWidth,
                    targetHeight,
                    "integration-sky-mask"
                )
                memoryBudget.requireAllocation(integrationMaskEstimate)
                memoryTracker.recordBoundary("integration-sky-mask", integrationMaskEstimate.bytes, 0)
                require(memoryBudget.safeWorkingBudgetBytes >= MIN_PROFILE_WORKING_MEMORY_BYTES) {
                    "Недостаточно безопасной рабочей памяти для JPEG-интеграции"
                }
                val maximumWorkingMemory = minOf(
                    memoryBudget.safeWorkingBudgetBytes,
                    MAX_PROFILE_WORKING_MEMORY_BYTES
                )
                val integrationFrames = cachedFrames.map { (accepted, cached) ->
                    check(
                        cached.referenceToSourceTransform ==
                            accepted.registration.referenceToSourceTransform()
                    ) { "Cached transform metadata does not match refined registration" }
                    WeightedIntegrationFrame(
                        id = accepted.analysis.id,
                        source = cached,
                        transform = accepted.registration,
                        normalizedWeight = checkNotNull(
                            weightsById[accepted.analysis.id]
                        ).normalizedWeight
                    )
                }
                integrationCheckpointStore = ProfileIntegrationCheckpointStore.open(
                    context = context,
                    sessionFolder = session.folderName,
                    profile = profile,
                    width = targetWidth,
                    height = targetHeight,
                    frames = acceptedProfileFrames.map { accepted ->
                        IntegrationCheckpointFrameSignature(
                            frameId = accepted.frame.key,
                            fileName = accepted.frame.fileName,
                            sizeBytes = accepted.frame.sizeBytes,
                            createdAtMillis = accepted.frame.createdAtMillis,
                            registration = accepted.registration,
                            normalizedWeight = checkNotNull(
                                weightsById[accepted.analysis.id]
                            ).normalizedWeight
                        )
                    },
                    sensorDefectMask = automaticSensorMask,
                    integrationSkyMask = initialFullResolutionSkyMask
                )
                val integrationRun = runAutomaticSensorMaskedIntegration(
                    checkpointStore = checkNotNull(integrationCheckpointStore),
                    temporaryFiles = temporaryFiles,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    frames = integrationFrames,
                    maximumWorkingMemory = maximumWorkingMemory,
                    sensorDefectMask = automaticSensorMask,
                    sensorDefectOriginalFrameIndices =
                        automaticSensorMaskResult.originalFrameIndices,
                    sensorMaskConstructionDurationMillis =
                        sensorMaskConstructionDurationMillis,
                    integrationSkyMask = initialFullResolutionSkyMask,
                    candidateStore = candidateStore,
                    onProgress = onProgress
                )
                val integrationDiagnostics = integrationRun.diagnostics
                val stackedSky = integrationRun.stackedSky
                val validCoverage = integrationRun.validCoverage
                val sensorDefectAffectedOutput =
                    integrationRun.sensorDefectAffectedOutput
                val sensorDefectFilteringReport = integrationRun.sensorDefectFiltering
                val activeAutomaticSensorMask = automaticSensorMask.takeIf {
                    sensorDefectFilteringReport.filteringAppliedToFinalResult
                }
                cachedFrames.forEach { (_, cached) -> cached.file.delete() }
                pipelineTiming.record("integration", integrationRun.totalDurationMillis)
                memoryTracker.recordBoundary(
                    "integration",
                    integrationDiagnostics.estimatedPeakWorkingMemoryBytes,
                    0
                )
                memoryTracker.recordTile(
                    "integration",
                    integrationDiagnostics.tileWidth,
                    integrationDiagnostics.tileHeight
                )
                logAutomaticIntegration(
                    profile,
                    selectedReference.frame.fileName,
                    commonWidth,
                    commonHeight,
                    alignmentRejected,
                    integrationDiagnostics,
                    sensorDefectFilteringReport
                )
                journalRunId?.let { runJournal.update(it, "integration_completed") }
                currentStage = "Уточнение маски неба"
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Уточнение маски неба", 0, 3)
                }
                val skyMaskingStarted = System.nanoTime()
                val fullResolutionStars = scaleV2Stars(
                    selectedReference.analysis.stars,
                    selectedReference.analysis.width,
                    selectedReference.analysis.height,
                    targetWidth,
                    targetHeight
                )
                val maskStage = buildFileBackedReferenceAndMask(
                    selectedReference = selectedReference,
                    initialSkyMask = initialFullResolutionSkyMask,
                    fullResolutionStars = fullResolutionStars,
                    registrationConfidence = acceptedProfileFrames
                        .map { it.registration.confidence }
                        .average()
                        .toFloat(),
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    candidateStore = candidateStore,
                    memoryBudget = memoryBudget,
                    memoryTracker = memoryTracker
                )
                val starPreservation = preserveAutomaticReferenceStars(
                    stackedSky = stackedSky,
                    reference = maskStage.referenceCandidate,
                    stars = fullResolutionStars,
                    store = candidateStore,
                    sensorDefectMask = activeAutomaticSensorMask,
                    sensorDefectAffectedOutput = sensorDefectAffectedOutput
                )
                val starPreservedStackedSky = starPreservation.image
                candidateStore.deleteTemporary(stackedSky)
                pipelineTiming.record(
                    "sky_masking",
                    (System.nanoTime() - skyMaskingStarted) / 1_000_000L
                )
                journalRunId?.let { runJournal.update(it, "reference_and_mask_completed") }

                currentStage = "Объединение неба и переднего плана"
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Объединение неба и переднего плана", 2, 3)
                }
                val composite = composeCleanCandidate(
                    stackedSky = starPreservedStackedSky,
                    reference = maskStage.referenceCandidate,
                    featheredSkyMask = maskStage.featheredSkyMask,
                    validCoverage = validCoverage,
                    sensorDefectAffectedOutput = sensorDefectAffectedOutput,
                    sensorDefectMask = activeAutomaticSensorMask,
                    candidateStore = candidateStore,
                    memoryBudget = memoryBudget,
                    memoryTracker = memoryTracker
                )
                val cleanStackHandle = candidateStore.register(
                    ResultCandidateType.CLEAN_STACK,
                    composite.image
                )
                val effectiveSkyAlpha = composite.effectiveSkyAlpha
                candidateStore.deleteTemporary(maskStage.featheredSkyMask)
                pipelineTiming.record(
                    "foreground_composition",
                    composite.diagnostics.compositionDurationMillis
                )
                require(composite.diagnostics.maximumForegroundChannelDifference <= 1) {
                    "Композиция изменила защищённый foreground"
                }
                require(
                    composite.diagnostics.foregroundSharpnessAfter + FOREGROUND_SHARPNESS_TOLERANCE >=
                        composite.diagnostics.foregroundSharpnessBefore
                ) {
                    "Резкость защищённого foreground снизилась"
                }
                journalRunId?.let { runJournal.update(it, "clean_stack_composed") }

                currentStage = "Проверка чистого стека"
                val qualityAnalysisStarted = System.nanoTime()
                val fullStaticArtifactMask = staticArtifactMask.scaledTo(targetWidth, targetHeight)
                val qualityAnalyzer = FileBackedResultQualityAnalyzer(
                    staticArtifactMask = fullStaticArtifactMask
                )
                val referenceCandidate = StoredResultCandidate(
                    ResultCandidateType.REFERENCE,
                    maskStage.referenceCandidate,
                    qualityAnalyzer.analyze(
                        maskStage.referenceCandidate,
                        maskStage.referenceCandidate,
                        effectiveSkyAlpha
                    )
                )
                val qualityGate = AstroResultQualityGate()
                val referenceDecision = qualityGate.evaluateReference(referenceCandidate)
                require(referenceDecision.accepted) {
                    "Selected JPEG reference failed structural validation: " +
                        referenceDecision.hardFailureReasons.joinToString("|")
                }
                val cleanStackCandidate = StoredResultCandidate(
                    ResultCandidateType.CLEAN_STACK,
                    cleanStackHandle,
                    qualityAnalyzer.analyze(
                        cleanStackHandle,
                        maskStage.referenceCandidate,
                        effectiveSkyAlpha
                    )
                )
                val beforeMetrics = profileMetricsFromQuality(cleanStackCandidate.metrics)
                logProfileStage(profile, source, "skyForegroundComposite", beforeMetrics)
                val retentionValidation = ReferenceStarRetentionValidator().validate(
                    maskStage.referenceCandidate,
                    cleanStackHandle,
                    fullResolutionStars
                )
                val coverageValidation = CoverageUniformityValidator().validate(
                    validCoverage,
                    effectiveSkyAlpha
                )
                val artifactCheckContext = currentCoroutineContext()
                val lineArtifactValidation = LineArtifactDetector().compare(
                    maskStage.referenceCandidate,
                    cleanStackHandle,
                    effectiveSkyAlpha
                ).combinedWith(
                    LineArtifactDetector().compareStarNeighborhoods(
                        maskStage.referenceCandidate, cleanStackHandle, fullResolutionStars,
                        cancellationCheck = { artifactCheckContext.ensureActive() }
                    )
                )
                val cleanStackEvidence = CleanStackValidationEvidence(
                    referenceStarRetention = retentionValidation,
                    coverageUniformity = coverageValidation,
                    lineArtifacts = lineArtifactValidation,
                    transformSequenceScore = transformSequenceScore,
                    acceptedFrameCount = acceptedFrames,
                    transformSequenceValid = acceptedFrames >= 2 && transformSequenceScore >= 0.50f
                )
                val cleanStackDecision = qualityGate.evaluateCleanStack(
                    referenceCandidate,
                    cleanStackCandidate,
                    profile,
                    cleanStackEvidence
                )
                Log.i(
                    "AstroPhotoJpegCleanStack",
                    "accepted=${cleanStackDecision.accepted} " +
                        "retention=${formatMetric(retentionValidation.metrics.retentionRatio)} " +
                        "coverageScore=${formatMetric(coverageValidation.metrics.uniformityScore)} " +
                        "lineArtifactScore=${formatMetric(lineArtifactValidation.metrics.lineArtifactScore)} " +
                        "hardFailures=${cleanStackDecision.hardFailureReasons.joinToString("|")}"
                )
                journalRunId?.let { runJournal.update(it, "clean_stack_validated") }

                val stage4Executed = CleanStackExecutionPolicy()
                    .shouldExecuteStage4(cleanStackDecision)
                val processedCandidate: StoredResultCandidate?
                val processedDecision: QualityGateDecision
                var adaptiveDiagnostics: AdaptiveProcessingDiagnostics? = null
                if (stage4Executed) {
                    currentStage = "Адаптивная обработка с временными файлами"
                    val alignedStackStars = qualityAnalyzer.detectStars(
                        starPreservedStackedSky,
                        effectiveSkyAlpha
                    )
                    val adaptiveResult = runProfilePostProcessing(
                        checkpointStore = integrationCheckpointStore,
                        temporaryFiles = temporaryFiles,
                        stackedSky = starPreservedStackedSky,
                        referenceForeground = maskStage.referenceCandidate,
                        effectiveSkyAlpha = effectiveSkyAlpha,
                        profile = profile,
                        frameCount = acceptedFrames,
                        alignedStackStars = alignedStackStars,
                        store = candidateStore,
                        memoryBudget = memoryBudget,
                        memoryTracker = memoryTracker,
                        sensorDefectAffectedOutput = sensorDefectAffectedOutput,
                        onProgress = { message, current, total ->
                            currentStage = message
                            withContext(Dispatchers.Main.immediate) {
                                onProgress(message, current, total)
                            }
                        }
                    )
                    pipelineTiming.recordAll(adaptiveResult.diagnostics.stageDurationsMillis)
                    adaptiveDiagnostics = adaptiveResult.diagnostics
                    processedCandidate = StoredResultCandidate(
                        ResultCandidateType.PROCESSED,
                        adaptiveResult.image,
                        qualityAnalyzer.analyze(
                            adaptiveResult.image,
                            maskStage.referenceCandidate,
                            effectiveSkyAlpha
                        )
                    )
                    processedDecision = LineArtifactDetector().compareStarNeighborhoods(
                        maskStage.referenceCandidate, adaptiveResult.image, fullResolutionStars,
                        cancellationCheck = { artifactCheckContext.ensureActive() }
                    ).constrain(qualityGate.evaluateProcessed(
                        referenceCandidate,
                        cleanStackCandidate,
                        processedCandidate,
                        profile,
                        acceptedFrames
                    ))
                    Log.i(
                        ADAPTIVE_PROCESSING_TAG,
                        "preset=${profile.name} fileBacked=true " +
                            "skyMedianBefore=${formatMetric(adaptiveResult.diagnostics.before.luminanceMedian)} " +
                            "skyMedianAfter=${formatMetric(adaptiveResult.diagnostics.after.luminanceMedian)} " +
                            "foregroundDifferenceOutsideMask=" +
                            "${adaptiveResult.diagnostics.foregroundDifferenceOutsideMask}"
                    )
                } else {
                    pipelineTiming.record("stage4_skipped", 0L)
                    processedCandidate = null
                    processedDecision = QualityGateDecision(
                        accepted = false,
                        score = 0f,
                        hardFailureReasons = listOf("stage4_skipped_clean_stack_invalid"),
                        warningReasons = cleanStackDecision.warningReasons,
                        metrics = referenceCandidate.metrics
                    )
                }
                sensorDefectAffectedOutput?.let(candidateStore::deleteTemporary)
                journalRunId?.let {
                    runJournal.update(
                        it,
                        if (stage4Executed) "stage4_completed" else "stage4_skipped"
                    )
                }
                currentStage = "Итоговая проверка качества"
                val finalSelection = userApprovedProfileSelection(
                    ResultSelectionPolicy().select(
                        referenceCandidate,
                        cleanStackCandidate,
                        processedCandidate,
                        processedDecision,
                        cleanStackDecision
                    ),
                    cleanStackCandidate,
                    userApprovedInsufficientProfileFrames.get() && lineArtifactValidation.accepted
                )
                val finalizedSensorDefectFilteringReport = candidateMaskLineage(
                    filtering = sensorDefectFilteringReport,
                    maskDiagnostics = automaticSensorMaskResult.diagnostics,
                    selected = finalSelection.selected,
                    reference = referenceCandidate,
                    clean = cleanStackCandidate,
                    processed = processedCandidate,
                    starPreservation = starPreservation,
                    composition = composite.diagnostics,
                    stars = fullResolutionStars,
                    cleanRetention = retentionValidation,
                    effectiveSkyAlpha = effectiveSkyAlpha,
                    integrationFrames = integrationFrames,
                    sensorDefectMask = activeAutomaticSensorMask
                )
                val outputPlan = jpegProfileOutputPlan(
                    profile,
                    finalSelection.selected.type,
                    acceptedFrames,
                    selectedFrames.size,
                    finalSelection.fallbackReason
                )
                val processingOutcome = outputPlan.outcome
                pipelineTiming.record(
                    "quality_analysis",
                    (System.nanoTime() - qualityAnalysisStarted) / 1_000_000L
                )
                val selectedProfileMetrics = profileMetricsFromQuality(finalSelection.selected.metrics)
                finalStars = finalSelection.selected.metrics.reliableStarCount
                sanityStatus = if (processedDecision.accepted) "passed" else "fallback"
                fallback = finalSelection.internalFallbackLabel ?: "none"
                fallbackReason = finalSelection.fallbackReason.orEmpty()
                if (finalSelection.fallbackUsed) {
                    warnings += qualityFallbackWarning(
                        finalSelection.selected.type,
                        fallbackReason
                    )
                }
                logProfileStage(profile, source, "finalQualityGate", selectedProfileMetrics)
                journalRunId?.let { runJournal.update(it, "final_candidate_selected") }

                if (
                    profile == AstroProcessingProfile.MAX_STARS ||
                    profile == AstroProcessingProfile.EXPERIMENTAL_STARS
                ) {
                    warnings += "Режим может усилить шум и артефакты"
                }
                if (alignmentRejected > 0) {
                    warnings += "Кадров отклонено из-за ненадёжной регистрации: $alignmentRejected"
                }
                logAutomaticComposition(
                    profile,
                    selectedReference.frame.fileName,
                    maskStage,
                    composite
                )

                val now = System.currentTimeMillis()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
                val requestedFileName = "${outputPlan.filePrefix}_$timestamp.png"
                val frameNameById = acceptedProfileFrames.associate {
                    it.analysis.id to it.frame.fileName
                }
                val frameNameByKey = selectedFrames.associate { it.key to it.fileName }
                val registrationDiagnostics = checkNotNull(sequenceDiagnostics)
                val selectedHypotheses = registrationDiagnostics.model.acceptedFrameHypotheses
                val finalVerificationAggregation = VerificationMetricsAggregator.aggregate(
                    perFrame = registrationDiagnostics.verification.perFrame,
                    acceptedFrameIds = allRegistrationsByKey.filterValues { it.isReliable }.keys,
                    rejectedFrameIds = allRegistrationsByKey.filterValues { !it.isReliable }.keys,
                    referenceFrameId = selectedReference.frame.key
                )
                val acceptedVerificationMean = finalVerificationAggregation.acceptedMean
                var processingReport = ProcessingReport(
                    timestampMillis = now,
                    presetId = profile.name,
                    presetDisplayName = profile.title,
                    inputFrameCount = frames.size + framesRejected,
                    eligibleFrameCount = selectedFrames.size,
                    acceptedFrameCount = acceptedFrames,
                    rejectedFrameCount = framesRejected +
                        (frames.size - selectedFrames.size) + alignmentRejected,
                    selectedReference = selectedReference.frame.fileName,
                    skyMaskConfidence = maskStage.confidence,
                    skyRatio = alphaCoverageRatio(effectiveSkyAlpha),
                    foregroundRatio = maskStage.protectedForegroundRatio,
                    registrations = registrationReports.toList(),
                    frameWeights = frameWeightReports(frameWeights, frameNameById),
                    integration = processingIntegrationReport(
                        commonWidth,
                        commonHeight,
                        integrationDiagnostics,
                        requiredCacheBytes
                    ),
                    sensorDefectFiltering = finalizedSensorDefectFilteringReport,
                    stage4Parameters = ExistingPresetParameterMapper.parametersFor(
                        profile,
                        acceptedFrames
                    ),
                    referenceMetrics = referenceCandidate.metrics,
                    cleanStackMetrics = cleanStackCandidate.metrics,
                    processedMetrics = processedCandidate?.metrics ?: referenceCandidate.metrics,
                    cleanStackDecision = cleanStackDecision,
                    processedDecision = processedDecision,
                    selectedCandidateType = finalSelection.selected.type.name,
                    fallbackUsed = finalSelection.fallbackUsed,
                    fallbackReason = finalSelection.fallbackReason,
                    internalFallbackLabel = finalSelection.internalFallbackLabel,
                    warnings = (
                        warnings + cleanStackDecision.warningReasons +
                            processedDecision.warningReasons
                        ).distinct(),
                    outputPngDisplayName = requestedFileName,
                    stageDurationsMillis = pipelineTiming.snapshot(),
                    stage4Executed = stage4Executed,
                    processingOutcome = processingOutcome.name,
                    actualSkyBrightnessGain = adaptiveDiagnostics?.let { diagnostics ->
                        diagnostics.after.luminanceMedian /
                            diagnostics.before.luminanceMedian.coerceAtLeast(0.000001f)
                    } ?: 1f,
                    actualStarContrastGain = processedCandidate?.metrics?.let { metrics ->
                        metrics.medianStarLocalContrast /
                            cleanStackCandidate.metrics.medianStarLocalContrast.coerceAtLeast(0.000001f)
                    } ?: 1f,
                    cleanStackAccepted = cleanStackDecision.accepted,
                    cleanStackRejectionReasons = cleanStackDecision.hardFailureReasons,
                    referenceReliableStarCount = retentionValidation.metrics.referenceReliableStarCount,
                    retainedReferenceStarCount = retentionValidation.metrics.retainedReferenceStarCount,
                    referenceStarRetentionRatio = retentionValidation.metrics.retentionRatio,
                    referenceStarContrastBefore = retentionValidation.metrics.medianContrastBefore,
                    referenceStarContrastAfter = retentionValidation.metrics.medianContrastAfter,
                    referenceStarWidthBefore = retentionValidation.metrics.medianWidthBefore,
                    referenceStarWidthAfter = retentionValidation.metrics.medianWidthAfter,
                    referenceStarSmearRate = retentionValidation.metrics.lineLikeSmearRate,
                    coverageMinimum = coverageValidation.metrics.minimumCoverage,
                    coverageMedian = coverageValidation.metrics.medianCoverage,
                    coverageMaximum = coverageValidation.metrics.maximumCoverage,
                    coverageUniformityScore = coverageValidation.metrics.uniformityScore,
                    coverageWedgeDiscontinuityScore = coverageValidation.metrics.wedgeDiscontinuityScore,
                    lineArtifactScore = lineArtifactValidation.metrics.lineArtifactScore,
                    fanPatternScore = lineArtifactValidation.metrics.fanPatternScore,
                    transformSequenceScore = transformSequenceScore,
                    temporalTrackCount = registrationDiagnostics.trackAnalysis.tracks.size,
                    stationaryTrackCount = registrationDiagnostics.trackAnalysis.stationaryTrackCount,
                    movingTrackCount = registrationDiagnostics.trackAnalysis.movingTrackCount,
                    unknownTrackCount = registrationDiagnostics.trackAnalysis.unknownTrackCount,
                    motionObservable = registrationDiagnostics.model.motionObservable,
                    estimatedVelocityXAnalysisPxPerFrame = registrationDiagnostics.model.velocityX,
                    estimatedVelocityYAnalysisPxPerFrame = registrationDiagnostics.model.velocityY,
                    estimatedVelocityXFullPxPerFrame = registrationDiagnostics.model.velocityX * scaleX,
                    estimatedVelocityYFullPxPerFrame = registrationDiagnostics.model.velocityY * scaleY,
                    sequenceModelScore = registrationDiagnostics.model.score,
                    sequenceModelResidual = registrationDiagnostics.model.residual,
                    zeroModelScore = registrationDiagnostics.model.competingZeroModelScore,
                    nonZeroModelScore = registrationDiagnostics.model.nonZeroModelScore,
                    selectedMotionModel = registrationDiagnostics.model.selectedMotionModel,
                    candidateHypothesisCountPerFrame = registrationDiagnostics
                        .hypothesisCountPerFrame.mapKeys { frameNameByKey[it.key] ?: it.key },
                    selectedHypothesisRankPerFrame = registrationDiagnostics
                        .selectedHypothesisRankPerFrame.mapKeys { frameNameByKey[it.key] ?: it.key },
                    movingTrackSupportPerFrame = selectedHypotheses.mapKeys {
                        frameNameByKey[it.key] ?: it.key
                    }.mapValues { it.value.movingTrackSupport },
                    stationaryTrackSupportPerFrame = selectedHypotheses.mapKeys {
                        frameNameByKey[it.key] ?: it.key
                    }.mapValues { it.value.stationaryTrackSupport },
                    spatialSectorSupportPerFrame = selectedHypotheses.mapKeys {
                        frameNameByKey[it.key] ?: it.key
                    }.mapValues { it.value.occupiedSectors },
                    verificationReferenceRetention = acceptedVerificationMean?.referenceRetention ?: 0f,
                    verificationContrastRatio = acceptedVerificationMean?.contrastRatio ?: 0f,
                    verificationWidthGrowth = acceptedVerificationMean?.widthGrowth ?: 0f,
                    verificationSmearRate = acceptedVerificationMean?.smearRate ?: 0f,
                    verificationIdentityScore = registrationDiagnostics.verification.identity.score,
                    verificationZeroModelScore = registrationDiagnostics.verification.zeroModel.score,
                    verificationSelectedModelScore = registrationDiagnostics.verification.selectedModel.score,
                    sequenceSmoothnessScore = sequenceSmoothnessScore,
                    sequencePriorAgreementScore = sequencePriorAgreementScore,
                    registrationRejectedReasons = registrationReports
                        .filterNot { it.accepted }
                        .associate { it.frameName to (it.rejectionReason ?: "registration_failed") },
                    referenceCaptureIndex = registrationDiagnostics.referenceCaptureIndex,
                    analysisWidth = registrationDiagnostics.analysisWidth,
                    analysisHeight = registrationDiagnostics.analysisHeight,
                    fullWidth = targetWidth,
                    fullHeight = targetHeight,
                    analysisToFullScaleX = scaleX,
                    analysisToFullScaleY = scaleY,
                    referenceIdentityVerified = allRegistrationsByKey
                        .getValue(selectedReference.frame.key)
                        .let { it.dx == 0f && it.dy == 0f && it.rotationRadians == 0f && it.scale == 1f },
                    inverseTransformVerificationScore = registrationDiagnostics.verification
                        .inverseModel.score,
                    canonicalTransformVerificationScore = registrationDiagnostics.verification
                        .selectedModel.score,
                    identityTransformVerificationScore = registrationDiagnostics.verification.identity.score,
                    doubleTransformVerificationScore = registrationDiagnostics.verification
                        .doubleAppliedModel.score,
                    perFramePredictedDx = selectedFrames.associate { frame ->
                        val captureIndex = captureIndexByFrameKey.getValue(frame.key)
                        frame.fileName to registrationDiagnostics.model
                            .predictedTransform(captureIndex).dx * scaleX
                    },
                    perFramePredictedDy = selectedFrames.associate { frame ->
                        val captureIndex = captureIndexByFrameKey.getValue(frame.key)
                        frame.fileName to registrationDiagnostics.model
                            .predictedTransform(captureIndex).dy * scaleY
                    },
                    perFrameSelectedDx = selectedFrames.associate { frame ->
                        frame.fileName to allRegistrationsByKey.getValue(frame.key).dx
                    },
                    perFrameSelectedDy = selectedFrames.associate { frame ->
                        frame.fileName to allRegistrationsByKey.getValue(frame.key).dy
                    },
                    perFrameVerificationRetention = registrationDiagnostics.verification.perFrame
                        .mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.referenceRetention },
                    perFrameVerificationContrastRatio = registrationDiagnostics.verification.perFrame
                        .mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.contrastRatio },
                    perFrameVerificationSmearRate = registrationDiagnostics.verification.perFrame
                        .mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.smearRate },
                    modelGuidedRegistrationEnabled = true,
                    modelGuidedSearchRadiusPerFrame = registrationDiagnostics
                        .modelGuidedRegistrations.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.searchRadius },
                    modelGuidedCorrectionDxPerFrame = registrationDiagnostics
                        .modelGuidedRegistrations.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.correctionDx * scaleX },
                    modelGuidedCorrectionDyPerFrame = registrationDiagnostics
                        .modelGuidedRegistrations.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.correctionDy * scaleY },
                    modelGuidedMatchedStarsPerFrame = registrationDiagnostics
                        .modelGuidedRegistrations.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.matchedStars },
                    modelGuidedInlierStarsPerFrame = registrationDiagnostics
                        .modelGuidedRegistrations.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.inlierStars },
                    modelGuidedResidualPerFrame = registrationDiagnostics
                        .modelGuidedRegistrations.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.residual },
                    modelGuidedConfidencePerFrame = registrationDiagnostics
                        .modelGuidedRegistrations.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.confidence },
                    modelGuidedRetryUsedPerFrame = registrationDiagnostics
                        .modelGuidedRegistrations.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.retryUsed },
                    frameAcceptancePathPerFrame = selectedFrames.associate { frame ->
                        val finalRegistration = allRegistrationsByKey.getValue(frame.key)
                        val enginePath = registrationDiagnostics.frameAcceptancePaths[frame.key]
                            ?: "UNAVAILABLE"
                        val refinement = centroidResultsByKey[frame.key]
                        frame.fileName to when {
                            refinement != null && !refinement.accepted -> "REJECTED_STELLAR_CENTROID"
                            refinement != null && refinement.accepted -> "$enginePath+STELLAR_CENTROID_REFINED"
                            !finalRegistration.isReliable && enginePath != "REJECTED" ->
                                "REJECTED_SEQUENCE_VALIDATION"
                            else -> enginePath
                        }
                    },
                    frameAcceptanceReasonPerFrame = selectedFrames.associate { frame ->
                        frame.fileName to (
                            allRegistrationsByKey.getValue(frame.key).rejectionReason
                                ?: registrationDiagnostics.frameAcceptanceReasons[frame.key]
                                ?: "accepted"
                            )
                    },
                    frameVerificationSampleCountPerFrame = registrationDiagnostics.verification
                        .perFrameComparisons.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.sampleCount },
                    frameVerificationConfidencePerFrame = registrationDiagnostics.verification
                        .perFrameComparisons.mapKeys { frameNameByKey[it.key] ?: it.key }
                        .mapValues { it.value.confidence },
                    verificationSampleCount = finalVerificationAggregation.sampleCount,
                    acceptedVerificationSampleCount = finalVerificationAggregation.acceptedSampleCount,
                    rejectedVerificationSampleCount = finalVerificationAggregation.rejectedSampleCount,
                    acceptedVerificationMeanRetention = acceptedVerificationMean?.referenceRetention ?: 0f,
                    acceptedVerificationMeanContrastRatio = acceptedVerificationMean?.contrastRatio ?: 0f,
                    acceptedVerificationMeanSmearRate = acceptedVerificationMean?.smearRate ?: 0f,
                    staticArtifactCandidates = staticArtifactMask.regions.size,
                    staticArtifactMaskRatio = staticArtifactMask.maskRatio,
                    runtimeMaxHeapBytes = memoryBudget.snapshot.maxHeapBytes,
                    heapUsedAtRunStartBytes = memoryBudget.snapshot.usedHeapBytes,
                    safeWorkingBudgetBytes = memoryBudget.safeWorkingBudgetBytes,
                    peakEstimatedResidentBytes = memoryTracker.peakEstimatedResidentBytes,
                    peakObservedHeapBytes = memoryTracker.peakObservedHeapBytes,
                    maximumSimultaneousFullResolutionCandidates =
                        memoryTracker.maximumSimultaneousFullResolutionHeapImages,
                    referenceCandidateBytesOnDisk = referenceCandidate.image.expectedBytes,
                    cleanStackCandidateBytesOnDisk = cleanStackCandidate.image.expectedBytes,
                    processedCandidateBytesOnDisk = processedCandidate?.image?.expectedBytes ?: 0L,
                    tileSizePerStage = memoryTracker.tileSizes(),
                    haloSizePerStage = memoryTracker.haloSizes(),
                    memoryPressureRetries = memoryTracker.memoryPressureRetries,
                    finalBitmapAllocationBytes = 0L,
                    lastCompletedStage = "report_prepared",
                    reportPublicationMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "MEDIASTORE_FILES"
                    } else {
                        "LEGACY_PUBLIC_FILE"
                    },
                    reportFallbackUsed = false,
                    staleRunRecoveryInformation = if (staleRunsRecovered > 0) {
                        "cleaned_$staleRunsRecovered"
                    } else {
                        null
                    },
                    processingRunId = journalRunId,
                    artifactSessionId = artifactSessionId(session.folderName)
                )
                processingReport = processingReport.withFullResolutionRefinement(
                    resultsByKey = refinementResultsByKey,
                    centroidResultsByKey = centroidResultsByKey,
                    frameNameByKey = frameNameByKey,
                    provisionalAcceptedFrameCount = provisionalAcceptedFrames,
                    finalAcceptedFrameCount = acceptedFrames,
                    rejectedCount = fullResolutionRefinementRejectedCount,
                    sampling = samplingFidelity
                )
                var reportJson = processingReport.toJson()
                temporaryFiles.atomicTextFile("final-report.json", reportJson)
                journalRunId?.let { runJournal.update(it, "report_prepared") }
                currentStage = "Сохранение PNG без потерь"
                val savedArtifacts = savePrimaryAndAncillaryEnhanced(
                    selected = finalSelection.selected,
                    referenceImage = maskStage.referenceCandidate,
                    requestedFileName = requestedFileName,
                    effectiveSkyAlpha = effectiveSkyAlpha,
                    confirmedStars = fullResolutionStars,
                    candidateStore = candidateStore,
                    session = session,
                    timestamp = timestamp,
                    pipelineTiming = pipelineTiming,
                    processingReport = processingReport,
                    warnings = warnings,
                    temporaryFiles = temporaryFiles,
                    journalRunId = journalRunId,
                    runJournal = runJournal,
                    experimentalSafeCandidate = cleanStackCandidate.takeIf {
                        profile == AstroProcessingProfile.EXPERIMENTAL_STARS &&
                            finalSelection.selected.type == ResultCandidateType.PROCESSED
                    },
                    experimentalSafeFileName = "${ResultSelectionPolicy.INTERNAL_FALLBACK_LABEL}_$timestamp.png",
                    onProgress = onProgress
                )
                if (savedArtifacts.primaryFallbackUsed) {
                    finalStars = cleanStackCandidate.metrics.reliableStarCount
                    sanityStatus = "fallback"
                    fallback = ResultSelectionPolicy.INTERNAL_FALLBACK_LABEL
                    fallbackReason = savedArtifacts.primaryFallbackReason.orEmpty()
                }
                val saved = savedArtifacts.saved
                val fileName = saved.fileName
                val additionalImageFileName = savedArtifacts.additionalImageFileName
                processingReport = savedArtifacts.processingReport
                reportJson = savedArtifacts.reportJson

                currentStage = "Запись отчёта обработки"
                val reportPublication = publishProcessingReport(
                    session = session,
                    fileName = fileName,
                    outputContentUri = saved.contentUri,
                    outputFilePath = saved.filePath,
                    initialReport = processingReport,
                    initialReportJson = reportJson,
                    temporaryFiles = temporaryFiles,
                    pipelineTiming = pipelineTiming,
                    warnings = warnings,
                    journalRunId = journalRunId,
                    runJournal = runJournal
                )
                processingReport = reportPublication.processingReport
                reportJson = reportPublication.reportJson
                val reportFiles = reportPublication.reportFiles
                val additionalFiles = reportFiles + listOfNotNull(additionalImageFileName)
                Log.i(
                    "AstroPhotoJpegMemory",
                    "peakEstimated=${memoryTracker.peakEstimatedResidentBytes} " +
                        "peakObservedHeap=${memoryTracker.peakObservedHeapBytes} " +
                        "maxFullHeapImages=${memoryTracker.maximumSimultaneousFullResolutionHeapImages} " +
                        "retries=${memoryTracker.memoryPressureRetries} " +
                        "candidateStorage=FILE_BACKED_LINEAR_RGB_16 finalBitmapBytes=0"
                )
                Log.i(
                    "AstroPhotoJpegTiming",
                    pipelineTiming.snapshot().entries.joinToString(" ") { (stage, duration) ->
                        "$stage=${duration}ms"
                    }
                )
                Log.i(
                    "AstroPhotoProfile",
                    "profile=${profile.name} source=${source.metadataValue} " +
                        "inputFrames=${selectedFrames.size} acceptedFrames=$acceptedFrames " +
                        "alignedFrames=$alignmentApplied alignmentFailures=$alignmentRejected " +
                        "stackingMethod=linearWeighted starsBefore=${beforeMetrics.stars} " +
                        "starsAfter=${selectedProfileMetrics.stars} " +
                        "backgroundBefore=${beforeMetrics.background} " +
                        "backgroundAfter=${selectedProfileMetrics.background} sanity=$sanityStatus " +
                        "fallback=$fallback fallbackReason=$fallbackReason outputName=$fileName"
                )
                Log.i(
                    POST_COMPLETION_TAG,
                    "post_completion.session_info.start run=${journalRunId?.take(8).orEmpty()} output=$fileName"
                )
                val infoUpdated = runCatching {
                    appendProfileSessionInfo(
                        session = session,
                        fileName = fileName,
                        profile = profile,
                        method = "Full-resolution JPEG v2 Stage 7 file-backed pipeline",
                        framesUsed = acceptedFrames,
                        framesRejected = framesRejected + (frames.size - selectedFrames.size) +
                            alignmentRejected,
                        alignmentMode = "STAR_SIMILARITY",
                        alignmentApplied = alignmentApplied,
                        alignmentRejected = alignmentRejected,
                        roiMode = recipe.roiName,
                        backgroundRemoval = "ADAPTIVE_SKY_GRADIENT",
                        stretchMode = "ADAPTIVE_ASINH",
                        starBoost = "LOCAL_STAR_CONTRAST",
                        starsBefore = referenceStars,
                        starsAfter = finalStars,
                        source = source,
                        sanity = sanityStatus,
                        fallback = fallback,
                        fallbackReason = fallbackReason,
                        warnings = warnings.distinct(),
                        processedAtMillis = now
                    )
                }.getOrElse { error ->
                    Log.e(
                        POST_COMPLETION_TAG,
                        "post_completion.session_info.failed run=${journalRunId?.take(8).orEmpty()} " +
                            "output=$fileName exception=${error::class.java.simpleName}",
                        error
                    )
                    false
                }
                if (infoUpdated) {
                    Log.i(
                        POST_COMPLETION_TAG,
                        "post_completion.session_info.done run=${journalRunId?.take(8).orEmpty()} output=$fileName"
                    )
                }
                JpegStackResult(
                    fileName = fileName,
                    displayPath = saved.displayPath,
                    contentUri = saved.contentUri,
                    filePath = saved.filePath,
                    frameCount = acceptedFrames,
                    sessionInfoUpdated = infoUpdated,
                    alignmentEnabled = alignmentApplied > 0,
                    astroStretchApplied = processingOutcome == JpegProfileProcessingOutcome.PROCESSED &&
                        !savedArtifacts.primaryFallbackUsed,
                    downscaled = false,
                    profile = profile,
                    selectedResultType = if (savedArtifacts.primaryFallbackUsed) {
                        ResultCandidateType.CLEAN_STACK.name
                    } else {
                        finalSelection.selected.type.name
                    },
                    starsBefore = cleanStackCandidate.metrics.reliableStarCount,
                    starCount = finalStars,
                    fallbackUsed = finalSelection.fallbackUsed || savedArtifacts.primaryFallbackUsed,
                    fallbackReason = savedArtifacts.primaryFallbackReason ?: finalSelection.fallbackReason,
                    warnings = warnings.distinct(),
                    additionalFiles = additionalFiles,
                    processingRunId = journalRunId,
                    processingOutcome = if (savedArtifacts.primaryFallbackUsed) {
                        JpegProfileProcessingOutcome.CLEAN_FALLBACK
                    } else {
                        processingOutcome
                    },
                    postProcessingExecuted = stage4Executed,
                    statistics = JpegResultStatisticsCalculator.calculate(
                        sessionInfo = session.infoContent,
                        inputFrames = frames.size,
                        analyses = acceptedProfileFrames.map { it.analysis },
                        registrations = acceptedProfileFrames.map { it.registration }
                    )
                ).also {
                    Log.i(
                        POST_COMPLETION_TAG,
                        "post_completion.result_created run=${journalRunId?.take(8).orEmpty()} output=$fileName"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: OutOfMemoryError) {
                throw IllegalStateException(
                    "Недостаточно памяти для полноразмерной JPEG-обработки; разрешение результата не уменьшалось.",
                    error
                )
            }
        }
        val outputFileName = stackResult.getOrNull()?.fileName.orEmpty()
        if (stackResult.isSuccess) {
            runCatching { analysisCheckpointStore?.clear() }
                .onFailure { error ->
                    Log.w(PROFILE_REGISTRATION_TAG, "Unable to clear analysis checkpoints", error)
                }
            runCatching { registrationCheckpointStore?.clear() }
                .onFailure { error ->
                    Log.w(PROFILE_REGISTRATION_TAG, "Unable to clear registration checkpoint", error)
                }
            runCatching { integrationCheckpointStore?.clear() }
                .onFailure { error ->
                    Log.w(PROFILE_REGISTRATION_TAG, "Unable to clear integration checkpoint", error)
                }
        }
        Log.i(
            POST_COMPLETION_TAG,
            "post_completion.temp_cleanup.start run=${journalRunId?.take(8).orEmpty()} output=$outputFileName"
        )
        runCatching { pipelineFiles?.close() }
            .onSuccess {
                Log.i(
                    POST_COMPLETION_TAG,
                    "post_completion.temp_cleanup.done run=${journalRunId?.take(8).orEmpty()} output=$outputFileName"
                )
            }
            .onFailure { error ->
                Log.e(
                    POST_COMPLETION_TAG,
                    "post_completion.temp_cleanup.failed run=${journalRunId?.take(8).orEmpty()} " +
                        "output=$outputFileName exception=${error::class.java.simpleName}",
                    error
                )
            }
        var finalizedStackResult = stackResult
        journalRunId?.let { runId ->
            when {
                stackResult.isSuccess -> {
                    val previousStage = runCatching {
                        runJournal.read(runId)?.lastCompletedStage
                    }.getOrNull() ?: "missing"
                    Log.i(
                        POST_COMPLETION_TAG,
                        "post_completion.journal_complete.start run=${runId.take(8)} " +
                            "output=$outputFileName previous=$previousStage requested=completed"
                    )
                    val completionFailure = completeJournalWithSingleRetry(
                        markCompleted = { runJournal.markCompleted(runId, "completed") },
                        onFailure = { attempt, error ->
                            Log.e(
                                POST_COMPLETION_JOURNAL_TAG,
                                "markCompleted failed: run=${runId.take(8)} previous=$previousStage " +
                                    "requested=completed attempt=$attempt " +
                                    "exception=${error::class.java.simpleName} message=${error.message.orEmpty()}",
                                error
                            )
                        }
                    )
                    if (completionFailure == null) {
                        Log.i(
                            POST_COMPLETION_TAG,
                            "post_completion.journal_complete.done run=${runId.take(8)} output=$outputFileName"
                        )
                    } else {
                        val created = checkNotNull(stackResult.getOrNull())
                        finalizedStackResult = Result.success(
                            created.copy(
                                warnings = (
                                    created.warnings +
                                        "Result saved, but processing journal completion failed"
                                    ).distinct()
                            )
                        )
                    }
                }
                stackResult.exceptionOrNull() is CancellationException -> {
                    completeJournalWithSingleRetry(
                        markCompleted = { runJournal.markCompleted(runId, "cancelled") },
                        onFailure = { attempt, error ->
                            Log.e(
                                POST_COMPLETION_JOURNAL_TAG,
                                "cancellation journal completion failed run=${runId.take(8)} " +
                                    "attempt=$attempt exception=${error::class.java.simpleName}",
                                error
                            )
                        }
                    )
                }
                else -> {
                    runCatching {
                        runJournal.recordFailure(
                            runId,
                            currentStage,
                            stackResult.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
                        )
                    }.onFailure { error ->
                        Log.e(
                            POST_COMPLETION_JOURNAL_TAG,
                            "failure journal update failed run=${runId.take(8)} " +
                                "stage=$currentStage exception=${error::class.java.simpleName}",
                            error
                        )
                    }
                }
            }
        }
        finalizedStackResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            Log.i(
                "AstroPhotoProfile",
                "profile=${profile.name} source=${source.metadataValue} inputFrames=${frames.size} " +
                    "alignedFrames=0 alignmentFailures=0 stackingMethod=unknown " +
                    "starsBefore=-1 starsAfter=-1 backgroundBefore=-1 backgroundAfter=-1 " +
                    "sanity=failed fallback=none fallbackReason=${error.message.orEmpty()} outputName="
            )
            Log.e(
                "AstroPhotoProcessing",
                "Profile ${profile.title} failed at $currentStage: ${error.message}",
                error
            )
            return@withContext Result.failure(
                IllegalStateException(
                    "Ошибка на этапе: $currentStage. " +
                        (error.message ?: "Неизвестная ошибка"),
                    error
                )
            )
        }
        Log.i(
            POST_COMPLETION_TAG,
            "post_completion.profile_returned run=${journalRunId?.take(8).orEmpty()} output=$outputFileName"
        )
        finalizedStackResult
    }

    internal fun registerProfileFramesWithWatchdog(
        frames: List<TemporalFeatureFrame>,
        referenceFrameId: String,
        imageWidth: Int,
        imageHeight: Int
    ): SequenceAwareRegistrationDiagnostics {
        profileRegistrationCancellation.set(false)
        val watchdog = ProgressWatchdog(PROFILE_REGISTRATION_STALL_TIMEOUT_MILLIS)
        return try {
            SequenceAwareRegistrationEngine().register(
                frames = frames,
                referenceFrameId = referenceFrameId,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                cancellationCheck = {
                    if (profileRegistrationCancellation.get()) {
                        throw CancellationException("Profile registration cancelled")
                    }
                    watchdog.check()
                },
                onProgress = watchdog::reportProgress
            )
        } catch (_: ProcessingStalledException) {
            error(
                "Выравнивание звёзд не сообщало о прогрессе более 5 минут. " +
                    "Попробуйте исключить кадры без звёзд или с сильным смазом."
            )
        }
    }

    private suspend fun runProfileRegistrationWithCheckpoint(
        store: ProfileRegistrationCheckpointStore,
        selectedFrames: List<SessionFrame>,
        analysesByFrameKey: Map<String, FrameAnalysis>,
        captureIndexByFrameKey: Map<String, Int>,
        referenceFrameId: String,
        imageWidth: Int,
        imageHeight: Int,
        minimumFrames: Int,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit
    ): SequenceAwareRegistrationDiagnostics = restoreOrComputeRegistration(
        store = store,
        onRestored = {
            withContext(Dispatchers.Main.immediate) {
                onProgress(
                    "Выравнивание восстановлено из checkpoint",
                    selectedFrames.size,
                    selectedFrames.size
                )
            }
        }
    ) {
        val featureFrames = buildTemporalFeatureFrames(selectedFrames, analysesByFrameKey, captureIndexByFrameKey)
        com.joe6355.astrophoto.processing.jpeg.v2.registration.registerWithReferenceRecovery(
            selectedAnalyses = selectedFrames.map { analysesByFrameKey.getValue(it.key) },
            captureIndexByFrameKey = captureIndexByFrameKey,
            referenceFrameId = referenceFrameId,
            minimumFrames = minimumFrames,
            onAttempt = { index, total, key ->
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Повторный выбор опорного кадра $index из $total", index, total)
                }
                Log.i(PROFILE_REGISTRATION_TAG, "referenceRecoveryAttempt=$index/$total frame=$key")
            }
        ) { candidateKey ->
            registerProfileFramesWithWatchdog(featureFrames, candidateKey, imageWidth, imageHeight)
        }
    }

    suspend fun loadResultPreview(
        result: JpegStackResult,
        maxSize: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            result.contentUri != null
        ) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    Uri.parse(result.contentUri),
                    Size(maxSize, maxSize),
                    null
                )
            }.getOrNull()
        } else {
            result.filePath?.let { decodeSampledFile(it, maxSize) }
        }
    }

    private fun readDimensions(frame: SessionFrame): Pair<Int, Int>? {
        return readOrientedJpegDimensions { openFrame(frame) }
    }

    private fun openFrame(frame: SessionFrame): InputStream? =
        if (frame.contentUri != null) {
            context.contentResolver.openInputStream(Uri.parse(frame.contentUri))
        } else {
            frame.filePath?.let { File(it).inputStream() }
        }

    internal fun decodeMedianFrame(
        frame: SessionFrame,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val dimensions = readDimensions(frame) ?: return null
        var sampleSize = 1
        while (
            dimensions.first / (sampleSize * 2) >= targetWidth &&
            dimensions.second / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        val decoded = decodeOrientedJpeg({ openFrame(frame) }, sampleSize) ?: return null
        if (decoded.width == targetWidth && decoded.height == targetHeight) {
            return decoded
        }
        return try {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        } finally {
            decoded.recycle()
        }
    }

    private data class ManualAlignmentReference(
        val image: ArgbPixelImage,
        val scaleX: Float,
        val scaleY: Float
    )

    private fun createAlignmentReference(
        frame: SessionFrame,
        targetWidth: Int,
        targetHeight: Int
    ): ManualAlignmentReference {
        val scale = minOf(1f, 640f / maxOf(targetWidth, targetHeight))
        val bitmap = decodeMedianFrame(frame, maxOf(1, (targetWidth * scale).roundToInt()),
            maxOf(1, (targetHeight * scale).roundToInt()))
            ?: error("Не удалось подготовить опорный кадр")
        return try {
            createManualAlignmentSample(bitmap).let {
                it.copy(scaleX = targetWidth.toFloat() / it.image.width, scaleY = targetHeight.toFloat() / it.image.height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun prepareManualSequenceAlignmentSelection(
        frames: List<SessionFrame>,
        targetWidth: Int,
        targetHeight: Int,
        mode: ManualAlignedStackMode,
        onProgress: suspend (
            current: Int,
            total: Int,
            message: String
        ) -> Unit
    ): ManualAlignmentSelection {
        return try {
            val selection = selectManualAlignmentPath(
                inputFrameCount = frames.size,
                mode = mode,
                failureInjector = manualAlignmentFailureInjector
            ) {
                val scale = minOf(
                    1f,
                    PROFILE_ANALYSIS_MAX_DIMENSION.toFloat() /
                        maxOf(targetWidth, targetHeight).coerceAtLeast(1)
                )
                val analysisWidth = maxOf(1, (targetWidth * scale).roundToInt())
                val analysisHeight = maxOf(1, (targetHeight * scale).roundToInt())
                val analyzer = JpegFrameAnalyzer()
                val maskEstimator = SkyMaskEstimator()
                val persistentDetector = PersistentSensorCandidateDetector()
                val persistentObservations = mutableListOf<ArtifactFrameObservation>()
                val analyses = frames.mapIndexed { index, frame ->
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        index + 1,
                        frames.size,
                        "Анализ выравнивания ${index + 1} из ${frames.size}"
                    )
                    val sample = decodeMedianFrame(frame, analysisWidth, analysisHeight)
                        ?: error("Unable to decode manual alignment sample")
                    try {
                        val image = bitmapToArgbImage(sample)
                        val skyMask = maskEstimator.estimate(image)
                        persistentObservations += ArtifactFrameObservation(
                            frame.key,
                            persistentDetector.detect(image, skyMask.mask)
                        )
                        analyzer.analyze(
                            id = frame.key,
                            fileName = frame.fileName,
                            image = image,
                            skyMask = skyMask
                        )
                    } finally {
                        sample.recycle()
                    }
                }
                evaluateManualSequenceAlignmentFromAnalyses(
                    analyses = analyses,
                    outputWidth = targetWidth,
                    outputHeight = targetHeight,
                    persistentArtifactObservations = persistentObservations
                )
            }
            val plan = selection.sequencePlan
            if (plan != null) {
                Log.i(
                    "AstroPhotoAlignment",
                    "source=sequence method=sequencePlan " +
                        "reason=${selection.report.manualAlignmentPathReason.name} " +
                        "referenceFrame=${plan.referenceFrameIndex + 1} " +
                        "modelScore=${formatMetric(plan.modelScore)} " +
                        "modelResidual=${formatMetric(plan.modelResidualPx)} " +
                        "stationaryArtifacts=${plan.stationaryArtifactCount} " +
                        "sensorDefectRegions=${plan.sensorDefectMask?.regions?.size ?: 0} " +
                        "sensorDefectMaskPixels=${plan.sensorDefectMask?.maskedPixelCount ?: 0} " +
                        "sensorDefectMaskEnabled=${plan.sensorDefectMask?.enabled == true} " +
                        "sensorDefectMaskReason=${plan.sensorDefectMask?.rejectionReason.orEmpty()} " +
                        "inputFrames=${plan.frames.size} " +
                        "acceptedRegistrations=${plan.acceptedRegistrationCount} " +
                        "rejectedRegistrations=${plan.rejectedRegistrationCount} " +
                        "rejectedOriginalIndices=${plan.frames.filterNot { it.accepted }
                            .joinToString { it.originalFrameNumber.toString() }}"
                )
            } else {
                Log.i(
                    "AstroPhotoAlignment",
                    "source=sequence method=legacyFallback " +
                        "reason=${selection.report.manualAlignmentPathReason.name} " +
                        "allowed=${selection.report.legacyFallbackAllowed}"
                )
            }
            selection
        } catch (error: CancellationException) {
            throw error
        } catch (error: ManualAlignmentReportedException) {
            Log.w(
                "AstroPhotoAlignment",
                "source=sequence method=failed " +
                    "reason=${error.alignmentReport.manualAlignmentPathReason.name} " +
                    "failureType=${error.alignmentReport.sequencePlannerFailureType.orEmpty()} " +
                    "legacyFallbackUsed=${error.alignmentReport.legacyFallbackUsed}"
            )
            throw error
        }
    }

    private suspend fun reportManualSequenceShift(
        plan: ManualSequenceAlignmentPlan,
        frameIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        source: ManualStackingSource,
        onAlignment: suspend (
            current: Int,
            total: Int,
            message: String
        ) -> Unit
    ): AlignmentShift {
        val frameNumber = frameIndex + 1
        val totalFrames = plan.frames.size
        val decision = plan.frames[frameIndex]
        require(decision.originalFrameIndex == frameIndex)
        require(decision.accepted) {
            "Rejected frame $frameNumber must not reach transform lookup"
        }
        val shift = decision.shift
        val maxShift = manualAlignmentShiftLimitPx(
            frameNumber,
            totalFrames,
            targetWidth,
            targetHeight
        )
        Log.i(
            "AstroPhotoAlignment",
            "source=${source.metadataValue} starsDetected=sequence " +
                "matches=${plan.acceptedRegistrationCount} shiftX=${shift.dx} shiftY=${shift.dy} " +
                "maxShift=$maxShift confidence=" +
                "${decision.registrationConfidence?.let(::formatMetric).orEmpty()} " +
                "residual=${decision.registrationResidualPx?.let(::formatMetric).orEmpty()} " +
                "originalFrameIndex=$frameNumber frameId=${decision.frameId.orEmpty()} " +
                "method=sequence fallbackReason="
        )
        onAlignment(
            frameNumber,
            totalFrames,
            "Выравнивание кадра $frameNumber из $totalFrames: " +
                "dx=${shift.dx}, dy=${shift.dy}, method=sequence"
        )
        return shift
    }

    private suspend fun findAlignmentOrZero(
        reference: ManualAlignmentReference,
        candidate: Bitmap,
        frameNumber: Int,
        totalFrames: Int,
        safeMode: Boolean,
        source: ManualStackingSource,
        outputWidth: Int = candidate.width,
        outputHeight: Int = candidate.height,
        onAlignment: suspend (
            current: Int,
            total: Int,
            message: String
        ) -> Unit
    ): AlignmentShift {
        onAlignment(
            frameNumber,
            totalFrames,
            "Выравнивание кадра $frameNumber из $totalFrames"
        )
        return try {
            val candidateSample = createManualAlignmentSample(candidate)
            val fullResolutionMaxShift = manualAlignmentShiftLimitPx(
                frameNumber = frameNumber,
                totalFrames = totalFrames,
                imageWidth = outputWidth,
                imageHeight = outputHeight
            )
            val maxShift = maxOf(
                ceil(fullResolutionMaxShift / reference.scaleX).toInt(),
                ceil(fullResolutionMaxShift / reference.scaleY).toInt()
            ).coerceAtLeast(1)
            val diagnostic = alignManualImages(
                reference.image,
                candidateSample.image,
                safeMode = safeMode,
                maxShiftPx = maxShift
            )
            val shift = diagnostic.shift.copy(
                dx = (diagnostic.shift.dx * reference.scaleX).roundToInt()
                    .coerceIn(-fullResolutionMaxShift, fullResolutionMaxShift),
                dy = (diagnostic.shift.dy * reference.scaleY).roundToInt()
                    .coerceIn(-fullResolutionMaxShift, fullResolutionMaxShift)
            )
            Log.i(
                "AstroPhotoAlignment",
                "source=${source.metadataValue} starsDetected=${diagnostic.starsDetected} " +
                    "matches=${diagnostic.matches} shiftX=${shift.dx} shiftY=${shift.dy} " +
                    "maxShift=$fullResolutionMaxShift " +
                    "confidence=${"%.3f".format(Locale.US, diagnostic.confidence)} " +
                    "method=${diagnostic.method} fallbackReason=${diagnostic.fallbackReason.orEmpty()}"
            )
            onAlignment(
                frameNumber,
                totalFrames,
                "Выравнивание кадра $frameNumber из $totalFrames: " +
                    "dx=${shift.dx}, dy=${shift.dy}, method=${diagnostic.method}"
            )
            shift
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onAlignment(
                frameNumber,
                totalFrames,
                "Не удалось выровнять кадр $frameNumber. " +
                    "Продолжаем без выравнивания."
            )
            AlignmentShift.Zero
        }
    }

    private fun createManualAlignmentSample(bitmap: Bitmap): ManualAlignmentReference {
        val maxDimension = 640f
        val scale = minOf(
            1f,
            maxDimension / maxOf(bitmap.width, bitmap.height).toFloat()
        )
        val width = maxOf(1, (bitmap.width * scale).roundToInt())
        val height = maxOf(1, (bitmap.height * scale).roundToInt())
        val thumbnail = if (
            width == bitmap.width && height == bitmap.height
        ) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, false)
        }

        try {
            val pixels = IntArray(width * height)
            thumbnail.getPixels(pixels, 0, width, 0, 0, width, height)
            return ManualAlignmentReference(
                image = ArgbPixelImage(width, height, pixels),
                scaleX = bitmap.width.toFloat() / width,
                scaleY = bitmap.height.toFloat() / height
            )
        } finally {
            if (thumbnail !== bitmap) thumbnail.recycle()
        }
    }

    private fun logProfileRegistration(fileName: String, registration: RegistrationResult) {
        Log.i(
            PROFILE_REGISTRATION_TAG,
            "frame=$fileName detectedStars=${registration.detectedStars} " +
                "matchedStars=${registration.matchedStars} inlierStars=${registration.inlierStars} " +
                "dx=${formatMetric(registration.dx)} dy=${formatMetric(registration.dy)} " +
                "rotation=${formatMetric(registration.rotationRadians)} " +
                "scale=${formatMetric(registration.scale)} " +
                "model=${registration.registrationModel} scaleFixed=${registration.scaleFixed} " +
                "rotationAllowed=${registration.rotationAllowed} " +
                "rotationRejectionReason=${registration.rotationRejectionReason.orEmpty()} " +
                "occupiedCells=${registration.occupiedDistributionCells} " +
                "horizontalSpan=${formatMetric(registration.horizontalDistributionSpan)} " +
                "verticalSpan=${formatMetric(registration.verticalDistributionSpan)} " +
                "distributionScore=${formatMetric(registration.spatialDistributionScore)} " +
                "residual=${formatMetric(registration.residualError)} " +
                "rawDx=${formatMetric(registration.rawDx)} rawDy=${formatMetric(registration.rawDy)} " +
                "rawRotation=${formatMetric(registration.rawRotationRadians)} " +
                "sequenceScore=${formatMetric(registration.transformSequenceScore)} " +
                "sequenceDeviation=${formatMetric(registration.transformSequenceDeviation)} " +
                "neighborDelta=${formatMetric(registration.neighborTransformDelta)} " +
                "retryUsed=${registration.transformRetryUsed} " +
                "confidence=${formatMetric(registration.confidence)} " +
                "accepted=${registration.isReliable} " +
                "rejectionReason=${registration.rejectionReason.orEmpty()}"
        )
    }

    internal fun scaleV2Stars(
        stars: List<V2DetectedStar>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<V2DetectedStar> {
        val scaleX = targetWidth.toFloat() / sourceWidth.coerceAtLeast(1)
        val scaleY = targetHeight.toFloat() / sourceHeight.coerceAtLeast(1)
        val sizeScale = (scaleX + scaleY) / 2f
        return stars.map { star ->
            star.copy(
                x = star.x * scaleX,
                y = star.y * scaleY,
                width = star.width * sizeScale
            )
        }
    }

    private fun frameWeightReports(
        weights: List<FrameWeight>,
        frameNameById: Map<String, String>
    ): List<FrameWeightReport> = weights.map { weight ->
        FrameWeightReport(
            frameName = frameNameById[weight.frameId] ?: weight.frameId,
            registrationWeight = weight.registrationWeight,
            sharpnessWeight = weight.sharpnessWeight,
            trailWeight = weight.trailWeight,
            noiseWeight = weight.noiseWeight,
            exposureWeight = weight.exposureWeight,
            normalizedWeight = weight.normalizedWeight
        )
    }

    private fun processingIntegrationReport(
        inputWidth: Int,
        inputHeight: Int,
        diagnostics: IntegrationDiagnostics,
        diskCacheBytes: Long
    ) = IntegrationReport(
        mode = diagnostics.mode.name,
        robustMode = diagnostics.robustModeEnabled,
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        outputWidth = diagnostics.outputWidth,
        outputHeight = diagnostics.outputHeight,
        tileWidth = diagnostics.tileWidth,
        tileHeight = diagnostics.tileHeight,
        resolutionChanged = diagnostics.resolutionChanged,
        validCoveragePercent = diagnostics.validCoveragePercent,
        estimatedWorkingMemoryBytes = diagnostics.estimatedPeakWorkingMemoryBytes,
        outputAllocationBytes = 0L,
        diskCacheBytes = diskCacheBytes,
        robustModeReason = diagnostics.robustModeReason
    )

    internal fun scaleSkyMask(mask: SkyMask, width: Int, height: Int): SkyMask =
        SkyMask(
            width,
            height,
            BooleanArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val sourceX = (x.toLong() * mask.width / width).toInt().coerceIn(0, mask.width - 1)
                val sourceY = (y.toLong() * mask.height / height).toInt().coerceIn(0, mask.height - 1)
                mask.contains(sourceX, sourceY)
            }
        )


    private fun bitmapToArgbImage(bitmap: Bitmap): ArgbPixelImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ArgbPixelImage(bitmap.width, bitmap.height, pixels)
    }

    private fun logProfileStage(
        profile: AstroProcessingProfile,
        source: ManualStackingSource,
        stage: String,
        metrics: ProfileSanityMetrics
    ) {
        Log.i(
            "AstroPhotoProfileStage",
            "profile=${profile.name} source=${source.metadataValue} stage=$stage " +
                "median=${metrics.medianLuminance} low=${metrics.lowPercentile} " +
                "high=${metrics.highPercentile} black=${metrics.blackPercent} " +
                "white=${metrics.whitePercent} stars=${metrics.stars} " +
                "starContrast=${metrics.medianStarContrast} " +
                "backgroundSpread=${metrics.backgroundSpread} " +
                "banding=${metrics.largeScaleBanding}"
        )
    }

    internal suspend fun saveBitmap(
        session: SessionSummary,
        bitmap: Bitmap,
        fileName: String
    ): SavedProcessedImage {
        currentCoroutineContext().ensureActive()
        val jpegQuality = CameraSettingsStore(context).load().jpegQuality
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val destination = processedImageDestination(session.folderName)
            val relativePath = destination.relativePath
            val finalFileName = findUniqueProcessedResultName(fileName) { candidate ->
                mediaStoreProcessedNameExists(relativePath, candidate)
            }
            val uri = resolver.insert(
                processedImagesCollection(destination.collection),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, destination.mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            ) ?: error("Не удалось сохранить результат")

            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                        error("Не удалось сохранить результат")
                    }
                } ?: error("Не удалось сохранить результат")
                currentCoroutineContext().ensureActive()
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                val savedInfo = resolver.query(
                    uri,
                    arrayOf(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.SIZE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0).orEmpty().ifBlank { finalFileName } to
                            cursor.getLong(1)
                    } else {
                        finalFileName to 0L
                    }
                } ?: (finalFileName to 0L)
                if (savedInfo.second <= 0L) {
                    resolver.delete(uri, null, null)
                    error("Файл результата не был сохранён")
                }
                currentCoroutineContext().ensureActive()
                val savedFileName = savedInfo.first
                Log.d(
                    "ProcessedResult",
                    "resultName=$savedFileName storedUri=$uri " +
                        "relativePath=$relativePath resolvedCollection=stored"
                )
                return retainedMediaStoreImage(
                    destination = destination,
                    actualFileName = savedFileName,
                    insertedUri = uri.toString()
                )
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val directory = File(
            pictures,
            "AstroPhoto/${session.folderName}/Processed"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("Не удалось создать папку Processed")
        }
        val finalFileName = findUniqueProcessedResultName(fileName) { candidate ->
            File(directory, candidate).exists() ||
                File(directory, "$candidate.tmp").exists()
        }
        val file = File(directory, finalFileName)
        val tempFile = File(directory, "$finalFileName.tmp")
        var moved = false
        try {
            require(tempFile.createNewFile()) {
                "Не удалось создать временный файл результата"
            }
            FileOutputStream(tempFile, false).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                    error("Не удалось сохранить результат")
                }
            }
            currentCoroutineContext().ensureActive()
            require(tempFile.length() > 0L) { "Файл результата не был сохранён" }
            try {
                Files.move(tempFile.toPath(), file.toPath())
                moved = true
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Не удалось создать уникальный файл результата",
                    error
                )
            }
            currentCoroutineContext().ensureActive()
            require(file.length() > 0L) { "Файл результата не был сохранён" }
        } catch (error: Exception) {
            tempFile.delete()
            if (moved) file.delete()
            throw error
        }
        return SavedProcessedImage(
            fileName = finalFileName,
            displayPath = file.absolutePath,
            contentUri = null,
            filePath = file.absolutePath
        )
    }

    private fun deleteSavedJpeg(saved: SavedProcessedImage) {
        saved.contentUri?.let { uri ->
            runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            return
        }
        saved.filePath?.let { path -> runCatching { File(path).delete() } }
    }

    private fun mediaStoreProcessedNameExists(
        relativePath: String,
        fileName: String
    ): Boolean {
        val resolver = context.contentResolver
        return resolver.query(
            processedImagesCollection(),
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(fileName, relativePath),
            null
        )?.use { cursor ->
            cursor.moveToFirst()
        } ?: error("Не удалось проверить имя файла результата")
    }

    private fun manualSequenceReportSummary(
        report: ManualSequenceIntegrationReport
    ): String = buildString {
        val alignment = report.alignmentPathReport
        append(
            "alignmentPath=${alignment.manualAlignmentPath.name}, " +
                "alignmentReason=${alignment.manualAlignmentPathReason.name}, " +
                "legacyFallbackUsed=${alignment.legacyFallbackUsed}, " +
                "input=${report.inputFrameCount}, accepted=${report.acceptedFrameCount}, " +
                "rejected=${report.rejectedFrameCount}"
        )
        if (report.rejectedFrames.isNotEmpty()) {
            append(
                ", rejectedOriginalIndices=" +
                    report.rejectedFrames.joinToString { it.originalFrameNumber.toString() }
            )
        }
        append(
            ", integratedOriginalIndices=" +
                report.integratedOriginalFrameIndices.joinToString { (it + 1).toString() }
        )
        report.sensorDefectFiltering?.let { filtering ->
            append(
                ", defectRegions=${filtering.regionCount}, " +
                    "defectPixels=${filtering.maskedSourcePixelCount}, " +
                    "defectFilteringApplied=${filtering.sampleLevelFilteringApplied}, " +
                    "excludedSamples=${filtering.excludedSampleCount}, " +
                    "affectedOutputPixels=${filtering.affectedOutputPixelCount}, " +
                    "insufficientCoveragePixels=${filtering.insufficientCoveragePixelCount}"
            )
        }
    }

    private fun logManualSequenceIntegrationReport(
        report: ManualSequenceIntegrationReport
    ) {
        Log.i(
            "AstroPhotoAlignment",
            "mode=${report.mode.reportName} ${manualSequenceReportSummary(report)} " +
                "rejectionReasons=${report.rejectedFrames.joinToString { frame ->
                    "${frame.originalFrameNumber}:${frame.frameId.orEmpty()}:${frame.reason}"
                }}"
        )
    }

    private fun StringBuilder.appendManualSequenceReport(
        report: ManualSequenceIntegrationReport?
    ) {
        if (report == null) return
        report.alignmentPathReport.toStableFields().forEach { (name, value) ->
            appendLine("$name: $value")
        }
        appendLine("manualSequenceMode: ${report.mode.reportName}")
        appendLine("manualSequenceInputFrames: ${report.inputFrameCount}")
        appendLine("manualSequenceAcceptedFrames: ${report.acceptedFrameCount}")
        appendLine("manualSequenceRejectedFrames: ${report.rejectedFrameCount}")
        appendLine(
            "manualSequenceRejectedOriginalIndices: " +
                report.rejectedFrames.joinToString { it.originalFrameNumber.toString() }
        )
        appendLine(
            "manualSequenceRejectionReasons: " +
                report.rejectedFrames.joinToString("|") { frame ->
                    "${frame.originalFrameNumber}:${frame.frameId.orEmpty()}:${frame.reason}"
                }
        )
        appendLine(
            "manualSequenceIntegratedOriginalIndices: " +
                report.integratedOriginalFrameIndices.joinToString { (it + 1).toString() }
        )
        report.sensorDefectFiltering?.let { filtering ->
            appendLine("sensorDefectMaskRegionCount: ${filtering.regionCount}")
            appendLine("sensorDefectMaskPixelCount: ${filtering.maskedSourcePixelCount}")
            appendLine("sensorDefectMaskedSourceFraction: ${filtering.maskedSourceFraction}")
            appendLine("sensorDefectExcludedSampleCount: ${filtering.excludedSampleCount}")
            appendLine(
                "sensorDefectAffectedOutputPixelCount: ${filtering.affectedOutputPixelCount}"
            )
            appendLine(
                "sensorDefectRemainingSamplesMinMedianMax: " +
                    "${filtering.minimumRemainingSampleCount}," +
                    "${filtering.medianRemainingSampleCount}," +
                    filtering.maximumRemainingSampleCount
            )
            appendLine(
                "sensorDefectInsufficientCoveragePixelCount: " +
                    filtering.insufficientCoveragePixelCount
            )
            appendLine(
                "sensorDefectSampleFilteringApplied: " +
                    filtering.sampleLevelFilteringApplied
            )
            appendLine(
                "sensorDefectFallbackOrRejectionReason: " +
                    filtering.fallbackOrRejectionReason.orEmpty()
            )
            appendLine(
                "sensorDefectRegions: " +
                    filtering.regions.joinToString("|") { region ->
                        "${region.stableRegionId}:pixels=${region.footprintPixelCount}:" +
                            "camera=${region.recurrence}/${region.totalFrameCount}:" +
                            "sky=${region.skySpaceSupport}/${region.totalFrameCount}:" +
                            "confidence=${region.confidence}:" +
                            "reason=${region.classificationReason}"
                    }
            )
        }
    }

    private suspend fun appendManualAlignmentFailureSessionInfo(
        session: SessionSummary,
        mode: ManualAlignedStackMode,
        report: ManualAlignmentPathReport
    ) {
        val timestamp = System.currentTimeMillis()
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(timestamp))
        val block = buildString {
            appendLine()
            appendLine("processedType: Manual alignment failure")
            appendLine("manualSequenceMode: ${mode.reportName}")
            report.toStableFields().forEach { (name, value) ->
                appendLine("$name: $value")
            }
            appendLine("processedAt: $processedAt")
        }
        val sessionInfoWritten = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendMediaStoreSessionInfo(session, block)
            } else {
                appendLegacySessionInfo(session, block)
                true
            }
        }.getOrDefault(false)
        val savedReport = ProcessingReportWriter(context).write(
            session = session,
            imageFileName = "ManualAlignmentFailure_$timestamp.json",
            json = manualAlignmentPathReportJson(mode, report)
        )
        Log.w(
            "AstroPhotoAlignment",
            "manualFailureReport=${savedReport.displayPath} " +
                "sessionInfoWritten=$sessionInfoWritten " +
                "publicationMode=${savedReport.publicationMode}"
        )
    }

    private fun appendSessionInfo(
        session: SessionSummary,
        fileName: String,
        frameCount: Int,
        alignmentEnabled: Boolean,
        astroStretchApplied: Boolean,
        source: ManualStackingSource,
        manualSequenceReport: ManualSequenceIntegrationReport?,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedFrames: $frameCount")
            appendLine("source=${source.metadataValue}")
            appendLine("alignmentEnabled: $alignmentEnabled")
            appendLine("astroStretchApplied: $astroStretchApplied")
            appendManualSequenceReport(manualSequenceReport)
            appendLine("processedAt: $processedAt")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendDarkStackSessionInfo(
        session: SessionSummary,
        resultFileName: String,
        masterDarkFileName: String?,
        lightFrameCount: Int,
        darkFrameCount: Int,
        shadowOffset: Int,
        alignmentEnabled: Boolean,
        astroStretchApplied: Boolean,
        source: ManualStackingSource,
        manualSequenceReport: ManualSequenceIntegrationReport?,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$resultFileName")
            appendLine("processedType: JPEG stacking + Dark Frames")
            appendLine("processedLightFrames: $lightFrameCount")
            appendLine("processedDarkFrames: $darkFrameCount")
            appendLine("source=${source.metadataValue}")
            appendLine("shadowOffset: $shadowOffset")
            appendLine("darkSubtractionMode: Safe")
            appendLine("alignmentEnabled: $alignmentEnabled")
            appendLine("astroStretchApplied: $astroStretchApplied")
            appendManualSequenceReport(manualSequenceReport)
            masterDarkFileName?.let {
                appendLine("masterDarkFile: Processed/$it")
            }
            appendLine("processedAt: $processedAt")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendMedianSessionInfo(
        session: SessionSummary,
        fileName: String,
        frameCount: Int,
        alignmentEnabled: Boolean,
        downscaled: Boolean,
        astroStretchApplied: Boolean,
        source: ManualStackingSource,
        manualSequenceReport: ManualSequenceIntegrationReport?,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedType: Median JPEG stacking")
            appendLine("medianFrames: $frameCount")
            appendLine("source=${source.metadataValue}")
            appendLine("medianAlignmentEnabled: $alignmentEnabled")
            appendLine("medianDownscaled: $downscaled")
            appendLine("astroStretchApplied: $astroStretchApplied")
            appendManualSequenceReport(manualSequenceReport)
            appendLine("processedAt: $processedAt")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendSigmaSessionInfo(
        session: SessionSummary,
        fileName: String,
        frameCount: Int,
        sigma: Double,
        alignmentEnabled: Boolean,
        downscaled: Boolean,
        astroStretchApplied: Boolean,
        source: ManualStackingSource,
        manualSequenceReport: ManualSequenceIntegrationReport?,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedType: Sigma clipping JPEG stacking")
            appendLine("sigmaValue: $sigma")
            appendLine("sigmaFrames: $frameCount")
            appendLine("source=${source.metadataValue}")
            appendLine("sigmaAlignmentEnabled: $alignmentEnabled")
            appendLine("sigmaDownscaled: $downscaled")
            appendLine("astroStretchApplied: $astroStretchApplied")
            appendManualSequenceReport(manualSequenceReport)
            appendLine("processedAt: $processedAt")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendProfileSessionInfo(
        session: SessionSummary,
        fileName: String,
        profile: AstroProcessingProfile,
        method: String,
        framesUsed: Int,
        framesRejected: Int,
        alignmentMode: String,
        alignmentApplied: Int,
        alignmentRejected: Int,
        roiMode: String,
        backgroundRemoval: String,
        stretchMode: String,
        starBoost: String,
        starsBefore: Int,
        starsAfter: Int,
        source: ManualStackingSource,
        sanity: String,
        fallback: String,
        fallbackReason: String,
        warnings: List<String>,
        processedAtMillis: Long
    ): Boolean {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedType: Astro profile JPEG")
            appendLine("profile: ${profile.title}")
            appendLine("source=${source.metadataValue}")
            appendLine("method: $method")
            appendLine("framesUsed: $framesUsed")
            appendLine("framesRejected: $framesRejected")
            appendLine("alignmentMode: $alignmentMode")
            appendLine("alignmentAppliedFrames: $alignmentApplied")
            appendLine("alignmentRejectedFrames: $alignmentRejected")
            appendLine("roiMode: $roiMode")
            appendLine("backgroundRemoval: $backgroundRemoval")
            appendLine("stretchMode: $stretchMode")
            appendLine("starBoost: $starBoost")
            appendLine("starsBefore: $starsBefore")
            appendLine("starsAfter: $starsAfter")
            appendLine("sanity: $sanity")
            appendLine("fallback: $fallback")
            appendLine("fallbackReason: $fallbackReason")
            warnings.forEachIndexed { index, warning ->
                appendLine("profileWarning${index + 1}: $warning")
            }
            appendLine("outputFile: Processed/$fileName")
            appendLine("processedAt: $processedAt")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
            true
        }
    }

    private fun appendMediaStoreSessionInfo(session: SessionSummary, block: String): Boolean {
        SessionInfoStore(context).append(session, block)
        return true
    }

    private fun appendLegacySessionInfo(session: SessionSummary, block: String) {
        SessionInfoStore(context).append(session, block)
    }

    private fun decodeSampledFile(path: String, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxSize ||
            bounds.outHeight / sampleSize > maxSize
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    internal fun appendRawStackSessionInfo(
        session: SessionSummary,
        fileName: String,
        frameCount: Int,
        rejectedFrameCount: Int,
        alignedFrameCount: Int,
        downscaled: Boolean,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedType: Linear RAW16 stacking")
            appendLine("rawFrames: $frameCount")
            appendLine("rawRejectedFrames: $rejectedFrameCount")
            appendLine("rawSubpixelAlignedFrames: $alignedFrameCount")
            appendLine("rawDownscaled: $downscaled")
            appendLine("rawToneMap: percentile asinh sRGB")
            appendLine("processedAt: $processedAt")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    companion object {
        private const val MAX_PROFILE_FRAMES = 30
        private const val PROFILE_REGISTRATION_STALL_TIMEOUT_MILLIS = 300_000L
        private const val PROFILE_ANALYSIS_MAX_DIMENSION = 960
        internal const val PROFILE_REGISTRATION_TAG = "AstroPhotoJpegV2"
        private const val ADAPTIVE_PROCESSING_TAG = "AstroPhotoJpegStage4"
        internal const val POST_COMPLETION_TAG = "AstroPhotoPostCompletion"
        internal const val POST_COMPLETION_JOURNAL_TAG = "AstroPhotoProcessingJournal"
        internal const val EXPERIMENTAL_PUBLICATION_FALLBACK_REASON =
            "experimental_save_or_verification_failed"
        private const val MIN_SKY_ALPHA_FOR_ADAPTIVE_STATISTICS = 0.98f
        private const val MIN_PROFILE_WORKING_MEMORY_BYTES = 64L * 1024L * 1024L
        private const val MAX_PROFILE_WORKING_MEMORY_BYTES = 256L * 1024L * 1024L
        private const val FOREGROUND_SHARPNESS_TOLERANCE = 0.05f
        private val SUPPORTED_SIGMA_VALUES = setOf(1.5, 2.0, 2.5, 3.0)
    }
}

internal fun RegistrationResult.toReport(frameName: String): FrameRegistrationReport =
    FrameRegistrationReport(
        frameName = frameName,
        accepted = isReliable,
        rejectionReason = rejectionReason,
        detectedStars = detectedStars,
        matchedStars = matchedStars,
        inlierStars = inlierStars,
        dx = dx,
        dy = dy,
        rotationRadians = rotationRadians,
        scale = scale,
        residualError = residualError,
        confidence = confidence,
        registrationModel = registrationModel,
        scaleFixed = scaleFixed,
        rotationAllowed = rotationAllowed,
        rotationRejectionReason = rotationRejectionReason,
        occupiedDistributionCells = occupiedDistributionCells,
        horizontalDistributionSpan = horizontalDistributionSpan,
        verticalDistributionSpan = verticalDistributionSpan,
        spatialDistributionScore = spatialDistributionScore,
        rawDx = rawDx,
        rawDy = rawDy,
        rawRotationRadians = rawRotationRadians,
        transformSequenceScore = transformSequenceScore,
        transformSequenceDeviation = transformSequenceDeviation,
        neighborTransformDelta = neighborTransformDelta,
        transformRetryUsed = transformRetryUsed
    )

internal fun qualityFallbackWarning(
    selectedType: ResultCandidateType,
    reason: String
): String {
    val cause = when {
        "star_" in reason || "retention" in reason || "smear" in reason || "points" in reason ->
            "звёзды стали менее резкими или заметными"
        "coverage" in reason -> "покрытие неба оказалось неоднородным"
        "line" in reason || "fan" in reason || "streak" in reason ->
            "появились новые линейные артефакты"
        "transform_sequence" in reason || "registered_frames" in reason ->
            "движение кадров оказалось недостаточно надёжным"
        "noise" in reason || "mad" in reason -> "увеличился шум фона"
        "median" in reason || "gray" in reason -> "фон стал слишком ярким"
        "banding" in reason -> "усилился бандинг"
        "foreground" in reason || "wire" in reason || "edge" in reason ->
            "изменился защищённый передний план"
        "dimension" in reason || "aspect" in reason || "crop" in reason || "border" in reason ->
            "нарушились размеры или границы изображения"
        "confidence" in reason -> "статистика неба была недостаточно надёжной"
        else -> "результат не прошёл финальную проверку качества"
    }
    val saved = if (selectedType == ResultCandidateType.CLEAN_STACK) {
        "Сохранён чистый стек."
    } else {
        "Сохранён выбранный опорный кадр."
    }
    val action = if (selectedType == ResultCandidateType.REFERENCE) {
        "Стекинг отклонён: $cause."
    } else {
        "Preset-обработка отклонена: $cause."
    }
    return "$action $saved"
}

internal fun resultCandidateTitle(type: String?): String = when (type) {
    ResultCandidateType.PROCESSED.name -> "обработанный"
    ResultCandidateType.CLEAN_STACK.name -> "чистый стек"
    ResultCandidateType.REFERENCE.name -> "опорный кадр"
    else -> "неизвестно"
}

internal fun logPostCompletionEvent(event: PostCompletionEvent) {
    val message = "post_completion.${event.operation}.${event.phase} " +
        "run=${event.runIdPrefix} output=${event.outputFileName}" +
        (event.throwable?.let { error ->
            " exception=${error::class.java.simpleName} message=${error.message.orEmpty()}"
        } ?: "")
    if (event.throwable == null) {
        Log.i("AstroPhotoPostCompletion", message)
    } else {
        Log.e("AstroPhotoPostCompletion", message, event.throwable)
    }
}
