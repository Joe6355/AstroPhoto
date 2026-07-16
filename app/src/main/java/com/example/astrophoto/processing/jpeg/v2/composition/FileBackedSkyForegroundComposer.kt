package com.example.astrophoto.processing.jpeg.v2.composition

import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.example.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.CompositeDiagnostics
import com.example.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneWriter
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import kotlin.math.abs
import kotlin.math.roundToInt

class AlphaMaskPixelSource(private val mask: AlphaMask) : AlphaPixelSource {
    override val width: Int get() = mask.width
    override val height: Int get() = mask.height
    override fun alphaAt(x: Int, y: Int): Float = mask.alphaAt(x, y)
}

data class FileBackedCompositeResult(
    val image: FileBackedImage,
    val effectiveSkyAlpha: FileBackedFloatPlane,
    val diagnostics: CompositeDiagnostics,
    val tileWidth: Int,
    val tileHeight: Int
)

class FileBackedSkyForegroundComposer {
    fun compose(
        stackedSky: FileBackedImage,
        reference: FileBackedImage,
        featheredSkyMask: AlphaPixelSource,
        validCoverage: AlphaPixelSource,
        output: FileBackedImageWriter,
        effectiveAlphaOutput: FileBackedFloatPlaneWriter,
        memoryBudget: JpegMemoryBudget,
        memoryTracker: PipelineMemoryTracker? = null,
        precomputedEffectiveSkyAlpha: AlphaPixelSource? = null
    ): FileBackedCompositeResult {
        require(stackedSky.width == reference.width && stackedSky.height == reference.height)
        require(featheredSkyMask.width == reference.width && featheredSkyMask.height == reference.height)
        require(validCoverage.width == reference.width && validCoverage.height == reference.height)
        require(output.image.width == reference.width && output.image.height == reference.height)
        require(effectiveAlphaOutput.plane.width == reference.width && effectiveAlphaOutput.plane.height == reference.height)
        require(
            precomputedEffectiveSkyAlpha == null ||
                (precomputedEffectiveSkyAlpha.width == reference.width &&
                    precomputedEffectiveSkyAlpha.height == reference.height)
        )
        val started = System.nanoTime()
        val tile = memoryBudget.chooseTile(
            outputWidth = reference.width,
            outputHeight = reference.height,
            preferredTileWidth = minOf(reference.width, PREFERRED_TILE_SIZE),
            preferredTileHeight = minOf(reference.height, PREFERRED_TILE_SIZE),
            argbBuffers = 3,
            floatBuffers = 3
        )
        require(tile.accepted) { "Insufficient safe memory for tiled sky composition" }
        if (tile.retryRequired) memoryTracker?.recordRetry()
        memoryTracker?.recordTile("composition", tile.tileWidth, tile.tileHeight)
        memoryTracker?.recordBoundary("composition", tile.estimatedBytes, 0)

        var skyCoverageSum = 0.0
        var skyMaskWeight = 0L
        var fallbackSum = 0.0
        var maximumForegroundDifference = 0
        FileBackedImageReader(stackedSky, cachedRows = 2).use { stacked ->
            FileBackedImageReader(reference, cachedRows = 2).use { referenceReader ->
                var top = 0
                while (top < reference.height) {
                    val tileHeight = minOf(tile.tileHeight, reference.height - top)
                    var left = 0
                    while (left < reference.width) {
                        val tileWidth = minOf(tile.tileWidth, reference.width - left)
                        val pixelCount = tileWidth * tileHeight
                        val stackedPixels = IntArray(pixelCount)
                        val referencePixels = IntArray(pixelCount)
                        val resultPixels = IntArray(pixelCount)
                        val effectiveValues = FloatArray(pixelCount)
                        stacked.readTile(left, top, tileWidth, tileHeight, stackedPixels)
                        referenceReader.readTile(left, top, tileWidth, tileHeight, referencePixels)
                        for (row in 0 until tileHeight) for (column in 0 until tileWidth) {
                            val index = row * tileWidth + column
                            val x = left + column
                            val y = top + row
                            val maskAlpha = featheredSkyMask.alphaAt(x, y)
                            val coverage = validCoverage.alphaAt(x, y)
                            val effectiveAlpha = precomputedEffectiveSkyAlpha?.alphaAt(x, y)
                                ?: (maskAlpha * coverage).coerceIn(0f, 1f)
                            effectiveValues[index] = effectiveAlpha
                            if (maskAlpha > 0f) {
                                skyCoverageSum += coverage
                                skyMaskWeight++
                            }
                            fallbackSum += 1f - effectiveAlpha
                            val referenceColor = referencePixels[index]
                            val resultColor = when {
                                effectiveAlpha <= EXACT_ALPHA_EPSILON -> referenceColor
                                effectiveAlpha >= 1f - EXACT_ALPHA_EPSILON -> stackedPixels[index]
                                else -> linearBlend(stackedPixels[index], referenceColor, effectiveAlpha)
                            } or OPAQUE_ALPHA
                            resultPixels[index] = resultColor
                            if (maskAlpha <= EXACT_ALPHA_EPSILON) {
                                maximumForegroundDifference = maxOf(
                                    maximumForegroundDifference,
                                    maximumChannelDifference(referenceColor, resultColor)
                                )
                            }
                        }
                        output.writeTile(left, top, tileWidth, tileHeight, resultPixels)
                        effectiveAlphaOutput.writeTile(left, top, tileWidth, tileHeight, effectiveValues)
                        left += tileWidth
                    }
                    top += tileHeight
                }
            }
        }
        val outputImage = output.finish()
        val effectivePlane = effectiveAlphaOutput.finish()
        val sharpness = foregroundSharpness(reference, outputImage, effectivePlane)
        return FileBackedCompositeResult(
            image = outputImage,
            effectiveSkyAlpha = effectivePlane,
            diagnostics = CompositeDiagnostics(
                validSkyCoverageRatio = if (skyMaskWeight > 0L) {
                    (skyCoverageSum / skyMaskWeight).toFloat()
                } else {
                    0f
                },
                referenceFallbackRatio = (fallbackSum / (reference.width.toLong() * reference.height)).toFloat(),
                foregroundSharpnessBefore = sharpness.first,
                foregroundSharpnessAfter = sharpness.second,
                maximumForegroundChannelDifference = maximumForegroundDifference,
                outputWidth = reference.width,
                outputHeight = reference.height,
                cropApplied = false,
                compositionDurationMillis = (System.nanoTime() - started) / 1_000_000L
            ),
            tileWidth = tile.tileWidth,
            tileHeight = tile.tileHeight
        )
    }

    private fun foregroundSharpness(
        reference: FileBackedImage,
        output: FileBackedImage,
        alpha: FileBackedFloatPlane
    ): Pair<Float, Float> {
        var referenceSum = 0L
        var outputSum = 0L
        var count = 0L
        FileBackedImageReader(reference, cachedRows = 3).use { referenceReader ->
            FileBackedImageReader(output, cachedRows = 3).use { outputReader ->
                FileBackedFloatPlaneReader(alpha, cachedRows = 3).use { alphaReader ->
                    for (y in 0 until reference.height) for (x in 0 until reference.width) {
                        if (alphaReader.alphaAt(x, y) > FOREGROUND_ALPHA_LIMIT) continue
                        fun add(nx: Int, ny: Int) {
                            if (nx !in 0 until reference.width || ny !in 0 until reference.height ||
                                alphaReader.alphaAt(nx, ny) > FOREGROUND_ALPHA_LIMIT
                            ) return
                            referenceSum += abs(
                                pixelLuminance(referenceReader.argbAt(x, y)) -
                                    pixelLuminance(referenceReader.argbAt(nx, ny))
                            )
                            outputSum += abs(
                                pixelLuminance(outputReader.argbAt(x, y)) -
                                    pixelLuminance(outputReader.argbAt(nx, ny))
                            )
                            count++
                        }
                        add(x + 1, y)
                        add(x, y + 1)
                    }
                }
            }
        }
        return referenceSum.toFloat() / count.coerceAtLeast(1L) to
            outputSum.toFloat() / count.coerceAtLeast(1L)
    }

    private fun linearBlend(stacked: Int, reference: Int, alpha: Float): Int {
        fun blendChannel(shift: Int): Int {
            val stackedSrgb = (stacked ushr shift and 0xFF) / 255f
            val referenceSrgb = (reference ushr shift and 0xFF) / 255f
            val linear = SrgbTransfer.srgbToLinear(stackedSrgb) * alpha +
                SrgbTransfer.srgbToLinear(referenceSrgb) * (1f - alpha)
            return (SrgbTransfer.linearToSrgb(linear) * 255f).roundToInt().coerceIn(0, 255)
        }
        return OPAQUE_ALPHA or (blendChannel(16) shl 16) or (blendChannel(8) shl 8) or blendChannel(0)
    }

    private fun maximumChannelDifference(first: Int, second: Int): Int = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    )

    companion object {
        private const val PREFERRED_TILE_SIZE = 256
        private const val EXACT_ALPHA_EPSILON = 0.0001f
        private const val FOREGROUND_ALPHA_LIMIT = 0.001f
        private const val OPAQUE_ALPHA = 0xFF000000.toInt()
    }
}
