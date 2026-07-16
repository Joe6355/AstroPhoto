package com.example.astrophoto.processing.jpeg.v2.diagnostics

import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingParameters
import com.example.astrophoto.processing.jpeg.v2.model.LinearRgb
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics

data class FrameRegistrationReport(
    val frameName: String,
    val accepted: Boolean,
    val rejectionReason: String?,
    val detectedStars: Int,
    val matchedStars: Int,
    val inlierStars: Int,
    val dx: Float,
    val dy: Float,
    val rotationRadians: Float,
    val scale: Float,
    val residualError: Float,
    val confidence: Float,
    val registrationModel: String = "LEGACY_SIMILARITY",
    val scaleFixed: Boolean = false,
    val rotationAllowed: Boolean = false,
    val rotationRejectionReason: String? = null,
    val occupiedDistributionCells: Int = 0,
    val horizontalDistributionSpan: Float = 0f,
    val verticalDistributionSpan: Float = 0f,
    val spatialDistributionScore: Float = 0f,
    val rawDx: Float = dx,
    val rawDy: Float = dy,
    val rawRotationRadians: Float = rotationRadians,
    val transformSequenceScore: Float = 1f,
    val transformSequenceDeviation: Float = 0f,
    val neighborTransformDelta: Float = 0f,
    val transformRetryUsed: Boolean = false
)

data class FrameWeightReport(
    val frameName: String,
    val registrationWeight: Float,
    val sharpnessWeight: Float,
    val trailWeight: Float,
    val noiseWeight: Float,
    val exposureWeight: Float,
    val normalizedWeight: Float
)

data class IntegrationReport(
    val mode: String,
    val robustMode: Boolean,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val resolutionChanged: Boolean,
    val validCoveragePercent: Float,
    val estimatedWorkingMemoryBytes: Long,
    val outputAllocationBytes: Long,
    val diskCacheBytes: Long,
    val robustModeReason: String = if (robustMode) {
        "legacy_repeatability_mode"
    } else {
        "plain_weighted_average"
    }
)

data class ProcessingReport(
    val schemaVersion: String = SCHEMA_VERSION,
    val timestampMillis: Long,
    val presetId: String,
    val presetDisplayName: String,
    val inputFrameCount: Int,
    val eligibleFrameCount: Int,
    val acceptedFrameCount: Int,
    val rejectedFrameCount: Int,
    val selectedReference: String,
    val skyMaskConfidence: Float,
    val skyRatio: Float,
    val foregroundRatio: Float,
    val registrations: List<FrameRegistrationReport>,
    val frameWeights: List<FrameWeightReport>,
    val integration: IntegrationReport,
    val stage4Parameters: AdaptiveProcessingParameters,
    val referenceMetrics: ResultQualityMetrics,
    val cleanStackMetrics: ResultQualityMetrics,
    val processedMetrics: ResultQualityMetrics,
    val cleanStackDecision: QualityGateDecision,
    val processedDecision: QualityGateDecision,
    val selectedCandidateType: String,
    val fallbackUsed: Boolean,
    val fallbackReason: String?,
    val internalFallbackLabel: String?,
    val warnings: List<String>,
    val outputPngDisplayName: String,
    val stageDurationsMillis: Map<String, Long>,
    val stage4Executed: Boolean = true,
    val cleanStackAccepted: Boolean = cleanStackDecision.accepted,
    val cleanStackRejectionReasons: List<String> = cleanStackDecision.hardFailureReasons,
    val referenceReliableStarCount: Int = referenceMetrics.reliableStarCount,
    val retainedReferenceStarCount: Int = referenceMetrics.reliableStarCount,
    val referenceStarRetentionRatio: Float = 1f,
    val referenceStarContrastBefore: Float = referenceMetrics.medianStarLocalContrast,
    val referenceStarContrastAfter: Float = cleanStackMetrics.medianStarLocalContrast,
    val referenceStarWidthBefore: Float = referenceMetrics.medianStarWidth,
    val referenceStarWidthAfter: Float = cleanStackMetrics.medianStarWidth,
    val referenceStarSmearRate: Float = 0f,
    val coverageMinimum: Float = 1f,
    val coverageMedian: Float = 1f,
    val coverageMaximum: Float = 1f,
    val coverageUniformityScore: Float = 1f,
    val coverageWedgeDiscontinuityScore: Float = 0f,
    val lineArtifactScore: Float = 0f,
    val fanPatternScore: Float = 0f,
    val transformSequenceScore: Float = 1f,
    val registrationSchemaVersion: String = "astrophoto.jpeg.registration/2",
    val temporalTrackCount: Int = 0,
    val stationaryTrackCount: Int = 0,
    val movingTrackCount: Int = 0,
    val unknownTrackCount: Int = 0,
    val motionObservable: Boolean = false,
    val estimatedVelocityXAnalysisPxPerFrame: Float = 0f,
    val estimatedVelocityYAnalysisPxPerFrame: Float = 0f,
    val estimatedVelocityXFullPxPerFrame: Float = 0f,
    val estimatedVelocityYFullPxPerFrame: Float = 0f,
    val sequenceModelScore: Float = 0f,
    val sequenceModelResidual: Float = 0f,
    val zeroModelScore: Float = 0f,
    val nonZeroModelScore: Float = 0f,
    val selectedMotionModel: String = "UNAVAILABLE",
    val candidateHypothesisCountPerFrame: Map<String, Int> = emptyMap(),
    val selectedHypothesisRankPerFrame: Map<String, Int> = emptyMap(),
    val movingTrackSupportPerFrame: Map<String, Int> = emptyMap(),
    val stationaryTrackSupportPerFrame: Map<String, Int> = emptyMap(),
    val spatialSectorSupportPerFrame: Map<String, Int> = emptyMap(),
    val verificationReferenceRetention: Float = 0f,
    val verificationContrastRatio: Float = 0f,
    val verificationWidthGrowth: Float = 0f,
    val verificationSmearRate: Float = 0f,
    val verificationIdentityScore: Float = 0f,
    val verificationZeroModelScore: Float = 0f,
    val verificationSelectedModelScore: Float = 0f,
    val sequenceSmoothnessScore: Float = 0f,
    val sequencePriorAgreementScore: Float = 0f,
    val registrationRejectedReasons: Map<String, String> = emptyMap(),
    val transformContractVersion: String = "astrophoto.jpeg.transform/reference-to-source-v1",
    val transformDirection: String = "OUTPUT_REFERENCE_TO_CANDIDATE_SOURCE",
    val referenceCaptureIndex: Int = 0,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val fullWidth: Int = 0,
    val fullHeight: Int = 0,
    val analysisToFullScaleX: Float = 1f,
    val analysisToFullScaleY: Float = 1f,
    val velocityCoordinateSpace: String = "ANALYSIS_PX_PER_CAPTURE_INDEX",
    val registrationCoordinateSpace: String = "FULL_RESOLUTION_PX",
    val verificationCoordinateSpace: String = "ANALYSIS_THUMBNAIL_PX",
    val samplerCoordinateSpace: String = "OUTPUT_REFERENCE_TO_FULL_SOURCE_PX",
    val referenceIdentityVerified: Boolean = false,
    val inverseTransformVerificationScore: Float = 0f,
    val canonicalTransformVerificationScore: Float = 0f,
    val identityTransformVerificationScore: Float = 0f,
    val doubleTransformVerificationScore: Float = 0f,
    val perFramePredictedDx: Map<String, Float> = emptyMap(),
    val perFramePredictedDy: Map<String, Float> = emptyMap(),
    val perFrameSelectedDx: Map<String, Float> = emptyMap(),
    val perFrameSelectedDy: Map<String, Float> = emptyMap(),
    val perFrameVerificationRetention: Map<String, Float> = emptyMap(),
    val perFrameVerificationContrastRatio: Map<String, Float> = emptyMap(),
    val perFrameVerificationSmearRate: Map<String, Float> = emptyMap(),
    val staticArtifactCandidates: Int = 0,
    val staticArtifactMaskRatio: Float = 0f,
    val memorySchemaVersion: String = "astrophoto.jpeg.memory/1",
    val runtimeMaxHeapBytes: Long = 0L,
    val heapUsedAtRunStartBytes: Long = 0L,
    val safeWorkingBudgetBytes: Long = 0L,
    val peakEstimatedResidentBytes: Long = 0L,
    val peakObservedHeapBytes: Long = 0L,
    val maximumSimultaneousFullResolutionCandidates: Int = 0,
    val candidateStorageMode: String = "FILE_BACKED_ARGB_8888",
    val referenceCandidateBytesOnDisk: Long = 0L,
    val cleanStackCandidateBytesOnDisk: Long = 0L,
    val processedCandidateBytesOnDisk: Long = 0L,
    val tileSizePerStage: Map<String, String> = emptyMap(),
    val haloSizePerStage: Map<String, Int> = emptyMap(),
    val memoryPressureRetries: Int = 0,
    val finalBitmapAllocationBytes: Long = 0L,
    val lastCompletedStage: String = "report_prepared",
    val reportPublicationMode: String = "PRIMARY_WITH_APP_FILES_FALLBACK",
    val reportFallbackUsed: Boolean = false,
    val staleRunRecoveryInformation: String? = null,
    val processingRunId: String? = null,
    val artifactSessionId: String? = null
) {
    fun toJson(): String = buildString {
        append("{\n")
        property("schemaVersion", schemaVersion)
        property("timestampMillis", timestampMillis)
        property("presetId", presetId)
        property("presetDisplayName", presetDisplayName)
        property("inputFrameCount", inputFrameCount)
        property("eligibleFrameCount", eligibleFrameCount)
        property("acceptedFrameCount", acceptedFrameCount)
        property("rejectedFrameCount", rejectedFrameCount)
        property("selectedReference", selectedReference)
        property("skyMaskConfidence", skyMaskConfidence)
        property("skyRatio", skyRatio)
        property("foregroundRatio", foregroundRatio)
        append("  \"registrations\": ")
        appendArray(registrations) { registrationJson(it) }
        append(",\n  \"frameWeights\": ")
        appendArray(frameWeights) { weightJson(it) }
        append(",\n  \"integration\": ${integrationJson(integration)},")
        append("\n  \"stage4Parameters\": ${parametersJson(stage4Parameters)},")
        append("\n  \"referenceMetrics\": ${metricsJson(referenceMetrics)},")
        append("\n  \"cleanStackMetrics\": ${metricsJson(cleanStackMetrics)},")
        append("\n  \"processedResultMetrics\": ${metricsJson(processedMetrics)},")
        append("\n  \"cleanStackDecision\": ${decisionJson(cleanStackDecision)},")
        append("\n  \"processedDecision\": ${decisionJson(processedDecision)},")
        append("\n")
        property("stage4Executed", stage4Executed)
        property("cleanStackAccepted", cleanStackAccepted)
        append("  \"cleanStackRejectionReasons\": ${stringArray(cleanStackRejectionReasons)},\n")
        property("referenceReliableStarCount", referenceReliableStarCount)
        property("retainedReferenceStarCount", retainedReferenceStarCount)
        property("referenceStarRetentionRatio", referenceStarRetentionRatio)
        property("referenceStarContrastBefore", referenceStarContrastBefore)
        property("referenceStarContrastAfter", referenceStarContrastAfter)
        property("referenceStarWidthBefore", referenceStarWidthBefore)
        property("referenceStarWidthAfter", referenceStarWidthAfter)
        property("referenceStarSmearRate", referenceStarSmearRate)
        property("coverageMinimum", coverageMinimum)
        property("coverageMedian", coverageMedian)
        property("coverageMaximum", coverageMaximum)
        property("coverageUniformityScore", coverageUniformityScore)
        property("coverageWedgeDiscontinuityScore", coverageWedgeDiscontinuityScore)
        property("lineArtifactScore", lineArtifactScore)
        property("fanPatternScore", fanPatternScore)
        property("transformSequenceScore", transformSequenceScore)
        property("registrationSchemaVersion", registrationSchemaVersion)
        property("temporalTrackCount", temporalTrackCount)
        property("stationaryTrackCount", stationaryTrackCount)
        property("movingTrackCount", movingTrackCount)
        property("unknownTrackCount", unknownTrackCount)
        property("motionObservable", motionObservable)
        property("estimatedVelocityXAnalysisPxPerFrame", estimatedVelocityXAnalysisPxPerFrame)
        property("estimatedVelocityYAnalysisPxPerFrame", estimatedVelocityYAnalysisPxPerFrame)
        property("estimatedVelocityXFullPxPerFrame", estimatedVelocityXFullPxPerFrame)
        property("estimatedVelocityYFullPxPerFrame", estimatedVelocityYFullPxPerFrame)
        property("sequenceModelScore", sequenceModelScore)
        property("sequenceModelResidual", sequenceModelResidual)
        property("zeroModelScore", zeroModelScore)
        property("nonZeroModelScore", nonZeroModelScore)
        property("selectedMotionModel", selectedMotionModel)
        append("  \"candidateHypothesisCountPerFrame\": ${intMapJson(candidateHypothesisCountPerFrame)},\n")
        append("  \"selectedHypothesisRankPerFrame\": ${intMapJson(selectedHypothesisRankPerFrame)},\n")
        append("  \"movingTrackSupportPerFrame\": ${intMapJson(movingTrackSupportPerFrame)},\n")
        append("  \"stationaryTrackSupportPerFrame\": ${intMapJson(stationaryTrackSupportPerFrame)},\n")
        append("  \"spatialSectorSupportPerFrame\": ${intMapJson(spatialSectorSupportPerFrame)},\n")
        property("verificationReferenceRetention", verificationReferenceRetention)
        property("verificationContrastRatio", verificationContrastRatio)
        property("verificationWidthGrowth", verificationWidthGrowth)
        property("verificationSmearRate", verificationSmearRate)
        property("verificationIdentityScore", verificationIdentityScore)
        property("verificationZeroModelScore", verificationZeroModelScore)
        property("verificationSelectedModelScore", verificationSelectedModelScore)
        property("sequenceSmoothnessScore", sequenceSmoothnessScore)
        property("sequencePriorAgreementScore", sequencePriorAgreementScore)
        append("  \"registrationRejectedReasons\": ${stringMapJson(registrationRejectedReasons)},\n")
        property("transformContractVersion", transformContractVersion)
        property("transformDirection", transformDirection)
        property("referenceCaptureIndex", referenceCaptureIndex)
        property("analysisWidth", analysisWidth)
        property("analysisHeight", analysisHeight)
        property("fullWidth", fullWidth)
        property("fullHeight", fullHeight)
        property("analysisToFullScaleX", analysisToFullScaleX)
        property("analysisToFullScaleY", analysisToFullScaleY)
        property("velocityCoordinateSpace", velocityCoordinateSpace)
        property("registrationCoordinateSpace", registrationCoordinateSpace)
        property("verificationCoordinateSpace", verificationCoordinateSpace)
        property("samplerCoordinateSpace", samplerCoordinateSpace)
        property("referenceIdentityVerified", referenceIdentityVerified)
        property("inverseTransformVerificationScore", inverseTransformVerificationScore)
        property("canonicalTransformVerificationScore", canonicalTransformVerificationScore)
        property("identityTransformVerificationScore", identityTransformVerificationScore)
        property("doubleTransformVerificationScore", doubleTransformVerificationScore)
        append("  \"perFramePredictedDx\": ${floatMapJson(perFramePredictedDx)},\n")
        append("  \"perFramePredictedDy\": ${floatMapJson(perFramePredictedDy)},\n")
        append("  \"perFrameSelectedDx\": ${floatMapJson(perFrameSelectedDx)},\n")
        append("  \"perFrameSelectedDy\": ${floatMapJson(perFrameSelectedDy)},\n")
        append("  \"perFrameVerificationRetention\": ${floatMapJson(perFrameVerificationRetention)},\n")
        append("  \"perFrameVerificationContrastRatio\": ${floatMapJson(perFrameVerificationContrastRatio)},\n")
        append("  \"perFrameVerificationSmearRate\": ${floatMapJson(perFrameVerificationSmearRate)},\n")
        property("staticArtifactCandidates", staticArtifactCandidates)
        property("staticArtifactMaskRatio", staticArtifactMaskRatio)
        property("memorySchemaVersion", memorySchemaVersion)
        property("runtimeMaxHeapBytes", runtimeMaxHeapBytes)
        property("heapUsedAtRunStartBytes", heapUsedAtRunStartBytes)
        property("safeWorkingBudgetBytes", safeWorkingBudgetBytes)
        property("peakEstimatedResidentBytes", peakEstimatedResidentBytes)
        property("peakObservedHeapBytes", peakObservedHeapBytes)
        property(
            "maximumSimultaneousFullResolutionCandidates",
            maximumSimultaneousFullResolutionCandidates
        )
        property("candidateStorageMode", candidateStorageMode)
        property("referenceCandidateBytesOnDisk", referenceCandidateBytesOnDisk)
        property("cleanStackCandidateBytesOnDisk", cleanStackCandidateBytesOnDisk)
        property("processedCandidateBytesOnDisk", processedCandidateBytesOnDisk)
        append("  \"tileSizePerStage\": ${stringMapJson(tileSizePerStage)},\n")
        append("  \"haloSizePerStage\": ${intMapJson(haloSizePerStage)},\n")
        property("memoryPressureRetries", memoryPressureRetries)
        property("finalBitmapAllocationBytes", finalBitmapAllocationBytes)
        property("lastCompletedStage", lastCompletedStage)
        property("reportPublicationMode", reportPublicationMode)
        property("reportFallbackUsed", reportFallbackUsed)
        nullableProperty("staleRunRecoveryInformation", staleRunRecoveryInformation)
        nullableProperty("processingRunId", processingRunId)
        nullableProperty("artifactSessionId", artifactSessionId)
        property("selectedCandidateType", selectedCandidateType)
        property("fallbackUsed", fallbackUsed)
        nullableProperty("fallbackReason", fallbackReason)
        nullableProperty("internalFallbackLabel", internalFallbackLabel)
        append("  \"warnings\": ${stringArray(warnings)},\n")
        property("outputPngDisplayName", outputPngDisplayName)
        append("  \"stageDurationsMillis\": ${longMapJson(stageDurationsMillis)},\n")
        append("  \"totalDurationMillis\": ${stageDurationsMillis["total"] ?: 0L}\n")
        append("}\n")
    }

    private fun StringBuilder.property(name: String, value: String) {
        append("  \"${escape(name)}\": \"${escape(value)}\",\n")
    }

    private fun StringBuilder.property(name: String, value: Long) {
        append("  \"${escape(name)}\": $value,\n")
    }

    private fun StringBuilder.property(name: String, value: Int) = property(name, value.toLong())

    private fun StringBuilder.property(name: String, value: Float) {
        append("  \"${escape(name)}\": ${number(value)},\n")
    }

    private fun StringBuilder.property(name: String, value: Boolean) {
        append("  \"${escape(name)}\": $value,\n")
    }

    private fun StringBuilder.nullableProperty(name: String, value: String?) {
        append("  \"${escape(name)}\": ")
        append(if (value == null) "null" else "\"${escape(value)}\"")
        append(",\n")
    }

    private fun <T> StringBuilder.appendArray(values: List<T>, json: (T) -> String) {
        append(values.joinToString(prefix = "[", postfix = "]") { json(it) })
    }

    companion object {
        const val SCHEMA_VERSION = "astrophoto.jpeg.processing/1"

        private fun registrationJson(value: FrameRegistrationReport): String = """{
            "frameName":"${escape(value.frameName)}","accepted":${value.accepted},
            "rejectionReason":${nullableString(value.rejectionReason)},"detectedStars":${value.detectedStars},
            "matchedStars":${value.matchedStars},"inlierStars":${value.inlierStars},
            "dx":${number(value.dx)},"dy":${number(value.dy)},
            "rotationRadians":${number(value.rotationRadians)},"scale":${number(value.scale)},
            "residualError":${number(value.residualError)},"confidence":${number(value.confidence)},
            "registrationModel":"${escape(value.registrationModel)}","scaleFixed":${value.scaleFixed},
            "rotationAllowed":${value.rotationAllowed},
            "rotationRejectionReason":${nullableString(value.rotationRejectionReason)},
            "occupiedDistributionCells":${value.occupiedDistributionCells},
            "horizontalDistributionSpan":${number(value.horizontalDistributionSpan)},
            "verticalDistributionSpan":${number(value.verticalDistributionSpan)},
            "spatialDistributionScore":${number(value.spatialDistributionScore)},
            "rawDx":${number(value.rawDx)},"rawDy":${number(value.rawDy)},
            "rawRotationRadians":${number(value.rawRotationRadians)},
            "transformSequenceScore":${number(value.transformSequenceScore)},
            "transformSequenceDeviation":${number(value.transformSequenceDeviation)},
            "neighborTransformDelta":${number(value.neighborTransformDelta)},
            "transformRetryUsed":${value.transformRetryUsed}
        }""".trimIndent().replace("\n", "")

        private fun weightJson(value: FrameWeightReport): String = """{
            "frameName":"${escape(value.frameName)}","registrationWeight":${number(value.registrationWeight)},
            "sharpnessWeight":${number(value.sharpnessWeight)},"trailWeight":${number(value.trailWeight)},
            "noiseWeight":${number(value.noiseWeight)},"exposureWeight":${number(value.exposureWeight)},
            "normalizedWeight":${number(value.normalizedWeight)}
        }""".trimIndent().replace("\n", "")

        private fun integrationJson(value: IntegrationReport): String = """{
            "mode":"${escape(value.mode)}","robustMode":${value.robustMode},
            "inputWidth":${value.inputWidth},"inputHeight":${value.inputHeight},
            "outputWidth":${value.outputWidth},"outputHeight":${value.outputHeight},
            "tileWidth":${value.tileWidth},"tileHeight":${value.tileHeight},
            "resolutionChanged":${value.resolutionChanged},"validCoveragePercent":${number(value.validCoveragePercent)},
            "estimatedWorkingMemoryBytes":${value.estimatedWorkingMemoryBytes},
            "outputAllocationBytes":${value.outputAllocationBytes},"diskCacheBytes":${value.diskCacheBytes},
            "robustModeReason":"${escape(value.robustModeReason)}"
        }""".trimIndent().replace("\n", "")

        private fun parametersJson(value: AdaptiveProcessingParameters): String = """{
            "gradientStrength":${number(value.gradientStrength)},
            "neutralizationStrength":${number(value.neutralizationStrength)},
            "stretchBlend":${number(value.stretchBlend)},"asinhStrength":${number(value.asinhStrength)},
            "highlightProtection":${number(value.highlightProtection)},
            "chromaNoiseStrength":${number(value.chromaNoiseStrength)},
            "starContrastStrength":${number(value.starContrastStrength)},
            "maximumSkyMedianFactor":${number(value.maximumSkyMedianFactor)},
            "maximumChannelClippingPercent":${number(value.maximumChannelClippingPercent)},
            "minimumBlackWhiteSeparation":${number(value.minimumBlackWhiteSeparation)},
            "maximumGradientCorrection":${number(value.maximumGradientCorrection)},
            "maximumNeutralizationCorrection":${number(value.maximumNeutralizationCorrection)},
            "maximumStarDetailGain":${number(value.maximumStarDetailGain)},
            "maximumChromaRadius":${value.maximumChromaRadius},
            "maximumStarWidthGrowth":${number(value.maximumStarWidthGrowth)}
        }""".trimIndent().replace("\n", "")

        private fun metricsJson(value: ResultQualityMetrics): String = """{
            "width":${value.width},"height":${value.height},"aspectRatio":${value.aspectRatio},
            "retainedValidArea":${number(value.retainedValidAreaRatio)},
            "reliableStarCount":${value.reliableStarCount},
            "medianStarLocalContrast":${number(value.medianStarLocalContrast)},
            "medianStarWidth":${number(value.medianStarWidth)},
            "medianStarEllipticity":${number(value.medianStarEllipticity)},
            "brightStarClipping":${number(value.brightStarClippingPercent)},
            "suspiciousPointCount":${value.suspiciousPointCount},
            "skyMedian":${number(value.skyMedian)},"skyMad":${number(value.skyMad)},
            "skyLowPercentile":${number(value.skyLowPercentile)},
            "skyHighPercentile":${number(value.skyHighPercentile)},
            "channelMedian":${rgbJson(value.channelMedian)},
            "channelClipping":${rgbJson(value.channelClippingPercent)},
            "chromaNoiseEstimate":${number(value.chromaNoiseEstimate)},
            "banding":{"horizontal":${number(value.banding.horizontalScore)},
                "vertical":${number(value.banding.verticalScore)},
                "combined":${number(value.banding.combinedScore)}},
            "gradientResidual":${number(value.gradientResidual)},
            "foregroundSharpness":${number(value.foregroundSharpness)},
            "foregroundEdgeDifference":${number(value.foregroundEdgeDifference)},
            "foregroundMeanPixelDifference":${number(value.foregroundMeanPixelDifference)},
            "foregroundMaximumPixelDifference":${value.foregroundMaximumPixelDifference},
            "invalidBorderRatio":${number(value.invalidBorderRatio)},
            "blackBorderRatio":${number(value.blackBorderRatio)},
            "processingConfidence":${number(value.processingConfidence)}
        }""".trimIndent().replace("\n", "")

        private fun decisionJson(value: QualityGateDecision): String = """{
            "accepted":${value.accepted},"score":${number(value.score)},
            "hardFailureReasons":${stringArray(value.hardFailureReasons)},
            "warningReasons":${stringArray(value.warningReasons)}
        }""".trimIndent().replace("\n", "")

        private fun rgbJson(value: LinearRgb): String =
            "{\"red\":${number(value.red)},\"green\":${number(value.green)},\"blue\":${number(value.blue)}}"

        private fun longMapJson(values: Map<String, Long>): String = values.entries.joinToString(
            prefix = "{", postfix = "}"
        ) { "\"${escape(it.key)}\":${it.value.coerceAtLeast(0L)}" }

        private fun intMapJson(values: Map<String, Int>): String = values.entries.joinToString(
            prefix = "{", postfix = "}"
        ) { "\"${escape(it.key)}\":${it.value.coerceAtLeast(0)}" }

        private fun stringMapJson(values: Map<String, String>): String = values.entries.joinToString(
            prefix = "{", postfix = "}"
        ) { "\"${escape(it.key)}\":\"${escape(it.value)}\"" }

        private fun floatMapJson(values: Map<String, Float>): String = values.entries.joinToString(
            prefix = "{", postfix = "}"
        ) { "\"${escape(it.key)}\":${number(it.value)}" }

        private fun stringArray(values: List<String>): String = values.joinToString(
            prefix = "[", postfix = "]"
        ) { "\"${escape(it)}\"" }

        private fun nullableString(value: String?): String =
            if (value == null) "null" else "\"${escape(value)}\""

        private fun number(value: Float): String = if (value.isFinite()) value.toString() else "null"

        private fun escape(value: String): String = buildString(value.length + 8) {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u${character.code.toString(16).padStart(4, '0')}")
                    } else {
                        append(character)
                    }
                }
            }
        }
    }
}
