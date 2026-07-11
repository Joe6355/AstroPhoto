package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualImageAlignmentTest {
    private val reference = SyntheticImageTestData.texture()

    @Test
    fun identicalImagesReturnZeroShift() {
        assertEquals(AlignmentShift.Zero, alignArgbImages(reference, reference, safeMode = true))
    }

    @Test
    fun horizontalAndVerticalTranslationsHaveCorrectSigns() {
        listOf(5 to 0, -6 to 0, 0 to 4, 0 to -7).forEach { (right, down) ->
            val result = alignArgbImages(
                reference,
                SyntheticImageTestData.translated(reference, right, down),
                safeMode = true
            )
            assertEquals("X shift for $right,$down", right, result.dx)
            assertEquals("Y shift for $right,$down", down, result.dy)
        }
    }

    @Test
    fun combinedTranslationsHaveCorrectSigns() {
        listOf(6 to 5, -5 to -4, 7 to -3, -4 to 6).forEach { (right, down) ->
            val result = alignArgbImages(
                reference,
                SyntheticImageTestData.translated(reference, right, down),
                safeMode = true
            )
            assertEquals(right, result.dx)
            assertEquals(down, result.dy)
        }
    }

    @Test
    fun returnedShiftRestoresTheSharedOverlap() {
        val candidate = SyntheticImageTestData.translated(reference, 6, -5)
        val shift = alignArgbImages(reference, candidate, safeMode = true)
        val restored = applyImageShift(candidate, shift.dx, shift.dy)
        val region = shiftedCopyRegion(reference.width, reference.height, 0, reference.height, shift.dx, shift.dy)
        assertNotNull(region)
        region!!
        for (y in region.destinationY until region.destinationY + region.height) {
            for (x in region.destinationX until region.destinationX + region.width) {
                assertEquals(reference.pixelAt(x, y), restored.pixelAt(x, y))
            }
        }
    }

    @Test
    fun maximumAllowedShiftIsFound() {
        val result = alignArgbImages(
            reference,
            SyntheticImageTestData.translated(reference, 12, -12),
            safeMode = true,
            maxShiftPx = 12
        )
        assertEquals(12, result.dx)
        assertEquals(-12, result.dy)
    }

    @Test
    fun shiftOutsideSearchRangeStaysBounded() {
        val result = alignArgbImages(
            reference,
            SyntheticImageTestData.translated(reference, 12, 0),
            safeMode = false,
            maxShiftPx = 5
        )
        assertTrue(result.dx in -5..5)
        assertTrue(result.dy in -5..5)
    }

    @Test
    fun uniformImagesDoNotProduceAlignment() {
        val image = SyntheticImageTestData.uniform()
        assertEquals(AlignmentShift.Zero, alignArgbImages(image, image, safeMode = false))
    }

    @Test
    fun noisyRelatedImageRemainsAlignable() {
        val candidate = SyntheticImageTestData.noisy(
            SyntheticImageTestData.translated(reference, -5, 4),
            seed = 8128,
            amplitude = 3
        )
        val result = alignArgbImages(reference, candidate, safeMode = true)
        assertEquals(-5, result.dx)
        assertEquals(4, result.dy)
    }

    @Test
    fun unrelatedImagesAreRejectedBySafeMode() {
        val unrelated = SyntheticImageTestData.noisy(
            SyntheticImageTestData.gradient(reference.width, reference.height),
            seed = 44,
            amplitude = 20
        )
        assertEquals(AlignmentShift.Zero, alignArgbImages(reference, unrelated, safeMode = true))
    }

    @Test
    fun safeModeAcceptsClearMatch() {
        val result = alignArgbImages(
            reference,
            SyntheticImageTestData.translated(reference, 4, 3),
            safeMode = true
        )
        assertFalse(result.isZero)
        assertTrue(result.confidence >= 0.02)
    }

    @Test
    fun aggressiveModeKeepsShiftThatSafeModeRejects() {
        val uncertain = AlignmentShift(dx = 3, dy = -2, score = 60.0, confidence = 0.01)
        assertEquals(AlignmentShift.Zero, selectManualAlignment(uncertain, safeMode = true))
        assertEquals(uncertain, selectManualAlignment(uncertain, safeMode = false))
    }

    @Test
    fun smallImagesDoNotCrashOrInventShift() {
        val image = SyntheticImageTestData.texture(3, 3)
        assertEquals(AlignmentShift.Zero, alignArgbImages(image, image, safeMode = true))
    }

    @Test
    fun shiftedCopyRegionUsesExclusiveBoundsWithoutOverflow() {
        val region = shiftedCopyRegion(10, 8, destinationTop = 2, destinationRows = 5, dx = 3, dy = -2)
        assertEquals(ShiftedCopyRegion(3, 0, 0, 2, 7, 5), region)
        assertNull(shiftedCopyRegion(10, 8, 0, 8, dx = 10, dy = 0))
    }

    @Test
    fun shiftApplicationFillsOnlyNonOverlappingEdges() {
        val image = SyntheticImageTestData.texture(8, 6)
        val output = applyImageShift(image, dx = 2, dy = 1, fillColor = 123)
        assertEquals(image.pixelAt(2, 1), output.pixelAt(0, 0))
        assertEquals(123, output.pixelAt(7, 0))
        assertEquals(123, output.pixelAt(0, 5))
    }

    @Test
    fun alignmentIsDeterministic() {
        val candidate = SyntheticImageTestData.translated(reference, -7, 5)
        val first = alignArgbImages(reference, candidate, safeMode = true)
        repeat(5) { assertEquals(first, alignArgbImages(reference, candidate, safeMode = true)) }
    }

    @Test
    fun alignmentDoesNotModifyInputs() {
        val candidate = SyntheticImageTestData.translated(reference, 3, 2)
        val referenceSnapshot = reference.pixels.copyOf()
        val candidateSnapshot = candidate.pixels.copyOf()
        alignArgbImages(reference, candidate, safeMode = true)
        assertArrayEquals(referenceSnapshot, reference.pixels)
        assertArrayEquals(candidateSnapshot, candidate.pixels)
    }
}
