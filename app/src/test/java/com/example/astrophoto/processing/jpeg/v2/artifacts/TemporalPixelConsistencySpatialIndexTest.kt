package com.example.astrophoto.processing.jpeg.v2.artifacts

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalPixelConsistencySpatialIndexTest {
    @Test
    fun spatialIndexIsExactlyEquivalentToBruteForce() {
        val random = Random(731_911)
        val frames = List(8) { frameIndex ->
            val recurrent = List(20) { sourceIndex ->
                star(
                    x = 40f + sourceIndex * 13f + (random.nextFloat() - 0.5f) * 0.08f,
                    y = 60f + sourceIndex * 9f + (random.nextFloat() - 0.5f) * 0.08f,
                    flux = 1_000f + sourceIndex
                )
            }
            val noise = List(100) { noiseIndex ->
                star(
                    x = random.nextFloat() * 1_800f,
                    y = random.nextFloat() * 1_200f,
                    flux = frameIndex * 1_000f + noiseIndex
                )
            }
            ArtifactFrameObservation(
                "frame-$frameIndex",
                (recurrent + noise).shuffled(random)
            )
        }
        val consistency = TemporalPixelConsistency()
        val brute = consistency.stationaryTracksBruteForceProfiled(frames)
        val indexed = consistency.stationaryTracksProfiled(frames)

        assertEquals(brute.tracks, indexed.tracks)
        assertEquals(
            StaticArtifactAnalyzer().analyzeTracks(brute.tracks, frames.size, 2_000, 1_400),
            StaticArtifactAnalyzer().analyzeTracks(indexed.tracks, frames.size, 2_000, 1_400)
        )
        assertTrue(indexed.candidateVisitCount < brute.candidateVisitCount / 10L)
        assertTrue(indexed.distanceComparisonCount < brute.distanceComparisonCount / 10L)
    }

    @Test
    fun spatialIndexPreservesLastCandidateTieAndClaimOrder() {
        val frames = List(3) { frameIndex ->
            ArtifactFrameObservation(
                "frame-$frameIndex",
                listOf(
                    star(10f, 10f, flux = frameIndex * 10f + 1f),
                    star(10f, 10f, flux = frameIndex * 10f + 2f)
                )
            )
        }
        val consistency = TemporalPixelConsistency()
        val brute = consistency.stationaryTracksBruteForceProfiled(frames)
        val indexed = consistency.stationaryTracksProfiled(frames)

        assertEquals(brute.tracks, indexed.tracks)
        assertEquals(2, indexed.tracks.size)
        assertEquals(12f, indexed.tracks[0].observations[1].second.flux, 0f)
        assertEquals(11f, indexed.tracks[1].observations[1].second.flux, 0f)
    }

    private fun star(x: Float, y: Float, flux: Float) = DetectedStar(
        x = x,
        y = y,
        flux = flux,
        localBackground = 10f,
        localContrast = 150f,
        width = 1f,
        ellipticity = 0f,
        confidence = 1f
    )
}
