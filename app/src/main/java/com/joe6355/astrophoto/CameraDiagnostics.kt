package com.joe6355.astrophoto

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Range
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

data class DiagnosticRow(
    val name: String,
    val value: String,
    val description: String,
    val isSupported: Boolean? = null
)

data class CameraDiagnosticInfo(
    val rows: List<DiagnosticRow>,
    val warning: String? = null
)

fun readCameraDiagnostics(context: Context): Result<CameraDiagnosticInfo> = runCatching {
    val cameraManager = context.getSystemService(CameraManager::class.java)
        ?: error("Системная служба камеры недоступна")

    val capabilityStore = ManualCameraCapabilityStore(context)
    val rearCandidates = readRearCameraCandidates(
        cameraManager = cameraManager,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        cachedMaximumExposureNs = { cameraId ->
            capabilityStore.load(cameraId).maximumExposureNs
        }
    )
    val rearCameraId = selectBestRearCameraId(rearCandidates)
        ?: error("Основная задняя камера не найдена")

    val characteristics = cameraManager.getCameraCharacteristics(rearCameraId)
    val compatibilityProfile = cameraCompatibilityProfile(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL
    )
    val storedOverrides = capabilityStore.load(rearCameraId)
    val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    val capabilities = characteristics.get(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
    )
    val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val exposureRangeSelection = selectManualExposureRange(
        publicRangeNs = exposureRange?.let { it.lower..it.upper },
        vendorRange = readVendorExposureRange(
            characteristics = characteristics,
            profile = compatibilityProfile
        ),
        cachedMaximumExposureNs = storedOverrides.maximumExposureNs
    )
    val postRawBoostRange = characteristics.get(
        CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE
    )
    val effectiveIsoRange = applyIsoOverrides(
        range = effectiveIsoRange(
            sensorRange = isoRange?.let { it.lower..it.upper },
            postRawBoostRange = postRawBoostRange?.let { it.lower..it.upper }
        ),
        minimumIso = storedOverrides.minimumIso,
        maximumIso = storedOverrides.maximumIso
    )
    val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        characteristics.physicalCameraIds
    } else {
        emptySet()
    }
    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
    val minimumFocusDistance = characteristics.get(
        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
    )
    val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
    val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)

    val supportsManualSensor = supportsVerifiedManualSensor(characteristics)
    val supportsRaw = capabilities?.contains(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
    ) == true
    val supportsManualFocus = (minimumFocusDistance ?: 0f) > 0f

    val missingValues = buildList {
        if (hardwareLevel == null) add("уровень оборудования")
        if (capabilities == null) add("capabilities")
        if (isoRange == null) add("диапазон ISO")
        if (exposureRange == null) add("диапазон выдержки")
        if (focalLengths == null) add("фокусные расстояния")
        if (minimumFocusDistance == null) add("минимальная дистанция фокуса")
        if (aeModes == null) add("режимы AE")
        if (awbModes == null) add("режимы AWB")
    }

    val warnings = buildList {
        if (!supportsManualSensor) {
            add("Камера не заявляет поддержку ручного управления выдержкой и ISO.")
        }
        if (missingValues.isNotEmpty()) {
            add("Часть характеристик не предоставлена устройством: ${missingValues.joinToString()}.")
        }
    }
    val recentErrors = CameraDiagnosticEventStore.recent(context)

    CameraDiagnosticInfo(
        rows = listOf(
            DiagnosticRow(
                name = "Профиль камеры",
                value = compatibilityProfile.displayName,
                description = if (compatibilityProfile == CameraCompatibilityProfile.GOOGLE_PIXEL) {
                    "Pixel использует только заявленные публичные диапазоны Camera2"
                } else {
                    "Политика чтения диапазонов камеры"
                }
            ),
            DiagnosticRow(
                name = "Camera ID",
                value = rearCameraId,
                description = "Выбранная задняя камера для ручной съёмки"
            ),
            DiagnosticRow(
                name = "Задние Camera ID",
                value = rearCandidates.joinToString { it.cameraId },
                description = "Доступные приложению задние камеры"
            ),
            DiagnosticRow(
                name = "Physical Camera ID",
                value = physicalCameraIds.takeIf { it.isNotEmpty() }
                    ?.joinToString() ?: "Недоступно",
                description = "Физические камеры внутри выбранной логической камеры"
            ),
            DiagnosticRow(
                name = "Hardware Level",
                value = formatHardwareLevel(hardwareLevel),
                description = "Полнота реализации Camera2 API"
            ),
            DiagnosticRow(
                name = "Manual Sensor",
                value = yesNo(supportsManualSensor),
                description = "Нужен для ручной выдержки и ISO",
                isSupported = supportsManualSensor
            ),
            DiagnosticRow(
                name = "RAW/DNG",
                value = yesNo(supportsRaw),
                description = "RAW нужен для сохранения необработанного кадра",
                isSupported = supportsRaw
            ),
            DiagnosticRow(
                name = "ISO",
                value = formatIntRange(isoRange),
                description = "Доступный диапазон чувствительности сенсора"
            ),
            DiagnosticRow(
                name = "Эффективный ISO",
                value = effectiveIsoRange?.let { "${it.first} - ${it.last}" }
                    ?: "Недоступно",
                description = "Сенсорный ISO с учётом boost и сохранённых ограничений"
            ),
            DiagnosticRow(
                name = "Выдержка",
                value = formatExposureRange(exposureRange),
                description = "Диапазон времени экспозиции"
            ),
            DiagnosticRow(
                name = "Макс. выдержка",
                value = exposureRangeSelection.effectiveRangeNs?.last
                    ?.let(::formatNanoseconds) ?: "Недоступно",
                description = if (exposureRangeSelection.usesExtendedRange) {
                    "Расширенный максимум из ${exposureRangeSelection.source?.displayName} " +
                        "Camera2 vendor metadata"
                } else {
                    "Максимальная выдержка, которую сообщает камера"
                }
            ),
            DiagnosticRow(
                name = "Vendor выдержка",
                value = exposureRangeSelection.vendorRange?.rangeNs
                    ?.let { range ->
                        "${formatNanoseconds(range.first)} - ${formatNanoseconds(range.last)}"
                    }
                    ?: "Недоступно",
                description = exposureRangeSelection.vendorRange?.let {
                    "${it.source.displayName}: ${it.keyName}"
                } ?: "Поддерживаемый vendor-диапазон не найден"
            ),
            DiagnosticRow(
                name = "Focal Lengths",
                value = formatFocalLengths(focalLengths),
                description = "Доступные фокусные расстояния объектива"
            ),
            DiagnosticRow(
                name = "Мин. дистанция фокуса",
                value = minimumFocusDistance?.let { "${formatNumber(it.toDouble())} дптр" }
                    ?: "Недоступно",
                description = "Максимальная сила фокусировки; 0 означает фиксированный фокус"
            ),
            DiagnosticRow(
                name = "Ручной фокус",
                value = yesNo(supportsManualFocus),
                description = "Потенциально доступен при дистанции фокуса больше 0",
                isSupported = supportsManualFocus
            ),
            DiagnosticRow(
                name = "Capabilities",
                value = formatCapabilities(capabilities),
                description = "Дополнительные возможности Camera2"
            ),
            DiagnosticRow(
                name = "Режимы AE",
                value = formatAeModes(aeModes),
                description = "Доступные режимы автоматической экспозиции"
            ),
            DiagnosticRow(
                name = "Режимы AWB",
                value = formatAwbModes(awbModes),
                description = "Доступные режимы автоматического баланса белого"
            ),
            DiagnosticRow(
                name = "Последние ошибки камеры",
                value = formatCameraDiagnosticEvents(recentErrors),
                description = "До 12 последних уникальных ошибок Camera2 и фоновой серии",
                isSupported = recentErrors.isEmpty()
            )
        ),
        warning = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
    )
}

private fun yesNo(value: Boolean) = if (value) "Да" else "Нет"

private fun formatIntRange(range: Range<Int>?): String =
    range?.let { "${it.lower} - ${it.upper}" } ?: "Недоступно"

private fun formatExposureRange(range: Range<Long>?): String =
    range?.let { "${formatNanoseconds(it.lower)} - ${formatNanoseconds(it.upper)}" }
        ?: "Недоступно"

private fun formatNanoseconds(nanoseconds: Long): String =
    "${formatNumber(nanoseconds / 1_000_000_000.0)} сек"

private fun formatFocalLengths(values: FloatArray?): String =
    values?.takeIf { it.isNotEmpty() }
        ?.joinToString { "${formatNumber(it.toDouble())} мм" }
        ?: "Недоступно"

private fun formatNumber(value: Double): String =
    DecimalFormat(
        "0.#########",
        DecimalFormatSymbols(Locale.forLanguageTag("ru-RU"))
    ).format(value)

private fun formatHardwareLevel(level: Int?): String = when (level) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "UNKNOWN"
}

private fun formatCapabilities(values: IntArray?): String =
    values?.takeIf { it.isNotEmpty() }?.joinToString { capabilityName(it) } ?: "Недоступно"

private fun capabilityName(value: Int): String = when (value) {
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE ->
        "BACKWARD_COMPATIBLE"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING ->
        "MANUAL_POST_PROCESSING"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING ->
        "PRIVATE_REPROCESSING"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS ->
        "READ_SENSOR_SETTINGS"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO ->
        "HIGH_SPEED_VIDEO"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA ->
        "LOGICAL_MULTI_CAMERA"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR ->
        "ULTRA_HIGH_RESOLUTION_SENSOR"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING ->
        "REMOSAIC_REPROCESSING"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT ->
        "DYNAMIC_RANGE_TEN_BIT"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES ->
        "COLOR_SPACE_PROFILES"
    else -> "UNKNOWN ($value)"
}

private fun formatAeModes(values: IntArray?): String =
    values?.takeIf { it.isNotEmpty() }?.joinToString { aeModeName(it) } ?: "Недоступно"

private fun aeModeName(value: Int): String = when (value) {
    CameraCharacteristics.CONTROL_AE_MODE_OFF -> "OFF"
    CameraCharacteristics.CONTROL_AE_MODE_ON -> "ON"
    CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH -> "AUTO_FLASH"
    CameraCharacteristics.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ALWAYS_FLASH"
    CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "AUTO_FLASH_REDEYE"
    CameraCharacteristics.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "EXTERNAL_FLASH"
    else -> "UNKNOWN ($value)"
}

private fun formatAwbModes(values: IntArray?): String =
    values?.takeIf { it.isNotEmpty() }?.joinToString { awbModeName(it) } ?: "Недоступно"

private fun awbModeName(value: Int): String = when (value) {
    CameraCharacteristics.CONTROL_AWB_MODE_OFF -> "OFF"
    CameraCharacteristics.CONTROL_AWB_MODE_AUTO -> "AUTO"
    CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
    CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
    CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
    CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
    CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
    CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
    CameraCharacteristics.CONTROL_AWB_MODE_SHADE -> "SHADE"
    else -> "UNKNOWN ($value)"
}
