package com.joe6355.astrophoto

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.AppSpecificProcessingReportStore
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.artifactSessionId
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.processingReportFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** Existing private/public sidecars only. No reports, photographs or session metadata are changed. */
internal class ProcessedResultReportReader(private val context: Context) {
    suspend fun load(sessionFolder: String, imageFileName: String): ResultProcessingSummary? = withContext(Dispatchers.IO) {
        if (!safeLeaf(sessionFolder) || !safeLeaf(imageFileName)) return@withContext null
        val privateReport = runCatching {
            AppSpecificProcessingReportStore(context).findForImage(sessionFolder, imageFileName)?.file
        }.getOrNull()
        for (privateFirst in listOf(true, false)) {
            coroutineContext.ensureActive()
            val summary = try {
                val json = if (privateFirst) privateReport?.inputStream()?.use(::boundedReportText)
                    else readPublic(sessionFolder, imageFileName)
                json?.let { parseResultProcessingSummary(it, imageFileName, artifactSessionId(sessionFolder)) }
            } catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { null }
            if (summary != null) return@withContext summary
        }
        null
    }

    private fun readPublic(folder: String, image: String): String? {
        val report = processingReportFileName(image)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Files.getContentUri("external")
            return context.contentResolver.query(collection, arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND ${MediaStore.Files.FileColumns.IS_PENDING}=0",
                arrayOf("Pictures/AstroPhoto/$folder/Processed/", report), null)?.use { cursor ->
                if (cursor.count != 1 || !cursor.moveToFirst()) return@use null
                context.contentResolver.openInputStream(ContentUris.withAppendedId(collection, cursor.getLong(0)))
                    ?.use(::boundedReportText)
            }
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "AstroPhoto/$folder/Processed").canonicalFile
        val file = File(directory, report).canonicalFile
        return file.takeIf { it.parentFile == directory && it.isFile }?.inputStream()?.use(::boundedReportText)
    }

    private fun safeLeaf(value: String) = value.isNotBlank() && value !in listOf(".", "..") &&
        value.none { it == '/' || it == '\\' || it == '\u0000' }
}

internal fun boundedReportText(input: InputStream): String = input.bufferedReader(Charsets.UTF_8).use { reader ->
    val text = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        require(text.length + count <= 2 * 1024 * 1024) { "Processing report is too large" }
        text.append(buffer, 0, count)
    }
    text.toString()
}

internal fun parseResultProcessingSummary(
    text: String, expectedImageFileName: String, expectedSessionId: String
): ResultProcessingSummary? = runCatching {
    require(text.length <= 2 * 1024 * 1024)
    val json = JSONObject(text)
    require(json.optString("schemaVersion").matches(Regex("astrophoto\\.jpeg\\.processing/[123]")))
    require(json.opt("outputPngDisplayName") == expectedImageFileName)
    val sessionId = json.opt("artifactSessionId")
    require(sessionId == null || sessionId == JSONObject.NULL || sessionId == expectedSessionId)
    fun count(key: String): Int {
        val number = json.get(key) as? Number ?: error("Invalid frame count")
        val value = number.toDouble()
        require(value.isFinite() && value in 0.0..1_000_000.0 && value == value.toInt().toDouble())
        return value.toInt()
    }
    val input = count("inputFrameCount")
    val accepted = count("acceptedFrameCount")
    val eligible = if (json.has("eligibleFrameCount")) count("eligibleFrameCount") else input
    require(input > 0 && accepted in 0..eligible && eligible <= input)
    if (json.has("rejectedFrameCount")) require(count("rejectedFrameCount") == input - accepted)
    val reference = (json.opt("selectedReference") as? String)?.takeIf {
        it.isNotBlank() && it.length <= 512 && it !in listOf(".", "..") &&
            it.none { c -> c == '/' || c == '\\' || c == '\u0000' }
    } ?: error("Missing reference")
    fun optionalString(key: String) = (json.opt(key) as? String)?.takeIf(String::isNotBlank)
    val registrations = json.optJSONArray("registrations")
    val reasons = buildList {
        if (registrations != null) for (index in 0 until registrations.length()) {
            val record = registrations.optJSONObject(index) ?: continue
            if (record.opt("accepted") == false) add((record.opt("rejectionReason") as? String)?.take(300))
        }
    }
    val enhanced = json.optJSONArray("enhancedRejectionReasons")
    ResultProcessingSummary(input, accepted, reference, optionalString("selectedCandidateType").orEmpty(),
        optionalString("enhancedValidationStatus") == "SAVED" && json.opt("enhancedOutputFileName") == expectedImageFileName,
        json.opt("fallbackUsed") == true, optionalString("fallbackReason"),
        frameExclusions(input, eligible, accepted, reasons), optionalString("enhancedValidationStatus").orEmpty(),
        buildList {
            if (enhanced != null) for (index in 0 until minOf(enhanced.length(), 64)) {
                (enhanced.opt(index) as? String)?.let { add(it.take(300)) }
            }
        })
}.getOrNull()
