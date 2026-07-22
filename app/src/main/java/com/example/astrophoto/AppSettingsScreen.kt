package com.example.astrophoto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.astrophoto.ui.AstroConfirmationDialog
import com.example.astrophoto.ui.AstroExpandableSection
import com.example.astrophoto.ui.AstroScaffold
import com.example.astrophoto.ui.AstroSecondaryButton
import com.example.astrophoto.ui.AstroSegmentedControl
import com.example.astrophoto.ui.AstroSettingRow
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.AstroTextButton

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

    AstroScaffold(title = "Настройки", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AstroSpacing.Lg,
                top = AstroSpacing.Md,
                end = AstroSpacing.Lg,
                bottom = AstroSpacing.Xxxl
            ),
            verticalArrangement = Arrangement.spacedBy(AstroSpacing.Md)
        ) {
            item {
                Text(
                    text = "Параметры съёмки, обработки и интерфейса",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                AstroExpandableSection(
                    title = "Настройки камеры",
                    initiallyExpanded = true
                ) {
                    Text("Качество JPEG", style = MaterialTheme.typography.bodyLarge)
                    AstroSegmentedControl(
                        options = CameraSettingsStore.JPEG_QUALITY_VALUES.sorted(),
                        selected = settings.jpegQuality,
                        label = Int::toString,
                        onSelected = {
                            onSettingsChanged(settings.copy(jpegQuality = it))
                        }
                    )
                    AstroSettingRow(
                        title = "Быстрый preview",
                        description = if (settings.fastPreviewEnabled) {
                            "Длинная выдержка применяется только к снимку"
                        } else {
                            "Ручная выдержка применяется и к preview"
                        },
                        checked = settings.fastPreviewEnabled,
                        onCheckedChange = {
                            onSettingsChanged(
                                settings.copy(
                                    fastPreviewEnabled = it,
                                    applyLongExposureToPreview = !it
                                )
                            )
                        }
                    )
                    AstroSettingRow(
                        title = "Гистограмма в preview",
                        description = "Анализ яркости поверх изображения камеры",
                        checked = settings.histogramEnabled,
                        onCheckedChange = {
                            onSettingsChanged(settings.copy(histogramEnabled = it))
                        }
                    )
                    AstroSettingRow(
                        title = "Звук после серии",
                        checked = settings.soundAfterSeries,
                        onCheckedChange = {
                            onSettingsChanged(settings.copy(soundAfterSeries = it))
                        }
                    )
                    AstroSettingRow(
                        title = "Вибрация после серии",
                        checked = settings.vibrationAfterSeries,
                        onCheckedChange = {
                            onSettingsChanged(settings.copy(vibrationAfterSeries = it))
                        }
                    )
                }
            }

            item {
                AstroExpandableSection(title = "Настройки обработки") {
                    AstroSettingRow(
                        title = "Сохранять пробные кадры",
                        description = "JPEG сохраняются в Tests текущей сессии",
                        checked = settings.saveTestShots,
                        onCheckedChange = {
                            onSettingsChanged(settings.copy(saveTestShots = it))
                        }
                    )
                }
            }

            item {
                AstroExpandableSection(title = "Хранилище") {
                    AstroSettingRow(
                        title = "Защита от случайного удаления",
                        description = "Перед удалением сессии требуется слово УДАЛИТЬ",
                        checked = settings.deletionProtectionEnabled,
                        onCheckedChange = {
                            onSettingsChanged(
                                settings.copy(deletionProtectionEnabled = it)
                            )
                        }
                    )
                }
            }

            item {
                AstroExpandableSection(title = "Интерфейс") {
                    Text("Тема приложения", style = MaterialTheme.typography.bodyLarge)
                    AstroSegmentedControl(
                        options = AppThemeMode.entries,
                        selected = AppThemeMode.entries.firstOrNull {
                            it.name == settings.themeMode
                        } ?: AppThemeMode.DARK,
                        label = {
                            when (it) {
                                AppThemeMode.LIGHT -> "Светлая"
                                AppThemeMode.DARK -> "Тёмная"
                                AppThemeMode.VERY_DARK -> "Очень тёмная"
                            }
                        },
                        onSelected = {
                            onSettingsChanged(settings.copy(themeMode = it.name))
                        }
                    )
                }
            }

            item {
                AstroExpandableSection(title = "Дополнительно") {
                    AstroSecondaryButton(
                        text = "Самопроверка",
                        onClick = onOpenSelfCheck,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AstroSecondaryButton(
                        text = "Диагностика камеры",
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AstroTextButton(
                        text = "Показать обучение снова",
                        onClick = onShowOnboarding,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AstroTextButton(
                        text = "Сбросить настройки",
                        onClick = { resetConfirmationVisible = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Фото и сессии при сбросе не удаляются",
                        modifier = Modifier.padding(horizontal = AstroSpacing.Sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                AstroExpandableSection(title = "О приложении") {
                    AstroSecondaryButton(
                        text = "Помощь",
                        onClick = onOpenHelp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AstroTextButton(
                        text = "Открыть сведения о приложении",
                        onClick = onOpenAbout,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (resetConfirmationVisible) {
        AstroConfirmationDialog(
            title = "Сбросить настройки?",
            message = "Будут восстановлены значения по умолчанию. Фото и сессии останутся.",
            confirmText = "Сбросить",
            onConfirm = {
                resetConfirmationVisible = false
                onReset()
            },
            onDismiss = { resetConfirmationVisible = false }
        )
    }
}
