package com.example.astrophoto.processing.jpeg.v2.artifacts

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

data class ArtifactFrameObservation(
    val frameId: String,
    val stars: List<DetectedStar>
)

data class TemporalPointTrack(
    val observations: List<Pair<Int, DetectedStar>>,
    val presenceRatio: Float,
    val positionDeviation: Float,
    val contrastVariation: Float,
    val widthVariation: Float
)

data class ProfiledTemporalPointTracks(
    val tracks: List<TemporalPointTrack>,
    val elapsedNanos: Long,
    val anchorVisitCount: Long,
    val unclaimedAnchorCount: Long,
    val candidateVisitCount: Long,
    val distanceComparisonCount: Long,
    val identityLookupCount: Long
)

class TemporalPixelConsistency {
    fun stationaryTracks(frames: List<ArtifactFrameObservation>): List<TemporalPointTrack> =
        stationaryTracksProfiled(frames).tracks

    fun stationaryTracksProfiled(
        frames: List<ArtifactFrameObservation>
    ): ProfiledTemporalPointTracks = stationaryTracksProfiled(
        frames,
        CandidateSearchMode.SPATIAL_INDEX
    )

    internal fun stationaryTracksBruteForceProfiled(
        frames: List<ArtifactFrameObservation>
    ): ProfiledTemporalPointTracks = stationaryTracksProfiled(
        frames,
        CandidateSearchMode.BRUTE_FORCE
    )

    private fun stationaryTracksProfiled(
        frames: List<ArtifactFrameObservation>,
        searchMode: CandidateSearchMode
    ): ProfiledTemporalPointTracks {
        val started = System.nanoTime()
        if (frames.size < MIN_TEMPORAL_FRAMES) {
            return ProfiledTemporalPointTracks(
                emptyList(),
                System.nanoTime() - started,
                0L,
                0L,
                0L,
                0L,
                0L
            )
        }
        val claimed = frames.map { BooleanArray(it.stars.size) }
        val spatialIndices = if (searchMode == CandidateSearchMode.SPATIAL_INDEX) {
            frames.map { FrameSpatialIndex(it.stars, MAX_STATIONARY_DISTANCE) }
        } else {
            emptyList()
        }
        val tracks = mutableListOf<TemporalPointTrack>()
        var anchorVisitCount = 0L
        var unclaimedAnchorCount = 0L
        val searchStats = CandidateSearchStats()
        var identityLookupCount = 0L
        frames.forEachIndexed { frameIndex, frame ->
            frame.stars.forEachIndexed starLoop@{ starIndex, anchor ->
                anchorVisitCount++
                if (claimed[frameIndex][starIndex]) return@starLoop
                unclaimedAnchorCount++
                val observations = mutableListOf(frameIndex to anchor)
                for (otherFrameIndex in frameIndex + 1 until frames.size) {
                    val candidates = frames[otherFrameIndex].stars
                    val bestIndex = when (searchMode) {
                        CandidateSearchMode.SPATIAL_INDEX -> {
                            spatialIndices[otherFrameIndex].findBest(
                                anchor,
                                candidates,
                                claimed[otherFrameIndex],
                                searchStats
                            )
                        }
                        CandidateSearchMode.BRUTE_FORCE -> findBestBruteForce(
                            anchor,
                            candidates,
                            claimed[otherFrameIndex],
                            searchStats
                        )
                    }
                    if (bestIndex >= 0) observations += otherFrameIndex to candidates[bestIndex]
                }
                val required = maxOf(
                    MIN_TEMPORAL_FRAMES,
                    ceil(frames.size * MIN_PRESENCE_RATIO).toInt()
                )
                if (observations.size < required) return@starLoop
                observations.forEach { (index, star) ->
                    var claimedIndex = -1
                    for (candidateIndex in frames[index].stars.indices) {
                        identityLookupCount++
                        if (frames[index].stars[candidateIndex] === star) {
                            claimedIndex = candidateIndex
                            break
                        }
                    }
                    if (claimedIndex >= 0) claimed[index][claimedIndex] = true
                }
                val stars = observations.map { it.second }
                val meanX = stars.map { it.x }.average().toFloat()
                val meanY = stars.map { it.y }.average().toFloat()
                val deviation = sqrt(stars.sumOf {
                    val dx = it.x - meanX
                    val dy = it.y - meanY
                    (dx * dx + dy * dy).toDouble()
                }.toFloat() / stars.size)
                tracks += TemporalPointTrack(
                    observations = observations,
                    presenceRatio = stars.size.toFloat() / frames.size,
                    positionDeviation = deviation,
                    contrastVariation = relativeDeviation(stars.map { it.localContrast }),
                    widthVariation = relativeDeviation(stars.map { it.width })
                )
            }
        }
        val sorted = tracks.sortedWith(
            compareByDescending<TemporalPointTrack> { it.presenceRatio }
                .thenBy { it.positionDeviation }
                .thenBy { it.observations.first().second.y }
                .thenBy { it.observations.first().second.x }
        )
        return ProfiledTemporalPointTracks(
            tracks = sorted,
            elapsedNanos = System.nanoTime() - started,
            anchorVisitCount = anchorVisitCount,
            unclaimedAnchorCount = unclaimedAnchorCount,
            candidateVisitCount = searchStats.candidateVisitCount,
            distanceComparisonCount = searchStats.distanceComparisonCount,
            identityLookupCount = identityLookupCount
        )
    }

    private fun findBestBruteForce(
        anchor: DetectedStar,
        candidates: List<DetectedStar>,
        claimed: BooleanArray,
        stats: CandidateSearchStats
    ): Int {
        var bestIndex = -1
        var bestDistanceSquared = MAX_STATIONARY_DISTANCE_SQUARED
        candidates.forEachIndexed { index, candidate ->
            stats.candidateVisitCount++
            if (claimed[index]) return@forEachIndexed
            stats.distanceComparisonCount++
            val dx = candidate.x - anchor.x
            val dy = candidate.y - anchor.y
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun relativeDeviation(values: List<Float>): Float {
        val mean = values.average().toFloat().coerceAtLeast(0.001f)
        val variance = values.sumOf { value ->
            val delta = value - mean
            (delta * delta).toDouble()
        }.toFloat() / values.size.coerceAtLeast(1)
        return sqrt(variance) / mean
    }

    companion object {
        const val MIN_TEMPORAL_FRAMES = 3
        const val MIN_PRESENCE_RATIO = 0.70f
        const val MAX_STATIONARY_DISTANCE = 0.35f
        private const val MAX_STATIONARY_DISTANCE_SQUARED =
            MAX_STATIONARY_DISTANCE * MAX_STATIONARY_DISTANCE
    }
}

private enum class CandidateSearchMode {
    SPATIAL_INDEX,
    BRUTE_FORCE
}

private class CandidateSearchStats {
    var candidateVisitCount: Long = 0L
    var distanceComparisonCount: Long = 0L
}

private class FrameSpatialIndex(
    stars: List<DetectedStar>,
    private val cellSize: Float
) {
    private val sortedCandidateIndices = IntArray(stars.size) { it }
    private val sortedCellKeys = LongArray(stars.size) { index ->
        cellKey(stars[index].x, stars[index].y)
    }

    init {
        sortParallel(sortedCellKeys, sortedCandidateIndices, 0, stars.lastIndex)
    }

    fun findBest(
        anchor: DetectedStar,
        candidates: List<DetectedStar>,
        claimed: BooleanArray,
        stats: CandidateSearchStats
    ): Int {
        if (!anchor.x.isFinite() || !anchor.y.isFinite()) return -1
        val radius = TemporalPixelConsistency.MAX_STATIONARY_DISTANCE
        val minimumCellX = cellCoordinate(anchor.x - radius)
        val maximumCellX = cellCoordinate(anchor.x + radius)
        val minimumCellY = cellCoordinate(anchor.y - radius)
        val maximumCellY = cellCoordinate(anchor.y + radius)
        var bestIndex = -1
        var bestDistanceSquared = radius * radius
        for (cellY in minimumCellY..maximumCellY) {
            for (cellX in minimumCellX..maximumCellX) {
                val key = cellKey(cellX, cellY)
                var sortedIndex = lowerBound(sortedCellKeys, key)
                while (
                    sortedIndex < sortedCellKeys.size &&
                    sortedCellKeys[sortedIndex] == key
                ) {
                    val candidateIndex = sortedCandidateIndices[sortedIndex]
                    stats.candidateVisitCount++
                    if (!claimed[candidateIndex]) {
                        stats.distanceComparisonCount++
                        val candidate = candidates[candidateIndex]
                        val dx = candidate.x - anchor.x
                        val dy = candidate.y - anchor.y
                        val distanceSquared = dx * dx + dy * dy
                        if (
                            distanceSquared < bestDistanceSquared ||
                            (
                                distanceSquared == bestDistanceSquared &&
                                    candidateIndex > bestIndex
                                )
                        ) {
                            bestDistanceSquared = distanceSquared
                            bestIndex = candidateIndex
                        }
                    }
                    sortedIndex++
                }
            }
        }
        return bestIndex
    }

    private fun cellKey(x: Float, y: Float): Long =
        cellKey(cellCoordinate(x), cellCoordinate(y))

    private fun cellCoordinate(value: Float): Int =
        floor(value.toDouble() / cellSize).toInt()

    private fun cellKey(x: Int, y: Int): Long =
        (x.toLong() shl Int.SIZE_BITS) xor (y.toLong() and 0xFFFF_FFFFL)

    private fun lowerBound(values: LongArray, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    private fun sortParallel(
        keys: LongArray,
        indices: IntArray,
        initialLow: Int,
        initialHigh: Int
    ) {
        var low = initialLow
        var high = initialHigh
        while (low < high) {
            var left = low
            var right = high
            val pivotPosition = (low + high) ushr 1
            val pivotKey = keys[pivotPosition]
            val pivotIndex = indices[pivotPosition]
            while (left <= right) {
                while (compare(keys[left], indices[left], pivotKey, pivotIndex) < 0) left++
                while (compare(keys[right], indices[right], pivotKey, pivotIndex) > 0) right--
                if (left <= right) {
                    val key = keys[left]
                    keys[left] = keys[right]
                    keys[right] = key
                    val index = indices[left]
                    indices[left] = indices[right]
                    indices[right] = index
                    left++
                    right--
                }
            }
            if (right - low < high - left) {
                if (low < right) sortParallel(keys, indices, low, right)
                low = left
            } else {
                if (left < high) sortParallel(keys, indices, left, high)
                high = right
            }
        }
    }

    private fun compare(
        firstKey: Long,
        firstIndex: Int,
        secondKey: Long,
        secondIndex: Int
    ): Int = when {
        firstKey < secondKey -> -1
        firstKey > secondKey -> 1
        firstIndex < secondIndex -> -1
        firstIndex > secondIndex -> 1
        else -> 0
    }
}
