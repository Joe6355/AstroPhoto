package com.example.astrophoto

data class CameraPreset(
    val name: String,
    val description: String,
    val iso: Int,
    val exposureTimeNs: Long,
    val preferRaw: Boolean,
    val frameCount: Int,
    val delaySeconds: Int,
    val focusMode: CameraFocusMode,
    val useMaximumExposureWhenNeeded: Boolean = false
)

val CAMERA_PRESETS = listOf(
    CameraPreset(
        name = "Тест камеры",
        description = "Быстрая проверка, что камера и сохранение работают.",
        iso = 400,
        exposureTimeNs = 33_333_333L,
        preferRaw = false,
        frameCount = 1,
        delaySeconds = 0,
        focusMode = CameraFocusMode.AF
    ),
    CameraPreset(
        name = "Если ничего не видно",
        description = "Для очень тёмной сцены. Кадр светлее, но шума будет больше.",
        iso = 1600,
        exposureTimeNs = 10_000_000_000L,
        preferRaw = true,
        frameCount = 3,
        delaySeconds = 1,
        focusMode = CameraFocusMode.INFINITY
    ),
    CameraPreset(
        name = "Если пересвечивает",
        description = "Для ярких объектов, фонарей, Луны и сильной засветки.",
        iso = 50,
        exposureTimeNs = 33_333_333L,
        preferRaw = false,
        frameCount = 1,
        delaySeconds = 0,
        focusMode = CameraFocusMode.AF
    ),
    CameraPreset(
        name = "Звёзды старт",
        description = "Безопасный стартовый режим для съёмки звёзд.",
        iso = 800,
        exposureTimeNs = 10_000_000_000L,
        preferRaw = true,
        frameCount = 5,
        delaySeconds = 1,
        focusMode = CameraFocusMode.INFINITY
    ),
    CameraPreset(
        name = "Звёзды максимум",
        description = "Максимальный сбор света. Обязательно используйте штатив.",
        iso = 1600,
        exposureTimeNs = 30_000_000_000L,
        preferRaw = true,
        frameCount = 10,
        delaySeconds = 1,
        focusMode = CameraFocusMode.INFINITY,
        useMaximumExposureWhenNeeded = true
    ),
    CameraPreset(
        name = "Меньше шума",
        description = "Темнее, но чище. Лучше подходит для последующей обработки.",
        iso = 400,
        exposureTimeNs = 30_000_000_000L,
        preferRaw = true,
        frameCount = 10,
        delaySeconds = 1,
        focusMode = CameraFocusMode.INFINITY,
        useMaximumExposureWhenNeeded = true
    ),
    CameraPreset(
        name = "С рук",
        description = "Если нет штатива. Для астрофото слабее, но меньше смазывает.",
        iso = 800,
        exposureTimeNs = 33_333_333L,
        preferRaw = false,
        frameCount = 1,
        delaySeconds = 0,
        focusMode = CameraFocusMode.AF
    ),
    CameraPreset(
        name = "Луна",
        description = "Для яркой Луны, чтобы она не превратилась в белое пятно.",
        iso = 50,
        exposureTimeNs = 4_000_000L,
        preferRaw = false,
        frameCount = 1,
        delaySeconds = 0,
        focusMode = CameraFocusMode.INFINITY
    ),
    CameraPreset(
        name = "Городское небо",
        description = "Для съёмки при сильной городской засветке.",
        iso = 400,
        exposureTimeNs = 5_000_000_000L,
        preferRaw = true,
        frameCount = 5,
        delaySeconds = 1,
        focusMode = CameraFocusMode.INFINITY
    ),
    CameraPreset(
        name = "Серия RAW для обработки",
        description = "Большая RAW-серия для последующей обработки на компьютере.",
        iso = 800,
        exposureTimeNs = 30_000_000_000L,
        preferRaw = true,
        frameCount = 20,
        delaySeconds = 1,
        focusMode = CameraFocusMode.INFINITY,
        useMaximumExposureWhenNeeded = true
    )
)
