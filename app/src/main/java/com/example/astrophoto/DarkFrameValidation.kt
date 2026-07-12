package com.example.astrophoto

data class DecodedPixelFrameShape(
    val width: Int,
    val height: Int,
    val pixelArrayLength: Int
)

sealed interface DarkValidationResult {
    data class Valid(val warnings: List<String>) : DarkValidationResult
    data class Invalid(val message: String) : DarkValidationResult
}

fun validateDarkFrames(
    darkFrames: List<DecodedPixelFrameShape>,
    lightFrames: List<DecodedPixelFrameShape>
): DarkValidationResult {
    if (darkFrames.isEmpty()) {
        return DarkValidationResult.Invalid("At least one Dark frame is required")
    }
    if (lightFrames.isEmpty()) {
        return DarkValidationResult.Invalid("At least one Light frame is required")
    }
    if (darkFrames.any { !it.hasValidPixelCount() }) {
        return DarkValidationResult.Invalid(
            "Dark frame pixel count does not match its dimensions"
        )
    }
    if (lightFrames.any { !it.hasValidPixelCount() }) {
        return DarkValidationResult.Invalid(
            "Light frame pixel count does not match its dimensions"
        )
    }

    val darkWidth = darkFrames.first().width
    val darkHeight = darkFrames.first().height
    if (darkFrames.any { it.width != darkWidth || it.height != darkHeight }) {
        return DarkValidationResult.Invalid("Dark frames must have equal dimensions")
    }

    val lightWidth = lightFrames.first().width
    val lightHeight = lightFrames.first().height
    if (lightFrames.any { it.width != lightWidth || it.height != lightHeight }) {
        return DarkValidationResult.Invalid("Light frames must have equal dimensions")
    }
    if (darkWidth != lightWidth || darkHeight != lightHeight) {
        return DarkValidationResult.Invalid(
            "Dark frame dimensions must match Light frame dimensions"
        )
    }

    return DarkValidationResult.Valid(warnings = emptyList())
}

internal fun decodedPixelFrameShape(
    width: Int,
    height: Int
): DecodedPixelFrameShape {
    val pixelCount = width.toLong() * height.toLong()
    return DecodedPixelFrameShape(
        width = width,
        height = height,
        pixelArrayLength = if (pixelCount in 1L..Int.MAX_VALUE.toLong()) {
            pixelCount.toInt()
        } else {
            -1
        }
    )
}

private fun DecodedPixelFrameShape.hasValidPixelCount(): Boolean {
    if (width <= 0 || height <= 0) return false
    val expected = width.toLong() * height.toLong()
    return expected <= Int.MAX_VALUE && pixelArrayLength == expected.toInt()
}
