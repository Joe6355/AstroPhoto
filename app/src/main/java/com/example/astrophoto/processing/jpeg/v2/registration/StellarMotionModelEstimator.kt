package com.example.astrophoto.processing.jpeg.v2.registration

import kotlin.math.hypot

class StellarMotionModelEstimator {
    fun fit(
        candidates: List<SequenceRegistrationCandidate>,
        referenceIndex: Int,
        trackAnalysis: TemporalTrackAnalysis
    ): StellarSequenceMotionModel {
        val ordered = candidates.sortedBy { it.captureIndex }
        if (ordered.isEmpty()) return emptyModel(referenceIndex)
        val velocitySeeds = buildList {
            add(0f to 0f)
            if (trackAnalysis.motionObservable) {
                add(trackAnalysis.coherentVelocityX to trackAnalysis.coherentVelocityY)
            }
            ordered.filterNot { it.isReference }.forEach { frame ->
                val delta = frame.captureIndex - referenceIndex
                if (delta != 0) frame.hypotheses.forEach { hypothesis ->
                    add(hypothesis.dx / delta to hypothesis.dy / delta)
                }
            }
        }.filter { hypot(it.first, it.second) <= MAX_VELOCITY }
            .distinctBy { velocityKey(it.first, it.second) }

        val evaluated = velocitySeeds.map { evaluate(it.first, it.second, ordered, referenceIndex) }
        val zero = evaluate(
            0f,
            0f,
            ordered,
            referenceIndex,
            maximumDeviation = ZERO_MODEL_MAX_DISPLACEMENT
        )
        val nonZero = evaluated.filter { hypot(it.velocityX, it.velocityY) >= MIN_NON_ZERO_VELOCITY }
            .maxWithOrNull(modelComparator())
        val nonZeroRefined = nonZero?.let { initial ->
            val ratios = initial.selected.entries.mapNotNull { (frameId, hypothesis) ->
                val frame = ordered.firstOrNull { it.frameId == frameId } ?: return@mapNotNull null
                val delta = frame.captureIndex - referenceIndex
                if (delta == 0) null else hypothesis.dx / delta to hypothesis.dy / delta
            }
            if (ratios.isEmpty()) initial else evaluate(
                median(ratios.map { it.first }),
                median(ratios.map { it.second }),
                ordered,
                referenceIndex
            )
        }
        val bestNonZero = listOfNotNull(nonZero, nonZeroRefined).maxWithOrNull(modelComparator())
        val nonZeroHasEvidence = bestNonZero != null &&
            trackAnalysis.motionObservable &&
            bestNonZero.acceptedNonReference >= MIN_ACCEPTED_NON_REFERENCE &&
            bestNonZero.score >= zero.score + MIN_NON_ZERO_SCORE_ADVANTAGE
        val selected = if (nonZeroHasEvidence) bestNonZero!! else zero
        val residual = selected.residual
        val motionObservable = nonZeroHasEvidence &&
            hypot(selected.velocityX, selected.velocityY) * captureSpan(ordered) >=
            MIN_OBSERVABLE_SEQUENCE_MOVEMENT
        return StellarSequenceMotionModel(
            velocityX = selected.velocityX,
            velocityY = selected.velocityY,
            referenceIndex = referenceIndex,
            acceptedFrameHypotheses = selected.selected,
            score = selected.score,
            residual = residual,
            motionObservable = motionObservable,
            rejectedFrameIds = ordered.map { it.frameId }.filterNot(selected.selected::containsKey),
            competingZeroModelScore = zero.score,
            nonZeroModelScore = bestNonZero?.score ?: 0f,
            selectedMotionModel = if (motionObservable) "NON_ZERO_STELLAR" else "ZERO_OR_UNOBSERVABLE",
            zeroFrameHypotheses = zero.selected,
            nonZeroFrameHypotheses = bestNonZero?.selected.orEmpty()
        )
    }

    private fun evaluate(
        velocityX: Float,
        velocityY: Float,
        candidates: List<SequenceRegistrationCandidate>,
        referenceIndex: Int,
        maximumDeviation: Float = MAX_MODEL_DEVIATION
    ): EvaluatedModel {
        val selected = linkedMapOf<String, TranslationHypothesis>()
        val normalizedEvidence = mutableListOf<Float>()
        val deviations = mutableListOf<Float>()
        candidates.forEach { frame ->
            if (frame.isReference) {
                selected[frame.frameId] = IDENTITY_HYPOTHESIS
                normalizedEvidence += 1f
                deviations += 0f
                return@forEach
            }
            val delta = frame.captureIndex - referenceIndex
            val predictedX = velocityX * delta
            val predictedY = velocityY * delta
            val best = frame.hypotheses.mapIndexed { rank, hypothesis ->
                val deviation = hypot(hypothesis.dx - predictedX, hypothesis.dy - predictedY)
                val evidence = hypothesisEvidence(hypothesis, rank) /
                    (1f + deviation * deviation / MODEL_TOLERANCE_SQUARED)
                CandidateMatch(hypothesis, deviation, evidence)
            }.maxWithOrNull(
                compareBy<CandidateMatch> { it.evidence }
                    .thenByDescending { it.deviation }
                    .thenBy { it.hypothesis.dy }
                    .thenBy { it.hypothesis.dx }
            )
            if (best != null && best.deviation <= maximumDeviation) {
                selected[frame.frameId] = best.hypothesis
                normalizedEvidence += best.evidence
                deviations += best.deviation
            }
        }
        val acceptedNonReference = selected.size - candidates.count { it.isReference && selected.containsKey(it.frameId) }
        val acceptedRatio = selected.size.toFloat() / candidates.size.coerceAtLeast(1)
        val evidenceScore = normalizedEvidence.average().takeIf { it.isFinite() }?.toFloat() ?: 0f
        val residual = rootMeanSquare(deviations)
        val continuity = (1f - residual / MAX_MODEL_DEVIATION).coerceIn(0f, 1f)
        val score = (
            evidenceScore.coerceIn(0f, 1f) * 0.55f +
                acceptedRatio * 0.30f +
                continuity * 0.15f
            ).coerceIn(0f, 1f)
        return EvaluatedModel(
            velocityX,
            velocityY,
            selected,
            score,
            residual,
            acceptedNonReference
        )
    }

    private fun hypothesisEvidence(hypothesis: TranslationHypothesis, rank: Int): Float {
        val support = (hypothesis.weightedSupport / 36f).coerceIn(0f, 1f)
        val sectors = (hypothesis.occupiedSectors / 5f).coerceIn(0f, 1f)
        val moving = (hypothesis.movingTrackSupport / 6f).coerceIn(0f, 1f)
        val stationaryPenalty = (hypothesis.stationaryTrackSupport / 8f).coerceIn(0f, 0.6f)
        val residual = (1f - hypothesis.residual / 1.75f).coerceIn(0f, 1f)
        val rankPenalty = rank * 0.015f
        return (
            support * 0.35f + sectors * 0.20f + moving * 0.30f + residual * 0.15f -
                stationaryPenalty - rankPenalty
            ).coerceIn(0f, 1f)
    }

    private fun modelComparator() = compareBy<EvaluatedModel> { it.score }
        .thenBy { it.acceptedNonReference }
        .thenByDescending { it.residual }
        .thenBy { it.velocityY }
        .thenBy { it.velocityX }

    private fun velocityKey(x: Float, y: Float): Pair<Int, Int> =
        (x / VELOCITY_BIN).toInt() to (y / VELOCITY_BIN).toInt()

    private fun captureSpan(values: List<SequenceRegistrationCandidate>): Int =
        ((values.maxOfOrNull { it.captureIndex } ?: 0) -
            (values.minOfOrNull { it.captureIndex } ?: 0)).coerceAtLeast(1)

    private fun rootMeanSquare(values: List<Float>): Float {
        if (values.isEmpty()) return Float.POSITIVE_INFINITY
        return kotlin.math.sqrt(values.sumOf { (it * it).toDouble() }.toFloat() / values.size)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private fun emptyModel(referenceIndex: Int) = StellarSequenceMotionModel(
        velocityX = 0f,
        velocityY = 0f,
        referenceIndex = referenceIndex,
        acceptedFrameHypotheses = emptyMap(),
        score = 0f,
        residual = Float.POSITIVE_INFINITY,
        motionObservable = false,
        rejectedFrameIds = emptyList(),
        competingZeroModelScore = 0f,
        nonZeroModelScore = 0f,
        selectedMotionModel = "UNAVAILABLE"
    )

    private data class CandidateMatch(
        val hypothesis: TranslationHypothesis,
        val deviation: Float,
        val evidence: Float
    )

    private data class EvaluatedModel(
        val velocityX: Float,
        val velocityY: Float,
        val selected: Map<String, TranslationHypothesis>,
        val score: Float,
        val residual: Float,
        val acceptedNonReference: Int
    )

    companion object {
        private const val MAX_VELOCITY = 12f
        private const val VELOCITY_BIN = 0.12f
        private const val MIN_NON_ZERO_VELOCITY = 0.08f
        private const val MIN_NON_ZERO_SCORE_ADVANTAGE = 0.025f
        private const val MIN_ACCEPTED_NON_REFERENCE = 2
        private const val MIN_OBSERVABLE_SEQUENCE_MOVEMENT = 2.5f
        private const val MAX_MODEL_DEVIATION = 2.4f
        private const val ZERO_MODEL_MAX_DISPLACEMENT = 0.75f
        private const val MODEL_TOLERANCE_SQUARED = 1.6f * 1.6f
        private val IDENTITY_HYPOTHESIS = TranslationHypothesis(
            dx = 0f,
            dy = 0f,
            support = Int.MAX_VALUE,
            weightedSupport = 100f,
            residual = 0f,
            occupiedSectors = 9,
            movingTrackSupport = 0,
            stationaryTrackSupport = 0
        )
    }
}
