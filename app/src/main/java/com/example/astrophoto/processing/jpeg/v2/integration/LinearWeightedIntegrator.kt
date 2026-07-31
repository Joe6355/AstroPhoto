package com.example.astrophoto.processing.jpeg.v2.integration

import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.model.IntegrationDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.IntegrationMode
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.example.astrophoto.processing.jpeg.v2.model.SensorDefectRegionReport
import com.example.astrophoto.processing.jpeg.v2.model.TileSpec
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.MutableSampledSrgb
import com.example.astrophoto.processing.jpeg.v2.sampling.PreparedReferenceToSourceTransform
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
        sensorDefectMask: SensorDefectMask? = null,
        includeOutputPixel: (Int, Int) -> Boolean = { _, _ -> true },
        writeTile: (TileSpec, IntArray) -> Unit,
        writeCoverageTile: (TileSpec, FloatArray) -> Unit = { _, _ -> },
        writeSensorDefectAffectedTile: (TileSpec, BooleanArray) -> Unit = { _, _ -> },
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
        val activeSensorDefectMask = sensorDefectMask?.takeIf {
            it.enabled && it.regions.isNotEmpty()
        }
        val filteringApplied = activeSensorDefectMask != null
        val preparedTransforms = if (filteringApplied) {
            frames.associate { frame ->
                frame.id to PreparedReferenceToSourceTransform(
                    frame.transform.referenceToSourceTransform()
                )
            }
        } else {
            emptyMap()
        }
        var excludedSamples = 0L
        var affectedOutputPixels = 0L
        var includedOutputPixels = 0L
        var insufficientCoveragePixels = 0L
        var minimumValidWeight = Float.POSITIVE_INFINITY
        var maximumValidWeight = 0f
        val validWeightRatioHistogram = LongArray(VALID_WEIGHT_RATIO_HISTOGRAM_BINS)
        for (tile in plan.tiles) {
            currentCoroutineContext().ensureActive()
            val accumulator = RobustSampleAccumulator(tile.pixelCount, robustMode)
            val affectedByMask = if (filteringApplied) BooleanArray(tile.pixelCount) else null
            for (frame in frames) {
                currentCoroutineContext().ensureActive()
                openSource(frame.source).use { source ->
                    if (filteringApplied) {
                        val mask = checkNotNull(activeSensorDefectMask)
                        require(
                            source.width == mask.width &&
                                source.height == mask.height
                        ) {
                            "Sensor mask ${mask.width}x${mask.height} " +
                                "does not match source ${source.width}x${source.height}"
                        }
                    }
                    val preparedTransform = preparedTransforms[frame.id]
                    val reusableSample = if (filteringApplied) MutableSampledSrgb() else null
                    for (localY in 0 until tile.height) {
                        if (localY % 32 == 0) currentCoroutineContext().ensureActive()
                        val outputY = tile.top + localY
                        for (localX in 0 until tile.width) {
                            val outputX = tile.left + localX
                            if (!includeOutputPixel(outputX, outputY)) continue
                            val accumulatorIndex = localY * tile.width + localX
                            if (filteringApplied) {
                                val mask = checkNotNull(activeSensorDefectMask)
                                val transform = checkNotNull(preparedTransform)
                                val sourceX = transform.sourceX(
                                    outputX.toFloat(),
                                    outputY.toFloat()
                                )
                                val sourceY = transform.sourceY(
                                    outputX.toFloat(),
                                    outputY.toFloat()
                                )
                                if (mask.intersectsBilinearSample(sourceX, sourceY)) {
                                    excludedSamples++
                                    checkNotNull(affectedByMask)[accumulatorIndex] = true
                                    continue
                                }
                                val sample = checkNotNull(reusableSample)
                                if (!sampler.sampleAt(source, sourceX, sourceY, sample)) continue
                                accumulator.add(
                                    index = accumulatorIndex,
                                    red = SrgbTransfer.srgbToLinear(sample.red),
                                    green = SrgbTransfer.srgbToLinear(sample.green),
                                    blue = SrgbTransfer.srgbToLinear(sample.blue),
                                    weight = frame.normalizedWeight
                                )
                            } else {
                                val sample = sampler.sample(
                                    source,
                                    frame.transform,
                                    outputX.toFloat(),
                                    outputY.toFloat()
                                ) ?: continue
                                accumulator.add(
                                    index = accumulatorIndex,
                                    red = SrgbTransfer.srgbToLinear(sample.red),
                                    green = SrgbTransfer.srgbToLinear(sample.green),
                                    blue = SrgbTransfer.srgbToLinear(sample.blue),
                                    weight = frame.normalizedWeight
                                )
                            }
                        }
                    }
                }
            }
            val output = IntArray(tile.pixelCount)
            val coverage = FloatArray(tile.pixelCount)
            output.indices.forEach { index ->
                val outputX = tile.left + index % tile.width
                val outputY = tile.top + index / tile.width
                if (!includeOutputPixel(outputX, outputY)) {
                    output[index] = OPAQUE_BLACK
                    coverage[index] = 0f
                    return@forEach
                }
                includedOutputPixels++
                if (affectedByMask?.get(index) == true) affectedOutputPixels++
                val pixel = accumulator.finish(index)
                if (pixel == null) {
                    output[index] = OPAQUE_BLACK
                    coverage[index] = 0f
                    insufficientCoveragePixels++
                    minimumValidWeight = 0f
                    validWeightRatioHistogram[0]++
                } else {
                    validPixels++
                    minimumWeight = minOf(minimumWeight, pixel.accumulatedWeight)
                    maximumWeight = maxOf(maximumWeight, pixel.accumulatedWeight)
                    minimumValidWeight = minOf(minimumValidWeight, pixel.accumulatedWeight)
                    maximumValidWeight = maxOf(maximumValidWeight, pixel.accumulatedWeight)
                    val ratio = (pixel.accumulatedWeight / expectedWeight).coerceIn(0f, 1f)
                    coverage[index] = ratio
                    validWeightRatioHistogram[
                        (ratio * validWeightRatioHistogram.lastIndex).roundToInt()
                            .coerceIn(0, validWeightRatioHistogram.lastIndex)
                    ]++
                    output[index] = linearToArgb(pixel.red, pixel.green, pixel.blue)
                }
            }
            writeTile(tile, output)
            writeCoverageTile(tile, coverage)
            affectedByMask?.let { writeSensorDefectAffectedTile(tile, it) }
            onTileCompleted(tile)
        }
        val totalPixels = outputWidth.toLong() * outputHeight
        val durationMillis = (System.nanoTime() - started) / 1_000_000L
        val medianValidWeightRatio = histogramMedianRatio(
            validWeightRatioHistogram,
            includedOutputPixels
        )
        val minimumValidWeightRatio = if (includedOutputPixels == 0L) {
            0f
        } else {
            (minimumValidWeight.takeIf { it.isFinite() } ?: 0f) / expectedWeight
        }
        val maximumValidWeightRatio = if (includedOutputPixels == 0L) {
            0f
        } else {
            maximumValidWeight / expectedWeight
        }
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
            processingDurationMillis = durationMillis,
            estimatedPeakWorkingMemoryBytes = plan.estimatedPeakWorkingMemoryBytes,
            resolutionChanged = false,
            robustModeReason = if (allowRobustClipping) {
                if (robustMode) "legacy_repeatability_mode" else "too_few_samples_for_legacy_robust_mode"
            } else {
                "faint_star_preservation"
            },
            sensorDefectFiltering = SensorDefectFilteringReport(
                regions = sensorDefectMask?.regions.orEmpty().map { region ->
                    SensorDefectRegionReport(
                        stableRegionId = region.stableRegionId,
                        footprintPixelCount = region.footprintPixels.size,
                        recurrence = region.recurrence,
                        totalFrameCount = region.totalFrameCount,
                        skySpaceSupport = region.skySpaceSupport,
                        confidence = region.confidence,
                        classificationReason = region.classificationReason
                    )
                },
                maskedSourcePixelCount = sensorDefectMask?.maskedPixelCount ?: 0,
                maskedSourceFraction = sensorDefectMask?.maskedSourceFraction ?: 0f,
                excludedSampleCount = excludedSamples,
                affectedOutputPixelCount = affectedOutputPixels.coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
                expectedUnmaskedWeight = expectedWeight,
                minimumValidWeight = minimumValidWeight.takeIf { it.isFinite() } ?: 0f,
                medianValidWeight = medianValidWeightRatio * expectedWeight,
                maximumValidWeight = maximumValidWeight,
                minimumValidWeightRatio = minimumValidWeightRatio.coerceIn(0f, 1f),
                medianValidWeightRatio = medianValidWeightRatio,
                maximumValidWeightRatio = maximumValidWeightRatio.coerceIn(0f, 1f),
                insufficientCoveragePixelCount =
                    insufficientCoveragePixels.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                insufficientCoverageFraction =
                    insufficientCoveragePixels.toFloat() /
                        includedOutputPixels.coerceAtLeast(1L),
                maskEnabled = sensorDefectMask?.enabled == true,
                sampleLevelFilteringApplied = filteringApplied,
                fallbackOrRejectionReason = when {
                    sensorDefectMask == null -> "sensor_defect_mask_not_supplied"
                    !sensorDefectMask.enabled ->
                        sensorDefectMask.rejectionReason ?: "sensor_defect_mask_disabled"
                    sensorDefectMask.regions.isEmpty() -> "no_confirmed_sensor_defects"
                    insufficientCoveragePixels > 0L -> "insufficient_coverage"
                    else -> null
                },
                integrationDurationMillis = durationMillis
            )
        )
    }

    private fun histogramMedianRatio(histogram: LongArray, total: Long): Float {
        if (total <= 0L) return 0f
        val target = (total - 1L) / 2L
        var seen = 0L
        histogram.indices.forEach { index ->
            seen += histogram[index]
            if (seen > target) return index.toFloat() / histogram.lastIndex
        }
        return 1f
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
        private const val VALID_WEIGHT_RATIO_HISTOGRAM_BINS = 10_001
    }
}
