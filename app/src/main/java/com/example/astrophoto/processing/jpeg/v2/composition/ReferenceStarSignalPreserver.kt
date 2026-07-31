package com.example.astrophoto.processing.jpeg.v2.composition

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.example.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ReferenceStarPreservationResult<T>(
    val image: T,
    val maskedReferenceSamplesSkipped: Long,
    val affectedOutputPixelCount: Int,
    val maskAware: Boolean,
    val reason: String?
)

class ReferenceStarSignalPreserver {
    fun preserve(
        stackedSky: ArgbPixelImage,
        reference: ArgbPixelImage,
        stars: List<DetectedStar>,
        sensorDefectMask: SensorDefectMask? = null,
        sensorDefectAffectedOutput: AlphaPixelSource? = null
    ): ReferenceStarPreservationResult<ArgbPixelImage> {
        requireCompatible(
            stackedSky.width,
            stackedSky.height,
            reference.width,
            reference.height,
            sensorDefectMask,
            sensorDefectAffectedOutput
        )
        val output = stackedSky.pixels.copyOf()
        val diagnostics = copyReferenceStars(
            stackedSky.width,
            stackedSky.height,
            stars,
            sensorDefectMask,
            sensorDefectAffectedOutput
        ) { x, y ->
            output[y * stackedSky.width + x] = reference.pixels[y * reference.width + x]
        }
        return diagnostics.withImage(
            ArgbPixelImage(stackedSky.width, stackedSky.height, output)
        )
    }

    fun preserve(
        stackedSky: FileBackedImage,
        reference: FileBackedImage,
        stars: List<DetectedStar>,
        store: ResultCandidateStore,
        sensorDefectMask: SensorDefectMask? = null,
        sensorDefectAffectedOutput: AlphaPixelSource? = null
    ): ReferenceStarPreservationResult<FileBackedImage> {
        requireCompatible(
            stackedSky.width,
            stackedSky.height,
            reference.width,
            reference.height,
            sensorDefectMask,
            sensorDefectAffectedOutput
        )
        val writer = store.createTemporaryWriter(
            "star-preserved-stack",
            stackedSky.width,
            stackedSky.height
        )
        val stackedRow = IntArray(stackedSky.width)
        val referenceRow = IntArray(stackedSky.width)
        var skipped = 0L
        val affected = hashSetOf<Int>()
        try {
            FileBackedImageReader(stackedSky).use { stackedReader ->
                FileBackedImageReader(reference).use { referenceReader ->
                    for (y in 0 until stackedSky.height) {
                        stackedReader.readArgbRow(y, stackedRow)
                        referenceReader.readArgbRow(y, referenceRow)
                        forEachStarPixelInRow(y, stackedSky.width, stars) { starIndex, x ->
                            if (
                                isReferenceSampleBlocked(
                                    x,
                                    y,
                                    stars[starIndex],
                                    sensorDefectMask,
                                    sensorDefectAffectedOutput
                                )
                            ) {
                                skipped++
                                affected += y * stackedSky.width + x
                            } else {
                                stackedRow[x] = referenceRow[x]
                            }
                        }
                        writer.writeRow(y, stackedRow)
                    }
                }
            }
            return ReferenceStarPreservationResult(
                image = writer.finish(),
                maskedReferenceSamplesSkipped = skipped,
                affectedOutputPixelCount = affected.size,
                maskAware = sensorDefectMask != null,
                reason = skipped.reason()
            )
        } catch (error: Throwable) {
            runCatching { writer.close() }
            throw error
        }
    }

    private fun copyReferenceStars(
        width: Int,
        height: Int,
        stars: List<DetectedStar>,
        sensorDefectMask: SensorDefectMask?,
        sensorDefectAffectedOutput: AlphaPixelSource?,
        copyPixel: (Int, Int) -> Unit
    ): PreservationDiagnostics {
        var skipped = 0L
        val affected = hashSetOf<Int>()
        for (y in 0 until height) {
            forEachStarPixelInRow(y, width, stars) { starIndex, x ->
                if (
                    isReferenceSampleBlocked(
                        x,
                        y,
                        stars[starIndex],
                        sensorDefectMask,
                        sensorDefectAffectedOutput
                    )
                ) {
                    skipped++
                    affected += y * width + x
                } else {
                    copyPixel(x, y)
                }
            }
        }
        return PreservationDiagnostics(
            skipped,
            affected.size,
            sensorDefectMask != null,
            skipped.reason()
        )
    }

    private fun isReferenceSampleBlocked(
        x: Int,
        y: Int,
        star: DetectedStar,
        sensorDefectMask: SensorDefectMask?,
        sensorDefectAffectedOutput: AlphaPixelSource?
    ): Boolean {
        if (sensorDefectMask?.contains(x, y) == true) return true
        if (!affectedOutputIntersects(x, y, sensorDefectAffectedOutput)) return false
        val centerX = star.x.roundToInt()
        val centerY = star.y.roundToInt()
        val coreRadius = ceil(star.width * 1.8f).toInt().coerceIn(3, 7)
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy > coreRadius * coreRadius
    }

    private fun affectedOutputIntersects(
        x: Int,
        y: Int,
        sensorDefectAffectedOutput: AlphaPixelSource?
    ): Boolean {
        if (sensorDefectAffectedOutput == null) return false
        val left = (x - FILTERED_SAMPLE_SUPPORT_RADIUS).coerceAtLeast(0)
        val right = (x + FILTERED_SAMPLE_SUPPORT_RADIUS)
            .coerceAtMost(sensorDefectAffectedOutput.width - 1)
        val top = (y - FILTERED_SAMPLE_SUPPORT_RADIUS).coerceAtLeast(0)
        val bottom = (y + FILTERED_SAMPLE_SUPPORT_RADIUS)
            .coerceAtMost(sensorDefectAffectedOutput.height - 1)
        for (sampleY in top..bottom) for (sampleX in left..right) {
            if (sensorDefectAffectedOutput.alphaAt(sampleX, sampleY) > 0f) return true
        }
        return false
    }

    private fun forEachStarPixelInRow(
        y: Int,
        width: Int,
        stars: List<DetectedStar>,
        action: (Int, Int) -> Unit
    ) {
        stars.forEachIndexed { starIndex, star ->
            val coreRadius = ceil(star.width * 1.8f).toInt().coerceIn(3, 7)
            val radius = coreRadius + 3
            val dy = y - star.y
            if (abs(dy) > radius) return@forEachIndexed
            val centerX = star.x.roundToInt()
            val span = sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
                .roundToInt()
            val left = (centerX - span).coerceAtLeast(0)
            val right = (centerX + span).coerceAtMost(width - 1)
            for (x in left..right) action(starIndex, x)
        }
    }

    private fun requireCompatible(
        stackedWidth: Int,
        stackedHeight: Int,
        referenceWidth: Int,
        referenceHeight: Int,
        sensorDefectMask: SensorDefectMask?,
        sensorDefectAffectedOutput: AlphaPixelSource?
    ) {
        require(stackedWidth == referenceWidth && stackedHeight == referenceHeight)
        require(
            sensorDefectMask == null ||
                (
                    sensorDefectMask.width == referenceWidth &&
                        sensorDefectMask.height == referenceHeight
                )
        )
        require(
            sensorDefectAffectedOutput == null ||
                (
                    sensorDefectAffectedOutput.width == referenceWidth &&
                        sensorDefectAffectedOutput.height == referenceHeight
                    )
        )
    }

    private fun Long.reason(): String? = if (this > 0L) {
        "confirmed_sensor_defect_or_filtered_sample_support_blocks_reference_sample"
    } else {
        null
    }

    private data class PreservationDiagnostics(
        val skipped: Long,
        val affected: Int,
        val maskAware: Boolean,
        val reason: String?
    ) {
        fun <T> withImage(image: T) = ReferenceStarPreservationResult(
            image,
            skipped,
            affected,
            maskAware,
            reason
        )
    }

    private companion object {
        const val FILTERED_SAMPLE_SUPPORT_RADIUS = 3
    }
}
