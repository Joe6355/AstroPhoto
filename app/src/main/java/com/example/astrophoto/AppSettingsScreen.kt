package com.example.astrophoto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AppSettingsScreen(
    settings: SavedCameraSettings,
    onSettingsChanged: (SavedCameraSettings) -> Unit,
    onReset: () -> Unit,
    onOpenHelp: () -> Unit,
    onShowOnboarding: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit
) {
    var resetConfirmationVisible by remember { mutableStateOf(false) }

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
            Text(
                text = "Настройки приложения",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Общие параметры съёмки и интерфейса",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            SettingsSwitchCard(
                title = "Звук после серии",
                description = "Короткий сигнал после успешного завершения серии.",
                checked = settings.soundAfterSeries,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(soundAfterSeries = it))
                }
            )
        }
        item {
            SettingsSwitchCard(
                title = "Вибрация после серии",
                description = "Вибрация после завершения или остановки серии.",
                checked = settings.vibrationAfterSeries,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(vibrationAfterSeries = it))
                }
            )
        }
        item {
            SettingsChoiceCard(
                title = "Качество JPEG",
                description = "Используется для съёмки, stacking и Edited JPEG."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CameraSettingsStore.JPEG_QUALITY_VALUES.sorted().forEach { quality ->
                        FilterChip(
                            selected = settings.jpegQuality == quality,
                            onClick = {
                                onSettingsChanged(settings.copy(jpegQuality = quality))
                            },
                            label = { Text(quality.toString()) }
                        )
                    }
                }
            }
        }
        item {
            SettingsSwitchCard(
                title = "Быстрый preview",
                description = if (settings.fastPreviewEnabled) {
                    "Длинная выдержка применяется к снимку, а preview остаётся плавным."
                } else {
                    "Ручная выдержка применяется к preview — изображение может замирать."
                },
                checked = settings.fastPreviewEnabled,
                onCheckedChange = {
                    onSettingsChanged(
                        settings.copy(
                            fastPreviewEnabled = it,
                            applyLongExposureToPreview = !it
                        )
                    )
                },
                warning = !settings.fastPreviewEnabled
            )
        }
        item {
            SettingsSwitchCard(
                title = "Гистограмма в preview",
                description = "Лёгкий анализ яркости поверх изображения камеры.",
                checked = settings.histogramEnabled,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(histogramEnabled = it))
                }
            )
        }
        item {
            SettingsSwitchCard(
                title = "Сохранять пробные кадры",
                description = "Пробные JPEG сохраняются в Tests/JPEG текущей сессии.",
                checked = settings.saveTestShots,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(saveTestShots = it))
                }
            )
        }
        item {
            SettingsChoiceCard(
                title = "Тема интерфейса",
                description = "Очень тёмная тема уменьшает яркость фоновых поверхностей."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode.name,
                            onClick = {
                                onSettingsChanged(settings.copy(themeMode = mode.name))
                            },
                            label = {
                                Text(
                                    if (mode == AppThemeMode.DARK) {
                                        "Тёмная"
                                    } else {
                                        "Очень тёмная"
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            SettingsSwitchCard(
                title = "Защита от случайного удаления",
                description = "Требует ввода слова УДАЛИТЬ перед удалением сессии.",
                checked = settings.deletionProtectionEnabled,
                onCheckedChange = {
                    onSettingsChanged(
                        settings.copy(deletionProtectionEnabled = it)
                    )
                }
            )
        }
        item {
            Button(
                onClick = onOpenHelp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text("Помощь")
            }
            Button(
                onClick = onOpenAbout,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(top = 8.dp)
            ) {
                Text("О приложении")
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
            TextButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Дополнительно: диагностика камеры")
            }
            TextButton(
                onClick = onShowOnboarding,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Показать обучение снова")
            }
        }
        item {
            Button(
                onClick = { resetConfirmationVisible = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text("Сбросить настройки")
            }
            Text(
                text = "Фото и сессии не удаляются.",
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Назад к диагностике")
            }
        }
    }

    if (resetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { resetConfirmationVisible = false },
            title = { Text("Сбросить настройки?") },
            text = {
                Text("Будут восстановлены значения по умолчанию. Фото и сессии останутся.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        resetConfirmationVisible = false
                        onReset()
                    }
                ) {
                    Text("Сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirmationVisible = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun SettingsSwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    warning: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    color = if (warning) Color(0xFFFFCC80) else Color(0xFFB8BECC)
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingsChoiceCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = Color(0xFFB8BECC))
            content()
        }
    }
}
