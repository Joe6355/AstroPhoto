package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarCentroidDetector
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal object ReplayStage1DThresholds {
    const val VERSION = "replay-trail-morphology/2"
    const val EXPECTED_MANIFEST_SHA256 = "27c4f9de687948c710ad6e934aa88968bac4c9d29dca5404ca9645b67bb169cb"
    const val MANUAL_SUPPORT_RADIUS = 1.5
    const val SEARCH_EXPANSION = 16.0
    const val CROP_EXPANSION = 24.0
    const val BOUNDARY_BAND = 2.0
    const val MAX_BOUNDARY_FRACTION = 0.10
    const val MAX_CONSECUTIVE_BOUNDARY = 3
    const val BACKGROUND_INNER_DISTANCE = 20.0
    const val BACKGROUND_OUTER_DISTANCE = 24.0
    const val NOISE_FLOOR_CODES = 0.50
    const val SEED_MIN_CODES = 1.50
    const val SEED_MIN_SNR = 3.50
    const val GROW_MIN_CODES = 0.75
    const val GROW_MIN_SNR = 2.00
    const val MIN_COMPONENT_AREA = 2
    const val MAX_COMPONENT_AREA = 128
    const val MIN_RELIABLE_SNR = 4.0
    const val MIN_RELIABLE_CONFIDENCE = 0.45
    const val MAX_CENTROID_UNCERTAINTY = 1.0
    const val FIT_RESIDUAL_EPSILON = 1.0 / 65535.0
    const val JACOBIAN_STEP = 0.25
    const val MIN_JACOBIAN_DETERMINANT = 1e-8
    const val MAX_JACOBIAN_CONDITION = 1e4
    const val TRACK_AGREEMENT_DISTANCE = 1.0
    const val MIN_TRACK_AGREEMENT = 0.90
    const val MIN_TRACK_OVERLAP = 3
    const val MIN_TRACK_COVERAGE = 0.60
    const val SKY_STEP_BASE = 3.0
    const val SKY_STEP_PER_GAP = 0.75
    const val SKY_STEP_CAP = 8.0
    const val CAMERA_STEP_BASE = 1.0
    const val CAMERA_STEP_PER_GAP = 0.10
    const val CAMERA_STEP_CAP = 2.0
    const val MISSING_FRAME_PENALTY = 0.35
    const val CONTROL_MIN_CONFIDENCE = 0.60
    const val CONTROL_MAX_ELLIPTICITY = 0.50
    const val CONTROL_MIN_COUNT = 3
    const val CONTROL_NEARBY_DIAGONAL_FRACTION = 0.20
    const val ELONGATED_AXIS_RATIO = 1.50
    const val ELONGATED_CONTROL_FWHM_RATIO = 1.25
    const val ELONGATED_ORIENTATION_DEGREES = 20.0
    const val ELONGATED_FRAME_FRACTION = 0.60
    const val MIN_MORPHOLOGY_CONTROL_FRACTION = 0.50
    const val DRIFT_MIN_RMS = 0.75
    const val DRIFT_MIN_SPAN = 1.50
    const val DRIFT_UNCERTAINTY_RATIO = 2.0
    const val CAMERA_MAX_RMS = 0.75
    const val CAMERA_MAX_SPAN = 1.50
    const val CAMERA_MIN_MODEL_SEPARATION = 1.50
    const val COMMON_MIN_TRAILS = 4
    const val COMMON_INLIER_DISTANCE = 0.75
    const val COMMON_MIN_INLIER_FRACTION = 0.75
    const val COMMON_MIN_EXPLAINED_VARIANCE = 0.60
    const val COMMON_OBJECT_SPECIFIC_MAX_VARIANCE = 0.40
    const val SPATIAL_MIN_ADDITIONAL_VARIANCE = 0.20
    const val SPATIAL_MIN_FIELD_DIFFERENCE = 0.75
    const val REFERENCE_AMPLIFIER_CHANGE = 0.15
    const val REFERENCE_NEGLIGIBLE_CHANGE = 0.05
    const val HALF_MAX_FRACTION = 0.50
}

internal enum class ReplayTrailMorphologyClass {
    INTRA_FRAME_TRAIL,
    INTER_FRAME_MISREGISTRATION,
    MIXED,
    CAMERA_FIXED_DEFECT,
    UNEXPLAINED
}

internal data class ReplayControlStar(val outputX: Double, val outputY: Double)

internal data class ReplayMatrix2(
    val xx: Double,
    val xy: Double,
    val yx: Double,
    val yy: Double
) {
    val determinant: Double get() = xx * yy - xy * yx
    fun isFinite(): Boolean = listOf(xx, xy, yx, yy).all(Double::isFinite)
}

internal data class ReplayMorphology(
    val centroid: ReplayPoint,
    val covariance: ReplayMatrix2,
    val sigmaMajor: Double,
    val sigmaMinor: Double,
    val gaussianEquivalentFwhmMajor: Double,
    val gaussianEquivalentFwhmMinor: Double,
    val ellipticity: Double,
    val orientationRadians: Double?,
    val pcaGeometricExtent: Double,
    val halfMaximumExtent: Double?,
    val componentArea: Int
)

internal data class ReplayMorphologyComponent(
    val id: String,
    val frameId: String,
    val captureIndex: Int,
    val source: ReplayMorphology,
    val output: ReplayMorphology?,
    val peakSignalCodes: Double,
    val localContrastCodes: Double,
    val chromaContrastCodes: Double,
    val componentSnr: Double,
    val fitResidual: Double,
    val confidence: Double,
    val centroidUncertaintySource: ReplayMatrix2,
    val centroidUncertaintyOutput: ReplayMatrix2?,
    val centroidUncertaintyOutputMajor: Double?,
    val jacobian: ReplayMatrix2?,
    val jacobianDeterminant: Double?,
    val jacobianConditionNumber: Double?,
    val boundaryContact: Boolean,
    val reliable: Boolean,
    val pixels: List<ReplayPoint>
)

internal data class ReplayMorphologyFrameEvidence(
    val frame: ReplayProvenanceFrame,
    val sourceSearchCenterline: List<ReplayPoint>,
    val components: List<ReplayMorphologyComponent>,
    val sourceCrop: ArgbPixelImage,
    val cropLeft: Int,
    val cropTop: Int
)

internal data class ReplayTrackerResult(
    val mode: String,
    val observations: Map<String, ReplayMorphologyComponent>,
    val coverage: Double,
    val temporalThirds: Int,
    val agreement: Double,
    val overlapCount: Int,
    val coherent: Boolean,
    val forwardIds: List<String>,
    val backwardIds: List<String>
)

internal data class ReplayControlPsf(
    val frameId: String,
    val outputX: Double,
    val outputY: Double,
    val outputFwhmMajor: Double,
    val confidence: Double
)

internal data class ReplayTrailMorphologyResult(
    val annotation: ReplayManualTrailAnnotation,
    val frames: List<ReplayMorphologyFrameEvidence>,
    val skyTrack: ReplayTrackerResult,
    val cameraTrack: ReplayTrackerResult,
    val classification: ReplayTrailMorphologyClass,
    val confidence: Double,
    val caveats: List<String>,
    val searchInconclusive: Boolean,
    val boundaryFraction: Double,
    val maximumConsecutiveBoundary: Int,
    val sourceElongationSignificant: Boolean?,
    val outputCentroidDriftSignificant: Boolean,
    val outputCentroidRms: Double,
    val outputCentroidSpan: Double,
    val medianOutputCentroidUncertainty: Double,
    val cameraCentroidRms: Double,
    val cameraCentroidSpan: Double,
    val controlPsfAvailableFrames: Int
)

internal data class ReplayCommonModeFrame(
    val captureIndex: Int,
    val trailCount: Int,
    val dx: Double,
    val dy: Double,
    val inlierFraction: Double,
    val translationExplainedVariance: Double,
    val affineAdditionalVariance: Double,
    val affineFieldDifference: Double,
    val status: String
)

internal data class ReplayReferenceWeightComparison(
    val trailId: String,
    val baselineEnergy: Double,
    val normalWeightEnergy: Double,
    val relativeEnergyChange: Double,
    val baselineContrast: Double,
    val normalWeightContrast: Double,
    val relativeContrastChange: Double,
    val status: String
)

internal data class ReplayStage1DBundle(
    val trails: List<ReplayTrailMorphologyResult>,
    val commonMode: List<ReplayCommonModeFrame>,
    val referenceWeightComparisons: List<ReplayReferenceWeightComparison>,
    val referenceOriginalWeight: Double,
    val referenceNormalWeight: Double,
    val manifest: String,
    val manifestHash: String
)

internal class ReplayTrailMorphologyDiagnosticRunner {
    fun run(
        baseline: ArgbPixelImage,
        normalWeightReferenceCandidate: ArgbPixelImage?,
        annotations: List<ReplayManualTrailAnnotation>,
        frames: List<ReplayProvenanceFrame>,
        controlStars: List<ReplayControlStar>,
        referenceFrameId: String,
        outputRoot: Path,
        imageLoader: (ReplayProvenanceFrame) -> ArgbPixelImage
    ): ReplayStage1DBundle {
        require(frames.isNotEmpty())
        require(annotations.isNotEmpty())
        val ordered = frames.sortedBy { it.captureIndex }
        val evidence = annotations.associateWith { mutableListOf<ReplayMorphologyFrameEvidence>() }
        val controls = mutableMapOf<String, List<ReplayControlPsf>>()
        ordered.forEach { frame ->
            val image = imageLoader(frame)
            require(image.width == baseline.width && image.height == baseline.height)
            val source = IntArrayPixelSource(image.width, image.height, image.pixels)
            controls[frame.id] = measureControls(frame, source, controlStars, image.width, image.height)
            annotations.forEach { annotation ->
                checkNotNull(evidence[annotation]) += analyzeFrame(annotation, frame, image, source)
            }
        }
        val results = annotations.map { annotation ->
            classifyTrail(
                annotation,
                checkNotNull(evidence[annotation]),
                checkNotNull(controls),
                baseline.width,
                baseline.height
            )
        }
        val common = commonMode(results, baseline.width, baseline.height)
        val comparisons = normalWeightReferenceCandidate?.let { candidate ->
            require(candidate.width == baseline.width && candidate.height == baseline.height)
            annotations.map { compareReferenceWeight(it, baseline, candidate) }
        } ?: emptyList()
        val manifest = manifest()
        val manifestHash = sha256(manifest.toByteArray(StandardCharsets.UTF_8))
        check(manifestHash == ReplayStage1DThresholds.EXPECTED_MANIFEST_SHA256) {
            "Stage 1D frozen threshold manifest changed: $manifestHash"
        }
        val referenceOriginalWeight = ordered.single { it.id == referenceFrameId }.normalizedWeight.toDouble()
        val referenceNormalWeight = median(ordered.filter { it.id != referenceFrameId }.map { it.normalizedWeight.toDouble() })
        val bundle = ReplayStage1DBundle(
            results,
            common,
            comparisons,
            referenceOriginalWeight,
            referenceNormalWeight,
            manifest,
            manifestHash
        )
        writeOutputs(bundle, baseline, normalWeightReferenceCandidate, referenceFrameId, outputRoot)
        return bundle
    }

    private fun analyzeFrame(
        annotation: ReplayManualTrailAnnotation,
        frame: ReplayProvenanceFrame,
        image: ArgbPixelImage,
        source: IntArrayPixelSource
    ): ReplayMorphologyFrameEvidence {
        val sourceLine = annotation.centerline.map {
            ReplayTransformSemantics.outputToSource(frame.transform, it.x, it.y)
        }
        val scale = outputToSourceScale(frame.transform)
        val supportRadius = ReplayStage1DThresholds.MANUAL_SUPPORT_RADIUS * scale
        val searchRadius = supportRadius + ReplayStage1DThresholds.SEARCH_EXPANSION
        val cropRadius = supportRadius + ReplayStage1DThresholds.CROP_EXPANSION
        val left = floor(sourceLine.minOf { it.x } - cropRadius).toInt().coerceAtLeast(0)
        val top = floor(sourceLine.minOf { it.y } - cropRadius).toInt().coerceAtLeast(0)
        val right = ceil(sourceLine.maxOf { it.x } + cropRadius).toInt().coerceAtMost(image.width - 1)
        val bottom = ceil(sourceLine.maxOf { it.y } + cropRadius).toInt().coerceAtMost(image.height - 1)
        val backgroundPixels = mutableListOf<PixelSignal>()
        for (y in top..bottom) for (x in left..right) {
            val distance = distanceToPolyline(ReplayPoint(x.toDouble(), y.toDouble()), sourceLine)
            if (distance in ReplayStage1DThresholds.BACKGROUND_INNER_DISTANCE..ReplayStage1DThresholds.BACKGROUND_OUTER_DISTANCE) {
                backgroundPixels += pixelSignal(image.pixels[y * image.width + x])
            }
        }
        val background = background(backgroundPixels)
        val eligible = HashMap<Int, ScalarSample>()
        for (y in top..bottom) for (x in left..right) {
            val distance = distanceToPolyline(ReplayPoint(x.toDouble(), y.toDouble()), sourceLine)
            if (distance > searchRadius) continue
            val pixel = pixelSignal(image.pixels[y * image.width + x])
            val luminance = pixel.luminance - background.luminance
            val channelResiduals = doubleArrayOf(
                pixel.red - background.red,
                pixel.green - background.green,
                pixel.blue - background.blue
            )
            val chroma = channelResiduals.maxOrNull()!! - channelResiduals.minOrNull()!!
            val signal = max(0.0, luminance) + 0.25 * max(0.0, chroma)
            eligible[y * image.width + x] = ScalarSample(x, y, signal, luminance, chroma, distance)
        }
        val seedThreshold = max(
            ReplayStage1DThresholds.SEED_MIN_CODES,
            ReplayStage1DThresholds.SEED_MIN_SNR * background.noise
        )
        val growThreshold = max(
            ReplayStage1DThresholds.GROW_MIN_CODES,
            ReplayStage1DThresholds.GROW_MIN_SNR * background.noise
        )
        val grow = eligible.filterValues { it.signal >= growThreshold }
        val visited = mutableSetOf<Int>()
        val rawComponents = mutableListOf<List<ScalarSample>>()
        grow.keys.sorted().forEach { start ->
            if (!visited.add(start)) return@forEach
            val queue = ArrayDeque<Int>()
            queue += start
            val component = mutableListOf<ScalarSample>()
            var hasSeed = false
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val sample = checkNotNull(grow[index])
                component += sample
                if (sample.signal >= seedThreshold) hasSeed = true
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = sample.x + dx
                    val ny = sample.y + dy
                    val ni = ny * image.width + nx
                    if (grow.containsKey(ni) && visited.add(ni)) queue += ni
                }
            }
            if (hasSeed && component.size in ReplayStage1DThresholds.MIN_COMPONENT_AREA..ReplayStage1DThresholds.MAX_COMPONENT_AREA) {
                rawComponents += component
            }
        }
        val components = rawComponents.mapIndexedNotNull { index, pixels ->
            fitComponent(
                id = "${frame.captureIndex.toString().padStart(3, '0')}-$index",
                frame = frame,
                pixels = pixels,
                eligible = eligible,
                backgroundNoise = background.noise,
                searchRadius = searchRadius
            )
        }.sortedWith(compareBy<ReplayMorphologyComponent> { it.source.centroid.y }.thenBy { it.source.centroid.x })
        return ReplayMorphologyFrameEvidence(
            frame,
            sourceLine,
            components,
            crop(image, left, top, right - left + 1, bottom - top + 1),
            left,
            top
        )
    }

    private fun fitComponent(
        id: String,
        frame: ReplayProvenanceFrame,
        pixels: List<ScalarSample>,
        eligible: Map<Int, ScalarSample>,
        backgroundNoise: Double,
        searchRadius: Double
    ): ReplayMorphologyComponent? {
        val weights = pixels.map { max(it.signal, ReplayStage1DThresholds.FIT_RESIDUAL_EPSILON) }
        val total = weights.sum().coerceAtLeast(ReplayStage1DThresholds.FIT_RESIDUAL_EPSILON)
        val cx = pixels.indices.sumOf { pixels[it].x * weights[it] } / total
        val cy = pixels.indices.sumOf { pixels[it].y * weights[it] } / total
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        pixels.indices.forEach { index ->
            val dx = pixels[index].x - cx
            val dy = pixels[index].y - cy
            xx += weights[index] * dx * dx
            yy += weights[index] * dy * dy
            xy += weights[index] * dx * dy
        }
        xx = max(xx / total, 0.04)
        yy = max(yy / total, 0.04)
        xy /= total
        val covariance = ReplayMatrix2(xx, xy, xy, yy)
        val sourceMorphology = morphology(
            ReplayPoint(cx, cy), covariance, pixels.map { ReplayPoint(it.x.toDouble(), it.y.toDouble()) },
            pixels.map { it.signal }
        ) ?: return null
        val peak = pixels.maxOf { it.signal }
        val halo = linkedSetOf<Int>()
        pixels.forEach { pixel ->
            for (dy in -1..1) for (dx in -1..1) halo += (pixel.y + dy) * 100000 + pixel.x + dx
        }
        val inverse = inverse(covariance)
        var residualSquared = 0.0
        var residualCount = 0
        halo.forEach { packed ->
            val y = floor(packed / 100000.0).toInt()
            val x = packed - y * 100000
            val observed = eligible.values.firstOrNull { it.x == x && it.y == y }?.signal ?: 0.0
            val dx = x - cx
            val dy = y - cy
            val radius = inverse?.let { dx * (it.xx * dx + it.xy * dy) + dy * (it.yx * dx + it.yy * dy) } ?: 100.0
            val fitted = peak * kotlin.math.exp(-0.5 * radius.coerceAtLeast(0.0))
            residualSquared += (observed - fitted).pow(2)
            residualCount++
        }
        val denominator = max(
            peak,
            max(3.0 * backgroundNoise, ReplayStage1DThresholds.FIT_RESIDUAL_EPSILON)
        )
        val fitResidual = sqrt(residualSquared / residualCount.coerceAtLeast(1)) / denominator
        val snr = peak / backgroundNoise.coerceAtLeast(ReplayStage1DThresholds.NOISE_FLOOR_CODES)
        val snrScore = ((snr - 2.0) / 8.0).coerceIn(0.0, 1.0)
        val areaScore = (pixels.size / 6.0).coerceIn(0.0, 1.0)
        val residualScore = (1.0 - fitResidual).coerceIn(0.0, 1.0)
        val confidence = 0.45 * snrScore + 0.25 * areaScore + 0.30 * residualScore
        val uncertaintyFactor = (1.0 + fitResidual).pow(2) / snr.coerceAtLeast(1.0).pow(2)
        val uncertaintySource = ReplayMatrix2(
            covariance.xx * uncertaintyFactor + 0.05.pow(2),
            covariance.xy * uncertaintyFactor,
            covariance.yx * uncertaintyFactor,
            covariance.yy * uncertaintyFactor + 0.05.pow(2)
        )
        val jacobian = jacobian(frame.transform, cx, cy)
        val determinant = jacobian?.determinant
        val condition = jacobian?.let(::conditionNumber)
        val stableJacobian = jacobian != null && jacobian.isFinite() && determinant != null && condition != null &&
            abs(determinant) >= ReplayStage1DThresholds.MIN_JACOBIAN_DETERMINANT &&
            condition <= ReplayStage1DThresholds.MAX_JACOBIAN_CONDITION
        val outputCovariance = if (stableJacobian) transformCovariance(checkNotNull(jacobian), covariance) else null
        val outputCentroid = if (stableJacobian) ReplayTransformSemantics.sourceToOutput(frame.transform, cx, cy) else null
        val outputPoints = if (stableJacobian) pixels.map {
            ReplayTransformSemantics.sourceToOutput(frame.transform, it.x.toDouble(), it.y.toDouble())
        } else emptyList()
        val outputMorphology = if (outputCovariance != null && outputCentroid != null) morphology(
            outputCentroid, outputCovariance, outputPoints, pixels.map { it.signal }
        ) else null
        val uncertaintyOutput = if (stableJacobian) transformCovariance(checkNotNull(jacobian), uncertaintySource) else null
        val uncertaintyMajor = uncertaintyOutput?.let { sqrt(eigenvalues(it).first.coerceAtLeast(0.0)) }
        val boundary = pixels.any { searchRadius - it.distanceToSupport <= ReplayStage1DThresholds.BOUNDARY_BAND }
        val reliable = snr >= ReplayStage1DThresholds.MIN_RELIABLE_SNR &&
            confidence >= ReplayStage1DThresholds.MIN_RELIABLE_CONFIDENCE &&
            uncertaintyMajor != null && uncertaintyMajor <= ReplayStage1DThresholds.MAX_CENTROID_UNCERTAINTY &&
            outputMorphology != null
        return ReplayMorphologyComponent(
            id,
            frame.id,
            frame.captureIndex,
            sourceMorphology,
            outputMorphology,
            peak,
            pixels.map { it.luminance }.average(),
            pixels.maxOf { it.chroma },
            snr,
            fitResidual,
            confidence,
            uncertaintySource,
            uncertaintyOutput,
            uncertaintyMajor,
            jacobian,
            determinant,
            condition,
            boundary,
            reliable,
            pixels.map { ReplayPoint(it.x.toDouble(), it.y.toDouble()) }
        )
    }

    private fun classifyTrail(
        annotation: ReplayManualTrailAnnotation,
        frames: List<ReplayMorphologyFrameEvidence>,
        controls: Map<String, List<ReplayControlPsf>>,
        width: Int,
        height: Int
    ): ReplayTrailMorphologyResult {
        val skyForward = track(frames, TrackMode.SKY, reverse = false)
        val skyBackward = track(frames, TrackMode.SKY, reverse = true)
        val cameraForward = track(frames, TrackMode.CAMERA, reverse = false)
        val cameraBackward = track(frames, TrackMode.CAMERA, reverse = true)
        val sky = combineTracks("sky", frames, skyForward, skyBackward)
        val camera = combineTracks("camera", frames, cameraForward, cameraBackward)
        val selected = sky.observations.values.sortedBy { it.captureIndex }
        val boundaryFraction = selected.count { it.boundaryContact }.toDouble() / selected.size.coerceAtLeast(1)
        val consecutiveBoundary = maximumConsecutive(selected.map { it.boundaryContact })
        val searchInconclusive = boundaryFraction > ReplayStage1DThresholds.MAX_BOUNDARY_FRACTION ||
            consecutiveBoundary >= ReplayStage1DThresholds.MAX_CONSECUTIVE_BOUNDARY
        val outputPoints = selected.mapNotNull { it.output?.centroid }
        val drift = positionEvidence(outputPoints)
        val medianUncertainty = median(selected.mapNotNull { it.centroidUncertaintyOutputMajor })
        val driftSignificant = outputPoints.size >= ReplayStage1DThresholds.MIN_TRACK_OVERLAP &&
            drift.rms >= ReplayStage1DThresholds.DRIFT_MIN_RMS &&
            drift.span >= ReplayStage1DThresholds.DRIFT_MIN_SPAN &&
            drift.rms >= ReplayStage1DThresholds.DRIFT_UNCERTAINTY_RATIO * medianUncertainty
        val cameraSelected = camera.observations.values.sortedBy { it.captureIndex }
        val cameraEvidence = positionEvidence(cameraSelected.map { it.source.centroid })
        val annotationCenter = ReplayPoint(
            annotation.centerline.map { it.x }.average(),
            annotation.centerline.map { it.y }.average()
        )
        val cameraModelSeparation = positionEvidence(frames.map {
            ReplayTransformSemantics.outputToSource(it.frame.transform, annotationCenter.x, annotationCenter.y)
        }).span
        val cameraCoherent = camera.coherent &&
            cameraEvidence.rms <= ReplayStage1DThresholds.CAMERA_MAX_RMS &&
            cameraEvidence.span <= ReplayStage1DThresholds.CAMERA_MAX_SPAN &&
            cameraModelSeparation >= ReplayStage1DThresholds.CAMERA_MIN_MODEL_SEPARATION
        val manualOrientation = annotation.centerline.takeIf { it.size >= 2 }?.let {
            kotlin.math.atan2(it.last().y - it.first().y, it.last().x - it.first().x)
        }
        val morphologyChecks = selected.mapNotNull { component ->
            val output = component.output ?: return@mapNotNull null
            val frameControls = selectControls(
                controls[component.frameId].orEmpty(),
                output.centroid,
                width,
                height
            ) ?: return@mapNotNull null
            val controlFwhm = median(frameControls.map { it.outputFwhmMajor })
            val axisRatio = output.sigmaMajor / output.sigmaMinor.coerceAtLeast(1e-9)
            val orientationDifference = manualOrientation?.let {
                ReplayDefectMath.orientationDifferenceDegrees(output.orientationRadians, it)
            }
            axisRatio >= ReplayStage1DThresholds.ELONGATED_AXIS_RATIO &&
                output.gaussianEquivalentFwhmMajor >=
                ReplayStage1DThresholds.ELONGATED_CONTROL_FWHM_RATIO * controlFwhm &&
                orientationDifference != null &&
                orientationDifference <= ReplayStage1DThresholds.ELONGATED_ORIENTATION_DEGREES
        }
        val minimumMorphologyFrames = max(
            ReplayStage1DThresholds.MIN_TRACK_OVERLAP,
            ceil(selected.size * ReplayStage1DThresholds.MIN_MORPHOLOGY_CONTROL_FRACTION).toInt()
        )
        val elongationSignificant = if (morphologyChecks.size >= minimumMorphologyFrames) {
            morphologyChecks.count { it }.toDouble() / morphologyChecks.size >=
                ReplayStage1DThresholds.ELONGATED_FRAME_FRACTION
        } else null
        val caveats = mutableListOf<String>()
        if (searchInconclusive) caveats += "search_inconclusive"
        if (!sky.coherent) caveats += "sky_tracker_inconclusive"
        if (elongationSignificant == null) caveats += "intra_morphology_unavailable"
        if (cameraCoherent) caveats += "independent_camera_track"
        val classification = when {
            searchInconclusive || !sky.coherent && !cameraCoherent -> ReplayTrailMorphologyClass.UNEXPLAINED
            cameraCoherent -> ReplayTrailMorphologyClass.CAMERA_FIXED_DEFECT
            elongationSignificant == true && driftSignificant -> ReplayTrailMorphologyClass.MIXED
            elongationSignificant == true -> ReplayTrailMorphologyClass.INTRA_FRAME_TRAIL
            driftSignificant -> ReplayTrailMorphologyClass.INTER_FRAME_MISREGISTRATION
            else -> ReplayTrailMorphologyClass.UNEXPLAINED
        }
        val classificationMargin = when (classification) {
            ReplayTrailMorphologyClass.INTRA_FRAME_TRAIL,
            ReplayTrailMorphologyClass.MIXED -> morphologyChecks.count { it }.toDouble() / morphologyChecks.size.coerceAtLeast(1)
            ReplayTrailMorphologyClass.INTER_FRAME_MISREGISTRATION -> min(
                drift.rms / ReplayStage1DThresholds.DRIFT_MIN_RMS,
                drift.span / ReplayStage1DThresholds.DRIFT_MIN_SPAN
            ).coerceIn(0.0, 1.0)
            ReplayTrailMorphologyClass.CAMERA_FIXED_DEFECT ->
                (1.0 - cameraEvidence.rms / ReplayStage1DThresholds.CAMERA_MAX_RMS).coerceIn(0.0, 1.0)
            ReplayTrailMorphologyClass.UNEXPLAINED -> 0.0
        }
        val confidence = if (searchInconclusive) 0.0 else min(
            classificationMargin,
            if (classification == ReplayTrailMorphologyClass.CAMERA_FIXED_DEFECT) camera.agreement else sky.agreement
        ) * selected.map { it.confidence }.averageOrZero()
        return ReplayTrailMorphologyResult(
            annotation,
            frames,
            sky,
            camera,
            classification,
            confidence.coerceIn(0.0, 1.0),
            caveats,
            searchInconclusive,
            boundaryFraction,
            consecutiveBoundary,
            elongationSignificant,
            driftSignificant,
            drift.rms,
            drift.span,
            medianUncertainty,
            cameraEvidence.rms,
            cameraEvidence.span,
            morphologyChecks.size
        )
    }

    private fun track(
        frames: List<ReplayMorphologyFrameEvidence>,
        mode: TrackMode,
        reverse: Boolean
    ): List<ReplayMorphologyComponent> {
        val ordered = if (reverse) frames.asReversed() else frames
        val nodes = ordered.flatMapIndexed { orderIndex, frame ->
            frame.components.filter { it.reliable }.map { TrackNode(orderIndex, frame, it) }
        }
        if (nodes.isEmpty()) return emptyList()
        val states = mutableMapOf<String, TrackState>()
        nodes.forEach { node ->
            val emission = emission(node)
            var best = TrackState(emission, listOf(node.component))
            states.values.forEach { prior ->
                val previous = prior.path.last()
                val previousOrder = ordered.indexOfFirst { it.frame.id == previous.frameId }
                if (previousOrder >= node.orderIndex) return@forEach
                val gap = abs(node.component.captureIndex - previous.captureIndex).coerceAtLeast(1)
                val distance = when (mode) {
                    TrackMode.SKY -> {
                        val a = previous.output?.centroid ?: return@forEach
                        val b = node.component.output?.centroid ?: return@forEach
                        hypot(a.x - b.x, a.y - b.y)
                    }
                    TrackMode.CAMERA -> hypot(
                        previous.source.centroid.x - node.component.source.centroid.x,
                        previous.source.centroid.y - node.component.source.centroid.y
                    )
                }
                val maximum = when (mode) {
                    TrackMode.SKY -> min(
                        ReplayStage1DThresholds.SKY_STEP_CAP,
                        ReplayStage1DThresholds.SKY_STEP_BASE + ReplayStage1DThresholds.SKY_STEP_PER_GAP * (gap - 1)
                    )
                    TrackMode.CAMERA -> min(
                        ReplayStage1DThresholds.CAMERA_STEP_CAP,
                        ReplayStage1DThresholds.CAMERA_STEP_BASE + ReplayStage1DThresholds.CAMERA_STEP_PER_GAP * (gap - 1)
                    )
                }
                if (distance > maximum) return@forEach
                val shapeA = previous.output?.sigmaMajor ?: previous.source.sigmaMajor
                val shapeB = node.component.output?.sigmaMajor ?: node.component.source.sigmaMajor
                val shapePenalty = 0.20 * abs(ln(shapeA.coerceAtLeast(0.1) / shapeB.coerceAtLeast(0.1)))
                val skipped = (node.orderIndex - previousOrder - 1).coerceAtLeast(0)
                val score = prior.score + emission - distance / maximum - shapePenalty -
                    skipped * ReplayStage1DThresholds.MISSING_FRAME_PENALTY
                val candidate = TrackState(score, prior.path + node.component)
                if (better(candidate, best)) best = candidate
            }
            states[node.component.id] = best
        }
        val best = states.values.maxWithOrNull(compareBy<TrackState> { it.score }.thenBy { pathKey(it.path) })
            ?: return emptyList()
        return best.path.sortedBy { it.captureIndex }
    }

    private fun combineTracks(
        mode: String,
        frames: List<ReplayMorphologyFrameEvidence>,
        forward: List<ReplayMorphologyComponent>,
        backward: List<ReplayMorphologyComponent>
    ): ReplayTrackerResult {
        val forwardByFrame = forward.associateBy { it.frameId }
        val backwardByFrame = backward.associateBy { it.frameId }
        val commonFrames = forwardByFrame.keys.intersect(backwardByFrame.keys)
        val agreed = commonFrames.count { frameId ->
            val first = checkNotNull(forwardByFrame[frameId])
            val second = checkNotNull(backwardByFrame[frameId])
            first.id == second.id || run {
                val a = first.output?.centroid
                val b = second.output?.centroid
                a != null && b != null && hypot(a.x - b.x, a.y - b.y) <= ReplayStage1DThresholds.TRACK_AGREEMENT_DISTANCE
            }
        }
        val agreement = agreed.toDouble() / commonFrames.size.coerceAtLeast(1)
        val selected = forwardByFrame.filterKeys { frameId ->
            frameId in commonFrames && run {
                val first = checkNotNull(forwardByFrame[frameId])
                val second = checkNotNull(backwardByFrame[frameId])
                first.id == second.id || run {
                    val a = first.output?.centroid
                    val b = second.output?.centroid
                    a != null && b != null && hypot(a.x - b.x, a.y - b.y) <= ReplayStage1DThresholds.TRACK_AGREEMENT_DISTANCE
                }
            }
        }
        val coverage = selected.size.toDouble() / frames.size
        val thirds = temporalThirdCount(selected.values.map { it.captureIndex }, frames.map { it.frame.captureIndex })
        val coherent = commonFrames.size >= ReplayStage1DThresholds.MIN_TRACK_OVERLAP &&
            agreement >= ReplayStage1DThresholds.MIN_TRACK_AGREEMENT &&
            coverage >= ReplayStage1DThresholds.MIN_TRACK_COVERAGE && thirds == 3
        return ReplayTrackerResult(
            mode,
            selected,
            coverage,
            thirds,
            agreement,
            commonFrames.size,
            coherent,
            forward.map { it.id },
            backward.map { it.id }
        )
    }

    private fun commonMode(
        results: List<ReplayTrailMorphologyResult>,
        width: Int,
        height: Int
    ): List<ReplayCommonModeFrame> {
        val centers = results.associate { result ->
            result.annotation.id to ReplayPoint(
                median(result.skyTrack.observations.values.mapNotNull { it.output?.centroid?.x }),
                median(result.skyTrack.observations.values.mapNotNull { it.output?.centroid?.y })
            )
        }
        val captureIndices = results.flatMap { it.frames }.map { it.frame.captureIndex }.distinct().sorted()
        return captureIndices.mapNotNull { captureIndex ->
            val observations = results.mapNotNull { result ->
                val component = result.skyTrack.observations.values.firstOrNull { it.captureIndex == captureIndex }
                    ?: return@mapNotNull null
                val point = component.output?.centroid ?: return@mapNotNull null
                val center = checkNotNull(centers[result.annotation.id])
                SpatialResidual(point.x, point.y, point.x - center.x, point.y - center.y)
            }
            if (observations.size < ReplayStage1DThresholds.COMMON_MIN_TRAILS) return@mapNotNull null
            val dx = median(observations.map { it.dx })
            val dy = median(observations.map { it.dy })
            val total = observations.sumOf { it.dx * it.dx + it.dy * it.dy }.coerceAtLeast(1e-12)
            val translationRemaining = observations.sumOf { (it.dx - dx).pow(2) + (it.dy - dy).pow(2) }
            val translationExplained = (1.0 - translationRemaining / total).coerceIn(0.0, 1.0)
            val inlierFraction = observations.count { hypot(it.dx - dx, it.dy - dy) <= ReplayStage1DThresholds.COMMON_INLIER_DISTANCE }
                .toDouble() / observations.size
            val affine = affineResidual(observations, width, height)
            val additional = if (translationRemaining <= 1e-12) 0.0 else
                ((translationRemaining - affine.remaining) / translationRemaining).coerceIn(0.0, 1.0)
            val status = when {
                inlierFraction >= ReplayStage1DThresholds.COMMON_MIN_INLIER_FRACTION &&
                    translationExplained >= ReplayStage1DThresholds.COMMON_MIN_EXPLAINED_VARIANCE -> "global_translation_residual"
                additional >= ReplayStage1DThresholds.SPATIAL_MIN_ADDITIONAL_VARIANCE &&
                    affine.fieldDifference >= ReplayStage1DThresholds.SPATIAL_MIN_FIELD_DIFFERENCE -> "spatial_model_residual"
                translationExplained < ReplayStage1DThresholds.COMMON_OBJECT_SPECIFIC_MAX_VARIANCE -> "object_specific_residual"
                else -> "inconclusive"
            }
            ReplayCommonModeFrame(
                captureIndex, observations.size, dx, dy, inlierFraction, translationExplained,
                additional, affine.fieldDifference, status
            )
        }
    }

    private fun compareReferenceWeight(
        annotation: ReplayManualTrailAnnotation,
        baseline: ArgbPixelImage,
        candidate: ArgbPixelImage
    ): ReplayReferenceWeightComparison {
        val first = outputTrailMetric(annotation, baseline)
        val second = outputTrailMetric(annotation, candidate)
        val energyChange = relativeChange(first.energy, second.energy)
        val contrastChange = relativeChange(first.contrast, second.contrast)
        val reduction = max(-energyChange, -contrastChange)
        val status = when {
            reduction >= ReplayStage1DThresholds.REFERENCE_AMPLIFIER_CHANGE -> "reference_009_amplifies"
            reduction < ReplayStage1DThresholds.REFERENCE_NEGLIGIBLE_CHANGE -> "reference_009_not_material"
            else -> "reference_009_mild_amplifier"
        }
        return ReplayReferenceWeightComparison(
            annotation.id,
            first.energy,
            second.energy,
            energyChange,
            first.contrast,
            second.contrast,
            contrastChange,
            status
        )
    }

    private fun outputTrailMetric(annotation: ReplayManualTrailAnnotation, image: ArgbPixelImage): OutputTrailMetric {
        val line = annotation.centerline
        val left = floor(line.minOf { it.x } - 10).toInt().coerceAtLeast(0)
        val right = ceil(line.maxOf { it.x } + 10).toInt().coerceAtMost(image.width - 1)
        val top = floor(line.minOf { it.y } - 10).toInt().coerceAtLeast(0)
        val bottom = ceil(line.maxOf { it.y } + 10).toInt().coerceAtMost(image.height - 1)
        val ring = mutableListOf<Double>()
        for (y in top..bottom) for (x in left..right) {
            val distance = distanceToPolyline(ReplayPoint(x.toDouble(), y.toDouble()), line)
            if (distance in 6.0..10.0) ring += pixelSignal(image.pixels[y * image.width + x]).luminance
        }
        val background = median(ring)
        val signals = mutableListOf<Double>()
        for (y in top..bottom) for (x in left..right) {
            if (distanceToPolyline(ReplayPoint(x.toDouble(), y.toDouble()), line) <= ReplayStage1DThresholds.MANUAL_SUPPORT_RADIUS) {
                signals += max(0.0, pixelSignal(image.pixels[y * image.width + x]).luminance - background)
            }
        }
        return OutputTrailMetric(signals.sum(), signals.maxOrNull() ?: 0.0)
    }

    private fun measureControls(
        frame: ReplayProvenanceFrame,
        source: IntArrayPixelSource,
        controls: List<ReplayControlStar>,
        width: Int,
        height: Int
    ): List<ReplayControlPsf> {
        val detector = FullResolutionStarCentroidDetector()
        return controls.mapNotNull { control ->
            val predicted = ReplayTransformSemantics.outputToSource(
                frame.transform, control.outputX, control.outputY
            )
            val detection = detector.detect(source, predicted.x.toFloat(), predicted.y.toFloat(), 3f)
            val value = detection.measurement ?: return@mapNotNull null
            if (value.confidence < ReplayStage1DThresholds.CONTROL_MIN_CONFIDENCE ||
                value.ellipticity > ReplayStage1DThresholds.CONTROL_MAX_ELLIPTICITY
            ) return@mapNotNull null
            val output = ReplayTransformSemantics.sourceToOutput(frame.transform, value.x.toDouble(), value.y.toDouble())
            val scale = sourceToOutputScale(frame.transform)
            ReplayControlPsf(
                frame.id,
                output.x,
                output.y,
                max(value.fwhmX, value.fwhmY) * scale,
                value.confidence.toDouble()
            )
        }
    }

    private fun selectControls(
        controls: List<ReplayControlPsf>,
        point: ReplayPoint,
        width: Int,
        height: Int
    ): List<ReplayControlPsf>? {
        val sectorX = (point.x / width * 3).toInt().coerceIn(0, 2)
        val sectorY = (point.y / height * 3).toInt().coerceIn(0, 2)
        val sameSector = controls.filter {
            (it.outputX / width * 3).toInt().coerceIn(0, 2) == sectorX &&
                (it.outputY / height * 3).toInt().coerceIn(0, 2) == sectorY
        }
        if (sameSector.size >= ReplayStage1DThresholds.CONTROL_MIN_COUNT) return sameSector
        val radius = hypot(width.toDouble(), height.toDouble()) *
            ReplayStage1DThresholds.CONTROL_NEARBY_DIAGONAL_FRACTION
        val nearby = controls.filter { hypot(it.outputX - point.x, it.outputY - point.y) <= radius }
        return nearby.takeIf { it.size >= ReplayStage1DThresholds.CONTROL_MIN_COUNT }
    }

    private fun morphology(
        centroid: ReplayPoint,
        covariance: ReplayMatrix2,
        points: List<ReplayPoint>,
        signals: List<Double>
    ): ReplayMorphology? {
        if (!covariance.isFinite()) return null
        val (majorVariance, minorVariance) = eigenvalues(covariance)
        if (majorVariance <= 0.0 || minorVariance < 0.0) return null
        val sigmaMajor = sqrt(majorVariance)
        val sigmaMinor = sqrt(minorVariance)
        val orientation = orientation(covariance)
        val axis = orientation?.let { ReplayPoint(cos(it), sin(it)) }
        val projections = axis?.let { a ->
            points.map { (it.x - centroid.x) * a.x + (it.y - centroid.y) * a.y }
        }.orEmpty()
        val extent = if (projections.size >= 2) projections.maxOrNull()!! - projections.minOrNull()!! else 0.0
        val peak = signals.maxOrNull() ?: 0.0
        val halfProjections = if (axis != null && peak > 0.0) points.indices.filter {
            signals[it] >= peak * ReplayStage1DThresholds.HALF_MAX_FRACTION
        }.map { index ->
            (points[index].x - centroid.x) * axis.x + (points[index].y - centroid.y) * axis.y
        } else emptyList()
        val halfExtent = if (halfProjections.distinct().size >= 2) {
            halfProjections.maxOrNull()!! - halfProjections.minOrNull()!!
        } else null
        return ReplayMorphology(
            centroid,
            covariance,
            sigmaMajor,
            sigmaMinor,
            2.35482 * sigmaMajor,
            2.35482 * sigmaMinor,
            if (sigmaMajor <= 1e-9) 1.0 else (1.0 - sigmaMinor / sigmaMajor).coerceIn(0.0, 1.0),
            orientation,
            extent,
            halfExtent,
            points.size
        )
    }

    private fun jacobian(transform: ReferenceToSourceTransform, x: Double, y: Double): ReplayMatrix2? {
        val h = ReplayStage1DThresholds.JACOBIAN_STEP
        val xPlus = ReplayTransformSemantics.sourceToOutput(transform, x + h, y)
        val xMinus = ReplayTransformSemantics.sourceToOutput(transform, x - h, y)
        val yPlus = ReplayTransformSemantics.sourceToOutput(transform, x, y + h)
        val yMinus = ReplayTransformSemantics.sourceToOutput(transform, x, y - h)
        val value = ReplayMatrix2(
            (xPlus.x - xMinus.x) / (2.0 * h),
            (yPlus.x - yMinus.x) / (2.0 * h),
            (xPlus.y - xMinus.y) / (2.0 * h),
            (yPlus.y - yMinus.y) / (2.0 * h)
        )
        return value.takeIf { it.isFinite() }
    }

    private fun transformCovariance(j: ReplayMatrix2, c: ReplayMatrix2): ReplayMatrix2 {
        val a00 = j.xx * c.xx + j.xy * c.yx
        val a01 = j.xx * c.xy + j.xy * c.yy
        val a10 = j.yx * c.xx + j.yy * c.yx
        val a11 = j.yx * c.xy + j.yy * c.yy
        return ReplayMatrix2(
            a00 * j.xx + a01 * j.xy,
            a00 * j.yx + a01 * j.yy,
            a10 * j.xx + a11 * j.xy,
            a10 * j.yx + a11 * j.yy
        )
    }

    private fun conditionNumber(matrix: ReplayMatrix2): Double {
        val a = matrix.xx * matrix.xx + matrix.yx * matrix.yx
        val b = matrix.xx * matrix.xy + matrix.yx * matrix.yy
        val d = matrix.xy * matrix.xy + matrix.yy * matrix.yy
        val trace = a + d
        val root = sqrt(max(0.0, (a - d).pow(2) + 4.0 * b * b))
        val maximum = max(0.0, (trace + root) * 0.5)
        val minimum = max(0.0, (trace - root) * 0.5)
        return if (minimum <= 1e-18) Double.POSITIVE_INFINITY else sqrt(maximum / minimum)
    }

    private fun eigenvalues(matrix: ReplayMatrix2): Pair<Double, Double> {
        val symmetricXy = (matrix.xy + matrix.yx) * 0.5
        val trace = matrix.xx + matrix.yy
        val root = sqrt(max(0.0, (matrix.xx - matrix.yy).pow(2) + 4.0 * symmetricXy * symmetricXy))
        return max(0.0, (trace + root) * 0.5) to max(0.0, (trace - root) * 0.5)
    }

    private fun orientation(matrix: ReplayMatrix2): Double? {
        val xy = (matrix.xy + matrix.yx) * 0.5
        val (major, _) = eigenvalues(matrix)
        if (major <= 1e-9) return null
        return 0.5 * kotlin.math.atan2(2.0 * xy, matrix.xx - matrix.yy)
    }

    private fun inverse(matrix: ReplayMatrix2): ReplayMatrix2? {
        val determinant = matrix.determinant
        if (abs(determinant) < 1e-12) return null
        return ReplayMatrix2(
            matrix.yy / determinant,
            -matrix.xy / determinant,
            -matrix.yx / determinant,
            matrix.xx / determinant
        )
    }

    private fun affineResidual(observations: List<SpatialResidual>, width: Int, height: Int): AffineEvidence {
        if (observations.size < ReplayStage1DThresholds.COMMON_MIN_TRAILS) return AffineEvidence(Double.POSITIVE_INFINITY, 0.0)
        fun fit(values: List<SpatialResidual>, selector: (SpatialResidual) -> Double): DoubleArray? {
            val matrix = Array(3) { DoubleArray(3) }
            val vector = DoubleArray(3)
            values.forEach { value ->
                val row = doubleArrayOf(1.0, value.x / width, value.y / height)
                for (i in 0..2) {
                    vector[i] += row[i] * selector(value)
                    for (j in 0..2) matrix[i][j] += row[i] * row[j]
                }
            }
            return solve3(matrix, vector)
        }
        fun predict(coefficients: DoubleArray, x: Double, y: Double): Double =
            coefficients[0] + coefficients[1] * x / width + coefficients[2] * y / height
        val corners = listOf(0.0 to 0.0, width.toDouble() to 0.0, 0.0 to height.toDouble(), width.toDouble() to height.toDouble())
        var remaining = 0.0
        val fieldDifferences = mutableListOf<Double>()
        observations.indices.forEach { heldOutIndex ->
            val training = observations.filterIndexed { index, _ -> index != heldOutIndex }
            val fitX = fit(training) { it.dx } ?: return AffineEvidence(Double.POSITIVE_INFINITY, 0.0)
            val fitY = fit(training) { it.dy } ?: return AffineEvidence(Double.POSITIVE_INFINITY, 0.0)
            val heldOut = observations[heldOutIndex]
            remaining += (heldOut.dx - predict(fitX, heldOut.x, heldOut.y)).pow(2) +
                (heldOut.dy - predict(fitY, heldOut.x, heldOut.y)).pow(2)
            val predictions = corners.map { (x, y) -> ReplayPoint(predict(fitX, x, y), predict(fitY, x, y)) }
            fieldDifferences += predictions.indices.maxOf { first -> predictions.indices.maxOf { second ->
                hypot(predictions[first].x - predictions[second].x, predictions[first].y - predictions[second].y)
            } }
        }
        return AffineEvidence(remaining, median(fieldDifferences))
    }

    private fun solve3(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
        val augmented = Array(3) { row -> DoubleArray(4) { column ->
            if (column < 3) matrix[row][column] else vector[row]
        } }
        for (column in 0..2) {
            val pivot = (column..2).maxByOrNull { abs(augmented[it][column]) } ?: return null
            if (abs(augmented[pivot][column]) < 1e-10) return null
            val temporary = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = temporary
            val divisor = augmented[column][column]
            for (index in column..3) augmented[column][index] /= divisor
            for (row in 0..2) if (row != column) {
                val factor = augmented[row][column]
                for (index in column..3) augmented[row][index] -= factor * augmented[column][index]
            }
        }
        return DoubleArray(3) { augmented[it][3] }
    }

    private fun positionEvidence(points: List<ReplayPoint>): PositionEvidence {
        if (points.isEmpty()) return PositionEvidence(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val center = ReplayPoint(median(points.map { it.x }), median(points.map { it.y }))
        val rms = sqrt(points.sumOf { (it.x - center.x).pow(2) + (it.y - center.y).pow(2) } / points.size)
        if (points.size < 2) return PositionEvidence(rms, 0.0)
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        points.forEach {
            val dx = it.x - center.x
            val dy = it.y - center.y
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        val covariance = ReplayMatrix2(xx / points.size, xy / points.size, xy / points.size, yy / points.size)
        val angle = orientation(covariance) ?: 0.0
        val axisX = cos(angle)
        val axisY = sin(angle)
        val projections = points.map { (it.x - center.x) * axisX + (it.y - center.y) * axisY }.sorted()
        val low = percentile(projections, 0.10)
        val high = percentile(projections, 0.90)
        return PositionEvidence(rms, high - low)
    }

    private fun temporalThirdCount(indices: List<Int>, allIndices: List<Int>): Int {
        if (indices.isEmpty() || allIndices.isEmpty()) return 0
        val minimum = allIndices.minOrNull()!!
        val maximum = allIndices.maxOrNull()!!
        val span = (maximum - minimum + 1).coerceAtLeast(1)
        return indices.map { ((it - minimum) * 3 / span).coerceIn(0, 2) }.distinct().size
    }

    private fun maximumConsecutive(values: List<Boolean>): Int {
        var maximum = 0
        var current = 0
        values.forEach {
            current = if (it) current + 1 else 0
            maximum = max(maximum, current)
        }
        return maximum
    }

    private fun emission(node: TrackNode): Double {
        val sourceDistance = distanceToPolyline(node.component.source.centroid, node.frame.sourceSearchCenterline)
        val normalizedDistance = (sourceDistance / ReplayStage1DThresholds.SEARCH_EXPANSION).coerceIn(0.0, 1.5)
        return 2.0 * node.component.confidence - 0.5 * normalizedDistance
    }

    private fun better(candidate: TrackState, current: TrackState): Boolean =
        candidate.score > current.score + 1e-12 ||
            abs(candidate.score - current.score) <= 1e-12 && pathKey(candidate.path) < pathKey(current.path)

    private fun pathKey(path: List<ReplayMorphologyComponent>): String = path.joinToString("|") { it.id }

    private fun background(pixels: List<PixelSignal>): Background {
        require(pixels.isNotEmpty()) { "Stage 1D background ring is empty" }
        val luminances = pixels.map { it.luminance }
        val luminance = median(luminances)
        val noise = max(
            ReplayStage1DThresholds.NOISE_FLOOR_CODES,
            1.4826 * median(luminances.map { abs(it - luminance) })
        )
        return Background(
            median(pixels.map { it.red }),
            median(pixels.map { it.green }),
            median(pixels.map { it.blue }),
            luminance,
            noise
        )
    }

    private fun pixelSignal(argb: Int): PixelSignal {
        val red = (argb ushr 16 and 0xFF).toDouble()
        val green = (argb ushr 8 and 0xFF).toDouble()
        val blue = (argb and 0xFF).toDouble()
        return PixelSignal(red, green, blue, 0.2126 * red + 0.7152 * green + 0.0722 * blue)
    }

    private fun outputToSourceScale(transform: ReferenceToSourceTransform): Double = transform.scale.toDouble()
    private fun sourceToOutputScale(transform: ReferenceToSourceTransform): Double = 1.0 / transform.scale

    private fun distanceToPolyline(point: ReplayPoint, line: List<ReplayPoint>): Double {
        if (line.size == 1) return hypot(point.x - line[0].x, point.y - line[0].y)
        return line.zipWithNext().minOf { (first, second) -> distanceToSegment(point, first, second) }
    }

    private fun distanceToSegment(point: ReplayPoint, first: ReplayPoint, second: ReplayPoint): Double {
        val dx = second.x - first.x
        val dy = second.y - first.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-12) return hypot(point.x - first.x, point.y - first.y)
        val t = (((point.x - first.x) * dx + (point.y - first.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(point.x - (first.x + t * dx), point.y - (first.y + t * dy))
    }

    private fun relativeChange(baseline: Double, candidate: Double): Double =
        if (abs(baseline) <= 1e-12) 0.0 else (candidate - baseline) / baseline

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val position = fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private fun crop(image: ArgbPixelImage, left: Int, top: Int, width: Int, height: Int): ArgbPixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(image.pixels, (top + y) * image.width + left, pixels, y * width, width)
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun manifest(): String {
        val values = sortedMapOf(
            "backgroundInnerDistance" to ReplayStage1DThresholds.BACKGROUND_INNER_DISTANCE,
            "backgroundOuterDistance" to ReplayStage1DThresholds.BACKGROUND_OUTER_DISTANCE,
            "boundaryBand" to ReplayStage1DThresholds.BOUNDARY_BAND,
            "cameraStepBase" to ReplayStage1DThresholds.CAMERA_STEP_BASE,
            "cameraStepCap" to ReplayStage1DThresholds.CAMERA_STEP_CAP,
            "cameraStepPerGap" to ReplayStage1DThresholds.CAMERA_STEP_PER_GAP,
            "cameraMaxRms" to ReplayStage1DThresholds.CAMERA_MAX_RMS,
            "cameraMaxSpan" to ReplayStage1DThresholds.CAMERA_MAX_SPAN,
            "cameraMinModelSeparation" to ReplayStage1DThresholds.CAMERA_MIN_MODEL_SEPARATION,
            "centroidUncertainty" to "C*(1+fitResidual)^2/componentSnr^2+I*0.05^2",
            "commonInlierDistance" to ReplayStage1DThresholds.COMMON_INLIER_DISTANCE,
            "commonMinExplainedVariance" to ReplayStage1DThresholds.COMMON_MIN_EXPLAINED_VARIANCE,
            "commonMinInlierFraction" to ReplayStage1DThresholds.COMMON_MIN_INLIER_FRACTION,
            "commonMinTrails" to ReplayStage1DThresholds.COMMON_MIN_TRAILS,
            "commonObjectSpecificMaxVariance" to ReplayStage1DThresholds.COMMON_OBJECT_SPECIFIC_MAX_VARIANCE,
            "componentConnectivity" to 8,
            "componentMaxArea" to ReplayStage1DThresholds.MAX_COMPONENT_AREA,
            "componentMinArea" to ReplayStage1DThresholds.MIN_COMPONENT_AREA,
            "controlMaxEllipticity" to ReplayStage1DThresholds.CONTROL_MAX_ELLIPTICITY,
            "controlMinConfidence" to ReplayStage1DThresholds.CONTROL_MIN_CONFIDENCE,
            "controlMinCount" to ReplayStage1DThresholds.CONTROL_MIN_COUNT,
            "controlNearbyDiagonalFraction" to ReplayStage1DThresholds.CONTROL_NEARBY_DIAGONAL_FRACTION,
            "cropExpansionSourcePixels" to ReplayStage1DThresholds.CROP_EXPANSION,
            "driftMinRms" to ReplayStage1DThresholds.DRIFT_MIN_RMS,
            "driftMinSpan" to ReplayStage1DThresholds.DRIFT_MIN_SPAN,
            "driftUncertaintyRatio" to ReplayStage1DThresholds.DRIFT_UNCERTAINTY_RATIO,
            "elongatedAxisRatio" to ReplayStage1DThresholds.ELONGATED_AXIS_RATIO,
            "elongatedControlFwhmRatio" to ReplayStage1DThresholds.ELONGATED_CONTROL_FWHM_RATIO,
            "elongatedFrameFraction" to ReplayStage1DThresholds.ELONGATED_FRAME_FRACTION,
            "elongatedOrientationDegrees" to ReplayStage1DThresholds.ELONGATED_ORIENTATION_DEGREES,
            "fitResidual" to "rms(observedSignal-fittedSignal)/max(componentPeak,3*localNoiseSigma,epsilon)",
            "fitResidualEpsilon" to ReplayStage1DThresholds.FIT_RESIDUAL_EPSILON,
            "forwardBackwardAgreementDistance" to ReplayStage1DThresholds.TRACK_AGREEMENT_DISTANCE,
            "forwardBackwardAgreementFraction" to ReplayStage1DThresholds.MIN_TRACK_AGREEMENT,
            "forwardBackwardMinimumOverlap" to ReplayStage1DThresholds.MIN_TRACK_OVERLAP,
            "growMinCodes" to ReplayStage1DThresholds.GROW_MIN_CODES,
            "growMinSnr" to ReplayStage1DThresholds.GROW_MIN_SNR,
            "jacobianEvaluation" to "central_difference_at_fitted_centroid",
            "jacobianStep" to ReplayStage1DThresholds.JACOBIAN_STEP,
            "manualSupportTransform" to "per_accepted_frame_complete_capsule",
            "manualSupportRadius" to ReplayStage1DThresholds.MANUAL_SUPPORT_RADIUS,
            "maxBoundaryFraction" to ReplayStage1DThresholds.MAX_BOUNDARY_FRACTION,
            "maxConsecutiveBoundary" to ReplayStage1DThresholds.MAX_CONSECUTIVE_BOUNDARY,
            "maxJacobianCondition" to ReplayStage1DThresholds.MAX_JACOBIAN_CONDITION,
            "minJacobianDeterminant" to ReplayStage1DThresholds.MIN_JACOBIAN_DETERMINANT,
            "minMorphologyControlFraction" to ReplayStage1DThresholds.MIN_MORPHOLOGY_CONTROL_FRACTION,
            "minReliableComponentConfidence" to ReplayStage1DThresholds.MIN_RELIABLE_CONFIDENCE,
            "minReliableComponentSnr" to ReplayStage1DThresholds.MIN_RELIABLE_SNR,
            "minTrackCoverage" to ReplayStage1DThresholds.MIN_TRACK_COVERAGE,
            "missingFramePenalty" to ReplayStage1DThresholds.MISSING_FRAME_PENALTY,
            "noiseFloorCodes" to ReplayStage1DThresholds.NOISE_FLOOR_CODES,
            "referenceAmplifierChange" to ReplayStage1DThresholds.REFERENCE_AMPLIFIER_CHANGE,
            "referenceNegligibleChange" to ReplayStage1DThresholds.REFERENCE_NEGLIGIBLE_CHANGE,
            "searchExpansionSourcePixels" to ReplayStage1DThresholds.SEARCH_EXPANSION,
            "seedMinCodes" to ReplayStage1DThresholds.SEED_MIN_CODES,
            "seedMinSnr" to ReplayStage1DThresholds.SEED_MIN_SNR,
            "skyStepBase" to ReplayStage1DThresholds.SKY_STEP_BASE,
            "skyStepCap" to ReplayStage1DThresholds.SKY_STEP_CAP,
            "skyStepPerGap" to ReplayStage1DThresholds.SKY_STEP_PER_GAP,
            "spatialCrossTrailValidation" to "leave_one_trail_out_affine",
            "spatialMinAdditionalVariance" to ReplayStage1DThresholds.SPATIAL_MIN_ADDITIONAL_VARIANCE,
            "spatialMinFieldDifference" to ReplayStage1DThresholds.SPATIAL_MIN_FIELD_DIFFERENCE,
            "trackerMissingObservation" to "never_force_unreliable_component",
            "maximumCentroidUncertainty" to ReplayStage1DThresholds.MAX_CENTROID_UNCERTAINTY,
            "version" to ReplayStage1DThresholds.VERSION,
            "widthDefinitions" to "sigma_eigenvalues;fwhm=2.35482*sigma;pca_extent;half_max_extent"
        )
        return values.entries.joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") {
            "  \"${it.key}\":\"${it.value}\""
        }
    }

    private fun writeOutputs(
        bundle: ReplayStage1DBundle,
        baseline: ArgbPixelImage,
        normalWeightCandidate: ArgbPixelImage?,
        referenceFrameId: String,
        root: Path
    ) {
        Files.createDirectories(root)
        Files.writeString(root.resolve("stage1d-threshold-manifest.json"), bundle.manifest)
        Files.writeString(root.resolve("stage1d-summary.tsv"), summaryTable(bundle))
        Files.writeString(root.resolve("stage1d-components.tsv"), componentTable(bundle))
        Files.writeString(root.resolve("stage1d-common-mode.tsv"), commonTable(bundle))
        Files.writeString(root.resolve("stage1d-report.txt"), textReport(bundle, referenceFrameId))
        bundle.trails.forEach { trail ->
            val trailRoot = root.resolve(trail.annotation.id)
            Files.createDirectories(trailRoot)
            writeImage(trailRoot.resolve("source-components-montage.png"), montage(trail))
            writeImage(trailRoot.resolve("centroid-track-source-output.png"), centroidTrack(trail))
            writeSeriesPlot(trailRoot.resolve("widths.png"), trail, "FWHM major/minor") { component ->
                val morphology = component.output
                (morphology?.gaussianEquivalentFwhmMajor ?: 0.0) to
                    (morphology?.gaussianEquivalentFwhmMinor ?: 0.0)
            }
            writeSeriesPlot(trailRoot.resolve("ellipticity.png"), trail, "Ellipticity") { component ->
                (component.output?.ellipticity ?: 0.0) to 0.0
            }
            writeSeriesPlot(trailRoot.resolve("orientation.png"), trail, "Orientation degrees") { component ->
                ((component.output?.orientationRadians ?: 0.0) * 180.0 / PI) to 0.0
            }
            if (normalWeightCandidate != null) {
                writeAbCrops(trailRoot, trail.annotation, baseline, normalWeightCandidate)
            }
        }
        writeCommonPlot(root.resolve("common-mode-residual.png"), bundle.commonMode)
        if (normalWeightCandidate != null) {
            writeImage(root.resolve("reference-009-normal-weight.png"), normalWeightCandidate)
            writeImage(root.resolve("reference-009-difference.png"), differenceImage(baseline, normalWeightCandidate))
        }
    }

    private fun summaryTable(bundle: ReplayStage1DBundle): String = buildString {
        appendLine("trailId\tclassification\tconfidence\tcaveats\tsearchInconclusive\tboundaryFraction\tmaximumConsecutiveBoundary\tskyCoverage\tskyAgreement\tskyTemporalThirds\tcameraCoverage\tcameraAgreement\tcameraTemporalThirds\tsourceElongationSignificant\toutputDriftSignificant\toutputCentroidRms\toutputCentroidSpan\tmedianCentroidUncertainty\tcameraCentroidRms\tcameraCentroidSpan\tcontrolPsfAvailableFrames")
        bundle.trails.forEach { value ->
            appendLine(listOf(
                value.annotation.id, value.classification, value.confidence, value.caveats.joinToString("|"),
                value.searchInconclusive, value.boundaryFraction, value.maximumConsecutiveBoundary,
                value.skyTrack.coverage, value.skyTrack.agreement, value.skyTrack.temporalThirds,
                value.cameraTrack.coverage, value.cameraTrack.agreement, value.cameraTrack.temporalThirds,
                value.sourceElongationSignificant ?: "inconclusive", value.outputCentroidDriftSignificant,
                value.outputCentroidRms, value.outputCentroidSpan, value.medianOutputCentroidUncertainty,
                value.cameraCentroidRms, value.cameraCentroidSpan, value.controlPsfAvailableFrames
            ).joinToString("\t"))
        }
    }

    private fun componentTable(bundle: ReplayStage1DBundle): String = buildString {
        appendLine("trailId\tframeId\tcaptureIndex\tcomponentId\tselectedSky\tselectedCamera\treliable\tboundary\tsourceX\tsourceY\tsourceSigmaMajor\tsourceSigmaMinor\tsourceFwhmMajor\tsourceFwhmMinor\tsourceEllipticity\tsourceOrientation\tsourcePcaExtent\tsourceHalfMaxExtent\toutputX\toutputY\toutputSigmaMajor\toutputSigmaMinor\toutputFwhmMajor\toutputFwhmMinor\toutputEllipticity\toutputOrientation\toutputPcaExtent\toutputHalfMaxExtent\tarea\tlocalContrast\tchromaContrast\tsnr\tfitResidual\tconfidence\tcentroidUncertainty\tjacobianDeterminant\tjacobianCondition")
        bundle.trails.forEach { trail -> trail.frames.forEach { frame -> frame.components.forEach { component ->
            val source = component.source
            val output = component.output
            appendLine(listOf(
                trail.annotation.id, frame.frame.id, frame.frame.captureIndex, component.id,
                trail.skyTrack.observations[frame.frame.id]?.id == component.id,
                trail.cameraTrack.observations[frame.frame.id]?.id == component.id,
                component.reliable, component.boundaryContact,
                source.centroid.x, source.centroid.y, source.sigmaMajor, source.sigmaMinor,
                source.gaussianEquivalentFwhmMajor, source.gaussianEquivalentFwhmMinor,
                source.ellipticity, source.orientationRadians ?: "undefined", source.pcaGeometricExtent,
                source.halfMaximumExtent ?: "undefined",
                output?.centroid?.x ?: "invalid", output?.centroid?.y ?: "invalid",
                output?.sigmaMajor ?: "invalid", output?.sigmaMinor ?: "invalid",
                output?.gaussianEquivalentFwhmMajor ?: "invalid", output?.gaussianEquivalentFwhmMinor ?: "invalid",
                output?.ellipticity ?: "invalid", output?.orientationRadians ?: "undefined",
                output?.pcaGeometricExtent ?: "invalid", output?.halfMaximumExtent ?: "undefined",
                source.componentArea, component.localContrastCodes, component.chromaContrastCodes,
                component.componentSnr, component.fitResidual, component.confidence,
                component.centroidUncertaintyOutputMajor ?: "invalid",
                component.jacobianDeterminant ?: "invalid", component.jacobianConditionNumber ?: "invalid"
            ).joinToString("\t"))
        } } }
    }

    private fun commonTable(bundle: ReplayStage1DBundle): String = buildString {
        appendLine("captureIndex\ttrailCount\tdx\tdy\tinlierFraction\ttranslationExplainedVariance\taffineAdditionalVariance\taffineFieldDifference\tstatus")
        bundle.commonMode.forEach { value ->
            appendLine(listOf(
                value.captureIndex, value.trailCount, value.dx, value.dy, value.inlierFraction,
                value.translationExplainedVariance, value.affineAdditionalVariance,
                value.affineFieldDifference, value.status
            ).joinToString("\t"))
        }
    }

    private fun textReport(bundle: ReplayStage1DBundle, referenceFrameId: String): String = buildString {
        appendLine("mode=replay_only_stage1d_source_morphology")
        appendLine("productionModified=false")
        appendLine("pixelsModified=false")
        appendLine("registrationModified=false")
        appendLine("integrationModified=false")
        appendLine("classificationUsesFrameWeights=false")
        appendLine("stage3Blocked=true")
        appendLine("referenceFrameId=$referenceFrameId")
        appendLine("referenceOriginalWeight=${bundle.referenceOriginalWeight}")
        appendLine("referenceNormalWeight=${bundle.referenceNormalWeight}")
        appendLine("manifestHash=${bundle.manifestHash}")
        bundle.trails.forEach {
            appendLine("trail=${it.annotation.id};classification=${it.classification};confidence=${it.confidence};caveats=${it.caveats.joinToString("|")};boundary=${it.boundaryFraction};driftRms=${it.outputCentroidRms};driftSpan=${it.outputCentroidSpan};sourceElongation=${it.sourceElongationSignificant}")
        }
        bundle.referenceWeightComparisons.forEach {
            appendLine("reference009=${it.trailId};status=${it.status};energyChange=${it.relativeEnergyChange};contrastChange=${it.relativeContrastChange}")
        }
    }

    private fun montage(trail: ReplayTrailMorphologyResult): ArgbPixelImage {
        val cell = 96
        val columns = 7
        val rows = ceil(trail.frames.size / columns.toDouble()).toInt().coerceAtLeast(1)
        val output = BufferedImage(columns * cell, rows * cell, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, output.width, output.height)
        graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 9)
        trail.frames.forEachIndexed { index, frame ->
            val ox = index % columns * cell
            val oy = index / columns * cell
            graphics.drawImage(toBuffered(frame.sourceCrop), ox, oy, cell - 1, cell - 14, null)
            val selected = trail.skyTrack.observations[frame.frame.id]
            if (selected != null) {
                val sx = (selected.source.centroid.x - frame.cropLeft) * (cell - 1) / frame.sourceCrop.width
                val sy = (selected.source.centroid.y - frame.cropTop) * (cell - 14) / frame.sourceCrop.height
                graphics.color = if (selected.boundaryContact) Color.RED else Color.GREEN
                graphics.stroke = BasicStroke(1.5f)
                val originalTransform = graphics.transform
                graphics.translate(ox + sx, oy + sy)
                graphics.rotate(selected.source.orientationRadians ?: 0.0)
                val fittedMajor = selected.source.gaussianEquivalentFwhmMajor * (cell - 1) / frame.sourceCrop.width
                val fittedMinor = selected.source.gaussianEquivalentFwhmMinor * (cell - 14) / frame.sourceCrop.height
                graphics.drawOval(
                    (-fittedMajor * 0.5).roundToInt(),
                    (-fittedMinor * 0.5).roundToInt(),
                    fittedMajor.roundToInt().coerceAtLeast(2),
                    fittedMinor.roundToInt().coerceAtLeast(2)
                )
                graphics.transform = originalTransform
                selected.source.orientationRadians?.let { angle ->
                    val length = selected.source.pcaGeometricExtent * 0.5 * (cell - 1) / frame.sourceCrop.width
                    graphics.drawLine(
                        (ox + sx - cos(angle) * length).roundToInt(),
                        (oy + sy - sin(angle) * length).roundToInt(),
                        (ox + sx + cos(angle) * length).roundToInt(),
                        (oy + sy + sin(angle) * length).roundToInt()
                    )
                }
            }
            graphics.color = Color.YELLOW
            graphics.drawString("${frame.frame.captureIndex} ${selected?.id ?: "missing"}", ox + 2, oy + cell - 3)
        }
        graphics.dispose()
        return fromBuffered(output)
    }

    private fun centroidTrack(trail: ReplayTrailMorphologyResult): ArgbPixelImage {
        val width = 760
        val height = 360
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.color = Color(18, 18, 18)
        graphics.fillRect(0, 0, width, height)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val points = trail.skyTrack.observations.values.sortedBy { it.captureIndex }
        drawTrackPanel(graphics, points.map { it.source.centroid }, 0, 0, width / 2, height, "source")
        drawTrackPanel(graphics, points.mapNotNull { it.output?.centroid }, width / 2, 0, width / 2, height, "output")
        graphics.dispose()
        return fromBuffered(output)
    }

    private fun drawTrackPanel(
        graphics: java.awt.Graphics2D,
        points: List<ReplayPoint>,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        label: String
    ) {
        graphics.color = Color.WHITE
        graphics.drawRect(left + 20, top + 20, width - 40, height - 40)
        graphics.drawString(label, left + 25, top + 35)
        if (points.isEmpty()) return
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        fun px(value: Double) = left + 25 + ((value - minX) / (maxX - minX).coerceAtLeast(1.0) * (width - 50)).roundToInt()
        fun py(value: Double) = top + 25 + ((value - minY) / (maxY - minY).coerceAtLeast(1.0) * (height - 50)).roundToInt()
        graphics.color = Color.CYAN
        points.zipWithNext().forEach { (a, b) -> graphics.drawLine(px(a.x), py(a.y), px(b.x), py(b.y)) }
        points.forEach { graphics.fillOval(px(it.x) - 2, py(it.y) - 2, 5, 5) }
    }

    private fun writeSeriesPlot(
        path: Path,
        trail: ReplayTrailMorphologyResult,
        label: String,
        values: (ReplayMorphologyComponent) -> Pair<Double, Double>
    ) {
        val selected = trail.skyTrack.observations.values.sortedBy { it.captureIndex }
        val width = 760
        val height = 300
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(18, 18, 18)
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.WHITE
        graphics.drawString(label, 20, 20)
        graphics.drawRect(40, 30, width - 60, height - 60)
        if (selected.isNotEmpty()) {
            val pairs = selected.map(values)
            val maximum = pairs.flatMap { listOf(it.first, it.second) }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
            val minFrame = selected.minOf { it.captureIndex }
            val maxFrame = selected.maxOf { it.captureIndex }
            fun x(frame: Int) = 40 + ((frame - minFrame).toDouble() / (maxFrame - minFrame).coerceAtLeast(1) * (width - 60)).roundToInt()
            fun y(value: Double) = height - 30 - (value / maximum * (height - 60)).roundToInt()
            graphics.stroke = BasicStroke(2f)
            listOf(Color.CYAN to { pair: Pair<Double, Double> -> pair.first }, Color.ORANGE to { pair: Pair<Double, Double> -> pair.second })
                .forEach { (color, selector) ->
                    graphics.color = color
                    selected.zip(pairs).zipWithNext().forEach { (first, second) ->
                        graphics.drawLine(x(first.first.captureIndex), y(selector(first.second)), x(second.first.captureIndex), y(selector(second.second)))
                    }
                }
        }
        graphics.dispose()
        writeImage(path, fromBuffered(image))
    }

    private fun writeCommonPlot(path: Path, values: List<ReplayCommonModeFrame>) {
        val width = 760
        val height = 300
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(18, 18, 18)
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.WHITE
        graphics.drawString("Common-mode residual dx/dy", 20, 20)
        graphics.drawRect(40, 30, width - 60, height - 60)
        if (values.isNotEmpty()) {
            val maximum = values.maxOf { max(abs(it.dx), abs(it.dy)) }.coerceAtLeast(1.0)
            val minFrame = values.minOf { it.captureIndex }
            val maxFrame = values.maxOf { it.captureIndex }
            fun x(frame: Int) = 40 + ((frame - minFrame).toDouble() / (maxFrame - minFrame).coerceAtLeast(1) * (width - 60)).roundToInt()
            fun y(value: Double) = height / 2 - (value / maximum * (height / 2 - 35)).roundToInt()
            listOf(Color.CYAN to { value: ReplayCommonModeFrame -> value.dx }, Color.MAGENTA to { value: ReplayCommonModeFrame -> value.dy })
                .forEach { (color, selector) ->
                    graphics.color = color
                    values.zipWithNext().forEach { (a, b) -> graphics.drawLine(x(a.captureIndex), y(selector(a)), x(b.captureIndex), y(selector(b))) }
                }
        }
        graphics.dispose()
        writeImage(path, fromBuffered(image))
    }

    private fun writeAbCrops(
        root: Path,
        annotation: ReplayManualTrailAnnotation,
        baseline: ArgbPixelImage,
        candidate: ArgbPixelImage
    ) {
        val left = floor(annotation.centerline.minOf { it.x } - 24).toInt().coerceAtLeast(0)
        val right = ceil(annotation.centerline.maxOf { it.x } + 24).toInt().coerceAtMost(baseline.width - 1)
        val top = floor(annotation.centerline.minOf { it.y } - 24).toInt().coerceAtLeast(0)
        val bottom = ceil(annotation.centerline.maxOf { it.y } + 24).toInt().coerceAtMost(baseline.height - 1)
        val a = crop(baseline, left, top, right - left + 1, bottom - top + 1)
        val b = crop(candidate, left, top, right - left + 1, bottom - top + 1)
        writeImage(root.resolve("reference-009-ab-baseline.png"), a)
        writeImage(root.resolve("reference-009-ab-normal-weight.png"), b)
        writeImage(root.resolve("reference-009-ab-difference.png"), differenceImage(a, b))
    }

    private fun differenceImage(first: ArgbPixelImage, second: ArgbPixelImage): ArgbPixelImage {
        require(first.width == second.width && first.height == second.height)
        val output = IntArray(first.pixels.size)
        output.indices.forEach { index ->
            val a = first.pixels[index]
            val b = second.pixels[index]
            val red = (128 + ((b ushr 16 and 0xFF) - (a ushr 16 and 0xFF)) * 8).coerceIn(0, 255)
            val green = (128 + ((b ushr 8 and 0xFF) - (a ushr 8 and 0xFF)) * 8).coerceIn(0, 255)
            val blue = (128 + ((b and 0xFF) - (a and 0xFF)) * 8).coerceIn(0, 255)
            output[index] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        return ArgbPixelImage(first.width, first.height, output)
    }

    private fun toBuffered(image: ArgbPixelImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        }

    private fun fromBuffered(image: BufferedImage): ArgbPixelImage = ArgbPixelImage(
        image.width,
        image.height,
        IntArray(image.width * image.height).also { image.getRGB(0, 0, image.width, image.height, it, 0, image.width) }
    )

    private fun writeImage(path: Path, image: ArgbPixelImage) {
        Files.createDirectories(path.parent)
        check(ImageIO.write(toBuffered(image), "png", path.toFile()))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private enum class TrackMode { SKY, CAMERA }
    private data class TrackNode(
        val orderIndex: Int,
        val frame: ReplayMorphologyFrameEvidence,
        val component: ReplayMorphologyComponent
    )
    private data class TrackState(val score: Double, val path: List<ReplayMorphologyComponent>)
    private data class PixelSignal(
        val red: Double,
        val green: Double,
        val blue: Double,
        val luminance: Double
    )
    private data class Background(
        val red: Double,
        val green: Double,
        val blue: Double,
        val luminance: Double,
        val noise: Double
    )
    private data class ScalarSample(
        val x: Int,
        val y: Int,
        val signal: Double,
        val luminance: Double,
        val chroma: Double,
        val distanceToSupport: Double
    )
    private data class PositionEvidence(val rms: Double, val span: Double)
    private data class SpatialResidual(val x: Double, val y: Double, val dx: Double, val dy: Double)
    private data class AffineEvidence(val remaining: Double, val fieldDifference: Double)
    private data class OutputTrailMetric(val energy: Double, val contrast: Double)
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
