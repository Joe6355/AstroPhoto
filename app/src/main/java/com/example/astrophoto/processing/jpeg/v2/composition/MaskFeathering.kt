package com.example.astrophoto.processing.jpeg.v2.composition

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import kotlin.math.roundToInt

class MaskFeathering {
    data class Result(
        val alphaMask: AlphaMask,
        val broadRadius: Int,
        val thinStructureRadius: Int
    )

    fun feather(
        skyMask: SkyMask,
        foregroundProtection: SkyMask = SkyMask.empty(skyMask.width, skyMask.height),
        radiusOverride: Int? = null
    ): Result {
        require(skyMask.width == foregroundProtection.width)
        require(skyMask.height == foregroundProtection.height)
        val radius = radiusOverride ?: adaptiveRadius(skyMask.width, skyMask.height)
        require(radius >= 1)
        val thinRadius = minOf(radius, maxOf(2, radius / 3))
        val skyPixels = skyMask.copyPixels()
        val protectedPixels = foregroundProtection.copyPixels()
        val distanceToBroadForeground = distanceFromSeeds(
            skyMask.width,
            skyMask.height,
            BooleanArray(skyPixels.size) { !skyPixels[it] },
            radius + 1
        )
        val distanceToProtection = distanceFromSeeds(
            skyMask.width,
            skyMask.height,
            protectedPixels,
            thinRadius + 1
        )
        val alpha = FloatArray(skyPixels.size) { index ->
            if (!skyPixels[index] || protectedPixels[index]) {
                0f
            } else {
                val broad = if (distanceToBroadForeground[index] >= radius) {
                    1f
                } else {
                    distanceToBroadForeground[index].toFloat() / radius
                }
                val thin = if (distanceToProtection[index] >= thinRadius) {
                    1f
                } else {
                    distanceToProtection[index].toFloat() / thinRadius
                }
                minOf(broad, thin).coerceIn(0f, 1f)
            }
        }
        return Result(
            AlphaMask(skyMask.width, skyMask.height, alpha),
            broadRadius = radius,
            thinStructureRadius = thinRadius
        )
    }

    fun adaptiveRadius(width: Int, height: Int): Int =
        (maxOf(width, height) / 320f).roundToInt().coerceIn(MIN_RADIUS, MAX_RADIUS)

    private fun distanceFromSeeds(
        width: Int,
        height: Int,
        seeds: BooleanArray,
        maximumDistance: Int
    ): IntArray {
        val unreached = maximumDistance + 1
        val distances = IntArray(seeds.size) { if (seeds[it]) 0 else unreached }
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            if (distances[index] == 0) continue
            var distance = distances[index]
            if (x > 0) distance = minOf(distance, distances[index - 1] + 1)
            if (y > 0) distance = minOf(distance, distances[index - width] + 1)
            distances[index] = minOf(distance, unreached)
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val index = y * width + x
            if (distances[index] == 0) continue
            var distance = distances[index]
            if (x + 1 < width) distance = minOf(distance, distances[index + 1] + 1)
            if (y + 1 < height) distance = minOf(distance, distances[index + width] + 1)
            distances[index] = minOf(distance, unreached)
        }
        return distances
    }

    companion object {
        private const val MIN_RADIUS = 2
        private const val MAX_RADIUS = 8
    }
}
