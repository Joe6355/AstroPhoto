package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.invariantSeparatorsPathString

internal object LocalResidualStretchAblationReportWriter {
    fun write(
        bundle: LocalResidualStretchAblationBundle,
        outputRoot: Path
    ): LocalResidualStretchAblationWriteResult {
        require(outputRoot.fileName.toString().isNotBlank())
        if (Files.exists(outputRoot)) outputRoot.toFile().deleteRecursively()
        Files.createDirectories(outputRoot)
        writeText(outputRoot.resolve("algorithm-and-parameters.md"), algorithmAndParameters(bundle))
        writeText(outputRoot.resolve("baseline-hashes.json"), baselineHashes(bundle))
        writeText(outputRoot.resolve("ablation-summary.csv"), summaryCsv(bundle))
        writeText(outputRoot.resolve("strict-star-metrics.csv"), strictStarCsv(bundle))
        writeText(outputRoot.resolve("sensor-defect-metrics.csv"), sensorDefectCsv(bundle))
        writeText(outputRoot.resolve("boundary-metrics.csv"), boundaryCsv(bundle))
        writeText(outputRoot.resolve("detection-metrics.csv"), detectionCsv(bundle))
        writeText(outputRoot.resolve("quality-policy-results.csv"), qualityCsv(bundle))
        writeText(outputRoot.resolve("decision.json"), decisionJson(bundle))
        writeVariants(bundle, outputRoot)
        writeText(outputRoot.resolve("report-summary.md"), reportSummary(bundle))
        writeText(outputRoot.resolve("index.html"), html(bundle))
        return writeDeterminismManifest(outputRoot)
    }

    private fun writeVariants(bundle: LocalResidualStretchAblationBundle, root: Path) {
        val clean = bundle.variants.single { it.id == LocalResidualStretchVariantId.CLEAN_STACK }
        bundle.variants.forEach { variant ->
            val directory = root.resolve(variant.id.stableId)
            Files.createDirectories(directory)
            ReplayDiagnosticImageIo.writePng(directory.resolve("full-resolution.png"), variant.output)
            Files.write(
                directory.resolve("full-resolution.argb32be"),
                ReplayDiagnosticHashing.argbBytes(variant.output)
            )
            ReplayDiagnosticImageIo.writeDifference(
                directory.resolve("minus-clean-stack-x8.png"),
                clean.output,
                variant.output,
                DIFF_SCALE
            )
            ReplayDiagnosticImageIo.writePng(directory.resolve("stretch-operation.png"), variant.stretchOutput)
            Files.write(
                directory.resolve("stretch-operation.argb32be"),
                ReplayDiagnosticHashing.argbBytes(variant.stretchOutput)
            )
            writeText(directory.resolve("variant.json"), variantJson(variant))
        }
    }

    private fun algorithmAndParameters(bundle: LocalResidualStretchAblationBundle): String {
        val prepared = bundle.preparedInput
        val production = ExistingPresetParameterMapper.parametersFor(
            AstroProcessingProfile.URBAN_SKY_STRONG,
            bundle.baseline.acceptedOriginalFrameIndices.size
        )
        val maximumRequestedGain =
            (production.maximumStarDetailGain - 1f) * production.starContrastStrength
        return buildString {
            appendLine("# Test-only local-background residual stretch")
            appendLine()
            appendLine("> TEST-ONLY ABLATION - PRODUCTION PROCESSING UNCHANGED")
            appendLine()
            appendLine("```text")
            appendLine("background = mean(full-alpha square annulus around Y)")
            appendLine("residual = max(Y - background, 0)")
            appendLine("noiseThreshold = max(0.0015, 2.2 * skyLuminanceMAD)")
            appendLine("support = smoothstep(noiseThreshold, 2 * noiseThreshold, residual)")
            appendLine("brightProtection = 1 - smoothstep(brightStart, brightEnd, Y)")
            appendLine("enhancedResidual = residual * (1 + strength * brightProtection)")
            appendLine("outputY = clamp(Y + sqrt(effectiveAlpha) * support * (enhancedResidual - residual), 0, 0.995)")
            appendLine("RGBout = RGBlinear * outputY / Y, with the existing 0.995 channel ceiling")
            appendLine("```")
            appendLine()
            appendLine("- median strict-star PSF width: `${number(bundle.medianStrictStarPsfWidth.toDouble())}` px")
            appendLine("- annulus radii: inner `${prepared.innerRadius}` px, outer `${prepared.outerRadius}` px")
            appendLine("- required full-alpha annulus samples: `${prepared.requiredAnnulusSamples}`")
            appendLine("- noise threshold: `${number(prepared.noiseThreshold.toDouble())}` linear")
            appendLine("- upper threshold: `${number(prepared.upperThreshold.toDouble())}` linear")
            appendLine("- bright protection: `${number(prepared.brightProtectionStart.toDouble())}` to " +
                "`${number(prepared.brightProtectionEnd.toDouble())}` linear")
            appendLine("- shared background SHA-256: `${prepared.backgroundFloat32LeSha256}`")
            appendLine("- existing URBAN_SKY_STRONG maximum requested local detail gain: " +
                "`(1.45 - 1) * 0.80 = ${number(maximumRequestedGain.toDouble())}`")
            appendLine("- LOW/MEDIUM/STRONG use one-third, two-thirds, and all of that maximum: " +
                "`0.12 / 0.24 / 0.36`")
            appendLine()
            appendLine("The annulus rule reuses the existing LocalStarContrastEnhancer scale: " +
                "`inner=ceil(max(2, 1.4*PSF))`, `outer=min(inner+3, 9)`. The full-annulus requirement " +
                "makes the mean symmetric and disables the operation at image/mask edges or invalid coverage.")
        }
    }

    private fun baselineHashes(bundle: LocalResidualStretchAblationBundle): String {
        val baseline = bundle.baseline
        val neutralized = baseline.postProcessingStages.single {
            it.id == "02-background-neutralization"
        }.image
        return buildString {
            appendLine("{")
            appendLine("  \"cleanStackArgbSha256\": ${json(ReplayDiagnosticHashing.sha256Argb(baseline.cleanStack))},")
            appendLine("  \"cleanComposedArgbSha256\": ${json(ReplayDiagnosticHashing.sha256Argb(baseline.cleanComposed))},")
            appendLine("  \"currentComposedArgbSha256\": ${json(ReplayDiagnosticHashing.sha256Argb(baseline.composedCurrent))},")
            appendLine("  \"backgroundNeutralizedArgbSha256\": ${json(ReplayDiagnosticHashing.sha256Argb(neutralized))},")
            appendLine("  \"effectiveAlphaFloat32LeSha256\": ${json(ReplayDiagnosticHashing.sha256Alpha(baseline.effectiveAlpha))},")
            appendLine("  \"localBackgroundFloat32LeSha256\": ${json(bundle.preparedInput.backgroundFloat32LeSha256)}")
            appendLine("}")
        }
    }

    private fun summaryCsv(bundle: LocalResidualStretchAblationBundle): String = buildString {
        appendLine("variant,strength,sky_mad,banding,boundary,mean_halo,mean_leakage,foreground_mean_change,sensor_defect_residual,weak_star_median_contrast_gain,max_star_width_ratio,chroma_residual,new_detections,false_weak_star_detections,background_preserved,strict_stars_passed,processed_accepted,production_candidate")
        bundle.globalMetrics.forEach { value ->
            appendLine(listOf(
                csv(value.variant.stableId),
                value.variant.strength?.let { number(it.toDouble()) } ?: "",
                number(value.skyMad), number(value.bandingProxy), number(value.boundaryEdgeExcess),
                number(value.meanHaloScore), number(value.meanLeakageScore),
                number(value.foregroundMeanChange), number(value.sensorDefectResidual),
                number(value.weakStarMedianContrastGain), number(value.maximumStrictStarWidthRatio),
                number(value.chromaResidual), value.newDetectionsVersusClean,
                value.falseWeakStarDetections,
                value.backgroundPreservedByOperation, value.strictStarGatePassed,
                value.processedAccepted, value.acceptableProductionCandidate
            ).joinToString(","))
        }
    }

    private fun strictStarCsv(bundle: LocalResidualStretchAblationBundle): String = buildString {
        appendLine("variant,star_id,weak_baseline_star,baseline_local_contrast,aperture_flux_retention,peak_retention,centroid_shift,width_ratio,ellipticity_change,local_contrast,local_contrast_retention,chroma_residual,established_gate_passed")
        bundle.strictStarMetrics.forEach { value ->
            appendLine(listOf(
                csv(value.variant.stableId), csv(value.starId), value.weakBaselineStar,
                number(value.baselineLocalContrast), number(value.apertureFluxRetention),
                number(value.peakRetention), number(value.centroidShift), number(value.widthRatio),
                number(value.ellipticityChange), number(value.localContrast),
                number(value.localContrastRetention), number(value.chromaResidual),
                value.establishedGatePassed
            ).joinToString(","))
        }
    }

    private fun sensorDefectCsv(bundle: LocalResidualStretchAblationBundle): String = buildString {
        appendLine("variant,defect_id,mean_residual,maximum_residual")
        bundle.sensorDefectMetrics.forEach { value ->
            appendLine(listOf(
                csv(value.variant.stableId), csv(value.defectId),
                number(value.meanResidual), number(value.maximumResidual)
            ).joinToString(","))
        }
    }

    private fun boundaryCsv(bundle: LocalResidualStretchAblationBundle): String = buildString {
        appendLine("variant,window_id,distance_to_boundary,halo,leakage,first_derivative_excess,second_derivative_spike,edge_aligned_residual")
        bundle.boundaryMetrics.forEach { value ->
            appendLine(listOf(
                csv(value.variant.stableId), csv(value.window.windowId),
                number(value.window.distanceToBoundary), number(value.window.haloScore),
                number(value.window.leakageScore), number(value.window.firstDerivativeExcess),
                number(value.window.secondDerivativeSpike), number(value.window.edgeAlignedResidual)
            ).joinToString(","))
        }
    }

    private fun detectionCsv(bundle: LocalResidualStretchAblationBundle): String = buildString {
        appendLine("variant,detected_stars,new_detections_versus_clean,false_weak_star_detections,new_detection_details,detector_background,detector_noise")
        bundle.detectionMetrics.forEach { value ->
            appendLine(listOf(
                csv(value.variant.stableId), value.detectedStars, value.newDetectionsVersusClean,
                value.falseWeakStarDetections, csv(value.newDetectionDetails.joinToString("|")),
                number(value.detectorBackground.toDouble()), number(value.detectorNoise.toDouble())
            ).joinToString(","))
        }
    }

    private fun qualityCsv(bundle: LocalResidualStretchAblationBundle): String = buildString {
        appendLine("variant,processed_accepted,rejection_reasons,selected_candidate,strict_star_gate,production_candidate")
        bundle.globalMetrics.forEach { value ->
            appendLine(listOf(
                csv(value.variant.stableId), value.processedAccepted,
                csv(value.rejectionReasons.joinToString("|")), csv(value.selectedCandidate),
                value.strictStarGatePassed, value.acceptableProductionCandidate
            ).joinToString(","))
        }
    }

    private fun decisionJson(bundle: LocalResidualStretchAblationBundle): String = buildString {
        appendLine("{")
        appendLine("  \"decision\": ${json(bundle.decision.name)},")
        appendLine("  \"productionCandidate\": ${bundle.productionCandidate?.stableId?.let(::json) ?: "null"},")
        appendLine("  \"evidence\": ${json(bundle.decisionEvidence)},")
        appendLine("  \"productionFixImplemented\": false")
        appendLine("}")
    }

    private fun variantJson(value: LocalResidualStretchVariant): String = buildString {
        appendLine("{")
        appendLine("  \"id\": ${json(value.id.stableId)},")
        appendLine("  \"strength\": ${value.id.strength?.let { number(it.toDouble()) } ?: "null"},")
        appendLine("  \"outputArgbSha256\": ${json(value.outputArgbSha256)},")
        appendLine("  \"stretchOutputArgbSha256\": ${json(value.stretchOutputArgbSha256)},")
        appendLine("  \"processedAccepted\": ${value.selection?.processedAccepted ?: true},")
        appendLine("  \"rejectionReasons\": [${value.selection?.processedRejectionReasons.orEmpty().joinToString(",") { json(it) }}],")
        appendLine("  \"selectedCandidate\": ${json(value.selection?.type?.name ?: "CLEAN_STACK")},")
        value.stretchDiagnostics?.let { diagnostic ->
            appendLine("  \"stretchDiagnostics\": {")
            appendLine("    \"blackPoint\": ${number(diagnostic.blackPoint.toDouble())},")
            appendLine("    \"whitePoint\": ${number(diagnostic.whitePoint.toDouble())},")
            appendLine("    \"asinhStrength\": ${number(diagnostic.asinhStrength.toDouble())},")
            appendLine("    \"highlightProtectionStrength\": ${number(diagnostic.highlightProtectionStrength.toDouble())},")
            appendLine("    \"appliedBlendOrResidualStrength\": ${number(diagnostic.appliedBlend.toDouble())},")
            appendLine("    \"medianSafetyScale\": ${number(diagnostic.medianSafetyScale.toDouble())}")
            appendLine("  },")
        } ?: appendLine("  \"stretchDiagnostics\": null,")
        value.localDiagnostics?.let { diagnostic ->
            appendLine("  \"localResidualDiagnostics\": {")
            appendLine("    \"validBackgroundPixels\": ${diagnostic.validBackgroundPixels},")
            appendLine("    \"negativeResidualPixels\": ${diagnostic.negativeResidualPixels},")
            appendLine("    \"belowNoisePixels\": ${diagnostic.belowNoisePixels},")
            appendLine("    \"supportedPixels\": ${diagnostic.supportedPixels},")
            appendLine("    \"changedPixels\": ${diagnostic.changedPixels},")
            appendLine("    \"negativeResidualChangedPixels\": ${diagnostic.negativeResidualChangedPixels},")
            appendLine("    \"backgroundChangedPixels\": ${diagnostic.backgroundChangedPixels},")
            appendLine("    \"meanPositiveLuminanceDelta\": ${number(diagnostic.meanPositiveLuminanceDelta)},")
            appendLine("    \"maximumPositiveLuminanceDelta\": ${number(diagnostic.maximumPositiveLuminanceDelta)},")
            appendLine("    \"meanLinearChromaticityShift\": ${number(diagnostic.meanLinearChromaticityShift)},")
            appendLine("    \"maximumLinearChromaticityShift\": ${number(diagnostic.maximumLinearChromaticityShift)}")
            appendLine("  },")
        }
        appendLine("  \"productionCodeChanged\": false")
        appendLine("}")
    }

    private fun reportSummary(bundle: LocalResidualStretchAblationBundle): String = buildString {
        appendLine("# Local-background residual stretch test-only ablation")
        appendLine()
        appendLine("> TEST-ONLY ABLATION - PRODUCTION PROCESSING UNCHANGED")
        appendLine()
        appendLine("- fixture: `urban-window-30`, `720x960`")
        appendLine("- exact production CURRENT replay difference: `0` maximum channel, `0` pixels")
        appendLine("- median strict-star PSF: `${number(bundle.medianStrictStarPsfWidth.toDouble())}` px")
        appendLine("- local background hash: `${bundle.preparedInput.backgroundFloat32LeSha256}`")
        appendLine("- decision: `${bundle.decision}`")
        appendLine("- production candidate: `${bundle.productionCandidate?.stableId ?: "NONE"}`")
        appendLine()
        appendLine("| Variant | Strength | Sky MAD | Banding | Boundary | Halo | Leakage | Defect | Weak gain | Width max | New / false detections | Quality |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
        bundle.globalMetrics.forEach { value ->
            appendLine("| ${value.variant.stableId} | ${value.variant.strength?.let { number(it.toDouble()) } ?: "-"} | " +
                "${number(value.skyMad)} | ${number(value.bandingProxy)} | ${number(value.boundaryEdgeExcess)} | " +
                "${number(value.meanHaloScore)} | ${number(value.meanLeakageScore)} | " +
                "${number(value.sensorDefectResidual)} | ${number(value.weakStarMedianContrastGain)} | " +
                "${number(value.maximumStrictStarWidthRatio)} | ${value.newDetectionsVersusClean} / " +
                "${value.falseWeakStarDetections} | " +
                "${if (value.processedAccepted) "accepted" else value.rejectionReasons.joinToString("|")} |")
        }
        appendLine()
        bundle.variants.filter { it.id.productionCandidateEligible }.forEach { variant ->
            val d = requireNotNull(variant.localDiagnostics)
            appendLine("- `${variant.id.stableId}` operator preservation: background changed " +
                "`${d.backgroundChangedPixels}`, negative residual changed `${d.negativeResidualChangedPixels}`, " +
                "linear chromaticity mean/max shift `${number(d.meanLinearChromaticityShift)}` / " +
                "`${number(d.maximumLinearChromaticityShift)}`.")
        }
        appendLine()
        appendLine("Decision evidence: ${bundle.decisionEvidence}")
        appendLine()
        appendLine("No production fix is included.")
    }

    private fun html(bundle: LocalResidualStretchAblationBundle): String {
        val rows = bundle.globalMetrics.joinToString("\n") { value ->
            "<tr><td>${escape(value.variant.stableId)}</td><td>${value.variant.strength?.let { number(it.toDouble()) } ?: "-"}</td>" +
                "<td>${number(value.skyMad)}</td><td>${number(value.bandingProxy)}</td>" +
                "<td>${number(value.boundaryEdgeExcess)}</td><td>${number(value.sensorDefectResidual)}</td>" +
                "<td>${number(value.weakStarMedianContrastGain)}</td><td>${value.newDetectionsVersusClean} / " +
                "${value.falseWeakStarDetections}</td>" +
                "<td>${escape(if (value.processedAccepted) "accepted" else value.rejectionReasons.joinToString("|"))}</td></tr>"
        }
        val sections = bundle.variants.joinToString("\n") { value ->
            "<section><h3>${escape(value.id.stableId)}</h3><div class=grid>" +
                "<figure><img src='${value.id.stableId}/full-resolution.png'><figcaption>full-resolution output</figcaption></figure>" +
                "<figure><img src='${value.id.stableId}/minus-clean-stack-x8.png'><figcaption>difference from CLEAN_STACK, x$DIFF_SCALE</figcaption></figure>" +
                "<figure><img src='${value.id.stableId}/stretch-operation.png'><figcaption>stretch-operation output</figcaption></figure>" +
                "</div></section>"
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Local-background residual stretch ablation</title>
<style>body{font:14px system-ui;margin:24px;background:#10151d;color:#e6edf3}code{color:#9cdcfe}table{border-collapse:collapse;width:100%;margin:12px 0}th,td{border:1px solid #43505f;padding:6px;text-align:left}.warning{padding:12px;background:#5c2c00;font-weight:700}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}img{width:100%}figure{margin:0}figcaption{color:#aab6c3}</style></head>
<body><h1>Local-background residual stretch test-only ablation</h1>
<p class="warning">TEST-ONLY ABLATION - PRODUCTION PROCESSING UNCHANGED</p>
<p>Decision: <b>${bundle.decision}</b>. Production candidate: <code>${escape(bundle.productionCandidate?.stableId ?: "NONE")}</code>.</p>
<table><thead><tr><th>Variant</th><th>Strength</th><th>Sky MAD</th><th>Banding</th><th>Boundary</th><th>Defect</th><th>Weak gain</th><th>New / false detections</th><th>Quality</th></tr></thead><tbody>$rows</tbody></table>
<h2>Full-resolution comparison</h2>$sections
<p>No production formula, mask, alpha, composition, defect filtering, registration, stacking, foreground preservation, or downstream stage was changed.</p></body></html>
"""
    }

    private fun writeDeterminismManifest(root: Path): LocalResidualStretchAblationWriteResult {
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
        val result = LocalResidualStretchAblationWriteResult(
            fileCount = dataFiles.size + 2,
            treeSha256 = treeHash,
            manifestSha256 = ReplayDiagnosticHashing.sha256File(manifestPath),
            summarySha256 = ReplayDiagnosticHashing.sha256File(root.resolve("ablation-summary.csv")),
            strictStarSha256 = ReplayDiagnosticHashing.sha256File(root.resolve("strict-star-metrics.csv")),
            htmlSha256 = ReplayDiagnosticHashing.sha256File(root.resolve("index.html"))
        )
        writeText(root.resolve("determinism.json"), buildString {
            appendLine("{")
            appendLine("  \"definition\": \"tree SHA-256 hashes the sorted path/file-hash manifest excluding sha256-manifest.txt and determinism.json\",")
            appendLine("  \"verification\": \"two independent report trees are compared byte-for-byte by LocalResidualStretchAblationTest\",")
            appendLine("  \"fileCount\": ${result.fileCount},")
            appendLine("  \"treeSha256\": ${json(result.treeSha256)},")
            appendLine("  \"manifestSha256\": ${json(result.manifestSha256)},")
            appendLine("  \"summarySha256\": ${json(result.summarySha256)},")
            appendLine("  \"strictStarSha256\": ${json(result.strictStarSha256)},")
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
        Files.writeString(path, value.replace("\r\n", "\n"), StandardCharsets.UTF_8)
    }

    private fun number(value: Double): String = String.format(Locale.US, "%.9f", value)

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val DIFF_SCALE = 8
}
