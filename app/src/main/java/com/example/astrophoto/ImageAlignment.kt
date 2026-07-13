package com.example.astrophoto

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class ArgbPixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val expected = width.toLong() * height.toLong()
        require(expected <= Int.MAX_VALUE && pixels.size == expected.toInt()) {
            "Image pixel count does not match its dimensions"
        }
    }

    fun pixelAt(x: Int, y: Int): Int = pixels[y * width + x]
}

internal data class AlignmentReference(
    val grayscale: ByteArray,
    val width: Int,
    val height: Int,
    val scaleX: Float,
    val scaleY: Float
)

internal data class AlignmentShift(
    val dx: Int,
    val dy: Int,
    val score: Double = 0.0,
    val confidence: Double = 1.0
) {
    val isZero: Boolean
        get() = dx == 0 && dy == 0

    companion object {
        val Zero = AlignmentShift(0, 0)
    }
}

internal data class ShiftedCopyRegion(
    val sourceX: Int,
    val sourceY: Int,
    val destinationX: Int,
    val destinationY: Int,
    val width: Int,
    val height: Int
)

internal fun createAlignmentReference(
    image: ArgbPixelImage,
    scaleX: Float = 1f,
    scaleY: Float = 1f
): AlignmentReference {
    val grayscale = ByteArray(image.pixels.size)
    var minimum = 255
    var maximum = 0
    image.pixels.forEachIndexed { index, color ->
        val value = pixelLuminance(color)
        grayscale[index] = value.toByte()
        minimum = minOf(minimum, value)
        maximum = maxOf(maximum, value)
    }
    require(maximum - minimum >= 8) { "Not enough image detail for alignment" }
    require(scaleX > 0f && scaleY > 0f) { "Alignment scale must be positive" }
    return AlignmentReference(grayscale, image.width, image.height, scaleX, scaleY)
}

internal fun findImageAlignment(
    reference: AlignmentReference,
    candidate: AlignmentReference,
    maxShiftPx: Int = 30
): AlignmentShift {
    require(reference.width == candidate.width && reference.height == candidate.height) {
        "Alignment image dimensions must match"
    }
    require(maxShiftPx >= 0) { "Maximum alignment shift must not be negative" }
    val maxDx = ceil(maxShiftPx / reference.scaleX).toInt().coerceAtLeast(1)
    val maxDy = ceil(maxShiftPx / reference.scaleY).toInt().coerceAtLeast(1)
    val sampleStep = maxOf(3, minOf(8, maxOf(maxDx, maxDy) / 4 + 2))
    var bestScore = Double.MAX_VALUE
    var bestDx = 0
    var bestDy = 0
    var zeroScore = Double.MAX_VALUE

    for (dy in -maxDy..maxDy) {
        val startY = maxOf(2, -dy + 2)
        val endY = minOf(reference.height - 2, reference.height - dy - 2)
        if (endY <= startY) continue
        for (dx in -maxDx..maxDx) {
            val startX = maxOf(2, -dx + 2)
            val endX = minOf(reference.width - 2, reference.width - dx - 2)
            if (endX <= startX) continue
            var difference = 0L
            var samples = 0
            var y = startY
            while (y < endY) {
                var x = startX
                val referenceRow = y * reference.width
                val candidateRow = (y + dy) * candidate.width
                while (x < endX) {
                    val a = reference.grayscale[referenceRow + x].toInt() and 0xFF
                    val b = candidate.grayscale[candidateRow + x + dx].toInt() and 0xFF
                    difference += abs(a - b)
                    samples++
                    x += sampleStep
                }
                y += sampleStep
            }
            if (samples == 0) continue
            val score = difference.toDouble() / samples
            if (dx == 0 && dy == 0) zeroScore = score
            val distance = abs(dx) + abs(dy)
            val bestDistance = abs(bestDx) + abs(bestDy)
            if (score < bestScore || (score == bestScore && distance < bestDistance)) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }
    }
    require(bestScore < Double.MAX_VALUE) { "Unable to find image shift" }
    val scaledDx = (bestDx * reference.scaleX).roundToInt().coerceIn(-maxShiftPx, maxShiftPx)
    val scaledDy = (bestDy * reference.scaleY).roundToInt().coerceIn(-maxShiftPx, maxShiftPx)
    val confidence = if (scaledDx == 0 && scaledDy == 0) {
        1.0
    } else {
        val baseline = zeroScore.takeIf { it < Double.MAX_VALUE } ?: bestScore
        val improvement = if (baseline <= 0.0) {
            1.0
        } else {
            ((baseline - bestScore) / baseline).coerceIn(0.0, 1.0)
        }
        minOf(improvement, alignmentCorrelation(reference, candidate, bestDx, bestDy))
    }
    return AlignmentShift(scaledDx, scaledDy, bestScore, confidence)
}

private fun alignmentCorrelation(
    reference: AlignmentReference,
    candidate: AlignmentReference,
    dx: Int,
    dy: Int
): Double {
    val startX = maxOf(2, -dx + 2)
    val endX = minOf(reference.width - 2, reference.width - dx - 2)
    val startY = maxOf(2, -dy + 2)
    val endY = minOf(reference.height - 2, reference.height - dy - 2)
    if (endX <= startX || endY <= startY) return 0.0
    val validationStep = maxOf(1, minOf(reference.width, reference.height) / 200)
    var count = 0L
    var sumA = 0.0
    var sumB = 0.0
    var sumAA = 0.0
    var sumBB = 0.0
    var sumAB = 0.0
    var y = startY
    while (y < endY) {
        var x = startX
        while (x < endX) {
            val a = (reference.grayscale[y * reference.width + x].toInt() and 0xFF).toDouble()
            val b = (candidate.grayscale[(y + dy) * candidate.width + x + dx].toInt() and 0xFF).toDouble()
            count++
            sumA += a
            sumB += b
            sumAA += a * a
            sumBB += b * b
            sumAB += a * b
            x += validationStep
        }
        y += validationStep
    }
    if (count < 2) return 0.0
    val covariance = count * sumAB - sumA * sumB
    val varianceA = count * sumAA - sumA * sumA
    val varianceB = count * sumBB - sumB * sumB
    val denominator = kotlin.math.sqrt(varianceA * varianceB)
    if (denominator <= 0.0) return 0.0
    val correlation = (covariance / denominator).coerceIn(0.0, 1.0)
    val randomCorrelationMargin = 3.0 / kotlin.math.sqrt(count.toDouble())
    return (correlation - randomCorrelationMargin).coerceIn(0.0, 1.0)
}

internal fun selectManualAlignment(shift: AlignmentShift, safeMode: Boolean): AlignmentShift =
    if (
        safeMode && !shift.isZero &&
        (shift.confidence < MIN_SAFE_ALIGNMENT_CONFIDENCE || shift.score > MAX_SAFE_ALIGNMENT_SCORE)
    ) {
        AlignmentShift.Zero
    } else {
        shift
    }

internal fun alignArgbImages(
    reference: ArgbPixelImage,
    candidate: ArgbPixelImage,
    safeMode: Boolean,
    maxShiftPx: Int = 30
): AlignmentShift = runCatching {
    selectManualAlignment(
        findImageAlignment(
            createAlignmentReference(reference),
            createAlignmentReference(candidate),
            maxShiftPx
        ),
        safeMode
    )
}.getOrDefault(AlignmentShift.Zero)

internal fun shiftedCopyRegion(
    sourceWidth: Int,
    sourceHeight: Int,
    destinationTop: Int,
    destinationRows: Int,
    dx: Int,
    dy: Int
): ShiftedCopyRegion? {
    val destinationX = maxOf(0, -dx)
    val destinationRight = minOf(sourceWidth, sourceWidth - dx)
    val destinationY = maxOf(destinationTop, -dy)
    val destinationBottom = minOf(destinationTop + destinationRows, sourceHeight - dy)
    val width = destinationRight - destinationX
    val height = destinationBottom - destinationY
    if (width <= 0 || height <= 0) return null
    return ShiftedCopyRegion(
        destinationX + dx,
        destinationY + dy,
        destinationX,
        destinationY,
        width,
        height
    )
}

internal fun commonAlignedRegion(
    width: Int,
    height: Int,
    shifts: List<AlignmentShift>
): PixelRect {
    require(width > 0 && height > 0) { "Aligned image dimensions must be positive" }
    require(shifts.isNotEmpty()) { "At least one alignment shift is required" }
    var left = 0
    var top = 0
    var right = width
    var bottom = height
    shifts.forEach { shift ->
        left = maxOf(left, maxOf(0, -shift.dx))
        top = maxOf(top, maxOf(0, -shift.dy))
        right = minOf(right, minOf(width, width - shift.dx))
        bottom = minOf(bottom, minOf(height, height - shift.dy))
    }
    require(right > left && bottom > top) { "Alignment shifts have no common image area" }
    return PixelRect(left, top, right, bottom)
}

internal fun applyImageShift(
    image: ArgbPixelImage,
    dx: Int,
    dy: Int,
    fillColor: Int = 0xFF000000.toInt()
): ArgbPixelImage {
    val output = IntArray(image.pixels.size) { fillColor }
    val region = shiftedCopyRegion(image.width, image.height, 0, image.height, dx, dy)
        ?: return ArgbPixelImage(image.width, image.height, output)
    for (row in 0 until region.height) {
        image.pixels.copyInto(
            output,
            (region.destinationY + row) * image.width + region.destinationX,
            (region.sourceY + row) * image.width + region.sourceX,
            (region.sourceY + row) * image.width + region.sourceX + region.width
        )
    }
    return ArgbPixelImage(image.width, image.height, output)
}

internal fun pixelLuminance(color: Int): Int {
    val red = color ushr 16 and 0xFF
    val green = color ushr 8 and 0xFF
    val blue = color and 0xFF
    return (red * 77 + green * 150 + blue * 29) ushr 8
}

private const val MIN_SAFE_ALIGNMENT_CONFIDENCE = 0.02
private const val MAX_SAFE_ALIGNMENT_SCORE = 55.0
