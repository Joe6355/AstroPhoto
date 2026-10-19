package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import java.io.File
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnhancedColorPrecisionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun dimLinearScalingDoesNotCreateAStrongColorPatch() {
        // Same near-black channel rounding seen in a real RGB16 buffer: [3,3,4] -> [3,3,6].
        val result = validate()
        assertFalse(result.hardFailureReasons.toString(), "new_strong_color_patch" in result.hardFailureReasons)
        assertEquals(0, result.metrics.newStrongColorPatchCount)
        assertTrue(result.metrics.quantizationOnlyColorPatchCount > 0)
        assertTrue("color_patch_within_output_quantization" in result.warnings)
        assertTrue(result.hardFailureReasons.toString(), result.accepted)
        assertEquals(1.0, result.metrics.foregroundStrongEdgeRetention, 0.0)
        assertEquals(1.0, result.metrics.foregroundEdgeSignAgreement, 0.0)
        assertEquals(1f, result.metrics.asReportMetrics()["colorPatchMeasurementLinear16"])
    }

    @Test fun realLinearColorShiftStillRejectsAConnectedSkyPatch() {
        val result = validate(bright = true, changed = connected)
        assertTrue("new_strong_color_patch" in result.hardFailureReasons)
        assertTrue(result.metrics.newStrongColorPatchCount > 0)
        assertFalse(result.accepted)
    }

    @Test fun isolatedColorNoiseDoesNotBecomeAConnectedPatch() {
        val result = validate(bright = true, changed = setOf(40 to 40, 50 to 50, 60 to 60))
        assertFalse("new_strong_color_patch" in result.hardFailureReasons)
        assertEquals(0, result.metrics.newStrongColorPatchCount)
    }

    @Test fun foregroundAndConfirmedStarWindowsRemainExcluded() {
        for (nearStar in listOf(false, true)) {
            val result = validate(bright = true, changed = connected,
                foreground = !nearStar, extraStar = nearStar)
            assertFalse("new_strong_color_patch" in result.hardFailureReasons)
            assertEquals(0, result.metrics.newStrongColorPatchCount)
        }
    }

    @Test fun anActualArgbSourceStillRejectsItsMeasuredColorShift() {
        // When RGB8 is the source data, its change is real rather than a lossy measurement
        // of a higher-precision buffer. Do not silently exempt the same displayed colours.
        val result = validate(format = FileBackedPixelFormat.ARGB_8888)
        assertTrue("new_strong_color_patch" in result.hardFailureReasons)
        assertTrue(result.metrics.newStrongColorPatchCount > 0)
    }

    @Test fun losingActualForegroundEdgesStillRejectsTheCandidate() {
        val result = validate(eraseForeground = true)
        assertTrue("foreground_edge_geometry_changed" in result.hardFailureReasons)
        assertEquals(0.0, result.metrics.foregroundStrongEdgeRetention, 0.0)
        assertFalse(result.accepted)
    }

    private fun validate(
        bright: Boolean = false,
        changed: Set<Pair<Int, Int>> = emptySet(),
        foreground: Boolean = false,
        extraStar: Boolean = false,
        eraseForeground: Boolean = false,
        format: FileBackedPixelFormat = FileBackedPixelFormat.LINEAR_RGB_16
    ): EnhancedGlobalToneValidation {
        val name = "${temporaryFolder.root.listFiles().orEmpty().size}"
        val before = FileBackedImageWriter(File(temporaryFolder.root, "$name-before.argb"), SIZE, SIZE, format)
        val after = FileBackedImageWriter(File(temporaryFolder.root, "$name-after.argb"), SIZE, SIZE, format)
        val alpha = FileBackedFloatPlaneWriter(File(temporaryFolder.root, "$name-alpha.f32"), SIZE, SIZE)
        for (y in 0 until SIZE) {
            val row = LongArray(SIZE) { x ->
                if (eraseForeground && y >= 72) return@LongArray LinearRgb16.pack(
                    if (x >= 48) 0.2f else 0.02f, 0.02f, 0.02f)
                val star = 0.025f * exp(-((x - 16f) * (x - 16f) + (y - 16f) * (y - 16f)) / 3.5f)
                val channels = if (bright) listOf(100f, 100f, 100f) else listOf(50f, 50f, 88f)
                LinearRgb16.pack(channels[0] / 65535f + star,
                    channels[1] / 65535f + star, channels[2] / 65535f + star)
            }
            before.writeLinearRow(y, row)
            after.writeLinearRow(y, LongArray(SIZE) { x ->
                if (eraseForeground && y >= 72) LinearRgb16.pack(0.02f, 0.02f, 0.02f)
                else if ((x to y) in changed) LinearRgb16.pack(128f / 65535f, 128f / 65535f, 800f / 65535f)
                else LinearRgb16.pack(LinearRgb16.red(row[x]) * 1.284f,
                    LinearRgb16.green(row[x]) * 1.284f, LinearRgb16.blue(row[x]) * 1.284f)
            })
            alpha.writeRow(y, FloatArray(SIZE) { x ->
                if (eraseForeground && y >= 72 || foreground && (x to y) in changed) 0f else 1f
            })
        }
        val stars = buildList {
            add(DetectedStar(16f, 16f, 1f, 0.001f, 0.025f, 3f, 0f, 1f))
            if (extraStar) add(DetectedStar(40.5f, 40.5f, 1f, 0.001f, 0.025f, 3f, 0f, 1f))
        }
        return EnhancedGlobalToneValidator().validate(before.finish(), after.finish(), alpha.finish(), stars,
            GlobalToneAnchors(0.0001, 0.002), 0, 0.1)
    }

    companion object {
        private const val SIZE = 96
        private val connected = setOf(40 to 40, 40 to 41, 41 to 40, 41 to 41)
    }
}
