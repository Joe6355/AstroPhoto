package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.AppSpecificProcessingReportStore
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResultProcessingSummaryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun summary(type: String = "PROCESSED", enhanced: String = "REJECTED", outputEnhanced: Boolean = false,
        fallback: Boolean = false) = ResultProcessingSummary(10, 4, "frame009.jpg", type, outputEnhanced,
        fallback, "mask_shaped_background_lift", emptyList(), enhanced,
        listOf("confirmed_star_lost", "confirmed_star_weakened"))
    private fun frame(name: String, time: Long, category: SessionFrameCategory = SessionFrameCategory.LIGHTS_JPEG,
        key: String = name) = SessionFrame(key, name, category, 1, time, name, null, null)

    @Test fun realSecondSeriesReasonsCoverExactlyTheSixExcludedFrames() {
        val reasons = List(5) { "centroid_median_residual_high" } + "sequence_agreement_too_low"
        val counts = frameExclusions(10, 10, 4, reasons)
        assertEquals(listOf(5, 1), counts.map { it.count })
        assertEquals("Не удалось точно совместить звёзды", counts.first().description)
        assertEquals(6, counts.sumOf { it.count })
    }
    @Test fun firstSeriesReasonsGroupWithoutLosingSpatialFailure() {
        val reasons = listOf("insufficient_centroid_spatial_sectors", "Insufficient robust local inliers",
            "Insufficient robust local inliers", "Insufficient local correspondences near sequence prediction") +
            List(2) { "insufficient_stellar_centroid_matches" } + List(12) { "centroid_median_residual_high" }
        val counts = frameExclusions(30, 30, 12, reasons)
        assertEquals(listOf(12, 5, 1), counts.map { it.count })
        assertEquals(18, counts.sumOf { it.count })
        assertTrue(counts.last().description.contains("распределены"))
    }
    @Test fun preliminaryAndUnknownFailuresKeepExactTotal() {
        val counts = frameExclusions(20, 15, 10, listOf("centroid_median_residual_high", null))
        assertEquals(10, counts.sumOf { it.count })
        assertEquals(5, counts.single { it.description == "Не дошли до выравнивания" }.count)
        assertEquals(4, counts.filter { it.description == "Причина исключения не указана" }.sumOf { it.count })
    }
    @Test fun manuallyApprovedOldRunDoesNotInventDetailedCauses() {
        val counts = frameExclusions(10, 10, 8, List(5) { "some_rejection" })
        assertEquals(listOf(FrameExclusionCount("Причина исключения не указана", 2)), counts)
    }
    @Test fun allAcceptedFramesHaveNoExclusionReasons() {
        assertTrue(frameExclusions(10, 10, 10, emptyList()).isEmpty())
    }
    @Test fun unfamiliarReasonIsNotExposedAsAnEnglishImplementationCode() {
        assertEquals("Другие причины отбора", frameReasonDescription("NEW_INTERNAL_REASON_v99"))
        assertEquals("не пройдены проверки качества", qualityReasonDescription("new_quality_check"))
    }
    @Test fun referenceFallbackIsNeverDescribedAsSuccessfulStackOutput() {
        val result = summary(type = "REFERENCE", fallback = true)
        assertTrue(result.savedResultText.startsWith("Сохранён только опорный кадр"))
        assertTrue(result.frameCountText.startsWith("В сложении прошли"))
        assertTrue(result.fallbackText!!.contains("пятна фона"))
    }
    @Test fun rejectedEnhancedExplainsBothStarReasonsAndKeepsPrimary() {
        val text = summary().enhancedText!!
        assertTrue(text.contains("звёзд пропала"))
        assertTrue(text.contains("звёзды стали слабее"))
        assertTrue(text.endsWith("Оставлен основной результат."))
    }
    @Test fun savedEnhancedAndCleanStackAreDistinguished() {
        assertTrue(summary(type = "CLEAN_STACK").savedResultText.contains("без дополнительного проявления"))
        assertTrue(summary(enhanced = "SAVED", outputEnhanced = true).savedResultText.startsWith("Сохранён Enhanced"))
        assertNull(summary(enhanced = "SAVED", outputEnhanced = true).enhancedText)
    }
    @Test fun preferredReferenceIsNinthAndNotFirstOrDarkFrame() {
        val first = frame("frame001.jpg", 1)
        val ninth = frame("frame009.jpg", 9)
        val dark = frame("frame009.jpg", 0, SessionFrameCategory.DARKS_JPEG, "dark")
        assertEquals(ResultComparisonReference(ninth, true), resultComparisonReference(summary(), listOf(dark, ninth, first)))
    }
    @Test fun absentReportUsesChronologicalFirstLight() {
        val first = frame("first.jpg", 1)
        assertEquals(ResultComparisonReference(first, false), resultComparisonReference(null,
            listOf(frame("later.jpg", 20), first, frame("dark.jpg", 0, SessionFrameCategory.DARKS_JPEG))))
    }
    @Test fun missingOrAmbiguousReferenceFallsBackExplicitly() {
        val first = frame("first.jpg", 1)
        assertEquals(ResultComparisonReference(first, false), resultComparisonReference(summary(), listOf(first)))
        assertEquals(ResultComparisonReference(first, false), resultComparisonReference(summary(), listOf(first,
            frame("frame009.jpg", 9), frame("frame009.jpg", 10, key = "duplicate"))))
    }
    @Test fun noLightDoesNotChooseACroppedOrDarkImage() {
        assertNull(resultComparisonReference(summary(), listOf(frame("frame009.jpg", 0, SessionFrameCategory.CROPPED_JPEG))))
    }
    @Test fun privateReportLookupPreservesImageIdentityAndSessionBoundary() {
        val store = AppSpecificProcessingReportStore(temporary.root, true)
        val written = store.write("session-one", "DeepSky.png", "{}")
        assertEquals(written.file, store.findForImage("session-one", "DeepSky.png")!!.file)
        assertNull(store.findForImage("session-two", "DeepSky.png"))
        assertNull(store.findForImage("session-one", "Renamed.png"))
        written.file.resolveSibling(written.file.name + ".image").writeText("Other.png")
        assertNull(store.findForImage("session-one", "DeepSky.png"))
    }
    @Test fun boundedTextPreservesUnicodeWithoutWritingTheInput() {
        val text = "{\"selectedReference\":\"Звёзды_027.jpg\"}"
        assertEquals(text, boundedReportText(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedReportsAreStoppedBeforeJsonParsing() {
        boundedReportText(ByteArrayInputStream(ByteArray(2 * 1024 * 1024 + 1) { 65 }))
    }
}
