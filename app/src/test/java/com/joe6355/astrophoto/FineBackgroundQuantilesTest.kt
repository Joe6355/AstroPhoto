package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FineBackgroundQuantiles
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

class FineBackgroundQuantilesTest {
    @Test fun twoPassResultsMatchIndependentSortedSamplesAcrossDarkAndBrightBins() {
        val random = Random(6355)
        val samples = listOf(
            List(401) { random.nextFloat() * .002f } + listOf(0f, 1f),
            List(402) { random.nextFloat() },
            List(499) { .97f + random.nextFloat() * .03f },
            List(400) { if (it % 2 == 0) .00013f else .000145f }
        )
        val quantiles = FineBackgroundQuantiles(samples.size)
        samples.forEachIndexed { group, values -> values.forEach { quantiles.addFirstPass(group, it) } }
        quantiles.beginRefinement()
        samples.forEachIndexed { group, values -> values.forEach { quantiles.addRefinementSample(group, it) } }
        samples.forEachIndexed { group, values ->
            val sorted = values.map { (it * 65535f).roundToInt() }.sorted()
            for ((slot, fraction) in listOf(.16, .50, .84).withIndex()) {
                val rank = ceil(sorted.size * fraction).toInt().coerceAtLeast(1) - 1
                assertEquals(sorted[rank] / 65535f, quantiles.percentile(group, slot), 0f)
            }
        }
    }

    @Test fun missingCellsDoNotTurnIntoZeroValuedBackgroundSamples() {
        val quantiles = FineBackgroundQuantiles(2)
        quantiles.addFirstPass(0, .0009f)
        quantiles.beginRefinement()
        quantiles.addRefinementSample(0, .0009f)
        assertTrue(quantiles.percentile(0, FineBackgroundQuantiles.MEDIAN) > 0f)
        assertTrue(quantiles.percentile(1, FineBackgroundQuantiles.MEDIAN).isNaN())
    }

    @Test(expected = IllegalStateException::class)
    fun incompleteRefinementCannotBeReturnedAsAValidMedian() {
        val quantiles = FineBackgroundQuantiles(1)
        quantiles.addFirstPass(0, .0009f)
        quantiles.beginRefinement()
        quantiles.percentile(0, FineBackgroundQuantiles.MEDIAN)
    }
}
