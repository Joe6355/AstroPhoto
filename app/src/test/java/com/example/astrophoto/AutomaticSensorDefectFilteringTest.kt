package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.artifacts.AutomaticSensorDefectMaskResult
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.PersistentArtifactClassification
import com.example.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorCandidateObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectFootprintPixel
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectRegion
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.TemporalPixelConsistency
import com.example.astrophoto.processing.jpeg.v2.artifacts.buildAutomaticSensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.artifacts.buildConfirmedSensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.composition.MaskFeathering
import com.example.astrophoto.processing.jpeg.v2.composition.AlphaMaskPixelSource
import com.example.astrophoto.processing.jpeg.v2.composition.ReferenceStarSignalPreserver
import com.example.astrophoto.processing.jpeg.v2.composition.SkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.example.astrophoto.processing.jpeg.v2.integration.TileProcessingCoordinator
import com.example.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.example.astrophoto.processing.jpeg.v2.masking.ForegroundProtectionMask
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskRefiner
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidate
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.example.astrophoto.processing.jpeg.v2.output.ArgbArrayPngSource
import com.example.astrophoto.processing.jpeg.v2.output.PngStreamEncoder
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.quality.AstroResultQualityGate
import com.example.astrophoto.processing.jpeg.v2.quality.CleanStackValidationEvidence
import com.example.astrophoto.processing.jpeg.v2.quality.CoverageUniformityValidator
import com.example.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.example.astrophoto.processing.jpeg.v2.quality.ResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.quality.ResultSelectionPolicy
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSensorDefectFilteringTest {
    @Test
    fun confirmedDefectIsNotRestoredByReferenceStarPreservation() {
        val width = 23
        val height = 9
        val stacked = ArgbPixelImage(width, height, IntArray(width * height) { gray(20) })
        val reference = ArgbPixelImage(width, height, IntArray(width * height) { gray(220) })
        val mask = onePixelMask(width, height, 4, 4)
        val result = ReferenceStarSignalPreserver().preserve(
            stacked,
            reference,
            listOf(
                DetectedStar(
                    x = 4f,
                    y = 4f,
                    flux = 200f,
                    localBackground = 20f,
                    localContrast = 180f,
                    width = 1f,
                    ellipticity = 0f,
                    confidence = 1f
                ),
                DetectedStar(
                    x = 17f,
                    y = 4f,
                    flux = 200f,
                    localBackground = 20f,
                    localContrast = 180f,
                    width = 1f,
                    ellipticity = 0f,
                    confidence = 1f
                )
            ),
            mask
        )

        assertEquals(gray(20), result.image.pixelAt(4, 4))
        assertEquals(gray(220), result.image.pixelAt(3, 4))
        assertEquals(gray(220), result.image.pixelAt(17, 4))
        assertTrue(result.maskedReferenceSamplesSkipped > 0L)
        assertEquals(1, result.affectedOutputPixelCount)
    }

    @Test
    fun affectedOutputAllowsStarCoreButKeepsProtectionRingFiltered() {
        val width = 23
        val height = 15
        val stacked = ArgbPixelImage(width, height, IntArray(width * height) { gray(20) })
        val reference = ArgbPixelImage(width, height, IntArray(width * height) { gray(220) })
        val affected = FloatArray(width * height).also { it[7 * width + 10] = 1f }
        val result = ReferenceStarSignalPreserver().preserve(
            stacked,
            reference,
            listOf(
                DetectedStar(
                    x = 11f,
                    y = 7f,
                    flux = 200f,
                    localBackground = 20f,
                    localContrast = 180f,
                    width = 1f,
                    ellipticity = 0f,
                    confidence = 1f
                )
            ),
            sensorDefectMask = SensorDefectMask.empty(width, height, "test"),
            sensorDefectAffectedOutput = AlphaMaskPixelSource(
                AlphaMask(width, height, affected)
            )
        )

        assertEquals(gray(220), result.image.pixelAt(11, 7))
        assertEquals(gray(220), result.image.pixelAt(8, 7))
        assertEquals(gray(20), result.image.pixelAt(7, 7))
        assertTrue(result.maskedReferenceSamplesSkipped > 0L)
        assertTrue(result.affectedOutputPixelCount > 0)
    }

    @Test
    fun affectedOutputCoreRestorationRetainsReferenceSource() {
        val width = 41
        val height = 41
        val centerX = 20f
        val centerY = 20f
        val referencePixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val distanceSquared =
                (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)
            val signal = (190f * kotlin.math.exp(-distanceSquared / 4.5f)).roundToInt()
            gray((20 + signal).coerceAtMost(255))
        }
        val reference = ArgbPixelImage(width, height, referencePixels)
        val stacked = ArgbPixelImage(width, height, IntArray(width * height) { gray(20) })
        val star = DetectedStar(
            x = centerX,
            y = centerY,
            flux = 500f,
            localBackground = 20f,
            localContrast = 190f,
            width = 2f,
            ellipticity = 0f,
            confidence = 1f
        )
        val result = ReferenceStarSignalPreserver().preserve(
            stacked,
            reference,
            listOf(star),
            sensorDefectMask = SensorDefectMask.empty(width, height, "test"),
            sensorDefectAffectedOutput = AlphaMaskPixelSource(
                AlphaMask(width, height, FloatArray(width * height) { 1f })
            )
        )
        val retention = ReferenceStarRetentionValidator().validate(
            reference,
            result.image,
            listOf(star)
        )

        assertEquals(1, retention.metrics.retainedReferenceStarCount)
        assertTrue(retention.sources.single().retained)
        assertTrue(result.maskedReferenceSamplesSkipped > 0L)
    }

    @Test
    fun compositionCannotReintroduceConfirmedFilteredReferencePixel() {
        val stacked = ArgbPixelImage(2, 1, intArrayOf(gray(20), gray(20)))
        val reference = ArgbPixelImage(2, 1, intArrayOf(gray(220), gray(220)))
        val sky = AlphaMask(2, 1, floatArrayOf(0.25f, 0.25f))
        val affected = AlphaMask(2, 1, floatArrayOf(1f, 0f))
        val result = SkyForegroundComposer().compose(
            stackedSky = stacked,
            reference = reference,
            featheredSkyMask = sky,
            sensorDefectAffectedOutput = affected,
            sensorDefectMask = onePixelMask(2, 1, 0, 0)
        )

        assertEquals(gray(20), result.image.pixelAt(0, 0))
        assertTrue(result.image.pixelAt(1, 0) != gray(20))
        assertEquals(1f, result.effectiveSkyAlpha.alphaAt(0, 0), 0f)
        assertEquals(1L, result.diagnostics.maskedReferenceSamplesSkipped)
        assertEquals(1, result.diagnostics.sensorDefectAffectedOutputPixels)
        assertEquals(0.25f, result.diagnostics.meanOriginalAlphaAtProtectedPixels, 0f)
    }

    @Test
    fun automaticIntegratorAcceptsSourceSpaceSensorMask() {
        val source = Files.readString(
            Path.of(
                "src/main/java/com/example/astrophoto/processing/jpeg/v2/integration/" +
                    "LinearWeightedIntegrator.kt"
            )
        )

        assertTrue(
            "Automatic LinearWeightedIntegrator has no source-space sensor mask entry point",
            source.contains("sensorDefectMask")
        )
    }

    @Test
    fun fractionalSampleIsRejectedWhenAnyNonZeroBilinearTapIsMasked() = runBlocking {
        val mask = onePixelMask(2, 1, 1, 0)
        val filtered = integrate(
            sources = listOf(
                intArrayOf(gray(20), gray(220)),
                intArrayOf(gray(20), gray(20))
            ),
            registrations = listOf(registration(dx = 0.5f), registration()),
            weights = listOf(1f, 1f),
            mask = mask
        )

        assertEquals(gray(20), filtered.pixels[0])
        assertEquals(1L, filtered.diagnostics.excludedSampleCount)
        assertEquals(1, filtered.diagnostics.affectedOutputPixelCount)
        assertEquals(0.5f, filtered.diagnostics.minimumValidWeightRatio, 0.0001f)
    }

    @Test
    fun exactIntegerSampleDoesNotRejectZeroWeightNeighborTap() = runBlocking {
        val mask = onePixelMask(2, 1, 1, 0)
        val filtered = integrate(
            sources = listOf(intArrayOf(gray(40), gray(220))),
            registrations = listOf(registration()),
            weights = listOf(1f),
            mask = mask
        )

        assertEquals(gray(40), filtered.pixels[0])
        assertEquals(0L, filtered.diagnostics.excludedSampleCount)
    }

    @Test
    fun rejectedSampleAddsNeitherPixelsNorWeightAndNormalizationUsesValidWeight() = runBlocking {
        val mask = onePixelMask(2, 1, 1, 0)
        val filtered = integrate(
            sources = listOf(
                intArrayOf(gray(10), gray(250)),
                intArrayOf(gray(60), gray(60))
            ),
            registrations = listOf(registration(dx = 0.5f), registration()),
            weights = listOf(1f, 2f),
            mask = mask
        )

        assertEquals(gray(60), filtered.pixels[0])
        assertEquals(3f, filtered.diagnostics.expectedUnmaskedWeight, 0.0001f)
        assertEquals(2f, filtered.diagnostics.minimumValidWeight, 0.0001f)
        assertEquals(2f / 3f, filtered.diagnostics.minimumValidWeightRatio, 0.0002f)
    }

    @Test
    fun zeroCoverageIsReportedAndNeverInventsAWeightedSample() = runBlocking {
        val mask = onePixelMask(2, 1, 1, 0)
        val filtered = integrate(
            sources = listOf(intArrayOf(gray(10), gray(250))),
            registrations = listOf(registration(dx = 0.5f)),
            weights = listOf(1f),
            mask = mask
        )

        assertEquals(0xFF000000.toInt(), filtered.pixels[0])
        assertEquals(1, filtered.diagnostics.insufficientCoveragePixelCount)
        assertEquals(1f, filtered.diagnostics.insufficientCoverageFraction, 0f)
        assertEquals("insufficient_coverage", filtered.diagnostics.fallbackOrRejectionReason)
        assertTrue(shouldRetryAutomaticIntegrationWithoutMask(filtered.diagnostics))
    }

    @Test
    fun unmaskedRetryIsNotRequestedForDisabledMaskOrCompleteCoverage() {
        assertFalse(
            shouldRetryAutomaticIntegrationWithoutMask(
                SensorDefectFilteringReport(
                    sampleLevelFilteringApplied = false,
                    insufficientCoveragePixelCount = 2
                )
            )
        )
        assertFalse(
            shouldRetryAutomaticIntegrationWithoutMask(
                SensorDefectFilteringReport(
                    sampleLevelFilteringApplied = true,
                    insufficientCoveragePixelCount = 0
                )
            )
        )
    }

    @Test
    fun disabledMaskIsByteIdenticalToLegacyIntegratorPath() = runBlocking {
        val sources = listOf(
            intArrayOf(gray(20), gray(80)),
            intArrayOf(gray(60), gray(40))
        )
        val transforms = listOf(registration(dx = 0.25f), registration())
        val withoutMask = integrate(sources, transforms, listOf(1f, 0.7f), null)
        val disabled = integrate(
            sources,
            transforms,
            listOf(1f, 0.7f),
            SensorDefectMask.empty(2, 1)
        )

        assertArrayEquals(withoutMask.pixels, disabled.pixels)
        assertFalse(disabled.diagnostics.sampleLevelFilteringApplied)
        assertEquals(0L, disabled.diagnostics.excludedSampleCount)
    }

    @Test
    fun automaticMaskUsesOriginalCaptureIndicesAfterReferenceReordering() {
        val observations = listOf(3, 1, 2).map { index ->
            observation(index, 100f, 100f)
        }
        val requestedIndices = mutableListOf<Int>()
        val first = automaticMask(observations, requestedIndices)
        val second = automaticMask(observations, mutableListOf())

        assertEquals(listOf(1, 2, 3), first.originalFrameIndices)
        assertEquals(listOf(1, 2, 3), requestedIndices)
        assertTrue(first.mask.enabled)
        assertEquals(
            first.mask.regions.map { it.stableRegionId },
            second.mask.regions.map { it.stableRegionId }
        )
    }

    @Test
    fun urbanWindowAutomaticMaskSuppressesTrailsWithoutAnnotatedStarRegression() = runBlocking {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        val plan = requireNotNull(
            planManualSequenceAlignment(
                fixture.frames,
                fixture.frames.first().width,
                fixture.frames.first().height
            )
        )
        val detector = com.example.astrophoto.processing.jpeg.v2.artifacts
            .PersistentSensorCandidateDetector()
        val maskEstimator = com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator()
        val allocationBean = (ManagementFactory.getThreadMXBean() as?
            com.sun.management.ThreadMXBean)?.apply {
            if (isThreadAllocatedMemorySupported && !isThreadAllocatedMemoryEnabled) {
                isThreadAllocatedMemoryEnabled = true
            }
        }
        val extractionAllocationBefore = allocationBean?.getThreadAllocatedBytes(
            Thread.currentThread().id
        ) ?: 0L
        val extractionStarted = System.nanoTime()
        val observations = fixture.frames.mapIndexed { index, image ->
            detector.observe(
                frameId = "frame-${index.toString().padStart(3, '0')}.jpg",
                originalCaptureIndex = index + 1,
                image = image,
                skyMask = maskEstimator.estimate(image).mask
            )
        }
        val extractionDurationMillis = (System.nanoTime() - extractionStarted) / 1_000_000L
        val extractionAllocatedBytes = (
            allocationBean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: 0L
            ) - extractionAllocationBefore
        val constructionAllocationBefore = allocationBean?.getThreadAllocatedBytes(
            Thread.currentThread().id
        ) ?: 0L
        val constructionStarted = System.nanoTime()
        val automaticMask = buildAutomaticSensorDefectMask(
            observations,
            fixture.frames.first().width,
            fixture.frames.first().height
        ) { captureIndex ->
            val shift = plan.frames[captureIndex - 1].shift
                ReferenceToSourceTransform(shift.dx.toFloat(), shift.dy.toFloat())
            }
        val constructionDurationMillis = (System.nanoTime() - constructionStarted) / 1_000_000L
        val constructionAllocatedBytes = (
            allocationBean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: 0L
            ) - constructionAllocationBefore
        val artifactFrames = observations
            .sortedBy { it.originalCaptureIndex }
            .map { it.asArtifactFrameObservation() }
        val bruteTracks = TemporalPixelConsistency()
            .stationaryTracksBruteForceProfiled(artifactFrames)
        val bruteStaticMask = StaticArtifactAnalyzer().analyzeTracks(
            bruteTracks.tracks,
            artifactFrames.size,
            fixture.frames.first().width,
            fixture.frames.first().height
        )
        val bruteMask = buildConfirmedSensorDefectMask(
            bruteStaticMask,
            artifactFrames,
            observations.sortedBy { it.originalCaptureIndex }.map { observation ->
                val shift = plan.frames[observation.originalCaptureIndex - 1].shift
                ReferenceToSourceTransform(shift.dx.toFloat(), shift.dy.toFloat())
            }
        )
        assertEquals(
            bruteMask.regions.map { it.stableRegionId },
            automaticMask.mask.regions.map { it.stableRegionId }
        )
        assertEquals(bruteMask.footprintPixels, automaticMask.mask.footprintPixels)
        println(
            "AUTOMATIC_SENSOR_MASK_PROFILE " +
                "decodeCount=${fixture.frames.size} " +
                "processedPixels=${fixture.frames.sumOf { it.width.toLong() * it.height }} " +
                "candidateCounts=${observations.joinToString(",") { it.candidates.size.toString() }} " +
                "totalCandidates=${observations.sumOf { it.candidates.size }} " +
                "extractionMillis=$extractionDurationMillis " +
                "extractionAllocatedBytes=$extractionAllocatedBytes " +
                "constructionMillis=$constructionDurationMillis " +
                "constructionAllocatedBytes=$constructionAllocatedBytes " +
                "matchingNanos=${automaticMask.diagnostics.candidateMatching.elapsedNanos} " +
                "matchingCandidateVisits=" +
                "${automaticMask.diagnostics.candidateMatchingCandidateVisitCount} " +
                "matchingDistanceComparisons=" +
                "${automaticMask.diagnostics.candidateMatchingDistanceComparisonCount} " +
                "matchingIdentityLookups=" +
                "${automaticMask.diagnostics.candidateMatchingIdentityLookupCount} " +
                "recurrenceNanos=${automaticMask.diagnostics.recurrenceCalculation.elapsedNanos} " +
                "footprintNanos=${automaticMask.diagnostics.footprintConstruction.elapsedNanos} " +
                "validationNanos=${automaticMask.diagnostics.maskValidation.elapsedNanos} " +
                "tracks=${automaticMask.diagnostics.candidateMatching.outputCount} " +
                "staticRegions=${automaticMask.diagnostics.recurrenceCalculation.outputCount}"
        )
        assertEquals((1..30).toList(), automaticMask.originalFrameIndices)
        assertTrue(automaticMask.mask.enabled)
        assertEquals(
            listOf(
                "sensor-hot_pixel-x68800-y13500",
                "sensor-hot_pixel-x4500-y18000",
                "sensor-hot_pixel-x6400-y21800",
                "sensor-hot_pixel-x4800-y24100",
                "sensor-hot_pixel-x13500-y26000",
                "sensor-hot_pixel-x53800-y34300",
                "sensor-hot_pixel-x66400-y41400",
                "sensor-hot_pixel-x4500-y60700",
                "sensor-hot_pixel-x4700-y61600",
                "sensor-hot_pixel-x40600-y67000"
            ),
            automaticMask.mask.regions.map { it.stableRegionId }
        )
        println(
            "AUTOMATIC_SENSOR_MASK_REGIONS " +
                automaticMask.mask.regions.joinToString(";") { region ->
                    "${region.stableRegionId}@${region.sourceX},${region.sourceY}"
                }
        )
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.SENSOR_DEFECT
        }.forEach { defect ->
            println(
                "AUTOMATIC_SENSOR_MASK_ANNOTATED_DEFECT " +
                    "${defect.id}@${defect.x},${defect.y} " +
                    "masked=${automaticMask.mask.contains(
                        defect.x.roundToInt(),
                        defect.y.roundToInt()
                    )}"
            )
        }
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.UNCERTAIN
        }.forEach { uncertain ->
            assertFalse(
                automaticMask.mask.contains(
                    uncertain.x.roundToInt(),
                    uncertain.y.roundToInt()
                )
            )
        }
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.STAR
        }.forEach { star ->
            assertFalse(
                automaticMask.mask.contains(star.x.roundToInt(), star.y.roundToInt())
            )
        }
        val referenceImage = fixture.frames[fixture.referenceFrameIndex]
        val referenceSkyMask = maskEstimator.estimate(referenceImage)
        val frameAnalyzer = JpegFrameAnalyzer()
        val rawFrameAnalyses = fixture.frames.mapIndexed { index, image ->
            val id = "frame-${index.toString().padStart(3, '0')}.jpg"
            frameAnalyzer.analyze(
                id = id,
                fileName = id,
                image = image,
                skyMask = maskEstimator.estimate(image)
            )
        }
        val staticArtifactAnalyzer = StaticArtifactAnalyzer()
        val registrationArtifactMask = staticArtifactAnalyzer.analyze(
            rawFrameAnalyses.map {
                ArtifactFrameObservation(it.id, it.stars)
            },
            referenceImage.width,
            referenceImage.height
        )
        val referenceAnalysis = staticArtifactAnalyzer.excludeFrom(
            rawFrameAnalyses[fixture.referenceFrameIndex],
            registrationArtifactMask
        )
        println(
            "AUTOMATIC_SENSOR_MASK_REFERENCE_FILTER " +
                "rawStars=${rawFrameAnalyses[fixture.referenceFrameIndex].stars.size} " +
                "filteredStars=${referenceAnalysis.stars.size} " +
                "registrationStaticRegions=${registrationArtifactMask.regions.size}"
        )
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.SENSOR_DEFECT
        }.forEach { defect ->
            val nearest = referenceAnalysis.stars.minByOrNull { star ->
                kotlin.math.hypot(star.x - defect.x, star.y - defect.y)
            }
            println(
                "AUTOMATIC_SENSOR_MASK_REFERENCE_LINEAGE " +
                    "${defect.id}@${defect.x},${defect.y} " +
                    "initialSky=${referenceSkyMask.mask.contains(
                        defect.x.roundToInt(),
                        defect.y.roundToInt()
                    )} " +
                    "nearestDetected=${nearest?.let {
                        "${it.x},${it.y},distance=" +
                            kotlin.math.hypot(it.x - defect.x, it.y - defect.y) +
                            ",width=${it.width},confidence=${it.confidence}"
                    } ?: "none"}"
            )
        }

        val baseline = integrateFixture(fixture, plan, null)
        val disabledMaskReplay = integrateFixture(
            fixture,
            plan,
            SensorDefectMask.empty(
                fixture.frames.first().width,
                fixture.frames.first().height,
                reason = "baseline_replay"
            )
        )
        assertArrayEquals(baseline.image.pixels, disabledMaskReplay.image.pixels)
        assertEquals(baseline.acceptedFrames, disabledMaskReplay.acceptedFrames)
        assertFalse(disabledMaskReplay.filtering.sampleLevelFilteringApplied)
        assertEquals(0L, disabledMaskReplay.filtering.excludedSampleCount)
        val filtered = integrateFixture(fixture, plan, automaticMask.mask)
        assertEquals(baseline.acceptedFrames, filtered.acceptedFrames)
        assertEquals(0, filtered.filtering.insufficientCoveragePixelCount)
        assertTrue(filtered.filtering.sampleLevelFilteringApplied)
        assertTrue(filtered.filtering.excludedSampleCount > 0)

        val trailMetrics = mutableListOf<String>()
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.SENSOR_DEFECT
        }.forEach { defect ->
            val path = plan.frames.filter { it.accepted }.map { decision ->
                (defect.x - decision.shift.dx) to (defect.y - decision.shift.dy)
            }.distinctBy { it.first.roundToInt() to it.second.roundToInt() }
            val beforeSamples = path.map {
                localContrast(baseline.image, it.first, it.second)
            }
            val afterSamples = path.map {
                localContrast(filtered.image, it.first, it.second)
            }
            val before = beforeSamples.average()
            val after = afterSamples.average()
            val beforePositive = beforeSamples.map { it.coerceAtLeast(0.0) }.average()
            val afterPositive = afterSamples.map { it.coerceAtLeast(0.0) }.average()
            val beforeAbsolute = beforeSamples.map(::abs).average()
            val afterAbsolute = afterSamples.map(::abs).average()
            val absoluteResidualLimit = maxOf(beforeAbsolute * 0.50, 1.0)
            trailMetrics +=
                "${defect.id}:mean=$before->$after,positive=$beforePositive->$afterPositive," +
                    "absolute=$beforeAbsolute->$afterAbsolute,minAfter=${afterSamples.min()}"
            assertTrue(
                "${defect.id}: mean=$before->$after positive=$beforePositive->$afterPositive " +
                    "absolute=$beforeAbsolute->$afterAbsolute limit=$absoluteResidualLimit " +
                    "minimumAfter=${afterSamples.min()}",
                afterPositive <= beforePositive * 0.50 &&
                    afterAbsolute <= absoluteResidualLimit &&
                    afterSamples.min() >= -1.0
            )
        }

        val starMetrics = mutableListOf<String>()
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.STAR
        }.forEach { star ->
            val before = compactSourceMetrics(baseline.image, star.x, star.y)
            val after = compactSourceMetrics(filtered.image, star.x, star.y)
            val centroidShift = kotlin.math.hypot(
                after.centroidX - before.centroidX,
                after.centroidY - before.centroidY
            )
            starMetrics +=
                "${star.id}:contrast=${before.contrast}->${after.contrast}," +
                    "centroidShift=$centroidShift,width=${before.width}->${after.width}"
            assertTrue(
                "${star.id}: contrast ${before.contrast} -> ${after.contrast}",
                after.contrast >= before.contrast * 0.95
            )
            assertTrue(
                "${star.id}: centroid ${before.centroidX},${before.centroidY} -> " +
                    "${after.centroidX},${after.centroidY}",
                centroidShift <= 0.25
            )
            assertTrue(
                "${star.id}: width ${before.width} -> ${after.width}",
                after.width <= before.width * 1.05
            )
        }

        val baselineBackground = backgroundMetrics(baseline.image, fixture)
        val filteredBackground = backgroundMetrics(filtered.image, fixture)
        assertTrue(filteredBackground.mad <= baselineBackground.mad * 1.05 + 0.001)
        assertTrue(filteredBackground.rms <= baselineBackground.rms * 1.05 + 0.001)
        println(
            "AUTOMATIC_SENSOR_MASK_FIXTURE " +
                "regions=${automaticMask.mask.regions.size} " +
                "excludedSamples=${filtered.filtering.excludedSampleCount} " +
                "affectedPixels=${filtered.filtering.affectedOutputPixelCount} " +
                "weightRatio=${filtered.filtering.minimumValidWeightRatio}/" +
                "${filtered.filtering.medianValidWeightRatio}/" +
                "${filtered.filtering.maximumValidWeightRatio} " +
                "trails=${trailMetrics.joinToString(";")} " +
                "stars=${starMetrics.joinToString(";")} " +
                "backgroundMad=${baselineBackground.mad}->${filteredBackground.mad} " +
                "backgroundRms=${baselineBackground.rms}->${filteredBackground.rms}"
        )
        writeCandidateLineageDiagnostics(
            fixture = fixture,
            plan = plan,
            referenceImage = referenceImage,
            referenceSkyMask = referenceSkyMask,
            referenceStars = referenceAnalysis.stars,
            sensorDefectMask = automaticMask.mask,
            unmaskedIntegration = baseline,
            maskedIntegration = filtered
        )
    }

    private data class IntegrationResult(
        val pixels: IntArray,
        val diagnostics: com.example.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
    )

    private data class FixtureIntegrationResult(
        val image: ArgbPixelImage,
        val coverage: FloatArray,
        val sensorDefectAffectedOutput: FloatArray,
        val acceptedFrames: Int,
        val filtering: com.example.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
    )

    private suspend fun integrateFixture(
        fixture: Stage6RegressionFixture,
        plan: ManualSequenceAlignmentPlan,
        mask: SensorDefectMask?
    ): FixtureIntegrationResult {
        val width = fixture.frames.first().width
        val height = fixture.frames.first().height
        val output = IntArray(width * height)
        val coverage = FloatArray(width * height)
        val sensorDefectAffectedOutput = FloatArray(width * height)
        val accepted = plan.frames.filter { it.accepted }
        val diagnostics = LinearWeightedIntegrator(
            tileCoordinator = TileProcessingCoordinator(128, 32)
        ).integrate(
            outputWidth = width,
            outputHeight = height,
            frames = accepted.map { decision ->
                WeightedIntegrationFrame(
                    id = decision.frameId ?: "frame-${decision.originalFrameIndex}",
                    source = fixture.frames[decision.originalFrameIndex],
                    transform = registration(
                        dx = decision.shift.dx.toFloat(),
                        dy = decision.shift.dy.toFloat()
                    ),
                    normalizedWeight = 1f
                )
            },
            maximumWorkingMemoryBytes = 64L * 1024L * 1024L,
            openSource = { IntArrayPixelSource(width, height, it.pixels) },
            allowRobustClipping = false,
            sensorDefectMask = mask,
            writeTile = { tile, pixels ->
                for (row in 0 until tile.height) {
                    pixels.copyInto(
                        output,
                        destinationOffset = (tile.top + row) * width + tile.left,
                        startIndex = row * tile.width,
                        endIndex = (row + 1) * tile.width
                    )
                }
            },
            writeCoverageTile = { tile, values ->
                for (row in 0 until tile.height) {
                    values.copyInto(
                        coverage,
                        destinationOffset = (tile.top + row) * width + tile.left,
                        startIndex = row * tile.width,
                        endIndex = (row + 1) * tile.width
                    )
                }
            },
            writeSensorDefectAffectedTile = { tile, values ->
                for (row in 0 until tile.height) {
                    for (column in 0 until tile.width) {
                        val sourceIndex = row * tile.width + column
                        val destinationIndex =
                            (tile.top + row) * width + tile.left + column
                        sensorDefectAffectedOutput[destinationIndex] =
                            if (values[sourceIndex]) 1f else 0f
                    }
                }
            }
        )
        return FixtureIntegrationResult(
            ArgbPixelImage(width, height, output),
            coverage,
            sensorDefectAffectedOutput,
            diagnostics.acceptedFrames,
            diagnostics.sensorDefectFiltering
        )
    }

    private suspend fun integrate(
        sources: List<IntArray>,
        registrations: List<RegistrationResult>,
        weights: List<Float>,
        mask: SensorDefectMask?
    ): IntegrationResult {
        require(sources.size == registrations.size && sources.size == weights.size)
        val output = IntArray(2)
        val diagnostics = LinearWeightedIntegrator(
            tileCoordinator = TileProcessingCoordinator(2, 1)
        ).integrate(
            outputWidth = 2,
            outputHeight = 1,
            frames = sources.indices.map { index ->
                WeightedIntegrationFrame(
                    id = "frame-$index",
                    source = sources[index],
                    transform = registrations[index],
                    normalizedWeight = weights[index]
                )
            },
            maximumWorkingMemoryBytes = 8L * 1024L * 1024L,
            openSource = { IntArrayPixelSource(2, 1, it) },
            allowRobustClipping = false,
            sensorDefectMask = mask,
            includeOutputPixel = { x, _ -> x == 0 },
            writeTile = { _, pixels -> pixels.copyInto(output) }
        )
        return IntegrationResult(output, diagnostics.sensorDefectFiltering)
    }

    private fun automaticMask(
        observations: List<PersistentSensorFrameObservation>,
        requestedIndices: MutableList<Int>
    ): AutomaticSensorDefectMaskResult = buildAutomaticSensorDefectMask(
        observations = observations,
        outputWidth = 200,
        outputHeight = 200
    ) { originalIndex ->
        requestedIndices += originalIndex
        ReferenceToSourceTransform((originalIndex - 1) * 3f, 0f)
    }

    private fun observation(
        captureIndex: Int,
        x: Float,
        y: Float
    ) = PersistentSensorFrameObservation(
        frameId = "frame-$captureIndex",
        originalCaptureIndex = captureIndex,
        width = 200,
        height = 200,
        candidates = listOf(
            PersistentSensorCandidateObservation(
                feature = DetectedStar(
                    x = x,
                    y = y,
                    flux = 200f,
                    localBackground = 10f,
                    localContrast = 100f,
                    width = 1f,
                    ellipticity = 0f,
                    confidence = 1f
                ),
                centerRed = 220,
                centerGreen = 30,
                centerBlue = 25,
                backgroundRed = 20,
                backgroundGreen = 20,
                backgroundBlue = 20,
                chromaExcess = 195
            )
        )
    )

    private fun onePixelMask(width: Int, height: Int, x: Int, y: Int): SensorDefectMask {
        val pixel = SensorDefectFootprintPixel(x, y)
        return SensorDefectMask(
            width,
            height,
            listOf(
                SensorDefectRegion(
                    stableRegionId = "sensor-test",
                    sourceX = x.toFloat(),
                    sourceY = y.toFloat(),
                    sourceRadiusX = 0.5f,
                    sourceRadiusY = 0.5f,
                    footprintPixels = listOf(pixel),
                    recurrence = 3,
                    totalFrameCount = 3,
                    skySpaceSupport = 0,
                    confidence = 1f,
                    classification = PersistentArtifactClassification.SENSOR_DEFECT,
                    classificationReason = "test"
                )
            ),
            enabled = true
        )
    }

    private fun registration(dx: Float = 0f, dy: Float = 0f) = RegistrationResult(
        dx = dx,
        dy = dy,
        rotationRadians = 0f,
        scale = 1f,
        detectedStars = 10,
        matchedStars = 10,
        inlierStars = 10,
        residualError = 0f,
        confidence = 1f,
        isReliable = true,
        rejectionReason = null
    )

    private fun gray(value: Int): Int =
        0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

    private data class CompactSourceMetrics(
        val contrast: Double,
        val centroidX: Double,
        val centroidY: Double,
        val width: Double
    )

    private fun compactSourceMetrics(
        image: ArgbPixelImage,
        x: Float,
        y: Float
    ): CompactSourceMetrics {
        val centerX = x.roundToInt().coerceIn(4, image.width - 5)
        val centerY = y.roundToInt().coerceIn(4, image.height - 5)
        val ring = mutableListOf<Double>()
        for (offset in -4..4) {
            ring += luminance(image.pixels[(centerY - 4) * image.width + centerX + offset])
            ring += luminance(image.pixels[(centerY + 4) * image.width + centerX + offset])
            if (offset in -3..3) {
                ring += luminance(
                    image.pixels[(centerY + offset) * image.width + centerX - 4]
                )
                ring += luminance(
                    image.pixels[(centerY + offset) * image.width + centerX + 4]
                )
            }
        }
        val background = ring.sorted()[ring.size / 2]
        var sum = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        var peak = Double.NEGATIVE_INFINITY
        for (dy in -3..3) for (dx in -3..3) {
            val value = luminance(
                image.pixels[(centerY + dy) * image.width + centerX + dx]
            )
            peak = maxOf(peak, value)
            val signal = (value - background).coerceAtLeast(0.0)
            sum += signal
            weightedX += (centerX + dx) * signal
            weightedY += (centerY + dy) * signal
        }
        val centroidX = if (sum > 0.0) weightedX / sum else centerX.toDouble()
        val centroidY = if (sum > 0.0) weightedY / sum else centerY.toDouble()
        var secondMoment = 0.0
        for (dy in -3..3) for (dx in -3..3) {
            val value = luminance(
                image.pixels[(centerY + dy) * image.width + centerX + dx]
            )
            val signal = (value - background).coerceAtLeast(0.0)
            val px = centerX + dx - centroidX
            val py = centerY + dy - centroidY
            secondMoment += (px * px + py * py) * signal
        }
        return CompactSourceMetrics(
            contrast = peak - background,
            centroidX = centroidX,
            centroidY = centroidY,
            width = if (sum > 0.0) sqrt(secondMoment / sum) else 0.0
        )
    }

    private data class BackgroundMetrics(val mad: Double, val rms: Double)

    private fun backgroundMetrics(
        image: ArgbPixelImage,
        fixture: Stage6RegressionFixture
    ): BackgroundMetrics {
        val excluded = fixture.groundTruth.map {
            it.x.roundToInt() to it.y.roundToInt()
        }
        val values = mutableListOf<Double>()
        for (y in 20 until image.height / 2 step 3) {
            for (x in 20 until image.width - 20 step 3) {
                if (excluded.any { (px, py) -> abs(px - x) <= 8 && abs(py - y) <= 8 }) {
                    continue
                }
                values += luminance(image.pixels[y * image.width + x])
            }
        }
        values.sort()
        val median = values[values.size / 2]
        val deviations = values.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val rms = sqrt(values.sumOf { (it - median) * (it - median) } / values.size)
        return BackgroundMetrics(mad, rms)
    }

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

    private fun luminance(color: Int): Double =
        (color ushr 16 and 0xFF) * 0.299 +
            (color ushr 8 and 0xFF) * 0.587 +
            (color and 0xFF) * 0.114

    private data class DiagnosticCandidateLineage(
        val stage: String,
        val sampleFiltered: Boolean,
        val parent: DiagnosticCandidateLineage?
    )

    private data class DiagnosticCropBounds(
        val left: Int,
        val top: Int,
        val rightExclusive: Int,
        val bottomExclusive: Int
    ) {
        val width: Int get() = rightExclusive - left
        val height: Int get() = bottomExclusive - top
    }

    private suspend fun writeCandidateLineageDiagnostics(
        fixture: Stage6RegressionFixture,
        plan: ManualSequenceAlignmentPlan,
        referenceImage: ArgbPixelImage,
        referenceSkyMask: com.example.astrophoto.processing.jpeg.v2.model.SkyMaskResult,
        referenceStars: List<DetectedStar>,
        sensorDefectMask: SensorDefectMask,
        unmaskedIntegration: FixtureIntegrationResult,
        maskedIntegration: FixtureIntegrationResult
    ) {
        val registrationConfidence = plan.frames
            .filter { it.accepted }
            .mapNotNull { it.registrationConfidence }
            .average()
            .takeIf { it.isFinite() }
            ?.toFloat()
            ?: 0f
        val refined = SkyMaskRefiner().refine(
            initialMask = referenceSkyMask.mask,
            reference = referenceImage,
            stars = referenceStars,
            initialConfidence = referenceSkyMask.confidence,
            initialUsedFallback = referenceSkyMask.usedFallback,
            registrationConfidence = registrationConfidence
        )
        val protection = ForegroundProtectionMask().detect(referenceImage, referenceStars)
        val feathered = MaskFeathering().feather(
            refined.binaryMask,
            protection.mask,
            radiusOverride = refined.diagnostics.featherRadius
        ).alphaMask
        val maskedCoverage = AlphaMask(
            maskedIntegration.image.width,
            maskedIntegration.image.height,
            maskedIntegration.coverage.copyOf()
        )
        val unmaskedCoverage = AlphaMask(
            unmaskedIntegration.image.width,
            unmaskedIntegration.image.height,
            unmaskedIntegration.coverage.copyOf()
        )
        val starPreserver = ReferenceStarSignalPreserver()
        val maskedStarPreservation = starPreserver.preserve(
            maskedIntegration.image,
            referenceImage,
            referenceStars,
            sensorDefectMask,
            AlphaMaskPixelSource(
                AlphaMask(
                    maskedIntegration.image.width,
                    maskedIntegration.image.height,
                    maskedIntegration.sensorDefectAffectedOutput.copyOf()
                )
            )
        )
        val maskedStarPreserved = maskedStarPreservation.image
        val unmaskedStarPreserved = starPreserver.preserve(
            unmaskedIntegration.image,
            referenceImage,
            referenceStars
        ).image
        val maskedComposite = SkyForegroundComposer().compose(
            maskedStarPreserved,
            referenceImage,
            feathered,
            maskedCoverage,
            sensorDefectAffectedOutput = AlphaMask(
                maskedIntegration.image.width,
                maskedIntegration.image.height,
                maskedIntegration.sensorDefectAffectedOutput.copyOf()
            ),
            sensorDefectMask = sensorDefectMask
        )
        val unmaskedComposite = SkyForegroundComposer().compose(
            unmaskedStarPreserved,
            referenceImage,
            feathered,
            unmaskedCoverage
        )
        val profile = AstroProcessingProfile.URBAN_SKY_STRONG
        val maskedProcessed = AdaptivePresetProcessor().process(
            stackedSky = maskedStarPreserved,
            referenceForeground = referenceImage,
            effectiveSkyAlpha = maskedComposite.effectiveSkyAlpha,
            profile = profile,
            frameCount = maskedIntegration.acceptedFrames,
            alignedStackStars = referenceStars
        ).image
        val unmaskedProcessed = AdaptivePresetProcessor().process(
            stackedSky = unmaskedStarPreserved,
            referenceForeground = referenceImage,
            effectiveSkyAlpha = unmaskedComposite.effectiveSkyAlpha,
            profile = profile,
            frameCount = unmaskedIntegration.acceptedFrames,
            alignedStackStars = referenceStars
        ).image

        val analyzer = ResultQualityAnalyzer()
        fun candidate(type: ResultCandidateType, image: ArgbPixelImage) = ResultCandidate(
            type,
            image,
            analyzer.analyze(image, referenceImage, maskedComposite.effectiveSkyAlpha)
        )
        val referenceCandidate = candidate(ResultCandidateType.REFERENCE, referenceImage)
        val cleanCandidate = candidate(ResultCandidateType.CLEAN_STACK, maskedComposite.image)
        val processedCandidate = candidate(ResultCandidateType.PROCESSED, maskedProcessed)
        val qualityGate = AstroResultQualityGate()
        val cleanEvidence = CleanStackValidationEvidence(
            referenceStarRetention = ReferenceStarRetentionValidator().validate(
                referenceImage,
                maskedComposite.image,
                referenceStars
            ),
            coverageUniformity = CoverageUniformityValidator().validate(
                maskedCoverage,
                maskedComposite.effectiveSkyAlpha
            ),
            lineArtifacts = LineArtifactDetector().compare(
                referenceImage,
                maskedComposite.image,
                maskedComposite.effectiveSkyAlpha
            ),
            transformSequenceScore = plan.modelScore,
            acceptedFrameCount = maskedIntegration.acceptedFrames,
            transformSequenceValid =
                maskedIntegration.acceptedFrames >= 2 && plan.modelScore >= 0.50f
        )
        val cleanDecision = qualityGate.evaluateCleanStack(
            referenceCandidate,
            cleanCandidate,
            profile,
            cleanEvidence
        )
        val processedDecision = qualityGate.evaluateProcessed(
            referenceCandidate,
            cleanCandidate,
            processedCandidate,
            profile,
            maskedIntegration.acceptedFrames
        )
        val selection = ResultSelectionPolicy().select(
            referenceCandidate,
            cleanCandidate,
            processedCandidate,
            processedDecision,
            cleanDecision
        )

        val lineageByImage = IdentityHashMap<ArgbPixelImage, DiagnosticCandidateLineage>()
        val unmaskedLineage = DiagnosticCandidateLineage(
            "unmasked_clean_integration",
            sampleFiltered = false,
            parent = null
        )
        val maskedLineage = DiagnosticCandidateLineage(
            "masked_clean_integration",
            sampleFiltered = maskedIntegration.filtering.sampleLevelFilteringApplied,
            parent = null
        )
        lineageByImage[unmaskedIntegration.image] = unmaskedLineage
        lineageByImage[maskedIntegration.image] = maskedLineage
        lineageByImage[referenceImage] = DiagnosticCandidateLineage(
            "reference",
            sampleFiltered = false,
            parent = null
        )
        lineageByImage[maskedStarPreserved] = DiagnosticCandidateLineage(
            "star_preservation",
            sampleFiltered = maskedLineage.sampleFiltered,
            parent = maskedLineage
        )
        lineageByImage[maskedComposite.image] = DiagnosticCandidateLineage(
            "clean_composition",
            sampleFiltered = maskedLineage.sampleFiltered,
            parent = checkNotNull(lineageByImage[maskedStarPreserved])
        )
        lineageByImage[maskedProcessed] = DiagnosticCandidateLineage(
            "processed_profile",
            sampleFiltered = maskedLineage.sampleFiltered,
            parent = checkNotNull(lineageByImage[maskedStarPreserved])
        )
        val selectedLineage = checkNotNull(lineageByImage[selection.selected.image])
        val unmaskedSelected = when (selection.selected.type) {
            ResultCandidateType.REFERENCE -> referenceImage
            ResultCandidateType.CLEAN_STACK -> unmaskedComposite.image
            ResultCandidateType.PROCESSED -> unmaskedProcessed
        }
        val selectedContainsFilteredPixels =
            selectedLineage.sampleFiltered &&
                !selection.selected.image.pixels.contentEquals(unmaskedSelected.pixels)

        val outputRoot = Path.of(
            "build",
            "reports",
            "automatic-sensor-lineage",
            fixture.name
        )
        Files.createDirectories(outputRoot)
        val stages = linkedMapOf(
            "01-unmasked-clean-integration" to unmaskedIntegration.image,
            "02-masked-clean-integration" to maskedIntegration.image,
            "02b-star-preserved-masked-integration" to maskedStarPreserved,
            "03-composed-clean-result" to maskedComposite.image,
            "04-processed-profile-candidate" to maskedProcessed,
            "05-selected-${selection.selected.type.name.lowercase()}" to selection.selected.image
        )
        stages.forEach { (name, image) ->
            writeArgbPng(outputRoot.resolve("$name.png"), image)
        }
        val encodedPath = outputRoot.resolve("06-final-encoded.png")
        writeArgbPng(encodedPath, selection.selected.image)
        val publishedPath = outputRoot.resolve("07-final-published.png")
        Files.copy(encodedPath, publishedPath, StandardCopyOption.REPLACE_EXISTING)
        val decodedPublished = checkNotNull(javax.imageio.ImageIO.read(publishedPath.toFile()))
        val publishedImage = try {
            ArgbPixelImage(
                decodedPublished.width,
                decodedPublished.height,
                IntArray(decodedPublished.width * decodedPublished.height).also { pixels ->
                    decodedPublished.getRGB(
                        0,
                        0,
                        decodedPublished.width,
                        decodedPublished.height,
                        pixels,
                        0,
                        decodedPublished.width
                    )
                }
            )
        } finally {
            decodedPublished.flush()
        }
        assertArrayEquals(selection.selected.image.pixels, publishedImage.pixels)

        val defectPaths = fixture.groundTruth
            .filter { it.classification == ProvisionalSourceClass.SENSOR_DEFECT }
            .associateWith { defect ->
                plan.frames.filter { it.accepted }.map { decision ->
                    (defect.x - decision.shift.dx) to (defect.y - decision.shift.dy)
                }.distinctBy { it.first.roundToInt() to it.second.roundToInt() }
            }
        assertEquals(ResultCandidateType.CLEAN_STACK, selection.selected.type)
        defectPaths.forEach { (defect, path) ->
            fun positiveTrail(image: ArgbPixelImage): Double =
                path.map { localContrast(image, it.first, it.second).coerceAtLeast(0.0) }
                    .average()
            val baselinePositive = positiveTrail(unmaskedIntegration.image)
            val publishedPositive = positiveTrail(publishedImage)
            assertTrue(
                "${defect.id}: published baseline=$baselinePositive final=$publishedPositive",
                publishedPositive < baselinePositive
            )
        }
        fixture.groundTruth
            .filter { it.classification == ProvisionalSourceClass.STAR }
            .forEach { star ->
                val baselineMetrics = compactSourceMetrics(
                    unmaskedSelected,
                    star.x,
                    star.y
                )
                val publishedMetrics = compactSourceMetrics(publishedImage, star.x, star.y)
                val centroidShift = kotlin.math.hypot(
                    publishedMetrics.centroidX - baselineMetrics.centroidX,
                    publishedMetrics.centroidY - baselineMetrics.centroidY
                )
                assertTrue(
                    "${star.id}: published contrast",
                    publishedMetrics.contrast >= baselineMetrics.contrast * 0.95
                )
                assertTrue("${star.id}: published centroid", centroidShift <= 0.25)
                assertTrue(
                    "${star.id}: published width",
                    publishedMetrics.width <= baselineMetrics.width * 1.05
                )
            }
        val cropBounds = defectPaths.mapValues { (_, path) ->
            val margin = 10
            DiagnosticCropBounds(
                left = (
                    path.minOf { it.first }.toInt() - margin
                    ).coerceIn(0, referenceImage.width - 1),
                top = (
                    path.minOf { it.second }.toInt() - margin
                    ).coerceIn(0, referenceImage.height - 1),
                rightExclusive = (
                    ceil(path.maxOf { it.first }.toDouble()).toInt() + margin + 1
                    ).coerceIn(1, referenceImage.width),
                bottomExclusive = (
                    ceil(path.maxOf { it.second }.toDouble()).toInt() + margin + 1
                    ).coerceIn(1, referenceImage.height)
            )
        }
        val allStages = LinkedHashMap(stages)
        allStages["06-final-encoded"] = publishedImage
        allStages["07-final-published"] = publishedImage
        cropBounds.forEach { (defect, bounds) ->
            require(bounds.width > 0 && bounds.height > 0)
            allStages.forEach { (stage, image) ->
                writeArgbPng(
                    outputRoot.resolve("${defect.id}-$stage-crop.png"),
                    crop(image, bounds)
                )
            }
        }

        val selectedHash = sha256Argb(selection.selected.image)
        val publishedHash = sha256File(publishedPath)
        val report = buildString {
            appendLine("# Automatic sensor-filter candidate lineage")
            appendLine()
            appendLine("- fixture: `${fixture.name}`")
            appendLine("- dimensions: `${referenceImage.width}x${referenceImage.height}`")
            appendLine("- commonRegionCropApplied: `false`")
            appendLine("- compositionCropApplied: `${maskedComposite.diagnostics.cropApplied}`")
            appendLine("- outputSizingApplied: `false`")
            appendLine("- cleanCandidateMasked: `${lineageByImage.getValue(maskedComposite.image).sampleFiltered}`")
            appendLine("- processedCandidateMasked: `${lineageByImage.getValue(maskedProcessed).sampleFiltered}`")
            appendLine("- selectedCandidateMasked: `${selectedLineage.sampleFiltered}`")
            appendLine("- selectedCandidateContainsFilteredPixels: `$selectedContainsFilteredPixels`")
            appendLine("- selectedCandidateType: `${selection.selected.type.name}`")
            appendLine("- selectedCandidateHash: `$selectedHash`")
            appendLine("- publishedOutputHash: `$publishedHash`")
            appendLine(
                "- starPreservationMaskedReferenceSamplesSkipped: " +
                    "`${maskedStarPreservation.maskedReferenceSamplesSkipped}`"
            )
            appendLine(
                "- starPreservationAffectedOutputPixelCount: " +
                    "`${maskedStarPreservation.affectedOutputPixelCount}`"
            )
            appendLine(
                "- compositionMaskedReferenceSamplesSkipped: " +
                    "`${maskedComposite.diagnostics.maskedReferenceSamplesSkipped}`"
            )
            appendLine(
                "- compositionAffectedOutputPixelCount: " +
                    "`${maskedComposite.diagnostics.sensorDefectAffectedOutputPixels}`"
            )
            appendLine(
                "- compositionMeanOriginalAlpha: " +
                    "`${maskedComposite.diagnostics.meanOriginalAlphaAtProtectedPixels}`"
            )
            appendLine("- processedAccepted: `${processedDecision.accepted}`")
            appendLine("- processedRejectionReasons: `${processedDecision.hardFailureReasons.joinToString("|")}`")
            appendLine("- cleanAccepted: `${cleanDecision.accepted}`")
            appendLine("- cleanRejectionReasons: `${cleanDecision.hardFailureReasons.joinToString("|")}`")
            appendLine()
            appendLine("## Candidate hashes")
            appendLine()
            appendLine("| stage | canonical ARGB SHA-256 |")
            appendLine("|---|---|")
            allStages.forEach { (stage, image) ->
                appendLine("| $stage | `${sha256Argb(image)}` |")
            }
            appendLine("| 06-final-encoded PNG bytes | `${sha256File(encodedPath)}` |")
            appendLine("| 07-final-published PNG bytes | `$publishedHash` |")
            appendLine()
            appendLine("## Fixed crop coordinates")
            appendLine()
            appendLine("| defect | fixture crop | mapped device/output crop |")
            appendLine("|---|---|---|")
            cropBounds.forEach { (defect, bounds) ->
                appendLine(
                    "| ${defect.id} | " +
                        "`${bounds.left},${bounds.top},${bounds.width},${bounds.height}` | " +
                        "`${(bounds.left / FIXTURE_SCALE).roundToInt()}," +
                        "${(bounds.top / FIXTURE_SCALE).roundToInt()}," +
                        "${(bounds.width / FIXTURE_SCALE).roundToInt()}," +
                        "${(bounds.height / FIXTURE_SCALE).roundToInt()}` |"
                )
            }
            appendLine()
            appendLine(
                "Fixture coordinates are source crop `(0,0,1200,1600)` resized by " +
                    "`$FIXTURE_SCALE`; mapping to the 1440x1920 device output is `fixture / $FIXTURE_SCALE`."
            )
            appendLine()
            appendLine("## Trail metrics")
            appendLine()
            appendLine(
                "| defect | stage | positive mean | absolute mean | minimum | " +
                    "mean effective sky alpha | star-preserved metric neighborhoods |"
            )
            appendLine("|---|---|---:|---:|---:|---:|---:|")
            defectPaths.forEach { (defect, path) ->
                val meanAlpha = path.map { (x, y) ->
                    maskedComposite.effectiveSkyAlpha.alphaAt(
                        x.roundToInt().coerceIn(0, referenceImage.width - 1),
                        y.roundToInt().coerceIn(0, referenceImage.height - 1)
                    )
                }.average()
                val starPreservedSamples = path.count { (x, y) ->
                    val centerX = x.roundToInt().coerceIn(3, referenceImage.width - 4)
                    val centerY = y.roundToInt().coerceIn(3, referenceImage.height - 4)
                    (-3..3).any { dy ->
                        (-3..3).any { dx ->
                            val index = (centerY + dy) * referenceImage.width + centerX + dx
                            maskedIntegration.image.pixels[index] !=
                                maskedStarPreserved.pixels[index]
                        }
                    }
                }
                allStages.forEach { (stage, image) ->
                    val samples = path.map { localContrast(image, it.first, it.second) }
                    appendLine(
                        "| ${defect.id} | $stage | " +
                            "${samples.map { it.coerceAtLeast(0.0) }.average()} | " +
                            "${samples.map(::abs).average()} | ${samples.min()} | " +
                            "$meanAlpha | $starPreservedSamples/${path.size} |"
                    )
                }
            }
            appendLine()
            appendLine("## Lineage")
            appendLine()
            fun appendLineage(value: DiagnosticCandidateLineage, indent: String = "") {
                appendLine(
                    "$indent- `${value.stage}`: sampleFiltered=`${value.sampleFiltered}`"
                )
                value.parent?.let { appendLineage(it, "$indent  ") }
            }
            appendLineage(selectedLineage)
        }
        Files.writeString(outputRoot.resolve("lineage-report.md"), report)
        println(
            "AUTOMATIC_SENSOR_MASK_LINEAGE " +
                "selected=${selection.selected.type.name} " +
                "selectedMasked=${selectedLineage.sampleFiltered} " +
                "selectedContainsFilteredPixels=$selectedContainsFilteredPixels " +
                "selectedHash=$selectedHash publishedHash=$publishedHash " +
                "report=${outputRoot.resolve("lineage-report.md").toAbsolutePath()}"
        )
    }

    private fun crop(image: ArgbPixelImage, bounds: DiagnosticCropBounds): ArgbPixelImage {
        val pixels = IntArray(bounds.width * bounds.height)
        for (row in 0 until bounds.height) {
            image.pixels.copyInto(
                pixels,
                destinationOffset = row * bounds.width,
                startIndex = (bounds.top + row) * image.width + bounds.left,
                endIndex = (bounds.top + row) * image.width + bounds.rightExclusive
            )
        }
        return ArgbPixelImage(bounds.width, bounds.height, pixels)
    }

    private fun writeArgbPng(path: Path, image: ArgbPixelImage) {
        Files.newOutputStream(path).use { output ->
            PngStreamEncoder.encode(
                ArgbArrayPngSource(image.width, image.height, image.pixels),
                output
            )
        }
    }

    private fun sha256Argb(image: ArgbPixelImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val encoded = ByteArray(Int.SIZE_BYTES)
        image.pixels.forEach { color ->
            encoded[0] = (color ushr 24).toByte()
            encoded[1] = (color ushr 16).toByte()
            encoded[2] = (color ushr 8).toByte()
            encoded[3] = color.toByte()
            digest.update(encoded)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        Files.newInputStream(path).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fixtureDirectory(): File {
        val resource = requireNotNull(
            requireNotNull(javaClass.classLoader)
                .getResource("jpeg-stage6/urban-window-30/manifest.properties")
        )
        return requireNotNull(File(resource.toURI()).parentFile)
    }

    companion object {
        private const val FIXTURE_SCALE = 0.6
    }
}
