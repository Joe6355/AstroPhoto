package com.example.astrophoto.processing.jpeg.v2.model

import com.example.astrophoto.ArgbPixelImage

data class CompositeDiagnostics(
    val validSkyCoverageRatio: Float,
    val referenceFallbackRatio: Float,
    val foregroundSharpnessBefore: Float,
    val foregroundSharpnessAfter: Float,
    val maximumForegroundChannelDifference: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val cropApplied: Boolean,
    val compositionDurationMillis: Long
)

data class CompositeResult(
    val image: ArgbPixelImage,
    val effectiveSkyAlpha: AlphaMask,
    val diagnostics: CompositeDiagnostics
)
