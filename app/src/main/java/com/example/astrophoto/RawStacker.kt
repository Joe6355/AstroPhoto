package com.example.astrophoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class RawStacker(private val context: Context) {
    suspend fun stack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit
    ): Result<JpegStackResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(frames.size >= 2) { "Для RAW-стекинга нужно минимум два кадра" }
            require(frames.all { it.category == SessionFrameCategory.LIGHTS_RAW }) {
                "RAW-стекинг поддерживает только Lights/RAW"
            }
            val sidecars = AstroRawSidecarStore(context)
            reportProgress(onProgress, "Чтение RAW 1 из ${frames.size}", 1, frames.size)
            val firstDevelopment = readRawFrame(sidecars, session, frames.first()).let { raw ->
                val targetPixels = rawLinearTargetPixels(raw.metadata.pixelCount)
                RawDevelopment(
                    metadata = raw.metadata,
                    image = developAstroRaw(raw, targetPixels),
                    targetPixels = targetPixels
                )
            }
            val firstMetadata = firstDevelopment.metadata
            val targetPixels = firstDevelopment.targetPixels
            var average = firstDevelopment.image
            val shifts = mutableListOf(SubpixelShift.Zero)
            var stackedFrameCount = 1
            var rejectedFrameCount = 0

            frames.drop(1).forEachIndexed { index, frame ->
                currentCoroutineContext().ensureActive()
                val frameNumber = index + 2
                reportProgress(
                    onProgress,
                    "Линейная обработка RAW $frameNumber из ${frames.size}",
                    frameNumber,
                    frames.size
                )
                val candidate = readRawFrame(sidecars, session, frame).let { raw ->
                    requireCompatibleRaw(firstMetadata, raw.metadata, frame.fileName)
                    developAstroRaw(raw, targetPixels)
                }
                require(
                    candidate.width == average.width && candidate.height == average.height
                ) { "RAW-кадры получили разные размеры после проявки" }
                val shift = findSubpixelAlignment(average, candidate, MAX_RAW_ALIGNMENT_SHIFT)
                if (!shift.isReliable) {
                    rejectedFrameCount++
                    return@forEachIndexed
                }
                shifts += shift
                stackedFrameCount++
                addShiftedLinearAverageInPlace(average, candidate, stackedFrameCount, shift)
            }

            currentCoroutineContext().ensureActive()
            require(stackedFrameCount >= 2) {
                "Не удалось надёжно совместить хотя бы два RAW-кадра"
            }
            average = cropLinearToCommonRegion(average, shifts)
            reportProgress(onProgress, "Растяжка и сохранение RAW-стека", frames.size, frames.size)
            val displayImage = toneMapLinearToArgb(average)
            val sourceBitmap = Bitmap.createBitmap(
                displayImage.width,
                displayImage.height,
                Bitmap.Config.ARGB_8888
            ).apply {
                setPixels(
                    displayImage.pixels,
                    0,
                    displayImage.width,
                    0,
                    0,
                    displayImage.width,
                    displayImage.height
                )
            }
            val orientedBitmap = orientBitmap(sourceBitmap, firstMetadata.sensorOrientation)
            val stacker = JpegStacker(context)
            val processedAt = System.currentTimeMillis()
            val saved = try {
                stacker.saveBitmap(
                    session = session,
                    bitmap = orientedBitmap,
                    fileName = buildProcessedResultBaseName(
                        ProcessedOutputType.RAW_LINEAR,
                        processedAt
                    )
                )
            } finally {
                if (orientedBitmap !== sourceBitmap) orientedBitmap.recycle()
                sourceBitmap.recycle()
            }
            val alignedCount = shifts.count { !it.isZero }
            val downscaled = average.width < firstMetadata.width ||
                average.height < firstMetadata.height
            val infoUpdated = runCatching {
                stacker.appendRawStackSessionInfo(
                    session = session,
                    fileName = saved.fileName,
                    frameCount = stackedFrameCount,
                    rejectedFrameCount = rejectedFrameCount,
                    alignedFrameCount = alignedCount,
                    downscaled = downscaled,
                    processedAtMillis = processedAt
                )
            }.isSuccess
            val warnings = buildList {
                if (downscaled) {
                    add("RAW уменьшен для безопасного расхода памяти")
                }
                if (rejectedFrameCount > 0) {
                    add("Пропущено RAW-кадров без надёжного совпадения: $rejectedFrameCount")
                }
                if (!infoUpdated) {
                    add("Результат сохранён, но session_info.txt не обновлён")
                }
            }
            JpegStackResult(
                fileName = saved.fileName,
                displayPath = saved.displayPath,
                contentUri = saved.contentUri,
                filePath = saved.filePath,
                frameCount = stackedFrameCount,
                sessionInfoUpdated = infoUpdated,
                alignmentEnabled = true,
                astroStretchApplied = true,
                downscaled = downscaled,
                warnings = warnings
            )
        }
    }

    private fun readRawFrame(
        store: AstroRawSidecarStore,
        session: SessionSummary,
        frame: SessionFrame
    ): AstroRawFrame {
        val input = store.openForFrame(session, frame)
            ?: error(
                "Для ${frame.fileName} нет RAW-sidecar. " +
                    "Обработка доступна для RAW, снятых после обновления приложения"
            )
        return input.use(::readAstroRaw)
    }

    private suspend fun reportProgress(
        callback: suspend (String, Int, Int) -> Unit,
        message: String,
        current: Int,
        total: Int
    ) {
        withContext(Dispatchers.Main.immediate) { callback(message, current, total) }
    }
}

internal fun requireCompatibleRaw(
    reference: AstroRawMetadata,
    candidate: AstroRawMetadata,
    fileName: String
) {
    require(
        candidate.width == reference.width &&
            candidate.height == reference.height &&
            candidate.cfaArrangement == reference.cfaArrangement &&
            candidate.whiteLevel == reference.whiteLevel &&
            candidate.sampleShift == reference.sampleShift
    ) { "RAW-кадр $fileName несовместим с первым кадром серии" }
    require(
        candidate.exposureTimeNs == reference.exposureTimeNs &&
            candidate.sensitivityIso == reference.sensitivityIso
    ) { "У RAW-кадра $fileName отличаются выдержка или ISO" }
}

private fun rawLinearTargetPixels(rawPixelCount: Int): Long {
    val heapBytes = Runtime.getRuntime().maxMemory()
    val rawBytes = rawPixelCount.toLong() * 2L
    val availableForLinearFrames = (heapBytes * 55L / 100L - rawBytes).coerceAtLeast(
        MIN_RAW_LINEAR_PIXELS * LINEAR_PAIR_BYTES_PER_PIXEL
    )
    return (availableForLinearFrames / LINEAR_PAIR_BYTES_PER_PIXEL)
        .coerceIn(MIN_RAW_LINEAR_PIXELS, MAX_RAW_LINEAR_PIXELS)
}

private fun orientBitmap(source: Bitmap, orientationDegrees: Int): Bitmap {
    val normalized = ((orientationDegrees % 360) + 360) % 360
    if (normalized == 0) return source
    require(normalized == 90 || normalized == 180 || normalized == 270) {
        "Unsupported RAW orientation"
    }
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
}

private const val MAX_RAW_ALIGNMENT_SHIFT = 32
private const val LINEAR_PAIR_BYTES_PER_PIXEL = 24L
private const val MIN_RAW_LINEAR_PIXELS = 250_000L
private const val MAX_RAW_LINEAR_PIXELS = 4_000_000L

private data class RawDevelopment(
    val metadata: AstroRawMetadata,
    val image: LinearRgbImage,
    val targetPixels: Long
)
