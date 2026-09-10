package com.joe6355.astrophoto

import android.util.Log
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.AutomaticSensorDefectMaskDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.AutomaticSensorDefectMaskStageDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.joe6355.astrophoto.processing.jpeg.v2.composition.MaskFeathering
import com.joe6355.astrophoto.processing.jpeg.v2.composition.FileBackedCompositeResult
import com.joe6355.astrophoto.processing.jpeg.v2.composition.FileBackedSkyForegroundComposer
import com.joe6355.astrophoto.processing.jpeg.v2.composition.ReferenceStarSignalPreserver
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.fileBackedDisplayPixelHash
import com.joe6355.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.joe6355.astrophoto.processing.jpeg.v2.masking.ForegroundProtectionMask
import com.joe6355.astrophoto.processing.jpeg.v2.masking.SkyMaskRefiner
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar as V2DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.CompositeDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.model.IntegrationDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.joe6355.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.joe6355.astrophoto.processing.jpeg.v2.model.SensorDefectConstructionStageReport
import com.joe6355.astrophoto.processing.jpeg.v2.model.StoredResultCandidate
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionEvidence
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionResult
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionStage
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.CachedArgbFrame
import com.joe6355.astrophoto.processing.jpeg.v2.memory.ImageAllocationEstimate
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.joe6355.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.joe6355.astrophoto.JpegStacker.Companion.PROFILE_REGISTRATION_TAG

internal data class FileBackedMaskStageResult(
    val referenceCandidate: FileBackedImage,
    val featheredSkyMask: FileBackedFloatPlane,
    val confidence: Float,
    val initialSkyRatio: Float,
    val refinedSkyRatio: Float,
    val protectedForegroundRatio: Float,
    val thinStructureProtectedPixels: Int,
    val protectionRadius: Int,
    val featherRadius: Int
)

internal fun JpegStacker.buildFileBackedReferenceAndMask(
    selectedReference: ProfileAnalyzedFrame,
    initialSkyMask: SkyMask,
    fullResolutionStars: List<V2DetectedStar>,
    registrationConfidence: Float,
    targetWidth: Int,
    targetHeight: Int,
    candidateStore: ResultCandidateStore,
    memoryBudget: JpegMemoryBudget,
    memoryTracker: PipelineMemoryTracker
): FileBackedMaskStageResult {
    val writer = candidateStore.createWriter(
        ResultCandidateType.REFERENCE,
        targetWidth,
        targetHeight
    )
    val decodeEstimate = ImageAllocationEstimate.bitmap(
        targetWidth,
        targetHeight,
        "reference-bitmap-decode"
    )
    memoryBudget.requireAllocation(decodeEstimate)
    memoryTracker.recordBoundary("reference-bitmap-decode", decodeEstimate.bytes, 1)
    val bitmap = decodeMedianFrame(selectedReference.frame, targetWidth, targetHeight)
        ?: error("Не удалось прочитать опорный кадр для композиции")
    try {
        val row = IntArray(targetWidth)
        for (y in 0 until targetHeight) {
            bitmap.getPixels(row, 0, targetWidth, 0, y, targetWidth, 1)
            writer.writeRow(y, row)
        }
    } finally {
        bitmap.recycle()
    }
    val reference = candidateStore.register(ResultCandidateType.REFERENCE, writer.finish())
    val maskStageBytes = ImageAllocationEstimate.intArray(targetWidth, targetHeight).bytes +
        ImageAllocationEstimate.floatArray(targetWidth, targetHeight).bytes +
        ImageAllocationEstimate.booleanMask(targetWidth, targetHeight).bytes * 4L
    memoryBudget.requireAllocation(
        ImageAllocationEstimate("reference-mask-stage", maskStageBytes)
    )
    memoryTracker.recordBoundary("reference-mask-stage", maskStageBytes, 1)

    val referencePixels = IntArray(targetWidth * targetHeight)
    FileBackedImageReader(reference).use { reader ->
        val row = IntArray(targetWidth)
        for (y in 0 until targetHeight) {
            reader.readArgbRow(y, row)
            row.copyInto(referencePixels, destinationOffset = y * targetWidth)
        }
    }
    val referenceImage = ArgbPixelImage(targetWidth, targetHeight, referencePixels)
    val initialRefinedMask = SkyMaskRefiner().refine(
        initialMask = initialSkyMask,
        reference = referenceImage,
        stars = fullResolutionStars,
        initialConfidence = selectedReference.skyMask.confidence,
        initialUsedFallback = selectedReference.skyMask.usedFallback,
        registrationConfidence = registrationConfidence
    )
    val protection = ForegroundProtectionMask().detect(
        referenceImage,
        fullResolutionStars
    )
    val finalSkyPixels = initialRefinedMask.binaryMask.copyPixels()
    val protectionPixels = protection.mask.copyPixels()
    finalSkyPixels.indices.forEach { index ->
        if (protectionPixels[index]) finalSkyPixels[index] = false
    }
    val finalBinarySkyMask = SkyMask(targetWidth, targetHeight, finalSkyPixels)
    val feathering = MaskFeathering().feather(
        initialRefinedMask.binaryMask,
        protection.mask,
        radiusOverride = initialRefinedMask.diagnostics.featherRadius
    )
    val featherWriter = candidateStore.createFloatPlaneWriter(
        "feathered-sky",
        targetWidth,
        targetHeight
    )
    val alphaRow = FloatArray(targetWidth)
    for (y in 0 until targetHeight) {
        for (x in 0 until targetWidth) {
            alphaRow[x] = feathering.alphaMask.alphaAt(x, y)
        }
        featherWriter.writeRow(y, alphaRow)
    }
    return FileBackedMaskStageResult(
        referenceCandidate = reference,
        featheredSkyMask = featherWriter.finish(),
        confidence = initialRefinedMask.confidence,
        initialSkyRatio = initialRefinedMask.diagnostics.initialSkyRatio,
        refinedSkyRatio = finalBinarySkyMask.retainedFraction(),
        protectedForegroundRatio = 1f - finalBinarySkyMask.retainedFraction(),
        thinStructureProtectedPixels = protection.protectedPixelCount,
        protectionRadius = protection.dilationRadius,
        featherRadius = feathering.broadRadius
    )
}

internal fun JpegStacker.alphaCoverageRatio(plane: FileBackedFloatPlane): Float {
    var positive = 0L
    FileBackedFloatPlaneReader(plane).use { reader ->
        val row = FloatArray(plane.width)
        for (y in 0 until plane.height) {
            reader.readAlphaRow(y, row)
            positive += row.count { it > 0f }
        }
    }
    return positive.toFloat() / (plane.width.toLong() * plane.height).coerceAtLeast(1L)
}

internal fun JpegStacker.profileMetricsFromQuality(
    metrics: com.joe6355.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
): ProfileSanityMetrics = ProfileSanityMetrics(
    width = metrics.width,
    height = metrics.height,
    stars = metrics.reliableStarCount,
    averageStarContrast = metrics.medianStarLocalContrast,
    blackPercent = metrics.blackBorderRatio * 100f,
    whitePercent = maxOf(
        metrics.channelClippingPercent.red,
        metrics.channelClippingPercent.green,
        metrics.channelClippingPercent.blue
    ),
    background = (metrics.skyMedian * 255f).roundToInt().coerceIn(0, 255),
    dynamicRange = ((metrics.skyHighPercentile - metrics.skyLowPercentile) * 255f)
        .roundToInt().coerceAtLeast(0),
    channelsValid = true,
    medianStarContrast = metrics.medianStarLocalContrast,
    lowPercentile = (metrics.skyLowPercentile * 255f).roundToInt().coerceIn(0, 255),
    highPercentile = (metrics.skyHighPercentile * 255f).roundToInt().coerceIn(0, 255),
    backgroundSpread = (metrics.skyMad * 255f).roundToInt().coerceAtLeast(0),
    largeScaleBanding = metrics.banding.combinedScore
)

internal data class CandidateMaskLineage(
    val stage: String,
    val sampleFilteringPresent: Boolean,
    val parent: CandidateMaskLineage? = null
)

internal data class AutomaticReferenceStarPreservationResult(
    val image: FileBackedImage,
    val maskedReferenceSamplesSkipped: Long,
    val affectedOutputPixelCount: Int,
    val maskAware: Boolean,
    val reason: String?,
    val retentionStages: List<ReferenceStarRetentionStage>
)

internal fun JpegStacker.logAutomaticIntegration(
    profile: AstroProcessingProfile,
    referenceFileName: String,
    inputWidth: Int,
    inputHeight: Int,
    rejectedFrames: Int,
    diagnostics: IntegrationDiagnostics,
    filtering: SensorDefectFilteringReport
) {
    Log.i(
        PROFILE_REGISTRATION_TAG,
        "preset=${profile.name} reference=$referenceFileName " +
            "inputResolution=${inputWidth}x$inputHeight " +
            "outputResolution=${diagnostics.outputWidth}x${diagnostics.outputHeight} " +
            "tileSize=${diagnostics.tileWidth}x${diagnostics.tileHeight} " +
            "acceptedFrames=${diagnostics.acceptedFrames} rejectedFrames=$rejectedFrames " +
            "integrationMode=${diagnostics.mode} robustMode=${diagnostics.robustModeEnabled} " +
            "validCoverage=${formatMetric(diagnostics.validCoveragePercent)} " +
            "sensorDefectFilteringApplied=${filtering.filteringAppliedToFinalResult} " +
            "sensorDefectExcludedSamples=${filtering.excludedSampleCount} " +
            "sensorDefectAffectedPixels=${filtering.affectedOutputPixelCount} " +
            "sensorDefectInsufficientPixels=${filtering.insufficientCoveragePixelCount} " +
            "sensorDefectUnmaskedRetry=${filtering.unmaskedRetryUsed} " +
            "estimatedPeakWorkingMemory=${diagnostics.estimatedPeakWorkingMemoryBytes} " +
            "resolutionChanged=${diagnostics.resolutionChanged}"
    )
}

internal fun JpegStacker.logAutomaticComposition(
    profile: AstroProcessingProfile,
    referenceFileName: String,
    maskStage: FileBackedMaskStageResult,
    composite: FileBackedCompositeResult
) {
    Log.i(
        PROFILE_REGISTRATION_TAG,
        "preset=${profile.name} reference=$referenceFileName " +
            "refinedSkyMaskConfidence=${formatMetric(maskStage.confidence)} " +
            "initialSkyRatio=${formatMetric(maskStage.initialSkyRatio)} " +
            "refinedSkyRatio=${formatMetric(maskStage.refinedSkyRatio)} " +
            "protectedForegroundRatio=${formatMetric(maskStage.protectedForegroundRatio)} " +
            "thinStructureProtectedPixelCount=${maskStage.thinStructureProtectedPixels} " +
            "protectionRadius=${maskStage.protectionRadius} " +
            "featherRadius=${maskStage.featherRadius} " +
            "validSkyCoverageRatio=" +
            formatMetric(composite.diagnostics.validSkyCoverageRatio) +
            " sensorDefectReferenceSamplesSkipped=" +
            composite.diagnostics.maskedReferenceSamplesSkipped +
            " sensorDefectCompositionPixels=" +
            composite.diagnostics.sensorDefectAffectedOutputPixels +
            " output=${composite.diagnostics.outputWidth}x" +
            composite.diagnostics.outputHeight
    )
}

internal fun JpegStacker.composeCleanCandidate(
    stackedSky: FileBackedImage,
    reference: FileBackedImage,
    featheredSkyMask: FileBackedFloatPlane,
    validCoverage: FileBackedFloatPlane,
    sensorDefectAffectedOutput: FileBackedFloatPlane?,
    sensorDefectMask: SensorDefectMask?,
    candidateStore: ResultCandidateStore,
    memoryBudget: JpegMemoryBudget,
    memoryTracker: PipelineMemoryTracker
): FileBackedCompositeResult {
    val cleanWriter = candidateStore.createWriter(
        ResultCandidateType.CLEAN_STACK,
        reference.width,
        reference.height
    )
    val effectiveAlphaWriter = candidateStore.createFloatPlaneWriter(
        "effective-sky",
        reference.width,
        reference.height
    )
    return sensorDefectAffectedOutput
        ?.let(::FileBackedFloatPlaneReader)
        .use { affectedOutput ->
            FileBackedFloatPlaneReader(featheredSkyMask).use { feathered ->
                FileBackedFloatPlaneReader(validCoverage).use { coverage ->
                    FileBackedSkyForegroundComposer().compose(
                        stackedSky = stackedSky,
                        reference = reference,
                        featheredSkyMask = feathered,
                        validCoverage = coverage,
                        output = cleanWriter,
                        effectiveAlphaOutput = effectiveAlphaWriter,
                        memoryBudget = memoryBudget,
                        memoryTracker = memoryTracker,
                        sensorDefectAffectedOutput = affectedOutput,
                        sensorDefectMask = sensorDefectMask
                    )
                }
            }
        }
}

internal fun JpegStacker.preserveAutomaticReferenceStars(
    stackedSky: FileBackedImage,
    reference: FileBackedImage,
    stars: List<V2DetectedStar>,
    store: ResultCandidateStore,
    sensorDefectMask: SensorDefectMask?,
    sensorDefectAffectedOutput: FileBackedFloatPlane?
): AutomaticReferenceStarPreservationResult {
    val maskedStage = measureReferenceStarRetention(
        "masked_clean_integration",
        reference,
        stackedSky,
        stars
    )
    return sensorDefectAffectedOutput
        ?.let(::FileBackedFloatPlaneReader)
        .use { affectedOutput ->
            val preservation = ReferenceStarSignalPreserver().preserve(
                stackedSky = stackedSky,
                reference = reference,
                stars = stars,
                store = store,
                sensorDefectMask = sensorDefectMask,
                sensorDefectAffectedOutput = affectedOutput
            )
            val stages = listOf(
                maskedStage,
                measureReferenceStarRetention(
                    "star_preserved_masked_integration",
                    reference,
                    preservation.image,
                    stars
                )
            ).map { stage ->
                stage.withMaskLineageEvidence(
                    stars,
                    sensorDefectMask,
                    affectedOutput
                )
            }
            AutomaticReferenceStarPreservationResult(
                image = preservation.image,
                maskedReferenceSamplesSkipped =
                    preservation.maskedReferenceSamplesSkipped,
                affectedOutputPixelCount = preservation.affectedOutputPixelCount,
                maskAware = preservation.maskAware,
                reason = preservation.reason,
                retentionStages = stages
            )
        }
}

internal fun JpegStacker.candidateMaskLineage(
    filtering: SensorDefectFilteringReport,
    maskDiagnostics: AutomaticSensorDefectMaskDiagnostics,
    selected: StoredResultCandidate,
    reference: StoredResultCandidate,
    clean: StoredResultCandidate,
    processed: StoredResultCandidate?,
    starPreservation: AutomaticReferenceStarPreservationResult,
    composition: CompositeDiagnostics,
    stars: List<V2DetectedStar>,
    cleanRetention: ReferenceStarRetentionResult,
    effectiveSkyAlpha: FileBackedFloatPlane,
    integrationFrames: List<WeightedIntegrationFrame<CachedArgbFrame>>,
    sensorDefectMask: SensorDefectMask?
): SensorDefectFilteringReport {
    val integration = CandidateMaskLineage(
        stage = "automatic_integration",
        sampleFilteringPresent =
            filtering.filteringAppliedToFinalResult &&
                filtering.sampleLevelFilteringApplied &&
                filtering.excludedSampleCount > 0L
    )
    val starPreserved = CandidateMaskLineage(
        stage = "reference_star_preservation",
        sampleFilteringPresent = integration.sampleFilteringPresent,
        parent = integration
    )
    val cleanLineage = CandidateMaskLineage(
        stage = "sky_foreground_composition",
        sampleFilteringPresent = starPreserved.sampleFilteringPresent,
        parent = starPreserved
    )
    val processedLineage = processed?.let {
        CandidateMaskLineage(
            stage = "processed_profile",
            sampleFilteringPresent = starPreserved.sampleFilteringPresent,
            parent = starPreserved
        )
    }
    val selectedLineage = when {
        selected === clean -> cleanLineage
        processed != null && selected === processed -> checkNotNull(processedLineage)
        selected === reference -> CandidateMaskLineage(
        stage = "reference",
        sampleFilteringPresent = false
        )
        else -> error("Selected candidate has no pixel lineage")
    }
    val referenceStarRetentionStages = completeReferenceStarRetentionLineage(
        recorded = starPreservation.retentionStages,
        reference = reference.image,
        cleanRetention = cleanRetention,
        processed = processed?.image,
        selectedType = selected.type,
        stars = stars,
        effectiveSkyAlpha = effectiveSkyAlpha,
        integrationFrames = integrationFrames,
        sensorDefectMask = sensorDefectMask
    )
    return filtering.copy(
        cleanCandidateMasked = cleanLineage.sampleFilteringPresent,
        processedCandidateMasked = processedLineage?.sampleFilteringPresent == true,
        selectedCandidateMasked = selectedLineage.sampleFilteringPresent,
        selectedCandidateHash = fileBackedDisplayPixelHash(selected.image),
        starPreservationMaskAware = starPreservation.maskAware,
        starPreservationMaskedReferenceSamplesSkipped =
            starPreservation.maskedReferenceSamplesSkipped,
        starPreservationAffectedOutputPixelCount =
            starPreservation.affectedOutputPixelCount,
        starPreservationReason = starPreservation.reason,
        compositionMaskAware =
            composition.sensorDefectProtectionReason != null,
        compositionMaskedReferenceSamplesSkipped =
            composition.maskedReferenceSamplesSkipped,
        compositionAffectedOutputPixelCount =
            composition.sensorDefectAffectedOutputPixels,
        compositionMeanOriginalAlpha =
            composition.meanOriginalAlphaAtProtectedPixels,
        compositionReason = composition.sensorDefectProtectionReason,
        compositionSafeBehavior =
            composition.sensorDefectProtectionReason?.let {
                "keep_masked_clean_sky_sample"
            },
        observationFrameCount = maskDiagnostics.inputFrameCount,
        observationCandidateCount = maskDiagnostics.observationCandidateCount,
        observationProcessedPixelCount =
            maskDiagnostics.observationProcessedPixelCount,
        additionalImageDecodeCount = maskDiagnostics.additionalImageDecodeCount,
        additionalFullFrameScanCount =
            maskDiagnostics.additionalFullFrameScanCount,
        candidateMatchingAnchorVisitCount =
            maskDiagnostics.candidateMatchingAnchorVisitCount,
        candidateMatchingCandidateVisitCount =
            maskDiagnostics.candidateMatchingCandidateVisitCount,
        candidateMatchingDistanceComparisonCount =
            maskDiagnostics.candidateMatchingDistanceComparisonCount,
        candidateMatchingIdentityLookupCount =
            maskDiagnostics.candidateMatchingIdentityLookupCount,
        constructionStages = sensorDefectConstructionStages(maskDiagnostics),
        referenceStarRetentionStages = referenceStarRetentionStages
    )
}

internal fun JpegStacker.sensorDefectConstructionStages(
    diagnostics: AutomaticSensorDefectMaskDiagnostics
): List<SensorDefectConstructionStageReport> {
    fun stage(
        name: String,
        value: AutomaticSensorDefectMaskStageDiagnostics
    ) = SensorDefectConstructionStageReport(
        name,
        value.elapsedNanos,
        value.inputCount,
        value.outputCount,
        value.processedUnitCount,
        value.estimatedAllocatedBytes
    )
    return listOf(
        SensorDefectConstructionStageReport(
            "additional_mask_decoding",
            0L,
            diagnostics.inputFrameCount,
            diagnostics.additionalImageDecodeCount,
            0L,
            0L
        ),
        stage(
            "persistent_observation_extraction",
            diagnostics.persistentObservationExtraction
        ),
        stage("candidate_matching", diagnostics.candidateMatching),
        stage("recurrence_calculation", diagnostics.recurrenceCalculation),
        stage("footprint_construction", diagnostics.footprintConstruction),
        stage("mask_validation", diagnostics.maskValidation)
    )
}

internal fun JpegStacker.completeReferenceStarRetentionLineage(
    recorded: List<ReferenceStarRetentionStage>,
    reference: FileBackedImage,
    cleanRetention: ReferenceStarRetentionResult,
    processed: FileBackedImage?,
    selectedType: ResultCandidateType,
    stars: List<V2DetectedStar>,
    effectiveSkyAlpha: FileBackedFloatPlane,
    integrationFrames: List<WeightedIntegrationFrame<CachedArgbFrame>>,
    sensorDefectMask: SensorDefectMask?
): List<ReferenceStarRetentionStage> {
    val composed = ReferenceStarRetentionStage(
        "composed_clean_result",
        cleanRetention.metrics,
        cleanRetention.sources
    )
    val processedStage = processed?.let {
        measureReferenceStarRetention(
            "processed_profile_candidate",
            reference,
            it,
            stars
        )
    }
    val selectedStage = when (selectedType) {
        ResultCandidateType.REFERENCE -> measureReferenceStarRetention(
            "selected_candidate",
            reference,
            reference,
            stars
        )
        ResultCandidateType.CLEAN_STACK -> composed.copy(stage = "selected_candidate")
        ResultCandidateType.PROCESSED -> checkNotNull(processedStage).copy(
            stage = "selected_candidate"
        )
    }
    val stages = buildList {
        addAll(recorded)
        add(composed)
        processedStage?.let(::add)
        add(selectedStage)
    }
    val affectedLineage = recorded.firstOrNull()?.sources.orEmpty()
        .associateBy { it.sourceIndex }
    val evidence = collectReferenceSourceEvidence(
        stars,
        sensorDefectMask,
        affectedLineage,
        effectiveSkyAlpha,
        integrationFrames
    )
    return stages.map { it.withReferenceSourceEvidence(evidence) }
}

internal fun JpegStacker.measureReferenceStarRetention(
    stage: String,
    reference: FileBackedImage,
    candidate: FileBackedImage,
    stars: List<V2DetectedStar>
): ReferenceStarRetentionStage = ReferenceStarRetentionValidator()
    .validate(reference, candidate, stars)
    .let { result -> ReferenceStarRetentionStage(stage, result.metrics, result.sources) }

internal fun ReferenceStarRetentionStage.withReferenceSourceEvidence(
    evidence: Map<Int, ReferenceStarRetentionEvidence>
): ReferenceStarRetentionStage = copy(
    sources = sources.map { source ->
        val item = evidence[source.sourceIndex] ?: return@map source
        source.copy(
            intersectsSourceSensorMask = item.intersectsSourceSensorMask,
            intersectsAffectedOutputLineage = item.intersectsAffectedOutputLineage,
            intersectsStarPreservationPatch = true,
            effectiveSkyAlpha = item.effectiveSkyAlpha,
            referenceContribution = 1f - item.effectiveSkyAlpha,
            validContributingFrameCount = item.validContributingFrameCount,
            validContributingFrameWeight = item.validContributingFrameWeight
        )
    }
)

internal fun ReferenceStarRetentionStage.withMaskLineageEvidence(
    stars: List<V2DetectedStar>,
    sensorDefectMask: SensorDefectMask?,
    affectedOutput: AlphaPixelSource?
): ReferenceStarRetentionStage = copy(
    sources = sources.map { source ->
        val star = stars.getOrNull(source.sourceIndex) ?: return@map source
        val width = affectedOutput?.width ?: sensorDefectMask?.width ?: 0
        val height = affectedOutput?.height ?: sensorDefectMask?.height ?: 0
        source.copy(
            intersectsSourceSensorMask = starFootprintIntersects(
                star,
                width,
                height
            ) { x, y -> sensorDefectMask?.contains(x, y) == true },
            intersectsAffectedOutputLineage = starFootprintIntersects(
                star,
                width,
                height
            ) { x, y -> affectedOutput?.alphaAt(x, y)?.let { it > 0f } == true },
            intersectsStarPreservationPatch = true
        )
    }
)

internal fun JpegStacker.collectReferenceSourceEvidence(
    stars: List<V2DetectedStar>,
    sensorDefectMask: SensorDefectMask?,
    affectedLineage: Map<Int, com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionSource>,
    effectiveSkyAlpha: FileBackedFloatPlane,
    integrationFrames: List<WeightedIntegrationFrame<CachedArgbFrame>>
): Map<Int, ReferenceStarRetentionEvidence> =
    FileBackedFloatPlaneReader(effectiveSkyAlpha).use { alpha ->
        stars.mapIndexed { sourceIndex, star ->
            val centerX = star.x.toInt().coerceIn(0, alpha.width - 1)
            val centerY = star.y.toInt().coerceIn(0, alpha.height - 1)
            var contributingFrames = 0
            var contributingWeight = 0f
            integrationFrames.forEach { frame ->
                val point = frame.transform.referenceToSourceTransform()
                    .mapOutputToSource(centerX.toFloat(), centerY.toFloat())
                val covered = point.x.isFinite() && point.y.isFinite() &&
                    point.x >= 0f && point.y >= 0f &&
                    point.x <= frame.source.width - 1f &&
                    point.y <= frame.source.height - 1f
                if (
                    covered &&
                    sensorDefectMask?.intersectsBilinearSample(point.x, point.y) != true
                ) {
                    contributingFrames++
                    contributingWeight += frame.normalizedWeight
                }
            }
            sourceIndex to ReferenceStarRetentionEvidence(
                intersectsSourceSensorMask = starFootprintIntersects(
                    star,
                    alpha.width,
                    alpha.height
                ) { x, y -> sensorDefectMask?.contains(x, y) == true },
                intersectsAffectedOutputLineage =
                    affectedLineage[sourceIndex]?.intersectsAffectedOutputLineage == true,
                effectiveSkyAlpha = alpha.alphaAt(centerX, centerY),
                validContributingFrameCount = contributingFrames,
                validContributingFrameWeight = contributingWeight
            )
        }.toMap()
    }

internal fun starFootprintIntersects(
    star: V2DetectedStar,
    width: Int,
    height: Int,
    predicate: (Int, Int) -> Boolean
): Boolean {
    if (width <= 0 || height <= 0) return false
    val centerX = star.x.roundToInt()
    val centerY = star.y.roundToInt()
    val coreRadius = ceil(star.width * 1.8f).toInt().coerceIn(3, 7)
    val radius = coreRadius + 3
    val left = (centerX - radius).coerceAtLeast(0)
    val right = (centerX + radius).coerceAtMost(width - 1)
    val top = (centerY - radius).coerceAtLeast(0)
    val bottom = (centerY + radius).coerceAtMost(height - 1)
    for (y in top..bottom) for (x in left..right) {
        val dx = x - centerX
        val dy = y - centerY
        if (dx * dx + dy * dy <= radius * radius && predicate(x, y)) return true
    }
    return false
}
