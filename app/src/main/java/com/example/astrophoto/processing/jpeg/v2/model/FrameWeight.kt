package com.example.astrophoto.processing.jpeg.v2.model

data class FrameWeight(
    val frameId: String,
    val registrationWeight: Float,
    val sharpnessWeight: Float,
    val trailWeight: Float,
    val noiseWeight: Float,
    val exposureWeight: Float,
    val rawWeight: Float,
    val normalizedWeight: Float
)
