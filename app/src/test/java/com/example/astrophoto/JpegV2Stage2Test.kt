package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.integration.FrameWeightCalculator
import com.example.astrophoto.processing.jpeg.v2.integration.FrameWeightInput
import com.example.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.example.astrophoto.processing.jpeg.v2.integration.RobustSampleAccumulator
import com.example.astrophoto.processing.jpeg.v2.integration.TileProcessingCoordinator
import com.example.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.example.astrophoto.processing.jpeg.v2.model.IntegrationMode
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.output.ArgbArrayPngSource
import com.example.astrophoto.processing.jpeg.v2.output.PngStreamEncoder
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage2Test {
    @Test fun srgbZeroIsLinearZero() = assertEquals(0f, SrgbTransfer.srgbToLinear(0f), 0f)

    @Test fun linearZeroIsSrgbZero() = assertEquals(0f, SrgbTransfer.linearToSrgb(0f), 0f)

    @Test fun knownSrgbValueUsesStandardTransfer() =
        assertEquals(0.214041f, SrgbTransfer.srgbToLinear(0.5f), 0.00001f)

    @Test fun srgbLinearRoundTripIsStable() {
        listOf(0f, 0.01f, 0.18f, 0.5f, 0.9f, 1f).forEach { value ->
            assertEquals(value, SrgbTransfer.linearToSrgb(SrgbTransfer.srgbToLinear(value)), 0.00001f)
        }
    }

    @Test fun linearAverageDiffersFromGammaAverage() {
        val linearAverage = SrgbTransfer.linearToSrgb(
            (SrgbTransfer.srgbToLinear(0f) + SrgbTransfer.srgbToLinear(1f)) / 2f
        )
        assertEquals(0.735357f, linearAverage, 0.00001f)
        assertNotEquals(0.5f, linearAverage, 0.1f)
    }

    @Test fun poorRegistrationConfidenceLowersWeight() {
        val weights = weights(
            input("reference", registration(confidence = 1f), reference = true),
            input("poor", registration(confidence = 0.35f))
        )
        assertTrue(weights.getValue("poor") < weights.getValue("reference"))
    }

    @Test fun highResidualLowersWeight() {
        val weights = weights(
            input("reference", registration(), reference = true),
            input("residual", registration(residual = 5f))
        )
        assertTrue(weights.getValue("residual") < weights.getValue("reference"))
    }

    @Test fun trailedStarsLowerWeight() {
        val weights = weights(
            input("reference", registration(), reference = true),
            input("trailed", registration(), ellipticity = 0.75f)
        )
        assertTrue(weights.getValue("trailed") < weights.getValue("reference"))
    }

    @Test fun highNoiseLowersWeight() {
        val weights = weights(
            input("reference", registration(), reference = true, noise = 2f),
            input("noisy", registration(), noise = 12f),
            input("normal", registration(), noise = 2f)
        )
        assertTrue(weights.getValue("noisy") < weights.getValue("normal"))
    }

    @Test fun normalizedWeightsAreSafelyClamped() {
        val calculated = FrameWeightCalculator().calculate(
            listOf(
                input("best", registration(), reference = true, noise = 1f),
                input("worst", registration(confidence = 0.1f, residual = 20f), noise = 50f)
            )
        )
        assertTrue(calculated.all {
            it.normalizedWeight in FrameWeightCalculator.MIN_NORMALIZED_WEIGHT..FrameWeightCalculator.MAX_NORMALIZED_WEIGHT
        })
    }

    @Test fun rejectedFramesNeverReceiveWeights() {
        val calculated = FrameWeightCalculator().calculate(
            listOf(
                input("reference", registration(), reference = true),
                input("rejected", registration(reliable = false))
            )
        )
        assertEquals(listOf("reference"), calculated.map { it.frameId })
    }

    @Test fun referenceReceivesRealWeight() {
        val calculated = FrameWeightCalculator().calculate(
            listOf(input("reference", registration(), reference = true))
        ).single()
        assertTrue(calculated.rawWeight > 0f)
        assertEquals(1f, calculated.normalizedWeight, 0.0001f)
    }

    @Test fun samplerRecoversFractionalTranslation() {
        val source = IntArrayPixelSource(3, 2, intArrayOf(gray(0), gray(100), gray(200), gray(0), gray(100), gray(200)))
        val sampled = TransformedBitmapSampler().sample(source, registration(dx = 0.5f), 0f, 0f)
        assertEquals(50f / 255f, checkNotNull(sampled).red, 0.0001f)
    }

    @Test fun samplerUsesSmallRotationWithoutRounding() {
        val source = IntArrayPixelSource(3, 3, IntArray(9) { gray(it * 20) })
        val transform = registration(rotation = 0.05f)
        val point = transform.transform(1f, 1f)
        val sampler = TransformedBitmapSampler()
        val transformed = checkNotNull(sampler.sample(source, transform, 1f, 1f))
        val direct = checkNotNull(sampler.sampleAt(source, point.first, point.second))
        assertEquals(direct.red, transformed.red, 0.000001f)
    }

    @Test fun bilinearSamplingIsContinuous() {
        val source = IntArrayPixelSource(2, 1, intArrayOf(gray(0), gray(255)))
        val sampler = TransformedBitmapSampler()
        val left = checkNotNull(sampler.sampleAt(source, 0.49f, 0f)).red
        val right = checkNotNull(sampler.sampleAt(source, 0.51f, 0f)).red
        assertTrue(right > left)
        assertTrue(right - left < 0.03f)
    }

    @Test fun samplesOutsideSourceAreRejected() {
        val source = IntArrayPixelSource(2, 2, IntArray(4) { gray(20) })
        assertEquals(null, TransformedBitmapSampler().sampleAt(source, -0.01f, 0f))
    }

    @Test fun tileBoundariesDoNotCreateSeams() = runBlocking {
        val pixels = IntArray(35 * 3) { index -> gray((index * 7) and 0xFF) }
        val result = integrate(35, 3, listOf(pixels), tileSize = 32)
        assertArrayEquals(pixels, result.pixels)
    }

    @Test fun tiledIntegrationMatchesSingleTileIntegration() = runBlocking {
        val first = IntArray(9 * 5) { gray((it * 3) and 0xFF) }
        val second = IntArray(9 * 5) { gray((it * 5 + 20) and 0xFF) }
        val tiled = integrate(9, 5, listOf(first, second), tileSize = 3)
        val single = integrate(9, 5, listOf(first, second), tileSize = 64)
        assertArrayEquals(single.pixels, tiled.pixels)
    }

    @Test fun weightedIntegrationReducesIndependentNoise() = runBlocking {
        val values = listOf(20, 80, 35, 65, 45, 55, 40)
        val result = integrate(1, 1, values.map { intArrayOf(gray(it)) })
        val integrated = result.pixels.single() and 0xFF
        assertTrue(kotlin.math.abs(integrated - 50) < kotlin.math.abs(values.first() - 50))
    }

    @Test fun repeatedFaintAlignedSignalIsPreserved() = runBlocking {
        val frames = List(8) { index -> intArrayOf(gray(if (index < 2) 70 else 30)) }
        val result = integrate(1, 1, frames)
        assertTrue((result.pixels.single() and 0xFF) > 30)
    }

    @Test fun oneFrameBrightTransientIsSuppressed() = runBlocking {
        val frames = List(8) { index -> intArrayOf(gray(if (index == 0) 255 else 32)) }
        val result = integrate(1, 1, frames)
        assertEquals(32, result.pixels.single() and 0xFF)
    }

    @Test fun repeatedBrightSamplesProtectEachOther() {
        val accumulator = RobustSampleAccumulator(1, true)
        repeat(6) { accumulator.add(0, 0.02f, 0.02f, 0.02f, 1f) }
        repeat(2) { accumulator.add(0, 0.45f, 0.45f, 0.45f, 1f) }
        assertFalse(checkNotNull(accumulator.finish(0)).removedTransient)
    }

    @Test fun fewerThanEightFramesUsePlainAverage() = runBlocking {
        val result = integrate(1, 1, List(7) { intArrayOf(gray(30)) })
        assertEquals(IntegrationMode.LINEAR_WEIGHTED_AVERAGE, result.mode)
        assertFalse(result.robust)
    }

    @Test fun eightFramesEnableMildRobustMode() = runBlocking {
        val result = integrate(1, 1, List(8) { intArrayOf(gray(30)) })
        assertEquals(IntegrationMode.LINEAR_WEIGHTED_REPEATABILITY_ROBUST, result.mode)
        assertTrue(result.robust)
    }

    @Test fun outputDimensionsEqualReferenceDimensions() = runBlocking {
        val result = integrate(7, 5, listOf(IntArray(35) { gray(20) }))
        assertEquals(7, result.width)
        assertEquals(5, result.height)
    }

    @Test fun outputAspectRatioRemainsUnchanged() = runBlocking {
        val result = integrate(12, 5, listOf(IntArray(60) { gray(20) }))
        assertEquals(12.0 / 5.0, result.width.toDouble() / result.height, 0.0)
    }

    @Test fun memoryFallbackReducesTileNotResolution() {
        val normal = TileProcessingCoordinator().plan(2000, 1500, true, 256L * 1024L * 1024L)
        val constrained = TileProcessingCoordinator().plan(2000, 1500, true, 25L * 1024L * 1024L)
        assertTrue(constrained.tileWidth < normal.tileWidth || constrained.tileHeight < normal.tileHeight)
        assertEquals(normal.outputWidth, constrained.outputWidth)
        assertEquals(normal.outputHeight, constrained.outputHeight)
    }

    @Test fun pngWriterCreatesValidExpectedDimensions() {
        val output = ByteArrayOutputStream()
        PngStreamEncoder.encode(
            ArgbArrayPngSource(3, 2, intArrayOf(red(), gray(20), gray(30), gray(40), gray(50), gray(60))),
            output
        )
        val decoded = ImageIO.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(0xFF0000, decoded.getRGB(0, 0) and 0xFFFFFF)
    }

    @Test fun pngEncoderUsesPngSignature() {
        val output = ByteArrayOutputStream()
        PngStreamEncoder.encode(ArgbArrayPngSource(1, 1, intArrayOf(gray(0))), output)
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            output.toByteArray().copyOf(8)
        )
    }

    @Test fun existingJpegAndNewPngAreDiscoverable() {
        assertTrue(isSupportedProcessedImageFile("DeepSky_1.jpg", "image/jpeg"))
        assertTrue(isSupportedProcessedImageFile("DeepSky_2.png", "image/png"))
        assertFalse(isSupportedProcessedImageFile("DeepSky_2.png", "image/jpeg"))
    }

    @Test fun openAndShareMimeFollowsFileExtension() {
        assertEquals("image/jpeg", processedImageMimeType("old.jpeg"))
        assertEquals("image/png", processedImageMimeType("new.png"))
    }

    @Test fun pngUniqueNamePreservesLosslessExtension() {
        val name = findUniqueProcessedResultName("DeepSky_20260101.png") { it == "DeepSky_20260101.png" }
        assertEquals("DeepSky_20260101_01.png", name)
    }

    @Test fun presetIdentifiersRemainCompatible() {
        assertEquals(
            listOf("NORMAL", "DEEP_SKY", "DEEP_SKY_ALIGNED", "URBAN_SKY", "URBAN_SKY_STRONG", "MAX_STARS"),
            AstroProcessingProfile.entries.map { it.name }
        )
    }

    private data class IntegrationResult(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val mode: IntegrationMode,
        val robust: Boolean
    )

    private suspend fun integrate(
        width: Int,
        height: Int,
        sources: List<IntArray>,
        tileSize: Int = 32
    ): IntegrationResult {
        val output = IntArray(width * height)
        val diagnostics = LinearWeightedIntegrator(
            tileCoordinator = TileProcessingCoordinator(tileSize, 1)
        ).integrate(
            outputWidth = width,
            outputHeight = height,
            frames = sources.mapIndexed { index, pixels ->
                WeightedIntegrationFrame(index.toString(), pixels, registration(), 1f)
            },
            maximumWorkingMemoryBytes = 64L * 1024L * 1024L,
            openSource = { IntArrayPixelSource(width, height, it) },
            writeTile = { tile, pixels ->
                for (row in 0 until tile.height) {
                    pixels.copyInto(
                        output,
                        destinationOffset = (tile.top + row) * width + tile.left,
                        startIndex = row * tile.width,
                        endIndex = (row + 1) * tile.width
                    )
                }
            }
        )
        return IntegrationResult(
            output,
            diagnostics.outputWidth,
            diagnostics.outputHeight,
            diagnostics.mode,
            diagnostics.robustModeEnabled
        )
    }

    private fun input(
        id: String,
        registration: RegistrationResult,
        reference: Boolean = false,
        noise: Float = 3f,
        ellipticity: Float = 0.1f
    ) = FrameWeightInput(
        analysis = FrameAnalysis(
            id = id,
            fileName = "$id.jpg",
            width = 100,
            height = 80,
            stars = emptyList(),
            reliableStarCount = 12,
            medianStarContrast = 30f,
            medianStarWidth = 1.8f,
            medianStarEllipticity = ellipticity,
            backgroundNoise = noise,
            clippingPercent = 0f,
            exposureSuitability = 1f,
            decodeValid = true,
            alignmentSuitability = 1f,
            skyMaskConfidence = 1f,
            skyMaskUsedFallback = false
        ),
        registration = registration,
        isReference = reference
    )

    private fun weights(vararg inputs: FrameWeightInput): Map<String, Float> =
        FrameWeightCalculator().calculate(inputs.toList()).associate { it.frameId to it.normalizedWeight }

    private fun registration(
        confidence: Float = 1f,
        residual: Float = 0f,
        reliable: Boolean = true,
        dx: Float = 0f,
        rotation: Float = 0f
    ) = RegistrationResult(
        dx = dx,
        dy = 0f,
        rotationRadians = rotation,
        scale = 1f,
        detectedStars = 12,
        matchedStars = 12,
        inlierStars = 12,
        residualError = residual,
        confidence = confidence,
        isReliable = reliable,
        rejectionReason = if (reliable) null else "rejected",
        referenceStars = 12
    )

    private fun gray(value: Int): Int = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
    private fun red(): Int = 0xFFFF0000.toInt()
}
