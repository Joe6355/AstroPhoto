package com.example.astrophoto.processing.jpeg.v2.model

class SkyMask(
    val width: Int,
    val height: Int,
    private val pixels: BooleanArray
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    fun contains(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && pixels[y * width + x]

    fun retainedFraction(): Float = pixels.count { it }.toFloat() / pixels.size

    companion object {
        fun full(width: Int, height: Int) = SkyMask(
            width,
            height,
            BooleanArray(width * height) { true }
        )
    }
}

data class SkyMaskResult(
    val mask: SkyMask,
    val confidence: Float,
    val usedFallback: Boolean
)
