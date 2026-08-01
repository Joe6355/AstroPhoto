package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorCandidateDetector
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.buildAutomaticSensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.composition.MaskFeathering
import com.example.astrophoto.processing.jpeg.v2.composition.ReferenceStarSignalPreserver
import com.example.astrophoto.processing.jpeg.v2.composition.SkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.masking.ForegroundProtectionMask
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskRefiner
import com.example.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.example.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.example.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidate
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.model.StretchDiagnostics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptiveAsinhStretch
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptiveGradientRemoval
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.postprocessing.BackgroundNeutralizer
import com.example.astrophoto.processing.jpeg.v2.postprocessing.ChromaNoiseReducer
import com.example.astrophoto.processing.jpeg.v2.postprocessing.LocalStarContrastEnhancer
import com.example.astrophoto.processing.jpeg.v2.postprocessing.SkyBackgroundToneMatcher
import com.example.astrophoto.processing.jpeg.v2.postprocessing.SkyStatistics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.linearChannel
import com.example.astrophoto.processing.jpeg.v2.postprocessing.packLinear
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import com.example.astrophoto.processing.jpeg.v2.quality.AstroResultQualityGate
import com.example.astrophoto.processing.jpeg.v2.quality.CleanStackValidationEvidence
import com.example.astrophoto.processing.jpeg.v2.quality.CoverageUniformityValidator
import com.example.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.example.astrophoto.processing.jpeg.v2.quality.ResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.quality.ResultSelectionPolicy
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.example.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class SkyMaskReplayDiagnosticRunner {
    suspend fun analyze(fixture: Stage6RegressionFixture): SkyMaskReplayBundle {
        require(fixture.name == "urban-window-30")
        require(fixture.frames.size == 30)
        require(fixture.strictReferenceStarLabels.size == 6)
        val width = fixture.frames.first().width
        val height = fixture.frames.first().height
        require(width == 720 && height == 960)
        val plan = requireNotNull(planManualSequenceAlignment(fixture.frames, width, height))
        val acceptedIndices = plan.frames.filter { it.accepted }.map { it.originalFrameIndex + 1 }
        val rejectedIndices = plan.frames.filterNot { it.accepted }.map { it.originalFrameIndex + 1 }
        require(fixture.referenceFrameIndex + 1 in acceptedIndices)

        val maskEstimator = SkyMaskEstimator()
        val persistentDetector = PersistentSensorCandidateDetector()
        val frameAnalyzer = JpegFrameAnalyzer()
        val observations = fixture.frames.mapIndexed { index, image ->
            val id = frameId(index)
            persistentDetector.observe(
                frameId = id,
                originalCaptureIndex = index + 1,
                image = image,
                skyMask = maskEstimator.estimate(image).mask
            )
        }
        val automaticSensorMask = buildAutomaticSensorDefectMask(
            observations,
            width,
            height
        ) { originalCaptureIndex ->
            val decision = plan.frames[originalCaptureIndex - 1]
            require(decision.originalFrameIndex + 1 == originalCaptureIndex)
            ReferenceToSourceTransform(decision.shift.dx.toFloat(), decision.shift.dy.toFloat())
        }
        require(automaticSensorMask.originalFrameIndices == (1..fixture.frames.size).toList())
        val sensorMask = automaticSensorMask.mask

        val rawAnalyses = fixture.frames.mapIndexed { index, image ->
            val id = frameId(index)
            frameAnalyzer.analyze(id, id, image, maskEstimator.estimate(image))
        }
        val staticAnalyzer = StaticArtifactAnalyzer()
        val staticMask = staticAnalyzer.analyze(
            rawAnalyses.map { ArtifactFrameObservation(it.id, it.stars) },
            width,
            height
        )
        val reference = fixture.frames[fixture.referenceFrameIndex]
        val referenceAnalysis = staticAnalyzer.excludeFrom(
            rawAnalyses[fixture.referenceFrameIndex],
            staticMask
        )
        val stars = referenceAnalysis.stars
        val initial = maskEstimator.estimate(reference)
        val registrationConfidence = plan.frames.filter { it.accepted }
            .mapNotNull { it.registrationConfidence }
            .average()
            .takeIf(Double::isFinite)
            ?.toFloat()
            ?: 0f
        val refined = SkyMaskRefiner().refine(
            initialMask = initial.mask,
            reference = reference,
            stars = stars,
            initialConfidence = initial.confidence,
            initialUsedFallback = initial.usedFallback,
            registrationConfidence = registrationConfidence
        )
        val protection = ForegroundProtectionMask().detect(reference, stars)
        val feathering = MaskFeathering().feather(
            refined.binaryMask,
            protection.mask,
            radiusOverride = refined.diagnostics.featherRadius
        )
        val skySelectionPixels = refined.binaryMask.copyPixels().also { pixels ->
            val protected = protection.mask.copyPixels()
            pixels.indices.forEach { index -> if (protected[index]) pixels[index] = false }
        }
        val skySelection = SkyMask(width, height, skySelectionPixels)

        val unmaskedIntegration = integrateFixtureForAutomaticReplay(fixture, plan, null)
        val maskedIntegration = integrateFixtureForAutomaticReplay(fixture, plan, sensorMask)
        require(maskedIntegration.filtering.sampleLevelFilteringApplied)
        require(maskedIntegration.filtering.insufficientCoveragePixelCount == 0)
        require(maskedIntegration.acceptedFrames == acceptedIndices.size)
        val affected = AlphaMask(width, height, maskedIntegration.sensorDefectAffectedOutput.copyOf())
        val coverage = AlphaMask(width, height, maskedIntegration.coverage.copyOf())
        val starPreserved = ReferenceStarSignalPreserver().preserve(
            stackedSky = maskedIntegration.image,
            reference = reference,
            stars = stars,
            sensorDefectMask = sensorMask,
            sensorDefectAffectedOutput =
                com.example.astrophoto.processing.jpeg.v2.composition.AlphaMaskPixelSource(affected)
        ).image
        val cleanComposite = SkyForegroundComposer().compose(
            stackedSky = starPreserved,
            reference = reference,
            featheredSkyMask = feathering.alphaMask,
            validCoverage = coverage,
            sensorDefectAffectedOutput = affected,
            sensorDefectMask = sensorMask
        )
        val currentAlpha = cleanComposite.effectiveSkyAlpha
        val profile = AstroProcessingProfile.URBAN_SKY_STRONG
        val currentProcessed = ReplayAdaptiveSkyProcessor().process(
            stackedSky = starPreserved,
            reference = reference,
            alpha = currentAlpha,
            profile = profile,
            frameCount = acceptedIndices.size,
            stars = stars
        )
        val productionCurrent = AdaptivePresetProcessor().process(
            stackedSky = starPreserved,
            referenceForeground = reference,
            effectiveSkyAlpha = currentAlpha,
            profile = profile,
            frameCount = acceptedIndices.size,
            alignedStackStars = stars
        ).image
        val adaptiveMatches = productionCurrent.pixels.contentEquals(currentProcessed.composed.pixels)
        require(adaptiveMatches) { "Replay adaptive component chain differs from AdaptivePresetProcessor" }
        val activeFileBackedCurrent = runActiveFileBackedAdaptive(
            starPreserved,
            reference,
            currentAlpha,
            profile,
            acceptedIndices.size,
            stars
        )
        val activeDifference = pixelDifference(
            currentProcessed.composed,
            activeFileBackedCurrent
        )
        require(activeDifference.maximumChannelDifference <= 2) {
            "Active file-backed Stage 4 differs from component replay by more than 2 levels"
        }

        val variants = buildVariants(
            reference = reference,
            cleanStack = starPreserved,
            cleanComposite = cleanComposite.image,
            currentProcessed = currentProcessed,
            currentAlpha = currentAlpha,
            initialMask = initial.mask,
            refinedMask = refined.binaryMask,
            protection = protection.mask,
            coverage = coverage,
            featherRadius = refined.diagnostics.featherRadius,
            profile = profile,
            frameCount = acceptedIndices.size,
            stars = stars,
            sensorMask = sensorMask,
            affected = affected,
            activeCurrentOutput = activeFileBackedCurrent
        )
        val currentVariant = variants.single { it.id == SkyMaskReplayVariantId.CURRENT }
        require(currentVariant.output.pixels.contentEquals(activeFileBackedCurrent.pixels))

        val selection = selectReplayCandidate(
            reference,
            cleanComposite.image,
            currentVariant.output,
            currentAlpha,
            coverage,
            stars,
            plan.modelScore,
            acceptedIndices.size,
            profile
        )
        val boundary = SkyMaskReplayMath.boundaryMetrics(
            initial.mask,
            refined.binaryMask,
            protection.mask,
            currentAlpha
        )
        val windows = SkyMaskReplayMath.selectWindows(
            fixture,
            reference,
            starPreserved,
            currentProcessed.processedSky,
            variants,
            refined.binaryMask,
            protection.mask,
            currentAlpha
        )
        val strictMetrics = SkyMaskReplayMath.strictStarMetrics(
            fixture,
            reference,
            starPreserved,
            currentProcessed.processedSky,
            cleanComposite.image,
            selection.image,
            variants,
            refined.binaryMask,
            protection.mask,
            currentAlpha
        )
        val windowMetrics = SkyMaskReplayMath.windowMetrics(
            windows,
            reference,
            starPreserved,
            currentProcessed.processedSky,
            variants,
            refined.binaryMask,
            protection.mask,
            currentAlpha
        )
        val variantMetrics = SkyMaskReplayMath.variantMetrics(
            fixture,
            reference,
            variants,
            windowMetrics,
            strictMetrics,
            currentAlpha,
            protection.mask,
            refined.binaryMask
        )
        val postProcessStageMetrics = SkyMaskReplayMath.postProcessStageMetrics(
            currentProcessed.stages,
            reference,
            currentAlpha,
            refined.binaryMask,
            windows
        )
        val issues = SkyMaskReplayMath.classifyIssues(
            windowMetrics,
            variantMetrics,
            postProcessStageMetrics
        )
        val manifest = pipelineManifest(
            fixture,
            initial.confidence,
            initial.usedFallback,
            refined.confidence,
            refined.usedFallback,
            refined.diagnostics.featherRadius,
            protection.dilationRadius,
            selection.type,
            acceptedIndices,
            rejectedIndices,
            sensorMask,
            activeDifference
        )
        return SkyMaskReplayBundle(
            fixture = fixture,
            reference = reference,
            unmaskedIntegration = unmaskedIntegration.image,
            cleanStack = starPreserved,
            processedSky = currentProcessed.processedSky,
            cleanComposed = cleanComposite.image,
            composedCurrent = currentVariant.output,
            finalCurrent = selection.image,
            selectedCandidateType = selection.type.name,
            cleanCandidateAccepted = selection.cleanAccepted,
            processedCandidateAccepted = selection.processedAccepted,
            processedCandidateRejectionReasons = selection.processedRejectionReasons,
            initialMask = initial.mask,
            refinedMask = refined.binaryMask,
            effectiveAlpha = currentAlpha,
            validCoverage = coverage,
            sensorDefectAffectedOutput = affected,
            sensorDefectMask = sensorMask,
            foregroundProtection = protection.mask,
            skySelection = skySelection,
            alignedStackStars = stars,
            alignmentModelScore = plan.modelScore,
            alignmentTransformFingerprint = ReplayDiagnosticHashing.sha256(
                plan.frames.joinToString("\n") { decision ->
                    listOf(
                        decision.originalFrameIndex,
                        decision.frameId ?: "",
                        decision.accepted,
                        decision.rejectionReason ?: "",
                        decision.shift.dx,
                        decision.shift.dy,
                        java.lang.Double.toHexString(decision.shift.score),
                        java.lang.Double.toHexString(decision.shift.confidence),
                        decision.registrationResidualPx?.let(java.lang.Float::toHexString) ?: "",
                        decision.registrationConfidence?.let(java.lang.Float::toHexString) ?: ""
                    ).joinToString("|")
                }.toByteArray()
            ),
            currentStretchDiagnostics = currentProcessed.stretchDiagnostics,
            variants = variants,
            boundaryMetrics = boundary,
            windows = windows,
            windowMetrics = windowMetrics,
            strictStarMetrics = strictMetrics,
            variantMetrics = variantMetrics,
            postProcessingStages = currentProcessed.stages,
            postProcessingStageMetrics = postProcessStageMetrics,
            issues = issues,
            acceptedOriginalFrameIndices = acceptedIndices,
            rejectedOriginalFrameIndices = rejectedIndices,
            initialMaskConfidence = initial.confidence,
            initialMaskUsedFallback = initial.usedFallback,
            refinedMaskConfidence = refined.confidence,
            refinedMaskUsedFallback = refined.usedFallback,
            featherRadius = refined.diagnostics.featherRadius,
            foregroundProtectionRadius = protection.dilationRadius,
            adaptiveReplayMatchesProductionPixels = adaptiveMatches,
            activeFileBackedMaximumChannelDifference = activeDifference.maximumChannelDifference,
            activeFileBackedDifferentPixelCount = activeDifference.differentPixelCount,
            pipelineManifestJson = manifest
        )
    }

    private suspend fun buildVariants(
        reference: ArgbPixelImage,
        cleanStack: ArgbPixelImage,
        cleanComposite: ArgbPixelImage,
        currentProcessed: ReplayProcessedSky,
        currentAlpha: AlphaMask,
        initialMask: SkyMask,
        refinedMask: SkyMask,
        protection: SkyMask,
        coverage: AlphaMask,
        featherRadius: Int,
        profile: AstroProcessingProfile,
        frameCount: Int,
        stars: List<DetectedStar>,
        sensorMask: SensorDefectMask,
        affected: AlphaMask,
        activeCurrentOutput: ArgbPixelImage
    ): List<SkyMaskReplayVariant> {
        val processor = ReplayAdaptiveSkyProcessor()
        fun effective(feathered: AlphaMask): AlphaMask = SkyForegroundComposer().compose(
            stackedSky = cleanStack,
            reference = reference,
            featheredSkyMask = feathered,
            validCoverage = coverage,
            sensorDefectAffectedOutput = affected,
            sensorDefectMask = sensorMask
        ).effectiveSkyAlpha
        suspend fun processed(
            id: SkyMaskReplayVariantId,
            alpha: AlphaMask,
            initialEnabled: Boolean,
            refineEnabled: Boolean,
            protectionEnabled: Boolean
        ): SkyMaskReplayVariant {
            val replay = processor.process(
                cleanStack, reference, alpha, profile, frameCount, stars
            )
            return SkyMaskReplayVariant(
                id,
                alpha,
                replay.processedSky,
                replay.composed,
                initialEnabled,
                refineEnabled,
                protectionEnabled,
                postProcessingEnabled = true
            )
        }
        val full = AlphaMask.full(reference.width, reference.height)
        val hardPixels = refinedMask.copyPixels()
        val protectedPixels = protection.copyPixels()
        hardPixels.indices.forEach { index -> if (protectedPixels[index]) hardPixels[index] = false }
        val hard = AlphaMask(
            reference.width,
            reference.height,
            FloatArray(hardPixels.size) { if (hardPixels[it]) 1f else 0f }
        )
        val noRefineFeather = MaskFeathering().feather(
            initialMask,
            protection,
            radiusOverride = featherRadius
        ).alphaMask
        val noProtectionFeather = MaskFeathering().feather(
            refinedMask,
            SkyMask.empty(reference.width, reference.height),
            radiusOverride = featherRadius
        ).alphaMask
        return listOf(
            SkyMaskReplayVariant(
                SkyMaskReplayVariantId.CURRENT,
                currentAlpha,
                currentProcessed.processedSky,
                activeCurrentOutput,
                initialMaskEnabled = true,
                refinementEnabled = true,
                foregroundProtectionEnabled = true,
                postProcessingEnabled = true
            ),
            processed(
                SkyMaskReplayVariantId.NO_MASK,
                full,
                initialEnabled = false,
                refineEnabled = false,
                protectionEnabled = false
            ),
            processed(
                SkyMaskReplayVariantId.HARD_MASK,
                hard,
                initialEnabled = true,
                refineEnabled = true,
                protectionEnabled = true
            ),
            processed(
                SkyMaskReplayVariantId.NO_REFINE,
                effective(noRefineFeather),
                initialEnabled = true,
                refineEnabled = false,
                protectionEnabled = true
            ),
            processed(
                SkyMaskReplayVariantId.NO_PROTECTION,
                effective(noProtectionFeather),
                initialEnabled = true,
                refineEnabled = true,
                protectionEnabled = false
            ),
            SkyMaskReplayVariant(
                SkyMaskReplayVariantId.NO_POSTPROCESS,
                currentAlpha,
                cleanStack,
                cleanComposite,
                initialMaskEnabled = true,
                refinementEnabled = true,
                foregroundProtectionEnabled = true,
                postProcessingEnabled = false
            )
        )
    }

    private fun pipelineManifest(
        fixture: Stage6RegressionFixture,
        initialConfidence: Float,
        initialFallback: Boolean,
        refinedConfidence: Float,
        refinedFallback: Boolean,
        featherRadius: Int,
        protectionRadius: Int,
        selectedType: ResultCandidateType,
        acceptedIndices: List<Int>,
        rejectedIndices: List<Int>,
        sensorMask: SensorDefectMask,
        activeDifference: PixelDifference
    ): String {
        fun quoted(value: String): String = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\""
        fun stage(
            order: Int,
            id: String,
            file: String,
            symbol: String,
            input: String,
            coordinateSpace: String,
            format: String,
            interpolation: String,
            parameters: String,
            fallback: String
        ): String = buildString {
            append("    {\"order\":").append(order)
            append(",\"id\":").append(quoted(id))
            append(",\"file\":").append(quoted(file))
            append(",\"symbol\":").append(quoted(symbol))
            append(",\"input\":").append(quoted(input))
            append(",\"coordinateSpace\":").append(quoted(coordinateSpace))
            append(",\"dimensions\":").append(quoted("${fixture.frames.first().width}x${fixture.frames.first().height}"))
            append(",\"pixelFormatOrRange\":").append(quoted(format))
            append(",\"interpolation\":").append(quoted(interpolation))
            append(",\"parameters\":").append(quoted(parameters))
            append(",\"fallback\":").append(quoted(fallback)).append('}')
        }
        val stages = listOf(
            stage(1, "fixture_decode", "Stage6RegressionFixture.kt", "Stage6RegressionFixtureLoader.load", "30 fixture JPEG frames", "camera pixels per original frame", "opaque ARGB_8888 sRGB [0,255]", "ImageIO decode; no replay resize", "reference originalCaptureIndex=${fixture.referenceFrameIndex + 1}", "fail if any frame cannot decode or dimensions differ"),
            stage(2, "initial_sky_mask", "processing/jpeg/v2/masking/SkyMaskEstimator.kt", "SkyMaskEstimator.estimate", "reference ARGB", "reference/output geometry", "Boolean SkyMask", "block expansion; no resampling because dimensions match", "blockSize=${minOf(fixture.frames.first().width, fixture.frames.first().height) / 28}; confidence=$initialConfidence", "usedFallback=$initialFallback; conservative upper component"),
            stage(3, "sequence_registration_and_sensor_mask", "ManualSequenceAlignment.kt + processing/jpeg/v2/artifacts/AutomaticSensorDefectMask.kt", "planManualSequenceAlignment + buildAutomaticSensorDefectMask", "all frames in original capture order", "reference-to-source transforms; camera-space sensor mask", "Float translation + O(1) Boolean mask", "original index lookup; no compact-index transform lookup", "accepted=${acceptedIndices.joinToString("|")}; rejected=${rejectedIndices.joinToString("|")}; sensorRegions=${sensorMask.regions.size}", "rejected frames remain excluded"),
            stage(4, "masked_clean_integration", "processing/jpeg/v2/integration/LinearWeightedIntegrator.kt", "LinearWeightedIntegrator.integrate", "accepted original frames", "reference/output geometry", "opaque ARGB_8888 plus Float32 coverage", "bilinear source sampling; four-tap sensor rejection", "normalization from accepted valid samples", "safe failure on insufficient coverage; no rejected-frame restoration"),
            stage(5, "refined_sky_mask", "processing/jpeg/v2/masking/SkyMaskRefiner.kt", "SkyMaskRefiner.refine", "initial mask + reference + detected stars", "reference/output geometry", "Boolean SkyMask", "nearest upscale is identity at fixture size", "color block=${minOf(fixture.frames.first().width, fixture.frames.first().height) / 28}; confidence=$refinedConfidence; fallbackErosion=1px cross", "usedFallback=$refinedFallback; empty mask remains empty"),
            stage(6, "foreground_protection", "processing/jpeg/v2/masking/ForegroundProtectionMask.kt", "ForegroundProtectionMask.detect", "reference + detected stars", "reference/output geometry", "Boolean protection mask", "Manhattan dilation", "dilationRadius=$protectionRadius; two-sided contrast threshold=18", "empty when no supported thin structures"),
            stage(7, "mask_feather", "processing/jpeg/v2/composition/MaskFeathering.kt", "MaskFeathering.feather", "refined mask + foreground protection", "reference/output geometry", "Float32 alpha [0,1]", "two-pass Manhattan distance", "broadRadius=$featherRadius; thinRadius=${minOf(featherRadius, maxOf(2, featherRadius / 3))}", "alpha=0 outside mask/protection"),
            stage(8, "reference_star_preservation", "processing/jpeg/v2/composition/ReferenceStarSignalPreserver.kt", "ReferenceStarSignalPreserver.preserve", "masked integration + reference + stars", "reference/output geometry", "opaque ARGB_8888", "no spatial interpolation", "sensor-defect affected samples remain filtered", "no star restoration over confirmed affected sensor samples"),
            stage(9, "clean_composition_and_effective_alpha", "processing/jpeg/v2/composition/SkyForegroundComposer.kt", "SkyForegroundComposer.compose", "clean sky + reference + feather alpha + coverage", "reference/output geometry", "linear-light blend to ARGB; Float32 effective alpha", "no spatial interpolation; per-pixel linear RGB blend", "effectiveAlpha=featherAlpha*validCoverage; affected confirmed defect may force 1", "reference used at alpha=0; sky used at alpha=1"),
            stage(10, "processed_sky", "processing/jpeg/v2/postprocessing/FileBackedAdaptivePresetProcessor.kt + AdaptivePresetProcessor.kt", "FileBackedAdaptivePresetProcessor.process; replay component hook", "star-preserved clean sky + effective alpha", "reference/output geometry", "file-backed opaque ARGB_8888; operations in linear RGB", "tiled runtime path; replay hook has no resize", "profile=URBAN_SKY_STRONG; active-vs-hook maxChannelDifference=${activeDifference.maximumChannelDifference}; differentPixels=${activeDifference.differentPixelCount}", "quality gate can reject processed candidate; pre-composition file-backed image is not externally exposed"),
            stage(11, "processed_composition", "processing/jpeg/v2/composition/SkyForegroundComposer.kt", "SkyForegroundComposer.compose(precomputedEffectiveSkyAlpha)", "processed sky + reference + effective alpha", "reference/output geometry", "linear-light blend to ARGB", "no spatial interpolation", "same effective alpha as clean composition", "reference outside alpha"),
            stage(12, "final_selection", "processing/jpeg/v2/quality/ResultSelectionPolicy.kt", "ResultSelectionPolicy.select", "reference, clean, processed candidates", "reference/output geometry", "opaque ARGB_8888", "none", "selected=${selectedType.name}", "processed -> clean -> reference; no legacy catch-all")
        )
        return buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": \"astrophoto.sky-mask-replay/1\",")
            appendLine("  \"fixture\": \"${fixture.name}\",")
            appendLine("  \"mode\": \"replay-only\",")
            appendLine("  \"productionProcessingChanged\": false,")
            appendLine("  \"coordinateContract\": \"720x960 reference/output geometry; origin top-left; no common-region crop; no output resize\",")
            appendLine("  \"stages\": [")
            appendLine(stages.joinToString(",\n"))
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun frameId(index: Int): String = "frame-${index.toString().padStart(3, '0')}.jpg"

    private data class PixelDifference(
        val maximumChannelDifference: Int,
        val differentPixelCount: Int
    )

    private fun pixelDifference(first: ArgbPixelImage, second: ArgbPixelImage): PixelDifference {
        require(first.width == second.width && first.height == second.height)
        var maximum = 0
        var count = 0
        first.pixels.indices.forEach { index ->
            val firstColor = first.pixels[index]
            val secondColor = second.pixels[index]
            val difference = maxOf(
                abs((firstColor ushr 16 and 0xFF) - (secondColor ushr 16 and 0xFF)),
                abs((firstColor ushr 8 and 0xFF) - (secondColor ushr 8 and 0xFF)),
                abs((firstColor and 0xFF) - (secondColor and 0xFF))
            )
            if (difference > 0) count++
            maximum = maxOf(maximum, difference)
        }
        return PixelDifference(maximum, count)
    }

    private suspend fun runActiveFileBackedAdaptive(
        stackedSky: ArgbPixelImage,
        reference: ArgbPixelImage,
        alpha: AlphaMask,
        profile: AstroProcessingProfile,
        frameCount: Int,
        stars: List<DetectedStar>
    ): ArgbPixelImage {
        val cacheRoot = Files.createTempDirectory("sky-mask-file-backed").toFile()
        val files = TemporaryPipelineFiles.create(cacheRoot)
        try {
            val store = ResultCandidateStore(files)
            val stackedHandle = writeTemporary(store, "replay-sky", stackedSky)
            val referenceHandle = writeCandidate(store, ResultCandidateType.REFERENCE, reference)
            val alphaHandle = writePlane(store, "replay-alpha", alpha)
            val result = FileBackedAdaptivePresetProcessor().process(
                stackedSky = stackedHandle,
                referenceForeground = referenceHandle,
                effectiveSkyAlpha = alphaHandle,
                profile = profile,
                frameCount = frameCount,
                alignedStackStars = stars,
                store = store,
                memoryBudget = JpegMemoryBudget(
                    RuntimeHeapSnapshot(512L * MIB, 128L * MIB, 96L * MIB),
                    reserveBytes = 64L * MIB
                ),
                memoryTracker = PipelineMemoryTracker()
            )
            return ArgbPixelImage(result.image.width, result.image.height, readAll(result.image))
        } finally {
            files.close()
            cacheRoot.deleteRecursively()
        }
    }

    private fun writeCandidate(
        store: ResultCandidateStore,
        type: ResultCandidateType,
        image: ArgbPixelImage
    ): FileBackedImage {
        val writer = store.createWriter(type, image.width, image.height)
        repeat(image.height) { writer.writeRow(it, image.pixels, it * image.width) }
        return store.register(type, writer.finish())
    }

    private fun writeTemporary(
        store: ResultCandidateStore,
        label: String,
        image: ArgbPixelImage
    ): FileBackedImage {
        val writer = store.createTemporaryWriter(label, image.width, image.height)
        repeat(image.height) { writer.writeRow(it, image.pixels, it * image.width) }
        return writer.finish()
    }

    private fun writePlane(
        store: ResultCandidateStore,
        label: String,
        alpha: AlphaMask
    ): FileBackedFloatPlane {
        val values = FloatArray(alpha.width * alpha.height) { index ->
            alpha.alphaAt(index % alpha.width, index / alpha.width)
        }
        val writer = store.createFloatPlaneWriter(label, alpha.width, alpha.height)
        repeat(alpha.height) { writer.writeRow(it, values, it * alpha.width) }
        return writer.finish()
    }

    private fun readAll(image: FileBackedImage): IntArray {
        val result = IntArray(image.width * image.height)
        FileBackedImageReader(image).use { reader ->
            val row = IntArray(image.width)
            repeat(image.height) { y ->
                reader.readArgbRow(y, row)
                row.copyInto(result, y * image.width)
            }
        }
        return result
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}

internal data class ReplayCandidateSelection(
    val type: ResultCandidateType,
    val image: ArgbPixelImage,
    val cleanAccepted: Boolean,
    val processedAccepted: Boolean,
    val processedRejectionReasons: List<String>
)

internal fun selectReplayCandidate(
    reference: ArgbPixelImage,
    clean: ArgbPixelImage,
    processed: ArgbPixelImage,
    alpha: AlphaMask,
    coverage: AlphaMask,
    stars: List<DetectedStar>,
    modelScore: Float,
    acceptedFrames: Int,
    profile: AstroProcessingProfile
): ReplayCandidateSelection {
    val analyzer = ResultQualityAnalyzer()
    fun candidate(type: ResultCandidateType, image: ArgbPixelImage) =
        ResultCandidate(type, image, analyzer.analyze(image, reference, alpha))
    val referenceCandidate = candidate(ResultCandidateType.REFERENCE, reference)
    val cleanCandidate = candidate(ResultCandidateType.CLEAN_STACK, clean)
    val processedCandidate = candidate(ResultCandidateType.PROCESSED, processed)
    val gate = AstroResultQualityGate()
    val cleanEvidence = CleanStackValidationEvidence(
        ReferenceStarRetentionValidator().validate(reference, clean, stars),
        CoverageUniformityValidator().validate(coverage, alpha),
        LineArtifactDetector().compare(reference, clean, alpha),
        modelScore,
        acceptedFrames,
        acceptedFrames >= 2 && modelScore >= 0.50f
    )
    val cleanDecision = gate.evaluateCleanStack(
        referenceCandidate, cleanCandidate, profile, cleanEvidence
    )
    val processedDecision = gate.evaluateProcessed(
        referenceCandidate, cleanCandidate, processedCandidate, profile, acceptedFrames
    )
    val selected = ResultSelectionPolicy().select(
        referenceCandidate,
        cleanCandidate,
        processedCandidate,
        processedDecision,
        cleanDecision
    ).selected
    return ReplayCandidateSelection(
        selected.type,
        selected.image,
        cleanDecision.accepted,
        processedDecision.accepted,
        processedDecision.hardFailureReasons
    )
}

internal enum class ReplayStretchOperationMode {
    PRODUCTION_CURRENT,
    SQRT_ALPHA,
    LINEAR_ALPHA,
    FULL,
    BYPASS
}

internal data class ReplayProcessedSky(
    val processedSky: ArgbPixelImage,
    val composed: ArgbPixelImage,
    val stages: List<SkyMaskPostProcessStage>,
    val stretchDiagnostics: StretchDiagnostics,
    val compositionAlpha: AlphaMask
)

/** Exposes the otherwise private pre-composition Stage 4 image using the same production components. */
internal class ReplayAdaptiveSkyProcessor {
    suspend fun process(
        stackedSky: ArgbPixelImage,
        reference: ArgbPixelImage,
        alpha: AlphaMask,
        profile: AstroProcessingProfile,
        frameCount: Int,
        stars: List<DetectedStar>,
        stretchOperationMode: ReplayStretchOperationMode = ReplayStretchOperationMode.PRODUCTION_CURRENT,
        stretchBlendMode: ReplayStretchBlendMode = ReplayStretchBlendMode.CURRENT,
        compositionAlpha: AlphaMask = alpha
    ): ReplayProcessedSky {
        require(compositionAlpha.width == alpha.width && compositionAlpha.height == alpha.height)
        require(
            stretchOperationMode != ReplayStretchOperationMode.PRODUCTION_CURRENT ||
                stretchBlendMode == ReplayStretchBlendMode.CURRENT
        )
        val statistics = SkyStatistics()
        val parameters = ExistingPresetParameterMapper.parametersFor(profile, frameCount)
        val before = statistics.calculate(stackedSky, alpha, stars)
        val stages = mutableListOf(SkyMaskPostProcessStage("00-clean-input", stackedSky))
        var working = AdaptiveGradientRemoval().apply(
            stackedSky, alpha, stars, before,
            parameters.gradientStrength, parameters.maximumGradientCorrection
        ).image
        stages += SkyMaskPostProcessStage("01-gradient-removal", working)
        var current = statistics.calculate(working, alpha, stars)
        working = BackgroundNeutralizer().apply(
            working, alpha, stars, current,
            parameters.neutralizationStrength, parameters.maximumNeutralizationCorrection
        ).image
        stages += SkyMaskPostProcessStage("02-background-neutralization", working)
        current = statistics.calculate(working, alpha, stars)
        val stretchResult = when (stretchOperationMode) {
            ReplayStretchOperationMode.PRODUCTION_CURRENT -> AdaptiveAsinhStretch().apply(
                working, alpha, stars, current,
                parameters.stretchBlend, parameters.asinhStrength,
                parameters.highlightProtection, parameters.maximumSkyMedianFactor,
                parameters.minimumBlackWhiteSeparation, parameters.targetDisplaySkyMedian
            )
            ReplayStretchOperationMode.BYPASS -> ReplayAdaptiveAsinhStretch().apply(
                working, alpha, stars, current,
                parameters.stretchBlend, parameters.asinhStrength,
                parameters.highlightProtection, parameters.maximumSkyMedianFactor,
                parameters.minimumBlackWhiteSeparation, parameters.targetDisplaySkyMedian,
                ReplayStretchOperationMode.BYPASS, stretchBlendMode
            )
            else -> ReplayAdaptiveAsinhStretch().apply(
                working, alpha, stars, current,
                parameters.stretchBlend, parameters.asinhStrength,
                parameters.highlightProtection, parameters.maximumSkyMedianFactor,
                parameters.minimumBlackWhiteSeparation, parameters.targetDisplaySkyMedian,
                stretchOperationMode, stretchBlendMode
            )
        }
        working = stretchResult.image
        stages += SkyMaskPostProcessStage("03-adaptive-stretch", working)
        current = statistics.calculate(working, alpha, stars)
        working = ChromaNoiseReducer().apply(
            working, alpha, stars, current,
            parameters.chromaNoiseStrength, parameters.maximumChromaRadius
        ).image
        stages += SkyMaskPostProcessStage("04-chroma-reduction", working)
        current = statistics.calculate(working, alpha, stars)
        working = LocalStarContrastEnhancer().apply(
            working, alpha, stars, current,
            parameters.starContrastStrength, parameters.maximumStarDetailGain,
            parameters.maximumStarWidthGrowth, parameters.minimumStarContrastGain
        ).image
        stages += SkyMaskPostProcessStage("05-star-enhancement", working)
        var after = statistics.calculate(working, alpha, stars)
        val safetyScale = finalSafetyScale(
            before,
            after,
            parameters.maximumSkyMedianFactor,
            parameters.targetDisplaySkyMedian,
            parameters.maximumChannelClippingPercent
        )
        if (safetyScale < 0.999f) {
            working = blendLinearSky(stackedSky, working, alpha, safetyScale)
            after = statistics.calculate(working, alpha, stars)
        }
        stages += SkyMaskPostProcessStage("06-final-safety", working)
        val backgroundMatch = SkyBackgroundToneMatcher.calculate(before, after)
        if (backgroundMatch.linearOffset > 0f) {
            val output = working.pixels.copyOf()
            output.indices.forEach { index ->
                output[index] = SkyBackgroundToneMatcher.apply(
                    output[index],
                    alpha.alphaAt(index % working.width, index / working.width),
                    backgroundMatch
                )
            }
            working = ArgbPixelImage(working.width, working.height, output)
        }
        stages += SkyMaskPostProcessStage("07-background-match", working)
        val composed = SkyForegroundComposer().compose(
            stackedSky = working,
            reference = reference,
            featheredSkyMask = compositionAlpha,
            validCoverage = compositionAlpha,
            precomputedEffectiveSkyAlpha = compositionAlpha
        ).image
        return ReplayProcessedSky(
            working,
            composed,
            stages,
            stretchResult.diagnostics.copy(
                medianSafetyScale = stretchResult.diagnostics.medianSafetyScale * safetyScale
            ),
            compositionAlpha
        )
    }

    private fun finalSafetyScale(
        before: com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult,
        after: com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult,
        maximumMedianFactor: Float,
        targetDisplaySkyMedian: Float,
        maximumClippingPercent: Float
    ): Float {
        if (before.skyPixelCount == 0 || after.skyPixelCount == 0) return 0f
        val allowedMedian = maxOf(
            com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer.srgbToLinear(
                targetDisplaySkyMedian
            ),
            before.luminanceMedian * maximumMedianFactor,
            before.luminanceMedian + maxOf(before.luminanceMad * 2f, 1f / 4095f)
        )
        val medianScale = if (
            after.luminanceMedian > allowedMedian && after.luminanceMedian > before.luminanceMedian
        ) {
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

    private fun blendLinearSky(
        original: ArgbPixelImage,
        processed: ArgbPixelImage,
        alpha: AlphaMask,
        amount: Float
    ): ArgbPixelImage {
        val output = original.pixels.copyOf()
        output.indices.forEach { index ->
            val value = alpha.alphaAt(index % original.width, index / original.width)
            if (value <= 0.001f) return@forEach
            val first = original.pixels[index]
            val second = processed.pixels[index]
            output[index] = packLinear(
                linearChannel(first, 16) +
                    (linearChannel(second, 16) - linearChannel(first, 16)) * amount,
                linearChannel(first, 8) +
                    (linearChannel(second, 8) - linearChannel(first, 8)) * amount,
                linearChannel(first, 0) +
                    (linearChannel(second, 0) - linearChannel(first, 0)) * amount
            )
        }
        return ArgbPixelImage(original.width, original.height, output)
    }
}
