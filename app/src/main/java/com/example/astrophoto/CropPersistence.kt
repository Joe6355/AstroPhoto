package com.example.astrophoto

internal fun <Stored> persistCropAfterVerifiedWrite(
    writeImage: () -> Stored,
    updateManifest: (Stored) -> Unit,
    commitImage: (Stored) -> Unit,
    rollbackImage: (Stored) -> Unit
): Stored {
    val stored = writeImage()
    try {
        updateManifest(stored)
    } catch (error: Throwable) {
        runCatching { rollbackImage(stored) }
            .onFailure(error::addSuppressed)
        throw error
    }
    commitImage(stored)
    return stored
}

internal suspend fun processCropBatch(
    frames: List<SessionFrame>,
    cropOriginal: suspend (SessionFrame) -> Unit
): CropBatchResult {
    var processed = 0
    var skipped = 0
    val failures = mutableListOf<CropBatchFailure>()
    frames.forEach { frame ->
        if (frame.category != SessionFrameCategory.LIGHTS_JPEG) {
            skipped++
        } else {
            runCatching { cropOriginal(frame) }
                .onSuccess { processed++ }
                .onFailure { error ->
                    failures += CropBatchFailure(
                        frame.fileName,
                        error.message ?: "Неизвестная ошибка"
                    )
                }
        }
    }
    return CropBatchResult(processed, skipped, failures.size, failures)
}
