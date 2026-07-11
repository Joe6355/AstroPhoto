package com.example.astrophoto

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean

enum class ExposureStatus {
    TOO_DARK,
    OK,
    TOO_BRIGHT,
    OVEREXPOSED,
    UNKNOWN
}

data class ExposureAnalysis(
    val averageBrightness: Float,
    val shadowPercent: Float,
    val highlightPercent: Float,
    val histogram: List<Float>,
    val status: ExposureStatus
)

class ExposureAnalyzer(
    private val textureView: TextureView,
    private val onAnalysis: (ExposureAnalysis) -> Unit,
    private val onUnavailable: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var consecutiveFailures = 0

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            try {
                if (!textureView.isAvailable) {
                    scheduleNext()
                    return
                }
                val bitmap = textureView.getBitmap(ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
                if (bitmap == null) {
                    handleFailure("Preview пока недоступен для анализа")
                    return
                }
                val result = try {
                    ExposureMetricsCalculator.calculate(bitmap)
                } finally {
                    bitmap.recycle()
                }
                consecutiveFailures = 0
                if (running.get()) {
                    textureView.post {
                        if (running.get()) onAnalysis(result)
                    }
                }
                scheduleNext()
            } catch (error: Exception) {
                handleFailure(error.message ?: "Ошибка анализа preview")
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        consecutiveFailures = 0
        val analyzerThread = HandlerThread("AstroPhotoExposureAnalyzer").also {
            it.start()
        }
        thread = analyzerThread
        handler = Handler(analyzerThread.looper).also {
            it.post(analyzeRunnable)
        }
    }

    fun stop() {
        running.set(false)
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        consecutiveFailures = 0
    }

    private fun scheduleNext() {
        if (running.get()) {
            handler?.postDelayed(analyzeRunnable, ANALYSIS_INTERVAL_MS)
        }
    }

    private fun handleFailure(message: String) {
        consecutiveFailures++
        if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
            scheduleNext()
            return
        }
        running.set(false)
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        textureView.post {
            onUnavailable("Гистограмма недоступна: $message")
        }
    }

    companion object {
        private const val ANALYSIS_WIDTH = 320
        private const val ANALYSIS_HEIGHT = 180
        private const val ANALYSIS_INTERVAL_MS = 800L
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}

object ExposureMetricsCalculator {
    fun calculate(bitmap: Bitmap): ExposureAnalysis {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogramCounts = IntArray(HISTOGRAM_BINS)
        var brightnessSum = 0L
        var shadowCount = 0
        var highlightCount = 0

        pixels.forEach { color ->
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            val luminance = (red * 77 + green * 150 + blue * 29) ushr 8
            brightnessSum += luminance
            if (luminance < SHADOW_THRESHOLD) shadowCount++
            if (luminance > HIGHLIGHT_THRESHOLD) highlightCount++
            val bin = (luminance * HISTOGRAM_BINS / 256)
                .coerceIn(0, HISTOGRAM_BINS - 1)
            histogramCounts[bin]++
        }

        val pixelCount = pixels.size.coerceAtLeast(1)
        val averageBrightness = brightnessSum.toFloat() / pixelCount
        val shadowPercent = shadowCount * 100f / pixelCount
        val highlightPercent = highlightCount * 100f / pixelCount
        val maxBin = histogramCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val histogram = histogramCounts.map { it.toFloat() / maxBin }
        val status = when {
            highlightPercent > OVEREXPOSED_PERCENT -> ExposureStatus.OVEREXPOSED
            averageBrightness < TOO_DARK_BRIGHTNESS &&
                highlightPercent < 1f -> ExposureStatus.TOO_DARK
            averageBrightness > TOO_BRIGHT_BRIGHTNESS -> ExposureStatus.TOO_BRIGHT
            else -> ExposureStatus.OK
        }

        return ExposureAnalysis(
            averageBrightness = averageBrightness,
            shadowPercent = shadowPercent,
            highlightPercent = highlightPercent,
            histogram = histogram,
            status = status
        )
    }

    private const val HISTOGRAM_BINS = 32
    private const val SHADOW_THRESHOLD = 10
    private const val HIGHLIGHT_THRESHOLD = 245
    private const val TOO_DARK_BRIGHTNESS = 25f
    private const val TOO_BRIGHT_BRIGHTNESS = 200f
    private const val OVEREXPOSED_PERCENT = 5f
}
