package com.example.astrophoto

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class ShootingGoal(val title: String) {
    STARS("Звёзды"),
    MOON("Луна"),
    CITY_SKY("Городское небо"),
    TEST("Тест"),
    HANDHELD("С рук")
}

enum class AssistantCaptureFormat {
    JPEG,
    RAW
}

data class ExposureRecommendation(
    val iso: Int,
    val exposureTimeNs: Long,
    val focusMode: CameraFocusMode,
    val format: AssistantCaptureFormat,
    val frameCount: Int,
    val timerSeconds: Int,
    val explanation: String
) {
    val summary: String
        get() = "ISO $iso, ${formatAssistantExposure(exposureTimeNs)}, " +
            formatAssistantFocus(focusMode)
}

fun buildExposureRecommendation(
    goal: ShootingGoal,
    testShot: TestShotResult?,
    capabilities: ManualCameraCapabilities,
    currentIso: Int,
    currentExposureTimeNs: Long
): ExposureRecommendation? {
    if (testShot == null) return null
    val isoRange = capabilities.isoRange ?: (50..3200)
    val exposureRange = capabilities.exposureRangeNs ?: (1_000_000L..30_000_000_000L)
    val base = goal.baseProfile()
    var iso = base.iso.coerceIn(isoRange.first, isoRange.last)
    var exposure = base.exposureTimeNs.coerceIn(
        exposureRange.first,
        exposureRange.last
    )
    var focus = if (
        base.focusMode == CameraFocusMode.INFINITY &&
        !capabilities.supportsManualFocus
    ) {
        CameraFocusMode.AF
    } else {
        base.focusMode
    }
    var timer = base.timerSeconds
    val explanation = mutableListOf<String>()

    when (testShot.status) {
        TestShotStatus.TOO_DARK -> {
            iso = maxOf(iso, currentIso.coerceIn(isoRange.first, isoRange.last))
            exposure = maxOf(
                exposure,
                currentExposureTimeNs.coerceIn(
                    exposureRange.first,
                    exposureRange.last
                )
            )
            if (goal == ShootingGoal.HANDHELD) {
                iso = nextIso(
                    current = iso,
                    range = isoRange,
                    brighter = true
                ) ?: iso
                exposure = 33_333_333L.coerceIn(
                    exposureRange.first,
                    exposureRange.last
                )
                explanation +=
                    "С рук длинная выдержка даст смаз, поэтому оставляем 1/30 сек."
                explanation += "Кадр тёмный, поэтому увеличиваем ISO до $iso."
            } else {
                val brighterExposure = moveExposure(
                    current = exposure,
                    range = exposureRange,
                    steps = if (goal == ShootingGoal.STARS) 2 else 1,
                    brighter = true
                )
                if (brighterExposure != null && brighterExposure > exposure) {
                    exposure = brighterExposure
                    explanation +=
                        "Кадр тёмный, поэтому увеличиваем выдержку до " +
                            "${formatAssistantExposure(exposure)}."
                } else {
                    val brighterIso = nextIso(iso, isoRange, brighter = true)
                    if (brighterIso != null) {
                        iso = brighterIso
                        explanation +=
                            "Выдержка уже высокая, поэтому увеличиваем ISO до $iso."
                    } else {
                        explanation +=
                            "Телефон уже на максимальных настройках. " +
                                "Нужна серия кадров и stacking."
                    }
                }
            }
        }

        TestShotStatus.OVEREXPOSED,
        TestShotStatus.TOO_BRIGHT -> {
            iso = minOf(iso, currentIso.coerceIn(isoRange.first, isoRange.last))
            exposure = minOf(
                exposure,
                currentExposureTimeNs.coerceIn(
                    exposureRange.first,
                    exposureRange.last
                )
            )
            val darkerIso = nextIso(iso, isoRange, brighter = false)
            if (darkerIso != null) {
                iso = darkerIso
                explanation += "Есть пересвет, уменьшаем ISO до $iso."
            } else {
                moveExposure(
                    current = exposure,
                    range = exposureRange,
                    steps = 2,
                    brighter = false
                )?.let {
                    exposure = it
                    explanation +=
                        "ISO уже минимальное, уменьшаем выдержку до " +
                            "${formatAssistantExposure(exposure)}."
                }
            }
            if (goal == ShootingGoal.MOON) {
                explanation +=
                    "Для Луны подойдут пресеты «Луна» или «Если пересвечивает»."
            }
        }

        TestShotStatus.BLURRY -> {
            timer = if (goal == ShootingGoal.HANDHELD) 3 else 5
            if (
                goal == ShootingGoal.STARS ||
                goal == ShootingGoal.MOON ||
                goal == ShootingGoal.CITY_SKY
            ) {
                focus = if (capabilities.supportsManualFocus) {
                    CameraFocusMode.INFINITY
                } else {
                    CameraFocusMode.AF
                }
            }
            if (goal == ShootingGoal.HANDHELD) {
                exposure = 33_333_333L.coerceIn(
                    exposureRange.first,
                    exposureRange.last
                )
            }
            explanation +=
                "Кадр смазан: используйте штатив, таймер и проверьте фокус."
        }

        TestShotStatus.NORMAL -> {
            explanation += "Настройки подходят. Можно запускать серию."
            if (goal == ShootingGoal.STARS) {
                explanation +=
                    "Для звёзд лучше RAW/DNG, серия от 10 кадров и Dark Frames."
            }
        }

        TestShotStatus.UNKNOWN -> {
            explanation +=
                "Кадр не удалось уверенно оценить. Сделайте ещё один пробный кадр."
        }
    }

    explanation += goal.explanation()
    return ExposureRecommendation(
        iso = iso.coerceIn(isoRange.first, isoRange.last),
        exposureTimeNs = exposure.coerceIn(
            exposureRange.first,
            exposureRange.last
        ),
        focusMode = focus,
        format = if (
            base.format == AssistantCaptureFormat.RAW &&
            !capabilities.supportsRawCapture
        ) {
            AssistantCaptureFormat.JPEG
        } else {
            base.format
        },
        frameCount = base.frameCount,
        timerSeconds = timer,
        explanation = explanation.distinct().joinToString(" ")
    )
}

@Composable
fun ExposureAssistantCard(
    goal: ShootingGoal,
    testShot: TestShotResult?,
    currentIso: Int,
    currentExposureTimeNs: Long,
    currentFocusMode: CameraFocusMode,
    recommendation: ExposureRecommendation?,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    onGoalChanged: (ShootingGoal) -> Unit,
    onApply: () -> Unit,
    onTestShot: () -> Unit,
    onStartSeries: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF172033))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChanged(!expanded) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Помощник экспозиции",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = recommendation?.let {
                            "Рекомендация: ${it.summary}"
                        } ?: "Сначала сделайте пробный кадр.",
                        color = Color(0xFFD5DBE8),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(if (expanded) "˅" else "˄")
            }

            if (expanded) {
                Text(
                    text = "Цель съёмки",
                    modifier = Modifier.padding(top = 10.dp),
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShootingGoal.entries.forEach { item ->
                        FilterChip(
                            selected = goal == item,
                            onClick = { onGoalChanged(item) },
                            label = { Text(item.title) }
                        )
                    }
                }

                Text(
                    text = "Статус: ${testShot?.status?.title ?: "нет пробного кадра"}",
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = "Сейчас: ISO $currentIso, " +
                        "${formatAssistantExposure(currentExposureTimeNs)}, " +
                        formatAssistantFocus(currentFocusMode),
                    color = Color(0xFFB8BECC)
                )
                recommendation?.let {
                    Text(
                        text = "Рекомендуется: ${it.summary}, " +
                            "${it.format.name}, ${it.frameCount} кадров, " +
                            "таймер ${it.timerSeconds} сек",
                        modifier = Modifier.padding(top = 5.dp),
                        color = Color(0xFFA5D6A7),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = it.explanation,
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = onApply,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text("Применить рекомендацию")
                    }
                }
                Button(
                    onClick = onTestShot,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Text("Сделать ещё пробный кадр")
                }
                Button(
                    onClick = onStartSeries,
                    enabled = recommendation != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Text("Запустить серию с этими настройками")
                }
            }
        }
    }
}

private data class GoalProfile(
    val iso: Int,
    val exposureTimeNs: Long,
    val focusMode: CameraFocusMode,
    val format: AssistantCaptureFormat,
    val frameCount: Int,
    val timerSeconds: Int
)

private fun ShootingGoal.baseProfile(): GoalProfile = when (this) {
    ShootingGoal.STARS -> GoalProfile(
        iso = 800,
        exposureTimeNs = 10_000_000_000L,
        focusMode = CameraFocusMode.INFINITY,
        format = AssistantCaptureFormat.RAW,
        frameCount = 10,
        timerSeconds = 5
    )
    ShootingGoal.MOON -> GoalProfile(
        iso = 100,
        exposureTimeNs = 4_000_000L,
        focusMode = CameraFocusMode.INFINITY,
        format = AssistantCaptureFormat.JPEG,
        frameCount = 1,
        timerSeconds = 3
    )
    ShootingGoal.CITY_SKY -> GoalProfile(
        iso = 400,
        exposureTimeNs = 5_000_000_000L,
        focusMode = CameraFocusMode.INFINITY,
        format = AssistantCaptureFormat.RAW,
        frameCount = 5,
        timerSeconds = 5
    )
    ShootingGoal.TEST -> GoalProfile(
        iso = 400,
        exposureTimeNs = 33_333_333L,
        focusMode = CameraFocusMode.AF,
        format = AssistantCaptureFormat.JPEG,
        frameCount = 1,
        timerSeconds = 0
    )
    ShootingGoal.HANDHELD -> GoalProfile(
        iso = 800,
        exposureTimeNs = 33_333_333L,
        focusMode = CameraFocusMode.AF,
        format = AssistantCaptureFormat.JPEG,
        frameCount = 1,
        timerSeconds = 0
    )
}

private fun ShootingGoal.explanation(): String = when (this) {
    ShootingGoal.STARS -> "Для звёзд предпочтительны RAW/DNG, фокус ∞ и серия."
    ShootingGoal.MOON -> "Луна яркая, поэтому нужны низкое ISO и короткая выдержка."
    ShootingGoal.CITY_SKY ->
        "Городское небо требует умеренного ISO и защиты от засветки."
    ShootingGoal.TEST -> "Тестовый профиль нужен для быстрой проверки камеры."
    ShootingGoal.HANDHELD ->
        "С рук длинная выдержка даст смаз, поэтому используем 1/30 сек."
}

private fun nextIso(
    current: Int,
    range: IntRange,
    brighter: Boolean
): Int? {
    val values = (listOf(50, 100, 200, 400, 800, 1600, 3200) + current)
        .distinct()
        .sorted()
        .filter { it in range }
    return if (brighter) {
        values.firstOrNull { it > current }
    } else {
        values.lastOrNull { it < current }
    }
}

private fun moveExposure(
    current: Long,
    range: LongRange,
    steps: Int,
    brighter: Boolean
): Long? {
    val values = (
        listOf(
            1_000_000L,
            4_000_000L,
            16_666_667L,
            33_333_333L,
            1_000_000_000L,
            2_000_000_000L,
            5_000_000_000L,
            10_000_000_000L,
            15_000_000_000L,
            30_000_000_000L
        ) + current + range.first + range.last
        ).distinct().sorted().filter { it in range }
    var value = current.coerceIn(range.first, range.last)
    repeat(steps.coerceAtLeast(1)) {
        val next = if (brighter) {
            values.firstOrNull { it > value }
        } else {
            values.lastOrNull { it < value }
        } ?: return value.takeIf { it != current }
        value = next
    }
    return value
}

private fun formatAssistantExposure(value: Long): String = when {
    value >= 1_000_000_000L -> {
        val seconds = value / 1_000_000_000.0
        if (seconds % 1.0 == 0.0) {
            "${seconds.toInt()} сек"
        } else {
            String.format(java.util.Locale.getDefault(), "%.1f сек", seconds)
        }
    }
    value > 0L -> "1/${(1_000_000_000.0 / value).toInt()} сек"
    else -> "неизвестно"
}

private fun formatAssistantFocus(mode: CameraFocusMode): String = when (mode) {
    CameraFocusMode.AF -> "AF"
    CameraFocusMode.MF -> "MF"
    CameraFocusMode.INFINITY -> "∞"
}
