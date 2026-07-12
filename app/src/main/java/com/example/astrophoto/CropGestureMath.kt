package com.example.astrophoto

import kotlin.math.abs

internal enum class CropDragHandle {
    NONE,
    MOVE,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

internal data class PreviewBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

internal data class CropHandlePoint(val x: Float, val y: Float)

internal fun previewBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int
): PreviewBounds {
    require(containerWidth > 0f && containerHeight > 0f)
    require(imageWidth > 0 && imageHeight > 0)
    val scale = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return PreviewBounds(
        left = (containerWidth - width) / 2f,
        top = (containerHeight - height) / 2f,
        width = width,
        height = height
    )
}

internal fun cropHandlePoints(
    bounds: PreviewBounds,
    crop: NormalizedCropRect
): Map<CropDragHandle, CropHandlePoint> {
    val left = bounds.left + crop.left * bounds.width
    val top = bounds.top + crop.top * bounds.height
    val right = bounds.left + crop.right * bounds.width
    val bottom = bounds.top + crop.bottom * bounds.height
    val middleX = (left + right) / 2f
    val middleY = (top + bottom) / 2f
    return linkedMapOf(
        CropDragHandle.TOP_LEFT to CropHandlePoint(left, top),
        CropDragHandle.TOP_RIGHT to CropHandlePoint(right, top),
        CropDragHandle.BOTTOM_LEFT to CropHandlePoint(left, bottom),
        CropDragHandle.BOTTOM_RIGHT to CropHandlePoint(right, bottom),
        CropDragHandle.LEFT to CropHandlePoint(left, middleY),
        CropDragHandle.TOP to CropHandlePoint(middleX, top),
        CropDragHandle.RIGHT to CropHandlePoint(right, middleY),
        CropDragHandle.BOTTOM to CropHandlePoint(middleX, bottom)
    )
}

internal fun cropHandle(
    positionX: Float,
    positionY: Float,
    bounds: PreviewBounds,
    crop: NormalizedCropRect,
    touchRadiusPx: Float
): CropDragHandle {
    require(touchRadiusPx > 0f)
    val points = cropHandlePoints(bounds, crop)
    points.entries.firstOrNull { (handle, point) ->
        handle in CORNER_HANDLES &&
            abs(positionX - point.x) <= touchRadiusPx &&
            abs(positionY - point.y) <= touchRadiusPx
    }?.let { return it.key }
    points.entries.firstOrNull { (handle, point) ->
        handle !in CORNER_HANDLES &&
            abs(positionX - point.x) <= touchRadiusPx &&
            abs(positionY - point.y) <= touchRadiusPx
    }?.let { return it.key }

    val left = bounds.left + crop.left * bounds.width
    val top = bounds.top + crop.top * bounds.height
    val right = bounds.left + crop.right * bounds.width
    val bottom = bounds.top + crop.bottom * bounds.height
    return if (positionX in left..right && positionY in top..bottom) {
        CropDragHandle.MOVE
    } else {
        CropDragHandle.NONE
    }
}

internal fun dragCrop(
    crop: NormalizedCropRect,
    handle: CropDragHandle,
    dx: Float,
    dy: Float,
    minimumWidth: Float,
    minimumHeight: Float
): NormalizedCropRect {
    val minWidth = minimumWidth.coerceIn(0.01f, 1f)
    val minHeight = minimumHeight.coerceIn(0.01f, 1f)
    val changed = when (handle) {
        CropDragHandle.NONE -> crop
        CropDragHandle.MOVE -> {
            val width = crop.right - crop.left
            val height = crop.bottom - crop.top
            val left = (crop.left + dx).coerceIn(0f, 1f - width)
            val top = (crop.top + dy).coerceIn(0f, 1f - height)
            NormalizedCropRect(left, top, left + width, top + height)
        }
        CropDragHandle.LEFT -> crop.copy(
            left = (crop.left + dx).coerceIn(0f, crop.right - minWidth)
        )
        CropDragHandle.TOP -> crop.copy(
            top = (crop.top + dy).coerceIn(0f, crop.bottom - minHeight)
        )
        CropDragHandle.RIGHT -> crop.copy(
            right = (crop.right + dx).coerceIn(crop.left + minWidth, 1f)
        )
        CropDragHandle.BOTTOM -> crop.copy(
            bottom = (crop.bottom + dy).coerceIn(crop.top + minHeight, 1f)
        )
        CropDragHandle.TOP_LEFT -> crop.copy(
            left = (crop.left + dx).coerceIn(0f, crop.right - minWidth),
            top = (crop.top + dy).coerceIn(0f, crop.bottom - minHeight)
        )
        CropDragHandle.TOP_RIGHT -> crop.copy(
            right = (crop.right + dx).coerceIn(crop.left + minWidth, 1f),
            top = (crop.top + dy).coerceIn(0f, crop.bottom - minHeight)
        )
        CropDragHandle.BOTTOM_LEFT -> crop.copy(
            left = (crop.left + dx).coerceIn(0f, crop.right - minWidth),
            bottom = (crop.bottom + dy).coerceIn(crop.top + minHeight, 1f)
        )
        CropDragHandle.BOTTOM_RIGHT -> crop.copy(
            right = (crop.right + dx).coerceIn(crop.left + minWidth, 1f),
            bottom = (crop.bottom + dy).coerceIn(crop.top + minHeight, 1f)
        )
    }
    return changed.validated()
}

private val CORNER_HANDLES = setOf(
    CropDragHandle.TOP_LEFT,
    CropDragHandle.TOP_RIGHT,
    CropDragHandle.BOTTOM_LEFT,
    CropDragHandle.BOTTOM_RIGHT
)
