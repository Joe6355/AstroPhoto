package com.joe6355.astrophoto

/** ETA is derived only from actual pipeline callbacks, never from a second progress timer. */
internal class ProcessingEtaEstimator {
    private data class Sample(val completed: Int, val atMillis: Long)

    private var phase = ""
    private var total = 0
    private var lastCompleted = 0
    private var lastObservedAt = 0L
    private var lastProgressAt = 0L
    private val samples = ArrayDeque<Sample>()

    fun update(message: String, completed: Int, total: Int, nowMillis: Long): Long? {
        if (nowMillis < 0) {
            samples.clear()
            return null
        }
        val key = message.replace(COUNTER_PATTERN, "#")
        val current = completed.coerceIn(0, total.coerceAtLeast(0))
        if (samples.isEmpty() || key != phase || this.total != total ||
            current < lastCompleted || nowMillis < lastObservedAt) {
            phase = key
            this.total = total
            lastCompleted = current
            lastObservedAt = nowMillis
            lastProgressAt = nowMillis
            samples.clear()
            samples.addLast(Sample(current, nowMillis))
            return null
        }
        lastObservedAt = nowMillis
        if (current > lastCompleted) {
            lastCompleted = current
            lastProgressAt = nowMillis
            // Coalesce dense tile callbacks, keeping a bounded time window rather than
            // letting the most recent few milliseconds replace a useful measurement.
            if (nowMillis - samples.last().atMillis >= SAMPLE_INTERVAL_MILLIS) {
                samples.addLast(Sample(current, nowMillis))
            }
            // Retain at least two intervals when an individual unit is slower than the window.
            while (samples.size > 3 && samples.first().atMillis < nowMillis - WINDOW_MILLIS) {
                samples.removeFirst()
            }
        }
        val first = samples.first()
        val processed = current - first.completed
        val elapsed = lastProgressAt - first.atMillis
        if (total <= 1 || current >= total || processed < 2 || elapsed < 1_000L) return null
        val unitMillis = elapsed.toDouble() / processed
        val sinceProgress = nowMillis - lastProgressAt
        if (sinceProgress > maxOf(WINDOW_MILLIS.toDouble(), unitMillis * 2)) return null
        val estimate = unitMillis * (total - current) - sinceProgress
        return estimate.takeIf { it.isFinite() && it >= 1.0 && it <= 86_400_000.0 }?.toLong()
    }

    private companion object {
        val COUNTER_PATTERN = Regex("\\d+(?:[.,]\\d+)?")
        const val WINDOW_MILLIS = 4_000L
        const val SAMPLE_INTERVAL_MILLIS = 250L
    }
}
