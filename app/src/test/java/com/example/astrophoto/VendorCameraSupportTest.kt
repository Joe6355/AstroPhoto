package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorCameraSupportTest {
    @Test
    fun pixelUsesExplicitPublicCamera2Profile() {
        assertEquals(
            CameraCompatibilityProfile.GOOGLE_PIXEL,
            cameraCompatibilityProfile("Google", "Pixel 9 Pro")
        )
        assertEquals(
            CameraCompatibilityProfile.STANDARD,
            cameraCompatibilityProfile("Google", "Nexus 6P")
        )
    }

    @Test
    fun knownVendorProfilesAreDetected() {
        assertEquals(
            CameraCompatibilityProfile.SAMSUNG,
            cameraCompatibilityProfile("samsung", "SM-S721B")
        )
        assertEquals(
            CameraCompatibilityProfile.SONY,
            cameraCompatibilityProfile("Sony", "XQ-EC54")
        )
    }

    @Test
    fun vendorRangeExtendsPublicMaximum() {
        val selected = selectManualExposureRange(
            publicRangeNs = 85_000L..100_000_000L,
            vendorRange = VendorExposureRange(
                rangeNs = 83_333L..30_000_000_000L,
                source = ExposureRangeSource.SAMSUNG_VENDOR,
                keyName = SAMSUNG_EXPOSURE_RANGE_KEY
            )
        )

        assertEquals(85_000L..30_000_000_000L, selected.effectiveRangeNs)
        assertEquals(ExposureRangeSource.SAMSUNG_VENDOR, selected.source)
        assertTrue(selected.usesExtendedRange)
    }

    @Test
    fun cachedFailureLimitOverridesVendorMaximum() {
        val selected = selectManualExposureRange(
            publicRangeNs = 85_000L..100_000_000L,
            vendorRange = VendorExposureRange(
                rangeNs = 83_333L..30_000_000_000L,
                source = ExposureRangeSource.SONY_VENDOR,
                keyName = SONY_EXPOSURE_RANGE_KEY
            ),
            cachedMaximumExposureNs = 100_000_000L
        )

        assertEquals(85_000L..100_000_000L, selected.effectiveRangeNs)
        assertFalse(selected.usesExtendedRange)
    }

    @Test
    fun invalidVendorRangeFallsBackToPublicRange() {
        val selected = selectManualExposureRange(
            publicRangeNs = 85_000L..1_000_000_000L,
            vendorRange = VendorExposureRange(
                rangeNs = 0L..30_000_000_000L,
                source = ExposureRangeSource.SONY_VENDOR,
                keyName = SONY_EXPOSURE_RANGE_KEY
            )
        )

        assertEquals(85_000L..1_000_000_000L, selected.effectiveRangeNs)
        assertNull(selected.vendorRange)
        assertFalse(selected.usesExtendedRange)
    }

    @Test
    fun extendedExposureNeverRunsAsRepeatingPreview() {
        assertTrue(
            shouldUseAutomaticPreviewExposure(
                forPreview = true,
                applyLongExposureToPreview = true,
                requestedExposureTimeNs = 30_000_000_000L,
                publicExposureRangeNs = 85_000L..100_000_000L,
                usesExtendedExposure = true
            )
        )
        assertFalse(
            shouldUseAutomaticPreviewExposure(
                forPreview = false,
                applyLongExposureToPreview = false,
                requestedExposureTimeNs = 30_000_000_000L,
                publicExposureRangeNs = 85_000L..100_000_000L,
                usesExtendedExposure = true
            )
        )
    }

    @Test
    fun bestRearCameraPrioritizesManualLongExposure() {
        val selected = selectBestRearCameraId(
            listOf(
                RearCameraCandidate(
                    cameraId = "0",
                    listOrder = 0,
                    supportsManualSensor = false,
                    maximumExposureNs = 30_000_000_000L,
                    supportsRaw = false,
                    sensorPixelCount = 50_000_000L,
                    isLogicalCamera = true
                ),
                RearCameraCandidate(
                    cameraId = "2",
                    listOrder = 1,
                    supportsManualSensor = true,
                    maximumExposureNs = 10_000_000_000L,
                    supportsRaw = true,
                    sensorPixelCount = 12_000_000L,
                    isLogicalCamera = false
                )
            )
        )

        assertEquals("2", selected)
    }

    @Test
    fun isoOverridesNarrowAdvertisedRange() {
        assertEquals(
            100..1600,
            applyIsoOverrides(
                range = 50..6400,
                minimumIso = 100,
                maximumIso = 1600
            )
        )
    }

    @Test
    fun materialClampUsesTenPercentTolerance() {
        assertFalse(materiallyLowerThanRequested(1_000_000_000L, 950_000_000L))
        assertTrue(materiallyLowerThanRequested(1_000_000_000L, 800_000_000L))
        assertFalse(materiallyLowerThanRequested(1000, 950))
        assertTrue(materiallyLowerThanRequested(1000, 800))
        assertTrue(materiallyHigherThanRequested(100, 120))
    }

    @Test
    fun genericVendorExposureRangeIsDiscoveredWithoutManufacturerProfile() {
        assertEquals(
            100_000L..30_000_000_000L,
            vendorExposureRangeValue(
                keyName = "vendor.camera.sensor.exposureTimeRange",
                value = longArrayOf(100_000L, 30_000_000_000L)
            )
        )
        assertNull(
            vendorExposureRangeValue(
                keyName = "vendor.camera.exposureCompensationRange",
                value = longArrayOf(1L, 30_000_000_000L)
            )
        )
    }

    @Test
    fun manualFrameDurationTracksExposureOnlyWhenHalSupportsIt() {
        assertEquals(
            10_000_000_000L,
            manualFrameDurationNs(10_000_000_000L, 30_000_000_000L, supported = true)
        )
        assertNull(manualFrameDurationNs(30_000_000_000L, 10_000_000_000L, supported = true))
        assertNull(manualFrameDurationNs(10_000_000_000L, 30_000_000_000L, supported = false))
    }
}
