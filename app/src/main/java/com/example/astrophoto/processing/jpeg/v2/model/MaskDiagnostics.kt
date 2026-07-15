package com.example.astrophoto.processing.jpeg.v2.model

class AlphaMask(
    val width: Int,
    val height: Int,
    private val values: FloatArray
) {
    init {
        require(width > 0 && height > 0 && values.size == width * height)
        require(values.all { it.isFinite() && it in 0f..1f })
    }

    fun alphaAt(x: Int, y: Int): Float =
        if (x in 0 until width && y in 0 until height) values[y * width + x] else 0f

    fun copyValues(): FloatArray = values.copyOf()

    fun mean(): Float = values.average().toFloat()

    companion object {
        fun full(width: Int, height: Int) = AlphaMask(
            width,
            height,
            FloatArray(width * height) { 1f }
        )

        fun empty(width: Int, height: Int) = AlphaMask(
            width,
            height,
            FloatArray(width * height)
        )
    }
}

data class MaskDiagnostics(
    val initialSkyRatio: Float,
    val refinedSkyRatio: Float,
    val removedIslandPixels: Int,
    val filledHolePixels: Int,
    val edgeRejectedPixels: Int,
    val borderStructurePixels: Int,
    val featherRadius: Int = 0
)

data class RefinedSkyMask(
    val binaryMask: SkyMask,
    val featheredMask: AlphaMask,
    val confidence: Float,
    val protectedForegroundRatio: Float,
    val usedFallback: Boolean,
    val diagnostics: MaskDiagnostics
)

data class ForegroundProtectionResult(
    val mask: SkyMask,
    val protectedPixelCount: Int,
    val dilationRadius: Int
)
