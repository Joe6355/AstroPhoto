package com.example.astrophoto

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SeriesCaptureRequest(
    val format: String,
    val frameCount: Int,
    val delaySeconds: Int,
    val startTimerSeconds: Int,
    val exposureTimeNs: Long,
    val iso: Int,
    val focusDistance: Float,
    val focusMode: String,
    val jpegQuality: Int,
    val sessionFolder: String,
    val relativeDirectory: String,
    val filePrefix: String,
    val vibrationAfterSeries: Boolean,
    val soundAfterSeries: Boolean
)

data class SeriesCaptureState(
    val current: Int,
    val total: Int,
    val status: String,
    val remainingMillis: Long,
    val running: Boolean,
    val message: String? = null
)

/** Process-local state mirror for the service-owned Camera2 capture series. */
object SeriesCaptureCoordinator {
    private val mutableState = MutableStateFlow<SeriesCaptureState?>(null)
    val state: StateFlow<SeriesCaptureState?> = mutableState.asStateFlow()

    fun start(context: Context, request: SeriesCaptureRequest) {
        require(request.frameCount > 0)
        val remaining = estimatedSeriesDurationMillis(
            request.exposureTimeNs,
            request.frameCount,
            request.delaySeconds,
            request.startTimerSeconds
        )
        update(
            current = 0,
            total = request.frameCount,
            status = if (request.startTimerSeconds > 0) {
                "Старт через ${request.startTimerSeconds}..."
            } else {
                "Подготовка камеры..."
            },
            remainingMillis = remaining,
            running = true
        )
        try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                SeriesCaptureForegroundService.startIntent(context.applicationContext, request)
            )
        } catch (error: Throwable) {
            update(
                current = 0,
                total = request.frameCount,
                status = "",
                remainingMillis = 0L,
                running = false,
                message = "Не удалось запустить фоновую серию"
            )
            throw error
        }
    }

    fun update(
        current: Int,
        total: Int,
        status: String,
        remainingMillis: Long,
        running: Boolean = true,
        message: String? = null
    ) {
        mutableState.value = SeriesCaptureState(
            current = current.coerceAtLeast(0),
            total = total.coerceAtLeast(1),
            status = status,
            remainingMillis = remainingMillis.coerceAtLeast(0L),
            running = running,
            message = message
        )
    }

    fun requestStop(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, SeriesCaptureForegroundService::class.java)
                .setAction(SeriesCaptureForegroundService.ACTION_STOP)
        )
    }
}
