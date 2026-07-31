package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage6RealDeviceFixtureTest {
    @Test fun checkedInThirtyFrameSeriesPreservesSkyAndCameraSpaceSemantics() {
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory())
        assertEquals("urban-window-30", fixture.name)
        assertEquals(30, fixture.frames.size)
        assertEquals(8, fixture.referenceFrameIndex)
        val labelsById = fixture.groundTruth.associateBy { it.id }
        assertEquals(ProvisionalSourceClass.STAR, labelsById.getValue("star-01").classification)
        assertEquals(ProvisionalSourceClass.STAR, labelsById.getValue("star-02").classification)
        assertEquals(
            ProvisionalSourceClass.SENSOR_DEFECT,
            labelsById.getValue("defect-01").classification
        )
        assertEquals(
            ProvisionalSourceClass.SENSOR_DEFECT,
            labelsById.getValue("defect-02").classification
        )
        assertEquals(
            ProvisionalSourceClass.UNCERTAIN,
            labelsById.getValue("uncertain-01").classification
        )
        assertEquals(
            ProvisionalSourceClass.UNCERTAIN,
            labelsById.getValue("uncertain-02").classification
        )
        assertTrue(fixture.groundTruth.count {
            it.classification == ProvisionalSourceClass.STAR
        } >= 2)
        assertTrue(fixture.groundTruth.count {
            it.classification == ProvisionalSourceClass.SENSOR_DEFECT
        } >= 2)
        assertTrue(fixture.groundTruth.any { it.classification == ProvisionalSourceClass.UNCERTAIN })
        assertTrue(fixture.scoredGroundTruth.none {
            it.classification == ProvisionalSourceClass.UNCERTAIN
        })
        fixture.groundTruth.filter { it.classification == ProvisionalSourceClass.STAR }.forEach {
            assertEquals(ProvisionalCoordinateSpace.SKY, it.coordinateSpace)
            assertTrue(it.skyResidualPx != null && it.cameraResidualPx != null)
        }
        fixture.groundTruth.filter { it.id in setOf("star-01", "star-02") }.forEach {
            assertTrue(it.skyResidualPx!! < it.cameraResidualPx!!)
        }
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.SENSOR_DEFECT
        }.forEach {
            assertEquals(ProvisionalCoordinateSpace.CAMERA, it.coordinateSpace)
            assertTrue(it.cameraResidualPx!! < it.skyResidualPx!!)
        }

        val analyzer = JpegFrameAnalyzer()
        val maskEstimator = SkyMaskEstimator()
        val raw = fixture.frames.mapIndexed { index, image ->
            val id = "frame-${index.toString().padStart(3, '0')}.jpg"
            analyzer.analyze(id, id, image, maskEstimator.estimate(image)) to (index + 1)
        }
        val artifactAnalyzer = StaticArtifactAnalyzer()
        val artifactMask = artifactAnalyzer.analyze(
            raw.map { (analysis, _) -> ArtifactFrameObservation(analysis.id, analysis.stars) },
            raw.first().first.width,
            raw.first().first.height
        )
        val filtered = raw.map { (analysis, captureIndex) ->
            artifactAnalyzer.excludeFrom(analysis, artifactMask) to captureIndex
        }
        val reference = filtered[8].first
        val registration = SequenceAwareRegistrationEngine().register(
            filtered.map { (analysis, captureIndex) ->
                TemporalFeatureFrame(analysis.id, captureIndex, analysis.stars)
            },
            reference.id,
            reference.width,
            reference.height
        )

        assertTrue(registration.model.motionObservable)
        assertTrue(registration.model.velocityX < 0f)
        assertTrue(registration.model.velocityY > 0f)
        val manualPlan = requireNotNull(
            planManualSequenceAlignment(
                frames = fixture.frames,
                outputWidth = 1440,
                outputHeight = 1920
            )
        )
        assertEquals(fixture.referenceFrameIndex, manualPlan.referenceFrameIndex)
        assertTrue(manualPlan.stationaryArtifactCount >= 2)
        assertTrue(manualPlan.acceptedRegistrationCount >= 14)
        assertTrue(kotlin.math.abs(manualPlan.shifts.last().dx) in 45..70)
        assertTrue(kotlin.math.abs(manualPlan.shifts.last().dy) in 45..70)
        fixture.groundTruth.filter { it.id in setOf("star-01", "star-02") }.forEach { label ->
            val matching = registration.trackAnalysis.tracks
                .filter { it.cluster == TemporalMotionCluster.COHERENT_MOVING_SKY }
                .mapNotNull { track ->
                    val referenceObservation =
                        track.observations.firstOrNull { it.frameId == reference.id }
                    val point = if (referenceObservation != null) {
                        referenceObservation.star.x to referenceObservation.star.y
                    } else {
                        val anchor = track.observations.firstOrNull() ?: return@mapNotNull null
                        val delta = fixture.referenceFrameIndex + 1 - anchor.captureIndex
                        (
                            anchor.star.x + track.velocityX * delta
                            ) to (
                            anchor.star.y + track.velocityY * delta
                            )
                    }
                    track to point
                }
                .minByOrNull { (_, point) ->
                    squaredDistance(point.first, point.second, label.x, label.y)
                }
            requireNotNull(matching)
            val (matchingTrack, referencePoint) = matching
            val referenceDistanceSquared = squaredDistance(
                referencePoint.first,
                referencePoint.second,
                label.x,
                label.y
            )
            assertTrue(
                "${label.id}: nearest coherent sky track distanceSquared=$referenceDistanceSquared",
                referenceDistanceSquared <= 1f
            )
            val transformedSupport = fixture.frames.indices.count { index ->
                val predicted = registration.model.predictedTransform(index + 1)
                localPeakContrast(
                    fixture.frames[index],
                    label.x + predicted.dx,
                    label.y + predicted.dy
                ) >= 3f
            }
            assertTrue(
                "${label.id}: track=${matchingTrack.observations.size}, " +
                    "sky=$transformedSupport, required=${label.supportFrames}",
                maxOf(matchingTrack.observations.size, transformedSupport) >= label.supportFrames
            )
        }
        fixture.groundTruth.filter {
            it.classification == ProvisionalSourceClass.SENSOR_DEFECT
        }.forEach { label ->
            val cameraSupport = fixture.frames.count { localPeakContrast(it, label.x, label.y) >= 3f }
            val skySupport = fixture.frames.indices.count { index ->
                val captureIndex = index + 1
                val predicted = registration.model.predictedTransform(captureIndex)
                localPeakContrast(
                    fixture.frames[index],
                    label.x + predicted.dx,
                    label.y + predicted.dy
                ) >= 3f
            }
            assertTrue("$label cameraSupport=$cameraSupport", cameraSupport >= label.supportFrames)
            assertTrue("$label skySupport=$skySupport", skySupport < cameraSupport / 2)
        }
    }

    private fun localPeakContrast(image: ArgbPixelImage, x: Float, y: Float): Float {
        var best = Float.NEGATIVE_INFINITY
        for (dy in -2..2) for (dx in -2..2) {
            best = maxOf(best, sampledContrast(image, x + dx, y + dy))
        }
        return best
    }

    private fun localPeakContrast(image: ArgbPixelImage, x: Double, y: Double): Float =
        localPeakContrast(image, x.toFloat(), y.toFloat())

    private fun sampledContrast(image: ArgbPixelImage, x: Float, y: Float): Float {
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

    private fun luminance(color: Int): Float {
        val red = color ushr 16 and 0xFF
        val green = color ushr 8 and 0xFF
        val blue = color and 0xFF
        return red * 0.299f + green * 0.587f + blue * 0.114f
    }

    private fun squaredDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun squaredDistance(x1: Float, y1: Float, x2: Double, y2: Double): Double =
        squaredDistance(x1.toDouble(), y1.toDouble(), x2, y2)

    private fun squaredDistance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
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
