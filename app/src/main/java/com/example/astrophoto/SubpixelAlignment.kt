package com.example.astrophoto

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SubpixelShift(
    val dx: Float,
    val dy: Float,
    val correlation: Float = 1f
) {
    val isZero: Boolean
        get() = dx == 0f && dy == 0f

    val isReliable: Boolean
        get() = correlation >= MIN_ACCEPTED_ALIGNMENT_CORRELATION

    companion object {
        val Zero = SubpixelShift(0f, 0f)
    }
}

data class LinearStackResult(
    val image: LinearRgbImage,
    val shifts: List<SubpixelShift>
)

fun findSubpixelAlignment(
    reference: LinearRgbImage,
    candidate: LinearRgbImage,
    maxShiftPixels: Int = 32
): SubpixelShift {
    require(reference.width == candidate.width && reference.height == candidate.height) {
        "Subpixel alignment requires equal image dimensions"
    }
    require(maxShiftPixels >= 0) { "Subpixel alignment range must not be negative" }
    if (maxShiftPixels == 0) return SubpixelShift.Zero

    val referenceCoarse = alignmentPlane(reference, MAX_COARSE_ALIGNMENT_DIMENSION)
    val candidateCoarse = alignmentPlane(candidate, MAX_COARSE_ALIGNMENT_DIMENSION)
    val maxDx = ceil(maxShiftPixels / referenceCoarse.scaleX).toInt().coerceAtLeast(1)
    val maxDy = ceil(maxShiftPixels / referenceCoarse.scaleY).toInt().coerceAtLeast(1)
    var bestDx = 0
    var bestDy = 0
    var bestCorrelation = -1f
    for (dy in -maxDy..maxDy) {
        for (dx in -maxDx..maxDx) {
            val correlation = planeCorrelation(
                referenceCoarse,
                candidateCoarse,
                dx.toFloat(),
                dy.toFloat(),
                sampleStep = 2
            )
            val distance = kotlin.math.abs(dx) + kotlin.math.abs(dy)
            val bestDistance = kotlin.math.abs(bestDx) + kotlin.math.abs(bestDy)
            if (
                correlation > bestCorrelation ||
                (correlation == bestCorrelation && distance < bestDistance)
            ) {
                bestCorrelation = correlation
                bestDx = dx
                bestDy = dy
            }
        }
    }

    var refinedDx = (bestDx * referenceCoarse.scaleX).coerceIn(
        -maxShiftPixels.toFloat(),
        maxShiftPixels.toFloat()
    )
    var refinedDy = (bestDy * referenceCoarse.scaleY).coerceIn(
        -maxShiftPixels.toFloat(),
        maxShiftPixels.toFloat()
    )
    var refinedCorrelation = linearImageCorrelation(
        reference,
        candidate,
        refinedDx,
        refinedDy
    )
    var step = max(referenceCoarse.scaleX, referenceCoarse.scaleY) / 2f
    while (step >= MIN_SUBPIXEL_REFINEMENT_STEP) {
        var levelDx = refinedDx
        var levelDy = refinedDy
        var levelCorrelation = refinedCorrelation
        for (offsetY in -1..1) {
            for (offsetX in -1..1) {
                val dx = (refinedDx + offsetX * step).coerceIn(
                    -maxShiftPixels.toFloat(),
                    maxShiftPixels.toFloat()
                )
                val dy = (refinedDy + offsetY * step).coerceIn(
                    -maxShiftPixels.toFloat(),
                    maxShiftPixels.toFloat()
                )
                val correlation = linearImageCorrelation(reference, candidate, dx, dy)
                if (correlation > levelCorrelation) {
                    levelCorrelation = correlation
                    levelDx = dx
                    levelDy = dy
                }
            }
        }
        refinedDx = levelDx
        refinedDy = levelDy
        refinedCorrelation = levelCorrelation
        step /= 2f
    }

    val zeroCorrelation = linearImageCorrelation(reference, candidate, 0f, 0f)
    if (refinedCorrelation < MIN_ACCEPTED_ALIGNMENT_CORRELATION) {
        return SubpixelShift(0f, 0f, refinedCorrelation)
    }
    if (refinedCorrelation < zeroCorrelation + MIN_ALIGNMENT_IMPROVEMENT) {
        return SubpixelShift(0f, 0f, zeroCorrelation)
    }
    return SubpixelShift(refinedDx, refinedDy, refinedCorrelation)
}

fun averageAlignedLinearFrames(
    frames: List<LinearRgbImage>,
    align: Boolean = true,
    maxShiftPixels: Int = 32
): LinearStackResult {
    require(frames.isNotEmpty()) { "Linear stack requires at least one frame" }
    val first = frames.first()
    require(frames.all { it.width == first.width && it.height == first.height }) {
        "Linear stack requires equal frame dimensions"
    }
    var average = LinearRgbImage(first.width, first.height, first.pixels.copyOf())
    val shifts = mutableListOf(SubpixelShift.Zero)
    frames.drop(1).forEachIndexed { index, frame ->
        val shift = if (align) {
            findSubpixelAlignment(first, frame, maxShiftPixels)
        } else {
            SubpixelShift.Zero
        }
        shifts += shift
        addShiftedLinearAverageInPlace(average, frame, index + 2, shift)
    }
    average = cropLinearToCommonRegion(average, shifts)
    return LinearStackResult(average, shifts)
}

internal fun addShiftedLinearAverageInPlace(
    average: LinearRgbImage,
    next: LinearRgbImage,
    frameNumber: Int,
    shift: SubpixelShift
) {
    require(average.width == next.width && average.height == next.height)
    require(frameNumber >= 2)
    val weightOld = (frameNumber - 1).toFloat() / frameNumber
    val weightNew = 1f / frameNumber
    for (y in 0 until average.height) {
        for (x in 0 until average.width) {
            val sourceX = x + shift.dx
            val sourceY = y + shift.dy
            if (!canSampleBilinear(next, sourceX, sourceY)) continue
            val target = (y * average.width + x) * 3
            for (channel in 0..2) {
                val value = bilinearChannel(next, sourceX, sourceY, channel)
                average.pixels[target + channel] =
                    average.pixels[target + channel] * weightOld + value * weightNew
            }
        }
    }
}

fun shiftLinearImage(image: LinearRgbImage, shift: SubpixelShift): LinearRgbImage {
    val output = FloatArray(image.pixels.size)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val sourceX = x + shift.dx
            val sourceY = y + shift.dy
            if (!canSampleBilinear(image, sourceX, sourceY)) continue
            val target = (y * image.width + x) * 3
            for (channel in 0..2) {
                output[target + channel] = bilinearChannel(image, sourceX, sourceY, channel)
            }
        }
    }
    return LinearRgbImage(image.width, image.height, output)
}

internal fun cropLinearToCommonRegion(
    image: LinearRgbImage,
    shifts: List<SubpixelShift>
): LinearRgbImage {
    require(shifts.isNotEmpty())
    var left = 0
    var top = 0
    var right = image.width
    var bottom = image.height
    shifts.forEach { shift ->
        left = maxOf(left, ceil(-shift.dx).toInt())
        top = maxOf(top, ceil(-shift.dy).toInt())
        right = minOf(right, floor(image.width - shift.dx).toInt())
        bottom = minOf(bottom, floor(image.height - shift.dy).toInt())
    }
    left = left.coerceIn(0, image.width - 1)
    top = top.coerceIn(0, image.height - 1)
    right = right.coerceIn(left + 1, image.width)
    bottom = bottom.coerceIn(top + 1, image.height)
    if (left == 0 && top == 0 && right == image.width && bottom == image.height) return image

    val width = right - left
    val height = bottom - top
    val output = FloatArray(width * height * 3)
    for (y in 0 until height) {
        val source = ((top + y) * image.width + left) * 3
        val target = y * width * 3
        image.pixels.copyInto(output, target, source, source + width * 3)
    }
    return LinearRgbImage(width, height, output)
}

private data class AlignmentPlane(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val scaleX: Float,
    val scaleY: Float
)

private fun alignmentPlane(image: LinearRgbImage, maxDimension: Int): AlignmentPlane {
    val scale = maxOf(1, ceil(maxOf(image.width, image.height) / maxDimension.toDouble()).toInt())
    val width = maxOf(1, image.width / scale)
    val height = maxOf(1, image.height / scale)
    val values = FloatArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var sum = 0f
            var count = 0
            val top = y * scale
            val left = x * scale
            for (sourceY in top until minOf(image.height, top + scale)) {
                for (sourceX in left until minOf(image.width, left + scale)) {
                    val index = (sourceY * image.width + sourceX) * 3
                    sum += image.pixels[index] * 0.2126f +
                        image.pixels[index + 1] * 0.7152f +
                        image.pixels[index + 2] * 0.0722f
                    count++
                }
            }
            values[y * width + x] = sum / count.coerceAtLeast(1)
        }
    }
    return AlignmentPlane(
        width,
        height,
        values,
        image.width.toFloat() / width,
        image.height.toFloat() / height
    )
}

private fun planeCorrelation(
    reference: AlignmentPlane,
    candidate: AlignmentPlane,
    dx: Float,
    dy: Float,
    sampleStep: Int
): Float {
    var count = 0
    var sumA = 0.0
    var sumB = 0.0
    var sumAA = 0.0
    var sumBB = 0.0
    var sumAB = 0.0
    var y = 1
    while (y < reference.height - 1) {
        var x = 1
        while (x < reference.width - 1) {
            val sourceX = x + dx
            val sourceY = y + dy
            if (canSamplePlane(candidate, sourceX, sourceY)) {
                val a = reference.values[y * reference.width + x].toDouble()
                val b = bilinearPlane(candidate, sourceX, sourceY).toDouble()
                count++
                sumA += a
                sumB += b
                sumAA += a * a
                sumBB += b * b
                sumAB += a * b
            }
            x += sampleStep
        }
        y += sampleStep
    }
    return correlation(count, sumA, sumB, sumAA, sumBB, sumAB)
}

private fun linearImageCorrelation(
    reference: LinearRgbImage,
    candidate: LinearRgbImage,
    dx: Float,
    dy: Float
): Float {
    val sampleStep = maxOf(
        2,
        sqrt(reference.width.toLong() * reference.height / 120_000.0).toInt()
    )
    var count = 0
    var sumA = 0.0
    var sumB = 0.0
    var sumAA = 0.0
    var sumBB = 0.0
    var sumAB = 0.0
    var y = 1
    while (y < reference.height - 1) {
        var x = 1
        while (x < reference.width - 1) {
            val sourceX = x + dx
            val sourceY = y + dy
            if (canSampleBilinear(candidate, sourceX, sourceY)) {
                val referenceIndex = (y * reference.width + x) * 3
                val a = (
                    reference.pixels[referenceIndex] * 0.2126f +
                        reference.pixels[referenceIndex + 1] * 0.7152f +
                        reference.pixels[referenceIndex + 2] * 0.0722f
                    ).toDouble()
                val b = (
                    bilinearChannel(candidate, sourceX, sourceY, 0) * 0.2126f +
                        bilinearChannel(candidate, sourceX, sourceY, 1) * 0.7152f +
                        bilinearChannel(candidate, sourceX, sourceY, 2) * 0.0722f
                    ).toDouble()
                count++
                sumA += a
                sumB += b
                sumAA += a * a
                sumBB += b * b
                sumAB += a * b
            }
            x += sampleStep
        }
        y += sampleStep
    }
    return correlation(count, sumA, sumB, sumAA, sumBB, sumAB)
}

private fun correlation(
    count: Int,
    sumA: Double,
    sumB: Double,
    sumAA: Double,
    sumBB: Double,
    sumAB: Double
): Float {
    if (count < 16) return -1f
    val covariance = count * sumAB - sumA * sumB
    val varianceA = count * sumAA - sumA * sumA
    val varianceB = count * sumBB - sumB * sumB
    val denominator = sqrt(varianceA * varianceB)
    return if (denominator <= 0.0) -1f else (covariance / denominator).toFloat().coerceIn(-1f, 1f)
}

private fun canSampleBilinear(image: LinearRgbImage, x: Float, y: Float): Boolean =
    x >= 0f && y >= 0f && x <= image.width - 1f && y <= image.height - 1f

private fun bilinearChannel(image: LinearRgbImage, x: Float, y: Float, channel: Int): Float {
    val x0 = floor(x).toInt().coerceIn(0, image.width - 1)
    val y0 = floor(y).toInt().coerceIn(0, image.height - 1)
    val x1 = minOf(x0 + 1, image.width - 1)
    val y1 = minOf(y0 + 1, image.height - 1)
    val fx = x - x0
    val fy = y - y0
    val top = image.channelAt(x0, y0, channel) * (1f - fx) +
        image.channelAt(x1, y0, channel) * fx
    val bottom = image.channelAt(x0, y1, channel) * (1f - fx) +
        image.channelAt(x1, y1, channel) * fx
    return top * (1f - fy) + bottom * fy
}

private fun canSamplePlane(plane: AlignmentPlane, x: Float, y: Float): Boolean =
    x >= 0f && y >= 0f && x <= plane.width - 1f && y <= plane.height - 1f

private fun bilinearPlane(plane: AlignmentPlane, x: Float, y: Float): Float {
    val x0 = floor(x).toInt().coerceIn(0, plane.width - 1)
    val y0 = floor(y).toInt().coerceIn(0, plane.height - 1)
    val x1 = minOf(x0 + 1, plane.width - 1)
    val y1 = minOf(y0 + 1, plane.height - 1)
    val fx = x - x0
    val fy = y - y0
    val top = plane.values[y0 * plane.width + x0] * (1f - fx) +
        plane.values[y0 * plane.width + x1] * fx
    val bottom = plane.values[y1 * plane.width + x0] * (1f - fx) +
        plane.values[y1 * plane.width + x1] * fx
    return top * (1f - fy) + bottom * fy
}

private const val MAX_COARSE_ALIGNMENT_DIMENSION = 384
private const val MIN_SUBPIXEL_REFINEMENT_STEP = 0.0625f
private const val MIN_ACCEPTED_ALIGNMENT_CORRELATION = 0.15f
private const val MIN_ALIGNMENT_IMPROVEMENT = 0.003f
