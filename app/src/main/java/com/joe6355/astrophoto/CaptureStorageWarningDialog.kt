package com.joe6355.astrophoto

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

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
                "Нужно примерно ${formatStorageSize(warning.estimatedBytes)}, " +
                    "свободно ${warning.availableBytes?.let(::formatStorageSize) ?: "неизвестно"}." +
                    if (warning.criticallyLow) "\nСвободного места меньше 500 MB." else ""
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Начать всё равно") } },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text("Отмена") } }
    )
}
