package com.joe6355.astrophoto

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.pow

internal class PhotoViewState {
    var zoom by mutableFloatStateOf(1f)
    var focus by mutableStateOf(Offset(0.5f, 0.5f))
    var exposure by mutableFloatStateOf(0f)
    fun reset() { zoom = 1f; focus = Offset(0.5f, 0.5f) }
    fun apply(viewport: PhotoViewport) { zoom = viewport.zoom; focus = viewport.boundedFocus }
}

@Composable
internal fun PhotoViewControls(state: PhotoViewState, modifier: Modifier = Modifier,
    onNativeSize: (() -> Unit)? = null) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = state::reset) { Text("Вписать") }
            if (onNativeSize != null) TextButton(onClick = onNativeSize) { Text("1:1") }
            FilterChip(selected = state.exposure > 0f,
                onClick = { state.exposure = if (state.exposure > 0f) 0f else 3f },
                label = { Text("Подсветка") })
        }
        if (state.exposure > 0f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(Locale.US, "+%.1f EV", state.exposure),
                    style = MaterialTheme.typography.labelSmall)
                Slider(value = state.exposure, onValueChange = { state.exposure = it },
                    valueRange = 0.1f..6f, modifier = Modifier.weight(1f).testTag("photo-exposure"))
            }
            Text("Подсветка только для просмотра — файл не меняется",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Decodes the visible area again as zoom changes, rather than enlarging a thumbnail. */
@Composable
internal fun PhotoViewer(source: ImageSourceReference, modifier: Modifier = Modifier,
    state: PhotoViewState = remember(source) { PhotoViewState() }, showControls: Boolean = true) {
    val context = LocalContext.current
    var decoder by remember(source) { mutableStateOf<PhotoRegionSource?>(null) }
    var overview by remember(source) { mutableStateOf<PhotoTile?>(null) }
    var tile by remember(source) { mutableStateOf<PhotoTile?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val dimensions = decoder?.orientedSize ?: IntSize(1, 1)
    val viewport = PhotoViewport(dimensions, viewportSize, state.zoom, state.focus)

    LaunchedEffect(source) {
        var opened: PhotoRegionSource? = null
        try {
            val initial = withContext(Dispatchers.IO) {
                PhotoRegionSource.open(context, source).also { opened = it }.let { it to it.overview() }
            }
            decoder = initial.first
            overview = initial.second
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: OutOfMemoryError) { error = "Недостаточно памяти для просмотра изображения" }
        catch (failure: Exception) { error = "Не удалось открыть изображение. Файл недоступен или повреждён." }
        finally { withContext(NonCancellable + Dispatchers.IO) { opened?.close() } }
    }
    val request = if (decoder != null && viewportSize.width > 0 && viewportSize.height > 0)
        decoder?.request(viewport) else null
    LaunchedEffect(decoder, request) {
        val current = decoder ?: return@LaunchedEffect
        val region = request ?: return@LaunchedEffect
        delay(60) // Coalesce pointer events; keep the overview visible while decoding.
        try {
            tile = withContext(Dispatchers.IO) { current.decode(region) }
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: OutOfMemoryError) { error = "Недостаточно памяти для детализации" }
        catch (failure: Exception) { error = "Не удалось прочитать этот участок изображения" }
    }
    val currentViewport by rememberUpdatedState(viewport)
    val gain = 2.0.pow(state.exposure.toDouble()).toFloat()
    val paint = remember(gain) { Paint().apply {
        colorFilter = if (gain == 1f) null else ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            gain, 0f, 0f, 0f, 0f, 0f, gain, 0f, 0f, 0f,
            0f, 0f, gain, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    } }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).clipToBounds()
            .onSizeChanged { newSize ->
                // Expanding the display-light controls must not change a chosen 1:1 view.
                if (showControls && decoder != null && viewportSize.width > 0 &&
                    viewportSize.height > 0 && state.zoom > 1f) {
                    val nextFit = PhotoViewport(dimensions, newSize).fitScale
                    state.zoom = maxOf(1f, viewport.scale / nextFit)
                }
                viewportSize = newSize
            }
            .testTag("photo-viewport")
            .semantics { contentDescription = "Фотография ${source.displayName}" }
            .pointerInput(state, source) {
                detectTapGestures(onDoubleTap = { point ->
                    val current = currentViewport
                    if (state.zoom > 1.01f) state.reset()
                    else state.apply(current.transform(point, Offset.Zero,
                        maxOf(3f, 1f / current.fitScale)))
                })
            }
            .pointerInput(state, source) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    state.apply(currentViewport.transform(centroid, pan, zoom))
                }
            }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val current = decoder ?: return@Canvas
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.translate(viewport.translation.x, viewport.translation.y)
                canvas.scale(viewport.scale, viewport.scale)
                canvas.concat(current.orientationMatrix)
                for (part in listOfNotNull(overview, tile)) {
                    // At 1:1 and beyond retain the actual pixels; downscaling uses filtering.
                    paint.isFilterBitmap = viewport.scale * part.sample < 1f
                    canvas.drawBitmap(part.bitmap, null, RectF(part.region), paint)
                }
                canvas.restore()
            }
            if (overview == null && error == null) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).background(Color.Black).padding(12.dp)) }
            if (decoder != null) Text(
                "${dimensions.width}×${dimensions.height} · ${String.format(Locale.US, "%.0f", viewport.scale * 100)}%",
                color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.7f)).padding(6.dp)
                    .testTag("photo-resolution").semantics {
                        stateDescription = if (tile != null && request != null &&
                            tile?.sample == request.sample &&
                            tile?.region == android.graphics.Rect(request.left, request.top, request.right, request.bottom))
                            "Детали загружены" else "Загрузка деталей"
                    })
            if (tile != null && request != null && tile!!.sample > request.sample) Text(
                "Детализация снижена: недостаточно памяти", color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopCenter).background(Color.Black).padding(6.dp))
        }
        if (showControls) PhotoViewControls(state, onNativeSize = if (decoder == null) null else ({
            state.zoom = maxOf(1f, 1f / viewport.fitScale)
        }))
    }
}
