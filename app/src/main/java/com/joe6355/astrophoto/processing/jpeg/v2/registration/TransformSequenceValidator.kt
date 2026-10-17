package com.joe6355.astrophoto.processing.jpeg.v2.registration

import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.model.TransformPoint
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
    val retryFrameIds: List<String>,
    val smoothnessScore: Float = score,
    val motionModelAgreementScore: Float = 1f,
    val verificationScore: Float = 1f
)

class TransformSequenceValidator {
    /** Compare all motion at one reference point, retaining the original sampling transforms. */
    fun validate(
        values: List<OrderedRegistration>,
        expectedMotionModel: ExpectedSequenceMotionModel? = null,
        comparisonPoint: TransformPoint = TransformPoint(0f, 0f),
        retryTranslationOnly: (OrderedRegistration) -> RegistrationResult? = { null }
    ): TransformSequenceValidation {
        if (values.isEmpty()) return TransformSequenceValidation(emptyList(), 0f, emptyList(), emptyList())
        val ordered = values.sortedBy { it.captureIndex }.toMutableList()
        val reliable = ordered.filter { it.registration.isReliable }
        if (reliable.size < 3) {
            val priorLimit = motionPriorLimit(expectedMotionModel)
            val validated = ordered.mapIndexed { index, entry ->
                val priorDeviation = motionPriorDeviation(entry, expectedMotionModel, comparisonPoint)
                val disagreesWithPrior =
                    entry.registration.isReliable &&
                        !entry.isReference &&
                        expectedMotionModel?.motionObservable == true &&
                        priorDeviation > priorLimit
                entry.copy(
                    registration = entry.registration.copy(
                        isReliable = entry.registration.isReliable && !disagreesWithPrior,
                        rejectionReason = if (disagreesWithPrior) {
                            "transform_sequence_motion_prior_disagreement"
                        } else {
                            entry.registration.rejectionReason
                        },
                        transformSequenceScore = if (
                            entry.registration.isReliable && !disagreesWithPrior
                        ) 1f else 0f,
                        transformSequenceDeviation = priorDeviation,
                        neighborTransformDelta = neighborDelta(entry, ordered.getOrNull(index - 1), comparisonPoint)
                    )
                )
            }
            val smoothness = if (reliable.size >= 2) 1f else 0f
            val priorAgreement = priorAgreementScore(ordered, expectedMotionModel, comparisonPoint)
            return TransformSequenceValidation(
                validated,
                minOf(smoothness, priorAgreement),
                validated.filterNot { it.registration.isReliable }.map { it.frameId },
                emptyList(),
                smoothnessScore = smoothness,
                motionModelAgreementScore = priorAgreement,
                verificationScore = expectedMotionModel?.verificationScore ?: 1f
            )
        }

        val stepDx = reliable.zipWithNext { first, second ->
            displacement(second, comparisonPoint).x - displacement(first, comparisonPoint).x
        }
        val stepDy = reliable.zipWithNext { first, second ->
            displacement(second, comparisonPoint).y - displacement(first, comparisonPoint).y
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
        var totalPriorDeviation = 0f
        var priorComparisons = 0

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
                medianStepDy,
                comparisonPoint
            )
            val rotationDeviation = localRotationDeviation(entry, previous, next, medianRotationStep)
            val currentNeighborDelta = neighborDelta(entry, previous, comparisonPoint)
            val directionReversal = hasUnsupportedDirectionReversal(
                previous,
                entry,
                next,
                medianStepDx,
                medianStepDy,
                comparisonPoint
            )
            val priorDeviation = motionPriorDeviation(entry, expectedMotionModel, comparisonPoint)
            val priorLimit = motionPriorLimit(expectedMotionModel)
            if (expectedMotionModel?.motionObservable == true) {
                totalPriorDeviation += priorDeviation
                priorComparisons++
            }
            totalDeviation += (deviation / translationLimit.coerceAtLeast(0.001f)).coerceAtMost(4f)
            var invalidReason = when {
                expectedMotionModel?.motionObservable == true && priorDeviation > priorLimit ->
                    "transform_sequence_motion_prior_disagreement"
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
                    medianStepDy,
                    comparisonPoint
                )
                val retryRotationDeviation = localRotationDeviation(retryEntry, previous, next, medianRotationStep)
                val retryPriorDeviation = motionPriorDeviation(retryEntry, expectedMotionModel, comparisonPoint)
                if (
                    retryDeviation <= translationLimit &&
                    retryRotationDeviation <= rotationLimit &&
                    (expectedMotionModel?.motionObservable != true || retryPriorDeviation <= priorLimit)
                ) {
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
                            neighborTransformDelta = neighborDelta(retryEntry, previous, comparisonPoint)
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
        val priorAgreement = if (priorComparisons == 0) 1f else
            (1f - totalPriorDeviation / priorComparisons /
                motionPriorLimit(expectedMotionModel).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        return TransformSequenceValidation(
            ordered,
            minOf(score, priorAgreement),
            rejectedIds,
            retryIds,
            smoothnessScore = score,
            motionModelAgreementScore = priorAgreement,
            verificationScore = expectedMotionModel?.verificationScore ?: 1f
        )
    }

    private fun motionPriorDeviation(
        value: OrderedRegistration,
        prior: ExpectedSequenceMotionModel?,
        comparisonPoint: TransformPoint
    ): Float {
        if (prior == null) return 0f
        val predicted = prior.predictedTransform(value.captureIndex)
        val observed = displacement(value, comparisonPoint)
        return hypot(
            observed.x - predicted.dx,
            observed.y - predicted.dy
        )
    }

    private fun motionPriorLimit(prior: ExpectedSequenceMotionModel?): Float =
        if (prior?.motionObservable == true) {
            maxOf(MIN_PRIOR_DEVIATION_PX, prior.residual * 3f + PRIOR_RESIDUAL_FLOOR)
        } else {
            Float.POSITIVE_INFINITY
        }

    private fun priorAgreementScore(
        values: List<OrderedRegistration>,
        prior: ExpectedSequenceMotionModel?,
        comparisonPoint: TransformPoint
    ): Float {
        if (prior?.motionObservable != true) return 1f
        val comparable = values.filter { it.registration.isReliable && !it.isReference }
        if (comparable.isEmpty()) return 0f
        val limit = motionPriorLimit(prior)
        return (1f - comparable.map { motionPriorDeviation(it, prior, comparisonPoint) }.average().toFloat() /
            limit.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    }

    private fun localTranslationDeviation(
        current: OrderedRegistration,
        previous: OrderedRegistration?,
        next: OrderedRegistration?,
        medianStepDx: Float,
        medianStepDy: Float,
        comparisonPoint: TransformPoint
    ): Float {
        val currentPoint = displacement(current, comparisonPoint)
        val previousPoint = previous?.let { displacement(it, comparisonPoint) }
        val nextPoint = next?.let { displacement(it, comparisonPoint) }
        return when {
            previousPoint != null && nextPoint != null -> minOf(
                hypot(
                    currentPoint.x - previousPoint.x - medianStepDx,
                    currentPoint.y - previousPoint.y - medianStepDy
                ),
                hypot(
                    nextPoint.x - currentPoint.x - medianStepDx,
                    nextPoint.y - currentPoint.y - medianStepDy
                )
            )
            previousPoint != null -> hypot(
                currentPoint.x - previousPoint.x - medianStepDx,
                currentPoint.y - previousPoint.y - medianStepDy
            )
            nextPoint != null -> hypot(
                nextPoint.x - currentPoint.x - medianStepDx,
                nextPoint.y - currentPoint.y - medianStepDy
            )
            else -> 0f
        }
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
        medianStepDy: Float,
        comparisonPoint: TransformPoint
    ): Boolean {
        if (previous == null || next == null) return false
        val previousPoint = displacement(previous, comparisonPoint)
        val currentPoint = displacement(current, comparisonPoint)
        val nextPoint = displacement(next, comparisonPoint)
        val firstDx = currentPoint.x - previousPoint.x
        val firstDy = currentPoint.y - previousPoint.y
        val secondDx = nextPoint.x - currentPoint.x
        val secondDy = nextPoint.y - currentPoint.y
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
        previous: OrderedRegistration?,
        comparisonPoint: TransformPoint
    ): Float = previous?.let {
        val currentPoint = displacement(current, comparisonPoint)
        val previousPoint = displacement(it, comparisonPoint)
        hypot(
            currentPoint.x - previousPoint.x,
            currentPoint.y - previousPoint.y
        )
    } ?: 0f

    /** Origin offsets of a rotating transform are not its displacement at the model's anchor. */
    private fun displacement(value: OrderedRegistration, point: TransformPoint): TransformPoint {
        val registration = value.registration
        if (registration.rotationRadians == 0f && registration.scale == 1f) {
            return TransformPoint(registration.dx, registration.dy)
        }
        val mapped = registration.referenceToSourceTransform().mapOutputToSource(point.x, point.y)
        return TransformPoint(mapped.x - point.x, mapped.y - point.y)
    }

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
        private const val MIN_PRIOR_DEVIATION_PX = 1.5f
        private const val PRIOR_RESIDUAL_FLOOR = 0.75f
    }
}
