package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SparseTranslationVotingEstimator {
    fun estimate(
        reference: TemporalFeatureFrame,
        candidate: TemporalFeatureFrame,
        imageWidth: Int,
        imageHeight: Int,
        tracks: TemporalTrackAnalysis,
        maximumHypotheses: Int = DEFAULT_MAX_HYPOTHESES
    ): List<TranslationHypothesis> {
        require(imageWidth > 0 && imageHeight > 0)
        val references = ranked(reference.stars).take(MAX_STARS)
        val candidates = ranked(candidate.stars).take(MAX_STARS)
        if (references.isEmpty() || candidates.isEmpty()) return emptyList()
        val maximumTranslation = hypot(imageWidth.toFloat(), imageHeight.toFloat()) *
            MAX_TRANSLATION_FRACTION
        val bins = mutableMapOf<Long, VoteBin>()
        references.forEach { referenceStar ->
            candidates.forEach { candidateStar ->
                val canonical = canonicalHypothesis(referenceStar, candidateStar)
                val dx = canonical.dx
                val dy = canonical.dy
                if (hypot(dx, dy) > maximumTranslation) return@forEach
                val keyX = (dx / BIN_SIZE).roundToInt()
                val keyY = (dy / BIN_SIZE).roundToInt()
                val key = key(keyX, keyY)
                val membership = membershipWeight(
                    tracks.clusterAt(reference.frameId, referenceStar),
                    tracks.clusterAt(candidate.frameId, candidateStar)
                )
                val weight = detectionWeight(referenceStar) * detectionWeight(candidateStar) * membership
                val sector = sector(referenceStar, imageWidth, imageHeight)
                bins.getOrPut(key) { VoteBin(keyX, keyY) }.add(weight, sector)
            }
        }
        val peaks = bins.values.sortedWith(
            compareByDescending<VoteBin> { it.weightedVotes + it.sectorCount * SECTOR_BONUS }
                .thenByDescending { it.sectorCount }
                .thenByDescending { it.votes }
                .thenBy { it.y }
                .thenBy { it.x }
        ).fold(mutableListOf<VoteBin>()) { selected, peak ->
            if (selected.size < MAX_RAW_PEAKS && selected.none {
                    hypot((it.x - peak.x) * BIN_SIZE, (it.y - peak.y) * BIN_SIZE) < PEAK_SEPARATION
                }) {
                selected += peak
            }
            selected
        }
        return peaks.mapNotNull { peak ->
            refine(
                initialDx = peak.x * BIN_SIZE,
                initialDy = peak.y * BIN_SIZE,
                references = references,
                candidates = candidates,
                referenceFrameId = reference.frameId,
                candidateFrameId = candidate.frameId,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                tracks = tracks
            )
        }.distinctBy { (it.dx / 0.35f).roundToInt() to (it.dy / 0.35f).roundToInt() }
            .sortedWith(
                compareByDescending<TranslationHypothesis> { it.weightedSupport }
                    .thenByDescending { it.occupiedSectors }
                    .thenByDescending { it.movingTrackSupport }
                    .thenByDescending { it.support }
                    .thenBy { it.residual }
                    .thenBy { it.dy }
                    .thenBy { it.dx }
            )
            .take(maximumHypotheses)
    }

    private fun refine(
        initialDx: Float,
        initialDy: Float,
        references: List<DetectedStar>,
        candidates: List<DetectedStar>,
        referenceFrameId: String,
        candidateFrameId: String,
        imageWidth: Int,
        imageHeight: Int,
        tracks: TemporalTrackAnalysis
    ): TranslationHypothesis? {
        var dx = initialDx
        var dy = initialDy
        var pairs = consistentPairs(dx, dy, references, candidates)
        repeat(2) {
            if (pairs.isEmpty()) return@repeat
            dx = weightedMedian(pairs.map {
                (it.candidate.x - it.reference.x) to pairWeight(
                    it.reference,
                    it.candidate,
                    referenceFrameId,
                    candidateFrameId,
                    tracks
                )
            })
            dy = weightedMedian(pairs.map {
                (it.candidate.y - it.reference.y) to pairWeight(
                    it.reference,
                    it.candidate,
                    referenceFrameId,
                    candidateFrameId,
                    tracks
                )
            })
            pairs = consistentPairs(dx, dy, references, candidates)
        }
        if (pairs.size < MIN_SUPPORT) return null
        val residuals = pairs.map {
            hypot(
                it.candidate.x - it.reference.x - dx,
                it.candidate.y - it.reference.y - dy
            )
        }
        val residual = sqrt(residuals.sumOf { (it * it).toDouble() }.toFloat() / pairs.size)
        val sectors = pairs.map { sector(it.reference, imageWidth, imageHeight) }.distinct().size
        var movingSupport = 0
        var stationarySupport = 0
        var weightedSupport = 0f
        pairs.forEach { pair ->
            val referenceCluster = tracks.clusterAt(referenceFrameId, pair.reference)
            val candidateCluster = tracks.clusterAt(candidateFrameId, pair.candidate)
            if (
                referenceCluster == TemporalMotionCluster.COHERENT_MOVING_SKY &&
                candidateCluster == TemporalMotionCluster.COHERENT_MOVING_SKY
            ) movingSupport++
            if (
                referenceCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE &&
                candidateCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE
            ) stationarySupport++
            weightedSupport += pairWeight(
                pair.reference,
                pair.candidate,
                referenceFrameId,
                candidateFrameId,
                tracks
            )
        }
        weightedSupport *= (1f + sectors.coerceAtMost(6) * SECTOR_SUPPORT_MULTIPLIER)
        weightedSupport /= (1f + residual)
        return TranslationHypothesis(
            dx = dx,
            dy = dy,
            support = pairs.size,
            weightedSupport = weightedSupport,
            residual = residual,
            occupiedSectors = sectors,
            movingTrackSupport = movingSupport,
            stationaryTrackSupport = stationarySupport
        )
    }

    private fun consistentPairs(
        dx: Float,
        dy: Float,
        references: List<DetectedStar>,
        candidates: List<DetectedStar>
    ): List<MatchedPair> {
        val used = BooleanArray(candidates.size)
        return references.mapNotNull { reference ->
            candidates.withIndex().asSequence()
                .filterNot { used[it.index] }
                .map { indexed ->
                    indexed to hypot(
                        indexed.value.x - reference.x - dx,
                        indexed.value.y - reference.y - dy
                    )
                }
                .filter { it.second <= MATCH_TOLERANCE }
                .minWithOrNull(
                    compareBy<Pair<IndexedValue<DetectedStar>, Float>> { it.second }
                        .thenBy { it.first.index }
                )
                ?.first
                ?.also { used[it.index] = true }
                ?.let { MatchedPair(reference, it.value) }
        }
    }

    private fun pairWeight(
        reference: DetectedStar,
        candidate: DetectedStar,
        referenceFrameId: String,
        candidateFrameId: String,
        tracks: TemporalTrackAnalysis
    ): Float = detectionWeight(reference) * detectionWeight(candidate) * membershipWeight(
        tracks.clusterAt(referenceFrameId, reference),
        tracks.clusterAt(candidateFrameId, candidate)
    )

    internal fun canonicalHypothesis(
        reference: DetectedStar,
        candidate: DetectedStar
    ): ReferenceToSourceTransform = ReferenceToSourceTransform(
        dx = candidate.x - reference.x,
        dy = candidate.y - reference.y
    )

    private fun membershipWeight(
        reference: TemporalMotionCluster,
        candidate: TemporalMotionCluster
    ): Float = when {
        reference == TemporalMotionCluster.COHERENT_MOVING_SKY &&
            candidate == TemporalMotionCluster.COHERENT_MOVING_SKY -> MOVING_TRACK_WEIGHT
        reference == TemporalMotionCluster.STATIONARY_CAMERA_SPACE &&
            candidate == TemporalMotionCluster.STATIONARY_CAMERA_SPACE -> STATIONARY_TRACK_WEIGHT
        reference == TemporalMotionCluster.STATIONARY_CAMERA_SPACE ||
            candidate == TemporalMotionCluster.STATIONARY_CAMERA_SPACE -> MIXED_STATIONARY_WEIGHT
        else -> UNKNOWN_TRACK_WEIGHT
    }

    private fun detectionWeight(star: DetectedStar): Float {
        val widthWeight = when {
            star.width < 0.45f -> 0.35f
            star.width > 5f -> 0.25f
            else -> 1f
        }
        val shapeWeight = (1f - star.ellipticity.coerceIn(0f, 1f) * 0.55f).coerceAtLeast(0.35f)
        val contrastWeight = (1f + star.localContrast.coerceAtLeast(0f)).coerceAtMost(4f)
        return sqrt(star.confidence.coerceIn(0.01f, 1f)) * widthWeight * shapeWeight * contrastWeight
    }

    private fun ranked(stars: List<DetectedStar>): List<DetectedStar> = stars
        .filter { it.x.isFinite() && it.y.isFinite() && it.confidence > 0f }
        .sortedWith(
            compareByDescending<DetectedStar> { detectionWeight(it) }
                .thenByDescending { it.flux }
                .thenBy { it.y }
                .thenBy { it.x }
        )

    private fun sector(star: DetectedStar, width: Int, height: Int): Int {
        val x = (star.x / width * SECTOR_COLUMNS).toInt().coerceIn(0, SECTOR_COLUMNS - 1)
        val y = (star.y / height * SECTOR_ROWS).toInt().coerceIn(0, SECTOR_ROWS - 1)
        return y * SECTOR_COLUMNS + x
    }

    private fun weightedMedian(values: List<Pair<Float, Float>>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedBy { it.first }
        val half = sorted.sumOf { it.second.toDouble() }.toFloat() * 0.5f
        var accumulated = 0f
        sorted.forEach { (value, weight) ->
            accumulated += weight
            if (accumulated >= half) return value
        }
        return sorted.last().first
    }

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

    private data class VoteBin(val x: Int, val y: Int) {
        var votes: Int = 0
        var weightedVotes: Float = 0f
        private var sectorMask: Int = 0
        val sectorCount: Int get() = Integer.bitCount(sectorMask)

        fun add(weight: Float, sector: Int) {
            votes++
            weightedVotes += weight
            sectorMask = sectorMask or (1 shl sector)
        }
    }

    private data class MatchedPair(val reference: DetectedStar, val candidate: DetectedStar)

    companion object {
        private const val DEFAULT_MAX_HYPOTHESES = 6
        private const val MAX_STARS = 96
        private const val MAX_RAW_PEAKS = 18
        private const val MAX_TRANSLATION_FRACTION = 0.20f
        private const val BIN_SIZE = 1.25f
        private const val PEAK_SEPARATION = 2.25f
        private const val MATCH_TOLERANCE = 1.65f
        private const val MIN_SUPPORT = 3
        private const val SECTOR_COLUMNS = 3
        private const val SECTOR_ROWS = 3
        private const val SECTOR_BONUS = 0.75f
        private const val SECTOR_SUPPORT_MULTIPLIER = 0.10f
        private const val MOVING_TRACK_WEIGHT = 3.5f
        private const val STATIONARY_TRACK_WEIGHT = 0.08f
        private const val MIXED_STATIONARY_WEIGHT = 0.15f
        private const val UNKNOWN_TRACK_WEIGHT = 1f
    }
}
