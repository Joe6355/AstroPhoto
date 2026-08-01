package com.example.astrophoto

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.invariantSeparatorsPathString

internal object AdaptiveAsinhAblationReportWriter {
    fun write(bundle: AdaptiveAsinhAblationBundle, outputRoot: Path): AdaptiveAsinhAblationWriteResult {
        require(outputRoot.fileName.toString().isNotBlank())
        if (Files.exists(outputRoot)) outputRoot.toFile().deleteRecursively()
        Files.createDirectories(outputRoot)
        writeText(outputRoot.resolve("baseline-hashes.json"), baselineHashes(bundle))
        writeText(outputRoot.resolve("clean-stack-metrics.json"), cleanStackMetrics(bundle))
        writeText(outputRoot.resolve("current-formula.md"), currentFormula(bundle))
        writeText(outputRoot.resolve("ablation-contract.json"), contract(bundle))
        writeText(outputRoot.resolve("ablation-summary.csv"), summaryCsv(bundle))
        writeText(outputRoot.resolve("ablation-stage-metrics.csv"), stageCsv(bundle))
        writeText(outputRoot.resolve("strict-star-ablation.csv"), strictStarCsv(bundle))
        writeText(outputRoot.resolve("boundary-ablation.csv"), boundaryCsv(bundle))
        writeText(outputRoot.resolve("quality-policy-results.csv"), qualityCsv(bundle))
        writeText(outputRoot.resolve("root-cause.json"), rootCause(bundle))
        writeVariants(bundle, outputRoot)
        writeText(outputRoot.resolve("report-summary.md"), reportSummary(bundle))
        writeText(outputRoot.resolve("index.html"), html(bundle))
        return writeDeterminismManifest(outputRoot)
    }

    private fun writeVariants(bundle: AdaptiveAsinhAblationBundle, root: Path) {
        val current = bundle.variants.single { it.id == AdaptiveAsinhAblationVariantId.CURRENT }
        bundle.variants.forEach { variant ->
            val directory = root.resolve(variant.id.stableId)
            Files.createDirectories(directory)
            variant.stages.forEach { stage ->
                ReplayDiagnosticImageIo.writePng(directory.resolve("${stage.id}.png"), stage.image)
                Files.write(
                    directory.resolve("${stage.id}.argb32be"),
                    ReplayDiagnosticHashing.argbBytes(stage.image)
                )
            }
            Files.write(
                directory.resolve("composition-alpha.f32le"),
                ReplayDiagnosticHashing.alphaFloat32LittleEndianBytes(variant.compositionAlpha)
            )
            ReplayDiagnosticImageIo.writeDifference(
                directory.resolve("01-adaptive-stretch-minus-current.png"),
                current.stages.single { it.id == "01-adaptive-stretch" }.image,
                variant.stages.single { it.id == "01-adaptive-stretch" }.image,
                DIFF_SCALE
            )
            ReplayDiagnosticImageIo.writeDifference(
                directory.resolve("06-composed-minus-current.png"),
                current.composed,
                variant.composed,
                DIFF_SCALE
            )
            writeText(directory.resolve("variant.json"), variantJson(variant))
        }
    }

    private fun baselineHashes(bundle: AdaptiveAsinhAblationBundle): String = with(bundle.baselineHashes) {
        buildString {
            appendLine("{")
            appendLine("  \"cleanInputArgbSha256\": ${json(cleanInputArgbSha256)},")
            appendLine("  \"backgroundNeutralizedArgbSha256\": ${json(backgroundNeutralizedArgbSha256)},")
            appendLine("  \"currentAdaptiveStretchArgbSha256\": ${json(currentAdaptiveStretchArgbSha256)},")
            appendLine("  \"currentComposedArgbSha256\": ${json(currentComposedArgbSha256)},")
            appendLine("  \"currentSelectedFinalArgbSha256\": ${json(currentSelectedFinalArgbSha256)},")
            appendLine("  \"initialMaskSha256\": ${json(initialMaskSha256)},")
            appendLine("  \"refinedMaskSha256\": ${json(refinedMaskSha256)},")
            appendLine("  \"effectiveAlphaFloat32LeSha256\": ${json(effectiveAlphaFloat32LeSha256)}")
            appendLine("}")
        }
    }

    private fun cleanStackMetrics(bundle: AdaptiveAsinhAblationBundle): String =
        with(bundle.cleanStackMetrics) {
            buildString {
                appendLine("{")
                appendLine("  \"measurementImage\": \"cleanComposed\",")
                appendLine("  \"skyMad\": ${number(skyMad)},")
                appendLine("  \"bandingProxy\": ${number(bandingProxy)},")
                appendLine("  \"boundaryEdgeExcess\": ${number(boundaryEdgeExcess)}")
                appendLine("}")
            }
        }

    private fun currentFormula(bundle: AdaptiveAsinhAblationBundle): String {
        val p = bundle.parameters
        val d = bundle.baseline.currentStretchDiagnostics
        val f = bundle.blendFormula
        return buildString {
            appendLine("# CURRENT AdaptiveAsinhStretch formula")
            appendLine()
            appendLine("> TEST-ONLY ABLATION — PRODUCTION PROCESSING UNCHANGED")
            appendLine()
            appendLine("Input is opaque 8-bit sRGB converted per channel with `SrgbTransfer.srgbToLinear`.")
            appendLine()
            appendLine("```text")
            appendLine("Y = 0.2126*Rlinear + 0.7152*Glinear + 0.0722*Blinear")
            appendLine("black = clamp(min(lowPercentile, estimatedBlackPoint), 0, 1-minimumSeparation)")
            appendLine("white = min(1, max(estimatedSafeWhitePoint, black+minimumSeparation))")
            appendLine("confidenceScale = clamp(0.18+0.82*statisticsConfidence, 0, 1)")
            appendLine("normalized = clamp((Y-black)/(white-black), 0, 1)")
            appendLine("mapped = asinh(asinhStrength*normalized) / asinh(asinhStrength)")
            appendLine("targetBlend = clamp((targetLinearMedian-statisticsMedian)/(fullyMappedMedian-statisticsMedian), 0, 1) when the denominator is positive; otherwise 0")
            appendLine("appliedBlend = clamp(max(stretchBlend*confidenceScale, targetBlend*confidenceScale), 0, 1)")
            appendLine("highlightWeight = 1-highlightProtection*smoothStep(0.52, 1, normalized)")
            appendLine("CURRENT localBlend = appliedBlend*sqrt(effectiveAlpha)*highlightWeight")
            appendLine("targetY = clamp(Y+(mapped-Y)*localBlend, 0, 0.995)")
            appendLine("RGB' = gamut-limited RGB*(targetY/Y), then linear-to-sRGB 8-bit packing")
            appendLine("stretch safety may linearly blend RGB' back toward the neutralized input")
            appendLine("composer RGB = processedRGB*effectiveAlpha + referenceRGB*(1-effectiveAlpha) in linear light")
            appendLine("```")
            appendLine()
            appendLine("The first alpha use is inside `AdaptiveAsinhStretch` as `sqrt(effectiveAlpha)`. " +
                "The second is the unchanged `SkyForegroundComposer` linear-light blend.")
            appendLine()
            appendLine("Fixture parameters: `stretchBlend=${p.stretchBlend}`, `asinhStrength=${p.asinhStrength}`, " +
                "`highlightProtection=${p.highlightProtection}`, `maximumSkyMedianFactor=${p.maximumSkyMedianFactor}`, " +
                "`minimumBlackWhiteSeparation=${p.minimumBlackWhiteSeparation}`, " +
                "`targetDisplaySkyMedian=${p.targetDisplaySkyMedian}`.")
            appendLine("Measured CURRENT diagnostics: `blackPoint=${d.blackPoint}`, `whitePoint=${d.whitePoint}`, " +
                "`appliedBlend=${d.appliedBlend}`, `combinedMedianSafetyScale=${d.medianSafetyScale}`.")
            appendLine()
            appendLine("Exact target-median branch values:")
            appendLine()
            appendLine("```text")
            appendLine("statisticsConfidence=${f.statisticsConfidence}")
            appendLine("confidenceScale=${f.confidenceScale}")
            appendLine("statisticsMedian=${f.statisticsMedian}")
            appendLine("targetLinearMedian=${f.targetLinearMedian}")
            appendLine("medianNormalized=${f.medianNormalized}")
            appendLine("fullyMappedMedian=${f.fullyMappedMedian}")
            appendLine("rawTargetBlend=${f.rawTargetBlend}")
            appendLine("targetBlend=${f.targetBlend}")
            appendLine("configuredContribution=${f.configuredBlend}*${f.confidenceScale}=${f.configuredContribution}")
            appendLine("targetMedianContribution=${f.targetBlend}*${f.confidenceScale}=${f.targetMedianContribution}")
            appendLine("currentAppliedBlend=max(${f.configuredContribution}, ${f.targetMedianContribution})=${f.currentAppliedBlend}")
            appendLine("```")
        }
    }

    private fun contract(bundle: AdaptiveAsinhAblationBundle): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": \"astrophoto.adaptive-asinh-ablation/2\",")
        appendLine("  \"mode\": \"test-only-replay\",")
        appendLine("  \"productionProcessingChanged\": false,")
        appendLine("  \"fixture\": \"urban-window-30\",")
        appendLine("  \"variants\": [")
        bundle.contracts.forEachIndexed { index, value ->
            appendLine("    {")
            appendLine("      \"id\": ${json(value.variant.stableId)},")
            appendLine("      \"available\": ${value.available},")
            appendLine("      \"unavailableReason\": ${value.unavailableReason?.let(::json) ?: "null"},")
            appendLine("      \"changedCondition\": ${json(value.changedCondition)},")
            appendLine("      \"changedVariables\": ${value.variant.changedVariables},")
            appendLine("      \"rootCauseEligible\": ${value.variant.rootCauseEligible},")
            appendLine("      \"appliedBlendPolicy\": ${json(value.variant.appliedBlendDescription)},")
            appendLine("      \"operationStrength\": ${json(value.variant.operationDescription)},")
            appendLine("      \"compositionAlpha\": ${json(value.variant.compositionDescription)},")
            appendLine("      \"sharedInputArgbSha256\": ${json(value.sharedInputArgbSha256)},")
            appendLine("      \"initialMaskSha256\": ${json(value.initialMaskSha256)},")
            appendLine("      \"refinedMaskSha256\": ${json(value.refinedMaskSha256)},")
            appendLine("      \"effectiveAlphaFloat32LeSha256\": ${json(value.effectiveAlphaFloat32LeSha256)},")
            appendLine("      \"acceptedOriginalIndices\": [${value.acceptedOriginalIndices.joinToString(",")}],")
            appendLine("      \"rejectedOriginalIndices\": [${value.rejectedOriginalIndices.joinToString(",")}],")
            appendLine("      \"alignmentTransformFingerprint\": ${json(value.alignmentTransformFingerprint)},")
            appendLine("      \"parameterFingerprint\": ${json(value.parameterFingerprint)},")
            appendLine("      \"qualityPolicy\": ${json(value.qualityPolicy)}")
            append("    }")
            appendLine(if (index == bundle.contracts.lastIndex) "" else ",")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun summaryCsv(bundle: AdaptiveAsinhAblationBundle): String = buildString {
        appendLine("variant,blend_policy,applied_blend,sky_mad,banding_proxy,boundary_edge_excess,halo,leakage,foreground_change,luminance_mean,luminance_median,clipped_low_pixels,clipped_high_pixels,chroma_residual,sensor_defect_residual,strict_star_gate,processed_accepted,acceptable_production_candidate")
        bundle.globalMetrics.forEach { value ->
            val variant = bundle.variants.single { it.id == value.variant }
            appendLine(listOf(
                csv(value.variant.stableId), csv(value.variant.appliedBlendDescription),
                number(variant.stretchDiagnostics.appliedBlend.toDouble()),
                number(value.skyMad), number(value.bandingProxy),
                number(value.boundaryEdgeExcess), number(value.meanHaloScore),
                number(value.meanLeakageScore), number(value.foregroundMeanChange),
                number(value.luminanceMean), number(value.luminanceMedian),
                value.clippedLowPixels, value.clippedHighPixels, number(value.chromaResidual),
                number(value.sensorDefectResidual), value.strictStarGatePassed,
                value.processedAccepted, value.acceptableProductionCandidate
            ).joinToString(","))
        }
    }

    private fun stageCsv(bundle: AdaptiveAsinhAblationBundle): String = buildString {
        appendLine("variant,stage,sky_mad,banding_proxy,boundary_edge_excess,mean_absolute_change_from_background_neutralized")
        bundle.stageMetrics.forEach { value ->
            appendLine(listOf(
                csv(value.variant.stableId), csv(value.metric.stage), number(value.metric.skyMad),
                number(value.metric.bandingProxy), number(value.metric.boundaryEdgeExcess),
                number(value.metric.meanAbsoluteChangeFromClean)
            ).joinToString(","))
        }
    }

    private fun strictStarCsv(bundle: AdaptiveAsinhAblationBundle): String = buildString {
        appendLine("variant,star_id,aperture_flux_retention,peak_retention,centroid_shift,width_ratio,ellipticity_change,local_contrast,local_contrast_retention,chroma_residual,center_alpha,distance_to_boundary,established_gate_passed")
        bundle.strictStarMetrics.forEach { value ->
            appendLine(listOf(
                csv(value.variant.stableId), csv(value.starId), number(value.apertureFluxRetention),
                number(value.peakRetention), number(value.centroidShift), number(value.widthRatio),
                number(value.ellipticityChange), number(value.localContrast),
                number(value.localContrastRetention), number(value.chromaResidual),
                number(value.centerAlpha), number(value.distanceToBoundary), value.establishedGatePassed
            ).joinToString(","))
        }
    }

    private fun boundaryCsv(bundle: AdaptiveAsinhAblationBundle): String = buildString {
        appendLine("variant,window_id,center_x,center_y,distance_to_boundary,halo,bright_rim,dark_rim,first_derivative_jump,second_derivative_spike,edge_aligned_residual,transition_band_variance,leakage")
        bundle.boundaryMetrics.forEach { value ->
            val metric = value.window
            appendLine(listOf(
                csv(value.variant.stableId), csv(metric.windowId), metric.centerX, metric.centerY,
                number(metric.distanceToBoundary), number(metric.haloScore), number(metric.brightRim),
                number(metric.darkRim), number(metric.firstDerivativeExcess),
                number(metric.secondDerivativeSpike), number(metric.edgeAlignedResidual),
                number(value.transitionBandVariance), number(metric.leakageScore)
            ).joinToString(","))
        }
    }

    private fun qualityCsv(bundle: AdaptiveAsinhAblationBundle): String = buildString {
        appendLine("variant,applied_blend,processed_accepted,rejection_reasons,selected_candidate,production_candidate_eligible")
        bundle.globalMetrics.forEach { value ->
            val variant = bundle.variants.single { it.id == value.variant }
            appendLine(listOf(
                csv(value.variant.stableId), number(variant.stretchDiagnostics.appliedBlend.toDouble()),
                value.processedAccepted,
                csv(value.rejectionReasons.joinToString("|")), csv(value.selectedCandidate),
                value.acceptableProductionCandidate
            ).joinToString(","))
        }
    }

    private fun rootCause(bundle: AdaptiveAsinhAblationBundle): String = buildString {
        appendLine("{")
        appendLine("  \"decision\": ${json(bundle.rootCause.name)},")
        appendLine("  \"evidence\": ${json(bundle.rootCauseEvidence)},")
        appendLine("  \"productionCandidate\": ${bundle.productionCandidate?.let(::json) ?: "null"},")
        appendLine("  \"productionFixImplemented\": false")
        appendLine("}")
    }

    private fun variantJson(value: AdaptiveAsinhAblationVariant): String = buildString {
        appendLine("{")
        appendLine("  \"id\": ${json(value.id.stableId)},")
        appendLine("  \"available\": ${value.available},")
        appendLine("  \"operationMode\": ${json(value.operationMode.name)},")
        appendLine("  \"blendMode\": ${json(value.blendMode.name)},")
        appendLine("  \"appliedBlendPolicy\": ${json(value.id.appliedBlendDescription)},")
        appendLine("  \"operationStrength\": ${json(value.id.operationDescription)},")
        appendLine("  \"compositionAlpha\": ${json(value.id.compositionDescription)},")
        appendLine("  \"processedAccepted\": ${value.selection.processedAccepted},")
        appendLine("  \"rejectionReasons\": [${value.selection.processedRejectionReasons.joinToString(",") { json(it) }}],")
        appendLine("  \"selectedCandidate\": ${json(value.selection.type.name)},")
        appendLine("  \"stretchDiagnostics\": {")
        appendLine("    \"blackPoint\": ${number(value.stretchDiagnostics.blackPoint.toDouble())},")
        appendLine("    \"whitePoint\": ${number(value.stretchDiagnostics.whitePoint.toDouble())},")
        appendLine("    \"asinhStrength\": ${number(value.stretchDiagnostics.asinhStrength.toDouble())},")
        appendLine("    \"appliedBlend\": ${number(value.stretchDiagnostics.appliedBlend.toDouble())},")
        appendLine("    \"medianSafetyScale\": ${number(value.stretchDiagnostics.medianSafetyScale.toDouble())}")
        appendLine("  }")
        appendLine("}")
    }

    private fun reportSummary(bundle: AdaptiveAsinhAblationBundle): String = buildString {
        appendLine("# AdaptiveAsinhStretch target-median test-only ablation")
        appendLine()
        appendLine("> TEST-ONLY ABLATION — PRODUCTION PROCESSING UNCHANGED")
        appendLine()
        appendLine("- fixture: `urban-window-30`, `720x960`")
        appendLine("- accepted original frames: `${bundle.baseline.acceptedOriginalFrameIndices.joinToString(",")}`")
        appendLine("- rejected original frames: `${bundle.baseline.rejectedOriginalFrameIndices.joinToString(",")}`")
        appendLine("- CURRENT selected candidate: `${bundle.baseline.selectedCandidateType}`")
        appendLine("- CURRENT rejection reasons: `${bundle.baseline.processedCandidateRejectionReasons.joinToString("|")}`")
        appendLine("- configured blend: `${bundle.blendFormula.configuredBlend}`")
        appendLine("- configured contribution: `${bundle.blendFormula.configuredContribution}`")
        appendLine("- target blend: `${bundle.blendFormula.targetBlend}`")
        appendLine("- target-median contribution: `${bundle.blendFormula.targetMedianContribution}`")
        appendLine("- CURRENT applied blend: `${bundle.blendFormula.currentAppliedBlend}`")
        appendLine("- CLEAN_STACK sky MAD / banding: `${number(bundle.cleanStackMetrics.skyMad)}` / " +
            "`${number(bundle.cleanStackMetrics.bandingProxy)}`")
        appendLine("- file-backed/replay maximum difference: `${bundle.baseline.activeFileBackedMaximumChannelDifference}`; differing pixels: `${bundle.baseline.activeFileBackedDifferentPixelCount}`")
        appendLine("- configurable sqrt(alpha) replay maximum difference: `${bundle.configurableCurrentMaximumChannelDifference}`; differing pixels: `${bundle.configurableCurrentDifferentPixelCount}`")
        appendLine("- strict confirmed stars: `${bundle.baseline.fixture.strictReferenceStarLabels.size}`")
        appendLine("- strict confirmed sensor defects: `${bundle.baseline.fixture.strictSensorDefects.size}`")
        appendLine("- ground-truth needs_review/rejected: `${bundle.baseline.fixture.groundTruthSummary.excludedNeedsReviewRows}` / `${bundle.baseline.fixture.groundTruthSummary.excludedRejectedRows}`")
        appendLine("- root-cause decision: `${bundle.rootCause}`")
        appendLine("- production candidate: `${bundle.productionCandidate ?: "NONE — evidence insufficient"}`")
        appendLine()
        appendLine("| Variant | Applied blend | Sky MAD | Banding | Boundary | Halo | Leakage | Quality |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---|")
        appendLine("| clean-stack | - | ${number(bundle.cleanStackMetrics.skyMad)} | " +
            "${number(bundle.cleanStackMetrics.bandingProxy)} | " +
            "${number(bundle.cleanStackMetrics.boundaryEdgeExcess)} | - | - | baseline |")
        bundle.globalMetrics.forEach { value ->
            val variant = bundle.variants.single { it.id == value.variant }
            appendLine("| ${value.variant.stableId} | ${number(variant.stretchDiagnostics.appliedBlend.toDouble())} | " +
                "${number(value.skyMad)} | ${number(value.bandingProxy)} | " +
                "${number(value.boundaryEdgeExcess)} | ${number(value.meanHaloScore)} | " +
                "${number(value.meanLeakageScore)} | ${if (value.processedAccepted) "accepted" else value.rejectionReasons.joinToString("|")} |")
        }
        appendLine()
        appendLine("Worst strict-star metrics across all variants: minimum flux retention " +
            "`${number(bundle.strictStarMetrics.minOf { it.apertureFluxRetention })}`, maximum centroid shift " +
            "`${number(bundle.strictStarMetrics.maxOf { it.centroidShift })}` px, width ratio " +
            "`${number(bundle.strictStarMetrics.minOf { it.widthRatio })}`-`${number(bundle.strictStarMetrics.maxOf { it.widthRatio })}`, " +
            "maximum ellipticity change `${number(bundle.strictStarMetrics.maxOf { it.ellipticityChange })}`.")
        appendLine()
        appendLine("Root-cause evidence: ${bundle.rootCauseEvidence}")
        appendLine()
        appendLine("No production fix is included.")
    }

    private fun html(bundle: AdaptiveAsinhAblationBundle): String {
        val cleanRow = "<tr><td>clean-stack</td><td>-</td><td>${number(bundle.cleanStackMetrics.skyMad)}</td>" +
            "<td>${number(bundle.cleanStackMetrics.bandingProxy)}</td>" +
            "<td>${number(bundle.cleanStackMetrics.boundaryEdgeExcess)}</td><td>-</td><td>-</td><td>baseline</td></tr>"
        val metricRows = cleanRow + "\n" + bundle.globalMetrics.joinToString("\n") { value ->
            val variant = bundle.variants.single { it.id == value.variant }
            "<tr><td>${htmlEscape(value.variant.stableId)}</td>" +
                "<td>${number(variant.stretchDiagnostics.appliedBlend.toDouble())}</td><td>${number(value.skyMad)}</td>" +
                "<td>${number(value.bandingProxy)}</td><td>${number(value.boundaryEdgeExcess)}</td>" +
                "<td>${number(value.meanHaloScore)}</td><td>${number(value.meanLeakageScore)}</td>" +
                "<td>${htmlEscape(if (value.processedAccepted) "accepted" else value.rejectionReasons.joinToString("|"))}</td></tr>"
        }
        val starRows = bundle.strictStarMetrics.joinToString("\n") { value ->
            "<tr><td>${htmlEscape(value.variant.stableId)}</td><td>${htmlEscape(value.starId)}</td>" +
                "<td>${number(value.apertureFluxRetention)}</td><td>${number(value.peakRetention)}</td>" +
                "<td>${number(value.centroidShift)}</td><td>${number(value.widthRatio)}</td>" +
                "<td>${number(value.ellipticityChange)}</td><td>${value.establishedGatePassed}</td></tr>"
        }
        val ranked = bundle.globalMetrics.sortedWith(
            compareByDescending<AdaptiveAsinhGlobalMetrics> { it.acceptableProductionCandidate }
                .thenByDescending { it.processedAccepted }
                .thenByDescending { it.strictStarGatePassed }
                .thenBy { it.bandingProxy }
                .thenBy { it.boundaryEdgeExcess }
        ).mapIndexed { index, value ->
            "<li>${index + 1}. <code>${htmlEscape(value.variant.stableId)}</code>: " +
                "eligible=${value.acceptableProductionCandidate}, accepted=${value.processedAccepted}, " +
                "strictGate=${value.strictStarGatePassed}, banding=${number(value.bandingProxy)}, " +
                "boundary=${number(value.boundaryEdgeExcess)}</li>"
        }.joinToString("\n")
        val variantSections = bundle.variants.joinToString("\n") { value ->
            "<section><h3>${htmlEscape(value.id.stableId)}</h3>" +
                "<p>applied blend: <code>${htmlEscape(value.id.appliedBlendDescription)}</code>; operation: " +
                "<code>${htmlEscape(value.id.operationDescription)}</code>; composition: " +
                "<code>${htmlEscape(value.id.compositionDescription)}</code></p>" +
                "<div class=grid><figure><img src='${value.id.stableId}/01-adaptive-stretch.png'><figcaption>adaptive stretch</figcaption></figure>" +
                "<figure><img src='${value.id.stableId}/06-composed.png'><figcaption>composed</figcaption></figure>" +
                "<figure><img src='${value.id.stableId}/06-composed-minus-current.png'><figcaption>difference from CURRENT, x$DIFF_SCALE</figcaption></figure></div></section>"
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>AdaptiveAsinhStretch target-median test-only ablation</title>
<style>body{font:14px system-ui;margin:24px;background:#10151d;color:#e6edf3}code{color:#9cdcfe}table{border-collapse:collapse;width:100%;margin:12px 0}th,td{border:1px solid #43505f;padding:6px;text-align:left}.warning{padding:12px;background:#5c2c00;font-weight:700}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}img{width:100%;image-rendering:auto}figure{margin:0}figcaption{color:#aab6c3}</style></head>
<body><h1>AdaptiveAsinhStretch target-median test-only ablation</h1>
<p class="warning">TEST-ONLY ABLATION — PRODUCTION PROCESSING UNCHANGED</p>
<h2>Current alpha usage</h2><pre>localBlend = appliedBlend * sqrt(effectiveAlpha) * highlightWeight
composer = processed * effectiveAlpha + reference * (1-effectiveAlpha)</pre>
<p>CURRENT configurable replay difference: max channel ${bundle.configurableCurrentMaximumChannelDifference}; ${bundle.configurableCurrentDifferentPixelCount} pixels.</p>
<h2>Global and boundary result</h2><table><thead><tr><th>Variant</th><th>Applied blend</th><th>Sky MAD</th><th>Banding</th><th>Boundary</th><th>Halo</th><th>Leakage</th><th>Quality</th></tr></thead><tbody>$metricRows</tbody></table>
<h2>Ranked comparison</h2><p>Order: production eligibility, unchanged quality acceptance, established strict-star gate, banding, then boundary excess.</p><ol>$ranked</ol>
<h2>Root-cause conclusion</h2><p><b>${bundle.rootCause}</b>: ${htmlEscape(bundle.rootCauseEvidence)}</p><p>Production candidate: <code>${htmlEscape(bundle.productionCandidate ?: "NONE — evidence insufficient")}</code></p>
<h2>Strict stars (all 6)</h2><table><thead><tr><th>Variant</th><th>Star</th><th>Flux retention</th><th>Peak retention</th><th>Centroid shift</th><th>Width ratio</th><th>Ellipticity change</th><th>Existing gate</th></tr></thead><tbody>$starRows</tbody></table>
<h2>Variant stage trace</h2>$variantSections
<p>No production formula, mask, registration, quality policy, ground truth, or runtime output was changed.</p></body></html>
"""
    }

    private fun writeDeterminismManifest(root: Path): AdaptiveAsinhAblationWriteResult {
        val excluded = setOf("sha256-manifest.txt", "determinism.json")
        val dataFiles = regularFiles(root).filter {
            root.relativize(it).invariantSeparatorsPathString !in excluded
        }
        val manifest = buildString {
            dataFiles.forEach { path ->
                append(ReplayDiagnosticHashing.sha256File(path)).append("  ")
                    .append(root.relativize(path).invariantSeparatorsPathString).append('\n')
            }
        }
        val treeHash = ReplayDiagnosticHashing.sha256(manifest.toByteArray(StandardCharsets.UTF_8))
        val manifestPath = root.resolve("sha256-manifest.txt")
        writeText(manifestPath, manifest)
        val fileCount = dataFiles.size + 2
        val result = AdaptiveAsinhAblationWriteResult(
            fileCount = fileCount,
            treeSha256 = treeHash,
            manifestSha256 = ReplayDiagnosticHashing.sha256File(manifestPath),
            summarySha256 = ReplayDiagnosticHashing.sha256File(root.resolve("ablation-summary.csv")),
            strictStarSha256 = ReplayDiagnosticHashing.sha256File(root.resolve("strict-star-ablation.csv")),
            htmlSha256 = ReplayDiagnosticHashing.sha256File(root.resolve("index.html"))
        )
        writeText(root.resolve("determinism.json"), buildString {
            appendLine("{")
            appendLine("  \"definition\": \"tree SHA-256 hashes the sorted path/file-hash manifest excluding sha256-manifest.txt and determinism.json\",")
            appendLine("  \"verification\": \"two independent report trees are compared byte-for-byte by AdaptiveAsinhAblationTest\",")
            appendLine("  \"fileCount\": ${result.fileCount},")
            appendLine("  \"treeSha256\": ${json(result.treeSha256)},")
            appendLine("  \"manifestSha256\": ${json(result.manifestSha256)},")
            appendLine("  \"ablationSummarySha256\": ${json(result.summarySha256)},")
            appendLine("  \"strictStarAblationSha256\": ${json(result.strictStarSha256)},")
            appendLine("  \"htmlSha256\": ${json(result.htmlSha256)}")
            appendLine("}")
        })
        require(regularFiles(root).size == result.fileCount)
        return result
    }

    private fun regularFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile).sorted().toList()
    }

    private fun writeText(path: Path, value: String) {
        Files.writeString(path, value, StandardCharsets.UTF_8)
    }

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
