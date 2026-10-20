package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.linearLuminance
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtectedFaintStarOverlapTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun duplicateDetectionsHaveTheSameOutputAsOneDetection() = withFixture { input, alpha, store ->
        val star = star(20f, 1f)
        val enhancer = ProtectedFaintStarEnhancer()
        val once = enhancer.apply(input, alpha, listOf(star), store)
        val repeated = enhancer.apply(input, alpha, List(12) { star }, store)
        assertTrue(once.metrics.changedPixelCount > 0)
        assertEquals(fileBackedPixelHash(once.image), fileBackedPixelHash(repeated.image))
    }

    @Test fun overlappingDetectionsAreIndependentOfTheirOrder() = withFixture { input, alpha, store ->
        val stars = listOf(star(20f, 1f), star(22f, .65f))
        val enhancer = ProtectedFaintStarEnhancer()
        val forward = enhancer.apply(input, alpha, stars, store)
        val reverse = enhancer.apply(input, alpha, stars.reversed(), store)
        assertTrue(forward.metrics.changedPixelCount > 0)
        assertEquals(fileBackedPixelHash(forward.image), fileBackedPixelHash(reverse.image))
    }

    @Test fun repeatedOverlapCannotExceedOneBoundedResidualIncrease() = withFixture { input, alpha, store ->
        val originalHash = fileBackedPixelHash(input)
        val result = ProtectedFaintStarEnhancer().apply(input, alpha, List(12) { star(20f, 1f) }, store)
        FileBackedImageReader(input).use { before -> FileBackedImageReader(result.image).use { after ->
            for (y in 0 until input.height) for (x in 0 until input.width) {
                val a = linearLuminance(before.linearRgbAt(x, y))
                val b = linearLuminance(after.linearRgbAt(x, y))
                assertTrue("Unbounded gain at $x,$y: $a -> $b", b <= a * (1f + ProtectedFaintStarEnhancer.PRODUCTION_STRENGTH) + 2f / 65535f)
            }
        } }
        assertEquals(originalHash, fileBackedPixelHash(input))
    }

    private fun star(x: Float, confidence: Float) = DetectedStar(x, 20f, 1f, .002f, .01f, 2.5f, .1f, confidence)

    private fun withFixture(block: (FileBackedImage, FileBackedFloatPlane, ResultCandidateStore) -> Unit) {
        TemporaryPipelineFiles.create(temporary.root).use { files ->
            val store = ResultCandidateStore(files)
            val size = 48
            val input = store.createTemporaryWriter("input", size, size).use { writer ->
                repeat(size) { y -> writer.writeLinearRow(y, LongArray(size) { x ->
                    val peak = 450.0 * exp(-((x - 20.0) * (x - 20.0) + (y - 20.0) * (y - 20.0)) / 3.0)
                    val value = (100.0 + x * 2.0 + peak).toFloat() / 65535f
                    LinearRgb16.pack(value, value, value)
                }) }; writer.finish()
            }
            val alpha = store.createFloatPlaneWriter("sky", size, size).use { writer ->
                repeat(size) { writer.writeRow(it, FloatArray(size) { 1f }) }; writer.finish()
            }
            block(input, alpha, store)
        }
    }
}
