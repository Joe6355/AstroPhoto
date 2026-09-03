package com.joe6355.astrophoto

/** ETA is derived only from actual pipeline callbacks, never from a second progress timer. */
internal class ProcessingEtaEstimator {
    private var phase = ""
    private var total = 0
    private var startedAt = 0L
    private var firstCompleted = 0
    private var lastCompleted = 0

    fun update(message: String, completed: Int, total: Int, nowMillis: Long): Long? {
        val key = message.replace(Regex("\\d+(?:[.,]\\d+)?"), "#")
        val current = completed.coerceIn(0, total.coerceAtLeast(0))
        if (key != phase || this.total != total || current < lastCompleted || nowMillis < startedAt) {
            phase = key
            this.total = total
            startedAt = nowMillis
            firstCompleted = current
            lastCompleted = current
            return null
        }
        lastCompleted = current
        val processed = current - firstCompleted
        val elapsed = nowMillis - startedAt
        if (total <= 1 || current >= total || processed < 2 || elapsed < 1_000L) return null
        val estimate = elapsed.toDouble() / processed * (total - current)
        return estimate.takeIf { it.isFinite() && it >= 0.0 && it <= 86_400_000.0 }?.toLong()
    }
}
