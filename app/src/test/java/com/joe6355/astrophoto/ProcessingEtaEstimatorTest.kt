package com.joe6355.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingEtaEstimatorTest {
    @Test fun fractionOfMillisecondDoesNotBecomeZeroBeforeCompletion() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Тайлы", 0, 10_000, 0)
        assertNull(estimator.update("Тайлы", 9_999, 10_000, 1_000))
    }

    @Test fun recentRateTracksSlowdownInsteadOfTheFastStart() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Этап", 0, 20, 0)
        var now = 0L
        var estimate: Long? = null
        for (current in 1..14) {
            now += if (current <= 10) 1_000 else 3_000
            estimate = estimator.update("Этап", current, 20, now)
        }
        val actualRemaining = 6 * 3_000L
        val oldCumulativeEstimate = now * 6 / 14
        assertEquals(actualRemaining, estimate)
        assertTrue(kotlin.math.abs(requireNotNull(estimate) - actualRemaining) <
            kotlin.math.abs(oldCumulativeEstimate - actualRemaining))
    }

    @Test fun recentRateTracksSpeedupInsteadOfTheSlowStart() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Этап", 0, 20, 0)
        var now = 0L
        var estimate: Long? = null
        for (current in 1..18) {
            now += if (current <= 10) 3_000 else 1_000
            estimate = estimator.update("Этап", current, 20, now)
        }
        assertEquals(2_000L, estimate)
        assertTrue(kotlin.math.abs(requireNotNull(estimate) - 2_000L) <
            kotlin.math.abs(now * 2 / 18 - 2_000L))
    }

    @Test fun expensiveFirstUnitDoesNotBiasAllFollowingUnits() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Этап", 0, 10, 0)
        estimator.update("Этап", 1, 10, 10_000)
        estimator.update("Этап", 2, 10, 11_000)
        estimator.update("Этап", 3, 10, 12_000)
        assertEquals(6_000L, estimator.update("Этап", 4, 10, 13_000))
    }

    @Test fun denseTileCallbacksKeepEnoughElapsedTimeToEstimate() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Тайлы", 0, 10_000, 0)
        var estimate: Long? = null
        for (current in 1..2_000) estimate = estimator.update("Тайлы", current, 10_000, current.toLong())
        assertEquals(8_000L, estimate)
    }

    @Test fun sparseBatchedProgressStillUsesMeasuredWork() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Пакет", 0, 100, 0)
        assertEquals(8_000L, estimator.update("Пакет", 20, 100, 2_000))
        assertEquals(4_000L, estimator.update("Пакет", 60, 100, 6_000))
    }

    @Test fun repeatedProgressDoesNotArtificiallySlowRateAndEventuallyHidesStaleEta() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Этап", 0, 10, 0)
        assertEquals(8_000L, estimator.update("Этап", 2, 10, 2_000))
        assertEquals(7_000L, estimator.update("Этап", 2, 10, 3_000))
        assertNull(estimator.update("Этап", 2, 10, 12_000))
        assertTrue(requireNotNull(estimator.update("Этап", 4, 10, 14_000)) > 0)
    }

    @Test fun clockRewindSinceLastObservationNeedsFreshSamples() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Этап", 0, 10, 0)
        estimator.update("Этап", 2, 10, 2_000)
        assertNull(estimator.update("Этап", 3, 10, 1_500))
        assertEquals(5_000L, estimator.update("Этап", 5, 10, 3_500))
    }

    @Test fun overdueLastUnitDoesNotShowZeroSecondsBeforeCompletion() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Этап", 0, 3, 0)
        assertEquals(1_000L, estimator.update("Этап", 2, 3, 2_000))
        assertNull(estimator.update("Этап", 2, 3, 4_000))
    }

    @Test fun usesMeasuredRateAndIgnoresChangingCounterInMessage() {
        val estimator = ProcessingEtaEstimator()
        assertNull(estimator.update("Анализ 0/30", 0, 30, 1_000))
        assertNull(estimator.update("Анализ 1/30", 1, 30, 2_000))
        assertEquals(28_000L, estimator.update("Анализ 2/30", 2, 30, 3_000))
        assertNull(estimator.update("Анализ 30/30", 30, 30, 31_000))
    }

    @Test fun stageChangeDoesNotReusePreviousStageRate() {
        val estimator = ProcessingEtaEstimator()
        estimator.update("Анализ", 0, 10, 0)
        assertEquals(8_000L, estimator.update("Анализ", 2, 10, 2_000))
        assertNull(estimator.update("Интеграция", 2, 10, 2_100))
        assertEquals(30_000L, estimator.update("Интеграция", 4, 10, 12_100))
    }

    @Test fun resumedOrRewoundStageNeedsNewSamples() {
        val estimator = ProcessingEtaEstimator()
        assertNull(estimator.update("Анализ", 18, 30, 0))
        assertEquals(10_000L, estimator.update("Анализ", 20, 30, 2_000))
        assertNull(estimator.update("Анализ", 0, 30, 3_000))
        assertEquals(28_000L, estimator.update("Анализ", 2, 30, 5_000))
        assertNull(estimator.update("Анализ", 3, 30, 1_000))
    }

    @Test fun invalidAndInstantProgressNeverInventsAnEta() {
        val estimator = ProcessingEtaEstimator()
        assertNull(estimator.update("Stack", 0, 0, 0))
        assertNull(estimator.update("Stack", 0, 1, 1_000))
        assertNull(estimator.update("Stack", 1, 1, 2_000))
        assertNull(estimator.update("Stack", 0, 100, 3_000))
        assertNull(estimator.update("Stack", 2, 100, 3_001))
        assertNull(estimator.update("Stack", 3, 101, 4_000))
    }

    @Test fun finishedStateHidesStaleEta() {
        val state = SessionProcessingState("s", "Deep Sky", "Анализ", 2, 30, true, 28_000)
        assertEquals("Анализ\nДо конца этапа ≈ ${formatSeriesDuration(28_000)}", state.statusWithEta)
        assertEquals("Анализ", state.copy(running = false).statusWithEta)
    }
}
