package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.PersistentArtifactClassification
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectFootprintPixel
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMaskPolicy
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectRegion
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactMask
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactRegion
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactType
import com.example.astrophoto.processing.jpeg.v2.artifacts.buildConfirmedSensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSensorDefectFilteringTest {
    @Test fun sourceMaskStaysInCameraCoordinatesAndProducesZeroSampleWeight() {
        val mask = onePixelMask(20, 20, 10, 10)
        val plan = plan(mask, listOf(0, 1, 2))
        val coverage = requireNotNull(
            manualSensorDefectCoveragePlan(
                plan,
                ManualAlignedStackMode.AVERAGE,
                20,
                20
            )
        )

        assertTrue(coverage.report.sampleLevelFilteringApplied)
        assertEquals(3L, coverage.report.excludedSampleCount)
        assertEquals(3, coverage.report.affectedOutputPixelCount)
        assertEquals(2, coverage.report.minimumRemainingSampleCount)
        assertFalse(coverage.sourceSampleIsValid(10, 10, AlignmentShift(0, 0), 20, 20))
        assertFalse(coverage.sourceSampleIsValid(9, 10, AlignmentShift(1, 0), 20, 20))
        assertTrue(coverage.sourceSampleIsValid(10, 10, AlignmentShift(1, 0), 20, 20))
    }

    @Test fun averageMedianSigmaAndDarkAverageUseOnlyValidSamples() {
        val colors = intArrayOf(gray(200), gray(20), gray(40))
        val valid = booleanArrayOf(false, true, true)
        val compact = IntArray(colors.size)
        val validCount = compactValidArgbSamples(colors, valid, compact)
        assertEquals(2, validCount)

        val average = intArrayOf(0)
        val accumulator = ArgbAverageAccumulator(
            pixelCount = 1,
            maximumFrameCount = colors.size,
            perPixelWeighting = true
        )
        colors.indices.forEach { index ->
            updateRunningAverageArgbValidSamples(
                accumulator,
                average,
                intArrayOf(colors[index]),
                booleanArrayOf(valid[index]),
                pixelCount = 1
            )
        }
        assertEquals(2, accumulator.validSampleCountAt(0))
        assertEquals(gray(30), average.single())

        assertEquals(
            gray(30),
            medianArgbPixel(
                compact,
                IntArray(3),
                IntArray(3),
                IntArray(3),
                count = validCount
            )
        )
        assertEquals(
            gray(30),
            sigmaClipArgbPixel(
                compact,
                sigmaThreshold = 10.0,
                iterations = 1,
                redValues = IntArray(3),
                greenValues = IntArray(3),
                blueValues = IntArray(3),
                count = validCount
            )
        )

        val calibrated = IntArray(3)
        subtractMasterDarkArgb(
            lightPixels = intArrayOf(gray(220), gray(40), gray(60)),
            darkPixels = intArrayOf(gray(20), gray(20), gray(20)),
            outputPixels = calibrated,
            neutralOffset = 3,
            pixelCount = 3
        )
        val darkAverage = intArrayOf(0)
        val darkAccumulator = ArgbAverageAccumulator(
            pixelCount = 1,
            maximumFrameCount = 3,
            perPixelWeighting = true
        )
        calibrated.indices.forEach { index ->
            updateRunningAverageArgbValidSamples(
                darkAccumulator,
                darkAverage,
                intArrayOf(calibrated[index]),
                booleanArrayOf(valid[index]),
                pixelCount = 1
            )
        }
        assertEquals(
            (channel(calibrated[1]) + channel(calibrated[2])) / 2,
            channel(darkAverage.single())
        )
    }

    @Test fun strictEligibilityRejectsUncertainSkyTracksAndOversizedMasks() {
        val cameraFrames = listOf(0f, 0f, 0f).mapIndexed { index, _ ->
            ArtifactFrameObservation("frame-$index", listOf(star(10f, 10f)))
        }
        val transforms = listOf(
            ReferenceToSourceTransform(0f, 0f),
            ReferenceToSourceTransform(3f, 0f),
            ReferenceToSourceTransform(6f, 0f)
        )
        val confirmedRegion = staticRegion(
            type = StaticArtifactType.HOT_PIXEL,
            confidence = 0.98f
        )
        val confirmed = buildConfirmedSensorDefectMask(
            StaticArtifactMask(20, 20, listOf(confirmedRegion), 0.98f, 0.01f),
            cameraFrames,
            transforms,
            SensorDefectMaskPolicy(maximumMaskedSourceFraction = 0.1f)
        )
        assertTrue(confirmed.enabled)
        assertEquals(1, confirmed.regions.size)
        assertEquals(
            PersistentArtifactClassification.SENSOR_DEFECT,
            confirmed.regions.single().classification
        )
        assertTrue(
            confirmed.regions.single().recurrence >
                confirmed.regions.single().skySpaceSupport * 2
        )

        val uncertain = buildConfirmedSensorDefectMask(
            StaticArtifactMask(
                20,
                20,
                listOf(confirmedRegion.copy(type = StaticArtifactType.REFLECTION_PATCH)),
                0.98f,
                0.01f
            ),
            cameraFrames,
            transforms,
            SensorDefectMaskPolicy(maximumMaskedSourceFraction = 0.1f)
        )
        assertFalse(uncertain.enabled)
        assertTrue(uncertain.regions.isEmpty())

        val coherentSkyFrames = transforms.mapIndexed { index, transform ->
            ArtifactFrameObservation(
                "sky-$index",
                listOf(star(10f + transform.dx, 10f + transform.dy))
            )
        }
        val coherentSky = buildConfirmedSensorDefectMask(
            StaticArtifactMask(20, 20, listOf(confirmedRegion), 0.98f, 0.01f),
            coherentSkyFrames,
            transforms,
            SensorDefectMaskPolicy(maximumMaskedSourceFraction = 0.1f)
        )
        assertFalse(coherentSky.enabled)
        assertTrue(coherentSky.regions.isEmpty())

        val oversized = buildConfirmedSensorDefectMask(
            StaticArtifactMask(20, 20, listOf(confirmedRegion), 0.98f, 0.01f),
            cameraFrames,
            transforms,
            SensorDefectMaskPolicy(maximumMaskedSourceFraction = 0.001f)
        )
        assertFalse(oversized.enabled)
        assertEquals("masked_source_fraction_exceeds_limit", oversized.rejectionReason)
    }

    @Test fun insufficientCoverageUsesFallbackOrDisablesExcessiveMask() {
        val sparseMask = onePixelMask(100, 100, 50, 50)
        val sparseCoverage = requireNotNull(
            manualSensorDefectCoveragePlan(
                plan(sparseMask, listOf(0, 0, 0)),
                ManualAlignedStackMode.AVERAGE,
                100,
                100
            )
        )
        assertTrue(sparseCoverage.report.sampleLevelFilteringApplied)
        assertEquals(1, sparseCoverage.report.insufficientCoveragePixelCount)
        assertEquals(
            "reference_sample_fallback_for_insufficient_coverage",
            sparseCoverage.report.fallbackOrRejectionReason
        )

        val excessiveMask = onePixelMask(10, 10, 5, 5)
        val excessiveCoverage = requireNotNull(
            manualSensorDefectCoveragePlan(
                plan(excessiveMask, listOf(0, 0, 0)),
                ManualAlignedStackMode.AVERAGE,
                10,
                10
            )
        )
        assertFalse(excessiveCoverage.report.sampleLevelFilteringApplied)
        assertEquals(
            "insufficient_coverage_exceeds_limit",
            excessiveCoverage.report.fallbackOrRejectionReason
        )
    }

    @Test fun movingStarSurvivesOneMaskedSensorCrossing() {
        val average = intArrayOf(0)
        val accumulator = ArgbAverageAccumulator(
            pixelCount = 1,
            maximumFrameCount = 3,
            perPixelWeighting = true
        )
        val starSamples = intArrayOf(gray(180), gray(180), gray(180))
        val valid = booleanArrayOf(true, false, true)
        starSamples.indices.forEach { index ->
            updateRunningAverageArgbValidSamples(
                accumulator,
                average,
                intArrayOf(starSamples[index]),
                booleanArrayOf(valid[index]),
                pixelCount = 1
            )
        }
        assertEquals(2, accumulator.validSampleCountAt(0))
        assertEquals(gray(180), average.single())
    }

    @Test fun stage6ConfirmedDefectsLoseTrailsAndAnnotatedStarsRemain() {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        val plan = requireNotNull(
            planManualSequenceAlignment(
                fixture.frames,
                fixture.frames.first().width,
                fixture.frames.first().height
            )
        )
        val mask = requireNotNull(plan.sensorDefectMask)
        assertTrue(
            "mask=${mask.rejectionReason} regions=${mask.regions}",
            mask.enabled
        )
        val modeCoverage = ManualAlignedStackMode.entries.associateWith { mode ->
            requireNotNull(
                manualSensorDefectCoveragePlan(
                    plan,
                    mode,
                    fixture.frames.first().width,
                    fixture.frames.first().height
                )
            )
        }
        modeCoverage.values.forEach { coverage ->
            assertTrue(coverage.report.sampleLevelFilteringApplied)
            assertEquals(mask.regions.size, coverage.report.regionCount)
            assertEquals(mask.maskedPixelCount, coverage.report.maskedSourcePixelCount)
        }
        val defects = fixture.strictSensorDefects
        defects.forEach { defect ->
            assertTrue(
                "${defect.id} missing from production source mask: ${mask.regions}",
                mask.regions.any { region ->
                    distanceSquared(
                        region.sourceX,
                        region.sourceY,
                        defect.x,
                        defect.y
                    ) <= 9f
                }
            )
        }
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.UNCERTAIN
        }.forEach { uncertain ->
            assertFalse(mask.contains(uncertain.x.roundToInt(), uncertain.y.roundToInt()))
        }
        val baseline = integrateFixture(fixture.frames, plan, mask = null)
        val filtered = integrateFixture(fixture.frames, plan, mask = mask)
        val trailMetrics = defects.associate { defect ->
            val path = plan.frames.filter { it.accepted }.map { decision ->
                (defect.x - decision.shift.dx) to (defect.y - decision.shift.dy)
            }.distinctBy { it.first.roundToInt() to it.second.roundToInt() }
            val before = path.map { localContrast(baseline, it.first, it.second) }.average()
            val after = path.map { localContrast(filtered, it.first, it.second) }.average()
            assertTrue("${defect.id}: before=$before after=$after", after < before * 0.65)
            defect.id to (before to after)
        }
        val starMetrics = fixture.strictReferenceStarLabels.associate { star ->
            val before = localPeakContrast(baseline, star.x, star.y)
            val after = localPeakContrast(filtered, star.x, star.y)
            assertTrue("${star.id}: before=$before after=$after", after >= before * 0.80)
            star.id to (before to after)
        }
        val averageCoverage = modeCoverage.getValue(ManualAlignedStackMode.AVERAGE).report
        println(
            "stage6SampleMask regions=${mask.regions.joinToString { region ->
                "${region.stableRegionId}@${region.sourceX},${region.sourceY}:" +
                    "${region.footprintPixels.size}px:" +
                    "camera=${region.recurrence}/${region.totalFrameCount}:" +
                    "sky=${region.skySpaceSupport}/${region.totalFrameCount}:" +
                    "confidence=${region.confidence}"
            }} maskedPixels=${mask.maskedPixelCount} " +
                "maskedFraction=${mask.maskedSourceFraction} " +
                "excludedSamples=${averageCoverage.excludedSampleCount} " +
                "affectedPixels=${averageCoverage.affectedOutputPixelCount} " +
                "remaining=${averageCoverage.minimumRemainingSampleCount}/" +
                "${averageCoverage.medianRemainingSampleCount}/" +
                "${averageCoverage.maximumRemainingSampleCount} " +
                "insufficient=${averageCoverage.insufficientCoveragePixelCount} " +
                "accepted=${plan.frames.filter { it.accepted }.map { it.originalFrameNumber }} " +
                "rejected=${plan.frames.filterNot { it.accepted }.map { it.originalFrameNumber }} " +
                "trails=$trailMetrics stars=$starMetrics"
        )
    }

    private fun integrateFixture(
        frames: List<ArgbPixelImage>,
        plan: ManualSequenceAlignmentPlan,
        mask: SensorDefectMask?
    ): ArgbPixelImage {
        val width = frames.first().width
        val height = frames.first().height
        val red = IntArray(width * height)
        val green = IntArray(width * height)
        val blue = IntArray(width * height)
        val counts = ShortArray(width * height)
        plan.frames.filter { it.accepted }.forEach { decision ->
            val frame = frames[decision.originalFrameIndex]
            for (outputY in 0 until height) for (outputX in 0 until width) {
                val sourceX = outputX + decision.shift.dx
                val sourceY = outputY + decision.shift.dy
                if (sourceX !in 0 until width || sourceY !in 0 until height) continue
                if (mask?.contains(sourceX, sourceY) == true) continue
                val outputIndex = outputY * width + outputX
                val color = frame.pixels[sourceY * width + sourceX]
                red[outputIndex] += color ushr 16 and 0xFF
                green[outputIndex] += color ushr 8 and 0xFF
                blue[outputIndex] += color and 0xFF
                counts[outputIndex]++
            }
        }
        return ArgbPixelImage(
            width,
            height,
            IntArray(width * height) { index ->
                val count = counts[index].toInt()
                if (count == 0) {
                    frames[plan.referenceFrameIndex].pixels[index]
                } else {
                    0xFF000000.toInt() or
                        ((red[index] / count) shl 16) or
                        ((green[index] / count) shl 8) or
                        (blue[index] / count)
                }
            }
        )
    }

    private fun localPeakContrast(image: ArgbPixelImage, x: Float, y: Float): Double {
        var best = Double.NEGATIVE_INFINITY
        for (dy in -2..2) for (dx in -2..2) {
            best = maxOf(best, localContrast(image, x + dx, y + dy))
        }
        return best
    }

    private fun localPeakContrast(image: ArgbPixelImage, x: Double, y: Double): Double =
        localPeakContrast(image, x.toFloat(), y.toFloat())

    private fun localContrast(image: ArgbPixelImage, x: Float, y: Float): Double {
        val centerX = x.roundToInt().coerceIn(3, image.width - 4)
        val centerY = y.roundToInt().coerceIn(3, image.height - 4)
        val center = luminance(image.pixels[centerY * image.width + centerX])
        val ring = buildList {
            for (offset in -3..3) {
                add(luminance(image.pixels[(centerY + offset) * image.width + centerX - 3]))
                add(luminance(image.pixels[(centerY + offset) * image.width + centerX + 3]))
            }
            for (offset in -2..2) {
                add(luminance(image.pixels[(centerY - 3) * image.width + centerX + offset]))
                add(luminance(image.pixels[(centerY + 3) * image.width + centerX + offset]))
            }
        }.sorted()
        return center - ring[ring.size / 2]
    }

    private fun localContrast(image: ArgbPixelImage, x: Double, y: Double): Double =
        localContrast(image, x.toFloat(), y.toFloat())

    private fun luminance(color: Int): Double =
        (color ushr 16 and 0xFF) * 0.299 +
            (color ushr 8 and 0xFF) * 0.587 +
            (color and 0xFF) * 0.114

    private fun plan(mask: SensorDefectMask, shifts: List<Int>) =
        ManualSequenceAlignmentPlan(
            referenceFrameIndex = 0,
            frames = shifts.mapIndexed { index, dx ->
                ManualSequenceFrameDecision(
                    originalFrameIndex = index,
                    frameId = "frame-$index",
                    accepted = true,
                    rejectionReason = null,
                    shift = AlignmentShift(dx, 0),
                    registrationResidualPx = 0.1f,
                    registrationConfidence = 0.9f
                )
            },
            modelScore = 0.9f,
            modelResidualPx = 0.1f,
            stationaryArtifactCount = 1,
            sensorDefectMask = mask
        )

    private fun onePixelMask(width: Int, height: Int, x: Int, y: Int): SensorDefectMask {
        val pixel = SensorDefectFootprintPixel(x, y)
        return SensorDefectMask(
            width,
            height,
            listOf(
                SensorDefectRegion(
                    stableRegionId = "sensor-test-x$x-y$y",
                    sourceX = x.toFloat(),
                    sourceY = y.toFloat(),
                    sourceRadiusX = 0.5f,
                    sourceRadiusY = 0.5f,
                    footprintPixels = listOf(pixel),
                    recurrence = 3,
                    totalFrameCount = 3,
                    skySpaceSupport = 1,
                    confidence = 1f,
                    classification = PersistentArtifactClassification.SENSOR_DEFECT,
                    classificationReason = "test_confirmed_sensor_defect"
                )
            ),
            enabled = true
        )
    }

    private fun staticRegion(
        type: StaticArtifactType,
        confidence: Float
    ) = StaticArtifactRegion(
        x = 10f,
        y = 10f,
        radius = 1f,
        type = type,
        confidence = confidence,
        reason = "test",
        recurrence = 3,
        frameCount = 3
    )

    private fun star(x: Float, y: Float) = DetectedStar(
        x = x,
        y = y,
        flux = 200f,
        localBackground = 10f,
        localContrast = 100f,
        width = 1f,
        ellipticity = 0f,
        confidence = 1f
    )

    private fun gray(value: Int): Int =
        0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

    private fun channel(color: Int): Int = color and 0xFF

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Double, y2: Double): Double =
        distanceSquared(x1.toDouble(), y1.toDouble(), x2, y2)

    private fun distanceSquared(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun fixtureDirectory(): File {
        val resource = requireNotNull(
            requireNotNull(javaClass.classLoader)
                .getResource("jpeg-stage6/urban-window-30/manifest.properties")
        )
        return requireNotNull(File(resource.toURI()).parentFile)
    }
}
