package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectRegion
import com.example.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.example.astrophoto.processing.jpeg.v2.model.SensorDefectRegionReport

internal data class ManualSensorDefectCoveragePlan(
    val mask: SensorDefectMask,
    val commonOutputRegion: PixelRect,
    val report: SensorDefectFilteringReport
) {
    fun sourceSampleIsValid(
        outputX: Int,
        outputY: Int,
        shift: AlignmentShift,
        sourceWidth: Int,
        sourceHeight: Int
    ): Boolean {
        val sourceX = outputX + shift.dx
        val sourceY = outputY + shift.dy
        return sourceX in 0 until sourceWidth &&
            sourceY in 0 until sourceHeight &&
            (
                !report.sampleLevelFilteringApplied ||
                    !mask.contains(sourceX, sourceY)
                )
    }
}

internal fun manualSensorDefectCoveragePlan(
    plan: ManualSequenceAlignmentPlan?,
    mode: ManualAlignedStackMode,
    outputWidth: Int,
    outputHeight: Int
): ManualSensorDefectCoveragePlan? {
    if (plan == null) return null
    require(outputWidth > 0 && outputHeight > 0)
    val accepted = plan.frames.filter { it.accepted }
    require(accepted.size >= mode.minimumFrameCount)
    val commonRegion = commonAlignedRegion(
        outputWidth,
        outputHeight,
        accepted.map { it.shift }
    )
    val mask = plan.sensorDefectMask ?: SensorDefectMask.empty(
        outputWidth,
        outputHeight,
        reason = "sensor_defect_analysis_unavailable"
    )
    require(mask.width == outputWidth && mask.height == outputHeight)
    val regionReports = mask.regions.map(SensorDefectRegion::toManualReport)
    if (!mask.enabled || mask.regions.isEmpty()) {
        return ManualSensorDefectCoveragePlan(
            mask,
            commonRegion,
            unfilteredReport(
                regions = regionReports,
                mask = mask,
                acceptedFrameCount = accepted.size,
                reason = mask.rejectionReason ?: "no_confirmed_sensor_defects"
            )
        )
    }

    val commonPixelCountLong = commonRegion.width.toLong() * commonRegion.height
    require(commonPixelCountLong in 1..Int.MAX_VALUE)
    val commonPixelCount = commonPixelCountLong.toInt()
    val exclusions = IntArray(commonPixelCount)
    var excludedSamples = 0L
    accepted.forEach { decision ->
        mask.footprintPixels.forEach { sourcePixel ->
            val outputX = sourcePixel.x - decision.shift.dx
            val outputY = sourcePixel.y - decision.shift.dy
            if (
                outputX in commonRegion.left until commonRegion.right &&
                outputY in commonRegion.top until commonRegion.bottom
            ) {
                val index = (outputY - commonRegion.top) * commonRegion.width +
                    (outputX - commonRegion.left)
                exclusions[index]++
                excludedSamples++
            }
        }
    }
    val remainingHistogram = IntArray(accepted.size + 1)
    var affected = 0
    var insufficient = 0
    exclusions.forEach { excluded ->
        val remaining = (accepted.size - excluded).coerceIn(0, accepted.size)
        remainingHistogram[remaining]++
        if (excluded > 0) affected++
        if (remaining < mode.minimumFrameCount) insufficient++
    }
    val insufficientFraction = insufficient.toFloat() / commonPixelCount
    if (insufficientFraction > MAX_INSUFFICIENT_COVERAGE_FRACTION) {
        return ManualSensorDefectCoveragePlan(
            mask,
            commonRegion,
            unfilteredReport(
                regions = regionReports,
                mask = mask,
                acceptedFrameCount = accepted.size,
                reason = "insufficient_coverage_exceeds_limit"
            )
        )
    }
    val minimumRemaining = remainingHistogram.indexOfFirst { it > 0 }
        .coerceAtLeast(0)
    val maximumRemaining = remainingHistogram.indexOfLast { it > 0 }
        .coerceAtLeast(0)
    val medianRemaining = histogramMedian(remainingHistogram, commonPixelCount)
    return ManualSensorDefectCoveragePlan(
        mask,
        commonRegion,
        SensorDefectFilteringReport(
            regions = regionReports,
            maskedSourcePixelCount = mask.maskedPixelCount,
            maskedSourceFraction = mask.maskedSourceFraction,
            excludedSampleCount = excludedSamples,
            affectedOutputPixelCount = affected,
            minimumRemainingSampleCount = minimumRemaining,
            medianRemainingSampleCount = medianRemaining,
            maximumRemainingSampleCount = maximumRemaining,
            insufficientCoveragePixelCount = insufficient,
            maskEnabled = mask.enabled,
            sampleLevelFilteringApplied = true,
            fallbackOrRejectionReason = if (insufficient > 0) {
                "reference_sample_fallback_for_insufficient_coverage"
            } else {
                null
            }
        )
    )
}

internal fun compactValidArgbSamples(
    colors: IntArray,
    validSamples: BooleanArray,
    destination: IntArray
): Int {
    require(validSamples.size >= colors.size)
    require(destination.size >= colors.size)
    var count = 0
    colors.indices.forEach { index ->
        if (validSamples[index]) {
            destination[count++] = colors[index]
        }
    }
    return count
}

private fun unfilteredReport(
    regions: List<SensorDefectRegionReport>,
    mask: SensorDefectMask,
    acceptedFrameCount: Int,
    reason: String
) = SensorDefectFilteringReport(
    regions = regions,
    maskedSourcePixelCount = mask.maskedPixelCount,
    maskedSourceFraction = mask.maskedSourceFraction,
    excludedSampleCount = 0L,
    affectedOutputPixelCount = 0,
    minimumRemainingSampleCount = acceptedFrameCount,
    medianRemainingSampleCount = acceptedFrameCount,
    maximumRemainingSampleCount = acceptedFrameCount,
    insufficientCoveragePixelCount = 0,
    maskEnabled = mask.enabled,
    sampleLevelFilteringApplied = false,
    fallbackOrRejectionReason = reason
)

private fun SensorDefectRegion.toManualReport() = SensorDefectRegionReport(
    stableRegionId = stableRegionId,
    footprintPixelCount = footprintPixels.size,
    recurrence = recurrence,
    totalFrameCount = totalFrameCount,
    skySpaceSupport = skySpaceSupport,
    confidence = confidence,
    classificationReason = classificationReason
)

private fun histogramMedian(histogram: IntArray, total: Int): Int {
    val target = (total - 1) / 2
    var seen = 0
    histogram.indices.forEach { value ->
        seen += histogram[value]
        if (seen > target) return value
    }
    return histogram.lastIndex
}

private const val MAX_INSUFFICIENT_COVERAGE_FRACTION = 0.001f
