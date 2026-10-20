package com.joe6355.astrophoto.processing.jpeg.v2.registration

import com.joe6355.astrophoto.processing.jpeg.v2.model.ProjectiveTransform
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Three rotation parameters with a fixed lens estimate: H = K R K^-1, without zoom or shear. */
internal class PerspectiveRotationGeometry private constructor(
    val width: Int,
    val height: Int,
    val focalPixels: Double
) {
    private val cx = (width - 1) * 0.5
    private val cy = (height - 1) * 0.5
    private val intrinsic = doubleArrayOf(focalPixels, 0.0, cx, 0.0, focalPixels, cy, 0.0, 0.0, 1.0)
    private val inverseIntrinsic = doubleArrayOf(
        1 / focalPixels, 0.0, -cx / focalPixels,
        0.0, 1 / focalPixels, -cy / focalPixels, 0.0, 0.0, 1.0
    )

    fun fit(matches: List<StellarCentroidMatch>, cancellationCheck: () -> Unit): ProjectiveTransform? {
        if (matches.size < 3) return null
        var rotation = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (iteration in 0 until 10) {
            cancellationCheck()
            val normal = Array(3) { DoubleArray(4) }
            fun add(j: DoubleArray, residual: Double) {
                for (row in 0..2) {
                    for (column in 0..2) normal[row][column] += j[row] * j[column]
                    normal[row][3] += j[row] * residual
                }
            }
            for (match in matches) {
                val x = (match.reference.x - cx) / focalPixels
                val y = (match.reference.y - cy) / focalPixels
                val z = rotation[6] * x + rotation[7] * y + rotation[8]
                if (!z.isFinite() || z <= 0.0) return null
                val u = (rotation[0] * x + rotation[1] * y + rotation[2]) / z
                val v = (rotation[3] * x + rotation[4] * y + rotation[5]) / z
                add(doubleArrayOf(-u * v, 1 + u * u, -v),
                    (match.candidate.x - cx) / focalPixels - u)
                add(doubleArrayOf(-1 - v * v, u * v, u),
                    (match.candidate.y - cy) / focalPixels - v)
            }
            val delta = solve(normal) ?: return null
            val angle = sqrt(delta.sumOf { it * it })
            if (!angle.isFinite() || angle > 0.1) return null
            val skew = doubleArrayOf(0.0, -delta[2], delta[1], delta[2], 0.0, -delta[0], -delta[1], delta[0], 0.0)
            val square = multiply(skew, skew)
            val a = if (angle < 1e-10) 1.0 else sin(angle) / angle
            val b = if (angle < 1e-10) 0.5 else (1 - cos(angle)) / (angle * angle)
            val increment = DoubleArray(9) { i -> (if (i % 4 == 0) 1.0 else 0.0) + a * skew[i] + b * square[i] }
            rotation = multiply(increment, rotation)
            if (angle < 1e-10) break
        }
        val angle = acos(((rotation[0] + rotation[4] + rotation[8] - 1) * 0.5).coerceIn(-1.0, 1.0))
        if (angle > StarSimilarityRegistrar.MAX_AUTOMATIC_ROTATION_RADIANS) return null
        val h = multiply(multiply(intrinsic, rotation), inverseIntrinsic)
        if (h.any { !it.isFinite() } || abs(h[8]) < 1e-9) return null
        val n = DoubleArray(8) { h[it] / h[8] }
        return runCatching { ProjectiveTransform(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7]) }.getOrNull()
    }

    private fun solve(normal: Array<DoubleArray>): DoubleArray? {
        for (column in 0..2) {
            val pivot = (column..2).maxBy { abs(normal[it][column]) }
            if (abs(normal[pivot][column]) < 1e-10) return null
            val swap = normal[column]; normal[column] = normal[pivot]; normal[pivot] = swap
            val divisor = normal[column][column]
            for (j in column..3) normal[column][j] /= divisor
            for (row in 0..2) if (row != column) {
                val factor = normal[row][column]
                for (j in column..3) normal[row][j] -= factor * normal[column][j]
            }
        }
        return DoubleArray(3) { normal[it][3] }.takeIf { it.all(Double::isFinite) }
    }

    private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val row = i / 3; val column = i % 3
        a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column]
    }

    companion object {
        fun fromFocalPixels(width: Int, height: Int, focalPixels: Double?): PerspectiveRotationGeometry? =
            focalPixels?.takeIf { width > 1 && height > 1 && it.isFinite() && it > 0.0 }
                ?.let { PerspectiveRotationGeometry(width, height, it) }

        // EXIF is an estimate, not a calibration. Independent star predictions must still pass.
        fun from35mmEquivalent(width: Int, height: Int, equivalentMm: Double?): PerspectiveRotationGeometry? =
            equivalentMm?.takeIf { it.isFinite() && it > 0.0 }?.let {
                fromFocalPixels(width, height, it * hypot(width.toDouble(), height.toDouble()) / hypot(36.0, 24.0))
            }
    }
}
