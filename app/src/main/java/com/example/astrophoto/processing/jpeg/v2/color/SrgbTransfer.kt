package com.example.astrophoto.processing.jpeg.v2.color

import kotlin.math.pow

object SrgbTransfer {
    fun srgbToLinear(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.04045f) {
            v / 12.92f
        } else {
            ((v + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    fun linearToSrgb(value: Float): Float {
        val v = value.coerceAtLeast(0f)
        return if (v <= 0.0031308f) {
            v * 12.92f
        } else {
            1.055f * v.pow(1f / 2.4f) - 0.055f
        }.coerceIn(0f, 1f)
    }
}
