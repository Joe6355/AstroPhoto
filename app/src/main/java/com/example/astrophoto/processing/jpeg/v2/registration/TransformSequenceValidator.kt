package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import kotlin.math.abs
import kotlin.math.hypot

data class OrderedRegistration(
    val frameId: String,
    val captureIndex: Int,
    val isReference: Boolean,
    val registration: RegistrationResult
)

data class TransformSequenceValidation(
    val registrations: List<OrderedRegistration>,
    val score: Float,
    val rejectedFrameIds: List<String>,
    val retryFrameIds: List<String>
)

class TransformSequenceValidator {
    fun validate(
        values: List<OrderedRegistration>,
        retryTranslationOnly: (OrderedRegistration) -> RegistrationResult? = { null }
    ): TransformSequenceValidation {
        if (values.isEmpty()) return TransformSequenceValidation(emptyList(), 0f, emptyList(), emptyList())
        val ordered = values.sortedBy { it.captureIndex }.toMutableList()
        val reliable = ordered.filter { it.registration.isReliable }
        if (reliable.size < 3) {
            return TransformSequenceValidation(
                ordered.mapIndexed { index, entry ->
                    entry.copy(
                        registration = entry.registration.copy(
                            transformSequenceScore = if (entry.registration.isReliable) 1f else 0f,
                            neighborTransformDelta = neighborDelta(entry, ordered.getOrNull(index - 1))
                        )
                    )
                },
                if (reliable.size >= 2) 1f else 0f,
                ordered.filterNot { it.registration.isReliable }.map { it.frameId },
                emptyList()
            )
        }

        val stepDx = reliable.zipWithNext { first, second ->
            second.registration.dx - first.registration.dx
        }
        val stepDy = reliable.zipWithNext { first, second ->
            second.registration.dy - first.registration.dy
        }
        val medianStepDx = median(stepDx)
        val medianStepDy = median(stepDy)
        val medianStep = hypot(medianStepDx, medianStepDy)
        val stepMad = lowerMedian(stepDx.indices.map { index ->
            hypot(stepDx[index] - medianStepDx, stepDy[index] - medianStepDy)
        })
        val translationFloor = maxOf(MIN_TRANSLATION_DEVIATION_PX, medianStep * 0.20f)
        val translationLimit = maxOf(translationFloor, stepMad * MAD_MULTIPLIER + translationFloor)
        val rotationSteps = reliable.zipWithNext { first, second ->
            second.registration.rotationRadians - first.registration.rotationRadians
        }
        val medianRotationStep = median(rotationSteps)
        val rotationMad = lowerMedian(rotationSteps.map { abs(it - medianRotationStep) })
        val rotationLimit = maxOf(MIN_ROTATION_DEVIATION_RADIANS, rotationMad * MAD_MULTIPLIER)
        val retryIds = mutableListOf<String>()
        val rejectedIds = mutableListOf<String>()
        var totalDeviation = 0f

        reliable.forEach { entry ->
            if (entry.isReference) return@forEach
            val index = reliable.indexOf(entry)
            val previous = reliable.getOrNull(index - 1)
            val next = reliable.getOrNull(index + 1)
            val deviation = localTranslationDeviation(
                entry,
                previous,
                next,
                medianStepDx,
                medianStepDy
            )
            val rotationDeviation = localRotationDeviation(entry, previous, next, medianRotationStep)
            val currentNeighborDelta = neighborDelta(entry, previous)
            val directionReversal = hasUnsupportedDirectionReversal(
                previous,
                entry,
                next,
                medianStepDx,
                medianStepDy
            )
            totalDeviation += (deviation / translationLimit.coerceAtLeast(0.001f)).coerceAtMost(4f)
            var invalidReason = when {
                deviation > translationLimit -> "transform_sequence_translation_jump"
                rotationDeviation > rotationLimit -> "transform_sequence_rotation_jump"
                directionReversal -> "transform_sequence_direction_reversal"
                else -> null
            }
            if (invalidReason == null) {
                replace(
                    ordered,
                    entry.frameId,
                    entry.registration.copy(
                        transformSequenceScore = sequenceEntryScore(deviation, translationLimit),
                        transformSequenceDeviation = deviation,
                        neighborTransformDelta = currentNeighborDelta
                    )
                )
                return@forEach
            }

            val retry = retryTranslationOnly(entry)
            if (retry != null && retry.isReliable && retry.matchedStars >= StarSimilarityRegistrar.MIN_MATCHED_STARS) {
                val retryEntry = entry.copy(registration = retry)
                val retryDeviation = localTranslationDeviation(
                    retryEntry,
                    previous,
                    next,
                    medianStepDx,
                    medianStepDy
                )
                val retryRotationDeviation = localRotationDeviation(retryEntry, previous, next, medianRotationStep)
                if (retryDeviation <= translationLimit && retryRotationDeviation <= rotationLimit) {
                    retryIds += entry.frameId
                    replace(
                        ordered,
                        entry.frameId,
                        retry.copy(
                            rawDx = entry.registration.dx,
                            rawDy = entry.registration.dy,
                            rawRotationRadians = entry.registration.rotationRadians,
                            transformRetryUsed = true,
                            transformSequenceScore = sequenceEntryScore(retryDeviation, translationLimit),
                            transformSequenceDeviation = retryDeviation,
                            neighborTransformDelta = neighborDelta(retryEntry, previous)
                        )
                    )
                    invalidReason = null
                }
            }
            if (invalidReason != null) {
                rejectedIds += entry.frameId
                replace(
                    ordered,
                    entry.frameId,
                    entry.registration.copy(
                        isReliable = false,
                        rejectionReason = invalidReason,
                        transformSequenceScore = 0f,
                        transformSequenceDeviation = deviation,
                        neighborTransformDelta = currentNeighborDelta,
                        transformRetryUsed = retry != null
                    )
                )
            }
        }
        val denominator = (reliable.size - 1).coerceAtLeast(1)
        val score = (1f - totalDeviation / denominator * 0.25f -
            rejectedIds.size.toFloat() / denominator * 0.5f).coerceIn(0f, 1f)
        return TransformSequenceValidation(ordered, score, rejectedIds, retryIds)
    }

    private fun localTranslationDeviation(
        current: OrderedRegistration,
        previous: OrderedRegistration?,
        next: OrderedRegistration?,
        medianStepDx: Float,
        medianStepDy: Float
    ): Float = when {
        previous != null && next != null -> minOf(
            hypot(
                current.registration.dx - previous.registration.dx - medianStepDx,
                current.registration.dy - previous.registration.dy - medianStepDy
            ),
            hypot(
                next.registration.dx - current.registration.dx - medianStepDx,
                next.registration.dy - current.registration.dy - medianStepDy
            )
        )
        previous != null -> hypot(
            current.registration.dx - previous.registration.dx - medianStepDx,
            current.registration.dy - previous.registration.dy - medianStepDy
        )
        next != null -> hypot(
            next.registration.dx - current.registration.dx - medianStepDx,
            next.registration.dy - current.registration.dy - medianStepDy
        )
        else -> 0f
    }

    private fun localRotationDeviation(
        current: OrderedRegistration,
        previous: OrderedRegistration?,
        next: OrderedRegistration?,
        medianStep: Float
    ): Float = when {
        previous != null && next != null -> minOf(
            abs(
                current.registration.rotationRadians -
                    previous.registration.rotationRadians - medianStep
            ),
            abs(
                next.registration.rotationRadians -
                    current.registration.rotationRadians - medianStep
            )
        )
        previous != null -> abs(
            current.registration.rotationRadians - previous.registration.rotationRadians - medianStep
        )
        next != null -> abs(
            next.registration.rotationRadians - current.registration.rotationRadians - medianStep
        )
        else -> 0f
    }

    private fun hasUnsupportedDirectionReversal(
        previous: OrderedRegistration?,
        current: OrderedRegistration,
        next: OrderedRegistration?,
        medianStepDx: Float,
        medianStepDy: Float
    ): Boolean {
        if (previous == null || next == null) return false
        val firstDx = current.registration.dx - previous.registration.dx
        val firstDy = current.registration.dy - previous.registration.dy
        val secondDx = next.registration.dx - current.registration.dx
        val secondDy = next.registration.dy - current.registration.dy
        val firstLength = hypot(firstDx, firstDy)
        val secondLength = hypot(secondDx, secondDy)
        if (firstLength < MIN_DIRECTION_STEP_PX || secondLength < MIN_DIRECTION_STEP_PX) return false
        val firstDeviation = hypot(firstDx - medianStepDx, firstDy - medianStepDy)
        val secondDeviation = hypot(secondDx - medianStepDx, secondDy - medianStepDy)
        return firstDeviation > MIN_TRANSLATION_DEVIATION_PX &&
            secondDeviation > MIN_TRANSLATION_DEVIATION_PX &&
            firstDx * secondDx + firstDy * secondDy < 0f
    }

    private fun sequenceEntryScore(deviation: Float, limit: Float): Float =
        (1f - deviation / limit.coerceAtLeast(0.001f)).coerceIn(0f, 1f)

    private fun neighborDelta(
        current: OrderedRegistration,
        previous: OrderedRegistration?
    ): Float = previous?.let {
        hypot(
            current.registration.dx - it.registration.dx,
            current.registration.dy - it.registration.dy
        )
    } ?: 0f

    private fun replace(
        values: MutableList<OrderedRegistration>,
        frameId: String,
        registration: RegistrationResult
    ) {
        val index = values.indexOfFirst { it.frameId == frameId }
        if (index >= 0) values[index] = values[index].copy(registration = registration)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private fun lowerMedian(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    companion object {
        private const val MAD_MULTIPLIER = 3f
        private const val MIN_TRANSLATION_DEVIATION_PX = 1.25f
        private val MIN_ROTATION_DEVIATION_RADIANS = Math.toRadians(0.15).toFloat()
        private const val MIN_DIRECTION_STEP_PX = 0.75f
    }
}
