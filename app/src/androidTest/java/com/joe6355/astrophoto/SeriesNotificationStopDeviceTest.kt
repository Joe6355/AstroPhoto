package com.joe6355.astrophoto

import android.app.ActivityManager
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in Camera2 test: new QA directory only; sends the real notification PendingIntent. */
class SeriesNotificationStopDeviceTest {
    @get:Rule val foreground = AstroUiForegroundRule()

    @Test fun notificationStopFinishesServiceAndAllowsNextSeries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("seriesStopAudit") == "true")
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val activities = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun serviceRunning() = activities.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == SeriesCaptureForegroundService::class.java.name
        }
        fun notification() = manager.activeNotifications.firstOrNull {
            it.notification.channelId == "series_capture"
        }
        fun awaitState(description: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("$description; state=${SeriesCaptureCoordinator.state.value}", predicate())
        }
        check(!serviceRunning()) { "An existing capture service must not be interrupted by this audit" }
        val folder = "Session_QA_Stop_${System.currentTimeMillis()}"
        for (mode in listOf("timer", "capture", "pause", "complete")) {
            val request = SeriesCaptureRequest(
                format = "JPEG", frameCount = if (mode == "complete") 1 else 20,
                delaySeconds = if (mode == "pause") 10 else 0,
                startTimerSeconds = if (mode == "timer") 10 else 0,
                exposureTimeNs = if (mode == "capture") 3_000_000_000L else 1_000_000_000L,
                iso = 400, focusDistance = 0f, focusMode = "INFINITY", jpegQuality = 92,
                sessionFolder = folder, relativeDirectory = "Pictures/AstroPhoto/$folder/Lights/JPEG/",
                filePrefix = "QA_$mode", vibrationAfterSeries = false, soundAfterSeries = false
            )
            try {
                instrumentation.runOnMainSync { SeriesCaptureCoordinator.start(context, request) }
                if (mode != "complete") {
                    awaitState("Capture reached $mode with a notification") {
                        val state = SeriesCaptureCoordinator.state.value
                        state?.running == true && notification() != null && when (mode) {
                            "timer" -> state.status.startsWith("Старт через")
                            "capture" -> state.current == 1 && state.status == "Съёмка..."
                            else -> state.status == "Пауза..."
                        }
                    }
                    val stop = requireNotNull(notification()).notification.actions.single {
                        it.title.toString() == "Остановить"
                    }
                    stop.actionIntent.send()
                }
                awaitState("Series finished") { SeriesCaptureCoordinator.state.value?.running == false }
                val state = requireNotNull(SeriesCaptureCoordinator.state.value)
                assertEquals(if (mode == "timer") 0 else 1, state.current)
                assertTrue(state.message.orEmpty().startsWith(
                    if (mode == "complete") "Серия завершена" else "Серия остановлена"))
                awaitState("Service and notification removed") { !serviceRunning() && notification() == null }
                android.util.Log.i("AstroPhotoDeviceQA", "QA_STOP_PASS mode=$mode saved=${state.current} folder=$folder")
            } finally {
                // Cleanup only the service created by this test if an assertion fails.
                if (serviceRunning()) context.stopService(android.content.Intent(context, SeriesCaptureForegroundService::class.java))
            }
        }
    }
}
