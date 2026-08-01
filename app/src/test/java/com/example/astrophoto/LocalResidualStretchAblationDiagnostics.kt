package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.example.astrophoto.processing.jpeg.v2.model.StretchDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

internal enum class LocalResidualStretchVariantId(
    val stableId: String,
    val strength: Float?,
    val productionCandidateEligible: Boolean
) {
    CLEAN_STACK("v0-clean-stack", null, false),
    CURRENT("v1-current", null, false),
    TARGET_MEDIAN_DISABLED("v2-target-median-disabled", null, false),
    RESIDUAL_SOFT_LOW("v3-residual-soft-low", ReplayLocalBackgroundResidualStretch.LOW_STRENGTH, true),
    RESIDUAL_SOFT_MEDIUM("v4-residual-soft-medium", ReplayLocalBackgroundResidualStretch.MEDIUM_STRENGTH, true),
    RESIDUAL_SOFT_STRONG("v5-residual-soft-strong", ReplayLocalBackgroundResidualStretch.STRONG_STRENGTH, true)
}

internal enum class LocalResidualStretchDecision {
    LOCAL_RESIDUAL_STRETCH_CANDIDATE_FOUND,
    LOCAL_RESIDUAL_STRETCH_REJECTED
}

internal data class LocalResidualStretchVariant(
    val id: LocalResidualStretchVariantId,
    val output: ArgbPixelImage,
    val processedSky: ArgbPixelImage,
    val stretchOutput: ArgbPixelImage,
    val selection: ReplayCandidateSelection?,
    val stretchDiagnostics: StretchDiagnostics?,
    val localDiagnostics: LocalResidualStretchDiagnostics?,
    val outputArgbSha256: String,
    val stretchOutputArgbSha256: String
)

internal data class LocalResidualStrictStarMetric(
    val variant: LocalResidualStretchVariantId,
    val starId: String,
    val baselineLocalContrast: Double,
    val apertureFluxRetention: Double,
    val peakRetention: Double,
    val centroidShift: Double,
    val widthRatio: Double,
    val ellipticityChange: Double,
    val localContrast: Double,
    val localContrastRetention: Double,
    val chromaResidual: Double,
    val weakBaselineStar: Boolean,
    val establishedGatePassed: Boolean
)

internal data class LocalResidualBoundaryMetric(
    val variant: LocalResidualStretchVariantId,
    val window: SkyMaskWindowMetrics
)

internal data class LocalResidualSensorDefectMetric(
    val variant: LocalResidualStretchVariantId,
    val defectId: String,
    val meanResidual: Double,
    val maximumResidual: Double
)

internal data class LocalResidualDetectionMetric(
    val variant: LocalResidualStretchVariantId,
    val detectedStars: Int,
    val newDetectionsVersusClean: Int,
    val falseWeakStarDetections: Int,
    val newDetectionDetails: List<String>,
    val detectorBackground: Float,
    val detectorNoise: Float
)

internal data class LocalResidualGlobalMetric(
    val variant: LocalResidualStretchVariantId,
    val skyMad: Double,
    val bandingProxy: Double,
    val boundaryEdgeExcess: Double,
    val meanHaloScore: Double,
    val meanLeakageScore: Double,
    val foregroundMeanChange: Double,
    val luminanceMean: Double,
    val luminanceMedian: Double,
    val clippedLowPixels: Int,
    val clippedHighPixels: Int,
    val chromaResidual: Double,
    val sensorDefectResidual: Double,
    val weakStarMedianContrastGain: Double,
    val maximumStrictStarWidthRatio: Double,
    val strictStarGatePassed: Boolean,
    val processedAccepted: Boolean,
    val rejectionReasons: List<String>,
    val selectedCandidate: String,
    val newDetectionsVersusClean: Int,
    val falseWeakStarDetections: Int,
    val backgroundPreservedByOperation: Boolean,
    val acceptableProductionCandidate: Boolean
)

internal data class LocalResidualStretchAblationBundle(
    val baseline: SkyMaskReplayBundle,
    val variants: List<LocalResidualStretchVariant>,
    val strictStarMetrics: List<LocalResidualStrictStarMetric>,
    val boundaryMetrics: List<LocalResidualBoundaryMetric>,
    val sensorDefectMetrics: List<LocalResidualSensorDefectMetric>,
    val detectionMetrics: List<LocalResidualDetectionMetric>,
    val globalMetrics: List<LocalResidualGlobalMetric>,
    val cleanStackMetrics: SkyMaskPostProcessStageMetrics,
    val medianStrictStarPsfWidth: Float,
    val preparedInput: LocalResidualPreparedInput,
    val decision: LocalResidualStretchDecision,
    val decisionEvidence: String,
    val productionCandidate: LocalResidualStretchVariantId?,
    val productionSourceChanged: Boolean
)

internal data class LocalResidualStretchAblationWriteResult(
    val fileCount: Int,
    val treeSha256: String,
    val manifestSha256: String,
    val summarySha256: String,
    val strictStarSha256: String,
    val htmlSha256: String
)

internal class LocalResidualStretchAblationDiagnosticRunner {
    suspend fun analyze(fixture: Stage6RegressionFixture): LocalResidualStretchAblationBundle {
        val productionRoot = Path.of("src/main")
        val productionHashBefore = treeHash(productionRoot)
        val baseline = SkyMaskReplayDiagnosticRunner().analyze(fixture)
        requireBaseline(baseline)
        val profile = AstroProcessingProfile.URBAN_SKY_STRONG
        val frameCount = baseline.acceptedOriginalFrameIndices.size
        val current = replayVariant(
            baseline,
            LocalResidualStretchVariantId.CURRENT,
            profile,
            frameCount,
            ReplayStretchOperationMode.PRODUCTION_CURRENT,
            ReplayStretchBlendMode.CURRENT
        )
        require(current.output.pixels.contentEquals(baseline.composedCurrent.pixels))
        require(current.processedSky.pixels.contentEquals(baseline.processedSky.pixels))
        val targetDisabled = replayVariant(
            baseline,
            LocalResidualStretchVariantId.TARGET_MEDIAN_DISABLED,
            profile,
            frameCount,
            ReplayStretchOperationMode.SQRT_ALPHA,
            ReplayStretchBlendMode.TARGET_MEDIAN_DISABLED
        )
        val cleanStrict = SkyMaskReplayMath.strictStarMetricsForStages(
            fixture,
            baseline.cleanStack,
            listOf(SkyMaskStarStageInput("clean-stack", baseline.cleanStack, baseline.effectiveAlpha)),
            baseline.refinedMask,
            baseline.foregroundProtection
        )
        require(cleanStrict.size == 6)
        val medianPsf = percentile(cleanStrict.map { it.robustWidth }, 0.5).toFloat()

        val lowOverride = ReplayLocalBackgroundResidualStretch(
            ReplayLocalBackgroundResidualStretch.LOW_STRENGTH,
            medianPsf
        )
        val low = residualVariant(
            baseline, LocalResidualStretchVariantId.RESIDUAL_SOFT_LOW, profile, frameCount, lowOverride
        )
        val prepared = lowOverride.prepared
        val medium = residualVariant(
            baseline,
            LocalResidualStretchVariantId.RESIDUAL_SOFT_MEDIUM,
            profile,
            frameCount,
            ReplayLocalBackgroundResidualStretch(
                ReplayLocalBackgroundResidualStretch.MEDIUM_STRENGTH,
                medianPsf,
                prepared
            )
        )
        val strong = residualVariant(
            baseline,
            LocalResidualStretchVariantId.RESIDUAL_SOFT_STRONG,
            profile,
            frameCount,
            ReplayLocalBackgroundResidualStretch(
                ReplayLocalBackgroundResidualStretch.STRONG_STRENGTH,
                medianPsf,
                prepared
            )
        )
        val clean = LocalResidualStretchVariant(
            id = LocalResidualStretchVariantId.CLEAN_STACK,
            output = baseline.cleanComposed,
            processedSky = baseline.cleanStack,
            stretchOutput = baseline.cleanStack,
            selection = null,
            stretchDiagnostics = null,
            localDiagnostics = null,
            outputArgbSha256 = ReplayDiagnosticHashing.sha256Argb(baseline.cleanComposed),
            stretchOutputArgbSha256 = ReplayDiagnosticHashing.sha256Argb(baseline.cleanStack)
        )
        val variants = listOf(clean, current, targetDisabled, low, medium, strong)
        require(variants.map { it.id } == LocalResidualStretchVariantId.entries)
        requireResidualContract(baseline, variants, prepared)

        val strict = strictStarMetrics(baseline, variants)
        val boundaries = boundaryMetrics(baseline, variants)
        val defects = sensorDefectMetrics(baseline, variants)
        val detections = detectionMetrics(baseline, variants)
        val cleanMetrics = stageMetric(baseline, clean)
        val global = globalMetrics(
            baseline, variants, strict, boundaries, defects, detections, cleanMetrics
        )
        val eligible = global.filter(LocalResidualGlobalMetric::acceptableProductionCandidate)
        val candidate = eligible.maxByOrNull {
            variants.single { variant -> variant.id == it.variant }.id.strength ?: 0f
        }?.variant
        val decision = if (candidate == null) {
            LocalResidualStretchDecision.LOCAL_RESIDUAL_STRETCH_REJECTED
        } else {
            LocalResidualStretchDecision.LOCAL_RESIDUAL_STRETCH_CANDIDATE_FOUND
        }
        val residualSummary = global.filter { it.variant.productionCandidateEligible }.joinToString("; ") {
            "${it.variant.stableId}: mad=${format(it.skyMad)}, banding=${format(it.bandingProxy)}, " +
                "accepted=${it.processedAccepted}, stars=${it.strictStarGatePassed}, " +
                "defect=${format(it.sensorDefectResidual)}, newDetections=${it.newDetectionsVersusClean}, " +
                "falseDetections=${it.falseWeakStarDetections}"
        }
        return LocalResidualStretchAblationBundle(
            baseline = baseline,
            variants = variants,
            strictStarMetrics = strict,
            boundaryMetrics = boundaries,
            sensorDefectMetrics = defects,
            detectionMetrics = detections,
            globalMetrics = global,
            cleanStackMetrics = cleanMetrics,
            medianStrictStarPsfWidth = medianPsf,
            preparedInput = prepared,
            decision = decision,
            decisionEvidence = residualSummary,
            productionCandidate = candidate,
            productionSourceChanged = productionHashBefore != treeHash(productionRoot)
        ).also { require(!it.productionSourceChanged) }
    }

    private suspend fun replayVariant(
        baseline: SkyMaskReplayBundle,
        id: LocalResidualStretchVariantId,
        profile: AstroProcessingProfile,
        frameCount: Int,
        operationMode: ReplayStretchOperationMode,
        blendMode: ReplayStretchBlendMode
    ): LocalResidualStretchVariant {
        val replay = ReplayAdaptiveSkyProcessor().process(
            stackedSky = baseline.cleanStack,
            reference = baseline.reference,
            alpha = baseline.effectiveAlpha,
            profile = profile,
            frameCount = frameCount,
            stars = baseline.alignedStackStars,
            stretchOperationMode = operationMode,
            stretchBlendMode = blendMode
        )
        return variantFromReplay(baseline, id, profile, replay, null)
    }

    private suspend fun residualVariant(
        baseline: SkyMaskReplayBundle,
        id: LocalResidualStretchVariantId,
        profile: AstroProcessingProfile,
        frameCount: Int,
        override: ReplayLocalBackgroundResidualStretch
    ): LocalResidualStretchVariant {
        require(id.strength != null)
        val replay = ReplayAdaptiveSkyProcessor().process(
            stackedSky = baseline.cleanStack,
            reference = baseline.reference,
            alpha = baseline.effectiveAlpha,
            profile = profile,
            frameCount = frameCount,
            stars = baseline.alignedStackStars,
            stretchOperationMode = ReplayStretchOperationMode.SQRT_ALPHA,
            stretchBlendMode = ReplayStretchBlendMode.CURRENT,
            stretchOverride = override
        )
        return variantFromReplay(baseline, id, profile, replay, override.diagnostics)
    }

    private fun variantFromReplay(
        baseline: SkyMaskReplayBundle,
        id: LocalResidualStretchVariantId,
        profile: AstroProcessingProfile,
        replay: ReplayProcessedSky,
        localDiagnostics: LocalResidualStretchDiagnostics?
    ): LocalResidualStretchVariant {
        val selection = selectReplayCandidate(
            reference = baseline.reference,
            clean = baseline.cleanComposed,
            processed = replay.composed,
            alpha = baseline.effectiveAlpha,
            coverage = baseline.validCoverage,
            stars = baseline.alignedStackStars,
            modelScore = baseline.alignmentModelScore,
            acceptedFrames = baseline.acceptedOriginalFrameIndices.size,
            profile = profile
        )
        val stretchOutput = replay.stages.single { it.id == "03-adaptive-stretch" }.image
        return LocalResidualStretchVariant(
            id = id,
            output = replay.composed,
            processedSky = replay.processedSky,
            stretchOutput = stretchOutput,
            selection = selection,
            stretchDiagnostics = replay.stretchDiagnostics,
            localDiagnostics = localDiagnostics,
            outputArgbSha256 = ReplayDiagnosticHashing.sha256Argb(replay.composed),
            stretchOutputArgbSha256 = ReplayDiagnosticHashing.sha256Argb(stretchOutput)
        )
    }

    private fun requireBaseline(value: SkyMaskReplayBundle) {
        require(value.fixture.name == "urban-window-30")
        require(value.reference.width == 720 && value.reference.height == 960)
        require(value.fixture.frames.size == 30)
        require(value.fixture.strictReferenceStarLabels.size == 6)
        require(value.fixture.strictSensorDefects.size == 2)
        require(value.acceptedOriginalFrameIndices == (1..21).toList() + 25)
        require(value.rejectedOriginalFrameIndices == listOf(22, 23, 24, 26, 27, 28, 29, 30))
        require(value.adaptiveReplayMatchesProductionPixels)
        require(value.activeFileBackedMaximumChannelDifference == 0)
        require(value.activeFileBackedDifferentPixelCount == 0)
        require(ReplayDiagnosticHashing.sha256Argb(value.cleanStack) == EXPECTED_CLEAN_HASH)
        require(ReplayDiagnosticHashing.sha256Argb(value.composedCurrent) == EXPECTED_CURRENT_HASH)
        require(ReplayDiagnosticHashing.sha256Alpha(value.effectiveAlpha) == EXPECTED_ALPHA_HASH)
    }

    private fun requireResidualContract(
        baseline: SkyMaskReplayBundle,
        variants: List<LocalResidualStretchVariant>,
        prepared: LocalResidualPreparedInput
    ) {
        val residuals = variants.filter { it.id.productionCandidateEligible }
        require(residuals.size == 3)
        require(residuals.map { it.localDiagnostics!!.parameters.strength } == listOf(0.12f, 0.24f, 0.36f))
        val fixedParameters = residuals.map { value ->
            value.localDiagnostics!!.parameters.copy(strength = 0f)
        }
        require(fixedParameters.distinct().size == 1)
        require(residuals.all {
            it.localDiagnostics!!.negativeResidualChangedPixels == 0 &&
                it.localDiagnostics.backgroundChangedPixels == 0
        })
        val neutralized = baseline.postProcessingStages.single {
            it.id == "02-background-neutralization"
        }.image
        require(prepared.inputArgbSha256 == ReplayDiagnosticHashing.sha256Argb(neutralized))
    }

    private fun strictStarMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<LocalResidualStretchVariant>
    ): List<LocalResidualStrictStarMetric> {
        val inputs = buildList {
            add(SkyMaskStarStageInput(
                LocalResidualStretchVariantId.CLEAN_STACK.stableId,
                baseline.cleanStack,
                baseline.effectiveAlpha
            ))
            variants.drop(1).forEach { variant ->
                add(SkyMaskStarStageInput(variant.id.stableId, variant.output, baseline.effectiveAlpha))
            }
        }
        val measured = SkyMaskReplayMath.strictStarMetricsForStages(
            baseline.fixture,
            baseline.cleanStack,
            inputs,
            baseline.refinedMask,
            baseline.foregroundProtection
        )
        val clean = measured.filter { it.stage == LocalResidualStretchVariantId.CLEAN_STACK.stableId }
            .associateBy { it.starId }
        val weakIds = clean.values.sortedBy { it.localContrast }.take(3).map { it.starId }.toSet()
        return variants.flatMap { variant ->
            measured.filter { it.stage == variant.id.stableId }.map { value ->
                val baselineValue = clean.getValue(value.starId)
                val contrastRetention = ratio(value.localContrast, baselineValue.localContrast)
                LocalResidualStrictStarMetric(
                    variant = variant.id,
                    starId = value.starId,
                    baselineLocalContrast = baselineValue.localContrast,
                    apertureFluxRetention = value.fluxRetentionFromClean,
                    peakRetention = 1.0 - value.peakAttenuationFromClean,
                    centroidShift = value.centroidShiftFromClean,
                    widthRatio = value.widthRatioFromClean,
                    ellipticityChange = abs(value.ellipticity - baselineValue.ellipticity),
                    localContrast = value.localContrast,
                    localContrastRetention = contrastRetention,
                    chromaResidual = value.chromaResidual,
                    weakBaselineStar = value.starId in weakIds,
                    establishedGatePassed = contrastRetention >= MIN_CONTRAST_RETENTION &&
                        value.centroidShiftFromClean <= MAX_CENTROID_SHIFT &&
                        value.widthRatioFromClean <= MAX_WIDTH_RATIO
                )
            }
        }
    }

    private fun boundaryMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<LocalResidualStretchVariant>
    ): List<LocalResidualBoundaryMetric> = variants.flatMap { variant ->
        SkyMaskReplayMath.windowMetricsForVariant(
            windows = baseline.windows,
            reference = baseline.reference,
            cleanComposed = baseline.cleanComposed,
            processedSky = variant.processedSky,
            output = variant.output,
            refined = baseline.refinedMask,
            protection = baseline.foregroundProtection,
            alpha = baseline.effectiveAlpha
        ).map { LocalResidualBoundaryMetric(variant.id, it) }
    }

    private fun sensorDefectMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<LocalResidualStretchVariant>
    ): List<LocalResidualSensorDefectMetric> = variants.flatMap { variant ->
        baseline.fixture.strictSensorDefects.map { defect ->
            val differences = mutableListOf<Double>()
            val centerX = defect.x.toInt()
            val centerY = defect.y.toInt()
            for (dy in -DEFECT_RADIUS..DEFECT_RADIUS) for (dx in -DEFECT_RADIUS..DEFECT_RADIUS) {
                if (dx * dx + dy * dy > DEFECT_RADIUS * DEFECT_RADIUS) continue
                val x = centerX + dx
                val y = centerY + dy
                if (x !in 0 until variant.output.width || y !in 0 until variant.output.height) continue
                val index = y * variant.output.width + x
                differences += colorDifference(variant.output.pixels[index], baseline.cleanComposed.pixels[index])
            }
            LocalResidualSensorDefectMetric(
                variant.id,
                defect.id,
                differences.averageOrZero(),
                differences.maxOrNull() ?: 0.0
            )
        }
    }

    private fun detectionMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<LocalResidualStretchVariant>
    ): List<LocalResidualDetectionMetric> {
        val detector = JpegStarDetector()
        val clean = detector.detect(baseline.cleanComposed, baseline.refinedMask)
        return variants.map { variant ->
            val result = if (variant.id == LocalResidualStretchVariantId.CLEAN_STACK) clean else {
                detector.detect(variant.output, baseline.refinedMask)
            }
            val newDetections = if (variant.id == LocalResidualStretchVariantId.CLEAN_STACK) emptyList() else {
                result.stars.filter { candidate ->
                    clean.stars.none { known ->
                        val radius = maxOf(2f, candidate.width * 0.75f)
                        hypot((candidate.x - known.x).toDouble(), (candidate.y - known.y).toDouble()) < radius
                    }
                }
            }
            val details = newDetections.map { candidate ->
                val radius = maxOf(2f, candidate.width * 0.75f).toDouble()
                fun matches(label: ProvisionalSourceLabel): Boolean =
                    hypot(candidate.x.toDouble() - label.x, candidate.y.toDouble() - label.y) < radius
                val classification = when {
                    baseline.fixture.strictReferenceStarLabels.any(::matches) -> {
                        val label = baseline.fixture.strictReferenceStarLabels.first(::matches)
                        "strict-star:${label.id}"
                    }
                    baseline.fixture.strictSensorDefects.any(::matches) -> {
                        val label = baseline.fixture.strictSensorDefects.first(::matches)
                        "strict-sensor-defect:${label.id}"
                    }
                    baseline.fixture.groundTruth.any(::matches) -> {
                        val label = baseline.fixture.groundTruth.first(::matches)
                        "ground-truth:${label.classification.name.lowercase()}:${label.id}"
                    }
                    baseline.alignedStackStars.any { known ->
                        hypot((candidate.x - known.x).toDouble(), (candidate.y - known.y).toDouble()) < radius
                    } -> "aligned-stack-star"
                    else -> "unmatched"
                }
                String.format(
                    Locale.US,
                    "x=%.3f;y=%.3f;width=%.3f;classification=%s",
                    candidate.x,
                    candidate.y,
                    candidate.width,
                    classification
                )
            }
            LocalResidualDetectionMetric(
                variant.id,
                result.stars.size,
                newDetections.size,
                details.count { it.endsWith("classification=unmatched") },
                details,
                result.background,
                result.noise
            )
        }
    }

    private fun stageMetric(
        baseline: SkyMaskReplayBundle,
        variant: LocalResidualStretchVariant
    ): SkyMaskPostProcessStageMetrics = SkyMaskReplayMath.postProcessStageMetrics(
        listOf(SkyMaskPostProcessStage(variant.id.stableId, variant.output)),
        baseline.reference,
        baseline.effectiveAlpha,
        baseline.refinedMask,
        baseline.windows
    ).single()

    private fun globalMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<LocalResidualStretchVariant>,
        strict: List<LocalResidualStrictStarMetric>,
        boundaries: List<LocalResidualBoundaryMetric>,
        defects: List<LocalResidualSensorDefectMetric>,
        detections: List<LocalResidualDetectionMetric>,
        cleanMetric: SkyMaskPostProcessStageMetrics
    ): List<LocalResidualGlobalMetric> {
        val preliminary = variants.map { variant ->
            val stage = if (variant.id == LocalResidualStretchVariantId.CLEAN_STACK) cleanMetric
                else stageMetric(baseline, variant)
            val variantBoundaries = boundaries.filter { it.variant == variant.id }.map { it.window }
            val nearBoundary = variantBoundaries.filter { it.distanceToBoundary <= 31.0 }
                .ifEmpty { variantBoundaries }
            val skyIndices = variant.output.pixels.indices.filter { index ->
                baseline.effectiveAlpha.alphaAt(index % variant.output.width, index / variant.output.width) >= 0.5f
            }
            val luminances = skyIndices.map { luminance(variant.output.pixels[it]) }
            val foregroundIndices = variant.output.pixels.indices.filter { index ->
                baseline.foregroundProtection.contains(index % variant.output.width, index / variant.output.width) ||
                    baseline.effectiveAlpha.alphaAt(index % variant.output.width, index / variant.output.width) <= 0.01f
            }
            val stars = strict.filter { it.variant == variant.id }
            val weakStars = stars.filter(LocalResidualStrictStarMetric::weakBaselineStar)
            val selection = variant.selection
            LocalResidualGlobalMetric(
                variant = variant.id,
                skyMad = stage.skyMad,
                bandingProxy = stage.bandingProxy,
                boundaryEdgeExcess = stage.boundaryEdgeExcess,
                meanHaloScore = nearBoundary.map { it.haloScore }.averageOrZero(),
                meanLeakageScore = nearBoundary.map { it.leakageScore }.averageOrZero(),
                foregroundMeanChange = foregroundIndices.map { index ->
                    colorDifference(variant.output.pixels[index], baseline.reference.pixels[index])
                }.averageOrZero(),
                luminanceMean = luminances.averageOrZero(),
                luminanceMedian = percentile(luminances, 0.5),
                clippedLowPixels = skyIndices.count { index -> hasChannel(variant.output.pixels[index], 0) },
                clippedHighPixels = skyIndices.count { index -> hasChannel(variant.output.pixels[index], 255) },
                chromaResidual = skyIndices.map { chroma(variant.output.pixels[it]) }.averageOrZero(),
                sensorDefectResidual = defects.filter { it.variant == variant.id }.map { it.meanResidual }.averageOrZero(),
                weakStarMedianContrastGain = percentile(weakStars.map { it.localContrastRetention }, 0.5),
                maximumStrictStarWidthRatio = stars.maxOfOrNull { it.widthRatio } ?: 1.0,
                strictStarGatePassed = stars.size == 6 && stars.all { it.establishedGatePassed },
                processedAccepted = selection?.processedAccepted ?: true,
                rejectionReasons = selection?.processedRejectionReasons.orEmpty(),
                selectedCandidate = selection?.type?.name ?: "CLEAN_STACK",
                newDetectionsVersusClean = detections.single { it.variant == variant.id }.newDetectionsVersusClean,
                falseWeakStarDetections = detections.single {
                    it.variant == variant.id
                }.falseWeakStarDetections,
                backgroundPreservedByOperation = (variant.localDiagnostics?.let {
                    it.backgroundChangedPixels == 0 && it.negativeResidualChangedPixels == 0
                } ?: (variant.id == LocalResidualStretchVariantId.CLEAN_STACK)),
                acceptableProductionCandidate = false
            )
        }
        val clean = preliminary.single { it.variant == LocalResidualStretchVariantId.CLEAN_STACK }
        val current = preliminary.single { it.variant == LocalResidualStretchVariantId.CURRENT }
        return preliminary.map { value ->
            value.copy(
                acceptableProductionCandidate = value.variant.productionCandidateEligible &&
                    value.processedAccepted && value.selectedCandidate == "PROCESSED" &&
                    value.skyMad <= clean.skyMad + EPSILON &&
                    value.bandingProxy <= clean.bandingProxy + EPSILON &&
                    value.boundaryEdgeExcess <= current.boundaryEdgeExcess + EPSILON &&
                    value.meanHaloScore <= current.meanHaloScore + EPSILON &&
                    value.meanLeakageScore <= current.meanLeakageScore + EPSILON &&
                    value.foregroundMeanChange <= current.foregroundMeanChange + EPSILON &&
                    value.strictStarGatePassed &&
                    value.weakStarMedianContrastGain > 1.0 + EPSILON &&
                    value.sensorDefectResidual <= clean.sensorDefectResidual + EPSILON &&
                    value.falseWeakStarDetections == 0 &&
                    value.backgroundPreservedByOperation
            )
        }
    }

    private fun luminance(color: Int): Double =
        (color ushr 16 and 0xFF) * 0.2126 +
            (color ushr 8 and 0xFF) * 0.7152 +
            (color and 0xFF) * 0.0722

    private fun chroma(color: Int): Double = (
        maxOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF) -
            minOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF)
        ).toDouble()

    private fun colorDifference(first: Int, second: Int): Double = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    ).toDouble()

    private fun hasChannel(color: Int, target: Int): Boolean =
        (color ushr 16 and 0xFF) == target || (color ushr 8 and 0xFF) == target || (color and 0xFF) == target

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val position = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        val amount = position - lower
        return sorted[lower] * (1.0 - amount) + sorted[upper] * amount
    }

    private fun ratio(value: Double, baseline: Double): Double =
        if (abs(baseline) <= 1e-12) 1.0 else value / baseline

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun treeHash(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile).sorted().forEach { path ->
                digest.update(root.relativize(path).toString().replace('\\', '/').toByteArray())
                digest.update(0)
                digest.update(Files.readAllBytes(path))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.9f", value)

    companion object {
        private const val EXPECTED_CLEAN_HASH =
            "c52a0100c241a01a0d39535eec16d242fc9d14cea18d75887d7755c6ed65c98d"
        private const val EXPECTED_CURRENT_HASH =
            "21c81eb44bb8710bcb59ffab8fb9aa5f60e5f3c40dd483278a8e525bb0bb8adf"
        private const val EXPECTED_ALPHA_HASH =
            "984cb0f5f9ce0e611830c894e8d59580367ca98871e59299d4c9fedd26820f51"
        private const val MIN_CONTRAST_RETENTION = 0.95
        private const val MAX_CENTROID_SHIFT = 0.25
        private const val MAX_WIDTH_RATIO = 1.05
        private const val DEFECT_RADIUS = 4
        private const val EPSILON = 1e-9
    }
}
