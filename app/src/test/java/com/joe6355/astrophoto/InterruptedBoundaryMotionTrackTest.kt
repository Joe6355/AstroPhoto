package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class InterruptedBoundaryMotionTrackTest {
    @Test fun coherentMiddleFramesRemainObservableWhenBothEndsContainCameraMovement() {
        val result = TemporalFeatureTrackBuilder().build(sequence())
        assertTrue(result.toString(), result.motionObservable)
        assertTrue(result.movingTrackCount.toString(), result.movingTrackCount >= 40)
        assertEquals(0.55f, result.coherentVelocityX, 0.01f)
        assertEquals(-1.25f, result.coherentVelocityY, 0.01f)
        assertTrue(result.stationaryTrackCount.toString(), result.stationaryTrackCount >= 10)
    }

    @Test fun missingStarsInTheFirstFrameDoNotHideASupportedInteriorSequence() {
        val frames = sequence().mapIndexed { index, frame ->
            if (index == 0) frame.copy(stars = emptyList()) else frame
        }
        val result = TemporalFeatureTrackBuilder().build(frames)
        assertTrue(result.toString(), result.motionObservable)
        assertTrue(result.movingTrackCount.toString(), result.movingTrackCount >= 40)
    }

    @Test fun stationaryStarsAndSensorPointsCannotCreateMotionThroughInteriorRetries() {
        val result = TemporalFeatureTrackBuilder().build((0..9).map { index ->
            TemporalFeatureFrame("f$index", index, stars())
        })
        assertFalse(result.toString(), result.motionObservable)
        assertEquals(0, result.movingTrackCount)
    }

    @Test fun independentTransientNoiseDoesNotBecomeAStellarSequence() {
        val random = Random(3193)
        val frames = (0..9).map { index -> TemporalFeatureFrame("f$index", index,
            (0 until 48).map { star(random.nextFloat() * 640f + 40f,
                random.nextFloat() * 880f + 40f) }) }
        assertFalse(TemporalFeatureTrackBuilder().build(frames).motionObservable)
    }

    @Test fun aSparseInteriorCoincidenceCannotReplaceAnUnobservablePrimarySequence() {
        val allowed = stars().indices.filter { it % 5 == 0 }.take(3).toSet()
        val frames = sequence().map { frame ->
            frame.copy(stars = frame.stars.filterIndexed { starIndex, _ ->
                starIndex >= 48 || starIndex in allowed
            })
        }
        assertFalse(TemporalFeatureTrackBuilder().build(frames).motionObservable)
    }

    @Test fun aSupportedSparsePrimarySequenceKeepsItsOriginalAcceptance() {
        val moving = stars().filterIndexed { index, _ -> index % 5 == 0 }.take(3)
        val frames = (0..9).map { index -> TemporalFeatureFrame("f$index", index,
            moving.map { it.copy(x = it.x + 0.55f * index, y = it.y - 1.25f * index) }) }
        val result = TemporalFeatureTrackBuilder().build(frames)
        assertTrue(result.toString(), result.motionObservable)
        assertEquals(3, result.movingTrackCount)
    }

    private fun sequence(): List<TemporalFeatureFrame> {
        val moving = stars()
        val fixed = listOf(40f to 40f, 350f to 40f, 680f to 40f, 40f to 900f, 350f to 900f,
            680f to 900f, 40f to 450f, 680f to 450f, 40f to 250f, 680f to 250f,
            40f to 650f, 680f to 650f, 350f to 900f).distinct().map { (x,y) -> star(x,y) }
        val shake = mapOf(0 to (-1.3f to -1.8f), 1 to (5.9f to -6.5f),
            8 to (-3.8f to 2.3f), 9 to (-4.1f to 2.6f))
        return (0..9).map { index ->
            val delta = index - 4
            val (extraX, extraY) = shake[index] ?: (0f to 0f)
            TemporalFeatureFrame("f$index", index, moving.map {
                it.copy(x = it.x + 0.55f * delta + extraX, y = it.y - 1.25f * delta + extraY)
            } + fixed)
        }
    }

    private fun stars(): List<DetectedStar> {
        val random = Random(153)
        return (0 until 48).map { index -> star(80f + index % 8 * 75f + random.nextFloat() * 8f,
            80f + index / 8 * 145f + random.nextFloat() * 8f) }
    }

    private fun star(x: Float, y: Float) = DetectedStar(x, y, 100f, 4f, 20f, 1.5f, 0.1f, 0.95f)
}
