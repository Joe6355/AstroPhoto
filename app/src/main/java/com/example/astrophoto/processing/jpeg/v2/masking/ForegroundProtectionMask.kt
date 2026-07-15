package com.example.astrophoto.processing.jpeg.v2.masking

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ForegroundProtectionResult
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

class ForegroundProtectionMask(
    private val radiusOverride: Int? = null
) {
    fun detect(
        reference: ArgbPixelImage,
        stars: List<DetectedStar>
    ): ForegroundProtectionResult {
        val width = reference.width
        val height = reference.height
        val luminance = IntArray(reference.pixels.size) { pixelLuminance(reference.pixels[it]) }
        val starSuppression = starSuppressionMask(width, height, stars)
        val orientation = ByteArray(reference.pixels.size)
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val index = y * width + x
                if (starSuppression[index]) continue
                val center = luminance[index]
                var bestContrast = pairedContrast(
                    center,
                    luminance[index - 2],
                    luminance[index + 2]
                )
                var bestOrientation = VERTICAL
                var contrast = pairedContrast(
                    center,
                    luminance[index - 2 * width],
                    luminance[index + 2 * width]
                )
                if (contrast > bestContrast) {
                    bestContrast = contrast
                    bestOrientation = HORIZONTAL
                }
                contrast = pairedContrast(
                    center,
                    luminance[index - 2 * width + 2],
                    luminance[index + 2 * width - 2]
                )
                if (contrast > bestContrast) {
                    bestContrast = contrast
                    bestOrientation = DIAGONAL_DOWN
                }
                contrast = pairedContrast(
                    center,
                    luminance[index - 2 * width - 2],
                    luminance[index + 2 * width + 2]
                )
                if (contrast > bestContrast) {
                    bestContrast = contrast
                    bestOrientation = DIAGONAL_UP
                }
                if (bestContrast >= MIN_TWO_SIDED_CONTRAST) {
                    orientation[index] = bestOrientation.toByte()
                }
            }
        }
        val thinStructures = BooleanArray(reference.pixels.size)
        orientation.indices.forEach { index ->
            val direction = orientation[index].toInt()
            if (direction == 0) return@forEach
            val x = index % width
            val y = index / width
            val step = when (direction) {
                VERTICAL -> width
                HORIZONTAL -> 1
                DIAGONAL_DOWN -> width + 1
                else -> width - 1
            }
            var support = 0
            for (offset in -LINE_SUPPORT_RADIUS..LINE_SUPPORT_RADIUS) {
                if (offset == 0) continue
                val nx = when (direction) {
                    VERTICAL -> x
                    HORIZONTAL -> x + offset
                    DIAGONAL_DOWN -> x + offset
                    else -> x - offset
                }
                val ny = when (direction) {
                    VERTICAL -> y + offset
                    HORIZONTAL -> y
                    else -> y + offset
                }
                if (nx !in 0 until width || ny !in 0 until height) continue
                val candidate = index + offset * step
                if (candidate in orientation.indices && orientation[candidate].toInt() == direction) support++
            }
            if (support >= MIN_LINE_SUPPORT) thinStructures[index] = true
        }
        val radius = radiusOverride ?: adaptiveRadius(width, height)
        require(radius >= 1)
        val dilated = dilate(thinStructures, width, height, radius)
        return ForegroundProtectionResult(
            mask = SkyMask(width, height, dilated),
            protectedPixelCount = dilated.count { it },
            dilationRadius = radius
        )
    }

    fun adaptiveRadius(width: Int, height: Int): Int =
        (minOf(width, height) / 900f).roundToInt().coerceIn(1, 4)

    private fun pairedContrast(center: Int, first: Int, second: Int): Int {
        val firstDifference = first - center
        val secondDifference = second - center
        if (firstDifference == 0 || secondDifference == 0 || firstDifference.sign != secondDifference.sign) {
            return 0
        }
        return minOf(abs(firstDifference), abs(secondDifference))
    }

    private fun starSuppressionMask(
        width: Int,
        height: Int,
        stars: List<DetectedStar>
    ): BooleanArray {
        val result = BooleanArray(width * height)
        stars.forEach { star ->
            val radius = ceil(maxOf(2f, star.width * 2.2f)).toInt()
            val centerX = star.x.roundToInt()
            val centerY = star.y.roundToInt()
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (dx * dx + dy * dy > radius * radius) continue
                val x = centerX + dx
                val y = centerY + dy
                if (x in 0 until width && y in 0 until height) result[y * width + x] = true
            }
        }
        return result
    }

    private fun dilate(
        source: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        val result = source.copyOf()
        source.indices.forEach { index ->
            if (!source[index]) return@forEach
            val centerX = index % width
            val centerY = index / width
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (abs(dx) + abs(dy) > radius) continue
                val x = centerX + dx
                val y = centerY + dy
                if (x in 0 until width && y in 0 until height) result[y * width + x] = true
            }
        }
        return result
    }

    private val Int.sign: Int get() = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }

    companion object {
        private const val MIN_TWO_SIDED_CONTRAST = 18
        private const val LINE_SUPPORT_RADIUS = 3
        private const val MIN_LINE_SUPPORT = 2
        private const val VERTICAL = 1
        private const val HORIZONTAL = 2
        private const val DIAGONAL_DOWN = 3
        private const val DIAGONAL_UP = 4
    }
}
