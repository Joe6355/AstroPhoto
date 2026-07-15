package com.example.astrophoto.processing.jpeg.v2.diagnostics

class PipelineTimingCollector {
    private val startedNanos = System.nanoTime()
    private val durations = linkedMapOf<String, Long>()

    fun record(stage: String, durationMillis: Long) {
        durations[stage] = durationMillis.coerceAtLeast(0L)
    }

    fun recordAll(values: Map<String, Long>) {
        values.forEach(::record)
    }

    fun elapsedMillis(): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    fun snapshot(includeTotal: Boolean = true): Map<String, Long> =
        LinkedHashMap(durations).apply {
            if (includeTotal) put("total", elapsedMillis())
        }
}
