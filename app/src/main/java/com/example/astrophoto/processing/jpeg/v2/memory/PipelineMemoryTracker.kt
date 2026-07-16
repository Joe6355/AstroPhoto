package com.example.astrophoto.processing.jpeg.v2.memory

class PipelineMemoryTracker(
    private val heapUsedBytes: () -> Long = {
        Runtime.getRuntime().let { runtime ->
            (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        }
    },
    private val eventLogger: (String) -> Unit = {}
) {
    var peakEstimatedResidentBytes: Long = 0L
        private set
    var peakObservedHeapBytes: Long = 0L
        private set
    var maximumSimultaneousFullResolutionHeapImages: Int = 0
        private set
    var memoryPressureRetries: Int = 0
        private set
    private val stageTiles = linkedMapOf<String, String>()
    private val stageHalos = linkedMapOf<String, Int>()

    fun recordBoundary(stage: String, estimatedResidentBytes: Long, fullResolutionHeapImages: Int) {
        require(stage.isNotBlank() && estimatedResidentBytes >= 0L && fullResolutionHeapImages >= 0)
        val observedHeapBytes = heapUsedBytes().coerceAtLeast(0L)
        peakEstimatedResidentBytes = maxOf(peakEstimatedResidentBytes, estimatedResidentBytes)
        peakObservedHeapBytes = maxOf(peakObservedHeapBytes, observedHeapBytes)
        maximumSimultaneousFullResolutionHeapImages = maxOf(
            maximumSimultaneousFullResolutionHeapImages,
            fullResolutionHeapImages
        )
        eventLogger(
            "event=allocation_boundary stage=$stage estimatedResidentBytes=$estimatedResidentBytes " +
                "observedHeapBytes=$observedHeapBytes fullResolutionHeapImages=$fullResolutionHeapImages"
        )
    }

    fun recordTile(stage: String, width: Int, height: Int, halo: Int = 0) {
        require(stage.isNotBlank() && width > 0 && height > 0 && halo >= 0)
        stageTiles[stage] = "${width}x$height"
        stageHalos[stage] = halo
        eventLogger("event=tile_selected stage=$stage tile=${width}x$height halo=$halo")
    }

    fun recordRetry() {
        memoryPressureRetries++
        eventLogger("event=memory_pressure_retry retry=$memoryPressureRetries")
    }

    fun tileSizes(): Map<String, String> = stageTiles.toMap()
    fun haloSizes(): Map<String, Int> = stageHalos.toMap()
}
