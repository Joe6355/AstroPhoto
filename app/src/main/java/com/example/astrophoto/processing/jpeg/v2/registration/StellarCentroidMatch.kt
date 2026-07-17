package com.example.astrophoto.processing.jpeg.v2.registration

data class StellarCentroidMatch(
    val patch: FullResolutionStarPatch,
    val reference: StellarCentroidMeasurement,
    val candidate: StellarCentroidMeasurement,
    val dx: Float,
    val dy: Float,
    val residualToPrediction: Float,
    val accepted: Boolean,
    val rejectionReason: String?,
    val confidence: Float,
    val weight: Float
)

data class StellarCentroidMatchDiagnostic(
    val x: Float,
    val y: Float,
    val sector: Int,
    val measuredDx: Float,
    val measuredDy: Float,
    val residualToPrediction: Float,
    val snr: Float,
    val fitResidual: Float,
    val accepted: Boolean,
    val rejectionReason: String?
)
