package com.joe6355.astrophoto.processing.jpeg.v2.composition

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import com.joe6355.astrophoto.pixelLuminance
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Restores measured display contrast at the destination sky level, with a feathered edge. */
internal class BackgroundMatchedStarSource private constructor(
    private val target: LinearRgbPixelSource,
    private val source: LinearRgbPixelSource,
    private val alpha: AlphaPixelSource,
    private val rows: Array<List<Patch>>
) : LinearRgbPixelSource {
    private data class Patch(
        val x: Int,
        val y: Int,
        val inner: Int,
        val outer: Int,
        val highlightScale: Float,
        val redOffset: Float,
        val greenOffset: Float,
        val blueOffset: Float,
        val luminanceOffset: Int
    ) {
        fun weight(x: Int, y: Int): Float {
            val dx = x - this.x
            val dy = y - this.y
            val distance = sqrt((dx * dx + dy * dy).toFloat())
            val t = ((outer - distance) / (outer - inner)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    override val width: Int get() = target.width
    override val height: Int get() = target.height
    override fun argbAt(x: Int, y: Int): Int = LinearRgb16.toArgb(linearRgbAt(x, y))

    fun weightAt(x: Int, y: Int): Float = if (alpha.alphaAt(x, y) <= 0f) 0f else
        rows[y].maxOfOrNull { it.weight(x, y) } ?: 0f

    override fun linearRgbAt(x: Int, y: Int): Long {
        // The base writer excludes masked pixels. Enhanced recovery retains its existing
        // selected-stack fallback outside the sky rather than matching a foreground background.
        if (alpha.alphaAt(x, y) <= 0f) return source.linearRgbAt(x, y)
        val color = source.linearRgbAt(x, y)
        var red = 0f
        var green = 0f
        var blue = 0f
        var total = 0f
        var luminanceOffset = 0f
        for (patch in rows[y]) {
            val weight = patch.weight(x, y)
            if (weight <= 0f) continue
            red += SrgbTransfer.srgbToLinear(SrgbTransfer.linearToSrgb(LinearRgb16.red(color)) * patch.highlightScale + patch.redOffset) * weight
            green += SrgbTransfer.srgbToLinear(SrgbTransfer.linearToSrgb(LinearRgb16.green(color)) * patch.highlightScale + patch.greenOffset) * weight
            blue += SrgbTransfer.srgbToLinear(SrgbTransfer.linearToSrgb(LinearRgb16.blue(color)) * patch.highlightScale + patch.blueOffset) * weight
            luminanceOffset += patch.luminanceOffset * weight
            total += weight
        }
        if (total <= 0f) return color
        // Matching must not introduce an encoded 255 in an unclipped source channel.
        var matched = LinearRgb16.pack(
            minOf(red / total, maxOf(LinearRgb16.red(color), UNCLIPPED_CEILING)),
            minOf(green / total, maxOf(LinearRgb16.green(color), UNCLIPPED_CEILING)),
            minOf(blue / total, maxOf(LinearRgb16.blue(color), UNCLIPPED_CEILING))
        )
        // Preserve the actual displayed luminance stencil: fractional channel shifts can
        // otherwise erase a dark wing or broaden a faint source after 8-bit encoding.
        val sourceLuminance = pixelLuminance(LinearRgb16.toArgb(color))
        val desired = (sourceLuminance + (luminanceOffset / total).roundToInt())
            .coerceIn(0, maxOf(sourceLuminance, 254))
        repeat(3) {
            val difference = desired - pixelLuminance(LinearRgb16.toArgb(matched))
            if (difference == 0) return matched
            if (difference != 0) {
                fun channel(value: Float, sourceValue: Float): Float = minOf(
                    SrgbTransfer.srgbToLinear(SrgbTransfer.linearToSrgb(value) + difference / 255f),
                    maxOf(sourceValue, UNCLIPPED_CEILING)
                )
                matched = LinearRgb16.pack(
                    channel(LinearRgb16.red(matched), LinearRgb16.red(color)),
                    channel(LinearRgb16.green(matched), LinearRgb16.green(color)),
                    channel(LinearRgb16.blue(matched), LinearRgb16.blue(color))
                )
            }
        }
        return matched
    }

    companion object {
        private val UNCLIPPED_CEILING = SrgbTransfer.srgbToLinear(254f / 255f)

        fun prepare(
            target: LinearRgbPixelSource,
            source: LinearRgbPixelSource,
            stars: List<DetectedStar>,
            alpha: AlphaPixelSource
        ): BackgroundMatchedStarSource {
            require(target.width == source.width && target.height == source.height)
            require(target.width == alpha.width && target.height == alpha.height)
            val rows = Array(target.height) { mutableListOf<Patch>() }
            for (star in stars) {
                if (!star.x.isFinite() || !star.y.isFinite() || !star.width.isFinite()) continue
                val x = star.x.toInt()
                val y = star.y.toInt()
                if (x !in 0 until target.width || y !in 0 until target.height) continue
                val core = ceil(star.width * 1.8f).toInt().coerceIn(3, 7)
                val inner = core + 3
                val outer = inner + 3
                val sourceSamples = Array(3) { mutableListOf<Float>() }
                val targetSamples = Array(3) { mutableListOf<Float>() }
                val sourceLuminanceSamples = mutableListOf<Int>()
                val targetLuminanceSamples = mutableListOf<Int>()
                val ringInner = core
                val ringOuter = core + 3
                for (dy in -ringOuter..ringOuter) for (dx in -ringOuter..ringOuter) {
                    val distance = dx * dx + dy * dy
                    if (distance <= ringInner * ringInner || distance > ringOuter * ringOuter) continue
                    val px = x + dx
                    val py = y + dy
                    if (px !in 0 until target.width || py !in 0 until target.height || alpha.alphaAt(px, py) <= 0f) continue
                    val first = source.linearRgbAt(px, py)
                    val second = target.linearRgbAt(px, py)
                    sourceLuminanceSamples += pixelLuminance(LinearRgb16.toArgb(first))
                    targetLuminanceSamples += pixelLuminance(LinearRgb16.toArgb(second))
                    sourceSamples[0] += LinearRgb16.red(first)
                    sourceSamples[1] += LinearRgb16.green(first)
                    sourceSamples[2] += LinearRgb16.blue(first)
                    targetSamples[0] += LinearRgb16.red(second)
                    targetSamples[1] += LinearRgb16.green(second)
                    targetSamples[2] += LinearRgb16.blue(second)
                }
                val sourceBackground = FloatArray(3)
                val targetBackground = FloatArray(3)
                var highlightScale = 1f
                var luminanceOffset = 0
                if (sourceSamples[0].size >= 32) {
                    // Luminance of RGB medians is not the median displayed luminance.
                    // They can differ by one code, which matters near the black floor.
                    sourceLuminanceSamples.sort()
                    targetLuminanceSamples.sort()
                    luminanceOffset = targetLuminanceSamples[targetLuminanceSamples.size / 2] -
                        sourceLuminanceSamples[sourceLuminanceSamples.size / 2]
                    for (channel in 0..2) {
                        sourceSamples[channel].sort()
                        targetSamples[channel].sort()
                        sourceBackground[channel] = sourceSamples[channel][sourceSamples[channel].size / 2]
                        targetBackground[channel] = targetSamples[channel][targetSamples[channel].size / 2]
                    }
                    // This recovery happens after tone mapping: retain the source's
                    // display contrast instead of multiplying it by the sky brightness ratio.
                    for (channel in 0..2) {
                        sourceBackground[channel] = SrgbTransfer.linearToSrgb(sourceBackground[channel])
                        targetBackground[channel] = SrgbTransfer.linearToSrgb(targetBackground[channel])
                    }
                    val peaks = FloatArray(3)
                    for (dy in -core..core) for (dx in -core..core) {
                        if (dx * dx + dy * dy > core * core) continue
                        val px = x + dx
                        val py = y + dy
                        if (px !in 0 until target.width || py !in 0 until target.height) continue
                        val color = source.linearRgbAt(px, py)
                        peaks[0] = maxOf(peaks[0], SrgbTransfer.linearToSrgb(LinearRgb16.red(color)))
                        peaks[1] = maxOf(peaks[1], SrgbTransfer.linearToSrgb(LinearRgb16.green(color)))
                        peaks[2] = maxOf(peaks[2], SrgbTransfer.linearToSrgb(LinearRgb16.blue(color)))
                    }
                    for (channel in 0..2) if (peaks[channel] > sourceBackground[channel]) {
                        val maximumGain = (maxOf(peaks[channel], 254f / 255f) - targetBackground[channel]) /
                            (peaks[channel] - sourceBackground[channel])
                        highlightScale = minOf(highlightScale, maximumGain.coerceAtLeast(0f))
                    }
                }
                val patch = Patch(
                    x, y, inner, outer, highlightScale,
                    targetBackground[0] - sourceBackground[0] * highlightScale,
                    targetBackground[1] - sourceBackground[1] * highlightScale,
                    targetBackground[2] - sourceBackground[2] * highlightScale,
                    luminanceOffset
                )
                for (py in maxOf(0, y - outer)..minOf(target.height - 1, y + outer)) rows[py] += patch
            }
            return BackgroundMatchedStarSource(target, source, alpha, Array(rows.size) { rows[it].toList() })
        }

        fun restore(
            candidate: FileBackedImage,
            baseline: FileBackedImage,
            stars: List<DetectedStar>,
            alphaPlane: FileBackedFloatPlane,
            store: ResultCandidateStore
        ): FileBackedImage {
            if (stars.isEmpty()) return candidate
            FileBackedImageReader(candidate, cachedRows = 48).use { target ->
                FileBackedImageReader(baseline, cachedRows = 48).use { source ->
                    FileBackedFloatPlaneReader(alphaPlane, cachedRows = 48).use { alpha ->
                        val matched = prepare(target, source, stars, alpha)
                        val writer = store.createTemporaryWriter("background-matched-stars", candidate.width, candidate.height)
                        try {
                            val row = LongArray(candidate.width)
                            for (y in 0 until candidate.height) {
                                target.readLinearRow(y, row)
                                for (x in 0 until candidate.width) {
                                    val weight = matched.weightAt(x, y)
                                    if (weight > 0f) row[x] = LinearRgb16.blend(row[x], matched.linearRgbAt(x, y), weight)
                                }
                                writer.writeLinearRow(y, row)
                            }
                            return writer.finish()
                        } catch (error: Throwable) {
                            runCatching { writer.close() }
                            runCatching { store.deleteTemporary(writer.image) }
                            throw error
                        }
                    }
                }
            }
        }

    }
}
