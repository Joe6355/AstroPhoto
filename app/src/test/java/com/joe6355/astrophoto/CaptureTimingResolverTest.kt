package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import org.junit.Assert.*
import org.junit.Test

class CaptureTimingResolverTest {
    @Test fun preservesTheMeasuredPauseAcrossMidnightWithoutChangingFrameIndices() {
        val times = listOf("2026:08:12 23:58:18" to "091959",
            "2026:08:12 23:58:23" to "788019",
            "2026:08:13 00:01:02" to "563299",
            "2026:08:13 00:01:54" to "808336",
            "2026:08:13 00:04:39" to "178714")
        val indices = listOf(1, 2, 30, 31, 60)
        val samples = times.mapIndexed { index, (date, subsecond) ->
            CaptureTimingSample("f${indices[index]}", indices[index], ExifCaptureTimeParser.parse(date, subsecond))
        }
        val axis = CaptureTimingResolver.resolve(samples)
        assertEquals("EXIF_ORIGINAL", axis.source)
        val nominal = requireNotNull(axis.nominalIntervalMillis)
        assertEquals(5683.767857, nominal, 0.001)
        val pause = axis.normalizedTimeByFrameId.getValue("f31") - axis.normalizedTimeByFrameId.getValue("f30")
        assertEquals(52245.0 / nominal, pause.toDouble(), 0.00001)
        assertTrue(pause > 9f)
        assertEquals(indices, samples.map { it.captureIndex })
    }

    @Test fun missingMetadataUsesTheWholeOrdinalAxisInsteadOfMixingTimeScales() {
        val samples = samples().mapIndexed { index, sample ->
            if (index == 2) sample.copy(capturedAtMillis = null) else sample
        }
        val axis = CaptureTimingResolver.resolve(samples)
        assertEquals("CAPTURE_INDEX", axis.source)
        assertTrue(axis.normalizedTimeByFrameId.isEmpty())
        assertNull(axis.nominalIntervalMillis)
    }

    @Test fun duplicatedOrBackwardCaptureTimesDoNotProduceMotionCoordinates() {
        val values = samples()
        for (badTime in listOf(values[1].capturedAtMillis!!, values[1].capturedAtMillis!! - 5000L)) {
            assertTrue(CaptureTimingResolver.resolve(values.mapIndexed { index, value ->
                if (index == 2) value.copy(capturedAtMillis = badTime) else value
            }).normalizedTimeByFrameId.isEmpty())
        }
    }

    @Test fun rejectsAmbiguousFrameIdentitiesAndCaptureIndices() {
        val values = samples()
        assertTrue(CaptureTimingResolver.resolve(values + values.first()).normalizedTimeByFrameId.isEmpty())
        assertTrue(CaptureTimingResolver.resolve(values.mapIndexed { index, value ->
            if (index == 1) value.copy(frameId = "f0") else value
        }).normalizedTimeByFrameId.isEmpty())
    }

    @Test fun missingFrameNumbersRetainTheNominalPerFrameCadence() {
        val values = listOf(3, 4, 8, 9).map { CaptureTimingSample("f$it", it, 10000L + it * 5000L) }
        val axis = CaptureTimingResolver.resolve(values.reversed())
        assertEquals(5000.0, axis.nominalIntervalMillis!!, 0.0)
        assertEquals(values.associate { it.frameId to it.captureIndex.toFloat() }, axis.normalizedTimeByFrameId)
    }

    @Test fun epochMagnitudeDoesNotLoseSubsecondPrecision() {
        val first = CaptureTimingResolver.resolve(samples())
        val shifted = CaptureTimingResolver.resolve(samples().map {
            it.copy(capturedAtMillis = it.capturedAtMillis!! + 1_790_000_000_000L)
        })
        assertEquals(first, shifted)
        assertEquals(first, CaptureTimingResolver.resolve(samples().reversed()))
    }

    @Test fun parserKeepsFractionalMillisecondsAndOptionalUtcOffset() {
        val noOffset = ExifCaptureTimeParser.parse("2026:08:12 23:58:18", "091959")!!
        val offset = ExifCaptureTimeParser.parse("2026:08:12 23:58:18", "091959", "+05:00")!!
        assertEquals(18_000_000L, noOffset - offset)
        assertEquals(609L, ExifCaptureTimeParser.parse("2026:08:12 23:58:18", "7")!! - noOffset)
        assertEquals(-91L, ExifCaptureTimeParser.parse("2026:08:12 23:58:18", null)!! - noOffset)
    }

    @Test fun parserRejectsMalformedOrImpossibleOriginalCaptureTime() {
        assertNull(ExifCaptureTimeParser.parse(null, null))
        assertNull(ExifCaptureTimeParser.parse("2026:02:30 23:58:18", "091959"))
        assertNull(ExifCaptureTimeParser.parse("2026:08:12 25:58:18", null))
        assertNull(ExifCaptureTimeParser.parse("2026:08:12 23:58:18", "abc"))
        assertNull(ExifCaptureTimeParser.parse("2026:08:12 23:58:18", null, "+25:00"))
    }

    private fun samples() = listOf(0L, 5696L, 11393L, 17089L, 69334L, 75030L).mapIndexed { index, time ->
        CaptureTimingSample("f$index", index + 1, time + 1000L)
    }
}
