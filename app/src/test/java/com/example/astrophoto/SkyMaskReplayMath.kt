package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object SkyMaskReplayMath {
    fun boundaryMetrics(
        initial: SkyMask,
        refined: SkyMask,
        protection: SkyMask,
        alpha: AlphaMask
    ): SkyMaskBoundaryMetrics {
        requireSameDimensions(initial, refined, protection, alpha)
        val width = initial.width
        val height = initial.height
        val initialPixels = initial.copyPixels()
        val refinedPixels = refined.copyPixels()
        val protectionPixels = protection.copyPixels()
        val alphaValues = FloatArray(width * height) { index ->
            alpha.alphaAt(index % width, index / width).also {
                require(it.isFinite() && it in 0f..1f)
            }
        }
        var zero = 0
        var below = 0
        var transition = 0
        var above = 0
        var one = 0
        alphaValues.forEach { value ->
            when {
                value == 0f -> zero++
                value < 0.01f -> below++
                value <= 0.99f -> transition++
                value < 1f -> above++
                else -> one++
            }
        }
        val runWidths = transitionRunWidths(alphaValues, width, height)
        return SkyMaskBoundaryMetrics(
            initial = topology(initialPixels, width, height),
            refined = topology(refinedPixels, width, height),
            foregroundProtection = topology(protectionPixels, width, height),
            transitionBandArea = transition,
            alpha = SkyMaskAlphaMetrics(
                zeroPixels = zero,
                belowOnePercentPixels = below,
                transitionPixels = transition,
                aboveNinetyNinePercentPixels = above,
                onePixels = one,
                meanTransitionRunWidth = runWidths.average().takeIf(Double::isFinite) ?: 0.0,
                maximumTransitionRunWidth = runWidths.maxOrNull() ?: 0
            ),
            foregroundRiskInclusionProxyPixels = refinedPixels.indices.count {
                refinedPixels[it] && protectionPixels[it]
            },
            initialPixelsRemovedByRefinementProxy = initialPixels.indices.count {
                initialPixels[it] && !refinedPixels[it]
            }
        )
    }

    fun selectWindows(
        fixture: Stage6RegressionFixture,
        reference: ArgbPixelImage,
        cleanStack: ArgbPixelImage,
        processedSky: ArgbPixelImage,
        variants: List<SkyMaskReplayVariant>,
        refined: SkyMask,
        protection: SkyMask,
        alpha: AlphaMask
    ): List<SkyMaskDiagnosticWindow> {
        val width = reference.width
        val height = reference.height
        val windows = mutableListOf<SkyMaskDiagnosticWindow>()
        fun add(id: String, x: Double, y: Double, size: Int, source: String) {
            require(size % 2 == 1)
            val radius = size / 2
            val centerX = x.roundToInt().coerceIn(radius, width - radius - 1)
            val centerY = y.roundToInt().coerceIn(radius, height - radius - 1)
            windows += SkyMaskDiagnosticWindow(id, centerX, centerY, size, source)
        }
        fixture.strictReferenceStarLabels.forEach { star ->
            add("strict-star-${star.id}", star.x, star.y, 63, "strict_star")
        }
        fixture.strictSensorDefects.forEach { defect ->
            add("strict-defect-${defect.id}", defect.x, defect.y, 63, "strict_sensor_defect")
        }
        fixture.groundTruth.singleOrNull { it.id == "candidate-x56925-y74428" }?.let {
            add(it.id, it.x, it.y, 127, "needs_review_boundary_target")
        }
        val refinedBoundary = boundaryPixels(refined.copyPixels(), width, height)
        val components = components(refinedBoundary, width, height, includeFalse = false)
        fixture.strictReferenceStarLabels.forEach { star ->
            components.mapNotNull { component ->
                component.minByOrNull { index ->
                    val dx = index % width - star.x
                    val dy = index / width - star.y
                    dx * dx + dy * dy
                }
            }.filter { index ->
                hypot(index % width - star.x, index / width - star.y) <= 64.0
            }.sortedWith(compareBy<Int> { it / width }.thenBy { it % width })
                .forEachIndexed { index, point ->
                    add(
                        "boundary-near-${star.id}-${(index + 1).toString().padStart(2, '0')}",
                        (point % width).toDouble(),
                        (point / width).toDouble(),
                        63,
                        "refined_boundary_near_strict_star"
                    )
                }
        }
        add("foreground-proxy-left-building-edge", 160.0, 480.0, 127, "reference_edge_proxy")
        add("foreground-proxy-lower-roof-edge", 400.0, 620.0, 127, "reference_edge_proxy")
        add("foreground-proxy-right-window-edge", 665.0, 400.0, 127, "reference_edge_proxy")

        val current = variants.single { it.id == SkyMaskReplayVariantId.CURRENT }
        val noMask = variants.single { it.id == SkyMaskReplayVariantId.NO_MASK }
        val noPost = variants.single { it.id == SkyMaskReplayVariantId.NO_POSTPROCESS }
        val refinedPixels = refined.copyPixels()
        val protectionPixels = protection.copyPixels()
        val maxAlphaGradient = maximumIndex(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) 0.0 else {
                hypot(
                    (alpha.alphaAt(x + 1, y) - alpha.alphaAt(x - 1, y)).toDouble(),
                    (alpha.alphaAt(x, y + 1) - alpha.alphaAt(x, y - 1)).toDouble()
                ) * 0.5
            }
        }
        val maxCompositionDifference = maximumIndex(width * height) { index ->
            colorDifference(current.output.pixels[index], noMask.output.pixels[index])
        }
        val maxDiscontinuity = maximumIndex(width * height) { index ->
            if (!refinedBoundary[index]) 0.0 else {
                abs(laplacian(current.output, index) - laplacian(reference, index))
            }
        }
        val maxHalo = maximumIndex(width * height) { index ->
            if (!refinedBoundary[index]) 0.0 else {
                localMean(index, width, height, 2) { sample ->
                    abs(luminance(current.output.pixels[sample]) -
                        luminance(noPost.output.pixels[sample]))
                }
            }
        }
        val maxLeakage = maximumIndex(width * height) { index ->
            val foregroundProxy = !refinedPixels[index] || protectionPixels[index]
            if (!foregroundProxy) 0.0 else {
                val x = index % width
                val y = index / width
                alpha.alphaAt(x, y) * colorDifference(
                    processedSky.pixels[index], reference.pixels[index]
                )
            }
        }
        listOf(
            Triple("maximum-alpha-gradient", maxAlphaGradient, "maximum_alpha_gradient"),
            Triple("maximum-composition-difference", maxCompositionDifference, "maximum_composition_difference"),
            Triple("maximum-local-discontinuity", maxDiscontinuity, "maximum_local_luminance_discontinuity"),
            Triple("maximum-halo-score", maxHalo, "maximum_halo_proxy"),
            Triple("maximum-edge-leakage", maxLeakage, "maximum_edge_leakage_proxy")
        ).forEach { (id, index, source) ->
            add(id, (index % width).toDouble(), (index / width).toDouble(), 127, source)
        }
        require(windows.map { it.id }.distinct().size == windows.size)
        return windows
    }

    fun strictStarMetrics(
        fixture: Stage6RegressionFixture,
        reference: ArgbPixelImage,
        cleanStack: ArgbPixelImage,
        processedSky: ArgbPixelImage,
        cleanComposed: ArgbPixelImage,
        finalCurrent: ArgbPixelImage,
        variants: List<SkyMaskReplayVariant>,
        refined: SkyMask,
        protection: SkyMask,
        currentAlpha: AlphaMask
    ): List<SkyMaskStarStageMetrics> {
        val boundary = boundaryPixels(refined.copyPixels(), refined.width, refined.height)
        val current = variants.single { it.id == SkyMaskReplayVariantId.CURRENT }
        require(current.output.pixels.contentEquals(
            variants.single { it.id == SkyMaskReplayVariantId.CURRENT }.output.pixels
        ))
        val stages = listOf(
            Triple("clean-stack", cleanStack, currentAlpha),
            Triple("processed-sky", processedSky, currentAlpha),
            Triple("composed-current", current.output, currentAlpha),
            Triple("final-current", finalCurrent, currentAlpha),
            Triple("no-mask", variants.single { it.id == SkyMaskReplayVariantId.NO_MASK }.output,
                variants.single { it.id == SkyMaskReplayVariantId.NO_MASK }.alpha),
            Triple("hard-mask", variants.single { it.id == SkyMaskReplayVariantId.HARD_MASK }.output,
                variants.single { it.id == SkyMaskReplayVariantId.HARD_MASK }.alpha),
            Triple("no-refine", variants.single { it.id == SkyMaskReplayVariantId.NO_REFINE }.output,
                variants.single { it.id == SkyMaskReplayVariantId.NO_REFINE }.alpha),
            Triple("no-protection", variants.single { it.id == SkyMaskReplayVariantId.NO_PROTECTION }.output,
                variants.single { it.id == SkyMaskReplayVariantId.NO_PROTECTION }.alpha),
            Triple("no-postprocess", variants.single { it.id == SkyMaskReplayVariantId.NO_POSTPROCESS }.output,
                variants.single { it.id == SkyMaskReplayVariantId.NO_POSTPROCESS }.alpha)
        )
        val protected = protection.copyPixels()
        return fixture.strictReferenceStarLabels.flatMap { star ->
            val clean = measureStar(cleanStack, star.x, star.y)
            stages.map { (stage, image, alpha) ->
                val measured = measureStar(image, star.x, star.y)
                val aperture = apertureIndices(
                    image.width,
                    image.height,
                    star.x,
                    star.y,
                    STAR_APERTURE_RADIUS
                )
                val alphaValues = aperture.map {
                    alpha.alphaAt(it % image.width, it / image.width).toDouble()
                }
                val distance = distanceToSet(star.x, star.y, boundary, image.width)
                SkyMaskStarStageMetrics(
                    starId = star.id,
                    stage = stage,
                    centroidX = measured.centroidX,
                    centroidY = measured.centroidY,
                    peakLuminance = measured.peak,
                    apertureFlux = measured.flux,
                    localBackground = measured.background,
                    localContrast = measured.contrast,
                    robustWidth = measured.width,
                    ellipticity = measured.ellipticity,
                    chromaResidual = measured.chromaResidual,
                    distanceToMaskBoundary = distance,
                    centerAlpha = alpha.alphaAt(
                        star.x.roundToInt().coerceIn(0, image.width - 1),
                        star.y.roundToInt().coerceIn(0, image.height - 1)
                    ).toDouble(),
                    minimumApertureAlpha = alphaValues.minOrNull() ?: 0.0,
                    meanApertureAlpha = alphaValues.average(),
                    maximumApertureAlpha = alphaValues.maxOrNull() ?: 0.0,
                    apertureFractionBelowHalfAlpha =
                        alphaValues.count { it < 0.5 }.toDouble() / alphaValues.size,
                    apertureFractionProtected =
                        aperture.count { protected[it] }.toDouble() / aperture.size,
                    fluxRetentionFromClean = safeRatio(measured.flux, clean.flux),
                    peakAttenuationFromClean = 1.0 - safeRatio(measured.peak, clean.peak),
                    centroidShiftFromClean = hypot(
                        measured.centroidX - clean.centroidX,
                        measured.centroidY - clean.centroidY
                    ),
                    widthRatioFromClean = safeRatio(measured.width, clean.width)
                ).also(::requireFinite)
            }
        }
    }

    fun windowMetrics(
        windows: List<SkyMaskDiagnosticWindow>,
        reference: ArgbPixelImage,
        cleanStack: ArgbPixelImage,
        processedSky: ArgbPixelImage,
        variants: List<SkyMaskReplayVariant>,
        refined: SkyMask,
        protection: SkyMask,
        alpha: AlphaMask
    ): List<SkyMaskWindowMetrics> {
        val boundary = boundaryPixels(refined.copyPixels(), refined.width, refined.height)
        val refinedPixels = refined.copyPixels()
        val protected = protection.copyPixels()
        val current = variants.single { it.id == SkyMaskReplayVariantId.CURRENT }.output
        val noPost = variants.single { it.id == SkyMaskReplayVariantId.NO_POSTPROCESS }.output
        return windows.map { window ->
            val indices = cropIndices(reference.width, window)
            val nearBoundary = indices.filter { isNearSet(it, boundary, reference.width, reference.height, 3) }
            val residuals = nearBoundary.map { index ->
                luminance(current.pixels[index]) - luminance(noPost.pixels[index])
            }
            val skyResidual = nearBoundary.filter { refinedPixels[it] }.map {
                luminance(current.pixels[it]) - luminance(noPost.pixels[it])
            }
            val foregroundResidual = nearBoundary.filterNot { refinedPixels[it] }.map {
                luminance(current.pixels[it]) - luminance(noPost.pixels[it])
            }
            val crossPairs = boundaryPairs(indices.toSet(), refinedPixels, reference.width, reference.height)
            val currentJumps = crossPairs.map { (first, second) ->
                abs(luminance(current.pixels[first]) - luminance(current.pixels[second]))
            }
            val referenceJumps = crossPairs.map { (first, second) ->
                abs(luminance(reference.pixels[first]) - luminance(reference.pixels[second]))
            }
            val chromaJumps = crossPairs.map { (first, second) ->
                abs(chroma(current.pixels[first]) - chroma(current.pixels[second]))
            }
            val edgeResiduals = crossPairs.map { (first, second) ->
                abs(
                    (luminance(current.pixels[first]) - luminance(reference.pixels[first])) -
                        (luminance(current.pixels[second]) - luminance(reference.pixels[second]))
                )
            }
            val second = nearBoundary.map { index ->
                abs(laplacian(current, index) - laplacian(reference, index))
            }
            val skyValues = nearBoundary.filter { refinedPixels[it] }.map { luminance(current.pixels[it]) }
            val foregroundValues = nearBoundary.filterNot { refinedPixels[it] }.map { luminance(current.pixels[it]) }
            val leakageValues = indices.filter { !refinedPixels[it] || protected[it] }.map { index ->
                val x = index % reference.width
                val y = index / reference.width
                alpha.alphaAt(x, y) * colorDifference(processedSky.pixels[index], reference.pixels[index])
            }
            SkyMaskWindowMetrics(
                windowId = window.id,
                centerX = window.centerX,
                centerY = window.centerY,
                distanceToBoundary = distanceToSet(
                    window.centerX.toDouble(), window.centerY.toDouble(), boundary, reference.width
                ),
                brightRim = residuals.filter { it > 0 }.averageOrZero(),
                darkRim = residuals.filter { it < 0 }.map { -it }.averageOrZero(),
                haloAsymmetry = abs(skyResidual.averageOrZero() - foregroundResidual.averageOrZero()),
                haloScore = residuals.map(::abs).averageOrZero() +
                    abs(skyResidual.averageOrZero() - foregroundResidual.averageOrZero()),
                luminanceJump = currentJumps.averageOrZero(),
                chromaJump = chromaJumps.averageOrZero(),
                firstDerivativeExcess = currentJumps.zip(referenceJumps).map {
                    abs(it.first - it.second)
                }.averageOrZero(),
                secondDerivativeSpike = second.averageOrZero(),
                localVarianceMismatch = abs(variance(skyValues) - variance(foregroundValues)),
                edgeAlignedResidual = edgeResiduals.averageOrZero(),
                leakageScore = leakageValues.averageOrZero()
            ).also(::requireFinite)
        }
    }

    fun variantMetrics(
        fixture: Stage6RegressionFixture,
        reference: ArgbPixelImage,
        variants: List<SkyMaskReplayVariant>,
        currentWindowMetrics: List<SkyMaskWindowMetrics>,
        strictMetrics: List<SkyMaskStarStageMetrics>,
        currentAlpha: AlphaMask,
        protection: SkyMask,
        refined: SkyMask
    ): List<SkyMaskVariantMetrics> {
        val protected = protection.copyPixels()
        val refinedPixels = refined.copyPixels()
        val foregroundProxy = BooleanArray(reference.pixels.size) { index ->
            protected[index] || currentAlpha.alphaAt(index % reference.width, index / reference.width) <= 0.01f
        }
        val windowById = currentWindowMetrics.associateBy { it.windowId }
        return variants.map { variant ->
            val skyValues = variant.output.pixels.indices.filter { index ->
                variant.alpha.alphaAt(index % reference.width, index / reference.width) >= 0.5f
            }.map { luminance(variant.output.pixels[it]) }
            val median = percentile(skyValues, 0.5)
            val mad = percentile(skyValues.map { abs(it - median) }, 0.5)
            val foregroundChange = variant.output.pixels.indices.filter { foregroundProxy[it] }
                .map { colorDifference(variant.output.pixels[it], reference.pixels[it]) }
                .averageOrZero()
            val haloScores = currentWindowMetrics.filter { it.distanceToBoundary <= 31.0 }.map { metric ->
                val window = requireNotNull(windowById[metric.windowId])
                boundaryEdgeExcess(
                    variant.output,
                    reference,
                    refinedPixels,
                    window.centerX,
                    window.centerY,
                    reference.width,
                    reference.height,
                    31
                )
            }
            val leakage = variant.output.pixels.indices.filter { foregroundProxy[it] }.map { index ->
                variant.alpha.alphaAt(index % reference.width, index / reference.width) *
                    colorDifference(variant.processedSky.pixels[index], reference.pixels[index])
            }
            val stage = when (variant.id) {
                SkyMaskReplayVariantId.CURRENT -> "composed-current"
                SkyMaskReplayVariantId.NO_MASK -> "no-mask"
                SkyMaskReplayVariantId.HARD_MASK -> "hard-mask"
                SkyMaskReplayVariantId.NO_PROTECTION -> "no-protection"
                SkyMaskReplayVariantId.NO_REFINE -> "no-refine"
                SkyMaskReplayVariantId.NO_POSTPROCESS -> "no-postprocess"
            }
            val stars = stage?.let { value -> strictMetrics.filter { it.stage == value } }.orEmpty()
            SkyMaskVariantMetrics(
                variant = variant.id,
                skyMad = mad,
                foregroundMeanChange = foregroundChange,
                bandingProxy = bandingProxy(variant.output, variant.alpha),
                meanHaloScore = haloScores.averageOrZero(),
                meanLeakageScore = leakage.averageOrZero(),
                strictStarMedianFluxRetention = if (stars.isEmpty()) 0.0 else
                    percentile(stars.map { it.fluxRetentionFromClean }, 0.5),
                strictStarMaximumCentroidShift = stars.maxOfOrNull { it.centroidShiftFromClean } ?: 0.0
            ).also(::requireFinite)
        }
    }

    fun postProcessStageMetrics(
        stages: List<SkyMaskPostProcessStage>,
        reference: ArgbPixelImage,
        alpha: AlphaMask,
        refined: SkyMask,
        windows: List<SkyMaskDiagnosticWindow>
    ): List<SkyMaskPostProcessStageMetrics> {
        require(stages.isNotEmpty())
        val clean = stages.first().image
        val refinedPixels = refined.copyPixels()
        val boundaryWindows = windows.filter { window ->
            distanceToSet(
                window.centerX.toDouble(),
                window.centerY.toDouble(),
                boundaryPixels(refinedPixels, refined.width, refined.height),
                refined.width
            ) <= window.size / 2.0
        }
        return stages.map { stage ->
            val skyValues = stage.image.pixels.indices.filter { index ->
                alpha.alphaAt(index % alpha.width, index / alpha.width) >= 0.5f
            }.map { luminance(stage.image.pixels[it]) }
            val median = percentile(skyValues, 0.5)
            val boundaryExcess = boundaryWindows.map { window ->
                boundaryEdgeExcess(
                    stage.image,
                    reference,
                    refinedPixels,
                    window.centerX,
                    window.centerY,
                    reference.width,
                    reference.height,
                    window.size / 2
                )
            }.averageOrZero()
            SkyMaskPostProcessStageMetrics(
                stage = stage.id,
                skyMad = percentile(skyValues.map { abs(it - median) }, 0.5),
                bandingProxy = bandingProxy(stage.image, alpha),
                boundaryEdgeExcess = boundaryExcess,
                meanAbsoluteChangeFromClean = stage.image.pixels.indices.map {
                    colorDifference(stage.image.pixels[it], clean.pixels[it])
                }.averageOrZero()
            ).also(::requireFinite)
        }
    }

    fun classifyIssues(
        windows: List<SkyMaskWindowMetrics>,
        variants: List<SkyMaskVariantMetrics>,
        postProcessStages: List<SkyMaskPostProcessStageMetrics>
    ): List<SkyMaskReplayIssue> {
        val byId = variants.associateBy { it.variant }
        val current = byId.getValue(SkyMaskReplayVariantId.CURRENT).meanHaloScore
        fun reduction(id: SkyMaskReplayVariantId): Double = if (current <= 1e-9) 0.0 else
            ((current - byId.getValue(id).meanHaloScore) / current).coerceIn(-2.0, 1.0)
        val candidates = listOf(
            SkyMaskReplayCause.POSTPROCESS_ERROR to reduction(SkyMaskReplayVariantId.NO_POSTPROCESS),
            SkyMaskReplayCause.ALPHA_TRANSITION_ERROR to reduction(SkyMaskReplayVariantId.HARD_MASK),
            SkyMaskReplayCause.REFINEMENT_ERROR to reduction(SkyMaskReplayVariantId.NO_REFINE),
            SkyMaskReplayCause.FOREGROUND_PROTECTION_ERROR to reduction(SkyMaskReplayVariantId.NO_PROTECTION),
            SkyMaskReplayCause.INITIAL_MASK_ERROR to reduction(SkyMaskReplayVariantId.NO_MASK)
        )
        val best = candidates.maxByOrNull { it.second }
        val cause = if (best != null && best.second >= ROOT_CAUSE_MIN_REDUCTION) {
            best.first
        } else {
            SkyMaskReplayCause.INSUFFICIENT_EVIDENCE
        }
        val reduction = best?.second?.coerceAtLeast(0.0) ?: 0.0
        val firstBadPostStage = if (cause == SkyMaskReplayCause.POSTPROCESS_ERROR) {
            firstBadPostProcessStage(postProcessStages)
        } else {
            null
        }
        val stage = when (cause) {
            SkyMaskReplayCause.POSTPROCESS_ERROR -> firstBadPostStage?.stage ?: "processed_sky"
            SkyMaskReplayCause.ALPHA_TRANSITION_ERROR -> "mask_feather_and_effective_alpha"
            SkyMaskReplayCause.REFINEMENT_ERROR -> "refined_sky_mask"
            SkyMaskReplayCause.FOREGROUND_PROTECTION_ERROR -> "foreground_protection"
            SkyMaskReplayCause.INITIAL_MASK_ERROR -> "initial_sky_mask"
            else -> "not_proven"
        }
        val lastClean = when (cause) {
            SkyMaskReplayCause.POSTPROCESS_ERROR -> firstBadPostStage?.let { bad ->
                val index = postProcessStages.indexOf(bad)
                postProcessStages.getOrNull(index - 1)?.stage
            } ?: "clean_composition"
            SkyMaskReplayCause.ALPHA_TRANSITION_ERROR -> "refined_binary_mask"
            SkyMaskReplayCause.REFINEMENT_ERROR -> "initial_sky_mask"
            SkyMaskReplayCause.FOREGROUND_PROTECTION_ERROR -> "refined_sky_mask"
            SkyMaskReplayCause.INITIAL_MASK_ERROR -> "reference_source"
            else -> "unknown"
        }
        val minimal = when (cause) {
            SkyMaskReplayCause.POSTPROCESS_ERROR -> "isolate the first adaptive pass responsible before any production change"
            SkyMaskReplayCause.ALPHA_TRANSITION_ERROR -> "review the existing alpha transition width using the proven hard-mask ablation"
            SkyMaskReplayCause.REFINEMENT_ERROR -> "review only refinement membership against the initial-mask ablation"
            SkyMaskReplayCause.FOREGROUND_PROTECTION_ERROR -> "review only protection membership against the no-protection ablation"
            SkyMaskReplayCause.INITIAL_MASK_ERROR -> "add manual sky/foreground truth before changing initial mask"
            else -> "none; evidence is insufficient for a production patch"
        }
        val postEvidence = firstBadPostStage?.let { bad ->
            val baseline = postProcessStages.first()
            ", firstPostStage=${bad.stage}, banding=${format(baseline.bandingProxy)}->" +
                "${format(bad.bandingProxy)}, boundaryEdgeExcess=" +
                "${format(baseline.boundaryEdgeExcess)}->${format(bad.boundaryEdgeExcess)}"
        }.orEmpty()
        return windows.filter {
            it.haloScore > 1e-9 || it.leakageScore > 1e-9 || it.firstDerivativeExcess > 1e-9
        }.sortedWith(
            compareByDescending<SkyMaskWindowMetrics> { it.haloScore }
                .thenByDescending { it.leakageScore }
                .thenBy { it.windowId }
        ).take(12).map { metric ->
            SkyMaskReplayIssue(
                windowId = metric.windowId,
                cause = cause,
                observedDefect = if (metric.haloScore >= metric.leakageScore) {
                    "boundary halo/discontinuity proxy"
                } else {
                    "processed-sky foreground leakage proxy"
                },
                firstBadStage = stage,
                lastCleanStage = lastClean,
                supportingMetric =
                    "halo=${format(metric.haloScore)}, leakage=${format(metric.leakageScore)}, " +
                        "bestGlobalAblationReduction=${format(reduction)}$postEvidence",
                confidence = if (cause == SkyMaskReplayCause.INSUFFICIENT_EVIDENCE) 0.35 else
                    (0.55 + reduction * 0.40).coerceAtMost(0.95),
                minimalFixCandidate = minimal,
                fixRisk = "mask-boundary changes can alter foreground protection and strict-star flux",
                requiredRegressionTest =
                    "window ${metric.windowId}: halo, leakage, strict-star flux and centroid gates"
            )
        }
    }

    private fun firstBadPostProcessStage(
        stages: List<SkyMaskPostProcessStageMetrics>
    ): SkyMaskPostProcessStageMetrics? {
        if (stages.size < 2) return null
        val baseline = stages.first()
        return stages.drop(1).firstOrNull { stage ->
            stage.bandingProxy > baseline.bandingProxy * 1.05 + 0.05 ||
                stage.boundaryEdgeExcess > baseline.boundaryEdgeExcess * 1.15 + 0.05
        }
    }

    private data class StarMeasurement(
        val centroidX: Double,
        val centroidY: Double,
        val peak: Double,
        val flux: Double,
        val background: Double,
        val contrast: Double,
        val width: Double,
        val ellipticity: Double,
        val chromaResidual: Double
    )

    private fun measureStar(image: ArgbPixelImage, x: Double, y: Double): StarMeasurement {
        val aperture = apertureIndices(image.width, image.height, x, y, STAR_APERTURE_RADIUS)
        val ring = annulusIndices(image.width, image.height, x, y, 6.0, 9.0)
        val background = percentile(ring.map { luminance(image.pixels[it]) }, 0.5)
        val weights = aperture.map { (luminance(image.pixels[it]) - background).coerceAtLeast(0.0) }
        val flux = weights.sum()
        val centerX = if (flux > 0.0) aperture.indices.sumOf {
            (aperture[it] % image.width) * weights[it]
        } / flux else x
        val centerY = if (flux > 0.0) aperture.indices.sumOf {
            (aperture[it] / image.width) * weights[it]
        } / flux else y
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        if (flux > 0.0) aperture.indices.forEach { offset ->
            val dx = aperture[offset] % image.width - centerX
            val dy = aperture[offset] / image.width - centerY
            xx += weights[offset] * dx * dx / flux
            yy += weights[offset] * dy * dy / flux
            xy += weights[offset] * dx * dy / flux
        }
        val trace = xx + yy
        val delta = sqrt(((xx - yy) * (xx - yy) + 4.0 * xy * xy).coerceAtLeast(0.0))
        val major = ((trace + delta) / 2.0).coerceAtLeast(0.0)
        val minor = ((trace - delta) / 2.0).coerceAtLeast(0.0)
        val peak = aperture.maxOf { luminance(image.pixels[it]) }
        return StarMeasurement(
            centroidX = centerX,
            centroidY = centerY,
            peak = peak,
            flux = flux,
            background = background,
            contrast = peak - background,
            width = 2.355 * sqrt(((major + minor) / 2.0).coerceAtLeast(0.0)),
            ellipticity = if (major <= 1e-12) 0.0 else 1.0 - sqrt(minor / major),
            chromaResidual = aperture.map { chroma(image.pixels[it]) }.averageOrZero()
        )
    }

    private fun topology(mask: BooleanArray, width: Int, height: Int): SkyMaskTopologyMetrics {
        val included = components(mask, width, height, includeFalse = false)
        val excluded = components(mask, width, height, includeFalse = true)
        val holes = excluded.filter { component ->
            component.none { index ->
                val x = index % width
                val y = index / width
                x == 0 || y == 0 || x == width - 1 || y == height - 1
            }
        }
        val small = included.filter { it.size < SMALL_ISLAND_AREA }
        return SkyMaskTopologyMetrics(
            boundaryPixels = boundaryPixels(mask, width, height).count { it },
            disconnectedRegions = included.size,
            smallIslandCount = small.size,
            smallIslandPixels = small.sumOf { it.size },
            holeCount = holes.size,
            holePixels = holes.sumOf { it.size }
        )
    }

    private fun components(
        source: BooleanArray,
        width: Int,
        height: Int,
        includeFalse: Boolean
    ): List<IntArray> {
        val target = !includeFalse
        val visited = BooleanArray(source.size)
        val result = mutableListOf<IntArray>()
        source.indices.forEach { start ->
            if (visited[start] || source[start] != target) return@forEach
            val queue = IntArray(source.size)
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                fun visit(next: Int) {
                    if (!visited[next] && source[next] == target) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < width) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y + 1 < height) visit(index + width)
            }
            result += queue.copyOf(tail)
        }
        return result
    }

    internal fun boundaryPixels(mask: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(mask.size) { index ->
            val x = index % width
            val y = index / width
            (x > 0 && mask[index - 1] != mask[index]) ||
                (x + 1 < width && mask[index + 1] != mask[index]) ||
                (y > 0 && mask[index - width] != mask[index]) ||
                (y + 1 < height && mask[index + width] != mask[index])
        }

    internal fun alphaGradient(alpha: AlphaMask): DoubleArray = DoubleArray(alpha.width * alpha.height) { index ->
        val x = index % alpha.width
        val y = index / alpha.width
        if (x == 0 || y == 0 || x == alpha.width - 1 || y == alpha.height - 1) 0.0 else {
            hypot(
                (alpha.alphaAt(x + 1, y) - alpha.alphaAt(x - 1, y)).toDouble(),
                (alpha.alphaAt(x, y + 1) - alpha.alphaAt(x, y - 1)).toDouble()
            ) * 0.5
        }
    }

    private fun transitionRunWidths(values: FloatArray, width: Int, height: Int): List<Int> {
        val result = mutableListOf<Int>()
        fun scan(length: Int, value: (Int) -> Float) {
            var start = -1
            for (position in 0..length) {
                val transition = position < length && value(position) in 0.01f..0.99f
                if (transition && start < 0) start = position
                if (!transition && start >= 0) {
                    result += position - start
                    start = -1
                }
            }
        }
        for (y in 0 until height) scan(width) { x -> values[y * width + x] }
        for (x in 0 until width) scan(height) { y -> values[y * width + x] }
        return result
    }

    private fun boundaryPairs(
        allowed: Set<Int>,
        mask: BooleanArray,
        width: Int,
        height: Int
    ): List<Pair<Int, Int>> = buildList {
        allowed.sorted().forEach { index ->
            val x = index % width
            val y = index / width
            if (x + 1 < width && index + 1 in allowed && mask[index] != mask[index + 1]) {
                add(index to index + 1)
            }
            if (y + 1 < height && index + width in allowed && mask[index] != mask[index + width]) {
                add(index to index + width)
            }
        }
    }

    private fun cropIndices(width: Int, window: SkyMaskDiagnosticWindow): List<Int> {
        val radius = window.size / 2
        return buildList(window.size * window.size) {
            for (y in window.centerY - radius..window.centerY + radius) {
                for (x in window.centerX - radius..window.centerX + radius) add(y * width + x)
            }
        }
    }

    private fun apertureIndices(
        width: Int,
        height: Int,
        centerX: Double,
        centerY: Double,
        radius: Double
    ): List<Int> = buildList {
        val left = floor(centerX - radius).toInt().coerceAtLeast(0)
        val right = ceil(centerX + radius).toInt().coerceAtMost(width - 1)
        val top = floor(centerY - radius).toInt().coerceAtLeast(0)
        val bottom = ceil(centerY + radius).toInt().coerceAtMost(height - 1)
        for (y in top..bottom) for (x in left..right) {
            if ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius) {
                add(y * width + x)
            }
        }
    }

    private fun annulusIndices(
        width: Int,
        height: Int,
        centerX: Double,
        centerY: Double,
        inner: Double,
        outer: Double
    ): List<Int> = buildList {
        val left = floor(centerX - outer).toInt().coerceAtLeast(0)
        val right = ceil(centerX + outer).toInt().coerceAtMost(width - 1)
        val top = floor(centerY - outer).toInt().coerceAtLeast(0)
        val bottom = ceil(centerY + outer).toInt().coerceAtMost(height - 1)
        for (y in top..bottom) for (x in left..right) {
            val squared = (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)
            if (squared in inner * inner..outer * outer) add(y * width + x)
        }
    }

    private fun distanceToSet(
        x: Double,
        y: Double,
        set: BooleanArray,
        width: Int
    ): Double {
        var best = Double.POSITIVE_INFINITY
        set.indices.forEach { index ->
            if (!set[index]) return@forEach
            val distance = hypot(index % width - x, index / width - y)
            if (distance < best) best = distance
        }
        return best.takeIf(Double::isFinite) ?: 0.0
    }

    private fun isNearSet(
        index: Int,
        set: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): Boolean {
        val centerX = index % width
        val centerY = index / width
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val x = centerX + dx
            val y = centerY + dy
            if (x in 0 until width && y in 0 until height && set[y * width + x]) return true
        }
        return false
    }

    private fun maximumIndex(size: Int, score: (Int) -> Double): Int {
        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (index in 0 until size) {
            val value = score(index)
            require(value.isFinite())
            if (value > bestScore) {
                bestScore = value
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun localMean(
        index: Int,
        width: Int,
        height: Int,
        radius: Int,
        value: (Int) -> Double
    ): Double {
        val centerX = index % width
        val centerY = index / width
        val values = mutableListOf<Double>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val x = centerX + dx
            val y = centerY + dy
            if (x in 0 until width && y in 0 until height) values += value(y * width + x)
        }
        return values.averageOrZero()
    }

    private fun boundaryEdgeExcess(
        image: ArgbPixelImage,
        reference: ArgbPixelImage,
        refined: BooleanArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Int
    ): Double {
        val left = (x - radius).coerceAtLeast(0)
        val right = (x + radius).coerceAtMost(width - 1)
        val top = (y - radius).coerceAtLeast(0)
        val bottom = (y + radius).coerceAtMost(height - 1)
        val values = mutableListOf<Double>()
        for (py in top..bottom) for (px in left..right) {
            val index = py * width + px
            fun compare(next: Int) {
                if (refined[index] == refined[next]) return
                val imageJump = luminance(image.pixels[index]) - luminance(image.pixels[next])
                val referenceJump = luminance(reference.pixels[index]) - luminance(reference.pixels[next])
                values += abs(imageJump - referenceJump)
            }
            if (px < right) compare(index + 1)
            if (py < bottom) compare(index + width)
        }
        return values.averageOrZero()
    }

    private fun laplacian(image: ArgbPixelImage, index: Int): Double {
        val x = index % image.width
        val y = index / image.width
        if (x == 0 || y == 0 || x == image.width - 1 || y == image.height - 1) return 0.0
        return luminance(image.pixels[index - 1]) + luminance(image.pixels[index + 1]) +
            luminance(image.pixels[index - image.width]) +
            luminance(image.pixels[index + image.width]) -
            4.0 * luminance(image.pixels[index])
    }

    private fun bandingProxy(image: ArgbPixelImage, alpha: AlphaMask): Double {
        val rowMeans = (0 until image.height).mapNotNull { y ->
            val values = (0 until image.width).filter {
                alpha.alphaAt(it, y) >= 0.5f
            }.map { luminance(image.pixelAt(it, y)) }
            values.takeIf { it.isNotEmpty() }?.average()
        }
        val columnMeans = (0 until image.width).mapNotNull { x ->
            val values = (0 until image.height).filter {
                alpha.alphaAt(x, it) >= 0.5f
            }.map { luminance(image.pixelAt(x, it)) }
            values.takeIf { it.isNotEmpty() }?.average()
        }
        return sqrt(variance(rowMeans) + variance(columnMeans))
    }

    private fun luminance(color: Int): Double =
        (color ushr 16 and 0xFF) * 0.2126 +
            (color ushr 8 and 0xFF) * 0.7152 +
            (color and 0xFF) * 0.0722

    private fun chroma(color: Int): Double {
        val red = color ushr 16 and 0xFF
        val green = color ushr 8 and 0xFF
        val blue = color and 0xFF
        return (maxOf(red, green, blue) - minOf(red, green, blue)).toDouble()
    }

    private fun colorDifference(first: Int, second: Int): Double = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    ).toDouble()

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val position = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        val amount = position - lower
        return sorted[lower] * (1.0 - amount) + sorted[upper] * amount
    }

    private fun safeRatio(value: Double, baseline: Double): Double =
        if (abs(baseline) <= 1e-12) 1.0 else value / baseline

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun requireSameDimensions(
        first: SkyMask,
        second: SkyMask,
        third: SkyMask,
        alpha: AlphaMask
    ) {
        require(first.width == second.width && first.height == second.height)
        require(first.width == third.width && first.height == third.height)
        require(first.width == alpha.width && first.height == alpha.height)
    }

    private fun requireFinite(value: SkyMaskStarStageMetrics) {
        require(listOf(
            value.centroidX, value.centroidY, value.peakLuminance, value.apertureFlux,
            value.localBackground, value.localContrast, value.robustWidth, value.ellipticity,
            value.chromaResidual, value.distanceToMaskBoundary, value.centerAlpha,
            value.minimumApertureAlpha, value.meanApertureAlpha, value.maximumApertureAlpha,
            value.apertureFractionBelowHalfAlpha, value.apertureFractionProtected,
            value.fluxRetentionFromClean, value.peakAttenuationFromClean,
            value.centroidShiftFromClean, value.widthRatioFromClean
        ).all(Double::isFinite))
    }

    private fun requireFinite(value: SkyMaskWindowMetrics) {
        require(listOf(
            value.distanceToBoundary, value.brightRim, value.darkRim, value.haloAsymmetry,
            value.haloScore, value.luminanceJump, value.chromaJump,
            value.firstDerivativeExcess, value.secondDerivativeSpike,
            value.localVarianceMismatch, value.edgeAlignedResidual, value.leakageScore
        ).all(Double::isFinite))
    }

    private fun requireFinite(value: SkyMaskVariantMetrics) {
        require(listOf(
            value.skyMad, value.foregroundMeanChange, value.bandingProxy,
            value.meanHaloScore, value.meanLeakageScore,
            value.strictStarMedianFluxRetention, value.strictStarMaximumCentroidShift
        ).all(Double::isFinite))
    }

    private fun requireFinite(value: SkyMaskPostProcessStageMetrics) {
        require(listOf(
            value.skyMad,
            value.bandingProxy,
            value.boundaryEdgeExcess,
            value.meanAbsoluteChangeFromClean
        ).all(Double::isFinite))
    }

    private fun format(value: Double): String = java.lang.String.format(java.util.Locale.US, "%.6f", value)

    private const val STAR_APERTURE_RADIUS = 4.0
    private const val SMALL_ISLAND_AREA = 64
    private const val ROOT_CAUSE_MIN_REDUCTION = 0.25
}
