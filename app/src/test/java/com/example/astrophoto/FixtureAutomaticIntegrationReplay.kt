package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.example.astrophoto.processing.jpeg.v2.integration.TileProcessingCoordinator
import com.example.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource

internal data class FixtureAutomaticIntegrationReplay(
    val image: ArgbPixelImage,
    val coverage: FloatArray,
    val sensorDefectAffectedOutput: FloatArray,
    val acceptedFrames: Int,
    val filtering: SensorDefectFilteringReport
)

/**
 * Test-only replay of the production automatic integrator using original fixture indices.
 * Rejected frames are compacted only at the accumulator boundary.
 */
internal suspend fun integrateFixtureForAutomaticReplay(
    fixture: Stage6RegressionFixture,
    plan: ManualSequenceAlignmentPlan,
    mask: SensorDefectMask?
): FixtureAutomaticIntegrationReplay {
    val width = fixture.frames.first().width
    val height = fixture.frames.first().height
    val output = IntArray(width * height)
    val coverage = FloatArray(width * height)
    val sensorDefectAffectedOutput = FloatArray(width * height)
    val accepted = plan.frames.filter { it.accepted }
    val diagnostics = LinearWeightedIntegrator(
        tileCoordinator = TileProcessingCoordinator(128, 32)
    ).integrate(
        outputWidth = width,
        outputHeight = height,
        frames = accepted.map { decision ->
            WeightedIntegrationFrame(
                id = decision.frameId ?: "frame-${decision.originalFrameIndex}",
                source = fixture.frames[decision.originalFrameIndex],
                transform = fixtureReplayRegistration(
                    dx = decision.shift.dx.toFloat(),
                    dy = decision.shift.dy.toFloat()
                ),
                normalizedWeight = 1f
            )
        },
        maximumWorkingMemoryBytes = 64L * 1024L * 1024L,
        openSource = { IntArrayPixelSource(width, height, it.pixels) },
        allowRobustClipping = false,
        sensorDefectMask = mask,
        writeTile = { tile, pixels ->
            for (row in 0 until tile.height) {
                pixels.copyInto(
                    output,
                    destinationOffset = (tile.top + row) * width + tile.left,
                    startIndex = row * tile.width,
                    endIndex = (row + 1) * tile.width
                )
            }
        },
        writeCoverageTile = { tile, values ->
            for (row in 0 until tile.height) {
                values.copyInto(
                    coverage,
                    destinationOffset = (tile.top + row) * width + tile.left,
                    startIndex = row * tile.width,
                    endIndex = (row + 1) * tile.width
                )
            }
        },
        writeSensorDefectAffectedTile = { tile, values ->
            for (row in 0 until tile.height) {
                for (column in 0 until tile.width) {
                    val sourceIndex = row * tile.width + column
                    val destinationIndex =
                        (tile.top + row) * width + tile.left + column
                    sensorDefectAffectedOutput[destinationIndex] =
                        if (values[sourceIndex]) 1f else 0f
                }
            }
        }
    )
    return FixtureAutomaticIntegrationReplay(
        image = ArgbPixelImage(width, height, output),
        coverage = coverage,
        sensorDefectAffectedOutput = sensorDefectAffectedOutput,
        acceptedFrames = diagnostics.acceptedFrames,
        filtering = diagnostics.sensorDefectFiltering
    )
}

private fun fixtureReplayRegistration(dx: Float, dy: Float) = RegistrationResult(
    dx = dx,
    dy = dy,
    rotationRadians = 0f,
    scale = 1f,
    detectedStars = 10,
    matchedStars = 10,
    inlierStars = 10,
    residualError = 0f,
    confidence = 1f,
    isReliable = true,
    rejectionReason = null
)
