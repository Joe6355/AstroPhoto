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
