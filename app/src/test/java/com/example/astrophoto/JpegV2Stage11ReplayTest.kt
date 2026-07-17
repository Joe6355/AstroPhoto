package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.registration.ExpectedSequenceMotionModel
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionRegistrationRefiner
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import com.example.astrophoto.processing.jpeg.v2.registration.OrderedRegistration
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.example.astrophoto.processing.jpeg.v2.registration.TransformSequenceValidator
import com.example.astrophoto.processing.jpeg.v2.registration.scaledToFullResolution
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage11ReplayTest {
    @Test fun realSessionFullResolutionReplayWhenZipIsProvided() {
        val zipPath = System.getenv(REPLAY_ENV)?.takeIf { it.isNotBlank() }?.let(Path::of) ?: return
        assertTrue("Replay ZIP does not exist: $zipPath", Files.isRegularFile(zipPath))
        val replayRoot = Path.of("build/tmp/stage11-full-resolution-replay")
        Files.createDirectories(replayRoot)
        val extracted = Files.createTempDirectory(replayRoot, "session-")
        try {
            extractReadOnly(zipPath, extracted)
            val jpegPaths = Files.walk(extracted).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().lowercase().matches(Regex(".*_\\d{3}\\.jpe?g"))
                }.sorted().toList()
            }
            assertTrue("No original JPEG frames in replay ZIP", jpegPaths.size >= 2)
            val reportReference = Files.walk(extracted).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".processing.json", ignoreCase = true)
                }.map { path ->
                    Regex("\"selectedReference\"\\s*:\\s*\"([^\"]+)\"")
                        .find(Files.readString(path))?.groupValues?.get(1)
                }.filter { it != null }.findFirst().orElse(null)
            }
            val analyzer = JpegFrameAnalyzer()
            val maskEstimator = SkyMaskEstimator()
            val raw = jpegPaths.mapIndexed { order, path ->
                val thumbnail = readThumbnail(path)
                val mask = maskEstimator.estimate(thumbnail)
                val fileName = path.fileName.toString()
                val capture = captureIndex(fileName) ?: order + 1
                ReplayAnalysis(path, capture, analyzer.analyze(fileName, fileName, thumbnail, mask))
            }.sortedBy { it.captureIndex }
            val artifactAnalyzer = StaticArtifactAnalyzer()
            val artifactMask = artifactAnalyzer.analyze(
                raw.map { ArtifactFrameObservation(it.analysis.id, it.analysis.stars) },
                raw.first().analysis.width,
                raw.first().analysis.height
            )
            val filtered = raw.map { value ->
                value.copy(analysis = artifactAnalyzer.excludeFrom(value.analysis, artifactMask))
            }
            val sequence = filtered.map {
                TemporalFeatureFrame(it.analysis.id, it.captureIndex, it.analysis.stars)
            }
            val reference = filtered.firstOrNull { it.analysis.id == reportReference }
                ?: filtered.firstOrNull { it.analysis.id.contains("_009.", ignoreCase = true) }
                ?: filtered[filtered.size / 2]
            assertTrue("Expected reference frame 009, got ${reference.analysis.id}",
                reference.analysis.id.contains("_009.", ignoreCase = true))
            val stage10 = SequenceAwareRegistrationEngine().register(
                sequence,
                reference.analysis.id,
                reference.analysis.width,
                reference.analysis.height
            )
            assertEquals("NON_ZERO_STELLAR", stage10.model.selectedMotionModel)
            assertTrue(stage10.model.velocityX < 0f)
            assertTrue(stage10.model.velocityY > 0f)

            val referenceImage = checkNotNull(ImageIO.read(reference.path.toFile()))
            try {
                val scaleX = referenceImage.width.toFloat() / reference.analysis.width
                val scaleY = referenceImage.height.toFloat() / reference.analysis.height
                val velocityScale = (scaleX + scaleY) * 0.5f
                val captureById = filtered.associate { it.analysis.id to it.captureIndex }
                val scaled = stage10.registrations.mapValues { (_, registration) ->
                    registration.scaledToFullResolution(scaleX, scaleY)
                }
                val sequenceValidated = TransformSequenceValidator().validate(
                    scaled.mapNotNull { (frameId, registration) ->
                        if (!registration.isReliable) null else OrderedRegistration(
                            frameId,
                            captureById.getValue(frameId),
                            frameId == reference.analysis.id,
                            registration
                        )
                    },
                    ExpectedSequenceMotionModel(
                        stage10.model.velocityX * scaleX,
                        stage10.model.velocityY * scaleY,
                        stage10.model.referenceIndex,
                        stage10.model.residual * velocityScale,
                        stage10.model.motionObservable,
                        stage10.verification.selectedModel.score
                    )
                )
                val provisional = sequenceValidated.registrations.filter { it.registration.isReliable }
                    .associate { it.frameId to it.registration }
                assertTrue("Stage 10 provisional registrations: ${provisional.keys}", provisional.size >= 5)
                val fullPatches = reference.analysis.stars.map { star ->
                    val x = star.x * scaleX
                    val y = star.y * scaleY
                    FullResolutionStarPatch(
                        x = x,
                        y = y,
                        confidence = star.confidence,
                        localContrast = star.localContrast,
                        width = star.width * velocityScale,
                        ellipticity = star.ellipticity,
                        sector = ((y / referenceImage.height * 3f).toInt().coerceIn(0, 2) * 3) +
                            (x / referenceImage.width * 3f).toInt().coerceIn(0, 2),
                        motionCluster = stage10.trackAnalysis.clusterAt(reference.analysis.id, star),
                        skyCoverage = 1f
                    )
                }
                val pathById = filtered.associate { it.analysis.id to it.path }
                val refiner = FullResolutionRegistrationRefiner()
                val refinedResults = linkedMapOf<String, com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionRefinementResult>()
                BufferedImageSource(referenceImage).use { referenceSource ->
                    provisional.toSortedMap().forEach { (frameId, registration) ->
                        val isReference = frameId == reference.analysis.id
                        val candidateImage = if (isReference) referenceImage else checkNotNull(
                            ImageIO.read(pathById.getValue(frameId).toFile())
                        )
                        try {
                            BufferedImageSource(candidateImage).use { candidateSource ->
                                refinedResults[frameId] = refiner.refine(
                                    frameId,
                                    isReference,
                                    referenceSource,
                                    candidateSource,
                                    registration.referenceToSourceTransform(),
                                    fullPatches,
                                    (maxOf(scaleX, scaleY) * 0.5f).coerceIn(0f, 1f),
                                    registration.residualError.takeIf { it.isFinite() }
                                        ?.times(velocityScale) ?: 3f,
                                    stage10.model.residual * velocityScale,
                                    registration.confidence
                                )
                            }
                        } finally {
                            if (!isReference) candidateImage.flush()
                        }
                    }
                }
                val finalTransforms = refinedResults.filterValues { it.accepted }
                    .mapValues { it.value.refinedTransform }
                assertTrue("Stage 11 results: $refinedResults", finalTransforms.size > 2)
                assertEquals(ReferenceToSourceTransform.Identity, finalTransforms[reference.analysis.id])
                val initialTransforms = provisional.mapValues { it.value.referenceToSourceTransform() }
                val before = boundedCleanStackReplay(pathById, initialTransforms, fullPatches, reference.path)
                val after = boundedCleanStackReplay(pathById, finalTransforms, fullPatches, reference.path)
                val beforeResiduals = refinedResults.values.filterNot { it.frameId == reference.analysis.id }
                    .map { hypot(it.correctionDx, it.correctionDy) }
                val afterResiduals = refinedResults.values.filter { it.accepted && it.frameId != reference.analysis.id }
                    .map { it.medianResidual }
                val beforeMedian = median(beforeResiduals)
                val afterMedian = median(afterResiduals)
                val beforeP90 = percentile(beforeResiduals, 0.90f)
                val afterP90 = percentile(afterResiduals, 0.90f)
                println(
                    "Stage11 replay reference=${reference.analysis.id} index=${reference.captureIndex} " +
                        "provisional=${provisional.size} final=${finalTransforms.size} " +
                        "medianResidual=$beforeMedian->$afterMedian p90Residual=$beforeP90->$afterP90 " +
                        "retention=${before.retention}->${after.retention} " +
                        "contrast=${before.contrastRatio}->${after.contrastRatio} " +
                        "centroid=${before.centroidResidual}->${after.centroidResidual} " +
                        "smear=${before.smear}->${after.smear} " +
                        "full=${referenceImage.width}x${referenceImage.height}"
                )
                refinedResults.values.forEach { value ->
                    println(
                        "Stage11 frame=${value.frameId} initial=(${value.initialTransform.dx},${value.initialTransform.dy}) " +
                            "refined=(${value.refinedTransform.dx},${value.refinedTransform.dy}) " +
                            "correction=(${value.correctionDx},${value.correctionDy}) " +
                            "patches=${value.acceptedPatchCount}/${value.attemptedPatchCount} " +
                            "median=${value.medianResidual} p90=${value.percentile90Residual} " +
                            "accepted=${value.accepted} reason=${value.rejectionReason}"
                    )
                }
                assertTrue("median residual $afterMedian", afterMedian <= 0.35f)
                assertTrue("p90 residual $afterP90", afterP90 <= 0.60f)
                assertTrue("clean retention ${after.retention}", after.retention >= 0.90f)
                assertTrue("clean contrast ${after.contrastRatio}", after.contrastRatio >= 0.85f)
                assertTrue("clean centroid ${after.centroidResidual}", after.centroidResidual <= 0.50f)
                assertTrue("clean smear ${after.smear}", after.smear <= 0.10f)
                assertEquals(1440, referenceImage.width)
                assertEquals(1920, referenceImage.height)
            } finally {
                referenceImage.flush()
            }
        } finally {
            extracted.toFile().deleteRecursively()
        }
    }

    private fun boundedCleanStackReplay(
        paths: Map<String, Path>,
        transforms: Map<String, ReferenceToSourceTransform>,
        patches: List<FullResolutionStarPatch>,
        referencePath: Path
    ): ReplayCleanMetrics {
        val selectedPatches = patches.take(MAX_PATCHES)
        val side = PATCH_RADIUS * 2 + 1
        val sums = Array(selectedPatches.size) { FloatArray(side * side) }
        val counts = Array(selectedPatches.size) { IntArray(side * side) }
        transforms.forEach { (frameId, transform) ->
            val image = checkNotNull(ImageIO.read(paths.getValue(frameId).toFile()))
            try {
                BufferedImageSource(image).use { source ->
                    selectedPatches.forEachIndexed { patchIndex, patch ->
                        var index = 0
                        for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS) {
                            val mapped = transform.mapOutputToSource(patch.x + dx, patch.y + dy)
                            val sample = TransformedBitmapSampler().sampleAt(source, mapped.x, mapped.y)
                            if (sample != null) {
                                sums[patchIndex][index] += 0.2126f * sample.red +
                                    0.7152f * sample.green + 0.0722f * sample.blue
                                counts[patchIndex][index]++
                            }
                            index++
                        }
                    }
                }
            } finally {
                image.flush()
            }
        }
        val referenceImage = checkNotNull(ImageIO.read(referencePath.toFile()))
        try {
            val referenceMetrics = BufferedImageSource(referenceImage).use { source ->
                selectedPatches.map { patch -> nativePatchMetrics(source, patch) }
            }
            val stackedMetrics = sums.indices.map { patchIndex ->
                val values = FloatArray(sums[patchIndex].size) { index ->
                    sums[patchIndex][index] / counts[patchIndex][index].coerceAtLeast(1)
                }
                signalMetrics(values)
            }
            val valid = stackedMetrics.indices.filter { referenceMetrics[it].contrast > 0.005f }
            if (valid.isEmpty()) return ReplayCleanMetrics(0f, 0f, Float.POSITIVE_INFINITY, 1f)
            return ReplayCleanMetrics(
                retention = valid.count { index ->
                    stackedMetrics[index].contrast >= referenceMetrics[index].contrast * 0.60f
                }.toFloat() / valid.size,
                contrastRatio = median(valid.map { stackedMetrics[it].contrast / referenceMetrics[it].contrast }),
                centroidResidual = median(valid.map { index ->
                    hypot(
                        stackedMetrics[index].centroidX - referenceMetrics[index].centroidX,
                        stackedMetrics[index].centroidY - referenceMetrics[index].centroidY
                    )
                }),
                smear = valid.count { index ->
                    stackedMetrics[index].ellipticity > maxOf(0.82f, referenceMetrics[index].ellipticity + 0.25f)
                }.toFloat() / valid.size
            )
        } finally {
            referenceImage.flush()
        }
    }

    private fun nativePatchMetrics(source: ArgbPixelSource, patch: FullResolutionStarPatch): PatchMetrics {
        val side = PATCH_RADIUS * 2 + 1
        val values = FloatArray(side * side)
        var index = 0
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS) {
            val sample = TransformedBitmapSampler().sampleAt(source, patch.x + dx, patch.y + dy)
            values[index++] = if (sample == null) 0f else
                0.2126f * sample.red + 0.7152f * sample.green + 0.0722f * sample.blue
        }
        return signalMetrics(values)
    }

    private fun signalMetrics(values: FloatArray): PatchMetrics {
        val side = PATCH_RADIUS * 2 + 1
        var border = 0f
        var borderCount = 0
        var maximum = 0f
        for (y in 0 until side) for (x in 0 until side) {
            val value = values[y * side + x]
            maximum = maxOf(maximum, value)
            if (x == 0 || y == 0 || x == side - 1 || y == side - 1) {
                border += value
                borderCount++
            }
        }
        val background = border / borderCount.coerceAtLeast(1)
        var weight = 0f
        var centroidX = 0f
        var centroidY = 0f
        for (y in 0 until side) for (x in 0 until side) {
            val signal = (values[y * side + x] - background).coerceAtLeast(0f)
            weight += signal
            centroidX += signal * (x - PATCH_RADIUS)
            centroidY += signal * (y - PATCH_RADIUS)
        }
        centroidX /= weight.coerceAtLeast(0.000001f)
        centroidY /= weight.coerceAtLeast(0.000001f)
        var xx = 0f
        var yy = 0f
        var xy = 0f
        for (y in 0 until side) for (x in 0 until side) {
            val signal = (values[y * side + x] - background).coerceAtLeast(0f)
            val dx = x - PATCH_RADIUS - centroidX
            val dy = y - PATCH_RADIUS - centroidY
            xx += signal * dx * dx
            yy += signal * dy * dy
            xy += signal * dx * dy
        }
        xx /= weight.coerceAtLeast(0.000001f)
        yy /= weight.coerceAtLeast(0.000001f)
        xy /= weight.coerceAtLeast(0.000001f)
        val trace = xx + yy
        val root = sqrt(((xx - yy) * (xx - yy) + 4f * xy * xy).coerceAtLeast(0f))
        val major = sqrt(((trace + root) * 0.5f).coerceAtLeast(0f))
        val minor = sqrt(((trace - root) * 0.5f).coerceAtLeast(0f))
        return PatchMetrics(
            contrast = (maximum - background).coerceAtLeast(0f),
            centroidX = centroidX,
            centroidY = centroidY,
            ellipticity = if (major < 0.0001f) 1f else (1f - minor / major).coerceIn(0f, 1f)
        )
    }

    private fun readThumbnail(path: Path): ArgbPixelImage {
        val source = checkNotNull(ImageIO.read(path.toFile()))
        try {
            val scale = minOf(1f, REPLAY_MAX_DIMENSION / maxOf(source.width, source.height))
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            val thumbnail = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = thumbnail.createGraphics()
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                )
                graphics.drawImage(source, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
            return ArgbPixelImage(width, height, IntArray(width * height).also {
                thumbnail.getRGB(0, 0, width, height, it, 0, width)
            })
        } finally {
            source.flush()
        }
    }

    private fun extractReadOnly(zipPath: Path, destination: Path) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipPath.toFile()))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = destination.resolve(entry.name).normalize()
                require(target.startsWith(destination)) { "Unsafe ZIP entry: ${entry.name}" }
                if (entry.isDirectory) Files.createDirectories(target) else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun captureIndex(fileName: String): Int? =
        Regex("_(\\d{3})\\.jpe?g", RegexOption.IGNORE_CASE).find(fileName)
            ?.groupValues?.get(1)?.toIntOrNull()

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return Float.POSITIVE_INFINITY
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private fun percentile(values: List<Float>, percentile: Float): Float {
        if (values.isEmpty()) return Float.POSITIVE_INFINITY
        val sorted = values.sorted()
        return sorted[ceil((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private class BufferedImageSource(private val image: BufferedImage) : ArgbPixelSource {
        override val width: Int = image.width
        override val height: Int = image.height
        override fun argbAt(x: Int, y: Int): Int = image.getRGB(x, y)
    }

    private data class ReplayAnalysis(
        val path: Path,
        val captureIndex: Int,
        val analysis: com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis
    )

    private data class PatchMetrics(
        val contrast: Float,
        val centroidX: Float,
        val centroidY: Float,
        val ellipticity: Float
    )

    private data class ReplayCleanMetrics(
        val retention: Float,
        val contrastRatio: Float,
        val centroidResidual: Float,
        val smear: Float
    )

    companion object {
        private const val REPLAY_ENV = "ASTROPHOTO_REGISTRATION_REPLAY_ZIP"
        private const val REPLAY_MAX_DIMENSION = 960f
        private const val PATCH_RADIUS = 5
        private const val MAX_PATCHES = 24
    }
}
