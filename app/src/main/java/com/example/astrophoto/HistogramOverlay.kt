package com.example.astrophoto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.example.astrophoto.ui.theme.AstroColors

@Composable
fun HistogramOverlay(
    analysis: ExposureAnalysis?,
    error: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(220.dp)
            .clickable(onClick = onToggleExpanded),
        color = Color.Black.copy(alpha = 0.68f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = error ?: analysis?.status?.let {
                    "Экспозиция • ${it.displayTitle()}"
                } ?: "Экспозиция: ожидание…",
                fontWeight = FontWeight.SemiBold,
                color = if (error != null) {
                AstroColors.Error
                } else {
                analysis?.status?.displayColor() ?: AstroColors.TextSecondary
                }
            )
            HistogramCanvas(
                bins = analysis?.histogram.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (expanded) 58.dp else 34.dp)
                    .padding(top = 5.dp)
            )
            if (expanded) {
                Text(
                    text = if (analysis != null) {
                        String.format(
                            Locale.getDefault(),
                            "Средняя яркость: %.1f\nТени: %.1f%%\nПересвет: %.2f%%",
                            analysis.averageBrightness,
                            analysis.shadowPercent,
                            analysis.highlightPercent
                        )
                    } else {
                        "Данные preview ещё не получены"
                    },
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = error ?: analysis?.status?.hint()
                        ?: "Ожидание live-анализа.",
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AstroColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun HistogramCanvas(
    bins: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (bins.isEmpty()) return@Canvas
        val barWidth = size.width / bins.size
        bins.forEachIndexed { index, value ->
            val height = size.height * value.coerceIn(0f, 1f)
            drawLine(
                            color = AstroColors.Secondary,
                start = Offset(
                    x = index * barWidth + barWidth / 2f,
                    y = size.height
                ),
                end = Offset(
                    x = index * barWidth + barWidth / 2f,
                    y = size.height - height
                ),
                strokeWidth = (barWidth * 0.72f).coerceAtLeast(1f)
            )
        }
    }
}

fun ExposureStatus.displayTitle(): String = when (this) {
    ExposureStatus.TOO_DARK -> "Кадр тёмный"
    ExposureStatus.OK -> "Экспозиция нормальная"
    ExposureStatus.TOO_BRIGHT -> "Слишком ярко"
    ExposureStatus.OVEREXPOSED -> "Есть пересвет"
    ExposureStatus.UNKNOWN -> "Экспозиция неизвестна"
}

fun ExposureStatus.hint(): String = when (this) {
    ExposureStatus.TOO_DARK -> "Поднимите ISO или увеличьте выдержку."
    ExposureStatus.OK -> "Экспозиция выглядит нормально."
    ExposureStatus.TOO_BRIGHT -> "Сцена слишком яркая для астро-съёмки."
    ExposureStatus.OVEREXPOSED -> "Уменьшите ISO или выдержку."
    ExposureStatus.UNKNOWN -> "Не удалось оценить экспозицию."
}

private fun ExposureStatus.displayColor(): Color = when (this) {
    ExposureStatus.OK -> AstroColors.Success
    ExposureStatus.TOO_DARK -> AstroColors.Secondary
    ExposureStatus.TOO_BRIGHT -> AstroColors.Warning
    ExposureStatus.OVEREXPOSED -> AstroColors.Error
    ExposureStatus.UNKNOWN -> AstroColors.TextSecondary
}
