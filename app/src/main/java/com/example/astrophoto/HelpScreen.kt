package com.example.astrophoto

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.astrophoto.ui.AstroScaffold
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.theme.AstroColors

enum class HelpTopic {
    QUICK_START,
    ISO,
    EXPOSURE,
    INFINITY_FOCUS,
    RAW,
    LIGHTS,
    DARKS,
    SERIES,
    STACKING,
    STACKING_METHODS,
    ALIGNMENT,
    SESSIONS,
    EXPORT,
    PROBLEMS
}

data class HelpSection(
    val topic: HelpTopic,
    val title: String,
    val text: String
)

val ASTROPHOTO_HELP_SECTIONS = listOf(
    HelpSection(
        HelpTopic.QUICK_START,
        "Быстрый старт",
        "Выберите Astro Mode и поставьте телефон на штатив. Сделайте пробный " +
            "кадр. Если экспозиция и фокус подходят, запустите RAW-серию. После " +
            "серии снимите Dark Frames, затем используйте stacking или " +
            "экспортируйте ZIP на ПК."
    ),
    HelpSection(
        HelpTopic.ISO,
        "ISO",
        "Чем выше ISO, тем светлее кадр, но больше шума. Низкое ISO даёт более " +
            "чистое, но тёмное изображение. Для звёзд обычно подходит ISO " +
            "800–1600, для Луны — ISO 50–100."
    ),
    HelpSection(
        HelpTopic.EXPOSURE,
        "Выдержка",
        "Длинная выдержка собирает больше света. Телефон может ограничивать её " +
            "максимум. Если доступно только около 30 секунд, снимайте серию и " +
            "складывайте кадры."
    ),
    HelpSection(
        HelpTopic.INFINITY_FOCUS,
        "Фокус ∞",
        "Для звёзд обычно нужен фокус на бесконечность. Если кадр выглядит " +
            "мыльным, снова проверьте фокус, устойчивость штатива и сделайте " +
            "пробный кадр."
    ),
    HelpSection(
        HelpTopic.RAW,
        "RAW/DNG",
        "RAW сохраняет больше исходных данных и лучше подходит для обработки на " +
            "ПК, но занимает больше места. Обычная галерея может не показывать " +
            "DNG — ищите их через файловый менеджер."
    ),
    HelpSection(
        HelpTopic.LIGHTS,
        "Light Frames",
        "Light Frames — обычные кадры неба или объекта. Их складывают, чтобы " +
            "усилить полезный сигнал и сделать слабые детали заметнее."
    ),
    HelpSection(
        HelpTopic.DARKS,
        "Dark Frames",
        "Dark Frames снимаются с закрытой камерой. Они нужны для вычитания шума. " +
            "Снимайте их с теми же ISO и выдержкой, что и основные кадры."
    ),
    HelpSection(
        HelpTopic.SERIES,
        "Серии кадров",
        "Серия помогает накопить больше света. Например, 10 кадров по 30 секунд " +
            "дают 300 секунд суммарной экспозиции и обходят лимит одиночной " +
            "выдержки."
    ),
    HelpSection(
        HelpTopic.STACKING,
        "Stacking",
        "Stacking складывает несколько кадров в один, уменьшает случайный шум и " +
            "помогает проявить слабые звёзды и объекты."
    ),
    HelpSection(
        HelpTopic.STACKING_METHODS,
        "Average / Median / Sigma",
        "Average — простой и быстрый метод. Median лучше убирает случайные " +
            "артефакты. Sigma clipping отбрасывает выбросы, но наиболее полезен " +
            "при большем количестве кадров."
    ),
    HelpSection(
        HelpTopic.ALIGNMENT,
        "Alignment",
        "Alignment выравнивает кадры перед stacking. Он помогает, если телефон " +
            "или изображение немного сдвинулись между снимками."
    ),
    HelpSection(
        HelpTopic.SESSIONS,
        "Сессии",
        "Сессия хранит Lights, Darks, Processed, пробные кадры и настройки. Лучше " +
            "создавать отдельную сессию для каждой ночной съёмки или объекта."
    ),
    HelpSection(
        HelpTopic.EXPORT,
        "Экспорт на ПК",
        "ZIP содержит RAW, JPEG, Darks, Processed и README. RAW/DNG можно " +
            "обработать в Siril, DeepSkyStacker, darktable, RawTherapee или " +
            "другом подходящем ПО."
    ),
    HelpSection(
        HelpTopic.PROBLEMS,
        "Частые проблемы",
        "Ничего не видно — увеличьте ISO или выдержку. Пересвет — уменьшите ISO " +
            "или выдержку. Всё мыльное — проверьте фокус ∞ и штатив. DNG не " +
            "видно — откройте файловый менеджер. Если мало места, уменьшите " +
            "RAW-серию либо экспортируйте и очистите старые сессии."
    )
)

@Composable
fun HelpScreen(
    initialTopic: HelpTopic? = null,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var expandedTopic by remember(initialTopic) { mutableStateOf(initialTopic) }
    val filteredSections = remember(query) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            ASTROPHOTO_HELP_SECTIONS
        } else {
            ASTROPHOTO_HELP_SECTIONS.filter {
                it.title.contains(normalized, ignoreCase = true) ||
                    it.text.contains(normalized, ignoreCase = true)
            }
        }
    }

    AstroScaffold(title = "Помощь", onBack = onBack) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            AstroSpacing.Lg,
            AstroSpacing.Md,
            AstroSpacing.Lg,
            AstroSpacing.Xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Короткая справка по астрофотографии и AstroPhoto",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск по справке") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
        }
        items(filteredSections, key = { it.topic.name }) { section ->
            val expanded = expandedTopic == section.topic
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expandedTopic = if (expanded) null else section.topic
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(if (expanded) "˅" else "˄")
                    }
                    if (expanded) {
                        Text(
                            text = section.text,
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AstroColors.TextSecondary
                        )
                    }
                }
            }
        }
        if (filteredSections.isEmpty()) {
            item {
                Text(
                    text = "По вашему запросу ничего не найдено.",
                    color = AstroColors.TextSecondary
                )
            }
        }
    }
    }
}

@Composable
fun HelpTopicDialog(
    topic: HelpTopic,
    onOpenHelp: (HelpTopic) -> Unit,
    onDismiss: () -> Unit
) {
    val section = ASTROPHOTO_HELP_SECTIONS.first { it.topic == topic }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(section.title) },
        text = { Text(section.text) },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onOpenHelp(topic)
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Открыть справку")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

private val ONBOARDING_PAGES = listOf(
    "Снимайте серию, чтобы накопить свет" to
        "Несколько кадров дают больше полезного сигнала, чем один снимок.",
    "RAW/DNG лучше для обработки" to
        "RAW сохраняет больше данных и даёт больше возможностей на компьютере.",
    "Dark Frames помогают убрать шум" to
        "Снимайте их с закрытой камерой и теми же ISO и выдержкой.",
    "Используйте Astro Mode для старта" to
        "Он подготовит базовые настройки, после чего сделайте пробный кадр."
)

@Composable
fun OnboardingDialog(
    onFinished: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    val current = ONBOARDING_PAGES[page]
    AlertDialog(
        onDismissRequest = {},
        title = { Text(current.first) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.second)
                Text(
                    text = "${page + 1} из ${ONBOARDING_PAGES.size}",
                color = AstroColors.TextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (page == ONBOARDING_PAGES.lastIndex) {
                        onFinished()
                    } else {
                        page++
                    }
                }
            ) {
                Text(
                    if (page == ONBOARDING_PAGES.lastIndex) {
                        "Готово"
                    } else {
                        "Дальше"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onFinished) {
                Text("Пропустить")
            }
        }
    )
}
