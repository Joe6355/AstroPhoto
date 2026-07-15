package com.example.astrophoto.processing.jpeg.v2.artifacts

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import kotlin.math.ceil
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

class TemporalPixelConsistency {
    fun stationaryTracks(frames: List<ArtifactFrameObservation>): List<TemporalPointTrack> {
        if (frames.size < MIN_TEMPORAL_FRAMES) return emptyList()
        val claimed = frames.map { BooleanArray(it.stars.size) }
        val tracks = mutableListOf<TemporalPointTrack>()
        frames.forEachIndexed { frameIndex, frame ->
            frame.stars.forEachIndexed starLoop@{ starIndex, anchor ->
                if (claimed[frameIndex][starIndex]) return@starLoop
                val observations = mutableListOf(frameIndex to anchor)
                for (otherFrameIndex in frameIndex + 1 until frames.size) {
                    val candidates = frames[otherFrameIndex].stars
                    var bestIndex = -1
                    var bestDistanceSquared = MAX_STATIONARY_DISTANCE * MAX_STATIONARY_DISTANCE
                    candidates.forEachIndexed { index, candidate ->
                        if (claimed[otherFrameIndex][index]) return@forEachIndexed
                        val dx = candidate.x - anchor.x
                        val dy = candidate.y - anchor.y
                        val distanceSquared = dx * dx + dy * dy
                        if (distanceSquared <= bestDistanceSquared) {
                            bestDistanceSquared = distanceSquared
                            bestIndex = index
                        }
                    }
                    if (bestIndex >= 0) observations += otherFrameIndex to candidates[bestIndex]
                }
                val required = maxOf(
                    MIN_TEMPORAL_FRAMES,
                    ceil(frames.size * MIN_PRESENCE_RATIO).toInt()
                )
                if (observations.size < required) return@starLoop
                observations.forEach { (index, star) ->
                    val claimedIndex = frames[index].stars.indexOfFirst { it === star }
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
        return tracks.sortedWith(
            compareByDescending<TemporalPointTrack> { it.presenceRatio }
                .thenBy { it.positionDeviation }
                .thenBy { it.observations.first().second.y }
                .thenBy { it.observations.first().second.x }
        )
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
    }
}
