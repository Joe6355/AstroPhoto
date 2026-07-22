package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTrailMorphologyDiagnosticsTest {
    @Test fun elongatedSourceIsMeasuredInOutputCoordinates() {
        val result = runSynthetic(
            transforms = List(10) { ReferenceToSourceTransform.Identity },
            objectAt = { ReplayPoint(64.0, 64.0) },
            sigmaMajor = 3.0,
            sigmaMinor = 0.9,
            angle = 0.0
        )
        assertEquals(ReplayTrailMorphologyClass.INTRA_FRAME_TRAIL, result.trails.single().classification)
        val component = result.trails.single().skyTrack.observations.values.first()
        assertNotNull(component.output)
        assertTrue(checkNotNull(component.output).sigmaMajor > checkNotNull(component.output).sigmaMinor * 1.5)
        assertTrue(checkNotNull(component.jacobianDeterminant) > 0.0)
        assertTrue(checkNotNull(component.jacobianConditionNumber) < 1.01)
        assertFalse(component.fitResidual.isNaN())
    }

    @Test fun covarianceAxesUseCentroidJacobianForRotationAndScale() {
        val transform = ReferenceToSourceTransform(
            dx = 2f,
            dy = -1f,
            rotationRadians = 0.20f,
            scale = 1.20f,
            rotationCenterX = 64f,
            rotationCenterY = 64f
        )
        val sourceCenter = ReplayTransformSemantics.outputToSource(transform, 64.0, 64.0)
        val result = runSynthetic(
            transforms = List(10) { transform },
            objectAt = { sourceCenter },
            sigmaMajor = 3.0,
            sigmaMinor = 0.9,
            angle = 0.20,
            controlsFollowTransforms = true
        )
        val component = result.trails.single().skyTrack.observations.values.first()
        val output = checkNotNull(component.output)
        assertTrue(output.sigmaMajor < component.source.sigmaMajor)
        assertTrue(kotlin.math.abs(output.sigmaMajor / component.source.sigmaMajor - 1.0 / 1.2) < 0.05)
        assertTrue(kotlin.math.abs(checkNotNull(component.jacobianDeterminant) - 1.0 / 1.44) < 0.01)
    }

    @Test fun compactSourceWithOutputDriftIsInterFrameMisregistration() {
        val result = runSynthetic(
            transforms = List(10) { ReferenceToSourceTransform.Identity },
            objectAt = { index -> ReplayPoint(61.0 + index * 0.65, 64.0) },
            sigmaMajor = 1.15,
            sigmaMinor = 1.05,
            angle = 0.0
        )
        val trail = result.trails.single()
        assertEquals(ReplayTrailMorphologyClass.INTER_FRAME_MISREGISTRATION, trail.classification)
        assertTrue(trail.outputCentroidDriftSignificant)
        assertEquals(false, trail.sourceElongationSignificant)
    }

    @Test fun missingControlPsfDoesNotBlockOutputDriftClassification() {
        val result = runSynthetic(
            transforms = List(10) { ReferenceToSourceTransform.Identity },
            objectAt = { index -> ReplayPoint(61.0 + index * 0.65, 64.0) },
            sigmaMajor = 1.15,
            sigmaMinor = 1.05,
            angle = 0.0,
            includeControls = false
        )
        val trail = result.trails.single()
        assertEquals(ReplayTrailMorphologyClass.INTER_FRAME_MISREGISTRATION, trail.classification)
        assertEquals(null, trail.sourceElongationSignificant)
        assertTrue("intra_morphology_unavailable" in trail.caveats)
    }

    @Test fun elongatedSourceWithOutputDriftIsMixed() {
        val result = runSynthetic(
            transforms = List(10) { ReferenceToSourceTransform.Identity },
            objectAt = { index -> ReplayPoint(61.0 + index * 0.65, 64.0) },
            sigmaMajor = 3.0,
            sigmaMinor = 0.9,
            angle = 0.0
        )
        assertEquals(ReplayTrailMorphologyClass.MIXED, result.trails.single().classification)
    }

    @Test fun independentStableCameraTrackTakesPrecedence() {
        val transforms = List(10) { index -> ReferenceToSourceTransform(dx = index.toFloat(), dy = 0f) }
        val result = runSynthetic(
            transforms = transforms,
            objectAt = { ReplayPoint(64.0, 64.0) },
            sigmaMajor = 1.15,
            sigmaMinor = 1.05,
            angle = 0.0,
            annotation = ReplayManualTrailAnnotation(
                "trail", listOf(ReplayPoint(52.0, 64.0), ReplayPoint(64.0, 64.0)), "camera fixed"
            ),
            controlsFollowTransforms = true
        )
        val trail = result.trails.single()
        assertEquals(ReplayTrailMorphologyClass.CAMERA_FIXED_DEFECT, trail.classification)
        assertTrue(trail.cameraTrack.coherent)
        assertTrue(trail.cameraCentroidRms <= ReplayStage1DThresholds.CAMERA_MAX_RMS)
    }

    @Test fun repeatedSearchBoundaryContactIsInconclusive() {
        val result = runSynthetic(
            transforms = List(10) { ReferenceToSourceTransform.Identity },
            objectAt = { ReplayPoint(84.0, 64.0) },
            sigmaMajor = 1.2,
            sigmaMinor = 1.0,
            angle = 0.0
        )
        val trail = result.trails.single()
        assertEquals(ReplayTrailMorphologyClass.UNEXPLAINED, trail.classification)
        assertTrue(trail.searchInconclusive)
        assertTrue("search_inconclusive" in trail.caveats)
    }

    @Test fun manifestIsFrozenBeforeRealReplay() {
        val result = runSynthetic(
            transforms = List(3) { ReferenceToSourceTransform.Identity },
            objectAt = { ReplayPoint(64.0, 64.0) },
            sigmaMajor = 2.5,
            sigmaMinor = 1.0,
            angle = 0.0
        )
        assertEquals(ReplayStage1DThresholds.EXPECTED_MANIFEST_SHA256, result.manifestHash)
    }

    private fun runSynthetic(
        transforms: List<ReferenceToSourceTransform>,
        objectAt: (Int) -> ReplayPoint,
        sigmaMajor: Double,
        sigmaMinor: Double,
        angle: Double,
        annotation: ReplayManualTrailAnnotation = ReplayManualTrailAnnotation(
            "trail", listOf(ReplayPoint(60.0, 64.0), ReplayPoint(68.0, 64.0)), "synthetic"
        ),
        controlsFollowTransforms: Boolean = false,
        includeControls: Boolean = true
    ): ReplayStage1DBundle {
        val width = 128
        val height = 128
        val controlStars = listOf(
            ReplayControlStar(46.0, 46.0),
            ReplayControlStar(82.0, 46.0),
            ReplayControlStar(46.0, 82.0),
            ReplayControlStar(82.0, 82.0)
        )
        val frames = transforms.mapIndexed { index, transform ->
            ReplayProvenanceFrame("f$index", index, transform, if (index == 4) 1.75f else 1f)
        }
        val images = frames.associate { frame ->
            val image = uniform(width, height, 10)
            val center = objectAt(frame.captureIndex)
            addGaussian(image, center.x, center.y, sigmaMajor, sigmaMinor, angle, 115.0)
            controlStars.takeIf { includeControls }.orEmpty().forEach { control ->
                val point = if (controlsFollowTransforms) {
                    ReplayTransformSemantics.outputToSource(frame.transform, control.outputX, control.outputY)
                } else ReplayPoint(control.outputX, control.outputY)
                addGaussian(image, point.x, point.y, 1.2, 1.1, 0.0, 125.0)
            }
            frame.id to image
        }
        val root = Files.createTempDirectory("stage1d-synthetic")
        return try {
            ReplayTrailMorphologyDiagnosticRunner().run(
                baseline = uniform(width, height, 10),
                normalWeightReferenceCandidate = null,
                annotations = listOf(annotation),
                frames = frames,
                controlStars = controlStars.takeIf { includeControls }.orEmpty(),
                referenceFrameId = frames[frames.size / 2].id,
                outputRoot = root,
                imageLoader = { images.getValue(it.id) }
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun uniform(width: Int, height: Int, value: Int): ArgbPixelImage =
        ArgbPixelImage(width, height, IntArray(width * height) { rgb(value, value, value) })

    private fun addGaussian(
        image: ArgbPixelImage,
        centerX: Double,
        centerY: Double,
        sigmaMajor: Double,
        sigmaMinor: Double,
        angle: Double,
        amplitude: Double
    ) {
        val radius = 10
        val cosine = cos(angle)
        val sine = sin(angle)
        for (y in (centerY.toInt() - radius)..(centerY.toInt() + radius)) {
            for (x in (centerX.toInt() - radius)..(centerX.toInt() + radius)) {
                if (x !in 0 until image.width || y !in 0 until image.height) continue
                val dx = x - centerX
                val dy = y - centerY
                val major = cosine * dx + sine * dy
                val minor = -sine * dx + cosine * dy
                val value = 10 + amplitude * exp(-0.5 * (major * major / (sigmaMajor * sigmaMajor) + minor * minor / (sigmaMinor * sigmaMinor)))
                val code = value.roundToInt().coerceIn(0, 255)
                image.pixels[y * image.width + x] = rgb(code, code, code)
            }
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}
