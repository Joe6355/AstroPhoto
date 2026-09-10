package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.MutableSampledSrgb
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class TiledManualFrame(
    val image: FileBackedImage,
    val shift: AlignmentShift,
    val originalFrameIndex: Int
)

/** Only rows/tiles are resident. Frame rejection, shift decisions and sensor masks belong to the existing planner. */
internal object TiledManualStacking {
    suspend fun resize(
        source: FileBackedImage, width: Int, height: Int, store: ResultCandidateStore
    ): FileBackedImage {
        if (source.width == width && source.height == height) return source
        val sampler = TransformedBitmapSampler()
        val sample = MutableSampledSrgb()
        val row = LongArray(width)
        store.createTemporaryWriter("manual-resized", width, height).use { writer ->
            FileBackedImageReader(source, cachedRows = 2).use { input ->
                for (y in 0 until height) {
                    currentCoroutineContext().ensureActive()
                    val sy = ((y + 0.5f) * source.height / height - 0.5f).coerceIn(0f, source.height - 1f)
                    for (x in 0 until width) {
                        val sx = ((x + 0.5f) * source.width / width - 0.5f).coerceIn(0f, source.width - 1f)
                        check(sampler.sampleAt(input, sx, sy, sample))
                        row[x] = LinearRgb16.pack(SrgbTransfer.srgbToLinear(sample.red),
                            SrgbTransfer.srgbToLinear(sample.green), SrgbTransfer.srgbToLinear(sample.blue))
                    }
                    writer.writeLinearRow(y, row)
                }
            }
            return writer.finish()
        }
    }

    suspend fun integrate(
        frames: List<TiledManualFrame>,
        mode: ManualAlignedStackMode,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        coverage: ManualSensorDefectCoveragePlan? = null,
        referenceOriginalFrameIndex: Int? = null,
        sigma: Double = 2.0,
        masterDark: FileBackedImage? = null,
        darkCrop: PixelRect? = null,
        shadowOffset: Int = 0,
        onProgress: suspend (Int, Int) -> Unit
    ): FileBackedImage {
        require(frames.isNotEmpty())
        val width = frames.first().image.width
        val height = frames.first().image.height
        require(frames.all { it.image.width == width && it.image.height == height })
        val region = commonAlignedRegion(width, height, frames.map { it.shift })
        val robust = mode == ManualAlignedStackMode.MEDIAN || mode == ManualAlignedStackMode.SIGMA
        require(!robust || frames.size <= 30)
        require((masterDark != null) == (mode == ManualAlignedStackMode.DARK_SUBTRACTED_AVERAGE))
        require(darkCrop == null || masterDark != null)
        if (masterDark != null) {
            require((darkCrop?.width ?: masterDark.width) == width && (darkCrop?.height ?: masterDark.height) == height)
            require(darkCrop == null || (darkCrop.left >= 0 && darkCrop.top >= 0 &&
                darkCrop.right <= masterDark.width && darkCrop.bottom <= masterDark.height))
        }
        val tile = budget.chooseTile(region.width, region.height, region.width, 32,
            argbBuffers = if (robust) frames.size + 5 else 11, floatBuffers = 0,
            residentBytes = maxOf(width, masterDark?.width ?: 0) * 160L + frames.size * 64L + 256L * 1024)
        require(tile.accepted) { "Недостаточно безопасной памяти для тайлового стека" }
        val reference = frames.firstOrNull { it.originalFrameIndex == referenceOriginalFrameIndex }
        require(coverage?.report?.sampleLevelFilteringApplied != true || reference != null) {
            "Sensor-mask fallback requires the accepted reference frame"
        }
        val fallbackFrame = reference ?: frames.first()
        store.createTemporaryWriter("manual-integrated", region.width, region.height).use { writer ->
            masterDark?.let { FileBackedImageReader(it, cachedRows = 2) }.use { dark ->
                FileBackedImageReader(fallbackFrame.image, cachedRows = 2).use { fallback ->
                    for (top in 0 until region.height step tile.tileHeight) {
                        for (left in 0 until region.width step tile.tileWidth) {
                            currentCoroutineContext().ensureActive()
                            val tw = minOf(tile.tileWidth, region.width - left)
                            val th = minOf(tile.tileHeight, region.height - top)
                            val count = tw * th
                            val allSamples = if (robust) Array(frames.size) { IntArray(count) } else null
                            val redSum = if (!robust) DoubleArray(count) else null
                            val greenSum = if (!robust) DoubleArray(count) else null
                            val blueSum = if (!robust) DoubleArray(count) else null
                            val validCounts = IntArray(count)
                            val scratch = IntArray(count)
                            frames.forEachIndexed { frameIndex, frame ->
                                currentCoroutineContext().ensureActive()
                                val pixels = allSamples?.get(frameIndex) ?: scratch
                                val sourceLeft = region.left + left + frame.shift.dx
                                val sourceTop = region.top + top + frame.shift.dy
                                FileBackedImageReader(frame.image, cachedRows = 1).use { input ->
                                    input.readTile(sourceLeft, sourceTop, tw, th, pixels)
                                }
                                if (!robust) for (y in 0 until th) for (x in 0 until tw) {
                                    val ox = region.left + left + x
                                    val oy = region.top + top + y
                                    if (coverage?.sourceSampleIsValid(ox, oy, frame.shift, width, height) == false) continue
                                    val index = y * tw + x
                                    val color = pixels[index]
                                    var r = (color ushr 16 and 255).toFloat()
                                    var g = (color ushr 8 and 255).toFloat()
                                    var b = (color and 255).toFloat()
                                    if (dark != null) {
                                        // Calibrate in sensor coordinates before applying the registration shift.
                                        val darkColor = dark.linearRgbAt(sourceLeft + x + (darkCrop?.left ?: 0),
                                            sourceTop + y + (darkCrop?.top ?: 0))
                                        r = subtractDarkChannelPrecise(r, SrgbTransfer.linearToSrgb(LinearRgb16.red(darkColor)) * 255f, shadowOffset)
                                        g = subtractDarkChannelPrecise(g, SrgbTransfer.linearToSrgb(LinearRgb16.green(darkColor)) * 255f, shadowOffset)
                                        b = subtractDarkChannelPrecise(b, SrgbTransfer.linearToSrgb(LinearRgb16.blue(darkColor)) * 255f, shadowOffset)
                                    }
                                    checkNotNull(redSum)[index] += r.toDouble()
                                    checkNotNull(greenSum)[index] += g.toDouble()
                                    checkNotNull(blueSum)[index] += b.toDouble()
                                    validCounts[index]++
                                }
                            }
                            val output = LongArray(count)
                            val samples = IntArray(frames.size)
                            val channels = Array(3) { IntArray(frames.size) }
                            for (y in 0 until th) {
                                currentCoroutineContext().ensureActive()
                                for (x in 0 until tw) {
                                    val index = y * tw + x
                                    val ox = region.left + left + x
                                    val oy = region.top + top + y
                                    val valid = if (allSamples != null) {
                                        var used = 0
                                        frames.forEachIndexed { frameIndex, frame ->
                                            if (coverage?.sourceSampleIsValid(ox, oy, frame.shift, width, height) != false) {
                                                samples[used++] = allSamples[frameIndex][index]
                                            }
                                        }
                                        used
                                    } else validCounts[index]
                                    output[index] = when {
                                        valid == 0 || (coverage?.report?.sampleLevelFilteringApplied == true && valid < mode.minimumFrameCount) -> {
                                            val sx = ox + fallbackFrame.shift.dx
                                            val sy = oy + fallbackFrame.shift.dy
                                            val color = fallback.linearRgbAt(sx, sy)
                                            if (dark == null) color else subtractMasterDarkLinearRgb(
                                                color, dark.linearRgbAt(sx + (darkCrop?.left ?: 0), sy + (darkCrop?.top ?: 0)), shadowOffset
                                            )
                                        }
                                        mode == ManualAlignedStackMode.MEDIAN -> medianLinearRgbPixel(samples, channels, valid)
                                        mode == ManualAlignedStackMode.SIGMA -> sigmaClipLinearRgbPixel(samples, channels, valid, sigma)
                                        else -> LinearRgb16.pack(
                                            SrgbTransfer.srgbToLinear((checkNotNull(redSum)[index] / valid / 255.0).toFloat()),
                                            SrgbTransfer.srgbToLinear((checkNotNull(greenSum)[index] / valid / 255.0).toFloat()),
                                            SrgbTransfer.srgbToLinear((checkNotNull(blueSum)[index] / valid / 255.0).toFloat())
                                        )
                                    }
                                }
                            }
                            writer.writeLinearTile(left, top, tw, th, output)
                        }
                        onProgress(minOf(top + tile.tileHeight, region.height), region.height)
                    }
                }
            }
            return writer.finish()
        }
    }

    suspend fun stretch(input: FileBackedImage, store: ResultCandidateStore): FileBackedImage {
        val histogram = IntArray(256)
        val row = LongArray(input.width)
        FileBackedImageReader(input, cachedRows = 1).use { reader ->
            for (y in 0 until input.height) {
                currentCoroutineContext().ensureActive()
                reader.readLinearRow(y, row)
                for (color in row) histogram[pixelLuminance(LinearRgb16.toArgb(color))]++
            }
            val parameters = manualAstroStretchParameters(histogram, input.width.toLong() * input.height)
            store.createTemporaryWriter("manual-stretched", input.width, input.height).use { writer ->
                for (y in 0 until input.height) {
                    currentCoroutineContext().ensureActive()
                    reader.readLinearRow(y, row)
                    for (x in row.indices) row[x] = stretchLinearRgbColor(row[x], parameters)
                    writer.writeLinearRow(y, row)
                }
                return writer.finish()
            }
        }
    }
}
