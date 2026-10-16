package com.joe6355.astrophoto

data class LinearRgbImage(
    val width: Int,
    val height: Int,
    val pixels: FloatArray
) {
    init {
        require(width > 0 && height > 0) { "Linear image dimensions must be positive" }
        require(pixels.size.toLong() == width.toLong() * height * 3L) {
            "Linear RGB sample count does not match dimensions"
        }
        require(pixels.all(Float::isFinite)) { "Linear RGB samples must be finite" }
    }

    fun channelAt(x: Int, y: Int, channel: Int): Float =
        pixels[(y * width + x) * 3 + channel]
}
