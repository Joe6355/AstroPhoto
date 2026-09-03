package com.joe6355.astrophoto

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.joe6355.astrophoto.ui.AstroExpandableSection
import com.joe6355.astrophoto.ui.AstroProgressPanel
import com.joe6355.astrophoto.ui.AstroSegmentedControl
import com.joe6355.astrophoto.ui.AstroTestTags
import com.joe6355.astrophoto.ui.theme.AstroColors
import com.joe6355.astrophoto.processing.jpeg.v2.completion.AutomaticProfileCompletionCoordinator
import com.joe6355.astrophoto.processing.jpeg.v2.completion.POST_COMPLETION_WARNING
import com.joe6355.astrophoto.processing.jpeg.v2.completion.appendUniqueResult

private enum class JpegStackingMode(val title: String) {
    AVERAGE("Average"),
    AVERAGE_DARK("Average + Dark"),
    MEDIAN("Median"),
    SIGMA("Sigma clipping")
}

private enum class StackAlignmentMode(val title: String) {
    OFF("OFF"),
    SAFE("SAFE"),
    AGGRESSIVE("AGGRESSIVE")
}

private enum class StackProcessingWorkflow(val title: String) {
    QUICK("Быстро"),
    QUALITY("Качество"),
    MANUAL("Ручная")
}

enum class ProcessingUiMode(val title: String) {
    READY("Пресеты"),
    MANUAL("Ручная обработка")
}

@Composable
fun ProcessingModeSelector(
    selected: ProcessingUiMode,
    onSelected: (ProcessingUiMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    AstroSegmentedControl(
        options = ProcessingUiMode.entries,
        selected = selected,
        label = { it.title },
        onSelected = onSelected,
        enabled = enabled,
        modifier = modifier.testTag(AstroTestTags.ProcessingModeTabs)
    )
}

@Composable
fun ProcessingProfileAvailability(
    unavailableReason: String?,
    availableFrames: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = unavailableReason?.let { "Недоступно: $it" }
            ?: "Доступно кадров: $availableFrames",
        modifier = modifier.testTag(AstroTestTags.ProcessingProfileAvailability),
        color = if (unavailableReason == null) {
            AstroColors.Success
        } else {
            AstroColors.Warning
        },
        style = MaterialTheme.typography.bodySmall
    )
}

internal fun processingProfileUnavailableReason(
    profile: AstroProcessingProfile,
    availableFrames: Int,
    sourceError: String?,
    loading: Boolean,
    running: Boolean,
    operationsEnabled: Boolean
): String? = when {
    loading -> "кадры ещё загружаются"
    running -> "обработка уже выполняется"
    !operationsEnabled -> "сначала завершите другую операцию"
    sourceError != null -> sourceError
    availableFrames < profile.minimumFrames ->
        "нужно минимум ${profile.minimumFrames} кадров"
    else -> null
}

internal fun canStartProcessing(running: Boolean, jobActive: Boolean): Boolean =
    !running && !jobActive

private fun StackProcessingWorkflow.displayTitle(): String = when (this) {
    StackProcessingWorkflow.QUICK -> "\u0411\u044B\u0441\u0442\u0440\u043E"
    StackProcessingWorkflow.QUALITY -> "\u041A\u0430\u0447\u0435\u0441\u0442\u0432\u043E"
    StackProcessingWorkflow.MANUAL -> "\u0420\u0443\u0447\u043D\u0430\u044F"
}

private const val MAX_MEDIAN_FRAMES_UI = 30
private const val MAX_SIGMA_FRAMES_UI = 30
private const val MAX_PROFILE_FRAMES_UI = 30


private data class PendingProfileContinuation(
    val profile: AstroProcessingProfile,
    val acceptedFrames: Int,
    val totalFrames: Int,
    val minimumFrames: Int
)

@Composable
fun JpegStackingBlock(
    session: SessionSummary,
    refreshKey: Int,
    onStackCompleted: () -> Unit,
    onResultReady: (String) -> Unit = {},
    onOpenHelp: (HelpTopic) -> Unit = {},
    onOpenResults: () -> Unit = {},
    operationsEnabled: Boolean = true,
    onOperationStateChanged: (Boolean) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val framesRepository = remember {
        SessionFramesRepository(context.applicationContext)
    }
    val cropsRepository = remember { CroppedFramesRepository(context.applicationContext) }
    val marksStore = remember { FrameMarksStore(context.applicationContext) }
    val stacker = remember { JpegStacker(context.applicationContext) }
    val rawStacker = remember { RawStacker(context.applicationContext) }
    val rawSidecarStore = remember { AstroRawSidecarStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    val processingStates by SessionProcessingCoordinator.states.collectAsState()
    val sessionProcessingState = processingStates[session.folderName]

    var frames by remember(session.folderName) {
        mutableStateOf<List<SessionFrame>>(emptyList())
    }
    var marks by remember(session.folderName) { mutableStateOf(FrameMarks()) }
    var cropManifest by remember(session.folderName) { mutableStateOf(CropManifest()) }
    var stackingSource by remember(session.folderName) {
        mutableStateOf(ManualStackingSource.ORIGINAL)
    }
    var loading by remember(session.folderName) { mutableStateOf(true) }
    var favoritesOnly by remember(session.folderName) { mutableStateOf(false) }
    var stackingMode by remember(session.folderName) {
        mutableStateOf(JpegStackingMode.AVERAGE)
    }
    var alignmentMode by remember(session.folderName) {
        mutableStateOf(StackAlignmentMode.SAFE)
    }
    var workflow by remember(session.folderName) {
        mutableStateOf(StackProcessingWorkflow.QUICK)
    }
    var autoStretchAfterStacking by remember(session.folderName) {
        mutableStateOf(false)
    }
    var sigmaValue by remember(session.folderName) { mutableDoubleStateOf(2.0) }
    var showSigmaConfirmation by remember(session.folderName) {
        mutableStateOf(false)
    }
    var pendingProfileContinuation by remember(session.folderName) {
        mutableStateOf<PendingProfileContinuation?>(null)
    }
    var helpTopic by remember(session.folderName) {
        mutableStateOf<HelpTopic?>(null)
    }
    var shadowOffset by remember(session.folderName) { mutableIntStateOf(16) }
    var stacking by remember(session.folderName) { mutableStateOf(false) }
    var processingJob by remember(session.folderName) { mutableStateOf<Job?>(null) }
    var progressCurrent by remember(session.folderName) { mutableIntStateOf(0) }
    var progressTotal by remember(session.folderName) { mutableIntStateOf(0) }
    var status by remember(session.folderName) { mutableStateOf<String?>(null) }
    var rawStatus by remember(session.folderName) { mutableStateOf<String?>(null) }
    var rawSidecarKeys by remember(session.folderName) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var result by remember(session.folderName) {
        mutableStateOf<JpegStackResult?>(null)
    }
    var profileResults by remember(session.folderName) {
        mutableStateOf<List<JpegStackResult>>(emptyList())
    }
    var manualProcessingExpanded by remember(session.folderName) {
        mutableStateOf(true)
    }
    var processingUiMode by remember(session.folderName) {
        mutableStateOf(ProcessingUiMode.READY)
    }
    var preview by remember(session.folderName) { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember(session.folderName) { mutableStateOf(false) }

    LaunchedEffect(session.folderName, refreshKey) {
        if (!stacking) {
            loading = true
            frames = framesRepository.loadFrames(session)
            rawSidecarKeys = withContext(Dispatchers.IO) {
                frames.asSequence()
                    .filter { it.category == SessionFrameCategory.LIGHTS_RAW }
                    .filter { frame ->
                        rawSidecarStore.openForFrame(session, frame)?.use { true } ?: false
                    }
                    .mapTo(mutableSetOf()) { it.key }
            }
            cropManifest = cropsRepository.loadManifest(session)
            marks = marksStore.loadOrCreate(session)
            loading = false
        }
    }
    LaunchedEffect(sessionProcessingState) {
        sessionProcessingState?.let { state ->
            stacking = state.running
            status = state.status
            progressCurrent = state.current
            progressTotal = state.total
        }
        onOperationStateChanged(sessionProcessingState?.running == true)
    }
    DisposableEffect(Unit) {
        onDispose {
            onOperationStateChanged(
                SessionProcessingCoordinator.isActive(session.folderName)
            )
        }
    }

    val jpegFrames = frames.filter {
        it.category == SessionFrameCategory.LIGHTS_JPEG
    }
    val rawFrames = frames.filter {
        it.category == SessionFrameCategory.LIGHTS_RAW
    }
    val excludedLightKeys = marks.bad + marks.autoBad
    val badFrames = jpegFrames.filter { it.key in excludedLightKeys }
    val usableRawFrames = rawFrames
        .filter { it.key in rawSidecarKeys }
        .filterNot { it.key in excludedLightKeys }
    val missingRawSidecars = rawFrames.count { it.key !in rawSidecarKeys }
    val eligibleFrames = selectEligibleLightFrames(
        frames = frames,
        marks = marks,
        favoritesOnly = false
    )
    val cropRecords = cropsRepository.records(cropManifest, frames)
    LaunchedEffect(cropRecords.isEmpty()) {
        if (cropRecords.isEmpty()) {
            stackingSource = ManualStackingSource.ORIGINAL
        }
    }
    val sourceSelection = resolveStackingSource(
        originals = jpegFrames,
        crops = cropRecords,
        marks = marks,
        favoritesOnly = favoritesOnly,
        source = stackingSource
    )
    val selectedFrames = (sourceSelection as? StackingSourceSelection.Valid)?.frames.orEmpty()
    val selectedCropEntries = (sourceSelection as? StackingSourceSelection.Valid)?.entries.orEmpty()
    val sourceError = (sourceSelection as? StackingSourceSelection.Invalid)?.message
    val darkFrames = frames.filter {
        it.category == SessionFrameCategory.DARKS_JPEG
    }
    val badDarkFrames = darkFrames.filter { it.key in marks.bad }
    val usableDarkFrames = darkFrames.filterNot { it.key in marks.bad }
    val useDarkFrames = stackingMode == JpegStackingMode.AVERAGE_DARK
    val medianMode = stackingMode == JpegStackingMode.MEDIAN
    val sigmaMode = stackingMode == JpegStackingMode.SIGMA
    val alignFrames = alignmentMode != StackAlignmentMode.OFF
    val alignmentSafe = alignmentMode == StackAlignmentMode.SAFE
    val darkCropResult = if (useDarkFrames && stackingSource == ManualStackingSource.CROPPED) {
        runCatching { commonDarkCrop(selectedCropEntries) }
    } else {
        null
    }

    fun notifyProcessingCompleted() {
        val settings = CameraSettingsStore(context.applicationContext).load()
        notifyCompletionFeedback(
            context = context,
            vibrationEnabled = settings.vibrationAfterProcessing,
            soundEnabled = settings.soundAfterProcessing,
            completed = true
        )
    }

    fun applyWorkflow(selectedWorkflow: StackProcessingWorkflow) {
        workflow = selectedWorkflow
        when (selectedWorkflow) {
            StackProcessingWorkflow.QUICK -> {
                stackingMode = JpegStackingMode.AVERAGE
                alignmentMode = StackAlignmentMode.SAFE
                autoStretchAfterStacking = false
                shadowOffset = 16
            }
            StackProcessingWorkflow.QUALITY -> {
                stackingMode = when {
                    usableDarkFrames.isNotEmpty() -> JpegStackingMode.AVERAGE_DARK
                    selectedFrames.size >= 6 -> JpegStackingMode.SIGMA
                    else -> JpegStackingMode.AVERAGE
                }
                alignmentMode = StackAlignmentMode.SAFE
                autoStretchAfterStacking = true
                shadowOffset = 16
            }
            StackProcessingWorkflow.MANUAL -> Unit
        }
    }

    fun reportProcessing(message: String, current: Int, total: Int) {
        status = message
        progressCurrent = current
        progressTotal = total
        SessionProcessingCoordinator.update(session.folderName, message, current, total)
    }

    fun reportProcessing(message: String) {
        status = message
        SessionProcessingCoordinator.update(session.folderName, message)
    }

    fun startStacking() {
        if (!canStartProcessing(
                stacking,
                SessionProcessingCoordinator.isActive(session.folderName)
            )
        ) return
        stacking = true
        progressCurrent = 0
        progressTotal = when {
            useDarkFrames -> usableDarkFrames.size
            medianMode -> minOf(selectedFrames.size, MAX_MEDIAN_FRAMES_UI)
            sigmaMode -> minOf(selectedFrames.size, MAX_SIGMA_FRAMES_UI)
            else -> selectedFrames.size
        }
        result = null
        status = "Подготовка кадров..."
        processingJob = SessionProcessingCoordinator.start(
            context = context,
            sessionFolder = session.folderName,
            label = "JPEG-стеккинг",
            initialStatus = checkNotNull(status),
            initialTotal = progressTotal
        ) {
            try {
                val stackResult = when {
                    sigmaMode -> stacker.sigmaStack(
                        session = session,
                        frames = selectedFrames.take(MAX_SIGMA_FRAMES_UI),
                        sigma = sigmaValue,
                        alignFrames = alignFrames,
                        alignmentSafe = alignmentSafe,
                        autoStretch = autoStretchAfterStacking,
                        source = stackingSource
                    ) { message, current, total ->
                        reportProcessing(message, current, total)
                    }
                    medianMode -> stacker.medianStack(
                        session = session,
                        frames = selectedFrames.take(MAX_MEDIAN_FRAMES_UI),
                        alignFrames = alignFrames,
                        alignmentSafe = alignmentSafe,
                        autoStretch = autoStretchAfterStacking,
                        source = stackingSource
                    ) { message, current, total ->
                        reportProcessing(message, current, total)
                    }
                    useDarkFrames -> stacker.stackWithDarkFrames(
                        session = session,
                        lightFrames = selectedFrames,
                        darkFrames = usableDarkFrames,
                        shadowOffset = shadowOffset,
                        alignFrames = alignFrames,
                        alignmentSafe = alignmentSafe,
                        autoStretch = autoStretchAfterStacking,
                        source = stackingSource,
                        darkCrop = darkCropResult?.getOrNull()
                    ) { message, current, total ->
                        reportProcessing(message, current, total)
                    }
                    else -> stacker.stack(
                        session = session,
                        frames = selectedFrames,
                        alignFrames = alignFrames,
                        alignmentSafe = alignmentSafe,
                        autoStretch = autoStretchAfterStacking,
                        source = stackingSource,
                        onProgress = { current, total ->
                            val message = "Обработка кадра $current из $total"
                            reportProcessing(message, current, total)
                        },
                        onAlignment = { current, total, message ->
                            reportProcessing(message, current, total)
                        }
                    )
                }
                stackResult.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                }
                stackResult.fold(
                    onSuccess = {
                        result = it
                        status = buildString {
                            append("Готово: ${it.fileName}")
                            if (it.additionalFiles.isNotEmpty()) {
                                append("\nДополнительные файлы: ${it.additionalFiles.joinToString()}")
                            }
                            it.manualAlignmentSummary?.let { summary ->
                                append("\nРучное выравнивание: $summary")
                            }
                            if (!it.sessionInfoUpdated) {
                                append(
                                    "\nФайл сохранён, но не удалось " +
                                        "обновить session_info.txt"
                                )
                            }
                            if (it.downscaled) {
                                append(
                                    if (sigmaMode) {
                                        "\nРезультат сигма-клиппинга сохранён в уменьшенном " +
                                            "размере из-за ограничений памяти."
                                    } else {
                                        "\nМедианный результат сохранён в уменьшенном " +
                                            "размере из-за ограничений памяти."
                                    }
                                )
                            }
                        }
                        reportProcessing(checkNotNull(status))
                        notifyProcessingCompleted()
                        onStackCompleted()
                        onResultReady(it.fileName)
                    },
                    onFailure = {
                        Log.e("AstroPhotoProcessing", "Manual processing failed", it)
                        val message = it.message ?: "Не удалось сохранить результат"
                        reportProcessing(message)
                    }
                )
            } catch (error: CancellationException) {
                reportProcessing("Обработка остановлена")
                throw error
            } catch (error: Throwable) {
                Log.e("AstroPhotoProcessing", "Manual processing crashed", error)
                reportProcessing(error.message ?: "Не удалось выполнить обработку")
            } finally {
                stacking = false
                processingJob = null
            }
        }
    }

    fun startRawStacking() {
        if (!canStartProcessing(
                stacking,
                SessionProcessingCoordinator.isActive(session.folderName)
            )
        ) return
        stacking = true
        progressCurrent = 0
        progressTotal = usableRawFrames.size
        result = null
        status = "Подготовка линейных RAW-кадров…"
        rawStatus = status
        processingJob = SessionProcessingCoordinator.start(
            context = context,
            sessionFolder = session.folderName,
            label = "RAW-стеккинг",
            initialStatus = checkNotNull(status),
            initialTotal = progressTotal
        ) {
            try {
                val rawResult = rawStacker.stack(
                    session = session,
                    frames = usableRawFrames
                ) { message, current, total ->
                    reportProcessing(message, current, total)
                    rawStatus = message
                }
                rawResult.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                }
                rawResult.fold(
                    onSuccess = { created ->
                        result = created
                        status = buildString {
                            append("Готово: ${created.fileName}")
                            created.warnings.forEach { append("\n$it") }
                        }
                        rawStatus = status
                        reportProcessing(checkNotNull(status))
                        notifyProcessingCompleted()
                        onStackCompleted()
                        onResultReady(created.fileName)
                    },
                    onFailure = { error ->
                        Log.e("AstroPhotoProcessing", "RAW processing failed", error)
                        reportProcessing(error.message ?: "Не удалось обработать RAW")
                        rawStatus = status
                    }
                )
            } catch (error: CancellationException) {
                reportProcessing("Обработка RAW остановлена")
                rawStatus = status
                throw error
            } catch (error: Throwable) {
                Log.e("AstroPhotoProcessing", "RAW processing crashed", error)
                reportProcessing(error.message ?: "Не удалось обработать RAW")
                rawStatus = status
            } finally {
                stacking = false
                processingJob = null
            }
        }
    }

    fun startProfile(profile: AstroProcessingProfile,
        userApprovedInsufficientFrames: Boolean = false
    ) {
        if (!canStartProcessing(
                stacking,
                SessionProcessingCoordinator.isActive(session.folderName)
            )
        ) return
        val validSource = sourceSelection as? StackingSourceSelection.Valid
        if (validSource == null) {
            status = sourceError ?: "Источник кадров недоступен"
            return
        }
        if (validSource.frames.size < profile.minimumFrames) {
            status = "Для ${profile.title} нужно минимум ${profile.minimumFrames} кадров"
            return
        }
        stacking = true
        progressCurrent = 0
        progressTotal = validSource.frames.size
        status = "Подготовка профиля ${profile.title}..."
        stacker.setUserApprovedInsufficientProfileFrames(userApprovedInsufficientFrames)
        processingJob = SessionProcessingCoordinator.start(
            context = context,
            sessionFolder = session.folderName,
            label = "Профиль ${profile.title}",
            initialStatus = checkNotNull(status),
            initialTotal = progressTotal
        ) {
            var successfulResult: JpegStackResult? = null
            try {
                val profileResult = stacker.profileStack(
                    session = session,
                    frames = validSource.frames,
                    profile = profile,
                    source = stackingSource,
                    framesRejected = badFrames.size + validSource.missingCropCount
                ) { message, current, total ->
                    reportProcessing(message, current, total)
                }
                profileResult.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                }
                profileResult.fold(
                    onSuccess = { created ->
                        successfulResult = created
                        notifyProcessingCompleted()
                        AutomaticProfileCompletionCoordinator(::logPostCompletionEvent).complete(
                            existingResults = profileResults,
                            created = created,
                            totalInputFrames = validSource.frames.size,
                            selectedResultTitle = resultCandidateTitle(created.selectedResultType),
                            updateResults = { updated -> profileResults = updated },
                            updateStatus = { updated -> reportProcessing(updated) },
                            onStackCompleted = onStackCompleted
                        )
                        onResultReady(created.fileName)
                        reportProcessing(checkNotNull(status))
                    },
                    onFailure = { error ->
                        val continuable = error.userContinuableProfileFailure()
                        if (!userApprovedInsufficientFrames && continuable != null) {
                            pendingProfileContinuation = PendingProfileContinuation(
                                profile = profile,
                                acceptedFrames = checkNotNull(continuable.acceptedFrames),
                                totalFrames = checkNotNull(continuable.totalFrames),
                                minimumFrames = checkNotNull(continuable.minimumFrames)
                            )
                            status = "Обработка приостановлена: требуется подтверждение"
                        } else {
                            status = error.message ?: "Профильная обработка не удалась"
                        }
                        reportProcessing(checkNotNull(status))
                    }
                )
            } catch (error: CancellationException) {
                reportProcessing("Обработка остановлена")
                throw error
            } catch (error: Throwable) {
                val created = successfulResult
                Log.e(
                    "AstroPhotoProcessing",
                    "Automatic JPEG profile completion crashed " +
                        "run=${created?.processingRunId?.take(8).orEmpty()} " +
                        "output=${created?.fileName.orEmpty()} " +
                        "exception=${error::class.java.simpleName}",
                    error
                )
                if (created != null) {
                    profileResults = appendUniqueResult(profileResults, created)
                    reportProcessing("Готово: ${created.fileName}\n$POST_COMPLETION_WARNING")
                } else {
                    reportProcessing(error.message ?: "Профильная обработка не удалась")
                }
            } finally {
                stacker.setUserApprovedInsufficientProfileFrames(false)
                stacking = false
                processingJob = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
            if (rawFrames.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "RAW 16-bit — линейный стек",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Баланс чёрного и белого, проявка Bayer, " +
                                "субпиксельное выравнивание и мягкая растяжка.",
                            color = AstroColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Готово к RAW16-обработке: ${usableRawFrames.size} " +
                                "из ${rawFrames.size}"
                        )
                        if (missingRawSidecars > 0) {
                            Text(
                                text = "Без служебного .araw: $missingRawSidecars. " +
                                    "Эти DNG нужно переснять после обновления приложения.",
                                color = AstroColors.Warning,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (usableRawFrames.size < 2) {
                            Text(
                                text = "Нужно минимум два RAW-кадра без отметки «брак».",
                                color = AstroColors.Warning,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = { startRawStacking() },
                            enabled = operationsEnabled && !loading && !stacking &&
                                usableRawFrames.size >= 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                        ) {
                            Text("Сложить RAW в линейном пространстве")
                        }
                        rawStatus?.let { message ->
                            Text(
                                text = message,
                                color = if (
                                    message.startsWith("Готово") ||
                                    message.startsWith("Чтение") ||
                                    message.startsWith("Линейная") ||
                                    message.startsWith("Растяжка") ||
                                    message.startsWith("Подготовка")
                                ) {
                                    AstroColors.TextSecondary
                                } else {
                                    AstroColors.Error
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "JPEG стеккинг",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { helpTopic = HelpTopic.STACKING }) {
                    Text("?")
                }
            }
            if (cropRecords.isNotEmpty()) {
                Text("Источник", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ManualStackingSource.entries.forEach { option ->
                        FilterChip(
                            selected = stackingSource == option,
                            onClick = { stackingSource = option },
                            enabled = !loading && !stacking,
                            label = {
                                Text(
                                    if (option == ManualStackingSource.ORIGINAL) {
                                        "Исходные JPEG"
                                    } else {
                                        "Обрезанные JPEG"
                                    }
                                )
                            }
                        )
                    }
                }
            }
            sourceError?.let { Text(it, color = AstroColors.Error) }

            ProcessingModeSelector(
                selected = processingUiMode,
                onSelected = { processingUiMode = it },
                enabled = !stacking,
                modifier = Modifier.fillMaxWidth()
            )

            if (stacking) {
                AstroProgressPanel(
                    title = "Идёт обработка",
                    step = sessionProcessingState?.statusWithEta ?: status ?: "Подготовка кадров…",
                    progress = if (progressTotal > 0) {
                        progressCurrent.toFloat() / progressTotal.coerceAtLeast(1)
                    } else {
                        null
                    },
                    onCancel = {
                        status = "Остановка обработки…"
                        stacker.cancelProfileRegistration()
                        SessionProcessingCoordinator.cancel(session.folderName)
                    },
                    modifier = Modifier.testTag(AstroTestTags.ProcessingProgress)
                )
            }
            if (!stacking && processingUiMode == ProcessingUiMode.READY) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AstroTestTags.ProcessingReadyModes),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                Text(
                    text = "Автоматическая обработка",
                    style = MaterialTheme.typography.titleMedium
                )
                USER_VISIBLE_PROCESSING_PROFILES
                    .forEach { profile ->
                    val unavailableReason = processingProfileUnavailableReason(
                        profile = profile,
                        availableFrames = selectedFrames.size,
                        sourceError = sourceError,
                        loading = loading,
                        running = stacking,
                        operationsEnabled = operationsEnabled
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(profile.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                profile.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            ProcessingProfileAvailability(
                                unavailableReason = unavailableReason,
                                availableFrames = selectedFrames.size
                            )
                            AstroExpandableSection(title = "Подробнее") {
                                Text(
                                    "Минимум кадров: ${profile.minimumFrames}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Источник: ${stackingSource.metadataValue}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(
                                onClick = { startProfile(profile) },
                                enabled = unavailableReason == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Запустить ${profile.title}")
                            }
                        }
                    }
                }
                }
            }
            if (profileResults.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = AstroColors.SuccessSurface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Созданы результаты",
                            fontWeight = FontWeight.SemiBold
                        )
                        profileResults.forEach { created ->
                            Text(
                                text = "• ${created.fileName}",
                                color = AstroColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            created.additionalFiles.forEach { fileName ->
                                Text(
                                    text = "  + $fileName",
                                    color = AstroColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Text(
                            text = "Откройте раздел «Результаты обработки», чтобы сравнить варианты.",
                            color = AstroColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = onOpenResults,
                            enabled = !stacking,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Открыть результаты")
                        }
                    }
                }
            }

            if (!stacking && processingUiMode == ProcessingUiMode.MANUAL) {
            Card(
                modifier = Modifier.testTag(AstroTestTags.ProcessingManual),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ручная обработка",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Среднее, среднее с тёмным кадром, медиана, сигма-клиппинг и детальные настройки.",
                                color = AstroColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(
                            onClick = {
                                manualProcessingExpanded = !manualProcessingExpanded
                            },
                            enabled = !stacking
                        ) {
                            Text(if (manualProcessingExpanded) "Скрыть" else "Открыть")
                        }
                    }
                    if (manualProcessingExpanded) {
            Text(
                text = "Метод stacking",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StackProcessingWorkflow.entries.forEach { option ->
                    FilterChip(
                        selected = workflow == option,
                        onClick = { applyWorkflow(option) },
                        enabled = !loading && !stacking,
                        label = { Text(option.displayTitle()) }
                    )
                }
            }
            Text(
                text = "\u0411\u044B\u0441\u0442\u0440\u043E: Average + SAFE alignment. \u041A\u0430\u0447\u0435\u0441\u0442\u0432\u043E: \u043C\u044F\u0433\u043A\u0438\u0439 Astro Stretch \u0438 Safe Dark, \u0435\u0441\u043B\u0438 \u0435\u0441\u0442\u044C dark frames.",
                color = AstroColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                JpegStackingMode.entries.chunked(2).forEach { modes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        modes.forEach { mode ->
                            FilterChip(
                                selected = stackingMode == mode,
                                onClick = {
                                    workflow = StackProcessingWorkflow.MANUAL
                                    stackingMode = mode
                                },
                                enabled = !loading && !stacking,
                                label = { Text(mode.title) }
                            )
                        }
                    }
                }
            }

            Text(
                text = "Выравнивание",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StackAlignmentMode.entries.forEach { mode ->
                    FilterChip(
                        selected = alignmentMode == mode,
                        onClick = {
                            workflow = StackProcessingWorkflow.MANUAL
                            alignmentMode = mode
                        },
                        enabled = !loading && !stacking,
                        label = { Text(mode.title) }
                    )
                }
            }
            Text(
                text = "Безопасный режим применяет сдвиг только при уверенном совпадении; усиленный оставлен для ручных экспериментов.",
                color = AstroColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Дополнительная обработка",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Только избранные")
                Switch(
                    checked = favoritesOnly,
                    onCheckedChange = { favoritesOnly = it },
                    enabled = !loading && !stacking
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when {
                        medianMode -> "Выравнивать перед median"
                        sigmaMode -> "Выравнивать перед sigma"
                        else -> "Выравнивать кадры"
                    }
                )
                Switch(
                    checked = alignFrames,
                    onCheckedChange = {
                        workflow = StackProcessingWorkflow.MANUAL
                        alignmentMode = if (it) {
                            StackAlignmentMode.SAFE
                        } else {
                            StackAlignmentMode.OFF
                        }
                    },
                    enabled = !loading && !stacking
                )
            }
            Text(
                text = "Помогает, если телефон немного сдвинулся между кадрами.",
                color = AstroColors.TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Астрономическая растяжка после стеккинга")
                Switch(
                    checked = autoStretchAfterStacking,
                    onCheckedChange = {
                        workflow = StackProcessingWorkflow.MANUAL
                        autoStretchAfterStacking = it
                    },
                    enabled = !loading && !stacking
                )
            }
            Text(
                text = "\u041C\u044F\u0433\u043A\u043E \u0432\u044B\u0442\u044F\u0433\u0438\u0432\u0430\u0435\u0442 \u0441\u043B\u0430\u0431\u044B\u0435 \u0437\u0432\u0451\u0437\u0434\u044B. \u0415\u0441\u043B\u0438 \u043D\u0443\u0436\u0435\u043D \u00AB\u0447\u0438\u0441\u0442\u044B\u0439\u00BB stack, \u043E\u0441\u0442\u0430\u0432\u044C\u0442\u0435 \u0432\u044B\u043A\u043B.",
                color = AstroColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            if (useDarkFrames) {
                Text(
                    text = "Тёмные кадры",
                    style = MaterialTheme.typography.titleMedium
                )
                Text("Найдено тёмных JPEG: ${darkFrames.size}")
                Text("Используется тёмных кадров: ${usableDarkFrames.size}")
                Text("Исключено dark-брака: ${badDarkFrames.size}")
                Text(
                    text = "Компенсация тени",
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 8, 16, 32).forEach { offset ->
                        FilterChip(
                            selected = shadowOffset == offset,
                            onClick = { shadowOffset = offset },
                            enabled = !stacking,
                            label = { Text(offset.toString()) }
                        )
                    }
                }

                if (darkFrames.isEmpty()) {
                    Text(
                        text = "Тёмные кадры не найдены. Можно выполнить обычный " +
                            "stacking без вычитания шума.",
                        color = AstroColors.Warning
                    )
                    Button(
                        onClick = {
                            stackingMode = JpegStackingMode.AVERAGE
                            status = "Выбран обычный JPEG stacking"
                        },
                        enabled = !stacking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Text("Продолжить без тёмных кадров")
                    }
                } else if (usableDarkFrames.isEmpty()) {
                    Text(
                        text = "Все dark frames помечены как брак",
                        color = AstroColors.Error
                    )
                }
            }
            if (medianMode && selectedFrames.size < 3) {
                Text(
                    text = "Для median желательно хотя бы 3 кадра.",
                    color = AstroColors.Warning
                )
            }
            if (medianMode && selectedFrames.size > MAX_MEDIAN_FRAMES_UI) {
                Text(
                    text = "Медианная обработка может быть медленной. Будут использованы " +
                        "первые $MAX_MEDIAN_FRAMES_UI кадров.",
                    color = AstroColors.Warning
                )
            }
            if (sigmaMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Сигма-клиппинг",
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { helpTopic = HelpTopic.STACKING_METHODS }
                    ) {
                        Text("?")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1.5, 2.0, 2.5, 3.0).forEach { value ->
                        FilterChip(
                            selected = sigmaValue == value,
                            onClick = { sigmaValue = value },
                            enabled = !stacking,
                            label = { Text(value.toString()) }
                        )
                    }
                }
                Text(
                    text = "Меньше sigma — агрессивнее удаление выбросов, " +
                        "больше — мягче.",
                    color = AstroColors.TextSecondary
                )
                if (selectedFrames.size < 4) {
                    Text(
                        text = "Для sigma clipping желательно минимум 4 кадра. " +
                            "Лучше используйте Average или Median.",
                        color = AstroColors.Warning
                    )
                }
                if (selectedFrames.size > MAX_SIGMA_FRAMES_UI) {
                    Text(
                        text = "Сигма-клиппинг может быть медленным. Будут " +
                            "использованы первые $MAX_SIGMA_FRAMES_UI кадров.",
                        color = AstroColors.Warning
                    )
                }
            }
            if (!operationsEnabled) {
                Text(
                    text = "Сначала завершите текущую операцию",
                    color = AstroColors.Warning
                )
            }

            Button(
                onClick = {
                    when {
                        sourceError != null -> {
                            status = sourceError
                        }
                        darkCropResult?.isFailure == true -> {
                            status = darkCropResult.exceptionOrNull()?.message
                        }
                        jpegFrames.isNotEmpty() && eligibleFrames.isEmpty() -> {
                            status = "Все кадры помечены как брак"
                        }
                        jpegFrames.size < 2 -> {
                            status = "Недостаточно JPEG кадров для стеккинга"
                        }
                        selectedFrames.size < 2 -> {
                            status = if (favoritesOnly) {
                                "Недостаточно избранных JPEG кадров для стеккинга"
                            } else {
                                "Недостаточно JPEG кадров для стеккинга"
                            }
                        }
                        useDarkFrames && darkFrames.isEmpty() -> {
                            status = "Тёмные кадры не найдены"
                        }
                        useDarkFrames && usableDarkFrames.isEmpty() -> {
                            status = "Все dark frames помечены как брак"
                        }
                        sigmaMode && selectedFrames.size < 4 -> {
                            showSigmaConfirmation = true
                        }
                        else -> {
                            startStacking()
                        }
                    }
                },
                enabled = !loading && !stacking && operationsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text(
                    when {
                        stacking -> "Стеккинг..."
                        sigmaMode && alignFrames -> "Sigma JPEG + Alignment"
                        sigmaMode -> "Sigma JPEG"
                        medianMode && alignFrames -> "Median JPEG + Alignment"
                        medianMode -> "Median JPEG"
                        useDarkFrames -> "Сложить JPEG с тёмными кадрами"
                        else -> "Сложить JPEG"
                    }
                )
            }
                    }
                }
            }
            }

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            status?.takeUnless { stacking }?.let {
                Text(
                    text = it,
                    color = if (
                        it.startsWith("Готово") ||
                        it.startsWith("Обработка") ||
                        it.startsWith("Подготовка") ||
                        it.startsWith("Создание") ||
                        it.startsWith("Сохранение") ||
                        it.startsWith("Выравнивание")
                    ) {
                        AstroColors.Success
                    } else {
                        AstroColors.Error
                    }
                )
            }

            result?.let { stackResult ->
                AstroExpandableSection(title = "Технические сведения") {
                    Text(
                        text = stackResult.displayPath,
                        color = AstroColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    stackResult.masterDarkDisplayPath?.let { masterPath ->
                        Text(
                            text = "Мастер тёмного кадра: $masterPath",
                            color = AstroColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(
                    onClick = {
                        previewLoading = true
                        coroutineScope.launch {
                            preview = stacker.loadResultPreview(stackResult, 1600)
                            previewLoading = false
                            if (preview == null) {
                                status = "Не удалось открыть результат"
                            }
                        }
                    },
                    enabled = !previewLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Text(
                        if (previewLoading) {
                            "Открытие..."
                        } else {
                            "Открыть результат"
                        }
                    )
                }
            }
    }

    if (showSigmaConfirmation) {
        AlertDialog(
            onDismissRequest = { showSigmaConfirmation = false },
            title = { Text("Мало кадров для Sigma clipping") },
            text = {
                Text(
                    "Для sigma clipping желательно минимум 4 кадра. " +
                        "С текущим количеством результат может быть нестабильным. " +
                        "Всё равно продолжить?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSigmaConfirmation = false
                        startStacking()
                    }
                ) {
                    Text("Продолжить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSigmaConfirmation = false }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    pendingProfileContinuation?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pendingProfileContinuation = null
                status = "Продолжение обработки отменено"
            },
            title = { Text("Продолжить с прошедшими кадрами?") },
            text = {
                Text(
                    "Проверку прошли ${pending.acceptedFrames} из ${pending.totalFrames} кадров. " +
                        "Для режима «${pending.profile.title}» обычно нужно минимум " +
                        "${pending.minimumFrames}. Отклонённые кадры не будут использованы. " +
                        "Результат может быть шумнее или содержать меньше деталей."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingProfileContinuation = null
                        startProfile(
                            pending.profile,
                            userApprovedInsufficientFrames = true
                        )
                    }
                ) {
                    Text("Продолжить обработку")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingProfileContinuation = null
                        status = "Продолжение обработки отменено"
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    helpTopic?.let { topic ->
        HelpTopicDialog(
            topic = topic,
            onOpenHelp = onOpenHelp,
            onDismiss = { helpTopic = null }
        )
    }

    preview?.let { bitmap ->
        DisposableEffect(bitmap) {
            onDispose {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        Dialog(
            onDismissRequest = { preview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Результат JPEG стеккинга",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = result?.fileName.orEmpty(),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Button(
                    onClick = { preview = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Text("Назад")
                }
            }
        }
    }
}
