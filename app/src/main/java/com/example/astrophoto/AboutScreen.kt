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
import com.example.astrophoto.ui.AstroScaffold
import com.example.astrophoto.ui.AstroInfoRow
import com.example.astrophoto.ui.AstroSecondaryButton
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.AstroTextButton
import com.example.astrophoto.ui.theme.AstroColors
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
    val versionName = remember { applicationVersionName(context) }

    val storageAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    val vibrationAvailable = remember { deviceHasVibrator(context) }

    AstroScaffold(title = "О приложении", onBack = onBack) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            AstroSpacing.Lg,
            AstroSpacing.Md,
            AstroSpacing.Lg,
            AstroSpacing.Xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(AstroSpacing.Md)
    ) {
        item {
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            AboutCard("Информация") {
                AstroInfoRow(label = "Разработчик", value = "Дудин С.В.")
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
            AstroSecondaryButton(
                text = "Открыть справку",
                onClick = onOpenHelp,
                modifier = Modifier.fillMaxWidth()
            )
            AstroSecondaryButton(
                text = "Открыть настройки",
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            AstroTextButton(
                text = "Самопроверка",
                onClick = onOpenSelfCheck,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
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
            color = AstroColors.SurfaceElevated,
            radius = size.minDimension / 2f
        )
        drawRoundRect(
            color = AstroColors.TextPrimary,
            topLeft = Offset(24f * unit, 42f * unit),
            size = Size(60f * unit, 39f * unit),
            cornerRadius = CornerRadius(6f * unit)
        )
        drawCircle(
            color = AstroColors.Primary,
            radius = 17f * unit,
            center = Offset(54f * unit, 61f * unit)
        )
        drawCircle(
            color = AstroColors.Secondary,
            radius = 10f * unit,
            center = Offset(54f * unit, 61f * unit)
        )
        drawCircle(
            color = AstroColors.Surface,
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
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
        color = if (available) AstroColors.Success else AstroColors.Error
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
