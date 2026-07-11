package com.example.astrophoto

import android.graphics.Bitmap
import kotlin.math.roundToInt

enum class StarBoostMode {
    OFF,
    SOFT,
    STRONG
}

data class StarBoostResult(
    val mode: StarBoostMode,
    val starsBoosted: Int,
    val warning: String? = null
)

class StarBoost {
    fun applyInPlace(
        bitmap: Bitmap,
        stars: List<DetectedStar>,
        mode: StarBoostMode
    ): StarBoostResult {
        if (mode == StarBoostMode.OFF) {
            return StarBoostResult(mode, 0)
        }
        if (stars.isEmpty()) {
            conservativeHighPass(bitmap, mode)
            return StarBoostResult(
                mode = mode,
                starsBoosted = 0,
                warning = "Звёзд найдено мало, применён консервативный detail boost"
            )
        }
        val amount = if (mode == StarBoostMode.STRONG) 0.34f else 0.18f
        val radius = if (mode == StarBoostMode.STRONG) 3 else 2
        var boosted = 0
        stars.take(if (mode == StarBoostMode.STRONG) 220 else 160).forEach { star ->
            if (star.radius > 5f || star.brightness > 252) return@forEach
            for (dy in -radius..radius) {
                val y = star.y + dy
                if (y !in 0 until bitmap.height) continue
                for (dx in -radius..radius) {
                    val x = star.x + dx
                    if (x !in 0 until bitmap.width) continue
                    val distance = kotlin.math.abs(dx) + kotlin.math.abs(dy)
                    val weight = (1f - distance.toFloat() / (radius + 1)).coerceIn(0f, 1f)
                    if (weight <= 0f) continue
                    val color = bitmap.getPixel(x, y)
                    val lum = StarDetector.luminance(color)
                    if (lum !in 18..245) continue
                    val factor = 1f + amount * weight * star.confidence
                    val red = ((color ushr 16 and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    val green = ((color ushr 8 and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    val blue = ((color and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    bitmap.setPixel(
                        x,
                        y,
                        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                    )
                }
            }
            boosted++
        }
        return StarBoostResult(mode = mode, starsBoosted = boosted)
    }

    private fun conservativeHighPass(bitmap: Bitmap, mode: StarBoostMode) {
        val row = IntArray(bitmap.width)
        val amount = if (mode == StarBoostMode.STRONG) 0.10f else 0.06f
        for (y in 1 until bitmap.height - 1) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 1 until bitmap.width - 1) {
                val color = row[x]
                val lum = StarDetector.luminance(color)
                if (lum !in 35..220) continue
                val left = StarDetector.luminance(bitmap.getPixel(x - 1, y))
                val right = StarDetector.luminance(bitmap.getPixel(x + 1, y))
                val top = StarDetector.luminance(bitmap.getPixel(x, y - 1))
                val bottom = StarDetector.luminance(bitmap.getPixel(x, y + 1))
                val local = (left + right + top + bottom) / 4
                val detail = (lum - local).coerceAtLeast(0)
                if (detail in 8..60) {
                    val factor = 1f + amount * (detail / 60f)
                    val red = ((color ushr 16 and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    val green = ((color ushr 8 and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    val blue = ((color and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    row[x] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                }
            }
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
    }
}
