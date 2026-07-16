package com.example.astrophoto.processing.jpeg.v2.memory

data class ImageAllocationEstimate(
    val label: String,
    val bytes: Long
) {
    init {
        require(label.isNotBlank())
        require(bytes >= 0L)
    }

    companion object {
        fun bitmap(width: Int, height: Int, label: String = "bitmap") =
            ImageAllocationEstimate(label, pixels(width, height, Int.SIZE_BYTES))

        fun intArray(width: Int, height: Int, label: String = "argb") =
            ImageAllocationEstimate(label, pixels(width, height, Int.SIZE_BYTES))

        fun floatArray(width: Int, height: Int, label: String = "float-plane") =
            ImageAllocationEstimate(label, pixels(width, height, Float.SIZE_BYTES))

        fun booleanMask(width: Int, height: Int, label: String = "mask") =
            ImageAllocationEstimate(label, pixels(width, height, 1))

        fun tile(
            width: Int,
            height: Int,
            argbBuffers: Int,
            floatBuffers: Int,
            maskBuffers: Int = 0,
            halo: Int = 0,
            label: String = "tile"
        ): ImageAllocationEstimate {
            require(argbBuffers >= 0 && floatBuffers >= 0 && maskBuffers >= 0 && halo >= 0)
            val expandedWidth = width.toLong() + halo * 2L
            val expandedHeight = height.toLong() + halo * 2L
            val pixelCount = multiplyExact(expandedWidth, expandedHeight)
            val bytesPerPixel = argbBuffers.toLong() * Int.SIZE_BYTES +
                floatBuffers.toLong() * Float.SIZE_BYTES + maskBuffers
            return ImageAllocationEstimate(label, multiplyExact(pixelCount, bytesPerPixel))
        }

        private fun pixels(width: Int, height: Int, bytesPerPixel: Int): Long {
            require(width > 0 && height > 0 && bytesPerPixel > 0)
            return multiplyExact(multiplyExact(width.toLong(), height.toLong()), bytesPerPixel.toLong())
        }

        private fun multiplyExact(first: Long, second: Long): Long = try {
            Math.multiplyExact(first, second)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Image allocation estimate overflow", error)
        }
    }
}
