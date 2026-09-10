package com.joe6355.astrophoto.processing.jpeg.v2.analysis

import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis

data class ScoredFrameAnalysis(
    val analysis: FrameAnalysis,
    val score: Float
)

data class IntegrationFrameSelection(
    val analyses: List<FrameAnalysis>,
    val droppedCount: Int
)

class ReferenceFrameSelector {
    fun select(analyses: List<FrameAnalysis>): ScoredFrameAnalysis {
        require(analyses.isNotEmpty())
        val scored = scoreAll(analyses).filter { it.analysis.hardInvalidReason == null }
        require(scored.isNotEmpty()) { "No decodable JPEG frames available for reference selection" }
        return scored.sortedWith(
            compareByDescending<ScoredFrameAnalysis> { it.score }
                .thenBy { it.analysis.fileName }
                .thenBy { it.analysis.id }
        ).first()
    }

    fun scoreAll(analyses: List<FrameAnalysis>): List<ScoredFrameAnalysis> {
        if (analyses.isEmpty()) return emptyList()
        val valid = analyses.filter { it.hardInvalidReason == null }
        class Range(values: List<Float>) {
            private val finite = values.filter { it.isFinite() }
            private val minimum = finite.minOrNull()
            private val maximum = finite.maxOrNull()
            fun normalize(value: Float): Float {
                if (!value.isFinite() || minimum == null || maximum == null) return 0f
                return if (maximum - minimum < 0.0001f) 0.5f
                else ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
            }
        }
        val counts = Range(valid.map { it.reliableStarCount.toFloat() })
        val contrasts = Range(valid.map { it.medianStarContrast })
        val sharpness = Range(valid.map { if (it.medianStarWidth.isFinite()) 1f / it.medianStarWidth.coerceAtLeast(0.1f) else 0f })
        val suitability = Range(valid.map { it.alignmentSuitability })
        val exposure = Range(valid.map { it.exposureSuitability })
        val trailQuality = Range(valid.map { 1f - it.medianStarEllipticity.coerceIn(0f, 1f) })
        val noiseQuality = Range(valid.map { -it.backgroundNoise })
        val clippingQuality = Range(valid.map { -it.clippingPercent })
        val signalToNoise = Range(valid.map { it.medianStarSnr })
        return analyses.map { analysis ->
            if (analysis.hardInvalidReason != null) {
                return@map ScoredFrameAnalysis(analysis, Float.NEGATIVE_INFINITY)
            }
            val sharp = if (analysis.medianStarWidth.isFinite()) 1f / analysis.medianStarWidth.coerceAtLeast(0.1f) else 0f
            val score = (
                counts.normalize(analysis.reliableStarCount.toFloat()) * 0.24f +
                    contrasts.normalize(analysis.medianStarContrast) * 0.13f +
                    signalToNoise.normalize(analysis.medianStarSnr) * 0.12f +
                    sharpness.normalize(sharp) * 0.17f +
                    suitability.normalize(analysis.alignmentSuitability) * 0.15f +
                    exposure.normalize(analysis.exposureSuitability) * 0.08f +
                    trailQuality.normalize(1f - analysis.medianStarEllipticity.coerceIn(0f, 1f)) * 0.05f +
                    noiseQuality.normalize(-analysis.backgroundNoise) * 0.03f +
                    clippingQuality.normalize(-analysis.clippingPercent) * 0.02f
                ).coerceIn(0f, 1f)
            ScoredFrameAnalysis(analysis, score)
        }
    }

    /** Keeps strictly the strongest valid frames; time only resolves equal quality. */
    fun selectForIntegration(
        analyses: List<FrameAnalysis>,
        captureIndexByFrameId: Map<String, Int>,
        maxFrames: Int
    ): IntegrationFrameSelection {
        require(maxFrames > 0)
        val valid = scoreAll(analyses)
            .filter { it.analysis.hardInvalidReason == null && it.score.isFinite() }
        if (valid.size <= maxFrames) {
            return IntegrationFrameSelection(
                analyses = valid.sortedBy { captureIndexByFrameId[it.analysis.id] ?: Int.MAX_VALUE }
                    .map { it.analysis },
                droppedCount = analyses.size - valid.size
            )
        }

        val minimumIndex = valid.minOf { captureIndexByFrameId[it.analysis.id] ?: 0 }
        val maximumIndex = valid.maxOf { captureIndexByFrameId[it.analysis.id] ?: minimumIndex }
        val temporalCenter = (minimumIndex + maximumIndex) / 2f
        val ranked = valid.sortedWith(
            compareByDescending<ScoredFrameAnalysis> { it.score }
                .thenBy {
                    kotlin.math.abs(
                        (captureIndexByFrameId[it.analysis.id] ?: Int.MAX_VALUE) - temporalCenter
                    )
                }
                .thenBy { captureIndexByFrameId[it.analysis.id] ?: Int.MAX_VALUE }
                .thenBy { it.analysis.fileName }
                .thenBy { it.analysis.id }
        )
        val selected = ranked.take(maxFrames)

        return IntegrationFrameSelection(
            analyses = selected
                .sortedBy { captureIndexByFrameId[it.analysis.id] ?: Int.MAX_VALUE }
                .map { it.analysis },
            droppedCount = analyses.size - selected.size
        )
    }

}
