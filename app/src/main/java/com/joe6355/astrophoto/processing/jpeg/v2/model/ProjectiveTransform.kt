package com.joe6355.astrophoto.processing.jpeg.v2.model

import kotlin.math.abs

/** Immutable output-to-source homography, normalized so its bottom-right coefficient is one. */
data class ProjectiveTransform(
    val xx: Double, val xy: Double, val tx: Double,
    val yx: Double, val yy: Double, val ty: Double,
    val px: Double, val py: Double
) : java.io.Serializable {
    init {
        require(coefficients().all { it.isFinite() })
        require(abs(determinant()) > 1e-12) { "Singular projective transform" }
    }

    fun coefficients(): List<Double> = listOf(xx, xy, tx, yx, yy, ty, px, py, 1.0)

    fun denominator(x: Double, y: Double): Double = px * x + py * y + 1.0

    fun sourceX(x: Float, y: Float): Float =
        ((xx * x + xy * y + tx) / denominator(x.toDouble(), y.toDouble())).toFloat()

    fun sourceY(x: Float, y: Float): Float =
        ((yx * x + yy * y + ty) / denominator(x.toDouble(), y.toDouble())).toFloat()

    fun map(x: Float, y: Float): TransformPoint = TransformPoint(sourceX(x, y), sourceY(x, y))

    /** Row-major 2x2 derivative, used to reject zoom, reflection and unstable extrapolation. */
    fun jacobian(x: Double, y: Double): DoubleArray {
        val w = denominator(x, y)
        val u = (xx * x + xy * y + tx) / w
        val v = (yx * x + yy * y + ty) / w
        return doubleArrayOf((xx - u * px) / w, (xy - u * py) / w,
            (yx - v * px) / w, (yy - v * py) / w)
    }

    fun inverse(): ProjectiveTransform = fromMatrix(doubleArrayOf(
        yy - ty * py, tx * py - xy, xy * ty - tx * yy,
        ty * px - yx, xx - tx * px, tx * yx - xx * ty,
        yx * py - yy * px, xy * px - xx * py, xx * yy - xy * yx
    ))

    /** this(after(point)); both matrices map output coordinates into their source plane. */
    fun composedWith(after: ProjectiveTransform): ProjectiveTransform {
        val a = coefficients(); val b = after.coefficients()
        val product = DoubleArray(9)
        for (row in 0..2) for (column in 0..2) for (k in 0..2) {
            product[row * 3 + column] += a[row * 3 + k] * b[k * 3 + column]
        }
        return fromMatrix(product)
    }

    fun scaled(sx: Float, sy: Float): ProjectiveTransform = ProjectiveTransform(
        xx, xy * sx / sy, tx * sx, yx * sy / sx, yy, ty * sy, px / sx, py / sy
    )

    private fun determinant(): Double =
        xx * (yy - ty * py) - xy * (yx - ty * px) + tx * (yx * py - yy * px)

    companion object {
        fun fromMatrix(values: DoubleArray): ProjectiveTransform {
            require(values.size == 9 && values.all { it.isFinite() } && abs(values[8]) > 1e-12)
            val w = values[8]
            return ProjectiveTransform(values[0] / w, values[1] / w, values[2] / w,
                values[3] / w, values[4] / w, values[5] / w, values[6] / w, values[7] / w)
        }
    }
}
