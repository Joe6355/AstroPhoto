package com.joe6355.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessingEtaEstimatorTest {
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
