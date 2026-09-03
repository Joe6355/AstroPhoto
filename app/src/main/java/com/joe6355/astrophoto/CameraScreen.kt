package com.joe6355.astrophoto

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.joe6355.astrophoto.ui.theme.AstroColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun CameraScreen(
    onBackToDiagnostics: () -> Unit,
    onOpenHelp: (HelpTopic) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val previewViewHolder = remember { arrayOfNulls<CameraPreviewView>(1) }
    val settingsStore = remember {
        CameraSettingsStore(context.applicationContext)
    }
    val sessionStore = remember {
        ShootingSessionStore(context.applicationContext)
    }
    val testShotProcessor = remember {
        TestShotProcessor(context.applicationContext)
    }
    val storageChecker = remember {
        StorageSpaceChecker(context.applicationContext)
    }
    val savedSettings = remember { settingsStore.load() }
    var currentSession by remember { mutableStateOf(sessionStore.load()) }
    var sessionDialogVisible by remember { mutableStateOf(false) }
    var sessionNameInput by remember { mutableStateOf("") }
    var sessionNoteInput by remember { mutableStateOf("") }
    var saveLocationStatus by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tapFocusEvent by remember { mutableStateOf<TapFocusEvent?>(null) }
    var captureStatus by remember { mutableStateOf<String?>(null) }
    var lastManualCaptureResult by remember { mutableStateOf<ManualCaptureResult?>(null) }
    var pendingCaptureType by remember { mutableStateOf<UiCaptureType?>(null) }
    var pendingSeriesStart by remember { mutableStateOf(false) }
    var pendingDarkFramesStart by remember { mutableStateOf(false) }
    var exposureWarning by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    val controls = remember { CameraControlsState(savedSettings) }
    var capabilities by controls.capabilities
    var exposureTimeNs by controls.exposureTimeNs
    var iso by controls.iso
    var focusDistance by controls.focusDistance
    var focusMode by controls.focusMode
    var applyLongExposureToPreview by controls.applyLongExposureToPreview
    var jpegQuality by controls.jpegQuality
    var singleFormat by controls.singleFormat
    var seriesFormat by controls.seriesFormat
    var captureMode by controls.captureMode
    var seriesFrameCount by controls.seriesFrameCount
    var seriesDelaySeconds by controls.seriesDelaySeconds
    var startTimerSeconds by controls.startTimerSeconds
    var astroModeEnabled by controls.astroModeEnabled
    var panelAnchor by rememberCameraPanelAnchor(
        if (savedSettings.panelExpanded) {
            CameraPanelAnchor.EXPANDED
        } else {
            CameraPanelAnchor.COLLAPSED
        }
    )
    var vibrationAfterSeries by remember {
        mutableStateOf(savedSettings.vibrationAfterSeries)
    }
    var soundAfterSeries by remember { mutableStateOf(savedSettings.soundAfterSeries) }
    var histogramEnabled by remember {
        mutableStateOf(savedSettings.histogramEnabled)
    }
    var histogramExpanded by remember { mutableStateOf(false) }
    var liveExposureAnalysis by remember {
        mutableStateOf<ExposureAnalysis?>(null)
    }
    var histogramError by remember { mutableStateOf<String?>(null) }
    var seriesExposureDialogVisible by remember { mutableStateOf(false) }
    var saveTestShots by remember { mutableStateOf(savedSettings.saveTestShots) }
    var testShotRunning by remember { mutableStateOf(false) }
    var testShotStatus by remember { mutableStateOf<String?>(null) }
    var lastTestShot by remember { mutableStateOf<TestShotResult?>(null) }
    var focusFwhmHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var pendingTestShotStart by remember { mutableStateOf(false) }
    var seriesConfirmationVisible by remember { mutableStateOf(false) }
    var shootingGoal by remember {
        mutableStateOf(
            runCatching { ShootingGoal.valueOf(savedSettings.shootingGoal) }
                .getOrDefault(ShootingGoal.STARS)
        )
    }
    var exposureAssistantExpanded by remember { mutableStateOf(false) }
    var selectedPreset by controls.selectedPreset
    var astroDefaultsApplied by controls.astroDefaultsApplied
    val initialSeriesState = remember {
        SeriesCaptureCoordinator.initialize(context.applicationContext)
        SeriesCaptureCoordinator.state.value
    }
    var seriesRunning by remember { mutableStateOf(initialSeriesState?.running == true) }
    var seriesStopRequested by remember { mutableStateOf(false) }
    var seriesCurrentFrame by remember { mutableIntStateOf(initialSeriesState?.current ?: 0) }
    var seriesCompletedFrames by remember { mutableIntStateOf(initialSeriesState?.current ?: 0) }
    var seriesEstimatedEndElapsedMillis by remember {
        mutableLongStateOf(
            initialSeriesState?.takeIf { it.running }
                ?.let { SystemClock.elapsedRealtime() + it.remainingMillis }
                ?: 0L
        )
    }
    var seriesRemainingMillis by remember {
        mutableLongStateOf(initialSeriesState?.remainingMillis ?: 0L)
    }
    var seriesAction by remember { mutableStateOf(initialSeriesState?.status.orEmpty()) }
    var seriesMessage by remember { mutableStateOf(initialSeriesState?.message) }
    var darkFramesFormat by remember { mutableStateOf(UiCaptureType.JPEG) }
    var darkFramesCount by remember { mutableIntStateOf(savedSettings.darkFramesCount) }
    var darkFramesRunning by remember { mutableStateOf(false) }
    var darkFramesStopRequested by remember { mutableStateOf(false) }
    var darkFramesCurrent by remember { mutableIntStateOf(0) }
    var darkFramesCompleted by remember { mutableIntStateOf(0) }
    var darkFramesAction by remember { mutableStateOf("") }
    var darkFramesMessage by remember { mutableStateOf<String?>(null) }
    var darkFramesJob by remember { mutableStateOf<Job?>(null) }
    var storageInfo by remember { mutableStateOf<StorageSpaceInfo?>(null) }
    var storageWarningInfo by remember { mutableStateOf<StorageSpaceInfo?>(null) }
    var storageWarningTarget by remember { mutableStateOf("съёмки") }
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun applyAstroDefaults(capabilities: ManualCameraCapabilities) = controls.applyAstroDefaults(capabilities)
    fun setAstroMode(enabled: Boolean) = controls.setAstroMode(enabled)
    fun applyPreset(preset: CameraPreset) = controls.applyPreset(preset)

    fun sessionMetadata(format: UiCaptureType): SessionCaptureMetadata =
        SessionCaptureMetadata(
            cameraId = capabilities?.cameraId ?: "unknown",
            iso = iso,
            exposureTimeNs = exposureTimeNs,
            focus = formatFocusMode(focusMode, focusDistance),
            selectedFormat = format.name
        )

    fun writeSessionInfo(session: ShootingSession, format: UiCaptureType) {
        val metadata = sessionMetadata(format)
        coroutineScope.launch(Dispatchers.IO) {
            val result = sessionStore.writeSessionInfo(session, metadata)
            if (result.isFailure) {
                withContext(Dispatchers.Main) {
                    saveLocationStatus =
                        "Не удалось обновить session_info: ${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    fun ensureSession(format: UiCaptureType): ShootingSession {
        currentSession?.let { return it }
        val created = sessionStore.create("", "")
        currentSession = created
        writeSessionInfo(created, format)
        return created
    }

    fun recordSessionFrame(dark: Boolean, format: UiCaptureType) {
        val session = currentSession ?: return
        val updated = if (dark) {
            session.copy(darkFrames = session.darkFrames + 1)
        } else {
            session.copy(lightFrames = session.lightFrames + 1)
        }
        currentSession = updated
        sessionStore.save(updated)
        writeSessionInfo(updated, format)
        saveLocationStatus = "Сохранено в ${
            if (dark) "Darks" else "Lights"
        }/${if (format == UiCaptureType.RAW) "RAW" else "JPEG"}"
        coroutineScope.launch {
            val refreshed = storageChecker.readAvailableSpace()
            storageInfo = refreshed.copy(
                estimatedBytes = storageInfo?.estimatedBytes ?: 0L,
                averageFrameBytes = storageInfo?.averageFrameBytes ?: 0L
            )
        }
    }

    fun checkStorageBeforeCapture(
        format: UiCaptureType,
        frameCount: Int,
        targetName: String,
        onApproved: () -> Unit
    ) {
        coroutineScope.launch {
            val checked = storageChecker.estimate(
                session = currentSession,
                format = if (format == UiCaptureType.RAW) {
                    StorageCaptureFormat.RAW
                } else {
                    StorageCaptureFormat.JPEG
                },
                frameCount = frameCount
            )
            storageInfo = checked
            if (checked.availableBytes == null) {
                saveLocationStatus = checked.errorMessage
                onApproved()
            } else if (checked.mayBeInsufficient) {
                storageWarningInfo = checked
                storageWarningTarget = targetName
                pendingStorageAction = onApproved
            } else {
                onApproved()
            }
        }
    }

    LaunchedEffect(
        currentSession?.folderName,
        seriesFormat,
        seriesFrameCount
    ) {
        storageInfo = storageChecker.estimate(
            session = currentSession,
            format = if (seriesFormat == UiCaptureType.RAW) {
                StorageCaptureFormat.RAW
            } else {
                StorageCaptureFormat.JPEG
            },
            frameCount = seriesFrameCount
        )
    }

    LaunchedEffect(Unit) {
        SeriesCaptureCoordinator.state.collectLatest { state ->
            state ?: return@collectLatest
            seriesRunning = state.running
            seriesCurrentFrame = state.current
            seriesCompletedFrames = state.current
            seriesRemainingMillis = state.remainingMillis
            seriesEstimatedEndElapsedMillis = if (state.running) {
                SystemClock.elapsedRealtime() + state.remainingMillis
            } else {
                0L
            }
            seriesAction = state.status
            state.message?.let { seriesMessage = it }
            if (!state.running) {
                seriesStopRequested = false
                currentSession = sessionStore.load()
            }
        }
    }

    LaunchedEffect(capabilities, astroModeEnabled) {
        val cameraCapabilities = capabilities
        if (astroModeEnabled && !astroDefaultsApplied && cameraCapabilities != null) {
            applyAstroDefaults(cameraCapabilities)
        }
    }

    LaunchedEffect(
        exposureTimeNs,
        iso,
        focusDistance,
        focusMode,
        applyLongExposureToPreview,
        singleFormat,
        seriesFormat,
        darkFramesFormat,
        darkFramesCount,
        captureMode,
        seriesFrameCount,
        seriesDelaySeconds,
        startTimerSeconds,
        astroModeEnabled,
        panelAnchor,
        vibrationAfterSeries,
        soundAfterSeries,
        histogramEnabled,
        saveTestShots,
        jpegQuality,
        shootingGoal
    ) {
        settingsStore.saveCameraSettings(
            SavedCameraSettings(
                exposureTimeNs = exposureTimeNs,
                iso = iso,
                focusDistance = focusDistance,
                focusMode = focusMode.name,
                applyLongExposureToPreview = applyLongExposureToPreview,
                singleFormat = singleFormat.name,
                seriesFormat = seriesFormat.name,
                darkFramesFormat = darkFramesFormat.name,
                darkFramesCount = darkFramesCount,
                captureMode = captureMode.name,
                seriesFrameCount = seriesFrameCount,
                seriesDelaySeconds = seriesDelaySeconds,
                startTimerSeconds = startTimerSeconds,
                astroModeEnabled = astroModeEnabled,
                panelExpanded = panelAnchor != CameraPanelAnchor.COLLAPSED,
                vibrationAfterSeries = vibrationAfterSeries,
                soundAfterSeries = soundAfterSeries,
                histogramEnabled = histogramEnabled,
                saveTestShots = saveTestShots,
                jpegQuality = jpegQuality,
                fastPreviewEnabled = !applyLongExposureToPreview,
                themeMode = savedSettings.themeMode,
                deletionProtectionEnabled =
                    savedSettings.deletionProtectionEnabled,
                shootingGoal = shootingGoal.name
            )
        )
    }

    fun startCapture(captureType: UiCaptureType) {
        if (!canStartSingleCapture(
                isCapturing = isCapturing,
                seriesRunning = seriesRunning,
                darkFramesRunning = darkFramesRunning,
                testShotRunning = testShotRunning,
                permissionRequestPending = pendingCaptureType != null
            )
        ) {
            captureStatus = if (isCapturing || pendingCaptureType != null) {
                "Съёмка уже выполняется"
            } else {
                "Сначала остановите активную серию"
            }
            return
        }
        val preview = previewViewHolder[0]
        if (preview == null) {
            captureStatus = "Камера ещё не готова"
            return
        }
        if (captureType == UiCaptureType.JPEG &&
            capabilities?.supportsJpegCapture != true
        ) {
            captureStatus = "JPEG-съёмка недоступна для этой камеры"
            return
        }
        if (captureType == UiCaptureType.RAW &&
            capabilities?.supportsRawCapture != true
        ) {
            captureStatus = "RAW_SENSOR недоступен для этой камеры"
            return
        }

        val session = ensureSession(captureType)
        val relativeDirectory = sessionStore.relativeDirectory(
            session = session,
            dark = false,
            raw = captureType == UiCaptureType.RAW
        )
        isCapturing = true
        captureStatus = if (captureType == UiCaptureType.RAW) {
            "Съёмка RAW..."
        } else {
            "Съёмка..."
        }
        val onResult: (Result<String>) -> Unit = { result ->
            isCapturing = false
            captureStatus = result.fold(
                onSuccess = { fileName ->
                    recordSessionFrame(dark = false, format = captureType)
                    if (captureType == UiCaptureType.RAW) {
                        "DNG сохранён: $fileName"
                    } else {
                        "Фото сохранено: $fileName"
                    }
                },
                onFailure = { error ->
                    "Ошибка съёмки: ${error.message ?: "неизвестная ошибка"}"
                }
            )
        }
        if (captureType == UiCaptureType.RAW) {
            preview.captureRawDng(
                relativeDirectory = relativeDirectory,
                onResult = onResult
            )
        } else {
            preview.captureJpeg(
                relativeDirectory = relativeDirectory,
                onResult = onResult
            )
        }
    }

    fun startSeries() {
        if (seriesRunning || darkFramesRunning || isCapturing || testShotRunning) return
        val preview = previewViewHolder[0]
        if (preview == null) {
            seriesMessage = "Камера ещё не готова"
            return
        }
        if (seriesFormat == UiCaptureType.JPEG &&
            capabilities?.supportsJpegCapture != true
        ) {
            seriesMessage = "JPEG-серия недоступна для этой камеры"
            return
        }
        if (seriesFormat == UiCaptureType.RAW &&
            capabilities?.supportsRawCapture != true
        ) {
            seriesMessage = "Выбранный формат серии недоступен для этой камеры"
            return
        }

        val selectedFormat = seriesFormat
        val selectedFrameCount = seriesFrameCount
        val selectedDelaySeconds = seriesDelaySeconds
        val selectedStartTimerSeconds = startTimerSeconds
        val session = ensureSession(selectedFormat)
        val relativeDirectory = sessionStore.relativeDirectory(
            session = session,
            dark = false,
            raw = selectedFormat == UiCaptureType.RAW
        )
        val seriesPrefix = "AstroSeries_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }"

        val initialDurationMillis = estimatedSeriesDurationMillis(
            exposureTimeNs = exposureTimeNs,
            frameCount = selectedFrameCount,
            delaySeconds = selectedDelaySeconds,
            startTimerSeconds = selectedStartTimerSeconds
        )
        val foregroundStarted = runCatching {
            preview.setExternalCaptureActive(true)
            SeriesCaptureCoordinator.start(
                context = context,
                request = SeriesCaptureRequest(
                    format = selectedFormat.name,
                    frameCount = selectedFrameCount,
                    delaySeconds = selectedDelaySeconds,
                    startTimerSeconds = selectedStartTimerSeconds,
                    exposureTimeNs = exposureTimeNs,
                    iso = iso,
                    focusDistance = focusDistance,
                    focusMode = focusMode.name,
                    jpegQuality = jpegQuality,
                    sessionFolder = session.folderName,
                    relativeDirectory = relativeDirectory,
                    filePrefix = seriesPrefix,
                    vibrationAfterSeries = vibrationAfterSeries,
                    soundAfterSeries = soundAfterSeries
                )
            )
        }
        if (foregroundStarted.isFailure) {
            preview.setExternalCaptureActive(false)
            seriesRunning = false
            seriesMessage = "Не удалось запустить надёжную фоновую серию: ${
                foregroundStarted.exceptionOrNull()?.message ?: "системная ошибка"
            }"
            return
        }
        seriesRunning = true
        seriesStopRequested = false
        seriesCurrentFrame = 0
        seriesCompletedFrames = 0
        seriesEstimatedEndElapsedMillis = SystemClock.elapsedRealtime() + initialDurationMillis
        seriesRemainingMillis = initialDurationMillis
        seriesAction = if (selectedStartTimerSeconds > 0) {
            "Старт через $selectedStartTimerSeconds..."
        } else {
            "Подготовка камеры..."
        }
        seriesMessage = null

    }

    fun startDarkFrames() {
        if (darkFramesRunning || seriesRunning || isCapturing || testShotRunning) return
        if (darkFramesCount == 0) {
            darkFramesMessage = "Dark Frames отключены"
            return
        }
        val preview = previewViewHolder[0]
        if (preview == null) {
            darkFramesMessage = "Камера ещё не готова"
            return
        }
        if (darkFramesFormat == UiCaptureType.JPEG &&
            capabilities?.supportsJpegCapture != true
        ) {
            darkFramesMessage = "JPEG недоступен для этой камеры"
            return
        }
        if (darkFramesFormat == UiCaptureType.RAW &&
            capabilities?.supportsRawCapture != true
        ) {
            darkFramesMessage = "Выбранный формат Dark Frames недоступен"
            return
        }

        val selectedFormat = darkFramesFormat
        val selectedCount = darkFramesCount
        val session = ensureSession(selectedFormat)
        val relativeDirectory = sessionStore.relativeDirectory(
            session = session,
            dark = true,
            raw = selectedFormat == UiCaptureType.RAW
        )
        val extension = if (selectedFormat == UiCaptureType.RAW) "dng" else "jpg"
        val prefix = "DarkFrames_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }"

        darkFramesRunning = true
        darkFramesStopRequested = false
        darkFramesCurrent = 1
        darkFramesCompleted = 0
        darkFramesAction = "Съёмка..."
        darkFramesMessage = null

        darkFramesJob = coroutineScope.launch {
            var failed = false
            try {
                for (frameIndex in 1..selectedCount) {
                    if (darkFramesStopRequested) break

                    darkFramesCurrent = frameIndex
                    darkFramesAction = "Съёмка..."
                    val fileName = "${prefix}_${
                        frameIndex.toString().padStart(3, '0')
                    }.$extension"
                    val result = captureSeriesFrame(
                        preview = preview,
                        format = selectedFormat,
                        fileName = fileName,
                        relativeDirectory = relativeDirectory,
                        exposureTimeNs = exposureTimeNs,
                        onStageChanged = { stage ->
                            darkFramesAction = when (stage) {
                                CameraCaptureStage.CAPTURING -> "Съёмка..."
                                CameraCaptureStage.SAVING -> "Сохранение..."
                            }
                        }
                    )

                    if (result.isFailure) {
                        failed = true
                        darkFramesMessage =
                            "Ошибка Dark Frames: ${
                                result.exceptionOrNull()?.message ?: "кадр не сохранён"
                            }"
                        break
                    }

                    recordSessionFrame(dark = true, format = selectedFormat)
                    darkFramesCompleted = frameIndex
                    if (darkFramesStopRequested) break
                }

                if (!failed) {
                    darkFramesMessage = if (darkFramesStopRequested) {
                        "Dark Frames остановлены: $darkFramesCompleted кадров"
                    } else {
                        "Dark frames готовы: $darkFramesCompleted кадров. " +
                            "Используйте их позже для вычитания шума."
                    }
                }
            } finally {
                darkFramesRunning = false
                darkFramesAction = ""
                darkFramesJob = null
            }
        }
    }

    fun startTestShot() {
        if (testShotRunning || isCapturing || seriesRunning || darkFramesRunning) {
            testShotStatus = "Сначала завершите текущую съёмку"
            return
        }
        val preview = previewViewHolder[0]
        if (preview == null) {
            testShotStatus = "Камера ещё не готова"
            return
        }
        if (capabilities?.supportsJpegCapture != true) {
            testShotStatus = "JPEG-съёмка недоступна для этой камеры"
            return
        }

        val testSession = if (saveTestShots) {
            ensureSession(UiCaptureType.JPEG)
        } else {
            currentSession
        }
        testShotRunning = true
        testShotStatus = "Пробный кадр снимается..."
        preview.captureTestJpeg(
            onStageChanged = { stage ->
                testShotStatus = when (stage) {
                    CameraCaptureStage.CAPTURING ->
                        "Пробный кадр снимается..."
                    CameraCaptureStage.SAVING ->
                        "Анализ пробного кадра..."
                }
            },
            onResult = { captureResult ->
                captureResult.fold(
                    onSuccess = { jpegBytes ->
                        coroutineScope.launch {
                            testShotStatus = "Анализ пробного кадра..."
                            val analysisResult = testShotProcessor.analyze(jpegBytes)
                            if (analysisResult.isFailure) {
                                testShotRunning = false
                                testShotStatus =
                                    analysisResult.exceptionOrNull()?.message
                                        ?: "Не удалось оценить пробный кадр"
                                return@launch
                            }

                            var analyzed = analysisResult.getOrThrow()
                            val previousFwhm = lastTestShot?.starFocus?.medianFwhm
                            val currentFwhm = analyzed.starFocus?.medianFwhm
                            if (previousFwhm != null && currentFwhm != null && previousFwhm > 0f) {
                                analyzed = analyzed.copy(
                                    focusFwhmChangePercent =
                                        (currentFwhm - previousFwhm) / previousFwhm * 100f
                                )
                            }
                            if (saveTestShots && testSession != null) {
                                val saveResult = testShotProcessor.save(
                                    jpegBytes = jpegBytes,
                                    session = testSession,
                                    analyzedAtMillis = analyzed.analyzedAtMillis
                                )
                                if (saveResult.isFailure) {
                                    testShotRunning = false
                                    testShotStatus =
                                        saveResult.exceptionOrNull()?.message
                                            ?: "Не удалось сохранить пробный кадр"
                                    return@launch
                                }
                                analyzed = analyzed.copy(
                                    savedFileName = saveResult.getOrThrow()
                                )
                            }

                            val session = testSession ?: currentSession
                            if (session != null) {
                                val updated = session.copy(
                                    testShots = session.testShots + 1,
                                    lastTestShotStatus = analyzed.status.title,
                                    lastTestShotAtMillis = analyzed.analyzedAtMillis
                                )
                                currentSession = updated
                                sessionStore.save(updated)
                                writeSessionInfo(updated, UiCaptureType.JPEG)
                            }
                            analyzed.starFocus?.medianFwhm?.takeIf { it.isFinite() }?.let { fwhm ->
                                focusFwhmHistory = (focusFwhmHistory + fwhm).takeLast(8)
                            }
                            lastTestShot = analyzed
                            testShotRunning = false
                            testShotStatus =
                                "Пробный кадр: ${analyzed.status.title.lowercase()}"
                        }
                    },
                    onFailure = { error ->
                        testShotRunning = false
                        testShotStatus =
                            "Ошибка пробного кадра: ${
                                error.message ?: "неизвестная ошибка"
                            }"
                    }
                )
            }
        )
    }

    fun adjustTestExposure(brighter: Boolean) {
        val cameraCapabilities = capabilities ?: run {
            testShotStatus = "Диапазоны камеры ещё не получены"
            return
        }
        val isoValues = cameraCapabilities.isoRange
            ?.let { range ->
                (ISO_PRESETS + iso)
                    .distinct()
                    .sorted()
                    .filter { it in range }
            }
            .orEmpty()
        val targetIso = if (brighter) {
            isoValues.firstOrNull { it > iso }
        } else {
            isoValues.lastOrNull { it < iso }
        }
        if (targetIso != null) {
            iso = targetIso
            testShotStatus = "ISO изменён на $targetIso"
            return
        }

        val exposureValues = cameraCapabilities.exposureRangeNs
            ?.let { range ->
                (EXPOSURE_PRESETS.map { it.nanoseconds } + exposureTimeNs)
                    .distinct()
                    .sorted()
                    .filter { it in range }
            }
            .orEmpty()
        val targetExposure = if (brighter) {
            exposureValues.firstOrNull { it > exposureTimeNs }
        } else {
            exposureValues.lastOrNull { it < exposureTimeNs }
        }
        if (targetExposure != null) {
            exposureTimeNs = targetExposure
            testShotStatus =
                "Выдержка изменена: ${formatExposure(targetExposure)}"
        } else {
            testShotStatus = if (brighter) {
                "Более светлое значение недоступно"
            } else {
                "Более тёмное значение недоступно"
            }
        }
    }

    fun applyExposureRecommendation(recommendation: ExposureRecommendation) {
        iso = recommendation.iso
        exposureTimeNs = recommendation.exposureTimeNs
        focusMode = recommendation.focusMode
        if (recommendation.focusMode == CameraFocusMode.INFINITY) {
            focusDistance = 0f
        }
        singleFormat = UiCaptureType.JPEG
        seriesFormat = UiCaptureType.JPEG
        seriesFrameCount = recommendation.frameCount.coerceAtLeast(1)
        startTimerSeconds = recommendation.timerSeconds
        captureMode = UiCaptureMode.SERIES
        testShotStatus = "Рекомендация применена"
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val captureType = pendingCaptureType
        val shouldStartSeries = pendingSeriesStart
        val shouldStartDarkFrames = pendingDarkFramesStart
        val shouldStartTestShot = pendingTestShotStart
        pendingCaptureType = null
        pendingSeriesStart = false
        pendingDarkFramesStart = false
        pendingTestShotStart = false
        if (granted) {
            if (shouldStartTestShot) {
                startTestShot()
            } else if (shouldStartDarkFrames) {
                startDarkFrames()
            } else if (shouldStartSeries) {
                startSeries()
                if (
                    seriesRunning &&
                    histogramEnabled &&
                    liveExposureAnalysis?.status == ExposureStatus.TOO_DARK
                ) {
                    seriesMessage =
                        "Кадр очень тёмный. Можно увеличить ISO или выдержку."
                }
            } else {
                captureType?.let(::startCapture)
            }
        } else {
            captureStatus = "Для сохранения фото нужно разрешение на запись"
            seriesMessage = "Для серии нужно разрешение на запись"
            darkFramesMessage = "Для Dark Frames нужно разрешение на запись"
            if (shouldStartTestShot) {
                testShotStatus =
                    "Для сохранения пробного кадра нужно разрешение на запись"
            }
        }
    }

    fun requestTestShot() {
        if (
            saveTestShots &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !context.hasLegacyStoragePermission()
        ) {
            pendingTestShotStart = true
            storagePermissionLauncher.launch(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            startTestShot()
        }
    }

    fun requestCapture(captureType: UiCaptureType) {
        val proceed: () -> Unit = {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !context.hasLegacyStoragePermission()
            ) {
                pendingCaptureType = captureType
                storagePermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            } else {
                startCapture(captureType)
            }
        }
        if (captureType == UiCaptureType.RAW) {
            checkStorageBeforeCapture(captureType, 1, "RAW-снимка", proceed)
        } else {
            proceed()
        }
    }

    fun proceedSeriesStart() {
        checkStorageBeforeCapture(seriesFormat, seriesFrameCount, "серии") {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !context.hasLegacyStoragePermission()
            ) {
                pendingSeriesStart = true
                storagePermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            } else {
                startSeries()
                if (
                    seriesRunning &&
                    histogramEnabled &&
                    liveExposureAnalysis?.status == ExposureStatus.TOO_DARK
                ) {
                    seriesMessage =
                        "Кадр очень тёмный. Можно увеличить ISO или выдержку."
                }
            }
        }
    }

    fun requestSeriesStartAfterTestCheck() {
        if (
            histogramEnabled &&
            liveExposureAnalysis?.status == ExposureStatus.OVEREXPOSED
        ) {
            seriesExposureDialogVisible = true
            return
        }
        proceedSeriesStart()
    }

    fun requestSeriesStart() {
        if (seriesRunning || darkFramesRunning || isCapturing || testShotRunning) return
        seriesConfirmationVisible = true
    }

    fun requestDarkFramesStart() {
        if (darkFramesCount == 0) {
            darkFramesMessage = "Dark Frames отключены"
            return
        }
        checkStorageBeforeCapture(
            darkFramesFormat,
            darkFramesCount,
            "Dark Frames"
        ) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !context.hasLegacyStoragePermission()
            ) {
                pendingDarkFramesStart = true
                storagePermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            } else {
                startDarkFrames()
            }
        }
    }

    fun requestSeriesStop() {
        if (!seriesRunning || seriesStopRequested) return
        seriesStopRequested = true
        notifyCompletionFeedback(
            context = context,
            vibrationEnabled = vibrationAfterSeries,
            soundEnabled = false,
            completed = false
        )
        seriesMessage = "Остановка после текущего кадра…"
        SeriesCaptureCoordinator.requestStop(context)
    }

    fun requestDarkFramesStop() {
        if (!darkFramesRunning || darkFramesStopRequested) return
        darkFramesStopRequested = true
        darkFramesMessage = "Остановка после текущего dark frame…"
    }

    fun handleBack() {
        if (darkFramesRunning) {
            requestDarkFramesStop()
        } else if (seriesRunning) {
            requestSeriesStop()
        } else if (cameraPanelBackTarget(panelAnchor) != null) {
            panelAnchor = CameraPanelAnchor.COLLAPSED
        } else {
            onBackToDiagnostics()
        }
    }

    BackHandler(onBack = ::handleBack)

    DisposableEffect(context) {
        val activity = context.findComponentActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(context) {
        val activity = context.findComponentActivity()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                darkFramesStopRequested = true
                darkFramesJob?.cancel()
                if (darkFramesRunning) {
                    darkFramesMessage = "Dark Frames остановлены при сворачивании приложения"
                }
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            darkFramesStopRequested = true
            darkFramesJob?.cancel()
        }
    }

    val exposureRecommendation = capabilities?.let {
        buildExposureRecommendation(
            goal = shootingGoal,
            testShot = lastTestShot,
            capabilities = it,
            currentIso = iso,
            currentExposureTimeNs = exposureTimeNs
        )?.forJpegOnlyUi()
    }

    LaunchedEffect(tapFocusEvent) {
        val event = tapFocusEvent ?: return@LaunchedEffect
        if (event.status != TapFocusStatus.FOCUSING) {
            delay(TAP_FOCUS_INDICATOR_VISIBLE_MS)
            if (tapFocusEvent == event) {
                tapFocusEvent = null
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            factory = { context ->
                CameraPreviewView(
                    context = context,
                    onCameraError = { message ->
                        errorMessage = message
                    },
                    onCapabilitiesAvailable = { detectedCapabilities ->
                        capabilities = detectedCapabilities
                        detectedCapabilities.exposureRangeNs?.let { range ->
                            exposureTimeNs = exposureTimeNs.coerceIn(range.first, range.last)
                        }
                        detectedCapabilities.isoRange?.let { range ->
                            iso = iso.coerceIn(range.first, range.last)
                        }
                        val maxFocus = detectedCapabilities.minimumFocusDistance ?: 0f
                        focusDistance = focusDistance.coerceIn(0f, maxFocus)
                    },
                    onExposureAnalysis = { analysis ->
                        liveExposureAnalysis = analysis
                        histogramError = null
                    },
                    onExposureAnalyzerUnavailable = { message ->
                        liveExposureAnalysis = null
                        histogramError = message
                        histogramEnabled = false
                    },
                    onTapFocusEvent = { event ->
                        if (event.status != TapFocusStatus.UNAVAILABLE) {
                            focusMode = CameraFocusMode.AF
                        }
                        tapFocusEvent = event
                    },
                    onManualCaptureResult = { result ->
                        lastManualCaptureResult = result
                    }
                ).also { previewViewHolder[0] = it }
            },
            update = { preview ->
                preview.updateManualParameters(
                    exposureTimeNs = exposureTimeNs,
                    iso = iso,
                    focusDistance = focusDistance,
                    focusMode = focusMode,
                    applyLongExposureToPreview = applyLongExposureToPreview
                )
                preview.setExposureAnalysisEnabled(histogramEnabled)
                preview.setJpegQuality(jpegQuality)
                preview.setExternalCaptureActive(seriesRunning)
            },
            modifier = Modifier.fillMaxSize()
        )

        tapFocusEvent?.let { event ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(
                    x = size.width * event.normalizedX,
                    y = size.height * event.normalizedY
                )
                val color = when (event.status) {
                    TapFocusStatus.FOCUSING -> Color(0xFFFFD54F)
                    TapFocusStatus.FOCUSED -> Color(0xFF66BB6A)
                    TapFocusStatus.FAILED,
                    TapFocusStatus.UNAVAILABLE -> Color(0xFFEF5350)
                }
                val radius = 30.dp.toPx()
                val strokeWidth = 2.dp.toPx()
                val crossHalf = 7.dp.toPx()
                drawCircle(
                    color = color,
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokeWidth)
                )
                drawLine(
                    color = color,
                    start = Offset(center.x - crossHalf, center.y),
                    end = Offset(center.x + crossHalf, center.y),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - crossHalf),
                    end = Offset(center.x, center.y + crossHalf),
                    strokeWidth = strokeWidth
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp),
            color = Color.Black.copy(alpha = 0.62f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = ::handleBack) {
                    Text("Назад", color = MaterialTheme.colorScheme.onSurface)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Серия",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            seriesRunning -> "Кадр $seriesCurrentFrame из $seriesFrameCount"
                            darkFramesRunning -> "Dark $darkFramesCurrent из $darkFramesCount"
                            isCapturing -> captureStatus ?: "Съёмка…"
                            else -> "${formatExposure(exposureTimeNs)} · ISO $iso"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        errorMessage?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 76.dp, start = 20.dp, end = 20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        if (histogramEnabled || histogramError != null) {
            HistogramOverlay(
                analysis = liveExposureAnalysis,
                error = histogramError,
                expanded = histogramExpanded,
                onToggleExpanded = {
                    histogramExpanded = !histogramExpanded
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(top = 76.dp, start = 12.dp)
            )
        }

        val panelSummary = buildString {
            append(UiCaptureType.JPEG.name)
            append(" · ")
            append(formatExposure(exposureTimeNs))
            append(" · ISO ")
            append(iso)
            append(" · ")
            append(formatFocusMode(focusMode, focusDistance))
        }
        CameraSettingsPanel(
            anchor = panelAnchor,
            onAnchorChanged = { panelAnchor = it },
            summary = panelSummary,
            modifier = Modifier.fillMaxSize(),
            collapsedContent = {
                CompactCapturePanel(
                    capabilities = capabilities,
                    isCapturing = isCapturing,
                    captureStatus = captureStatus,
                    seriesRunning = seriesRunning,
                    seriesCurrentFrame = seriesCurrentFrame,
                    seriesFrameCount = seriesFrameCount,
                    seriesCompletedFrames = seriesCompletedFrames,
                    seriesRemainingMillis = seriesRemainingMillis,
                    seriesAction = seriesAction,
                    seriesMessage = seriesMessage,
                    darkFramesRunning = darkFramesRunning,
                    darkFramesCurrent = darkFramesCurrent,
                    darkFramesCount = darkFramesCount,
                    darkFramesCompleted = darkFramesCompleted,
                    darkFramesAction = darkFramesAction,
                    darkFramesMessage = darkFramesMessage,
                    testShotRunning = testShotRunning,
                    testShotStatus = testShotStatus,
                    onSeriesStart = ::requestSeriesStart,
                    onSeriesStop = ::requestSeriesStop,
                    onDarkFramesStop = ::requestDarkFramesStop,
                    modifier = Modifier.fillMaxSize()
                )
            },
            expandedContent = { panelScrollState ->
                ManualControlsPanel(
                    capabilities = capabilities,
                    exposureTimeNs = exposureTimeNs,
                    iso = iso,
                    focusDistance = focusDistance,
                    focusMode = focusMode,
                    applyLongExposureToPreview = applyLongExposureToPreview,
                    isCapturing = isCapturing,
                    exposureWarning = exposureWarning,
                    lastManualCaptureResult = lastManualCaptureResult,
                    seriesFrameCount = seriesFrameCount,
                    seriesDelaySeconds = seriesDelaySeconds,
                    startTimerSeconds = startTimerSeconds,
                    astroModeEnabled = astroModeEnabled,
                    vibrationAfterSeries = vibrationAfterSeries,
                    soundAfterSeries = soundAfterSeries,
                    histogramEnabled = histogramEnabled,
                    saveTestShots = saveTestShots,
                    testShotRunning = testShotRunning,
                    testShotStatus = testShotStatus,
                    lastTestShot = lastTestShot,
                    focusFwhmHistory = focusFwhmHistory,
                    shootingGoal = shootingGoal,
                    exposureRecommendation = exposureRecommendation,
                    exposureAssistantExpanded = exposureAssistantExpanded,
                    currentSessionName = currentSession?.sessionName,
                    saveLocationStatus = saveLocationStatus,
                    storageInfo = storageInfo,
                    seriesRunning = seriesRunning,
                    seriesCurrentFrame = seriesCurrentFrame,
                    seriesCompletedFrames = seriesCompletedFrames,
                    seriesRemainingMillis = seriesRemainingMillis,
                    seriesAction = seriesAction,
                    seriesMessage = seriesMessage,
                    darkFramesCount = darkFramesCount,
                    darkFramesRunning = darkFramesRunning,
                    darkFramesCurrent = darkFramesCurrent,
                    darkFramesCompleted = darkFramesCompleted,
                    darkFramesAction = darkFramesAction,
                    darkFramesMessage = darkFramesMessage,
                    onAstroModeChanged = ::setAstroMode,
                    onFocusModeChanged = {
                        focusMode = it
                        if (it == CameraFocusMode.INFINITY) focusDistance = 0f
                    },
                    onApplyLongExposureToPreviewChanged = {
                        applyLongExposureToPreview = it
                    },
                    onVibrationAfterSeriesChanged = { vibrationAfterSeries = it },
                    onSoundAfterSeriesChanged = { soundAfterSeries = it },
                    onHistogramEnabledChanged = { enabled ->
                        histogramEnabled = enabled
                        liveExposureAnalysis = null
                        histogramError = null
                        if (!enabled) histogramExpanded = false
                    },
                    onSaveTestShotsChanged = { saveTestShots = it },
                    onTestShot = ::requestTestShot,
                    onTestShotDarker = { adjustTestExposure(brighter = false) },
                    onTestShotBrighter = { adjustTestExposure(brighter = true) },
                    onTestShotInfinityFocus = {
                        focusMode = CameraFocusMode.INFINITY
                        focusDistance = 0f
                        testShotStatus = "Фокус установлен на ∞"
                    },
                    onShootingGoalChanged = { shootingGoal = it },
                    onExposureAssistantExpandedChanged = {
                        exposureAssistantExpanded = it
                    },
                    onApplyExposureRecommendation = {
                        exposureRecommendation?.let {
                            applyExposureRecommendation(it)
                        }
                    },
                    onAssistantTestShot = ::requestTestShot,
                    onAssistantStartSeries = {
                        exposureRecommendation?.let {
                            applyExposureRecommendation(it)
                            requestSeriesStart()
                        }
                    },
                    onPresetSelected = { selectedPreset = it },
                    onNewSession = {
                        sessionNameInput = ""
                        sessionNoteInput = ""
                        sessionDialogVisible = true
                    },
                    onFinishSession = {
                        currentSession?.let { session ->
                            writeSessionInfo(session, singleFormat)
                        }
                        sessionStore.clear()
                        currentSession = null
                        saveLocationStatus = "Сессия завершена"
                    },
                    onSeriesFrameCountChanged = { seriesFrameCount = it },
                    onSeriesDelayChanged = { seriesDelaySeconds = it },
                    onStartTimerChanged = { startTimerSeconds = it },
                    onSeriesStart = ::requestSeriesStart,
                    onSeriesStop = ::requestSeriesStop,
                    onDarkFramesCountChanged = { darkFramesCount = it },
                    onDarkFramesStart = ::requestDarkFramesStart,
                    onDarkFramesStop = ::requestDarkFramesStop,
                    onExposureChanged = {
                        exposureWarning = null
                        exposureTimeNs = it
                    },
                    onUnsupportedExposure = { requestedExposure, maximumExposure ->
                        val range = capabilities?.exposureRangeNs
                        val plan = range
                            ?.takeIf { capabilities?.supportsManualSensor == true }
                            ?.let {
                            astroIntegrationPlan(
                                exposureRangeNs = it,
                                targetIntegrationNs = requestedExposure
                            )
                        }
                        if (plan == null) {
                            exposureWarning =
                                "Эта выдержка не поддерживается камерой. " +
                                "Максимум: ${formatExposure(maximumExposure)}."
                        } else {
                            exposureTimeNs = plan.exposurePerFrameNs
                            seriesFrameCount = plan.frameCount
                            seriesFormat = UiCaptureType.JPEG
                            captureMode = UiCaptureMode.SERIES
                            exposureWarning = if (plan.reachesTarget) {
                                "Один кадр ограничен ${formatExposure(maximumExposure)}. " +
                                    "Выбран план накопления: ${plan.frameCount} × " +
                                    "${formatExposure(plan.exposurePerFrameNs)} = " +
                                    "${formatExposure(plan.totalIntegrationNs)}; " +
                                    "после серии выполните stacking."
                            } else {
                                "Лимит 500 кадров не позволяет накопить " +
                                    "${formatExposure(requestedExposure)} при выдержке " +
                                    "${formatExposure(plan.exposurePerFrameNs)}."
                            }
                        }
                    },
                    onIsoChanged = { iso = it },
                    onFocusChanged = { focusDistance = it },
                    onOpenHelp = onOpenHelp,
                    scrollState = panelScrollState,
                    modifier = Modifier.fillMaxSize()
                )
            }
        )

        selectedPreset?.let { preset ->
            PresetDialog(
                preset = preset,
                capabilities = capabilities,
                exposureWarning = presetExposureWarning(
                    preset,
                    if (histogramEnabled) liveExposureAnalysis else null
                ),
                onApply = { applyPreset(preset) },
                onDismiss = { selectedPreset = null }
            )
        }
        if (seriesConfirmationVisible) {
            val expectedDurationMillis = estimatedSeriesDurationMillis(
                exposureTimeNs = exposureTimeNs,
                frameCount = seriesFrameCount,
                delaySeconds = seriesDelaySeconds,
                startTimerSeconds = startTimerSeconds
            )
            AlertDialog(
                onDismissRequest = { seriesConfirmationVisible = false },
                title = { Text("Перед запуском серии") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Ожидаемое время: ≈ ${
                                formatSeriesDuration(expectedDurationMillis)
                            }",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "$seriesFrameCount кадров × ${formatExposure(exposureTimeNs)}"
                        )
                        if (seriesDelaySeconds > 0 || startTimerSeconds > 0) {
                            Text(
                                "Пауза: $seriesDelaySeconds сек · " +
                                    "таймер: $startTimerSeconds сек"
                            )
                        }
                        Text(
                            "Фактическое время может быть больше из-за сохранения " +
                                "и обработки кадров камерой.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            seriesConfirmationVisible = false
                            requestSeriesStartAfterTestCheck()
                        }
                    ) {
                        Text("Начать серию")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { seriesConfirmationVisible = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
        if (seriesExposureDialogVisible) {
            AlertDialog(
                onDismissRequest = {
                    seriesExposureDialogVisible = false
                },
                title = { Text("Предупреждение экспозиции") },
                text = {
                    Text("Есть риск пересвета. Всё равно начать серию?")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            seriesExposureDialogVisible = false
                            proceedSeriesStart()
                        }
                    ) {
                        Text("Начать")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            seriesExposureDialogVisible = false
                        }
                    ) {
                        Text("Отмена")
                    }
                }
            )
        }
        storageWarningInfo?.let { warning ->
            AlertDialog(
                onDismissRequest = {
                    storageWarningInfo = null
                    pendingStorageAction = null
                },
                title = { Text("Может не хватить места для $storageWarningTarget") },
                text = {
                    Text(
                        "Нужно примерно ${formatStorageSize(warning.estimatedBytes)}, " +
                            "свободно ${
                                warning.availableBytes?.let(::formatStorageSize)
                                    ?: "неизвестно"
                            }.${
                                if (warning.criticallyLow) {
                                    "\nСвободного места меньше 500 MB."
                                } else {
                                    ""
                                }
                            }"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val action = pendingStorageAction
                            storageWarningInfo = null
                            pendingStorageAction = null
                            action?.invoke()
                        }
                    ) {
                        Text("Начать всё равно")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            storageWarningInfo = null
                            pendingStorageAction = null
                        }
                    ) {
                        Text("Отмена")
                    }
                }
            )
        }
        if (sessionDialogVisible) {
            SessionDialog(
                name = sessionNameInput,
                note = sessionNoteInput,
                onNameChanged = { sessionNameInput = it },
                onNoteChanged = { sessionNoteInput = it },
                onCreate = {
                    val created = sessionStore.create(
                        name = sessionNameInput,
                        note = sessionNoteInput
                    )
                    currentSession = created
                    saveLocationStatus = "Сессия создана: ${created.sessionName}"
                    writeSessionInfo(created, singleFormat)
                    sessionDialogVisible = false
                },
                onDismiss = { sessionDialogVisible = false }
            )
        }
    }
}

@Composable
internal fun ContextHelpTitle(
    title: String,
    topic: HelpTopic,
    onHelp: (HelpTopic) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = if (large) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = { onHelp(topic) }) {
            Text("?")
        }
    }
}

@Composable
internal fun CameraControlSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
internal fun ManualControlsPanel(
    capabilities: ManualCameraCapabilities?,
    exposureTimeNs: Long,
    iso: Int,
    focusDistance: Float,
    focusMode: CameraFocusMode,
    applyLongExposureToPreview: Boolean,
    isCapturing: Boolean,
    exposureWarning: String?,
    lastManualCaptureResult: ManualCaptureResult?,
    seriesFrameCount: Int,
    seriesDelaySeconds: Int,
    startTimerSeconds: Int,
    astroModeEnabled: Boolean,
    vibrationAfterSeries: Boolean,
    soundAfterSeries: Boolean,
    histogramEnabled: Boolean,
    saveTestShots: Boolean,
    testShotRunning: Boolean,
    testShotStatus: String?,
    lastTestShot: TestShotResult?,
    focusFwhmHistory: List<Float>,
    shootingGoal: ShootingGoal,
    exposureRecommendation: ExposureRecommendation?,
    exposureAssistantExpanded: Boolean,
    currentSessionName: String?,
    saveLocationStatus: String?,
    storageInfo: StorageSpaceInfo?,
    seriesRunning: Boolean,
    seriesCurrentFrame: Int,
    seriesCompletedFrames: Int,
    seriesRemainingMillis: Long,
    seriesAction: String,
    seriesMessage: String?,
    darkFramesCount: Int,
    darkFramesRunning: Boolean,
    darkFramesCurrent: Int,
    darkFramesCompleted: Int,
    darkFramesAction: String,
    darkFramesMessage: String?,
    onAstroModeChanged: (Boolean) -> Unit,
    onFocusModeChanged: (CameraFocusMode) -> Unit,
    onApplyLongExposureToPreviewChanged: (Boolean) -> Unit,
    onVibrationAfterSeriesChanged: (Boolean) -> Unit,
    onSoundAfterSeriesChanged: (Boolean) -> Unit,
    onHistogramEnabledChanged: (Boolean) -> Unit,
    onSaveTestShotsChanged: (Boolean) -> Unit,
    onTestShot: () -> Unit,
    onTestShotDarker: () -> Unit,
    onTestShotBrighter: () -> Unit,
    onTestShotInfinityFocus: () -> Unit,
    onShootingGoalChanged: (ShootingGoal) -> Unit,
    onExposureAssistantExpandedChanged: (Boolean) -> Unit,
    onApplyExposureRecommendation: () -> Unit,
    onAssistantTestShot: () -> Unit,
    onAssistantStartSeries: () -> Unit,
    onPresetSelected: (CameraPreset) -> Unit,
    onNewSession: () -> Unit,
    onFinishSession: () -> Unit,
    onSeriesFrameCountChanged: (Int) -> Unit,
    onSeriesDelayChanged: (Int) -> Unit,
    onStartTimerChanged: (Int) -> Unit,
    onSeriesStart: () -> Unit,
    onSeriesStop: () -> Unit,
    onDarkFramesCountChanged: (Int) -> Unit,
    onDarkFramesStart: () -> Unit,
    onDarkFramesStop: () -> Unit,
    onExposureChanged: (Long) -> Unit,
    onUnsupportedExposure: (Long, Long) -> Unit,
    onIsoChanged: (Int) -> Unit,
    onFocusChanged: (Float) -> Unit,
    onOpenHelp: (HelpTopic) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val exposureRange = capabilities?.exposureRangeNs
    val isoRange = capabilities?.isoRange
    val maxFocusDistance = capabilities?.minimumFocusDistance ?: 0f
    val manualSensorAvailable = capabilities?.supportsManualSensor == true &&
        exposureRange != null &&
        isoRange != null
    val manualFocusAvailable = capabilities?.supportsManualFocus == true &&
        maxFocusDistance > 0f
    val vendorExtendedExposureSelected = capabilities?.usesExtendedExposure == true &&
        capabilities.publicExposureRangeNs?.let { exposureTimeNs > it.last } == true
    val controlsLocked = isCapturing || seriesRunning || darkFramesRunning || testShotRunning
    val availableIsoPresets = cameraIsoPresets(isoRange)
    val availableExposurePresets = remember(exposureRange) {
        val maximumPreset = exposureRange?.let { range ->
            ExposurePreset("Макс. ${formatExposure(range.last)}", range.last)
        }
        (EXPOSURE_PRESETS + listOfNotNull(maximumPreset))
            .distinctBy { it.nanoseconds }
    }
    val minimumIntegrationPlan = exposureRange?.takeIf {
        manualSensorAvailable && it.last < MINIMUM_ASTRO_INTEGRATION_NS
    }?.let {
        astroIntegrationPlan(it, MINIMUM_ASTRO_INTEGRATION_NS)
    }
    var presetsExpanded by remember { mutableStateOf(false) }
    var helpTopic by remember { mutableStateOf<HelpTopic?>(null) }
    val warnings = buildList {
        if (capabilities == null) {
            add("Чтение диапазонов камеры…")
        } else {
            if (!manualSensorAvailable) {
                add("Ручные ISO и выдержка не поддерживаются этой камерой.")
            }
            if (!manualFocusAvailable) {
                add("Ручной фокус не поддерживается этой камерой.")
            }
            if (!capabilities.supportsJpegCapture) {
                add("JPEG-съёмка не поддерживается этой камерой.")
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
            ContextHelpTitle(
                title = "Сессия",
                topic = HelpTopic.SESSIONS,
                onHelp = { helpTopic = it },
                modifier = Modifier.padding(top = 8.dp),
                large = true
            )
            Text(
                text = "Сессия: ${currentSessionName ?: "не выбрана"}",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onNewSession,
                    enabled = !controlsLocked,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Новая сессия")
                }
                TextButton(
                    onClick = onFinishSession,
                    enabled = !controlsLocked && currentSessionName != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Завершить")
                }
            }
            saveLocationStatus?.let { status ->
                Text(
                    text = status,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AstroColors.Success
                )
            }
            Text(
                text = storageInfo?.availableBytes?.let { bytes ->
                    "Свободно: ${formatStorageSize(bytes)}"
                } ?: "Не удалось определить свободное место",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (storageInfo?.criticallyLow == true) {
                    AstroColors.Error
                } else {
                    AstroColors.TextSecondary
                }
            )
            if (storageInfo?.criticallyLow == true) {
                Text(
                    text = "Мало места",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AstroColors.Error
                )
            }
            storageInfo?.takeIf { it.estimatedBytes > 0L }?.let { estimate ->
                Text(
                    text = "Оценка текущей серии: ${
                        formatStorageSize(estimate.estimatedBytes)
                    } — ${
                        if (estimate.mayBeInsufficient) {
                            "может не хватить места"
                        } else {
                            "места должно хватить"
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (estimate.mayBeInsufficient) {
                        AstroColors.Warning
                    } else {
                        AstroColors.Success
                    }
                )
            }

            com.joe6355.astrophoto.ui.AstroExpandableSection(
                title = "Дополнительно",
                modifier = Modifier.padding(top = 12.dp)
            ) {
            FilterChip(
                selected = astroModeEnabled,
                onClick = { onAstroModeChanged(!astroModeEnabled) },
                label = { Text("Астрорежим") },
                enabled = !controlsLocked,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "Астрорежим: JPEG, ∞, длинная выдержка, серия кадров",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = AstroColors.TextSecondary
            )
            FilterChip(
                selected = histogramEnabled,
                onClick = {
                    onHistogramEnabledChanged(!histogramEnabled)
                },
                label = { Text("Гистограмма") },
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Лёгкий анализ яркости live preview.",
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = AstroColors.TextSecondary
            )
            FilterChip(
                selected = saveTestShots,
                onClick = {
                    onSaveTestShotsChanged(!saveTestShots)
                },
                label = { Text("Сохранять пробные кадры") },
                enabled = !testShotRunning,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(
                onClick = onTestShot,
                enabled = !isCapturing &&
                    !controlsLocked &&
                    capabilities?.supportsJpegCapture == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(top = 8.dp)
            ) {
                Text(
                    if (testShotRunning) {
                        "Пробный кадр снимается..."
                    } else {
                        "Пробный кадр"
                    }
                )
            }
            TestShotResultCard(
                result = lastTestShot,
                focusFwhmHistory = focusFwhmHistory,
                statusMessage = testShotStatus,
                running = testShotRunning,
                onDarker = onTestShotDarker,
                onBrighter = onTestShotBrighter,
                onInfinityFocus = onTestShotInfinityFocus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            ExposureAssistantCard(
                goal = shootingGoal,
                testShot = lastTestShot,
                currentIso = iso,
                currentExposureTimeNs = exposureTimeNs,
                currentFocusMode = focusMode,
                recommendation = exposureRecommendation,
                expanded = exposureAssistantExpanded,
                onExpandedChanged = onExposureAssistantExpandedChanged,
                onGoalChanged = onShootingGoalChanged,
                onApply = onApplyExposureRecommendation,
                onTestShot = onAssistantTestShot,
                onStartSeries = onAssistantStartSeries,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            TextButton(
                onClick = { presetsExpanded = !presetsExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(if (presetsExpanded) "Готовые пресеты ˅" else "Готовые пресеты ˄")
            }
            if (presetsExpanded) {
                CAMERA_PRESETS.forEach { preset ->
                    TextButton(
                        onClick = { onPresetSelected(preset) },
                        enabled = !controlsLocked,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = preset.name,
                                fontWeight = FontWeight.SemiBold,
                                color = AstroColors.TextPrimary
                            )
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = AstroColors.TextSecondary
                            )
                        }
                    }
                }
            }

            Text(
                text = "Завершение серии",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = vibrationAfterSeries,
                    onClick = {
                        onVibrationAfterSeriesChanged(!vibrationAfterSeries)
                    },
                    label = { Text("Вибрация") },
                    enabled = !controlsLocked
                )
                FilterChip(
                    selected = soundAfterSeries,
                    onClick = { onSoundAfterSeriesChanged(!soundAfterSeries) },
                    label = { Text("Звук") },
                    enabled = !controlsLocked
                )
            }
            }

            warnings.forEach { warning ->
                Text(
                    text = warning,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AstroColors.Warning
                )
            }
            if (controlsLocked) {
                Text(
                    text = "Идёт серия, параметры заблокированы",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AstroColors.Warning
                )
            }

            CameraControlSectionTitle("Серия")
            Text(
                text = "Количество кадров",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (SERIES_FRAME_COUNTS + seriesFrameCount).distinct().sorted().forEach { count ->
                    FilterChip(
                        selected = seriesFrameCount == count,
                        onClick = { onSeriesFrameCountChanged(count) },
                        label = { Text(count.toString()) },
                        enabled = !controlsLocked
                    )
                }
            }

            Text(
                text = "Задержка между кадрами",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SERIES_DELAYS_SECONDS.forEach { seconds ->
                    FilterChip(
                        selected = seriesDelaySeconds == seconds,
                        onClick = { onSeriesDelayChanged(seconds) },
                        label = { Text("$seconds сек") },
                        enabled = !controlsLocked
                    )
                }
            }

            Text(
                text = "Таймер старта",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                START_TIMER_SECONDS.forEach { seconds ->
                    FilterChip(
                        selected = startTimerSeconds == seconds,
                        onClick = { onStartTimerChanged(seconds) },
                        label = { Text("$seconds сек") },
                        enabled = !controlsLocked
                    )
                }
            }

            if (seriesRunning) {
                Text(
                    text = if (seriesCurrentFrame > 0) {
                        "Кадр $seriesCurrentFrame из $seriesFrameCount"
                    } else {
                        "Подготовка серии"
                    },
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = seriesAction,
                    modifier = Modifier.padding(top = 4.dp),
                    color = AstroColors.Secondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LinearProgressIndicator(
                        progress = {
                            seriesCompletedFrames.toFloat() /
                                seriesFrameCount.coerceAtLeast(1)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = seriesRemainingLabel(seriesRemainingMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = AstroColors.TextSecondary
                    )
                }
                Button(
                    onClick = onSeriesStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(top = 10.dp)
                ) {
                    Text("Остановить")
                }
            } else {
                Button(
                    onClick = onSeriesStart,
                    enabled = !isCapturing &&
                        !darkFramesRunning &&
                        capabilities?.supportsJpegCapture == true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(top = 12.dp)
                ) {
                    Text("Старт серии")
                }
            }
            seriesMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.startsWith("Ошибка")) {
                        AstroColors.Error
                    } else {
                        AstroColors.Success
                    }
                )
            }

            ContextHelpTitle(
                title = "Тёмные кадры",
                topic = HelpTopic.DARKS,
                onHelp = { helpTopic = it },
                modifier = Modifier.padding(top = 20.dp),
                large = true
            )
            Text(
                text = "Закройте камеру/объектив и не двигайте телефон. " +
                    "Тёмные кадры снимаются с теми же ISO, выдержкой и фокусом.",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = AstroColors.TextSecondary
            )
            Text(
                text = "Количество тёмных кадров",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CameraSettingsStore.DARK_FRAME_COUNT_VALUES.forEach { count ->
                    FilterChip(
                        selected = darkFramesCount == count,
                        onClick = { onDarkFramesCountChanged(count) },
                        label = { Text(count.toString()) },
                        enabled = !controlsLocked
                    )
                }
            }
            if (darkFramesCount == 0) {
                Text(
                    text = "0 — не снимать тёмные кадры",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AstroColors.TextSecondary
                )
            }
            if (darkFramesRunning) {
                Text(
                    text = "Тёмный кадр $darkFramesCurrent из $darkFramesCount",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = darkFramesAction,
                    modifier = Modifier.padding(top = 4.dp),
                    color = AstroColors.Secondary
                )
                LinearProgressIndicator(
                    progress = {
                        darkFramesCompleted.toFloat() / darkFramesCount.coerceAtLeast(1)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Button(
                    onClick = onDarkFramesStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(top = 10.dp)
                ) {
                    Text("Остановить")
                }
            } else {
                Button(
                    onClick = onDarkFramesStart,
                    enabled = !isCapturing &&
                        !seriesRunning &&
                        darkFramesCount > 0 &&
                        capabilities?.supportsJpegCapture == true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(top = 12.dp)
                ) {
                    Text("Снять тёмные кадры")
                }
            }
            darkFramesMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.startsWith("Ошибка")) {
                        AstroColors.Error
                    } else {
                        AstroColors.Success
                    }
                )
            }
            Text(
                text = "Используйте их позже для вычитания шума.",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = AstroColors.TextSecondary
            )

            CameraControlSectionTitle("Экспозиция")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Выдержка: ${formatExposure(exposureTimeNs)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { helpTopic = HelpTopic.EXPOSURE }) {
                    Text("?")
                }
                Text(
                    text = "Листайте →",
                    style = MaterialTheme.typography.bodySmall,
                    color = AstroColors.TextSecondary
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableExposurePresets.forEach { preset ->
                    val supported = exposureRange?.contains(preset.nanoseconds) == true
                    val canAccumulate = manualSensorAvailable &&
                        preset.nanoseconds > exposureRange.last
                    FilterChip(
                        selected = supported && exposureTimeNs == preset.nanoseconds,
                        onClick = {
                            if (supported) {
                                onExposureChanged(preset.nanoseconds)
                            } else {
                                exposureRange?.last?.let { maximum ->
                                    onUnsupportedExposure(preset.nanoseconds, maximum)
                                }
                            }
                        },
                        label = {
                            Text(if (canAccumulate) "Σ ${preset.label}" else preset.label)
                        },
                        enabled = capabilities != null && !controlsLocked,
                        modifier = Modifier.alpha(if (supported || canAccumulate) 1f else 0.45f)
                    )
                }
            }
            minimumIntegrationPlan?.let { plan ->
                Text(
                    text = if (plan.reachesTarget) {
                        "Нативный предел одного кадра: ${formatExposure(plan.exposurePerFrameNs)}. " +
                            "Σ 30 сек автоматически создаст серию ${plan.frameCount} × " +
                            "${formatExposure(plan.exposurePerFrameNs)} для последующего stacking."
                    } else {
                        "Нативный предел одного кадра слишком мал для накопления 30 сек " +
                            "в пределах 500 кадров."
                    },
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AstroColors.TextSecondary
                )
            }
            if (exposureRange != null && exposureRange.first < exposureRange.last) {
                Text(
                    text = "Ручная выдержка",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = exposureSliderFraction(exposureTimeNs, exposureRange),
                    onValueChange = { fraction ->
                        onExposureChanged(
                            exposureFromSliderFraction(fraction, exposureRange)
                        )
                    },
                    valueRange = 0f..1f,
                    enabled = manualSensorAvailable && !controlsLocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatExposure(exposureRange.first),
                        style = MaterialTheme.typography.bodySmall,
                        color = AstroColors.TextSecondary
                    )
                    Text(
                        formatExposure(exposureRange.last),
                        style = MaterialTheme.typography.bodySmall,
                        color = AstroColors.TextSecondary
                    )
                }
            }
            exposureWarning?.let { warning ->
                Text(
                    text = warning,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AstroColors.Warning
                )
            }
            FilterChip(
                selected = applyLongExposureToPreview && !vendorExtendedExposureSelected,
                onClick = {
                    onApplyLongExposureToPreviewChanged(!applyLongExposureToPreview)
                },
                label = {
                    Text("Применять длинную выдержку к preview")
                },
                enabled = !controlsLocked && !vendorExtendedExposureSelected,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (vendorExtendedExposureSelected) {
                Text(
                    text = "${capabilities.extendedExposureProvider ?: "Камера"}: " +
                        "расширенная выдержка применяется только к снимку; " +
                        "preview остаётся быстрым.",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AstroColors.TextSecondary
                )
            } else if (!applyLongExposureToPreview && exposureTimeNs > 1_000_000_000L) {
                Text(
                    text = "Для плавности preview использует автоэкспозицию и авто-ISO. " +
                        "Ручные значения применяются к сохраняемому кадру и показываются " +
                        "в строке «Фактически».",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AstroColors.TextSecondary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ISO $iso",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { helpTopic = HelpTopic.ISO }) {
                    Text("?")
                }
                Text(
                    text = "Листайте →",
                    style = MaterialTheme.typography.bodySmall,
                    color = AstroColors.TextSecondary
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableIsoPresets.forEach { preset ->
                    FilterChip(
                        selected = iso == preset,
                        onClick = {
                            val validRange = isoRange ?: return@FilterChip
                            onIsoChanged(preset.coerceIn(validRange.first, validRange.last))
                        },
                        label = { Text(preset.toString()) },
                        enabled = manualSensorAvailable && !controlsLocked
                    )
                }
            }
            lastManualCaptureResult?.let { result ->
                Text(
                    text = manualCaptureResultLabel(result),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (manualCaptureResultMatchesRequest(result)) {
                        AstroColors.Success
                    } else {
                        AstroColors.Warning
                    }
                )
            }
            if (isoRange != null && isoRange.first < isoRange.last) {
                Text(
                    text = "Ручной ISO",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = isoSliderFraction(iso, isoRange),
                    onValueChange = { value ->
                        onIsoChanged(
                            isoFromSliderFraction(value, isoRange)
                        )
                    },
                    valueRange = 0f..1f,
                    enabled = manualSensorAvailable && !controlsLocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "ISO ${isoRange.first}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AstroColors.TextSecondary
                    )
                    Text(
                        "ISO ${isoRange.last}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AstroColors.TextSecondary
                    )
                }
                capabilities.sensorIsoRange?.last
                    ?.takeIf { sensorMaximum -> isoRange.last > sensorMaximum }
                    ?.let { sensorMaximum ->
                        Text(
                            text = "ISO выше $sensorMaximum использует " +
                                "дополнительное цифровое усиление Camera2.",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = AstroColors.TextSecondary
                        )
                    }
            }

            CameraControlSectionTitle("Фокус")
            ContextHelpTitle(
                title = "Фокус: ${formatFocusMode(focusMode, focusDistance)}",
                topic = HelpTopic.INFINITY_FOCUS,
                onHelp = { helpTopic = it },
                modifier = Modifier.padding(top = 16.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = focusMode == CameraFocusMode.AF,
                    onClick = { onFocusModeChanged(CameraFocusMode.AF) },
                    label = { Text("AF") },
                    enabled = !controlsLocked
                )
                FilterChip(
                    selected = focusMode == CameraFocusMode.MF,
                    onClick = { onFocusModeChanged(CameraFocusMode.MF) },
                    label = { Text("MF") },
                    enabled = !controlsLocked && manualFocusAvailable
                )
                FilterChip(
                    selected = focusMode == CameraFocusMode.INFINITY,
                    onClick = { onFocusModeChanged(CameraFocusMode.INFINITY) },
                    label = { Text("∞") },
                    enabled = !controlsLocked && manualFocusAvailable
                )
            }
            if (manualFocusAvailable && focusMode == CameraFocusMode.MF) {
                Slider(
                    value = focusDistance.coerceIn(0f, maxFocusDistance),
                    onValueChange = {
                        onFocusChanged(it.coerceIn(0f, maxFocusDistance))
                    },
                    valueRange = 0f..maxFocusDistance,
                    enabled = !controlsLocked,
                    modifier = Modifier.fillMaxWidth()
                )
            }
    }
    helpTopic?.let { topic ->
        HelpTopicDialog(
            topic = topic,
            onOpenHelp = onOpenHelp,
            onDismiss = { helpTopic = null }
        )
    }
}

@Composable
internal fun CompactCapturePanel(
    capabilities: ManualCameraCapabilities?,
    seriesFrameCount: Int,
    isCapturing: Boolean,
    captureStatus: String?,
    seriesRunning: Boolean,
    seriesCurrentFrame: Int,
    seriesCompletedFrames: Int,
    seriesRemainingMillis: Long,
    seriesAction: String,
    seriesMessage: String?,
    darkFramesRunning: Boolean,
    darkFramesCurrent: Int,
    darkFramesCount: Int,
    darkFramesCompleted: Int,
    darkFramesAction: String,
    darkFramesMessage: String?,
    testShotRunning: Boolean,
    testShotStatus: String?,
    onSeriesStart: () -> Unit,
    onSeriesStop: () -> Unit,
    onDarkFramesStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatAvailable = capabilities?.supportsJpegCapture == true

    Column(
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when {
                darkFramesRunning -> {
                    Text(
                        text = "Тёмный кадр $darkFramesCurrent из $darkFramesCount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = darkFramesAction,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = {
                            darkFramesCompleted.toFloat() /
                                darkFramesCount.coerceAtLeast(1)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                    Button(
                        onClick = onDarkFramesStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Text("Остановить")
                    }
                }

                seriesRunning -> {
                    Text(
                        text = if (seriesCurrentFrame > 0) {
                            "Кадр $seriesCurrentFrame из $seriesFrameCount"
                        } else {
                            seriesAction
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (seriesCurrentFrame > 0) {
                        Text(
                            text = seriesAction,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = {
                                seriesCompletedFrames.toFloat() /
                                    seriesFrameCount.coerceAtLeast(1)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = seriesRemainingLabel(seriesRemainingMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onSeriesStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Text("Остановить")
                    }
                }

                isCapturing -> {
                    Text(
                        text = captureStatus ?: "Съёмка...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }

                testShotRunning -> {
                    Text(
                        text = testShotStatus ?: "Пробный кадр снимается...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }

                else -> {
                    Button(
                        onClick = onSeriesStart,
                        enabled = formatAvailable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag(com.joe6355.astrophoto.ui.AstroTestTags.CameraCapture)
                    ) {
                        Text("Старт серии")
                    }
                    val status = darkFramesMessage ?: seriesMessage
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (it.startsWith("Ошибка")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                }
            }
    }
}

@Composable
internal fun PresetDialog(
    preset: CameraPreset,
    capabilities: ManualCameraCapabilities?,
    exposureWarning: String?,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val adaptedPreset = capabilities?.let { adaptCameraPreset(preset, it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preset.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(preset.description)
                exposureWarning?.let { warning ->
                    Text(
                        text = warning,
                        modifier = Modifier.padding(top = 10.dp),
                        color = AstroColors.Warning,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                adaptedPreset?.let { adapted ->
                    Text(
                        text = "ISO ${adapted.iso} • ${formatExposure(adapted.exposureTimeNs)}",
                        modifier = Modifier.padding(top = 12.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "JPEG • ${formatFocusMode(adapted.focusMode, 0f)}" +
                            " • ${adapted.frameCount} кадров" +
                            " • пауза ${adapted.delaySeconds} сек",
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (adapted.wasAdapted) {
                        Text(
                            text = "Часть значений адаптирована под ваш телефон.",
                            modifier = Modifier.padding(top = 10.dp),
                            color = AstroColors.Warning
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApply,
                enabled = adaptedPreset != null
            ) {
                Text("Применить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

internal fun presetExposureWarning(
    preset: CameraPreset,
    analysis: ExposureAnalysis?
): String? {
    val brightScene = analysis?.status == ExposureStatus.OVEREXPOSED ||
        analysis?.status == ExposureStatus.TOO_BRIGHT
    if (!brightScene) return null
    return when (preset.name) {
        "Звёзды максимум" ->
            "Возможно пересвет. Попробуйте пресет «Городское небо» " +
                "или уменьшите ISO."
        "Если ничего не видно" ->
            "Этот пресет может пересветить кадр."
        else -> if (
            preset.iso >= 800 &&
            preset.exposureTimeNs >= 5_000_000_000L
        ) {
            "Live preview уже выглядит ярким. Этот пресет может дать пересвет."
        } else {
            null
        }
    }
}

@Composable
internal fun SessionDialog(
    name: String,
    note: String,
    onNameChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая сессия") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChanged,
                    label = { Text("Имя: Orion, Moon, CitySky…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChanged,
                    label = { Text("Заметка, необязательно") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
                Text(
                    text = "Если имя пустое, оно будет создано автоматически.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AstroColors.TextSecondary
                )
            }
        },
        confirmButton = {
            Button(onClick = onCreate) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
internal fun WarningCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AstroColors.WarningSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Обратите внимание",
                fontWeight = FontWeight.Bold,
                color = AstroColors.Warning
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 6.dp),
                color = AstroColors.Warning
            )
        }
    }
}

@Composable
internal fun DiagnosticCard(row: DiagnosticRow) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = AstroColors.TextPrimary
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = row.name,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AstroColors.TextPrimary
            )
            Text(
                text = row.value,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                softWrap = true,
                color = when (row.isSupported) {
                    true -> AstroColors.Success
                    false -> AstroColors.Error
                    null -> AstroColors.Secondary
                }
            )
            Text(
                text = row.description,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = AstroColors.TextSecondary
            )
        }
    }
}

internal enum class UiCaptureType {
    JPEG,
    RAW
}

internal enum class UiCaptureMode {
    SINGLE,
    SERIES
}

internal fun manualCaptureResultMatchesRequest(result: ManualCaptureResult): Boolean {
    val exposureMatches = result.requestedExposureTimeNs != null &&
        result.actualExposureTimeNs != null &&
        !materiallyLowerThanRequested(
            result.requestedExposureTimeNs,
            result.actualExposureTimeNs
        )
    val isoMatches = result.requestedIso != null &&
        result.actualIso != null &&
        !materiallyLowerThanRequested(result.requestedIso, result.actualIso) &&
        !materiallyHigherThanRequested(result.requestedIso, result.actualIso)
    return exposureMatches && isoMatches
}

internal fun manualCaptureResultLabel(result: ManualCaptureResult): String {
    val requestedExposure = result.requestedExposureTimeNs?.let(::formatExposure) ?: "—"
    val actualExposure = result.actualExposureTimeNs?.let(::formatExposure) ?: "—"
    val requestedIso = result.requestedIso?.toString() ?: "—"
    val actualIso = result.actualIso?.toString() ?: "—"
    return "Фактически: $actualExposure, ISO $actualIso · " +
        "запрошено: $requestedExposure, ISO $requestedIso"
}

internal val SERIES_DELAYS_SECONDS = listOf(0, 1, 2, 5)
internal val START_TIMER_SECONDS = listOf(0, 3, 5, 10)

internal suspend fun captureSeriesFrame(
    preview: CameraPreviewView,
    format: UiCaptureType,
    fileName: String,
    relativeDirectory: String,
    exposureTimeNs: Long,
    onStageChanged: (CameraCaptureStage) -> Unit
): Result<String> = try {
    withTimeout(seriesFrameTimeoutMillis(exposureTimeNs)) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                preview.cancelActiveCapture("Кадр отменён")
            }
            val onResult: (Result<String>) -> Unit = { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            if (format == UiCaptureType.RAW) {
                preview.captureRawDng(
                    fileName = fileName,
                    relativeDirectory = relativeDirectory,
                    onStageChanged = onStageChanged,
                    onResult = onResult
                )
            } else {
                preview.captureJpeg(
                    fileName = fileName,
                    relativeDirectory = relativeDirectory,
                    onStageChanged = onStageChanged,
                    onResult = onResult
                )
            }
        }
    }
} catch (_: TimeoutCancellationException) {
    preview.cancelActiveCapture("Камера не завершила кадр вовремя")
    Result.failure(IllegalStateException("Камера не завершила кадр вовремя"))
}

internal fun seriesFrameTimeoutMillis(exposureTimeNs: Long): Long {
    val exposureMillis = (exposureTimeNs.coerceAtLeast(0L) / 1_000_000L)
        .coerceAtMost(10 * 60_000L)
    val overhead = maxOf(30_000L, exposureMillis / 2L)
    return (exposureMillis + overhead).coerceAtMost(15 * 60_000L)
}

internal data class ExposurePreset(
    val label: String,
    val nanoseconds: Long
)

internal val EXPOSURE_PRESETS = listOf(
    ExposurePreset("1/1000 сек", 1_000_000L),
    ExposurePreset("1/250 сек", 4_000_000L),
    ExposurePreset("1/60 сек", 16_666_667L),
    ExposurePreset("1/30 сек", 33_333_333L),
    ExposurePreset("1 сек", 1_000_000_000L),
    ExposurePreset("2 сек", 2_000_000_000L),
    ExposurePreset("5 сек", 5_000_000_000L),
    ExposurePreset("10 сек", 10_000_000_000L),
    ExposurePreset("15 сек", 15_000_000_000L),
    ExposurePreset("30 сек", 30_000_000_000L),
    ExposurePreset("33 сек", 33_000_000_000L),
    ExposurePreset("45 сек", 45_000_000_000L),
    ExposurePreset("60 сек", 60_000_000_000L),
    ExposurePreset("90 сек", 90_000_000_000L),
    ExposurePreset("120 сек", 120_000_000_000L)
)

internal val ISO_PRESETS = listOf(
    50,
    100,
    200,
    400,
    800,
    1600,
    3200,
    6400,
    12800,
    25600,
    51200,
    102400
)

internal fun cameraIsoPresets(supportedRange: IntRange?): List<Int> {
    supportedRange ?: return emptyList()
    return (ISO_PRESETS + supportedRange.first + supportedRange.last)
        .distinct()
        .sorted()
        .filter { it in supportedRange }
}

internal fun isoSliderFraction(
    iso: Int,
    supportedRange: IntRange
): Float {
    val minimum = supportedRange.first.coerceAtLeast(1)
    val maximum = supportedRange.last.coerceAtLeast(minimum)
    if (minimum == maximum) return 0f
    val current = iso.coerceIn(minimum, maximum)
    return ((ln(current.toDouble()) - ln(minimum.toDouble())) /
        (ln(maximum.toDouble()) - ln(minimum.toDouble())))
        .toFloat()
        .coerceIn(0f, 1f)
}

internal fun isoFromSliderFraction(
    fraction: Float,
    supportedRange: IntRange
): Int {
    val minimum = supportedRange.first.coerceAtLeast(1)
    val maximum = supportedRange.last.coerceAtLeast(minimum)
    if (minimum == maximum) return minimum
    val position = fraction.coerceIn(0f, 1f).toDouble()
    val value = exp(
        ln(minimum.toDouble()) +
            (ln(maximum.toDouble()) - ln(minimum.toDouble())) * position
    ).roundToInt()
    return value.coerceIn(minimum, maximum)
}

internal fun exposureSliderFraction(
    exposureTimeNs: Long,
    supportedRange: LongRange
): Float {
    val minimum = supportedRange.first.coerceAtLeast(1L)
    val maximum = supportedRange.last.coerceAtLeast(minimum)
    if (minimum == maximum) return 0f
    val current = exposureTimeNs.coerceIn(minimum, maximum)
    return ((ln(current.toDouble()) - ln(minimum.toDouble())) /
        (ln(maximum.toDouble()) - ln(minimum.toDouble())))
        .toFloat()
        .coerceIn(0f, 1f)
}

internal fun exposureFromSliderFraction(
    fraction: Float,
    supportedRange: LongRange
): Long {
    val minimum = supportedRange.first.coerceAtLeast(1L)
    val maximum = supportedRange.last.coerceAtLeast(minimum)
    if (minimum == maximum) return minimum
    val position = fraction.coerceIn(0f, 1f).toDouble()
    val value = exp(
        ln(minimum.toDouble()) +
            (ln(maximum.toDouble()) - ln(minimum.toDouble())) * position
    ).roundToLong()
    return value.coerceIn(minimum, maximum)
}

internal fun formatExposure(nanoseconds: Long): String {
    EXPOSURE_PRESETS.firstOrNull { it.nanoseconds == nanoseconds }?.let {
        return it.label
    }
    val seconds = nanoseconds / 1_000_000_000.0
    return if (seconds >= 1.0) {
        String.format(Locale.US, "%.2f сек", seconds).trimTrailingZeros()
    } else {
        String.format(Locale.US, "%.6f сек", seconds).trimTrailingZeros()
    }
}

internal fun formatFocus(distance: Float): String =
    if (distance == 0f) "∞" else String.format(Locale.US, "%.1f дптр", distance)

internal fun formatFocusMode(mode: CameraFocusMode, distance: Float): String = when (mode) {
    CameraFocusMode.AF -> "AF"
    CameraFocusMode.MF -> formatFocus(distance)
    CameraFocusMode.INFINITY -> "∞"
}

internal fun String.trimTrailingZeros(): String =
    replace(Regex("""(\.\d*?[1-9])0+(?= сек)"""), "$1")
        .replace(".00 сек", " сек")

internal fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

internal fun Context.hasLegacyStoragePermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

internal fun Context.findComponentActivity(): ComponentActivity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is ComponentActivity) return currentContext
        currentContext = currentContext.baseContext
    }
    return currentContext as? ComponentActivity
}

@Suppress("DEPRECATION")
internal fun notifyCompletionFeedback(
    context: Context,
    vibrationEnabled: Boolean,
    soundEnabled: Boolean,
    completed: Boolean
) {
    if (vibrationEnabled) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        if (completed) 200L else 80L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            }
        }.onSuccess {
            Log.d("AstroPhotoFeedback", "Completion vibration played")
        }.onFailure { error ->
            Log.e("AstroPhotoFeedback", "Vibration unavailable", error)
        }
    }

    if (soundEnabled && completed) {
        runCatching {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            Handler(Looper.getMainLooper()).postDelayed(
                { toneGenerator.release() },
                240L
            )
        }.onSuccess {
            Log.d("AstroPhotoFeedback", "Completion tone played")
        }.onFailure { error ->
            Log.e("AstroPhotoFeedback", "Tone unavailable", error)
        }
    }
}

internal const val TAP_FOCUS_INDICATOR_VISIBLE_MS = 1_400L
