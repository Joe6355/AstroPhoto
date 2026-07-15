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
    val confidence: Float
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
    val diskCacheBytes: Long
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
    val stageDurationsMillis: Map<String, Long>
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
            "residualError":${number(value.residualError)},"confidence":${number(value.confidence)}
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
            "outputAllocationBytes":${value.outputAllocationBytes},"diskCacheBytes":${value.diskCacheBytes}
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
