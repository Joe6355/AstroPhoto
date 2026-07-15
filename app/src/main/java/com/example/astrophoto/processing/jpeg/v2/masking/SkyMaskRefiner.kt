package com.example.astrophoto.processing.jpeg.v2.masking

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.composition.MaskFeathering
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.MaskDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.RefinedSkyMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class SkyMaskRefiner(
    private val feathering: MaskFeathering = MaskFeathering()
) {
    fun refine(
        initialMask: SkyMask,
        reference: ArgbPixelImage,
        stars: List<DetectedStar>,
        initialConfidence: Float,
        initialUsedFallback: Boolean,
        registrationConfidence: Float? = null
    ): RefinedSkyMask {
        val width = reference.width
        val height = reference.height
        val total = width * height
        val initial = upscale(initialMask, width, height)
        val initialRatio = initial.count { it }.toFloat() / total
        val luminance = IntArray(total) { pixelLuminance(reference.pixels[it]) }
        val starSuppression = starSuppressionMask(width, height, stars)
        val edges = detectStrongEdges(luminance, width, height)
        val borderStructures = detectBorderStructures(edges, width, height)
        var edgeRejected = 0
        val candidate = BooleanArray(total) { index ->
            if (!initial[index]) return@BooleanArray false
            val color = reference.pixels[index]
            val saturatedObject = luminance[index] >= BRIGHT_OBJECT_LUMINANCE &&
                maxOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF) >= 248
            val rejectEdge = edges[index] && !starSuppression[index]
            val rejected = saturatedObject || rejectEdge || borderStructures[index]
            if (rejected && (rejectEdge || borderStructures[index])) edgeRejected++
            !rejected
        }
        val connected = retainPlausibleUpperComponents(candidate, width, height)
        var removedIslands = 0
        candidate.indices.forEach { if (candidate[it] && !connected[it]) removedIslands++ }
        val holeResult = fillSmallHoles(connected, edges, luminance, width, height)
        var refinedPixels = holeResult.mask
        var refinedRatio = refinedPixels.count { it }.toFloat() / total
        val retainedFraction = if (initialRatio > 0f) refinedRatio / initialRatio else 0f
        val registrationTerm = registrationConfidence?.coerceIn(0f, 1f) ?: 0.65f
        var confidence = (
            initialConfidence.coerceIn(0f, 1f) * 0.42f +
                retainedFraction.coerceIn(0f, 1f) * 0.33f +
                registrationTerm * 0.25f
            ).coerceIn(0f, 1f)
        var usedFallback = initialUsedFallback || confidence < MIN_REFINED_CONFIDENCE
        if (usedFallback && refinedPixels.any { it }) {
            val conservative = erodeOnePixel(refinedPixels, width, height)
            if (conservative.any { it }) refinedPixels = conservative
            refinedRatio = refinedPixels.count { it }.toFloat() / total
            confidence = minOf(confidence, FALLBACK_CONFIDENCE_CEILING)
        }
        if (refinedPixels.none { it }) {
            usedFallback = true
            confidence = 0f
        }
        val binary = SkyMask(width, height, refinedPixels)
        val feathered = feathering.feather(binary)
        val diagnostics = MaskDiagnostics(
            initialSkyRatio = initialRatio,
            refinedSkyRatio = refinedRatio,
            removedIslandPixels = removedIslands,
            filledHolePixels = holeResult.filledPixels,
            edgeRejectedPixels = edgeRejected,
            borderStructurePixels = borderStructures.count { it },
            featherRadius = feathered.broadRadius
        )
        return RefinedSkyMask(
            binaryMask = binary,
            featheredMask = feathered.alphaMask,
            confidence = confidence,
            protectedForegroundRatio = 1f - refinedRatio,
            usedFallback = usedFallback,
            diagnostics = diagnostics
        )
    }

    private data class HoleFillResult(val mask: BooleanArray, val filledPixels: Int)

    private fun upscale(mask: SkyMask, width: Int, height: Int): BooleanArray =
        BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val sourceX = (x.toLong() * mask.width / width).toInt().coerceIn(0, mask.width - 1)
            val sourceY = (y.toLong() * mask.height / height).toInt().coerceIn(0, mask.height - 1)
            mask.contains(sourceX, sourceY)
        }

    private fun detectStrongEdges(luminance: IntArray, width: Int, height: Int): BooleanArray {
        val result = BooleanArray(luminance.size)
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            val left = luminance[y * width + maxOf(0, x - 1)]
            val right = luminance[y * width + minOf(width - 1, x + 1)]
            val top = luminance[maxOf(0, y - 1) * width + x]
            val bottom = luminance[minOf(height - 1, y + 1) * width + x]
            val horizontal = abs(left - right)
            val vertical = abs(top - bottom)
            val local = maxOf(abs(luminance[index] - left), abs(luminance[index] - right),
                abs(luminance[index] - top), abs(luminance[index] - bottom))
            result[index] = maxOf(horizontal, vertical, local) >= STRONG_EDGE_DELTA
        }
        return result
    }

    private fun detectBorderStructures(edges: BooleanArray, width: Int, height: Int): BooleanArray {
        val result = BooleanArray(edges.size)
        val borderBand = max(3, minOf(width, height) / 10)
        val lineRadius = max(1, minOf(width, height) / 500)
        for (x in 0 until width) {
            if (x >= borderBand && x < width - borderBand) continue
            var edgeCount = 0
            for (y in 0 until height) if (edges[y * width + x]) edgeCount++
            if (edgeCount < height * MIN_BORDER_LINE_FRACTION) continue
            for (xx in maxOf(0, x - lineRadius)..minOf(width - 1, x + lineRadius)) {
                for (y in 0 until height) result[y * width + xx] = true
            }
        }
        for (y in 0 until height) {
            if (y >= borderBand && y < height - borderBand) continue
            var edgeCount = 0
            for (x in 0 until width) if (edges[y * width + x]) edgeCount++
            if (edgeCount < width * MIN_BORDER_LINE_FRACTION) continue
            for (yy in maxOf(0, y - lineRadius)..minOf(height - 1, y + lineRadius)) {
                for (x in 0 until width) result[yy * width + x] = true
            }
        }
        return result
    }

    private fun retainPlausibleUpperComponents(
        source: BooleanArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val labels = IntArray(source.size) { -1 }
        val sizes = mutableListOf<Int>()
        val upperHits = mutableListOf<Int>()
        val queue = IntArray(source.size)
        val upperLimit = max(1, (height * UPPER_CONNECTIVITY_LIMIT).roundToInt())
        var label = 0
        source.indices.forEach { start ->
            if (!source[start] || labels[start] >= 0) return@forEach
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var size = 0
            var hits = 0
            while (head < tail) {
                val index = queue[head++]
                size++
                val x = index % width
                val y = index / width
                if (y <= upperLimit) hits++
                fun visit(next: Int) {
                    if (source[next] && labels[next] < 0) {
                        labels[next] = label
                        queue[tail++] = next
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < width) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y + 1 < height) visit(index + width)
            }
            sizes += size
            upperHits += hits
            label++
        }
        if (sizes.isEmpty()) return BooleanArray(source.size)
        val upperComponents = sizes.indices.filter { upperHits[it] > 0 }
        val bestCandidates = upperComponents.ifEmpty { sizes.indices.toList() }
        val best = bestCandidates.maxByOrNull { sizes[it] * 3L + upperHits[it] * 2L } ?: 0
        val minimumArea = max(MIN_COMPONENT_PIXELS, source.size / COMPONENT_AREA_DIVISOR)
        val keep = BooleanArray(sizes.size) { component ->
            component == best || (upperHits[component] > 0 && sizes[component] >= minimumArea)
        }
        return BooleanArray(source.size) { index -> labels[index] >= 0 && keep[labels[index]] }
    }

    private fun fillSmallHoles(
        source: BooleanArray,
        edges: BooleanArray,
        luminance: IntArray,
        width: Int,
        height: Int
    ): HoleFillResult {
        val result = source.copyOf()
        val visited = BooleanArray(source.size)
        val queue = IntArray(source.size)
        val maximumHole = max(MIN_HOLE_PIXELS, source.size / HOLE_AREA_DIVISOR)
        var filled = 0
        source.indices.forEach { start ->
            if (source[start] || visited[start]) return@forEach
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var touchesBorder = false
            var edgeCount = 0
            var brightCount = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) touchesBorder = true
                if (edges[index]) edgeCount++
                if (luminance[index] >= BRIGHT_OBJECT_LUMINANCE) brightCount++
                fun visit(next: Int) {
                    if (!source[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < width) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y + 1 < height) visit(index + width)
            }
            if (
                !touchesBorder && tail <= maximumHole &&
                edgeCount * 4 < tail && brightCount * 8 < tail
            ) {
                for (offset in 0 until tail) result[queue[offset]] = true
                filled += tail
            }
        }
        return HoleFillResult(result, filled)
    }

    private fun erodeOnePixel(source: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(source.size) { index ->
            if (!source[index]) return@BooleanArray false
            val x = index % width
            val y = index / width
            x > 0 && x + 1 < width && y > 0 && y + 1 < height &&
                source[index - 1] && source[index + 1] &&
                source[index - width] && source[index + width]
        }

    private fun starSuppressionMask(
        width: Int,
        height: Int,
        stars: List<DetectedStar>
    ): BooleanArray {
        val result = BooleanArray(width * height)
        stars.forEach { star ->
            val radius = ceil(maxOf(2f, star.width * 2.1f)).toInt()
            val centerX = star.x.roundToInt()
            val centerY = star.y.roundToInt()
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (dx * dx + dy * dy > radius * radius) continue
                val x = centerX + dx
                val y = centerY + dy
                if (x in 0 until width && y in 0 until height) result[y * width + x] = true
            }
        }
        return result
    }

    companion object {
        private const val STRONG_EDGE_DELTA = 26
        private const val BRIGHT_OBJECT_LUMINANCE = 238
        private const val MIN_BORDER_LINE_FRACTION = 0.34f
        private const val UPPER_CONNECTIVITY_LIMIT = 0.18f
        private const val MIN_COMPONENT_PIXELS = 12
        private const val COMPONENT_AREA_DIVISOR = 5000
        private const val MIN_HOLE_PIXELS = 12
        private const val HOLE_AREA_DIVISOR = 1500
        private const val MIN_REFINED_CONFIDENCE = 0.24f
        private const val FALLBACK_CONFIDENCE_CEILING = 0.30f
    }
}
