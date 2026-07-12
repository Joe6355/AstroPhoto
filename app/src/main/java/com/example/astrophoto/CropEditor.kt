package com.example.astrophoto

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
fun CropEditorDialog(
    session: SessionSummary,
    original: SessionFrame,
    originals: List<SessionFrame>,
    repository: CroppedFramesRepository,
    initialRect: NormalizedCropRect,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var preview by remember(original.key) { mutableStateOf<Bitmap?>(null) }
    var crop by remember(original.key) { mutableStateOf(initialRect.validated()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(original.key) {
        preview = repository.loadEditorPreview(original)
        if (preview == null) status = "Не удалось открыть JPEG"
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF080B12))
                .safeDrawingPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
                Text(original.fileName, style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { crop = NormalizedCropRect.Full },
                    enabled = !busy
                ) { Text("Сброс") }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                val bitmap = preview
                if (bitmap == null) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    val containerWidth = constraints.maxWidth.toFloat()
                    val containerHeight = constraints.maxHeight.toFloat()
                    val bounds = remember(containerWidth, containerHeight, bitmap.width, bitmap.height) {
                        previewBounds(containerWidth, containerHeight, bitmap.width, bitmap.height)
                    }
                    val density = LocalDensity.current
                    val touchRadiusPx = with(density) { 24.dp.toPx() }
                    val minimumSizePx = with(density) { 48.dp.toPx() }
                    val visibleHandleRadiusPx = with(density) { 6.dp.toPx() }
                    val currentCrop by rememberUpdatedState(crop)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(bounds, touchRadiusPx, minimumSizePx) {
                                var handle = CropDragHandle.NONE
                                detectDragGestures(
                                    onDragStart = { position ->
                                        handle = cropHandle(
                                            position.x,
                                            position.y,
                                            bounds,
                                            currentCrop,
                                            touchRadiusPx
                                        )
                                    },
                                    onDrag = { change, amount ->
                                        if (handle == CropDragHandle.NONE) return@detectDragGestures
                                        change.consume()
                                        crop = dragCrop(
                                            crop = currentCrop,
                                            handle = handle,
                                            dx = amount.x / bounds.width,
                                            dy = amount.y / bounds.height,
                                            minimumWidth = minimumSizePx / bounds.width,
                                            minimumHeight = minimumSizePx / bounds.height
                                        )
                                    }
                                )
                            }
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = original.fileName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(
                                color = Color(0xFFFFD54F),
                                topLeft = Offset(
                                    bounds.left + crop.left * bounds.width,
                                    bounds.top + crop.top * bounds.height
                                ),
                                size = Size(
                                    (crop.right - crop.left) * bounds.width,
                                    (crop.bottom - crop.top) * bounds.height
                                ),
                                style = Stroke(width = 3.dp.toPx())
                            )
                            cropHandlePoints(bounds, crop).values.forEach { point ->
                                drawCircle(
                                    color = Color(0xFFFFD54F),
                                    radius = visibleHandleRadiusPx,
                                    center = Offset(point.x, point.y)
                                )
                                drawCircle(
                                    color = Color(0xFF3A3000),
                                    radius = visibleHandleRadiusPx / 2.4f,
                                    center = Offset(point.x, point.y)
                                )
                            }
                        }
                    }
                }
            }

            status?.let { Text(it, color = Color(0xFFFFCC80)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { repository.saveCrop(session, original, crop) }
                                .onSuccess { onSaved("Обрезка сохранена: ${it.croppedWidth}×${it.croppedHeight}") }
                                .onFailure { status = it.message }
                            busy = false
                        }
                    },
                    enabled = preview != null && !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить кадр") }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = repository.applyToAll(session, originals, crop)
                            val firstFailure = result.failures.firstOrNull()?.let {
                                "\n${it.fileName}: ${it.reason}"
                            }.orEmpty()
                            val message = "Apply to all: обработано ${result.processed}, " +
                                "пропущено ${result.skipped}, ошибок ${result.failed}$firstFailure"
                            if (result.processed > 0) onSaved(message) else status = message
                            busy = false
                        }
                    },
                    enabled = preview != null && originals.isNotEmpty() && !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Применить ко всем") }
            }
        }
    }
}
