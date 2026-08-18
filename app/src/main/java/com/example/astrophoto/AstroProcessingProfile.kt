package com.example.astrophoto

enum class AstroProcessingProfile(
    val title: String,
    val description: String,
    val filePrefix: String,
    val minimumFrames: Int
) {
    NORMAL(
        title = "Обычный",
        description = "Старые Average / Median / Sigma остаются в ручном блоке ниже.",
        filePrefix = "Average",
        minimumFrames = 2
    ),
    DEEP_SKY(
        title = "Улучшение фото",
        description = "Выравнивает звёзды, складывает JPEG-кадры и безопасно улучшает видимость неба.",
        filePrefix = "DeepSky",
        minimumFrames = 4
    ),
    DEEP_SKY_ALIGNED(
        title = "Чистое небо + Alignment",
        description = "Требует подтверждённого звёздного выравнивания движущегося неба.",
        filePrefix = "DeepSkyAligned",
        minimumFrames = 4
    ),
    URBAN_SKY(
        title = "Город / окно",
        description = "Для засветки, домов, окна и фонарей. Убирает градиент и вытягивает звёзды.",
        filePrefix = "UrbanSky",
        minimumFrames = 4
    ),
    URBAN_SKY_STRONG(
        title = "Город / окно Strong",
        description = "Усиленная коррекция городской засветки с обязательной sanity-проверкой.",
        filePrefix = "UrbanSkyStrong",
        minimumFrames = 6
    ),
    MAX_STARS(
        title = "Максимум звёзд",
        description = "Сильно вытягивает слабые точки. Может усилить шум.",
        filePrefix = "MaxStars",
        minimumFrames = 6
    ),
    EXPERIMENTAL_STARS(
        title = "Experimental Stars",
        description = "Локально усиливает подтверждённые звёзды без общего осветления неба.",
        filePrefix = "ExperimentalStars",
        minimumFrames = 6
    )
}

/**
 * Профили, подтверждённые реальными прогонами и доступные пользователю.
 *
 * Остальные значения enum сохраняются для чтения старых результатов и отчётов.
 */
internal val USER_VISIBLE_PROCESSING_PROFILES: List<AstroProcessingProfile> =
    listOf(AstroProcessingProfile.DEEP_SKY)
