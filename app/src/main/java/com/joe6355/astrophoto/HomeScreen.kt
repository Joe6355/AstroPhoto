package com.joe6355.astrophoto

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joe6355.astrophoto.ui.AstroPrimaryButton
import com.joe6355.astrophoto.ui.AstroTestTags
import com.joe6355.astrophoto.ui.AstroTextButton
import com.joe6355.astrophoto.ui.OrbitArtwork
import com.joe6355.astrophoto.ui.theme.AstroColors

@Composable
fun AstroHomeScreen(
    onOpenCamera: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    modifier: Modifier = Modifier,
    recentSession: SessionSummary? = null,
    onOpenRecentSession: () -> Unit = onOpenSessions
) {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val heroText = if (light) AstroColors.TextPrimary else MaterialTheme.colorScheme.onBackground
    val heroSecondary = if (light) AstroColors.TextSecondary else MaterialTheme.colorScheme.onSurfaceVariant
    val heroAccent = if (light) AstroColors.Primary else MaterialTheme.colorScheme.primary
    LazyColumn(
        modifier = modifier.fillMaxSize().safeDrawingPadding().testTag(AstroTestTags.HomeScreen),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        item {
            Column(Modifier.fillMaxWidth().testTag(AstroTestTags.HomeMainContent),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("AstroPhoto", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    AstroTextButton("Настройки", onOpenSettings)
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 286.dp).clip(MaterialTheme.shapes.large)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)) {
                    OrbitArtwork(Modifier.matchParentSize())
                    Column(Modifier.align(Alignment.BottomStart).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("ВАША СЛЕДУЮЩАЯ НОЧЬ", style = MaterialTheme.typography.labelMedium,
                            color = heroAccent, letterSpacing = 1.2.sp)
                        Text("Ближе\nк звёздам.", color = heroText,
                            style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp, lineHeight = 40.sp,
                                fontWeight = FontWeight.Normal, letterSpacing = (-1).sp))
                        Text("От первого кадра\nдо вашего снимка неба.", style = MaterialTheme.typography.bodyMedium,
                            color = heroSecondary)
                    }
                }
                Column {
                    AstroPrimaryButton("Начать съёмку", onOpenCamera,
                        Modifier.fillMaxWidth().testTag(AstroTestTags.HomePrimaryAction))
                    Row(Modifier.fillMaxWidth().testTag(AstroTestTags.HomeSecondaryNavigation)) {
                        AstroTextButton("Подготовка", onOpenHelp, Modifier.weight(1f))
                        AstroTextButton("Камера", onOpenCamera, Modifier.weight(1f))
                        AstroTextButton("Обработка", onOpenSessions, Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (recentSession == null) "Ваши съёмки" else "Продолжить работу",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    AstroTextButton("Все", onOpenSessions)
                }
                Card(
                    onClick = if (recentSession == null) onOpenSessions else onOpenRecentSession,
                    modifier = Modifier.fillMaxWidth().testTag(AstroTestTags.HomeSessions),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                    recentSession?.let { session ->
                        SessionCover(session, Modifier.size(58.dp, 68.dp).clip(MaterialTheme.shapes.small), maxSize = 160)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(recentSession?.sessionName ?: "Кадры и готовые результаты",
                            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(recentSession?.let { "Основные: ${it.lightFrames} · Тёмные: ${it.darkFrames}" }
                            ?: "Откройте съёмки, чтобы выбрать кадры для обработки.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (recentSession == null) "Открыть съёмки →" else "Открыть съёмку →",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).testTag(AstroTestTags.HomeFooter)) {
                AstroTextButton("О приложении", onOpenAbout, Modifier.weight(1f))
                AstroTextButton("Самопроверка", onOpenSelfCheck, Modifier.weight(1f))
            }
        }
    }
}
