package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis

/** Explain a dark, sparse input series without changing which frames may be registered. */
internal fun registrationInputAdvice(frames: List<FrameAnalysis>): String? {
    val readable = frames.filter { it.decodeValid && it.width > 0 && it.height > 0 }
    if (readable.size < 3) return null
    val darkSparse = readable.count {
        it.backgroundLevel.isFinite() && it.backgroundLevel < 2f && it.reliableStarCount < 3
    }
    if (darkSparse * 5 < readable.size * 4) return null
    return "В большинстве исходных JPEG недостаточно надёжных звёзд: кадры очень тёмные. " +
        "Проверьте исходники и экспозицию; для выравнивания нужны звёзды в разных частях кадра."
}

internal fun profileContinuationMessage(
    profile: AstroProcessingProfile,
    acceptedFrames: Int,
    totalFrames: Int,
    minimumFrames: Int,
    inputAdvice: String?
): String =
    "Проверку прошли $acceptedFrames из $totalFrames кадров. " +
        "Для режима «${profile.title}» обычно нужно минимум $minimumFrames. " +
        "Отклонённые кадры не будут использованы. " +
        "Результат может быть шумнее или содержать меньше деталей." +
        inputAdvice?.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
