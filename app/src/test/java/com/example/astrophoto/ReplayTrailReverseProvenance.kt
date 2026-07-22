package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.SampledSrgb
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object ReplayStage1CThresholds {
    const val VERSION = "replay-trail-reverse-provenance/1"
    const val SUPPORT_RADIUS = 1.5
    const val PEAK_SEARCH_RADIUS = 4
    const val SOURCE_CROP_SIZE = 48
    const val SIGNAL_RESIDUAL_CODES = 1.5
    const val SKY_NEAR_PREDICTED_RADIUS = 1.5
    const val SKY_SUPPORT_FRACTION = 0.40
    const val CAMERA_SUPPORT_FRACTION = 0.20
    const val CAMERA_MIN_SUPPORT = 3
    const val CAMERA_POSITION_RMS = 0.75
    const val TREND_SUPPORT_FRACTION = 0.30
    const val TREND_MIN_SLOPE = 0.15
    const val TREND_MIN_R_SQUARED = 0.50
    const val TRANSIENT_MAX_SUPPORT_FRACTION = 0.30
    const val TRANSIENT_TOP_THREE_ENERGY_FRACTION = 0.70
    const val RESAMPLING_MAX_SUPPORT_FRACTION = 0.20
    const val RESAMPLING_MIN_OUTPUT_RESIDUAL = 1.0
    const val RESAMPLING_MAX_RECONSTRUCTION_ERROR = 1.0
}

internal data class ReplayProvenanceFrame(
    val id: String,
    val captureIndex: Int,
    val transform: ReferenceToSourceTransform,
    val normalizedWeight: Float
)

internal enum class ReplayTrailProvenanceClass {
    SKY_ALIGNED_SIGNAL,
    REGISTRATION_RESIDUAL,
    CAMERA_FIXED_DEFECT,
    TRANSIENT_FRAME_OUTLIER,
    RESAMPLING_OR_QUANTIZATION,
    UNEXPLAINED
}

internal data class ReplayTrailFrameEvidence(
    val frameId: String,
    val captureIndex: Int,
    val frameWeight: Double,
    val predictedCameraX: Double,
    val predictedCameraY: Double,
    val measuredCameraX: Double,
    val measuredCameraY: Double,
    val measuredOutputX: Double,
    val measuredOutputY: Double,
    val peakOffsetX: Double,
    val peakOffsetY: Double,
    val peakAtSearchBoundary: Boolean,
    val peakLuminanceResidualCodes: Double,
    val peakChromaResidualCodes: Double,
    val meanSupportLuminanceResidualCodes: Double,
    val meanSupportChromaResidualCodes: Double,
    val weightedTrailEnergy: Double,
    val signalEnergyContributionPercent: Double,
    val actualCleanStackContributionPercent: Double,
    val validSupportIndices: IntArray,
    val sampledLinearRgb: DoubleArray,
    val supportResidualEnergyCodes: DoubleArray,
    val sourceCrop: ArgbPixelImage
) {
    val supportsSignal: Boolean get() =
        peakLuminanceResidualCodes >= ReplayStage1CThresholds.SIGNAL_RESIDUAL_CODES ||
            peakChromaResidualCodes >= ReplayStage1CThresholds.SIGNAL_RESIDUAL_CODES
}

internal data class ReplayTrailProvenance(
    val annotation: ReplayManualTrailAnnotation,
    val supportPixels: IntArray,
    val frames: List<ReplayTrailFrameEvidence>,
    val classification: ReplayTrailProvenanceClass,
    val confidence: Double,
    val supportingFrameIds: List<String>,
    val cameraPositionRms: Double,
    val offsetTrendXPerFrame: Double,
    val offsetTrendYPerFrame: Double,
    val offsetTrendRSquared: Double,
    val topThreeEnergyFraction: Double,
    val peakSearchBoundaryFraction: Double,
    val outputMedianLuminanceResidualCodes: Double,
    val outputMaximumLuminanceResidualCodes: Double,
    val meanAbsoluteReconstructionErrorCodes: Double,
    val referenceFallbackPercent: Double,
    val contributionHeat: DoubleArray
)

internal data class ReplayStage1CBundle(
    val trails: List<ReplayTrailProvenance>,
    val thresholdManifest: String,
    val thresholdManifestHash: String
)

internal class ReplayTrailReverseProvenanceRunner(
    private val sampler: TransformedBitmapSampler = TransformedBitmapSampler()
) {
    fun run(
        baseline: ArgbPixelImage,
        reference: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        annotations: List<ReplayManualTrailAnnotation>,
        frames: List<ReplayProvenanceFrame>,
        outputRoot: Path,
        imageLoader: (ReplayProvenanceFrame) -> ArgbPixelImage
    ): ReplayStage1CBundle {
        require(baseline.width == reference.width && baseline.height == reference.height)
        require(effectiveSkyAlpha.width == baseline.width && effectiveSkyAlpha.height == baseline.height)
        require(frames.isNotEmpty() && frames.all { it.normalizedWeight > 0f && it.normalizedWeight.isFinite() })
        require(frames.map { it.id }.toSet().size == frames.size)
        val orderedFrames = frames.sortedBy { it.captureIndex }
        val supports = annotations.associateWith { rasterizeSupport(it, baseline.width, baseline.height) }
        val mutableEvidence = annotations.associateWith { mutableListOf<ReplayTrailFrameEvidence>() }
        Files.createDirectories(outputRoot)
        orderedFrames.forEach { frame ->
            val sourceImage = imageLoader(frame)
            require(sourceImage.width == baseline.width && sourceImage.height == baseline.height)
            val source = IntArrayPixelSource(sourceImage.width, sourceImage.height, sourceImage.pixels)
            annotations.forEach { annotation ->
                val evidence = analyzeFrame(
                    annotation,
                    checkNotNull(supports[annotation]),
                    frame,
                    sourceImage,
                    source
                )
                checkNotNull(mutableEvidence[annotation]) += evidence
                val cropRoot = outputRoot.resolve(annotation.id).resolve("source-rgb-crops")
                Files.createDirectories(cropRoot)
                writeImage(
                    cropRoot.resolve("${frame.captureIndex.toString().padStart(3, '0')}-${frame.id}.png"),
                    evidence.sourceCrop
                )
            }
        }
        val provenances = annotations.map { annotation ->
            finalizeTrail(
                baseline,
                reference,
                effectiveSkyAlpha,
                annotation,
                checkNotNull(supports[annotation]),
                checkNotNull(mutableEvidence[annotation])
            )
        }
        val manifest = manifest()
        val bundle = ReplayStage1CBundle(provenances, manifest, sha256(manifest.toByteArray(StandardCharsets.UTF_8)))
        writeOutputs(bundle, baseline, outputRoot)
        return bundle
    }

    private fun analyzeFrame(
        annotation: ReplayManualTrailAnnotation,
        support: IntArray,
        frame: ReplayProvenanceFrame,
        sourceImage: ArgbPixelImage,
        source: IntArrayPixelSource
    ): ReplayTrailFrameEvidence {
        val width = sourceImage.width
        val predictedPoints = support.map { index ->
            ReplayTransformSemantics.outputToSource(
                frame.transform,
                (index % width).toDouble(),
                (index / width).toDouble()
            )
        }
        val centerOutput = annotation.centerline.let { points ->
            ReplayPoint(points.map { it.x }.average(), points.map { it.y }.average())
        }
        val predictedCenter = ReplayTransformSemantics.outputToSource(frame.transform, centerOutput.x, centerOutput.y)
        var bestScore = Double.NEGATIVE_INFINITY
        var bestPoint = predictedCenter
        var bestPredicted = predictedCenter
        var bestResidual = LocalResidual(0.0, 0.0)
        predictedPoints.forEach { predicted ->
            for (dy in -ReplayStage1CThresholds.PEAK_SEARCH_RADIUS..ReplayStage1CThresholds.PEAK_SEARCH_RADIUS) {
                for (dx in -ReplayStage1CThresholds.PEAK_SEARCH_RADIUS..ReplayStage1CThresholds.PEAK_SEARCH_RADIUS) {
                    val candidate = ReplayPoint(predicted.x + dx, predicted.y + dy)
                    val residual = localResidual(source, candidate.x, candidate.y) ?: continue
                    val score = max(0.0, residual.luminanceCodes) + residual.chromaCodes
                    if (score > bestScore) {
                        bestScore = score
                        bestPoint = candidate
                        bestPredicted = predicted
                        bestResidual = residual
                    }
                }
            }
        }
        val sampled = DoubleArray(support.size * 3) { Double.NaN }
        val supportEnergy = DoubleArray(support.size)
        val valid = mutableListOf<Int>()
        var luminanceResidual = 0.0
        var chromaResidual = 0.0
        var energy = 0.0
        support.forEachIndexed { supportIndex, _ ->
            val point = predictedPoints[supportIndex]
            val sample = sampler.sampleAt(source, point.x.toFloat(), point.y.toFloat()) ?: return@forEachIndexed
            val residual = localResidual(source, point.x, point.y) ?: return@forEachIndexed
            sampled[supportIndex * 3] = SrgbTransfer.srgbToLinear(sample.red).toDouble()
            sampled[supportIndex * 3 + 1] = SrgbTransfer.srgbToLinear(sample.green).toDouble()
            sampled[supportIndex * 3 + 2] = SrgbTransfer.srgbToLinear(sample.blue).toDouble()
            valid += supportIndex
            luminanceResidual += residual.luminanceCodes
            chromaResidual += residual.chromaCodes
            supportEnergy[supportIndex] = max(0.0, residual.luminanceCodes) + residual.chromaCodes * 0.25
            energy += supportEnergy[supportIndex]
        }
        val validCount = valid.size.coerceAtLeast(1)
        val measuredOutput = ReplayTransformSemantics.sourceToOutput(frame.transform, bestPoint.x, bestPoint.y)
        val peakAtSearchBoundary =
            abs(bestPoint.x - bestPredicted.x) >= ReplayStage1CThresholds.PEAK_SEARCH_RADIUS.toDouble() ||
                abs(bestPoint.y - bestPredicted.y) >= ReplayStage1CThresholds.PEAK_SEARCH_RADIUS.toDouble()
        val crop = crop(sourceImage, predictedCenter.x.roundToInt(), predictedCenter.y.roundToInt(), ReplayStage1CThresholds.SOURCE_CROP_SIZE)
        return ReplayTrailFrameEvidence(
            frame.id,
            frame.captureIndex,
            frame.normalizedWeight.toDouble(),
            bestPredicted.x,
            bestPredicted.y,
            bestPoint.x,
            bestPoint.y,
            measuredOutput.x,
            measuredOutput.y,
            bestPoint.x - bestPredicted.x,
            bestPoint.y - bestPredicted.y,
            peakAtSearchBoundary,
            bestResidual.luminanceCodes,
            bestResidual.chromaCodes,
            luminanceResidual / validCount,
            chromaResidual / validCount,
            energy * frame.normalizedWeight,
            0.0,
            0.0,
            valid.toIntArray(),
            sampled,
            supportEnergy,
            crop
        )
    }

    private fun finalizeTrail(
        baseline: ArgbPixelImage,
        reference: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        annotation: ReplayManualTrailAnnotation,
        support: IntArray,
        rawFrames: List<ReplayTrailFrameEvidence>
    ): ReplayTrailProvenance {
        val energySum = rawFrames.sumOf { it.weightedTrailEnergy }.coerceAtLeast(1e-12)
        val weightSums = DoubleArray(support.size)
        rawFrames.forEach { frame -> frame.validSupportIndices.forEach { weightSums[it] += frame.frameWeight } }
        val frames = rawFrames.map { frame ->
            val actualContribution = frame.validSupportIndices.map { supportIndex ->
                val outputIndex = support[supportIndex]
                val alpha = effectiveSkyAlpha.alphaAt(outputIndex % baseline.width, outputIndex / baseline.width)
                alpha * frame.frameWeight / weightSums[supportIndex].coerceAtLeast(1e-12)
            }.averageOrZero() * 100.0
            frame.copy(
                signalEnergyContributionPercent = frame.weightedTrailEnergy * 100.0 / energySum,
                actualCleanStackContributionPercent = actualContribution
            )
        }
        val supporting = frames.filter { it.supportsSignal }
        val supportFraction = supporting.size.toDouble() / frames.size
        val nearPredictedFraction = supporting.count {
            hypot(it.peakOffsetX, it.peakOffsetY) <= ReplayStage1CThresholds.SKY_NEAR_PREDICTED_RADIUS
        }.toDouble() / frames.size
        val cameraRms = positionRms(supporting.map { ReplayPoint(it.measuredCameraX, it.measuredCameraY) })
        val trendFrames = supporting.takeIf { it.size >= 2 } ?: frames
        val trendX = regression(trendFrames.map { it.captureIndex.toDouble() }, trendFrames.map { it.peakOffsetX })
        val trendY = regression(trendFrames.map { it.captureIndex.toDouble() }, trendFrames.map { it.peakOffsetY })
        val trendSlope = hypot(trendX.slope, trendY.slope)
        val trendR2 = max(trendX.rSquared, trendY.rSquared)
        val topThreeEnergy = frames.sortedByDescending { it.weightedTrailEnergy }.take(3)
            .sumOf { it.weightedTrailEnergy } / energySum
        val peakSearchBoundaryFraction = frames.count { it.peakAtSearchBoundary }.toDouble() / frames.size
        val outputResiduals = support.map { index -> encodedLocalLuminanceResidual(baseline, index) }
        val outputMedian = ReplayDefectMath.median(outputResiduals)
        val outputMaximum = outputResiduals.maxOrNull() ?: 0.0
        val reconstructionError = reconstructionError(baseline, reference, effectiveSkyAlpha, support, frames)
        val referenceFallback = support.map { index ->
            1.0 - effectiveSkyAlpha.alphaAt(index % baseline.width, index / baseline.width)
        }.averageOrZero() * 100.0
        val transientMaxSupport = max(3, ceil(frames.size * ReplayStage1CThresholds.TRANSIENT_MAX_SUPPORT_FRACTION).toInt())
        val classification = when {
            supporting.size >= ReplayStage1CThresholds.CAMERA_MIN_SUPPORT &&
                supportFraction >= ReplayStage1CThresholds.CAMERA_SUPPORT_FRACTION &&
                cameraRms <= ReplayStage1CThresholds.CAMERA_POSITION_RMS -> ReplayTrailProvenanceClass.CAMERA_FIXED_DEFECT
            nearPredictedFraction >= ReplayStage1CThresholds.SKY_SUPPORT_FRACTION -> ReplayTrailProvenanceClass.SKY_ALIGNED_SIGNAL
            supportFraction >= ReplayStage1CThresholds.TREND_SUPPORT_FRACTION &&
                trendSlope >= ReplayStage1CThresholds.TREND_MIN_SLOPE &&
                trendR2 >= ReplayStage1CThresholds.TREND_MIN_R_SQUARED -> ReplayTrailProvenanceClass.REGISTRATION_RESIDUAL
            supporting.size <= transientMaxSupport &&
                topThreeEnergy >= ReplayStage1CThresholds.TRANSIENT_TOP_THREE_ENERGY_FRACTION -> ReplayTrailProvenanceClass.TRANSIENT_FRAME_OUTLIER
            supportFraction <= ReplayStage1CThresholds.RESAMPLING_MAX_SUPPORT_FRACTION &&
                outputMaximum >= ReplayStage1CThresholds.RESAMPLING_MIN_OUTPUT_RESIDUAL &&
                reconstructionError <= ReplayStage1CThresholds.RESAMPLING_MAX_RECONSTRUCTION_ERROR -> ReplayTrailProvenanceClass.RESAMPLING_OR_QUANTIZATION
            else -> ReplayTrailProvenanceClass.UNEXPLAINED
        }
        val confidence = when (classification) {
            ReplayTrailProvenanceClass.CAMERA_FIXED_DEFECT ->
                (supportFraction * (1.0 - cameraRms / ReplayStage1CThresholds.CAMERA_POSITION_RMS)).coerceIn(0.0, 1.0)
            ReplayTrailProvenanceClass.SKY_ALIGNED_SIGNAL ->
                (nearPredictedFraction / ReplayStage1CThresholds.SKY_SUPPORT_FRACTION).coerceIn(0.0, 1.0)
            ReplayTrailProvenanceClass.REGISTRATION_RESIDUAL -> (supportFraction * trendR2).coerceIn(0.0, 1.0)
            ReplayTrailProvenanceClass.TRANSIENT_FRAME_OUTLIER -> topThreeEnergy.coerceIn(0.0, 1.0)
            ReplayTrailProvenanceClass.RESAMPLING_OR_QUANTIZATION ->
                (1.0 - reconstructionError / ReplayStage1CThresholds.RESAMPLING_MAX_RECONSTRUCTION_ERROR).coerceIn(0.0, 1.0)
            ReplayTrailProvenanceClass.UNEXPLAINED -> 0.0
        }
        val heat = DoubleArray(support.size)
        frames.forEach { frame ->
            frame.validSupportIndices.forEach { supportIndex ->
                heat[supportIndex] += frame.supportResidualEnergyCodes[supportIndex] * frame.frameWeight
            }
        }
        return ReplayTrailProvenance(
            annotation,
            support,
            frames,
            classification,
            confidence,
            supporting.map { it.frameId },
            cameraRms,
            trendX.slope,
            trendY.slope,
            trendR2,
            topThreeEnergy,
            peakSearchBoundaryFraction,
            outputMedian,
            outputMaximum,
            reconstructionError,
            referenceFallback,
            heat
        )
    }

    private fun localResidual(source: IntArrayPixelSource, x: Double, y: Double): LocalResidual? {
        val center = sampler.sampleAt(source, x.toFloat(), y.toFloat()) ?: return null
        val ring = ReplayDefectMath.ringOffsets.mapNotNull { (dx, dy) ->
            sampler.sampleAt(source, (x + dx).toFloat(), (y + dy).toFloat())
        }
        if (ring.size != ReplayDefectMath.ringOffsets.size) return null
        val redMedian = medianChannel(ring) { it.red }
        val greenMedian = medianChannel(ring) { it.green }
        val blueMedian = medianChannel(ring) { it.blue }
        val red = (center.red - redMedian) * 255.0
        val green = (center.green - greenMedian) * 255.0
        val blue = (center.blue - blueMedian) * 255.0
        val positives = listOf(max(0.0, red), max(0.0, green), max(0.0, blue)).sortedDescending()
        return LocalResidual(
            luminanceCodes = 0.2126 * red + 0.7152 * green + 0.0722 * blue,
            chromaCodes = positives[0] - positives[1]
        )
    }

    private fun medianChannel(samples: List<SampledSrgb>, channel: (SampledSrgb) -> Float): Double =
        ReplayDefectMath.median(samples.map { channel(it).toDouble() })

    private fun rasterizeSupport(annotation: ReplayManualTrailAnnotation, width: Int, height: Int): IntArray {
        val result = linkedSetOf<Int>()
        annotation.centerline.zipWithNext().forEach { (first, second) ->
            val left = floor(min(first.x, second.x) - ReplayStage1CThresholds.SUPPORT_RADIUS).toInt().coerceAtLeast(0)
            val right = ceil(max(first.x, second.x) + ReplayStage1CThresholds.SUPPORT_RADIUS).toInt().coerceAtMost(width - 1)
            val top = floor(min(first.y, second.y) - ReplayStage1CThresholds.SUPPORT_RADIUS).toInt().coerceAtLeast(0)
            val bottom = ceil(max(first.y, second.y) + ReplayStage1CThresholds.SUPPORT_RADIUS).toInt().coerceAtMost(height - 1)
            for (y in top..bottom) for (x in left..right) {
                if (distanceToSegment(ReplayPoint(x.toDouble(), y.toDouble()), first, second) <= ReplayStage1CThresholds.SUPPORT_RADIUS) {
                    result += y * width + x
                }
            }
        }
        require(result.isNotEmpty()) { "Empty Stage 1C support for ${annotation.id}" }
        return result.toIntArray()
    }

    private fun distanceToSegment(point: ReplayPoint, first: ReplayPoint, second: ReplayPoint): Double {
        val vx = second.x - first.x
        val vy = second.y - first.y
        val denominator = vx * vx + vy * vy
        if (denominator <= 1e-12) return hypot(point.x - first.x, point.y - first.y)
        val t = (((point.x - first.x) * vx + (point.y - first.y) * vy) / denominator).coerceIn(0.0, 1.0)
        return hypot(point.x - first.x - t * vx, point.y - first.y - t * vy)
    }

    private fun reconstructionError(
        baseline: ArgbPixelImage,
        reference: ArgbPixelImage,
        alpha: AlphaMask,
        support: IntArray,
        frames: List<ReplayTrailFrameEvidence>
    ): Double {
        var error = 0.0
        var channels = 0
        support.forEachIndexed { supportIndex, outputIndex ->
            var weight = 0.0
            var red = 0.0
            var green = 0.0
            var blue = 0.0
            frames.forEach { frame ->
                val sampleRed = frame.sampledLinearRgb[supportIndex * 3]
                if (!sampleRed.isFinite()) return@forEach
                weight += frame.frameWeight
                red += sampleRed * frame.frameWeight
                green += frame.sampledLinearRgb[supportIndex * 3 + 1] * frame.frameWeight
                blue += frame.sampledLinearRgb[supportIndex * 3 + 2] * frame.frameWeight
            }
            if (weight <= 0.0) return@forEachIndexed
            val stacked = intArrayOf(encode(red / weight), encode(green / weight), encode(blue / weight))
            val referenceColor = reference.pixels[outputIndex]
            val effective = alpha.alphaAt(outputIndex % baseline.width, outputIndex / baseline.width).toDouble()
            val reconstructed = IntArray(3) { channel ->
                if (effective <= 0.0001) referenceColor ushr (16 - channel * 8) and 0xFF
                else if (effective >= 0.9999) stacked[channel]
                else {
                    val referenceCode = referenceColor ushr (16 - channel * 8) and 0xFF
                    val linear = SrgbTransfer.srgbToLinear(stacked[channel] / 255f) * effective +
                        SrgbTransfer.srgbToLinear(referenceCode / 255f) * (1.0 - effective)
                    encode(linear)
                }
            }
            val actual = baseline.pixels[outputIndex]
            reconstructed.forEachIndexed { channel, value ->
                val actualCode = actual ushr (16 - channel * 8) and 0xFF
                error += abs(value - actualCode)
                channels++
            }
        }
        return error / channels.coerceAtLeast(1)
    }

    private fun encode(linear: Double): Int =
        (SrgbTransfer.linearToSrgb(linear.toFloat()) * 255f).roundToInt().coerceIn(0, 255)

    private fun encodedLocalLuminanceResidual(image: ArgbPixelImage, index: Int): Double {
        val x = index % image.width
        val y = index / image.width
        if (x !in 2 until image.width - 2 || y !in 2 until image.height - 2) return 0.0
        val ring = ReplayDefectMath.ringOffsets.map { (dx, dy) -> encodedLuminance(image.pixels[(y + dy) * image.width + x + dx]) }
        return encodedLuminance(image.pixels[index]) - ReplayDefectMath.median(ring)
    }

    private fun encodedLuminance(color: Int): Double =
        0.2126 * (color ushr 16 and 0xFF) + 0.7152 * (color ushr 8 and 0xFF) + 0.0722 * (color and 0xFF)

    private fun positionRms(points: List<ReplayPoint>): Double {
        if (points.isEmpty()) return Double.POSITIVE_INFINITY
        val centerX = points.map { it.x }.average()
        val centerY = points.map { it.y }.average()
        return sqrt(points.sumOf { (it.x - centerX).pow(2) + (it.y - centerY).pow(2) } / points.size)
    }

    private fun regression(x: List<Double>, y: List<Double>): Regression {
        if (x.size < 2 || x.size != y.size) return Regression(0.0, 0.0)
        val meanX = x.average()
        val meanY = y.average()
        val denominator = x.sumOf { (it - meanX).pow(2) }
        if (denominator <= 1e-12) return Regression(0.0, 0.0)
        val slope = x.indices.sumOf { (x[it] - meanX) * (y[it] - meanY) } / denominator
        val intercept = meanY - slope * meanX
        val total = y.sumOf { (it - meanY).pow(2) }
        val residual = y.indices.sumOf { (y[it] - (intercept + slope * x[it])).pow(2) }
        return Regression(slope, if (total <= 1e-12) 0.0 else (1.0 - residual / total).coerceIn(0.0, 1.0))
    }

    private fun manifest(): String {
        val values = sortedMapOf(
            "cameraPositionRms" to ReplayStage1CThresholds.CAMERA_POSITION_RMS.toString(),
            "cameraMinSupport" to ReplayStage1CThresholds.CAMERA_MIN_SUPPORT.toString(),
            "cameraSupportFraction" to ReplayStage1CThresholds.CAMERA_SUPPORT_FRACTION.toString(),
            "peakSearchRadius" to ReplayStage1CThresholds.PEAK_SEARCH_RADIUS.toString(),
            "resamplingMaxReconstructionError" to ReplayStage1CThresholds.RESAMPLING_MAX_RECONSTRUCTION_ERROR.toString(),
            "resamplingMaxSupportFraction" to ReplayStage1CThresholds.RESAMPLING_MAX_SUPPORT_FRACTION.toString(),
            "resamplingMinOutputResidual" to ReplayStage1CThresholds.RESAMPLING_MIN_OUTPUT_RESIDUAL.toString(),
            "signalResidualCodes" to ReplayStage1CThresholds.SIGNAL_RESIDUAL_CODES.toString(),
            "skyNearPredictedRadius" to ReplayStage1CThresholds.SKY_NEAR_PREDICTED_RADIUS.toString(),
            "skySupportFraction" to ReplayStage1CThresholds.SKY_SUPPORT_FRACTION.toString(),
            "sourceCropSize" to ReplayStage1CThresholds.SOURCE_CROP_SIZE.toString(),
            "supportRadius" to ReplayStage1CThresholds.SUPPORT_RADIUS.toString(),
            "transientMaxSupportFraction" to ReplayStage1CThresholds.TRANSIENT_MAX_SUPPORT_FRACTION.toString(),
            "transientTopThreeEnergyFraction" to ReplayStage1CThresholds.TRANSIENT_TOP_THREE_ENERGY_FRACTION.toString(),
            "trendMinRSquared" to ReplayStage1CThresholds.TREND_MIN_R_SQUARED.toString(),
            "trendMinSlope" to ReplayStage1CThresholds.TREND_MIN_SLOPE.toString(),
            "trendSupportFraction" to ReplayStage1CThresholds.TREND_SUPPORT_FRACTION.toString(),
            "version" to ReplayStage1CThresholds.VERSION
        )
        return values.entries.joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") {
            "  \"${it.key}\":\"${it.value}\""
        }
    }

    private fun writeOutputs(bundle: ReplayStage1CBundle, baseline: ArgbPixelImage, outputRoot: Path) {
        Files.writeString(outputRoot.resolve("stage1c-threshold-manifest.json"), bundle.thresholdManifest)
        Files.writeString(outputRoot.resolve("stage1c-provenance-report.tsv"), summaryTable(bundle))
        Files.writeString(outputRoot.resolve("stage1c-frame-evidence.tsv"), frameTable(bundle))
        Files.writeString(outputRoot.resolve("stage1c-provenance-report.txt"), textReport(bundle))
        bundle.trails.forEach { trail ->
            val root = outputRoot.resolve(trail.annotation.id)
            Files.createDirectories(root)
            writeImage(root.resolve("source-frame-montage.png"), montage(trail.frames))
            writeImage(root.resolve("contribution-heatmap.png"), contributionHeatmap(trail, baseline.width))
            writePlot(root.resolve("frame-index-vs-peak-offset.png"), trail.frames, "Peak offset (px)") {
                hypot(it.peakOffsetX, it.peakOffsetY)
            }
            writePlot(root.resolve("frame-index-vs-trail-energy.png"), trail.frames, "Trail energy contribution (%)") {
                it.signalEnergyContributionPercent
            }
        }
    }

    private fun summaryTable(bundle: ReplayStage1CBundle): String = buildString {
        appendLine("trailId\tclassification\tconfidence\tclassificationCaveat\tsupportingFrames\tcameraPositionRms\ttrendXPerFrame\ttrendYPerFrame\ttrendRSquared\ttopThreeEnergyFraction\tpeakSearchBoundaryFraction\toutputMedianLumaResidual\toutputMaxLumaResidual\treconstructionErrorCodes\treferenceFallbackPercent")
        bundle.trails.forEach { trail ->
            appendLine(listOf(
                trail.annotation.id, trail.classification, trail.confidence,
                classificationCaveat(trail),
                trail.supportingFrameIds.joinToString("|"), trail.cameraPositionRms,
                trail.offsetTrendXPerFrame, trail.offsetTrendYPerFrame, trail.offsetTrendRSquared,
                trail.topThreeEnergyFraction, trail.peakSearchBoundaryFraction,
                trail.outputMedianLuminanceResidualCodes,
                trail.outputMaximumLuminanceResidualCodes, trail.meanAbsoluteReconstructionErrorCodes,
                trail.referenceFallbackPercent
            ).joinToString("\t"))
        }
    }

    private fun frameTable(bundle: ReplayStage1CBundle): String = buildString {
        appendLine("trailId\tframeId\tcaptureIndex\tframeWeight\tactualCleanContributionPercent\tsignalEnergyContributionPercent\tpredictedCameraX\tpredictedCameraY\tmeasuredCameraX\tmeasuredCameraY\tmeasuredOutputX\tmeasuredOutputY\tpeakOffsetX\tpeakOffsetY\tpeakAtSearchBoundary\tpeakLumaResidual\tpeakChromaResidual\tmeanSupportLumaResidual\tmeanSupportChromaResidual\tsupportsSignal")
        bundle.trails.forEach { trail -> trail.frames.forEach { frame ->
            appendLine(listOf(
                trail.annotation.id, frame.frameId, frame.captureIndex, frame.frameWeight,
                frame.actualCleanStackContributionPercent, frame.signalEnergyContributionPercent,
                frame.predictedCameraX, frame.predictedCameraY, frame.measuredCameraX, frame.measuredCameraY,
                frame.measuredOutputX, frame.measuredOutputY, frame.peakOffsetX, frame.peakOffsetY,
                frame.peakAtSearchBoundary,
                frame.peakLuminanceResidualCodes, frame.peakChromaResidualCodes,
                frame.meanSupportLuminanceResidualCodes, frame.meanSupportChromaResidualCodes,
                frame.supportsSignal
            ).joinToString("\t"))
        } }
    }

    private fun textReport(bundle: ReplayStage1CBundle): String = buildString {
        appendLine("mode=replay_only_direct_reverse_trail_provenance")
        appendLine("productionModified=false")
        appendLine("registrationModified=false")
        appendLine("integrationModified=false")
        appendLine("stage3Blocked=true")
        appendLine("thresholdManifestHash=${bundle.thresholdManifestHash}")
        bundle.trails.forEach { trail ->
            appendLine("trail=${trail.annotation.id}")
            appendLine("classification=${trail.classification}")
            appendLine("confidence=${trail.confidence}")
            appendLine("classificationCaveat=${classificationCaveat(trail)}")
            appendLine("peakSearchBoundaryFraction=${trail.peakSearchBoundaryFraction}")
            appendLine("supportingFrameIds=${trail.supportingFrameIds.joinToString("|")}")
            appendLine("contributionByFrame=${trail.frames.joinToString("|") { "${it.frameId}:${it.signalEnergyContributionPercent}" }}")
            appendLine("actualStackContributionByFrame=${trail.frames.joinToString("|") { "${it.frameId}:${it.actualCleanStackContributionPercent}" }}")
            appendLine("peakOffsets=${trail.frames.joinToString("|") { "${it.captureIndex}:${it.peakOffsetX},${it.peakOffsetY}" }}")
            appendLine("cameraCoordinates=${trail.frames.joinToString("|") { "${it.captureIndex}:${it.measuredCameraX},${it.measuredCameraY}" }}")
            appendLine("outputCoordinates=${trail.frames.joinToString("|") { "${it.captureIndex}:${it.measuredOutputX},${it.measuredOutputY}" }}")
            appendLine("temporalTrend=${trail.offsetTrendXPerFrame},${trail.offsetTrendYPerFrame};r2=${trail.offsetTrendRSquared}")
        }
    }

    private fun classificationCaveat(trail: ReplayTrailProvenance): String =
        if (trail.peakSearchBoundaryFraction > 0.0) "peak_search_boundary_hits_present" else "none"

    private fun montage(frames: List<ReplayTrailFrameEvidence>): ArgbPixelImage {
        val columns = 7
        val cellWidth = 64
        val cellHeight = 74
        val rows = ceil(frames.size / columns.toDouble()).toInt().coerceAtLeast(1)
        val image = BufferedImage(columns * cellWidth, rows * cellHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 9)
        frames.forEachIndexed { index, frame ->
            val x = index % columns * cellWidth
            val y = index / columns * cellHeight
            val crop = toBuffered(frame.sourceCrop)
            graphics.drawImage(crop, x + 8, y, 48, 48, null)
            crop.flush()
            graphics.color = if (frame.supportsSignal) Color.GREEN else Color.LIGHT_GRAY
            graphics.drawString("${frame.captureIndex} ${"%.1f".format(frame.signalEnergyContributionPercent)}%", x + 2, y + 60)
            graphics.drawString("d=${"%.1f".format(hypot(frame.peakOffsetX, frame.peakOffsetY))}", x + 2, y + 70)
        }
        graphics.dispose()
        val result = fromBuffered(image)
        image.flush()
        return result
    }

    private fun contributionHeatmap(trail: ReplayTrailProvenance, width: Int): ArgbPixelImage {
        val xs = trail.supportPixels.map { it % width }
        val ys = trail.supportPixels.map { it / width }
        val padding = 8
        val left = xs.minOrNull()!! - padding
        val top = ys.minOrNull()!! - padding
        val outputWidth = xs.maxOrNull()!! - xs.minOrNull()!! + 1 + padding * 2
        val outputHeight = ys.maxOrNull()!! - ys.minOrNull()!! + 1 + padding * 2
        val pixels = IntArray(outputWidth * outputHeight) { 0xFF000000.toInt() }
        val maximum = trail.contributionHeat.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        trail.supportPixels.forEachIndexed { index, outputIndex ->
            val x = outputIndex % width - left
            val y = outputIndex / width - top
            val strength = (trail.contributionHeat[index] / maximum * 255.0).roundToInt().coerceIn(0, 255)
            pixels[y * outputWidth + x] = 0xFF000000.toInt() or (strength shl 16) or ((255 - strength) shl 8)
        }
        return ArgbPixelImage(outputWidth, outputHeight, pixels)
    }

    private fun writePlot(
        path: Path,
        frames: List<ReplayTrailFrameEvidence>,
        label: String,
        value: (ReplayTrailFrameEvidence) -> Double
    ) {
        val width = 640
        val height = 320
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.DARK_GRAY
        graphics.stroke = BasicStroke(1f)
        graphics.drawLine(48, height - 40, width - 20, height - 40)
        graphics.drawLine(48, 20, 48, height - 40)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        graphics.drawString(label, 52, 16)
        val values = frames.map(value)
        val maximum = values.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0
        val minIndex = frames.minOf { it.captureIndex }
        val maxIndex = frames.maxOf { it.captureIndex }.coerceAtLeast(minIndex + 1)
        var previous: Pair<Int, Int>? = null
        frames.forEachIndexed { index, frame ->
            val x = 48 + ((frame.captureIndex - minIndex).toDouble() / (maxIndex - minIndex) * (width - 68)).roundToInt()
            val y = height - 40 - (values[index] / maximum * (height - 70)).roundToInt()
            graphics.color = Color(30, 100, 220)
            previous?.let { graphics.drawLine(it.first, it.second, x, y) }
            graphics.fillOval(x - 3, y - 3, 6, 6)
            previous = x to y
        }
        graphics.dispose()
        check(ImageIO.write(image, "png", path.toFile()))
        image.flush()
    }

    private fun crop(image: ArgbPixelImage, centerX: Int, centerY: Int, size: Int): ArgbPixelImage {
        val actual = min(size, min(image.width, image.height))
        val left = (centerX - actual / 2).coerceIn(0, image.width - actual)
        val top = (centerY - actual / 2).coerceIn(0, image.height - actual)
        val pixels = IntArray(actual * actual)
        for (row in 0 until actual) {
            image.pixels.copyInto(pixels, row * actual, (top + row) * image.width + left, (top + row) * image.width + left + actual)
        }
        return ArgbPixelImage(actual, actual, pixels)
    }

    private fun writeImage(path: Path, image: ArgbPixelImage) {
        Files.createDirectories(path.parent)
        val buffered = toBuffered(image)
        check(ImageIO.write(buffered, "png", path.toFile()))
        buffered.flush()
    }

    private fun toBuffered(image: ArgbPixelImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        }

    private fun fromBuffered(image: BufferedImage): ArgbPixelImage =
        ArgbPixelImage(image.width, image.height, IntArray(image.width * image.height).also {
            image.getRGB(0, 0, image.width, image.height, it, 0, image.width)
        })

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private data class LocalResidual(val luminanceCodes: Double, val chromaCodes: Double)
    private data class Regression(val slope: Double, val rSquared: Double)
}
