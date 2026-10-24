package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import kotlin.math.ceil
import kotlin.math.sqrt

data class EnhancedStarPreservationMetrics(
    val baselineRetainedSourceCount: Int,
    val protectedSourceCount: Int,
    val remainingLostSourceCount: Int
) {
    companion object {
        val NONE = EnhancedStarPreservationMetrics(0, 0, 0)
    }
}

internal data class EnhancedStarPreservationResult(
    val image: FileBackedImage,
    val metrics: EnhancedStarPreservationMetrics
)

/** Prevent a visual finish from losing a source retained by the selected stack. */
internal class EnhancedStarSignalPreserver {
    fun preserve(
        candidate: FileBackedImage,
        baseline: FileBackedImage,
        reference: FileBackedImage,
        stars: List<DetectedStar>,
        store: ResultCandidateStore
    ): EnhancedStarPreservationResult {
        require(candidate.width == baseline.width && candidate.height == baseline.height)
        require(reference.width == baseline.width && reference.height == baseline.height)
        FileBackedImageReader(reference, cachedRows = 24).use { original ->
            FileBackedImageReader(baseline, cachedRows = 24).use { selected ->
                FileBackedImageReader(candidate, cachedRows = 24).use { enhanced ->
                    val validator = ReferenceStarRetentionValidator()
                    fun retained(image: LinearRgbPixelSource) = validator.validateSources(original, image, stars)
                        .sources.filter { it.retained }.map { it.sourceIndex }.toSet()
                    val retainedBefore = retained(selected)
                    val retainedEnhanced = retained(enhanced)
                    val required = retainedBefore + retainedEnhanced
                    val protect = retainedBefore - retainedEnhanced
                    if (protect.isEmpty()) return EnhancedStarPreservationResult(
                        candidate, EnhancedStarPreservationMetrics(retainedBefore.size, 0, 0)
                    )
                    var weights: Map<Long, Float> = emptyMap()
                    val protected = protect.toMutableSet()
                    for (index in protect) {
                        // Display quantization makes retention non-monotonic in blend amount.
                        // Keep the first passing finish, rather than assuming a binary search.
                        for (amount in RESTORATION_AMOUNTS) {
                            val proposal = addPatch(weights, stars[index], candidate, amount)
                            val image = BlendedSource(enhanced, selected, proposal)
                            val kept = validator.validateSources(original, image, listOf(stars[index]))
                                .sources.singleOrNull()?.retained == true
                            if (kept || amount == 1f) {
                                weights = proposal
                                break
                            }
                        }
                    }
                    var blended = BlendedSource(enhanced, selected, weights)
                    var lost = required - retained(blended)
                    // Neighbouring measurement windows can overlap. Recheck the complete fixed
                    // catalogue and restore their selected-stack support before publishing.
                    repeat(3) {
                        if (lost.isNotEmpty()) {
                            val recoverable = lost.intersect(retainedBefore)
                            recoverable.forEach { index -> weights = addPatch(weights, stars[index], candidate, 1f) }
                            protected += recoverable
                            blended = BlendedSource(enhanced, selected, weights)
                            lost = required - retained(blended)
                        }
                    }
                    val writer = store.createTemporaryWriter("enhanced-star-preserved", candidate.width, candidate.height)
                    try {
                        val row = LongArray(candidate.width)
                        val rows = weights.keys.groupBy { (it / candidate.width).toInt() }
                        for (y in 0 until candidate.height) {
                            enhanced.readLinearRow(y, row)
                            rows[y].orEmpty().forEach { key ->
                                val x = (key % candidate.width).toInt()
                                row[x] = blended.linearRgbAt(x, y)
                            }
                            writer.writeLinearRow(y, row)
                        }
                        return EnhancedStarPreservationResult(
                            writer.finish(),
                            EnhancedStarPreservationMetrics(retainedBefore.size, protected.size, lost.size)
                        )
                    } catch (error: Throwable) {
                        runCatching { writer.close() }
                        runCatching { store.deleteTemporary(writer.image) }
                        throw error
                    }
                }
            }
        }
    }

    private fun addPatch(
        previous: Map<Long, Float>,
        star: DetectedStar,
        image: FileBackedImage,
        amount: Float
    ): Map<Long, Float> {
        // Match the retention validator's integer centre and include its full background ring.
        val centerX = star.x.toInt()
        val centerY = star.y.toInt()
        val inner = ceil(star.width * 1.8f).toInt().coerceIn(3, 7) + 3
        val outer = inner + 3
        val result = previous.toMutableMap()
        for (y in maxOf(0, centerY - outer)..minOf(image.height - 1, centerY + outer)) {
            for (x in maxOf(0, centerX - outer)..minOf(image.width - 1, centerX + outer)) {
                val dx = x - centerX
                val dy = y - centerY
                val distance = sqrt((dx * dx + dy * dy).toFloat())
                val t = ((outer - distance) / 3f).coerceIn(0f, 1f)
                val weight = amount * t * t * (3f - 2f * t)
                if (weight > 0f) {
                    val key = y.toLong() * image.width + x
                    result[key] = maxOf(result[key] ?: 0f, weight)
                }
            }
        }
        return result
    }

    private class BlendedSource(
        private val candidate: FileBackedImageReader,
        private val baseline: FileBackedImageReader,
        private val weights: Map<Long, Float>
    ) : LinearRgbPixelSource {
        override val width: Int get() = candidate.width
        override val height: Int get() = candidate.height
        override fun argbAt(x: Int, y: Int): Int = LinearRgb16.toArgb(linearRgbAt(x, y))
        override fun linearRgbAt(x: Int, y: Int): Long {
            val original = candidate.linearRgbAt(x, y)
            val weight = weights[y.toLong() * width + x] ?: return original
            // The original reference supplies measurements only; pixels come from the selected stack.
            return LinearRgb16.blend(original, baseline.linearRgbAt(x, y), weight)
        }
    }

    private companion object {
        val RESTORATION_AMOUNTS = listOf(0.125f, 0.25f, 0.5f, 0.75f, 1f)
    }
}
