package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.composition.ReferenceStarRestorationPolicy
import com.joe6355.astrophoto.processing.jpeg.v2.composition.ReferenceStarSignalPreserver
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionStage
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt

class SelectiveReferenceStarPreservationTest {
    @get:Rule val folder = TemporaryFolder()
    private val stars = List(4) { star(20f + it * 40, 20f) }
    private val validator = ReferenceStarRetentionValidator()
    private val policy = ReferenceStarRestorationPolicy()

    @Test fun healthyStackKeepsItsNoiseReductionInsteadOfCopyingReferencePatches() {
        val reference = render(noise = 6)
        val stacked = render(noise = 1, amplitude = 205f)
        val measured = validator.validate(reference, stacked, stars)
        assertTrue(measured.accepted)
        val plan = policy.plan(stars, measured.sources)
        assertTrue(plan.sourceIndices.isEmpty())
        val result = ReferenceStarSignalPreserver().preserve(stacked, reference, plan.starsToRestore)
        assertArrayEquals(stacked.pixels, result.image.pixels)
        assertFalse(reference.pixels.contentEquals(result.image.pixels))
        stars.forEach { assertFalse(plan.intersectsPatch(it, reference.width, reference.height)) }
    }

    @Test fun missingSourceIsRestoredWhileHealthyNeighbourhoodsStayBitExact() {
        val reference = render(noise = 6)
        val stacked = render(noise = 1, missing = 1)
        val plan = policy.plan(stars, validator.validate(reference, stacked, stars).sources)
        assertEquals(listOf(1), plan.sourceIndices)
        val result = ReferenceStarSignalPreserver().preserve(stacked, reference, plan.starsToRestore).image
        assertEquals(reference.pixelAt(60, 20), result.pixelAt(60, 20))
        for (y in 10..30) for (x in 10..30) assertEquals(stacked.pixelAt(x, y), result.pixelAt(x, y))
        assertTrue(validator.validate(reference, result, stars).accepted)
        assertFalse(plan.intersectsPatch(stars[0], reference.width, reference.height))
        assertTrue(plan.intersectsPatch(stars[1], reference.width, reference.height))
    }

    @Test fun perSourceChecksAreAtLeastAsStrictAsExistingAggregateLimits() {
        val reference = render()
        val base = validator.validate(reference, reference, stars).sources.first()
        val bad = listOf(
            base.copy(afterContrast = base.beforeContrast * .84f),
            base.copy(afterWidth = base.beforeWidth * 1.21f),
            base.copy(centroidShift = .51f),
            base.copy(retained = false),
            base.copy(afterContrast = Float.NaN),
            base.copy(beforeWidth = Float.POSITIVE_INFINITY)
        )
        for (source in bad) {
            assertEquals(listOf(0), policy.plan(listOf(stars[0]), listOf(source)).sourceIndices)
        }
        val boundary = base.copy(afterContrast = base.beforeContrast * .85f,
            afterWidth = base.beforeWidth * 1.2f, centroidShift = .5f)
        assertTrue(policy.plan(listOf(stars[0]), listOf(boundary)).sourceIndices.isEmpty())
    }

    @Test fun unmeasurableSourcesKeepConservativeReferenceRestoration() {
        assertEquals(listOf(0, 1, 2, 3), policy.plan(stars, emptyList()).sourceIndices)
    }

    @Test fun lineageIncludesOverlappingRepairPatchesButNotDistantSources() {
        val list = listOf(star(20f, 20f), star(31f, 20f), star(60f, 20f))
        val plan = policy.plan(list, emptyList()).copy(sourceIndices = listOf(0), starsToRestore = listOf(list[0]))
        assertTrue(plan.intersectsPatch(list[0], 100, 50))
        assertTrue(plan.intersectsPatch(list[1], 100, 50))
        assertFalse(plan.intersectsPatch(list[2], 100, 50))
    }

    @Test fun propagatedLineageDoesNotMarkEverySourceAsRestored() {
        val reference = render()
        val measurement = validator.validate(reference, reference, stars)
        val stage = ReferenceStarRetentionStage("test", measurement.metrics, measurement.sources)
        val evidence = stars.indices.associateWith {
            com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionEvidence(
                false, false, 1f, 7, 1f)
        }
        val result = stage.withReferenceSourceEvidence(evidence, setOf(1))
        assertEquals(listOf(false, true, false, false), result.sources.map { it.intersectsStarPreservationPatch })
    }

    @Test fun rgb16StorageKeepsUntouchedSubByteSignalAndRestoresOnlySelectedSource() {
        val width = 161
        val height = 41
        val first = LinearRgb16.pack(.0200f, .0200f, .0200f)
        val second = LinearRgb16.pack(.0201f, .0200f, .0200f)
        assertEquals(LinearRgb16.toArgb(first), LinearRgb16.toArgb(second))
        val raw = LongArray(width * height) { if (it % 2 == 0) first else second }
        val reference = LongArray(raw.size) { LinearRgb16.pack(.1f, .2f, .3f) }
        fun file(name: String, pixels: LongArray) = FileBackedImageWriter(File(folder.root, name),
            width, height, FileBackedPixelFormat.LINEAR_RGB_16).use { writer ->
            for (y in 0 until height) writer.writeLinearRow(y, pixels, y * width)
            writer.finish()
        }
        val stackedImage = file("stacked.rgb16", raw)
        val referenceImage = file("reference.rgb16", reference)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = ReferenceStarSignalPreserver().preserve(stackedImage, referenceImage,
                listOf(stars[1]), ResultCandidateStore(files))
            FileBackedImageReader(result.image).use { reader ->
                for (y in 10..30) for (x in 10..30) assertEquals(raw[y * width + x], reader.linearRgbAt(x, y))
                assertEquals(reference[20 * width + 60], reader.linearRgbAt(60, 20))
            }
        }
    }

    private fun star(x: Float, y: Float) = DetectedStar(x, y, 1000f, 20f, 210f, 2.4f, 0f, 1f)

    private fun render(noise: Int = 0, amplitude: Float = 210f, missing: Int = -1): ArgbPixelImage {
        val width = 161
        val height = 41
        return ArgbPixelImage(width, height, IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val signal = stars.mapIndexed { index, star ->
                if (index == missing) 0f else amplitude * exp(-((x-star.x)*(x-star.x) +
                    (y-star.y)*(y-star.y)) / (2 * 1.35f * 1.35f))
            }.sum()
            val random = if (noise == 0) 0 else ((x * 73 + y * 131 + x * y * 7) % (2 * noise + 1)) - noise
            val value = (20 + random + signal.roundToInt()).coerceIn(0, 255)
            0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        })
    }
}
