package com.joe6355.astrophoto.processing.jpeg.v2.integration

import com.joe6355.astrophoto.processing.jpeg.v2.model.TilePlan
import com.joe6355.astrophoto.processing.jpeg.v2.model.TileSpec

class TileProcessingCoordinator(
    private val preferredTileSize: Int = DEFAULT_TILE_SIZE,
    private val minimumTileSize: Int = MINIMUM_TILE_SIZE
) {
    fun plan(
        outputWidth: Int,
        outputHeight: Int,
        robustMode: Boolean,
        maximumWorkingMemoryBytes: Long,
        residentBufferBytes: Long = outputWidth.toLong() * outputHeight * 8L,
        highPrecisionOutput: Boolean = false
    ): TilePlan {
        require(outputWidth > 0 && outputHeight > 0)
        require(maximumWorkingMemoryBytes > 0)
        var tileWidth = minOf(preferredTileSize, outputWidth)
        var tileHeight = minOf(preferredTileSize, outputHeight)
        require(residentBufferBytes >= 0)
        val fullResolutionBuffers = residentBufferBytes
        val bytesPerTilePixel = (if (robustMode) ROBUST_TILE_BYTES_PER_PIXEL else PLAIN_TILE_BYTES_PER_PIXEL) +
            (if (highPrecisionOutput) 4L else 0L)
        while (
            estimatedPeak(fullResolutionBuffers, tileWidth, tileHeight, bytesPerTilePixel) > maximumWorkingMemoryBytes &&
            (tileWidth > minimumTileSize || tileHeight > minimumTileSize)
        ) {
            if (tileWidth >= tileHeight && tileWidth > minimumTileSize) {
                tileWidth = maxOf(minimumTileSize, tileWidth / 2)
            } else if (tileHeight > minimumTileSize) {
                tileHeight = maxOf(minimumTileSize, tileHeight / 2)
            } else {
                tileWidth = maxOf(minimumTileSize, tileWidth / 2)
            }
            tileWidth = minOf(tileWidth, outputWidth)
            tileHeight = minOf(tileHeight, outputHeight)
        }
        require(estimatedPeak(fullResolutionBuffers, tileWidth, tileHeight, bytesPerTilePixel) <= maximumWorkingMemoryBytes) {
            "Minimum integration tile exceeds safe memory budget"
        }
        val columns = (outputWidth + tileWidth - 1) / tileWidth
        val rows = (outputHeight + tileHeight - 1) / tileHeight
        val total = columns * rows
        val tiles = buildList(total) {
            var index = 0
            for (row in 0 until rows) for (column in 0 until columns) {
                add(
                    TileSpec(
                        left = column * tileWidth,
                        top = row * tileHeight,
                        width = minOf(tileWidth, outputWidth - column * tileWidth),
                        height = minOf(tileHeight, outputHeight - row * tileHeight),
                        index = index++,
                        total = total
                    )
                )
            }
        }
        return TilePlan(
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            tiles = tiles,
            estimatedPeakWorkingMemoryBytes = estimatedPeak(
                fullResolutionBuffers,
                tileWidth,
                tileHeight,
                bytesPerTilePixel
            )
        )
    }

    private fun estimatedPeak(
        fullResolutionBuffers: Long,
        tileWidth: Int,
        tileHeight: Int,
        tileBytesPerPixel: Long
    ): Long = fullResolutionBuffers + tileWidth.toLong() * tileHeight * tileBytesPerPixel

    companion object {
        const val DEFAULT_TILE_SIZE = 256
        const val MINIMUM_TILE_SIZE = 32
        // Accumulators, output, coverage, mask and the optional affected-output callback buffer.
        private const val PLAIN_TILE_BYTES_PER_PIXEL = 33L
        private const val ROBUST_TILE_BYTES_PER_PIXEL = 60L
    }
}
