package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.composition.MaskFeathering
import com.example.astrophoto.processing.jpeg.v2.composition.SkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.enhancement.EnhancedGlobalToneProcessor
import com.example.astrophoto.processing.jpeg.v2.enhancement.fileBackedPixelHash
import com.example.astrophoto.processing.jpeg.v2.integration.FrameWeightCalculator
import com.example.astrophoto.processing.jpeg.v2.integration.FrameWeightInput
import com.example.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.example.astrophoto.processing.jpeg.v2.integration.TileProcessingCoordinator
import com.example.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.example.astrophoto.processing.jpeg.v2.masking.ForegroundProtectionMask
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskRefiner
import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingFeatureFlags
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMaskResult
import com.example.astrophoto.processing.jpeg.v2.output.ArgbArrayPngSource
import com.example.astrophoto.processing.jpeg.v2.output.PngStreamEncoder
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.quality.ResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.registration.ExpectedSequenceMotionModel
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionRegistrationRefiner
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import com.example.astrophoto.processing.jpeg.v2.registration.StellarCentroidFrameRefiner
import com.example.astrophoto.processing.jpeg.v2.registration.StellarCentroidRefinementResult
import com.example.astrophoto.processing.jpeg.v2.registration.OrderedRegistration
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.example.astrophoto.processing.jpeg.v2.registration.TransformSequenceValidator
import com.example.astrophoto.processing.jpeg.v2.registration.scaledToFullResolution
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneWriter
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.example.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage12ReplayTest {
    @Test fun realSessionStellarCentroidReplayWhenZipIsProvided() {
        val zipPath = System.getenv(REPLAY_ENV)?.takeIf { it.isNotBlank() }?.let(Path::of) ?: return
        assertTrue("Replay ZIP does not exist: $zipPath", Files.isRegularFile(zipPath))
        val replayRoot = Path.of("build/tmp/stage12-stellar-centroid-replay")
        Files.createDirectories(replayRoot)
        val extracted = Files.createTempDirectory(replayRoot, "session-")
        try {
            extractReadOnly(zipPath, extracted)
            val jpegPaths = Files.walk(extracted).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.toString().replace('\\', '/').contains("/Lights/JPEG/", ignoreCase = true) &&
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
                ReplayAnalysis(path, capture, analyzer.analyze(fileName, fileName, thumbnail, mask), mask)
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
                assertTrue("Stage 10 provisional registrations: ${provisional.keys}", provisional.size >= 20)
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
                val centroidRefiner = StellarCentroidFrameRefiner()
                val refinedResults = linkedMapOf<String, com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionRefinementResult>()
                val centroidResults = linkedMapOf<String, StellarCentroidRefinementResult>()
                BufferedImageSource(referenceImage).use { referenceSource ->
                    provisional.toSortedMap().forEach { (frameId, registration) ->
                        val isReference = frameId == reference.analysis.id
                        val candidateImage = if (isReference) referenceImage else checkNotNull(
                            ImageIO.read(pathById.getValue(frameId).toFile())
                        )
                        try {
                            BufferedImageSource(candidateImage).use { candidateSource ->
                                val zncc = refiner.refine(
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
                                refinedResults[frameId] = zncc
                                centroidResults[frameId] = centroidRefiner.refine(
                                    frameId,
                                    isReference,
                                    referenceSource,
                                    candidateSource,
                                    registration.referenceToSourceTransform(),
                                    zncc.refinedTransform,
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
                val finalTransforms = centroidResults.filterValues { it.accepted }
                    .mapValues { it.value.refinedTransform }
                assertTrue("Stage 12 results: $centroidResults", finalTransforms.size >= 20)
                assertEquals(ReferenceToSourceTransform.Identity, finalTransforms[reference.analysis.id])
                val initialTransforms = provisional.mapValues { it.value.referenceToSourceTransform() }
                val znccTransforms = refinedResults.mapValues { it.value.refinedTransform }
                val cleanReplayPatches = fullPatches.filter {
                    it.motionCluster == com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster.COHERENT_MOVING_SKY
                }
                val finalRegistrations = centroidResults.filterValues { it.accepted }.mapValues { (frameId, result) ->
                    scaled.getValue(frameId).withTransform(result.refinedTransform).copy(
                        isReliable = true,
                        rejectionReason = null,
                        matchedStars = result.attemptedStarCount,
                        inlierStars = result.acceptedStarCount,
                        residualError = result.medianResidual,
                        confidence = result.confidence
                    )
                }
                val analysisById = filtered.associateBy { it.analysis.id }
                val replayWeights = FrameWeightCalculator().calculate(finalRegistrations.map { (frameId, registration) ->
                    FrameWeightInput(
                        analysisById.getValue(frameId).analysis,
                        registration,
                        frameId == reference.analysis.id
                    )
                }).associate { it.frameId to it.normalizedWeight }
                val before = boundedCleanStackReplay(
                    pathById, initialTransforms.filterKeys(replayWeights::containsKey), cleanReplayPatches,
                    reference.path, replayWeights
                )
                val afterZncc = boundedCleanStackReplay(
                    pathById, znccTransforms.filterKeys(replayWeights::containsKey), cleanReplayPatches,
                    reference.path, replayWeights
                )
                val after = boundedCleanStackReplay(
                    pathById, finalTransforms, cleanReplayPatches, reference.path, replayWeights
                )
                val backgroundDiagnostic = writeBackgroundDiagnosticReplay(
                    outputRoot = Path.of("build/reports/jpeg-postprocess-ab"),
                    reference = reference,
                    referenceImage = referenceImage,
                    paths = pathById,
                    captureIndices = captureById,
                    registrations = finalRegistrations,
                    weights = replayWeights,
                    fullResolutionStars = fullPatches
                )
                val beforeResiduals = centroidResults.values.filterNot { it.frameId == reference.analysis.id }
                    .map { hypot(it.correctionDx, it.correctionDy) }
                val znccResiduals = centroidResults.values.filterNot { it.frameId == reference.analysis.id }
                    .map { it.verification.zncc.centroidResidual }
                val afterResiduals = centroidResults.values.filter { it.accepted && it.frameId != reference.analysis.id }
                    .map { it.medianResidual }
                val beforeMedian = median(beforeResiduals)
                val znccMedian = median(znccResiduals)
                val afterMedian = median(afterResiduals)
                val beforeP90 = percentile(beforeResiduals, 0.90f)
                val znccP90 = percentile(znccResiduals, 0.90f)
                val afterP90 = percentile(afterResiduals, 0.90f)
                val acceptedWeights = finalTransforms.keys.map { replayWeights.getValue(it) }
                val effectiveFrameCount = acceptedWeights.sum().let { total ->
                    total * total / acceptedWeights.sumOf { (it * it).toDouble() }.toFloat()
                }
                val estimatedNoiseReduction = 1f - 1f / sqrt(effectiveFrameCount)
                println(
                    "Stage12 replay reference=${reference.analysis.id} index=${reference.captureIndex} " +
                        "provisional=${provisional.size} final=${finalTransforms.size} " +
                        "medianResidual=$beforeMedian->$znccMedian->$afterMedian " +
                        "p90Residual=$beforeP90->$znccP90->$afterP90 " +
                        "retention=${before.retention}->${after.retention} " +
                        "znccContrast=${afterZncc.contrastRatio} contrast=${before.contrastRatio}->${after.contrastRatio} " +
                        "centroid=${before.centroidResidual}->${after.centroidResidual} " +
                        "smear=${before.smear}->${after.smear} " +
                        "effectiveFrames=$effectiveFrameCount noiseReduction=$estimatedNoiseReduction " +
                        "cleanSkyMad=${backgroundDiagnostic.clean.skyMad} " +
                        "processedSkyMad=${backgroundDiagnostic.processed.skyMad} " +
                        "cleanBanding=${backgroundDiagnostic.clean.banding.combinedScore} " +
                        "processedBanding=${backgroundDiagnostic.processed.banding.combinedScore} " +
                        "cleanGradient=${backgroundDiagnostic.clean.gradientResidual} " +
                        "processedGradient=${backgroundDiagnostic.processed.gradientResidual} " +
                        "full=${referenceImage.width}x${referenceImage.height}"
                )
                centroidResults.values.forEach { value ->
                    println(
                        "Stage12 frame=${value.frameId} initial=(${value.initialTransform.dx},${value.initialTransform.dy}) " +
                            "zncc=(${value.znccTransform.dx},${value.znccTransform.dy}) " +
                            "refined=(${value.refinedTransform.dx},${value.refinedTransform.dy}) " +
                            "correction=(${value.correctionDx},${value.correctionDy}) " +
                            "centroids=${value.acceptedStarCount}/${value.attemptedStarCount} " +
                            "median=${value.medianResidual} p90=${value.percentile90Residual} " +
                            "accepted=${value.accepted} reason=${value.rejectionReason}"
                    )
                }
                assertTrue("centroid median $afterMedian must beat ZNCC $znccMedian", afterMedian < znccMedian)
                assertTrue("clean retention ${after.retention}", after.retention >= 0.90f)
                assertTrue("clean contrast ${after.contrastRatio}", after.contrastRatio >= 0.85f)
                assertTrue("clean centroid ${after.centroidResidual}", after.centroidResidual <= 0.50f)
                assertTrue("clean smear ${after.smear}", after.smear <= 0.10f)
                assertTrue("estimated clean noise reduction $estimatedNoiseReduction", estimatedNoiseReduction >= 0.40f)
                assertEquals(1440, referenceImage.width)
                assertEquals(1920, referenceImage.height)
            } finally {
                referenceImage.flush()
            }
        } finally {
            extracted.toFile().deleteRecursively()
        }
    }

    private fun writeBackgroundDiagnosticReplay(
        outputRoot: Path,
        reference: ReplayAnalysis,
        referenceImage: BufferedImage,
        paths: Map<String, Path>,
        captureIndices: Map<String, Int>,
        registrations: Map<String, com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult>,
        weights: Map<String, Float>,
        fullResolutionStars: List<FullResolutionStarPatch>
    ): BackgroundDiagnosticMetrics {
        val width = referenceImage.width
        val height = referenceImage.height
        val initialSkyMask = scaleSkyMask(reference.skyMask.mask, width, height)
        val stackedPixels = IntArray(width * height)
        val coverage = FloatArray(width * height)
        val frames = registrations.map { (frameId, registration) ->
            WeightedIntegrationFrame(
                id = frameId,
                source = paths.getValue(frameId),
                transform = registration,
                normalizedWeight = weights.getValue(frameId)
            )
        }
        val cleanIntegrationDiagnostics = runBlocking {
            LinearWeightedIntegrator(
                tileCoordinator = TileProcessingCoordinator(
                    preferredTileSize = maxOf(width, height),
                    minimumTileSize = 32
                )
            ).integrate(
                outputWidth = width,
                outputHeight = height,
                frames = frames,
                maximumWorkingMemoryBytes = 512L * 1024L * 1024L,
                openSource = { path ->
                    OwnedBufferedImageSource(checkNotNull(ImageIO.read(path.toFile())))
                },
                allowRobustClipping = false,
                includeOutputPixel = initialSkyMask::contains,
                writeTile = { tile, pixels ->
                    copyTile(pixels, tile.width, tile.height, tile.left, tile.top, width, stackedPixels)
                },
                writeCoverageTile = { tile, values ->
                    copyTile(values, tile.width, tile.height, tile.left, tile.top, width, coverage)
                }
            )
        }
        val stacked = ArgbPixelImage(width, height, stackedPixels)
        val referenceArgb = bufferedImageToArgb(referenceImage)
        val stars = fullResolutionStars.map { patch ->
            com.example.astrophoto.processing.jpeg.v2.model.DetectedStar(
                patch.x,
                patch.y,
                1f,
                patch.localContrast,
                patch.localContrast,
                patch.width,
                patch.ellipticity,
                patch.confidence
            )
        }
        val refined = SkyMaskRefiner().refine(
            initialSkyMask,
            referenceArgb,
            stars,
            reference.skyMask.confidence,
            reference.skyMask.usedFallback,
            registrations.values.map { it.confidence }.average().toFloat()
        )
        val protection = ForegroundProtectionMask().detect(referenceArgb, stars)
        val feathered = MaskFeathering().feather(
            refined.binaryMask,
            protection.mask,
            refined.diagnostics.featherRadius
        ).alphaMask
        val validCoverage = AlphaMask(width, height, coverage)
        val cleanComposite = SkyForegroundComposer().compose(
            stacked,
            referenceArgb,
            feathered,
            validCoverage
        )
        val effectiveSky = cleanComposite.effectiveSkyAlpha
        val bypassed = runBlocking {
            AdaptivePresetProcessor(
                featureFlags = AdaptiveProcessingFeatureFlags(
                    bypassArtifactPronePostProcessing = true
                )
            ).process(
                stacked,
                referenceArgb,
                effectiveSky,
                AstroProcessingProfile.DEEP_SKY,
                frames.size,
                stars
            )
        }
        assertTrue(
            "Diagnostic bypass must be pixel-identical to the clean stack",
            bypassed.image.pixels.contentEquals(cleanComposite.image.pixels)
        )
        val processed = runBlocking {
            AdaptivePresetProcessor().process(
                stacked,
                referenceArgb,
                effectiveSky,
                AstroProcessingProfile.DEEP_SKY,
                frames.size,
                stars
            )
        }
        val analyzer = ResultQualityAnalyzer()
        val cleanMetrics = analyzer.analyze(cleanComposite.image, referenceArgb, effectiveSky)
        val processedMetrics = analyzer.analyze(processed.image, referenceArgb, effectiveSky)
        val confirmedStars = fullResolutionStars
            .filter {
                it.motionCluster == com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster.COHERENT_MOVING_SKY &&
                    it.confidence >= 0.35f
            }
            .map { patch ->
                com.example.astrophoto.processing.jpeg.v2.model.DetectedStar(
                    patch.x,
                    patch.y,
                    1f,
                    patch.localContrast,
                    patch.localContrast,
                    patch.width,
                    patch.ellipticity,
                    patch.confidence
                )
            }
        val toneDiagnostics = ReplayGlobalToneDiagnosticRunner().run(
            baseline = cleanComposite.image,
            effectiveSkyAlpha = effectiveSky,
            confirmedStars = confirmedStars
        )
        val visualToneDiagnostics = ReplayGlobalToneVisualFinishRunner().run(
            baseline = cleanComposite.image,
            effectiveSkyAlpha = effectiveSky,
            confirmedStars = confirmedStars,
            outputRoot = outputRoot.resolve("global-tone-visual-finish")
        )
        verifyProductionEnhancedRuntime(
            baseline = cleanComposite.image,
            effectiveSkyAlpha = effectiveSky,
            confirmedStars = confirmedStars,
            approvedReplayCandidate = visualToneDiagnostics.results
                .single { it.candidate.gain == 0.40 }
                .candidate.image,
            outputRoot = outputRoot.resolve("production-enhanced-runtime")
        )
        val manualTrails = ReplayStage1BAnnotations.forCurrentReplay(
            cleanComposite.image.width,
            cleanComposite.image.height,
            paths.keys
        )
        val localCleanupBaselineSnapshot = cleanComposite.image.pixels.copyOf()
        val localCleanupDiagnostics = ReplayLocalTrailCleanupDiagnosticRunner().run(
            baseline = cleanComposite.image,
            reference = referenceArgb,
            effectiveSkyAlpha = effectiveSky,
            annotations = manualTrails,
            confirmedStars = confirmedStars,
            outputRoot = outputRoot.resolve("trail-cleanup").resolve("stage1")
        )
        val normalReferenceWeight = median(
            weights.filterKeys { it != reference.analysis.id }.values.toList()
        )
        val normalReferencePixels = IntArray(width * height)
        val normalReferenceFrames = frames.map { frame ->
            if (frame.id == reference.analysis.id) frame.copy(normalizedWeight = normalReferenceWeight) else frame
        }
        val normalReferenceIntegrationDiagnostics = runBlocking {
            LinearWeightedIntegrator(
                tileCoordinator = TileProcessingCoordinator(
                    preferredTileSize = maxOf(width, height),
                    minimumTileSize = 32
                )
            ).integrate(
                outputWidth = width,
                outputHeight = height,
                frames = normalReferenceFrames,
                maximumWorkingMemoryBytes = 512L * 1024L * 1024L,
                openSource = { path ->
                    OwnedBufferedImageSource(checkNotNull(ImageIO.read(path.toFile())))
                },
                allowRobustClipping = false,
                includeOutputPixel = initialSkyMask::contains,
                writeTile = { tile, pixels ->
                    copyTile(pixels, tile.width, tile.height, tile.left, tile.top, width, normalReferencePixels)
                }
            )
        }
        val normalReferenceCandidate = SkyForegroundComposer().compose(
            ArgbPixelImage(width, height, normalReferencePixels),
            referenceArgb,
            effectiveSky,
            AlphaMask.full(width, height),
            precomputedEffectiveSkyAlpha = effectiveSky
        ).image
        val cameraDefectDiagnostics = ReplayCameraDefectDiagnosticRunner().run(
            baseline = cleanComposite.image,
            effectiveSkyAlpha = effectiveSky,
            frames = registrations.map { (frameId, registration) ->
                ReplayDefectFrame(
                    id = frameId,
                    captureIndex = captureIndices.getValue(frameId),
                    transform = registration.referenceToSourceTransform()
                )
            },
            allFrameIds = paths.keys,
            stars = fullResolutionStars,
            outputRoot = outputRoot.resolve("camera-defects"),
            manualTrails = manualTrails,
            imageLoader = { frame ->
                val image = checkNotNull(ImageIO.read(paths.getValue(frame.id).toFile()))
                try {
                    bufferedImageToArgb(image)
                } finally {
                    image.flush()
                }
            }
        )
        val reverseProvenance = ReplayTrailReverseProvenanceRunner().run(
            baseline = cleanComposite.image,
            reference = referenceArgb,
            effectiveSkyAlpha = effectiveSky,
            annotations = manualTrails,
            frames = registrations.map { (frameId, registration) ->
                ReplayProvenanceFrame(
                    id = frameId,
                    captureIndex = captureIndices.getValue(frameId),
                    transform = registration.referenceToSourceTransform(),
                    normalizedWeight = weights.getValue(frameId)
                )
            },
            outputRoot = outputRoot.resolve("camera-defects").resolve("stage1c"),
            imageLoader = { frame ->
                val image = checkNotNull(ImageIO.read(paths.getValue(frame.id).toFile()))
                try {
                    bufferedImageToArgb(image)
                } finally {
                    image.flush()
                }
            }
        )
        val stage1DBaselineSnapshot = cleanComposite.image.pixels.copyOf()
        val morphologyDiagnostics = ReplayTrailMorphologyDiagnosticRunner().run(
            baseline = cleanComposite.image,
            normalWeightReferenceCandidate = normalReferenceCandidate,
            annotations = manualTrails,
            frames = registrations.map { (frameId, registration) ->
                ReplayProvenanceFrame(
                    id = frameId,
                    captureIndex = captureIndices.getValue(frameId),
                    transform = registration.referenceToSourceTransform(),
                    normalizedWeight = weights.getValue(frameId)
                )
            },
            controlStars = confirmedStars.map { ReplayControlStar(it.x.toDouble(), it.y.toDouble()) },
            referenceFrameId = reference.analysis.id,
            outputRoot = outputRoot.resolve("camera-defects").resolve("stage1d"),
            imageLoader = { frame ->
                val image = checkNotNull(ImageIO.read(paths.getValue(frame.id).toFile()))
                try {
                    bufferedImageToArgb(image)
                } finally {
                    image.flush()
                }
            }
        )
        val stage1EBaselineSnapshot = cleanComposite.image.pixels.copyOf()
        val registrationBakeoff = ReplayRegistrationCorrectionBakeoffRunner().run(
            baseline = cleanComposite.image,
            normalReferenceWeightCandidate = normalReferenceCandidate,
            reference = referenceArgb,
            initialSkyMask = initialSkyMask,
            effectiveSkyAlpha = effectiveSky,
            annotations = manualTrails,
            morphology = morphologyDiagnostics,
            frames = registrations.map { (frameId, registration) ->
                ReplayProvenanceFrame(
                    id = frameId,
                    captureIndex = captureIndices.getValue(frameId),
                    transform = registration.referenceToSourceTransform(),
                    normalizedWeight = weights.getValue(frameId)
                )
            },
            confirmedStars = confirmedStars,
            referenceFrameId = reference.analysis.id,
            baselineProcessingDurationMillis = cleanIntegrationDiagnostics.processingDurationMillis,
            normalReferenceProcessingDurationMillis = normalReferenceIntegrationDiagnostics.processingDurationMillis,
            outputRoot = outputRoot.resolve("camera-defects").resolve("stage1e"),
            imageLoader = { frame ->
                val image = checkNotNull(ImageIO.read(paths.getValue(frame.id).toFile()))
                try {
                    bufferedImageToArgb(image)
                } finally {
                    image.flush()
                }
            }
        )
        Files.createDirectories(outputRoot)
        writePng(outputRoot.resolve("clean-stack.png"), cleanComposite.image)
        writePng(outputRoot.resolve("recovered-stars-baseline.png"), cleanComposite.image)
        writePng(outputRoot.resolve("deep-sky-current.png"), processed.image)
        toneDiagnostics.results.forEach { result ->
            writePng(
                outputRoot.resolve("global-tone-gain-${gainLabel(result.candidate.gain)}.png"),
                result.candidate.image
            )
        }
        writeToneDiagnosticCrops(
            outputRoot.resolve("crops"),
            cleanComposite.image,
            effectiveSky,
            toneDiagnostics
        )
        Files.writeString(
            outputRoot.resolve("global-tone-metrics.txt"),
            toneDiagnostics.reportText()
        )
        Files.writeString(
            outputRoot.resolve("metrics.txt"),
            buildString {
                appendLine("acceptedFrames=${frames.size}")
                appendLine("clean.skyMad=${cleanMetrics.skyMad}")
                appendLine("processed.skyMad=${processedMetrics.skyMad}")
                appendLine("clean.banding=${cleanMetrics.banding.combinedScore}")
                appendLine("processed.banding=${processedMetrics.banding.combinedScore}")
                appendLine("clean.gradientResidual=${cleanMetrics.gradientResidual}")
                appendLine("processed.gradientResidual=${processedMetrics.gradientResidual}")
            }
        )
        assertTrue("Global-tone diagnostics mutated baseline pixels", toneDiagnostics.baselineUnchanged)
        assertTrue("No confirmed fixed star supports", toneDiagnostics.supports.isNotEmpty())
        assertTrue(
            "Candidate anchors diverged",
            toneDiagnostics.results.all { it.candidate.anchors == toneDiagnostics.anchors }
        )
        assertTrue(
            "Linear gamut overflow",
            toneDiagnostics.results.all { it.candidate.maximumLinearChannel <= 1.0 + 1e-12 }
        )
        assertTrue(
            "Visual-finish diagnostics mutated RecoveredStars baseline",
            visualToneDiagnostics.baselineUnchanged
        )
        assertEquals(
            ReplayToneVisualFinishPolicy.GAINS,
            visualToneDiagnostics.results.map { it.candidate.gain }
        )
        assertTrue(
            "Visual-finish anchors diverged",
            visualToneDiagnostics.results.all {
                it.candidate.anchors == visualToneDiagnostics.anchors
            }
        )
        assertTrue(
            "Visual-finish linear gamut overflow",
            visualToneDiagnostics.results.all {
                it.candidate.maximumLinearChannel <= 1.0 + 1e-12
            }
        )
        assertEquals(
            ReplayToneVisualFinishPolicy.PROVISIONAL_PREFERENCE_GAIN,
            visualToneDiagnostics.provisionalPreferenceGain ?: Double.NaN,
            1e-12
        )
        val visualToneRoot = outputRoot.resolve("global-tone-visual-finish")
        listOf(
            "full-resolution/baseline-recovered-stars.png",
            "full-resolution/gain-030.png",
            "full-resolution/gain-040.png",
            "full-resolution/gain-050.png",
            "four-way-baseline-gain030-gain040-gain050.png",
            "sky-comparison-zoom-3x.png",
            "sky-comparison-zoom-8x.png",
            "crops/bright-window-four-way.png",
            "crops/building-edge-four-way.png",
            "metrics-report.txt"
        ).forEach { file ->
            assertTrue(
                "Missing visual-finish output $file",
                Files.isRegularFile(visualToneRoot.resolve(file))
            )
        }
        assertEquals(4, localCleanupDiagnostics.candidates.size)
        assertTrue(
            "Stage 1 local cleanup mutated baseline pixels",
            localCleanupBaselineSnapshot.contentEquals(cleanComposite.image.pixels)
        )
        assertTrue("Stage 1 baseline hash changed", localCleanupDiagnostics.baselineUnchanged)
        localCleanupDiagnostics.candidates.forEach { candidate ->
            assertEquals(
                "Candidate changed pixels outside accepted repair masks",
                0,
                candidate.outsideMaskMaximumDifference
            )
            assertEquals(
                "Candidate changed frozen foreground",
                0,
                candidate.foregroundMaximumDifference
            )
            candidate.trailMetrics.filterNot { it.applied }.forEach { rejected ->
                localCleanupDiagnostics.trailMaskIndices.getValue(rejected.trailId).forEach { index ->
                    assertEquals(
                        "${rejected.trailId} changed despite ${rejected.status} in ${candidate.strength}",
                        cleanComposite.image.pixels[index],
                        candidate.image.pixels[index]
                    )
                }
            }
            val manual06 = candidate.trailMetrics.single { it.trailId == "manual-06" }
            assertEquals(
                ReplayTrailRepairStatus.SKIPPED_OUTSIDE_SAFE_REGION,
                manual06.status
            )
            assertTrue("manual-06 must not be applied", !manual06.applied)
            localCleanupDiagnostics.trailMaskIndices.getValue("manual-06").forEach { index ->
                assertEquals(
                    "manual-06 changed in ${candidate.strength}",
                    cleanComposite.image.pixels[index],
                    candidate.image.pixels[index]
                )
            }
        }
        val manualReview = localCleanupDiagnostics.manualReview
        val manualReviewByTrail = manualReview.trailResults.associateBy { it.trailId }
        assertEquals(
            ReplayTrailRepairStrength.LUMINANCE_80,
            manualReview.sourceStrength
        )
        assertTrue(
            "Manual review changed formal Stage 1 results",
            manualReview.formalStage1ResultsUnchanged
        )
        assertEquals(
            setOf("manual-02", "manual-03", "manual-05"),
            manualReview.trailResults.filter { it.applied }.map { it.trailId }.toSet()
        )
        assertEquals(
            ReplayManualReviewDecision.APPLIED_STRICT_STAGE1_RESULT,
            manualReviewByTrail.getValue("manual-02").decision
        )
        listOf("manual-03", "manual-05").forEach { id ->
            val result = manualReviewByTrail.getValue(id)
            assertEquals(
                ReplayManualReviewDecision.APPLIED_BELOW_STRICT_ENERGY_THRESHOLD,
                result.decision
            )
            assertEquals(ReplayTrailRepairStatus.REJECTED_TRAIL_ENERGY, result.formalStatus)
            assertTrue("$id must stay marked below strict threshold", result.belowStrictEnergyThreshold)
            assertTrue(
                "$id formal luminance ratio must remain above 0.20",
                result.luminanceEnergyRatio > ReplayLocalCleanupThresholds.MAX_TRAIL_ENERGY_RATIO
            )
        }
        listOf("manual-01", "manual-04").forEach { id ->
            val result = manualReviewByTrail.getValue(id)
            assertEquals(ReplayManualReviewDecision.UNCHANGED_STAR_VETO, result.decision)
            assertEquals(ReplayTrailRepairStatus.REJECTED_CONFIRMED_STAR, result.formalStatus)
        }
        assertEquals(
            ReplayManualReviewDecision.UNCHANGED_OUTSIDE_SAFE_REGION,
            manualReviewByTrail.getValue("manual-06").decision
        )
        assertEquals(
            ReplayTrailRepairStatus.SKIPPED_OUTSIDE_SAFE_REGION,
            manualReviewByTrail.getValue("manual-06").formalStatus
        )
        listOf("manual-01", "manual-04", "manual-06").forEach { id ->
            localCleanupDiagnostics.trailMaskIndices.getValue(id).forEach { index ->
                assertEquals(
                    "$id changed in manual-review candidate",
                    cleanComposite.image.pixels[index],
                    manualReview.image.pixels[index]
                )
            }
        }
        assertEquals(
            manualReview.changedPixelCount,
            cleanComposite.image.pixels.indices.count { index ->
                cleanComposite.image.pixels[index] != manualReview.image.pixels[index]
            }
        )
        assertTrue("Manual review changed no pixels", manualReview.changedPixelCount > 0)
        assertEquals(0, manualReview.outsideMaskMaximumDifference)
        assertEquals(0, manualReview.foregroundMaximumDifference)
        assertEquals(0, manualReview.confirmedStarSupportMaximumDifference)
        assertEquals(1.0, manualReview.starContrastMinimumRatio, 1e-12)
        assertEquals(0.0, manualReview.starMaximumCentroidShift, 1e-12)
        assertEquals(0.0, manualReview.starMaximumWidthChange, 1e-12)
        assertEquals(0.0, manualReview.starMaximumEllipticityChange, 1e-12)
        assertTrue(
            "Manual review sky MAD worsened: ${manualReview.skyMadRatio}",
            manualReview.skyMadRatio <= 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING
        )
        assertTrue(
            "Manual review banding worsened: ${manualReview.bandingRatio}",
            manualReview.bandingRatio <= 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING
        )
        assertTrue(
            "Manual review gradient worsened: ${manualReview.gradientRatio}",
            manualReview.gradientRatio <= 1.0 + ReplayLocalCleanupThresholds.MAX_BACKGROUND_WORSENING
        )
        manualReview.trailResults.filter { it.applied }.forEach { result ->
            val boundary = checkNotNull(result.boundary)
            assertTrue("${result.trailId} introduced seam", !boundary.seamDetected)
            assertTrue("${result.trailId} introduced halo", !boundary.haloDetected)
            assertTrue("${result.trailId} introduced smooth stripe", !boundary.smoothStripeDetected)
        }
        assertTrue(
            "Manual-review validation failed: ${manualReview.validationReasons}",
            manualReview.validationPassed
        )
        val manualReviewRoot = outputRoot
            .resolve("trail-cleanup")
            .resolve("stage1")
            .resolve("manual-review-luminance-080")
        listOf(
            "manual-review-candidate.png",
            "manual-review-L80-phone-open.png",
            "baseline-vs-candidate.png",
            "difference-x32.png",
            "manual-review-report.txt",
            "manual-review-trails.tsv"
        ).forEach { file ->
            assertTrue("Missing manual-review output $file", Files.isRegularFile(manualReviewRoot.resolve(file)))
        }
        assertEquals(registrations.keys, cameraDefectDiagnostics.acceptedFrameIds)
        assertTrue(
            "Rejected frames leaked into camera-space diagnostics",
            cameraDefectDiagnostics.acceptedFrameIds.intersect(cameraDefectDiagnostics.rejectedFrameIds).isEmpty()
        )
        assertEquals(manualTrails.map { it.id }, reverseProvenance.trails.map { it.annotation.id })
        assertEquals(manualTrails.map { it.id }, morphologyDiagnostics.trails.map { it.annotation.id })
        assertTrue(
            "Stage 1D diagnostics mutated baseline pixels",
            stage1DBaselineSnapshot.contentEquals(cleanComposite.image.pixels)
        )
        assertTrue("Stage 1E baseline hash changed", registrationBakeoff.baselineUnchanged)
        assertTrue(
            "Stage 1E diagnostics mutated baseline pixels",
            stage1EBaselineSnapshot.contentEquals(cleanComposite.image.pixels)
        )
        return BackgroundDiagnosticMetrics(cleanMetrics, processedMetrics)
    }

    private fun writeToneDiagnosticCrops(
        outputRoot: Path,
        baseline: ArgbPixelImage,
        effectiveSky: AlphaMask,
        diagnostics: ReplayToneDiagnosticBundle
    ) {
        Files.createDirectories(outputRoot)
        val crops = replayToneCrops(baseline, effectiveSky, diagnostics)
        crops.forEach { crop ->
            writePng(
                outputRoot.resolve("${crop.name}-baseline.png"),
                cropImage(baseline, crop)
            )
            diagnostics.results.forEach { result ->
                writePng(
                    outputRoot.resolve(
                        "${crop.name}-gain-${gainLabel(result.candidate.gain)}.png"
                    ),
                    cropImage(result.candidate.image, crop)
                )
            }
        }
    }

    private fun replayToneCrops(
        baseline: ArgbPixelImage,
        effectiveSky: AlphaMask,
        diagnostics: ReplayToneDiagnosticBundle
    ): List<ReplayToneCrop> {
        val cropSize = minOf(256, baseline.width, baseline.height)
        val skyTargetX = (baseline.width * 0.58).roundToInt()
        val skyTargetY = (baseline.height * 0.24).roundToInt()
        val skyCenter = closestPixel(
            baseline.width,
            baseline.height,
            skyTargetX,
            skyTargetY
        ) { x, y -> effectiveSky.alphaAt(x, y) >= 0.98f }
        val weakStarSupport = diagnostics.supports.indices
            .minByOrNull { diagnostics.baselineStarMetrics[it].contrast }
            ?.let(diagnostics.supports::get)
        val starCenter = weakStarSupport?.let {
            it.baselineX.roundToInt() to it.baselineY.roundToInt()
        } ?: skyCenter
        val highlightCenter = brightestForegroundPixel(baseline, effectiveSky)
        return listOf(
            cropAround("sky", skyCenter.first, skyCenter.second, cropSize, baseline),
            cropAround("weak-star", starCenter.first, starCenter.second, cropSize, baseline),
            cropAround(
                "building-edge",
                (baseline.width * 0.20).roundToInt(),
                (baseline.height * 0.34).roundToInt(),
                cropSize,
                baseline
            ),
            cropAround("bright-window", highlightCenter.first, highlightCenter.second, cropSize, baseline)
        )
    }

    private fun brightestForegroundPixel(
        image: ArgbPixelImage,
        effectiveSky: AlphaMask
    ): Pair<Int, Int> {
        var bestIndex = 0
        var bestValue = -1
        image.pixels.indices.forEach { index ->
            val x = index % image.width
            val y = index / image.width
            if (effectiveSky.alphaAt(x, y) > 0.02f) return@forEach
            val color = image.pixels[index]
            val value = maxOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF)
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex % image.width to bestIndex / image.width
    }

    private fun closestPixel(
        width: Int,
        height: Int,
        targetX: Int,
        targetY: Int,
        included: (Int, Int) -> Boolean
    ): Pair<Int, Int> {
        var bestX = targetX.coerceIn(0, width - 1)
        var bestY = targetY.coerceIn(0, height - 1)
        var bestDistance = Long.MAX_VALUE
        for (y in 0 until height) for (x in 0 until width) {
            if (!included(x, y)) continue
            val dx = (x - targetX).toLong()
            val dy = (y - targetY).toLong()
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestX = x
                bestY = y
            }
        }
        return bestX to bestY
    }

    private fun cropAround(
        name: String,
        centerX: Int,
        centerY: Int,
        size: Int,
        image: ArgbPixelImage
    ): ReplayToneCrop {
        val left = (centerX - size / 2).coerceIn(0, image.width - size)
        val top = (centerY - size / 2).coerceIn(0, image.height - size)
        return ReplayToneCrop(name, left, top, size, size)
    }

    private fun cropImage(image: ArgbPixelImage, crop: ReplayToneCrop): ArgbPixelImage {
        val pixels = IntArray(crop.width * crop.height)
        for (row in 0 until crop.height) {
            image.pixels.copyInto(
                pixels,
                destinationOffset = row * crop.width,
                startIndex = (crop.top + row) * image.width + crop.left,
                endIndex = (crop.top + row) * image.width + crop.left + crop.width
            )
        }
        return ArgbPixelImage(crop.width, crop.height, pixels)
    }

    private fun scaleSkyMask(source: SkyMask, width: Int, height: Int): SkyMask = SkyMask(
        width,
        height,
        BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            source.contains(
                (x.toLong() * source.width / width).toInt().coerceIn(0, source.width - 1),
                (y.toLong() * source.height / height).toInt().coerceIn(0, source.height - 1)
            )
        }
    )

    private fun copyTile(
        source: IntArray,
        tileWidth: Int,
        tileHeight: Int,
        left: Int,
        top: Int,
        outputWidth: Int,
        destination: IntArray
    ) {
        for (row in 0 until tileHeight) source.copyInto(
            destination,
            destinationOffset = (top + row) * outputWidth + left,
            startIndex = row * tileWidth,
            endIndex = (row + 1) * tileWidth
        )
    }

    private fun copyTile(
        source: FloatArray,
        tileWidth: Int,
        tileHeight: Int,
        left: Int,
        top: Int,
        outputWidth: Int,
        destination: FloatArray
    ) {
        for (row in 0 until tileHeight) source.copyInto(
            destination,
            destinationOffset = (top + row) * outputWidth + left,
            startIndex = row * tileWidth,
            endIndex = (row + 1) * tileWidth
        )
    }

    private fun bufferedImageToArgb(image: BufferedImage): ArgbPixelImage = ArgbPixelImage(
        image.width,
        image.height,
        IntArray(image.width * image.height).also { pixels ->
            image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        }
    )

    private fun writePng(path: Path, image: ArgbPixelImage) {
        Files.newOutputStream(path).use { output ->
            PngStreamEncoder.encode(
                ArgbArrayPngSource(image.width, image.height, image.pixels),
                output
            )
        }
    }

    private fun verifyProductionEnhancedRuntime(
        baseline: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        confirmedStars: List<DetectedStar>,
        approvedReplayCandidate: ArgbPixelImage,
        outputRoot: Path
    ) {
        Files.createDirectories(outputRoot)
        val scratch = Files.createTempDirectory(outputRoot, "scratch-")
        val baselineWriter = FileBackedImageWriter(
            scratch.resolve("recovered-stars.argb").toFile(),
            baseline.width,
            baseline.height
        )
        val baselineRow = IntArray(baseline.width)
        for (y in 0 until baseline.height) {
            baseline.pixels.copyInto(
                baselineRow,
                startIndex = y * baseline.width,
                endIndex = (y + 1) * baseline.width
            )
            baselineWriter.writeRow(y, baselineRow)
        }
        val fileBackedBaseline = baselineWriter.finish()
        val alphaWriter = FileBackedFloatPlaneWriter(
            scratch.resolve("effective-sky.f32").toFile(),
            baseline.width,
            baseline.height
        )
        val alphaValues = effectiveSkyAlpha.copyValues()
        val alphaRow = FloatArray(baseline.width)
        for (y in 0 until baseline.height) {
            alphaValues.copyInto(
                alphaRow,
                startIndex = y * baseline.width,
                endIndex = (y + 1) * baseline.width
            )
            alphaWriter.writeRow(y, alphaRow)
        }
        val fileBackedAlpha = alphaWriter.finish()
        val pipelineFiles = TemporaryPipelineFiles.create(scratch.toFile())
        val store = ResultCandidateStore(pipelineFiles)
        var candidateImage: com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage? = null
        try {
            val heapPools = ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP }
            val heapPeakMeasurementAvailable = heapPools.isNotEmpty() &&
                heapPools.map { runCatching { it.resetPeakUsage() }.isSuccess }.all { it }
            val heapUsedBefore = heapPools.sumOf { it.usage?.used ?: 0L }
            val generationStarted = System.nanoTime()
            val candidate = EnhancedGlobalToneProcessor().createCandidate(
                baseline = fileBackedBaseline,
                effectiveSkyAlpha = fileBackedAlpha,
                confirmedStars = confirmedStars,
                store = store
            )
            val generationValidationDurationMillis =
                (System.nanoTime() - generationStarted) / 1_000_000L
            val peakHeapAfterValidation = heapPools.sumOf {
                it.peakUsage?.used ?: it.usage?.used ?: 0L
            }
            val approximatePeakHeapIncreaseBytes = if (heapPeakMeasurementAvailable) {
                max(0L, peakHeapAfterValidation - heapUsedBefore)
            } else {
                -1L
            }
            candidateImage = candidate.generation.image
            val enhancedPixelHash = fileBackedPixelHash(candidate.generation.image)
            assertEquals(
                "Production Enhanced changed RecoveredStars baseline",
                candidate.baselinePixelHashBefore,
                candidate.baselinePixelHashAfter
            )
            assertTrue(
                "Production Enhanced runtime validation rejected gain 0.40: " +
                    candidate.validation.hardFailureReasons.joinToString("|"),
                candidate.validation.accepted
            )
            val candidatePixels = IntArray(baseline.pixels.size)
            FileBackedImageReader(candidate.generation.image).use { reader ->
                for (y in 0 until baseline.height) {
                    reader.readArgbRow(y, baselineRow)
                    baselineRow.copyInto(
                        candidatePixels,
                        destinationOffset = y * baseline.width
                    )
                }
            }
            assertTrue(
                "Production Enhanced gain 0.40 differs from approved replay pixels",
                candidatePixels.contentEquals(approvedReplayCandidate.pixels)
            )
            writePng(
                outputRoot.resolve("production-enhanced-gain-040.png"),
                ArgbPixelImage(baseline.width, baseline.height, candidatePixels)
            )
            Files.writeString(
                outputRoot.resolve("validation-metrics.txt"),
                buildString {
                    appendLine("accepted=${candidate.validation.accepted}")
                    appendLine(
                        "hardFailureReasons=" +
                            candidate.validation.hardFailureReasons.joinToString("|")
                    )
                    appendLine("warnings=${candidate.validation.warnings.joinToString("|")}")
                    appendLine("baselineHashBefore=${candidate.baselinePixelHashBefore}")
                    appendLine("baselineHashAfter=${candidate.baselinePixelHashAfter}")
                    appendLine("recoveredStarsArgbSha256=${candidate.baselinePixelHashBefore}")
                    appendLine("enhancedArgbSha256=$enhancedPixelHash")
                    appendLine("hashDomain=file-backed-row-major-ARGB_8888-pixel-bytes")
                    appendLine("imageWidth=${baseline.width}")
                    appendLine("imageHeight=${baseline.height}")
                    appendLine(
                        "candidateGenerationAndValidationDurationMillis=" +
                            generationValidationDurationMillis
                    )
                    appendLine(
                        "heapPeakMeasurementAvailable=" +
                            heapPeakMeasurementAvailable
                    )
                    appendLine(
                        "approximatePeakHeapIncreaseBytes=" +
                            approximatePeakHeapIncreaseBytes
                    )
                    appendLine(
                        "candidateTemporaryBytesOnDisk=" +
                            candidate.generation.image.expectedBytes
                    )
                    appendLine("productionFullResolutionBitmapAllocationBytes=0")
                    appendLine(
                        "fixedSupportMetricSemantics=" +
                            "baseline-confirmed coordinates measured on identical fixed support"
                    )
                    appendLine(
                        "independentDetectorMetricSemantics=" +
                            "separate detector count on a downsampled image; report-only"
                    )
                    appendLine("independentDetectorMaximumDimension=960")
                    appendLine("toeStartLinear=${candidate.generation.anchors.toeStart}")
                    appendLine("toeEndLinear=${candidate.generation.anchors.toeEnd}")
                    candidate.validation.metrics.asReportMetrics()
                        .toSortedMap()
                        .forEach { (key, value) -> appendLine("$key=$value") }
                }
            )
        } finally {
            candidateImage?.let { image -> runCatching { store.deleteTemporary(image) } }
            runCatching { pipelineFiles.close() }
            scratch.toFile().deleteRecursively()
        }
    }

    private fun boundedCleanStackReplay(
        paths: Map<String, Path>,
        transforms: Map<String, ReferenceToSourceTransform>,
        patches: List<FullResolutionStarPatch>,
        referencePath: Path,
        weights: Map<String, Float> = emptyMap()
    ): ReplayCleanMetrics {
        val selectedPatches = patches.take(MAX_PATCHES)
        val side = PATCH_RADIUS * 2 + 1
        val sums = Array(selectedPatches.size) { FloatArray(side * side) }
        val accumulatedWeights = Array(selectedPatches.size) { FloatArray(side * side) }
        transforms.forEach { (frameId, transform) ->
            val frameWeight = weights[frameId] ?: 1f
            val image = checkNotNull(ImageIO.read(paths.getValue(frameId).toFile()))
            try {
                BufferedImageSource(image).use { source ->
                    selectedPatches.forEachIndexed { patchIndex, patch ->
                        var index = 0
                        for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS) {
                            val mapped = transform.mapOutputToSource(patch.x + dx, patch.y + dy)
                            val sample = TransformedBitmapSampler().sampleAt(source, mapped.x, mapped.y)
                            if (sample != null) {
                                sums[patchIndex][index] += frameWeight * (
                                    0.2126f * sample.red + 0.7152f * sample.green + 0.0722f * sample.blue
                                    )
                                accumulatedWeights[patchIndex][index] += frameWeight
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
            val referenceValues = BufferedImageSource(referenceImage).use { source ->
                selectedPatches.map { patch -> nativePatchValues(source, patch) }
            }
            val referenceMetrics = referenceValues.map(::signalMetrics)
            val stackedMetrics = sums.indices.map { patchIndex ->
                val values = FloatArray(sums[patchIndex].size) { index ->
                    sums[patchIndex][index] / accumulatedWeights[patchIndex][index].coerceAtLeast(0.001f)
                }
                values.indices.forEach { index ->
                    values[index] = maxOf(values[index], referenceValues[patchIndex][index])
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
        return signalMetrics(nativePatchValues(source, patch))
    }

    private fun nativePatchValues(source: ArgbPixelSource, patch: FullResolutionStarPatch): FloatArray {
        val side = PATCH_RADIUS * 2 + 1
        val values = FloatArray(side * side)
        var index = 0
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS) {
            val sample = TransformedBitmapSampler().sampleAt(source, patch.x + dx, patch.y + dy)
            values[index++] = if (sample == null) 0f else
                0.2126f * sample.red + 0.7152f * sample.green + 0.0722f * sample.blue
        }
        return values
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

    private class OwnedBufferedImageSource(private val image: BufferedImage) : ArgbPixelSource {
        override val width: Int = image.width
        override val height: Int = image.height
        override fun argbAt(x: Int, y: Int): Int = image.getRGB(x, y)
        override fun close() = image.flush()
    }

    private data class ReplayAnalysis(
        val path: Path,
        val captureIndex: Int,
        val analysis: com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis,
        val skyMask: SkyMaskResult
    )

    private data class BackgroundDiagnosticMetrics(
        val clean: ResultQualityMetrics,
        val processed: ResultQualityMetrics
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

    private data class ReplayToneCrop(
        val name: String,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    companion object {
        private const val REPLAY_ENV = "ASTROPHOTO_REGISTRATION_REPLAY_ZIP"
        private const val REPLAY_MAX_DIMENSION = 960f
        private const val PATCH_RADIUS = 5
        private const val MAX_PATCHES = 24
    }
}
