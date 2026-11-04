package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.joe6355.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.SkyBackgroundToneMatcher
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSkyBackgroundToneMatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val budget get() = JpegMemoryBudget(RuntimeHeapSnapshot(512L shl 20, 128L shl 20, 96L shl 20))

    @Test fun removesMaskShapedLiftAcrossAGradientWithoutErasingACompactStar() {
        TemporaryPipelineFiles.create(temporary.root).use { files ->
            val store = ResultCandidateStore(files)
            val width = 240
            val height = 192
            val before = store.createTemporaryWriter("before", width, height)
            val processed = store.createTemporaryWriter("processed", width, height)
            val mask = store.createFloatPlaneWriter("alpha", width, height)
            for (y in 0 until height) {
                val baselineRow = LongArray(width)
                val processedRow = LongArray(width)
                val alphas = FloatArray(width)
                for (x in 0 until width) {
                    val a = ((x - 24f) / 32f).coerceIn(0f, 1f)
                    val sky = 0.008f + x * 0.000025f + y * 0.00003f
                    val star = (0.025 * exp(-((x - 120.0) * (x - 120) + (y - 96.0) * (y - 96)) / 3.0)).toFloat()
                    val lift = (0.003f + x * 0.000015f + y * 0.00001f) * sqrt(a)
                    baselineRow[x] = LinearRgb16.pack(sky + star, sky + star, sky + star)
                    processedRow[x] = LinearRgb16.pack(sky + star * 1.5f + lift,
                        sky + star * 1.5f + lift, sky + star * 1.5f + lift)
                    alphas[x] = a
                }
                before.writeLinearRow(y, baselineRow)
                processed.writeLinearRow(y, processedRow)
                mask.writeRow(y, alphas)
            }
            val baseline = before.finish()
            val candidate = processed.finish()
            val result = SkyBackgroundToneMatcher.matchLocal(baseline, candidate, mask.finish(), store,
                budget, PipelineMemoryTracker())
            FileBackedImageReader(baseline).use { first ->
                FileBackedImageReader(candidate).use { second ->
                    FileBackedImageReader(result).use { actual ->
                        for (y in 50 until height - 48 step 7) for (x in 0 until width - 48 step 7) {
                            if (abs(x - 120) < 12 && abs(y - 96) < 12) continue
                            if (x <= 24) assertEquals(second.linearRgbAt(x, y), actual.linearRgbAt(x, y))
                            else assertEquals("A smooth sky must not reveal the mask at $x,$y",
                                LinearRgb16.luminance(first.linearRgbAt(x, y)),
                                LinearRgb16.luminance(actual.linearRgbAt(x, y)), 0.00015f)
                        }
                        assertTrue("The enhanced stellar signal must survive background matching",
                            LinearRgb16.luminance(actual.linearRgbAt(120, 96)) >
                                LinearRgb16.luminance(first.linearRgbAt(120, 96)) + 0.01f)
                    }
                }
            }
        }
    }

    @Test fun doesNotUndoBackgroundSubtractionOrAllocateAnotherImageForAnEmptyMask() {
        TemporaryPipelineFiles.create(temporary.root).use { files ->
            val store = ResultCandidateStore(files)
            val before = store.createTemporaryWriter("before", 96, 96)
            val processed = store.createTemporaryWriter("processed", 96, 96)
            val full = store.createFloatPlaneWriter("full", 96, 96)
            val empty = store.createFloatPlaneWriter("empty", 96, 96)
            for (y in 0 until 96) {
                before.writeLinearRow(y, LongArray(96) { LinearRgb16.pack(0.02f, 0.02f, 0.02f) })
                processed.writeLinearRow(y, LongArray(96) { LinearRgb16.pack(0.01f, 0.01f, 0.01f) })
                full.writeRow(y, FloatArray(96) { 1f })
                empty.writeRow(y, FloatArray(96))
            }
            val baseline = before.finish()
            val candidate = processed.finish()
            for (mask in listOf(full.finish(), empty.finish())) {
                assertSame(candidate, SkyBackgroundToneMatcher.matchLocal(baseline, candidate, mask, store,
                    budget, PipelineMemoryTracker()))
            }
        }
    }
}
