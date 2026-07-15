package com.example.astrophoto.processing.jpeg.v2.model

data class TileSpec(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val index: Int,
    val total: Int
) {
    val pixelCount: Int get() = width * height
}

data class TilePlan(
    val outputWidth: Int,
    val outputHeight: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val tiles: List<TileSpec>,
    val estimatedPeakWorkingMemoryBytes: Long
)
