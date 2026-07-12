package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarAlignmentTest {
    private val alignment = StarAlignment()
    private val referenceCoordinates = listOf(9 to 11, 18 to 31, 29 to 16, 41 to 39, 52 to 24)

    @Test
    fun identicalStarFieldsReturnAppliedZeroShift() {
        val result = match(referenceCoordinates, referenceCoordinates)
        assertTrue(result.applied)
        assertEquals(0, result.dx)
        assertEquals(0, result.dy)
    }

    @Test
    fun translatedStarFieldReturnsCandidateMinusReferenceShift() {
        val result = match(referenceCoordinates, translatedCoordinates(5, -4))
        assertTrue(result.applied)
        assertEquals(5, result.dx)
        assertEquals(-4, result.dy)
    }

    @Test
    fun starShiftSignMatchesPixelApplication() {
        val reference = starField(referenceCoordinates)
        val candidate = SyntheticImageTestData.translated(reference, right = 4, down = 3)
        val result = alignment.align(
            reference,
            candidate,
            AstroRoi.Full,
            StarDetectionSensitivity.MEDIUM,
            maxShiftPx = 10
        )
        assertEquals("STAR_SAFE", result.method)
        assertEquals(4, result.dx)
        assertEquals(3, result.dy)
        val restored = applyImageShift(candidate, result.dx, result.dy)
        assertEquals(reference.pixelAt(20, 20), restored.pixelAt(20, 20))
    }

    @Test
    fun inputOrderDoesNotChangeStarMatch() {
        val expected = match(referenceCoordinates, translatedCoordinates(-3, 4))
        val reordered = alignment.matchDetectedStars(
            detected(referenceCoordinates.reversed()),
            detected(translatedCoordinates(-3, 4).shuffled(kotlin.random.Random(7))),
            maxShiftPx = 10,
            aggressive = false
        )
        assertEquals(expected, reordered)
    }

    @Test
    fun oneMissingStarIsTolerated() {
        val result = match(referenceCoordinates, translatedCoordinates(3, 2).dropLast(1))
        assertTrue(result.applied)
        assertEquals(4, result.matchedStars)
    }

    @Test
    fun additionalFalseStarsDoNotDominateValidTranslation() {
        val candidates = translatedCoordinates(2, -3) + listOf(6 to 50, 58 to 56, 34 to 7)
        val result = match(referenceCoordinates, candidates)
        assertTrue(result.applied)
        assertEquals(2, result.dx)
        assertEquals(-3, result.dy)
    }

    @Test
    fun oneCandidateCannotBeMatchedToSeveralReferenceStars() {
        val refs = listOf(10 to 10, 11 to 10, 10 to 11, 11 to 11)
        val candidates = listOf(15 to 15, 40 to 40, 50 to 50)
        val result = match(refs, candidates, maxShift = 10)
        assertFalse(result.applied)
        assertEquals(1, result.matchedStars)
    }

    @Test
    fun insufficientStarsProduceClearFailure() {
        val result = match(listOf(10 to 10, 20 to 20), listOf(13 to 12, 23 to 22))
        assertFalse(result.applied)
        assertEquals(0, result.matchedStars)
        assertNotNull(result.warning)
    }

    @Test
    fun unrelatedStarFieldsAreRejected() {
        val result = alignment.matchDetectedStars(
            detected(listOf(8 to 8, 17 to 29, 33 to 12, 51 to 43)),
            detected(listOf(7 to 50, 22 to 9, 39 to 34, 56 to 17)),
            maxShiftPx = 8,
            aggressive = false
        )
        assertEquals(null, result)
    }

    @Test
    fun clearMatchHasHighConfidence() {
        val result = match(referenceCoordinates, translatedCoordinates(4, 5))
        assertTrue(result.confidence > 0.9f)
    }

    @Test
    fun starMatchingIsDeterministic() {
        val first = match(referenceCoordinates, translatedCoordinates(-4, -2))
        repeat(5) { assertEquals(first, match(referenceCoordinates, translatedCoordinates(-4, -2))) }
    }

    @Test
    fun starMatchingDoesNotModifyLists() {
        val refs = detected(referenceCoordinates).toMutableList()
        val candidates = detected(translatedCoordinates(3, 3)).toMutableList()
        val refsSnapshot = refs.toList()
        val candidatesSnapshot = candidates.toList()
        alignment.matchDetectedStars(refs, candidates, 10, false)
        assertEquals(refsSnapshot, refs)
        assertEquals(candidatesSnapshot, candidates)
    }

    @Test
    fun actualProfilePathUsesStarAlignmentWhenStarsSucceed() {
        val reference = starField(referenceCoordinates)
        val candidate = SyntheticImageTestData.translated(reference, 3, -2)
        val result = alignment.align(
            reference, candidate, AstroRoi.Full, StarDetectionSensitivity.MEDIUM, 10, false
        )
        assertTrue(result.applied)
        assertEquals("STAR_SAFE", result.method)
        assertEquals(3 to -2, result.dx to result.dy)
    }

    @Test
    fun profilePathFallsBackToImageAlignmentWhenStarsFail() {
        val reference = lowContrastTexture()
        val candidate = SyntheticImageTestData.translated(reference, -4, 4, fill = 20)
        val result = alignment.align(
            reference, candidate, AstroRoi.Full, StarDetectionSensitivity.MEDIUM, 8, false
        )
        assertTrue(result.applied)
        assertEquals("IMAGE_SAFE", result.method)
        assertEquals(-4 to 4, result.dx to result.dy)
    }

    @Test
    fun uniformImagesMakeBothAlignmentMethodsFailSafely() {
        val image = SyntheticImageTestData.uniform(64, 64, 20)
        val result = alignment.align(
            image, image, AstroRoi.Full, StarDetectionSensitivity.MEDIUM, 8, false
        )
        assertFalse(result.applied)
        assertEquals(0 to 0, result.dx to result.dy)
        assertEquals("IMAGE_SAFE", result.method)
    }

    @Test
    fun fallbackDoesNotReturnArbitraryShiftForUnrelatedUniformImages() {
        val result = alignment.imageSafeAlignment(
            SyntheticImageTestData.uniform(64, 64, 20),
            SyntheticImageTestData.uniform(64, 64, 80),
            AstroRoi.Full,
            maxShiftPx = 8,
            aggressive = true
        )
        assertFalse(result.applied)
        assertEquals(0 to 0, result.dx to result.dy)
    }

    @Test
    fun fallbackRejectsUnrelatedLowContrastImages() {
        val result = alignment.imageSafeAlignment(
            lowContrastTexture(),
            unrelatedLowContrastTexture(),
            AstroRoi.Full,
            maxShiftPx = 8,
            aggressive = false
        )
        assertFalse(result.applied)
        assertEquals(0 to 0, result.dx to result.dy)
    }

    @Test
    fun profileAlignmentDoesNotModifyPixelInputs() {
        val reference = starField(referenceCoordinates)
        val candidate = SyntheticImageTestData.translated(reference, 2, 2)
        val referenceSnapshot = reference.pixels.copyOf()
        val candidateSnapshot = candidate.pixels.copyOf()
        alignment.align(reference, candidate, AstroRoi.Full, StarDetectionSensitivity.MEDIUM, 10)
        assertArrayEquals(referenceSnapshot, reference.pixels)
        assertArrayEquals(candidateSnapshot, candidate.pixels)
    }

    private fun match(
        refs: List<Pair<Int, Int>>,
        candidates: List<Pair<Int, Int>>,
        maxShift: Int = 10
    ): StarAlignmentResult = alignment.matchDetectedStars(
        detected(refs), detected(candidates), maxShift, aggressive = false
    )!!

    private fun translatedCoordinates(dx: Int, dy: Int) =
        referenceCoordinates.map { (x, y) -> x + dx to y + dy }

    private fun detected(coordinates: List<Pair<Int, Int>>) = coordinates.mapIndexed { index, (x, y) ->
        DetectedStar(x, y, 220 - index * 10, 100f, 1f, 0.9f)
    }

    private fun starField(coordinates: List<Pair<Int, Int>>) = SyntheticImageTestData.stars(
        width = 64,
        height = 64,
        points = coordinates.mapIndexed { index, (x, y) -> Triple(x, y, 220 - index * 15) }
    )

    private fun lowContrastTexture(width: Int = 72, height: Int = 64): ArgbPixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = SyntheticImageTestData.gray(20 + (x * 17 + y * 29 + x * y) % 41)
            }
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun unrelatedLowContrastTexture(width: Int = 72, height: Int = 64): ArgbPixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = SyntheticImageTestData.gray(
                    20 + (x * 11 + y * 23 + x * y * 3) % 41
                )
            }
        }
        return ArgbPixelImage(width, height, pixels)
    }
}
