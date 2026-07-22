package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayDeque
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

internal object ReplayDefectThresholds {
    const val MANIFEST_VERSION = "replay-camera-defects/1"
    const val SKY_MASK_VERSION = "replay-sky-mask/effective-alpha-098-erode-disk3-v1"
    const val SKY_ALPHA = 0.98f
    const val SKY_EROSION_RADIUS = 3
    const val LOCAL_RADIUS = 2
    const val CHROMA_CENTER_RESIDUAL = 6.0
    const val CHROMA_DOMINANCE = 3.0
    const val LUMINANCE_CENTER_RESIDUAL = 8.0
    const val CONSISTENT_CHANNEL_FRACTION = 0.60
    const val POSITION_RMS_LIMIT = 0.75
    const val SKY_OBSERVATION_RATIO = 0.80
    const val CHROMA_SUPPORT_FRACTION = 0.20
    const val CHROMA_MIN_SUPPORT = 4
    const val LUMINANCE_SUPPORT_FRACTION = 0.40
    const val LUMINANCE_MIN_SUPPORT = 8
    const val STAR_MATCH_FRACTION = 0.50
    const val SKY_TRACK_DISTANCE = 0.75
    const val PSF_COMPONENT_MIN_PIXELS = 3
    const val PSF_COMPONENT_MIN_RESIDUAL = 3.0
    const val PSF_COMPONENT_PEAK_FRACTION = 0.35
    const val PSF_ELLIPTICITY_LIMIT = 0.45
    const val PSF_SYMMETRY_ERROR_LIMIT = 0.30
    const val PSF_RADIAL_VIOLATION_FRACTION = 0.20
    const val COHERENT_NEIGHBOUR_COUNT = 3
    const val FOOTPRINT_MIN_RESIDUAL = 2.0
    const val FOOTPRINT_MIN_FRAMES = 3
    const val FOOTPRINT_RECURRENCE_FRACTION = 0.50
    const val FOOTPRINT_MEDIAN_FRACTION = 0.25
    const val TRAIL_CHROMA_RESIDUAL = 3.0
    const val TRAIL_MIN_AREA = 3
    const val TRAIL_MIN_MAJOR_AXIS = 5.0
    const val TRAIL_MIN_ELONGATION = 2.0
    const val PATH_BUFFER_RADIUS = 1.5
    const val EXPLAINED_MIN_IOU = 0.30
    const val EXPLAINED_MAX_MEDIAN_DISTANCE = 1.5
    const val EXPLAINED_MAX_DISTANCE = 3.0
    const val EXPLAINED_MAX_ORIENTATION_DEGREES = 15.0
    const val EXPLAINED_MIN_COVERED_FRACTION = 0.60
    const val ROUND_TRIP_MAX_ERROR = 0.01
    const val NORMAL_COVERAGE_PERCENT = 0.005
    const val HARD_COVERAGE_PERCENT = 0.02
}

internal data class ReplayDefectFrame(
    val id: String,
    val captureIndex: Int,
    val transform: ReferenceToSourceTransform
)

internal enum class ReplayDefectKind { CHROMA, LUMINANCE }

internal data class ReplayDefectObservation(
    val frameId: String,
    val captureIndex: Int,
    val sourceX: Int,
    val sourceY: Int,
    val centroidX: Double?,
    val centroidY: Double?,
    val redResidual: Double,
    val greenResidual: Double,
    val blueResidual: Double,
    val signalResidual: Double,
    val kind: ReplayDefectKind
)

internal data class ReplayDefectPathPoint(
    val frameId: String,
    val captureIndex: Int,
    val x: Double,
    val y: Double,
    val observed: Boolean
)

internal data class ReplayPcaGeometry(
    val orientationRadians: Double?,
    val majorAxisLength: Double,
    val elongation: Double
)

internal data class ReplayTrailComponent(
    val id: Int,
    val pixels: IntArray,
    val geometry: ReplayPcaGeometry
)

internal data class ReplayTrailCorrespondence(
    val trailId: Int,
    val defectX: Int?,
    val defectY: Int?,
    val intersectionOverUnion: Double,
    val medianDistance: Double,
    val maximumDistance: Double,
    val orientationDifferenceDegrees: Double?,
    val coveredFraction: Double,
    val explained: Boolean,
    val status: String
)

internal data class ReplayManualTrailAnnotation(
    val id: String,
    val centerline: List<ReplayPoint>,
    val note: String
)

internal enum class ReplayManualTrailStatus { EXPLAINED, PARTIALLY_MATCHED, UNMATCHED, UNCERTAIN }

internal data class ReplayDefectComponent(
    val id: Int,
    val cores: List<ReplayConfirmedDefect>,
    val footprintPixels: IntArray
) {
    val paths: List<List<ReplayDefectPathPoint>> get() = cores.map { it.eligiblePath }.filter { it.isNotEmpty() }
}

internal data class ReplayManualTrailCorrespondence(
    val trailId: String,
    val componentId: Int?,
    val intersectionOverUnion: Double,
    val medianDistance: Double,
    val maximumDistance: Double,
    val orientationDifferenceDegrees: Double?,
    val coveredFraction: Double,
    val status: ReplayManualTrailStatus,
    val overlapsConfirmedStar: Boolean,
    val medianChromaResidual: Double,
    val maximumChromaResidual: Double,
    val medianLuminanceResidual: Double,
    val maximumLuminanceResidual: Double
)

internal object ReplayStage1BAnnotations {
    /** Fixed manual annotations for the supplied 1440x1920 urban 30-frame replay. */
    val CURRENT_30_FRAME_1440_X_1920: List<ReplayManualTrailAnnotation> = listOf(
        ReplayManualTrailAnnotation("manual-01", listOf(ReplayPoint(921.0, 368.0), ReplayPoint(935.0, 373.0)), "upper shallow trail"),
        ReplayManualTrailAnnotation("manual-02", listOf(ReplayPoint(931.0, 454.0), ReplayPoint(941.0, 460.0)), "upper faint diagonal trail"),
        ReplayManualTrailAnnotation("manual-03", listOf(ReplayPoint(701.0, 729.0), ReplayPoint(711.0, 719.0)), "central faint diagonal trail"),
        ReplayManualTrailAnnotation("manual-04", listOf(ReplayPoint(856.0, 882.0), ReplayPoint(866.0, 870.0)), "central-right diagonal trail"),
        ReplayManualTrailAnnotation("manual-05", listOf(ReplayPoint(940.0, 1247.0), ReplayPoint(960.0, 1233.0)), "lower-middle diagonal trail"),
        ReplayManualTrailAnnotation("manual-06", listOf(ReplayPoint(1015.0, 1432.0), ReplayPoint(1041.0, 1417.0)), "lower-right diagonal trail")
    )

    fun forCurrentReplay(width: Int, height: Int, frameIds: Set<String>): List<ReplayManualTrailAnnotation> {
        val expected = (1..30).map { index ->
            "AstroSeries_20260714_022705_${index.toString().padStart(3, '0')}.jpg"
        }.toSet()
        return if (width == 1440 && height == 1920 && frameIds == expected) CURRENT_30_FRAME_1440_X_1920
        else emptyList()
    }
}

internal data class ReplayConfirmedDefect(
    val coreX: Int,
    val coreY: Int,
    val kind: ReplayDefectKind,
    val observations: List<ReplayDefectObservation>,
    val eligiblePath: List<ReplayDefectPathPoint>,
    val integerPositionRms: Double,
    val centroidPositionRms: Double?,
    val coreMedianResidual: Double,
    val footprintOffsets: Set<Pair<Int, Int>>,
    val rejectionReason: String?,
    val matchedTrailIds: Set<Int> = emptySet()
) {
    val observedSupport: Int get() = observations.size
}

internal enum class ReplayMapCoverageStatus { NORMAL, MANUAL_REVIEW, HARD_REJECTION }

internal data class ReplayCameraDefectBundle(
    val skyMask: BooleanArray,
    val skyMaskHash: String,
    val thresholdManifest: String,
    val thresholdManifestHash: String,
    val allSupportedCandidates: List<ReplayConfirmedDefect>,
    val confirmedDefects: List<ReplayConfirmedDefect>,
    val rejectedStarLikeCandidates: List<ReplayConfirmedDefect>,
    val trails: List<ReplayTrailComponent>,
    val correspondences: List<ReplayTrailCorrespondence>,
    val proposedMap: BooleanArray,
    val undilatedMap: BooleanArray,
    val coveragePercent: Double,
    val coverageStatus: ReplayMapCoverageStatus,
    val rejectedFrameIds: Set<String>,
    val acceptedFrameIds: Set<String>,
    val defectComponents: List<ReplayDefectComponent>,
    val manualTrails: List<ReplayManualTrailAnnotation>,
    val manualCorrespondences: List<ReplayManualTrailCorrespondence>
) {
    val explainedTrails: List<ReplayTrailCorrespondence> = correspondences.filter { it.explained }
    val unexplainedTrails: List<ReplayTrailCorrespondence> = correspondences.filterNot { it.explained }
}

internal object ReplayTransformSemantics {
    fun outputToSource(transform: ReferenceToSourceTransform, x: Double, y: Double): ReplayPoint {
        val point = transform.mapOutputToSource(x.toFloat(), y.toFloat())
        return ReplayPoint(point.x.toDouble(), point.y.toDouble())
    }

    /** Selected only because the synthetic round-trip contract proves these semantics. */
    fun sourceToOutput(transform: ReferenceToSourceTransform, x: Double, y: Double): ReplayPoint {
        val point = transform.inverse().mapSourceToOutput(x.toFloat(), y.toFloat())
        return ReplayPoint(point.x.toDouble(), point.y.toDouble())
    }

    fun maximumRoundTripError(
        transform: ReferenceToSourceTransform,
        points: List<ReplayPoint>
    ): Double = points.maxOfOrNull { output ->
        val source = outputToSource(transform, output.x, output.y)
        val restored = sourceToOutput(transform, source.x, source.y)
        hypot(restored.x - output.x, restored.y - output.y)
    } ?: 0.0
}

internal data class ReplayPoint(val x: Double, val y: Double)

internal object ReplayDefectMath {
    val ringOffsets: List<Pair<Int, Int>> = buildList {
        for (dy in -2..2) for (dx in -2..2) {
            if (max(abs(dx), abs(dy)) == 2) add(dx to dy)
        }
    }

    fun erodedSkyMask(alpha: AlphaMask): BooleanArray {
        val initial = BooleanArray(alpha.width * alpha.height) { index ->
            alpha.alphaAt(index % alpha.width, index / alpha.width) >= ReplayDefectThresholds.SKY_ALPHA
        }
        return BooleanArray(initial.size) { index ->
            val x = index % alpha.width
            val y = index / alpha.width
            var included = true
            for (dy in -ReplayDefectThresholds.SKY_EROSION_RADIUS..ReplayDefectThresholds.SKY_EROSION_RADIUS) {
                for (dx in -ReplayDefectThresholds.SKY_EROSION_RADIUS..ReplayDefectThresholds.SKY_EROSION_RADIUS) {
                    if (dx * dx + dy * dy > ReplayDefectThresholds.SKY_EROSION_RADIUS * ReplayDefectThresholds.SKY_EROSION_RADIUS) continue
                    val sx = x + dx
                    val sy = y + dy
                    if (sx !in 0 until alpha.width || sy !in 0 until alpha.height || !initial[sy * alpha.width + sx]) {
                        included = false
                        break
                    }
                }
                if (!included) break
            }
            included
        }
    }

    fun hashBits(bits: BooleanArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        bits.forEach { digest.update(if (it) 1.toByte() else 0.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun positionRms(observations: List<Pair<Double, Double>>, centerX: Double, centerY: Double): Double {
        if (observations.isEmpty()) return 0.0
        return sqrt(observations.sumOf { (x, y) ->
            val dx = x - centerX
            val dy = y - centerY
            dx * dx + dy * dy
        } / observations.size)
    }

    fun pca(points: List<ReplayPoint>): ReplayPcaGeometry {
        if (points.size < 3 || points.distinct().size < 3) return ReplayPcaGeometry(null, 0.0, 0.0)
        val meanX = points.map { it.x }.average()
        val meanY = points.map { it.y }.average()
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        points.forEach { point ->
            val dx = point.x - meanX
            val dy = point.y - meanY
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        xx /= points.size
        yy /= points.size
        xy /= points.size
        val trace = xx + yy
        val root = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4.0 * xy * xy))
        val major = (trace + root) * 0.5
        val minor = max(0.0, (trace - root) * 0.5)
        if (major <= 1e-6) return ReplayPcaGeometry(null, 0.0, 0.0)
        val orientation = 0.5 * atan2(2.0 * xy, xx - yy)
        val axisX = cos(orientation)
        val axisY = sin(orientation)
        val projections = points.map { (it.x - meanX) * axisX + (it.y - meanY) * axisY }
        val length = projections.maxOrNull()!! - projections.minOrNull()!!
        val elongation = sqrt(major / max(minor, 1e-9))
        return ReplayPcaGeometry(orientation, length, elongation)
    }

    fun orientationDifferenceDegrees(first: Double?, second: Double?): Double? {
        if (first == null || second == null) return null
        var difference = abs(first - second) * 180.0 / PI % 180.0
        if (difference > 90.0) difference = 180.0 - difference
        return difference
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5
    }

    fun classifyResidual(red: Double, green: Double, blue: Double): ReplayDefectKind? {
        val sorted = listOf(red.coerceAtLeast(0.0), green.coerceAtLeast(0.0), blue.coerceAtLeast(0.0)).sortedDescending()
        if (sorted[0] >= ReplayDefectThresholds.CHROMA_CENTER_RESIDUAL &&
            sorted[0] - sorted[1] >= ReplayDefectThresholds.CHROMA_DOMINANCE
        ) return ReplayDefectKind.CHROMA
        val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        return if (luminance >= ReplayDefectThresholds.LUMINANCE_CENTER_RESIDUAL &&
            minimum > 0.0 && minimum >= ReplayDefectThresholds.CONSISTENT_CHANNEL_FRACTION * maximum
        ) ReplayDefectKind.LUMINANCE else null
    }

    fun coverageStatus(percent: Double): ReplayMapCoverageStatus = when {
        percent > ReplayDefectThresholds.HARD_COVERAGE_PERCENT -> ReplayMapCoverageStatus.HARD_REJECTION
        percent > ReplayDefectThresholds.NORMAL_COVERAGE_PERCENT -> ReplayMapCoverageStatus.MANUAL_REVIEW
        else -> ReplayMapCoverageStatus.NORMAL
    }
}

internal class ReplayCameraDefectDiagnosticRunner {
    fun run(
        baseline: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        frames: List<ReplayDefectFrame>,
        allFrameIds: Set<String>,
        stars: List<FullResolutionStarPatch>,
        outputRoot: Path,
        manualTrails: List<ReplayManualTrailAnnotation> = emptyList(),
        imageLoader: (ReplayDefectFrame) -> ArgbPixelImage
    ): ReplayCameraDefectBundle {
        require(frames.isNotEmpty())
        require(frames.map { it.id }.toSet().size == frames.size)
        require(manualTrails.flatMap { it.centerline }.all { point ->
            point.x.isFinite() && point.y.isFinite() &&
                point.x in 0.0..(baseline.width - 1).toDouble() &&
                point.y in 0.0..(baseline.height - 1).toDouble()
        }) { "Manual Stage 1B trail annotation is outside the clean-stack bounds" }
        val orderedFrames = frames.sortedBy { it.captureIndex }
        val acceptedIds = orderedFrames.map { it.id }.toSet()
        require(acceptedIds.all { it in allFrameIds })
        val rejectedIds = allFrameIds - acceptedIds
        val maximumRoundTripError = orderedFrames.maxOf { frame ->
            ReplayTransformSemantics.maximumRoundTripError(
                frame.transform,
                listOf(
                    ReplayPoint(0.0, 0.0),
                    ReplayPoint((baseline.width - 1).toDouble(), 0.0),
                    ReplayPoint(0.0, (baseline.height - 1).toDouble()),
                    ReplayPoint((baseline.width - 1).toDouble(), (baseline.height - 1).toDouble()),
                    ReplayPoint((baseline.width - 1) * 0.37, (baseline.height - 1) * 0.61)
                )
            )
        }
        require(maximumRoundTripError <= ReplayDefectThresholds.ROUND_TRIP_MAX_ERROR) {
            "Output/source transform round trip failed: $maximumRoundTripError"
        }
        val skyMask = ReplayDefectMath.erodedSkyMask(effectiveSkyAlpha)
        val skyHash = ReplayDefectMath.hashBits(skyMask)
        val manifest = manifest(baseline.width, baseline.height, skyHash)
        val manifestHash = sha256(manifest.toByteArray(StandardCharsets.UTF_8))
        val observations = detectObservations(
            orderedFrames,
            baseline.width,
            baseline.height,
            skyMask,
            imageLoader
        )
        val supported = buildSupportedCandidates(
            observations,
            orderedFrames,
            baseline.width,
            baseline.height,
            skyMask
        )
        val enriched = enrichCandidates(supported, orderedFrames, stars, imageLoader)
        val rejectedStarLike = enriched.filter { it.rejectionReason != null }
        val confirmed = enriched.filter { it.rejectionReason == null }
        val trails = detectTrails(baseline, skyMask)
        val correspondences = correspond(trails, confirmed, baseline.width, baseline.height)
        val matchedIdsByCore = correspondences.filter {
            it.defectX != null &&
                (it.intersectionOverUnion > 0.0 || it.medianDistance <= ReplayDefectThresholds.EXPLAINED_MAX_DISTANCE)
        }.groupBy {
            checkNotNull(it.defectX) to checkNotNull(it.defectY)
        }.mapValues { (_, values) -> values.map { it.trailId }.toSet() }
        val confirmedWithMatches = confirmed.map { defect ->
            defect.copy(matchedTrailIds = matchedIdsByCore[defect.coreX to defect.coreY].orEmpty())
        }
        val undilated = BooleanArray(baseline.pixels.size)
        val proposed = BooleanArray(baseline.pixels.size)
        confirmedWithMatches.forEach { defect ->
            val core = defect.coreY * baseline.width + defect.coreX
            undilated[core] = true
            proposed[core] = true
            defect.footprintOffsets.forEach { (dx, dy) ->
                val x = defect.coreX + dx
                val y = defect.coreY + dy
                if (x in 0 until baseline.width && y in 0 until baseline.height) proposed[y * baseline.width + x] = true
            }
        }
        val coverage = proposed.count { it } * 100.0 / proposed.size
        val defectComponents = consolidateDefectComponents(confirmedWithMatches, baseline.width, baseline.height)
        val manualCorrespondences = correspondManualTrails(
            baseline,
            manualTrails,
            defectComponents,
            stars
        )
        val bundle = ReplayCameraDefectBundle(
            skyMask,
            skyHash,
            manifest,
            manifestHash,
            (confirmedWithMatches + rejectedStarLike).sortedWith(
                compareBy<ReplayConfirmedDefect> { it.coreY }.thenBy { it.coreX }
            ),
            confirmedWithMatches,
            rejectedStarLike,
            trails,
            correspondences,
            proposed,
            undilated,
            coverage,
            ReplayDefectMath.coverageStatus(coverage),
            rejectedIds,
            acceptedIds,
            defectComponents,
            manualTrails,
            manualCorrespondences
        )
        writeOutputs(bundle, baseline, orderedFrames, outputRoot, imageLoader, maximumRoundTripError)
        return bundle
    }

    private fun detectObservations(
        frames: List<ReplayDefectFrame>,
        width: Int,
        height: Int,
        skyMask: BooleanArray,
        imageLoader: (ReplayDefectFrame) -> ArgbPixelImage
    ): Map<Int, List<ReplayDefectObservation>> {
        val byCoordinate = linkedMapOf<Int, MutableList<ReplayDefectObservation>>()
        frames.forEach { frame ->
            val image = imageLoader(frame)
            require(image.width == width && image.height == height)
            val redRing = IntArray(16)
            val greenRing = IntArray(16)
            val blueRing = IntArray(16)
            for (y in 2 until height - 2) for (x in 2 until width - 2) {
                val center = image.pixels[y * width + x]
                val centerRed = center ushr 16 and 0xFF
                val centerGreen = center ushr 8 and 0xFF
                val centerBlue = center and 0xFF
                var minimumRed = 255
                var minimumGreen = 255
                var minimumBlue = 255
                ReplayDefectMath.ringOffsets.forEachIndexed { index, (dx, dy) ->
                    val color = image.pixels[(y + dy) * width + x + dx]
                    redRing[index] = color ushr 16 and 0xFF
                    greenRing[index] = color ushr 8 and 0xFF
                    blueRing[index] = color and 0xFF
                    minimumRed = min(minimumRed, redRing[index])
                    minimumGreen = min(minimumGreen, greenRing[index])
                    minimumBlue = min(minimumBlue, blueRing[index])
                }
                if (maxOf(centerRed - minimumRed, centerGreen - minimumGreen, centerBlue - minimumBlue) < 6) continue
                val output = ReplayTransformSemantics.sourceToOutput(frame.transform, x.toDouble(), y.toDouble())
                if (!output.x.isFinite() || !output.y.isFinite()) continue
                val outputX = output.x.roundToInt()
                val outputY = output.y.roundToInt()
                if (outputX !in 0 until width || outputY !in 0 until height || !skyMask[outputY * width + outputX]) continue
                redRing.sort()
                greenRing.sort()
                blueRing.sort()
                val redBackground = (redRing[7] + redRing[8]) * 0.5
                val greenBackground = (greenRing[7] + greenRing[8]) * 0.5
                val blueBackground = (blueRing[7] + blueRing[8]) * 0.5
                val redResidual = centerRed - redBackground
                val greenResidual = centerGreen - greenBackground
                val blueResidual = centerBlue - blueBackground
                val kind = ReplayDefectMath.classifyResidual(redResidual, greenResidual, blueResidual) ?: continue
                val centroid = residualCentroid(image, x, y, redBackground, greenBackground, blueBackground)
                val signal = if (kind == ReplayDefectKind.CHROMA) {
                    maxOf(redResidual, greenResidual, blueResidual)
                } else {
                    0.2126 * redResidual + 0.7152 * greenResidual + 0.0722 * blueResidual
                }
                byCoordinate.getOrPut(y * width + x, ::mutableListOf) += ReplayDefectObservation(
                    frame.id,
                    frame.captureIndex,
                    x,
                    y,
                    centroid?.x,
                    centroid?.y,
                    redResidual,
                    greenResidual,
                    blueResidual,
                    signal,
                    kind
                )
            }
        }
        return byCoordinate
    }

    private fun residualCentroid(
        image: ArgbPixelImage,
        centerX: Int,
        centerY: Int,
        backgroundRed: Double,
        backgroundGreen: Double,
        backgroundBlue: Double
    ): ReplayPoint? {
        var weight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        for (dy in -1..1) for (dx in -1..1) {
            val color = image.pixels[(centerY + dy) * image.width + centerX + dx]
            val red = (color ushr 16 and 0xFF) - backgroundRed
            val green = (color ushr 8 and 0xFF) - backgroundGreen
            val blue = (color and 0xFF) - backgroundBlue
            val signal = max(0.0, 0.2126 * red + 0.7152 * green + 0.0722 * blue)
            weight += signal
            weightedX += (centerX + dx) * signal
            weightedY += (centerY + dy) * signal
        }
        return if (weight > 0.0) ReplayPoint(weightedX / weight, weightedY / weight) else null
    }

    private fun buildSupportedCandidates(
        observations: Map<Int, List<ReplayDefectObservation>>,
        frames: List<ReplayDefectFrame>,
        width: Int,
        height: Int,
        skyMask: BooleanArray
    ): List<ReplayConfirmedDefect> {
        val chromaMinimum = max(ReplayDefectThresholds.CHROMA_MIN_SUPPORT, ceil(frames.size * ReplayDefectThresholds.CHROMA_SUPPORT_FRACTION).toInt())
        val luminanceMinimum = max(ReplayDefectThresholds.LUMINANCE_MIN_SUPPORT, ceil(frames.size * ReplayDefectThresholds.LUMINANCE_SUPPORT_FRACTION).toInt())
        return observations.entries.mapNotNull { (index, values) ->
            val chroma = values.filter { it.kind == ReplayDefectKind.CHROMA }
            val luminance = values.filter { it.kind == ReplayDefectKind.LUMINANCE }
            val selected = if (chroma.size >= chromaMinimum) chroma else luminance
            val kind = if (chroma.size >= chromaMinimum) ReplayDefectKind.CHROMA else ReplayDefectKind.LUMINANCE
            val minimum = if (kind == ReplayDefectKind.CHROMA) chromaMinimum else luminanceMinimum
            val thirdsRequired = if (kind == ReplayDefectKind.CHROMA) 2 else 3
            if (selected.size < minimum) return@mapNotNull null
            val thirds = selected.map { observation ->
                val order = frames.indexOfFirst { it.id == observation.frameId }.coerceAtLeast(0)
                min(2, order * 3 / frames.size)
            }.toSet().size
            if (thirds < thirdsRequired) return@mapNotNull null
            val x = index % width
            val y = index / width
            val eligiblePath = eligiblePath(x, y, selected.map { it.frameId }.toSet(), frames, width, height, skyMask)
            val integerRms = ReplayDefectMath.positionRms(selected.map { it.sourceX.toDouble() to it.sourceY.toDouble() }, x.toDouble(), y.toDouble())
            val centroids = selected.mapNotNull { observation ->
                observation.centroidX?.let { cx -> observation.centroidY?.let { cy -> cx to cy } }
            }
            val centroidRms = if (centroids.isEmpty()) null else ReplayDefectMath.positionRms(
                centroids,
                centroids.map { it.first }.average(),
                centroids.map { it.second }.average()
            )
            if (integerRms > ReplayDefectThresholds.POSITION_RMS_LIMIT ||
                centroidRms != null && centroidRms > ReplayDefectThresholds.POSITION_RMS_LIMIT
            ) return@mapNotNull null
            ReplayConfirmedDefect(
                x,
                y,
                kind,
                selected.sortedBy { it.captureIndex },
                eligiblePath,
                integerRms,
                centroidRms,
                ReplayDefectMath.median(selected.map { it.signalResidual }),
                emptySet(),
                null
            )
        }.sortedWith(compareBy<ReplayConfirmedDefect> { it.coreY }.thenBy { it.coreX })
    }

    private fun eligiblePath(
        sourceX: Int,
        sourceY: Int,
        observedFrameIds: Set<String>,
        frames: List<ReplayDefectFrame>,
        width: Int,
        height: Int,
        skyMask: BooleanArray
    ): List<ReplayDefectPathPoint> = frames.mapNotNull { frame ->
        if (sourceX !in 0 until width || sourceY !in 0 until height) return@mapNotNull null
        val output = ReplayTransformSemantics.sourceToOutput(frame.transform, sourceX.toDouble(), sourceY.toDouble())
        if (!output.x.isFinite() || !output.y.isFinite()) return@mapNotNull null
        val x = output.x.roundToInt()
        val y = output.y.roundToInt()
        if (x !in 0 until width || y !in 0 until height || !skyMask[y * width + x]) return@mapNotNull null
        ReplayDefectPathPoint(frame.id, frame.captureIndex, output.x, output.y, frame.id in observedFrameIds)
    }.sortedBy { it.captureIndex }

    private fun enrichCandidates(
        candidates: List<ReplayConfirmedDefect>,
        frames: List<ReplayDefectFrame>,
        stars: List<FullResolutionStarPatch>,
        imageLoader: (ReplayDefectFrame) -> ArgbPixelImage
    ): List<ReplayConfirmedDefect> {
        if (candidates.isEmpty()) return emptyList()
        val patches = candidates.associate { it.coreX to it.coreY to linkedMapOf<String, Array<DoubleArray>>() }.toMutableMap()
        frames.forEach { frame ->
            val relevant = candidates.filter { candidate -> candidate.observations.any { it.frameId == frame.id } }
            if (relevant.isEmpty()) return@forEach
            val image = imageLoader(frame)
            relevant.forEach { candidate ->
                patches.getValue(candidate.coreX to candidate.coreY)[frame.id] = residualPatch(image, candidate.coreX, candidate.coreY)
            }
        }
        return candidates.map { candidate ->
            val patchByFrame = patches.getValue(candidate.coreX to candidate.coreY)
            val medianPatch = temporalMedianPatch(patchByFrame.values.toList())
            val rejection = starRejection(candidate, medianPatch, stars)
            val footprint = footprint(candidate, patchByFrame)
            candidate.copy(footprintOffsets = footprint, rejectionReason = rejection)
        }
    }

    private fun residualPatch(image: ArgbPixelImage, centerX: Int, centerY: Int): Array<DoubleArray> {
        val ringRed = mutableListOf<Double>()
        val ringGreen = mutableListOf<Double>()
        val ringBlue = mutableListOf<Double>()
        ReplayDefectMath.ringOffsets.forEach { (dx, dy) ->
            val color = image.pixels[(centerY + dy) * image.width + centerX + dx]
            ringRed += (color ushr 16 and 0xFF).toDouble()
            ringGreen += (color ushr 8 and 0xFF).toDouble()
            ringBlue += (color and 0xFF).toDouble()
        }
        val background = doubleArrayOf(
            ReplayDefectMath.median(ringRed),
            ReplayDefectMath.median(ringGreen),
            ReplayDefectMath.median(ringBlue)
        )
        return Array(25) { index ->
            val dx = index % 5 - 2
            val dy = index / 5 - 2
            val color = image.pixels[(centerY + dy) * image.width + centerX + dx]
            doubleArrayOf(
                (color ushr 16 and 0xFF) - background[0],
                (color ushr 8 and 0xFF) - background[1],
                (color and 0xFF) - background[2]
            )
        }
    }

    private fun temporalMedianPatch(patches: List<Array<DoubleArray>>): Array<DoubleArray> = Array(25) { index ->
        DoubleArray(3) { channel -> ReplayDefectMath.median(patches.map { it[index][channel] }) }
    }

    private fun starRejection(
        candidate: ReplayConfirmedDefect,
        patch: Array<DoubleArray>,
        stars: List<FullResolutionStarPatch>
    ): String? {
        val observedOutput = candidate.eligiblePath.filter { it.observed }
        val coherent = stars.filter { it.motionCluster == TemporalMotionCluster.COHERENT_MOVING_SKY }
        if (observedOutput.isNotEmpty() && coherent.any { star ->
                observedOutput.count { hypot(it.x - star.x, it.y - star.y) <= ReplayDefectThresholds.SKY_TRACK_DISTANCE } >=
                    ceil(observedOutput.size * ReplayDefectThresholds.STAR_MATCH_FRACTION).toInt()
            }
        ) return "sky_track"
        if (observedOutput.isNotEmpty() && stars.any { star ->
                val radius = ceil(max(3.0, star.width * 1.8)).coerceAtMost(7.0)
                observedOutput.count { hypot(it.x - star.x, it.y - star.y) <= radius } >=
                    ceil(observedOutput.size * ReplayDefectThresholds.STAR_MATCH_FRACTION).toInt()
            }
        ) return "reference_star"
        if (isSymmetricPsf(patch)) return "symmetric_psf"
        if (hasCoherentNeighbours(patch)) return "coherent_neighbours"
        return null
    }

    private fun luminance(value: DoubleArray): Double =
        0.2126 * value[0] + 0.7152 * value[1] + 0.0722 * value[2]

    private fun isSymmetricPsf(patch: Array<DoubleArray>): Boolean {
        val values = patch.map { max(0.0, luminance(it)) }
        val peak = values.maxOrNull() ?: 0.0
        if (peak <= 0.0) return false
        val threshold = max(ReplayDefectThresholds.PSF_COMPONENT_MIN_RESIDUAL, peak * ReplayDefectThresholds.PSF_COMPONENT_PEAK_FRACTION)
        val component = connectedComponentContainingCenter(BooleanArray(25) { values[it] >= threshold }, 5, 5, 12)
        if (component.size < ReplayDefectThresholds.PSF_COMPONENT_MIN_PIXELS) return false
        val points = component.map { ReplayPoint((it % 5 - 2).toDouble(), (it / 5 - 2).toDouble()) }
        val geometry = weightedGeometry(component, values)
        if (geometry.third > ReplayDefectThresholds.PSF_ELLIPTICITY_LIMIT) return false
        var symmetryNumerator = 0.0
        var symmetryDenominator = 0.0
        for (dy in -2..2) for (dx in -2..2) {
            val first = values[(dy + 2) * 5 + dx + 2]
            val opposite = values[(-dy + 2) * 5 - dx + 2]
            symmetryNumerator += abs(first - opposite)
            symmetryDenominator += first + opposite
        }
        val symmetry = symmetryNumerator / max(symmetryDenominator, 1e-9)
        if (symmetry > ReplayDefectThresholds.PSF_SYMMETRY_ERROR_LIMIT) return false
        fun meanFor(predicate: (Int) -> Boolean): Double {
            val selected = values.indices.filter { index ->
                val dx = index % 5 - 2
                val dy = index / 5 - 2
                predicate(dx * dx + dy * dy)
            }
            return selected.map { values[it] }.average()
        }
        val core = meanFor { it <= 1 }
        val middle = meanFor { it in 2..4 }
        val outer = meanFor { it in 5..8 }
        val violation = max(0.0, middle - core) + max(0.0, outer - middle)
        return violation <= ReplayDefectThresholds.PSF_RADIAL_VIOLATION_FRACTION * peak && points.size >= 3
    }

    private fun weightedGeometry(component: List<Int>, weights: List<Double>): Triple<Double, Double, Double> {
        val total = component.sumOf { weights[it] }.coerceAtLeast(1e-9)
        val meanX = component.sumOf { (it % 5 - 2) * weights[it] } / total
        val meanY = component.sumOf { (it / 5 - 2) * weights[it] } / total
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        component.forEach { index ->
            val dx = index % 5 - 2 - meanX
            val dy = index / 5 - 2 - meanY
            xx += weights[index] * dx * dx
            yy += weights[index] * dy * dy
            xy += weights[index] * dx * dy
        }
        xx /= total
        yy /= total
        xy /= total
        val trace = xx + yy
        val root = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4.0 * xy * xy))
        val major = max(0.0, (trace + root) * 0.5)
        val minor = max(0.0, (trace - root) * 0.5)
        val ellipticity = if (major <= 1e-9) 1.0 else 1.0 - sqrt(minor / major)
        return Triple(major, minor, ellipticity)
    }

    private fun hasCoherentNeighbours(patch: Array<DoubleArray>): Boolean {
        val center = max(0.0, luminance(patch[12]))
        val threshold = max(3.0, center * ReplayDefectThresholds.PSF_COMPONENT_PEAK_FRACTION)
        var count = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val residual = patch[(dy + 2) * 5 + dx + 2]
            val maximum = residual.maxOrNull() ?: 0.0
            val minimum = residual.minOrNull() ?: 0.0
            if (luminance(residual) >= threshold && minimum > 0.0 &&
                minimum >= ReplayDefectThresholds.CONSISTENT_CHANNEL_FRACTION * maximum
            ) count++
        }
        return count >= ReplayDefectThresholds.COHERENT_NEIGHBOUR_COUNT
    }

    private fun footprint(
        candidate: ReplayConfirmedDefect,
        patches: Map<String, Array<DoubleArray>>
    ): Set<Pair<Int, Int>> {
        val frameIds = candidate.observations.map { it.frameId }
        val coreSignals = frameIds.map { frameId -> signal(candidate.kind, checkNotNull(patches[frameId])[12], candidate) }
        val coreMedian = ReplayDefectMath.median(coreSignals)
        val minimumRecurrence = max(3, ceil(candidate.observations.size * ReplayDefectThresholds.FOOTPRINT_RECURRENCE_FRACTION).toInt())
        return buildSet {
            for (dy in -2..2) for (dx in -2..2) {
                if (dx == 0 && dy == 0) continue
                val index = (dy + 2) * 5 + dx + 2
                val residuals = frameIds.map { frameId ->
                    signal(candidate.kind, checkNotNull(patches[frameId])[index], candidate).takeIf {
                        it >= ReplayDefectThresholds.FOOTPRINT_MIN_RESIDUAL
                    } ?: 0.0
                }
                val observed = residuals.count { it > 0.0 }
                if (observed < ReplayDefectThresholds.FOOTPRINT_MIN_FRAMES) continue
                if (observed >= minimumRecurrence ||
                    ReplayDefectMath.median(residuals) >= ReplayDefectThresholds.FOOTPRINT_MEDIAN_FRACTION * coreMedian
                ) add(dx to dy)
            }
        }
    }

    private fun signal(kind: ReplayDefectKind, residual: DoubleArray, candidate: ReplayConfirmedDefect): Double {
        if (kind == ReplayDefectKind.LUMINANCE) return max(0.0, luminance(residual))
        val medianResidual = doubleArrayOf(
            ReplayDefectMath.median(candidate.observations.map { it.redResidual }),
            ReplayDefectMath.median(candidate.observations.map { it.greenResidual }),
            ReplayDefectMath.median(candidate.observations.map { it.blueResidual })
        )
        val dominant = medianResidual.indices.maxByOrNull { medianResidual[it] } ?: 0
        return max(0.0, residual[dominant])
    }

    private fun detectTrails(image: ArgbPixelImage, skyMask: BooleanArray): List<ReplayTrailComponent> {
        val candidates = BooleanArray(image.pixels.size)
        val redRing = IntArray(16)
        val greenRing = IntArray(16)
        val blueRing = IntArray(16)
        for (y in 2 until image.height - 2) for (x in 2 until image.width - 2) {
            val index = y * image.width + x
            if (!skyMask[index]) continue
            ReplayDefectMath.ringOffsets.forEachIndexed { ringIndex, (dx, dy) ->
                val color = image.pixels[(y + dy) * image.width + x + dx]
                redRing[ringIndex] = color ushr 16 and 0xFF
                greenRing[ringIndex] = color ushr 8 and 0xFF
                blueRing[ringIndex] = color and 0xFF
            }
            redRing.sort(); greenRing.sort(); blueRing.sort()
            val color = image.pixels[index]
            val residuals = listOf(
                (color ushr 16 and 0xFF) - (redRing[7] + redRing[8]) * 0.5,
                (color ushr 8 and 0xFF) - (greenRing[7] + greenRing[8]) * 0.5,
                (color and 0xFF) - (blueRing[7] + blueRing[8]) * 0.5
            ).map { max(0.0, it) }.sortedDescending()
            candidates[index] = residuals[0] - residuals[1] >= ReplayDefectThresholds.TRAIL_CHROMA_RESIDUAL
        }
        val visited = BooleanArray(candidates.size)
        val trails = mutableListOf<ReplayTrailComponent>()
        candidates.indices.forEach { start ->
            if (!candidates[start] || visited[start]) return@forEach
            val component = connectedComponent(candidates, image.width, image.height, start, visited)
            if (component.size < ReplayDefectThresholds.TRAIL_MIN_AREA) return@forEach
            val geometry = ReplayDefectMath.pca(component.map { ReplayPoint((it % image.width).toDouble(), (it / image.width).toDouble()) })
            if (geometry.majorAxisLength < ReplayDefectThresholds.TRAIL_MIN_MAJOR_AXIS ||
                geometry.elongation < ReplayDefectThresholds.TRAIL_MIN_ELONGATION
            ) return@forEach
            trails += ReplayTrailComponent(trails.size, component.toIntArray(), geometry)
        }
        return trails
    }

    private fun consolidateDefectComponents(
        defects: List<ReplayConfirmedDefect>,
        width: Int,
        height: Int
    ): List<ReplayDefectComponent> {
        val footprints = defects.map { defect ->
            buildSet {
                add(defect.coreY * width + defect.coreX)
                defect.footprintOffsets.forEach { (dx, dy) ->
                    val x = defect.coreX + dx
                    val y = defect.coreY + dy
                    if (x in 0 until width && y in 0 until height) add(y * width + x)
                }
            }
        }
        val remaining = defects.indices.toMutableSet()
        val groups = mutableListOf<List<Int>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.minWith(compareBy<Int> { defects[it].coreY }.thenBy { defects[it].coreX })
            remaining.remove(seed)
            val group = mutableListOf(seed)
            val queue = ArrayDeque<Int>()
            queue += seed
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val adjacent = remaining.filter { candidate -> footprintsTouch(footprints[current], footprints[candidate], width, height) }
                adjacent.forEach {
                    remaining.remove(it)
                    group += it
                    queue += it
                }
            }
            groups += group
        }
        return groups.sortedWith(compareBy<List<Int>> { group -> group.minOf { defects[it].coreY } }
            .thenBy { group -> group.minOf { defects[it].coreX } })
            .mapIndexed { id, group ->
                ReplayDefectComponent(
                    id,
                    group.map { defects[it] }.sortedWith(compareBy<ReplayConfirmedDefect> { it.coreY }.thenBy { it.coreX }),
                    group.flatMap { footprints[it] }.distinct().sorted().toIntArray()
                )
            }
    }

    private fun footprintsTouch(first: Set<Int>, second: Set<Int>, width: Int, height: Int): Boolean {
        if (first.size > second.size) return footprintsTouch(second, first, width, height)
        return first.any { index ->
            val x = index % width
            val y = index / width
            (-1..1).any { dy ->
                (-1..1).any { dx ->
                    val nx = x + dx
                    val ny = y + dy
                    nx in 0 until width && ny in 0 until height && ny * width + nx in second
                }
            }
        }
    }

    private fun correspondManualTrails(
        baseline: ArgbPixelImage,
        annotations: List<ReplayManualTrailAnnotation>,
        components: List<ReplayDefectComponent>,
        stars: List<FullResolutionStarPatch>
    ): List<ReplayManualTrailCorrespondence> = annotations.flatMap { annotation ->
        val trailPath = annotation.centerline.mapIndexed { index, point ->
            ReplayDefectPathPoint(annotation.id, index, point.x, point.y, true)
        }
        val trailPixels = rasterize(trailPath, baseline.width, baseline.height)
        val trailGeometry = ReplayDefectMath.pca(trailPixels.map {
            ReplayPoint((it % baseline.width).toDouble(), (it / baseline.width).toDouble())
        })
        val residuals = segmentSamples(annotation.centerline)
            .distinct()
            .mapNotNull { point -> localResidualAt(baseline, point.x.roundToInt(), point.y.roundToInt()) }
        val chromaResiduals = residuals.map { it.first }
        val luminanceResiduals = residuals.map { it.second }
        if (components.isEmpty()) {
            listOf(ReplayManualTrailCorrespondence(
                annotation.id, null, 0.0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                null, 0.0, ReplayManualTrailStatus.UNCERTAIN, false,
                ReplayDefectMath.median(chromaResiduals), chromaResiduals.maxOrNull() ?: 0.0,
                ReplayDefectMath.median(luminanceResiduals), luminanceResiduals.maxOrNull() ?: 0.0
            ))
        } else components.map { component ->
            val pathPixels = component.paths.flatMap { rasterize(it, baseline.width, baseline.height) }.toSet()
            val intersection = pathPixels.count { it in trailPixels }
            val union = pathPixels.size + trailPixels.size - intersection
            val distances = trailPixels.map { index ->
                val point = ReplayPoint((index % baseline.width).toDouble(), (index / baseline.width).toDouble())
                component.paths.minOfOrNull { distanceToPath(point, it) } ?: Double.POSITIVE_INFINITY
            }
            val medianDistance = ReplayDefectMath.median(distances)
            val maximumDistance = distances.maxOrNull() ?: Double.POSITIVE_INFINITY
            val pathGeometry = ReplayDefectMath.pca(pathPixels.map {
                ReplayPoint((it % baseline.width).toDouble(), (it / baseline.width).toDouble())
            })
            val orientation = ReplayDefectMath.orientationDifferenceDegrees(
                trailGeometry.orientationRadians,
                pathGeometry.orientationRadians
            )
            val iou = intersection.toDouble() / union.coerceAtLeast(1)
            val covered = intersection.toDouble() / trailPixels.size.coerceAtLeast(1)
            val explained = iou >= ReplayDefectThresholds.EXPLAINED_MIN_IOU &&
                medianDistance <= ReplayDefectThresholds.EXPLAINED_MAX_MEDIAN_DISTANCE &&
                maximumDistance <= ReplayDefectThresholds.EXPLAINED_MAX_DISTANCE &&
                orientation != null && orientation <= ReplayDefectThresholds.EXPLAINED_MAX_ORIENTATION_DEGREES &&
                covered >= ReplayDefectThresholds.EXPLAINED_MIN_COVERED_FRACTION
            val status = when {
                explained -> ReplayManualTrailStatus.EXPLAINED
                orientation == null -> ReplayManualTrailStatus.UNCERTAIN
                intersection > 0 || (medianDistance <= ReplayDefectThresholds.EXPLAINED_MAX_DISTANCE &&
                    orientation <= ReplayDefectThresholds.EXPLAINED_MAX_ORIENTATION_DEGREES) -> ReplayManualTrailStatus.PARTIALLY_MATCHED
                else -> ReplayManualTrailStatus.UNMATCHED
            }
            val overlapsStar = pathPixels.any { index ->
                val x = index % baseline.width
                val y = index / baseline.width
                stars.any { star -> hypot(x - star.x.toDouble(), y - star.y.toDouble()) <= max(1.5, star.width * 2.0) }
            }
            ReplayManualTrailCorrespondence(
                annotation.id, component.id, iou, medianDistance, maximumDistance, orientation, covered,
                status, overlapsStar,
                ReplayDefectMath.median(chromaResiduals), chromaResiduals.maxOrNull() ?: 0.0,
                ReplayDefectMath.median(luminanceResiduals), luminanceResiduals.maxOrNull() ?: 0.0
            )
        }
    }

    private fun segmentSamples(it: List<ReplayPoint>): List<ReplayPoint> {
        if (it.isEmpty()) return emptyList()
        if (it.size == 1) return it
        return it.zipWithNext().flatMap { (first, second) ->
            val steps = ceil(hypot(second.x - first.x, second.y - first.y)).toInt().coerceAtLeast(1)
            (0..steps).map { step ->
                val t = step.toDouble() / steps
                ReplayPoint(first.x + (second.x - first.x) * t, first.y + (second.y - first.y) * t)
            }
        }
    }

    private fun localResidualAt(image: ArgbPixelImage, x: Int, y: Int): Pair<Double, Double>? {
        if (x !in 2 until image.width - 2 || y !in 2 until image.height - 2) return null
        val rings = Array(3) { IntArray(16) }
        ReplayDefectMath.ringOffsets.forEachIndexed { index, (dx, dy) ->
            val color = image.pixels[(y + dy) * image.width + x + dx]
            rings[0][index] = color ushr 16 and 0xFF
            rings[1][index] = color ushr 8 and 0xFF
            rings[2][index] = color and 0xFF
        }
        rings.forEach { it.sort() }
        val color = image.pixels[y * image.width + x]
        val residuals = listOf(
            (color ushr 16 and 0xFF) - (rings[0][7] + rings[0][8]) * 0.5,
            (color ushr 8 and 0xFF) - (rings[1][7] + rings[1][8]) * 0.5,
            (color and 0xFF) - (rings[2][7] + rings[2][8]) * 0.5
        )
        val positive = residuals.map { max(0.0, it) }.sortedDescending()
        val chroma = positive[0] - positive[1]
        val luminance = 0.2126 * residuals[0] + 0.7152 * residuals[1] + 0.0722 * residuals[2]
        return chroma to luminance
    }

    private fun connectedComponent(
        mask: BooleanArray,
        width: Int,
        height: Int,
        start: Int,
        visited: BooleanArray
    ): List<Int> {
        val result = mutableListOf<Int>()
        val queue = ArrayDeque<Int>()
        visited[start] = true
        queue += start
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            result += index
            val x = index % width
            val y = index / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (mask[next] && !visited[next]) {
                    visited[next] = true
                    queue += next
                }
            }
        }
        return result
    }

    private fun connectedComponentContainingCenter(mask: BooleanArray, width: Int, height: Int, center: Int): List<Int> {
        if (!mask[center]) return emptyList()
        return connectedComponent(mask, width, height, center, BooleanArray(mask.size))
    }

    private fun correspond(
        trails: List<ReplayTrailComponent>,
        defects: List<ReplayConfirmedDefect>,
        width: Int,
        height: Int
    ): List<ReplayTrailCorrespondence> = trails.map { trail ->
        val candidates = defects.map { defect -> correspondence(trail, defect, width, height) }
        candidates.sortedWith(
            compareByDescending<ReplayTrailCorrespondence> { it.intersectionOverUnion }
                .thenBy { it.medianDistance }
                .thenBy { it.maximumDistance }
                .thenBy { it.defectY }
                .thenBy { it.defectX }
        ).firstOrNull() ?: ReplayTrailCorrespondence(
            trail.id, null, null, 0.0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            null, 0.0, false, "unexplained_no_confirmed_defect"
        )
    }

    private fun correspondence(
        trail: ReplayTrailComponent,
        defect: ReplayConfirmedDefect,
        width: Int,
        height: Int
    ): ReplayTrailCorrespondence {
        val pathPixels = rasterize(defect.eligiblePath, width, height)
        val trailSet = trail.pixels.toSet()
        val intersection = pathPixels.count { it in trailSet }
        val union = pathPixels.size + trailSet.size - intersection
        val iou = intersection.toDouble() / union.coerceAtLeast(1)
        val covered = intersection.toDouble() / trailSet.size.coerceAtLeast(1)
        val distances = trail.pixels.map { index ->
            distanceToPath(ReplayPoint((index % width).toDouble(), (index / width).toDouble()), defect.eligiblePath)
        }
        val medianDistance = ReplayDefectMath.median(distances)
        val maximumDistance = distances.maxOrNull() ?: Double.POSITIVE_INFINITY
        val pathGeometry = ReplayDefectMath.pca(pathPixels.map { ReplayPoint((it % width).toDouble(), (it / width).toDouble()) })
        val orientation = ReplayDefectMath.orientationDifferenceDegrees(
            trail.geometry.orientationRadians,
            pathGeometry.orientationRadians
        )
        val explained = iou >= ReplayDefectThresholds.EXPLAINED_MIN_IOU &&
            medianDistance <= ReplayDefectThresholds.EXPLAINED_MAX_MEDIAN_DISTANCE &&
            maximumDistance <= ReplayDefectThresholds.EXPLAINED_MAX_DISTANCE &&
            orientation != null && orientation <= ReplayDefectThresholds.EXPLAINED_MAX_ORIENTATION_DEGREES &&
            covered >= ReplayDefectThresholds.EXPLAINED_MIN_COVERED_FRACTION
        return ReplayTrailCorrespondence(
            trail.id,
            defect.coreX,
            defect.coreY,
            iou,
            medianDistance,
            maximumDistance,
            orientation,
            covered,
            explained,
            if (explained) "explained" else if (orientation == null) "unexplained_orientation_undefined" else "unexplained_thresholds"
        )
    }

    private fun rasterize(path: List<ReplayDefectPathPoint>, width: Int, height: Int): Set<Int> {
        if (path.isEmpty()) return emptySet()
        val segments = if (path.size == 1) listOf(path[0] to path[0]) else path.zipWithNext()
        return buildSet {
            segments.forEach { (first, second) ->
                val left = floor(min(first.x, second.x) - ReplayDefectThresholds.PATH_BUFFER_RADIUS).toInt().coerceAtLeast(0)
                val right = ceil(max(first.x, second.x) + ReplayDefectThresholds.PATH_BUFFER_RADIUS).toInt().coerceAtMost(width - 1)
                val top = floor(min(first.y, second.y) - ReplayDefectThresholds.PATH_BUFFER_RADIUS).toInt().coerceAtLeast(0)
                val bottom = ceil(max(first.y, second.y) + ReplayDefectThresholds.PATH_BUFFER_RADIUS).toInt().coerceAtMost(height - 1)
                for (y in top..bottom) for (x in left..right) {
                    if (distanceToSegment(ReplayPoint(x.toDouble(), y.toDouble()), first, second) <= ReplayDefectThresholds.PATH_BUFFER_RADIUS) {
                        add(y * width + x)
                    }
                }
            }
        }
    }

    private fun distanceToPath(point: ReplayPoint, path: List<ReplayDefectPathPoint>): Double {
        if (path.isEmpty()) return Double.POSITIVE_INFINITY
        if (path.size == 1) return hypot(point.x - path[0].x, point.y - path[0].y)
        return path.zipWithNext().minOf { (first, second) -> distanceToSegment(point, first, second) }
    }

    private fun distanceToSegment(point: ReplayPoint, first: ReplayDefectPathPoint, second: ReplayDefectPathPoint): Double {
        val vx = second.x - first.x
        val vy = second.y - first.y
        val lengthSquared = vx * vx + vy * vy
        if (lengthSquared <= 1e-12) return hypot(point.x - first.x, point.y - first.y)
        val t = (((point.x - first.x) * vx + (point.y - first.y) * vy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(point.x - (first.x + t * vx), point.y - (first.y + t * vy))
    }

    private fun manifest(width: Int, height: Int, skyHash: String): String {
        val values = sortedMapOf<String, String>(
            "chromaCenterResidual" to ReplayDefectThresholds.CHROMA_CENTER_RESIDUAL.toString(),
            "chromaDominance" to ReplayDefectThresholds.CHROMA_DOMINANCE.toString(),
            "chromaMinSupport" to ReplayDefectThresholds.CHROMA_MIN_SUPPORT.toString(),
            "chromaSupportFraction" to ReplayDefectThresholds.CHROMA_SUPPORT_FRACTION.toString(),
            "coherentNeighbourCount" to ReplayDefectThresholds.COHERENT_NEIGHBOUR_COUNT.toString(),
            "connectivity" to "8",
            "consistentChannelFraction" to ReplayDefectThresholds.CONSISTENT_CHANNEL_FRACTION.toString(),
            "coverageHardPercent" to ReplayDefectThresholds.HARD_COVERAGE_PERCENT.toString(),
            "coverageNormalPercent" to ReplayDefectThresholds.NORMAL_COVERAGE_PERCENT.toString(),
            "explainedMaxDistance" to ReplayDefectThresholds.EXPLAINED_MAX_DISTANCE.toString(),
            "explainedMaxMedianDistance" to ReplayDefectThresholds.EXPLAINED_MAX_MEDIAN_DISTANCE.toString(),
            "explainedMaxOrientationDegrees" to ReplayDefectThresholds.EXPLAINED_MAX_ORIENTATION_DEGREES.toString(),
            "explainedMinCoveredFraction" to ReplayDefectThresholds.EXPLAINED_MIN_COVERED_FRACTION.toString(),
            "explainedMinIou" to ReplayDefectThresholds.EXPLAINED_MIN_IOU.toString(),
            "footprintMedianFraction" to ReplayDefectThresholds.FOOTPRINT_MEDIAN_FRACTION.toString(),
            "footprintMinFrames" to ReplayDefectThresholds.FOOTPRINT_MIN_FRAMES.toString(),
            "footprintMinResidual" to ReplayDefectThresholds.FOOTPRINT_MIN_RESIDUAL.toString(),
            "footprintRecurrenceFraction" to ReplayDefectThresholds.FOOTPRINT_RECURRENCE_FRACTION.toString(),
            "height" to height.toString(),
            "luminanceCenterResidual" to ReplayDefectThresholds.LUMINANCE_CENTER_RESIDUAL.toString(),
            "luminanceMinSupport" to ReplayDefectThresholds.LUMINANCE_MIN_SUPPORT.toString(),
            "luminanceSupportFraction" to ReplayDefectThresholds.LUMINANCE_SUPPORT_FRACTION.toString(),
            "localRing" to "max(abs(dx),abs(dy))==2;central3x3Excluded",
            "manifestVersion" to ReplayDefectThresholds.MANIFEST_VERSION,
            "pathBufferRadius" to ReplayDefectThresholds.PATH_BUFFER_RADIUS.toString(),
            "positionRmsLimit" to ReplayDefectThresholds.POSITION_RMS_LIMIT.toString(),
            "psfComponentMinPixels" to ReplayDefectThresholds.PSF_COMPONENT_MIN_PIXELS.toString(),
            "psfComponentMinResidual" to ReplayDefectThresholds.PSF_COMPONENT_MIN_RESIDUAL.toString(),
            "psfComponentPeakFraction" to ReplayDefectThresholds.PSF_COMPONENT_PEAK_FRACTION.toString(),
            "psfEllipticityLimit" to ReplayDefectThresholds.PSF_ELLIPTICITY_LIMIT.toString(),
            "psfRadialViolationFraction" to ReplayDefectThresholds.PSF_RADIAL_VIOLATION_FRACTION.toString(),
            "psfSymmetryErrorLimit" to ReplayDefectThresholds.PSF_SYMMETRY_ERROR_LIMIT.toString(),
            "roundTripMaxError" to ReplayDefectThresholds.ROUND_TRIP_MAX_ERROR.toString(),
            "skyAlpha" to ReplayDefectThresholds.SKY_ALPHA.toString(),
            "skyErosionRadius" to ReplayDefectThresholds.SKY_EROSION_RADIUS.toString(),
            "skyObservationRatio" to ReplayDefectThresholds.SKY_OBSERVATION_RATIO.toString(),
            "skyMaskHash" to skyHash,
            "skyMaskVersion" to ReplayDefectThresholds.SKY_MASK_VERSION,
            "trailMinArea" to ReplayDefectThresholds.TRAIL_MIN_AREA.toString(),
            "trailChromaResidual" to ReplayDefectThresholds.TRAIL_CHROMA_RESIDUAL.toString(),
            "trailMinElongation" to ReplayDefectThresholds.TRAIL_MIN_ELONGATION.toString(),
            "trailMinMajorAxis" to ReplayDefectThresholds.TRAIL_MIN_MAJOR_AXIS.toString(),
            "starMatchFraction" to ReplayDefectThresholds.STAR_MATCH_FRACTION.toString(),
            "skyTrackDistance" to ReplayDefectThresholds.SKY_TRACK_DISTANCE.toString(),
            "transformContract" to "verified-output-to-source-with-mathematical-inverse",
            "width" to width.toString()
        )
        return values.entries.joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") {
            "  \"${it.key}\":\"${it.value}\""
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeOutputs(
        bundle: ReplayCameraDefectBundle,
        baseline: ArgbPixelImage,
        frames: List<ReplayDefectFrame>,
        outputRoot: Path,
        imageLoader: (ReplayDefectFrame) -> ArgbPixelImage,
        maximumRoundTripError: Double
    ) {
        Files.createDirectories(outputRoot)
        Files.writeString(outputRoot.resolve("threshold-manifest.json"), bundle.thresholdManifest)
        writeImage(outputRoot.resolve("frozen-sky-mask.png"), maskImage(baseline.width, baseline.height, bundle.skyMask, 0xFFFFFFFF.toInt()))
        writeImage(outputRoot.resolve("defect-map-undilated.png"), maskImage(baseline.width, baseline.height, bundle.undilatedMap, 0xFFFFFFFF.toInt()))
        writeImage(outputRoot.resolve("defect-map-proposed.png"), maskImage(baseline.width, baseline.height, bundle.proposedMap, 0xFFFF00FF.toInt()))
        writeImage(outputRoot.resolve("camera-persistence-heatmap.png"), persistenceImage(baseline, bundle.allSupportedCandidates, frames.size))
        writeImage(outputRoot.resolve("projected-path-overlay.png"), pathOverlay(baseline, bundle.confirmedDefects, observedOnly = false))
        writeImage(outputRoot.resolve("observed-inferred-overlay.png"), pathOverlay(baseline, bundle.confirmedDefects, observedOnly = true))
        val stretched = strongStretchPreview(baseline)
        writeImage(outputRoot.resolve("stage1b-strong-stretch.png"), stretched)
        writeImage(outputRoot.resolve("stage1b-manual-trails-overlay.png"), manualTrailOverlay(stretched, bundle.manualTrails))
        writeImage(
            outputRoot.resolve("stage1b-manual-component-overlay.png"),
            manualComponentOverlay(stretched, bundle.manualTrails, bundle.defectComponents)
        )
        Files.writeString(outputRoot.resolve("defect-candidates.tsv"), candidateTable(bundle))
        Files.writeString(outputRoot.resolve("trail-correspondence.tsv"), correspondenceTable(bundle))
        Files.writeString(outputRoot.resolve("stage1b-components.tsv"), componentTable(bundle))
        Files.writeString(outputRoot.resolve("stage1b-manual-annotations.tsv"), manualAnnotationTable(bundle.manualTrails))
        Files.writeString(outputRoot.resolve("stage1b-manual-correspondence.tsv"), manualCorrespondenceTable(bundle))
        Files.writeString(outputRoot.resolve("camera-defect-report.txt"), report(bundle, maximumRoundTripError))
        writeReviewCrops(bundle, baseline, frames, outputRoot.resolve("review-crops"), imageLoader)
    }

    private fun maskImage(width: Int, height: Int, mask: BooleanArray, color: Int): ArgbPixelImage =
        ArgbPixelImage(width, height, IntArray(mask.size) { if (mask[it]) color else 0xFF000000.toInt() })

    private fun persistenceImage(
        baseline: ArgbPixelImage,
        candidates: List<ReplayConfirmedDefect>,
        frameCount: Int
    ): ArgbPixelImage {
        val pixels = IntArray(baseline.pixels.size) { 0xFF000000.toInt() }
        candidates.forEach { candidate ->
            val strength = (candidate.observedSupport * 255 / frameCount.coerceAtLeast(1)).coerceIn(0, 255)
            pixels[candidate.coreY * baseline.width + candidate.coreX] =
                0xFF000000.toInt() or (strength shl 16) or ((255 - strength) shl 8)
        }
        return ArgbPixelImage(baseline.width, baseline.height, pixels)
    }

    private fun pathOverlay(
        baseline: ArgbPixelImage,
        defects: List<ReplayConfirmedDefect>,
        observedOnly: Boolean
    ): ArgbPixelImage {
        val pixels = baseline.pixels.copyOf()
        defects.forEach { defect ->
            defect.eligiblePath.forEach { point ->
                val x = point.x.roundToInt()
                val y = point.y.roundToInt()
                if (x in 0 until baseline.width && y in 0 until baseline.height) {
                    pixels[y * baseline.width + x] = if (point.observed) 0xFF00FF00.toInt()
                    else if (observedOnly) 0xFFFFFF00.toInt() else 0xFFFF8800.toInt()
                }
            }
        }
        return ArgbPixelImage(baseline.width, baseline.height, pixels)
    }

    private fun strongStretchPreview(baseline: ArgbPixelImage): ArgbPixelImage {
        val pixels = IntArray(baseline.pixels.size) { index ->
            val color = baseline.pixels[index]
            val red = ((color ushr 16 and 0xFF) / 255.0).pow(0.35)
            val green = ((color ushr 8 and 0xFF) / 255.0).pow(0.35)
            val blue = ((color and 0xFF) / 255.0).pow(0.35)
            0xFF000000.toInt() or
                ((red * 255.0).roundToInt().coerceIn(0, 255) shl 16) or
                ((green * 255.0).roundToInt().coerceIn(0, 255) shl 8) or
                (blue * 255.0).roundToInt().coerceIn(0, 255)
        }
        return ArgbPixelImage(baseline.width, baseline.height, pixels)
    }

    private fun manualTrailOverlay(
        preview: ArgbPixelImage,
        annotations: List<ReplayManualTrailAnnotation>
    ): ArgbPixelImage {
        val pixels = preview.pixels.copyOf()
        annotations.forEach { annotation ->
            val path = annotation.centerline.mapIndexed { index, point ->
                ReplayDefectPathPoint(annotation.id, index, point.x, point.y, true)
            }
            rasterize(path, preview.width, preview.height).forEach { pixels[it] = 0xFF00FFFF.toInt() }
        }
        return ArgbPixelImage(preview.width, preview.height, pixels)
    }

    private fun manualComponentOverlay(
        preview: ArgbPixelImage,
        annotations: List<ReplayManualTrailAnnotation>,
        components: List<ReplayDefectComponent>
    ): ArgbPixelImage {
        val pixels = preview.pixels.copyOf()
        components.forEach { component ->
            component.paths.flatMap { rasterize(it, preview.width, preview.height) }
                .forEach { pixels[it] = 0xFFFF00FF.toInt() }
        }
        annotations.forEach { annotation ->
            val path = annotation.centerline.mapIndexed { index, point ->
                ReplayDefectPathPoint(annotation.id, index, point.x, point.y, true)
            }
            rasterize(path, preview.width, preview.height).forEach { pixels[it] = 0xFF00FFFF.toInt() }
        }
        return ArgbPixelImage(preview.width, preview.height, pixels)
    }

    private fun candidateTable(bundle: ReplayCameraDefectBundle): String = buildString {
        appendLine("coreX\tcoreY\tkind\tstatus\tobservedSupport\teligiblePathPoints\tobservedPathPoints\tinferredPathPoints\tintegerRms\tcentroidRms\tcoreMedianResidual\tfootprintPixels\tmatchedTrails")
        bundle.allSupportedCandidates.forEach { defect ->
            appendLine(listOf(
                defect.coreX, defect.coreY, defect.kind,
                defect.rejectionReason ?: if (defect.matchedTrailIds.isEmpty()) "confirmed_visually_unmatched" else "confirmed_visually_matched",
                defect.observedSupport, defect.eligiblePath.size,
                defect.eligiblePath.count { it.observed }, defect.eligiblePath.count { !it.observed },
                defect.integerPositionRms, defect.centroidPositionRms ?: "undefined",
                defect.coreMedianResidual, defect.footprintOffsets.size + 1,
                defect.matchedTrailIds.sorted().joinToString(",")
            ).joinToString("\t"))
        }
    }

    private fun correspondenceTable(bundle: ReplayCameraDefectBundle): String = buildString {
        appendLine("trailId\tdefectX\tdefectY\tiou\tmedianDistance\tmaximumDistance\torientationDifferenceDegrees\tcoveredFraction\tstatus")
        bundle.correspondences.forEach { value ->
            appendLine(listOf(
                value.trailId, value.defectX ?: "none", value.defectY ?: "none",
                value.intersectionOverUnion, value.medianDistance, value.maximumDistance,
                value.orientationDifferenceDegrees ?: "undefined", value.coveredFraction, value.status
            ).joinToString("\t"))
        }
    }

    private fun componentTable(bundle: ReplayCameraDefectBundle): String = buildString {
        appendLine("componentId\tcoreCount\tcores\tfootprintPixels\tpathCount\tobservedPathPoints\tinferredPathPoints")
        bundle.defectComponents.forEach { component ->
            appendLine(listOf(
                component.id,
                component.cores.size,
                component.cores.joinToString("|") { "${it.coreX},${it.coreY}" },
                component.footprintPixels.size,
                component.paths.size,
                component.paths.sumOf { path -> path.count { it.observed } },
                component.paths.sumOf { path -> path.count { !it.observed } }
            ).joinToString("\t"))
        }
    }

    private fun manualAnnotationTable(annotations: List<ReplayManualTrailAnnotation>): String = buildString {
        appendLine("trailId\tcenterline\tnote")
        annotations.sortedBy { it.id }.forEach { annotation ->
            appendLine(listOf(
                annotation.id,
                annotation.centerline.joinToString("|") { "${it.x},${it.y}" },
                annotation.note
            ).joinToString("\t"))
        }
    }

    private fun manualCorrespondenceTable(bundle: ReplayCameraDefectBundle): String = buildString {
        appendLine("trailId\tcomponentId\tiou\tmedianDistance\tmaximumDistance\torientationDifferenceDegrees\tcoveredFraction\tstatus\toverlapsConfirmedStar\tmedianChromaResidual\tmaximumChromaResidual\tmedianLuminanceResidual\tmaximumLuminanceResidual\tselectedBest")
        val best = bestManualCorrespondences(bundle.manualCorrespondences).associateBy { it.trailId }
        bundle.manualCorrespondences.sortedWith(compareBy<ReplayManualTrailCorrespondence> { it.trailId }.thenBy { it.componentId })
            .forEach { value ->
                appendLine(listOf(
                    value.trailId, value.componentId ?: "none", value.intersectionOverUnion,
                    value.medianDistance, value.maximumDistance,
                    value.orientationDifferenceDegrees ?: "undefined", value.coveredFraction,
                    value.status, value.overlapsConfirmedStar, value.medianChromaResidual,
                    value.maximumChromaResidual, value.medianLuminanceResidual,
                    value.maximumLuminanceResidual, best[value.trailId] == value
                ).joinToString("\t"))
            }
    }

    private fun bestManualCorrespondences(
        values: List<ReplayManualTrailCorrespondence>
    ): List<ReplayManualTrailCorrespondence> = values.groupBy { it.trailId }.values.map { candidates ->
        candidates.sortedWith(
            compareByDescending<ReplayManualTrailCorrespondence> { it.intersectionOverUnion }
                .thenBy { it.medianDistance }
                .thenBy { it.maximumDistance }
                .thenBy { it.componentId }
        ).first()
    }.sortedBy { it.trailId }

    private fun report(bundle: ReplayCameraDefectBundle, maximumRoundTripError: Double): String = buildString {
        val bestManual = bestManualCorrespondences(bundle.manualCorrespondences)
        appendLine("mode=replay_only_camera_space_defect_map")
        appendLine("integrationModified=false")
        appendLine("productionEnabled=false")
        appendLine("stage3Blocked=true")
        appendLine("manualReviewRequired=true")
        appendLine("thresholdManifestHash=${bundle.thresholdManifestHash}")
        appendLine("skyMaskVersion=${ReplayDefectThresholds.SKY_MASK_VERSION}")
        appendLine("skyMaskHash=${bundle.skyMaskHash}")
        appendLine("transformMaximumRoundTripError=$maximumRoundTripError")
        appendLine("acceptedFrameCount=${bundle.acceptedFrameIds.size}")
        appendLine("acceptedFrameIds=${bundle.acceptedFrameIds.sorted().joinToString("|")}")
        appendLine("rejectedFrameIds=${bundle.rejectedFrameIds.sorted().joinToString("|")}")
        appendLine("confirmedDefectCount=${bundle.confirmedDefects.size}")
        appendLine("rawConfirmedCoreCount=${bundle.confirmedDefects.size}")
        appendLine("uniqueConnectedComponentCount=${bundle.defectComponents.size}")
        appendLine("uniqueConnectedComponentFootprintPixels=${bundle.defectComponents.sumOf { it.footprintPixels.size }}")
        appendLine("rejectedStarLikeCandidateCount=${bundle.rejectedStarLikeCandidates.size}")
        appendLine("mapCoveragePercent=${bundle.coveragePercent}")
        appendLine("mapCoverageStatus=${bundle.coverageStatus}")
        appendLine("visibleTrailCount=${bundle.trails.size}")
        appendLine("explainedTrailCount=${bundle.explainedTrails.size}")
        appendLine("unexplainedTrailCount=${bundle.unexplainedTrails.size}")
        appendLine("missedTrailDiagnosticUncertainty=manual_review_pending")
        appendLine("confirmedVisuallyMatched=${bundle.confirmedDefects.count { it.matchedTrailIds.isNotEmpty() }}")
        appendLine("confirmedVisuallyUnmatched=${bundle.confirmedDefects.count { it.matchedTrailIds.isEmpty() }}")
        appendLine("stage1bPreviewTransform=encoded_srgb_channel_power_0.35_display_only")
        appendLine("stage1bMeasurementsSource=unchanged_clean_stack_pixels")
        appendLine("manualTrailCount=${bundle.manualTrails.size}")
        appendLine("manualAnnotationHash=${sha256(manualAnnotationTable(bundle.manualTrails).toByteArray(StandardCharsets.UTF_8))}")
        appendLine("manualExplained=${bestManual.count { it.status == ReplayManualTrailStatus.EXPLAINED }}")
        appendLine("manualPartiallyMatched=${bestManual.count { it.status == ReplayManualTrailStatus.PARTIALLY_MATCHED }}")
        appendLine("manualUnmatched=${bestManual.count { it.status == ReplayManualTrailStatus.UNMATCHED }}")
        appendLine("manualUncertain=${bestManual.count { it.status == ReplayManualTrailStatus.UNCERTAIN }}")
        appendLine("manualBestMatches=${bestManual.joinToString("|") { "${it.trailId}:${it.componentId ?: "none"}:${it.status}" }}")
        appendLine("stage3Decision=BLOCKED_PENDING_MANUAL_STAGE1B_REVIEW")
    }

    private fun writeReviewCrops(
        bundle: ReplayCameraDefectBundle,
        baseline: ArgbPixelImage,
        frames: List<ReplayDefectFrame>,
        outputRoot: Path,
        imageLoader: (ReplayDefectFrame) -> ArgbPixelImage
    ) {
        val trailAssociated = bundle.confirmedDefects.filter { it.matchedTrailIds.isNotEmpty() }
        val remaining = bundle.confirmedDefects.filter { it.matchedTrailIds.isEmpty() }
            .sortedWith(compareByDescending<ReplayConfirmedDefect> { it.observedSupport }.thenByDescending { it.coreMedianResidual })
            .take(50)
        val selected = (trailAssociated + bundle.rejectedStarLikeCandidates + remaining)
            .distinctBy { it.coreX to it.coreY }
        if (selected.isEmpty()) return
        Files.createDirectories(outputRoot)
        val byFrame = selected.groupBy { defect -> defect.observations.first().frameId }
        byFrame.forEach { (frameId, defects) ->
            val frame = frames.first { it.id == frameId }
            val source = imageLoader(frame)
            defects.forEach { defect ->
                val outputPoint = defect.eligiblePath.firstOrNull { it.frameId == frameId } ?: defect.eligiblePath.firstOrNull()
                val sourceCrop = crop(source, defect.coreX, defect.coreY, 64)
                val outputCrop = crop(
                    baseline,
                    outputPoint?.x?.roundToInt() ?: defect.coreX,
                    outputPoint?.y?.roundToInt() ?: defect.coreY,
                    64
                )
                val combined = combine(sourceCrop, outputCrop)
                val reason = defect.rejectionReason ?: if (defect.matchedTrailIds.isEmpty()) "confirmed" else "trail"
                writeImage(
                    outputRoot.resolve("${reason}-${defect.coreY.toString().padStart(4, '0')}-${defect.coreX.toString().padStart(4, '0')}.png"),
                    combined
                )
            }
        }
    }

    private fun crop(image: ArgbPixelImage, centerX: Int, centerY: Int, size: Int): ArgbPixelImage {
        val actual = minOf(size, image.width, image.height)
        val left = (centerX - actual / 2).coerceIn(0, image.width - actual)
        val top = (centerY - actual / 2).coerceIn(0, image.height - actual)
        val pixels = IntArray(actual * actual)
        for (row in 0 until actual) {
            image.pixels.copyInto(pixels, row * actual, (top + row) * image.width + left, (top + row) * image.width + left + actual)
        }
        return ArgbPixelImage(actual, actual, pixels)
    }

    private fun combine(first: ArgbPixelImage, second: ArgbPixelImage): ArgbPixelImage {
        val width = first.width + second.width
        val height = max(first.height, second.height)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        for (y in 0 until first.height) first.pixels.copyInto(pixels, y * width, y * first.width, (y + 1) * first.width)
        for (y in 0 until second.height) second.pixels.copyInto(pixels, y * width + first.width, y * second.width, (y + 1) * second.width)
        return ArgbPixelImage(width, height, pixels)
    }

    private fun writeImage(path: Path, image: ArgbPixelImage) {
        val buffered = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        check(ImageIO.write(buffered, "png", path.toFile())) { "PNG writer unavailable" }
        buffered.flush()
    }
}
