package com.example.astrophoto.processing.jpeg.v2.diagnostics

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.astrophoto.SessionSummary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

data class SavedProcessingReport(
    val fileName: String,
    val displayPath: String,
    val contentUri: String?,
    val filePath: String?,
    val writeDurationMillis: Long,
    val publicationMode: String = "PUBLIC_MEDIASTORE",
    val fallbackUsed: Boolean = false,
    val publicationFailureReason: String? = null
)

sealed interface ReportWriteOutcome<out T> {
    data class Written<T>(val value: T) : ReportWriteOutcome<T>
    data class Failed(val reason: String) : ReportWriteOutcome<Nothing>
}

internal inline fun <T> safeReportWrite(block: () -> T): ReportWriteOutcome<T> =
    try {
        ReportWriteOutcome.Written(block())
    } catch (error: Exception) {
        ReportWriteOutcome.Failed(error.message ?: error::class.java.simpleName)
    }

internal fun processingReportFileName(imageFileName: String): String =
    "${imageFileName.substringBeforeLast('.', imageFileName)}.processing.json"

internal fun processingReportMatchesImage(reportFileName: String, imageFileName: String): Boolean =
    reportFileName.equals(processingReportFileName(imageFileName), ignoreCase = true)

internal class ProcessingReportWriter(private val context: Context) {
    suspend fun write(
        session: SessionSummary,
        imageFileName: String,
        json: String
    ): SavedProcessingReport = try {
        writePrimary(session, imageFileName, json)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val started = System.nanoTime()
        val fallback = AppSpecificProcessingReportStore(context).write(
            session.folderName,
            imageFileName,
            json
        )
        SavedProcessingReport(
            fileName = fallback.reportFileName,
            displayPath = "Internal reports/${fallback.reportFileName}",
            contentUri = null,
            filePath = fallback.file.absolutePath,
            writeDurationMillis = (System.nanoTime() - started) / 1_000_000L,
            publicationMode = "APP_SPECIFIC_FILES",
            fallbackUsed = true,
            publicationFailureReason = error.message ?: error::class.java.simpleName
        )
    }

    private suspend fun writePrimary(
        session: SessionSummary,
        imageFileName: String,
        json: String
    ): SavedProcessingReport {
        val started = System.nanoTime()
        val reportName = processingReportFileName(imageFileName)
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "Processing report is empty" }
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri("external")
            val relativePath = processedReportRelativePath(session.folderName)
            require(!mediaStoreNameExists(relativePath, reportName)) {
                "Processing report already exists"
            }
            val uri = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, reportName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
            ) ?: error("Unable to create processing report")
            try {
                resolver.openOutputStream(uri, "w")?.use { output -> output.write(bytes) }
                    ?: error("Unable to write processing report")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                return SavedProcessingReport(
                    fileName = reportName,
                    displayPath = "$relativePath$reportName",
                    contentUri = uri.toString(),
                    filePath = null,
                    writeDurationMillis = (System.nanoTime() - started) / 1_000_000L,
                    publicationMode = "MEDIASTORE_FILES"
                )
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, "AstroPhoto/${session.folderName}/Processed")
        require(directory.exists() || directory.mkdirs()) { "Unable to create Processed directory" }
        val target = File(directory, reportName)
        val temporary = File(directory, "$reportName.tmp")
        require(!target.exists() && !temporary.exists()) { "Processing report already exists" }
        var moved = false
        try {
            require(temporary.createNewFile()) { "Unable to create temporary processing report" }
            FileOutputStream(temporary, false).use { it.write(bytes) }
            Files.move(temporary.toPath(), target.toPath())
            moved = true
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf("application/json"),
                null
            )
            return SavedProcessingReport(
                fileName = reportName,
                displayPath = target.absolutePath,
                contentUri = null,
                filePath = target.absolutePath,
                writeDurationMillis = (System.nanoTime() - started) / 1_000_000L,
                publicationMode = "LEGACY_PUBLIC_FILE"
            )
        } catch (error: Throwable) {
            temporary.delete()
            if (moved) target.delete()
            throw error
        }
    }

    private fun mediaStoreNameExists(relativePath: String, fileName: String): Boolean =
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, fileName),
            null
        )?.use { it.moveToFirst() } == true

    companion object {
        fun processedReportRelativePath(sessionFolderName: String): String =
            "Pictures/AstroPhoto/$sessionFolderName/Processed/"
    }
}
