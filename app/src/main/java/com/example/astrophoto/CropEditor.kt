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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private enum class CropDragHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private data class PreviewBounds(val left: Float, val top: Float, val width: Float, val height: Float)

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
                    var handle by remember { mutableStateOf(CropDragHandle.MOVE) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(bounds, crop) {
                                detectDragGestures(
                                    onDragStart = { position ->
                                        handle = cropHandle(position, bounds, crop)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        crop = dragCrop(crop, handle, amount.x / bounds.width, amount.y / bounds.height)
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
                            onSaved(
                                "Apply to all: обработано ${result.processed}, " +
                                    "пропущено ${result.skipped}, ошибок ${result.failed}"
                            )
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

private fun previewBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int
): PreviewBounds {
    val scale = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return PreviewBounds((containerWidth - width) / 2f, (containerHeight - height) / 2f, width, height)
}

private fun cropHandle(
    position: Offset,
    bounds: PreviewBounds,
    crop: NormalizedCropRect
): CropDragHandle {
    val x = ((position.x - bounds.left) / bounds.width).coerceIn(0f, 1f)
    val y = ((position.y - bounds.top) / bounds.height).coerceIn(0f, 1f)
    val threshold = 0.12f
    return when {
        kotlin.math.abs(x - crop.left) < threshold && kotlin.math.abs(y - crop.top) < threshold ->
            CropDragHandle.TOP_LEFT
        kotlin.math.abs(x - crop.right) < threshold && kotlin.math.abs(y - crop.top) < threshold ->
            CropDragHandle.TOP_RIGHT
        kotlin.math.abs(x - crop.left) < threshold && kotlin.math.abs(y - crop.bottom) < threshold ->
            CropDragHandle.BOTTOM_LEFT
        kotlin.math.abs(x - crop.right) < threshold && kotlin.math.abs(y - crop.bottom) < threshold ->
            CropDragHandle.BOTTOM_RIGHT
        else -> CropDragHandle.MOVE
    }
}

private fun dragCrop(
    crop: NormalizedCropRect,
    handle: CropDragHandle,
    dx: Float,
    dy: Float
): NormalizedCropRect {
    val minimum = 0.02f
    return when (handle) {
        CropDragHandle.MOVE -> {
            val width = crop.right - crop.left
            val height = crop.bottom - crop.top
            val left = (crop.left + dx).coerceIn(0f, 1f - width)
            val top = (crop.top + dy).coerceIn(0f, 1f - height)
            NormalizedCropRect(left, top, left + width, top + height)
        }
        CropDragHandle.TOP_LEFT -> crop.copy(
            left = (crop.left + dx).coerceIn(0f, crop.right - minimum),
            top = (crop.top + dy).coerceIn(0f, crop.bottom - minimum)
        )
        CropDragHandle.TOP_RIGHT -> crop.copy(
            right = (crop.right + dx).coerceIn(crop.left + minimum, 1f),
            top = (crop.top + dy).coerceIn(0f, crop.bottom - minimum)
        )
        CropDragHandle.BOTTOM_LEFT -> crop.copy(
            left = (crop.left + dx).coerceIn(0f, crop.right - minimum),
            bottom = (crop.bottom + dy).coerceIn(crop.top + minimum, 1f)
        )
        CropDragHandle.BOTTOM_RIGHT -> crop.copy(
            right = (crop.right + dx).coerceIn(crop.left + minimum, 1f),
            bottom = (crop.bottom + dy).coerceIn(crop.top + minimum, 1f)
        )
    }
}
