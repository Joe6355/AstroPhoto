package com.example.astrophoto

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen(
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val storageChecker = remember { StorageSpaceChecker(context.applicationContext) }
    val sessionStore = remember { ShootingSessionStore(context.applicationContext) }
    var diagnostics by remember { mutableStateOf<CameraDiagnosticInfo?>(null) }
    var freeStorageBytes by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val versionName = remember { applicationVersionName(context) }
    val activeSession = remember { sessionStore.load()?.sessionName ?: "не выбрана" }

    LaunchedEffect(cameraPermissionGranted) {
        diagnostics = withContext(Dispatchers.Default) {
            readCameraDiagnostics(context.applicationContext).getOrNull()
        }
        freeStorageBytes = storageChecker.readAvailableSpace().availableBytes
    }

    val rows = diagnostics?.rows.orEmpty()
    fun diagnosticValue(name: String): String =
        rows.firstOrNull { it.name == name }?.value ?: "неизвестно"

    val storageAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    val vibrationAvailable = remember { deviceHasVibrator(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            TextButton(onClick = onBack) {
                Text("← Назад")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AstroPhotoLogo(modifier = Modifier.size(76.dp))
                Column {
                    Text(
                        text = "AstroPhoto",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Версия $versionName",
                        color = Color(0xFFB8BECC)
                    )
                }
            }
            Text(
                text = "Ручная камера для астрофото, серий, RAW/DNG, " +
                    "dark frames и stacking.",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        item {
            AboutCard("Хранение и сессия") {
                Text("Активная сессия: $activeSession")
                Text("Файлы: Pictures/AstroPhoto")
                Text(
                    "Свободно: ${
                        freeStorageBytes?.let(::formatStorageSize) ?: "неизвестно"
                    }"
                )
            }
        }

        item {
            AboutCard("Поддержка устройства") {
                Text("RAW/DNG: ${diagnosticValue("RAW/DNG")}")
                Text("Manual Sensor: ${diagnosticValue("Manual Sensor")}")
                Text("Максимальная выдержка: ${diagnosticValue("Макс. выдержка")}")
                Text("ISO: ${diagnosticValue("ISO")}")
            }
        }

        item {
            AboutCard("Разрешения") {
                PermissionLine("Камера", cameraPermissionGranted)
                PermissionLine("Сохранение файлов", storageAvailable)
                PermissionLine("Вибрация", vibrationAvailable)
                if (!cameraPermissionGranted) {
                    Button(
                        onClick = onRequestCameraPermission,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(top = 8.dp)
                    ) {
                        Text("Запросить разрешение")
                    }
                }
            }
        }

        item {
            AboutCard("Что умеет сейчас") {
                ABOUT_FEATURES.forEach { Text("• $it") }
            }
        }

        item {
            Button(
                onClick = onOpenHelp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text("Открыть справку")
            }
            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(top = 8.dp)
            ) {
                Text("Открыть настройки")
            }
            Button(
                onClick = onOpenSelfCheck,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(top = 8.dp)
            ) {
                Text("Самопроверка")
            }
            Button(
                onClick = {
                    val text = buildDiagnosticsText(
                        versionName = versionName,
                        diagnostics = diagnostics,
                        activeSession = activeSession,
                        freeStorageBytes = freeStorageBytes
                    )
                    val clipboard = context.getSystemService(
                        ClipboardManager::class.java
                    )
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("AstroPhoto diagnostics", text)
                    )
                    status = if (clipboard != null) {
                        "Диагностика скопирована"
                    } else {
                        "Буфер обмена недоступен"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(top = 8.dp)
            ) {
                Text("Скопировать диагностику")
            }
            status?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 8.dp),
                    color = if (it == "Диагностика скопирована") {
                        Color(0xFFA5D6A7)
                    } else {
                        Color(0xFFFFAB91)
                    }
                )
            }
        }
    }
}

@Composable
fun AstroPhotoLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = size.minDimension / 108f
        drawCircle(
            color = Color(0xFF111936),
            radius = size.minDimension / 2f
        )
        drawRoundRect(
            color = Color(0xFFF4F6FF),
            topLeft = Offset(24f * unit, 42f * unit),
            size = Size(60f * unit, 39f * unit),
            cornerRadius = CornerRadius(6f * unit)
        )
        drawCircle(
            color = Color(0xFF766CFF),
            radius = 17f * unit,
            center = Offset(54f * unit, 61f * unit)
        )
        drawCircle(
            color = Color(0xFF35D5FF),
            radius = 10f * unit,
            center = Offset(54f * unit, 61f * unit)
        )
        drawCircle(
            color = Color(0xFF10152E),
            radius = 5f * unit,
            center = Offset(54f * unit, 61f * unit)
        )
        drawCircle(
            color = Color.White,
            radius = 2.5f * unit,
            center = Offset(79f * unit, 30f * unit)
        )
        drawCircle(
            color = Color.White,
            radius = 5f * unit,
            center = Offset(79f * unit, 30f * unit),
            style = Stroke(width = 1f * unit)
        )
    }
}

@Composable
private fun AboutCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun PermissionLine(label: String, available: Boolean) {
    Text(
        text = "$label: ${if (available) "доступно" else "не доступно"}",
        color = if (available) Color(0xFFA5D6A7) else Color(0xFFFFAB91)
    )
}

private fun applicationVersionName(context: Context): String = runCatching {
    context.packageManager
        .getPackageInfo(context.packageName, 0)
        .versionName
        .orEmpty()
        .ifBlank { "неизвестно" }
}.getOrDefault("неизвестно")

private fun deviceHasVibrator(context: Context): Boolean = runCatching {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    vibrator?.hasVibrator() == true
}.getOrDefault(false)

private fun buildDiagnosticsText(
    versionName: String,
    diagnostics: CameraDiagnosticInfo?,
    activeSession: String,
    freeStorageBytes: Long?
): String {
    val rows = diagnostics?.rows.orEmpty()
    fun value(name: String): String =
        rows.firstOrNull { it.name == name }?.value ?: "неизвестно"
    return buildString {
        appendLine("AstroPhoto diagnostics")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App version: $versionName")
        appendLine("Camera ID: ${value("Camera ID")}")
        appendLine("Hardware level: ${value("Hardware Level")}")
        appendLine("RAW/DNG: ${value("RAW/DNG")}")
        appendLine("Manual Sensor: ${value("Manual Sensor")}")
        appendLine("Max exposure: ${value("Макс. выдержка")}")
        appendLine("ISO range: ${value("ISO")}")
        appendLine("Active session: $activeSession")
        appendLine("Storage path: Pictures/AstroPhoto")
        appendLine(
            "Free storage: ${
                freeStorageBytes?.let(::formatStorageSize) ?: "неизвестно"
            }"
        )
    }
}

private val ABOUT_FEATURES = listOf(
    "Ручная выдержка, ISO и фокус",
    "JPEG и RAW/DNG",
    "Астро-серии и Dark Frames",
    "Сессии съёмки",
    "Stacking Average, Median и Sigma",
    "Alignment и автоотбор кадров",
    "Редактор JPEG",
    "Экспорт сессии в ZIP",
    "Встроенная справка"
)
