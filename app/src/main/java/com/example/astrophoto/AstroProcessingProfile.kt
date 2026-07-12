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
        title = "Чистое небо",
        description = "Для поля, штатива и тёмного неба. Мягко вытягивает слабые звёзды.",
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
    )
}
