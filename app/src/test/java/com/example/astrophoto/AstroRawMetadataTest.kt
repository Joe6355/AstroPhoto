package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AstroRawMetadataTest {
    @Test
    fun channelBlackLevelsArePlacedInCfaCellOrder() {
        val channels = listOf(10f, 20f, 30f, 40f)

        assertEquals(listOf(10f, 20f, 30f, 40f), channelValuesToCfaCells(channels, 0))
        assertEquals(listOf(20f, 10f, 40f, 30f), channelValuesToCfaCells(channels, 1))
        assertEquals(listOf(20f, 40f, 10f, 30f), channelValuesToCfaCells(channels, 2))
        assertEquals(listOf(40f, 20f, 30f, 10f), channelValuesToCfaCells(channels, 3))
    }

    @Test
    fun incompatibleExposureIsRejectedBeforeStacking() {
        val reference = metadata(exposureTimeNs = 1_000_000_000L)
        val candidate = metadata(exposureTimeNs = 2_000_000_000L)

        assertThrows(IllegalArgumentException::class.java) {
            requireCompatibleRaw(reference, candidate, "different.dng")
        }
    }

    private fun metadata(exposureTimeNs: Long) = AstroRawMetadata(
        width = 4,
        height = 4,
        sampleShift = 0,
        cfaArrangement = 0,
        whiteLevel = 1023,
        blackLevels = List(4) { 64f },
        colorGains = List(4) { 1f },
        colorTransform = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        exposureTimeNs = exposureTimeNs,
        sensitivityIso = 800,
        postRawSensitivityBoost = 100,
        sensorOrientation = 0
    )
}
