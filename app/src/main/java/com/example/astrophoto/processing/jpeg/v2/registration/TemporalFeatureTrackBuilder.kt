package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import kotlin.math.hypot

class TemporalFeatureTrackBuilder {
    fun build(frames: List<TemporalFeatureFrame>): TemporalTrackAnalysis {
        val ordered = frames.sortedBy { it.captureIndex }
        if (ordered.size < 3 || ordered.first().stars.isEmpty()) {
            return TemporalTrackAnalysis(emptyList(), false, 0f, 0f)
        }
        val first = ordered.first()
        val anchor = ordered.lastOrNull { it.captureIndex > first.captureIndex && it.stars.isNotEmpty() }
            ?: return TemporalTrackAnalysis(emptyList(), false, 0f, 0f)
        val usedByFrame = ordered.associate { it.frameId to BooleanArray(it.stars.size) }
        val tracks = first.stars
            .withIndex()
            .sortedByDescending { quality(it.value) }
            .take(MAX_TRACK_SEEDS)
            .mapNotNull { indexed ->
                if (usedByFrame.getValue(first.frameId)[indexed.index]) return@mapNotNull null
                val seed = indexed.value
                val delta = (anchor.captureIndex - first.captureIndex).coerceAtLeast(1)
                val velocities = buildList {
                    add(0f to 0f)
                    anchor.stars.take(MAX_ANCHOR_STARS).forEach { candidate ->
                        val vx = (candidate.x - seed.x) / delta
                        val vy = (candidate.y - seed.y) / delta
                        if (hypot(vx, vy) <= MAX_ANALYSIS_VELOCITY) add(vx to vy)
                    }
                }.distinctBy { velocityKey(it.first, it.second) }
                val best = velocities.map { velocity ->
                    observationsFor(seed, first, ordered, velocity, usedByFrame)
                }.filter { it.size >= MIN_TRACK_OBSERVATIONS }
                    .minWithOrNull(
                        compareByDescending<List<IndexedObservation>> { it.size }
                            .thenBy { fit(it).residual }
                            .thenByDescending { it.sumOf { observation -> quality(observation.star).toDouble() } }
                    ) ?: return@mapNotNull null
                best.forEach { usedByFrame.getValue(it.frame.frameId)[it.starIndex] = true }
                val fitted = fit(best)
                TemporalFeatureTrack(
                    observations = best.map {
                        TemporalTrackObservation(it.frame.frameId, it.frame.captureIndex, it.star)
                    },
                    presenceRatio = best.size.toFloat() / ordered.size,
                    velocityX = fitted.velocityX,
                    velocityY = fitted.velocityY,
                    fitResidual = fitted.residual,
                    cluster = TemporalMotionCluster.UNSTABLE_OR_UNKNOWN
                )
            }

        val captureSpan = (ordered.last().captureIndex - ordered.first().captureIndex).coerceAtLeast(1)
        val stationary = tracks.filter {
            it.presenceRatio >= MIN_PRESENCE_RATIO &&
                hypot(it.velocityX, it.velocityY) * captureSpan <= STATIONARY_TOTAL_MOVEMENT &&
                it.fitResidual <= MAX_TRACK_RESIDUAL
        }
        val movingCandidates = tracks.filter {
            it.presenceRatio >= MIN_PRESENCE_RATIO &&
                hypot(it.velocityX, it.velocityY) * captureSpan >= MIN_OBSERVABLE_TOTAL_MOVEMENT &&
                it.fitResidual <= MAX_TRACK_RESIDUAL
        }
        val coherentVelocity = dominantMovingVelocity(movingCandidates, ordered.first())
        val coherentMoving = if (coherentVelocity == null) emptySet() else movingCandidates.filter {
            hypot(
                it.velocityX - coherentVelocity.first,
                it.velocityY - coherentVelocity.second
            ) <= MOVING_VELOCITY_TOLERANCE
        }.toSet()
        val occupiedSectors = coherentMoving.mapNotNull { it.observations.firstOrNull()?.star }
            .map { sector(it, ordered.first()) }
            .distinct()
            .size
        val observable = coherentMoving.size >= MIN_COHERENT_MOVING_TRACKS &&
            occupiedSectors >= MIN_MOVING_SECTORS
        val classified = tracks.map { track ->
            track.copy(
                cluster = when {
                    track in stationary -> TemporalMotionCluster.STATIONARY_CAMERA_SPACE
                    observable && track in coherentMoving -> TemporalMotionCluster.COHERENT_MOVING_SKY
                    else -> TemporalMotionCluster.UNSTABLE_OR_UNKNOWN
                }
            )
        }
        return TemporalTrackAnalysis(
            tracks = classified,
            motionObservable = observable,
            coherentVelocityX = if (observable) coherentVelocity?.first ?: 0f else 0f,
            coherentVelocityY = if (observable) coherentVelocity?.second ?: 0f else 0f
        )
    }

    private fun observationsFor(
        seed: DetectedStar,
        first: TemporalFeatureFrame,
        frames: List<TemporalFeatureFrame>,
        velocity: Pair<Float, Float>,
        usedByFrame: Map<String, BooleanArray>
    ): List<IndexedObservation> = frames.mapNotNull { frame ->
        val delta = frame.captureIndex - first.captureIndex
        val expectedX = seed.x + velocity.first * delta
        val expectedY = seed.y + velocity.second * delta
        frame.stars.withIndex()
            .asSequence()
            .filterNot { usedByFrame.getValue(frame.frameId)[it.index] }
            .map { indexed ->
                indexed to hypot(indexed.value.x - expectedX, indexed.value.y - expectedY)
            }
            .filter { it.second <= TRACK_MATCH_TOLERANCE }
            .minWithOrNull(compareBy<Pair<IndexedValue<DetectedStar>, Float>> { it.second }.thenBy { it.first.index })
            ?.first
            ?.let { IndexedObservation(frame, it.index, it.value) }
    }

    private fun fit(observations: List<IndexedObservation>): Fit {
        val slopesX = mutableListOf<Float>()
        val slopesY = mutableListOf<Float>()
        for (first in 0 until observations.lastIndex) for (second in first + 1 until observations.size) {
            val delta = observations[second].frame.captureIndex - observations[first].frame.captureIndex
            if (delta != 0) {
                slopesX += (observations[second].star.x - observations[first].star.x) / delta
                slopesY += (observations[second].star.y - observations[first].star.y) / delta
            }
        }
        val velocityX = median(slopesX)
        val velocityY = median(slopesY)
        val interceptX = median(observations.map { it.star.x - velocityX * it.frame.captureIndex })
        val interceptY = median(observations.map { it.star.y - velocityY * it.frame.captureIndex })
        val residual = median(observations.map {
            hypot(
                it.star.x - (interceptX + velocityX * it.frame.captureIndex),
                it.star.y - (interceptY + velocityY * it.frame.captureIndex)
            )
        })
        return Fit(velocityX, velocityY, residual)
    }

    private fun dominantMovingVelocity(
        tracks: List<TemporalFeatureTrack>,
        dimensions: TemporalFeatureFrame
    ): Pair<Float, Float>? = tracks.maxWithOrNull(
        compareBy<TemporalFeatureTrack> { seed ->
            tracks.filter {
                hypot(it.velocityX - seed.velocityX, it.velocityY - seed.velocityY) <=
                    MOVING_VELOCITY_TOLERANCE
            }.mapNotNull { it.observations.firstOrNull()?.star }
                .map { sector(it, dimensions) }
                .distinct().size
        }.thenBy { seed ->
            tracks.count {
                hypot(it.velocityX - seed.velocityX, it.velocityY - seed.velocityY) <=
                    MOVING_VELOCITY_TOLERANCE
            }
        }.thenByDescending { it.fitResidual }
    )?.let { seed ->
        val cluster = tracks.filter {
            hypot(it.velocityX - seed.velocityX, it.velocityY - seed.velocityY) <=
                MOVING_VELOCITY_TOLERANCE
        }
        median(cluster.map { it.velocityX }) to median(cluster.map { it.velocityY })
    }

    private fun sector(star: DetectedStar, frame: TemporalFeatureFrame): Int {
        val width = frame.stars.maxOfOrNull { it.x }?.coerceAtLeast(1f) ?: 1f
        val height = frame.stars.maxOfOrNull { it.y }?.coerceAtLeast(1f) ?: 1f
        val x = (star.x / width * 3f).toInt().coerceIn(0, 2)
        val y = (star.y / height * 3f).toInt().coerceIn(0, 2)
        return y * 3 + x
    }

    private fun quality(star: DetectedStar): Float =
        star.confidence.coerceIn(0f, 1f) * (1f + star.localContrast.coerceAtLeast(0f))

    private fun velocityKey(x: Float, y: Float): Pair<Int, Int> =
        (x / VELOCITY_BIN).toInt() to (y / VELOCITY_BIN).toInt()

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private data class IndexedObservation(
        val frame: TemporalFeatureFrame,
        val starIndex: Int,
        val star: DetectedStar
    )

    private data class Fit(val velocityX: Float, val velocityY: Float, val residual: Float)

    companion object {
        private const val MAX_TRACK_SEEDS = 96
        private const val MAX_ANCHOR_STARS = 96
        private const val MIN_TRACK_OBSERVATIONS = 3
        private const val TRACK_MATCH_TOLERANCE = 1.35f
        private const val MAX_ANALYSIS_VELOCITY = 12f
        private const val VELOCITY_BIN = 0.12f
        private const val MIN_PRESENCE_RATIO = 0.35f
        private const val STATIONARY_TOTAL_MOVEMENT = 1.2f
        private const val MIN_OBSERVABLE_TOTAL_MOVEMENT = 2.5f
        private const val MAX_TRACK_RESIDUAL = 0.85f
        private const val MOVING_VELOCITY_TOLERANCE = 0.35f
        private const val MIN_COHERENT_MOVING_TRACKS = 3
        private const val MIN_MOVING_SECTORS = 2
    }
}
