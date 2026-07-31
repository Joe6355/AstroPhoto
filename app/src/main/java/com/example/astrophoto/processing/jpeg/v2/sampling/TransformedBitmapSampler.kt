package com.example.astrophoto.processing.jpeg.v2.sampling

import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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

class MutableSampledSrgb(
    var red: Float = 0f,
    var green: Float = 0f,
    var blue: Float = 0f
)

class PreparedReferenceToSourceTransform(transform: ReferenceToSourceTransform) {
    private val scaledCosine = transform.scale * cos(transform.rotationRadians)
    private val scaledSine = transform.scale * sin(transform.rotationRadians)
    private val centerX = transform.rotationCenterX
    private val centerY = transform.rotationCenterY
    private val dx = transform.dx
    private val dy = transform.dy

    fun sourceX(outputX: Float, outputY: Float): Float {
        val relativeX = outputX - centerX
        val relativeY = outputY - centerY
        return scaledCosine * relativeX - scaledSine * relativeY + centerX + dx
    }

    fun sourceY(outputX: Float, outputY: Float): Float {
        val relativeX = outputX - centerX
        val relativeY = outputY - centerY
        return scaledSine * relativeX + scaledCosine * relativeY + centerY + dy
    }
}

class TransformedBitmapSampler {
    /** Stage 1 transforms map reference/output coordinates directly into candidate/source coordinates. */
    fun sample(
        source: ArgbPixelSource,
        transform: RegistrationResult,
        outputX: Float,
        outputY: Float
    ): SampledSrgb? {
        val sourcePoint = transform.referenceToSourceTransform()
            .mapOutputToSource(outputX, outputY)
        return sampleAt(source, sourcePoint.x, sourcePoint.y)
    }

    fun sampleAt(source: ArgbPixelSource, sourceX: Float, sourceY: Float): SampledSrgb? {
        val destination = MutableSampledSrgb()
        if (!sampleAt(source, sourceX, sourceY, destination)) return null
        return SampledSrgb(destination.red, destination.green, destination.blue)
    }

    fun sampleAt(
        source: ArgbPixelSource,
        sourceX: Float,
        sourceY: Float,
        destination: MutableSampledSrgb
    ): Boolean {
        if (!isCovered(source, sourceX, sourceY)) return false
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
        destination.red = bilinearChannel(
            topLeft ushr 16,
            topRight ushr 16,
            bottomLeft ushr 16,
            bottomRight ushr 16,
            fractionX,
            fractionY
        )
        destination.green = bilinearChannel(
            topLeft ushr 8,
            topRight ushr 8,
            bottomLeft ushr 8,
            bottomRight ushr 8,
            fractionX,
            fractionY
        )
        destination.blue = bilinearChannel(
            topLeft,
            topRight,
            bottomLeft,
            bottomRight,
            fractionX,
            fractionY
        )
        return true
    }

    /** Uses the exact production bilinear coverage and interpolation semantics for patch evidence. */
    fun sampleLuminanceAt(source: ArgbPixelSource, sourceX: Float, sourceY: Float): Float? {
        if (!isCovered(source, sourceX, sourceY)) return null
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val x1 = minOf(source.width - 1, x0 + 1)
        val y1 = minOf(source.height - 1, y0 + 1)
        val fractionX = sourceX - x0
        val fractionY = sourceY - y0
        val topLeft = luminance(source.argbAt(x0, y0))
        val topRight = luminance(source.argbAt(x1, y0))
        val bottomLeft = luminance(source.argbAt(x0, y1))
        val bottomRight = luminance(source.argbAt(x1, y1))
        val top = topLeft + (topRight - topLeft) * fractionX
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fractionX
        return (top + (bottom - top) * fractionY).coerceIn(0f, 1f)
    }

    private fun isCovered(source: ArgbPixelSource, sourceX: Float, sourceY: Float): Boolean =
        sourceX.isFinite() && sourceY.isFinite() &&
            sourceX >= 0f && sourceY >= 0f &&
            sourceX <= source.width - 1f && sourceY <= source.height - 1f

    private fun luminance(argb: Int): Float =
        0.2126f * (argb ushr 16 and 0xFF) / 255f +
            0.7152f * (argb ushr 8 and 0xFF) / 255f +
            0.0722f * (argb and 0xFF) / 255f

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
