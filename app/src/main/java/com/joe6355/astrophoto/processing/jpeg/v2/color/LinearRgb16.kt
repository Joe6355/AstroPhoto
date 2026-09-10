package com.joe6355.astrophoto.processing.jpeg.v2.color

import com.joe6355.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import kotlin.math.roundToInt

/** Three unsigned linear-light channels packed into 48 bits; opaque JPEG input only. */
object LinearRgb16 {
    private val decoded8 = FloatArray(256) { SrgbTransfer.srgbToLinear(it / 255f) }
    private val encoded16 = IntArray(65536) {
        (SrgbTransfer.linearToSrgb(it / 65535f) * 255f).roundToInt().coerceIn(0, 255)
    }

    fun pack(red: Float, green: Float, blue: Float): Long =
        (encode(red).toLong() shl 32) or (encode(green).toLong() shl 16) or encode(blue).toLong()

    fun red(color: Long): Float = ((color ushr 32) and 65535L) / 65535f
    fun green(color: Long): Float = ((color ushr 16) and 65535L) / 65535f
    fun blue(color: Long): Float = (color and 65535L) / 65535f
    fun luminance(color: Long): Float = 0.2126f * red(color) + 0.7152f * green(color) + 0.0722f * blue(color)

    fun fromArgb(color: Int): Long = pack(
        decoded8[color ushr 16 and 255], decoded8[color ushr 8 and 255], decoded8[color and 255]
    )

    fun toArgb(color: Long): Int = (255 shl 24) or
        (encoded16[(color ushr 32 and 65535L).toInt()] shl 16) or
        (encoded16[(color ushr 16 and 65535L).toInt()] shl 8) or
        encoded16[(color and 65535L).toInt()]

    fun blend(first: Long, second: Long, amount: Float): Long = pack(
        red(first) + (red(second) - red(first)) * amount,
        green(first) + (green(second) - green(first)) * amount,
        blue(first) + (blue(second) - blue(first)) * amount
    )

    private fun encode(value: Float): Int {
        require(value.isFinite()) { "Non-finite linear pixel" }
        return (value.coerceIn(0f, 1f) * 65535f).roundToInt()
    }
}

interface LinearRgbPixelSource : ArgbPixelSource {
    fun linearRgbAt(x: Int, y: Int): Long
}

/** Legacy/diagnostic sources remain supported without forcing high-precision data through ARGB8. */
fun ArgbPixelSource.linearRgbAt(x: Int, y: Int): Long =
    if (this is LinearRgbPixelSource) linearRgbAt(x, y) else LinearRgb16.fromArgb(argbAt(x, y))
