package com.example.astrophoto.processing.jpeg.v2.artifacts

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask

data class PersistentSensorCandidateObservation(
    val feature: DetectedStar,
    val centerRed: Int,
    val centerGreen: Int,
    val centerBlue: Int,
    val backgroundRed: Int,
    val backgroundGreen: Int,
    val backgroundBlue: Int,
    val chromaExcess: Int
)

data class PersistentSensorFrameObservation(
    val frameId: String,
    val originalCaptureIndex: Int,
    val width: Int,
    val height: Int,
    val candidates: List<PersistentSensorCandidateObservation>,
    val extractionElapsedNanos: Long = 0L,
    val processedPixelCount: Long = 0L,
    val estimatedAllocatedBytes: Long = 0L
) {
    fun asArtifactFrameObservation(): ArtifactFrameObservation =
        ArtifactFrameObservation(frameId, candidates.map { it.feature })
}

/**
 * Detects compact camera-space peaks before temporal classification. It deliberately emits
 * candidates, not defects: only recurrence, camera-vs-sky support and the strict production
 * mask policy may classify a candidate as a sensor defect.
 */
class PersistentSensorCandidateDetector {
    fun detect(image: ArgbPixelImage, skyMask: SkyMask): List<DetectedStar> =
        detectCandidates(image, skyMask).map { it.feature }

    fun observe(
        frameId: String,
        originalCaptureIndex: Int,
        image: ArgbPixelImage,
        skyMask: SkyMask
    ): PersistentSensorFrameObservation {
        require(originalCaptureIndex > 0)
        val started = System.nanoTime()
        val candidates = detectCandidates(image, skyMask)
        return PersistentSensorFrameObservation(
            frameId = frameId,
            originalCaptureIndex = originalCaptureIndex,
            width = image.width,
            height = image.height,
            candidates = candidates,
            extractionElapsedNanos = System.nanoTime() - started,
            processedPixelCount = image.width.toLong() * image.height,
            estimatedAllocatedBytes =
                DETECTOR_SCRATCH_BYTES_ESTIMATE +
                    candidates.size * CANDIDATE_BYTES_ESTIMATE
        )
    }

    private fun detectCandidates(
        image: ArgbPixelImage,
        skyMask: SkyMask
    ): List<PersistentSensorCandidateObservation> {
        require(image.width == skyMask.width && image.height == skyMask.height)
        val ringColors = IntArray(RING_SAMPLE_COUNT)
        val ringLuminance = IntArray(RING_SAMPLE_COUNT)
        val red = IntArray(RING_SAMPLE_COUNT)
        val green = IntArray(RING_SAMPLE_COUNT)
        val blue = IntArray(RING_SAMPLE_COUNT)
        val candidates = mutableListOf<PersistentSensorCandidateObservation>()
        for (y in BORDER_MARGIN until image.height - BORDER_MARGIN) {
            for (x in BORDER_MARGIN until image.width - BORDER_MARGIN) {
                if (!insideStableSky(skyMask, x, y)) continue
                val color = image.pixelAt(x, y)
                if (maximumChannel(color) >= SATURATED_CHANNEL_LIMIT) continue
                val center = pixelLuminance(color)
                var strictlyAboveNeighbor = false
                var localMaximum = true
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val neighbor = pixelLuminance(image.pixelAt(x + dx, y + dy))
                    if (neighbor > center) localMaximum = false
                    if (neighbor < center) strictlyAboveNeighbor = true
                }
                if (!localMaximum || !strictlyAboveNeighbor) continue
                fillBackgroundRing(image, x, y, ringColors)
                ringColors.indices.forEach { index ->
                    val ringColor = ringColors[index]
                    ringLuminance[index] = pixelLuminance(ringColor)
                    red[index] = ringColor ushr 16 and 0xFF
                    green[index] = ringColor ushr 8 and 0xFF
                    blue[index] = ringColor and 0xFF
                }
                java.util.Arrays.sort(ringLuminance)
                java.util.Arrays.sort(red)
                java.util.Arrays.sort(green)
                java.util.Arrays.sort(blue)
                val background = ringLuminance[ringLuminance.size / 2]
                val contrast = center - background
                if (contrast < MIN_LOCAL_CONTRAST) continue
                val backgroundRange =
                    ringLuminance[ringLuminance.size - 3] - ringLuminance[2]
                if (backgroundRange > MAX_BACKGROUND_RANGE) continue
                var compactSupport = 0
                val supportThreshold = background + contrast * MIN_SUPPORT_FRACTION
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (pixelLuminance(image.pixelAt(x + dx, y + dy)) >= supportThreshold) {
                        compactSupport++
                    }
                }
                if (compactSupport > MAX_COMPACT_SUPPORT) continue
                val centerRed = color ushr 16 and 0xFF
                val centerGreen = color ushr 8 and 0xFF
                val centerBlue = color and 0xFF
                val backgroundRed = red[red.size / 2]
                val backgroundGreen = green[green.size / 2]
                val backgroundBlue = blue[blue.size / 2]
                val channelExcesses = intArrayOf(
                    centerRed - backgroundRed,
                    centerGreen - backgroundGreen,
                    centerBlue - backgroundBlue
                ).sorted()
                candidates += PersistentSensorCandidateObservation(
                    feature = DetectedStar(
                        x = x.toFloat(),
                        y = y.toFloat(),
                        flux = contrast.toFloat() * (compactSupport + 1),
                        localBackground = background.toFloat(),
                        localContrast = contrast.toFloat(),
                        width = COMPACT_CANDIDATE_WIDTH,
                        ellipticity = 0f,
                        confidence = (
                            MIN_CANDIDATE_CONFIDENCE +
                                contrast / CONFIDENCE_CONTRAST_RANGE
                            ).coerceIn(MIN_CANDIDATE_CONFIDENCE, 1f)
                    ),
                    centerRed = centerRed,
                    centerGreen = centerGreen,
                    centerBlue = centerBlue,
                    backgroundRed = backgroundRed,
                    backgroundGreen = backgroundGreen,
                    backgroundBlue = backgroundBlue,
                    chromaExcess = channelExcesses.last() - channelExcesses.first()
                )
            }
        }
        return candidates
    }

    private fun insideStableSky(mask: SkyMask, x: Int, y: Int): Boolean =
        mask.contains(x, y) &&
            mask.contains(x - SKY_MARGIN, y) &&
            mask.contains(x + SKY_MARGIN, y) &&
            mask.contains(x, y - SKY_MARGIN) &&
            mask.contains(x, y + SKY_MARGIN)

    private fun fillBackgroundRing(
        image: ArgbPixelImage,
        centerX: Int,
        centerY: Int,
        destination: IntArray
    ) {
        var index = 0
        for (offset in -RING_RADIUS..RING_RADIUS) {
            destination[index++] = image.pixelAt(centerX - RING_RADIUS, centerY + offset)
            destination[index++] = image.pixelAt(centerX + RING_RADIUS, centerY + offset)
        }
        for (offset in -(RING_RADIUS - 1)..(RING_RADIUS - 1)) {
            destination[index++] = image.pixelAt(centerX + offset, centerY - RING_RADIUS)
            destination[index++] = image.pixelAt(centerX + offset, centerY + RING_RADIUS)
        }
        check(index == destination.size)
    }

    private fun maximumChannel(color: Int): Int = maxOf(
        color ushr 16 and 0xFF,
        color ushr 8 and 0xFF,
        color and 0xFF
    )

    companion object {
        private const val RING_RADIUS = 3
        private const val RING_SAMPLE_COUNT = 24
        private const val BORDER_MARGIN = 5
        private const val SKY_MARGIN = 4
        private const val MIN_LOCAL_CONTRAST = 3
        private const val MAX_BACKGROUND_RANGE = 12
        private const val MIN_SUPPORT_FRACTION = 0.30f
        private const val MAX_COMPACT_SUPPORT = 8
        private const val SATURATED_CHANNEL_LIMIT = 250
        private const val COMPACT_CANDIDATE_WIDTH = 1f
        private const val MIN_CANDIDATE_CONFIDENCE = 0.55f
        private const val CONFIDENCE_CONTRAST_RANGE = 80f
        private const val DETECTOR_SCRATCH_BYTES_ESTIMATE = 2_048L
        private const val CANDIDATE_BYTES_ESTIMATE = 160L
    }
}
