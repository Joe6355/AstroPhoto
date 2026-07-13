package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFormatDefaultTest {
    @Test
    fun missingPreferenceDefaultsToJpeg() {
        assertEquals("JPEG", canonicalCaptureFormat(null))
    }

    @Test
    fun explicitSupportedPreferencesArePreserved() {
        assertEquals("JPEG", canonicalCaptureFormat("JPEG"))
        assertEquals("RAW", canonicalCaptureFormat("RAW"))
    }

    @Test
    fun unknownLegacyPreferenceMigratesPredictablyToJpeg() {
        assertEquals("JPEG", canonicalCaptureFormat("JPEG_RAW"))
    }
}
