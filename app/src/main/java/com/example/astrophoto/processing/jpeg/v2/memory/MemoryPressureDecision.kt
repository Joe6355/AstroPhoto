package com.example.astrophoto.processing.jpeg.v2.memory

data class MemoryPressureDecision(
    val accepted: Boolean,
    val tileWidth: Int,
    val tileHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val estimatedBytes: Long,
    val retryRequired: Boolean,
    val reason: String?
)
