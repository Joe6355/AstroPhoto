package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.output.CrashSafePngFilePublisher
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class EditorExportRowsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nativeRowsMatchPreviousWholeImageRenderingIncludingCropNeighbors() {
        val width = 271
        val height = 311
        val random = Random(20261029L)
        val original = IntArray(width*height) { random.nextInt() }
        val cases = listOf(
            ImageAdjustments() to CropSettings(),
            ImageAdjustments(8f,18f,1.35f,5f,245f,112f) to CropSettings(7f,13f,32f*100/height,31f*100/height),
            ImageAdjustments(sharpness=67f) to CropSettings(),
            ImageAdjustments(-6f,100f,2.5f,25f,200f,200f,100f) to CropSettings(7f,13f,5f,7f)
        )
        for ((adjustment,cropSettings) in cases) {
            val crop = editorCropPixels(width,height,cropSettings)
            val expected = legacyWholeImage(original,width,height,adjustment)
            val output = EditorExportRows(width,height,crop,adjustment) { y,row ->
                original.copyInto(row,startIndex=y*width,endIndex=(y+1)*width)
            }
            val actual = IntArray(output.width)
            for (y in 0 until output.height) {
                output.readArgbRow(y,actual)
                val start = (crop.top+y)*width+crop.left
                assertArrayEquals("row=$y settings=$adjustment", expected.copyOfRange(start,start+output.width),actual)
            }
            assertTrue(output.metrics().averageBrightness in 0f..255f)
        }
    }

    @Test fun cachedColorTransformPreservesEveryChannelValueAndClamping() {
        val cases = listOf(ImageAdjustments(), ImageAdjustments(8f,18f,1.35f,5f,245f,112f),
            ImageAdjustments(-100f,-100f,.5f,100f,155f,0f),
            ImageAdjustments(100f,100f,2.5f,-20f,300f,200f))
        for (settings in cases) {
            val transform = EditorColorTransform(settings)
            for (value in 0..255) {
                val color = ((value xor 0x7F) shl 24) or (value shl 16) or
                    ((255-value) shl 8) or (value xor 0x5A)
                assertEquals(legacyColor(color,settings),transform.apply(color))
            }
        }
    }

    @Test fun statisticsComeFromAllCroppedOutputPixelsAndIgnoreDestinationTail() {
        val crop = editorCropPixels(400,200,CropSettings(10f,10f))
        val source = EditorExportRows(400,200,crop,ImageAdjustments()) { _,row ->
            for (x in row.indices) row[x] = if (x<200) 0xFF000000.toInt() else -1
        }
        assertThrows(IllegalStateException::class.java) { source.metrics() }
        val row = IntArray(source.width+3) { 123456 }
        for (y in 0 until source.height) source.readArgbRow(y,row)
        assertEquals(123456,row.last())
        val metrics = source.metrics()
        assertEquals(127.5f,metrics.averageBrightness,0f)
        assertEquals(50f,metrics.shadowPercent,0f)
        assertEquals(50f,metrics.highlightPercent,0f)
        assertEquals(50f,metrics.clippedBlackPercent,0f)
        assertEquals(50f,metrics.clippedWhitePercent,0f)
        assertEquals(1f,metrics.histogram.first(),0f)
        assertEquals(1f,metrics.histogram.last(),0f)
        assertTrue(metrics.histogram.subList(1,63).all { it == 0f })
    }

    @Test fun veryTallInputUsesOnlyThreeSourceRowBuffers() {
        val buffers = Collections.newSetFromMap(IdentityHashMap<IntArray,Boolean>())
        var reads = 0
        val source = EditorExportRows(4096,1_000_000,CropPixels(0,0,0,0,4096,1_000_000),
            ImageAdjustments(sharpness=50f)) { y,row ->
            assertEquals(4096,row.size)
            buffers.add(row)
            reads++
            row.fill(0xFF102030.toInt()+y)
        }
        val row = IntArray(source.width)
        for (y in 0 until 100) source.readArgbRow(y,row)
        assertTrue(buffers.size<=3)
        assertEquals(102,reads)
    }

    @Test fun cropUsesOriginalPixelDimensionsAndRejectsTooSmallOutput() {
        val crop = editorCropPixels(4096,3073,CropSettings(3.3f,6.7f,9.1f,4.5f))
        assertEquals(CropPixels(135,274,280,138,3687,2655),crop)
        assertThrows(IllegalArgumentException::class.java) {
            editorCropPixels(200,200,CropSettings(30f,30f,30f,30f))
        }
    }

    @Test fun invalidReadDoesNotConsumeSourceRows() {
        var reads = 0
        val source = EditorExportRows(240,240,CropPixels(0,0,0,0,240,240),ImageAdjustments()) { _,_ -> reads++ }
        assertThrows(IllegalArgumentException::class.java) { source.readArgbRow(1,IntArray(240)) }
        assertThrows(IllegalArgumentException::class.java) { source.readArgbRow(0,IntArray(239)) }
        assertEquals(0,reads)
    }

    @Test fun memoryFailureDuringStreamingRemovesOnlyOwnUnfinishedFile() {
        verifyFailureCleanup(OutOfMemoryError("synthetic_editor_row_failure"))
    }

    @Test fun cancellationDuringStreamingIsPreservedAndCleansTemporaryOutput() {
        verifyFailureCleanup(CancellationException("synthetic_editor_cancel"))
    }

    private fun verifyFailureCleanup(failure: Throwable) {
        val existing = File(temporary.root,"existing.png").apply { writeBytes(byteArrayOf(1,2,3)) }
        val target = File(temporary.root,"own.png")
        val pending = File(temporary.root,"own.png.tmp")
        val source = EditorExportRows(240,240,CropPixels(0,0,0,0,240,240),ImageAdjustments()) { y,row ->
            if (y==37) throw failure
            row.fill(-1)
        }
        val caught = assertThrows(failure.javaClass) { CrashSafePngFilePublisher.publish(source,target,pending) }
        assertSame(failure,caught)
        assertFalse(target.exists())
        assertFalse(pending.exists())
        assertArrayEquals(byteArrayOf(1,2,3),existing.readBytes())
    }

    // Frozen pre-streaming formulas; the independent full-frame reference crops only after sharpening.
    private fun legacyWholeImage(input: IntArray, width: Int, height: Int, settings: ImageAdjustments): IntArray {
        val toned = IntArray(input.size) { legacyColor(input[it],settings) }
        val sharpness = settings.sharpness.coerceIn(0f,100f)/100f*1.6f
        if (sharpness==0f) return toned
        return IntArray(toned.size) { i ->
            val x = i%width;val y = i/width
            val c=toned[i];val l=toned[y*width+(x-1).coerceAtLeast(0)]
            val r=toned[y*width+(x+1).coerceAtMost(width-1)]
            val u=toned[(y-1).coerceAtLeast(0)*width+x];val d=toned[(y+1).coerceAtMost(height-1)*width+x]
            fun channel(shift: Int): Int {
                val center=c ushr shift and 255
                val detail=center-((l ushr shift and 255)+(r ushr shift and 255)+
                    (u ushr shift and 255)+(d ushr shift and 255))/4f
                return if (abs(detail)<1.5f) center else (center+detail*sharpness).roundToInt().coerceIn(0,255)
            }
            0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }

    private fun legacyColor(color: Int, settings: ImageAdjustments): Int {
        val black=settings.blackPoint.coerceIn(0f,100f)
        val white=settings.whitePoint.coerceIn(155f,255f).coerceAtLeast(black+1f)
        fun channel(value: Int): Int {
            var v=((value-black)/(white-black)).coerceIn(0f,1f).toDouble()
                .pow(1.0/settings.gamma.coerceIn(.5f,2.5f)).toFloat()
            v+=settings.brightness.coerceIn(-100f,100f)/255f
            v=(v-.5f)*(1f+settings.contrast.coerceIn(-100f,100f)/100f)+.5f
            return (v.coerceIn(0f,1f)*255f).toInt().coerceIn(0,255)
        }
        val r=channel(color ushr 16 and 255);val g=channel(color ushr 8 and 255);val b=channel(color and 255)
        val gray=r*.299f+g*.587f+b*.114f;val saturation=settings.saturation.coerceIn(0f,200f)/100f
        return 0xFF000000.toInt() or ((gray+(r-gray)*saturation).toInt().coerceIn(0,255) shl 16) or
            ((gray+(g-gray)*saturation).toInt().coerceIn(0,255) shl 8) or
            (gray+(b-gray)*saturation).toInt().coerceIn(0,255)
    }
}
