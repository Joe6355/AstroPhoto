package com.example.astrophoto.processing.jpeg.v2.diagnostics

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class PreviousRunClassification {
    INTERRUPTED_PROCESSING,
    PROCESSING_COMPLETED_UI_FAILURE
}

internal fun classifyPreviousRun(
    record: ProcessingRunRecord,
    publishedArtifactsValid: Boolean
): PreviousRunClassification = if (
    record.lastCompletedStage == "report_published" && publishedArtifactsValid
) {
    PreviousRunClassification.PROCESSING_COMPLETED_UI_FAILURE
} else {
    PreviousRunClassification.INTERRUPTED_PROCESSING
}

internal fun artifactSessionId(sessionFolder: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(sessionFolder.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun publishedReportIdentityMatches(
    reportJson: String,
    record: ProcessingRunRecord
): Boolean {
    fun value(name: String): String? = Regex(
        "\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\""
    ).find(reportJson)?.groupValues?.get(1)
    return value("processingRunId") == record.runId &&
        value("artifactSessionId") == record.artifactSessionId &&
        value("outputPngDisplayName") == record.outputFileName
}

internal class PublishedArtifactVerifier(
    private val openReference: (contentUri: String?, filePath: String?) -> InputStream?
) {
    constructor(context: Context) : this({ contentUri, filePath ->
        when {
            !contentUri.isNullOrBlank() -> context.contentResolver.openInputStream(Uri.parse(contentUri))
            !filePath.isNullOrBlank() -> File(filePath).takeIf(File::isFile)?.inputStream()
            else -> null
        }
    })

    fun verify(record: ProcessingRunRecord): Boolean = runCatching {
        val outputName = requireNotNull(record.outputFileName)
        val reportName = requireNotNull(record.reportFileName)
        val sessionId = requireNotNull(record.artifactSessionId)
        require(sessionId == artifactSessionId(record.sessionFolder))
        require(processingReportMatchesImage(reportName, outputName))
        require(validPng(record.outputContentUri, record.outputFilePath))
        publishedReportIdentityMatches(
            readText(record.reportContentUri, record.reportFilePath),
            record
        )
    }.getOrDefault(false)

    private fun validPng(contentUri: String?, filePath: String?): Boolean =
        openReference(contentUri, filePath)?.use { input ->
            val signature = ByteArray(PNG_SIGNATURE.size)
            input.read(signature) == signature.size && signature.contentEquals(PNG_SIGNATURE)
        } ?: false

    private fun readText(contentUri: String?, filePath: String?): String =
        openReference(contentUri, filePath)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= MAX_REPORT_CHARACTERS)
                result.append(buffer, 0, count)
            }
            require(result.isNotEmpty())
            result.toString()
        } ?: error("Published report is unavailable")

    companion object {
        private const val MAX_REPORT_CHARACTERS = 2 * 1024 * 1024
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
