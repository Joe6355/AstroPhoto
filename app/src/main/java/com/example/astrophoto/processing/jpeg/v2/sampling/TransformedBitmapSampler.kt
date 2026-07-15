package com.example.astrophoto.processing.jpeg.v2.sampling

import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import kotlin.math.floor

interface ArgbPixelSource : AutoCloseable {
    val width: Int
    val height: Int
    fun argbAt(x: Int, y: Int): Int
    override fun close() = Unit
}

class IntArrayPixelSource(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray
) : ArgbPixelSource {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
    }

    override fun argbAt(x: Int, y: Int): Int = pixels[y * width + x]
}

data class SampledSrgb(
    val red: Float,
    val green: Float,
    val blue: Float
)

class TransformedBitmapSampler {
    /** Stage 1 transforms map reference/output coordinates directly into candidate/source coordinates. */
    fun sample(
        source: ArgbPixelSource,
        transform: RegistrationResult,
        outputX: Float,
        outputY: Float
    ): SampledSrgb? {
        val sourcePoint = transform.transform(outputX, outputY)
        return sampleAt(source, sourcePoint.first, sourcePoint.second)
    }

    fun sampleAt(source: ArgbPixelSource, sourceX: Float, sourceY: Float): SampledSrgb? {
        if (
            !sourceX.isFinite() || !sourceY.isFinite() ||
            sourceX < 0f || sourceY < 0f ||
            sourceX > source.width - 1f || sourceY > source.height - 1f
        ) {
            return null
        }
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val x1 = minOf(source.width - 1, x0 + 1)
        val y1 = minOf(source.height - 1, y0 + 1)
        val fractionX = sourceX - x0
        val fractionY = sourceY - y0
        val topLeft = source.argbAt(x0, y0)
        val topRight = source.argbAt(x1, y0)
        val bottomLeft = source.argbAt(x0, y1)
        val bottomRight = source.argbAt(x1, y1)
        return SampledSrgb(
            red = bilinearChannel(topLeft ushr 16, topRight ushr 16, bottomLeft ushr 16, bottomRight ushr 16, fractionX, fractionY),
            green = bilinearChannel(topLeft ushr 8, topRight ushr 8, bottomLeft ushr 8, bottomRight ushr 8, fractionX, fractionY),
            blue = bilinearChannel(topLeft, topRight, bottomLeft, bottomRight, fractionX, fractionY)
        )
    }

    private fun bilinearChannel(
        topLeftColor: Int,
        topRightColor: Int,
        bottomLeftColor: Int,
        bottomRightColor: Int,
        fractionX: Float,
        fractionY: Float
    ): Float {
        val topLeft = (topLeftColor and 0xFF) / 255f
        val topRight = (topRightColor and 0xFF) / 255f
        val bottomLeft = (bottomLeftColor and 0xFF) / 255f
        val bottomRight = (bottomRightColor and 0xFF) / 255f
        val top = topLeft + (topRight - topLeft) * fractionX
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fractionX
        return (top + (bottom - top) * fractionY).coerceIn(0f, 1f)
    }
}
