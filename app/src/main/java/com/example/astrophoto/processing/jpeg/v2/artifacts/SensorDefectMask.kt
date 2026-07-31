package com.example.astrophoto.processing.jpeg.v2.artifacts

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class PersistentArtifactClassification {
    SENSOR_DEFECT
}

data class SensorDefectFootprintPixel(
    val x: Int,
    val y: Int
)

data class SensorDefectRegion(
    val stableRegionId: String,
    val sourceX: Float,
    val sourceY: Float,
    val sourceRadiusX: Float,
    val sourceRadiusY: Float,
    val footprintPixels: List<SensorDefectFootprintPixel>,
    val recurrence: Int,
    val totalFrameCount: Int,
    val skySpaceSupport: Int,
    val confidence: Float,
    val classification: PersistentArtifactClassification,
    val classificationReason: String
)

data class SensorDefectMaskPolicy(
    val minimumConfidence: Float = 0.95f,
    val minimumRecurrenceRatio: Float = 0.90f,
    val maximumRegionFootprintPixels: Int = 64,
    val maximumMaskedSourceFraction: Float = 0.001f,
    val minimumCameraToSkySupportRatio: Float = 2.5f
) {
    init {
        require(minimumConfidence in 0f..1f)
        require(minimumRecurrenceRatio in TemporalPixelConsistency.MIN_PRESENCE_RATIO..1f)
        require(maximumRegionFootprintPixels > 0)
        require(maximumMaskedSourceFraction in 0f..1f)
        require(minimumCameraToSkySupportRatio > 1f)
    }
}

class SensorDefectMask(
    val width: Int,
    val height: Int,
    val regions: List<SensorDefectRegion>,
    val enabled: Boolean,
    val rejectionReason: String? = null
) {
    private val footprint = BooleanArray(
        width.toLong().times(height).also {
            require(width > 0 && height > 0 && it <= Int.MAX_VALUE)
        }.toInt()
    )

    val footprintPixels: List<SensorDefectFootprintPixel>
    val maskedPixelCount: Int
    val maskedSourceFraction: Float

    init {
        regions.forEach { region ->
            require(region.classification == PersistentArtifactClassification.SENSOR_DEFECT)
            require(region.confidence in 0f..1f)
            require(region.recurrence in 0..region.totalFrameCount)
            require(region.skySpaceSupport in 0..region.totalFrameCount)
            region.footprintPixels.forEach { pixel ->
                require(pixel.x in 0 until width && pixel.y in 0 until height)
                footprint[pixel.y * width + pixel.x] = true
            }
        }
        footprintPixels = footprint.indices
            .asSequence()
            .filter { footprint[it] }
            .map { SensorDefectFootprintPixel(it % width, it / width) }
            .toList()
        maskedPixelCount = footprintPixels.size
        maskedSourceFraction = maskedPixelCount.toFloat() /
            (width.toLong() * height).coerceAtLeast(1L)
    }

    fun contains(sourceX: Int, sourceY: Int): Boolean =
        enabled &&
            sourceX in 0 until width &&
            sourceY in 0 until height &&
            footprint[sourceY * width + sourceX]

    fun intersectsBilinearSample(sourceX: Float, sourceY: Float): Boolean {
        if (
            !enabled ||
            !sourceX.isFinite() ||
            !sourceY.isFinite() ||
            sourceX < 0f ||
            sourceY < 0f ||
            sourceX > width - 1f ||
            sourceY > height - 1f
        ) {
            return false
        }
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val x1 = minOf(width - 1, x0 + 1)
        val y1 = minOf(height - 1, y0 + 1)
        val fractionX = sourceX - x0
        val fractionY = sourceY - y0
        val leftWeight = 1f - fractionX
        val topWeight = 1f - fractionY
        return (
            leftWeight * topWeight > 0f && contains(x0, y0)
            ) || (
            fractionX * topWeight > 0f && contains(x1, y0)
            ) || (
            leftWeight * fractionY > 0f && contains(x0, y1)
            ) || (
            fractionX * fractionY > 0f && contains(x1, y1)
            )
    }

    fun scaledTo(targetWidth: Int, targetHeight: Int): SensorDefectMask {
        require(targetWidth > 0 && targetHeight > 0)
        if (targetWidth == width && targetHeight == height) return this
        val scaleX = targetWidth.toFloat() / width
        val scaleY = targetHeight.toFloat() / height
        val scaledRegions = regions.map { region ->
            val x = region.sourceX * scaleX
            val y = region.sourceY * scaleY
            val radiusX = region.sourceRadiusX * scaleX
            val radiusY = region.sourceRadiusY * scaleY
            region.copy(
                sourceX = x,
                sourceY = y,
                sourceRadiusX = radiusX,
                sourceRadiusY = radiusY,
                footprintPixels = rasterizedEllipse(
                    targetWidth,
                    targetHeight,
                    x,
                    y,
                    radiusX,
                    radiusY
                )
            )
        }
        return SensorDefectMask(
            targetWidth,
            targetHeight,
            scaledRegions,
            enabled,
            rejectionReason
        )
    }

    companion object {
        fun empty(
            width: Int,
            height: Int,
            reason: String = "no_confirmed_sensor_defects"
        ): SensorDefectMask = SensorDefectMask(
            width,
            height,
            emptyList(),
            enabled = false,
            rejectionReason = reason
        )
    }
}

internal fun buildConfirmedSensorDefectMask(
    staticMask: StaticArtifactMask,
    frames: List<ArtifactFrameObservation>,
    referenceToSourceTransforms: List<ReferenceToSourceTransform>,
    policy: SensorDefectMaskPolicy = SensorDefectMaskPolicy()
): SensorDefectMask = buildConfirmedSensorDefectMaskProfiled(
    staticMask,
    frames,
    referenceToSourceTransforms,
    policy
).mask

internal data class SensorDefectMaskBuildStageProfile(
    val elapsedNanos: Long,
    val inputCount: Int,
    val outputCount: Int,
    val processedPixels: Long,
    val estimatedAllocatedBytes: Long
)

internal data class ProfiledSensorDefectMask(
    val mask: SensorDefectMask,
    val recurrenceCalculation: SensorDefectMaskBuildStageProfile,
    val footprintConstruction: SensorDefectMaskBuildStageProfile,
    val maskValidation: SensorDefectMaskBuildStageProfile
)

internal fun buildConfirmedSensorDefectMaskProfiled(
    staticMask: StaticArtifactMask,
    frames: List<ArtifactFrameObservation>,
    referenceToSourceTransforms: List<ReferenceToSourceTransform>,
    policy: SensorDefectMaskPolicy = SensorDefectMaskPolicy()
): ProfiledSensorDefectMask {
    require(frames.size == referenceToSourceTransforms.size)
    require(frames.isNotEmpty())
    require(staticMask.width > 0 && staticMask.height > 0)
    val eligibleTypes = setOf(
        StaticArtifactType.HOT_PIXEL,
        StaticArtifactType.SINGLE_CHANNEL_SPIKE,
        StaticArtifactType.FIXED_PATTERN_POINT
    )
    val recurrenceStarted = System.nanoTime()
    val requiredRecurrence = maxOf(
        TemporalPixelConsistency.MIN_TEMPORAL_FRAMES,
        ceil(frames.size * policy.minimumRecurrenceRatio).toInt()
    )
    val recurrentRegions = staticMask.regions.filter { region ->
        if (region.type !in eligibleTypes) return@filter false
        if (region.confidence < policy.minimumConfidence) return@filter false
        val recurrence = region.recurrence
        if (recurrence < requiredRecurrence || region.frameCount != frames.size) {
            return@filter false
        }
        true
    }
    val recurrenceProfile = SensorDefectMaskBuildStageProfile(
        elapsedNanos = System.nanoTime() - recurrenceStarted,
        inputCount = staticMask.regions.size,
        outputCount = recurrentRegions.size,
        processedPixels = 0L,
        estimatedAllocatedBytes = recurrentRegions.size * REFERENCE_BYTES_ESTIMATE
    )

    val footprintStarted = System.nanoTime()
    val footprints = recurrentRegions.mapNotNull { region ->
        val pixels = rasterizedEllipse(
            staticMask.width,
            staticMask.height,
            region.x,
            region.y,
            region.radius,
            region.radius
        )
        if (pixels.isEmpty() || pixels.size > policy.maximumRegionFootprintPixels) {
            return@mapNotNull null
        }
        region to pixels
    }
    val footprintPixelCount = footprints.sumOf { it.second.size.toLong() }
    val footprintProfile = SensorDefectMaskBuildStageProfile(
        elapsedNanos = System.nanoTime() - footprintStarted,
        inputCount = recurrentRegions.size,
        outputCount = footprints.size,
        processedPixels = footprintPixelCount,
        estimatedAllocatedBytes =
            footprintPixelCount * SENSOR_FOOTPRINT_PIXEL_BYTES_ESTIMATE +
                footprints.size * PAIR_BYTES_ESTIMATE
    )

    val validationStarted = System.nanoTime()
    val regions = footprints.mapNotNull { (region, footprint) ->
        val skySupport = frames.indices.count { index ->
            val transform = referenceToSourceTransforms[index]
            val expectedX = region.x + transform.dx
            val expectedY = region.y + transform.dy
            frames[index].stars.any { star ->
                val dx = star.x - expectedX
                val dy = star.y - expectedY
                dx * dx + dy * dy <= region.radius * region.radius
            }
        }
        if (
            region.recurrence.toFloat() <=
            skySupport.toFloat() * policy.minimumCameraToSkySupportRatio
        ) {
            return@mapNotNull null
        }
        SensorDefectRegion(
            stableRegionId = stableSensorDefectRegionId(region),
            sourceX = region.x,
            sourceY = region.y,
            sourceRadiusX = region.radius,
            sourceRadiusY = region.radius,
            footprintPixels = footprint,
            recurrence = region.recurrence,
            totalFrameCount = frames.size,
            skySpaceSupport = skySupport,
            confidence = region.confidence,
            classification = PersistentArtifactClassification.SENSOR_DEFECT,
            classificationReason =
                "confirmed_camera_stable_sensor_defect:${region.reason}"
        )
    }.sortedWith(
        compareBy<SensorDefectRegion> { it.sourceY }
            .thenBy { it.sourceX }
            .thenBy { it.stableRegionId }
    )
    val mask = if (regions.isEmpty()) {
        SensorDefectMask.empty(staticMask.width, staticMask.height)
    } else {
        val candidate = SensorDefectMask(
            staticMask.width,
            staticMask.height,
            regions,
            enabled = true
        )
        if (candidate.maskedSourceFraction <= policy.maximumMaskedSourceFraction) {
            candidate
        } else {
            SensorDefectMask(
                staticMask.width,
                staticMask.height,
                regions,
                enabled = false,
                rejectionReason = "masked_source_fraction_exceeds_limit"
            )
        }
    }
    val validationProfile = SensorDefectMaskBuildStageProfile(
        elapsedNanos = System.nanoTime() - validationStarted,
        inputCount = footprints.size,
        outputCount = regions.size,
        processedPixels =
            footprints.size.toLong() * frames.sumOf { it.stars.size.toLong() } +
                staticMask.width.toLong() * staticMask.height,
        estimatedAllocatedBytes =
            staticMask.width.toLong() * staticMask.height +
                mask.footprintPixels.size * SENSOR_FOOTPRINT_PIXEL_BYTES_ESTIMATE
    )
    return ProfiledSensorDefectMask(
        mask,
        recurrenceProfile,
        footprintProfile,
        validationProfile
    )
}

private fun stableSensorDefectRegionId(region: StaticArtifactRegion): String =
    "sensor-${region.type.name.lowercase()}-" +
        "x${(region.x * 100f).roundToInt()}-y${(region.y * 100f).roundToInt()}"

private fun rasterizedEllipse(
    width: Int,
    height: Int,
    centerX: Float,
    centerY: Float,
    radiusX: Float,
    radiusY: Float
): List<SensorDefectFootprintPixel> {
    if (radiusX <= 0f || radiusY <= 0f) return emptyList()
    val left = floor(centerX - radiusX).toInt().coerceAtLeast(0)
    val top = floor(centerY - radiusY).toInt().coerceAtLeast(0)
    val right = ceil(centerX + radiusX).toInt().coerceAtMost(width - 1)
    val bottom = ceil(centerY + radiusY).toInt().coerceAtMost(height - 1)
    return buildList {
        for (y in top..bottom) for (x in left..right) {
            val normalizedX = (x - centerX) / radiusX
            val normalizedY = (y - centerY) / radiusY
            if (normalizedX * normalizedX + normalizedY * normalizedY <= 1f) {
                add(SensorDefectFootprintPixel(x, y))
            }
        }
    }
}

private const val REFERENCE_BYTES_ESTIMATE = 8L
private const val PAIR_BYTES_ESTIMATE = 24L
private const val SENSOR_FOOTPRINT_PIXEL_BYTES_ESTIMATE = 24L
