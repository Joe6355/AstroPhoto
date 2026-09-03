package com.joe6355.astrophoto

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
import com.joe6355.astrophoto.ui.AstroScaffold
import com.joe6355.astrophoto.ui.AstroSpacing
import com.joe6355.astrophoto.ui.theme.AstroColors

enum class HelpTopic {
    QUICK_START,
    CAMERA_COMPATIBILITY,
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
    PROCESSING,
    TIMELAPSE,
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
        "Закрепите телефон на штативе, выберите Astro Mode или ручные ISO, " +
            "выдержку и фокус. Укажите JPEG либо RAW/DNG, количество кадров, " +
            "задержку и таймер, затем запустите серию. Результаты сохраняются " +
            "в сессии, где доступны отбор, обработка, таймлапс и экспорт."
    ),
    HelpSection(
        HelpTopic.CAMERA_COMPATIBILITY,
        "Камера и совместимость",
        "Приложение выбирает заднюю камеру с ручным управлением и использует " +
            "диапазоны выдержки и ISO, которые сообщает Camera2. Приложение " +
            "проверяет публичные и доступные vendor-диапазоны всех задних камер " +
            "и выбирает лучший совместимый сенсор. Фактические параметры " +
            "проверяются по результату каждого обычного снимка."
    ),
    HelpSection(
        HelpTopic.ISO,
        "ISO",
        "Чем выше ISO, тем светлее кадр, но больше шума. Доступный диапазон " +
            "зависит от выбранной камеры; приложение ограничивает регулятор " +
            "фактически доступными значениями. После снимка под регулятором " +
            "показаны запрошенное и реально применённое камерой ISO."
    ),
    HelpSection(
        HelpTopic.EXPOSURE,
        "Выдержка",
        "Регулятор покрывает диапазон, заявленный выбранной камерой. Длинная " +
            "выдержка применяется к сохраняемому кадру; быстрый preview может " +
            "оставаться автоматическим. Если камера ограничивает значение, " +
            "приложение запоминает фактический предел после обычного снимка. " +
            "Значок Σ означает суммарное накопление: недоступные 30 секунд " +
            "автоматически разбиваются на несколько нативных выдержек для stacking."
    ),
    HelpSection(
        HelpTopic.INFINITY_FOCUS,
        "Фокус ∞",
        "Для звёзд обычно нужен фокус на бесконечность. Если кадр выглядит " +
            "мыльным, проверьте устойчивость штатива и переключитесь между " +
            "фокусом ∞, ручным фокусом и автофокусом. Пробный кадр показывает " +
            "FWHM звёзд: сравнивайте последовательные кадры, меньшее значение " +
            "означает более точный фокус."
    ),
    HelpSection(
        HelpTopic.RAW,
        "JPEG и RAW/DNG",
        "JPEG занимает меньше места и используется для встроенного stacking и " +
            "MP4-таймлапса. RAW/DNG сохраняет больше исходных данных для " +
            "дальнейшей обработки, но требует больше места и может не " +
            "отображаться обычной галереей."
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
        "Dark Frames снимаются с закрытым объективом при тех же ISO и выдержке, " +
            "что и Light Frames. Значение 0 отключает их съёмку. Доступны также " +
            "3, 5, 10 и 20 кадров."
    ),
    HelpSection(
        HelpTopic.SERIES,
        "Серии кадров",
        "Доступны серии от 1 до 500 кадров, включая 100, 150, 200, 300 и 500. " +
            "Можно задать паузу между снимками и таймер старта. Серия продолжает " +
            "работать при свёрнутом приложении, а ход и остановка доступны в " +
            "уведомлении. При критической нехватке места, заряде 5% без питания " +
            "или сильном перегреве съёмка безопасно остановится."
    ),
    HelpSection(
        HelpTopic.STACKING,
        "Stacking",
        "В деталях сессии выберите подходящие Light JPEG, профиль обработки и " +
            "запустите stacking. Обработка может продолжаться в фоне; исходные " +
            "кадры при этом не изменяются."
    ),
    HelpSection(
        HelpTopic.STACKING_METHODS,
        "Average / Median / Sigma",
        "Average — простой и быстрый метод. Median лучше убирает случайные " +
            "артефакты. Sigma clipping отбрасывает выбросы и рассчитан на " +
            "серии из нескольких кадров."
    ),
    HelpSection(
        HelpTopic.ALIGNMENT,
        "Alignment",
        "Alignment выравнивает кадры перед stacking. Он помогает, если телефон " +
            "или изображение немного сдвинулись между снимками."
    ),
    HelpSection(
        HelpTopic.PROCESSING,
        "Отбор и обработка",
        "Перед stacking можно отметить бракованные кадры. Автоматический отбор " +
            "и выравнивание исключают неподходящие снимки; если хороших кадров " +
            "недостаточно, приложение сообщит об этом. В серии длиннее 30 снимков " +
            "для полноразмерной обработки выбираются 30 лучших кадров с сохранением " +
            "покрытия всей серии. Результаты находятся в разделе Processed выбранной сессии."
    ),
    HelpSection(
        HelpTopic.TIMELAPSE,
        "MP4-таймлапс",
        "Таймлапс создаётся из подходящих Light JPEG в порядке съёмки. Доступны " +
            "2, 5, 10, 20 и 30 кадр/с. Отмеченные как брак кадры не попадают " +
            "в видео; MP4 сохраняется в Movies/AstroPhoto."
    ),
    HelpSection(
        HelpTopic.SESSIONS,
        "Сессии",
        "Сессия хранит Light JPEG/RAW, Dark JPEG/RAW, результаты обработки и " +
            "настройки съёмки. Создавайте отдельную сессию для каждого объекта " +
            "или ночной съёмки."
    ),
    HelpSection(
        HelpTopic.EXPORT,
        "Экспорт на ПК",
        "Экспорт ZIP собирает исходные JPEG/RAW, Dark Frames, результаты, " +
            "метаданные и README сессии. Архив можно передать через системное " +
            "меню «Поделиться»."
    ),
    HelpSection(
        HelpTopic.PROBLEMS,
        "Частые проблемы",
        "Кадр тёмный — увеличьте ISO или выдержку; пересвечен — уменьшите их. " +
            "Смаз — проверьте штатив и фокус. Недоступны ручные значения — " +
            "откройте диагностику камеры. При нехватке места остановите серию, " +
            "экспортируйте нужные сессии и только затем удаляйте лишние данные."
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
                text = "Актуальная справка по съёмке и обработке в AstroPhoto",
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

internal val ASTROPHOTO_ONBOARDING_PAGES = listOf(
    "Подготовьте камеру" to
        "Закрепите телефон на штативе. Astro Mode задаст стартовые параметры, " +
        "а ISO, выдержку и фокус можно скорректировать вручную.",
    "Выберите формат и серию" to
        "JPEG подходит для обработки и таймлапса в приложении. RAW/DNG хранит " +
        "больше исходных данных. В серии доступно до 500 кадров.",
    "Настройте запуск" to
        "Выберите количество кадров, задержку между ними и таймер старта, затем " +
        "не двигайте телефон до окончания серии.",
    "Dark Frames необязательны" to
        "Для Dark Frames закройте объектив и сохраните те же ISO и выдержку. " +
        "Выберите 0, если тёмные кадры не нужны.",
    "Обработайте сессию" to
        "Отметьте брак, выполните stacking, создайте MP4 с частотой до 30 кадр/с " +
        "или экспортируйте ZIP для обработки на компьютере."
)

@Composable
fun OnboardingDialog(
    onFinished: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    val current = ASTROPHOTO_ONBOARDING_PAGES[page]
    AlertDialog(
        onDismissRequest = {},
        title = { Text(current.first) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.second)
                Text(
                    text = "${page + 1} из ${ASTROPHOTO_ONBOARDING_PAGES.size}",
                color = AstroColors.TextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (page == ASTROPHOTO_ONBOARDING_PAGES.lastIndex) {
                        onFinished()
                    } else {
                        page++
                    }
                }
            ) {
                Text(
                    if (page == ASTROPHOTO_ONBOARDING_PAGES.lastIndex) {
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
