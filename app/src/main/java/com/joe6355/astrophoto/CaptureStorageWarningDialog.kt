package com.joe6355.astrophoto

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun CaptureStorageWarningDialog(
    warning: StorageSpaceInfo,
    targetName: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Может не хватить места для $targetName") },
        text = {
            Text(
                "Свободно в хранилище: ${warning.availableBytes?.let(::formatStorageSize) ?: "неизвестно"}.\n" +
                    "Нужно примерно для $targetName: ${formatStorageSize(warning.estimatedBytes)}." +
                    (if (warning.criticallyLow) "\nСвободного места меньше 500 MB." else "") +
                    "\nОсвободите место или уменьшите число кадров. При нехватке места съёмка может остановиться раньше.",
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Начать всё равно") } },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text("Отмена") } }
    )
}
