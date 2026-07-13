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
        if (mode == StarBoostMode.OFF) return StarBoostResult(mode, 0)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = applyInPlace(ArgbPixelImage(bitmap.width, bitmap.height, pixels), stars, mode)
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }

    fun applyInPlace(
        image: ArgbPixelImage,
        stars: List<DetectedStar>,
        mode: StarBoostMode
    ): StarBoostResult {
        if (mode == StarBoostMode.OFF) {
            return StarBoostResult(mode, 0)
        }
        if (stars.isEmpty()) {
            return StarBoostResult(
                mode = mode,
                starsBoosted = 0,
                warning = "Star Boost пропущен: надёжные звёзды не найдены"
            )
        }
        val amount = if (mode == StarBoostMode.STRONG) 0.34f else 0.18f
        val radius = if (mode == StarBoostMode.STRONG) 3 else 2
        var boosted = 0
        stars.take(if (mode == StarBoostMode.STRONG) 220 else 160).forEach { star ->
            if (star.radius > 5f || star.brightness > 252) return@forEach
            for (dy in -radius..radius) {
                val y = star.y + dy
                if (y !in 0 until image.height) continue
                for (dx in -radius..radius) {
                    val x = star.x + dx
                    if (x !in 0 until image.width) continue
                    val distance = kotlin.math.abs(dx) + kotlin.math.abs(dy)
                    val weight = (1f - distance.toFloat() / (radius + 1)).coerceIn(0f, 1f)
                    if (weight <= 0f) continue
                    val index = y * image.width + x
                    val color = image.pixels[index]
                    val lum = pixelLuminance(color)
                    if (lum !in 18..245) continue
                    val factor = 1f + amount * weight * star.confidence
                    val red = ((color ushr 16 and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    val green = ((color ushr 8 and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    val blue = ((color and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                    image.pixels[index] =
                        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                }
            }
            boosted++
        }
        makeOpaque(image)
        return StarBoostResult(mode = mode, starsBoosted = boosted)
    }

    private fun makeOpaque(image: ArgbPixelImage) {
        image.pixels.indices.forEach { index ->
            image.pixels[index] = image.pixels[index] or 0xFF000000.toInt()
        }
    }

}
