package com.joe6355.astrophoto

import java.util.Locale

internal data class FrameExclusionCount(val description: String, val count: Int)

/** Read-only presentation of the report paired with one published image. */
internal data class ResultProcessingSummary(
    val inputFrames: Int,
    val acceptedFrames: Int,
    val referenceFileName: String,
    val candidateType: String,
    val isEnhancedOutput: Boolean,
    val fallbackUsed: Boolean,
    val fallbackReason: String?,
    val exclusions: List<FrameExclusionCount>,
    val enhancedStatus: String,
    val enhancedReasons: List<String>
) {
    val frameCountText: String
        get() = if (candidateType == "REFERENCE") "В сложении прошли $acceptedFrames из $inputFrames кадров"
        else "Использовано $acceptedFrames из $inputFrames кадров"

    val savedResultText: String get() = when {
        isEnhancedOutput -> "Сохранён Enhanced — вариант с дополнительным осветлением."
        candidateType == "REFERENCE" -> "Сохранён только опорный кадр. Сложение не стало итоговым результатом."
        candidateType == "CLEAN_STACK" -> "Сохранено сложение без дополнительного проявления."
        candidateType == "PROCESSED" -> "Сохранён обработанный результат сложения."
        else -> "Сохранён результат обработки."
    }

    val fallbackText: String? get() = if (fallbackUsed) {
        "Основной вариант не прошёл проверку: ${qualityReasonDescription(fallbackReason)}."
    } else null

    val enhancedText: String? get() = when (enhancedStatus) {
        "SAVED" -> if (isEnhancedOutput) null else "Дополнительно сохранён вариант Enhanced."
        "REJECTED" -> "Enhanced не сохранён: " + enhancedReasons.map(::qualityReasonDescription)
            .distinct().ifEmpty { listOf("не пройдены проверки качества") }.joinToString("; ") +
            ". Оставлен основной результат."
        "FAILED" -> "Дополнительный вариант Enhanced не удалось сохранить. Основной результат сохранён."
        "NOT_ATTEMPTED_SAFE_FALLBACK" -> "Enhanced не создавался для выбранного безопасного варианта."
        "NOT_ATTEMPTED_EXPERIMENTAL_STARS" -> "Для этого режима дополнительный вариант Enhanced не создаётся."
        else -> null
    }
}

internal fun frameExclusions(
    input: Int, eligible: Int, accepted: Int, registrationReasons: List<String?>
): List<FrameExclusionCount> {
    require(input >= 1 && accepted in 0..eligible && eligible <= input)
    val rejected = input - accepted
    // In older manually approved runs the detailed registration decisions can disagree with
    // the final frame count. Preserve the authoritative total without inventing per-frame causes.
    val reasons = registrationReasons.takeIf { it.size <= rejected }.orEmpty()
    val counts = linkedMapOf<String, Int>()
    reasons.forEach { reason ->
        val description = frameReasonDescription(reason)
        counts[description] = (counts[description] ?: 0) + 1
    }
    val preliminary = minOf(input - eligible, rejected - reasons.size)
    if (preliminary > 0) counts["Не дошли до выравнивания"] = preliminary
    val remaining = rejected - reasons.size - preliminary
    if (remaining > 0) counts["Причина исключения не указана"] =
        (counts["Причина исключения не указана"] ?: 0) + remaining
    return counts.map { FrameExclusionCount(it.key, it.value) }
        .sortedWith(compareByDescending<FrameExclusionCount> { it.count }.thenBy { it.description })
}

internal fun frameReasonDescription(reason: String?): String {
    val value = reason.orEmpty().lowercase(Locale.ROOT)
    return when {
        value.isBlank() -> "Причина исключения не указана"
        listOf("spatial", "sector", "distribution").any(value::contains) -> "Звёзды недостаточно распределены по кадру"
        listOf("insufficient", "too_few", "not_enough", "no_matches", "no_stars").any(value::contains) ->
            "Недостаточно надёжных звёзд для совмещения"
        listOf("residual", "reprojection", "refinement").any(value::contains) -> "Не удалось точно совместить звёзды"
        value.contains("sequence") -> "Смещение не согласуется с остальной серией"
        listOf("blur", "sharp", "trail").any(value::contains) -> "Размытый кадр или вытянутые звёзды"
        listOf("exposure", "brightness", "saturation").any(value::contains) -> "Не пройдена проверка экспозиции"
        else -> "Другие причины отбора"
    }
}

internal fun qualityReasonDescription(reason: String?): String {
    val value = reason.orEmpty().lowercase(Locale.ROOT)
    return when {
        value.contains("confirmed_star_lost") -> "часть подтверждённых звёзд пропала"
        value.contains("confirmed_star_weakened") -> "подтверждённые звёзды стали слабее"
        listOf("streak", "smear", "trail", "elongat").any(value::contains) -> "появились вытянутые следы звёзд"
        value.contains("mask_shaped") || value.contains("background_lift") -> "появились пятна фона по маске"
        value.contains("foreground") -> "изменились детали переднего плана"
        value.contains("clipping") || value.contains("saturat") -> "появились пересвеченные участки"
        value.contains("memory") -> "не хватило памяти"
        value.contains("gain") || value.contains("improvement") -> "улучшение оказалось недостаточным"
        else -> "не пройдены проверки качества"
    }
}

internal data class ResultComparisonReference(val frame: SessionFrame, val fromReport: Boolean)

internal fun resultComparisonReference(
    summary: ResultProcessingSummary?, frames: List<SessionFrame>
): ResultComparisonReference? {
    val lights = frames.filter { it.category == SessionFrameCategory.LIGHTS_JPEG }
    val reference = summary?.referenceFileName?.let { name -> lights.singleOrNull { it.fileName == name } }
    if (reference != null) return ResultComparisonReference(reference, true)
    return lights.minWithOrNull(compareBy<SessionFrame> { it.createdAtMillis }.thenBy { it.fileName })
        ?.let { ResultComparisonReference(it, false) }
}
