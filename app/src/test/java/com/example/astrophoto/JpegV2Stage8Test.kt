package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.registration.ExpectedSequenceMotionModel
import com.example.astrophoto.processing.jpeg.v2.registration.OrderedRegistration
import com.example.astrophoto.processing.jpeg.v2.registration.RegistrationSequenceVerifier
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceRegistrationCandidate
import com.example.astrophoto.processing.jpeg.v2.registration.SparseTranslationVotingEstimator
import com.example.astrophoto.processing.jpeg.v2.registration.StellarMotionModelEstimator
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureTrackBuilder
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalTrackAnalysis
import com.example.astrophoto.processing.jpeg.v2.registration.TransformSequenceValidator
import com.example.astrophoto.processing.jpeg.v2.registration.TranslationHypothesis
import com.example.astrophoto.processing.jpeg.v2.registration.scaledToFullResolution
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Developer replay: ./gradlew testDebugUnitTest --tests com.example.astrophoto.JpegV2Stage8Test */
class JpegV2Stage8Test {
    private val sequence = regressionSequence()
    private val tracks by lazy { TemporalFeatureTrackBuilder().build(sequence) }

    @Test fun stationaryStarLikeTracksAreClassifiedInCameraSpace() {
        assertTrue(tracks.stationaryTrackCount >= 8)
        assertTrue(tracks.tracks.filter {
            it.cluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE
        }.all { abs(it.velocityX) < 0.08f && abs(it.velocityY) < 0.08f })
    }

    @Test fun coherentMovingTracksAreClassifiedAsSkyMotionDespiteMissingObservations() {
        assertTrue(tracks.motionObservable)
        assertTrue(tracks.movingTrackCount >= 4)
        assertEquals(VELOCITY_X.toDouble(), tracks.coherentVelocityX.toDouble(), 0.18)
        assertEquals(VELOCITY_Y.toDouble(), tracks.coherentVelocityY.toDouble(), 0.18)
        assertTrue(tracks.tracks.any {
            it.cluster == TemporalMotionCluster.COHERENT_MOVING_SKY && it.observations.size < sequence.size
        })
    }

    @Test fun sparseVotingRetainsZeroAndNonZeroPeaksWithoutMagnitudeTieBreak() {
        val hypotheses = voteFor(sequence.last())
        assertTrue(hypotheses.any { kotlin.math.hypot(it.dx, it.dy) < 0.6f })
        val moving = hypotheses.firstOrNull {
            abs(it.dx - VELOCITY_X * 7) < 1f && abs(it.dy - VELOCITY_Y * 7) < 1f
        }
        assertTrue(hypotheses.toString(), moving != null)
        assertTrue(moving!!.occupiedSectors >= 3)
        assertTrue(moving.movingTrackSupport >= 4)
        assertTrue(hypotheses.indexOf(moving) < hypotheses.indexOfFirst {
            kotlin.math.hypot(it.dx, it.dy) < 0.6f
        })
    }

    @Test fun clusteredReflectionVotesDoNotDefeatDistributedMovingStars() {
        val hypotheses = voteFor(sequence[5])
        val selected = hypotheses.first()
        assertEquals((VELOCITY_X * 5).toDouble(), selected.dx.toDouble(), 0.7)
        assertEquals((VELOCITY_Y * 5).toDouble(), selected.dy.toDouble(), 0.7)
        assertTrue(selected.occupiedSectors >= 3)
    }

    @Test fun robustVotingRefinementRecoversTranslationUnderOutliers() {
        val candidate = sequence[6].copy(
            stars = sequence[6].stars + List(12) { index ->
                star(82f + index * 0.31f, 8f + index * 0.27f, confidence = 0.35f)
            }
        )
        val selected = voteFor(candidate).first()
        assertEquals((VELOCITY_X * 6).toDouble(), selected.dx.toDouble(), 0.7)
        assertEquals((VELOCITY_Y * 6).toDouble(), selected.dy.toDouble(), 0.7)
        assertTrue(selected.residual < 0.5f)
    }

    @Test fun globalFitRecoversDriftAndRejectsIsolatedWrongHypotheses() {
        val model = StellarMotionModelEstimator().fit(
            modelCandidates(includeOutlier = true),
            referenceIndex = REFERENCE_INDEX,
            trackAnalysis = observableTracks()
        )
        assertTrue(model.motionObservable)
        assertEquals(VELOCITY_X.toDouble(), model.velocityX.toDouble(), 0.12)
        assertEquals(VELOCITY_Y.toDouble(), model.velocityY.toDouble(), 0.12)
        assertTrue(model.acceptedFrameHypotheses.size >= 6)
        assertTrue(model.nonZeroModelScore > model.competingZeroModelScore)
        assertNotEquals(18f, model.acceptedFrameHypotheses["f5"]?.dx ?: 18f, 0.01f)
    }

    @Test fun globalFitToleratesRejectedFrames() {
        val model = StellarMotionModelEstimator().fit(
            modelCandidates(includeOutlier = false).map {
                if (it.frameId == "f2") it.copy(hypotheses = emptyList()) else it
            },
            REFERENCE_INDEX,
            observableTracks()
        )
        assertTrue(model.motionObservable)
        assertTrue("f2" in model.rejectedFrameIds)
        assertTrue(model.acceptedFrameHypotheses.size >= 6)
    }

    @Test fun genuineUnobservableZeroMotionRemainsSafe() {
        val values = (0..5).map { index ->
            SequenceRegistrationCandidate(
                "z$index",
                index,
                index == 2,
                listOf(hypothesis(0.03f, -0.02f, moving = 0, stationary = 4))
            )
        }
        val model = StellarMotionModelEstimator().fit(
            values,
            2,
            TemporalTrackAnalysis(emptyList(), false, 0f, 0f)
        )
        assertFalse(model.motionObservable)
        assertEquals("ZERO_OR_UNOBSERVABLE", model.selectedMotionModel)
        assertEquals(0f, model.velocityX, 0.001f)
    }

    @Test fun verificationPrefersTrueDirectionAndRejectsSignInversion() {
        val movingOnly = movingOnlySequence()
        val reference = movingOnly[REFERENCE_INDEX]
        val trueTransforms = transforms(movingOnly, VELOCITY_X, VELOCITY_Y)
        val inverseTransforms = transforms(movingOnly, -VELOCITY_X, -VELOCITY_Y)
        val verifier = RegistrationSequenceVerifier()
        val trueMetrics = verifier.measure(reference, movingOnly, trueTransforms, observableTracks())
        val inverseMetrics = verifier.measure(reference, movingOnly, inverseTransforms, observableTracks())
        assertTrue(trueMetrics.referenceRetention > 0.90f)
        assertTrue(trueMetrics.score > inverseMetrics.score + 0.20f)
        assertTrue(inverseMetrics.referenceRetention < trueMetrics.referenceRetention)
    }

    @Test fun verificationRejectsLineSmearFromAlternatingTransformError() {
        val movingOnly = movingOnlySequence(noise = 0.02f)
        val reference = movingOnly[REFERENCE_INDEX]
        val correct = transforms(movingOnly, VELOCITY_X, VELOCITY_Y)
        val smeared = correct.mapValues { (frameId, value) ->
            val index = frameId.removePrefix("m").toInt()
            value.copy(dx = value.dx + if (index % 2 == 0) 0.85f else -0.85f)
        }
        val verifier = RegistrationSequenceVerifier()
        val clean = verifier.measure(reference, movingOnly, correct, observableTracks())
        val smear = verifier.measure(reference, movingOnly, smeared, observableTracks())
        assertTrue(smear.smearRate > clean.smearRate)
        assertTrue(smear.score < clean.score)
    }

    @Test fun independentMotionPriorRejectsSmoothFalseZeroSequence() {
        val zeroRegistrations = (0..7).map { index ->
            OrderedRegistration(
                frameId = "p$index",
                captureIndex = index,
                isReference = index == REFERENCE_INDEX,
                registration = registration(0f, 0f)
            )
        }
        val validation = TransformSequenceValidator().validate(
            zeroRegistrations,
            expectedMotionModel = ExpectedSequenceMotionModel(
                VELOCITY_X,
                VELOCITY_Y,
                REFERENCE_INDEX,
                residual = 0.1f,
                motionObservable = true,
                verificationScore = 0.92f
            )
        )
        assertTrue(validation.rejectedFrameIds.isNotEmpty())
        assertTrue(validation.motionModelAgreementScore < validation.smoothnessScore)
        assertTrue(validation.registrations.any {
            it.registration.rejectionReason == "transform_sequence_motion_prior_disagreement"
        })
    }

    @Test fun endToEndEngineSelectsNonZeroSequenceAndNeverSubstitutesIdentity() {
        val result = SequenceAwareRegistrationEngine().register(
            sequence,
            referenceFrameId = "f$REFERENCE_INDEX",
            imageWidth = WIDTH,
            imageHeight = HEIGHT
        )
        assertTrue(result.model.motionObservable)
        assertEquals("NON_ZERO_STELLAR", result.model.selectedMotionModel)
        assertTrue(result.verification.toString(), result.verification.selectedModelAccepted)
        assertTrue(result.registrations.getValue("f$REFERENCE_INDEX").isReliable)
        assertEquals(0f, result.registrations.getValue("f$REFERENCE_INDEX").dx, 0f)
        val nonReferenceAccepted = result.registrations.filterKeys { it != "f$REFERENCE_INDEX" }
            .values.filter { it.isReliable }
        assertTrue(result.registrations.toString(), nonReferenceAccepted.size >= 5)
        assertTrue(nonReferenceAccepted.none { abs(it.dx) < 0.4f && abs(it.dy) < 0.4f })
        assertTrue(result.registrations.filterValues { !it.isReliable }.values.none {
            it.registrationModel == "REFERENCE_IDENTITY"
        })
    }

    @Test fun oneBadFrameDoesNotGloballyRejectTheOtherwiseReliableSequence() {
        val corrupted = sequence.map { frame ->
            if (frame.frameId == "f5") {
                frame.copy(stars = frame.stars.map { it.copy(x = it.x + 22f, y = it.y - 19f) })
            } else {
                frame
            }
        }
        val result = SequenceAwareRegistrationEngine().register(
            corrupted,
            referenceFrameId = "f$REFERENCE_INDEX",
            imageWidth = WIDTH,
            imageHeight = HEIGHT
        )
        assertTrue(result.registrations.getValue("f$REFERENCE_INDEX").isReliable)
        assertFalse(result.registrations.getValue("f5").isReliable)
        assertTrue(result.registrations.filterKeys { it != "f5" }.count { it.value.isReliable } >= 5)
        assertTrue(result.registrations.filterValues { !it.isReliable }.values.none {
            it.registrationModel == "REFERENCE_IDENTITY"
        })
    }

    @Test fun regressionReplayClearsTheFourRegistrationStarLossSignals() {
        val result = SequenceAwareRegistrationEngine().register(
            sequence,
            referenceFrameId = "f$REFERENCE_INDEX",
            imageWidth = WIDTH,
            imageHeight = HEIGHT
        )
        val metrics = result.verification.selectedModel
        assertTrue(metrics.reliableStarCount >= 4)
        assertTrue(metrics.referenceRetention >= ReferenceStarRetentionValidator.MIN_RETENTION_RATIO)
        assertTrue(metrics.contrastRatio >= ReferenceStarRetentionValidator.MIN_MEDIAN_CONTRAST_RATIO)
        assertTrue(metrics.smearRate <= ReferenceStarRetentionValidator.MAX_LINE_LIKE_SMEAR_RATE)
    }

    @Test fun finalQualityGateThresholdsRemainUnchanged() {
        assertEquals(0.90f, ReferenceStarRetentionValidator.MIN_RETENTION_RATIO, 0f)
        assertEquals(0.85f, ReferenceStarRetentionValidator.MIN_MEDIAN_CONTRAST_RATIO, 0f)
        assertEquals(0.10f, ReferenceStarRetentionValidator.MAX_LINE_LIKE_SMEAR_RATE, 0f)
    }

    @Test fun analysisTranslationScalesToFullResolutionWithoutDirectionChange() {
        val scaled = registration(-4.25f, 5.0f).scaledToFullResolution(2f, 2f)
        assertEquals(-8.5f, scaled.dx, 0f)
        assertEquals(10f, scaled.dy, 0f)
        assertEquals(-8.5f, scaled.rawDx, 0f)
        assertEquals(10f, scaled.rawDy, 0f)
    }

    @Test fun transformedSamplerUsesReferenceToCandidateDirection() {
        val source = IntArrayPixelSource(3, 1, intArrayOf(gray(10), gray(80), gray(160)))
        val sampled = TransformedBitmapSampler().sample(source, registration(1f, 0f), 0f, 0f)
        assertEquals(80f / 255f, sampled!!.red, 0.001f)
    }

    @Test fun productionProfileUsesSequenceEngineAndKeepsManualAlignmentSeparate() {
        val source = Files.readString(Path.of("src/main/java/com/example/astrophoto/JpegStacker.kt"))
        val profile = source.substring(
            source.indexOf("suspend fun profileStack("),
            source.indexOf("suspend fun loadResultPreview(")
        )
        assertTrue(profile.contains("SequenceAwareRegistrationEngine().register("))
        assertTrue(profile.contains("expectedMotionModel = ExpectedSequenceMotionModel("))
        assertFalse(profile.contains("registerAutomatic("))
        assertTrue(profile.contains("acceptedProfileFrames.mapIndexed"))
        assertTrue(source.contains("private suspend fun findAlignmentOrZero("))
    }

    @Test fun reportingContainsSeparateSequenceAndVerificationScores() {
        val source = Files.readString(
            Path.of("src/main/java/com/example/astrophoto/processing/jpeg/v2/diagnostics/ProcessingReport.kt")
        )
        listOf(
            "registrationSchemaVersion",
            "stationaryTrackCount",
            "movingTrackCount",
            "zeroModelScore",
            "nonZeroModelScore",
            "verificationReferenceRetention",
            "verificationSmearRate",
            "sequenceSmoothnessScore",
            "sequencePriorAgreementScore",
            "registrationRejectedReasons"
        ).forEach { assertTrue(it, source.contains(it)) }
    }

    @Test fun realSessionReplayWhenZipIsProvided() {
        val zipPath = System.getenv("ASTROPHOTO_REGISTRATION_REPLAY_ZIP")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: return
        assertTrue("Replay ZIP does not exist: $zipPath", Files.isRegularFile(zipPath))
        val replayRoot = Path.of("build/tmp/stage10-registration-replay")
        Files.createDirectories(replayRoot)
        val extracted = Files.createTempDirectory(replayRoot, "session-")
        try {
            extractReadOnlyReplay(zipPath, extracted)
            val jpegPaths = Files.walk(extracted).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().lowercase().matches(Regex(".*_\\d{3}\\.jpe?g"))
                }.sorted().toList()
            }
            assertTrue("No captured JPEG frames found in replay ZIP", jpegPaths.size >= 2)
            val reportPaths = Files.walk(extracted).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".processing.json", ignoreCase = true)
                }.toList()
            }
            val reportReference = reportPaths.asSequence().mapNotNull { path ->
                Regex("\"selectedReference\"\\s*:\\s*\"([^\"]+)\"")
                    .find(Files.readString(path))?.groupValues?.get(1)
            }.firstOrNull()
            val analyzer = JpegFrameAnalyzer()
            val skyMaskEstimator = SkyMaskEstimator()
            val rawFrames = jpegPaths.mapIndexed { order, path ->
                val image = readReplayThumbnail(path)
                val mask = skyMaskEstimator.estimate(image)
                val fileName = path.fileName.toString()
                val captureIndex = Regex("_(\\d{3})\\.jpe?g", RegexOption.IGNORE_CASE)
                    .find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: order + 1
                captureIndex to analyzer.analyze(fileName, fileName, image, mask)
            }.sortedBy { it.first }
            val artifactAnalyzer = StaticArtifactAnalyzer()
            val artifactMask = artifactAnalyzer.analyze(
                rawFrames.map { (_, analysis) ->
                    ArtifactFrameObservation(analysis.id, analysis.stars)
                },
                rawFrames.first().second.width,
                rawFrames.first().second.height
            )
            val frames = rawFrames.map { (captureIndex, analysis) ->
                val filtered = artifactAnalyzer.excludeFrom(analysis, artifactMask)
                TemporalFeatureFrame(filtered.id, captureIndex, filtered.stars)
            }
            val fullResolution = checkNotNull(ImageIO.read(jpegPaths.first().toFile())).let { image ->
                (image.width to image.height).also { image.flush() }
            }
            val reference = frames.firstOrNull { it.frameId == reportReference }
                ?: frames.firstOrNull { it.frameId.contains("_009.", ignoreCase = true) }
                ?: frames[frames.size / 2]
            val result = SequenceAwareRegistrationEngine().register(
                frames,
                reference.frameId,
                rawFrames.first().second.width,
                rawFrames.first().second.height
            )
            assertTrue(result.toString(), result.model.motionObservable)
            assertTrue(result.toString(), result.model.velocityX < 0f)
            assertTrue(result.toString(), result.model.velocityY > 0f)
            assertTrue(result.toString(), result.verification.selectedModelAccepted)
            assertTrue(result.toString(), result.registrations.count { it.value.isReliable } > 2)
            println(
                "Stage10 registration replay: reference=${reference.frameId} " +
                    "referenceCaptureIndex=${reference.captureIndex} " +
                    "analysis=${rawFrames.first().second.width}x${rawFrames.first().second.height} " +
                    "full=${fullResolution.first}x${fullResolution.second} " +
                    "scale=(${fullResolution.first.toFloat() / rawFrames.first().second.width}," +
                    "${fullResolution.second.toFloat() / rawFrames.first().second.height}) " +
                    "model=${result.model.selectedMotionModel} " +
                    "velocity=(${result.model.velocityX},${result.model.velocityY}) " +
                    "accepted=${result.registrations.count { it.value.isReliable }} " +
                    "canonical=${result.verification.selectedModel.score} " +
                    "inverse=${result.verification.inverseModel.score} " +
                    "identity=${result.verification.identity.score} " +
                    "double=${result.verification.doubleAppliedModel.score} " +
                    "verificationSamples=${result.verification.aggregation.sampleCount} " +
                    "acceptedVerificationSamples=${result.verification.aggregation.acceptedSampleCount} " +
                    "acceptedMeanSmear=${result.verification.aggregation.acceptedMean?.smearRate} " +
                    "hypotheses=${result.hypothesisCountPerFrame.values.joinToString(",")}"
            )
            frames.forEach { frame ->
                val predicted = result.model.predictedTransform(frame.captureIndex)
                val selected = result.registrations.getValue(frame.frameId)
                val metrics = result.verification.perFrame[frame.frameId]
                val local = result.modelGuidedRegistrations[frame.frameId]
                println(
                    "Stage10 frame=${frame.frameId} capture=${frame.captureIndex} " +
                        "predicted=(${predicted.dx},${predicted.dy}) " +
                        "sparseRank=${result.selectedHypothesisRankPerFrame[frame.frameId]} " +
                        "searchRadius=${local?.searchRadius} " +
                        "correction=(${local?.correctionDx},${local?.correctionDy}) " +
                        "selected=(${selected.dx},${selected.dy}) " +
                        "difference=(${selected.dx - predicted.dx},${selected.dy - predicted.dy}) " +
                        "matched=${local?.matchedStars} inliers=${local?.inlierStars} " +
                        "sequenceAgreement=${selected.transformSequenceScore} " +
                        "retention=${metrics?.referenceRetention} contrast=${metrics?.contrastRatio} " +
                        "smear=${metrics?.smearRate} " +
                        "path=${result.frameAcceptancePaths[frame.frameId]} " +
                        "reason=${result.frameAcceptanceReasons[frame.frameId]} " +
                        "retry=${local?.retryUsed} accepted=${selected.isReliable}"
                )
            }
        } finally {
            extracted.toFile().deleteRecursively()
        }
    }

    private fun voteFor(candidate: TemporalFeatureFrame): List<TranslationHypothesis> =
        SparseTranslationVotingEstimator().estimate(
            sequence.first(),
            candidate,
            WIDTH,
            HEIGHT,
            tracks
        )

    private fun extractReadOnlyReplay(zipPath: Path, destination: Path) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipPath.toFile()))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = destination.resolve(entry.name).normalize()
                require(target.startsWith(destination)) { "Unsafe ZIP entry: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun readReplayThumbnail(path: Path): ArgbPixelImage {
        val source = checkNotNull(ImageIO.read(path.toFile())) { "Cannot decode ${path.fileName}" }
        val scale = minOf(1f, REPLAY_MAX_DIMENSION / maxOf(source.width, source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val thumbnail = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = thumbnail.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return ArgbPixelImage(
            width,
            height,
            IntArray(width * height).also {
                thumbnail.getRGB(0, 0, width, height, it, 0, width)
            }
        )
    }

    private fun regressionSequence(): List<TemporalFeatureFrame> {
        val stationary = listOf(
            8f to 8f, 21f to 9f, 35f to 11f, 52f to 8f, 72f to 12f,
            12f to 34f, 28f to 32f, 45f to 36f, 63f to 31f, 84f to 35f,
            18f to 61f, 76f to 62f
        )
        val moving = listOf(
            11f to 18f, 39f to 20f, 69f to 22f,
            17f to 49f, 50f to 53f, 82f to 51f
        )
        return (0..7).map { index ->
            val fixed = stationary.mapIndexed { pointIndex, point ->
                val jitter = ((index + pointIndex) % 3 - 1) * 0.015f
                star(point.first + jitter, point.second - jitter, confidence = 0.82f)
            }
            val sky = moving.mapIndexedNotNull { pointIndex, point ->
                if (index == 3 && pointIndex == 2) return@mapIndexedNotNull null
                val jitter = ((index * 2 + pointIndex) % 3 - 1) * 0.025f
                star(
                    point.first + VELOCITY_X * index + jitter,
                    point.second + VELOCITY_Y * index - jitter,
                    confidence = 0.96f,
                    contrast = 1.8f
                )
            }
            val clusteredReflection = List(5) { reflection ->
                star(
                    91f + reflection * 0.18f,
                    70f + reflection * 0.15f,
                    confidence = 0.55f,
                    width = 3.5f,
                    ellipticity = 0.65f
                )
            }
            TemporalFeatureFrame("f$index", index, fixed + sky + clusteredReflection)
        }
    }

    private fun movingOnlySequence(noise: Float = 0f): List<TemporalFeatureFrame> {
        val points = listOf(
            12f to 12f, 36f to 14f, 64f to 17f, 85f to 20f,
            18f to 42f, 48f to 45f, 75f to 48f,
            14f to 68f, 43f to 70f, 79f to 66f
        )
        return (0..7).map { index ->
            TemporalFeatureFrame(
                "m$index",
                index,
                points.mapIndexed { starIndex, point ->
                    val jitter = if ((index + starIndex) % 2 == 0) noise else -noise
                    star(
                        point.first + VELOCITY_X * (index - REFERENCE_INDEX) + jitter,
                        point.second + VELOCITY_Y * (index - REFERENCE_INDEX) - jitter,
                        contrast = 1.6f
                    )
                }
            )
        }
    }

    private fun transforms(
        frames: List<TemporalFeatureFrame>,
        velocityX: Float,
        velocityY: Float
    ): Map<String, TranslationHypothesis> = frames.associate { frame ->
        val delta = frame.captureIndex - REFERENCE_INDEX
        frame.frameId to hypothesis(velocityX * delta, velocityY * delta)
    }

    private fun modelCandidates(includeOutlier: Boolean): List<SequenceRegistrationCandidate> =
        (0..7).map { index ->
            val delta = index - REFERENCE_INDEX
            val trueHypothesis = hypothesis(
                VELOCITY_X * delta,
                VELOCITY_Y * delta,
                moving = 6,
                stationary = 0
            )
            val zero = hypothesis(0.04f, -0.03f, support = 12, moving = 0, stationary = 10)
            val outlier = hypothesis(18f, -14f, support = 20, moving = 0, stationary = 0)
            SequenceRegistrationCandidate(
                frameId = "f$index",
                captureIndex = index,
                isReference = index == REFERENCE_INDEX,
                hypotheses = when {
                    index == REFERENCE_INDEX -> listOf(hypothesis(0f, 0f))
                    includeOutlier && index == 5 -> listOf(outlier, trueHypothesis, zero)
                    else -> listOf(zero, trueHypothesis)
                }
            )
        }

    private fun observableTracks() = TemporalTrackAnalysis(
        tracks = emptyList(),
        motionObservable = true,
        coherentVelocityX = VELOCITY_X,
        coherentVelocityY = VELOCITY_Y
    )

    private fun hypothesis(
        dx: Float,
        dy: Float,
        support: Int = 8,
        moving: Int = 6,
        stationary: Int = 0
    ) = TranslationHypothesis(
        dx,
        dy,
        support,
        weightedSupport = if (moving > 0) 52f else 16f,
        residual = 0.08f,
        occupiedSectors = if (moving > 0) 6 else 3,
        movingTrackSupport = moving,
        stationaryTrackSupport = stationary
    )

    private fun registration(dx: Float, dy: Float) = RegistrationResult(
        dx = dx,
        dy = dy,
        rotationRadians = 0f,
        scale = 1f,
        detectedStars = 12,
        matchedStars = 10,
        inlierStars = 10,
        residualError = 0.1f,
        confidence = 0.9f,
        isReliable = true,
        rejectionReason = null,
        registrationModel = "SEQUENCE_TRANSLATION",
        scaleFixed = true
    )

    private fun star(
        x: Float,
        y: Float,
        confidence: Float = 0.9f,
        contrast: Float = 1f,
        width: Float = 1.1f,
        ellipticity: Float = 0.12f
    ) = DetectedStar(
        x = x,
        y = y,
        flux = 100f,
        localBackground = 0.08f,
        localContrast = contrast,
        width = width,
        ellipticity = ellipticity,
        confidence = confidence
    )

    private fun gray(value: Int): Int =
        0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

    companion object {
        private const val WIDTH = 110
        private const val HEIGHT = 82
        private const val REFERENCE_INDEX = 3
        private const val VELOCITY_X = -1.18f
        private const val VELOCITY_Y = 1.42f
        private const val REPLAY_MAX_DIMENSION = 960f
    }
}
