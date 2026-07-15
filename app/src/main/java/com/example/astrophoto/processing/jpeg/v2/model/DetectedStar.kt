package com.example.astrophoto.processing.jpeg.v2.model

data class DetectedStar(
    val x: Float,
    val y: Float,
    val flux: Float,
    val localBackground: Float,
    val localContrast: Float,
    val width: Float,
    val ellipticity: Float,
    val confidence: Float
)
