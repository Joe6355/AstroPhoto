package com.example.astrophoto

enum class AstroProcessingProfile(
    val title: String,
    val description: String,
    val filePrefix: String
) {
    NORMAL(
        title = "Обычный",
        description = "Старые Average / Median / Sigma остаются в ручном блоке ниже.",
        filePrefix = "Average"
    ),
    DEEP_SKY(
        title = "Чистое небо",
        description = "Для поля, штатива и тёмного неба. Мягко вытягивает слабые звёзды.",
        filePrefix = "DeepSky"
    ),
    URBAN_SKY(
        title = "Город / окно",
        description = "Для засветки, домов, окна и фонарей. Убирает градиент и вытягивает звёзды.",
        filePrefix = "UrbanSky"
    ),
    MAX_STARS(
        title = "Максимум звёзд",
        description = "Сильно вытягивает слабые точки. Может усилить шум.",
        filePrefix = "MaxStars"
    )
}
