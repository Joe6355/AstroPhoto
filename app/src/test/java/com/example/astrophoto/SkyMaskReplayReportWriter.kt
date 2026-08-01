package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class SkyMaskReplayWriteResult(
    val fileCount: Int,
    val treeSha256: String,
    val pipelineManifestSha256: String,
    val strictStarMetricsSha256: String,
    val boundaryMetricsSha256: String,
    val htmlSha256: String
)

internal object SkyMaskReplayReportWriter {
    fun write(bundle: SkyMaskReplayBundle, outputRoot: Path): SkyMaskReplayWriteResult {
        require(outputRoot.fileName.toString().isNotBlank())
        if (Files.exists(outputRoot)) outputRoot.toFile().deleteRecursively()
        Files.createDirectories(outputRoot)
        writeGlobalLayers(bundle, outputRoot)
        writeBoundaryArtifacts(bundle, outputRoot)
        writeAblationMetrics(bundle, outputRoot)
        writePostProcessStages(bundle, outputRoot)
        writeStrictStarArtifacts(bundle, outputRoot)
        writeWindowArtifacts(bundle, outputRoot)
        writeRootCause(bundle, outputRoot)
        Files.writeString(
            outputRoot.resolve("pipeline-manifest.json"),
            bundle.pipelineManifestJson,
            StandardCharsets.UTF_8
        )
        Files.writeString(
            outputRoot.resolve("baseline-hashes.json"),
            baselineHashes(bundle),
            StandardCharsets.UTF_8
        )
        Files.writeString(
            outputRoot.resolve("report-summary.md"),
            summary(bundle),
            StandardCharsets.UTF_8
        )
        Files.writeString(
            outputRoot.resolve("index.html"),
            html(bundle),
            StandardCharsets.UTF_8
        )
        return writeDeterminismManifest(outputRoot)
    }

    private fun writeGlobalLayers(bundle: SkyMaskReplayBundle, root: Path) {
        val images = linkedMapOf(
            "reference.png" to bundle.reference,
            "unmasked-integration.png" to bundle.unmaskedIntegration,
            "clean-stack.png" to bundle.cleanStack,
            "processed-sky.png" to bundle.processedSky,
            "composed-clean.png" to bundle.cleanComposed,
            "composed-current.png" to bundle.composedCurrent,
            "final-current.png" to bundle.finalCurrent,
            "initial-mask.png" to booleanMaskImage(bundle.initialMask),
            "refined-mask.png" to booleanMaskImage(bundle.refinedMask),
            "foreground-protection.png" to booleanMaskImage(bundle.foregroundProtection),
            "sky-selection.png" to booleanMaskImage(bundle.skySelection),
            "effective-alpha-heatmap.png" to alphaHeatmap(bundle.effectiveAlpha)
        )
        images.forEach { (name, image) -> ReplayDiagnosticImageIo.writePng(root.resolve(name), image) }
        writeAlpha16(root.resolve("effective-alpha.png"), bundle.effectiveAlpha)
        writeAlphaRaw(root.resolve("effective-alpha.f32le"), bundle.effectiveAlpha)
        bundle.variants.forEach { variant ->
            ReplayDiagnosticImageIo.writePng(
                root.resolve("composition-${variant.id.stableId}.png"),
                variant.output
            )
        }
        ReplayDiagnosticImageIo.writePng(
            root.resolve("composition-current-alpha.png"),
            bundle.variants.single { it.id == SkyMaskReplayVariantId.CURRENT }.output
        )
        ReplayDiagnosticImageIo.writePng(
            root.resolve("composition-no-foreground-protection.png"),
            bundle.variants.single { it.id == SkyMaskReplayVariantId.NO_PROTECTION }.output
        )
        val current = bundle.variants.single { it.id == SkyMaskReplayVariantId.CURRENT }.output
        fun diff(name: String, id: SkyMaskReplayVariantId) {
            ReplayDiagnosticImageIo.writeDifference(
                root.resolve(name),
                bundle.variants.single { it.id == id }.output,
                current,
                DIFF_SCALE
            )
        }
        diff("diff-current-vs-no-mask.png", SkyMaskReplayVariantId.NO_MASK)
        diff("diff-current-vs-hard-mask.png", SkyMaskReplayVariantId.HARD_MASK)
        diff("diff-current-vs-no-protection.png", SkyMaskReplayVariantId.NO_PROTECTION)
        diff("diff-current-vs-no-postprocess.png", SkyMaskReplayVariantId.NO_POSTPROCESS)
    }

    private fun writeBoundaryArtifacts(bundle: SkyMaskReplayBundle, root: Path) {
        val initialBoundary = SkyMaskReplayMath.boundaryPixels(
            bundle.initialMask.copyPixels(), bundle.initialMask.width, bundle.initialMask.height
        )
        val refinedBoundary = SkyMaskReplayMath.boundaryPixels(
            bundle.refinedMask.copyPixels(), bundle.refinedMask.width, bundle.refinedMask.height
        )
        val foregroundBoundary = SkyMaskReplayMath.boundaryPixels(
            bundle.foregroundProtection.copyPixels(),
            bundle.foregroundProtection.width,
            bundle.foregroundProtection.height
        )
        val transition = BooleanArray(bundle.effectiveAlpha.width * bundle.effectiveAlpha.height) { index ->
            val value = bundle.effectiveAlpha.alphaAt(
                index % bundle.effectiveAlpha.width,
                index / bundle.effectiveAlpha.width
            )
            value in 0.01f..0.99f
        }
        linkedMapOf(
            "initial-boundary.png" to initialBoundary,
            "refined-boundary.png" to refinedBoundary,
            "effective-alpha-transition-band.png" to transition,
            "foreground-boundary.png" to foregroundBoundary
        ).forEach { (name, mask) ->
            ReplayDiagnosticImageIo.writePng(
                root.resolve(name),
                ReplayDiagnosticImageIo.booleanMask(
                    bundle.reference.width,
                    bundle.reference.height,
                    mask,
                    0xFFFFFFFF.toInt()
                )
            )
        }
        val metrics = bundle.boundaryMetrics
        Files.writeString(
            root.resolve("mask-statistics.json"),
            buildString {
                appendLine("{")
                appendLine("  \"boundaryLengthDefinition\": \"4-neighbour boundary pixel count proxy\",")
                appendLine("  \"featherWidthDefinition\": \"horizontal and vertical alpha-transition run proxy\",")
                appendLine("  \"foregroundTruthAvailable\": false,")
                appendLine("  \"initialBoundaryPixels\": ${metrics.initial.boundaryPixels},")
                appendLine("  \"refinedBoundaryPixels\": ${metrics.refined.boundaryPixels},")
                appendLine("  \"foregroundBoundaryPixels\": ${metrics.foregroundProtection.boundaryPixels},")
                appendLine("  \"transitionBandArea\": ${metrics.transitionBandArea},")
                appendLine("  \"meanTransitionRunWidth\": ${number(metrics.alpha.meanTransitionRunWidth)},")
                appendLine("  \"maximumTransitionRunWidth\": ${metrics.alpha.maximumTransitionRunWidth},")
                appendLine("  \"initialDisconnectedRegions\": ${metrics.initial.disconnectedRegions},")
                appendLine("  \"refinedDisconnectedRegions\": ${metrics.refined.disconnectedRegions},")
                appendLine("  \"initialSmallIslands\": ${metrics.initial.smallIslandCount},")
                appendLine("  \"refinedSmallIslands\": ${metrics.refined.smallIslandCount},")
                appendLine("  \"initialHoles\": ${metrics.initial.holeCount},")
                appendLine("  \"refinedHoles\": ${metrics.refined.holeCount},")
                appendLine("  \"foregroundRiskInclusionProxyPixels\": ${metrics.foregroundRiskInclusionProxyPixels},")
                appendLine("  \"initialPixelsRemovedByRefinementProxy\": ${metrics.initialPixelsRemovedByRefinementProxy},")
                appendLine("  \"alphaZeroPixels\": ${metrics.alpha.zeroPixels},")
                appendLine("  \"alphaBelowOnePercentPixels\": ${metrics.alpha.belowOnePercentPixels},")
                appendLine("  \"alphaTransitionPixels\": ${metrics.alpha.transitionPixels},")
                appendLine("  \"alphaAboveNinetyNinePercentPixels\": ${metrics.alpha.aboveNinetyNinePercentPixels},")
                appendLine("  \"alphaOnePixels\": ${metrics.alpha.onePixels}")
                appendLine("}")
            },
            StandardCharsets.UTF_8
        )
        val boundaryCsv = buildString {
            appendLine("window_id,center_x,center_y,distance_to_boundary,bright_rim,dark_rim,halo_asymmetry,halo_score,luminance_jump,chroma_jump,first_derivative_excess,second_derivative_spike,local_variance_mismatch,edge_aligned_residual,leakage_score")
            bundle.windowMetrics.sortedBy { it.windowId }.forEach { value ->
                appendLine(listOf(
                    csv(value.windowId), value.centerX, value.centerY,
                    number(value.distanceToBoundary), number(value.brightRim), number(value.darkRim),
                    number(value.haloAsymmetry), number(value.haloScore), number(value.luminanceJump),
                    number(value.chromaJump), number(value.firstDerivativeExcess),
                    number(value.secondDerivativeSpike), number(value.localVarianceMismatch),
                    number(value.edgeAlignedResidual), number(value.leakageScore)
                ).joinToString(","))
            }
        }
        Files.writeString(root.resolve("boundary-metrics.csv"), boundaryCsv, StandardCharsets.UTF_8)
        Files.writeString(
            root.resolve("halo-ranking.csv"),
            rankingCsv(bundle.windowMetrics.sortedWith(compareByDescending<SkyMaskWindowMetrics> { it.haloScore }.thenBy { it.windowId }), "halo_score") { it.haloScore },
            StandardCharsets.UTF_8
        )
        Files.writeString(
            root.resolve("leakage-ranking.csv"),
            rankingCsv(bundle.windowMetrics.sortedWith(compareByDescending<SkyMaskWindowMetrics> { it.leakageScore }.thenBy { it.windowId }), "leakage_score") { it.leakageScore },
            StandardCharsets.UTF_8
        )
    }

    private fun writeAblationMetrics(bundle: SkyMaskReplayBundle, root: Path) {
        Files.writeString(
            root.resolve("ablation-metrics.csv"),
            buildString {
                appendLine("variant,initial_mask,refinement,alpha,foreground_protection,postprocess,sky_mad,foreground_mean_change,banding_proxy,mean_halo_score,mean_leakage_score,strict_star_median_flux_retention,strict_star_max_centroid_shift")
                bundle.variantMetrics.forEach { metrics ->
                    val variant = bundle.variants.single { it.id == metrics.variant }
                    val alpha = when (variant.id) {
                        SkyMaskReplayVariantId.NO_MASK -> "one"
                        SkyMaskReplayVariantId.HARD_MASK -> "binary"
                        else -> "derived"
                    }
                    appendLine(listOf(
                        variant.id.stableId,
                        variant.initialMaskEnabled,
                        variant.refinementEnabled,
                        alpha,
                        variant.foregroundProtectionEnabled,
                        variant.postProcessingEnabled,
                        number(metrics.skyMad),
                        number(metrics.foregroundMeanChange),
                        number(metrics.bandingProxy),
                        number(metrics.meanHaloScore),
                        number(metrics.meanLeakageScore),
                        number(metrics.strictStarMedianFluxRetention),
                        number(metrics.strictStarMaximumCentroidShift)
                    ).joinToString(","))
                }
            },
            StandardCharsets.UTF_8
        )
    }

    private fun writePostProcessStages(bundle: SkyMaskReplayBundle, root: Path) {
        val stageRoot = root.resolve("postprocess")
        Files.createDirectories(stageRoot)
        val clean = bundle.postProcessingStages.first().image
        bundle.postProcessingStages.forEach { stage ->
            ReplayDiagnosticImageIo.writePng(stageRoot.resolve("${stage.id}.png"), stage.image)
            ReplayDiagnosticImageIo.writeDifference(
                stageRoot.resolve("${stage.id}-minus-clean.png"),
                clean,
                stage.image,
                DIFF_SCALE
            )
        }
        Files.writeString(
            root.resolve("postprocess-stage-metrics.csv"),
            buildString {
                appendLine("stage,sky_mad,banding_proxy,boundary_edge_excess,mean_absolute_change_from_clean")
                bundle.postProcessingStageMetrics.forEach { value ->
                    appendLine(listOf(
                        value.stage,
                        number(value.skyMad),
                        number(value.bandingProxy),
                        number(value.boundaryEdgeExcess),
                        number(value.meanAbsoluteChangeFromClean)
                    ).joinToString(","))
                }
            },
            StandardCharsets.UTF_8
        )
    }

    private fun writeStrictStarArtifacts(bundle: SkyMaskReplayBundle, root: Path) {
        val csv = buildString {
            appendLine("star_id,stage,centroid_x,centroid_y,peak_luminance,aperture_flux,local_background,local_contrast,robust_width,ellipticity,chroma_residual,distance_to_mask_boundary,center_alpha,min_aperture_alpha,mean_aperture_alpha,max_aperture_alpha,aperture_fraction_alpha_below_0_5,aperture_fraction_protected,flux_retention_from_clean,peak_attenuation_from_clean,centroid_shift_from_clean,width_ratio_from_clean")
            bundle.strictStarMetrics.forEach { value ->
                appendLine(listOf(
                    csv(value.starId), csv(value.stage), number(value.centroidX), number(value.centroidY),
                    number(value.peakLuminance), number(value.apertureFlux), number(value.localBackground),
                    number(value.localContrast), number(value.robustWidth), number(value.ellipticity),
                    number(value.chromaResidual), number(value.distanceToMaskBoundary), number(value.centerAlpha),
                    number(value.minimumApertureAlpha), number(value.meanApertureAlpha),
                    number(value.maximumApertureAlpha), number(value.apertureFractionBelowHalfAlpha),
                    number(value.apertureFractionProtected), number(value.fluxRetentionFromClean),
                    number(value.peakAttenuationFromClean), number(value.centroidShiftFromClean),
                    number(value.widthRatioFromClean)
                ).joinToString(","))
            }
        }
        Files.writeString(root.resolve("strict-star-metrics.csv"), csv, StandardCharsets.UTF_8)
        val final = bundle.strictStarMetrics.filter { it.stage == "final-current" }
        Files.writeString(
            root.resolve("strict-star-summary.md"),
            buildString {
                appendLine("# Strict-star replay summary")
                appendLine()
                appendLine("Exactly ${bundle.fixture.strictReferenceStarLabels.size} strict confirmed stars are measured; `uncertain` labels are excluded.")
                appendLine()
                appendLine("| star | distance to refined boundary px | center alpha | final flux retention | final centroid shift px | final width ratio |")
                appendLine("|---|---:|---:|---:|---:|---:|")
                final.forEach { value ->
                    appendLine("| ${value.starId} | ${number(value.distanceToMaskBoundary)} | ${number(value.centerAlpha)} | ${number(value.fluxRetentionFromClean)} | ${number(value.centroidShiftFromClean)} | ${number(value.widthRatioFromClean)} |")
                }
                appendLine()
                appendLine("These are fixture replay measurements, not a claim about every star in the photograph.")
            },
            StandardCharsets.UTF_8
        )
        val stages = listOf("clean-stack", "processed-sky", "composed-current", "final-current", "no-mask", "hard-mask", "no-protection")
        val stageImages = mapOf(
            "clean-stack" to bundle.cleanStack,
            "processed-sky" to bundle.processedSky,
            "composed-current" to bundle.composedCurrent,
            "final-current" to bundle.finalCurrent,
            "no-mask" to bundle.variants.single { it.id == SkyMaskReplayVariantId.NO_MASK }.output,
            "hard-mask" to bundle.variants.single { it.id == SkyMaskReplayVariantId.HARD_MASK }.output,
            "no-protection" to bundle.variants.single { it.id == SkyMaskReplayVariantId.NO_PROTECTION }.output
        )
        val rows = bundle.fixture.strictReferenceStarLabels.map { star ->
            stages.map { stage ->
                ReplayDiagnosticImageIo.cropAround(
                    stageImages.getValue(stage), star.x.roundToInt(), star.y.roundToInt(), 63
                )
            }.let(::horizontal)
        }
        ReplayDiagnosticImageIo.writePng(root.resolve("strict-star-contact-sheet.png"), vertical(rows))
        val clipping = final.sortedWith(
            compareByDescending<SkyMaskStarStageMetrics> { 1.0 - it.fluxRetentionFromClean }
                .thenBy { it.starId }
        )
        Files.writeString(
            root.resolve("star-clipping-ranking.csv"),
            buildString {
                appendLine("rank,star_id,flux_lost_fraction,peak_attenuation,centroid_shift,shape_width_ratio")
                clipping.forEachIndexed { index, value ->
                    appendLine(listOf(
                        index + 1, csv(value.starId), number(1.0 - value.fluxRetentionFromClean),
                        number(value.peakAttenuationFromClean), number(value.centroidShiftFromClean),
                        number(value.widthRatioFromClean)
                    ).joinToString(","))
                }
            },
            StandardCharsets.UTF_8
        )
    }

    private fun writeWindowArtifacts(bundle: SkyMaskReplayBundle, root: Path) {
        val windowsRoot = root.resolve("windows")
        val current = bundle.variants.single { it.id == SkyMaskReplayVariantId.CURRENT }.output
        val hard = bundle.variants.single { it.id == SkyMaskReplayVariantId.HARD_MASK }.output
        val noMask = bundle.variants.single { it.id == SkyMaskReplayVariantId.NO_MASK }.output
        val noProtection = bundle.variants.single { it.id == SkyMaskReplayVariantId.NO_PROTECTION }.output
        val noPost = bundle.variants.single { it.id == SkyMaskReplayVariantId.NO_POSTPROCESS }.output
        val initialVisual = booleanMaskImage(bundle.initialMask)
        val refinedVisual = booleanMaskImage(bundle.refinedMask)
        val alphaVisual = alphaHeatmap(bundle.effectiveAlpha)
        val protectionVisual = booleanMaskImage(bundle.foregroundProtection)
        val gradientValues = SkyMaskReplayMath.alphaGradient(bundle.effectiveAlpha)
        val gradientVisual = scalarHeatmap(bundle.reference.width, bundle.reference.height, gradientValues)
        val boundary = SkyMaskReplayMath.boundaryPixels(
            bundle.refinedMask.copyPixels(), bundle.reference.width, bundle.reference.height
        )
        val boundaryOverlay = overlay(bundle.reference, boundary, 0xFFFF00FF.toInt())
        val metricById = bundle.windowMetrics.associateBy { it.windowId }
        bundle.windows.forEach { window ->
            val windowRoot = windowsRoot.resolve(window.id)
            Files.createDirectories(windowRoot)
            fun crop(image: ArgbPixelImage): ArgbPixelImage {
                val radius = window.size / 2
                return ReplayDiagnosticImageIo.crop(
                    image,
                    window.centerX - radius,
                    window.centerY - radius,
                    window.size,
                    window.size
                )
            }
            linkedMapOf(
                "reference.png" to bundle.reference,
                "clean-stack.png" to bundle.cleanStack,
                "processed-sky.png" to bundle.processedSky,
                "initial-mask.png" to initialVisual,
                "refined-mask.png" to refinedVisual,
                "effective-alpha.png" to alphaVisual,
                "foreground-protection.png" to protectionVisual,
                "composed-current.png" to current,
                "composed-hard-mask.png" to hard,
                "composed-no-mask.png" to noMask,
                "composed-no-protection.png" to noProtection,
                "alpha-gradient.png" to gradientVisual,
                "boundary-overlay.png" to boundaryOverlay
            ).forEach { (name, image) ->
                ReplayDiagnosticImageIo.writePng(windowRoot.resolve(name), crop(image))
            }
            ReplayDiagnosticImageIo.writeDifference(
                windowRoot.resolve("current-minus-clean.png"), crop(bundle.cleanStack), crop(current), DIFF_SCALE
            )
            ReplayDiagnosticImageIo.writeDifference(
                windowRoot.resolve("current-minus-no-mask.png"), crop(noMask), crop(current), DIFF_SCALE
            )
            writeProfile(
                windowRoot.resolve("profile-horizontal.csv"),
                bundle,
                window,
                horizontal = true
            )
            writeProfile(
                windowRoot.resolve("profile-vertical.csv"),
                bundle,
                window,
                horizontal = false
            )
            val metric = metricById.getValue(window.id)
            Files.writeString(
                windowRoot.resolve("metrics.json"),
                buildString {
                    appendLine("{")
                    appendLine("  \"id\": ${json(window.id)},")
                    appendLine("  \"source\": ${json(window.source)},")
                    appendLine("  \"coordinateSpace\": \"720x960 reference/output pixels\",")
                    appendLine("  \"centerX\": ${window.centerX},")
                    appendLine("  \"centerY\": ${window.centerY},")
                    appendLine("  \"cropSize\": ${window.size},")
                    appendLine("  \"measurementInterpolation\": \"none\",")
                    appendLine("  \"distanceToBoundary\": ${number(metric.distanceToBoundary)},")
                    appendLine("  \"brightRim\": ${number(metric.brightRim)},")
                    appendLine("  \"darkRim\": ${number(metric.darkRim)},")
                    appendLine("  \"haloAsymmetry\": ${number(metric.haloAsymmetry)},")
                    appendLine("  \"haloScore\": ${number(metric.haloScore)},")
                    appendLine("  \"luminanceJump\": ${number(metric.luminanceJump)},")
                    appendLine("  \"chromaJump\": ${number(metric.chromaJump)},")
                    appendLine("  \"firstDerivativeExcess\": ${number(metric.firstDerivativeExcess)},")
                    appendLine("  \"secondDerivativeSpike\": ${number(metric.secondDerivativeSpike)},")
                    appendLine("  \"localVarianceMismatch\": ${number(metric.localVarianceMismatch)},")
                    appendLine("  \"edgeAlignedResidual\": ${number(metric.edgeAlignedResidual)},")
                    appendLine("  \"leakageScore\": ${number(metric.leakageScore)}")
                    appendLine("}")
                },
                StandardCharsets.UTF_8
            )
        }
    }

    private fun writeProfile(
        path: Path,
        bundle: SkyMaskReplayBundle,
        window: SkyMaskDiagnosticWindow,
        horizontal: Boolean
    ) {
        val radius = window.size / 2
        val refined = bundle.refinedMask.copyPixels()
        val protected = bundle.foregroundProtection.copyPixels()
        val current = bundle.variants.single { it.id == SkyMaskReplayVariantId.CURRENT }.output
        val hard = bundle.variants.single { it.id == SkyMaskReplayVariantId.HARD_MASK }.output
        val noMask = bundle.variants.single { it.id == SkyMaskReplayVariantId.NO_MASK }.output
        val text = buildString {
            appendLine("offset,x,y,reference_luminance,clean_luminance,processed_sky_luminance,current_luminance,hard_mask_luminance,no_mask_luminance,effective_alpha,refined_mask,foreground_protection")
            for (offset in -radius..radius) {
                val x = if (horizontal) window.centerX + offset else window.centerX
                val y = if (horizontal) window.centerY else window.centerY + offset
                val index = y * bundle.reference.width + x
                appendLine(listOf(
                    offset, x, y,
                    number(luminance(bundle.reference.pixels[index])),
                    number(luminance(bundle.cleanStack.pixels[index])),
                    number(luminance(bundle.processedSky.pixels[index])),
                    number(luminance(current.pixels[index])),
                    number(luminance(hard.pixels[index])),
                    number(luminance(noMask.pixels[index])),
                    number(bundle.effectiveAlpha.alphaAt(x, y).toDouble()),
                    if (refined[index]) 1 else 0,
                    if (protected[index]) 1 else 0
                ).joinToString(","))
            }
        }
        Files.writeString(path, text, StandardCharsets.UTF_8)
    }

    private fun writeRootCause(bundle: SkyMaskReplayBundle, root: Path) {
        Files.writeString(
            root.resolve("root-cause.json"),
            buildString {
                appendLine("{")
                appendLine("  \"classificationPolicy\": \"single-stage ablation must reduce mean halo by at least 25 percent\",")
                appendLine("  \"foregroundTruthAvailable\": false,")
                appendLine("  \"issues\": [")
                bundle.issues.forEachIndexed { index, issue ->
                    append("    {\"windowId\":${json(issue.windowId)}")
                    append(",\"cause\":${json(issue.cause.name)}")
                    append(",\"observedDefect\":${json(issue.observedDefect)}")
                    append(",\"firstBadStage\":${json(issue.firstBadStage)}")
                    append(",\"lastCleanStage\":${json(issue.lastCleanStage)}")
                    append(",\"supportingMetric\":${json(issue.supportingMetric)}")
                    append(",\"confidence\":${number(issue.confidence)}")
                    append(",\"minimalFixCandidate\":${json(issue.minimalFixCandidate)}")
                    append(",\"fixRisk\":${json(issue.fixRisk)}")
                    append(",\"requiredRegressionTest\":${json(issue.requiredRegressionTest)}}")
                    appendLine(if (index == bundle.issues.lastIndex) "" else ",")
                }
                appendLine("  ]")
                appendLine("}")
            },
            StandardCharsets.UTF_8
        )
    }

    private fun baselineHashes(bundle: SkyMaskReplayBundle): String {
        val initial = bundle.initialMask.copyPixels()
        val refined = bundle.refinedMask.copyPixels()
        val alphaBytes = alphaBytes(bundle.effectiveAlpha)
        val values = linkedMapOf(
            "referenceArgbSha256" to sha256Argb(bundle.reference),
            "cleanStackArgbSha256" to sha256Argb(bundle.cleanStack),
            "processedSkyArgbSha256" to sha256Argb(bundle.processedSky),
            "composedCurrentArgbSha256" to sha256Argb(bundle.composedCurrent),
            "finalCurrentArgbSha256" to sha256Argb(bundle.finalCurrent),
            "initialMaskSha256" to sha256(ByteArray(initial.size) { if (initial[it]) 1 else 0 }),
            "refinedMaskSha256" to sha256(ByteArray(refined.size) { if (refined[it]) 1 else 0 }),
            "effectiveAlphaFloat32LeSha256" to sha256(alphaBytes)
        )
        return values.entries.joinToString(
            prefix = "{\n",
            postfix = "\n}\n",
            separator = ",\n"
        ) { "  ${json(it.key)}: ${json(it.value)}" }
    }

    private fun summary(bundle: SkyMaskReplayBundle): String = buildString {
        appendLine("# Sky-mask replay diagnostics: ${bundle.fixture.name}")
        appendLine()
        appendLine("> Replay-only diagnostics. Production processing was not changed.")
        appendLine()
        appendLine("- geometry: `${bundle.reference.width}x${bundle.reference.height}`, reference/output coordinates, no crop or resize")
        appendLine("- selected candidate: `${bundle.selectedCandidateType}`")
        appendLine("- clean candidate accepted: `${bundle.cleanCandidateAccepted}`")
        appendLine("- processed candidate accepted: `${bundle.processedCandidateAccepted}`")
        appendLine("- processed rejection reasons: `${bundle.processedCandidateRejectionReasons.joinToString("|")}`")
        appendLine("- accepted original indices: `${bundle.acceptedOriginalFrameIndices.joinToString(",")}`")
        appendLine("- rejected original indices: `${bundle.rejectedOriginalFrameIndices.joinToString(",")}`")
        appendLine("- initial mask confidence/fallback: `${bundle.initialMaskConfidence}` / `${bundle.initialMaskUsedFallback}`")
        appendLine("- refined mask confidence/fallback: `${bundle.refinedMaskConfidence}` / `${bundle.refinedMaskUsedFallback}`")
        appendLine("- feather radius: `${bundle.featherRadius}` px")
        appendLine("- foreground protection radius: `${bundle.foregroundProtectionRadius}` px")
        appendLine("- adaptive replay matches `AdaptivePresetProcessor`: `${bundle.adaptiveReplayMatchesProductionPixels}`")
        appendLine("- active `FileBackedAdaptivePresetProcessor` max channel delta vs exposed component replay: `${bundle.activeFileBackedMaximumChannelDifference}`")
        appendLine("- active file-backed pixels differing from component replay: `${bundle.activeFileBackedDifferentPixelCount}`")
        appendLine("- strict confirmed stars measured: `${bundle.fixture.strictReferenceStarLabels.size}`")
        appendLine("- manual foreground truth available: `false`; inclusion/exclusion and leakage values are proxies")
        appendLine()
        appendLine("## Ablation")
        appendLine()
        appendLine("| variant | halo | leakage | sky MAD | foreground change |")
        appendLine("|---|---:|---:|---:|---:|")
        bundle.variantMetrics.forEach { value ->
            appendLine("| ${value.variant.stableId} | ${number(value.meanHaloScore)} | ${number(value.meanLeakageScore)} | ${number(value.skyMad)} | ${number(value.foregroundMeanChange)} |")
        }
        appendLine()
        appendLine("## Root-cause classification")
        appendLine()
        val first = bundle.issues.firstOrNull()
        appendLine("- classification: `${first?.cause?.name ?: SkyMaskReplayCause.INSUFFICIENT_EVIDENCE.name}`")
        appendLine("- first bad stage: `${first?.firstBadStage ?: "not_proven"}`")
        appendLine("- evidence: `${first?.supportingMetric ?: "no ranked boundary windows"}`")
        appendLine()
        appendLine("## Post-processing stage trace")
        appendLine()
        appendLine("| stage | sky MAD | banding proxy | boundary edge excess | mean change from clean |")
        appendLine("|---|---:|---:|---:|---:|")
        bundle.postProcessingStageMetrics.forEach { value ->
            appendLine("| ${value.stage} | ${number(value.skyMad)} | ${number(value.bandingProxy)} | ${number(value.boundaryEdgeExcess)} | ${number(value.meanAbsoluteChangeFromClean)} |")
        }
        appendLine()
        appendLine("No production fix is included in this report.")
    }

    private fun html(bundle: SkyMaskReplayBundle): String {
        val topHalo = bundle.windowMetrics.sortedWith(
            compareByDescending<SkyMaskWindowMetrics> { it.haloScore }.thenBy { it.windowId }
        ).take(8)
        val strictIds = bundle.fixture.strictReferenceStarLabels.map { "strict-star-${it.id}" }
        fun cards(paths: List<Pair<String, String>>): String = paths.joinToString("\n") { (label, path) ->
            "<figure><img src=\"${htmlEscape(path)}\" alt=\"${htmlEscape(label)}\"><figcaption>${htmlEscape(label)}</figcaption></figure>"
        }
        val global = listOf(
            "Reference" to "reference.png",
            "Clean stack" to "clean-stack.png",
            "Processed sky" to "processed-sky.png",
            "Current composition" to "composed-current.png",
            "Final selected" to "final-current.png",
            "Initial mask" to "initial-mask.png",
            "Refined mask" to "refined-mask.png",
            "Effective alpha" to "effective-alpha-heatmap.png",
            "Foreground protection" to "foreground-protection.png"
        )
        val ablations = bundle.variants.map {
            it.id.stableId to "composition-${it.id.stableId}.png"
        }
        val postprocess = bundle.postProcessingStages.map {
            it.id to "postprocess/${it.id}.png"
        }
        val issueRows = bundle.issues.joinToString("\n") {
            "<tr><td><a href=\"windows/${htmlEscape(it.windowId)}/metrics.json\">${htmlEscape(it.windowId)}</a></td>" +
                "<td>${it.cause.name}</td><td>${htmlEscape(it.firstBadStage)}</td>" +
                "<td>${htmlEscape(it.supportingMetric)}</td><td>${number(it.confidence)}</td></tr>"
        }
        val haloCards = topHalo.flatMap { metric ->
            listOf(
                "${metric.windowId}: current" to "windows/${metric.windowId}/composed-current.png",
                "${metric.windowId}: alpha" to "windows/${metric.windowId}/effective-alpha.png",
                "${metric.windowId}: hard" to "windows/${metric.windowId}/composed-hard-mask.png"
            )
        }
        val starCards = strictIds.flatMap { id ->
            listOf(
                "$id: clean" to "windows/$id/clean-stack.png",
                "$id: current" to "windows/$id/composed-current.png",
                "$id: alpha" to "windows/$id/effective-alpha.png"
            )
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AstroPhoto sky-mask replay diagnostics</title>
<style>
body{font-family:system-ui,sans-serif;background:#10131a;color:#e8edf5;margin:0;padding:24px}a{color:#8bc5ff}h1,h2{line-height:1.2}.warning{padding:12px;border:1px solid #efb64c;background:#362b15}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:12px}figure{margin:0;background:#1b2230;padding:8px;border-radius:6px}img{width:100%;height:auto;image-rendering:auto}figcaption{padding-top:6px;font-size:13px}table{border-collapse:collapse;width:100%;font-size:13px}th,td{border:1px solid #394356;padding:6px;text-align:left}.links{display:flex;gap:14px;flex-wrap:wrap}
</style></head><body>
<h1>AstroPhoto — urban-window-30 sky-mask replay</h1>
<p class="warning"><strong>Replay-only diagnostics.</strong> Production processing is unchanged. Foreground inclusion/exclusion and leakage measurements are explicitly proxies because no manual foreground mask exists.</p>
<p>Geometry: 720×960 reference/output coordinates; no common-region crop and no output resize. Selected candidate: <code>${bundle.selectedCandidateType}</code>.</p>
<div class="links"><a href="pipeline-manifest.json">pipeline manifest</a><a href="baseline-hashes.json">baseline hashes</a><a href="mask-statistics.json">mask statistics</a><a href="ablation-metrics.csv">ablation CSV</a><a href="strict-star-metrics.csv">strict-star CSV</a><a href="boundary-metrics.csv">boundary CSV</a><a href="root-cause.json">root cause JSON</a></div>
<h2>Global layers</h2><div class="grid">${cards(global)}</div>
<h2>Ablation outputs</h2><div class="grid">${cards(ablations)}</div>
<h2>Post-processing stage trace</h2><div class="grid">${cards(postprocess)}</div>
<h2>Top halo/leakage windows</h2><div class="grid">${cards(haloCards)}</div>
<h2>Strict confirmed stars</h2><div class="grid">${cards(starCards)}</div>
<h2>Ranked issues</h2><table><thead><tr><th>window</th><th>classification</th><th>first bad stage</th><th>metric</th><th>confidence</th></tr></thead><tbody>$issueRows</tbody></table>
<p>No production patch is part of this report.</p>
</body></html>
"""
    }

    private fun writeDeterminismManifest(root: Path): SkyMaskReplayWriteResult {
        val excluded = setOf("sha256-manifest.txt", "determinism.json")
        val entries = Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile)
                .map { path -> root.relativize(path).invariantSeparatorsPathString to sha256File(path) }
                .filter { it.first !in excluded }
                .sorted(compareBy<Pair<String, String>> { it.first })
                .toList()
        }
        val manifestText = entries.joinToString(separator = "\n", postfix = "\n") {
            "${it.second}  ${it.first}"
        }
        Files.writeString(root.resolve("sha256-manifest.txt"), manifestText, StandardCharsets.UTF_8)
        val treeHash = sha256(manifestText.toByteArray(StandardCharsets.UTF_8))
        val pipelineHash = sha256File(root.resolve("pipeline-manifest.json"))
        val strictHash = sha256File(root.resolve("strict-star-metrics.csv"))
        val boundaryHash = sha256File(root.resolve("boundary-metrics.csv"))
        val htmlHash = sha256File(root.resolve("index.html"))
        val fileCount = entries.size + 2
        Files.writeString(
            root.resolve("determinism.json"),
            buildString {
                appendLine("{")
                appendLine("  \"definition\": \"tree hash is SHA-256 of sorted path/file-hash manifest excluding sha256-manifest.txt and determinism.json\",")
                appendLine("  \"verification\": \"enforced by SkyMaskReplayDiagnosticsTest second-run byte comparison\",")
                appendLine("  \"fileCount\": $fileCount,")
                appendLine("  \"treeSha256\": ${json(treeHash)},")
                appendLine("  \"pipelineManifestSha256\": ${json(pipelineHash)},")
                appendLine("  \"strictStarMetricsSha256\": ${json(strictHash)},")
                appendLine("  \"boundaryMetricsSha256\": ${json(boundaryHash)},")
                appendLine("  \"htmlSha256\": ${json(htmlHash)}")
                appendLine("}")
            },
            StandardCharsets.UTF_8
        )
        return SkyMaskReplayWriteResult(
            fileCount,
            treeHash,
            pipelineHash,
            strictHash,
            boundaryHash,
            htmlHash
        )
    }

    private fun booleanMaskImage(mask: SkyMask): ArgbPixelImage =
        ReplayDiagnosticImageIo.booleanMask(
            mask.width,
            mask.height,
            mask.copyPixels(),
            0xFFFFFFFF.toInt()
        )

    private fun alphaHeatmap(alpha: AlphaMask): ArgbPixelImage = ArgbPixelImage(
        alpha.width,
        alpha.height,
        IntArray(alpha.width * alpha.height) { index ->
            heatColor(alpha.alphaAt(index % alpha.width, index / alpha.width).toDouble())
        }
    )

    private fun scalarHeatmap(width: Int, height: Int, values: DoubleArray): ArgbPixelImage {
        val maximum = values.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        return ArgbPixelImage(
            width,
            height,
            IntArray(values.size) { heatColor((values[it] / maximum).coerceIn(0.0, 1.0)) }
        )
    }

    private fun heatColor(value: Double): Int {
        val v = value.coerceIn(0.0, 1.0)
        val red = (255.0 * v).roundToInt()
        val green = (255.0 * (1.0 - abs(v * 2.0 - 1.0))).roundToInt()
        val blue = (255.0 * (1.0 - v)).roundToInt()
        return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
    }

    private fun overlay(image: ArgbPixelImage, mask: BooleanArray, color: Int): ArgbPixelImage {
        val pixels = image.pixels.copyOf()
        mask.indices.forEach { if (mask[it]) pixels[it] = color }
        return ArgbPixelImage(image.width, image.height, pixels)
    }

    private fun writeAlpha16(path: Path, alpha: AlphaMask) {
        val image = BufferedImage(alpha.width, alpha.height, BufferedImage.TYPE_USHORT_GRAY)
        require(image.raster.dataBuffer.dataType == DataBuffer.TYPE_USHORT)
        for (y in 0 until alpha.height) for (x in 0 until alpha.width) {
            image.raster.setSample(
                x,
                y,
                0,
                (alpha.alphaAt(x, y) * 65535f).roundToInt().coerceIn(0, 65535)
            )
        }
        check(ImageIO.write(image, "png", path.toFile()))
        image.flush()
    }

    private fun writeAlphaRaw(path: Path, alpha: AlphaMask) {
        Files.write(path, alphaBytes(alpha))
    }

    private fun alphaBytes(alpha: AlphaMask): ByteArray {
        return ReplayDiagnosticHashing.alphaFloat32LittleEndianBytes(alpha)
    }

    private fun horizontal(images: List<ArgbPixelImage>): ArgbPixelImage {
        require(images.isNotEmpty() && images.map { it.height }.distinct().size == 1)
        val width = images.sumOf { it.width }
        val height = images.first().height
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        var left = 0
        images.forEach { image ->
            for (y in 0 until height) {
                image.pixels.copyInto(pixels, y * width + left, y * image.width, (y + 1) * image.width)
            }
            left += image.width
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun vertical(images: List<ArgbPixelImage>): ArgbPixelImage {
        require(images.isNotEmpty() && images.map { it.width }.distinct().size == 1)
        val width = images.first().width
        val height = images.sumOf { it.height }
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        var top = 0
        images.forEach { image ->
            image.pixels.copyInto(pixels, top * width)
            top += image.height
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun rankingCsv(
        values: List<SkyMaskWindowMetrics>,
        metricName: String,
        metric: (SkyMaskWindowMetrics) -> Double
    ): String = buildString {
        appendLine("rank,window_id,center_x,center_y,$metricName")
        values.forEachIndexed { index, value ->
            appendLine(listOf(
                index + 1,
                csv(value.windowId),
                value.centerX,
                value.centerY,
                number(metric(value))
            ).joinToString(","))
        }
    }

    private fun luminance(color: Int): Double =
        (color ushr 16 and 0xFF) * 0.2126 +
            (color ushr 8 and 0xFF) * 0.7152 +
            (color and 0xFF) * 0.0722

    private fun sha256Argb(image: ArgbPixelImage): String {
        return ReplayDiagnosticHashing.sha256Argb(image)
    }

    private fun sha256File(path: Path): String = ReplayDiagnosticHashing.sha256File(path)

    private fun sha256(bytes: ByteArray): String = ReplayDiagnosticHashing.sha256(bytes)

    private fun number(value: Double): String {
        require(value.isFinite())
        return String.format(Locale.US, "%.9f", value)
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val DIFF_SCALE = 6
}
