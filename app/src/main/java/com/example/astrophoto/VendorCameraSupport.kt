package com.example.astrophoto

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build

internal const val SAMSUNG_EXPOSURE_RANGE_KEY =
    "samsung.android.sensor.info.exposureTimeRange"
internal const val SONY_EXPOSURE_RANGE_KEY =
    "com.sonymobile.sensor.info.exposureTimeRange"

internal enum class ExposureRangeSource(val displayName: String) {
    PUBLIC("Camera2"),
    SAMSUNG_VENDOR("Samsung"),
    SONY_VENDOR("Sony")
}

internal enum class CameraCompatibilityProfile(val displayName: String) {
    GOOGLE_PIXEL("Google Pixel / public Camera2"),
    SAMSUNG("Samsung / Camera2 + vendor metadata"),
    SONY("Sony / Camera2 + vendor metadata"),
    STANDARD("Стандартный Camera2")
}

internal fun cameraCompatibilityProfile(
    manufacturer: String,
    model: String
): CameraCompatibilityProfile = when {
    manufacturer.trim().equals("google", ignoreCase = true) &&
        model.trim().startsWith("Pixel", ignoreCase = true) ->
        CameraCompatibilityProfile.GOOGLE_PIXEL

    manufacturer.trim().equals("samsung", ignoreCase = true) ->
        CameraCompatibilityProfile.SAMSUNG

    manufacturer.trim().let {
        it.equals("sony", ignoreCase = true) ||
            it.equals("sony ericsson", ignoreCase = true)
    } -> CameraCompatibilityProfile.SONY

    else -> CameraCompatibilityProfile.STANDARD
}

internal data class VendorExposureRange(
    val rangeNs: LongRange,
    val source: ExposureRangeSource,
    val keyName: String
)

internal data class ManualExposureRangeSelection(
    val effectiveRangeNs: LongRange?,
    val publicRangeNs: LongRange?,
    val vendorRange: VendorExposureRange?,
    val source: ExposureRangeSource?,
    val usesExtendedRange: Boolean
)

internal data class RearCameraCandidate(
    val cameraId: String,
    val listOrder: Int,
    val supportsManualSensor: Boolean,
    val maximumExposureNs: Long?,
    val supportsRaw: Boolean,
    val sensorPixelCount: Long,
    val isLogicalCamera: Boolean
)

internal fun selectManualExposureRange(
    publicRangeNs: LongRange?,
    vendorRange: VendorExposureRange?,
    cachedMaximumExposureNs: Long? = null
): ManualExposureRangeSelection {
    val validatedPublicRange = publicRangeNs?.takeIf(::isValidExposureRange)
    val validatedVendorRange = vendorRange?.takeIf {
        isValidExposureRange(it.rangeNs)
    }
    val usesExtendedRange = validatedVendorRange != null &&
        (validatedPublicRange == null ||
            validatedVendorRange.rangeNs.last > validatedPublicRange.last)
    val advertisedRange = when {
        usesExtendedRange -> {
            val minimum = validatedPublicRange?.first
                ?: validatedVendorRange.rangeNs.first
            minimum..validatedVendorRange.rangeNs.last
        }

        validatedPublicRange != null -> validatedPublicRange
        else -> validatedVendorRange?.rangeNs
    }
    val effectiveRange = applyExposureMaximumOverride(
        advertisedRange,
        cachedMaximumExposureNs
    )

    return ManualExposureRangeSelection(
        effectiveRangeNs = effectiveRange,
        publicRangeNs = validatedPublicRange,
        vendorRange = validatedVendorRange,
        source = when {
            usesExtendedRange -> validatedVendorRange.source
            validatedPublicRange != null -> ExposureRangeSource.PUBLIC
            else -> validatedVendorRange?.source
        },
        usesExtendedRange = usesExtendedRange &&
            effectiveRange?.last?.let { maximum ->
                validatedPublicRange == null || maximum > validatedPublicRange.last
            } == true
    )
}

internal fun selectBestRearCameraId(candidates: List<RearCameraCandidate>): String? =
    candidates.maxWithOrNull(
        compareBy<RearCameraCandidate> { it.supportsManualSensor }
            .thenBy { it.maximumExposureNs ?: 0L }
            .thenBy { it.supportsRaw }
            .thenBy { it.isLogicalCamera }
            .thenBy { it.sensorPixelCount }
            .thenBy { -it.listOrder }
    )?.cameraId

internal fun readRearCameraCandidates(
    cameraManager: CameraManager,
    manufacturer: String,
    model: String,
    cachedMaximumExposureNs: (String) -> Long?
): List<RearCameraCandidate> = cameraManager.cameraIdList.mapIndexedNotNull { index, id ->
    val characteristics = cameraManager.getCameraCharacteristics(id)
    if (
        characteristics.get(CameraCharacteristics.LENS_FACING) !=
        CameraCharacteristics.LENS_FACING_BACK
    ) {
        return@mapIndexedNotNull null
    }
    val capabilities = characteristics.get(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
    ) ?: intArrayOf()
    val publicRange = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
    )?.let { it.lower..it.upper }
    val rangeSelection = selectManualExposureRange(
        publicRangeNs = publicRange,
        vendorRange = readVendorExposureRange(
            characteristics,
            cameraCompatibilityProfile(manufacturer, model)
        ),
        cachedMaximumExposureNs = cachedMaximumExposureNs(id)
    )
    val pixelArraySize = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
    )
    RearCameraCandidate(
        cameraId = id,
        listOrder = index,
        supportsManualSensor = supportsVerifiedManualSensor(characteristics),
        maximumExposureNs = rangeSelection.effectiveRangeNs?.last,
        supportsRaw = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        ),
        sensorPixelCount = pixelArraySize?.let {
            it.width.toLong() * it.height.toLong()
        } ?: 0L,
        isLogicalCamera = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        )
    )
}

internal fun shouldUseAutomaticPreviewExposure(
    forPreview: Boolean,
    applyLongExposureToPreview: Boolean,
    requestedExposureTimeNs: Long,
    publicExposureRangeNs: LongRange?,
    usesExtendedExposure: Boolean
): Boolean {
    if (!forPreview) return false
    val usesExtendedValue = usesExtendedExposure &&
        publicExposureRangeNs?.let { requestedExposureTimeNs > it.last } == true
    return !applyLongExposureToPreview || usesExtendedValue
}

internal fun readVendorExposureRange(
    characteristics: CameraCharacteristics,
    profile: CameraCompatibilityProfile
): VendorExposureRange? {
    val descriptor = when (profile) {
        CameraCompatibilityProfile.SAMSUNG -> Triple(
            SAMSUNG_EXPOSURE_RANGE_KEY,
            ExposureRangeSource.SAMSUNG_VENDOR,
            "Samsung"
        )

        CameraCompatibilityProfile.SONY -> Triple(
            SONY_EXPOSURE_RANGE_KEY,
            ExposureRangeSource.SONY_VENDOR,
            "Sony"
        )

        CameraCompatibilityProfile.GOOGLE_PIXEL,
        CameraCompatibilityProfile.STANDARD -> return null
    }
    val keyName = descriptor.first
    val key = characteristics.keys.firstOrNull { it.name == keyName }
        ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CameraCharacteristics.Key(keyName, LongArray::class.java)
        } else {
            return null
        }
    val value = readCameraCharacteristicValue(characteristics, key) as? LongArray
        ?: return null
    if (value.size < 2) return null
    val range = (value[0]..value[1]).takeIf(::isValidExposureRange) ?: return null
    return VendorExposureRange(
        rangeNs = range,
        source = descriptor.second,
        keyName = keyName
    )
}

internal fun supportsVerifiedManualSensor(
    characteristics: CameraCharacteristics
): Boolean {
    val capabilities = characteristics.get(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
    ) ?: return false
    if (!capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )
    ) {
        return false
    }

    return runCatching {
        characteristics.availableCaptureRequestKeys.contains(
            CaptureRequest.SENSOR_EXPOSURE_TIME
        ) && characteristics.availableCaptureRequestKeys.contains(
            CaptureRequest.SENSOR_SENSITIVITY
        ) && characteristics.availableCaptureResultKeys.contains(
            CaptureResult.SENSOR_EXPOSURE_TIME
        ) && characteristics.availableCaptureResultKeys.contains(
            CaptureResult.SENSOR_SENSITIVITY
        )
    }.getOrDefault(false)
}

internal fun applyExposureMaximumOverride(
    range: LongRange?,
    maximumExposureNs: Long?
): LongRange? {
    range ?: return null
    val maximum = maximumExposureNs
        ?.takeIf { it > 0L }
        ?.coerceIn(range.first, range.last)
        ?: range.last
    return range.first..maximum
}

internal fun applyIsoOverrides(
    range: IntRange?,
    minimumIso: Int?,
    maximumIso: Int?
): IntRange? {
    range ?: return null
    val minimum = minimumIso
        ?.takeIf { it > 0 }
        ?.coerceIn(range.first, range.last)
        ?: range.first
    val maximum = maximumIso
        ?.takeIf { it > 0 }
        ?.coerceIn(minimum, range.last)
        ?: range.last
    return minimum..maximum
}

internal fun materiallyLowerThanRequested(requested: Long, actual: Long): Boolean =
    requested > 0L && actual > 0L && actual < requested * 9L / 10L

internal fun materiallyLowerThanRequested(requested: Int, actual: Int): Boolean =
    requested > 0 && actual > 0 && actual.toLong() * 10L < requested.toLong() * 9L

internal fun materiallyHigherThanRequested(requested: Int, actual: Int): Boolean =
    requested > 0 && actual > 0 && actual.toLong() * 10L > requested.toLong() * 11L

@Suppress("UNCHECKED_CAST")
private fun readCameraCharacteristicValue(
    characteristics: CameraCharacteristics,
    key: CameraCharacteristics.Key<*>
): Any? = runCatching {
    characteristics.get(key as CameraCharacteristics.Key<Any>)
}.getOrNull()

private fun isValidExposureRange(range: LongRange): Boolean =
    range.first > 0L && range.last >= range.first
