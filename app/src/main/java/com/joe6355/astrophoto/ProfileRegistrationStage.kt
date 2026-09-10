package com.joe6355.astrophoto

import android.util.Log
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.UUID
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReport
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.registration.FrameAcceptanceDecision
import com.joe6355.astrophoto.processing.jpeg.v2.registration.FullResolutionRefinementResult
import com.joe6355.astrophoto.processing.jpeg.v2.registration.FullResolutionRegistrationRefiner
import com.joe6355.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import com.joe6355.astrophoto.processing.jpeg.v2.registration.ProfileRegistrationCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.registration.FullResolutionCheckpoint
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StellarCentroidFrameRefiner
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StellarCentroidRefinementPolicy
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StellarCentroidRefinementResult
import com.joe6355.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.registration.ExpectedSequenceMotionModel
import com.joe6355.astrophoto.processing.jpeg.v2.registration.OrderedRegistration
import com.joe6355.astrophoto.processing.jpeg.v2.registration.TransformSequenceValidator
import com.joe6355.astrophoto.processing.jpeg.v2.registration.scaledToFullResolution
import com.joe6355.astrophoto.processing.jpeg.v2.registration.buildTemporalFeatureFrames
import com.joe6355.astrophoto.processing.jpeg.v2.registration.rankedReferenceRecoveryCandidates
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.ArgbFrameDiskCache
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.CachedArgbFrame
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.FileBackedArgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.SamplingFidelityDiagnostic
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.SamplingFidelityResult
import com.joe6355.astrophoto.processing.jpeg.v2.memory.ImageAllocationEstimate
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import com.joe6355.astrophoto.JpegStacker.Companion.PROFILE_REGISTRATION_TAG

private const val MAX_FULL_RES_REFERENCE_RECOVERY_ATTEMPTS = 3
private const val FULL_RES_CACHE_RESERVE_BYTES = 32L * 1024L * 1024L

internal data class ProvisionalProfileRegistrationResult(
    val allRegistrationsByKey: Map<String, RegistrationResult>,
    val acceptedFrames: List<AcceptedProfileFrame>,
    val scaleX: Float,
    val scaleY: Float,
    val fullVelocityScale: Float,
    val sequenceScore: Float,
    val sequenceSmoothnessScore: Float,
    val sequencePriorAgreementScore: Float
)

internal data class FullResolutionReferencePreparationResult(
    val selection: ProfileFrameSelectionPreparation,
    val diagnostics: SequenceAwareRegistrationDiagnostics,
    val provisional: ProvisionalProfileRegistrationResult,
    val fullResolution: FullResolutionPreparationResult,
    val warnings: List<String>
)

/** Apply the same scaling and sequence gate for the primary and recovered references. */
internal fun prepareProvisionalProfileRegistration(
    selection: ProfileFrameSelectionPreparation,
    diagnostics: SequenceAwareRegistrationDiagnostics,
    captureIndexByFrameKey: Map<String, Int>
): ProvisionalProfileRegistrationResult {
    val selectedReference = selection.selectedReference
    val scaleX = selection.targetWidth.toFloat() /
        selectedReference.analysis.width.coerceAtLeast(1)
    val scaleY = selection.targetHeight.toFloat() /
        selectedReference.analysis.height.coerceAtLeast(1)
    val allRegistrationsByKey = diagnostics.registrations
        .mapValuesTo(linkedMapOf()) { (_, registration) ->
            registration.scaledToFullResolution(scaleX, scaleY)
        }
    val acceptedFrames = selection.selectedFrames.mapNotNull { frame ->
        val registration = checkNotNull(allRegistrationsByKey[frame.key])
        if (!registration.isReliable) return@mapNotNull null
        AcceptedProfileFrame(
            frame = frame,
            analysis = checkNotNull(selection.analyzedByFrameKey[frame.key]).analysis,
            registration = registration,
            captureIndex = captureIndexByFrameKey.getValue(frame.key)
        )
    }.toMutableList()
    val fullVelocityScale = (scaleX + scaleY) * 0.5f
    val sequenceValidation = TransformSequenceValidator().validate(
        acceptedFrames.map { accepted ->
            OrderedRegistration(
                frameId = accepted.frame.key,
                captureIndex = accepted.captureIndex,
                isReference = accepted.frame.key == selectedReference.frame.key,
                registration = accepted.registration
            )
        },
        expectedMotionModel = ExpectedSequenceMotionModel(
            velocityX = diagnostics.model.velocityX * scaleX,
            velocityY = diagnostics.model.velocityY * scaleY,
            referenceIndex = diagnostics.model.referenceIndex,
            residual = diagnostics.model.residual * fullVelocityScale,
            motionObservable = diagnostics.model.motionObservable,
            verificationScore = diagnostics.verification.selectedModel.score
        )
    )
    val validatedByKey = sequenceValidation.registrations.associate {
        it.frameId to it.registration
    }
    acceptedFrames.replaceAll { accepted ->
        accepted.copy(registration = validatedByKey[accepted.frame.key] ?: accepted.registration)
    }
    acceptedFrames.removeAll { !it.registration.isReliable }
    validatedByKey.forEach { (key, registration) ->
        allRegistrationsByKey[key] = registration
    }
    return ProvisionalProfileRegistrationResult(
        allRegistrationsByKey = allRegistrationsByKey,
        acceptedFrames = acceptedFrames,
        scaleX = scaleX,
        scaleY = scaleY,
        fullVelocityScale = fullVelocityScale,
        sequenceScore = sequenceValidation.score,
        sequenceSmoothnessScore = sequenceValidation.smoothnessScore,
        sequencePriorAgreementScore = sequenceValidation.motionModelAgreementScore
    )
}

/**
 * Keep reference recovery inside the registration stage. Every candidate must pass the
 * unchanged thumbnail, sequence and full-resolution gates before it can replace the primary.
 */
internal suspend fun JpegStacker.prepareFullResolutionWithReferenceRecovery(
    checkpointStore: ProfileRegistrationCheckpointStore,
    initialSelection: ProfileFrameSelectionPreparation,
    startingSelection: ProfileFrameSelectionPreparation,
    startingDiagnostics: SequenceAwareRegistrationDiagnostics,
    startingProvisional: ProvisionalProfileRegistrationResult,
    dimensionsByFrameKey: Map<String, Pair<Int, Int>>,
    captureIndexByFrameKey: Map<String, Int>,
    profile: AstroProcessingProfile,
    userApprovedInsufficientFrames: Boolean,
    temporaryFiles: TemporaryPipelineFiles,
    memoryBudget: JpegMemoryBudget,
    memoryTracker: PipelineMemoryTracker,
    onProgress: suspend (message: String, current: Int, total: Int) -> Unit
): FullResolutionReferencePreparationResult {
    suspend fun refine(
        selection: ProfileFrameSelectionPreparation,
        diagnostics: SequenceAwareRegistrationDiagnostics,
        provisional: ProvisionalProfileRegistrationResult
    ): FullResolutionPreparationResult {
        val pixelCount = selection.targetWidth.toLong() * selection.targetHeight
        val requiredCacheBytes = pixelCount * Int.SIZE_BYTES * provisional.acceptedFrames.size
        val requiredTemporaryBytes = requiredCacheBytes + pixelCount * 28L
        require(
            availableTemporaryBytes(temporaryFiles.directory) >=
                requiredTemporaryBytes + FULL_RES_CACHE_RESERVE_BYTES
        ) {
            "Недостаточно временного места для полноразмерной JPEG-обработки"
        }
        return prepareAndRefineFullResolutionFrames(
            checkpointStore = checkpointStore,
            provisionalFrames = provisional.acceptedFrames,
            selectedReference = selection.selectedReference,
            targetWidth = selection.targetWidth,
            targetHeight = selection.targetHeight,
            scaleX = provisional.scaleX,
            scaleY = provisional.scaleY,
            fullVelocityScale = provisional.fullVelocityScale,
            registrationDiagnostics = diagnostics,
            fullResolutionSkyMask = scaleSkyMask(
                selection.selectedReference.skyMask.mask,
                selection.targetWidth,
                selection.targetHeight
            ),
            temporaryFiles = temporaryFiles,
            memoryBudget = memoryBudget,
            memoryTracker = memoryTracker,
            onProgress = onProgress
        )
    }

    val fullResolution = refine(startingSelection, startingDiagnostics, startingProvisional)
    if (
        fullResolution.acceptedFrames.size >= profile.minimumFrames ||
        userApprovedInsufficientFrames
    ) {
        return FullResolutionReferencePreparationResult(
            startingSelection,
            startingDiagnostics,
            startingProvisional,
            fullResolution,
            emptyList()
        )
    }

    val originalDiagnostics = startingDiagnostics
    val featureFrames = buildTemporalFeatureFrames(
        initialSelection.selectedFrames,
        initialSelection.analysesByFrameKey,
        captureIndexByFrameKey
    )
    val candidates = rankedReferenceRecoveryCandidates(
        initialSelection.selectedFrames.map {
            initialSelection.analysesByFrameKey.getValue(it.key)
        },
        captureIndexByFrameKey,
        startingSelection.selectedReference.frame.key
    )
    var fullResolutionAttempts = 0
    for ((candidateIndex, scoredCandidate) in candidates.withIndex()) {
        if (fullResolutionAttempts >= MAX_FULL_RES_REFERENCE_RECOVERY_ATTEMPTS) break
        currentCoroutineContext().ensureActive()
        val candidateKey = scoredCandidate.analysis.id
        withContext(Dispatchers.Main.immediate) {
            onProgress(
                "Проверка другой опоры ${candidateIndex + 1} из ${candidates.size}",
                candidateIndex + 1,
                candidates.size
            )
        }
        val candidateDiagnostics = registerProfileFramesWithWatchdog(
            featureFrames,
            candidateKey,
            scoredCandidate.analysis.width,
            scoredCandidate.analysis.height
        )
        if (candidateDiagnostics.registrations.values.count { it.isReliable } <
            profile.minimumFrames
        ) continue
        val candidateSelection = ProfileFrameSelectionCoordinator.withReference(
            initialSelection,
            candidateKey,
            dimensionsByFrameKey
        )
        val candidateProvisional = prepareProvisionalProfileRegistration(
            candidateSelection,
            candidateDiagnostics,
            captureIndexByFrameKey
        )
        if (candidateProvisional.acceptedFrames.size < profile.minimumFrames) continue
        fullResolutionAttempts++
        checkpointStore.clearFullResolution()
        val candidateFullResolution = refine(
            candidateSelection,
            candidateDiagnostics,
            candidateProvisional
        )
        Log.i(
            PROFILE_REGISTRATION_TAG,
            "fullResolutionReferenceRecoveryAttempt=$fullResolutionAttempts/" +
                "$MAX_FULL_RES_REFERENCE_RECOVERY_ATTEMPTS " +
                "frame=${candidateSelection.selectedReference.frame.fileName} " +
                "accepted=${candidateFullResolution.acceptedFrames.size}"
        )
        if (candidateFullResolution.acceptedFrames.size < profile.minimumFrames) {
            candidateFullResolution.cachedFrames.forEach { (_, cached) -> cached.file.delete() }
            continue
        }
        fullResolution.cachedFrames.forEach { (_, cached) -> cached.file.delete() }
        try {
            checkpointStore.write(candidateDiagnostics)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(
                "AstroPhotoCheckpoint",
                "Recovered reference checkpoint unavailable",
                error
            )
        }
        return FullResolutionReferencePreparationResult(
            selection = candidateSelection,
            diagnostics = candidateDiagnostics,
            provisional = candidateProvisional,
            fullResolution = candidateFullResolution,
            warnings = listOf(
                "Опорный кадр заменён после полноразмерной проверки: " +
                    candidateSelection.selectedReference.frame.fileName
            )
        )
    }

    try {
        checkpointStore.clearFullResolution()
        checkpointStore.write(originalDiagnostics)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w("AstroPhotoCheckpoint", "Unable to restore initial reference checkpoint", error)
    }
    return FullResolutionReferencePreparationResult(
        startingSelection,
        startingDiagnostics,
        startingProvisional,
        fullResolution,
        emptyList()
    )
}

internal suspend fun JpegStacker.prepareAndRefineFullResolutionFrames(
    checkpointStore: ProfileRegistrationCheckpointStore?,
    provisionalFrames: List<AcceptedProfileFrame>,
    selectedReference: ProfileAnalyzedFrame,
    targetWidth: Int,
    targetHeight: Int,
    scaleX: Float,
    scaleY: Float,
    fullVelocityScale: Float,
    registrationDiagnostics: SequenceAwareRegistrationDiagnostics,
    fullResolutionSkyMask: SkyMask,
    temporaryFiles: TemporaryPipelineFiles,
    memoryBudget: JpegMemoryBudget,
    memoryTracker: PipelineMemoryTracker,
    onProgress: suspend (message: String, current: Int, total: Int) -> Unit
): FullResolutionPreparationResult {
    val context = currentCoroutineContext()
    val decodeEstimate = ImageAllocationEstimate.bitmap(
        targetWidth,
        targetHeight,
        "integration-frame-decode"
    )
    memoryBudget.requireAllocation(decodeEstimate)
    memoryTracker.recordBoundary("integration-frame-decode", decodeEstimate.bytes, 1)
    // A recovery attempt shares the run directory with the primary. Keep its cache distinct
    // so the original result remains usable if every replacement reference fails.
    val cacheAttemptId = UUID.randomUUID().toString()
    val provisionalCachedFrames = provisionalFrames.mapIndexed { index, accepted ->
        context.ensureActive()
        withContext(Dispatchers.Main.immediate) {
            onProgress(
                "Preparing full-resolution frame ${index + 1} of ${provisionalFrames.size}",
                index + 1,
                provisionalFrames.size
            )
        }
        val bitmap = decodeMedianFrame(accepted.frame, targetWidth, targetHeight)
            ?: error("Не удалось прочитать кадр: ${accepted.frame.fileName}")
        val cached = try {
            ArgbFrameDiskCache.write(
                bitmap,
                temporaryFiles.file("frame-$cacheAttemptId-${index.toString().padStart(3, '0')}.argb"),
                accepted.registration.referenceToSourceTransform()
            ) { row ->
                if (row % 64 == 0) context.ensureActive()
            }
        } finally {
            bitmap.recycle()
        }
        accepted to cached
    }
    val initialRegistrations = provisionalFrames.associate { it.frame.key to it.registration }
    val restored = checkpointStore?.readFullResolution()
    if (restored != null && restored.matches(targetWidth, targetHeight, initialRegistrations)) {
        val accepted = provisionalCachedFrames.mapNotNull { (frame, cached) ->
            val registration = restored.finalRegistrations.getValue(frame.frame.key)
            if (!registration.isReliable) {
                cached.file.delete()
                null
            } else {
                frame.copy(registration = registration) to cached.copy(
                    referenceToSourceTransform = registration.referenceToSourceTransform()
                )
            }
        }
        withContext(Dispatchers.Main.immediate) {
            onProgress("Full-resolution alignment восстановлен", provisionalFrames.size, provisionalFrames.size)
        }
        return FullResolutionPreparationResult(accepted.map { it.first }, accepted,
            restored.finalRegistrations, restored.refinements, restored.centroids,
            restored.samplingFidelity, restored.warnings)
    }
    if (restored != null) checkpointStore.clearFullResolution()
    val patches = buildFullResolutionStarPatches(
        selectedReference = selectedReference,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        fullResolutionSkyMask = fullResolutionSkyMask,
        registrationDiagnostics = registrationDiagnostics
    )
    val referenceCached = checkNotNull(
        provisionalCachedFrames.firstOrNull { it.first.frame.key == selectedReference.frame.key }
    ).second
    val finalFrames = mutableListOf<Pair<AcceptedProfileFrame, CachedArgbFrame>>()
    val finalRegistrations = linkedMapOf<String, RegistrationResult>()
    val refinementResults = linkedMapOf<String, FullResolutionRefinementResult>()
    val centroidResults = linkedMapOf<String, StellarCentroidRefinementResult>()
    val warnings = mutableListOf<String>()
    val refiner = FullResolutionRegistrationRefiner()
    val centroidRefiner = StellarCentroidFrameRefiner(rotationDiagnostic = {
        Log.i(PROFILE_REGISTRATION_TAG, it)
    })
    provisionalCachedFrames.forEachIndexed { index, (accepted, cached) ->
        context.ensureActive()
        withContext(Dispatchers.Main.immediate) {
            onProgress(
                "Refining full-resolution frame ${index + 1} of ${provisionalFrames.size}",
                index + 1,
                provisionalFrames.size
            )
        }
        val results = FileBackedArgbPixelSource(referenceCached, 32).use { referenceSource ->
            FileBackedArgbPixelSource(cached, 32).use { candidateSource ->
                fun refineAt(initial: com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform):
                    Pair<FullResolutionRefinementResult, StellarCentroidRefinementResult> {
                    val zncc = refiner.refine(
                        frameId = accepted.frame.key,
                        isReference = accepted.frame.key == selectedReference.frame.key,
                        reference = referenceSource,
                        candidate = candidateSource,
                        initialTransform = initial,
                        patches = patches,
                        analysisScaleUncertainty = (maxOf(scaleX, scaleY) * 0.5f).coerceIn(0f, 1f),
                        stage10Residual = accepted.registration.residualError
                            .takeIf { it.isFinite() }?.times(fullVelocityScale) ?: 3f,
                        sequenceResidual = registrationDiagnostics.model.residual * fullVelocityScale,
                        stage10Confidence = accepted.registration.confidence,
                        cancellationCheck = { context.ensureActive() }
                    )
                    val centroid = centroidRefiner.refine(
                        frameId = accepted.frame.key,
                        isReference = accepted.frame.key == selectedReference.frame.key,
                        reference = referenceSource,
                        candidate = candidateSource,
                        initialTransform = initial,
                        znccTransform = zncc.refinedTransform,
                        patches = patches,
                        analysisScaleUncertainty = (maxOf(scaleX, scaleY) * 0.5f).coerceIn(0f, 1f),
                        stage10Residual = accepted.registration.residualError
                            .takeIf { it.isFinite() }?.times(fullVelocityScale) ?: 3f,
                        sequenceResidual = registrationDiagnostics.model.residual * fullVelocityScale,
                        stage10Confidence = accepted.registration.confidence,
                        cancellationCheck = { context.ensureActive() }
                    )
                    return zncc to centroid
                }
                val initial = accepted.registration.referenceToSourceTransform()
                var refined = refineAt(initial)
                var rotationFallbackUsed = false
                if (initial.rotationRadians != 0f && !refined.second.accepted) {
                    val fallback = com.joe6355.astrophoto.processing.jpeg.v2.model
                        .ReferenceToSourceTransform(
                            accepted.registration.rawDx,
                            accepted.registration.rawDy
                        )
                    refined = refineAt(fallback)
                    rotationFallbackUsed = true
                }
                val zncc = refined.first
                val rawCentroid = refined.second
                val identityPath = registrationDiagnostics.frameAcceptancePaths[
                    accepted.frame.key
                ] == FrameAcceptanceDecision.PATH_VERIFIED_IDENTITY
                val stationaryProvisionalPath = registrationDiagnostics.frameAcceptancePaths[
                    accepted.frame.key
                ] == FrameAcceptanceDecision.PATH_STATIONARY_PROVISIONAL
                val centroid = if (identityPath || stationaryProvisionalPath && !rawCentroid.accepted) {
                    val identityDecision = StellarCentroidRefinementPolicy()
                        .decideVerifiedIdentity(rawCentroid.verification)
                    rawCentroid.copy(
                        refinedTransform = com.joe6355.astrophoto.processing.jpeg.v2.model
                            .ReferenceToSourceTransform.Identity,
                        correctionDx = 0f,
                        correctionDy = 0f,
                        accepted = identityDecision.accepted,
                        rejectionReason = identityDecision.rejectionReason
                    )
                } else {
                    rawCentroid
                }
                Triple(zncc, centroid, rotationFallbackUsed)
            }
        }
        val result = results.first
        val centroidResult = results.second
        val rotationFallbackUsed = results.third
        refinementResults[accepted.frame.key] = result
        centroidResults[accepted.frame.key] = centroidResult
        val finalRegistration = accepted.registration
            .withTransform(centroidResult.refinedTransform)
            .copy(
                matchedStars = centroidResult.attemptedStarCount,
                inlierStars = centroidResult.acceptedStarCount,
                residualError = centroidResult.medianResidual,
                confidence = (
                    accepted.registration.confidence * 0.35f +
                        centroidResult.confidence * 0.65f
                    ).coerceIn(0f, 1f),
                isReliable = centroidResult.accepted,
                rejectionReason = centroidResult.rejectionReason,
                registrationModel = when {
                    accepted.frame.key == selectedReference.frame.key -> "REFERENCE_IDENTITY"
                    registrationDiagnostics.frameAcceptancePaths[accepted.frame.key] ==
                        FrameAcceptanceDecision.PATH_VERIFIED_IDENTITY -> "VERIFIED_IDENTITY"
                    registrationDiagnostics.frameAcceptancePaths[accepted.frame.key] ==
                        FrameAcceptanceDecision.PATH_STATIONARY_PROVISIONAL &&
                        centroidResult.refinedTransform == com.joe6355.astrophoto.processing.jpeg.v2.model
                            .ReferenceToSourceTransform.Identity -> "VERIFIED_IDENTITY"
                    rotationFallbackUsed -> "TRANSLATION_FALLBACK_FULL_RESOLUTION"
                    centroidResult.refinedTransform.rotationRadians != 0f -> "FIXED_SCALE_RIGID_REFINED"
                    else -> "STELLAR_CENTROID_REFINED"
                },
                scaleFixed = true,
                rotationAllowed = centroidResult.refinedTransform.rotationRadians != 0f,
                rotationRejectionReason = when {
                    rotationFallbackUsed -> "rotation_failed_full_resolution_verification"
                    centroidResult.refinedTransform.rotationRadians != 0f -> null
                    else -> accepted.registration.rotationRejectionReason
                }
            )
        finalRegistrations[accepted.frame.key] = finalRegistration
        if (centroidResult.accepted) {
            val finalFrame = accepted.copy(registration = finalRegistration)
            finalFrames += finalFrame to cached.copy(
                referenceToSourceTransform = centroidResult.refinedTransform
            )
        } else {
            cached.file.delete()
            warnings += "Кадр ${accepted.frame.fileName} отклонён full-resolution проверкой: " +
                (centroidResult.rejectionReason ?: "centroid refinement failed")
        }
        logFullResolutionRefinement(accepted.frame.fileName, result)
        logStellarCentroidRefinement(accepted.frame.fileName, centroidResult)
    }
    val samplingFidelity = FileBackedArgbPixelSource(referenceCached, 32).use { source ->
        SamplingFidelityDiagnostic().measure(source, patches)
    }
    try {
        checkpointStore?.writeFullResolution(FullResolutionCheckpoint(
            targetWidth, targetHeight, initialRegistrations, finalRegistrations,
            refinementResults, centroidResults, samplingFidelity, warnings
        ))
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Log.w("AstroPhotoCheckpoint", "Full-resolution checkpoint unavailable", error)
    }
    return FullResolutionPreparationResult(
        acceptedFrames = finalFrames.map { it.first },
        cachedFrames = finalFrames,
        finalRegistrationsByKey = finalRegistrations,
        refinementResultsByKey = refinementResults,
        centroidResultsByKey = centroidResults,
        samplingFidelity = samplingFidelity,
        warnings = warnings
    )
}

internal fun logSequenceIdentityVerification(
    diagnostics: SequenceAwareRegistrationDiagnostics
) {
    val identity = diagnostics.verification.identity
    Log.i(
        PROFILE_REGISTRATION_TAG,
        "sequenceIdentityRetention=${formatMetric(identity.referenceRetention)} " +
            "sequenceIdentityContrast=${formatMetric(identity.contrastRatio)} " +
            "sequenceIdentitySmear=${formatMetric(identity.smearRate)} " +
            "sequenceIdentityStars=${identity.reliableStarCount} " +
            "sequenceIdentityScore=${formatMetric(identity.score)}"
    )
}

internal fun logAnalysisRegistration(
    frameName: String,
    frameKey: String,
    captureIndex: Int,
    registration: RegistrationResult,
    diagnostics: SequenceAwareRegistrationDiagnostics,
    scaleX: Float,
    scaleY: Float
) {
    val predicted = diagnostics.model.predicted(captureIndex)
    val selectedHypothesis = diagnostics.model.acceptedFrameHypotheses[frameKey]
    val verification = diagnostics.verification.perFrame[frameKey]
    val identity = diagnostics.verification.perFrameComparisons[frameKey]?.identity
    val local = diagnostics.modelGuidedRegistrations[frameKey]
    Log.i(
        PROFILE_REGISTRATION_TAG,
        "frame=$frameName " +
            "modelScore=${formatMetric(diagnostics.model.score)} " +
            "predictedDx=${formatMetric(predicted.first * scaleX)} " +
            "predictedDy=${formatMetric(predicted.second * scaleY)} " +
            "predictionDifferenceDx=${formatMetric(registration.dx - predicted.first * scaleX)} " +
            "predictionDifferenceDy=${formatMetric(registration.dy - predicted.second * scaleY)} " +
            "rawHypothesisDx=${formatMetric((selectedHypothesis?.dx ?: 0f) * scaleX)} " +
            "rawHypothesisDy=${formatMetric((selectedHypothesis?.dy ?: 0f) * scaleY)} " +
            "selectedDx=${formatMetric(registration.dx)} selectedDy=${formatMetric(registration.dy)} " +
            "hypothesisRank=${diagnostics.selectedHypothesisRankPerFrame[frameKey] ?: -1} " +
            "movingSupport=${selectedHypothesis?.movingTrackSupport ?: 0} " +
            "stationarySupport=${selectedHypothesis?.stationaryTrackSupport ?: 0} " +
            "sequenceAgreement=${formatMetric(registration.transformSequenceScore)} " +
            "verificationRetention=${formatMetric(verification?.referenceRetention ?: 0f)} " +
            "verificationContrast=${formatMetric(verification?.contrastRatio ?: 0f)} " +
            "verificationSmear=${formatMetric(verification?.smearRate ?: 0f)} " +
            "identityRetention=${formatMetric(identity?.referenceRetention ?: 0f)} " +
            "identityContrast=${formatMetric(identity?.contrastRatio ?: 0f)} " +
            "identitySmear=${formatMetric(identity?.smearRate ?: 0f)} " +
            "localSearchRadius=${formatMetric(local?.searchRadius ?: 0f)} " +
            "localCorrectionDx=${formatMetric((local?.correctionDx ?: 0f) * scaleX)} " +
            "localCorrectionDy=${formatMetric((local?.correctionDy ?: 0f) * scaleY)} " +
            "localMatches=${local?.matchedStars ?: 0} localInliers=${local?.inlierStars ?: 0} " +
            "localResidual=${formatMetric(local?.residual ?: Float.POSITIVE_INFINITY)} " +
            "localConfidence=${formatMetric(local?.confidence ?: 0f)} " +
            "localReason=${local?.rejectionReason.orEmpty()} " +
            "retryUsed=${local?.retryUsed ?: false} " +
            "acceptancePath=${diagnostics.frameAcceptancePaths[frameKey].orEmpty()} " +
            "accepted=${registration.isReliable}"
    )
}

internal fun logFullResolutionRefinement(
    frameName: String,
    result: FullResolutionRefinementResult
) {
    Log.i(
        PROFILE_REGISTRATION_TAG,
        "frame=$frameName fullResolutionInitial=(${formatMetric(result.initialTransform.dx)}," +
            "${formatMetric(result.initialTransform.dy)}) refined=(${formatMetric(result.refinedTransform.dx)}," +
            "${formatMetric(result.refinedTransform.dy)}) correction=(${formatMetric(result.correctionDx)}," +
            "${formatMetric(result.correctionDy)}) searchRadius=${formatMetric(result.searchRadius)} " +
            "patches=${result.acceptedPatchCount}/${result.attemptedPatchCount} " +
            "medianResidual=${formatMetric(result.medianResidual)} " +
            "p90Residual=${formatMetric(result.percentile90Residual)} " +
            "retention=${formatMetric(result.verification.refined.retention)} " +
            "contrast=${formatMetric(result.verification.refined.contrastRatio)} " +
            "centroid=${formatMetric(result.verification.refined.centroidResidual)} " +
            "accepted=${result.accepted} reason=${result.rejectionReason.orEmpty()}"
    )
    result.patchDiagnostics.filterNot { it.accepted }.forEach { diagnostic ->
        Log.d(
            PROFILE_REGISTRATION_TAG,
            "frame=$frameName patch=(${formatMetric(diagnostic.x)},${formatMetric(diagnostic.y)}) " +
                "sector=${diagnostic.sector} rejected=${diagnostic.rejectionReason.orEmpty()}"
        )
    }
}

internal fun logStellarCentroidRefinement(
    frameName: String,
    result: StellarCentroidRefinementResult
) {
    Log.i(
        PROFILE_REGISTRATION_TAG,
        "frame=$frameName centroidInitial=(${formatMetric(result.initialTransform.dx)}," +
            "${formatMetric(result.initialTransform.dy)}) zncc=(${formatMetric(result.znccTransform.dx)}," +
            "${formatMetric(result.znccTransform.dy)}) refined=(${formatMetric(result.refinedTransform.dx)}," +
            "${formatMetric(result.refinedTransform.dy)}) correction=(${formatMetric(result.correctionDx)}," +
            "${formatMetric(result.correctionDy)}) stars=${result.acceptedStarCount}/${result.attemptedStarCount} " +
            "median=${formatMetric(result.medianResidual)} p90=${formatMetric(result.percentile90Residual)} " +
            "snr=${formatMetric(result.medianSnr)} fit=${formatMetric(result.medianFitResidual)} " +
            "retention=${formatMetric(result.verification.refined.retention)} " +
            "contrast=${formatMetric(result.verification.refined.contrastRatio)} " +
            "accepted=${result.accepted} reason=${result.rejectionReason.orEmpty()}"
    )
    result.diagnostics.filterNot { it.accepted }.forEach { diagnostic ->
        Log.d(
            PROFILE_REGISTRATION_TAG,
            "frame=$frameName centroid=(${formatMetric(diagnostic.x)},${formatMetric(diagnostic.y)}) " +
                "sector=${diagnostic.sector} rejected=${diagnostic.rejectionReason.orEmpty()}"
        )
    }
}

internal fun ProcessingReport.withFullResolutionRefinement(
    resultsByKey: Map<String, FullResolutionRefinementResult>,
    centroidResultsByKey: Map<String, StellarCentroidRefinementResult>,
    frameNameByKey: Map<String, String>,
    provisionalAcceptedFrameCount: Int,
    finalAcceptedFrameCount: Int,
    rejectedCount: Int,
    sampling: SamplingFidelityResult
): ProcessingReport {
    fun <T> named(selector: (FullResolutionRefinementResult) -> T): Map<String, T> =
        resultsByKey.mapKeys { frameNameByKey[it.key] ?: it.key }.mapValues { selector(it.value) }
    fun <T> centroidNamed(selector: (StellarCentroidRefinementResult) -> T): Map<String, T> =
        centroidResultsByKey.mapKeys { frameNameByKey[it.key] ?: it.key }.mapValues { selector(it.value) }
    val referenceCentroids = centroidResultsByKey.values.firstOrNull {
        it.initialTransform == com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform.Identity &&
            it.znccTransform == com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform.Identity
    }
    return copy(
        registrationSchemaVersion = "astrophoto.jpeg.registration/4",
        verificationCoordinateSpace = "ANALYSIS_THUMBNAIL_AND_FULL_RESOLUTION_STELLAR_CENTROID_PX",
        fullResolutionRefinementEnabled = true,
        provisionalAcceptedFrameCount = provisionalAcceptedFrameCount,
        finalAcceptedFrameCount = finalAcceptedFrameCount,
        fullResolutionRefinementRejectedCount = rejectedCount,
        fullResolutionInitialDxPerFrame = named { it.initialTransform.dx },
        fullResolutionInitialDyPerFrame = named { it.initialTransform.dy },
        fullResolutionRefinedDxPerFrame = named { it.refinedTransform.dx },
        fullResolutionRefinedDyPerFrame = named { it.refinedTransform.dy },
        fullResolutionCorrectionDxPerFrame = named { it.correctionDx },
        fullResolutionCorrectionDyPerFrame = named { it.correctionDy },
        fullResolutionAttemptedPatchCountPerFrame = named { it.attemptedPatchCount },
        fullResolutionAcceptedPatchCountPerFrame = named { it.acceptedPatchCount },
        fullResolutionRejectedPatchCountPerFrame = named { it.rejectedPatchCount },
        fullResolutionMedianPatchScorePerFrame = named { it.medianPatchScore },
        fullResolutionMedianResidualPerFrame = named { it.medianResidual },
        fullResolutionP90ResidualPerFrame = named { it.percentile90Residual },
        fullResolutionSpatialSectorCountPerFrame = named { it.spatialSectorCount },
        fullResolutionRefinementConfidencePerFrame = named { it.confidence },
        fullResolutionRefinementAcceptedPerFrame = named { it.accepted },
        fullResolutionRefinementReasonPerFrame = named { it.rejectionReason ?: "accepted" },
        fullResolutionPatchRetentionPerFrame = named { it.verification.refined.retention },
        fullResolutionPatchContrastRatioPerFrame = named { it.verification.refined.contrastRatio },
        fullResolutionPatchCentroidResidualPerFrame = named { it.verification.refined.centroidResidual },
        fullResolutionPatchWidthGrowthPerFrame = named { it.verification.refined.widthGrowth },
        fullResolutionPatchSmearPerFrame = named { it.verification.refined.smear },
        stellarCentroidRefinementEnabled = true,
        stellarCentroidSchemaVersion = "astrophoto.jpeg.stellar-centroid/1",
        centroidPrimaryUsedPerFrame = centroidNamed { it.centroidPrimaryUsed },
        znccSecondaryUsedPerFrame = centroidNamed { it.znccSecondaryUsed },
        znccFallbackUsedPerFrame = centroidNamed { it.znccFallbackUsed },
        referenceCentroidCount = referenceCentroids?.referenceCentroidCount ?: 0,
        referenceCentroidAcceptedCount = referenceCentroids?.referenceCentroidAcceptedCount ?: 0,
        referenceCentroidRejectedCount = referenceCentroids?.referenceCentroidRejectedCount ?: 0,
        centroidAttemptedStarCountPerFrame = centroidNamed { it.attemptedStarCount },
        centroidAcceptedStarCountPerFrame = centroidNamed { it.acceptedStarCount },
        centroidRejectedStarCountPerFrame = centroidNamed { it.rejectedStarCount },
        centroidSpatialSectorCountPerFrame = centroidNamed { it.spatialSectorCount },
        centroidInitialDxPerFrame = centroidNamed { it.initialTransform.dx },
        centroidInitialDyPerFrame = centroidNamed { it.initialTransform.dy },
        centroidRefinedDxPerFrame = centroidNamed { it.refinedTransform.dx },
        centroidRefinedDyPerFrame = centroidNamed { it.refinedTransform.dy },
        centroidCorrectionDxPerFrame = centroidNamed { it.correctionDx },
        centroidCorrectionDyPerFrame = centroidNamed { it.correctionDy },
        centroidMedianResidualPerFrame = centroidNamed { it.medianResidual },
        centroidP90ResidualPerFrame = centroidNamed { it.percentile90Residual },
        centroidMedianSnrPerFrame = centroidNamed { it.medianSnr },
        centroidMedianFitResidualPerFrame = centroidNamed { it.medianFitResidual },
        centroidConfidencePerFrame = centroidNamed { it.confidence },
        centroidRefinementAcceptedPerFrame = centroidNamed { it.accepted },
        centroidRefinementReasonPerFrame = centroidNamed { it.rejectionReason ?: "accepted" },
        centroidRetentionPerFrame = centroidNamed { it.verification.refined.retention },
        centroidContrastRatioPerFrame = centroidNamed { it.verification.refined.contrastRatio },
        centroidWidthGrowthPerFrame = centroidNamed { it.verification.refined.widthGrowth },
        centroidEllipticityGrowthPerFrame = centroidNamed { it.verification.refined.ellipticityGrowth },
        centroidSmearPerFrame = centroidNamed { it.verification.refined.smear },
        samplingKernel = sampling.kernel,
        samplingIdentityContrastRatio = sampling.identityContrastRatio,
        samplingProductionContrastRatio = sampling.productionContrastRatio,
        samplingAlternativeContrastRatio = sampling.alternativeContrastRatio
    )
}

internal fun JpegStacker.buildFullResolutionStarPatches(
    selectedReference: ProfileAnalyzedFrame,
    targetWidth: Int,
    targetHeight: Int,
    fullResolutionSkyMask: SkyMask,
    registrationDiagnostics: SequenceAwareRegistrationDiagnostics
): List<FullResolutionStarPatch> {
    val scaled = scaleV2Stars(
        selectedReference.analysis.stars,
        selectedReference.analysis.width,
        selectedReference.analysis.height,
        targetWidth,
        targetHeight
    )
    return selectedReference.analysis.stars.zip(scaled).map { (analysisStar, fullStar) ->
        val sectorX = (fullStar.x / targetWidth * 3f).toInt().coerceIn(0, 2)
        val sectorY = (fullStar.y / targetHeight * 3f).toInt().coerceIn(0, 2)
        FullResolutionStarPatch(
            x = fullStar.x,
            y = fullStar.y,
            confidence = fullStar.confidence,
            localContrast = fullStar.localContrast,
            width = fullStar.width,
            ellipticity = fullStar.ellipticity,
            sector = sectorY * 3 + sectorX,
            motionCluster = registrationDiagnostics.trackAnalysis.clusterAt(
                selectedReference.frame.key,
                analysisStar
            ).let { cluster ->
                if (
                    !registrationDiagnostics.model.motionObservable &&
                    registrationDiagnostics.frameAcceptancePaths.values.any {
                        it == FrameAcceptanceDecision.PATH_VERIFIED_IDENTITY ||
                            it == FrameAcceptanceDecision.PATH_STATIONARY_PROVISIONAL
                    } &&
                    cluster == com.joe6355.astrophoto.processing.jpeg.v2.registration
                        .TemporalMotionCluster.STATIONARY_CAMERA_SPACE
                ) {
                    com.joe6355.astrophoto.processing.jpeg.v2.registration
                        .TemporalMotionCluster.UNSTABLE_OR_UNKNOWN
                } else {
                    cluster
                }
            },
            skyCoverage = fullResolutionPatchSkyCoverage(
                fullResolutionSkyMask,
                fullStar.x,
                fullStar.y
            )
        )
    }
}

internal fun fullResolutionPatchSkyCoverage(mask: SkyMask, x: Float, y: Float): Float {
    var sky = 0
    var total = 0
    for (offsetY in -7..7 step 3) for (offsetX in -7..7 step 3) {
        val sampleX = (x + offsetX).roundToInt()
        val sampleY = (y + offsetY).roundToInt()
        total++
        if (sampleX in 0 until mask.width && sampleY in 0 until mask.height &&
            mask.contains(sampleX, sampleY)
        ) sky++
    }
    return sky.toFloat() / total.coerceAtLeast(1)
}
