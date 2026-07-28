package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactRegion
import com.example.astrophoto.processing.jpeg.v2.integration.FrameWeightCalculator
import com.example.astrophoto.processing.jpeg.v2.integration.FrameWeightInput
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationDiagnostics
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureTrack
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class Stage6CandidateShape {
    COMPACT,
    ELONGATED,
    IRREGULAR
}

internal data class Stage6BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
}

internal data class Stage6ShapeMeasurement(
    val boundingBox: Stage6BoundingBox,
    val shape: Stage6CandidateShape,
    val orientationDegrees: Double,
    val majorAxisLength: Double,
    val minorAxisLength: Double,
    val elongation: Double,
    val brightnessProfile: List<Double>,
    val chromaProfile: List<Double>
)

internal data class Stage6PathMeasurement(
    val recurrence: Int,
    val medianContrast: Double,
    val medianChromaResidual: Double,
    val medianResidual: Double,
    val observedFrameIndices: List<Int>
)

internal data class Stage6FrameDiagnostic(
    val frameId: String,
    val frameIndex: Int,
    val cleanAccepted: Boolean,
    val cleanIntegrationWeight: Double,
    val cleanTransform: ReferenceToSourceTransform,
    val cleanResidual: Double?,
    val cleanRejectionReason: String?,
    val manualIntegrationWeight: Double,
    val manualTransform: ReferenceToSourceTransform
)

internal data class Stage6CandidateDiagnostic(
    val id: String,
    val referenceX: Double,
    val referenceY: Double,
    val boundingBox: Stage6BoundingBox,
    val shape: Stage6CandidateShape,
    val cameraSpaceRecurrence: Int,
    val skySpaceRecurrence: Int,
    val medianLocalContrast: Double,
    val chromaResidual: Double,
    val estimatedMotionX: Double,
    val estimatedMotionY: Double,
    val registrationResidual: Double,
    val provisionalClass: ProvisionalSourceClass,
    val confidence: Double,
    val classificationReason: String,
    val origins: List<String>,
    val preserved: Boolean,
    val cameraResidual: Double,
    val skyResidual: Double,
    val shapeMeasurement: Stage6ShapeMeasurement
)

internal data class Stage6TrailContribution(
    val frameIndex: Int,
    val frameId: String,
    val transform: ReferenceToSourceTransform,
    val manualIntegrationWeight: Double,
    val cleanIntegrationWeight: Double,
    val cameraContrast: Double,
    val skyContrast: Double
)

internal data class Stage6TrailDiagnostic(
    val candidateId: String,
    val orientationDegrees: Double,
    val majorAxisLength: Double,
    val elongation: Double,
    val brightnessProfile: List<Double>,
    val chromaProfile: List<Double>,
    val cameraSpaceOverlap: Int,
    val skySpaceOverlap: Int,
    val contributingFrameIndices: List<Int>,
    val rejectedFramesWithManualWeight: List<Int>,
    val contributions: List<Stage6TrailContribution>,
    val presentInIndividualFrames: Boolean,
    val introducedOnlyByEnhancement: Boolean,
    val interpolationArtifact: Boolean,
    val foregroundBoundaryArtifact: Boolean,
    val leakyBaselineRejectedPathContrast: Double,
    val filteredManualRejectedPathContrast: Double,
    val provenance: String,
    val reason: String
)

internal data class Stage6DiagnosticBundle(
    val fixtureName: String,
    val referenceFrameIndex: Int,
    val width: Int,
    val height: Int,
    val modelScore: Double,
    val modelResidual: Double,
    val motionXPerFrame: Double,
    val motionYPerFrame: Double,
    val finalDisplacementX: Double,
    val finalDisplacementY: Double,
    val stationaryArtifactCount: Int,
    val frames: List<Stage6FrameDiagnostic>,
    val candidates: List<Stage6CandidateDiagnostic>,
    val trails: List<Stage6TrailDiagnostic>,
    val referenceImage: ArgbPixelImage,
    val leakyManualAlignedStack: ArgbPixelImage,
    val manualAlignedStack: ArgbPixelImage,
    val cleanStack: ArgbPixelImage,
    val enhancedProfile: ArgbPixelImage,
    val skyMask: SkyMask
) {
    val candidateCounts: Map<ProvisionalSourceClass, Int>
        get() = ProvisionalSourceClass.entries.associateWith { classification ->
            candidates.count { it.provisionalClass == classification }
        }
}

internal class Stage6CandidateDiagnosticRunner {
    fun analyze(fixture: Stage6RegressionFixture): Stage6DiagnosticBundle {
        require(fixture.frames.size == EXPECTED_FRAME_COUNT)
        val analyzer = JpegFrameAnalyzer()
        val maskEstimator = SkyMaskEstimator()
        val rawAnalyses = fixture.frames.mapIndexed { index, image ->
            val id = frameId(index)
            analyzer.analyze(id, id, image, maskEstimator.estimate(image))
        }
        val artifactAnalyzer = StaticArtifactAnalyzer()
        val artifactMask = artifactAnalyzer.analyze(
            rawAnalyses.map { ArtifactFrameObservation(it.id, it.stars) },
            fixture.frames.first().width,
            fixture.frames.first().height
        )
        val filteredAnalyses = rawAnalyses.map { artifactAnalyzer.excludeFrom(it, artifactMask) }
        val reference = filteredAnalyses[fixture.referenceFrameIndex]
        val registration = SequenceAwareRegistrationEngine().register(
            filteredAnalyses.mapIndexed { index, analysis ->
                TemporalFeatureFrame(analysis.id, index + 1, analysis.stars)
            },
            reference.id,
            reference.width,
            reference.height
        )
        val manualPlan = requireNotNull(
            planManualSequenceAlignmentFromAnalyses(
                rawAnalyses,
                reference.width,
                reference.height
            )
        )
        val weights = FrameWeightCalculator().calculate(
            filteredAnalyses.mapIndexedNotNull { index, analysis ->
                val frameRegistration = registration.registrations[analysis.id] ?: return@mapIndexedNotNull null
                FrameWeightInput(
                    analysis,
                    frameRegistration,
                    isReference = index == fixture.referenceFrameIndex
                )
            }
        ).associate { it.frameId to it.normalizedWeight.toDouble() }
        val frameDiagnostics = filteredAnalyses.mapIndexed { index, analysis ->
            val frameRegistration = registration.registrations[analysis.id]
            val cleanAccepted = frameRegistration?.isReliable == true
            val cleanTransform = if (cleanAccepted) {
                checkNotNull(frameRegistration).referenceToSourceTransform()
            } else {
                registration.model.predictedTransform(index + 1)
            }
            val manualDecision = manualPlan.frames[index]
            require(manualDecision.originalFrameIndex == index)
            Stage6FrameDiagnostic(
                frameId = analysis.id,
                frameIndex = index + 1,
                cleanAccepted = cleanAccepted,
                cleanIntegrationWeight = weights[analysis.id] ?: 0.0,
                cleanTransform = cleanTransform,
                cleanResidual = frameRegistration?.residualError?.toDouble()?.takeIf(Double::isFinite),
                cleanRejectionReason = frameRegistration?.rejectionReason
                    ?: registration.rejectedReasons[analysis.id],
                manualIntegrationWeight = if (manualDecision.accepted) 1.0 else 0.0,
                manualTransform = ReferenceToSourceTransform(
                    dx = manualDecision.shift.dx.toFloat(),
                    dy = manualDecision.shift.dy.toFloat()
                )
            )
        }
        val cleanStack = integrate(
            fixture.frames,
            frameDiagnostics.map { it.cleanTransform },
            frameDiagnostics.map { it.cleanIntegrationWeight },
            bilinear = true
        )
        val manualStack = integrate(
            fixture.frames,
            frameDiagnostics.map { it.manualTransform },
            frameDiagnostics.map { it.manualIntegrationWeight },
            bilinear = false
        )
        val leakyManualStack = integrate(
            fixture.frames,
            frameDiagnostics.map { it.manualTransform },
            List(frameDiagnostics.size) { 1.0 },
            bilinear = false
        )
        val enhanced = displayStretch(cleanStack)
        val referenceImage = fixture.frames[fixture.referenceFrameIndex]
        val referenceMask = maskEstimator.estimate(referenceImage).mask
        val candidates = candidates(
            fixture,
            rawAnalyses,
            artifactMask.regions,
            registration,
            frameDiagnostics,
            referenceImage,
            cleanStack,
            manualStack,
            enhanced,
            referenceMask
        )
        val trails = candidates
            .filter { candidate ->
                candidate.shape == Stage6CandidateShape.ELONGATED &&
                    (
                        candidate.provisionalClass == ProvisionalSourceClass.SENSOR_DEFECT ||
                            (
                                candidate.skySpaceRecurrence >= MIN_DEFECT_RECURRENCE &&
                                    candidate.cameraSpaceRecurrence <= MIN_STAR_RECURRENCE
                                ) ||
                            (
                                "manual_aligned_stack" in candidate.origins &&
                                    candidate.medianLocalContrast >= MIN_TRAIL_CONTRAST &&
                                    candidate.shapeMeasurement.majorAxisLength >= MIN_TRAIL_LENGTH
                                )
                        )
            }
            .map {
                trailDiagnostic(
                    it,
                    fixture.frames,
                    frameDiagnostics,
                    referenceImage,
                    leakyManualStack,
                    manualStack,
                    cleanStack,
                    enhanced,
                    referenceMask
                )
            }
        val finalTransform = registration.model.predictedTransform(EXPECTED_FRAME_COUNT)
        return Stage6DiagnosticBundle(
            fixtureName = fixture.name,
            referenceFrameIndex = fixture.referenceFrameIndex,
            width = reference.width,
            height = reference.height,
            modelScore = registration.model.score.toDouble(),
            modelResidual = registration.model.residual.toDouble(),
            motionXPerFrame = registration.model.velocityX.toDouble(),
            motionYPerFrame = registration.model.velocityY.toDouble(),
            finalDisplacementX = finalTransform.dx.toDouble(),
            finalDisplacementY = finalTransform.dy.toDouble(),
            stationaryArtifactCount = artifactMask.regions.size,
            frames = frameDiagnostics,
            candidates = candidates,
            trails = trails,
            referenceImage = referenceImage,
            leakyManualAlignedStack = leakyManualStack,
            manualAlignedStack = manualStack,
            cleanStack = cleanStack,
            enhancedProfile = enhanced,
            skyMask = referenceMask
        )
    }

    fun writeArtifacts(
        bundle: Stage6DiagnosticBundle,
        outputRoot: Path,
        existingGroundTruth: Path,
        expandedGroundTruth: Path
    ) {
        Files.createDirectories(outputRoot)
        Files.writeString(
            outputRoot.resolve("candidate-diagnostics.json"),
            diagnosticsJson(bundle),
            StandardCharsets.UTF_8
        )
        Files.writeString(
            outputRoot.resolve("candidate-review.md"),
            reviewMarkdown(bundle),
            StandardCharsets.UTF_8
        )
        writeCandidateContactSheet(bundle, outputRoot.resolve("candidates-contact-sheet.png"))
        writeTrailContactSheet(bundle, outputRoot.resolve("trail-provenance-contact-sheet.png"))
        writeExpandedGroundTruth(bundle, existingGroundTruth, expandedGroundTruth)
    }

    internal fun diagnosticsJson(bundle: Stage6DiagnosticBundle): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": \"stage6-candidates/1\",")
        appendLine("  \"fixture\": ${json(bundle.fixtureName)},")
        appendLine("  \"frameCount\": ${bundle.frames.size},")
        appendLine("  \"referenceFrameIndex\": ${bundle.referenceFrameIndex},")
        appendLine("  \"model\": {")
        appendLine("    \"score\": ${number(bundle.modelScore)},")
        appendLine("    \"residualPx\": ${number(bundle.modelResidual)},")
        appendLine("    \"motionPerFrame\": {\"dx\": ${number(bundle.motionXPerFrame)}, \"dy\": ${number(bundle.motionYPerFrame)}},")
        appendLine("    \"finalDisplacement\": {\"dx\": ${number(bundle.finalDisplacementX)}, \"dy\": ${number(bundle.finalDisplacementY)}}")
        appendLine("  },")
        appendLine("  \"stationaryArtifactCount\": ${bundle.stationaryArtifactCount},")
        appendLine("  \"candidateCounts\": {")
        ProvisionalSourceClass.entries.forEachIndexed { index, classification ->
            append("    ${json(classification.name.lowercase())}: ${bundle.candidateCounts.getValue(classification)}")
            appendLine(if (index == ProvisionalSourceClass.entries.lastIndex) "" else ",")
        }
        appendLine("  },")
        appendLine("  \"frames\": [")
        bundle.frames.forEachIndexed { index, frame ->
            appendLine("    {")
            appendLine("      \"frameIndex\": ${frame.frameIndex},")
            appendLine("      \"frameId\": ${json(frame.frameId)},")
            appendLine("      \"cleanAccepted\": ${frame.cleanAccepted},")
            appendLine("      \"cleanIntegrationWeight\": ${number(frame.cleanIntegrationWeight)},")
            appendLine("      \"cleanTransform\": ${transformJson(frame.cleanTransform)},")
            appendLine("      \"cleanResidual\": ${frame.cleanResidual?.let(::number) ?: "null"},")
            appendLine("      \"cleanRejectionReason\": ${frame.cleanRejectionReason?.let(::json) ?: "null"},")
            appendLine("      \"manualIntegrationWeight\": ${number(frame.manualIntegrationWeight)},")
            appendLine("      \"manualTransform\": ${transformJson(frame.manualTransform)}")
            append("    }")
            appendLine(if (index == bundle.frames.lastIndex) "" else ",")
        }
        appendLine("  ],")
        appendLine("  \"candidates\": [")
        bundle.candidates.forEachIndexed { index, candidate ->
            appendLine("    {")
            appendLine("      \"id\": ${json(candidate.id)},")
            appendLine("      \"referenceFrameCoordinates\": {\"x\": ${number(candidate.referenceX)}, \"y\": ${number(candidate.referenceY)}},")
            appendLine("      \"boundingBox\": ${boxJson(candidate.boundingBox)},")
            appendLine("      \"shape\": ${json(candidate.shape.name.lowercase())},")
            appendLine("      \"cameraSpaceRecurrence\": ${candidate.cameraSpaceRecurrence},")
            appendLine("      \"skySpaceRecurrence\": ${candidate.skySpaceRecurrence},")
            appendLine("      \"medianLocalContrast\": ${number(candidate.medianLocalContrast)},")
            appendLine("      \"chromaResidual\": ${number(candidate.chromaResidual)},")
            appendLine("      \"estimatedMotionVector\": {\"dx\": ${number(candidate.estimatedMotionX)}, \"dy\": ${number(candidate.estimatedMotionY)}},")
            appendLine("      \"registrationResidual\": ${number(candidate.registrationResidual)},")
            appendLine("      \"cameraResidual\": ${number(candidate.cameraResidual)},")
            appendLine("      \"skyResidual\": ${number(candidate.skyResidual)},")
            appendLine("      \"provisionalClass\": ${json(candidate.provisionalClass.name.lowercase())},")
            appendLine("      \"confidence\": ${number(candidate.confidence)},")
            appendLine("      \"classificationReason\": ${json(candidate.classificationReason)},")
            appendLine("      \"preserved\": ${candidate.preserved},")
            appendLine("      \"origins\": ${stringArray(candidate.origins)},")
            appendLine("      \"morphology\": {")
            appendLine("        \"orientationDegrees\": ${number(candidate.shapeMeasurement.orientationDegrees)},")
            appendLine("        \"majorAxisLength\": ${number(candidate.shapeMeasurement.majorAxisLength)},")
            appendLine("        \"minorAxisLength\": ${number(candidate.shapeMeasurement.minorAxisLength)},")
            appendLine("        \"elongation\": ${number(candidate.shapeMeasurement.elongation)}")
            appendLine("      }")
            append("    }")
            appendLine(if (index == bundle.candidates.lastIndex) "" else ",")
        }
        appendLine("  ],")
        appendLine("  \"trails\": [")
        bundle.trails.forEachIndexed { index, trail ->
            appendLine("    {")
            appendLine("      \"candidateId\": ${json(trail.candidateId)},")
            appendLine("      \"orientationDegrees\": ${number(trail.orientationDegrees)},")
            appendLine("      \"majorAxisLength\": ${number(trail.majorAxisLength)},")
            appendLine("      \"elongation\": ${number(trail.elongation)},")
            appendLine("      \"brightnessProfile\": ${numberArray(trail.brightnessProfile)},")
            appendLine("      \"chromaProfile\": ${numberArray(trail.chromaProfile)},")
            appendLine("      \"cameraSpaceOverlap\": ${trail.cameraSpaceOverlap},")
            appendLine("      \"skySpaceOverlap\": ${trail.skySpaceOverlap},")
            appendLine("      \"contributingFrameIndices\": ${intArray(trail.contributingFrameIndices)},")
            appendLine("      \"rejectedFramesWithManualWeight\": ${intArray(trail.rejectedFramesWithManualWeight)},")
            appendLine("      \"presentInIndividualFrames\": ${trail.presentInIndividualFrames},")
            appendLine("      \"introducedOnlyByEnhancement\": ${trail.introducedOnlyByEnhancement},")
            appendLine("      \"interpolationArtifact\": ${trail.interpolationArtifact},")
            appendLine("      \"foregroundBoundaryArtifact\": ${trail.foregroundBoundaryArtifact},")
            appendLine(
                "      \"leakyBaselineRejectedPathContrast\": " +
                    "${number(trail.leakyBaselineRejectedPathContrast)},"
            )
            appendLine(
                "      \"filteredManualRejectedPathContrast\": " +
                    "${number(trail.filteredManualRejectedPathContrast)},"
            )
            appendLine("      \"provenance\": ${json(trail.provenance)},")
            appendLine("      \"reason\": ${json(trail.reason)},")
            appendLine("      \"contributions\": [")
            trail.contributions.forEachIndexed { contributionIndex, contribution ->
                append("        {\"frameIndex\": ${contribution.frameIndex}, \"frameId\": ${json(contribution.frameId)}, ")
                append("\"transform\": ${transformJson(contribution.transform)}, ")
                append("\"manualIntegrationWeight\": ${number(contribution.manualIntegrationWeight)}, ")
                append("\"cleanIntegrationWeight\": ${number(contribution.cleanIntegrationWeight)}, ")
                append("\"cameraContrast\": ${number(contribution.cameraContrast)}, ")
                append("\"skyContrast\": ${number(contribution.skyContrast)}}")
                appendLine(if (contributionIndex == trail.contributions.lastIndex) "" else ",")
            }
            appendLine("      ]")
            append("    }")
            appendLine(if (index == bundle.trails.lastIndex) "" else ",")
        }
        appendLine("  ]")
        appendLine("}")
    }

    internal fun reviewMarkdown(bundle: Stage6DiagnosticBundle): String = buildString {
        appendLine("# Stage 6 candidate review")
        appendLine()
        appendLine("- Fixture: `${bundle.fixtureName}`; frames: ${bundle.frames.size}; reference: ${bundle.referenceFrameIndex + 1}.")
        appendLine("- Production processing modified: **false**.")
        appendLine("- Registration: score ${number(bundle.modelScore)}, residual ${number(bundle.modelResidual)} px, motion ${number(bundle.motionXPerFrame)}, ${number(bundle.motionYPerFrame)} px/frame.")
        appendLine("- Final predicted displacement: ${number(bundle.finalDisplacementX)}, ${number(bundle.finalDisplacementY)} px.")
        appendLine("- Candidate review ROI: вручную проверенная внутренняя область неба fixture; production sky mask не изменён.")
        appendLine("- Candidates: ${bundle.candidates.size}; star ${bundle.candidateCounts.getValue(ProvisionalSourceClass.STAR)}, sensor_defect ${bundle.candidateCounts.getValue(ProvisionalSourceClass.SENSOR_DEFECT)}, uncertain ${bundle.candidateCounts.getValue(ProvisionalSourceClass.UNCERTAIN)}.")
        appendLine("- `uncertain` is excluded from recall and retention metrics.")
        appendLine()
        appendLine("> All currently annotated reference stars were retained.")
        appendLine()
        appendLine("Эта формулировка относится только к текущей provisional-разметке и не означает, что удержаны все видимые звёзды.")
        appendLine()
        appendLine("## Candidates")
        appendLine()
        appendLine("| ID | class | shape | x | y | camera | sky | contrast | confidence | reason |")
        appendLine("|---|---|---|---:|---:|---:|---:|---:|---:|---|")
        bundle.candidates.forEach { candidate ->
            appendLine(
                "| ${candidate.id} | ${candidate.provisionalClass.name.lowercase()} | " +
                    "${candidate.shape.name.lowercase()} | ${number(candidate.referenceX)} | " +
                    "${number(candidate.referenceY)} | ${candidate.cameraSpaceRecurrence}/30 | " +
                    "${candidate.skySpaceRecurrence}/30 | ${number(candidate.medianLocalContrast)} | " +
                    "${number(candidate.confidence)} | ${candidate.classificationReason} |"
            )
        }
        appendLine()
        appendLine("## Residual trail provenance")
        appendLine()
        appendLine(
            "| candidate | provenance | length | elongation | camera | sky | " +
                "leaky rejected-path contrast | filtered contrast | rejected manual contributors |"
        )
        appendLine("|---|---|---:|---:|---:|---:|---:|---:|---|")
        bundle.trails.forEach { trail ->
            appendLine(
                "| ${trail.candidateId} | ${trail.provenance} | ${number(trail.majorAxisLength)} | " +
                    "${number(trail.elongation)} | ${trail.cameraSpaceOverlap}/30 | " +
                    "${trail.skySpaceOverlap}/30 | " +
                    "${number(trail.leakyBaselineRejectedPathContrast)} | " +
                    "${number(trail.filteredManualRejectedPathContrast)} | " +
                    "${trail.rejectedFramesWithManualWeight.joinToString()} |"
            )
        }
        appendLine()
        val rejected = bundle.frames.filterNot { it.cleanAccepted }
        appendLine("## Proven")
        appendLine()
        appendLine("- Clean-stack rejected frames have zero integration weight.")
        appendLine(
            "- Manual sequence stack assigns zero weight to ${rejected.size} " +
                "sequence-rejected frames: ${rejected.joinToString { it.frameIndex.toString() }}."
        )
        bundle.trails.filter {
            it.provenance == "camera_space_defect_smeared_by_sky_alignment"
        }.forEach { trail ->
            val reduction = if (trail.leakyBaselineRejectedPathContrast > 0.0) {
                (
                    1.0 -
                        trail.filteredManualRejectedPathContrast /
                        trail.leakyBaselineRejectedPathContrast
                    ) * 100.0
            } else {
                0.0
            }
            appendLine(
                "- ${trail.candidateId} rejected-path contrast: " +
                    "${number(trail.leakyBaselineRejectedPathContrast)} -> " +
                    "${number(trail.filteredManualRejectedPathContrast)} " +
                    "(${number(reduction)}% reduction)."
            )
        }
        appendLine("- Camera-stable compact defects form diagonal paths after the sky transform; they are present before display enhancement.")
        appendLine("- The manual diagnostic uses integer translation and nearest-neighbour sampling, so its trails are not interpolation artifacts.")
        appendLine()
        appendLine("## Uncertain")
        appendLine()
        appendLine("- Low-confidence, blended and reference-only candidates remain `uncertain`.")
        appendLine("- A full astronomical recall value still requires a larger manual catalogue.")
    }

    private fun candidates(
        fixture: Stage6RegressionFixture,
        rawAnalyses: List<FrameAnalysis>,
        artifacts: List<StaticArtifactRegion>,
        registration: SequenceAwareRegistrationDiagnostics,
        frameDiagnostics: List<Stage6FrameDiagnostic>,
        referenceImage: ArgbPixelImage,
        cleanStack: ArgbPixelImage,
        manualStack: ArgbPixelImage,
        enhanced: ArgbPixelImage,
        referenceMask: SkyMask
    ): List<Stage6CandidateDiagnostic> {
        val seeds = mutableListOf<CandidateSeed>()
        fixture.groundTruth.filterNot { isGeneratedCandidateId(it.id) }.forEach { label ->
            val origins = linkedSetOf("preserved_ground_truth")
            when (label.classification) {
                ProvisionalSourceClass.STAR -> origins += "sky_space_temporal_motion"
                ProvisionalSourceClass.SENSOR_DEFECT ->
                    origins += "camera_space_temporal_persistence"
                ProvisionalSourceClass.UNCERTAIN -> Unit
            }
            seeds += CandidateSeed(
                id = label.id,
                x = label.x.toDouble(),
                y = label.y.toDouble(),
                origins = origins,
                preserved = label
            )
        }
        val referenceId = rawAnalyses[fixture.referenceFrameIndex].id
        registration.trackAnalysis.tracks.forEach { track ->
            val point = trackReferencePoint(track, referenceId, fixture.referenceFrameIndex + 1)
                ?: return@forEach
            mergeSeed(seeds, point.first, point.second, "sky_space_temporal_motion", track = track)
        }
        artifacts.forEach { artifact ->
            mergeSeed(
                seeds,
                artifact.x.toDouble(),
                artifact.y.toDouble(),
                "camera_space_temporal_persistence",
                artifact = artifact
            )
        }
        rawAnalyses[fixture.referenceFrameIndex].stars.forEach {
            mergeSeed(seeds, it.x.toDouble(), it.y.toDouble(), "reference_frame", detected = it)
        }
        listOf(
            "aligned_clean_stack" to cleanStack,
            "manual_aligned_stack" to manualStack,
            "enhanced_profile" to enhanced
        ).forEach { (origin, image) ->
            val analysis = JpegFrameAnalyzer().analyze(
                origin,
                origin,
                image,
                SkyMaskEstimator().estimate(image)
            )
            analysis.stars.forEach {
                mergeSeed(seeds, it.x.toDouble(), it.y.toDouble(), origin, detected = it)
            }
        }
        listOf(
            "reference_frame" to referenceImage,
            "aligned_clean_stack" to cleanStack,
            "manual_aligned_stack" to manualStack,
            "enhanced_profile" to enhanced
        ).forEach { (origin, image) ->
            proposeLocalMaxima(image).forEach { proposal ->
                mergeSeed(seeds, proposal.first, proposal.second, origin)
            }
        }
        val measured = seeds
            .filter { seed ->
                seed.preserved != null ||
                    (
                        referenceMask.contains(seed.x.roundToInt(), seed.y.roundToInt()) &&
                            inDiagnosticSkyReviewRegion(
                                seed.x,
                                seed.y,
                                referenceImage.width,
                                referenceImage.height
                            )
                        )
            }
            .map { seed ->
                candidate(
                    seed,
                    fixture.frames,
                    registration,
                    frameDiagnostics,
                    manualStack
                )
            }
            .filter {
                it.preserved ||
                    it.cameraSpaceRecurrence >= MIN_CANDIDATE_RECURRENCE ||
                    it.skySpaceRecurrence >= MIN_CANDIDATE_RECURRENCE ||
                    it.medianLocalContrast >= MIN_STANDALONE_CONTRAST
            }
        return selectCandidates(measured)
            .sortedWith(
                compareBy<Stage6CandidateDiagnostic> { it.referenceY }
                    .thenBy { it.referenceX }
                    .thenBy { it.id }
            )
    }

    private fun proposeLocalMaxima(image: ArgbPixelImage): List<Pair<Double, Double>> {
        val proposals = mutableListOf<Pair<Pair<Double, Double>, Double>>()
        for (y in LOCAL_RING_RADIUS until image.height - LOCAL_RING_RADIUS) {
            for (x in LOCAL_RING_RADIUS until image.width - LOCAL_RING_RADIUS) {
                if (!inDiagnosticSkyReviewRegion(
                        x.toDouble(),
                        y.toDouble(),
                        image.width,
                        image.height
                    )
                ) continue
                val luminance = signal(image.pixels[y * image.width + x]).luminance
                var localMaximum = true
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val neighbor = signal(
                        image.pixels[(y + dy) * image.width + x + dx]
                    ).luminance
                    if (
                        neighbor > luminance ||
                        neighbor == luminance && (dy < 0 || dy == 0 && dx < 0)
                    ) {
                        localMaximum = false
                    }
                }
                if (!localMaximum) continue
                val peak = localPeak(image, x.toDouble(), y.toDouble())
                if (peak.contrast < PROPOSAL_MIN_CONTRAST) continue
                proposals += (peak.x to peak.y) to peak.contrast
            }
        }
        val selected = mutableListOf<Pair<Double, Double>>()
        proposals.sortedWith(
            compareByDescending<Pair<Pair<Double, Double>, Double>> { it.second }
                .thenBy { it.first.second }
                .thenBy { it.first.first }
        ).forEach { proposal ->
            if (
                selected.size < MAX_PROPOSALS_PER_IMAGE &&
                selected.none {
                    squaredDistance(
                        it.first,
                        it.second,
                        proposal.first.first,
                        proposal.first.second
                    ) < PROPOSAL_SEPARATION * PROPOSAL_SEPARATION
                }
            ) {
                selected += proposal.first
            }
        }
        return selected
    }

    private fun selectCandidates(
        candidates: List<Stage6CandidateDiagnostic>
    ): List<Stage6CandidateDiagnostic> {
        if (candidates.size <= MAX_CANDIDATES) return candidates
        val selected = linkedMapOf<String, Stage6CandidateDiagnostic>()
        candidates.filter { it.preserved }.forEach { selected[it.id] = it }
        REQUIRED_ORIGINS.forEach { origin ->
            candidates.filter { origin in it.origins }
                .maxWithOrNull(candidatePriority())
                ?.let { selected[it.id] = it }
        }
        candidates.filter { it.provisionalClass != ProvisionalSourceClass.UNCERTAIN }
            .sortedWith(candidatePriority().reversed())
            .forEach {
                if (selected.size < MAX_CANDIDATES) selected[it.id] = it
            }
        candidates.sortedWith(candidatePriority().reversed()).forEach {
            if (selected.size < MAX_CANDIDATES) selected[it.id] = it
        }
        return selected.values.toList()
    }

    private fun candidatePriority(): Comparator<Stage6CandidateDiagnostic> =
        compareBy<Stage6CandidateDiagnostic> {
            when (it.provisionalClass) {
                ProvisionalSourceClass.STAR -> 3
                ProvisionalSourceClass.SENSOR_DEFECT -> 2
                ProvisionalSourceClass.UNCERTAIN -> 1
            }
        }.thenBy { max(it.cameraSpaceRecurrence, it.skySpaceRecurrence) }
            .thenBy { it.origins.size }
            .thenBy { it.medianLocalContrast }
            .thenByDescending { it.referenceY }
            .thenByDescending { it.referenceX }
            .thenByDescending { it.id }

    private fun inDiagnosticSkyReviewRegion(
        x: Double,
        y: Double,
        width: Int,
        height: Int
    ): Boolean {
        if (width != 720 || height != 960) return false
        val left = if (y <= REVIEW_FOREGROUND_TOP) {
            REVIEW_LEFT_EDGE
        } else {
            REVIEW_LOWER_LEFT_EDGE +
                (y - REVIEW_FOREGROUND_TOP) * REVIEW_LOWER_EDGE_SLOPE
        }
        return x >= left && x <= REVIEW_RIGHT_EDGE && y <= REVIEW_BOTTOM
    }

    private fun candidate(
        seed: CandidateSeed,
        frames: List<ArgbPixelImage>,
        registration: SequenceAwareRegistrationDiagnostics,
        frameDiagnostics: List<Stage6FrameDiagnostic>,
        manualStack: ArgbPixelImage
    ): Stage6CandidateDiagnostic {
        val camera = measurePath(frames, seed.x, seed.y) { _, _, x, y -> x to y }
        val sky = measurePath(frames, seed.x, seed.y) { index, _, x, y ->
            val mapped = registration.model.predictedTransform(index + 1)
                .mapOutputToSource(x.toFloat(), y.toFloat())
            mapped.x.toDouble() to mapped.y.toDouble()
        }
        val preserved = seed.preserved
        val artifactPath = if (
            seed.artifact != null ||
            preserved?.classification == ProvisionalSourceClass.SENSOR_DEFECT
        ) {
            val pathX = seed.artifact?.x?.toDouble() ?: seed.x
            val pathY = seed.artifact?.y?.toDouble() ?: seed.y
            frameDiagnostics.map { frame ->
                sourceToOutput(frame.manualTransform, pathX, pathY)
            }
        } else {
            null
        }
        val localShape = if (artifactPath != null && pathLength(artifactPath) >= MIN_TRAIL_LENGTH) {
            pathShape(manualStack, artifactPath)
        } else {
            measureShape(manualStack, seed.x, seed.y)
        }
        val classification = when {
            preserved != null -> preserved.classification
            seed.track?.cluster == TemporalMotionCluster.COHERENT_MOVING_SKY &&
                seed.track!!.presenceRatio >= MIN_STAR_PRESENCE &&
                seed.track!!.fitResidual <= MAX_STAR_TRACK_RESIDUAL &&
                sky.recurrence >= MIN_STAR_RECURRENCE &&
                sky.recurrence >= camera.recurrence + MIN_RECURRENCE_SEPARATION ->
                ProvisionalSourceClass.STAR
            seed.artifact != null &&
                camera.recurrence >= MIN_DEFECT_RECURRENCE &&
                camera.recurrence >= sky.recurrence + MIN_RECURRENCE_SEPARATION ->
                ProvisionalSourceClass.SENSOR_DEFECT
            else -> ProvisionalSourceClass.UNCERTAIN
        }
        val confidence = when {
            preserved != null -> preserved.confidence.toDouble()
            classification == ProvisionalSourceClass.STAR -> (
                0.30 +
                    0.30 * checkNotNull(seed.track).presenceRatio +
                    0.20 * (
                        1.0 - seed.track!!.fitResidual.toDouble() / MAX_TRACK_RESIDUAL
                    ).coerceIn(0.0, 1.0) +
                    0.20 * sky.recurrence / EXPECTED_FRAME_COUNT
                ).coerceIn(0.50, 0.97)
            classification == ProvisionalSourceClass.SENSOR_DEFECT -> (
                0.35 +
                    0.30 * checkNotNull(seed.artifact).confidence +
                    0.25 * camera.recurrence / EXPECTED_FRAME_COUNT +
                    0.10 * (camera.recurrence - sky.recurrence).coerceAtLeast(0) / EXPECTED_FRAME_COUNT
                ).coerceIn(0.50, 0.97)
            else -> max(
                0.20,
                min(
                    0.49,
                    max(camera.recurrence, sky.recurrence).toDouble() / EXPECTED_FRAME_COUNT * 0.5
                )
            )
        }
        val reason = when {
            preserved != null -> "Сохранена существующая ручная классификация"
            classification == ProvisionalSourceClass.STAR ->
                "Когерентный sky-space трек отделён от camera-space recurrence"
            classification == ProvisionalSourceClass.SENSOR_DEFECT ->
                "Стабилен в координатах камеры и расходится после sky transform"
            seed.track?.cluster == TemporalMotionCluster.UNSTABLE_OR_UNKNOWN ->
                "Нестабильный temporal track"
            seed.origins.size == 1 && "reference_frame" in seed.origins ->
                "Обнаружен только на reference frame"
            else -> "Недостаточная или противоречивая temporal evidence"
        }
        val id = seed.id ?: stableId(seed.x, seed.y)
        val selectedPath = when (classification) {
            ProvisionalSourceClass.STAR -> sky
            ProvisionalSourceClass.SENSOR_DEFECT -> camera
            ProvisionalSourceClass.UNCERTAIN -> if (sky.recurrence >= camera.recurrence) sky else camera
        }
        return Stage6CandidateDiagnostic(
            id = id,
            referenceX = seed.x,
            referenceY = seed.y,
            boundingBox = localShape.boundingBox,
            shape = localShape.shape,
            cameraSpaceRecurrence = camera.recurrence,
            skySpaceRecurrence = sky.recurrence,
            medianLocalContrast = preserved?.let {
                max(camera.medianContrast, sky.medianContrast)
            } ?: selectedPath.medianContrast,
            chromaResidual = selectedPath.medianChromaResidual,
            estimatedMotionX = when (classification) {
                ProvisionalSourceClass.STAR -> seed.track?.velocityX?.toDouble()
                    ?: registration.model.velocityX.toDouble()
                ProvisionalSourceClass.SENSOR_DEFECT -> 0.0
                ProvisionalSourceClass.UNCERTAIN -> seed.track?.velocityX?.toDouble() ?: 0.0
            },
            estimatedMotionY = when (classification) {
                ProvisionalSourceClass.STAR -> seed.track?.velocityY?.toDouble()
                    ?: registration.model.velocityY.toDouble()
                ProvisionalSourceClass.SENSOR_DEFECT -> 0.0
                ProvisionalSourceClass.UNCERTAIN -> seed.track?.velocityY?.toDouble() ?: 0.0
            },
            registrationResidual = seed.track?.fitResidual?.toDouble()
                ?: registration.model.residual.toDouble(),
            provisionalClass = classification,
            confidence = confidence,
            classificationReason = reason,
            origins = seed.origins.sorted(),
            preserved = preserved != null,
            cameraResidual = preserved?.cameraResidualPx?.toDouble() ?: camera.medianResidual,
            skyResidual = preserved?.skyResidualPx?.toDouble() ?: sky.medianResidual,
            shapeMeasurement = localShape
        )
    }

    private fun trailDiagnostic(
        candidate: Stage6CandidateDiagnostic,
        frames: List<ArgbPixelImage>,
        frameDiagnostics: List<Stage6FrameDiagnostic>,
        reference: ArgbPixelImage,
        leakyManual: ArgbPixelImage,
        manual: ArgbPixelImage,
        clean: ArgbPixelImage,
        enhanced: ArgbPixelImage,
        skyMask: SkyMask
    ): Stage6TrailDiagnostic {
        val contributions = frameDiagnostics.mapIndexed { index, frame ->
            val camera = localPeak(frames[index], candidate.referenceX, candidate.referenceY)
            val mapped = frame.cleanTransform.mapOutputToSource(
                candidate.referenceX.toFloat(),
                candidate.referenceY.toFloat()
            )
            val sky = localPeak(frames[index], mapped.x.toDouble(), mapped.y.toDouble())
            Stage6TrailContribution(
                frameIndex = frame.frameIndex,
                frameId = frame.frameId,
                transform = frame.manualTransform,
                manualIntegrationWeight = frame.manualIntegrationWeight,
                cleanIntegrationWeight = frame.cleanIntegrationWeight,
                cameraContrast = camera.contrast,
                skyContrast = sky.contrast
            )
        }
        val classPathCamera = candidate.provisionalClass == ProvisionalSourceClass.SENSOR_DEFECT
        val contributing = contributions.filter {
            it.manualIntegrationWeight > 0.0 &&
                (if (classPathCamera) it.cameraContrast else it.skyContrast) >= RECURRENCE_CONTRAST
        }
        val sourceShapes = frames.mapIndexedNotNull { index, image ->
            val point = if (classPathCamera) {
                candidate.referenceX to candidate.referenceY
            } else {
                val mapped = frameDiagnostics[index].manualTransform.mapOutputToSource(
                    candidate.referenceX.toFloat(),
                    candidate.referenceY.toFloat()
                )
                mapped.x.toDouble() to mapped.y.toDouble()
            }
            measureShape(image, point.first, point.second)
                .takeIf { localPeak(image, point.first, point.second).contrast >= RECURRENCE_CONTRAST }
        }
        val medianSourceElongation = median(sourceShapes.map { it.elongation })
        val presentInSources = medianSourceElongation >= SOURCE_ELONGATION_THRESHOLD
        val referenceContrast = localPeak(reference, candidate.referenceX, candidate.referenceY).contrast
        val manualContrast = localPeak(manual, candidate.referenceX, candidate.referenceY).contrast
        val cleanContrast = localPeak(clean, candidate.referenceX, candidate.referenceY).contrast
        val enhancedContrast = localPeak(enhanced, candidate.referenceX, candidate.referenceY).contrast
        val introducedOnlyByEnhancement = enhancedContrast >= RECURRENCE_CONTRAST &&
            max(referenceContrast, max(manualContrast, cleanContrast)) < RECURRENCE_CONTRAST
        val rejectedPathPoints = frameDiagnostics.filter {
            it.manualIntegrationWeight == 0.0
        }.map { frame ->
            frame.manualTransform.inverse().mapSourceToOutput(
                candidate.referenceX.toFloat(),
                candidate.referenceY.toFloat()
            )
        }.distinctBy { it.x.roundToInt() to it.y.roundToInt() }
        val leakyRejectedPathContrast = rejectedPathPoints.map {
            exactLocalContrast(leakyManual, it.x.toDouble(), it.y.toDouble()).coerceAtLeast(0.0)
        }.average().takeIf(Double::isFinite) ?: 0.0
        val filteredRejectedPathContrast = rejectedPathPoints.map {
            exactLocalContrast(manual, it.x.toDouble(), it.y.toDouble()).coerceAtLeast(0.0)
        }.average().takeIf(Double::isFinite) ?: 0.0
        val boundary = touchesMaskBoundary(candidate.boundingBox, skyMask)
        val provenance = when {
            candidate.provisionalClass == ProvisionalSourceClass.SENSOR_DEFECT &&
                candidate.cameraSpaceRecurrence >= MIN_DEFECT_RECURRENCE &&
                candidate.shapeMeasurement.majorAxisLength >= MIN_TRAIL_LENGTH ->
                "camera_space_defect_smeared_by_sky_alignment"
            candidate.provisionalClass == ProvisionalSourceClass.STAR && presentInSources ->
                "already_present_in_individual_source_frames"
            candidate.provisionalClass == ProvisionalSourceClass.STAR &&
                candidate.registrationResidual > MAX_STAR_TRACK_RESIDUAL ->
                "possible_registration_residual"
            boundary -> "possible_foreground_sky_mask_boundary"
            else -> "uncertain"
        }
        val reason = when (provenance) {
            "camera_space_defect_smeared_by_sky_alignment" ->
                "Camera-stable compact source projects to an elongated path under the accepted sky transforms"
            "already_present_in_individual_source_frames" ->
                "Comparable elongation is measurable before alignment"
            "possible_registration_residual" ->
                "Sky track residual exceeds the provisional star threshold"
            "possible_foreground_sky_mask_boundary" ->
                "Candidate footprint crosses the estimated sky-mask boundary"
            else -> "Evidence is insufficient for a unique provenance"
        }
        return Stage6TrailDiagnostic(
            candidateId = candidate.id,
            orientationDegrees = candidate.shapeMeasurement.orientationDegrees,
            majorAxisLength = candidate.shapeMeasurement.majorAxisLength,
            elongation = candidate.shapeMeasurement.elongation,
            brightnessProfile = candidate.shapeMeasurement.brightnessProfile,
            chromaProfile = candidate.shapeMeasurement.chromaProfile,
            cameraSpaceOverlap = candidate.cameraSpaceRecurrence,
            skySpaceOverlap = candidate.skySpaceRecurrence,
            contributingFrameIndices = contributing.map { it.frameIndex },
            rejectedFramesWithManualWeight = contributions.filter {
                it.cleanIntegrationWeight == 0.0 && it.manualIntegrationWeight > 0.0
            }.map { it.frameIndex },
            contributions = contributions,
            presentInIndividualFrames = presentInSources,
            introducedOnlyByEnhancement = introducedOnlyByEnhancement,
            interpolationArtifact = false,
            foregroundBoundaryArtifact = boundary,
            leakyBaselineRejectedPathContrast = leakyRejectedPathContrast,
            filteredManualRejectedPathContrast = filteredRejectedPathContrast,
            provenance = provenance,
            reason = reason
        )
    }

    private fun integrate(
        frames: List<ArgbPixelImage>,
        transforms: List<ReferenceToSourceTransform>,
        weights: List<Double>,
        bilinear: Boolean
    ): ArgbPixelImage {
        require(frames.size == transforms.size && frames.size == weights.size)
        val width = frames.first().width
        val height = frames.first().height
        val red = DoubleArray(width * height)
        val green = DoubleArray(width * height)
        val blue = DoubleArray(width * height)
        val totals = DoubleArray(width * height)
        frames.indices.forEach { frameIndex ->
            val weight = weights[frameIndex]
            if (weight <= 0.0) return@forEach
            val image = frames[frameIndex]
            val transform = transforms[frameIndex]
            for (y in 0 until height) for (x in 0 until width) {
                val mapped = transform.mapOutputToSource(x.toFloat(), y.toFloat())
                val color = if (bilinear) {
                    bilinear(image, mapped.x.toDouble(), mapped.y.toDouble())
                } else {
                    nearest(image, mapped.x.toDouble(), mapped.y.toDouble())
                } ?: continue
                val index = y * width + x
                red[index] += (color ushr 16 and 0xFF) * weight
                green[index] += (color ushr 8 and 0xFF) * weight
                blue[index] += (color and 0xFF) * weight
                totals[index] += weight
            }
        }
        return ArgbPixelImage(
            width,
            height,
            IntArray(width * height) { index ->
                val weight = totals[index]
                if (weight <= 0.0) {
                    0xFF000000.toInt()
                } else {
                    rgb(
                        (red[index] / weight).roundToInt(),
                        (green[index] / weight).roundToInt(),
                        (blue[index] / weight).roundToInt()
                    )
                }
            }
        )
    }

    private fun displayStretch(image: ArgbPixelImage): ArgbPixelImage = ArgbPixelImage(
        image.width,
        image.height,
        IntArray(image.pixels.size) { index ->
            val color = image.pixels[index]
            rgb(
                stretchChannel(color ushr 16 and 0xFF),
                stretchChannel(color ushr 8 and 0xFF),
                stretchChannel(color and 0xFF)
            )
        }
    )

    private fun stretchChannel(value: Int): Int =
        ((value / 255.0).pow(DISPLAY_GAMMA) * 255.0).roundToInt().coerceIn(0, 255)

    private fun measurePath(
        frames: List<ArgbPixelImage>,
        referenceX: Double,
        referenceY: Double,
        coordinate: (Int, ArgbPixelImage, Double, Double) -> Pair<Double, Double>
    ): Stage6PathMeasurement {
        val observations = frames.mapIndexed { index, image ->
            val expected = coordinate(index, image, referenceX, referenceY)
            val peak = localPeak(image, expected.first, expected.second)
            peak to hypot(peak.x - expected.first, peak.y - expected.second)
        }
        val supported = observations.withIndex().filter { it.value.first.contrast >= RECURRENCE_CONTRAST }
        return Stage6PathMeasurement(
            recurrence = supported.size,
            medianContrast = median(supported.map { it.value.first.contrast }),
            medianChromaResidual = median(supported.map { it.value.first.chromaResidual }),
            medianResidual = median(supported.map { it.value.second }),
            observedFrameIndices = supported.map { it.index + 1 }
        )
    }

    private fun localPeak(image: ArgbPixelImage, expectedX: Double, expectedY: Double): LocalPeak {
        val centerX = expectedX.roundToInt()
        val centerY = expectedY.roundToInt()
        var best = LocalPeak(expectedX, expectedY, 0.0, 0.0)
        for (dy in -LOCAL_SEARCH_RADIUS..LOCAL_SEARCH_RADIUS) {
            for (dx in -LOCAL_SEARCH_RADIUS..LOCAL_SEARCH_RADIUS) {
                val x = centerX + dx
                val y = centerY + dy
                if (x !in LOCAL_RING_RADIUS until image.width - LOCAL_RING_RADIUS ||
                    y !in LOCAL_RING_RADIUS until image.height - LOCAL_RING_RADIUS
                ) continue
                val center = signal(image.pixels[y * image.width + x])
                val ring = mutableListOf<PixelSignal>()
                for (offset in -LOCAL_RING_RADIUS..LOCAL_RING_RADIUS) {
                    ring += signal(image.pixels[(y - LOCAL_RING_RADIUS) * image.width + x + offset])
                    ring += signal(image.pixels[(y + LOCAL_RING_RADIUS) * image.width + x + offset])
                    if (abs(offset) < LOCAL_RING_RADIUS) {
                        ring += signal(image.pixels[(y + offset) * image.width + x - LOCAL_RING_RADIUS])
                        ring += signal(image.pixels[(y + offset) * image.width + x + LOCAL_RING_RADIUS])
                    }
                }
                val backgroundLuminance = median(ring.map { it.luminance })
                val backgroundRed = median(ring.map { it.red })
                val backgroundGreen = median(ring.map { it.green })
                val backgroundBlue = median(ring.map { it.blue })
                val contrast = center.luminance - backgroundLuminance
                val residuals = listOf(
                    center.red - backgroundRed,
                    center.green - backgroundGreen,
                    center.blue - backgroundBlue
                )
                val chroma = residuals.max() - residuals.min()
                if (
                    contrast > best.contrast ||
                    (
                        contrast == best.contrast &&
                            (
                                y.toDouble() < best.y ||
                                    y.toDouble() == best.y && x.toDouble() < best.x
                                )
                        )
                ) {
                    best = LocalPeak(x.toDouble(), y.toDouble(), contrast, chroma)
                }
            }
        }
        return best
    }

    private fun exactLocalContrast(
        image: ArgbPixelImage,
        expectedX: Double,
        expectedY: Double
    ): Double {
        val x = expectedX.roundToInt()
        val y = expectedY.roundToInt()
        if (
            x !in LOCAL_RING_RADIUS until image.width - LOCAL_RING_RADIUS ||
            y !in LOCAL_RING_RADIUS until image.height - LOCAL_RING_RADIUS
        ) return 0.0
        val center = signal(image.pixels[y * image.width + x]).luminance
        val ring = mutableListOf<Double>()
        for (offset in -LOCAL_RING_RADIUS..LOCAL_RING_RADIUS) {
            ring += signal(
                image.pixels[(y - LOCAL_RING_RADIUS) * image.width + x + offset]
            ).luminance
            ring += signal(
                image.pixels[(y + LOCAL_RING_RADIUS) * image.width + x + offset]
            ).luminance
            if (abs(offset) < LOCAL_RING_RADIUS) {
                ring += signal(
                    image.pixels[(y + offset) * image.width + x - LOCAL_RING_RADIUS]
                ).luminance
                ring += signal(
                    image.pixels[(y + offset) * image.width + x + LOCAL_RING_RADIUS]
                ).luminance
            }
        }
        return center - median(ring)
    }

    private fun measureShape(image: ArgbPixelImage, centerX: Double, centerY: Double): Stage6ShapeMeasurement {
        val left = (centerX.roundToInt() - SHAPE_RADIUS).coerceIn(0, image.width - 1)
        val top = (centerY.roundToInt() - SHAPE_RADIUS).coerceIn(0, image.height - 1)
        val right = (centerX.roundToInt() + SHAPE_RADIUS).coerceIn(0, image.width - 1)
        val bottom = (centerY.roundToInt() + SHAPE_RADIUS).coerceIn(0, image.height - 1)
        val border = mutableListOf<Double>()
        for (y in top..bottom) for (x in left..right) {
            if (x == left || x == right || y == top || y == bottom) {
                border += signal(image.pixels[y * image.width + x]).luminance
            }
        }
        val background = median(border)
        val samples = mutableListOf<WeightedPoint>()
        var peak = 0.0
        for (y in top..bottom) for (x in left..right) {
            val pixel = signal(image.pixels[y * image.width + x])
            peak = max(peak, pixel.luminance - background)
        }
        val threshold = max(MIN_SHAPE_SIGNAL, peak * SHAPE_SIGNAL_FRACTION)
        for (y in top..bottom) for (x in left..right) {
            val pixel = signal(image.pixels[y * image.width + x])
            val weight = pixel.luminance - background
            if (weight >= threshold) samples += WeightedPoint(x.toDouble(), y.toDouble(), weight)
        }
        if (samples.isEmpty()) {
            val x = centerX.roundToInt().coerceIn(0, image.width - 1)
            val y = centerY.roundToInt().coerceIn(0, image.height - 1)
            return Stage6ShapeMeasurement(
                Stage6BoundingBox(x, y, x, y),
                Stage6CandidateShape.IRREGULAR,
                0.0,
                1.0,
                1.0,
                1.0,
                emptyList(),
                emptyList()
            )
        }
        return morphology(image, samples)
    }

    private fun pathShape(image: ArgbPixelImage, path: List<Pair<Double, Double>>): Stage6ShapeMeasurement {
        val start = path.first()
        val end = path.last()
        val dx = end.first - start.first
        val dy = end.second - start.second
        val length = hypot(dx, dy).coerceAtLeast(1.0)
        val samples = (0..PROFILE_SAMPLES).map { index ->
            val fraction = index.toDouble() / PROFILE_SAMPLES
            val x = start.first + dx * fraction
            val y = start.second + dy * fraction
            val peak = localPeak(image, x, y)
            WeightedPoint(peak.x, peak.y, max(peak.contrast, MIN_SHAPE_SIGNAL))
        }
        val measured = morphology(image, samples)
        val bbox = Stage6BoundingBox(
            floor(path.minOf { it.first } - PATH_BOX_MARGIN).toInt().coerceAtLeast(0),
            floor(path.minOf { it.second } - PATH_BOX_MARGIN).toInt().coerceAtLeast(0),
            ceil(path.maxOf { it.first } + PATH_BOX_MARGIN).toInt().coerceAtMost(image.width - 1),
            ceil(path.maxOf { it.second } + PATH_BOX_MARGIN).toInt().coerceAtMost(image.height - 1)
        )
        val profiles = profile(image, start, end)
        return measured.copy(
            boundingBox = bbox,
            shape = Stage6CandidateShape.ELONGATED,
            orientationDegrees = normalizeOrientation(Math.toDegrees(atan2(dy, dx))),
            majorAxisLength = max(length, measured.majorAxisLength),
            minorAxisLength = max(1.0, measured.minorAxisLength),
            elongation = max(length / max(1.0, measured.minorAxisLength), measured.elongation),
            brightnessProfile = profiles.first,
            chromaProfile = profiles.second
        )
    }

    private fun morphology(image: ArgbPixelImage, samples: List<WeightedPoint>): Stage6ShapeMeasurement {
        val total = samples.sumOf { it.weight }.coerceAtLeast(1e-6)
        val cx = samples.sumOf { it.x * it.weight } / total
        val cy = samples.sumOf { it.y * it.weight } / total
        val xx = samples.sumOf { (it.x - cx).pow(2) * it.weight } / total
        val yy = samples.sumOf { (it.y - cy).pow(2) * it.weight } / total
        val xy = samples.sumOf { (it.x - cx) * (it.y - cy) * it.weight } / total
        val trace = xx + yy
        val discriminant = sqrt(max(0.0, (xx - yy).pow(2) + 4.0 * xy * xy))
        val majorVariance = max(0.04, (trace + discriminant) * 0.5)
        val minorVariance = max(0.04, (trace - discriminant) * 0.5)
        val major = 4.0 * sqrt(majorVariance)
        val minor = 4.0 * sqrt(minorVariance)
        val elongation = major / minor.coerceAtLeast(0.2)
        val orientation = normalizeOrientation(
            Math.toDegrees(0.5 * atan2(2.0 * xy, xx - yy))
        )
        val shape = when {
            elongation >= ELONGATED_RATIO && major >= MIN_TRAIL_LENGTH ->
                Stage6CandidateShape.ELONGATED
            elongation <= COMPACT_RATIO && major <= MAX_COMPACT_LENGTH ->
                Stage6CandidateShape.COMPACT
            else -> Stage6CandidateShape.IRREGULAR
        }
        val bbox = Stage6BoundingBox(
            floor(samples.minOf { it.x }).toInt().coerceIn(0, image.width - 1),
            floor(samples.minOf { it.y }).toInt().coerceIn(0, image.height - 1),
            ceil(samples.maxOf { it.x }).toInt().coerceIn(0, image.width - 1),
            ceil(samples.maxOf { it.y }).toInt().coerceIn(0, image.height - 1)
        )
        val angle = Math.toRadians(orientation)
        val half = major * 0.5
        val start = cx - cos(angle) * half to cy - sin(angle) * half
        val end = cx + cos(angle) * half to cy + sin(angle) * half
        val profiles = profile(image, start, end)
        return Stage6ShapeMeasurement(
            bbox,
            shape,
            orientation,
            major,
            minor,
            elongation,
            profiles.first,
            profiles.second
        )
    }

    private fun profile(
        image: ArgbPixelImage,
        start: Pair<Double, Double>,
        end: Pair<Double, Double>
    ): Pair<List<Double>, List<Double>> {
        val luminance = mutableListOf<Double>()
        val chroma = mutableListOf<Double>()
        for (index in 0..PROFILE_SAMPLES) {
            val fraction = index.toDouble() / PROFILE_SAMPLES
            val x = start.first + (end.first - start.first) * fraction
            val y = start.second + (end.second - start.second) * fraction
            val peak = localPeak(image, x, y)
            luminance += peak.contrast
            chroma += peak.chromaResidual
        }
        return luminance to chroma
    }

    internal fun writeExpandedGroundTruth(
        bundle: Stage6DiagnosticBundle,
        existingGroundTruth: Path,
        output: Path
    ) {
        val existingLines = Files.readAllLines(existingGroundTruth, StandardCharsets.UTF_8)
        val retainedLines = existingLines.filter { line ->
            val trimmed = line.trim()
            trimmed.isEmpty() ||
                trimmed.startsWith('#') ||
                !isGeneratedCandidateId(trimmed.substringBefore(','))
        }
        val existingIds = retainedLines.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.substringBefore(',') }
            .toSet()
        val appended = bundle.candidates.filterNot { it.id in existingIds }.map { candidate ->
            val coordinateSpace = when (candidate.provisionalClass) {
                ProvisionalSourceClass.STAR -> "sky"
                ProvisionalSourceClass.SENSOR_DEFECT -> "camera"
                ProvisionalSourceClass.UNCERTAIN -> "unknown"
            }
            val support = when (candidate.provisionalClass) {
                ProvisionalSourceClass.STAR -> candidate.skySpaceRecurrence
                ProvisionalSourceClass.SENSOR_DEFECT -> candidate.cameraSpaceRecurrence
                ProvisionalSourceClass.UNCERTAIN ->
                    max(candidate.cameraSpaceRecurrence, candidate.skySpaceRecurrence)
            }.coerceIn(1, EXPECTED_FRAME_COUNT)
            listOf(
                candidate.id,
                candidate.provisionalClass.name.lowercase(),
                number(candidate.referenceX),
                number(candidate.referenceY),
                coordinateSpace,
                support,
                number(candidate.skyResidual),
                number(candidate.cameraResidual),
                number(candidate.confidence),
                candidate.classificationReason.replace(',', ';')
            ).joinToString(",")
        }
        Files.createDirectories(checkNotNull(output.parent))
        Files.write(
            output,
            (retainedLines + appended).joinToString(
                System.lineSeparator(),
                postfix = System.lineSeparator()
            )
                .toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun writeCandidateContactSheet(bundle: Stage6DiagnosticBundle, output: Path) {
        val columns = CONTACT_COLUMNS
        val rows = ceil(bundle.candidates.size.toDouble() / columns).toInt().coerceAtLeast(1)
        val image = BufferedImage(columns * CONTACT_CELL_WIDTH, rows * CONTACT_CELL_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.color = Color(18, 18, 22)
            graphics.fillRect(0, 0, image.width, image.height)
            val base = buffered(bundle.enhancedProfile)
            bundle.candidates.forEachIndexed { index, candidate ->
                val cellX = index % columns * CONTACT_CELL_WIDTH
                val cellY = index / columns * CONTACT_CELL_HEIGHT
                drawCrop(
                    graphics,
                    base,
                    candidate.referenceX,
                    candidate.referenceY,
                    CONTACT_CROP_SIZE,
                    cellX + 4,
                    cellY + 4,
                    CONTACT_IMAGE_SIZE
                )
                graphics.color = classColor(candidate.provisionalClass)
                graphics.stroke = BasicStroke(2f)
                graphics.drawRect(cellX + 4, cellY + 4, CONTACT_IMAGE_SIZE, CONTACT_IMAGE_SIZE)
                graphics.font = Font(Font.MONOSPACED, Font.BOLD, 12)
                graphics.drawString(candidate.id, cellX + 6, cellY + CONTACT_IMAGE_SIZE + 20)
                graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                graphics.drawString(
                    "${candidate.provisionalClass.name.lowercase()} ${candidate.shape.name.lowercase()}",
                    cellX + 6,
                    cellY + CONTACT_IMAGE_SIZE + 36
                )
                graphics.drawString(
                    "cam=${candidate.cameraSpaceRecurrence} sky=${candidate.skySpaceRecurrence}",
                    cellX + 6,
                    cellY + CONTACT_IMAGE_SIZE + 51
                )
            }
            base.flush()
        } finally {
            graphics.dispose()
        }
        Files.createDirectories(checkNotNull(output.parent))
        check(ImageIO.write(image, "png", output.toFile()))
        image.flush()
    }

    private fun writeTrailContactSheet(bundle: Stage6DiagnosticBundle, output: Path) {
        val rows = bundle.trails.size.coerceAtLeast(1)
        val image = BufferedImage(
            TRAIL_LABEL_WIDTH + TRAIL_COLUMNS * TRAIL_CELL_SIZE,
            TRAIL_HEADER_HEIGHT + rows * TRAIL_CELL_SIZE,
            BufferedImage.TYPE_INT_RGB
        )
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.color = Color(16, 16, 20)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.WHITE
            graphics.font = Font(Font.MONOSPACED, Font.BOLD, 13)
            listOf(
                "reference",
                "manual before",
                "manual filtered",
                "clean stack",
                "enhanced profile"
            ).forEachIndexed { index, label ->
                graphics.drawString(
                    label,
                    TRAIL_LABEL_WIDTH + index * TRAIL_CELL_SIZE + 6,
                    19
                )
            }
            val sources = listOf(
                buffered(bundle.referenceImage),
                buffered(bundle.leakyManualAlignedStack),
                buffered(bundle.manualAlignedStack),
                buffered(bundle.cleanStack),
                buffered(bundle.enhancedProfile)
            )
            bundle.trails.forEachIndexed { row, trail ->
                val candidate = bundle.candidates.single { it.id == trail.candidateId }
                val top = TRAIL_HEADER_HEIGHT + row * TRAIL_CELL_SIZE
                graphics.color = Color.WHITE
                graphics.font = Font(Font.MONOSPACED, Font.BOLD, 12)
                graphics.drawString(trail.candidateId, 5, top + 18)
                graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
                graphics.drawString(trail.provenance.take(22), 5, top + 35)
                sources.forEachIndexed { column, source ->
                    drawCrop(
                        graphics,
                        source,
                        candidate.referenceX,
                        candidate.referenceY,
                        TRAIL_CROP_SIZE,
                        TRAIL_LABEL_WIDTH + column * TRAIL_CELL_SIZE,
                        top,
                        TRAIL_CELL_SIZE
                    )
                }
            }
            sources.forEach(BufferedImage::flush)
        } finally {
            graphics.dispose()
        }
        Files.createDirectories(checkNotNull(output.parent))
        check(ImageIO.write(image, "png", output.toFile()))
        image.flush()
    }

    private fun drawCrop(
        graphics: java.awt.Graphics2D,
        source: BufferedImage,
        centerX: Double,
        centerY: Double,
        cropSize: Int,
        destinationX: Int,
        destinationY: Int,
        destinationSize: Int
    ) {
        val size = min(cropSize, min(source.width, source.height))
        val left = (centerX.roundToInt() - size / 2).coerceIn(0, source.width - size)
        val top = (centerY.roundToInt() - size / 2).coerceIn(0, source.height - size)
        graphics.drawImage(
            source,
            destinationX,
            destinationY,
            destinationX + destinationSize,
            destinationY + destinationSize,
            left,
            top,
            left + size,
            top + size,
            null
        )
    }

    private fun touchesMaskBoundary(box: Stage6BoundingBox, mask: SkyMask): Boolean {
        var hasSky = false
        var hasForeground = false
        val left = (box.left - MASK_BOUNDARY_MARGIN).coerceAtLeast(0)
        val top = (box.top - MASK_BOUNDARY_MARGIN).coerceAtLeast(0)
        val right = (box.right + MASK_BOUNDARY_MARGIN).coerceAtMost(mask.width - 1)
        val bottom = (box.bottom + MASK_BOUNDARY_MARGIN).coerceAtMost(mask.height - 1)
        for (y in top..bottom) for (x in left..right) {
            if (mask.contains(x, y)) hasSky = true else hasForeground = true
        }
        return hasSky && hasForeground
    }

    private fun mergeSeed(
        seeds: MutableList<CandidateSeed>,
        x: Double,
        y: Double,
        origin: String,
        track: TemporalFeatureTrack? = null,
        artifact: StaticArtifactRegion? = null,
        detected: DetectedStar? = null
    ) {
        if (!x.isFinite() || !y.isFinite()) return
        val match = seeds.minByOrNull { squaredDistance(it.x, it.y, x, y) }
            ?.takeIf { squaredDistance(it.x, it.y, x, y) <= MERGE_RADIUS * MERGE_RADIUS }
        if (match == null) {
            seeds += CandidateSeed(
                id = null,
                x = x,
                y = y,
                origins = linkedSetOf(origin),
                track = track,
                artifact = artifact,
                detected = detected,
                preserved = null
            )
        } else {
            match.origins += origin
            if (match.track == null || track != null && track.presenceRatio > match.track!!.presenceRatio) {
                match.track = track
            }
            if (match.artifact == null || artifact != null && artifact.confidence > match.artifact!!.confidence) {
                match.artifact = artifact
            }
            if (match.detected == null || detected != null && detected.confidence > match.detected!!.confidence) {
                match.detected = detected
            }
        }
    }

    private fun trackReferencePoint(
        track: TemporalFeatureTrack,
        referenceId: String,
        referenceCaptureIndex: Int
    ): Pair<Double, Double>? {
        track.observations.firstOrNull { it.frameId == referenceId }?.let {
            return it.star.x.toDouble() to it.star.y.toDouble()
        }
        val anchor = track.observations.firstOrNull() ?: return null
        val delta = referenceCaptureIndex - anchor.captureIndex
        return (
            anchor.star.x + track.velocityX * delta
            ).toDouble() to (
            anchor.star.y + track.velocityY * delta
            ).toDouble()
    }

    private fun sourceToOutput(
        transform: ReferenceToSourceTransform,
        sourceX: Double,
        sourceY: Double
    ): Pair<Double, Double> {
        val mapped = transform.inverse().mapSourceToOutput(sourceX.toFloat(), sourceY.toFloat())
        return mapped.x.toDouble() to mapped.y.toDouble()
    }

    private fun nearest(image: ArgbPixelImage, x: Double, y: Double): Int? {
        val sx = x.roundToInt()
        val sy = y.roundToInt()
        return if (sx in 0 until image.width && sy in 0 until image.height) {
            image.pixels[sy * image.width + sx]
        } else {
            null
        }
    }

    private fun bilinear(image: ArgbPixelImage, x: Double, y: Double): Int? {
        if (x < 0.0 || y < 0.0 || x > image.width - 1.0 || y > image.height - 1.0) return null
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, image.width - 1)
        val y1 = min(y0 + 1, image.height - 1)
        val fx = x - x0
        val fy = y - y0
        val c00 = image.pixels[y0 * image.width + x0]
        val c10 = image.pixels[y0 * image.width + x1]
        val c01 = image.pixels[y1 * image.width + x0]
        val c11 = image.pixels[y1 * image.width + x1]
        fun channel(shift: Int): Int {
            val top = (c00 ushr shift and 0xFF) * (1.0 - fx) + (c10 ushr shift and 0xFF) * fx
            val bottom = (c01 ushr shift and 0xFF) * (1.0 - fx) + (c11 ushr shift and 0xFF) * fx
            return (top * (1.0 - fy) + bottom * fy).roundToInt()
        }
        return rgb(channel(16), channel(8), channel(0))
    }

    private fun buffered(image: ArgbPixelImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        }

    private fun configure(graphics: java.awt.Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun classColor(classification: ProvisionalSourceClass): Color = when (classification) {
        ProvisionalSourceClass.STAR -> Color(90, 210, 255)
        ProvisionalSourceClass.SENSOR_DEFECT -> Color(255, 95, 120)
        ProvisionalSourceClass.UNCERTAIN -> Color(255, 210, 90)
    }

    private fun signal(color: Int): PixelSignal {
        val red = (color ushr 16 and 0xFF).toDouble()
        val green = (color ushr 8 and 0xFF).toDouble()
        val blue = (color and 0xFF).toDouble()
        return PixelSignal(red, green, blue, red * 0.299 + green * 0.587 + blue * 0.114)
    }

    private fun stableId(x: Double, y: Double): String =
        "candidate-x${(x * 100).roundToInt().toString().padStart(5, '0')}" +
            "-y${(y * 100).roundToInt().toString().padStart(5, '0')}"

    private fun isGeneratedCandidateId(id: String): Boolean =
        id.startsWith(GENERATED_CANDIDATE_PREFIX)

    private fun frameId(index: Int): String = "frame-${index.toString().padStart(3, '0')}.jpg"

    private fun pathLength(path: List<Pair<Double, Double>>): Double =
        path.zipWithNext().sumOf { (first, second) ->
            hypot(second.first - first.first, second.second - first.second)
        }

    private fun normalizeOrientation(value: Double): Double {
        var normalized = value
        while (normalized < 0.0) normalized += 180.0
        while (normalized >= 180.0) normalized -= 180.0
        return normalized
    }

    private fun squaredDistance(x1: Double, y1: Double, x2: Double, y2: Double): Double =
        (x1 - x2).pow(2) + (y1 - y2).pow(2)

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) * 0.5
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)

    private fun number(value: Double): String =
        String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

    private fun transformJson(transform: ReferenceToSourceTransform): String =
        "{\"dx\": ${number(transform.dx.toDouble())}, \"dy\": ${number(transform.dy.toDouble())}, " +
            "\"rotationRadians\": ${number(transform.rotationRadians.toDouble())}, " +
            "\"scale\": ${number(transform.scale.toDouble())}}"

    private fun boxJson(box: Stage6BoundingBox): String =
        "{\"left\": ${box.left}, \"top\": ${box.top}, \"right\": ${box.right}, \"bottom\": ${box.bottom}}"

    private fun stringArray(values: List<String>): String = values.joinToString(
        prefix = "[",
        postfix = "]"
    ) { json(it) }

    private fun numberArray(values: List<Double>): String = values.joinToString(
        prefix = "[",
        postfix = "]"
    ) { number(it) }

    private fun intArray(values: List<Int>): String = values.joinToString(prefix = "[", postfix = "]")

    private data class CandidateSeed(
        val id: String?,
        val x: Double,
        val y: Double,
        val origins: LinkedHashSet<String>,
        var track: TemporalFeatureTrack? = null,
        var artifact: StaticArtifactRegion? = null,
        var detected: DetectedStar? = null,
        val preserved: ProvisionalSourceLabel? = null
    )

    private data class PixelSignal(
        val red: Double,
        val green: Double,
        val blue: Double,
        val luminance: Double
    )

    private data class LocalPeak(
        val x: Double,
        val y: Double,
        val contrast: Double,
        val chromaResidual: Double
    )

    private data class WeightedPoint(val x: Double, val y: Double, val weight: Double)

    companion object {
        private const val EXPECTED_FRAME_COUNT = 30
        private const val DISPLAY_GAMMA = 0.35
        private const val MERGE_RADIUS = 8.0
        private const val PROPOSAL_MIN_CONTRAST = 1.25
        private const val PROPOSAL_SEPARATION = 7.0
        private const val MAX_PROPOSALS_PER_IMAGE = 64
        private const val MAX_CANDIDATES = 32
        private const val REVIEW_LEFT_EDGE = 210.0
        private const val REVIEW_FOREGROUND_TOP = 600.0
        private const val REVIEW_LOWER_LEFT_EDGE = 425.0
        private const val REVIEW_LOWER_EDGE_SLOPE = 0.10
        private const val REVIEW_RIGHT_EDGE = 638.0
        private const val REVIEW_BOTTOM = 930.0
        private const val RECURRENCE_CONTRAST = 3.0
        private const val MIN_CANDIDATE_RECURRENCE = 3
        private const val MIN_STANDALONE_CONTRAST = 3.0
        private const val MIN_TRAIL_CONTRAST = 4.0
        private const val MIN_STAR_PRESENCE = 0.50f
        private const val MAX_STAR_TRACK_RESIDUAL = 0.65f
        private const val MAX_TRACK_RESIDUAL = 0.85
        private const val MIN_STAR_RECURRENCE = 15
        private const val MIN_DEFECT_RECURRENCE = 18
        private const val MIN_RECURRENCE_SEPARATION = 5
        private const val LOCAL_SEARCH_RADIUS = 2
        private const val LOCAL_RING_RADIUS = 3
        private const val SHAPE_RADIUS = 12
        private const val MIN_SHAPE_SIGNAL = 1.25
        private const val SHAPE_SIGNAL_FRACTION = 0.32
        private const val ELONGATED_RATIO = 1.8
        private const val COMPACT_RATIO = 1.55
        private const val MAX_COMPACT_LENGTH = 7.5
        private const val MIN_TRAIL_LENGTH = 5.0
        private const val SOURCE_ELONGATION_THRESHOLD = 1.8
        private const val PATH_BOX_MARGIN = 4.0
        private const val PROFILE_SAMPLES = 16
        private const val MASK_BOUNDARY_MARGIN = 3
        private const val CONTACT_COLUMNS = 4
        private const val CONTACT_CELL_WIDTH = 210
        private const val CONTACT_CELL_HEIGHT = 250
        private const val CONTACT_CROP_SIZE = 72
        private const val CONTACT_IMAGE_SIZE = 200
        private const val TRAIL_COLUMNS = 5
        private const val TRAIL_LABEL_WIDTH = 180
        private const val TRAIL_HEADER_HEIGHT = 28
        private const val TRAIL_CELL_SIZE = 160
        private const val TRAIL_CROP_SIZE = 80
        private const val GENERATED_CANDIDATE_PREFIX = "candidate-"
        private val REQUIRED_ORIGINS = listOf(
            "reference_frame",
            "aligned_clean_stack",
            "manual_aligned_stack",
            "enhanced_profile",
            "camera_space_temporal_persistence",
            "sky_space_temporal_motion"
        )
    }
}
