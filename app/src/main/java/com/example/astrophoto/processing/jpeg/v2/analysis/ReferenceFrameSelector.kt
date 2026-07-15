package com.example.astrophoto.processing.jpeg.v2.analysis

import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis

data class ScoredFrameAnalysis(
    val analysis: FrameAnalysis,
    val score: Float
)

class ReferenceFrameSelector {
    fun select(analyses: List<FrameAnalysis>): ScoredFrameAnalysis {
        require(analyses.isNotEmpty())
        val scored = scoreAll(analyses).filter { it.analysis.decodeValid }
        require(scored.isNotEmpty()) { "No decodable JPEG frames available for reference selection" }
        return scored.sortedWith(
            compareByDescending<ScoredFrameAnalysis> { it.score }
                .thenBy { it.analysis.fileName }
                .thenBy { it.analysis.id }
        ).first()
    }

    fun scoreAll(analyses: List<FrameAnalysis>): List<ScoredFrameAnalysis> {
        if (analyses.isEmpty()) return emptyList()
        val valid = analyses.filter { it.decodeValid }
        fun normalized(value: Float, values: List<Float>): Float {
            val finite = values.filter { it.isFinite() }
            if (!value.isFinite() || finite.isEmpty()) return 0f
            val minimum = finite.minOrNull() ?: return 0f
            val maximum = finite.maxOrNull() ?: return 0f
            return if (maximum - minimum < 0.0001f) 0.5f else ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
        }
        val counts = valid.map { it.reliableStarCount.toFloat() }
        val contrasts = valid.map { it.medianStarContrast }
        val sharpness = valid.map { if (it.medianStarWidth.isFinite()) 1f / it.medianStarWidth.coerceAtLeast(0.1f) else 0f }
        val suitability = valid.map { it.alignmentSuitability }
        val exposure = valid.map { it.exposureSuitability }
        val trailQuality = valid.map { 1f - it.medianStarEllipticity.coerceIn(0f, 1f) }
        val noiseQuality = valid.map { -it.backgroundNoise }
        val clippingQuality = valid.map { -it.clippingPercent }
        return analyses.map { analysis ->
            if (!analysis.decodeValid) return@map ScoredFrameAnalysis(analysis, Float.NEGATIVE_INFINITY)
            val sharp = if (analysis.medianStarWidth.isFinite()) 1f / analysis.medianStarWidth.coerceAtLeast(0.1f) else 0f
            val score = (
                normalized(analysis.reliableStarCount.toFloat(), counts) * 0.28f +
                    normalized(analysis.medianStarContrast, contrasts) * 0.20f +
                    normalized(sharp, sharpness) * 0.18f +
                    normalized(analysis.alignmentSuitability, suitability) * 0.16f +
                    normalized(analysis.exposureSuitability, exposure) * 0.08f +
                    normalized(1f - analysis.medianStarEllipticity.coerceIn(0f, 1f), trailQuality) * 0.05f +
                    normalized(-analysis.backgroundNoise, noiseQuality) * 0.03f +
                    normalized(-analysis.clippingPercent, clippingQuality) * 0.02f
                ).coerceIn(0f, 1f)
            ScoredFrameAnalysis(analysis, score)
        }
    }
}
