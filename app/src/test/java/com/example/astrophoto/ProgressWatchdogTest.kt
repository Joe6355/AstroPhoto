package com.example.astrophoto

import org.junit.Assert.assertThrows
import org.junit.Test

class ProgressWatchdogTest {
    @Test
    fun continuousProgressAllowsOperationLongerThanTotalTimeout() {
        var now = 0L
        val watchdog = ProgressWatchdog(timeoutMillis = 300_000L) { now }

        repeat(10) {
            now += 240_000L * 1_000_000L
            watchdog.check()
            watchdog.reportProgress()
        }
    }

    @Test
    fun missingProgressTriggersWatchdog() {
        var now = 0L
        val watchdog = ProgressWatchdog(timeoutMillis = 300_000L) { now }
        now = 300_000L * 1_000_000L

        assertThrows(ProcessingStalledException::class.java) {
            watchdog.check()
        }
    }
}
