package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.math.abs
import kotlin.math.hypot
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.ExperimentalStarStrengthVariant

internal data class ExperimentalStarsReviewResult(
    val fileCount: Int,
    val treeSha256: String,
    val experimentalArgbSha256: String,
    val selectedVariant: ExperimentalStarStrengthVariant
)

internal object ExperimentalStarsReviewWriter {
    fun write(
        baseline: SkyMaskReplayBundle,
        evaluations: List<ExperimentalStrengthEvaluation>,
        selectedVariant: ExperimentalStarStrengthVariant,
        outputRoot: Path
    ): ExperimentalStarsReviewResult {
        require(outputRoot.fileName.toString().isNotBlank())
        require(evaluations.map { it.variant } == ExperimentalStarStrengthVariant.entries)
        val experimental = evaluations.single { it.variant == selectedVariant }.image
        if (Files.exists(outputRoot)) outputRoot.toFile().deleteRecursively()
        Files.createDirectories(outputRoot)

        val currentSafe = baseline.finalCurrent
        val variants = listOf(
            Variant("CLEAN_STACK", baseline.cleanComposed),
            Variant("CURRENT_STRONG_REJECTED", baseline.composedCurrent),
            Variant("CURRENT_SAFE", currentSafe),
            Variant("RECOVERED_STARS", currentSafe),
            Variant("EXPERIMENTAL_STARS", experimental)
        )
        variants.forEachIndexed { index, variant ->
            ReplayDiagnosticImageIo.writePng(
                outputRoot.resolve("${index + 1}-${variant.id.lowercase().replace('_', '-')}.png"),
                variant.image
            )
        }
        ReplayDiagnosticImageIo.writeDifference(
            outputRoot.resolve("diff-experimental-minus-clean-x8.png"),
            baseline.cleanComposed,
            experimental,
            8
        )
        ReplayDiagnosticImageIo.writeDifference(
            outputRoot.resolve("diff-experimental-minus-current-safe-x8.png"),
            currentSafe,
            experimental,
            8
        )
        writeFullComparison(
            outputRoot.resolve("full-comparison-clean-recovered-experimental.png"),
            listOf(
                "CLEAN_STACK" to baseline.cleanComposed,
                "RECOVERED_STARS (current safe)" to currentSafe,
                "EXPERIMENTAL_STARS" to experimental
            )
        )
        writeStrengthVariantReview(outputRoot, baseline, evaluations, selectedVariant)

        val cropVariants = listOf(
            "CLEAN" to baseline.cleanComposed,
            "RECOVERED" to currentSafe,
            "EXPERIMENTAL" to experimental,
            "DIFF x8" to differenceImage(baseline.cleanComposed, experimental, 8)
        )
        val strictDirectory = outputRoot.resolve("strict-stars")
        val defectDirectory = outputRoot.resolve("sensor-defects")
        val unconfirmedDirectory = outputRoot.resolve("stage17d-unconfirmed")
        Files.createDirectories(strictDirectory)
        Files.createDirectories(defectDirectory)
        Files.createDirectories(unconfirmedDirectory)
        baseline.fixture.strictReferenceStarLabels.forEachIndexed { index, label ->
            writeCropSheet(
                strictDirectory.resolve(String.format(Locale.US, "star-%02d-%s.png", index + 1, safe(label.id))),
                label.x.toInt(),
                label.y.toInt(),
                cropVariants
            )
        }
        baseline.fixture.strictSensorDefects.forEachIndexed { index, label ->
            writeCropSheet(
                defectDirectory.resolve(String.format(Locale.US, "defect-%02d-%s.png", index + 1, safe(label.id))),
                label.x.toInt(),
                label.y.toInt(),
                cropVariants
            )
        }
        STAGE17D_UNCONFIRMED.forEachIndexed { index, location ->
            writeCropSheet(
                unconfirmedDirectory.resolve(String.format(Locale.US, "unconfirmed-%02d.png", index + 1)),
                location.first,
                location.second,
                cropVariants
            )
        }

        val metrics = calculateMetrics(baseline, variants)
        writeText(outputRoot.resolve("metrics.csv"), metricsCsv(metrics))
        writeText(
            outputRoot.resolve("algorithm-and-fixed-parameters.md"),
            algorithmGuide(selectedVariant)
        )
        writeText(outputRoot.resolve("review-guide.md"), reviewGuide(metrics, selectedVariant))

        val dataFiles = regularFiles(outputRoot).sorted()
        val manifest = buildString {
            dataFiles.forEach { path ->
                append(ReplayDiagnosticHashing.sha256File(path)).append("  ")
                    .append(outputRoot.relativize(path).invariantSeparatorsPathString).append('\n')
            }
        }
        writeText(outputRoot.resolve("sha256-manifest.txt"), manifest)
        return ExperimentalStarsReviewResult(
            fileCount = regularFiles(outputRoot).size,
            treeSha256 = ReplayDiagnosticHashing.sha256(manifest.toByteArray(StandardCharsets.UTF_8)),
            experimentalArgbSha256 = ReplayDiagnosticHashing.sha256Argb(experimental),
            selectedVariant = selectedVariant
        )
    }

    private fun writeStrengthVariantReview(
        outputRoot: Path,
        baseline: SkyMaskReplayBundle,
        evaluations: List<ExperimentalStrengthEvaluation>,
        selectedVariant: ExperimentalStarStrengthVariant
    ) {
        val directory = outputRoot.resolve("strength-variants")
        Files.createDirectories(directory)
        evaluations.forEachIndexed { index, evaluation ->
            ReplayDiagnosticImageIo.writePng(
                directory.resolve("${index + 1}-${evaluation.variant.name.lowercase()}.png"),
                evaluation.image
            )
        }
        writeFullComparison(
            directory.resolve("full-comparison-clean-current-medium-strong.png"),
            listOf("CLEAN_STACK" to baseline.cleanComposed) + evaluations.map {
                "${it.variant.name}${if (it.variant == selectedVariant) " (SELECTED)" else ""}" to it.image
            }
        )
        val metricVariants = listOf(Variant("CLEAN_STACK", baseline.cleanComposed)) + evaluations.map {
            Variant(it.variant.name, it.image)
        }
        writeText(directory.resolve("metrics.csv"), metricsCsv(calculateMetrics(baseline, metricVariants)))
        writeText(
            directory.resolve("accepted-psf-support.csv"),
            buildString {
                appendLine(
                    "variant,residual_strength,maximum_detail_gain,considered_candidates," +
                        "candidates_writing_unique_max,rejected_or_overlapped_candidates," +
                        "changed_psf_support_pixels,argb_sha256"
                )
                evaluations.forEach { evaluation ->
                    append(evaluation.variant.name).append(',')
                    append(number(evaluation.variant.residualStrength.toDouble())).append(',')
                    append(number(evaluation.variant.maximumDetailGain.toDouble())).append(',')
                    append(evaluation.diagnostics.considered).append(',')
                    append(evaluation.diagnostics.enhanced).append(',')
                    append(evaluation.diagnostics.rejected).append(',')
                    append(changedPixels(baseline.cleanComposed, evaluation.image)).append(',')
                    append(ReplayDiagnosticHashing.sha256Argb(evaluation.image)).append('\n')
                }
            }
        )
    }

    private data class Variant(val id: String, val image: ArgbPixelImage)

    private data class Metrics(
        val id: String,
        val hash: String,
        val changedPixels: Int,
        val skyMad: Double,
        val banding: Double,
        val weakMedianGain: Double,
        val medianApertureFluxRatio: Double,
        val medianPeakRatio: Double,
        val maximumCentroidShift: Double,
        val maximumWidthRatio: Double,
        val maximumEllipticityChange: Double,
        val boundaryEdgeExcess: Double,
        val meanHaloScore: Double,
        val meanLeakageScore: Double,
        val foregroundMeanChange: Double,
        val foregroundMaximumChange: Double,
        val defectMean: Double,
        val defectMaximum: Double,
        val protectedChanges: Int,
        val newDetections: Int,
        val newDetectionCoordinates: String
    )

    private fun calculateMetrics(
        baseline: SkyMaskReplayBundle,
        variants: List<Variant>
    ): List<Metrics> {
        val stages = variants.map { SkyMaskPostProcessStage(it.id, it.image) }
        val global = SkyMaskReplayMath.postProcessStageMetrics(
            stages,
            baseline.reference,
            baseline.effectiveAlpha,
            baseline.refinedMask,
            baseline.windows
        ).associateBy { it.stage }
        val stars = SkyMaskReplayMath.strictStarMetricsForStages(
            baseline.fixture,
            baseline.cleanComposed,
            variants.map { SkyMaskStarStageInput(it.id, it.image, baseline.effectiveAlpha) },
            baseline.refinedMask,
            baseline.foregroundProtection
        )
        val cleanStars = stars.filter { it.stage == "CLEAN_STACK" }.associateBy { it.starId }
        val weakIds = cleanStars.values.sortedBy { it.localContrast }.take(3).map { it.starId }.toSet()
        val detector = JpegStarDetector()
        val cleanDetected = detector.detect(baseline.cleanComposed, baseline.refinedMask).stars
        return variants.map { variant ->
            val variantStars = stars.filter { it.stage == variant.id }
            val weakGains = variantStars.filter { it.starId in weakIds }.map {
                it.localContrast / cleanStars.getValue(it.starId).localContrast
            }
            val apertureFluxRatios = variantStars.map {
                it.apertureFlux / cleanStars.getValue(it.starId).apertureFlux
            }
            val peakRatios = variantStars.map {
                it.peakLuminance / cleanStars.getValue(it.starId).peakLuminance
            }
            val defects = baseline.fixture.strictSensorDefects.map { label ->
                residual(baseline.cleanComposed, variant.image, label.x.toInt(), label.y.toInt(), 4)
            }
            val detected = detector.detect(variant.image, baseline.refinedMask).stars
            val newDetections = detected.filter { candidate ->
                cleanDetected.none { known ->
                    val radius = maxOf(2f, candidate.width * 0.75f)
                    hypot((candidate.x - known.x).toDouble(), (candidate.y - known.y).toDouble()) < radius
                }
            }.sortedWith(compareBy({ it.y }, { it.x }))
            val foregroundDifferences = variant.image.pixels.indices.filter { index ->
                val x = index % variant.image.width
                val y = index / variant.image.width
                baseline.effectiveAlpha.alphaAt(x, y) <= 0.0001f ||
                    baseline.foregroundProtection.contains(x, y)
            }.map { index ->
                channelDifference(baseline.cleanComposed.pixels[index], variant.image.pixels[index]).toDouble()
            }
            val windows = SkyMaskReplayMath.windowMetricsForVariant(
                windows = baseline.windows,
                reference = baseline.reference,
                cleanComposed = baseline.cleanComposed,
                processedSky = variant.image,
                output = variant.image,
                refined = baseline.refinedMask,
                protection = baseline.foregroundProtection,
                alpha = baseline.effectiveAlpha
            )
            Metrics(
                id = variant.id,
                hash = ReplayDiagnosticHashing.sha256Argb(variant.image),
                changedPixels = changedPixels(baseline.cleanComposed, variant.image),
                skyMad = global.getValue(variant.id).skyMad,
                banding = global.getValue(variant.id).bandingProxy,
                weakMedianGain = median(weakGains),
                medianApertureFluxRatio = median(apertureFluxRatios),
                medianPeakRatio = median(peakRatios),
                maximumCentroidShift = variantStars.maxOf { it.centroidShiftFromClean },
                maximumWidthRatio = variantStars.maxOf { it.widthRatioFromClean },
                maximumEllipticityChange = variantStars.maxOf {
                    abs(it.ellipticity - cleanStars.getValue(it.starId).ellipticity)
                },
                boundaryEdgeExcess = global.getValue(variant.id).boundaryEdgeExcess,
                meanHaloScore = windows.map { it.haloScore }.average(),
                meanLeakageScore = windows.map { it.leakageScore }.average(),
                foregroundMeanChange = foregroundDifferences.average(),
                foregroundMaximumChange = foregroundDifferences.maxOrNull() ?: 0.0,
                defectMean = defects.map { it.first }.average(),
                defectMaximum = defects.maxOf { it.second },
                protectedChanges = protectedChanges(baseline, variant.image),
                newDetections = newDetections.size,
                newDetectionCoordinates = if (newDetections.isEmpty()) "-" else newDetections.joinToString(";") {
                    "${number(it.x.toDouble())}:${number(it.y.toDouble())}"
                }
            )
        }
    }

    private fun metricsCsv(values: List<Metrics>): String = buildString {
        appendLine("variant,argb_sha256,changed_pixels,sky_mad,banding_proxy,weak_star_median_contrast_gain,strict_star_median_aperture_flux_ratio,strict_star_median_peak_ratio,maximum_strict_star_centroid_shift,maximum_strict_star_width_ratio,maximum_strict_star_ellipticity_change,boundary_edge_excess,mean_halo_score,mean_leakage_score,foreground_mean_change_from_clean,foreground_maximum_change_from_clean,sensor_defect_mean_residual,sensor_defect_maximum_residual,protected_pixel_changes,new_detections_vs_clean,new_detection_coordinates")
        values.forEach { value ->
            append(value.id).append(',').append(value.hash).append(',').append(value.changedPixels).append(',')
            append(number(value.skyMad)).append(',').append(number(value.banding)).append(',')
            append(number(value.weakMedianGain)).append(',').append(number(value.medianApertureFluxRatio)).append(',')
            append(number(value.medianPeakRatio)).append(',').append(number(value.maximumCentroidShift)).append(',')
            append(number(value.maximumWidthRatio)).append(',').append(number(value.maximumEllipticityChange)).append(',')
            append(number(value.boundaryEdgeExcess)).append(',').append(number(value.meanHaloScore)).append(',')
            append(number(value.meanLeakageScore)).append(',').append(number(value.foregroundMeanChange)).append(',')
            append(number(value.foregroundMaximumChange)).append(',')
            append(number(value.defectMean)).append(',').append(number(value.defectMaximum)).append(',')
            append(value.protectedChanges).append(',').append(value.newDetections).append(',')
            append(value.newDetectionCoordinates).append('\n')
        }
    }

    private fun algorithmGuide(selectedVariant: ExperimentalStarStrengthVariant): String = """
        # Experimental Stars fixed production configuration

        - No global asinh or target-median stretch.
        - Positive local residual only; local background is unchanged when support is zero.
        - Noise support starts at `0.75 * sky luminance MAD` and reaches full support smoothly.
        - Shape support requires a compact multi-pixel PSF, at least one opposite support pair, and ellipticity <= `0.68`.
        - Known sensor-defect pixels are excluded from discovery, background estimation, and enhancement.
        - Existing effective sky alpha supplies foreground support; the residual delta is multiplied by `sqrt(alpha)`.
        - Bright cores use the existing percentile protection and are capped below `0.985` linear.
        - Exactly three fixed strength variants were evaluated without changing detection or geometry: `CURRENT=1.00/1.75`, `MEDIUM=1.50/2.50`, `STRONG=2.25/3.25` (`residual strength / maximum detail gain`).
        - The selected production variant is `${selectedVariant.name}`. Final residual gain is capped at `maximum detail gain - 1`.
        - Strength changes the positive residual and its perceptual weighting inside the same accepted PSF radius; support weight uses `weight^(1 / strength^2)`. Zero support remains zero.
        - Discovery PSF width remains fixed to the Stage 17D median `2.85 px`.
        - The active post-composition suspicious-point classifier remains enabled.
    """.trimIndent() + "\n"

    private fun reviewGuide(
        metrics: List<Metrics>,
        selectedVariant: ExperimentalStarStrengthVariant
    ): String {
        val experimental = metrics.single { it.id == "EXPERIMENTAL_STARS" }
        return """
            # Human review: Experimental Stars

            Compare `1-clean-stack.png`, `3-current-safe.png` / `4-recovered-stars.png`, and
            `5-experimental-stars.png`. The two safe files are intentionally byte-identical.

            Selected fixed strength variant: `${selectedVariant.name}`.

            Inspect, in order:

            1. `full-comparison-clean-recovered-experimental.png` at fit-to-screen scale: weak stars must be visibly easier to see without zooming.
            2. `strength-variants/full-comparison-clean-current-medium-strong.png` at the same scale: compare only the three fixed strengths.
            3. `strict-stars/`: verify that stronger visibility did not widen or asymmetrically deform cores.
            4. `sensor-defects/`: Experimental must match CLEAN at both known defects.
            5. `stage17d-unconfirmed/`: the two old unconfirmed locations must not become new stars.

            Measured experimental evidence: changed pixels `${experimental.changedPixels}`, weak-star median
            contrast gain `${number(experimental.weakMedianGain)}x`, aperture-flux / peak ratios
            `${number(experimental.medianApertureFluxRatio)}` / `${number(experimental.medianPeakRatio)}`,
            maximum centroid shift / width ratio / ellipticity change
            `${number(experimental.maximumCentroidShift)}` / `${number(experimental.maximumWidthRatio)}` /
            `${number(experimental.maximumEllipticityChange)}`, sensor-defect mean/max `${number(experimental.defectMean)}` /
            `${number(experimental.defectMaximum)}`, protected changes `${experimental.protectedChanges}`, new detections
            `${experimental.newDetections}` at `${experimental.newDetectionCoordinates}`.

            This is an experimental preset, not a production-quality visual approval. Final acceptance is by device review.
        """.trimIndent() + "\n"
    }

    private fun writeFullComparison(path: Path, panels: List<Pair<String, ArgbPixelImage>>) {
        val width = panels.first().second.width
        val height = panels.first().second.height
        val output = BufferedImage(width * panels.size, height + HEADER_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, output.width, output.height)
        graphics.color = Color.WHITE
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        panels.forEachIndexed { index, (label, image) ->
            graphics.drawString(label, index * width + 12, 22)
            graphics.drawImage(buffered(image), index * width, HEADER_HEIGHT, null)
        }
        graphics.dispose()
        check(ImageIO.write(output, "png", path.toFile()))
    }

    private fun writeCropSheet(
        path: Path,
        centerX: Int,
        centerY: Int,
        variants: List<Pair<String, ArgbPixelImage>>
    ) {
        val panelSize = CROP_SIZE * CROP_SCALE
        val output = BufferedImage(panelSize * variants.size, panelSize + HEADER_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, output.width, output.height)
        graphics.color = Color.WHITE
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        variants.forEachIndexed { index, (label, image) ->
            graphics.drawString("$label ($centerX,$centerY)", index * panelSize + 8, 21)
            val crop = crop(image, centerX, centerY)
            graphics.drawImage(crop, index * panelSize, HEADER_HEIGHT, panelSize, panelSize, null)
        }
        graphics.dispose()
        check(ImageIO.write(output, "png", path.toFile()))
    }

    private fun crop(image: ArgbPixelImage, centerX: Int, centerY: Int): BufferedImage {
        val result = BufferedImage(CROP_SIZE, CROP_SIZE, BufferedImage.TYPE_INT_ARGB)
        val radius = CROP_SIZE / 2
        for (y in 0 until CROP_SIZE) for (x in 0 until CROP_SIZE) {
            val sourceX = centerX + x - radius
            val sourceY = centerY + y - radius
            result.setRGB(
                x,
                y,
                if (sourceX in 0 until image.width && sourceY in 0 until image.height) {
                    image.pixelAt(sourceX, sourceY)
                } else {
                    0xFF000000.toInt()
                }
            )
        }
        return result
    }

    private fun buffered(image: ArgbPixelImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        }

    private fun differenceImage(first: ArgbPixelImage, second: ArgbPixelImage, scale: Int): ArgbPixelImage =
        ArgbPixelImage(first.width, first.height, IntArray(first.pixels.size) { index ->
            val red = (abs((second.pixels[index] ushr 16 and 0xFF) - (first.pixels[index] ushr 16 and 0xFF)) * scale)
                .coerceAtMost(255)
            val green = (abs((second.pixels[index] ushr 8 and 0xFF) - (first.pixels[index] ushr 8 and 0xFF)) * scale)
                .coerceAtMost(255)
            val blue = (abs((second.pixels[index] and 0xFF) - (first.pixels[index] and 0xFF)) * scale)
                .coerceAtMost(255)
            0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        })

    private fun residual(
        clean: ArgbPixelImage,
        output: ArgbPixelImage,
        centerX: Int,
        centerY: Int,
        radius: Int
    ): Pair<Double, Double> {
        val values = mutableListOf<Double>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy > radius * radius) continue
            val x = centerX + dx
            val y = centerY + dy
            if (x !in 0 until clean.width || y !in 0 until clean.height) continue
            values += channelDifference(clean.pixelAt(x, y), output.pixelAt(x, y)).toDouble()
        }
        return values.average() to (values.maxOrNull() ?: 0.0)
    }

    private fun protectedChanges(baseline: SkyMaskReplayBundle, output: ArgbPixelImage): Int =
        output.pixels.indices.count { index ->
            val x = index % output.width
            val y = index / output.width
            (baseline.effectiveAlpha.alphaAt(x, y) <= 0.0001f || baseline.foregroundProtection.contains(x, y)) &&
                output.pixels[index] != baseline.cleanComposed.pixels[index]
        }

    private fun changedPixels(first: ArgbPixelImage, second: ArgbPixelImage): Int =
        first.pixels.indices.count { first.pixels[it] != second.pixels[it] }

    private fun channelDifference(first: Int, second: Int): Int = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    )

    private fun median(values: List<Double>): Double = values.sorted().let { sorted ->
        if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5
    }

    private fun regularFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile).toList()
    }

    private fun writeText(path: Path, value: String) {
        Files.writeString(path, value.replace("\r\n", "\n"), StandardCharsets.UTF_8)
    }

    private fun safe(value: String): String = value.lowercase().replace(Regex("[^a-z0-9-]+"), "-")
    private fun number(value: Double): String = String.format(Locale.US, "%.9f", value)

    private const val CROP_SIZE = 41
    private const val CROP_SCALE = 8
    private const val HEADER_HEIGHT = 32
    private val STAGE17D_UNCONFIRMED = listOf(661 to 216, 243 to 346)
}
