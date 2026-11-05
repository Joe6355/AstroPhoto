package com.joe6355.astrophoto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.joe6355.astrophoto.ui.AstroExpandableSection

@Composable
internal fun ResultProcessingSummaryDialog(
    summary: ResultProcessingSummary?,
    referenceAvailable: Boolean,
    fileDetails: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (summary == null) "Сведения о файле" else "Итог обработки") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()).testTag("result-processing-summary"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (summary != null) {
                    Text(summary.frameCountText, style = MaterialTheme.typography.titleSmall)
                    Text(summary.savedResultText)
                    summary.fallbackText?.let { Text(it) }
                    if (summary.exclusions.isNotEmpty()) {
                        Text("Почему не вошли остальные", style = MaterialTheme.typography.titleSmall)
                        summary.exclusions.forEach { reason -> Text("${reason.description}: ${reason.count}") }
                    }
                    summary.enhancedText?.let { Text(it) }
                    Text("Опорный кадр", style = MaterialTheme.typography.titleSmall)
                    Text(summary.referenceFileName, style = MaterialTheme.typography.bodySmall)
                    if (!referenceAvailable) Text("Опорный кадр не удалось однозначно найти среди исходников. " +
                        "Для сравнения используется первый доступный кадр, если он есть.")
                } else {
                    Text("Отчёт обработки для этого файла недоступен. Число кадров и причины отбора неизвестны.")
                }
                AstroExpandableSection(title = "Технические сведения") {
                    Text(fileDetails, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
