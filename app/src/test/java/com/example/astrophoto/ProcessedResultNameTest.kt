package com.example.astrophoto

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedResultNameTest {
    private val timestamp = Instant.parse("2024-01-02T03:04:05Z").toEpochMilli()

    @Test
    fun averageUsesCurrentPrefix() {
        assertName(ProcessedOutputType.AVERAGE, "Stacked_20240102_030405.jpg")
    }

    @Test
    fun alignedAverageUsesCurrentPrefix() {
        assertName(
            ProcessedOutputType.AVERAGE_ALIGNED,
            "StackedAligned_20240102_030405.jpg"
        )
    }

    @Test
    fun darkAverageUsesCurrentPrefix() {
        assertName(
            ProcessedOutputType.AVERAGE_DARK,
            "StackedDark_20240102_030405.jpg"
        )
    }

    @Test
    fun alignedDarkAverageUsesCurrentPrefix() {
        assertName(
            ProcessedOutputType.AVERAGE_DARK_ALIGNED,
            "StackedDarkAligned_20240102_030405.jpg"
        )
    }

    @Test
    fun masterDarkUsesCurrentPrefix() {
        assertName(ProcessedOutputType.MASTER_DARK, "MasterDark_20240102_030405.jpg")
    }

    @Test
    fun medianUsesCurrentPrefixes() {
        assertName(ProcessedOutputType.MEDIAN, "Median_20240102_030405.jpg")
        assertName(
            ProcessedOutputType.MEDIAN_ALIGNED,
            "MedianAligned_20240102_030405.jpg"
        )
    }

    @Test
    fun sigmaUsesCurrentPrefixes() {
        assertName(ProcessedOutputType.SIGMA, "Sigma_20240102_030405.jpg")
        assertName(
            ProcessedOutputType.SIGMA_ALIGNED,
            "SigmaAligned_20240102_030405.jpg"
        )
    }

    @Test
    fun timestampUsesExactRequiredFormat() {
        val name = buildProcessedResultBaseName(
            ProcessedOutputType.AVERAGE,
            timestamp,
            ZoneOffset.UTC
        )

        assertTrue(name.contains("_20240102_030405."))
    }

    @Test
    fun generatedNameUsesJpgExtension() {
        val name = buildProcessedResultBaseName(
            ProcessedOutputType.MEDIAN,
            timestamp,
            ZoneOffset.UTC
        )

        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun noCollisionReturnsBaseName() {
        val baseName = "Stacked_20240102_030405.jpg"

        val result = findUniqueProcessedResultName(baseName) { false }

        assertEquals(baseName, result)
    }

    @Test
    fun firstCollisionReturnsSuffixOne() {
        val baseName = "Stacked_20240102_030405.jpg"

        val result = findUniqueProcessedResultName(baseName) { it == baseName }

        assertEquals("Stacked_20240102_030405_01.jpg", result)
    }

    @Test
    fun multipleCollisionsIncrementSuffix() {
        val existing = setOf(
            "Stacked_20240102_030405.jpg",
            "Stacked_20240102_030405_01.jpg",
            "Stacked_20240102_030405_02.jpg"
        )

        val result = findUniqueProcessedResultName("Stacked_20240102_030405.jpg") {
            it in existing
        }

        assertEquals("Stacked_20240102_030405_03.jpg", result)
    }

    @Test
    fun suffixFormattingIsDeterministic() {
        val existing = buildSet {
            add("Median_20240102_030405.jpg")
            for (suffix in 1..9) {
                add("Median_20240102_030405_${suffix.toString().padStart(2, '0')}.jpg")
            }
        }

        val result = findUniqueProcessedResultName("Median_20240102_030405.jpg") {
            it in existing
        }

        assertEquals("Median_20240102_030405_10.jpg", result)
    }

    @Test
    fun existingNameIsNeverReturned() {
        val existing = setOf(
            "Sigma_20240102_030405.jpg",
            "Sigma_20240102_030405_01.jpg"
        )

        val result = findUniqueProcessedResultName("Sigma_20240102_030405.jpg") {
            it in existing
        }

        assertFalse(result in existing)
        assertEquals("Sigma_20240102_030405_02.jpg", result)
    }

    @Test
    fun collisionSearchUsesBoundedAttempts() {
        val checked = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            findUniqueProcessedResultName(
                baseName = "Stacked_20240102_030405.jpg",
                maxAttempts = 3
            ) {
                checked += it
                true
            }
        }

        assertEquals(
            listOf(
                "Stacked_20240102_030405.jpg",
                "Stacked_20240102_030405_01.jpg",
                "Stacked_20240102_030405_02.jpg"
            ),
            checked
        )
    }

    @Test
    fun collisionExhaustionHasClearFailure() {
        val error = assertThrows(IllegalStateException::class.java) {
            findUniqueProcessedResultName(
                baseName = "MasterDark_20240102_030405.jpg",
                maxAttempts = 2
            ) { true }
        }

        assertTrue(error.message.orEmpty().contains("2 попыток"))
    }

    @Test
    fun outputIsStableForSameInput() {
        val first = buildProcessedResultBaseName(
            ProcessedOutputType.SIGMA,
            timestamp,
            ZoneOffset.UTC
        )
        val second = buildProcessedResultBaseName(
            ProcessedOutputType.SIGMA,
            timestamp,
            ZoneOffset.UTC
        )

        assertEquals(first, second)
    }

    @Test
    fun providedTimestampIsUsedInsteadOfCurrentTime() {
        val name = buildProcessedResultBaseName(
            ProcessedOutputType.AVERAGE,
            timestampMillis = 0L,
            zoneId = ZoneOffset.UTC
        )

        assertEquals("Stacked_19700101_000000.jpg", name)
    }

    private fun assertName(type: ProcessedOutputType, expected: String) {
        assertEquals(
            expected,
            buildProcessedResultBaseName(type, timestamp, ZoneOffset.UTC)
        )
    }
}
