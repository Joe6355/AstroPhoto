package com.example.astrophoto.processing.jpeg.v2.integration

import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.model.IntegrationDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.IntegrationMode
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.model.TileSpec
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

data class WeightedIntegrationFrame<T>(
    val id: String,
    val source: T,
    val transform: RegistrationResult,
    val normalizedWeight: Float
)

class LinearWeightedIntegrator(
    private val sampler: TransformedBitmapSampler = TransformedBitmapSampler(),
    private val tileCoordinator: TileProcessingCoordinator = TileProcessingCoordinator()
) {
    suspend fun <T> integrate(
        outputWidth: Int,
        outputHeight: Int,
        frames: List<WeightedIntegrationFrame<T>>,
        maximumWorkingMemoryBytes: Long,
        openSource: (T) -> ArgbPixelSource,
        allowRobustClipping: Boolean = true,
        includeOutputPixel: (Int, Int) -> Boolean = { _, _ -> true },
        writeTile: (TileSpec, IntArray) -> Unit,
        writeCoverageTile: (TileSpec, FloatArray) -> Unit = { _, _ -> },
        onTileCompleted: suspend (TileSpec) -> Unit = {}
    ): IntegrationDiagnostics {
        require(outputWidth > 0 && outputHeight > 0)
        require(frames.isNotEmpty())
        require(frames.all { it.transform.isReliable && it.normalizedWeight > 0f })
        val robustMode = allowRobustClipping &&
            frames.size >= RobustSampleAccumulator.MIN_ROBUST_SAMPLES
        val plan = tileCoordinator.plan(
            outputWidth,
            outputHeight,
            robustMode,
            maximumWorkingMemoryBytes
        )
        val started = System.nanoTime()
        var validPixels = 0L
        var minimumWeight = Float.POSITIVE_INFINITY
        var maximumWeight = 0f
        val expectedWeight = frames.sumOf { it.normalizedWeight.toDouble() }.toFloat()
        for (tile in plan.tiles) {
            currentCoroutineContext().ensureActive()
            val accumulator = RobustSampleAccumulator(tile.pixelCount, robustMode)
            for (frame in frames) {
                currentCoroutineContext().ensureActive()
                openSource(frame.source).use { source ->
                    for (localY in 0 until tile.height) {
                        if (localY % 32 == 0) currentCoroutineContext().ensureActive()
                        val outputY = tile.top + localY
                        for (localX in 0 until tile.width) {
                            val outputX = tile.left + localX
                            if (!includeOutputPixel(outputX, outputY)) continue
                            val sample = sampler.sample(
                                source,
                                frame.transform,
                                outputX.toFloat(),
                                outputY.toFloat()
                            ) ?: continue
                            accumulator.add(
                                index = localY * tile.width + localX,
                                red = SrgbTransfer.srgbToLinear(sample.red),
                                green = SrgbTransfer.srgbToLinear(sample.green),
                                blue = SrgbTransfer.srgbToLinear(sample.blue),
                                weight = frame.normalizedWeight
                            )
                        }
                    }
                }
            }
            val output = IntArray(tile.pixelCount)
            val coverage = FloatArray(tile.pixelCount)
            output.indices.forEach { index ->
                val pixel = accumulator.finish(index)
                if (pixel == null) {
                    output[index] = OPAQUE_BLACK
                    coverage[index] = 0f
                } else {
                    validPixels++
                    minimumWeight = minOf(minimumWeight, pixel.accumulatedWeight)
                    maximumWeight = maxOf(maximumWeight, pixel.accumulatedWeight)
                    coverage[index] = (pixel.accumulatedWeight / expectedWeight).coerceIn(0f, 1f)
                    output[index] = linearToArgb(pixel.red, pixel.green, pixel.blue)
                }
            }
            writeTile(tile, output)
            writeCoverageTile(tile, coverage)
            onTileCompleted(tile)
        }
        val totalPixels = outputWidth.toLong() * outputHeight
        return IntegrationDiagnostics(
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            tileWidth = plan.tileWidth,
            tileHeight = plan.tileHeight,
            acceptedFrames = frames.size,
            mode = if (robustMode) {
                IntegrationMode.LINEAR_WEIGHTED_REPEATABILITY_ROBUST
            } else {
                IntegrationMode.LINEAR_WEIGHTED_AVERAGE
            },
            robustModeEnabled = robustMode,
            validCoveragePercent = validPixels * 100f / totalPixels,
            minimumAccumulatedWeight = minimumWeight.takeIf { it.isFinite() } ?: 0f,
            maximumAccumulatedWeight = maximumWeight,
            processingDurationMillis = (System.nanoTime() - started) / 1_000_000L,
            estimatedPeakWorkingMemoryBytes = plan.estimatedPeakWorkingMemoryBytes,
            resolutionChanged = false,
            robustModeReason = if (allowRobustClipping) {
                if (robustMode) "legacy_repeatability_mode" else "too_few_samples_for_legacy_robust_mode"
            } else {
                "faint_star_preservation"
            }
        )
    }

    private fun linearToArgb(red: Float, green: Float, blue: Float): Int {
        // This is the only v2 precision boundary before unavoidable legacy Bitmap post-processing.
        val srgbRed = (SrgbTransfer.linearToSrgb(red) * 255f).roundToInt().coerceIn(0, 255)
        val srgbGreen = (SrgbTransfer.linearToSrgb(green) * 255f).roundToInt().coerceIn(0, 255)
        val srgbBlue = (SrgbTransfer.linearToSrgb(blue) * 255f).roundToInt().coerceIn(0, 255)
        return OPAQUE_ALPHA or (srgbRed shl 16) or (srgbGreen shl 8) or srgbBlue
    }

    companion object {
        private const val OPAQUE_ALPHA = 0xFF000000.toInt()
        private const val OPAQUE_BLACK = OPAQUE_ALPHA
    }
}
