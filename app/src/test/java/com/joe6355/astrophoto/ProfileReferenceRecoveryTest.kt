package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorFrameObservation
import com.joe6355.astrophoto.processing.jpeg.v2.model.*
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ProfileReferenceRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val analyses = (0..4).map(::analysis)
    private val indices = analyses.mapIndexed { index, frame -> frame.id to index + 1 }.toMap()

    @Test fun successfulPrimaryIsReturnedUnchangedWithoutRetry() = runBlocking {
        val primary = diagnostics("f0", analyses.map { it.id }.toSet())
        val calls = mutableListOf<String>()
        val result = recover { key -> calls += key; primary }
        assertSame(primary, result)
        assertEquals(listOf("f0"), calls)
    }

    @Test fun equalQualityRetryUsesTemporalCenterDeterministically() = runBlocking {
        for (input in listOf(analyses, analyses.reversed())) {
            val calls = mutableListOf<String>()
            val result = registerWithReferenceRecovery(input, indices, "f0", 4) { key ->
                calls += key
                diagnostics(key, if (key == "f0") setOf(key) else analyses.map { it.id }.toSet())
            }
            assertEquals(listOf("f0", "f2"), calls)
            assertEquals(indices.getValue("f2"), result.referenceCaptureIndex)
        }
    }

    @Test fun fullResolutionRecoveryUsesTheSameDeterministicCandidateOrder() {
        assertEquals(
            listOf("f2", "f1", "f3", "f4"),
            rankedReferenceRecoveryCandidates(analyses.reversed(), indices, "f0")
                .map { it.analysis.id }
        )
    }

    @Test fun invalidSparseAndDifferentAnalysisSizeCannotBecomeRetryReference() = runBlocking {
        val input = analyses.map { when (it.id) {
            "f1" -> it.copy(clippingPercent = 90f, reliableStarCount = 9999)
            "f2" -> it.copy(width = 121)
            "f3" -> it.copy(stars = it.stars.take(2), reliableStarCount = 2)
            else -> it
        } }
        val calls = mutableListOf<String>()
        registerWithReferenceRecovery(input, indices, "f0", 4) { key ->
            calls += key
            diagnostics(key, setOf(key))
        }
        assertEquals(listOf("f0", "f4"), calls)
    }

    @Test fun unsuccessfulRetriesRetainOriginalFailure() = runBlocking {
        val primary = diagnostics("f0", setOf("f0"))
        val calls = mutableListOf<String>()
        val result = recover { key ->
            calls += key
            if (key == "f0") primary else diagnostics(key, setOf(key, "f0"))
        }
        assertSame(primary, result)
        assertEquals(5, calls.size)
        assertEquals(5, calls.toSet().size)
    }

    @Test fun cancellationIsNotSwallowedOrRetried() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            recover { key ->
                calls += key
                if (key != "f0") throw CancellationException("test cancellation")
                diagnostics(key, setOf(key))
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("f0", "f2"), calls)
        }
    }

    @Test fun realEngineRecoversSharedStarsWithoutRelaxingAcceptancePolicy() = runBlocking {
        val input = analyses.map { if (it.id == "f0") it.copy(stars = emptyList(), reliableStarCount = 0) else it }
        val features = input.map { TemporalFeatureFrame(it.id, indices.getValue(it.id), it.stars) }
        val engine = SequenceAwareRegistrationEngine()
        val initial = engine.register(features, "f0", 120, 90)
        assertEquals(1, initial.registrations.values.count { it.isReliable })
        val result = registerWithReferenceRecovery(input, indices, "f0", 4) { key ->
            engine.register(features, key, 120, 90)
        }
        assertEquals(4, result.registrations.values.count { it.isReliable })
        assertFalse(result.registrations.getValue("f0").isReliable)
        assertEquals(indices.getValue("f2"), result.referenceCaptureIndex)
    }

    @Test fun promotedReferenceUpdatesOrderFullResolutionAndMaskTogether() {
        val prepared = preparation()
        val dimensions = analyses.associate { it.id to (240 to 180) } + ("f2" to (480 to 360))
        val promoted = ProfileFrameSelectionCoordinator.withReference(prepared, "f2", dimensions)
        assertEquals("f2", promoted.selectedReference.frame.key)
        assertSame(prepared.analyzedByFrameKey.getValue("f2").skyMask, promoted.selectedReference.skyMask)
        assertEquals("f2", promoted.selectedFrames.first().key)
        assertEquals(prepared.selectedFrames.map { it.key }.toSet(), promoted.selectedFrames.map { it.key }.toSet())
        assertEquals(480, promoted.targetWidth)
        assertEquals(360, promoted.targetHeight)
        assertSame(prepared, ProfileFrameSelectionCoordinator.withReference(prepared,
            prepared.selectedReference.frame.key, dimensions))
    }

    @Test fun recoveredReferenceSurvivesCheckpointWithoutRecomputation() = runBlocking {
        val prepared = preparation()
        val store = ProfileRegistrationCheckpointStore.open(temporary.root, "session",
            AstroProcessingProfile.DEEP_SKY, prepared.selectedFrames,
            prepared.selectedFrames.map { it.key }, 120, 90, forTesting = true)
        val expected = diagnostics("f2", analyses.map { it.id }.toSet())
        restoreOrComputeRegistration(store, onRestored = {}) { expected }
        var restored = false
        val actual = restoreOrComputeRegistration(store, onRestored = { restored = true }) {
            error("Recovered registration must not be recomputed")
        }
        assertTrue(restored)
        assertEquals(expected.referenceCaptureIndex, actual.referenceCaptureIndex)
        assertEquals(expected.registrations, actual.registrations)
        val key = prepared.selectedFrames.single {
            indices.getValue(it.key) == actual.referenceCaptureIndex
        }.key
        val promoted = ProfileFrameSelectionCoordinator.withReference(prepared, key,
            analyses.associate { it.id to (240 to 180) })
        assertEquals("f2", promoted.selectedFrames.first().key)
    }

    @Test fun clearingFullResolutionEvidencePreservesRegistrationCheckpoint() {
        val prepared = preparation()
        val store = ProfileRegistrationCheckpointStore.open(
            temporary.root,
            "session-clear-full-resolution",
            AstroProcessingProfile.DEEP_SKY,
            prepared.selectedFrames,
            prepared.selectedFrames.map { it.key },
            120,
            90,
            forTesting = true
        )
        val expected = diagnostics("f2", analyses.map { it.id }.toSet())
        store.write(expected)
        store.clearFullResolution()
        assertEquals(expected.referenceCaptureIndex, store.read()?.referenceCaptureIndex)
    }

    @Test fun provisionalPreparationReappliesSequenceGateForPromotedReference() {
        val prepared = preparation()
        val promoted = ProfileFrameSelectionCoordinator.withReference(
            prepared,
            "f2",
            analyses.associate { it.id to (240 to 180) }
        )
        val result = prepareProvisionalProfileRegistration(
            promoted,
            diagnostics("f2", analyses.map { it.id }.toSet()),
            indices
        )
        assertEquals("f2", result.acceptedFrames.first().frame.key)
        assertEquals(analyses.size, result.acceptedFrames.size)
        assertEquals(2f, result.scaleX, 0f)
        assertEquals(2f, result.scaleY, 0f)
        assertTrue(result.allRegistrationsByKey.values.all { it.isReliable })
    }

    @Test fun productionRetriesOnlyBeforeMinimumGateAndKeepsFullResolutionBound() {
        val source = Files.readString(
            Path.of("src/main/java/com/joe6355/astrophoto/JpegStacker.kt")
        )
        val profile = source.substring(
            source.indexOf("suspend fun profileStack("),
            source.indexOf("suspend fun loadResultPreview(")
        )
        val stage = Files.readString(
            Path.of("src/main/java/com/joe6355/astrophoto/ProfileRegistrationStage.kt")
        )
        val firstRefinement = stage.indexOf("val fullResolution = refine(")
        val recoveryCandidates = stage.indexOf("val candidates = rankedReferenceRecoveryCandidates(")
        val recoveredRefinement = stage.indexOf("val candidateFullResolution = refine(")
        val stageCall = profile.indexOf("prepareFullResolutionWithReferenceRecovery(")
        val minimumGate = profile.indexOf("requireMinimumRegisteredFrames(")
        assertTrue(firstRefinement in 0 until recoveryCandidates)
        assertTrue(recoveryCandidates in 0 until recoveredRefinement)
        assertTrue(stageCall in 0 until minimumGate)
        assertTrue(stage.contains(
            "fullResolutionAttempts >= MAX_FULL_RES_REFERENCE_RECOVERY_ATTEMPTS"
        ))
        assertTrue(stage.contains(
            "candidateFullResolution.acceptedFrames.size < profile.minimumFrames"
        ))
        assertTrue(stage.contains("checkpointStore.clearFullResolution()"))
    }

    private suspend fun recover(register: suspend (String) -> SequenceAwareRegistrationDiagnostics) =
        registerWithReferenceRecovery(analyses, indices, "f0", 4, register = register)

    /** Controlled outcomes test recovery orchestration, not the registration acceptance algorithm. */
    private fun diagnostics(reference: String, accepted: Set<String>): SequenceAwareRegistrationDiagnostics {
        val actual = SequenceAwareRegistrationEngine().register(
            analyses.map { TemporalFeatureFrame(it.id, indices.getValue(it.id), it.stars) }, reference, 120, 90)
        return actual.copy(registrations = actual.registrations.mapValues { (key, registration) ->
            registration.copy(isReliable = key in accepted, rejectionReason = if (key in accepted) null else "test rejection")
        })
    }

    private fun preparation(): ProfileFrameSelectionPreparation {
        val analyzed = analyses.map { analysis ->
            ProfileAnalyzedFrame(SessionFrame(analysis.id, analysis.fileName, SessionFrameCategory.LIGHTS_JPEG,
                100L, 200L, analysis.fileName, null, null), analysis,
                SkyMaskResult(SkyMask.full(120, 90), 1f, false),
                PersistentSensorFrameObservation(analysis.id, indices.getValue(analysis.id), 120, 90, emptyList()))
        }
        return ProfileFrameSelectionCoordinator.prepare(analyzed, analyzed.map { it.frame }, indices,
            analyses.associate { it.id to (240 to 180) }, 30)
    }

    private fun analysis(index: Int) = FrameAnalysis(
        id = "f$index", fileName = "$index.jpg", width = 120, height = 90,
        stars = listOf(12f to 12f, 55f to 12f, 98f to 12f, 12f to 70f, 55f to 70f, 98f to 70f).map { (x, y) ->
            DetectedStar(x, y, 100f, 4f, 20f, 1.5f, 0.1f, 0.9f)
        }, reliableStarCount = 6, medianStarContrast = 20f, medianStarWidth = 2f,
        medianStarEllipticity = 0.2f, backgroundNoise = 2f, clippingPercent = 0f,
        exposureSuitability = 0.8f, decodeValid = true, alignmentSuitability = 0.8f,
        skyMaskConfidence = 0.9f, skyMaskUsedFallback = false, backgroundLevel = 20f
    )
}
