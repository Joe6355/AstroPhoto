package com.example.astrophoto.processing.jpeg.v2.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class TransformPoint(val x: Float, val y: Float)

/**
 * Canonical JPEG registration contract.
 *
 * Maps an output/reference coordinate to the coordinate sampled in a candidate/source image.
 * Rotation and scale are applied around the explicit [rotationCenterX], [rotationCenterY].
 */
data class ReferenceToSourceTransform(
    val dx: Float,
    val dy: Float,
    val rotationRadians: Float = 0f,
    val scale: Float = 1f,
    val rotationCenterX: Float = 0f,
    val rotationCenterY: Float = 0f
) {
    init {
        require(
            dx.isFinite() && dy.isFinite() && rotationRadians.isFinite() &&
                scale.isFinite() && scale > 0f &&
                rotationCenterX.isFinite() && rotationCenterY.isFinite()
        )
    }

    fun mapOutputToSource(outputX: Float, outputY: Float): TransformPoint {
        val relativeX = outputX - rotationCenterX
        val relativeY = outputY - rotationCenterY
        val cosine = cos(rotationRadians)
        val sine = sin(rotationRadians)
        return TransformPoint(
            x = scale * (cosine * relativeX - sine * relativeY) + rotationCenterX + dx,
            y = scale * (sine * relativeX + cosine * relativeY) + rotationCenterY + dy
        )
    }

    fun inverse(): SourceToReferenceTransform = SourceToReferenceTransform(this)

    fun scaledToFullResolution(scaleX: Float, scaleY: Float): ReferenceToSourceTransform {
        require(scaleX > 0f && scaleY > 0f && scaleX.isFinite() && scaleY.isFinite())
        require(rotationRadians == 0f || abs(scaleX - scaleY) <= UNIFORM_SCALE_TOLERANCE) {
            "Rotated transforms require uniform analysis-to-full scaling"
        }
        return copy(
            dx = dx * scaleX,
            dy = dy * scaleY,
            rotationCenterX = rotationCenterX * scaleX,
            rotationCenterY = rotationCenterY * scaleY
        )
    }

    fun scaledToAnalysisResolution(scaleX: Float, scaleY: Float): ReferenceToSourceTransform =
        scaledToFullResolution(1f / scaleX, 1f / scaleY)

    fun appliedTwice(): ReferenceToSourceTransform {
        val cosine = cos(rotationRadians)
        val sine = sin(rotationRadians)
        return copy(
            dx = scale * (cosine * dx - sine * dy) + dx,
            dy = scale * (sine * dx + cosine * dy) + dy,
            rotationRadians = rotationRadians * 2f,
            scale = scale * scale
        )
    }

    companion object {
        val Identity = ReferenceToSourceTransform(0f, 0f)
        private const val UNIFORM_SCALE_TOLERANCE = 0.0001f
    }
}

class SourceToReferenceTransform internal constructor(
    private val forward: ReferenceToSourceTransform
) {
    fun mapSourceToOutput(sourceX: Float, sourceY: Float): TransformPoint {
        val translatedX = sourceX - forward.rotationCenterX - forward.dx
        val translatedY = sourceY - forward.rotationCenterY - forward.dy
        val cosine = cos(forward.rotationRadians)
        val sine = sin(forward.rotationRadians)
        return TransformPoint(
            x = (cosine * translatedX + sine * translatedY) / forward.scale +
                forward.rotationCenterX,
            y = (-sine * translatedX + cosine * translatedY) / forward.scale +
                forward.rotationCenterY
        )
    }

    fun inverse(): ReferenceToSourceTransform = forward

    /** Reinterprets the inverse mapping as a deliberately competing output-to-source model. */
    fun asReferenceToSourceTransform(): ReferenceToSourceTransform {
        val cosine = cos(forward.rotationRadians)
        val sine = sin(forward.rotationRadians)
        return ReferenceToSourceTransform(
            dx = -(cosine * forward.dx + sine * forward.dy) / forward.scale,
            dy = -(-sine * forward.dx + cosine * forward.dy) / forward.scale,
            rotationRadians = -forward.rotationRadians,
            scale = 1f / forward.scale,
            rotationCenterX = forward.rotationCenterX,
            rotationCenterY = forward.rotationCenterY
        )
    }
}
