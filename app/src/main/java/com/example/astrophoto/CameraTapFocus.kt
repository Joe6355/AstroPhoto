package com.example.astrophoto

import kotlin.math.max
import kotlin.math.roundToInt

internal data class CameraSensorRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    init {
        require(width > 0 && height > 0) { "Camera sensor rectangle must be positive" }
    }
}

internal fun calculateTapToFocusRect(
    viewWidth: Int,
    viewHeight: Int,
    touchX: Float,
    touchY: Float,
    previewWidth: Int,
    previewHeight: Int,
    activeArray: CameraSensorRect,
    relativeRotationDegrees: Int,
    meteringFraction: Float = DEFAULT_TAP_FOCUS_METERING_FRACTION
): CameraSensorRect {
    require(viewWidth > 0 && viewHeight > 0) { "Camera preview view must be positive" }
    require(previewWidth > 0 && previewHeight > 0) { "Camera preview buffer must be positive" }
    require(relativeRotationDegrees in setOf(0, 90, 180, 270)) {
        "Camera preview rotation must be 0, 90, 180 or 270 degrees"
    }
    require(meteringFraction > 0f && meteringFraction <= 1f) {
        "Tap-focus metering fraction must be in (0, 1]"
    }

    val rotated = relativeRotationDegrees == 90 || relativeRotationDegrees == 270
    val orientedWidth = if (rotated) previewHeight.toDouble() else previewWidth.toDouble()
    val orientedHeight = if (rotated) previewWidth.toDouble() else previewHeight.toDouble()
    val viewScale = max(
        viewWidth.toDouble() / orientedWidth,
        viewHeight.toDouble() / orientedHeight
    )
    val displayedWidth = orientedWidth * viewScale
    val displayedHeight = orientedHeight * viewScale
    val cropX = (displayedWidth - viewWidth) * 0.5
    val cropY = (displayedHeight - viewHeight) * 0.5
    val orientedX = (
        (touchX.coerceIn(0f, viewWidth.toFloat()) + cropX) / displayedWidth
        ).coerceIn(0.0, 1.0)
    val orientedY = (
        (touchY.coerceIn(0f, viewHeight.toFloat()) + cropY) / displayedHeight
        ).coerceIn(0.0, 1.0)

    val (sensorX, sensorY) = when (relativeRotationDegrees) {
        90 -> orientedY to (1.0 - orientedX)
        180 -> (1.0 - orientedX) to (1.0 - orientedY)
        270 -> (1.0 - orientedY) to orientedX
        else -> orientedX to orientedY
    }
    val sensorPreviewCrop = centerCropToAspect(
        activeArray,
        previewWidth.toDouble() / previewHeight
    )
    val centerX = sensorPreviewCrop.left + sensorPreviewCrop.width * sensorX
    val centerY = sensorPreviewCrop.top + sensorPreviewCrop.height * sensorY
    val areaWidth = (sensorPreviewCrop.width * meteringFraction)
        .roundToInt()
        .coerceIn(1, activeArray.width)
    val areaHeight = (sensorPreviewCrop.height * meteringFraction)
        .roundToInt()
        .coerceIn(1, activeArray.height)
    val left = (centerX - areaWidth * 0.5)
        .roundToInt()
        .coerceIn(activeArray.left, activeArray.right - areaWidth)
    val top = (centerY - areaHeight * 0.5)
        .roundToInt()
        .coerceIn(activeArray.top, activeArray.bottom - areaHeight)
    return CameraSensorRect(
        left = left,
        top = top,
        right = left + areaWidth,
        bottom = top + areaHeight
    )
}

private fun centerCropToAspect(
    source: CameraSensorRect,
    targetAspect: Double
): CameraSensorRect {
    val sourceAspect = source.width.toDouble() / source.height
    return if (sourceAspect > targetAspect) {
        val width = (source.height * targetAspect).roundToInt().coerceAtMost(source.width)
        val left = source.left + (source.width - width) / 2
        CameraSensorRect(left, source.top, left + width, source.bottom)
    } else {
        val height = (source.width / targetAspect).roundToInt().coerceAtMost(source.height)
        val top = source.top + (source.height - height) / 2
        CameraSensorRect(source.left, top, source.right, top + height)
    }
}

internal const val TAP_FOCUS_METERING_WEIGHT = 1000
private const val DEFAULT_TAP_FOCUS_METERING_FRACTION = 0.12f
