package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EligibleFrameFilterTest {
    @Test
    fun emptyFrameListReturnsEmptyResult() {
        val result = selectEligibleLightFrames(emptyList(), FrameMarks(), false)

        assertTrue(result.isEmpty())
    }

    @Test
    fun unmarkedFramesAreIncludedInNormalMode() {
        val frames = listOf(light("first"), light("second"))

        val result = selectEligibleLightFrames(frames, FrameMarks(), false)

        assertEquals(frames, result)
    }

    @Test
    fun unmarkedFramesAreExcludedInFavoritesOnlyMode() {
        val result = selectEligibleLightFrames(
            listOf(light("first"), light("second")),
            FrameMarks(),
            true
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun badFrameIsExcluded() {
        val frame = light("bad")

        val result = selectEligibleLightFrames(
            listOf(frame),
            FrameMarks(bad = setOf(frame.key)),
            false
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun autoBadFrameIsExcluded() {
        val frame = light("auto-bad")

        val result = selectEligibleLightFrames(
            listOf(frame),
            FrameMarks(autoBad = setOf(frame.key)),
            false
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun favoriteFrameIsIncludedInNormalMode() {
        val frame = light("favorite")

        val result = selectEligibleLightFrames(
            listOf(frame),
            FrameMarks(favorite = setOf(frame.key)),
            false
        )

        assertEquals(listOf(frame), result)
    }

    @Test
    fun favoriteFrameIsIncludedInFavoritesOnlyMode() {
        val frame = light("favorite")

        val result = selectEligibleLightFrames(
            listOf(frame),
            FrameMarks(favorite = setOf(frame.key)),
            true
        )

        assertEquals(listOf(frame), result)
    }

    @Test
    fun favoriteBadFrameIsExcluded() {
        val frame = light("favorite-bad")
        val marks = FrameMarks(
            bad = setOf(frame.key),
            favorite = setOf(frame.key)
        )

        val result = selectEligibleLightFrames(listOf(frame), marks, true)

        assertTrue(result.isEmpty())
    }

    @Test
    fun favoriteAutoBadFrameIsExcluded() {
        val frame = light("favorite-auto-bad")
        val marks = FrameMarks(
            favorite = setOf(frame.key),
            autoBad = setOf(frame.key)
        )

        val result = selectEligibleLightFrames(listOf(frame), marks, true)

        assertTrue(result.isEmpty())
    }

    @Test
    fun mixedMarksApplyAllRules() {
        val plain = light("plain")
        val favorite = light("favorite")
        val bad = light("bad")
        val autoBad = light("auto-bad")
        val favoriteBad = light("favorite-bad")
        val favoriteAutoBad = light("favorite-auto-bad")
        val raw = frame("raw", SessionFrameCategory.LIGHTS_RAW)
        val frames = listOf(
            plain,
            favorite,
            bad,
            autoBad,
            favoriteBad,
            favoriteAutoBad,
            raw
        )
        val marks = FrameMarks(
            bad = setOf(bad.key, favoriteBad.key),
            favorite = setOf(favorite.key, favoriteBad.key, favoriteAutoBad.key),
            autoBad = setOf(autoBad.key, favoriteAutoBad.key)
        )

        assertEquals(
            listOf(plain, favorite),
            selectEligibleLightFrames(frames, marks, false)
        )
        assertEquals(
            listOf(favorite),
            selectEligibleLightFrames(frames, marks, true)
        )
    }

    @Test
    fun originalFrameOrderIsPreserved() {
        val third = light("third")
        val first = light("first")
        val second = light("second")
        val frames = listOf(third, first, second)

        val result = selectEligibleLightFrames(frames, FrameMarks(), false)

        assertEquals(frames, result)
    }

    @Test
    fun inputFrameListIsNotModified() {
        val frames = mutableListOf(light("first"), light("bad"), light("third"))
        val snapshot = frames.toList()

        selectEligibleLightFrames(
            frames,
            FrameMarks(bad = setOf("bad")),
            false
        )

        assertEquals(snapshot, frames)
    }

    @Test
    fun inputMarksAreNotModified() {
        val bad = mutableSetOf("bad")
        val favorite = mutableSetOf("favorite")
        val autoBad = mutableSetOf("auto-bad")
        val marks = FrameMarks(bad = bad, favorite = favorite, autoBad = autoBad)
        val expected = FrameMarks(bad.toSet(), favorite.toSet(), autoBad.toSet())

        selectEligibleLightFrames(
            listOf(light("bad"), light("favorite"), light("auto-bad")),
            marks,
            false
        )

        assertEquals(expected, marks)
    }

    @Test
    fun missingMarkEntryDoesNotCrashOrExcludeNormalFrame() {
        val frame = light("missing")
        val marks = FrameMarks(
            bad = setOf("another-bad"),
            favorite = setOf("another-favorite"),
            autoBad = setOf("another-auto-bad")
        )

        val result = selectEligibleLightFrames(listOf(frame), marks, false)

        assertEquals(listOf(frame), result)
    }

    @Test
    fun manualAndProfileInputsUseTheSameFilterRules() {
        val favorite = light("favorite")
        val bad = light("bad")
        val frames = listOf(favorite, bad)
        val marks = FrameMarks(
            bad = setOf(bad.key),
            favorite = setOf(favorite.key)
        )

        val manualInput = selectEligibleLightFrames(frames, marks, true)
        val profileInput = selectEligibleLightFrames(frames, marks, true)

        assertEquals(manualInput, profileInput)
        assertEquals(listOf(favorite), manualInput)
    }

    @Test
    fun duplicateFrameRecordsArePreserved() {
        val frame = light("duplicate")
        val frames = listOf(frame, frame)

        val result = selectEligibleLightFrames(frames, FrameMarks(), false)

        assertEquals(frames, result)
    }

    private fun light(key: String): SessionFrame =
        frame(key, SessionFrameCategory.LIGHTS_JPEG)

    private fun frame(
        key: String,
        category: SessionFrameCategory
    ): SessionFrame = SessionFrame(
        key = key,
        fileName = "$key.jpg",
        category = category,
        sizeBytes = 1L,
        createdAtMillis = 1L,
        displayPath = key,
        contentUri = null,
        filePath = null
    )
}
