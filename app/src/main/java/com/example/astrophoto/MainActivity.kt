package com.example.astrophoto

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.astrophoto.ui.theme.AstroPhotoTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AstroPhotoTheme(darkTheme = true, dynamicColor = false) {
                AstroPhotoApp()
            }
        }
    }
}

@Composable
private fun AstroPhotoApp() {
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(700L)
        showSplash = false
    }

    if (showSplash) {
        StarSplashScreen()
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val appSettingsStore = remember {
        CameraSettingsStore(context.applicationContext)
    }
    var appSettings by remember { mutableStateOf(appSettingsStore.load()) }
    var showOnboarding by remember {
        mutableStateOf(!appSettingsStore.isOnboardingSeen())
    }
    var currentScreen by remember { mutableStateOf(AppScreen.Diagnostics) }
    val screenBackStack = remember { mutableStateListOf<AppScreen>() }
    var showExitDialog by remember { mutableStateOf(false) }
    var helpReturnScreen by remember { mutableStateOf(AppScreen.Diagnostics) }
    var helpInitialTopic by remember { mutableStateOf<HelpTopic?>(null) }
    var settingsReturnScreen by remember { mutableStateOf(AppScreen.Diagnostics) }
    var aboutReturnScreen by remember { mutableStateOf(AppScreen.Diagnostics) }
    var selfCheckReturnScreen by remember { mutableStateOf(AppScreen.Diagnostics) }
    var selectedSession by remember { mutableStateOf<SessionSummary?>(null) }
    var sessionManagerMessage by remember { mutableStateOf<String?>(null) }
    var cameraPermissionGranted by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var initialRequestSent by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
    }
    val activity = context.findComponentActivity()

    fun navigateTo(screen: AppScreen) {
        if (currentScreen != screen) {
            screenBackStack.add(currentScreen)
            currentScreen = screen
        }
    }

    fun navigateBack() {
        if (currentScreen == AppScreen.Help) {
            helpInitialTopic = null
        }
        if (screenBackStack.isNotEmpty()) {
            currentScreen = screenBackStack.removeAt(screenBackStack.lastIndex)
        } else if (currentScreen != AppScreen.Diagnostics) {
            currentScreen = AppScreen.Diagnostics
        } else {
            showExitDialog = true
        }
    }

    fun navigateToSessionsAfterDelete() {
        screenBackStack.clear()
        screenBackStack.add(AppScreen.Diagnostics)
        currentScreen = AppScreen.Sessions
    }

    BackHandler(enabled = showExitDialog) {
        showExitDialog = false
    }

    BackHandler(
        enabled = (currentScreen != AppScreen.Camera || !cameraPermissionGranted) &&
            !showOnboarding &&
            !showExitDialog
    ) {
        navigateBack()
    }

    LaunchedEffect(cameraPermissionGranted) {
        if (!cameraPermissionGranted && !initialRequestSent) {
            initialRequestSent = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (appSettings.themeMode == AppThemeMode.VERY_DARK.name) {
            Color(0xFF020306)
        } else {
            Color(0xFF080B12)
        },
        contentColor = Color(0xFFF4F6FF)
    ) {
        val screenAvailableWithoutCamera = currentScreen == AppScreen.Diagnostics ||
            currentScreen == AppScreen.DiagnosticsDetails ||
            currentScreen == AppScreen.About ||
            currentScreen == AppScreen.Settings ||
            currentScreen == AppScreen.Help ||
            currentScreen == AppScreen.SelfCheck
        if (cameraPermissionGranted || screenAvailableWithoutCamera) {
            when (currentScreen) {
                AppScreen.Diagnostics -> AstroHomeScreen(
                    onOpenCamera = { navigateTo(AppScreen.Camera) },
                    onOpenSessions = { navigateTo(AppScreen.Sessions) },
                    onOpenSettings = {
                        settingsReturnScreen = AppScreen.Diagnostics
                        navigateTo(AppScreen.Settings)
                    },
                    onOpenHelp = {
                        helpReturnScreen = AppScreen.Diagnostics
                        helpInitialTopic = null
                        navigateTo(AppScreen.Help)
                    },
                    onOpenAbout = {
                        aboutReturnScreen = AppScreen.Diagnostics
                        navigateTo(AppScreen.About)
                    },
                    onOpenSelfCheck = {
                        selfCheckReturnScreen = AppScreen.Diagnostics
                        navigateTo(AppScreen.SelfCheck)
                    }
                )
                AppScreen.DiagnosticsDetails -> CameraDiagnosticsScreen(
                    onBack = { navigateBack() }
                )
                AppScreen.Camera -> CameraScreen(
                    onBackToDiagnostics = { navigateBack() },
                    onOpenHelp = { topic ->
                        helpReturnScreen = AppScreen.Camera
                        helpInitialTopic = topic
                        navigateTo(AppScreen.Help)
                    }
                )
                AppScreen.Sessions -> SessionsScreen(
                    onBack = { navigateBack() },
                    statusMessage = sessionManagerMessage,
                    onOpenDetails = { session ->
                        sessionManagerMessage = null
                        selectedSession = session
                        navigateTo(AppScreen.SessionDetails)
                    }
                )
                AppScreen.SessionDetails -> selectedSession?.let { session ->
                    SessionDetailsScreen(
                        session = session,
                        onBack = { navigateBack() },
                        onActivated = {},
                        onRenamed = { renamed ->
                            selectedSession = renamed
                        },
                        onDeleted = { message ->
                            sessionManagerMessage = message
                            selectedSession = null
                            navigateToSessionsAfterDelete()
                        },
                        onOpenHelp = { topic ->
                            helpReturnScreen = AppScreen.SessionDetails
                            helpInitialTopic = topic
                            navigateTo(AppScreen.Help)
                        }
                    )
                } ?: run {
                    SessionsScreen(
                        onBack = { navigateBack() },
                        statusMessage = sessionManagerMessage,
                        onOpenDetails = { session ->
                            selectedSession = session
                            navigateTo(AppScreen.SessionDetails)
                        }
                    )
                }
                AppScreen.Settings -> AppSettingsScreen(
                    settings = appSettings,
                    onSettingsChanged = { updated ->
                        appSettings = updated
                        appSettingsStore.save(updated)
                    },
                    onReset = {
                        appSettings = appSettingsStore.reset()
                    },
                    onOpenHelp = {
                        helpReturnScreen = AppScreen.Settings
                        helpInitialTopic = null
                        navigateTo(AppScreen.Help)
                    },
                    onShowOnboarding = {
                        showOnboarding = true
                    },
                    onOpenAbout = {
                        aboutReturnScreen = AppScreen.Settings
                        navigateTo(AppScreen.About)
                    },
                    onOpenSelfCheck = {
                        selfCheckReturnScreen = AppScreen.Settings
                        navigateTo(AppScreen.SelfCheck)
                    },
                    onOpenDiagnostics = {
                        navigateTo(AppScreen.DiagnosticsDetails)
                    },
                    onBack = { navigateBack() }
                )
                AppScreen.Help -> HelpScreen(
                    initialTopic = helpInitialTopic,
                    onBack = {
                        navigateBack()
                    }
                )
                AppScreen.About -> AboutScreen(
                    cameraPermissionGranted = cameraPermissionGranted,
                    onRequestCameraPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onOpenHelp = {
                        helpReturnScreen = AppScreen.About
                        helpInitialTopic = null
                        navigateTo(AppScreen.Help)
                    },
                    onOpenSettings = {
                        settingsReturnScreen = AppScreen.About
                        navigateTo(AppScreen.Settings)
                    },
                    onOpenSelfCheck = {
                        selfCheckReturnScreen = AppScreen.About
                        navigateTo(AppScreen.SelfCheck)
                    },
                    onBack = { navigateBack() }
                )
                AppScreen.SelfCheck -> SelfCheckScreen(
                    cameraPermissionGranted = cameraPermissionGranted,
                    onRequestCameraPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onBack = { navigateBack() }
                )
            }
        } else {
            PermissionScreen(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onOpenAbout = {
                    aboutReturnScreen = AppScreen.Diagnostics
                    navigateTo(AppScreen.About)
                }
            )
        }
    }
    if (showOnboarding) {
        OnboardingDialog(
            onFinished = {
                appSettingsStore.setOnboardingSeen(true)
                showOnboarding = false
            }
        )
    }
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Выйти из AstroPhoto?") },
            text = { Text("Нажмите «Выйти», чтобы закрыть приложение, или «Отмена», чтобы остаться.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        activity?.finish()
                    }
                ) {
                    Text("Выйти")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun StarSplashScreen() {
    val transition = rememberInfiniteTransition(label = "stars")
    val glow by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "starGlow"
    )
    val stars = remember {
        listOf(
            0.08f to 0.12f, 0.2f to 0.28f, 0.35f to 0.09f, 0.52f to 0.21f,
            0.7f to 0.1f, 0.88f to 0.26f, 0.12f to 0.55f, 0.3f to 0.72f,
            0.47f to 0.48f, 0.65f to 0.65f, 0.82f to 0.52f, 0.93f to 0.78f,
            0.16f to 0.9f, 0.55f to 0.87f, 0.76f to 0.92f
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            stars.forEachIndexed { index, star ->
                val starAlpha = if (index % 2 == 0) glow else 1f - glow * 0.35f
                drawCircle(
                    color = Color.White.copy(alpha = starAlpha.coerceIn(0.25f, 0.9f)),
                    radius = if (index % 3 == 0) 3.2f else 2f,
                    center = Offset(size.width * star.first, size.height * star.second)
                )
            }
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AstroPhotoLogo(
                modifier = Modifier
                    .size(92.dp)
                    .padding(bottom = 10.dp)
            )
            Text(
                text = "AstroPhoto",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Manual Night Camera",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFB8BECC)
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 22.dp)
                    .size(24.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun PermissionScreen(
    onRequestPermission: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "AstroPhoto",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Доступ к камере",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Для проверки камеры нужно разрешение CAMERA",
            modifier = Modifier.padding(top = 32.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onRequestPermission) {
            Text("Разрешить камеру")
        }
        TextButton(
            onClick = onOpenAbout,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("О приложении")
        }
    }
}

@Composable
private fun CameraDiagnosticsScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember { mutableStateOf<DiagnosticsState>(DiagnosticsState.Loading) }

    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.Default) {
            readCameraDiagnostics(context.applicationContext).fold(
                onSuccess = { DiagnosticsState.Ready(it) },
                onFailure = {
                    DiagnosticsState.Error(
                        it.message ?: "Не удалось прочитать характеристики камеры"
                    )
                }
            )
        }
    }

    when (val currentState = state) {
        DiagnosticsState.Loading -> LoadingScreen()
        is DiagnosticsState.Error -> ErrorScreen(currentState.message)
        is DiagnosticsState.Ready -> DiagnosticsList(
            info = currentState.info,
            onBack = onBack
        )
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(
            text = "Читаем характеристики камеры…",
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun ErrorScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Не удалось выполнить диагностику",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = message,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun DiagnosticsList(
    info: CameraDiagnosticInfo,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 24.dp,
            end = 16.dp,
            bottom = 36.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            com.example.astrophoto.ui.AstroTopBar(
                title = "Диагностика камеры",
                onBack = onBack
            )
        }

        info.warning?.let { warning ->
            item {
                WarningCard(warning)
            }
        }

        items(info.rows) { row ->
            DiagnosticCard(row)
        }
    }
}

@Composable
private fun CameraScreen(
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
    var cameraStatus by remember { mutableStateOf("camera starting") }
    var captureStatus by remember { mutableStateOf<String?>(null) }
    var pendingCaptureType by remember { mutableStateOf<UiCaptureType?>(null) }
    var pendingSeriesStart by remember { mutableStateOf(false) }
    var pendingDarkFramesStart by remember { mutableStateOf(false) }
    var exposureWarning by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var capabilities by remember { mutableStateOf<ManualCameraCapabilities?>(null) }
    var exposureTimeNs by remember { mutableStateOf(savedSettings.exposureTimeNs) }
    var iso by remember { mutableStateOf(savedSettings.iso) }
    var focusDistance by remember { mutableStateOf(savedSettings.focusDistance) }
    var focusMode by remember {
        mutableStateOf(
            runCatching { CameraFocusMode.valueOf(savedSettings.focusMode) }
                .getOrDefault(CameraFocusMode.INFINITY)
        )
    }
    var applyLongExposureToPreview by remember {
        mutableStateOf(!savedSettings.fastPreviewEnabled)
    }
    var jpegQuality by remember { mutableStateOf(savedSettings.jpegQuality) }
    var singleFormat by remember {
        mutableStateOf(
            runCatching { UiCaptureType.valueOf(savedSettings.singleFormat) }
                .getOrDefault(UiCaptureType.JPEG)
        )
    }
    var seriesFormat by remember {
        mutableStateOf(
            runCatching { UiCaptureType.valueOf(savedSettings.seriesFormat) }
                .getOrDefault(UiCaptureType.JPEG)
        )
    }
    var captureMode by remember {
        mutableStateOf(
            runCatching { UiCaptureMode.valueOf(savedSettings.captureMode) }
                .getOrDefault(UiCaptureMode.SINGLE)
        )
    }
    var seriesFrameCount by remember { mutableStateOf(savedSettings.seriesFrameCount) }
    var seriesDelaySeconds by remember { mutableStateOf(savedSettings.seriesDelaySeconds) }
    var startTimerSeconds by remember { mutableStateOf(savedSettings.startTimerSeconds) }
    var astroModeEnabled by remember { mutableStateOf(savedSettings.astroModeEnabled) }
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
    var pendingTestShotStart by remember { mutableStateOf(false) }
    var seriesPreflightReason by remember {
        mutableStateOf<SeriesPreflightReason?>(null)
    }
    var shootingGoal by remember {
        mutableStateOf(
            runCatching { ShootingGoal.valueOf(savedSettings.shootingGoal) }
                .getOrDefault(ShootingGoal.STARS)
        )
    }
    var exposureAssistantExpanded by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<CameraPreset?>(null) }
    var astroDefaultsApplied by remember { mutableStateOf(false) }
    var seriesRunning by remember { mutableStateOf(false) }
    var seriesStopRequested by remember { mutableStateOf(false) }
    var seriesCurrentFrame by remember { mutableStateOf(0) }
    var seriesCompletedFrames by remember { mutableStateOf(0) }
    var seriesAction by remember { mutableStateOf("") }
    var seriesMessage by remember { mutableStateOf<String?>(null) }
    var seriesJob by remember { mutableStateOf<Job?>(null) }
    var darkFramesFormat by remember { mutableStateOf(UiCaptureType.JPEG) }
    var darkFramesCount by remember { mutableStateOf(3) }
    var darkFramesRunning by remember { mutableStateOf(false) }
    var darkFramesStopRequested by remember { mutableStateOf(false) }
    var darkFramesCurrent by remember { mutableStateOf(0) }
    var darkFramesCompleted by remember { mutableStateOf(0) }
    var darkFramesAction by remember { mutableStateOf("") }
    var darkFramesMessage by remember { mutableStateOf<String?>(null) }
    var darkFramesJob by remember { mutableStateOf<Job?>(null) }
    var storageInfo by remember { mutableStateOf<StorageSpaceInfo?>(null) }
    var storageWarningInfo by remember { mutableStateOf<StorageSpaceInfo?>(null) }
    var storageWarningTarget by remember { mutableStateOf("съёмки") }
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun applyAstroDefaults(cameraCapabilities: ManualCameraCapabilities) {
        focusDistance = 0f
        focusMode = CameraFocusMode.INFINITY
        iso = cameraCapabilities.isoRange
            ?.let { 800.coerceIn(it.first, it.last) }
            ?: 800
        exposureTimeNs = cameraCapabilities.exposureRangeNs?.let { range ->
            val thirtySeconds = 30_000_000_000L
            if (range.contains(thirtySeconds)) thirtySeconds else range.last
        } ?: 30_000_000_000L
        seriesFormat = if (cameraCapabilities.supportsRawCapture) {
            UiCaptureType.RAW
        } else {
            UiCaptureType.JPEG
        }
        captureMode = UiCaptureMode.SERIES
        seriesFrameCount = 10
        seriesDelaySeconds = 1
        startTimerSeconds = 5
        astroDefaultsApplied = true
    }

    fun setAstroMode(enabled: Boolean) {
        astroModeEnabled = enabled
        if (enabled) {
            capabilities?.let(::applyAstroDefaults)
        } else {
            astroDefaultsApplied = false
        }
    }

    fun applyPreset(preset: CameraPreset) {
        val cameraCapabilities = capabilities ?: return
        val adaptedPreset = adaptCameraPreset(preset, cameraCapabilities)
        iso = adaptedPreset.iso
        exposureTimeNs = adaptedPreset.exposureTimeNs
        focusMode = adaptedPreset.focusMode
        if (adaptedPreset.focusMode == CameraFocusMode.INFINITY) {
            focusDistance = 0f
        }
        singleFormat = adaptedPreset.format
        seriesFormat = adaptedPreset.format
        captureMode = if (adaptedPreset.frameCount > 1) {
            UiCaptureMode.SERIES
        } else {
            UiCaptureMode.SINGLE
        }
        seriesFrameCount = adaptedPreset.frameCount.coerceAtLeast(3)
        seriesDelaySeconds = adaptedPreset.delaySeconds
        startTimerSeconds = 0
        astroModeEnabled = false
        astroDefaultsApplied = false
        selectedPreset = null
    }

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
        settingsStore.save(
            SavedCameraSettings(
                exposureTimeNs = exposureTimeNs,
                iso = iso,
                focusDistance = focusDistance,
                focusMode = focusMode.name,
                applyLongExposureToPreview = applyLongExposureToPreview,
                singleFormat = singleFormat.name,
                seriesFormat = seriesFormat.name,
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
            seriesMessage = "RAW/DNG-серия недоступна для этой камеры"
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
        val extension = if (selectedFormat == UiCaptureType.RAW) "dng" else "jpg"
        val seriesPrefix = "AstroSeries_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }"

        seriesRunning = true
        seriesStopRequested = false
        seriesCurrentFrame = 0
        seriesCompletedFrames = 0
        seriesAction = if (selectedStartTimerSeconds > 0) {
            "Старт через $selectedStartTimerSeconds..."
        } else {
            "Съёмка..."
        }
        seriesMessage = null

        seriesJob = coroutineScope.launch {
            var seriesFailed = false
            try {
                for (secondsLeft in selectedStartTimerSeconds downTo 1) {
                    if (seriesStopRequested) {
                        seriesMessage = "Таймер старта отменён"
                        return@launch
                    }
                    seriesAction = "Старт через $secondsLeft..."
                    delay(1_000L)
                }

                if (seriesStopRequested) {
                    seriesMessage = "Таймер старта отменён"
                    return@launch
                }

                for (frameIndex in 1..selectedFrameCount) {
                    if (seriesStopRequested) break

                    seriesCurrentFrame = frameIndex
                    seriesAction = "Съёмка..."
                    val fileName = "${seriesPrefix}_${
                        frameIndex.toString().padStart(3, '0')
                    }.$extension"
                    val result = captureSeriesFrame(
                        preview = preview,
                        format = selectedFormat,
                        fileName = fileName,
                        relativeDirectory = relativeDirectory,
                        onStageChanged = { stage ->
                            seriesAction = when (stage) {
                                CameraCaptureStage.CAPTURING -> "Съёмка..."
                                CameraCaptureStage.SAVING -> "Сохранение..."
                            }
                        }
                    )

                    if (result.isFailure) {
                        seriesFailed = true
                        seriesMessage =
                            "Ошибка серии: ${
                                result.exceptionOrNull()?.message ?: "кадр не сохранён"
                            }"
                        break
                    }

                    recordSessionFrame(dark = false, format = selectedFormat)
                    seriesCompletedFrames = frameIndex
                    if (seriesStopRequested || frameIndex == selectedFrameCount) break

                    if (selectedDelaySeconds > 0) {
                        seriesAction = "Пауза..."
                        delay(selectedDelaySeconds * 1_000L)
                    }
                }

                if (!seriesFailed) {
                    seriesMessage = if (seriesStopRequested) {
                        "Серия остановлена после текущего кадра"
                    } else {
                        notifySeriesFeedback(
                            context = context,
                            vibrationEnabled = vibrationAfterSeries,
                            soundEnabled = soundAfterSeries,
                            completed = true
                        )
                        "Серия завершена: $seriesCompletedFrames кадров сохранено"
                    }
                }
            } finally {
                seriesRunning = false
                seriesAction = ""
                seriesJob = null
            }
        }
    }

    fun startDarkFrames() {
        if (darkFramesRunning || seriesRunning || isCapturing || testShotRunning) return
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
            darkFramesMessage = "RAW/DNG недоступен для этой камеры"
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

    fun applyExposureRecommendation(
        recommendation: ExposureRecommendation,
        forceSeries: Boolean
    ) {
        iso = recommendation.iso
        exposureTimeNs = recommendation.exposureTimeNs
        focusMode = recommendation.focusMode
        if (recommendation.focusMode == CameraFocusMode.INFINITY) {
            focusDistance = 0f
        }
        val recommendedFormat =
            if (recommendation.format == AssistantCaptureFormat.RAW) {
                UiCaptureType.RAW
            } else {
                UiCaptureType.JPEG
            }
        singleFormat = recommendedFormat
        seriesFormat = recommendedFormat
        seriesFrameCount = recommendation.frameCount.coerceAtLeast(3)
        startTimerSeconds = recommendation.timerSeconds
        captureMode = if (forceSeries || recommendation.frameCount > 1) {
            UiCaptureMode.SERIES
        } else {
            UiCaptureMode.SINGLE
        }
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
        seriesPreflightReason = when {
            lastTestShot == null -> SeriesPreflightReason.MISSING
            lastTestShot?.isGood != true -> SeriesPreflightReason.BAD
            else -> null
        }
        if (seriesPreflightReason == null) {
            requestSeriesStartAfterTestCheck()
        }
    }

    fun requestDarkFramesStart() {
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
        notifySeriesFeedback(
            context = context,
            vibrationEnabled = vibrationAfterSeries,
            soundEnabled = false,
            completed = false
        )
        seriesMessage = "Остановка после текущего кадра…"
        if (seriesCurrentFrame == 0 || seriesAction == "Пауза...") {
            seriesJob?.cancel()
            seriesMessage = if (seriesCurrentFrame == 0) {
                "Таймер старта отменён"
            } else {
                "Серия остановлена"
            }
        }
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
                seriesStopRequested = true
                seriesJob?.cancel()
                darkFramesStopRequested = true
                darkFramesJob?.cancel()
                if (seriesRunning) {
                    seriesMessage = "Серия остановлена при сворачивании приложения"
                }
                if (darkFramesRunning) {
                    darkFramesMessage = "Dark Frames остановлены при сворачивании приложения"
                }
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            seriesStopRequested = true
            seriesJob?.cancel()
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
        )
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
                    onCameraStatus = { status ->
                        cameraStatus = status
                    },
                    onExposureAnalysis = { analysis ->
                        liveExposureAnalysis = analysis
                        histogramError = null
                    },
                    onExposureAnalyzerUnavailable = { message ->
                        liveExposureAnalysis = null
                        histogramError = message
                        histogramEnabled = false
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
            },
            modifier = Modifier.fillMaxSize()
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp),
            color = Color.Black.copy(alpha = 0.62f),
            contentColor = Color.White,
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
                    Text("Назад", color = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (captureMode == UiCaptureMode.SERIES) {
                            "Серия"
                        } else {
                            "Одиночный кадр"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = when {
                            seriesRunning -> "Кадр $seriesCurrentFrame из $seriesFrameCount"
                            darkFramesRunning -> "Dark $darkFramesCurrent из $darkFramesCount"
                            isCapturing -> captureStatus ?: "Съёмка…"
                            else -> "${formatExposure(exposureTimeNs)} · ISO $iso"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD5DBE8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = cameraStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD5DBE8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

        val selectedPanelFormat = if (captureMode == UiCaptureMode.SERIES) {
            seriesFormat
        } else {
            singleFormat
        }
        val panelSummary = buildString {
            append(selectedPanelFormat.name)
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
                    captureMode = captureMode,
                    singleFormat = singleFormat,
                    seriesFormat = seriesFormat,
                    isCapturing = isCapturing,
                    captureStatus = captureStatus,
                    seriesRunning = seriesRunning,
                    seriesCurrentFrame = seriesCurrentFrame,
                    seriesFrameCount = seriesFrameCount,
                    seriesCompletedFrames = seriesCompletedFrames,
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
                    onSingleCapture = { requestCapture(singleFormat) },
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
                    captureStatus = captureStatus,
                    exposureWarning = exposureWarning,
                    captureMode = captureMode,
                    singleFormat = singleFormat,
                    seriesFormat = seriesFormat,
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
                    shootingGoal = shootingGoal,
                    exposureRecommendation = exposureRecommendation,
                    exposureAssistantExpanded = exposureAssistantExpanded,
                    currentSessionName = currentSession?.sessionName,
                    saveLocationStatus = saveLocationStatus,
                    storageInfo = storageInfo,
                    seriesRunning = seriesRunning,
                    seriesCurrentFrame = seriesCurrentFrame,
                    seriesCompletedFrames = seriesCompletedFrames,
                    seriesAction = seriesAction,
                    seriesMessage = seriesMessage,
                    darkFramesFormat = darkFramesFormat,
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
                            applyExposureRecommendation(it, forceSeries = false)
                        }
                    },
                    onAssistantTestShot = ::requestTestShot,
                    onAssistantStartSeries = {
                        exposureRecommendation?.let {
                            applyExposureRecommendation(it, forceSeries = true)
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
                    onCaptureModeChanged = { captureMode = it },
                    onSingleFormatChanged = { singleFormat = it },
                    onJpegCapture = { requestCapture(UiCaptureType.JPEG) },
                    onRawCapture = { requestCapture(UiCaptureType.RAW) },
                    onSeriesFormatChanged = { seriesFormat = it },
                    onSeriesFrameCountChanged = { seriesFrameCount = it },
                    onSeriesDelayChanged = { seriesDelaySeconds = it },
                    onStartTimerChanged = { startTimerSeconds = it },
                    onSeriesStart = ::requestSeriesStart,
                    onSeriesStop = ::requestSeriesStop,
                    onDarkFramesFormatChanged = { darkFramesFormat = it },
                    onDarkFramesCountChanged = { darkFramesCount = it },
                    onDarkFramesStart = ::requestDarkFramesStart,
                    onDarkFramesStop = ::requestDarkFramesStop,
                    onExposureChanged = {
                        exposureWarning = null
                        exposureTimeNs = it
                    },
                    onUnsupportedExposure = { maximumExposure ->
                        exposureWarning =
                            "Эта выдержка не поддерживается вашим телефоном. " +
                            "Максимум: ${formatExposure(maximumExposure)}."
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
        seriesPreflightReason?.let { reason ->
            AlertDialog(
                onDismissRequest = { seriesPreflightReason = null },
                title = { Text("Проверка перед серией") },
                text = {
                    Text(
                        when (reason) {
                            SeriesPreflightReason.MISSING ->
                                "Пробный кадр не сделан. Настройки могут быть неверными."
                            SeriesPreflightReason.BAD ->
                                "Последний пробный кадр: ${
                                    lastTestShot?.status?.title?.lowercase()
                                        ?: "не удалось оценить"
                                }."
                        }
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            seriesPreflightReason = null
                            if (reason == SeriesPreflightReason.MISSING) {
                                requestTestShot()
                            } else {
                                panelAnchor = CameraPanelAnchor.EXPANDED
                            }
                        }
                    ) {
                        Text(
                            if (reason == SeriesPreflightReason.MISSING) {
                                "Сделать пробный кадр"
                            } else {
                                "Исправить"
                            }
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            seriesPreflightReason = null
                            requestSeriesStartAfterTestCheck()
                        }
                    ) {
                        Text("Начать всё равно")
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
private fun ContextHelpTitle(
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
private fun CameraControlSectionTitle(
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
private fun ManualControlsPanel(
    capabilities: ManualCameraCapabilities?,
    exposureTimeNs: Long,
    iso: Int,
    focusDistance: Float,
    focusMode: CameraFocusMode,
    applyLongExposureToPreview: Boolean,
    isCapturing: Boolean,
    captureStatus: String?,
    exposureWarning: String?,
    captureMode: UiCaptureMode,
    singleFormat: UiCaptureType,
    seriesFormat: UiCaptureType,
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
    shootingGoal: ShootingGoal,
    exposureRecommendation: ExposureRecommendation?,
    exposureAssistantExpanded: Boolean,
    currentSessionName: String?,
    saveLocationStatus: String?,
    storageInfo: StorageSpaceInfo?,
    seriesRunning: Boolean,
    seriesCurrentFrame: Int,
    seriesCompletedFrames: Int,
    seriesAction: String,
    seriesMessage: String?,
    darkFramesFormat: UiCaptureType,
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
    onCaptureModeChanged: (UiCaptureMode) -> Unit,
    onSingleFormatChanged: (UiCaptureType) -> Unit,
    onJpegCapture: () -> Unit,
    onRawCapture: () -> Unit,
    onSeriesFormatChanged: (UiCaptureType) -> Unit,
    onSeriesFrameCountChanged: (Int) -> Unit,
    onSeriesDelayChanged: (Int) -> Unit,
    onStartTimerChanged: (Int) -> Unit,
    onSeriesStart: () -> Unit,
    onSeriesStop: () -> Unit,
    onDarkFramesFormatChanged: (UiCaptureType) -> Unit,
    onDarkFramesCountChanged: (Int) -> Unit,
    onDarkFramesStart: () -> Unit,
    onDarkFramesStop: () -> Unit,
    onExposureChanged: (Long) -> Unit,
    onUnsupportedExposure: (Long) -> Unit,
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
    val controlsLocked = isCapturing || seriesRunning || darkFramesRunning || testShotRunning
    val availableIsoPresets = ISO_PRESETS.filter { preset ->
        isoRange?.contains(preset) == true
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
            if (!capabilities.supportsRawCapture) {
                add("RAW_SENSOR не поддерживается этой камерой.")
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
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
                    color = Color(0xFFA5D6A7)
                )
            }
            Text(
                text = storageInfo?.availableBytes?.let { bytes ->
                    "Свободно: ${formatStorageSize(bytes)}"
                } ?: "Не удалось определить свободное место",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (storageInfo?.criticallyLow == true) {
                    Color(0xFFFF6E6E)
                } else {
                    Color(0xFFB8BECC)
                }
            )
            if (storageInfo?.criticallyLow == true) {
                Text(
                    text = "Мало места",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF6E6E)
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
                        Color(0xFFFFCC80)
                    } else {
                        Color(0xFFA5D6A7)
                    }
                )
            }

            com.example.astrophoto.ui.AstroExpandableSection(
                title = "Дополнительно",
                modifier = Modifier.padding(top = 12.dp)
            ) {
            FilterChip(
                selected = astroModeEnabled,
                onClick = { onAstroModeChanged(!astroModeEnabled) },
                label = { Text("Astro Mode") },
                enabled = !controlsLocked,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "Astro Mode: RAW, ∞, длинная выдержка, серия кадров",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB8BECC)
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
                color = Color(0xFFB8BECC)
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
                                color = Color(0xFFF4F6FF)
                            )
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB8BECC)
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

            CameraControlSectionTitle("Формат и съёмка")
            Text("Режим съёмки", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = captureMode == UiCaptureMode.SINGLE,
                    onClick = { onCaptureModeChanged(UiCaptureMode.SINGLE) },
                    label = { Text("Одиночный") },
                    enabled = !controlsLocked
                )
                FilterChip(
                    selected = captureMode == UiCaptureMode.SERIES,
                    onClick = { onCaptureModeChanged(UiCaptureMode.SERIES) },
                    label = { Text("Серия") },
                    enabled = !controlsLocked
                )
            }

            ContextHelpTitle(
                title = "Формат одиночного снимка",
                topic = HelpTopic.RAW,
                onHelp = { helpTopic = it },
                modifier = Modifier.padding(top = 10.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = singleFormat == UiCaptureType.JPEG,
                    onClick = { onSingleFormatChanged(UiCaptureType.JPEG) },
                    label = { Text("JPEG") },
                    enabled = !controlsLocked && capabilities?.supportsJpegCapture == true
                )
                FilterChip(
                    selected = singleFormat == UiCaptureType.RAW,
                    onClick = { onSingleFormatChanged(UiCaptureType.RAW) },
                    label = { Text("RAW/DNG") },
                    enabled = !controlsLocked && capabilities?.supportsRawCapture == true
                )
            }

            warnings.forEach { warning ->
                Text(
                    text = warning,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFCC80)
                )
            }
            if (controlsLocked) {
                Text(
                    text = "Идёт серия, параметры заблокированы",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFCC80)
                )
            }

            Button(
                onClick = onJpegCapture,
                enabled = !isCapturing &&
                    !controlsLocked &&
                    capabilities?.supportsJpegCapture == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(top = 14.dp)
            ) {
                Text("Снять JPEG")
            }
            Button(
                onClick = onRawCapture,
                enabled = !isCapturing &&
                    !controlsLocked &&
                    capabilities?.supportsRawCapture == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(top = 8.dp)
            ) {
                Text("Снять RAW/DNG")
            }
            captureStatus?.let { status ->
                Text(
                    text = status,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (status.startsWith("Ошибка")) {
                        Color(0xFFFFAB91)
                    } else {
                        Color(0xFFA5D6A7)
                    }
                )
            }

            CameraControlSectionTitle("Серия")
            Text(
                text = "Формат серии",
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
                    selected = seriesFormat == UiCaptureType.JPEG,
                    onClick = { onSeriesFormatChanged(UiCaptureType.JPEG) },
                    label = { Text("JPEG") },
                    enabled = !controlsLocked && capabilities?.supportsJpegCapture == true
                )
                FilterChip(
                    selected = seriesFormat == UiCaptureType.RAW,
                    onClick = { onSeriesFormatChanged(UiCaptureType.RAW) },
                    label = { Text("RAW/DNG") },
                    enabled = !controlsLocked && capabilities?.supportsRawCapture == true
                )
            }

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
                SERIES_FRAME_COUNTS.forEach { count ->
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
                    color = Color(0xFFB7C9FF)
                )
                LinearProgressIndicator(
                    progress = {
                        seriesCompletedFrames.toFloat() / seriesFrameCount.coerceAtLeast(1)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
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
                    enabled = !isCapturing && !darkFramesRunning && when (seriesFormat) {
                        UiCaptureType.JPEG -> capabilities?.supportsJpegCapture == true
                        UiCaptureType.RAW -> capabilities?.supportsRawCapture == true
                    },
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
                        Color(0xFFFFAB91)
                    } else {
                        Color(0xFFA5D6A7)
                    }
                )
            }

            ContextHelpTitle(
                title = "Dark Frames",
                topic = HelpTopic.DARKS,
                onHelp = { helpTopic = it },
                modifier = Modifier.padding(top = 20.dp),
                large = true
            )
            Text(
                text = "Закройте камеру/объектив и не двигайте телефон. " +
                    "Dark frames снимаются с теми же ISO, выдержкой и фокусом.",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB8BECC)
            )
            Text(
                text = "Формат Dark Frames",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = darkFramesFormat == UiCaptureType.JPEG,
                    onClick = { onDarkFramesFormatChanged(UiCaptureType.JPEG) },
                    label = { Text("JPEG") },
                    enabled = !controlsLocked && capabilities?.supportsJpegCapture == true
                )
                FilterChip(
                    selected = darkFramesFormat == UiCaptureType.RAW,
                    onClick = { onDarkFramesFormatChanged(UiCaptureType.RAW) },
                    label = { Text("RAW/DNG") },
                    enabled = !controlsLocked && capabilities?.supportsRawCapture == true
                )
            }
            Text(
                text = "Количество Dark Frames",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DARK_FRAME_COUNTS.forEach { count ->
                    FilterChip(
                        selected = darkFramesCount == count,
                        onClick = { onDarkFramesCountChanged(count) },
                        label = { Text(count.toString()) },
                        enabled = !controlsLocked
                    )
                }
            }
            if (darkFramesRunning) {
                Text(
                    text = "Dark frame $darkFramesCurrent из $darkFramesCount",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = darkFramesAction,
                    modifier = Modifier.padding(top = 4.dp),
                    color = Color(0xFFB7C9FF)
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
                    enabled = !isCapturing && !seriesRunning && when (darkFramesFormat) {
                        UiCaptureType.JPEG -> capabilities?.supportsJpegCapture == true
                        UiCaptureType.RAW -> capabilities?.supportsRawCapture == true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(top = 12.dp)
                ) {
                    Text("Снять Dark Frames")
                }
            }
            darkFramesMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.startsWith("Ошибка")) {
                        Color(0xFFFFAB91)
                    } else {
                        Color(0xFFA5D6A7)
                    }
                )
            }
            Text(
                text = "Используйте их позже для вычитания шума.",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8BECC)
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
                    color = Color(0xFFB8BECC)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EXPOSURE_PRESETS.forEach { preset ->
                    val supported = exposureRange?.contains(preset.nanoseconds) == true
                    FilterChip(
                        selected = supported && exposureTimeNs == preset.nanoseconds,
                        onClick = {
                            if (supported) {
                                onExposureChanged(preset.nanoseconds)
                            } else {
                                exposureRange?.last?.let(onUnsupportedExposure)
                            }
                        },
                        label = { Text(preset.label) },
                        enabled = capabilities != null && !controlsLocked,
                        modifier = Modifier.alpha(if (supported) 1f else 0.45f)
                    )
                }
            }
            exposureWarning?.let { warning ->
                Text(
                    text = warning,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFCC80)
                )
            }
            FilterChip(
                selected = applyLongExposureToPreview,
                onClick = {
                    onApplyLongExposureToPreviewChanged(!applyLongExposureToPreview)
                },
                label = {
                    Text("Применять длинную выдержку к preview")
                },
                enabled = !controlsLocked,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (!applyLongExposureToPreview && exposureTimeNs > 1_000_000_000L) {
                Text(
                    text = "Для плавности preview используется безопасная выдержка 1/30 сек.",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8BECC)
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
                    color = Color(0xFFB8BECC)
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
private fun CompactCapturePanel(
    capabilities: ManualCameraCapabilities?,
    captureMode: UiCaptureMode,
    singleFormat: UiCaptureType,
    seriesFormat: UiCaptureType,
    seriesFrameCount: Int,
    isCapturing: Boolean,
    captureStatus: String?,
    seriesRunning: Boolean,
    seriesCurrentFrame: Int,
    seriesCompletedFrames: Int,
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
    onSingleCapture: () -> Unit,
    onSeriesStart: () -> Unit,
    onSeriesStop: () -> Unit,
    onDarkFramesStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedFormat = if (captureMode == UiCaptureMode.SERIES) {
        seriesFormat
    } else {
        singleFormat
    }
    val formatAvailable = when (selectedFormat) {
        UiCaptureType.JPEG -> capabilities?.supportsJpegCapture == true
        UiCaptureType.RAW -> capabilities?.supportsRawCapture == true
    }

    Column(
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when {
                darkFramesRunning -> {
                    Text(
                        text = "Dark frame $darkFramesCurrent из $darkFramesCount",
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
                    LinearProgressIndicator(
                        progress = {
                            seriesCompletedFrames.toFloat() /
                                seriesFrameCount.coerceAtLeast(1)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                    )
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
                        onClick = if (captureMode == UiCaptureMode.SERIES) {
                            onSeriesStart
                        } else {
                            onSingleCapture
                        },
                        enabled = formatAvailable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag(com.example.astrophoto.ui.AstroTestTags.CameraCapture)
                    ) {
                        Text(
                            if (captureMode == UiCaptureMode.SERIES) {
                                "Старт серии"
                            } else {
                                "Снять"
                            }
                        )
                    }
                    val status = if (captureMode == UiCaptureMode.SERIES) {
                        darkFramesMessage ?: seriesMessage
                    } else {
                        darkFramesMessage ?: captureStatus
                    }
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
private fun PresetDialog(
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
                        color = Color(0xFFFFCC80),
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
                        text = "${if (adapted.format == UiCaptureType.RAW) "RAW/DNG" else "JPEG"}" +
                            " • ${formatFocusMode(adapted.focusMode, 0f)}" +
                            " • ${adapted.frameCount} кадров" +
                            " • пауза ${adapted.delaySeconds} сек",
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (adapted.wasAdapted) {
                        Text(
                            text = "Часть значений адаптирована под ваш телефон.",
                            modifier = Modifier.padding(top = 10.dp),
                            color = Color(0xFFFFCC80)
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

private fun presetExposureWarning(
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
private fun SessionDialog(
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
                    color = Color(0xFFB8BECC)
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
private fun WarningCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF4A3210))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Обратите внимание",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD180)
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 6.dp),
                color = Color(0xFFFFE0B2)
            )
        }
    }
}

@Composable
private fun DiagnosticCard(row: DiagnosticRow) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF151A24),
            contentColor = Color(0xFFF4F6FF)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = row.name,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF4F6FF)
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
                    true -> Color(0xFF81C784)
                    false -> Color(0xFFEF9A9A)
                    null -> Color(0xFFB7C9FF)
                }
            )
            Text(
                text = row.description,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB8BECC)
            )
        }
    }
}

private sealed interface DiagnosticsState {
    data object Loading : DiagnosticsState
    data class Ready(val info: CameraDiagnosticInfo) : DiagnosticsState
    data class Error(val message: String) : DiagnosticsState
}

private enum class AppScreen {
    Diagnostics,
    DiagnosticsDetails,
    Camera,
    Sessions,
    SessionDetails,
    Settings,
    Help,
    About,
    SelfCheck
}

private enum class UiCaptureType {
    JPEG,
    RAW
}

private enum class UiCaptureMode {
    SINGLE,
    SERIES
}

private enum class SeriesPreflightReason {
    MISSING,
    BAD
}

internal fun canStartSingleCapture(
    isCapturing: Boolean,
    seriesRunning: Boolean,
    darkFramesRunning: Boolean,
    testShotRunning: Boolean,
    permissionRequestPending: Boolean
): Boolean = !isCapturing &&
    !seriesRunning &&
    !darkFramesRunning &&
    !testShotRunning &&
    !permissionRequestPending

private data class AdaptedCameraPreset(
    val iso: Int,
    val exposureTimeNs: Long,
    val format: UiCaptureType,
    val frameCount: Int,
    val delaySeconds: Int,
    val focusMode: CameraFocusMode,
    val wasAdapted: Boolean
)

private fun adaptCameraPreset(
    preset: CameraPreset,
    capabilities: ManualCameraCapabilities
): AdaptedCameraPreset {
    val adaptedIso = capabilities.isoRange
        ?.let { preset.iso.coerceIn(it.first, it.last) }
        ?: preset.iso
    val adaptedExposure = capabilities.exposureRangeNs?.let { range ->
        when {
            range.contains(preset.exposureTimeNs) -> preset.exposureTimeNs
            preset.useMaximumExposureWhenNeeded -> range.last
            else -> preset.exposureTimeNs.coerceIn(range.first, range.last)
        }
    } ?: preset.exposureTimeNs
    val adaptedFormat = if (preset.preferRaw && capabilities.supportsRawCapture) {
        UiCaptureType.RAW
    } else {
        UiCaptureType.JPEG
    }
    val adaptedFocusMode = if (
        preset.focusMode != CameraFocusMode.AF &&
        !capabilities.supportsManualFocus
    ) {
        CameraFocusMode.AF
    } else {
        preset.focusMode
    }

    return AdaptedCameraPreset(
        iso = adaptedIso,
        exposureTimeNs = adaptedExposure,
        format = adaptedFormat,
        frameCount = preset.frameCount,
        delaySeconds = preset.delaySeconds,
        focusMode = adaptedFocusMode,
        wasAdapted = adaptedIso != preset.iso ||
            adaptedExposure != preset.exposureTimeNs ||
            (preset.preferRaw && adaptedFormat != UiCaptureType.RAW) ||
            adaptedFocusMode != preset.focusMode
    )
}

private val SERIES_FRAME_COUNTS = listOf(3, 5, 10, 20, 30)
private val DARK_FRAME_COUNTS = listOf(3, 5, 10, 20)
private val SERIES_DELAYS_SECONDS = listOf(0, 1, 2, 5)
private val START_TIMER_SECONDS = listOf(0, 3, 5, 10)

private suspend fun captureSeriesFrame(
    preview: CameraPreviewView,
    format: UiCaptureType,
    fileName: String,
    relativeDirectory: String,
    onStageChanged: (CameraCaptureStage) -> Unit
): Result<String> = suspendCancellableCoroutine { continuation ->
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

private data class ExposurePreset(
    val label: String,
    val nanoseconds: Long
)

private val EXPOSURE_PRESETS = listOf(
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

private val ISO_PRESETS = listOf(50, 100, 200, 400, 800, 1600, 3200)

private fun formatExposure(nanoseconds: Long): String {
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

private fun formatFocus(distance: Float): String =
    if (distance == 0f) "∞" else String.format(Locale.US, "%.1f дптр", distance)

private fun formatFocusMode(mode: CameraFocusMode, distance: Float): String = when (mode) {
    CameraFocusMode.AF -> "AF"
    CameraFocusMode.MF -> formatFocus(distance)
    CameraFocusMode.INFINITY -> "∞"
}

private fun String.trimTrailingZeros(): String =
    replace(Regex("""(\.\d*?[1-9])0+(?= сек)"""), "$1")
        .replace(".00 сек", " сек")

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

private fun Context.hasLegacyStoragePermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

private fun Context.findComponentActivity(): ComponentActivity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is ComponentActivity) return currentContext
        currentContext = currentContext.baseContext
    }
    return currentContext as? ComponentActivity
}

@Suppress("DEPRECATION")
private fun notifySeriesFeedback(
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
            Log.d("AstroPhotoFeedback", "Series vibration played")
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
            Log.d("AstroPhotoFeedback", "Series tone played")
        }.onFailure { error ->
            Log.e("AstroPhotoFeedback", "Tone unavailable", error)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionScreenPreview() {
    AstroPhotoTheme(darkTheme = true, dynamicColor = false) {
        Surface(
            color = Color(0xFF080B12),
            contentColor = Color(0xFFF4F6FF)
        ) {
            PermissionScreen(
                onRequestPermission = {},
                onOpenAbout = {}
            )
        }
    }
}
