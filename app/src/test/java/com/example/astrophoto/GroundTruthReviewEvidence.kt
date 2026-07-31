package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class GroundTruthReviewEvidenceResult(
    val outputRoot: Path,
    val manifest: Path,
    val archive: Path,
    val candidateCount: Int,
    val fileCount: Int,
    val treeSha256: String,
    val manifestSha256: String,
    val archiveSha256: String,
    val maskActive: Boolean,
    val maskUnavailableReason: String?
)

private data class EvidenceCandidate(
    val candidate: Stage6CandidateDiagnostic,
    val label: ProvisionalSourceLabel,
    val selectedFrameIndices: List<Int>,
    val measurements: List<EvidenceFrameMeasurement>,
    val maskIntersection: Boolean
)

private data class EvidenceFrameMeasurement(
    val frameIndex: Int,
    val accepted: Boolean,
    val rejectionReason: String?,
    val transform: ReferenceToSourceTransform,
    val cameraExpectedX: Double,
    val cameraExpectedY: Double,
    val cameraObservedX: Double?,
    val cameraObservedY: Double?,
    val cameraResidual: Double?,
    val cameraContrast: Double,
    val cameraChroma: Double,
    val skyExpectedSourceX: Double,
    val skyExpectedSourceY: Double,
    val skyObservedSourceX: Double?,
    val skyObservedSourceY: Double?,
    val skyResidual: Double?,
    val skyContrast: Double,
    val skyChroma: Double
)

private data class PeakMeasurement(
    val x: Double?,
    val y: Double?,
    val contrast: Double,
    val chroma: Double
)

private data class StretchPoints(val black: Int, val white: Int)

/** Offline-only evidence renderer. It never mutates fixture ground truth or production output. */
internal class GroundTruthReviewEvidenceGenerator {
    fun generate(
        fixture: Stage6RegressionFixture,
        bundle: Stage6DiagnosticBundle,
        candidates: List<Stage6CandidateDiagnostic>,
        labels: List<ProvisionalSourceLabel>,
        outputRoot: Path
    ): GroundTruthReviewEvidenceResult {
        require(candidates.size == labels.size)
        require(bundle.width == fixture.frames.first().width)
        require(bundle.height == fixture.frames.first().height)
        recreateDirectory(outputRoot)

        val reviewCandidates = candidates.zip(labels)
            .filter { (_, label) ->
                label.annotationSource == GroundTruthAnnotationSource.AUTOMATIC &&
                    label.reviewStatus == GroundTruthReviewStatus.UNREVIEWED
            }
        require(reviewCandidates.size == EXPECTED_REVIEW_CANDIDATES) {
            "Expected $EXPECTED_REVIEW_CANDIDATES automatic/unreviewed candidates"
        }
        require(reviewCandidates.map { it.first.id }.distinct().size == reviewCandidates.size)

        val referenceFrame = bundle.frames[bundle.referenceFrameIndex]
        require(referenceFrame.cleanAccepted) { "Reference frame must be accepted" }
        require(referenceFrame.cleanTransform == ReferenceToSourceTransform.Identity) {
            "Reference coordinate mapping must be identity for the review fixture"
        }

        val maskState = validateMask(bundle.sensorDefectMask, bundle.width, bundle.height)
        val mask = maskState.first
        val maskUnavailableReason = maskState.second
        if (mask != null) {
            writeMaskGlobal(mask, outputRoot.resolve("sensor-mask-global.png"))
        } else {
            writeUnavailableImage(
                outputRoot.resolve("mask-data-unavailable.png"),
                "MASK DATA UNAVAILABLE",
                maskUnavailableReason ?: "unknown"
            )
        }

        val globalStretch = globalStretchPoints(fixture.frames)
        val evidence = reviewCandidates.map { (candidate, label) ->
            val selected = selectFrameIndices(bundle, candidate)
            val measurements = bundle.frames.map { frame ->
                frameMeasurement(
                    fixture.frames[frame.frameIndex - 1],
                    frame,
                    candidate
                )
            }
            val intersects = mask?.let { candidateIntersectsMask(candidate, it) } ?: false
            writeCandidateEvidence(
                fixture,
                bundle,
                candidate,
                selected,
                measurements,
                mask,
                globalStretch,
                outputRoot.resolve("candidates/${safeName(candidate.id)}")
            )
            EvidenceCandidate(candidate, label, selected, measurements, intersects)
        }

        writeDecisionsTemplate(evidence, outputRoot.resolve("review-decisions-template.csv"))
        writeCandidateSummary(
            evidence,
            bundle,
            globalStretch,
            mask,
            maskUnavailableReason,
            outputRoot.resolve("candidate-summary.csv")
        )
        writeReadme(
            evidence,
            bundle,
            globalStretch,
            mask,
            maskUnavailableReason,
            outputRoot.resolve("README_RU.md")
        )
        writeHtml(
            evidence,
            bundle,
            globalStretch,
            mask,
            maskUnavailableReason,
            outputRoot.resolve("index.html")
        )
        writeContactSheet(evidence, mask != null, outputRoot, outputRoot.resolve("contact-sheet.png"))

        val contentFiles = listFiles(outputRoot)
        val treeHash = treeSha256(outputRoot, contentFiles)
        val manifest = outputRoot.resolve("evidence-manifest.json")
        Files.writeString(
            manifest,
            evidenceManifest(
                fixture,
                evidence,
                globalStretch,
                mask,
                maskUnavailableReason,
                outputRoot,
                contentFiles,
                treeHash
            ),
            StandardCharsets.UTF_8
        )
        val archive = outputRoot.resolve("manual-review-bundle.zip")
        writeDeterministicZip(outputRoot, archive)
        val files = listFiles(outputRoot)
        require(files.none { forbiddenArchiveExtension(it) }) {
            "Evidence bundle contains a forbidden original/runtime artifact"
        }
        return GroundTruthReviewEvidenceResult(
            outputRoot = outputRoot,
            manifest = manifest,
            archive = archive,
            candidateCount = evidence.size,
            fileCount = files.size,
            treeSha256 = treeHash,
            manifestSha256 = sha256(manifest),
            archiveSha256 = sha256(archive),
            maskActive = mask != null,
            maskUnavailableReason = maskUnavailableReason
        )
    }

    private fun writeCandidateEvidence(
        fixture: Stage6RegressionFixture,
        bundle: Stage6DiagnosticBundle,
        candidate: Stage6CandidateDiagnostic,
        selectedFrameIndices: List<Int>,
        measurements: List<EvidenceFrameMeasurement>,
        mask: SensorDefectMask?,
        globalStretch: StretchPoints,
        output: Path
    ) {
        Files.createDirectories(output)
        val reference = cropRaw(bundle.referenceImage, candidate.referenceX, candidate.referenceY)
        val aligned = cropRaw(bundle.cleanStack, candidate.referenceX, candidate.referenceY)
        writePng(reference, output.resolve("raw-reference.png"))
        writePng(scaleNearest(reference, REVIEW_SCALE), output.resolve("raw-reference-nearest.png"))
        writePng(stretch(reference, globalStretch), output.resolve("raw-reference-global-stretch.png"))
        writePng(stretch(reference, localStretchPoints(reference)), output.resolve("raw-reference-local-stretch.png"))
        writePng(aligned, output.resolve("aligned-stack-raw.png"))
        writePng(stretch(aligned, localStretchPoints(aligned)), output.resolve("aligned-stack-local-stretch.png"))

        val cameraCrops = selectedFrameIndices.map { index ->
            cropRaw(fixture.frames[index - 1], candidate.referenceX, candidate.referenceY)
        }
        val skyCrops = selectedFrameIndices.map { index ->
            cropSky(
                fixture.frames[index - 1],
                candidate.referenceX,
                candidate.referenceY,
                bundle.frames[index - 1].cleanTransform
            )
        }
        val cameraLocal = cameraCrops.map { stretch(it, localStretchPoints(it)) }
        val skyLocal = skyCrops.map { stretch(it, localStretchPoints(it)) }
        val cameraMedian = medianCrop(cameraCrops)
        val skyMedian = medianCrop(skyCrops)
        writePng(cameraMedian, output.resolve("camera-median.png"))
        writePng(skyMedian, output.resolve("sky-median.png"))
        writePng(
            stretch(cameraMedian, localStretchPoints(cameraMedian)),
            output.resolve("camera-median-local-stretch.png")
        )
        writePng(
            stretch(skyMedian, localStretchPoints(skyMedian)),
            output.resolve("sky-median-local-stretch.png")
        )
        writePng(differenceCrop(cameraCrops, cameraMedian), output.resolve("camera-difference.png"))
        writePng(differenceCrop(skyCrops, skyMedian), output.resolve("sky-difference.png"))
        writePng(
            frameStrip("CAMERA SPACE — fixed source coordinate", selectedFrameIndices, cameraCrops, cameraLocal),
            output.resolve("camera-space-strip.png")
        )
        writePng(
            frameStrip("SKY SPACE — existing reference-to-source transforms", selectedFrameIndices, skyCrops, skyLocal),
            output.resolve("sky-space-strip.png")
        )
        writePng(blinkSprite(cameraLocal), output.resolve("camera-space-blink-sprite.png"))
        writePng(blinkSprite(skyLocal), output.resolve("sky-space-blink-sprite.png"))
        writePng(blinkSprite(cameraCrops), output.resolve("camera-space-raw-sprite.png"))
        writePng(blinkSprite(skyCrops), output.resolve("sky-space-raw-sprite.png"))

        if (mask != null) {
            writePng(maskCrop(mask, candidate.referenceX, candidate.referenceY), output.resolve("sensor-mask.png"))
        } else {
            writeUnavailableImage(
                output.resolve("mask-unavailable.png"),
                "MASK DATA UNAVAILABLE",
                "No validated fixture mask"
            )
        }
        writeTrackPlot(
            output.resolve("camera-track.png"),
            "Camera-space track — fixed source coordinate",
            measurements,
            cameraSpace = true
        )
        writeTrackPlot(
            output.resolve("sky-track.png"),
            "Sky-space track — expected source motion",
            measurements,
            cameraSpace = false
        )
        writeEvidenceSeriesPlot(
            output.resolve("contrast-by-frame.png"),
            "Local contrast by original frame index",
            measurements,
            { it.cameraContrast },
            { it.skyContrast }
        )
        writeEvidenceSeriesPlot(
            output.resolve("chroma-by-frame.png"),
            "Chroma residual by original frame index",
            measurements,
            { it.cameraChroma },
            { it.skyChroma }
        )
    }

    private fun selectFrameIndices(
        bundle: Stage6DiagnosticBundle,
        candidate: Stage6CandidateDiagnostic
    ): List<Int> {
        val accepted = bundle.frames.filter(Stage6FrameDiagnostic::cleanAccepted).map { it.frameIndex }
        require(accepted.size >= FRAME_SELECTION_COUNT)
        val selected = linkedSetOf<Int>()
        selected += accepted.first()
        selected += bundle.referenceFrameIndex + 1
        selected += accepted.last()
        evenlySample(candidate.cameraObservedFrameIndices.filter { it in accepted }, SUPPORT_SAMPLE_COUNT)
            .forEach(selected::add)
        evenlySample(candidate.skyObservedFrameIndices.filter { it in accepted }, SUPPORT_SAMPLE_COUNT)
            .forEach(selected::add)
        evenlySample(accepted, FRAME_SELECTION_COUNT).forEach {
            if (selected.size < FRAME_SELECTION_COUNT) selected += it
        }
        accepted.forEach {
            if (selected.size < FRAME_SELECTION_COUNT) selected += it
        }
        require(selected.size == FRAME_SELECTION_COUNT)
        require(selected.all { bundle.frames[it - 1].cleanAccepted })
        return selected.sorted()
    }

    private fun evenlySample(values: List<Int>, requested: Int): List<Int> {
        if (values.isEmpty() || requested <= 0) return emptyList()
        if (values.size <= requested) return values
        if (requested == 1) return listOf(values[values.size / 2])
        return (0 until requested).map { index ->
            val position = (index.toDouble() * (values.size - 1) / (requested - 1)).roundToInt()
            values[position]
        }.distinct()
    }

    private fun frameMeasurement(
        image: ArgbPixelImage,
        frame: Stage6FrameDiagnostic,
        candidate: Stage6CandidateDiagnostic
    ): EvidenceFrameMeasurement {
        val camera = measurePeak(image, candidate.referenceX, candidate.referenceY)
        val skyExpected = frame.cleanTransform.mapOutputToSource(
            candidate.referenceX.toFloat(),
            candidate.referenceY.toFloat()
        )
        val sky = measurePeak(image, skyExpected.x.toDouble(), skyExpected.y.toDouble())
        return EvidenceFrameMeasurement(
            frameIndex = frame.frameIndex,
            accepted = frame.cleanAccepted,
            rejectionReason = frame.cleanRejectionReason,
            transform = frame.cleanTransform,
            cameraExpectedX = candidate.referenceX,
            cameraExpectedY = candidate.referenceY,
            cameraObservedX = camera.x,
            cameraObservedY = camera.y,
            cameraResidual = residual(camera, candidate.referenceX, candidate.referenceY),
            cameraContrast = camera.contrast,
            cameraChroma = camera.chroma,
            skyExpectedSourceX = skyExpected.x.toDouble(),
            skyExpectedSourceY = skyExpected.y.toDouble(),
            skyObservedSourceX = sky.x,
            skyObservedSourceY = sky.y,
            skyResidual = residual(sky, skyExpected.x.toDouble(), skyExpected.y.toDouble()),
            skyContrast = sky.contrast,
            skyChroma = sky.chroma
        )
    }

    private fun residual(measurement: PeakMeasurement, expectedX: Double, expectedY: Double): Double? =
        if (measurement.x == null || measurement.y == null) null
        else hypot(measurement.x - expectedX, measurement.y - expectedY)

    private fun measurePeak(image: ArgbPixelImage, expectedX: Double, expectedY: Double): PeakMeasurement {
        var bestX = expectedX.roundToInt()
        var bestY = expectedY.roundToInt()
        var bestContrast = Double.NEGATIVE_INFINITY
        var bestChroma = 0.0
        var bestDistance = Double.POSITIVE_INFINITY
        for (y in expectedY.roundToInt() - PEAK_RADIUS..expectedY.roundToInt() + PEAK_RADIUS) {
            for (x in expectedX.roundToInt() - PEAK_RADIUS..expectedX.roundToInt() + PEAK_RADIUS) {
                if (x !in RING_RADIUS until image.width - RING_RADIUS ||
                    y !in RING_RADIUS until image.height - RING_RADIUS
                ) continue
                val local = localEvidence(image, x, y)
                val distance = hypot(x - expectedX, y - expectedY)
                if (
                    local.first > bestContrast ||
                    (local.first == bestContrast && distance < bestDistance)
                ) {
                    bestX = x
                    bestY = y
                    bestContrast = local.first
                    bestChroma = local.second
                    bestDistance = distance
                }
            }
        }
        if (!bestContrast.isFinite() || bestContrast < MIN_OBSERVATION_CONTRAST) {
            return PeakMeasurement(null, null, max(0.0, bestContrast.takeIf(Double::isFinite) ?: 0.0), 0.0)
        }
        return PeakMeasurement(bestX.toDouble(), bestY.toDouble(), bestContrast, bestChroma)
    }

    private fun localEvidence(image: ArgbPixelImage, x: Int, y: Int): Pair<Double, Double> {
        val luminance = IntArray(RING_SAMPLE_COUNT)
        val red = IntArray(RING_SAMPLE_COUNT)
        val green = IntArray(RING_SAMPLE_COUNT)
        val blue = IntArray(RING_SAMPLE_COUNT)
        var index = 0
        for (dy in -RING_RADIUS..RING_RADIUS) for (dx in -RING_RADIUS..RING_RADIUS) {
            if (kotlin.math.abs(dx) != RING_RADIUS && kotlin.math.abs(dy) != RING_RADIUS) continue
            val color = image.pixelAt(x + dx, y + dy)
            luminance[index] = pixelLuminance(color)
            red[index] = color ushr 16 and 0xFF
            green[index] = color ushr 8 and 0xFF
            blue[index] = color and 0xFF
            index++
        }
        luminance.sort()
        red.sort()
        green.sort()
        blue.sort()
        val color = image.pixelAt(x, y)
        val contrast = (pixelLuminance(color) - luminance[luminance.size / 2]).toDouble()
        val excess = intArrayOf(
            (color ushr 16 and 0xFF) - red[red.size / 2],
            (color ushr 8 and 0xFF) - green[green.size / 2],
            (color and 0xFF) - blue[blue.size / 2]
        )
        val chroma = (excess.maxOrNull()!! - excess.minOrNull()!!).toDouble()
        return contrast to chroma
    }

    private fun cropRaw(image: ArgbPixelImage, centerX: Double, centerY: Double): BufferedImage {
        val output = BufferedImage(CROP_SIZE, CROP_SIZE, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until CROP_SIZE) for (x in 0 until CROP_SIZE) {
            val sourceX = (centerX + x - CROP_RADIUS).roundToInt()
            val sourceY = (centerY + y - CROP_RADIUS).roundToInt()
            if (sourceX in 0 until image.width && sourceY in 0 until image.height) {
                output.setRGB(x, y, image.pixelAt(sourceX, sourceY))
            }
        }
        return output
    }

    private fun cropSky(
        image: ArgbPixelImage,
        centerX: Double,
        centerY: Double,
        transform: ReferenceToSourceTransform
    ): BufferedImage {
        val output = BufferedImage(CROP_SIZE, CROP_SIZE, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until CROP_SIZE) for (x in 0 until CROP_SIZE) {
            val mapped = transform.mapOutputToSource(
                (centerX + x - CROP_RADIUS).toFloat(),
                (centerY + y - CROP_RADIUS).toFloat()
            )
            val sourceX = mapped.x.roundToInt()
            val sourceY = mapped.y.roundToInt()
            if (sourceX in 0 until image.width && sourceY in 0 until image.height) {
                output.setRGB(x, y, image.pixelAt(sourceX, sourceY))
            }
        }
        return output
    }

    private fun globalStretchPoints(frames: List<ArgbPixelImage>): StretchPoints {
        val histogram = LongArray(256)
        frames.forEach { frame -> frame.pixels.forEach { histogram[pixelLuminance(it)]++ } }
        return stretchPoints(histogram, GLOBAL_BLACK_QUANTILE, GLOBAL_WHITE_QUANTILE)
    }

    private fun localStretchPoints(image: BufferedImage): StretchPoints {
        val histogram = LongArray(256)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            histogram[pixelLuminance(image.getRGB(x, y))]++
        }
        return stretchPoints(histogram, LOCAL_BLACK_QUANTILE, LOCAL_WHITE_QUANTILE)
    }

    private fun stretchPoints(histogram: LongArray, blackQuantile: Double, whiteQuantile: Double): StretchPoints {
        val total = histogram.sum()
        require(total > 0)
        fun percentile(quantile: Double): Int {
            val target = ((total - 1) * quantile).toLong()
            var cumulative = 0L
            histogram.forEachIndexed { value, count ->
                cumulative += count
                if (cumulative > target) return value
            }
            return 255
        }
        val black = percentile(blackQuantile)
        val white = max(black + 1, percentile(whiteQuantile)).coerceAtMost(255)
        return StretchPoints(black, white)
    }

    private fun stretch(source: BufferedImage, points: StretchPoints): BufferedImage {
        val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val color = source.getRGB(x, y)
            val luminance = pixelLuminance(color)
            val target = stretchChannel(luminance, points)
            val red = preserveHue(color ushr 16 and 0xFF, luminance, target)
            val green = preserveHue(color ushr 8 and 0xFF, luminance, target)
            val blue = preserveHue(color and 0xFF, luminance, target)
            output.setRGB(x, y, 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue)
        }
        return output
    }

    private fun preserveHue(channel: Int, luminance: Int, target: Int): Int = when {
        target == 0 || luminance == 0 -> 0
        else -> (channel.toLong() * target / luminance).toInt().coerceIn(0, 255)
    }

    private fun stretchChannel(value: Int, points: StretchPoints): Int =
        ((value - points.black) * 255 / (points.white - points.black))
            .coerceIn(0, 255)

    private fun medianCrop(crops: List<BufferedImage>): BufferedImage {
        require(crops.isNotEmpty() && crops.all { it.width == CROP_SIZE && it.height == CROP_SIZE })
        val output = BufferedImage(CROP_SIZE, CROP_SIZE, BufferedImage.TYPE_INT_ARGB)
        val red = IntArray(crops.size)
        val green = IntArray(crops.size)
        val blue = IntArray(crops.size)
        for (y in 0 until CROP_SIZE) for (x in 0 until CROP_SIZE) {
            crops.forEachIndexed { index, crop ->
                val color = crop.getRGB(x, y)
                red[index] = color ushr 16 and 0xFF
                green[index] = color ushr 8 and 0xFF
                blue[index] = color and 0xFF
            }
            red.sort()
            green.sort()
            blue.sort()
            val median = (crops.size - 1) / 2
            output.setRGB(
                x,
                y,
                0xFF000000.toInt() or (red[median] shl 16) or (green[median] shl 8) or blue[median]
            )
        }
        return output
    }

    private fun differenceCrop(crops: List<BufferedImage>, median: BufferedImage): BufferedImage {
        val output = BufferedImage(CROP_SIZE, CROP_SIZE, BufferedImage.TYPE_INT_ARGB)
        val deviations = IntArray(crops.size)
        for (y in 0 until CROP_SIZE) for (x in 0 until CROP_SIZE) {
            val reference = pixelLuminance(median.getRGB(x, y))
            crops.forEachIndexed { index, crop ->
                deviations[index] = kotlin.math.abs(pixelLuminance(crop.getRGB(x, y)) - reference)
            }
            deviations.sort()
            val value = deviations[(deviations.size - 1) / 2].coerceIn(0, 255)
            output.setRGB(x, y, 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value)
        }
        return output
    }

    private fun frameStrip(
        title: String,
        frameIndices: List<Int>,
        raw: List<BufferedImage>,
        local: List<BufferedImage>
    ): BufferedImage {
        require(frameIndices.size == raw.size && raw.size == local.size)
        val thumbnailSize = CROP_SIZE * STRIP_SCALE
        val left = 86
        val width = left + frameIndices.size * (thumbnailSize + STRIP_GAP) + STRIP_GAP
        val height = STRIP_TITLE_HEIGHT + 2 * (STRIP_LABEL_HEIGHT + thumbnailSize + STRIP_GAP)
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            configure(graphics)
            graphics.color = BACKGROUND
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
            graphics.drawString(title, 10, 21)
            listOf("RAW", "LOCAL").forEachIndexed { row, label ->
                val rowTop = STRIP_TITLE_HEIGHT + row * (STRIP_LABEL_HEIGHT + thumbnailSize + STRIP_GAP)
                graphics.color = if (row == 0) Color(190, 205, 220) else Color(250, 180, 70)
                graphics.font = Font(Font.MONOSPACED, Font.BOLD, 13)
                graphics.drawString(label, 10, rowTop + STRIP_LABEL_HEIGHT + thumbnailSize / 2)
                frameIndices.forEachIndexed { index, frameIndex ->
                    val x = left + index * (thumbnailSize + STRIP_GAP)
                    graphics.color = Color(190, 205, 220)
                    graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                    graphics.drawString("F${frameIndex.toString().padStart(2, '0')} A", x, rowTop + 14)
                    val source = if (row == 0) raw[index] else local[index]
                    val enlarged = scaleNearest(source, STRIP_SCALE)
                    graphics.drawImage(enlarged, x, rowTop + STRIP_LABEL_HEIGHT, null)
                    graphics.color = Color(80, 95, 115)
                    graphics.drawRect(x, rowTop + STRIP_LABEL_HEIGHT, thumbnailSize - 1, thumbnailSize - 1)
                }
            }
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun blinkSprite(crops: List<BufferedImage>): BufferedImage {
        val output = BufferedImage(CROP_SIZE * crops.size, CROP_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            crops.forEachIndexed { index, image -> graphics.drawImage(image, index * CROP_SIZE, 0, null) }
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun maskCrop(mask: SensorDefectMask, centerX: Double, centerY: Double): BufferedImage {
        val output = BufferedImage(CROP_SIZE, CROP_SIZE, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until CROP_SIZE) for (x in 0 until CROP_SIZE) {
            val sourceX = (centerX + x - CROP_RADIUS).roundToInt()
            val sourceY = (centerY + y - CROP_RADIUS).roundToInt()
            val masked = sourceX in 0 until mask.width && sourceY in 0 until mask.height &&
                mask.contains(sourceX, sourceY)
            output.setRGB(x, y, if (masked) Color.WHITE.rgb else Color.BLACK.rgb)
        }
        return output
    }

    private fun candidateIntersectsMask(candidate: Stage6CandidateDiagnostic, mask: SensorDefectMask): Boolean {
        for (y in candidate.boundingBox.top..candidate.boundingBox.bottom) {
            for (x in candidate.boundingBox.left..candidate.boundingBox.right) {
                if (x in 0 until mask.width && y in 0 until mask.height && mask.contains(x, y)) return true
            }
        }
        return false
    }

    private fun validateMask(
        mask: SensorDefectMask?,
        width: Int,
        height: Int
    ): Pair<SensorDefectMask?, String?> = when {
        mask == null -> null to "bundle_sensor_defect_mask_null"
        !mask.enabled -> null to "bundle_sensor_defect_mask_disabled:${mask.rejectionReason}"
        mask.width != width || mask.height != height ->
            null to "mask_dimensions_${mask.width}x${mask.height}_expected_${width}x$height"
        mask.regions.isEmpty() || mask.footprintPixels.isEmpty() -> null to "mask_has_no_confirmed_regions"
        else -> mask to null
    }

    private fun writeMaskGlobal(mask: SensorDefectMask, output: Path) {
        val image = BufferedImage(mask.width, mask.height, BufferedImage.TYPE_BYTE_BINARY)
        mask.footprintPixels.forEach { pixel -> image.setRGB(pixel.x, pixel.y, Color.WHITE.rgb) }
        writePng(image, output)
    }

    private fun writeUnavailableImage(output: Path, title: String, reason: String) {
        val image = BufferedImage(CROP_SIZE * REVIEW_SCALE, CROP_SIZE * REVIEW_SCALE, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.color = Color(20, 20, 20)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(255, 100, 100)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
            graphics.drawString(title, 12, image.height / 2 - 8)
            graphics.color = Color(210, 210, 210)
            graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
            graphics.drawString(reason.take(34), 12, image.height / 2 + 16)
        } finally {
            graphics.dispose()
        }
        writePng(image, output)
    }

    private fun writeTrackPlot(
        output: Path,
        title: String,
        measurements: List<EvidenceFrameMeasurement>,
        cameraSpace: Boolean
    ) {
        val expectedX = measurements.map {
            if (cameraSpace) it.cameraExpectedX else it.skyExpectedSourceX
        }
        val expectedY = measurements.map {
            if (cameraSpace) it.cameraExpectedY else it.skyExpectedSourceY
        }
        val observedX = measurements.map {
            if (cameraSpace) it.cameraObservedX else it.skyObservedSourceX
        }
        val observedY = measurements.map {
            if (cameraSpace) it.cameraObservedY else it.skyObservedSourceY
        }
        val residuals = measurements.map {
            if (cameraSpace) it.cameraResidual else it.skyResidual
        }
        val image = BufferedImage(PLOT_WIDTH, TRACK_PLOT_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.color = BACKGROUND
            graphics.fillRect(0, 0, image.width, image.height)
            drawPlotTitle(graphics, title)
            drawTrackPanel(graphics, measurements, expectedX, observedX, 55, 150, "source X")
            drawTrackPanel(graphics, measurements, expectedY, observedY, 220, 150, "source Y")
            drawTrackPanel(
                graphics,
                measurements,
                List(measurements.size) { 0.0 },
                residuals,
                385,
                150,
                "residual px"
            )
            drawLegend(graphics, 650, 25)
        } finally {
            graphics.dispose()
        }
        writePng(image, output)
    }

    private fun drawTrackPanel(
        graphics: Graphics2D,
        measurements: List<EvidenceFrameMeasurement>,
        expected: List<Double>,
        observed: List<Double?>,
        top: Int,
        height: Int,
        label: String
    ) {
        val left = PLOT_LEFT
        val width = PLOT_WIDTH - PLOT_LEFT - PLOT_RIGHT
        val values = expected + observed.filterNotNull()
        var minimum = values.minOrNull() ?: 0.0
        var maximum = values.maxOrNull() ?: 1.0
        if (maximum - minimum < 1.0) {
            minimum -= 0.5
            maximum += 0.5
        }
        val padding = (maximum - minimum) * 0.08
        minimum -= padding
        maximum += padding
        shadeRejectedFrames(graphics, measurements, left, top, width, height)
        graphics.color = Color(70, 85, 105)
        graphics.drawRect(left, top, width, height)
        graphics.color = Color(200, 210, 225)
        graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        graphics.drawString(label, 8, top + 14)
        fun px(index: Int): Int = left + index * width / max(1, measurements.size - 1)
        fun py(value: Double): Int = top + height - ((value - minimum) / (maximum - minimum) * height).roundToInt()
        graphics.color = Color(245, 180, 55)
        graphics.stroke = BasicStroke(1.5f)
        for (index in 1 until expected.size) {
            graphics.drawLine(px(index - 1), py(expected[index - 1]), px(index), py(expected[index]))
        }
        observed.forEachIndexed { index, value ->
            val x = px(index)
            if (value == null) {
                graphics.color = Color(150, 150, 150)
                graphics.drawLine(x - 3, top + height - 5, x + 3, top + height + 1)
                graphics.drawLine(x + 3, top + height - 5, x - 3, top + height + 1)
            } else {
                graphics.color = if (measurements[index].accepted) Color(70, 210, 255) else Color(150, 150, 150)
                graphics.fillOval(x - 3, py(value) - 3, 7, 7)
            }
        }
        drawFrameTicks(graphics, measurements, left, top + height, width)
    }

    private fun writeEvidenceSeriesPlot(
        output: Path,
        title: String,
        measurements: List<EvidenceFrameMeasurement>,
        cameraValue: (EvidenceFrameMeasurement) -> Double,
        skyValue: (EvidenceFrameMeasurement) -> Double
    ) {
        val camera = measurements.map(cameraValue)
        val sky = measurements.map(skyValue)
        val maximum = max(1.0, (camera + sky).maxOrNull() ?: 1.0)
        val image = BufferedImage(PLOT_WIDTH, SERIES_PLOT_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.color = BACKGROUND
            graphics.fillRect(0, 0, image.width, image.height)
            drawPlotTitle(graphics, title)
            val top = 65
            val height = 300
            val left = PLOT_LEFT
            val width = PLOT_WIDTH - PLOT_LEFT - PLOT_RIGHT
            shadeRejectedFrames(graphics, measurements, left, top, width, height)
            graphics.color = Color(70, 85, 105)
            graphics.drawRect(left, top, width, height)
            fun px(index: Int): Int = left + index * width / max(1, measurements.size - 1)
            fun py(value: Double): Int = top + height - (value / maximum * height).roundToInt()
            fun drawSeries(values: List<Double>, color: Color) {
                graphics.color = color
                graphics.stroke = BasicStroke(1.6f)
                for (index in 1 until values.size) {
                    graphics.drawLine(px(index - 1), py(values[index - 1]), px(index), py(values[index]))
                }
                values.forEachIndexed { index, value ->
                    graphics.color = if (measurements[index].accepted) color else Color(150, 150, 150)
                    graphics.fillOval(px(index) - 3, py(value) - 3, 7, 7)
                }
            }
            drawSeries(camera, Color(255, 125, 100))
            drawSeries(sky, Color(70, 210, 255))
            drawFrameTicks(graphics, measurements, left, top + height, width)
            graphics.color = Color(255, 125, 100)
            graphics.drawString("camera", 720, 30)
            graphics.color = Color(70, 210, 255)
            graphics.drawString("sky/aligned", 790, 30)
            graphics.color = Color(200, 210, 225)
            graphics.drawString("max=${decimal(maximum)}", 8, top + 14)
        } finally {
            graphics.dispose()
        }
        writePng(image, output)
    }

    private fun shadeRejectedFrames(
        graphics: Graphics2D,
        measurements: List<EvidenceFrameMeasurement>,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ) {
        val half = max(2, width / max(2, measurements.size - 1) / 2)
        measurements.forEachIndexed { index, measurement ->
            if (measurement.accepted) return@forEachIndexed
            val x = left + index * width / max(1, measurements.size - 1)
            graphics.color = Color(140, 45, 45, 80)
            graphics.fillRect(x - half, top, half * 2, height)
        }
    }

    private fun drawFrameTicks(
        graphics: Graphics2D,
        measurements: List<EvidenceFrameMeasurement>,
        left: Int,
        baseline: Int,
        width: Int
    ) {
        graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        measurements.forEachIndexed { index, measurement ->
            if (measurement.frameIndex == 1 || measurement.frameIndex % 5 == 0) {
                val x = left + index * width / max(1, measurements.size - 1)
                graphics.color = Color(150, 165, 180)
                graphics.drawString(measurement.frameIndex.toString(), x - 5, baseline + 14)
            }
        }
    }

    private fun drawPlotTitle(graphics: Graphics2D, title: String) {
        graphics.color = Color.WHITE
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
        graphics.drawString(title, 12, 24)
    }

    private fun drawLegend(graphics: Graphics2D, x: Int, y: Int) {
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        graphics.color = Color(245, 180, 55)
        graphics.drawString("expected", x, y)
        graphics.color = Color(70, 210, 255)
        graphics.drawString("observed accepted", x + 80, y)
        graphics.color = Color(150, 150, 150)
        graphics.drawString("rejected/missing", x + 205, y)
    }

    private fun writeDecisionsTemplate(evidence: List<EvidenceCandidate>, output: Path) {
        Files.writeString(
            output,
            buildString {
                appendLine("id,final_class,review_status,reviewed_by,reviewed_at,notes")
                evidence.forEach { appendLine(CsvCodec.encodeRow(listOf(it.candidate.id, "", "", "", "", ""))) }
            },
            StandardCharsets.UTF_8
        )
    }

    private fun writeCandidateSummary(
        evidence: List<EvidenceCandidate>,
        bundle: Stage6DiagnosticBundle,
        globalStretch: StretchPoints,
        mask: SensorDefectMask?,
        maskUnavailableReason: String?,
        output: Path
    ) {
        val header = listOf(
            "id", "proposal_class", "proposal_confidence", "annotation_source", "review_status",
            "x", "y", "coordinate_space", "camera_recurrence", "camera_support_frames",
            "sky_recurrence", "sky_support_frames", "shape", "footprint", "major_axis_px",
            "minor_axis_px", "elongation", "median_local_contrast", "chroma_residual",
            "camera_residual_px", "sky_residual_px", "selected_accepted_frames",
            "all_accepted_frames", "rejected_frames", "reference_original_frame_index",
            "crop_size", "global_black", "global_white", "mask_active", "mask_regions",
            "mask_intersection", "mask_unavailable_reason", "camera_track", "sky_track",
            "classification_reason", "strict_eligible"
        )
        val accepted = bundle.frames.filter(Stage6FrameDiagnostic::cleanAccepted).map { it.frameIndex }
        val rejected = bundle.frames.filterNot(Stage6FrameDiagnostic::cleanAccepted).map { it.frameIndex }
        val rows = evidence.map { item ->
            val candidate = item.candidate
            listOf(
                candidate.id,
                candidate.provisionalClass.name.lowercase(Locale.US),
                decimal(candidate.confidence),
                item.label.annotationSource.name.lowercase(Locale.US),
                item.label.reviewStatus.name.lowercase(Locale.US),
                decimal(candidate.referenceX),
                decimal(candidate.referenceY),
                item.label.coordinateSpace.name.lowercase(Locale.US),
                candidate.cameraSpaceRecurrence.toString(),
                candidate.cameraObservedFrameIndices.joinToString(";"),
                candidate.skySpaceRecurrence.toString(),
                candidate.skyObservedFrameIndices.joinToString(";"),
                candidate.shape.name.lowercase(Locale.US),
                "${candidate.boundingBox.width}x${candidate.boundingBox.height}",
                decimal(candidate.shapeMeasurement.majorAxisLength),
                decimal(candidate.shapeMeasurement.minorAxisLength),
                decimal(candidate.shapeMeasurement.elongation),
                decimal(candidate.medianLocalContrast),
                decimal(candidate.chromaResidual),
                decimal(candidate.cameraResidual),
                decimal(candidate.skyResidual),
                item.selectedFrameIndices.joinToString(";"),
                accepted.joinToString(";"),
                rejected.joinToString(";"),
                (bundle.referenceFrameIndex + 1).toString(),
                "${CROP_SIZE}x$CROP_SIZE",
                globalStretch.black.toString(),
                globalStretch.white.toString(),
                (mask != null).toString(),
                (mask?.regions?.size ?: 0).toString(),
                item.maskIntersection.toString(),
                maskUnavailableReason.orEmpty(),
                trackSeries(item.measurements, camera = true),
                trackSeries(item.measurements, camera = false),
                candidate.classificationReason,
                "false"
            )
        }
        Files.writeString(
            output,
            buildString {
                appendLine(CsvCodec.encodeRow(header))
                rows.forEach { appendLine(CsvCodec.encodeRow(it)) }
            },
            StandardCharsets.UTF_8
        )
    }

    private fun trackSeries(measurements: List<EvidenceFrameMeasurement>, camera: Boolean): String =
        measurements.joinToString(";") { measurement ->
            val x = if (camera) measurement.cameraObservedX else measurement.skyObservedSourceX
            val y = if (camera) measurement.cameraObservedY else measurement.skyObservedSourceY
            val residual = if (camera) measurement.cameraResidual else measurement.skyResidual
            "${measurement.frameIndex}:${if (measurement.accepted) "A" else "R"}:" +
                "${x?.let(::decimal) ?: "missing"}:${y?.let(::decimal) ?: "missing"}:" +
                (residual?.let(::decimal) ?: "missing")
        }

    private fun writeReadme(
        evidence: List<EvidenceCandidate>,
        bundle: Stage6DiagnosticBundle,
        globalStretch: StretchPoints,
        mask: SensorDefectMask?,
        maskUnavailableReason: String?,
        output: Path
    ) {
        val maskText = if (mask == null) {
            "MASK DATA UNAVAILABLE: ${maskUnavailableReason ?: "unknown"}. Отсутствие локального пересечения ничего не доказывает."
        } else {
            "Активна реальная automatic mask fixture: ${mask.regions.size} регионов, " +
                "${mask.footprintPixels.size} footprint pixels, ${mask.width}×${mask.height}. " +
                "Она построена из persistent RGB/chroma observations и существующих transforms."
        }
        Files.writeString(
            output,
            """# AstroPhoto — evidence bundle для ручной проверки

Здесь находятся только ${evidence.size} кандидатов `automatic/unreviewed`. Никакой кандидат не подтверждён автоматически, ground truth не изменён.

## Mask

$maskText

Reference mapping доказан identity-transform кадра ${bundle.referenceFrameIndex + 1}; mask и fixture имеют одну геометрию ${bundle.width}×${bundle.height}. `sensor-mask.png` — бинарная source/camera-space mask без наложения на исходные пиксели.

## Яркость и геометрия

- `raw-reference.png` и `aligned-stack-raw.png` содержат реальные пиксели без текста, окружностей и interpolation.
- Размер измерительного crop: ${CROP_SIZE}×${CROP_SIZE}, центр — точные reference coordinates с nearest integer sampling.
- `raw-reference-global-stretch.png`: общие точки black=${globalStretch.black}, white=${globalStretch.white} для всей 30-кадровой серии. Этот режим допускает сравнение яркости.
- `*-local-stretch.png`: одинаковый алгоритм, но отдельные точки для каждого crop. Он показывает слабую структуру, но яркость разных crops физически сравнивать нельзя.
- `camera/sky-median.png` остаются raw; отдельные `camera/sky-median-local-stretch.png` предназначены только для видимости и именно они показаны на contact sheet.
- `camera/sky-difference.png`: median absolute luminance deviation относительно соответствующего median crop.

## Strips и blink

Для каждого кандидата выбраны ${FRAME_SELECTION_COUNT} accepted original frames: первый accepted, reference, последний accepted, support frames и равномерная выборка. Camera и sky используют один порядок. Sky crops применяют неизменённый `ReferenceToSourceTransform`; rejected frames в strips/blink не включаются.

`camera-space-strip.png` держит фиксированную source coordinate. `sky-space-strip.png` отображает один reference/sky coordinate через существующий transform. В `index.html` blink работает с deterministic PNG sprites при 3 fps, без motion interpolation; номер кадра расположен вне crop, доступны pause/previous/next.

`camera/sky-space-raw-sprite.png` сохраняют те же кадры без stretch и используются для проверки точного coordinate mapping; интерактивный blink показывает отдельные local-stretched sprites.

## Решения

Кнопки в HTML сохраняют решения только в памяти страницы. `reviewed_at` создаётся только при явном выборе класса. Кнопка экспорта скачивает CSV:

`id,final_class,review_status,reviewed_by,reviewed_at,notes`

Mapping: STAR → `star,confirmed`; SENSOR_DEFECT → `sensor_defect,confirmed`; UNCERTAIN → `uncertain,needs_review`; REJECTED → `uncertain,rejected`. Незаполненные строки остаются пустыми.

Автоматические proposals и графики являются evidence, не ground truth. Неясные случаи оставляйте `uncertain/needs_review`.
""".trimIndent() + "\n",
            StandardCharsets.UTF_8
        )
    }

    private fun writeHtml(
        evidence: List<EvidenceCandidate>,
        bundle: Stage6DiagnosticBundle,
        globalStretch: StretchPoints,
        mask: SensorDefectMask?,
        maskUnavailableReason: String?,
        output: Path
    ) {
        val idsJson = evidence.joinToString(",") { json(it.candidate.id) }
        val maskSummary = if (mask == null) {
            "MASK DATA UNAVAILABLE: ${html(maskUnavailableReason ?: "unknown")}"
        } else {
            "ACTIVE: ${mask.regions.size} regions / ${mask.footprintPixels.size} footprint pixels / ${mask.width}×${mask.height}"
        }
        Files.writeString(
            output,
            buildString {
                appendLine("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
                appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                appendLine("<title>AstroPhoto manual review evidence</title>")
                appendLine("""<style>
body{margin:0;background:#090d12;color:#e8edf2;font:15px/1.45 system-ui,sans-serif}main{max-width:1680px;margin:auto;padding:24px}a{color:#7dd3fc}
.warning{padding:14px;border:2px solid #e0a000;background:#302400;border-radius:8px}.mask{padding:12px;border:1px solid #64748b;background:#111a23}
.candidate{margin:24px 0;padding:18px;border:1px solid #405065;border-radius:10px;background:#111923}.meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:7px}
.meta span{padding:7px;background:#0a1119;border-radius:5px;overflow-wrap:anywhere}.gallery{display:grid;grid-template-columns:repeat(auto-fit,minmax(225px,1fr));gap:12px}
figure{margin:0;padding:8px;background:#06090d;border-radius:7px}figcaption{padding-top:6px;color:#c9d3df}.crop{width:252px;height:252px;image-rendering:pixelated;max-width:100%}.wide{max-width:100%;height:auto}
.blink{padding:10px;background:#080c11;border:1px solid #334155}.blink canvas{width:252px;height:252px;image-rendering:pixelated;background:#000;display:block}.controls button,.decision button,#download{margin:4px;padding:8px 11px;border:1px solid #64748b;border-radius:5px;background:#1d2a38;color:#fff;cursor:pointer}
.decision{margin-top:14px;padding:14px;border:2px solid #f59e0b;background:#251d0d}.decision button.active{background:#c47b00;border-color:#ffd166}.decision input,.decision textarea{width:min(100%,600px);box-sizing:border-box;margin:5px 0;padding:7px;background:#071018;color:#fff;border:1px solid #526174}.muted{color:#aab6c4}.raw{color:#70d6ff}.global{color:#94e59a}.local{color:#ffbf69}
</style></head><body><main>""")
                appendLine("<h1>AstroPhoto — manual-review evidence (${evidence.size})</h1>")
                appendLine("<p class=\"warning\"><b>Automatic proposals не являются ground truth.</b> HTML не пишет в repository и не импортирует решения.</p>")
                appendLine("<p class=\"mask\"><b>Sensor mask:</b> $maskSummary</p>")
                appendLine("<p>Global stretch всей серии: black=${globalStretch.black}, white=${globalStretch.white}. Local stretch нельзя использовать для сравнения абсолютной яркости.</p>")
                appendLine("<p><a href=\"README_RU.md\">README</a> · <a href=\"candidate-summary.csv\">candidate summary</a> · <a href=\"contact-sheet.png\">contact sheet</a> · <button id=\"download\" type=\"button\" onclick=\"downloadCsv()\">Скачать review-decisions-template.csv</button></p>")
                evidence.forEachIndexed { index, item ->
                    val candidate = item.candidate
                    val id = safeName(candidate.id)
                    val root = "candidates/$id"
                    val frames = item.selectedFrameIndices.joinToString(",")
                    appendLine("<section class=\"candidate\" id=\"$id\"><h2>${index + 1}. ${html(candidate.id)}</h2>")
                    appendLine("<div class=\"meta\">")
                    appendLine("<span>proposal=${candidate.provisionalClass.name.lowercase()} / confidence=${decimal(candidate.confidence)}</span>")
                    appendLine("<span>source=${item.label.annotationSource.name.lowercase()} / status=${item.label.reviewStatus.name.lowercase()}</span>")
                    appendLine("<span>x=${decimal(candidate.referenceX)}, y=${decimal(candidate.referenceY)} / ${item.label.coordinateSpace.name.lowercase()}</span>")
                    appendLine("<span>camera=${candidate.cameraSpaceRecurrence}/30 / sky=${candidate.skySpaceRecurrence}/30</span>")
                    appendLine("<span>shape=${candidate.shape.name.lowercase()} / footprint=${candidate.boundingBox.width}×${candidate.boundingBox.height} / elongation=${decimal(candidate.shapeMeasurement.elongation)}</span>")
                    appendLine("<span>mask active=${mask != null} / intersection=${item.maskIntersection}</span>")
                    appendLine("<span>strict eligible=false</span><span>selected accepted frames=$frames</span></div>")
                    appendLine("<p>${html(candidate.classificationReason)}</p>")
                    appendLine("<div class=\"gallery\">")
                    figure(this, "$root/raw-reference.png", "Raw reference 63×63 — без overlay", "crop raw")
                    figure(this, "$root/raw-reference-nearest.png", "Raw reference nearest ×4", "crop raw")
                    figure(this, "$root/raw-reference-global-stretch.png", "Global stretch — brightness comparable", "crop global")
                    figure(this, "$root/raw-reference-local-stretch.png", "Local stretch — visibility only", "crop local")
                    figure(this, "$root/aligned-stack-raw.png", "Aligned clean stack raw", "crop raw")
                    figure(this, "$root/aligned-stack-local-stretch.png", "Aligned clean stack local stretch", "crop local")
                    figure(this, "$root/camera-median.png", "Camera-space median", "crop")
                    figure(this, "$root/sky-median.png", "Sky-space median", "crop")
                    figure(this, "$root/camera-median-local-stretch.png", "Camera median local stretch — visibility only", "crop local")
                    figure(this, "$root/sky-median-local-stretch.png", "Sky median local stretch — visibility only", "crop local")
                    figure(this, "$root/camera-difference.png", "Camera median absolute difference", "crop")
                    figure(this, "$root/sky-difference.png", "Sky median absolute difference", "crop")
                    figure(
                        this,
                        "$root/${if (mask == null) "mask-unavailable.png" else "sensor-mask.png"}",
                        if (mask == null) "MASK DATA UNAVAILABLE" else "Binary source-space sensor mask",
                        "crop"
                    )
                    appendLine("</div>")
                    appendLine("<h3>Frame strips — тот же порядок accepted original frames</h3>")
                    appendLine("<figure><img class=\"wide\" src=\"$root/camera-space-strip.png\" alt=\"camera strip\"><figcaption>Camera-space: fixed source coordinate; raw + local rows.</figcaption></figure>")
                    appendLine("<figure><img class=\"wide\" src=\"$root/sky-space-strip.png\" alt=\"sky strip\"><figcaption>Sky-space: existing reference-to-source transforms; raw + local rows.</figcaption></figure>")
                    appendLine("<div class=\"gallery\">")
                    blink(this, "camera-$id", "$root/camera-space-blink-sprite.png", frames, "Camera-space blink (local stretch)")
                    blink(this, "sky-$id", "$root/sky-space-blink-sprite.png", frames, "Sky-space blink (local stretch)")
                    appendLine("</div><h3>Track diagnostics</h3><div class=\"gallery\">")
                    figure(this, "$root/camera-track.png", "Observed source x/y and camera residual", "wide")
                    figure(this, "$root/sky-track.png", "Expected sky motion, observed source x/y and residual", "wide")
                    figure(this, "$root/contrast-by-frame.png", "Camera/sky contrast; rejected frames shaded", "wide")
                    figure(this, "$root/chroma-by-frame.png", "Camera/sky chroma; rejected frames shaded", "wide")
                    appendLine("</div>")
                    appendLine("<div class=\"decision\"><h3>Решение человека</h3>")
                    listOf("STAR", "SENSOR_DEFECT", "UNCERTAIN", "REJECTED").forEach { decision ->
                        appendLine("<button type=\"button\" data-id=\"$id\" data-decision=\"$decision\" onclick=\"choose('${js(id)}','$decision',this)\">$decision</button>")
                    }
                    appendLine("<button type=\"button\" onclick=\"clearDecision('${js(id)}')\">ОЧИСТИТЬ</button><br>")
                    appendLine("<label>Reviewer<br><input id=\"reviewer-$id\" autocomplete=\"name\"></label><br>")
                    appendLine("<label>Notes<br><textarea id=\"notes-$id\" rows=\"3\"></textarea></label>")
                    appendLine("<p id=\"status-$id\" class=\"muted\">Решение не выбрано.</p></div></section>")
                }
                appendLine("<script>")
                appendLine("const ids=[$idsJson]; const decisions={};")
                appendLine("const mapping={STAR:['star','confirmed'],SENSOR_DEFECT:['sensor_defect','confirmed'],UNCERTAIN:['uncertain','needs_review'],REJECTED:['uncertain','rejected']};")
                appendLine("function choose(id,key,button){const m=mapping[key];decisions[id]={final_class:m[0],review_status:m[1],reviewed_at:new Date().toISOString()};document.querySelectorAll('[data-id=\"'+id+'\"]').forEach(x=>x.classList.remove('active'));button.classList.add('active');document.getElementById('status-'+id).textContent=key+' · '+decisions[id].reviewed_at;}")
                appendLine("function clearDecision(id){delete decisions[id];document.querySelectorAll('[data-id=\"'+id+'\"]').forEach(x=>x.classList.remove('active'));document.getElementById('status-'+id).textContent='Решение не выбрано.';}")
                appendLine("function csv(v){v=String(v??'');return /[\",\\r\\n]/.test(v)?'\"'+v.replaceAll('\\\"','\\\"\\\"')+'\"':v;}")
                appendLine("function downloadCsv(){const rows=[['id','final_class','review_status','reviewed_by','reviewed_at','notes']];ids.forEach(id=>{const d=decisions[id];if(!d){rows.push([id,'','','','','']);return;}rows.push([id,d.final_class,d.review_status,document.getElementById('reviewer-'+id).value,d.reviewed_at,document.getElementById('notes-'+id).value]);});const text=rows.map(r=>r.map(csv).join(',')).join('\\n')+'\\n';const url=URL.createObjectURL(new Blob([text],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='review-decisions-template.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),0);}")
                appendLine("document.querySelectorAll('.blink').forEach(root=>{const image=new Image(),canvas=root.querySelector('canvas'),ctx=canvas.getContext('2d'),frames=root.dataset.frames.split(','),count=frames.length;let index=0,running=true;ctx.imageSmoothingEnabled=false;function draw(){ctx.clearRect(0,0,63,63);ctx.drawImage(image,index*63,0,63,63,0,0,63,63);root.querySelector('.frame-label').textContent='original frame '+frames[index]+' · accepted';}image.onload=draw;image.src=root.dataset.sprite;root.querySelector('.pause').onclick=()=>{running=!running;root.querySelector('.pause').textContent=running?'PAUSE':'PLAY';};root.querySelector('.prev').onclick=()=>{index=(index+count-1)%count;draw();};root.querySelector('.next').onclick=()=>{index=(index+1)%count;draw();};setInterval(()=>{if(running){index=(index+1)%count;draw();}},333);});")
                appendLine("</script></main></body></html>")
            },
            StandardCharsets.UTF_8
        )
    }

    private fun figure(builder: StringBuilder, source: String, caption: String, css: String) {
        builder.appendLine("<figure><img class=\"$css\" src=\"$source\" alt=\"${html(caption)}\"><figcaption>${html(caption)}</figcaption></figure>")
    }

    private fun blink(builder: StringBuilder, id: String, sprite: String, frames: String, caption: String) {
        builder.appendLine("<figure class=\"blink\" id=\"$id\" data-sprite=\"$sprite\" data-frames=\"$frames\"><canvas width=\"63\" height=\"63\"></canvas><figcaption>${html(caption)}<br><span class=\"frame-label\"></span></figcaption><div class=\"controls\"><button class=\"prev\" type=\"button\">PREV</button><button class=\"pause\" type=\"button\">PAUSE</button><button class=\"next\" type=\"button\">NEXT</button></div></figure>")
    }

    private fun writeContactSheet(
        evidence: List<EvidenceCandidate>,
        maskActive: Boolean,
        root: Path,
        output: Path
    ) {
        val width = 2040
        val rowHeight = 270
        val header = 80
        val image = BufferedImage(width, header + evidence.size * rowHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.color = BACKGROUND
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
            graphics.drawString("AstroPhoto urban-window-30 — raw evidence for 18 automatic/unreviewed candidates", 16, 28)
            val columns = listOf(
                480 to "RAW", 620 to "GLOBAL", 760 to "LOCAL", 900 to "CAM MED LOCAL",
                1040 to "SKY MED LOCAL", 1180 to "CAM STRIP LOCAL", 1460 to "SKY STRIP LOCAL", 1740 to "MASK"
            )
            graphics.font = Font(Font.MONOSPACED, Font.BOLD, 12)
            columns.forEach { (x, title) -> graphics.drawString(title, x, 62) }
            evidence.forEachIndexed { index, item ->
                val top = header + index * rowHeight
                graphics.color = if (index % 2 == 0) Color(18, 27, 37) else Color(12, 19, 27)
                graphics.fillRect(0, top, width, rowHeight)
                graphics.color = Color(70, 85, 105)
                graphics.drawLine(0, top, width, top)
                graphics.color = Color.WHITE
                graphics.font = Font(Font.MONOSPACED, Font.BOLD, 13)
                graphics.drawString("${index + 1}. ${item.candidate.id}", 16, top + 24)
                graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                val lines = listOf(
                    "proposal=${item.candidate.provisionalClass.name.lowercase()} conf=${decimal(item.candidate.confidence)}",
                    "camera=${item.candidate.cameraSpaceRecurrence}/30 sky=${item.candidate.skySpaceRecurrence}/30",
                    "shape=${item.candidate.shape.name.lowercase()} elong=${decimal(item.candidate.shapeMeasurement.elongation)}",
                    "contrast=${decimal(item.candidate.medianLocalContrast)} chroma=${decimal(item.candidate.chromaResidual)}",
                    "mask active=$maskActive intersect=${item.maskIntersection}",
                    "frames=${item.selectedFrameIndices.joinToString()}"
                )
                lines.forEachIndexed { line, value ->
                    graphics.color = if (line == 4 && !maskActive) Color(255, 100, 100) else Color(195, 207, 220)
                    graphics.drawString(value, 16, top + 50 + line * 20)
                }
                val directory = root.resolve("candidates/${safeName(item.candidate.id)}")
                val cropNames = listOf(
                    "raw-reference.png", "raw-reference-global-stretch.png", "raw-reference-local-stretch.png",
                    "camera-median-local-stretch.png", "sky-median-local-stretch.png"
                )
                cropNames.forEachIndexed { column, name ->
                    val crop = ImageIO.read(directory.resolve(name).toFile())
                    graphics.drawImage(scaleNearest(crop, 2), 480 + column * 140, top + 45, null)
                }
                listOf("camera-space-strip.png", "sky-space-strip.png").forEachIndexed { column, name ->
                    val strip = ImageIO.read(directory.resolve(name).toFile())
                    val fragment = strip.getSubimage(
                        min(86, strip.width - 1),
                        min(208, strip.height - 1),
                        min(252, strip.width - min(86, strip.width - 1)),
                        min(126, strip.height - min(208, strip.height - 1))
                    )
                    graphics.drawImage(fragment, 1180 + column * 280, top + 45, null)
                }
                val maskName = if (maskActive) "sensor-mask.png" else "mask-unavailable.png"
                val maskImage = ImageIO.read(directory.resolve(maskName).toFile())
                val displayedMask = if (maskImage.width == CROP_SIZE) scaleNearest(maskImage, 2) else maskImage
                graphics.drawImage(displayedMask, 1740, top + 45, null)
            }
        } finally {
            graphics.dispose()
        }
        writePng(image, output)
    }

    private fun evidenceManifest(
        fixture: Stage6RegressionFixture,
        evidence: List<EvidenceCandidate>,
        globalStretch: StretchPoints,
        mask: SensorDefectMask?,
        maskUnavailableReason: String?,
        root: Path,
        files: List<Path>,
        treeHash: String
    ): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": \"astrophoto.ground-truth-review-evidence/1\",")
        appendLine("  \"fixture\": ${json(fixture.name)},")
        appendLine("  \"candidateCount\": ${evidence.size},")
        appendLine("  \"cropSize\": $CROP_SIZE,")
        appendLine("  \"selectedFrameCount\": $FRAME_SELECTION_COUNT,")
        appendLine("  \"globalStretchBlack\": ${globalStretch.black},")
        appendLine("  \"globalStretchWhite\": ${globalStretch.white},")
        appendLine("  \"maskActive\": ${mask != null},")
        appendLine("  \"maskUnavailableReason\": ${json(maskUnavailableReason.orEmpty())},")
        appendLine("  \"maskRegionCount\": ${mask?.regions?.size ?: 0},")
        appendLine("  \"maskFootprintPixelCount\": ${mask?.footprintPixels?.size ?: 0},")
        appendLine("  \"treeSha256\": ${json(treeHash)},")
        appendLine("  \"candidateOrder\": [${evidence.joinToString(",") { json(it.candidate.id) }}],")
        appendLine("  \"files\": [")
        files.forEachIndexed { index, file ->
            val relative = root.relativize(file).toString().replace('\\', '/')
            append("    {\"path\": ${json(relative)}, \"bytes\": ${Files.size(file)}, \"sha256\": ${json(sha256(file))}}")
            appendLine(if (index == files.lastIndex) "" else ",")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun writeDeterministicZip(root: Path, output: Path) {
        val files = listFiles(root).filter { it != output }.sortedBy {
            root.relativize(it).toString().replace('\\', '/')
        }
        ZipOutputStream(Files.newOutputStream(output)).use { archive ->
            archive.setLevel(9)
            files.forEach { file ->
                val relative = root.relativize(file).toString().replace('\\', '/')
                val entry = ZipEntry(relative).apply {
                    time = 0L
                    lastModifiedTime = FileTime.fromMillis(0L)
                    lastAccessTime = FileTime.fromMillis(0L)
                    creationTime = FileTime.fromMillis(0L)
                }
                archive.putNextEntry(entry)
                Files.copy(file, archive)
                archive.closeEntry()
            }
        }
    }

    private fun treeSha256(root: Path, files: List<Path>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { root.relativize(it).toString().replace('\\', '/') }.forEach { file ->
            val relative = root.relativize(file).toString().replace('\\', '/')
            digest.update(relative.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(sha256(file).toByteArray(StandardCharsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun scaleNearest(source: BufferedImage, scale: Int): BufferedImage {
        val output = BufferedImage(source.width * scale, source.height * scale, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            graphics.drawImage(source, 0, 0, output.width, output.height, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun writePng(image: BufferedImage, output: Path) {
        Files.createDirectories(checkNotNull(output.parent))
        check(ImageIO.write(image, "png", output.toFile()))
    }

    private fun configure(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun recreateDirectory(output: Path) {
        if (Files.exists(output)) {
            Files.walk(output).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        Files.createDirectories(output)
    }

    private fun listFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile).sorted().toList()
    }

    private fun forbiddenArchiveExtension(path: Path): Boolean =
        path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.US) in
            setOf("jpg", "jpeg", "dng", "apk")

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun decimal(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

    private fun html(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun js(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val EXPECTED_REVIEW_CANDIDATES = 18
        internal const val CROP_SIZE = 63
        internal const val FRAME_SELECTION_COUNT = 12
        private const val CROP_RADIUS = CROP_SIZE / 2
        private const val REVIEW_SCALE = 4
        private const val STRIP_SCALE = 2
        private const val STRIP_GAP = 8
        private const val STRIP_TITLE_HEIGHT = 34
        private const val STRIP_LABEL_HEIGHT = 20
        private const val SUPPORT_SAMPLE_COUNT = 3
        private const val PEAK_RADIUS = 3
        private const val RING_RADIUS = 2
        private const val RING_SAMPLE_COUNT = 16
        private const val MIN_OBSERVATION_CONTRAST = 3.0
        private const val GLOBAL_BLACK_QUANTILE = 0.005
        private const val GLOBAL_WHITE_QUANTILE = 0.995
        private const val LOCAL_BLACK_QUANTILE = 0.01
        private const val LOCAL_WHITE_QUANTILE = 0.999
        private const val PLOT_WIDTH = 960
        private const val TRACK_PLOT_HEIGHT = 560
        private const val SERIES_PLOT_HEIGHT = 410
        private const val PLOT_LEFT = 90
        private const val PLOT_RIGHT = 35
        private val BACKGROUND = Color(9, 14, 20)
    }
}
