package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayLocalTrailCleanupDiagnosticsTest {
    @Test
    fun generatesFourCandidatesWithoutMutatingBaselineAndWritesDiagnostics() {
        val baseline = syntheticSky()
        val snapshot = baseline.pixels.copyOf()
        val annotation = horizontalTrail("manual-01", 24, 58, 32)
        paintTrail(baseline, annotation, 200)
        val controlStar = detectedStar(92f, 70f)
        val output = Files.createTempDirectory("local-trail-four-candidates")
        try {
            val bundle = run(baseline, listOf(annotation), listOf(controlStar), output)

            assertEquals(ReplayTrailRepairStrength.entries, bundle.candidates.map { it.strength })
            assertTrue(bundle.baselineUnchanged)
            assertEquals(bundle.baselinePixelHashBefore, bundle.baselinePixelHashAfter)
            assertArrayEquals(snapshot.also {
                paintTrail(ArgbPixelImage(baseline.width, baseline.height, it), annotation, 200)
            }, baseline.pixels)

            val repairMask = bundle.trailMaskIndices.getValue(annotation.id).toSet()
            assertTrue(repairMask.isNotEmpty())
            assertTrue(bundle.leftBackgroundSampleIndices.getValue(annotation.id).isNotEmpty())
            assertTrue(bundle.rightBackgroundSampleIndices.getValue(annotation.id).isNotEmpty())
            bundle.candidates.forEach { candidate ->
                baseline.pixels.indices.filterNot(repairMask::contains).forEach { index ->
                    assertEquals("outside mask ${candidate.strength} at $index", baseline.pixels[index], candidate.image.pixels[index])
                }
                candidate.image.pixels.indices.forEach { index ->
                    assertEquals(
                        "alpha ${candidate.strength} at $index",
                        baseline.pixels[index] ushr 24,
                        candidate.image.pixels[index] ushr 24
                    )
                }
                val root = output.resolve(candidate.strength.fileLabel)
                assertTrue(Files.isRegularFile(root.resolve("candidate.png")))
                assertTrue(Files.isRegularFile(root.resolve("difference-x8.png")))
                assertTrue(Files.isRegularFile(root.resolve("applied-repair-mask.png")))
                assertTrue(Files.isRegularFile(root.resolve("trails/manual-01-before.png")))
                assertTrue(Files.isRegularFile(root.resolve("trails/manual-01-after.png")))
                assertTrue(Files.isRegularFile(root.resolve("trails/manual-01-difference-x8.png")))
                assertTrue(Files.isRegularFile(root.resolve("control-stars/star-00-before.png")))
                assertTrue(Files.isRegularFile(root.resolve("control-stars/star-00-after.png")))
            }
            assertTrue(Files.isRegularFile(output.resolve("recovered-stars-baseline.png")))
            assertTrue(Files.isRegularFile(output.resolve("frozen-threshold-manifest.json")))
            assertTrue(Files.isRegularFile(output.resolve("report.txt")))
            assertTrue(Files.isRegularFile(output.resolve("per-trail-results.tsv")))
            assertTrue(Files.isRegularFile(output.resolve("masks/manual-01-repair-mask.png")))
            assertTrue(Files.isRegularFile(output.resolve("masks/manual-01-background-left-right.png")))
            val reviewRoot = output.resolve("manual-review-luminance-080")
            assertTrue(Files.isRegularFile(reviewRoot.resolve("baseline.png")))
            assertTrue(Files.isRegularFile(reviewRoot.resolve("manual-review-candidate.png")))
            assertTrue(Files.isRegularFile(reviewRoot.resolve("manual-review-L80-phone-open.png")))
            assertTrue(Files.isRegularFile(reviewRoot.resolve("baseline-vs-candidate.png")))
            assertTrue(Files.isRegularFile(reviewRoot.resolve("difference-x32.png")))
            assertTrue(Files.isRegularFile(reviewRoot.resolve("manual-review-report.txt")))
            assertTrue(
                Files.isRegularFile(
                    reviewRoot.resolve("control-crops/trails/manual-01-before.png")
                )
            )
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun manualReviewAppliesRequestedStrictRepairWithoutChangingFormalCandidate() {
        val baseline = syntheticSky()
        val annotation = horizontalTrail("manual-02", 24, 72, 48)
        paintTrail(baseline, annotation, 200)
        val output = Files.createTempDirectory("local-trail-manual-review")
        try {
            val bundle = run(baseline, listOf(annotation), emptyList(), output)
            val formal = bundle.candidate(ReplayTrailRepairStrength.LUMINANCE_80)
            val formalSnapshot = formal.image.pixels.copyOf()
            val result = bundle.manualReview.trailResults.single()
            val mask = bundle.trailMaskIndices.getValue(annotation.id).toSet()

            assertEquals(
                ReplayManualReviewDecision.APPLIED_STRICT_STAGE1_RESULT,
                result.decision
            )
            assertTrue(result.applied)
            assertTrue(bundle.manualReview.changedPixelCount > 0)
            assertEquals(0, bundle.manualReview.outsideMaskMaximumDifference)
            assertEquals(0, bundle.manualReview.foregroundMaximumDifference)
            assertTrue(bundle.manualReview.validationPassed)
            assertArrayEquals(formalSnapshot, formal.image.pixels)
            baseline.pixels.indices.filterNot(mask::contains).forEach { index ->
                assertEquals(baseline.pixels[index], bundle.manualReview.image.pixels[index])
            }
            assertEquals(
                bundle.manualReview.changedPixelCount,
                baseline.pixels.indices.count { index ->
                    baseline.pixels[index] != bundle.manualReview.image.pixels[index]
                }
            )
            val root = output.resolve("manual-review-luminance-080")
            assertTrue(Files.isRegularFile(root.resolve("repair-crops/manual-02-before.png")))
            assertTrue(Files.isRegularFile(root.resolve("repair-crops/manual-02-after.png")))
            assertTrue(
                Files.isRegularFile(root.resolve("repair-crops/manual-02-difference-x32.png"))
            )
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun manualReviewOverrideAllowsOnlyEnergyFailureWithSafeBoundaries() {
        val boundary = safeBoundary()
        val energyOnly = ReplayTrailRepairMetric(
            trailId = "manual-03",
            status = ReplayTrailRepairStatus.REJECTED_TRAIL_ENERGY,
            applied = false,
            reasons = listOf("luminance_trail_energy_reduction_below_80_percent"),
            baselineLuminanceEnergy = 1.0,
            proposedLuminanceEnergy = 0.22,
            luminanceEnergyRatio = 0.22,
            baselineChromaEnergy = 0.0,
            proposedChromaEnergy = 0.0,
            chromaEnergyRatio = 0.0,
            baselineCombinedEnergy = 1.0,
            proposedCombinedEnergy = 0.22,
            combinedEnergyRatio = 0.22,
            luminanceSupportPixels = 10,
            chromaSupportPixels = 0,
            proposedChangedPixels = 10,
            boundary = boundary
        )

        assertTrue(ReplayManualReviewPolicy.allowsEnergyOverride(energyOnly))
        assertFalse(
            ReplayManualReviewPolicy.allowsEnergyOverride(
                energyOnly.copy(
                    reasons = energyOnly.reasons + "detectable_boundary_seam",
                    boundary = boundary.copy(seamDetected = true)
                )
            )
        )
        assertFalse(
            ReplayManualReviewPolicy.allowsEnergyOverride(
                energyOnly.copy(status = ReplayTrailRepairStatus.REJECTED_GAMUT)
            )
        )
    }

    @Test
    fun manual06OutsideSafeRegionIsSkippedWithoutBlockingEligibleRepair() {
        val baseline = syntheticSky()
        val good = horizontalTrail("manual-01", 24, 58, 32)
        val outside = horizontalTrail("manual-06", 1, 22, 82)
        paintTrail(baseline, good, 200)
        paintTrail(baseline, outside, 200)
        val output = Files.createTempDirectory("local-trail-skipped-manual-06")
        try {
            val bundle = run(baseline, listOf(good, outside), emptyList(), output)
            val outsideMask = bundle.trailMaskIndices.getValue(outside.id)

            bundle.candidates.forEach { candidate ->
                val skipped = candidate.metric(outside.id)
                assertEquals(ReplayTrailRepairStatus.SKIPPED_OUTSIDE_SAFE_REGION, skipped.status)
                assertFalse(skipped.applied)
                outsideMask.forEach { index ->
                    assertEquals("manual-06 changed by ${candidate.strength}", baseline.pixels[index], candidate.image.pixels[index])
                }
            }

            val eighty = bundle.candidate(ReplayTrailRepairStrength.LUMINANCE_80)
            assertEquals(ReplayTrailRepairStatus.ACCEPTED, eighty.metric(good.id).status)
            assertTrue(eighty.metric(good.id).applied)
            assertEquals(ReplayTrailRepairStrength.LUMINANCE_80, bundle.preferredStrength)
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun validTrailRepairSurvivesIndependentBackgroundRejection() {
        val baseline = syntheticSky()
        val good = horizontalTrail("good", 18, 50, 28)
        val bad = horizontalTrail("bad-background", 72, 106, 67)
        paintTrail(baseline, good, 200)
        paintTrail(baseline, bad, 200)
        paintHorizontalBand(baseline, 70, 108, 61, 63, 26)
        paintHorizontalBand(baseline, 70, 108, 71, 73, 34)
        val output = Files.createTempDirectory("local-trail-independent-rejection")
        try {
            val bundle = run(baseline, listOf(good, bad), emptyList(), output)
            val eighty = bundle.candidate(ReplayTrailRepairStrength.LUMINANCE_80)
            val goodMetric = eighty.metric(good.id)
            val badMetric = eighty.metric(bad.id)

            assertEquals(ReplayTrailRepairStatus.ACCEPTED, goodMetric.status)
            assertTrue(goodMetric.applied)
            assertTrue(
                "unexpected conservative rejection: ${badMetric.status}",
                badMetric.status in setOf(
                    ReplayTrailRepairStatus.REJECTED_BACKGROUND,
                    ReplayTrailRepairStatus.REJECTED_COMPACT_SOURCE
                )
            )
            assertFalse(badMetric.applied)
            bundle.trailMaskIndices.getValue(bad.id).forEach { index ->
                assertEquals("rejected trail changed", baseline.pixels[index], eighty.image.pixels[index])
            }
            assertTrue(bundle.trailMaskIndices.getValue(good.id).any { index ->
                eighty.image.pixels[index] != baseline.pixels[index]
            })
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun masksSamplesAndCandidatesAreBaselineDeterministicAndEnergyIsMonotonic() {
        val baseline = syntheticSky()
        val annotation = horizontalTrail("manual-01", 24, 58, 32)
        paintTrail(baseline, annotation, 200)
        val firstRoot = Files.createTempDirectory("local-trail-determinism-a")
        val secondRoot = Files.createTempDirectory("local-trail-determinism-b")
        try {
            val first = run(baseline, listOf(annotation), emptyList(), firstRoot)
            val second = run(baseline, listOf(annotation), emptyList(), secondRoot)

            assertTrue(first.safeSkyMask.contentEquals(second.safeSkyMask))
            assertArrayEquals(
                first.trailMaskIndices.getValue(annotation.id),
                second.trailMaskIndices.getValue(annotation.id)
            )
            assertArrayEquals(
                first.leftBackgroundSampleIndices.getValue(annotation.id),
                second.leftBackgroundSampleIndices.getValue(annotation.id)
            )
            assertArrayEquals(
                first.rightBackgroundSampleIndices.getValue(annotation.id),
                second.rightBackgroundSampleIndices.getValue(annotation.id)
            )
            first.candidates.zip(second.candidates).forEach { (one, two) ->
                assertEquals(one.strength, two.strength)
                assertArrayEquals(one.image.pixels, two.image.pixels)
            }

            val metrics = ReplayTrailRepairStrength.entries.map { strength ->
                first.candidate(strength).metric(annotation.id)
            }
            metrics.zipWithNext().forEach { (less, more) ->
                assertTrue(
                    "${less.status} ${less.proposedLuminanceEnergy} > ${more.status} ${more.proposedLuminanceEnergy}",
                    less.proposedLuminanceEnergy + 1e-12 >= more.proposedLuminanceEnergy
                )
            }
            assertEquals(ReplayTrailRepairStatus.REJECTED_TRAIL_ENERGY, metrics[0].status)
            assertEquals(ReplayTrailRepairStatus.REJECTED_TRAIL_ENERGY, metrics[1].status)
            assertEquals(ReplayTrailRepairStatus.ACCEPTED, metrics[2].status)
            assertTrue(metrics[2].luminanceEnergyRatio <= ReplayLocalCleanupThresholds.MAX_TRAIL_ENERGY_RATIO)
            assertEquals(ReplayLocalTrailRepairStatusOrNull.ACCEPTED, statusOrNull(metrics[3]))
            assertEquals(ReplayTrailRepairStrength.LUMINANCE_80, first.preferredStrength)
            assertTrue(first.candidate(ReplayTrailRepairStrength.LUMINANCE_80).preferred)
            assertFalse(first.candidate(ReplayTrailRepairStrength.FULL_RECONSTRUCTION).preferred)
        } finally {
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun confirmedStarVetoRejectsTrailAndKeepsEveryPixelIdentical() {
        val baseline = syntheticSky()
        val annotation = horizontalTrail("star-overlap", 42, 82, 48)
        paintTrail(baseline, annotation, 200)
        val star = detectedStar(62f, 48f)
        val output = Files.createTempDirectory("local-trail-star-veto")
        try {
            val bundle = run(baseline, listOf(annotation), listOf(star), output)

            assertTrue(bundle.confirmedStarVeto.any { it })
            bundle.candidates.forEach { candidate ->
                val metric = candidate.metric(annotation.id)
                assertEquals(ReplayTrailRepairStatus.REJECTED_CONFIRMED_STAR, metric.status)
                assertFalse(metric.applied)
                assertArrayEquals(baseline.pixels, candidate.image.pixels)
            }
            assertEquals(null, bundle.preferredStrength)
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun backgroundSamplesDoNotLeakPastPolylineEndpoints() {
        val baseline = syntheticSky()
        val startX = 30
        val endX = 70
        val annotation = horizontalTrail("endpoint-bounds", startX, endX, 48)
        val output = Files.createTempDirectory("local-trail-endpoint-bounds")
        try {
            val bundle = run(baseline, listOf(annotation), emptyList(), output)
            val samples =
                bundle.leftBackgroundSampleIndices.getValue(annotation.id).asList() +
                    bundle.rightBackgroundSampleIndices.getValue(annotation.id).asList()

            assertTrue(samples.isNotEmpty())
            samples.forEach { index ->
                val x = index % baseline.width
                assertTrue(
                    "background sample leaked past endpoint at x=$x",
                    x >= startX - ReplayLocalCleanupThresholds.BACKGROUND_LONGITUDINAL_RADIUS &&
                        x <= endX + ReplayLocalCleanupThresholds.BACKGROUND_LONGITUDINAL_RADIUS
                )
            }
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun weakCompactSourceInsideAnnotationRemainsPixelIdentical() {
        val baseline = syntheticSky()
        val annotation = horizontalTrail("weak-compact-only", 30, 78, 48)
        val centerX = 54
        val centerY = 48
        paintWeakCompactSource(baseline, centerX, centerY)
        val snapshot = baseline.pixels.copyOf()
        val output = Files.createTempDirectory("local-trail-weak-compact")
        try {
            val bundle = run(baseline, listOf(annotation), emptyList(), output)

            bundle.candidates.forEach { candidate ->
                assertArrayEquals(
                    "weak compact source changed by ${candidate.strength}",
                    snapshot,
                    candidate.image.pixels
                )
                assertFalse(candidate.metric(annotation.id).applied)
                assertTrue(
                    candidate.metric(annotation.id).reasons.contains(
                        "no_baseline_elongated_signal_support"
                    ) || candidate.metric(annotation.id).status ==
                        ReplayTrailRepairStatus.REJECTED_COMPACT_SOURCE
                )
            }
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun unrelatedCompactChromaNoiseDoesNotBlockLuminanceOnlyTrailRepair() {
        val baseline = syntheticSky()
        val annotation = horizontalTrail("luminance-with-chroma-noise", 24, 82, 48)
        paintTrail(baseline, annotation, 200)
        listOf(27, 33, 39, 45, 51, 57, 63, 69, 75, 81).forEachIndexed { offset, x ->
            val value = if (offset % 2 == 0) rgb(201, 199, 200) else rgb(199, 201, 200)
            baseline.pixels[48 * baseline.width + x] = value
        }
        val output = Files.createTempDirectory("local-trail-domain-support")
        try {
            val bundle = run(baseline, listOf(annotation), emptyList(), output)
            val eighty = bundle.candidate(ReplayTrailRepairStrength.LUMINANCE_80)
            val metric = eighty.metric(annotation.id)

            assertTrue(metric.luminanceSupportPixels > 0)
            assertEquals(0, metric.chromaSupportPixels)
            assertEquals(ReplayTrailRepairStatus.ACCEPTED, metric.status)
            assertTrue(metric.applied)
            assertTrue(metric.luminanceEnergyRatio <= ReplayLocalCleanupThresholds.MAX_TRAIL_ENERGY_RATIO)
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    private fun run(
        baseline: ArgbPixelImage,
        annotations: List<ReplayManualTrailAnnotation>,
        stars: List<DetectedStar>,
        output: Path
    ): ReplayLocalTrailCleanupBundle = ReplayLocalTrailCleanupDiagnosticRunner().run(
        baseline = baseline,
        reference = baseline,
        effectiveSkyAlpha = AlphaMask.full(baseline.width, baseline.height),
        annotations = annotations,
        confirmedStars = stars,
        outputRoot = output
    )

    private fun syntheticSky(width: Int = 128, height: Int = 96): ArgbPixelImage =
        ArgbPixelImage(width, height, IntArray(width * height) { rgb(30, 30, 30) })

    private fun horizontalTrail(
        id: String,
        startX: Int,
        endX: Int,
        y: Int
    ): ReplayManualTrailAnnotation = ReplayManualTrailAnnotation(
        id,
        listOf(ReplayPoint(startX.toDouble(), y.toDouble()), ReplayPoint(endX.toDouble(), y.toDouble())),
        "synthetic horizontal trail"
    )

    private fun paintTrail(image: ArgbPixelImage, annotation: ReplayManualTrailAnnotation, value: Int) {
        val first = annotation.centerline.first()
        val last = annotation.centerline.last()
        require(first.y == last.y)
        for (x in first.x.toInt()..last.x.toInt()) {
            image.pixels[first.y.toInt() * image.width + x] = rgb(value, value, value)
        }
    }

    private fun paintHorizontalBand(
        image: ArgbPixelImage,
        startX: Int,
        endX: Int,
        startY: Int,
        endY: Int,
        value: Int
    ) {
        for (y in startY..endY) for (x in startX..endX) {
            image.pixels[y * image.width + x] = rgb(value, value, value)
        }
    }

    private fun paintWeakCompactSource(image: ArgbPixelImage, centerX: Int, centerY: Int) {
        val values = arrayOf(
            intArrayOf(31, 33, 31),
            intArrayOf(33, 38, 33),
            intArrayOf(31, 33, 31)
        )
        values.forEachIndexed { dy, row ->
            row.forEachIndexed { dx, value ->
                image.pixels[(centerY + dy - 1) * image.width + centerX + dx - 1] =
                    rgb(value, value, value)
            }
        }
    }

    private fun detectedStar(x: Float, y: Float): DetectedStar =
        DetectedStar(x, y, 1f, 0.1f, 0.5f, 1.5f, 0.1f, 1f)

    private fun safeBoundary(): ReplayBoundaryMetrics = ReplayBoundaryMetrics(
        innerLuminanceP95 = 0.0,
        outerLuminanceP95 = 0.0,
        innerChromaP95 = 0.0,
        outerChromaP95 = 0.0,
        luminanceGradientDeltaP95 = 0.0,
        chromaGradientDeltaP95 = 0.0,
        nearbyLuminanceNoise = 0.0,
        nearbyChromaNoise = 0.0,
        haloOvershoot = 0.0,
        baselineCoreTextureMad = 0.0,
        candidateCoreTextureMad = 0.0,
        nearbyTextureMad = 0.0,
        smoothnessMeasurable = true,
        seamDetected = false,
        haloDetected = false,
        smoothStripeDetected = false
    )

    private fun ReplayLocalTrailCleanupBundle.candidate(
        strength: ReplayTrailRepairStrength
    ): ReplayLocalCleanupCandidate = candidates.single { it.strength == strength }

    private fun ReplayLocalCleanupCandidate.metric(trailId: String): ReplayTrailRepairMetric =
        trailMetrics.single { it.trailId == trailId }

    private enum class ReplayLocalTrailRepairStatusOrNull { ACCEPTED, NOT_ACCEPTED }

    private fun statusOrNull(metric: ReplayTrailRepairMetric): ReplayLocalTrailRepairStatusOrNull =
        if (metric.status == ReplayTrailRepairStatus.ACCEPTED) {
            ReplayLocalTrailRepairStatusOrNull.ACCEPTED
        } else {
            ReplayLocalTrailRepairStatusOrNull.NOT_ACCEPTED
        }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}
