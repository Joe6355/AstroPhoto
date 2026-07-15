package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer

private val LINEAR_CHANNEL_LUT = FloatArray(256) { value ->
    SrgbTransfer.srgbToLinear(value / 255f)
}

internal fun qualityLinearChannel(color: Int, shift: Int): Float =
    LINEAR_CHANNEL_LUT[color ushr shift and 0xFF]

internal fun qualityLinearLuminance(color: Int): Float =
    0.2126f * qualityLinearChannel(color, 16) +
        0.7152f * qualityLinearChannel(color, 8) +
        0.0722f * qualityLinearChannel(color, 0)
