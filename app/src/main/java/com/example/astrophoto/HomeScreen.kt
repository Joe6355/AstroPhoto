package com.example.astrophoto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.astrophoto.ui.AstroCard
import com.example.astrophoto.ui.AstroPrimaryButton
import com.example.astrophoto.ui.AstroSecondaryButton
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.AstroTestTags
import com.example.astrophoto.ui.AstroTextButton

@Composable
fun AstroHomeScreen(
    onOpenCamera: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .testTag(AstroTestTags.HomeScreen),
        contentPadding = PaddingValues(
            start = AstroSpacing.Lg,
            top = AstroSpacing.Xl,
            end = AstroSpacing.Lg,
            bottom = AstroSpacing.Xxl
        ),
        verticalArrangement = Arrangement.spacedBy(AstroSpacing.Lg)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AstroSpacing.Xs)) {
                Text(
                    text = "AstroPhoto",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Ночная съёмка и обработка звёздного неба",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            AstroPrimaryButton(
                text = "Новая съёмка",
                onClick = onOpenCamera,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AstroTestTags.HomePrimaryAction)
            )
        }

        item {
            AstroCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AstroTestTags.HomeSessions)
            ) {
                Text("Сессии", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Кадры, обработка и готовые результаты",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AstroSecondaryButton(
                    text = "Открыть сессии",
                    onClick = onOpenSessions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AstroTestTags.HomeSecondaryNavigation),
                verticalArrangement = Arrangement.spacedBy(AstroSpacing.Sm)
            ) {
                Text(
                    text = "Дополнительно",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AstroSpacing.Sm)
                ) {
                    AstroSecondaryButton(
                        text = "Настройки",
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f)
                    )
                    AstroSecondaryButton(
                        text = "Помощь",
                        onClick = onOpenHelp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.fillParentMaxHeight(0.2f))
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AstroTestTags.HomeFooter),
                horizontalArrangement = Arrangement.spacedBy(AstroSpacing.Sm)
            ) {
                AstroTextButton(
                    text = "О приложении",
                    onClick = onOpenAbout,
                    modifier = Modifier.weight(1f)
                )
                AstroTextButton(
                    text = "Самопроверка",
                    onClick = onOpenSelfCheck,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
