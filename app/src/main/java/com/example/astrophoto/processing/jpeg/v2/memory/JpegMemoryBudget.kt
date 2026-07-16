package com.example.astrophoto.processing.jpeg.v2.memory

import kotlin.math.max

data class RuntimeHeapSnapshot(
    val maxHeapBytes: Long,
    val totalHeapBytes: Long,
    val freeHeapBytes: Long
) {
    init {
        require(maxHeapBytes > 0L)
        require(totalHeapBytes >= 0L && freeHeapBytes >= 0L && freeHeapBytes <= totalHeapBytes)
    }

    val usedHeapBytes: Long get() = (totalHeapBytes - freeHeapBytes).coerceAtLeast(0L)
    val availableHeapBytes: Long get() = (maxHeapBytes - usedHeapBytes).coerceAtLeast(0L)
}

class JpegMemoryBudget(
    val snapshot: RuntimeHeapSnapshot,
    reserveBytes: Long = max(MIN_FRAMEWORK_RESERVE_BYTES, snapshot.maxHeapBytes / 4L),
    private val workingFraction: Double = SAFE_WORKING_FRACTION
) {
    val frameworkReserveBytes: Long = reserveBytes.coerceAtLeast(MIN_FRAMEWORK_RESERVE_BYTES)
    val safeWorkingBudgetBytes: Long = minOf(
        (snapshot.availableHeapBytes * workingFraction).toLong(),
        (snapshot.availableHeapBytes - frameworkReserveBytes).coerceAtLeast(0L)
    ).coerceAtLeast(0L)

    init {
        require(workingFraction in 0.10..0.90)
    }

    fun permits(estimate: ImageAllocationEstimate): Boolean = estimate.bytes <= safeWorkingBudgetBytes

    fun requireAllocation(estimate: ImageAllocationEstimate) {
        require(permits(estimate)) {
            "Unsafe JPEG allocation ${estimate.label}: ${estimate.bytes} bytes, safe budget $safeWorkingBudgetBytes bytes"
        }
    }

    fun chooseTile(
        outputWidth: Int,
        outputHeight: Int,
        preferredTileWidth: Int,
        preferredTileHeight: Int,
        minimumTileSize: Int = MIN_TILE_SIZE,
        halo: Int = 0,
        argbBuffers: Int,
        floatBuffers: Int,
        maskBuffers: Int = 0
    ): MemoryPressureDecision {
        require(outputWidth > 0 && outputHeight > 0 && minimumTileSize > 0)
        var width = preferredTileWidth.coerceIn(1, outputWidth)
        var height = preferredTileHeight.coerceIn(1, outputHeight)
        var reduced = false
        while (true) {
            val estimate = ImageAllocationEstimate.tile(
                width,
                height,
                argbBuffers,
                floatBuffers,
                maskBuffers,
                halo,
                "jpeg-tile"
            )
            if (permits(estimate)) {
                return MemoryPressureDecision(
                    accepted = true,
                    tileWidth = width,
                    tileHeight = height,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    estimatedBytes = estimate.bytes,
                    retryRequired = reduced,
                    reason = if (reduced) "tile_reduced_for_memory" else null
                )
            }
            if (width <= minimumTileSize && height <= minimumTileSize) {
                return MemoryPressureDecision(
                    accepted = false,
                    tileWidth = width,
                    tileHeight = height,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    estimatedBytes = estimate.bytes,
                    retryRequired = reduced,
                    reason = "minimum_tile_exceeds_safe_budget"
                )
            }
            if (width >= height && width > minimumTileSize) {
                width = max(minimumTileSize, (width + 1) / 2)
            } else {
                height = max(minimumTileSize, (height + 1) / 2)
            }
            reduced = true
        }
    }

    companion object {
        const val MEMORY_SCHEMA_VERSION = "astrophoto.jpeg.memory/1"
        const val MIN_FRAMEWORK_RESERVE_BYTES = 32L * 1024L * 1024L
        const val MIN_TILE_SIZE = 16
        private const val SAFE_WORKING_FRACTION = 0.60

        fun current(runtime: Runtime = Runtime.getRuntime()): JpegMemoryBudget = JpegMemoryBudget(
            RuntimeHeapSnapshot(
                maxHeapBytes = runtime.maxMemory(),
                totalHeapBytes = runtime.totalMemory(),
                freeHeapBytes = runtime.freeMemory()
            )
        )
    }
}
