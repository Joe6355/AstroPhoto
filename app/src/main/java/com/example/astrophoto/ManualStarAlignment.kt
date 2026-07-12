package com.example.astrophoto

internal data class ManualAlignmentDiagnostic(
    val shift: AlignmentShift,
    val starsDetected: Int,
    val matches: Int,
    val confidence: Float,
    val method: String,
    val fallbackReason: String?
)

internal fun alignManualImages(
    reference: ArgbPixelImage,
    candidate: ArgbPixelImage,
    safeMode: Boolean,
    maxShiftPx: Int = 30,
    roi: AstroRoi = AstroRoi.Top70
): ManualAlignmentDiagnostic {
    require(reference.width == candidate.width && reference.height == candidate.height) {
        "Alignment image dimensions must match"
    }
    val detector = StarDetector()
    val referenceStars = detector.detect(
        reference,
        roi,
        StarDetectionSensitivity.HIGH
    ).stars.filter { it.radius <= MAX_MANUAL_ALIGNMENT_STAR_RADIUS }
    val candidateStars = detector.detect(
        candidate,
        roi,
        StarDetectionSensitivity.HIGH
    ).stars.filter { it.radius <= MAX_MANUAL_ALIGNMENT_STAR_RADIUS }
    val starResult = StarAlignment(detector).matchDetectedStars(
        referenceStars,
        candidateStars,
        maxShiftPx,
        aggressive = !safeMode
    )
    if (starResult?.applied == true) {
        return ManualAlignmentDiagnostic(
            shift = AlignmentShift(
                starResult.dx,
                starResult.dy,
                confidence = starResult.confidence.toDouble()
            ),
            starsDetected = candidateStars.size,
            matches = starResult.matchedStars,
            confidence = starResult.confidence,
            method = "stars",
            fallbackReason = null
        )
    }

    val fallbackReason = when {
        referenceStars.size < 3 || candidateStars.size < 3 -> "tooFewStars"
        starResult == null -> "noBoundedStarShift"
        else -> "weakStarMatch"
    }
    val fallback = runCatching {
        findImageAlignment(
            createAlignmentReference(reference),
            createAlignmentReference(candidate),
            maxShiftPx
        )
    }.getOrNull()
    if (fallback == null) {
        return ManualAlignmentDiagnostic(
            AlignmentShift.Zero,
            candidateStars.size,
            starResult?.matchedStars ?: 0,
            starResult?.confidence ?: 0f,
            "none",
            fallbackReason
        )
    }
    val accepted = selectManualAlignment(fallback, safeMode)
    val method = if (accepted.isZero && !fallback.isZero) "none" else "imageFallback"
    return ManualAlignmentDiagnostic(
        shift = accepted,
        starsDetected = candidateStars.size,
        matches = starResult?.matchedStars ?: 0,
        confidence = if (method == "imageFallback") fallback.confidence.toFloat() else 0f,
        method = method,
        fallbackReason = fallbackReason
    )
}

private const val MAX_MANUAL_ALIGNMENT_STAR_RADIUS = 2.2f
