package com.example.astrophoto

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.astrophoto.ui.AstroConfirmationDialog
import com.example.astrophoto.ui.AstroPrimaryButton
import com.example.astrophoto.ui.AstroSecondaryButton
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.AstroTestTags
import com.example.astrophoto.ui.AstroTopBar
import com.example.astrophoto.ui.theme.AstroColors
import kotlinx.coroutines.launch

internal enum class CropDismissAction {
    BLOCK,
    CONFIRM,
    DISMISS
}

internal fun cropDismissAction(
    initialCrop: NormalizedCropRect,
    currentCrop: NormalizedCropRect,
    busy: Boolean
): CropDismissAction = when {
    busy -> CropDismissAction.BLOCK
    initialCrop != currentCrop -> CropDismissAction.CONFIRM
    else -> CropDismissAction.DISMISS
}

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
    val initialCrop = remember(original.key, initialRect) { initialRect.validated() }
    var preview by remember(original.key) { mutableStateOf<Bitmap?>(null) }
    var crop by remember(original.key) { mutableStateOf(initialCrop) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }

    fun requestDismiss() {
        when (cropDismissAction(initialCrop, crop, busy)) {
            CropDismissAction.BLOCK -> Unit
            CropDismissAction.CONFIRM -> showDiscardConfirmation = true
            CropDismissAction.DISMISS -> onDismiss()
        }
    }

    LaunchedEffect(original.key) {
        preview = repository.loadEditorPreview(original)
        if (preview == null) status = "Не удалось открыть JPEG"
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false
        )
    ) {
        BackHandler(enabled = !busy, onBack = ::requestDismiss)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .testTag(AstroTestTags.CropEditor)
        ) {
            AstroTopBar(title = "Обрезка", onBack = ::requestDismiss)
            Text(
                text = original.fileName,
                modifier = Modifier.padding(horizontal = AstroSpacing.Lg),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = AstroSpacing.Sm)
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
                val bitmap = preview
                if (bitmap == null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                    )
                } else {
                    val containerWidth = constraints.maxWidth.toFloat()
                    val containerHeight = constraints.maxHeight.toFloat()
                    val bounds = remember(
                        containerWidth,
                        containerHeight,
                        bitmap.width,
                        bitmap.height
                    ) {
                        previewBounds(
                            containerWidth,
                            containerHeight,
                            bitmap.width,
                            bitmap.height
                        )
                    }
                    val density = LocalDensity.current
                    val touchRadiusPx = with(density) { 24.dp.toPx() }
                    val minimumSizePx = with(density) { 48.dp.toPx() }
                    val visibleHandleRadiusPx = with(density) { 6.dp.toPx() }
                    val currentCrop by rememberUpdatedState(crop)
                    val cropColor = MaterialTheme.colorScheme.secondary
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
                                        if (handle == CropDragHandle.NONE) {
                                            return@detectDragGestures
                                        }
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
                                color = cropColor,
                                topLeft = Offset(
                                    bounds.left + crop.left * bounds.width,
                                    bounds.top + crop.top * bounds.height
                                ),
                                size = Size(
                                    (crop.right - crop.left) * bounds.width,
                                    (crop.bottom - crop.top) * bounds.height
                                ),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            cropHandlePoints(bounds, crop).values.forEach { point ->
                                drawCircle(
                                    color = cropColor,
                                    radius = visibleHandleRadiusPx,
                                    center = Offset(point.x, point.y)
                                )
                                drawCircle(
                                    color = AstroColors.OnSecondary,
                                    radius = visibleHandleRadiusPx / 2.4f,
                                    center = Offset(point.x, point.y)
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.padding(AstroSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AstroSpacing.Sm)
            ) {
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = status ?: "Обработка кадров…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AstroSpacing.Sm)
                ) {
                    AstroSecondaryButton(
                        text = "Сбросить",
                        onClick = { crop = NormalizedCropRect.Full },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                    AstroPrimaryButton(
                        text = "Сохранить",
                        onClick = {
                            busy = true
                            status = "Сохраняем обрезку…"
                            scope.launch {
                                runCatching {
                                    repository.saveCrop(session, original, crop)
                                }.onSuccess {
                                    onSaved(
                                        "Обрезка сохранена: " +
                                            "${it.croppedWidth}×${it.croppedHeight}"
                                    )
                                }.onFailure { status = it.message }
                                busy = false
                            }
                        },
                        enabled = preview != null && !busy,
                        modifier = Modifier.weight(1f)
                    )
                }
                AstroSecondaryButton(
                    text = "Применить ко всем",
                    onClick = {
                        busy = true
                        status = "Обрезаем ${originals.size} кадров…"
                        scope.launch {
                            val result = repository.applyToAll(session, originals, crop)
                            val firstFailure = result.failures.firstOrNull()?.let {
                                "\n${it.fileName}: ${it.reason}"
                            }.orEmpty()
                            val message = "Обработано ${result.processed}, " +
                                "пропущено ${result.skipped}, ошибок ${result.failed}" +
                                firstFailure
                            if (result.processed > 0) onSaved(message) else status = message
                            busy = false
                        }
                    },
                    enabled = preview != null && originals.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showDiscardConfirmation) {
        AstroConfirmationDialog(
            title = "Закрыть без сохранения?",
            message = "Изменённая область обрезки будет потеряна.",
            confirmText = "Закрыть",
            onConfirm = {
                showDiscardConfirmation = false
                onDismiss()
            },
            onDismiss = { showDiscardConfirmation = false }
        )
    }
}
