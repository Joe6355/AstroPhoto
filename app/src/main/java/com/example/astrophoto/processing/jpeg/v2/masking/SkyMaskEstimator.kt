package com.example.astrophoto.processing.jpeg.v2.masking

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMaskResult
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

class SkyMaskEstimator {
    fun estimate(image: ArgbPixelImage): SkyMaskResult {
        val blockSize = max(4, minOf(image.width, image.height) / TARGET_BLOCKS_SHORT_SIDE)
        val columns = ceil(image.width.toDouble() / blockSize).toInt()
        val rows = ceil(image.height.toDouble() / blockSize).toInt()
        val stats = Array(columns * rows) { index ->
            val blockX = index % columns
            val blockY = index / columns
            blockStats(image, blockX, blockY, blockSize)
        }
        val medianTexture = median(stats.map { it.texture })
        val medianBrightness = median(stats.map { it.mean })
        val textureLimit = max(18f, medianTexture * 2.2f + 2f)
        val initial = BooleanArray(stats.size) { index ->
            val x = index % columns
            val y = index / columns
            val centerFraction = (y + 0.5f) / rows
            val block = stats[index]
            val border = x == 0 || y == 0 || x == columns - 1 || y == rows - 1
            val upperCandidate = centerFraction <= PRIMARY_SKY_LIMIT
            val lowerCandidate = centerFraction <= SECONDARY_SKY_LIMIT &&
                block.texture <= max(6f, medianTexture * 1.35f + 1f)
            !border &&
                (upperCandidate || lowerCandidate) &&
                block.texture <= textureLimit &&
                block.strongEdgeRatio <= MAX_STRONG_EDGE_RATIO &&
                block.saturatedRatio <= MAX_SATURATED_RATIO &&
                block.mean <= minOf(225f, medianBrightness + 105f)
        }

        val cleaned = removeIsolatedBlocks(initial, columns, rows)
        val component = bestSkyComponent(cleaned, columns, rows)
        val skyComponent = fillSmallSkyHoles(component, stats, columns, rows)
        val upperBlocks = max(1, columns * max(1, (rows * PRIMARY_SKY_LIMIT).toInt()))
        val coverage = skyComponent.count { it }.toFloat() / upperBlocks
        val averageTexture = stats.indices
            .filter { skyComponent[it] }
            .map { stats[it].texture }
            .average()
            .takeIf { it.isFinite() }
            ?.toFloat()
            ?: Float.POSITIVE_INFINITY
        val textureConfidence = (1f - averageTexture / (textureLimit + 1f)).coerceIn(0f, 1f)
        val confidence = (coverage.coerceIn(0f, 1f) * 0.72f + textureConfidence * 0.28f)
            .coerceIn(0f, 1f)
        val minimumComponent = max(3, columns / 3)

        if (skyComponent.count { it } >= minimumComponent && confidence >= MIN_CONFIDENCE) {
            return SkyMaskResult(
                mask = expandMask(skyComponent, columns, rows, blockSize, image.width, image.height),
                confidence = confidence,
                usedFallback = false
            )
        }

        val fallback = BooleanArray(stats.size) { index ->
            val x = index % columns
            val y = index / columns
            val centerFraction = (y + 0.5f) / rows
            val block = stats[index]
            val border = x == 0 || y == 0 || x == columns - 1 || y == rows - 1
            !border && centerFraction <= FALLBACK_SKY_LIMIT &&
                block.texture <= max(12f, medianTexture * 3f + 4f) &&
                block.strongEdgeRatio <= FALLBACK_MAX_EDGE_RATIO &&
                block.saturatedRatio <= FALLBACK_MAX_SATURATED_RATIO &&
                block.mean < 235f
        }
        val fallbackComponent = bestSkyComponent(
            removeIsolatedBlocks(fallback, columns, rows),
            columns,
            rows
        )
        val usableFallback = if (fallbackComponent.any { it }) {
            fallbackComponent
        } else {
            conservativeUpperMask(stats, columns, rows)
        }
        val fallbackCoverage = usableFallback.count { it }.toFloat() / upperBlocks
        return SkyMaskResult(
            mask = expandMask(usableFallback, columns, rows, blockSize, image.width, image.height),
            confidence = minOf(0.24f, fallbackCoverage * 0.35f),
            usedFallback = true
        )
    }

    private data class BlockStats(
        val mean: Float,
        val texture: Float,
        val strongEdgeRatio: Float,
        val saturatedRatio: Float
    )

    private fun blockStats(
        image: ArgbPixelImage,
        blockX: Int,
        blockY: Int,
        blockSize: Int
    ): BlockStats {
        val left = blockX * blockSize
        val top = blockY * blockSize
        val right = minOf(image.width, left + blockSize)
        val bottom = minOf(image.height, top + blockSize)
        var luminanceSum = 0L
        var textureSum = 0L
        var gradientCount = 0
        var strongEdges = 0
        var saturated = 0
        var pixels = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val color = image.pixelAt(x, y)
                val value = pixelLuminance(color)
                luminanceSum += value
                pixels++
                val red = color ushr 16 and 0xFF
                val green = color ushr 8 and 0xFF
                val blue = color and 0xFF
                if (maxOf(red, green, blue) >= 250) saturated++
                if (x > left) {
                    val difference = abs(value - pixelLuminance(image.pixelAt(x - 1, y)))
                    textureSum += difference
                    gradientCount++
                    if (difference >= STRONG_EDGE_DELTA) strongEdges++
                }
                if (y > top) {
                    val difference = abs(value - pixelLuminance(image.pixelAt(x, y - 1)))
                    textureSum += difference
                    gradientCount++
                    if (difference >= STRONG_EDGE_DELTA) strongEdges++
                }
            }
        }
        return BlockStats(
            mean = luminanceSum.toFloat() / max(1, pixels),
            texture = textureSum.toFloat() / max(1, gradientCount),
            strongEdgeRatio = strongEdges.toFloat() / max(1, gradientCount),
            saturatedRatio = saturated.toFloat() / max(1, pixels)
        )
    }

    private fun removeIsolatedBlocks(
        source: BooleanArray,
        columns: Int,
        rows: Int
    ): BooleanArray = BooleanArray(source.size) { index ->
        if (!source[index]) return@BooleanArray false
        val x = index % columns
        val y = index / columns
        var neighbors = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until columns && ny in 0 until rows && source[ny * columns + nx]) {
                neighbors++
            }
        }
        neighbors >= 2
    }

    private fun fillSmallSkyHoles(
        source: BooleanArray,
        stats: Array<BlockStats>,
        columns: Int,
        rows: Int
    ): BooleanArray = source.copyOf().also { result ->
        source.indices.forEach { index ->
            if (source[index]) return@forEach
            val x = index % columns
            val y = index / columns
            if (x == 0 || y == 0 || x == columns - 1 || y == rows - 1) return@forEach
            if (stats[index].saturatedRatio > MAX_SATURATED_RATIO || stats[index].mean > 225f) {
                return@forEach
            }
            var neighbors = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                if (source[(y + dy) * columns + x + dx]) neighbors++
            }
            if (neighbors >= 5) result[index] = true
        }
    }

    private fun bestSkyComponent(
        source: BooleanArray,
        columns: Int,
        rows: Int
    ): BooleanArray {
        val visited = BooleanArray(source.size)
        var best = emptyList<Int>()
        var bestScore = -1
        source.indices.forEach { start ->
            if (!source[start] || visited[start]) return@forEach
            val queue = ArrayDeque<Int>()
            val component = mutableListOf<Int>()
            queue.add(start)
            visited[start] = true
            var upperBlocks = 0
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                component += index
                val x = index % columns
                val y = index / columns
                if (y <= rows / 3) upperBlocks++
                val neighbors = intArrayOf(index - 1, index + 1, index - columns, index + columns)
                neighbors.forEach { next ->
                    if (next !in source.indices || visited[next] || !source[next]) return@forEach
                    val nextX = next % columns
                    val nextY = next / columns
                    if (abs(nextX - x) + abs(nextY - y) != 1) return@forEach
                    visited[next] = true
                    queue.add(next)
                }
            }
            val score = component.size * 3 + upperBlocks * 2
            if (score > bestScore || (score == bestScore && component.first() < best.firstOrNull() ?: Int.MAX_VALUE)) {
                bestScore = score
                best = component
            }
        }
        return BooleanArray(source.size).also { result -> best.forEach { result[it] = true } }
    }

    private fun conservativeUpperMask(
        stats: Array<BlockStats>,
        columns: Int,
        rows: Int
    ): BooleanArray {
        val textureLimit = max(18f, median(stats.map { it.texture }) * 3f + 4f)
        return BooleanArray(stats.size) { index ->
            val x = index % columns
            val y = index / columns
            x in 1 until columns - 1 && y in 1 until max(2, (rows * 0.45f).toInt()) &&
                stats[index].texture <= textureLimit &&
                stats[index].strongEdgeRatio <= FALLBACK_MAX_EDGE_RATIO &&
                stats[index].saturatedRatio <= FALLBACK_MAX_SATURATED_RATIO &&
                stats[index].mean < 235f
        }
    }

    private fun expandMask(
        blocks: BooleanArray,
        columns: Int,
        rows: Int,
        blockSize: Int,
        width: Int,
        height: Int
    ): SkyMask {
        val pixels = BooleanArray(width * height)
        for (blockY in 0 until rows) for (blockX in 0 until columns) {
            if (!blocks[blockY * columns + blockX]) continue
            val left = blockX * blockSize
            val top = blockY * blockSize
            val right = minOf(width, left + blockSize)
            val bottom = minOf(height, top + blockSize)
            for (y in top until bottom) {
                for (x in left until right) pixels[y * width + x] = true
            }
        }
        return SkyMask(width, height, pixels)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    companion object {
        private const val TARGET_BLOCKS_SHORT_SIDE = 28
        private const val PRIMARY_SKY_LIMIT = 0.68f
        private const val SECONDARY_SKY_LIMIT = 0.78f
        private const val FALLBACK_SKY_LIMIT = 0.58f
        private const val STRONG_EDGE_DELTA = 38
        private const val MAX_STRONG_EDGE_RATIO = 0.16f
        private const val FALLBACK_MAX_EDGE_RATIO = 0.17f
        private const val MAX_SATURATED_RATIO = 0.025f
        private const val FALLBACK_MAX_SATURATED_RATIO = 0.05f
        private const val MIN_CONFIDENCE = 0.25f
    }
}
