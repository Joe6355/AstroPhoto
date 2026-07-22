package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ReplayRegistrationCorrectionBakeoffTest {
    @Test
    fun translationFitRecoversDesiredToObservedCorrection() {
        val pairs = fieldPoints().mapIndexed { index, desired ->
            ReplayModelPair("trail-$index", desired, ReplayPoint(desired.x + 2.25, desired.y - 1.75))
        }

        val fit = ReplayStage1EModelFitter.fit(ReplayCorrectionModelType.TRANSLATION, pairs)
        assertNotNull(fit)
        val model = checkNotNull(fit).model

        assertEquals(2.25, model.tx, 1e-9)
        assertEquals(-1.75, model.ty, 1e-9)
        pairs.forEach { pair ->
            val corrected = checkNotNull(model.inverseMap(pair.observed))
            assertEquals(pair.desired.x, corrected.x, 1e-9)
            assertEquals(pair.desired.y, corrected.y, 1e-9)
        }
    }

    @Test
    fun leaveOneTrailOutAcceptsValidatedSimilarity() {
        val angleSin = 0.012
        val angleCos = 0.999928
        val scale = 1.0015
        val model = ReplayAffineCorrection(
            scale * angleCos,
            -scale * angleSin,
            scale * angleSin,
            scale * angleCos,
            2.0,
            -3.0,
            ReplayCorrectionModelType.SIMILARITY
        )
        val pairs = fieldPoints().mapIndexed { index, desired ->
            ReplayModelPair("trail-$index", desired, model.map(desired))
        }

        val validation = ReplayStage1EModelFitter.validate(
            "frame-1",
            1,
            ReplayCorrectionModelType.SIMILARITY,
            pairs
        )

        assertTrue(validation.rejectionReasons.toString(), validation.accepted)
        assertTrue(validation.correctedHeldOutMedian < 1e-6)
        assertNotNull(validation.fullModel)
    }

    @Test
    fun affineFitAndInversePreserveRoundTrip() {
        val expected = ReplayAffineCorrection(
            1.002,
            0.004,
            -0.003,
            0.998,
            1.25,
            -2.5,
            ReplayCorrectionModelType.AFFINE
        )
        val pairs = fieldPoints().mapIndexed { index, point ->
            ReplayModelPair("trail-$index", point, expected.map(point))
        }
        val fit = ReplayStage1EModelFitter.fit(ReplayCorrectionModelType.AFFINE, pairs)
        assertNotNull(fit)
        val actual = checkNotNull(fit)

        pairs.forEach { pair ->
            val observed = actual.model.map(pair.desired)
            assertTrue(hypot(observed.x - pair.observed.x, observed.y - pair.observed.y) <= 1e-6)
            val restored = checkNotNull(actual.model.inverseMap(observed))
            assertTrue(hypot(restored.x - pair.desired.x, restored.y - pair.desired.y) <= 1e-6)
        }
    }

    @Test
    fun fewerThanFourTrailsCannotGenerateCorrection() {
        val pairs = fieldPoints().take(3).mapIndexed { index, desired ->
            ReplayModelPair("trail-$index", desired, ReplayPoint(desired.x + 2.0, desired.y - 1.0))
        }

        val validation = ReplayStage1EModelFitter.validate(
            "frame-1",
            1,
            ReplayCorrectionModelType.TRANSLATION,
            pairs
        )

        assertFalse(validation.accepted)
        assertTrue("fewer_than_four_reliable_trails" in validation.rejectionReasons)
    }

    @Test
    fun heldOutObjectSpecificResidualRejectsGlobalCorrection() {
        val points = fieldPoints()
        val pairs = points.mapIndexed { index, desired ->
            val observed = if (index == points.lastIndex) {
                ReplayPoint(desired.x - 7.0, desired.y + 8.0)
            } else {
                ReplayPoint(desired.x + 2.0, desired.y - 1.0)
            }
            ReplayModelPair("trail-$index", desired, observed)
        }

        val validation = ReplayStage1EModelFitter.validate(
            "frame-1",
            1,
            ReplayCorrectionModelType.TRANSLATION,
            pairs
        )

        assertFalse(validation.accepted)
        assertTrue(validation.rejectionReasons.any { it.startsWith("held_out_worsened:") })
    }

    @Test
    fun collinearAffineModelIsRejectedAsIllConditioned() {
        val pairs = (0 until 5).map { index ->
            val desired = ReplayPoint(index * 100.0, index * 100.0)
            ReplayModelPair("trail-$index", desired, ReplayPoint(desired.x + 1.0, desired.y + 1.0))
        }

        assertEquals(null, ReplayStage1EModelFitter.fit(ReplayCorrectionModelType.AFFINE, pairs))
    }

    private fun fieldPoints(): List<ReplayPoint> = listOf(
        ReplayPoint(100.0, 100.0),
        ReplayPoint(1200.0, 120.0),
        ReplayPoint(150.0, 1700.0),
        ReplayPoint(1180.0, 1650.0),
        ReplayPoint(720.0, 900.0),
        ReplayPoint(900.0, 500.0)
    )
}
