package com.joe6355.astrophoto

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Coordinates are oriented source pixels, never pixels of a screen-sized thumbnail. */
internal data class PhotoViewport(
    val image: IntSize,
    val viewport: IntSize,
    val zoom: Float = 1f,
    val focus: Offset = Offset(0.5f, 0.5f)
) {
    val fitScale: Float = min(viewport.width.toFloat() / image.width,
        viewport.height.toFloat() / image.height).coerceIn(0.0001f, 1f)
    val scale: Float = fitScale * zoom
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    val boundedFocus: Offset = Offset(bound(focus.x, image.width, viewport.width),
        bound(focus.y, image.height, viewport.height))
    val translation: Offset = center - Offset(boundedFocus.x * image.width,
        boundedFocus.y * image.height) * scale

    private fun bound(value: Float, pixels: Int, available: Int): Float {
        val half = available / (2f * pixels * scale)
        return if (half >= 0.5f) 0.5f else value.coerceIn(half, 1f - half)
    }

    fun transform(centroid: Offset, pan: Offset, zoomChange: Float): PhotoViewport {
        val nextZoom = (zoom * zoomChange).coerceIn(1f, max(8f, 8f / fitScale))
        val sourceAnchor = (centroid - translation) / scale
        val nextFocus = (sourceAnchor - (centroid + pan - center) / (fitScale * nextZoom))
        val next = copy(zoom = nextZoom, focus = Offset(nextFocus.x / image.width,
            nextFocus.y / image.height))
        return next.copy(focus = next.boundedFocus)
    }

    fun visibleSource(): Rect = Rect(
        ((-translation.x) / scale).coerceIn(0f, image.width.toFloat()),
        ((-translation.y) / scale).coerceIn(0f, image.height.toFloat()),
        ((viewport.width - translation.x) / scale).coerceIn(0f, image.width.toFloat()),
        ((viewport.height - translation.y) / scale).coerceIn(0f, image.height.toFloat())
    )
}

internal data class PhotoRegionRequest(val left: Int, val top: Int, val right: Int,
    val bottom: Int, val sample: Int)

internal fun photoRegionRequest(visible: Rect, width: Int, height: Int,
    screenPixelsPerSourcePixel: Float): PhotoRegionRequest {
    // Decode at least one bitmap pixel per screen pixel, with a small panning margin.
    var sample = 1
    while (sample * 2 * screenPixelsPerSourcePixel <= 1f && sample < 1024) sample *= 2
    val margin = sample * 64
    val left = (floor(visible.left).toInt() - margin).coerceIn(0, width - 1)
    val top = (floor(visible.top).toInt() - margin).coerceIn(0, height - 1)
    val right = (ceil(visible.right).toInt() + margin).coerceIn(left + 1, width)
    val bottom = (ceil(visible.bottom).toInt() + margin).coerceIn(top + 1, height)
    while (ceil((right - left).toDouble() / sample) *
        ceil((bottom - top).toDouble() / sample) > MAX_PHOTO_REGION_PIXELS) sample *= 2
    return PhotoRegionRequest(left, top, right, bottom, sample)
}

internal const val MAX_PHOTO_REGION_PIXELS = 4_000_000
