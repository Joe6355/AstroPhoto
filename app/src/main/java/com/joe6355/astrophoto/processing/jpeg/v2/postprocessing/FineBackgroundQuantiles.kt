package com.joe6355.astrophoto.processing.jpeg.v2.postprocessing

import kotlin.math.ceil

/** Linear 16-bit percentiles without allocating a 65536-bin histogram for every sky cell. */
internal class FineBackgroundQuantiles(private val groups: Int) {
    private val counts = IntArray(groups)
    private val coarse = IntArray(Math.multiplyExact(groups, BINS))
    private val selectedBins = IntArray(Math.multiplyExact(groups, FRACTIONS.size)) { -1 }
    private val ranks = IntArray(selectedBins.size)
    private var fine: IntArray? = null

    fun addFirstPass(group: Int, value: Float) {
        check(fine == null)
        val code = histogramBin(value, LEVELS)
        coarse[group * BINS + (code ushr 8)]++
        counts[group]++
    }

    fun beginRefinement() {
        check(fine == null)
        for (group in 0 until groups) {
            if (counts[group] == 0) continue
            for (quantile in FRACTIONS.indices) {
                val slot = group * FRACTIONS.size + quantile
                val target = ceil(counts[group].toDouble() * FRACTIONS[quantile]).toInt().coerceAtLeast(1)
                var accumulated = 0
                for (bin in 0 until BINS) {
                    val count = coarse[group * BINS + bin]
                    if (accumulated + count >= target) {
                        selectedBins[slot] = bin
                        ranks[slot] = target - accumulated
                        break
                    }
                    accumulated += count
                }
            }
        }
        fine = IntArray(Math.multiplyExact(selectedBins.size, BINS))
    }

    fun addRefinementSample(group: Int, value: Float) {
        val histogram = checkNotNull(fine)
        val code = histogramBin(value, LEVELS)
        val high = code ushr 8
        for (quantile in FRACTIONS.indices) {
            val slot = group * FRACTIONS.size + quantile
            if (selectedBins[slot] == high) histogram[slot * BINS + (code and 255)]++
        }
    }

    fun percentile(group: Int, quantile: Int): Float {
        val histogram = checkNotNull(fine)
        val slot = group * FRACTIONS.size + quantile
        val high = selectedBins[slot]
        if (high < 0) return Float.NaN
        var accumulated = 0
        for (bin in 0 until BINS) {
            accumulated += histogram[slot * BINS + bin]
            if (accumulated >= ranks[slot]) return (high * BINS + bin) / 65535f
        }
        error("Incomplete background quantile refinement")
    }

    companion object {
        const val LOW = 0
        const val MEDIAN = 1
        const val HIGH = 2
        private const val BINS = 256
        private const val LEVELS = 65536
        private val FRACTIONS = doubleArrayOf(.16, .50, .84)
    }
}
