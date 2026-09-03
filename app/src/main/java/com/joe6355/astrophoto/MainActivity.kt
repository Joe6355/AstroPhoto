package com.joe6355.astrophoto

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import com.joe6355.astrophoto.ui.theme.AstroColors
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingFailureSummary
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.PreviousProcessingFailureDetector
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.PreviousRunClassification
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val previousProcessingFailure = PreviousProcessingFailureDetector(this).detect()
        val interruptedProcessing = if (previousProcessingFailure == null) {
            ProcessingRecoveryStore(this).takeInterrupted()
        } else {
            null
        }
        setContent {
            AstroPhotoTheme(darkTheme = true, dynamicColor = false) {
                AstroPhotoApp(
                    initialProcessingFailure = previousProcessingFailure,
                    initialInterruptedProcessing = if (previousProcessingFailure == null) {
                        interruptedProcessing
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun AstroPhotoApp(
    initialProcessingFailure: ProcessingFailureSummary? = null,
    initialInterruptedProcessing: InterruptedSessionProcessing? = null
) {
    var showSplash by remember { mutableStateOf(true) }
    var processingFailure by remember { mutableStateOf(initialProcessingFailure) }
    var interruptedProcessing by remember { mutableStateOf(initialInterruptedProcessing) }

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
    val navigation = remember { AppNavigationController() }
    val currentScreen by navigation.currentScreen
    var showExitDialog by navigation.showExitDialog
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
        navigation.navigateTo(screen)
    }

    fun navigateBack() {
        if (currentScreen == AppScreen.Help) {
            helpInitialTopic = null
        }
        navigation.navigateBack()
    }

    fun navigateToSessionsAfterDelete() {
        navigation.navigateToSessionsAfterDelete()
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

    AstroPhotoTheme(
        darkTheme = appSettings.themeMode != AppThemeMode.LIGHT.name,
        dynamicColor = false,
        veryDark = appSettings.themeMode == AppThemeMode.VERY_DARK.name,
        redNight = appSettings.themeMode == AppThemeMode.RED_NIGHT.name
    ) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
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
                        appSettings = appSettingsStore.load()
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
                        appSettingsStore.saveAppSettings(updated)
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
                        appSettings = appSettingsStore.load()
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
    processingFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = {
                PreviousProcessingFailureDetector(context.applicationContext).dismiss(failure)
                processingFailure = null
            },
            title = {
                Text(
                    if (failure.classification ==
                        PreviousRunClassification.PROCESSING_COMPLETED_UI_FAILURE
                    ) {
                        "JPEG-результат сохранён"
                    } else {
                        "JPEG-обработка была остановлена"
                    }
                )
            },
            text = { Text(failure.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        PreviousProcessingFailureDetector(context.applicationContext).dismiss(failure)
                        processingFailure = null
                    }
                ) {
                    Text("Понятно")
                }
            }
        )
    }
    interruptedProcessing?.let { interrupted ->
        AlertDialog(
            onDismissRequest = { interruptedProcessing = null },
            title = { Text("Обработка была прервана") },
            text = {
                Text(
                    "${interrupted.label} в сессии «${interrupted.sessionFolder}» не завершена. " +
                        "Исходные кадры сохранены: откройте сессию и запустите обработку снова."
                )
            },
            confirmButton = {
                TextButton(onClick = { interruptedProcessing = null }) {
                    Text("Понятно")
                }
            }
        )
    }
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
                text = "Ручная ночная камера",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = AstroColors.TextSecondary
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var copyStatus by remember { mutableStateOf<String?>(null) }
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
            com.joe6355.astrophoto.ui.AstroTopBar(
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
        item {
            Button(
                onClick = {
                    val text = buildString {
                        appendLine("AstroPhoto diagnostics")
                        info.warning?.let { appendLine("Warning: $it") }
                        info.rows.forEach { appendLine("${it.name}: ${it.value}") }
                    }
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("AstroPhoto diagnostics", text)
                    )
                    copyStatus = if (clipboard == null) {
                        "Буфер обмена недоступен"
                    } else {
                        "Диагностика скопирована"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Скопировать диагностику")
            }
            copyStatus?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 6.dp),
                    color = if (it.endsWith("скопирована")) {
                        com.joe6355.astrophoto.ui.theme.AstroColors.Success
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

private sealed interface DiagnosticsState {
    data object Loading : DiagnosticsState
    data class Ready(val info: CameraDiagnosticInfo) : DiagnosticsState
    data class Error(val message: String) : DiagnosticsState
}

@Preview(showBackground = true)
@Composable
private fun PermissionScreenPreview() {
    AstroPhotoTheme(darkTheme = true, dynamicColor = false) {
        Surface(
            color = AstroColors.Background,
            contentColor = AstroColors.TextPrimary
        ) {
            PermissionScreen(
                onRequestPermission = {},
                onOpenAbout = {}
            )
        }
    }
}
