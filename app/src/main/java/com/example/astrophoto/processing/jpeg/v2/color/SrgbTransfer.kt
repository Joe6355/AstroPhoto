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

    /** Exact IEC 61966-2-1 transfer in Double precision for final-output transforms. */
    fun srgbToLinear(value: Double): Double {
        val v = value.coerceIn(0.0, 1.0)
        return if (v <= 0.04045) {
            v / 12.92
        } else {
            ((v + 0.055) / 1.055).pow(2.4)
        }
    }

    /** Exact IEC 61966-2-1 transfer in Double precision for final-output transforms. */
    fun linearToSrgb(value: Double): Double {
        val v = value.coerceAtLeast(0.0)
        return if (v <= 0.0031308) {
            v * 12.92
        } else {
            1.055 * v.pow(1.0 / 2.4) - 0.055
        }.coerceIn(0.0, 1.0)
    }
}
