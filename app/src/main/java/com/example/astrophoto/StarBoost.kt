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
            conservativeHighPass(image, mode)
            makeOpaque(image)
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

    private fun conservativeHighPass(image: ArgbPixelImage, mode: StarBoostMode) {
        val row = IntArray(image.width)
        val amount = if (mode == StarBoostMode.STRONG) 0.10f else 0.06f
        for (y in 1 until image.height - 1) {
            image.pixels.copyInto(row, 0, y * image.width, (y + 1) * image.width)
            for (x in 1 until image.width - 1) {
                val color = row[x]
                val lum = pixelLuminance(color)
                if (lum !in 35..220) continue
                val left = pixelLuminance(image.pixelAt(x - 1, y))
                val right = pixelLuminance(image.pixelAt(x + 1, y))
                val top = pixelLuminance(image.pixelAt(x, y - 1))
                val bottom = pixelLuminance(image.pixelAt(x, y + 1))
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
            row.copyInto(image.pixels, y * image.width)
        }
    }
}
