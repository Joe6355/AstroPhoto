package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.AstroProcessingProfile
import com.example.astrophoto.processing.jpeg.v2.composition.FileBackedSkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.example.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingFeatureFlags
import com.example.astrophoto.processing.jpeg.v2.model.ChromaNoiseDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.GradientRemovalDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.LinearRgb
import com.example.astrophoto.processing.jpeg.v2.model.NeutralizationDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.example.astrophoto.processing.jpeg.v2.model.StarEnhancementDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.StretchDiagnostics
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import com.example.astrophoto.processing.jpeg.v2.quality.FileBackedSuspiciousPointClassifier
import com.example.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FileBackedAdaptiveProcessingResult(
    val image: FileBackedImage,
    val diagnostics: AdaptiveProcessingDiagnostics
)

/** Stage 4 equivalent that owns only bounded tile/halo buffers and file handles. */
class FileBackedAdaptivePresetProcessor(
    private val statistics: FileBackedSkyStatistics = FileBackedSkyStatistics(),
    private val composer: FileBackedSkyForegroundComposer = FileBackedSkyForegroundComposer(),
    private val featureFlags: AdaptiveProcessingFeatureFlags = AdaptiveProcessingFeatureFlags()
) {
    suspend fun process(
        stackedSky: FileBackedImage,
        referenceForeground: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane,
        profile: AstroProcessingProfile,
        frameCount: Int,
        alignedStackStars: List<DetectedStar>,
        store: ResultCandidateStore,
        memoryBudget: JpegMemoryBudget,
        memoryTracker: PipelineMemoryTracker,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ): FileBackedAdaptiveProcessingResult {
        require(stackedSky.width == referenceForeground.width && stackedSky.height == referenceForeground.height)
        require(stackedSky.width == effectiveSkyAlpha.width && stackedSky.height == effectiveSkyAlpha.height)
        require(profile != AstroProcessingProfile.NORMAL)
        val started = System.nanoTime()
        val parameters = ExistingPresetParameterMapper.parametersFor(profile, frameCount)
        val durations = linkedMapOf<String, Long>()
        fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000L
        fun fileStatistics(image: FileBackedImage): SkyStatisticsResult =
            FileBackedImageReader(image).use { reader ->
                FileBackedFloatPlaneReader(effectiveSkyAlpha).use { alpha ->
                    statistics.calculate(reader, alpha, alignedStackStars)
                }
            }

        onProgress("Analyzing sky", 0, TOTAL_STAGES)
        var stageStarted = System.nanoTime()
        val before = fileStatistics(stackedSky)
        durations["sky_statistics"] = elapsed(stageStarted)
        if (featureFlags.bypassArtifactPronePostProcessing) {
            durations["artifact_prone_postprocessing_bypassed"] = 0L
            return bypassPostProcessing(
                stackedSky,
                referenceForeground,
                effectiveSkyAlpha,
                profile,
                before,
                store,
                memoryBudget,
                memoryTracker,
                started,
                durations
            )
        }

        onProgress("Removing light pollution", 1, TOTAL_STAGES)
        stageStarted = System.nanoTime()
        var working = gradientPass(
            stackedSky,
            effectiveSkyAlpha,
            alignedStackStars,
            before,
            parameters.gradientStrength,
            parameters.maximumGradientCorrection,
            store,
            memoryBudget,
            memoryTracker
        )
        val gradientDiagnostics = working.second
        var workingImage = working.first
        durations["gradient_removal"] = elapsed(stageStarted)
        var currentStatistics = fileStatistics(workingImage)

        onProgress("Balancing sky color", 2, TOTAL_STAGES)
        stageStarted = System.nanoTime()
        val neutralized = neutralizationPass(
            workingImage,
            effectiveSkyAlpha,
            alignedStackStars,
            currentStatistics,
            parameters.neutralizationStrength,
            parameters.maximumNeutralizationCorrection,
            store,
            memoryBudget,
            memoryTracker
        )
        store.deleteTemporary(workingImage)
        workingImage = neutralized.first
        val neutralizationDiagnostics = neutralized.second
        durations["background_neutralization"] = elapsed(stageStarted)
        currentStatistics = fileStatistics(workingImage)

        onProgress("Stretching faint stars", 3, TOTAL_STAGES)
        stageStarted = System.nanoTime()
        val stretched = stretchPass(
            workingImage,
            effectiveSkyAlpha,
            alignedStackStars,
            currentStatistics,
            parameters.stretchBlend,
            parameters.asinhStrength,
            parameters.highlightProtection,
            parameters.maximumSkyMedianFactor,
            parameters.targetDisplaySkyMedian,
            parameters.minimumBlackWhiteSeparation,
            store,
            memoryBudget,
            memoryTracker
        )
        store.deleteTemporary(workingImage)
        workingImage = stretched.first
        val stretchDiagnostics = stretched.second
        durations["stretch"] = elapsed(stageStarted)
        currentStatistics = fileStatistics(workingImage)

        onProgress("Reducing color noise", 4, TOTAL_STAGES)
        stageStarted = System.nanoTime()
        val chroma = chromaPass(
            workingImage,
            effectiveSkyAlpha,
            alignedStackStars,
            currentStatistics,
            parameters.chromaNoiseStrength,
            parameters.maximumChromaRadius,
            store,
            memoryBudget,
            memoryTracker
        )
        store.deleteTemporary(workingImage)
        workingImage = chroma.first
        val chromaDiagnostics = chroma.second
        durations["chroma_reduction"] = elapsed(stageStarted)

        onProgress("Enhancing stars", 5, TOTAL_STAGES)
        currentStatistics = fileStatistics(workingImage)
        stageStarted = System.nanoTime()
        val enhanced = starPass(
            workingImage,
            effectiveSkyAlpha,
            alignedStackStars,
            currentStatistics,
            parameters.starContrastStrength,
            parameters.maximumStarDetailGain,
            parameters.minimumStarContrastGain,
            parameters.maximumStarWidthGrowth,
            store,
            memoryBudget,
            memoryTracker
        )
        store.deleteTemporary(workingImage)
        workingImage = enhanced.first
        val starDiagnostics = enhanced.second
        durations["star_enhancement"] = elapsed(stageStarted)

        var after = fileStatistics(workingImage)
        val safetyScale = finalSafetyScale(
            before,
            after,
            parameters.maximumSkyMedianFactor,
            parameters.targetDisplaySkyMedian,
            parameters.maximumChannelClippingPercent
        )
        if (safetyScale < 0.999f) {
            val blended = blendPass(
                stackedSky,
                workingImage,
                effectiveSkyAlpha,
                safetyScale,
                "final-safety",
                store,
                memoryBudget,
                memoryTracker
            )
            store.deleteTemporary(workingImage)
            workingImage = blended
            after = fileStatistics(workingImage)
        }

        stageStarted = System.nanoTime()
        val backgroundMatch = SkyBackgroundToneMatcher.calculate(before, after)
        if (backgroundMatch.linearOffset > 0f) {
            val matched = transform(
                "background-match",
                workingImage,
                effectiveSkyAlpha,
                store,
                memoryBudget,
                memoryTracker
            ) { x, y, color, _, alpha ->
                SkyBackgroundToneMatcher.apply(color, alpha.alphaAt(x, y), backgroundMatch)
            }
            store.deleteTemporary(workingImage)
            workingImage = matched
            after = fileStatistics(workingImage)
        }
        durations["background_matching"] = elapsed(stageStarted)

        stageStarted = System.nanoTime()
        val processedWriter = store.createWriter(
            ResultCandidateType.PROCESSED,
            stackedSky.width,
            stackedSky.height
        )
        val alphaCopyWriter = store.createFloatPlaneWriter(
            "processed-effective",
            stackedSky.width,
            stackedSky.height
        )
        val alphaForMask = FileBackedFloatPlaneReader(effectiveSkyAlpha)
        val alphaForCoverage = FileBackedFloatPlaneReader(effectiveSkyAlpha)
        val alphaPrecomputed = FileBackedFloatPlaneReader(effectiveSkyAlpha)
        val composite = try {
            composer.compose(
                stackedSky = workingImage,
                reference = referenceForeground,
                featheredSkyMask = alphaForMask,
                validCoverage = alphaForCoverage,
                output = processedWriter,
                effectiveAlphaOutput = alphaCopyWriter,
                memoryBudget = memoryBudget,
                memoryTracker = memoryTracker,
                precomputedEffectiveSkyAlpha = alphaPrecomputed
            )
        } finally {
            alphaForMask.close()
            alphaForCoverage.close()
            alphaPrecomputed.close()
        }
        store.deleteTemporary(workingImage)
        store.deleteTemporary(composite.effectiveSkyAlpha)
        val pointSafe = suppressNewSuspiciousPoints(
            candidate = composite.image,
            baseline = stackedSky,
            alphaPlane = effectiveSkyAlpha,
            baselineStatistics = before,
            candidateStatistics = after,
            store = store,
            budget = memoryBudget,
            tracker = memoryTracker
        )
        if (pointSafe !== composite.image) store.deleteTemporary(composite.image)
        val processed = store.register(ResultCandidateType.PROCESSED, pointSafe)
        durations["foreground_composition"] = elapsed(stageStarted)
        return FileBackedAdaptiveProcessingResult(
            image = processed,
            diagnostics = AdaptiveProcessingDiagnostics(
                preset = profile.name,
                before = before,
                after = after,
                gradient = gradientDiagnostics,
                neutralization = neutralizationDiagnostics,
                stretch = stretchDiagnostics.copy(
                    medianSafetyScale = stretchDiagnostics.medianSafetyScale * safetyScale
                ),
                chromaNoise = chromaDiagnostics,
                starEnhancement = starDiagnostics,
                foregroundDifferenceOutsideMask = composite.diagnostics.maximumForegroundChannelDifference,
                processingDurationMillis = (System.nanoTime() - started) / 1_000_000L,
                stageDurationsMillis = durations
            )
        )
    }

    private fun bypassPostProcessing(
        stackedSky: FileBackedImage,
        referenceForeground: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane,
        profile: AstroProcessingProfile,
        before: SkyStatisticsResult,
        store: ResultCandidateStore,
        memoryBudget: JpegMemoryBudget,
        memoryTracker: PipelineMemoryTracker,
        started: Long,
        durations: Map<String, Long>
    ): FileBackedAdaptiveProcessingResult {
        val processedWriter = store.createWriter(
            ResultCandidateType.PROCESSED,
            stackedSky.width,
            stackedSky.height
        )
        val alphaCopyWriter = store.createFloatPlaneWriter(
            "diagnostic-bypass-effective",
            stackedSky.width,
            stackedSky.height
        )
        val alphaForMask = FileBackedFloatPlaneReader(effectiveSkyAlpha)
        val alphaForCoverage = FileBackedFloatPlaneReader(effectiveSkyAlpha)
        val alphaPrecomputed = FileBackedFloatPlaneReader(effectiveSkyAlpha)
        val composite = try {
            composer.compose(
                stackedSky = stackedSky,
                reference = referenceForeground,
                featheredSkyMask = alphaForMask,
                validCoverage = alphaForCoverage,
                output = processedWriter,
                effectiveAlphaOutput = alphaCopyWriter,
                memoryBudget = memoryBudget,
                memoryTracker = memoryTracker,
                precomputedEffectiveSkyAlpha = alphaPrecomputed
            )
        } finally {
            alphaForMask.close()
            alphaForCoverage.close()
            alphaPrecomputed.close()
        }
        store.deleteTemporary(composite.effectiveSkyAlpha)
        val processed = store.register(ResultCandidateType.PROCESSED, composite.image)
        return FileBackedAdaptiveProcessingResult(
            processed,
            AdaptiveProcessingDiagnostics(
                preset = profile.name,
                before = before,
                after = before,
                gradient = GradientRemovalDiagnostics(0f, 0, 0, 0, 0f),
                neutralization = NeutralizationDiagnostics(LinearRgb(0f, 0f, 0f)),
                stretch = StretchDiagnostics(0f, 1f, 0f, 0f, 0f, 1f),
                chromaNoise = ChromaNoiseDiagnostics(0f, 0),
                starEnhancement = StarEnhancementDiagnostics(0f, 0, 0, 0, 0f),
                foregroundDifferenceOutsideMask = composite.diagnostics.maximumForegroundChannelDifference,
                processingDurationMillis = (System.nanoTime() - started) / 1_000_000L,
                stageDurationsMillis = durations
            )
        )
    }

    private fun suppressNewSuspiciousPoints(
        candidate: FileBackedImage,
        baseline: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        baselineStatistics: SkyStatisticsResult,
        candidateStatistics: SkyStatisticsResult,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker
    ): FileBackedImage {
        val baselinePoints = FileBackedSuspiciousPointClassifier.coordinates(
            baseline,
            alphaPlane,
            baselineStatistics.luminanceMedian,
            baselineStatistics.luminanceMad
        )
        val candidatePoints = FileBackedSuspiciousPointClassifier.coordinates(
            candidate,
            alphaPlane,
            candidateStatistics.luminanceMedian,
            candidateStatistics.luminanceMad
        )
        val newPoints = candidatePoints - baselinePoints
        if (newPoints.isEmpty()) return candidate
        FileBackedImageReader(baseline, cachedRows = 5).use { baselineReader ->
            return transform(
                "point-safety",
                candidate,
                alphaPlane,
                store,
                budget,
                tracker,
                halo = 1
            ) { x, y, color, _, _ ->
                if (y.toLong() * candidate.width + x in newPoints) baselineReader.argbAt(x, y) else color
            }
        }
    }

    private fun gradientPass(
        input: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        stars: List<DetectedStar>,
        stats: SkyStatisticsResult,
        strength: Float,
        maximumCorrection: Float,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker
    ): Pair<FileBackedImage, GradientRemovalDiagnostics> {
        val grid = GradientGrid.create(input.width, input.height)
        if (strength <= 0f || stats.skyPixelCount == 0) {
            return transform("gradient-copy", input, alphaPlane, store, budget, tracker) { _, _, color, _, _ -> color } to
                GradientRemovalDiagnostics(0f, grid.columns, grid.rows, 0, 0f)
        }
        val starCores = StarCoreIndex.create(input.width, input.height, stars)
        val histograms = IntArray(grid.cellCount * CHANNELS_PER_CELL * MODEL_BINS)
        val counts = IntArray(grid.cellCount)
        FileBackedImageReader(input).use { image ->
            FileBackedFloatPlaneReader(alphaPlane).use { alpha ->
                for (y in 0 until input.height) for (x in 0 until input.width) {
                    if (alpha.alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD || starCores.contains(x, y)) continue
                    val color = image.argbAt(x, y)
                    val red = linearChannel(color, 16)
                    val green = linearChannel(color, 8)
                    val blue = linearChannel(color, 0)
                    if (maxOf(red, green, blue) >= SATURATED_SAMPLE_LIMIT) continue
                    val cell = grid.cellIndex(x, y)
                    counts[cell]++
                    increment(histograms, cell, 0, red)
                    increment(histograms, cell, 1, green)
                    increment(histograms, cell, 2, blue)
                    increment(histograms, cell, 3, 0.2126f * red + 0.7152f * green + 0.0722f * blue)
                }
            }
        }
        val redModel = FloatArray(grid.cellCount) { Float.NaN }
        val greenModel = FloatArray(grid.cellCount) { Float.NaN }
        val blueModel = FloatArray(grid.cellCount) { Float.NaN }
        var validCells = 0
        counts.indices.forEach { cell ->
            val minimum = maxOf(MIN_CELL_SAMPLES, (grid.approximateCellArea * MIN_SKY_CELL_FRACTION).roundToInt())
            if (counts[cell] < minimum) return@forEach
            val luminosity = channelHistogram(histograms, cell, 3)
            if (histogramPercentile(luminosity, counts[cell].toLong(), 0.84f) -
                histogramPercentile(luminosity, counts[cell].toLong(), 0.16f) >
                maxOf(MIN_TEXTURE_LIMIT, stats.luminanceMad * 9f)
            ) return@forEach
            redModel[cell] = histogramPercentile(channelHistogram(histograms, cell, 0), counts[cell].toLong(), 0.5f)
            greenModel[cell] = histogramPercentile(channelHistogram(histograms, cell, 1), counts[cell].toLong(), 0.5f)
            blueModel[cell] = histogramPercentile(channelHistogram(histograms, cell, 2), counts[cell].toLong(), 0.5f)
            validCells++
        }
        if (validCells == 0) {
            return transform("gradient-copy", input, alphaPlane, store, budget, tracker) { _, _, color, _, _ -> color } to
                GradientRemovalDiagnostics(0f, grid.columns, grid.rows, 0, 0f)
        }
        fillMissing(redModel, grid, stats.channelMedian.red)
        fillMissing(greenModel, grid, stats.channelMedian.green)
        fillMissing(blueModel, grid, stats.channelMedian.blue)
        val smoothRed = smooth(smooth(redModel, grid), grid)
        val smoothGreen = smooth(smooth(greenModel, grid), grid)
        val smoothBlue = smooth(smooth(blueModel, grid), grid)
        val confidence = (validCells.toFloat() / grid.cellCount * stats.confidence).coerceIn(0f, 1f)
        val applied = strength.coerceIn(0f, 1f) * confidence
        var maximumApplied = 0f
        val output = transform("gradient", input, alphaPlane, store, budget, tracker) { x, y, color, _, alpha ->
            val alphaValue = alpha.alphaAt(x, y)
            if (alphaValue <= OPERATION_ALPHA_THRESHOLD) color else {
                val scale = sqrt(alphaValue.coerceIn(0f, 1f))
                val correctionRed = ((grid.sample(smoothRed, x, y) - stats.channelMedian.red) * applied * scale)
                    .coerceIn(-maximumCorrection, maximumCorrection)
                val correctionGreen = ((grid.sample(smoothGreen, x, y) - stats.channelMedian.green) * applied * scale)
                    .coerceIn(-maximumCorrection, maximumCorrection)
                val correctionBlue = ((grid.sample(smoothBlue, x, y) - stats.channelMedian.blue) * applied * scale)
                    .coerceIn(-maximumCorrection, maximumCorrection)
                maximumApplied = maxOf(maximumApplied, abs(correctionRed), abs(correctionGreen), abs(correctionBlue))
                packLinear(
                    (linearChannel(color, 16) - correctionRed).coerceAtLeast(0f),
                    (linearChannel(color, 8) - correctionGreen).coerceAtLeast(0f),
                    (linearChannel(color, 0) - correctionBlue).coerceAtLeast(0f)
                )
            }
        }
        return output to GradientRemovalDiagnostics(confidence, grid.columns, grid.rows, validCells, maximumApplied)
    }

    private fun neutralizationPass(
        input: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        stars: List<DetectedStar>,
        stats: SkyStatisticsResult,
        strength: Float,
        maximumCorrection: Float,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker
    ): Pair<FileBackedImage, NeutralizationDiagnostics> {
        if (strength <= 0f || stats.skyPixelCount == 0) {
            return transform("neutral-copy", input, alphaPlane, store, budget, tracker) { _, _, color, _, _ -> color } to
                NeutralizationDiagnostics(LinearRgb(0f, 0f, 0f))
        }
        val median = stats.channelMedian
        val target = (median.red + median.green + median.blue) / 3f
        val confidence = stats.confidence.coerceIn(0.20f, 1f)
        val correction = LinearRgb(
            ((target - median.red) * strength * confidence).coerceIn(-maximumCorrection, maximumCorrection),
            ((target - median.green) * strength * confidence).coerceIn(-maximumCorrection, maximumCorrection),
            ((target - median.blue) * strength * confidence).coerceIn(-maximumCorrection, maximumCorrection)
        )
        val starCores = StarCoreIndex.create(input.width, input.height, stars, 2f)
        val highlightStart = maxOf(stats.highPercentile, stats.luminanceMedian + 0.08f)
        val highlightEnd = maxOf(highlightStart + 0.08f, stats.estimatedSafeWhitePoint)
        val output = transform("neutral", input, alphaPlane, store, budget, tracker) { x, y, color, _, alpha ->
            val alphaValue = alpha.alphaAt(x, y)
            if (alphaValue <= OPERATION_ALPHA_THRESHOLD || starCores.contains(x, y)) color else {
                val red = linearChannel(color, 16)
                val green = linearChannel(color, 8)
                val blue = linearChannel(color, 0)
                val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
                val scale = sqrt(alphaValue.coerceIn(0f, 1f)) *
                    (1f - smoothStep(highlightStart, highlightEnd, luminance))
                packLinear(
                    (red + correction.red * scale).coerceAtLeast(0f),
                    (green + correction.green * scale).coerceAtLeast(0f),
                    (blue + correction.blue * scale).coerceAtLeast(0f)
                )
            }
        }
        return output to NeutralizationDiagnostics(correction)
    }

    private fun stretchPass(
        input: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        stars: List<DetectedStar>,
        stats: SkyStatisticsResult,
        stretchBlend: Float,
        asinhStrength: Float,
        highlightProtection: Float,
        maximumSkyMedianFactor: Float,
        targetDisplaySkyMedian: Float,
        minimumSeparation: Float,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker
    ): Pair<FileBackedImage, StretchDiagnostics> {
        val black = minOf(stats.lowPercentile, stats.estimatedBlackPoint).coerceIn(0f, 1f - minimumSeparation)
        val white = maxOf(stats.estimatedSafeWhitePoint, black + minimumSeparation).coerceAtMost(1f)
        if (stretchBlend <= 0f || asinhStrength <= 0f || stats.skyPixelCount == 0) {
            return transform("stretch-copy", input, alphaPlane, store, budget, tracker) { _, _, color, _, _ -> color } to
                StretchDiagnostics(black, white, asinhStrength, highlightProtection, 0f, 1f)
        }
        val denominator = asinh(asinhStrength.toDouble()).toFloat().coerceAtLeast(0.0001f)
        val range = (white - black).coerceAtLeast(minimumSeparation)
        val confidence = (0.18f + 0.82f * stats.confidence).coerceIn(0f, 1f)
        val targetLinearMedian = SrgbTransfer.srgbToLinear(targetDisplaySkyMedian)
        val medianNormalized = ((stats.luminanceMedian - black) / range).coerceIn(0f, 1f)
        val fullyMappedMedian = (
            asinh(asinhStrength * medianNormalized.toDouble()) / denominator
            ).toFloat()
        val targetBlend = if (fullyMappedMedian > stats.luminanceMedian) {
            ((targetLinearMedian - stats.luminanceMedian) /
                (fullyMappedMedian - stats.luminanceMedian)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val applied = maxOf(
            stretchBlend.coerceIn(0f, 1f) * confidence,
            targetBlend * confidence
        ).coerceIn(0f, 1f)
        var stretched = transform("stretch", input, alphaPlane, store, budget, tracker) { x, y, color, _, alpha ->
            val alphaValue = alpha.alphaAt(x, y)
            val red = linearChannel(color, 16)
            val green = linearChannel(color, 8)
            val blue = linearChannel(color, 0)
            val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            if (alphaValue <= OPERATION_ALPHA_THRESHOLD || luminance <= MIN_LUMINANCE) color else {
                val normalized = ((luminance - black) / range).coerceIn(0f, 1f)
                val mapped = (asinh(asinhStrength * normalized.toDouble()) / denominator).toFloat()
                val highlightWeight = 1f - highlightProtection.coerceIn(0f, 1f) * smoothStep(0.52f, 1f, normalized)
                val localBlend = applied * sqrt(alphaValue.coerceIn(0f, 1f)) * highlightWeight
                val target = (luminance + (mapped - luminance) * localBlend).coerceIn(0f, MAX_UNCLIPPED_VALUE)
                var scale = target / luminance
                val maximum = maxOf(red, green, blue)
                if (maximum < MAX_UNCLIPPED_VALUE && maximum * scale > MAX_UNCLIPPED_VALUE) {
                    scale = MAX_UNCLIPPED_VALUE / maximum.coerceAtLeast(MIN_LUMINANCE)
                }
                packLinear(red * scale, green * scale, blue * scale)
            }
        }
        val stretchedStats = FileBackedImageReader(stretched).use { image ->
            FileBackedFloatPlaneReader(alphaPlane).use { alpha -> statistics.calculate(image, alpha, stars) }
        }
        val allowedMedian = maxOf(
            targetLinearMedian,
            stats.luminanceMedian * maximumSkyMedianFactor,
            stats.luminanceMedian + maxOf(stats.luminanceMad * 2f, MIN_MEDIAN_HEADROOM)
        )
        val safety = when {
            stretchedStats.luminanceMedian <= allowedMedian -> 1f
            stretchedStats.luminanceMedian <= stats.luminanceMedian -> 1f
            else -> ((allowedMedian - stats.luminanceMedian) /
                (stretchedStats.luminanceMedian - stats.luminanceMedian)).coerceIn(0f, 1f)
        }
        if (safety < 0.999f) {
            val safe = blendPass(input, stretched, alphaPlane, safety, "stretch-safe", store, budget, tracker)
            store.deleteTemporary(stretched)
            stretched = safe
        }
        return stretched to StretchDiagnostics(black, white, asinhStrength, highlightProtection, applied, safety)
    }

    private fun chromaPass(
        input: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        stars: List<DetectedStar>,
        stats: SkyStatisticsResult,
        strength: Float,
        maximumRadius: Int,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker
    ): Pair<FileBackedImage, ChromaNoiseDiagnostics> {
        val radius = if (maximumRadius <= 0) 0 else (
            1 + minOf(input.width, input.height) / 1800 +
                if (stats.chromaNoiseEstimate >= CHROMA_NOISE_REFERENCE) 1 else 0
            ).coerceIn(1, maximumRadius)
        if (strength <= 0f || radius <= 0 || stats.skyPixelCount == 0) {
            return transform("chroma-copy", input, alphaPlane, store, budget, tracker) { _, _, color, _, _ -> color } to
                ChromaNoiseDiagnostics(0f, 0)
        }
        val starsIndex = StarCoreIndex.create(input.width, input.height, stars, 2.2f)
        val edgeLimit = maxOf(MIN_EDGE_LIMIT, stats.luminanceMad * 6f)
        val noiseScale = (stats.chromaNoiseEstimate / CHROMA_NOISE_REFERENCE).coerceIn(0.20f, 1f)
        val applied = strength.coerceIn(0f, 1f) * noiseScale * (0.25f + 0.75f * stats.confidence)
        val lowSignalEnd = maxOf(stats.highPercentile, stats.luminanceMedian + maxOf(stats.luminanceMad * 8f, 0.025f))
        val output = transform("chroma", input, alphaPlane, store, budget, tracker, halo = radius) {
                x, y, color, image, alpha ->
            val alphaValue = alpha.alphaAt(x, y)
            if (starsIndex.contains(x, y) || alphaValue <= OPERATION_ALPHA_THRESHOLD) color else {
                val center = channels(color)
                val darkWeight = 1f - smoothStep(stats.luminanceMedian, lowSignalEnd, center.luminance)
                if (darkWeight <= 0f) color else {
                    var redSum = (center.red - center.luminance) * CENTER_WEIGHT
                    var blueSum = (center.blue - center.luminance) * CENTER_WEIGHT
                    var weightSum = CENTER_WEIGHT
                    for (distance in 1..radius) {
                        val neighbors = arrayOf(
                            x - distance to y,
                            x + distance to y,
                            x to y - distance,
                            x to y + distance
                        )
                        neighbors.forEach { (nx, ny) ->
                            if (nx !in 0 until input.width || ny !in 0 until input.height ||
                                starsIndex.contains(nx, ny) || alpha.alphaAt(nx, ny) <= OPERATION_ALPHA_THRESHOLD
                            ) return@forEach
                            val neighbor = channels(image.argbAt(nx, ny))
                            val difference = abs(neighbor.luminance - center.luminance)
                            if (difference > edgeLimit) return@forEach
                            val weight = 1f / (1f + distance + difference / edgeLimit)
                            redSum += (neighbor.red - neighbor.luminance) * weight
                            blueSum += (neighbor.blue - neighbor.luminance) * weight
                            weightSum += weight
                        }
                    }
                    if (weightSum <= CENTER_WEIGHT) color else {
                        val local = applied * darkWeight * sqrt(alphaValue.coerceIn(0f, 1f))
                        val redChroma = (center.red - center.luminance) * (1f - local) + redSum / weightSum * local
                        val blueChroma = (center.blue - center.luminance) * (1f - local) + blueSum / weightSum * local
                        val fitted = fitChroma(center.luminance, redChroma, blueChroma)
                        packLinear(fitted.red, fitted.green, fitted.blue)
                    }
                }
            }
        }
        return output to ChromaNoiseDiagnostics(applied, radius)
    }

    private fun starPass(
        input: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        stars: List<DetectedStar>,
        stats: SkyStatisticsResult,
        strength: Float,
        maximumDetailGain: Float,
        minimumContrastGain: Float,
        maximumWidthGrowth: Float,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker
    ): Pair<FileBackedImage, StarEnhancementDiagnostics> {
        if (strength <= 0f || maximumDetailGain <= 1f || stars.isEmpty()) {
            return transform("stars-copy", input, alphaPlane, store, budget, tracker) { _, _, color, _, _ -> color } to
                StarEnhancementDiagnostics(strength, stars.size, 0, stars.size, 0f)
        }
        val changes = mutableMapOf<Long, Int>()
        var enhanced = 0
        var rejected = 0
        val noiseFloor = maxOf(MIN_DETAIL, stats.luminanceMad * 2.2f)
        FileBackedImageReader(input, cachedRows = 20).use { image ->
            FileBackedFloatPlaneReader(alphaPlane, cachedRows = 20).use { alpha ->
                stars.forEach { star ->
                    val cx = star.x.roundToInt()
                    val cy = star.y.roundToInt()
                    if (!validStarShape(star) || cx !in 0 until input.width || cy !in 0 until input.height ||
                        alpha.alphaAt(cx, cy) < STATISTICS_ALPHA_THRESHOLD
                    ) {
                        rejected++
                        return@forEach
                    }
                    val background = annulusMedian(image, alpha, cx, cy, star.width)
                    if (!background.isFinite()) {
                        rejected++
                        return@forEach
                    }
                    val centerColor = image.argbAt(cx, cy)
                    val centerLuminance = linearLuminance(centerColor)
                    val detail = centerLuminance - background
                    if (detail <= noiseFloor || centerLuminance >= SATURATED_STAR_LIMIT ||
                        isSingleChannelSpike(centerColor, background, detail)
                    ) {
                        rejected++
                        return@forEach
                    }
                    val radius = ceil(maxOf(1f, star.width * 0.55f)).toInt().coerceAtMost(3)
                    var support = 0
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val x = cx + dx
                        val y = cy + dy
                        if (x in 0 until input.width && y in 0 until input.height &&
                            linearLuminance(image.argbAt(x, y)) - background >= detail * MIN_CORE_DETAIL_FRACTION
                        ) support++
                    }
                    if (support !in MIN_STAR_SUPPORT..MAX_STAR_SUPPORT) {
                        rejected++
                        return@forEach
                    }
                    val brightProtection = 1f - smoothStep(
                        maxOf(stats.starBrightnessMedian, stats.highPercentile),
                        maxOf(stats.brightStarCorePercentile, stats.estimatedSafeWhitePoint),
                        centerLuminance
                    )
                    val requestedGain = maxOf(
                        minimumContrastGain,
                        (maximumDetailGain - 1f) * strength.coerceIn(0f, 1f) *
                            star.confidence.coerceIn(0f, 1f)
                    )
                    val multiplier = 1f + requestedGain * brightProtection
                    if (multiplier <= 1.001f) {
                        rejected++
                        return@forEach
                    }
                    var changed = false
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        val distanceSquared = dx * dx + dy * dy
                        if (distanceSquared > radius * radius) continue
                        val x = cx + dx
                        val y = cy + dy
                        if (x !in 0 until input.width || y !in 0 until input.height ||
                            alpha.alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD
                        ) continue
                        val key = y.toLong() * input.width + x
                        val color = changes[key] ?: image.argbAt(x, y)
                        val luminance = linearLuminance(color)
                        val localDetail = luminance - background
                        if (localDetail < detail * MIN_CORE_DETAIL_FRACTION || luminance <= MIN_DETAIL) continue
                        val radialWeight = 1f - distanceSquared.toFloat() / (radius * radius + 1f)
                        val gain = 1f + (multiplier - 1f) * radialWeight
                        val target = (background + localDetail * gain).coerceAtMost(MAX_ENHANCED_VALUE)
                        val scale = target / luminance
                        changes[key] = packLinear(
                            linearChannel(color, 16) * scale,
                            linearChannel(color, 8) * scale,
                            linearChannel(color, 0) * scale
                        )
                        changed = true
                    }
                    if (changed) enhanced++ else rejected++
                }
            }
        }
        val output = transform("stars", input, alphaPlane, store, budget, tracker, halo = 9) {
                x, y, color, _, _ -> changes[y.toLong() * input.width + x] ?: color
        }
        return output to StarEnhancementDiagnostics(
            strength,
            stars.size,
            enhanced,
            rejected,
            0f.coerceAtMost(maximumWidthGrowth)
        )
    }

    private fun blendPass(
        original: FileBackedImage,
        processed: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        amount: Float,
        label: String,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker
    ): FileBackedImage {
        val decision = budget.chooseTile(
            original.width,
            original.height,
            minOf(original.width, PREFERRED_TILE_SIZE),
            minOf(original.height, PREFERRED_TILE_SIZE),
            argbBuffers = 3,
            floatBuffers = 1
        )
        require(decision.accepted) { "Insufficient safe memory for Stage 4 $label" }
        if (decision.retryRequired) tracker.recordRetry()
        tracker.recordTile("stage4-$label", decision.tileWidth, decision.tileHeight)
        tracker.recordBoundary("stage4-$label", decision.estimatedBytes, 0)
        val writer = store.createTemporaryWriter(label, original.width, original.height)
        FileBackedImageReader(original).use { firstReader ->
            FileBackedImageReader(processed).use { secondReader ->
                FileBackedFloatPlaneReader(alphaPlane).use { alpha ->
                    var top = 0
                    while (top < original.height) {
                        val tileHeight = minOf(decision.tileHeight, original.height - top)
                        var left = 0
                        while (left < original.width) {
                            val tileWidth = minOf(decision.tileWidth, original.width - left)
                            val pixels = IntArray(tileWidth * tileHeight)
                            for (row in 0 until tileHeight) for (column in 0 until tileWidth) {
                                val x = left + column
                                val y = top + row
                                val first = firstReader.argbAt(x, y)
                                pixels[row * tileWidth + column] = if (
                                    alpha.alphaAt(x, y) <= OPERATION_ALPHA_THRESHOLD
                                ) {
                                    first
                                } else {
                                    val second = secondReader.argbAt(x, y)
                                    packLinear(
                                        linearChannel(first, 16) +
                                            (linearChannel(second, 16) - linearChannel(first, 16)) * amount,
                                        linearChannel(first, 8) +
                                            (linearChannel(second, 8) - linearChannel(first, 8)) * amount,
                                        linearChannel(first, 0) +
                                            (linearChannel(second, 0) - linearChannel(first, 0)) * amount
                                    )
                                }
                            }
                            writer.writeTile(left, top, tileWidth, tileHeight, pixels)
                            left += tileWidth
                        }
                        top += tileHeight
                    }
                }
            }
        }
        return writer.finish()
    }

    private inline fun transform(
        label: String,
        input: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker,
        halo: Int = 0,
        crossinline operation: (
            x: Int,
            y: Int,
            color: Int,
            image: FileBackedImageReader,
            alpha: AlphaPixelSource
        ) -> Int
    ): FileBackedImage {
        val decision = budget.chooseTile(
            input.width,
            input.height,
            minOf(input.width, PREFERRED_TILE_SIZE),
            minOf(input.height, PREFERRED_TILE_SIZE),
            halo = halo,
            argbBuffers = 2,
            floatBuffers = 1
        )
        require(decision.accepted) { "Insufficient safe memory for Stage 4 $label" }
        if (decision.retryRequired) tracker.recordRetry()
        tracker.recordTile("stage4-$label", decision.tileWidth, decision.tileHeight, halo)
        tracker.recordBoundary("stage4-$label", decision.estimatedBytes, 0)
        val writer = store.createTemporaryWriter(label, input.width, input.height)
        FileBackedImageReader(input, cachedRows = maxOf(4, halo * 2 + 3)).use { image ->
            FileBackedFloatPlaneReader(alphaPlane, cachedRows = maxOf(4, halo * 2 + 3)).use { alpha ->
                var top = 0
                while (top < input.height) {
                    val tileHeight = minOf(decision.tileHeight, input.height - top)
                    var left = 0
                    while (left < input.width) {
                        val tileWidth = minOf(decision.tileWidth, input.width - left)
                        val pixels = IntArray(tileWidth * tileHeight)
                        for (row in 0 until tileHeight) for (column in 0 until tileWidth) {
                            val x = left + column
                            val y = top + row
                            pixels[row * tileWidth + column] = operation(x, y, image.argbAt(x, y), image, alpha)
                        }
                        writer.writeTile(left, top, tileWidth, tileHeight, pixels)
                        left += tileWidth
                    }
                    top += tileHeight
                }
            }
        }
        return writer.finish()
    }

    private fun finalSafetyScale(
        before: SkyStatisticsResult,
        after: SkyStatisticsResult,
        maximumMedianFactor: Float,
        targetDisplaySkyMedian: Float,
        maximumClippingPercent: Float
    ): Float {
        if (before.skyPixelCount == 0 || after.skyPixelCount == 0) return 0f
        val allowedMedian = maxOf(
            SrgbTransfer.srgbToLinear(targetDisplaySkyMedian),
            before.luminanceMedian * maximumMedianFactor,
            before.luminanceMedian + maxOf(before.luminanceMad * 2f, MIN_MEDIAN_HEADROOM)
        )
        val medianScale = if (after.luminanceMedian > allowedMedian && after.luminanceMedian > before.luminanceMedian) {
            ((allowedMedian - before.luminanceMedian) /
                (after.luminanceMedian - before.luminanceMedian)).coerceIn(0f, 1f)
        } else 1f
        fun clippingScale(beforeValue: Float, afterValue: Float): Float {
            val allowed = maxOf(beforeValue, maximumClippingPercent)
            return if (afterValue > allowed && afterValue > beforeValue) {
                ((allowed - beforeValue) / (afterValue - beforeValue)).coerceIn(0f, 1f)
            } else 1f
        }
        return minOf(
            medianScale,
            clippingScale(before.channelClippingPercent.red, after.channelClippingPercent.red),
            clippingScale(before.channelClippingPercent.green, after.channelClippingPercent.green),
            clippingScale(before.channelClippingPercent.blue, after.channelClippingPercent.blue)
        )
    }

    private data class Channels(val red: Float, val green: Float, val blue: Float, val luminance: Float)

    private fun channels(color: Int): Channels {
        val red = linearChannel(color, 16)
        val green = linearChannel(color, 8)
        val blue = linearChannel(color, 0)
        return Channels(red, green, blue, 0.2126f * red + 0.7152f * green + 0.0722f * blue)
    }

    private fun fitChroma(luminance: Float, redChroma: Float, blueChroma: Float): Channels {
        var scale = 1f
        repeat(6) {
            val red = luminance + redChroma * scale
            val blue = luminance + blueChroma * scale
            val green = (luminance - 0.2126f * red - 0.0722f * blue) / 0.7152f
            if (red in 0f..1f && green in 0f..1f && blue in 0f..1f) return Channels(red, green, blue, luminance)
            scale *= 0.75f
        }
        return Channels(luminance, luminance, luminance, luminance)
    }

    private fun annulusMedian(
        image: FileBackedImageReader,
        mask: AlphaPixelSource,
        centerX: Int,
        centerY: Int,
        width: Float
    ): Float {
        val inner = ceil(maxOf(2f, width * 1.4f)).toInt()
        val outer = (inner + 3).coerceAtMost(9)
        val values = FloatArray((outer * 2 + 1) * (outer * 2 + 1))
        var count = 0
        for (dy in -outer..outer) for (dx in -outer..outer) {
            val distance = dx * dx + dy * dy
            if (distance < inner * inner || distance > outer * outer) continue
            val x = centerX + dx
            val y = centerY + dy
            if (x !in 0 until image.width || y !in 0 until image.height ||
                mask.alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD
            ) continue
            values[count++] = linearLuminance(image.argbAt(x, y))
        }
        if (count < MIN_ANNULUS_SAMPLES) return Float.NaN
        values.sort(0, count)
        return values[count / 2]
    }

    private fun validStarShape(star: DetectedStar): Boolean =
        star.confidence >= 0.32f && star.width in 0.65f..4.4f &&
            star.ellipticity <= 0.62f && star.localContrast > 0f

    private fun isSingleChannelSpike(color: Int, background: Float, detail: Float): Boolean {
        val deltas = floatArrayOf(
            linearChannel(color, 16) - background,
            linearChannel(color, 8) - background,
            linearChannel(color, 0) - background
        ).sortedDescending()
        return deltas[0] > maxOf(0.06f, detail * 0.8f) && deltas[1] < maxOf(0.012f, deltas[0] * 0.28f)
    }

    private fun increment(histograms: IntArray, cell: Int, channel: Int, value: Float) {
        val offset = (cell * CHANNELS_PER_CELL + channel) * MODEL_BINS
        histograms[offset + histogramBin(value, MODEL_BINS)]++
    }

    private fun channelHistogram(histograms: IntArray, cell: Int, channel: Int): IntArray {
        val offset = (cell * CHANNELS_PER_CELL + channel) * MODEL_BINS
        return histograms.copyOfRange(offset, offset + MODEL_BINS)
    }

    private fun fillMissing(model: FloatArray, grid: GradientGrid, fallback: Float) {
        val valid = model.indices.filter { model[it].isFinite() }
        model.indices.forEach { index ->
            if (model[index].isFinite()) return@forEach
            val row = index / grid.columns
            val column = index % grid.columns
            var weighted = 0f
            var weights = 0f
            valid.forEach { candidate ->
                val dy = row - candidate / grid.columns
                val dx = column - candidate % grid.columns
                val weight = 1f / (1f + dx * dx + dy * dy)
                weighted += model[candidate] * weight
                weights += weight
            }
            model[index] = if (weights > 0f) weighted / weights else fallback
        }
    }

    private fun smooth(source: FloatArray, grid: GradientGrid): FloatArray = FloatArray(source.size) { index ->
        val row = index / grid.columns
        val column = index % grid.columns
        var sum = 0f
        var weights = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val x = column + dx
            val y = row + dy
            if (x !in 0 until grid.columns || y !in 0 until grid.rows) continue
            val weight = if (dx == 0 && dy == 0) 4f else if (dx == 0 || dy == 0) 2f else 1f
            sum += source[y * grid.columns + x] * weight
            weights += weight
        }
        sum / weights.coerceAtLeast(1f)
    }

    private data class GradientGrid(val columns: Int, val rows: Int, val width: Int, val height: Int) {
        val cellCount = columns * rows
        val approximateCellArea = ((width.toLong() * height) / cellCount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        fun cellIndex(x: Int, y: Int): Int {
            val column = (x.toLong() * columns / width).toInt().coerceIn(0, columns - 1)
            val row = (y.toLong() * rows / height).toInt().coerceIn(0, rows - 1)
            return row * columns + column
        }
        fun sample(values: FloatArray, x: Int, y: Int): Float {
            val gx = ((x + 0.5f) * columns / width - 0.5f).coerceIn(0f, columns - 1f)
            val gy = ((y + 0.5f) * rows / height - 0.5f).coerceIn(0f, rows - 1f)
            val left = floor(gx).toInt()
            val top = floor(gy).toInt()
            val right = minOf(columns - 1, left + 1)
            val bottom = minOf(rows - 1, top + 1)
            val fx = gx - left
            val fy = gy - top
            val upper = values[top * columns + left] * (1f - fx) + values[top * columns + right] * fx
            val lower = values[bottom * columns + left] * (1f - fx) + values[bottom * columns + right] * fx
            return upper * (1f - fy) + lower * fy
        }
        companion object {
            fun create(width: Int, height: Int): GradientGrid {
                val target = (minOf(width, height) / 8).coerceIn(24, 320)
                return GradientGrid(
                    ((width + target - 1) / target).coerceIn(4, 24),
                    ((height + target - 1) / target).coerceIn(3, 18),
                    width,
                    height
                )
            }
        }
    }

    companion object {
        private const val TOTAL_STAGES = 6
        private const val PREFERRED_TILE_SIZE = 256
        private const val MODEL_BINS = 512
        private const val CHANNELS_PER_CELL = 4
        private const val MIN_CELL_SAMPLES = 16
        private const val MIN_SKY_CELL_FRACTION = 0.08f
        private const val MIN_TEXTURE_LIMIT = 0.035f
        private const val SATURATED_SAMPLE_LIMIT = 0.985f
        private const val MIN_LUMINANCE = 0.000001f
        private const val MAX_UNCLIPPED_VALUE = 0.995f
        private const val MIN_MEDIAN_HEADROOM = 1f / 4095f
        private const val MIN_EDGE_LIMIT = 0.008f
        private const val CHROMA_NOISE_REFERENCE = 0.012f
        private const val CENTER_WEIGHT = 2f
        private const val MIN_DETAIL = 0.0015f
        private const val SATURATED_STAR_LIMIT = 0.985f
        private const val MIN_STAR_SUPPORT = 2
        private const val MAX_STAR_SUPPORT = 24
        private const val MIN_ANNULUS_SAMPLES = 12
        private const val MIN_CORE_DETAIL_FRACTION = 0.18f
        private const val MAX_ENHANCED_VALUE = 0.985f
    }
}
