package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class AdaptiveAsinhAblationDiagnosticRunner {
    suspend fun analyze(fixture: Stage6RegressionFixture): AdaptiveAsinhAblationBundle {
        val productionRoot = Path.of("src/main")
        require(Files.isDirectory(productionRoot))
        val productionHashBefore = treeHash(productionRoot)
        val baseline = SkyMaskReplayDiagnosticRunner().analyze(fixture)
        requireBaseline(baseline)

        val profile = AstroProcessingProfile.URBAN_SKY_STRONG
        val parameters = ExistingPresetParameterMapper.parametersFor(
            profile,
            baseline.acceptedOriginalFrameIndices.size
        )
        val current = processVariant(
            baseline,
            AdaptiveAsinhAblationVariantId.CURRENT,
            ReplayStretchOperationMode.PRODUCTION_CURRENT,
            baseline.effectiveAlpha,
            profile
        )
        require(current.composed.pixels.contentEquals(baseline.composedCurrent.pixels))
        require(current.processedSky.pixels.contentEquals(baseline.processedSky.pixels))
        require(current.selection.type.name == "CLEAN_STACK")
        require(!current.selection.processedAccepted)
        require(current.selection.processedRejectionReasons == EXPECTED_CURRENT_REJECTIONS)

        val configurableCurrent = processVariant(
            baseline,
            AdaptiveAsinhAblationVariantId.CURRENT,
            ReplayStretchOperationMode.SQRT_ALPHA,
            baseline.effectiveAlpha,
            profile
        )
        val configurableDifference = pixelDifference(current.composed, configurableCurrent.composed)
        require(configurableDifference.maximumChannelDifference == 0)
        require(configurableDifference.differentPixelCount == 0)
        current.stages.zip(configurableCurrent.stages).forEach { (production, configurable) ->
            require(production.id == configurable.id)
            require(production.image.pixels.contentEquals(configurable.image.pixels)) {
                "Configurable CURRENT differs at ${production.id}"
            }
        }

        val binarySkySelection = alphaFrom(baseline.skySelection)
        val binaryRefined = alphaFrom(baseline.refinedMask)
        val variants = listOf(
            current,
            processVariant(
                baseline,
                AdaptiveAsinhAblationVariantId.FULL_STRETCH_SINGLE_COMPOSE,
                ReplayStretchOperationMode.FULL,
                baseline.effectiveAlpha,
                profile
            ),
            processVariant(
                baseline,
                AdaptiveAsinhAblationVariantId.LINEAR_ALPHA_THEN_COMPOSE,
                ReplayStretchOperationMode.LINEAR_ALPHA,
                baseline.effectiveAlpha,
                profile
            ),
            processVariant(
                baseline,
                AdaptiveAsinhAblationVariantId.SQRT_ALPHA_NO_SECOND_COMPOSE,
                ReplayStretchOperationMode.PRODUCTION_CURRENT,
                binarySkySelection,
                profile
            ),
            processVariant(
                baseline,
                AdaptiveAsinhAblationVariantId.FULL_STRETCH_HARD_COMPOSE,
                ReplayStretchOperationMode.FULL,
                binaryRefined,
                profile
            ),
            processVariant(
                baseline,
                AdaptiveAsinhAblationVariantId.NO_STRETCH,
                ReplayStretchOperationMode.BYPASS,
                baseline.effectiveAlpha,
                profile
            )
        )
        require(variants.map { it.id } == AdaptiveAsinhAblationVariantId.entries)

        val hashes = baselineHashes(baseline)
        val fingerprint = parameterFingerprint(parameters)
        val contracts = variants.map { variant ->
            AdaptiveAsinhAblationContract(
                variant = variant.id,
                available = variant.available,
                unavailableReason = variant.unavailableReason,
                changedCondition = changedCondition(variant.id),
                sharedInputArgbSha256 = hashes.backgroundNeutralizedArgbSha256,
                initialMaskSha256 = hashes.initialMaskSha256,
                refinedMaskSha256 = hashes.refinedMaskSha256,
                effectiveAlphaFloat32LeSha256 = hashes.effectiveAlphaFloat32LeSha256,
                acceptedOriginalIndices = baseline.acceptedOriginalFrameIndices,
                rejectedOriginalIndices = baseline.rejectedOriginalFrameIndices,
                alignmentTransformFingerprint = baseline.alignmentTransformFingerprint,
                parameterFingerprint = fingerprint,
                qualityPolicy = "AstroResultQualityGate + ResultSelectionPolicy (production classes, unchanged)"
            )
        }
        val strictStars = AdaptiveAsinhAblationMath.strictStarMetrics(baseline, variants)
        val boundaries = AdaptiveAsinhAblationMath.boundaryMetrics(baseline, variants)
        val stageMetrics = AdaptiveAsinhAblationMath.stageMetrics(baseline, variants)
        val global = AdaptiveAsinhAblationMath.globalMetrics(
            baseline,
            variants,
            strictStars,
            boundaries,
            stageMetrics
        )
        val rootCause = AdaptiveAsinhAblationMath.rootCause(global)
        val productionHashAfter = treeHash(productionRoot)
        return AdaptiveAsinhAblationBundle(
            baseline = baseline,
            baselineHashes = hashes,
            parameters = parameters,
            contracts = contracts,
            variants = variants,
            globalMetrics = global,
            boundaryMetrics = boundaries,
            strictStarMetrics = strictStars,
            stageMetrics = stageMetrics,
            rootCause = rootCause.first,
            rootCauseEvidence = rootCause.second,
            productionCandidate = rootCause.third,
            configurableCurrentMaximumChannelDifference = configurableDifference.maximumChannelDifference,
            configurableCurrentDifferentPixelCount = configurableDifference.differentPixelCount,
            productionSourceChanged = productionHashBefore != productionHashAfter
        ).also { require(!it.productionSourceChanged) }
    }

    private suspend fun processVariant(
        baseline: SkyMaskReplayBundle,
        id: AdaptiveAsinhAblationVariantId,
        operationMode: ReplayStretchOperationMode,
        compositionAlpha: AlphaMask,
        profile: AstroProcessingProfile
    ): AdaptiveAsinhAblationVariant {
        val replay = ReplayAdaptiveSkyProcessor().process(
            stackedSky = baseline.cleanStack,
            reference = baseline.reference,
            alpha = baseline.effectiveAlpha,
            profile = profile,
            frameCount = baseline.acceptedOriginalFrameIndices.size,
            stars = baseline.alignedStackStars,
            stretchOperationMode = operationMode,
            compositionAlpha = compositionAlpha
        )
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
        val stageById = replay.stages.associateBy { it.id }
        val stages = listOf(
            AdaptiveAsinhAblationStage(
                "00-background-neutralized",
                stageById.getValue("02-background-neutralization").image
            ),
            AdaptiveAsinhAblationStage(
                "01-adaptive-stretch",
                stageById.getValue("03-adaptive-stretch").image
            ),
            AdaptiveAsinhAblationStage(
                "02-chroma-reduction",
                stageById.getValue("04-chroma-reduction").image
            ),
            AdaptiveAsinhAblationStage(
                "03-star-enhancement",
                stageById.getValue("05-star-enhancement").image
            ),
            AdaptiveAsinhAblationStage(
                "04-final-safety",
                stageById.getValue("06-final-safety").image
            ),
            AdaptiveAsinhAblationStage(
                "05-background-match",
                stageById.getValue("07-background-match").image
            ),
            AdaptiveAsinhAblationStage("06-composed", replay.composed),
            AdaptiveAsinhAblationStage("07-selected-or-rejected", selection.image)
        )
        return AdaptiveAsinhAblationVariant(
            id = id,
            available = true,
            unavailableReason = null,
            operationMode = operationMode,
            compositionAlpha = compositionAlpha,
            stages = stages,
            processedSky = replay.processedSky,
            composed = replay.composed,
            selectedOrRejected = selection.image,
            selection = selection,
            stretchDiagnostics = replay.stretchDiagnostics
        )
    }

    private fun requireBaseline(baseline: SkyMaskReplayBundle) {
        require(baseline.fixture.name == "urban-window-30")
        require(baseline.reference.width == 720 && baseline.reference.height == 960)
        require(baseline.fixture.strictReferenceStarLabels.size == 6)
        require(baseline.fixture.strictSensorDefects.size == 2)
        require(baseline.fixture.groundTruthSummary.totalRows == 24)
        require(baseline.fixture.groundTruthSummary.eligibleConfirmedStars == 6)
        require(baseline.fixture.groundTruthSummary.eligibleConfirmedSensorDefects == 2)
        require(baseline.fixture.groundTruthSummary.excludedNeedsReviewRows == 6)
        require(baseline.fixture.groundTruthSummary.excludedRejectedRows == 8)
        require(baseline.acceptedOriginalFrameIndices == ((1..21).toList() + 25))
        require(baseline.rejectedOriginalFrameIndices == listOf(22, 23, 24, 26, 27, 28, 29, 30))
        require(baseline.selectedCandidateType == "CLEAN_STACK")
        require(!baseline.processedCandidateAccepted)
        require(baseline.processedCandidateRejectionReasons == EXPECTED_CURRENT_REJECTIONS)
        require(baseline.adaptiveReplayMatchesProductionPixels)
        require(baseline.activeFileBackedMaximumChannelDifference == 0)
        require(baseline.activeFileBackedDifferentPixelCount == 0)
        require(ReplayDiagnosticHashing.sha256Argb(baseline.cleanStack) == EXPECTED_CLEAN_HASH)
        require(ReplayDiagnosticHashing.sha256Argb(baseline.composedCurrent) == EXPECTED_COMPOSED_HASH)
        require(ReplayDiagnosticHashing.sha256Argb(baseline.finalCurrent) == EXPECTED_FINAL_HASH)
        require(ReplayDiagnosticHashing.sha256Alpha(baseline.effectiveAlpha) == EXPECTED_ALPHA_HASH)
    }

    private fun baselineHashes(baseline: SkyMaskReplayBundle): AdaptiveAsinhBaselineHashes {
        val neutralized = baseline.postProcessingStages.single {
            it.id == "02-background-neutralization"
        }.image
        val stretch = baseline.postProcessingStages.single { it.id == "03-adaptive-stretch" }.image
        return AdaptiveAsinhBaselineHashes(
            cleanInputArgbSha256 = ReplayDiagnosticHashing.sha256Argb(baseline.cleanStack),
            backgroundNeutralizedArgbSha256 = ReplayDiagnosticHashing.sha256Argb(neutralized),
            currentAdaptiveStretchArgbSha256 = ReplayDiagnosticHashing.sha256Argb(stretch),
            currentComposedArgbSha256 = ReplayDiagnosticHashing.sha256Argb(baseline.composedCurrent),
            currentSelectedFinalArgbSha256 = ReplayDiagnosticHashing.sha256Argb(baseline.finalCurrent),
            initialMaskSha256 = ReplayDiagnosticHashing.sha256Mask(baseline.initialMask),
            refinedMaskSha256 = ReplayDiagnosticHashing.sha256Mask(baseline.refinedMask),
            effectiveAlphaFloat32LeSha256 = ReplayDiagnosticHashing.sha256Alpha(baseline.effectiveAlpha)
        )
    }

    private fun alphaFrom(mask: SkyMask): AlphaMask {
        val pixels = mask.copyPixels()
        return AlphaMask(mask.width, mask.height, FloatArray(pixels.size) { if (pixels[it]) 1f else 0f })
    }

    private fun changedCondition(id: AdaptiveAsinhAblationVariantId): String = when (id) {
        AdaptiveAsinhAblationVariantId.CURRENT -> "none; exact production behavior"
        AdaptiveAsinhAblationVariantId.FULL_STRETCH_SINGLE_COMPOSE ->
            "operationStrength sqrt(effectiveAlpha) -> 1"
        AdaptiveAsinhAblationVariantId.LINEAR_ALPHA_THEN_COMPOSE ->
            "operationStrength sqrt(effectiveAlpha) -> effectiveAlpha"
        AdaptiveAsinhAblationVariantId.SQRT_ALPHA_NO_SECOND_COMPOSE ->
            "compositionAlpha effectiveAlpha -> binary skySelection"
        AdaptiveAsinhAblationVariantId.FULL_STRETCH_HARD_COMPOSE ->
            "negative control changes operationStrength and compositionAlpha; excluded from root-cause inference"
        AdaptiveAsinhAblationVariantId.NO_STRETCH -> "AdaptiveAsinhStretch bypass only"
    }

    private fun parameterFingerprint(value: com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingParameters): String =
        listOf(
            value.gradientStrength,
            value.neutralizationStrength,
            value.stretchBlend,
            value.asinhStrength,
            value.highlightProtection,
            value.chromaNoiseStrength,
            value.starContrastStrength,
            value.maximumSkyMedianFactor,
            value.maximumChannelClippingPercent,
            value.minimumBlackWhiteSeparation,
            value.maximumGradientCorrection,
            value.maximumNeutralizationCorrection,
            value.maximumStarDetailGain,
            value.maximumChromaRadius,
            value.maximumStarWidthGrowth,
            value.targetDisplaySkyMedian,
            value.minimumStarContrastGain
        ).joinToString("|")

    private data class PixelDifference(val maximumChannelDifference: Int, val differentPixelCount: Int)

    private fun pixelDifference(first: ArgbPixelImage, second: ArgbPixelImage): PixelDifference {
        require(first.width == second.width && first.height == second.height)
        var maximum = 0
        var count = 0
        first.pixels.indices.forEach { index ->
            val firstColor = first.pixels[index]
            val secondColor = second.pixels[index]
            val difference = maxOf(
                kotlin.math.abs((firstColor ushr 16 and 0xFF) - (secondColor ushr 16 and 0xFF)),
                kotlin.math.abs((firstColor ushr 8 and 0xFF) - (secondColor ushr 8 and 0xFF)),
                kotlin.math.abs((firstColor and 0xFF) - (secondColor and 0xFF))
            )
            if (difference > 0) count++
            maximum = maxOf(maximum, difference)
        }
        return PixelDifference(maximum, count)
    }

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

    private companion object {
        val EXPECTED_CURRENT_REJECTIONS = listOf(
            "sky_mad_increased_excessively",
            "banding_increased_excessively"
        )
        const val EXPECTED_CLEAN_HASH = "c52a0100c241a01a0d39535eec16d242fc9d14cea18d75887d7755c6ed65c98d"
        const val EXPECTED_COMPOSED_HASH = "21c81eb44bb8710bcb59ffab8fb9aa5f60e5f3c40dd483278a8e525bb0bb8adf"
        const val EXPECTED_FINAL_HASH = "786052b443af8fca5484beafa5482fcfa53430a4cb685b89a2e7a12d1551daef"
        const val EXPECTED_ALPHA_HASH = "984cb0f5f9ce0e611830c894e8d59580367ca98871e59299d4c9fedd26820f51"
    }
}
