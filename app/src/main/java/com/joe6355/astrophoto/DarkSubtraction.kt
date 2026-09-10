package com.joe6355.astrophoto

import kotlin.math.roundToInt
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer

fun subtractMasterDark(
    light: AveragePixelFrame,
    masterDark: AveragePixelFrame,
    neutralOffset: Int
): AveragePixelFrame {
    validateSubtractionFrame(light, "Light")
    validateSubtractionFrame(masterDark, "Master Dark")
    require(light.width == masterDark.width && light.height == masterDark.height) {
        "Light and Master Dark dimensions must match"
    }

    val output = IntArray(light.pixels.size)
    subtractMasterDarkArgb(
        lightPixels = light.pixels,
        darkPixels = masterDark.pixels,
        outputPixels = output,
        neutralOffset = neutralOffset,
        pixelCount = output.size
    )
    return AveragePixelFrame(light.width, light.height, output)
}

internal fun subtractMasterDarkArgb(
    lightPixels: IntArray,
    darkPixels: IntArray,
    outputPixels: IntArray,
    neutralOffset: Int,
    pixelCount: Int
) {
    require(pixelCount >= 0) { "Pixel count must not be negative" }
    require(
        lightPixels.size >= pixelCount &&
            darkPixels.size >= pixelCount &&
            outputPixels.size >= pixelCount
    ) {
        "Dark subtraction pixel arrays are shorter than the requested pixel count"
    }

    for (pixelIndex in 0 until pixelCount) {
        val lightColor = lightPixels[pixelIndex]
        val darkColor = darkPixels[pixelIndex]
        val red = subtractDarkChannel(
            light = lightColor ushr 16 and 0xFF,
            dark = darkColor ushr 16 and 0xFF,
            neutralOffset = neutralOffset
        )
        val green = subtractDarkChannel(
            light = lightColor ushr 8 and 0xFF,
            dark = darkColor ushr 8 and 0xFF,
            neutralOffset = neutralOffset
        )
        val blue = subtractDarkChannel(
            light = lightColor and 0xFF,
            dark = darkColor and 0xFF,
            neutralOffset = neutralOffset
        )
        outputPixels[pixelIndex] =
            OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
    }
}

private fun validateSubtractionFrame(frame: AveragePixelFrame, label: String) {
    require(frame.width > 0 && frame.height > 0) {
        "$label dimensions must be positive"
    }
    val expected = frame.width.toLong() * frame.height.toLong()
    require(expected <= Int.MAX_VALUE && frame.pixels.size == expected.toInt()) {
        "$label pixel count does not match its dimensions"
    }
}

private fun subtractDarkChannel(
    light: Int,
    dark: Int,
    neutralOffset: Int
): Int {
    val safeDark = (dark * DARK_SUBTRACTION_STRENGTH).roundToInt()
    return (light - safeDark + neutralOffset).coerceIn(0, 255)
}

private const val DARK_SUBTRACTION_STRENGTH = 0.65f

internal fun subtractDarkChannelPrecise(light: Float, dark: Float, neutralOffset: Int): Float =
    (light - dark * DARK_SUBTRACTION_STRENGTH + neutralOffset).coerceIn(0f, 255f)

internal fun subtractMasterDarkLinearRgb(light: Long, dark: Long, neutralOffset: Int): Long {
    fun channel(lightValue: Float, darkValue: Float): Float = SrgbTransfer.srgbToLinear(
        subtractDarkChannelPrecise(SrgbTransfer.linearToSrgb(lightValue) * 255f,
            SrgbTransfer.linearToSrgb(darkValue) * 255f, neutralOffset) / 255f
    )
    return LinearRgb16.pack(channel(LinearRgb16.red(light), LinearRgb16.red(dark)),
        channel(LinearRgb16.green(light), LinearRgb16.green(dark)), channel(LinearRgb16.blue(light), LinearRgb16.blue(dark)))
}
private const val OPAQUE_ALPHA = 0xFF000000.toInt()
