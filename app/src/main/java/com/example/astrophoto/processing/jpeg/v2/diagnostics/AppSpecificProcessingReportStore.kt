package com.example.astrophoto.processing.jpeg.v2.diagnostics

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class AppSpecificProcessingReport(
    val sessionFolder: String,
    val imageFileName: String,
    val reportFileName: String,
    val file: File
)

class AppSpecificProcessingReportStore private constructor(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, ROOT_DIRECTORY))
    constructor(filesRoot: File, forTesting: Boolean) : this(File(filesRoot, ROOT_DIRECTORY)) {
        @Suppress("UNUSED_VARIABLE") val ignored = forTesting
    }

    init {
        require(root.isDirectory || root.mkdirs()) { "Unable to create fallback report storage" }
    }

    fun write(sessionFolder: String, imageFileName: String, json: String): AppSpecificProcessingReport {
        require(sessionFolder.isNotBlank() && imageFileName.isNotBlank() && json.isNotBlank())
        val directory = sessionDirectory(sessionFolder, create = true)
        val reportName = processingReportFileName(imageFileName)
        val target = File(directory, safeLeaf(reportName))
        val temporary = File(directory, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        moveAtomic(temporary, target)
        File(directory, "${target.name}.image").writeText(imageFileName, Charsets.UTF_8)
        return AppSpecificProcessingReport(sessionFolder, imageFileName, reportName, target)
    }

    fun listForSession(sessionFolder: String): List<AppSpecificProcessingReport> {
        val directory = sessionDirectory(sessionFolder, create = false)
        if (!validatedSessionDirectory(directory, sessionFolder)) return emptyList()
        return directory.listFiles { file ->
            file.isFile && file.name.endsWith(".processing.json", ignoreCase = true)
        }.orEmpty().sortedBy { it.name.lowercase() }.mapNotNull { file ->
            val image = File(directory, "${file.name}.image").takeIf(File::isFile)
                ?.readText(Charsets.UTF_8)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            AppSpecificProcessingReport(sessionFolder, image, processingReportFileName(image), file)
        }
    }

    fun delete(sessionFolder: String, imageFileName: String): Boolean {
        val directory = sessionDirectory(sessionFolder, create = false)
        if (!validatedSessionDirectory(directory, sessionFolder)) return false
        val target = File(directory, safeLeaf(processingReportFileName(imageFileName)))
        require(target.canonicalFile.parentFile == directory.canonicalFile)
        File(directory, "${target.name}.image").delete()
        val deleted = !target.exists() || target.delete()
        if (directory.listFiles().orEmpty().none { it.name != MARKER_FILE }) directory.deleteRecursively()
        return deleted
    }

    private fun sessionDirectory(sessionFolder: String, create: Boolean): File {
        val directory = File(root, "session-${sha256(sessionFolder).take(32)}")
        if (create) {
            require(directory.isDirectory || directory.mkdirs())
            val marker = File(directory, MARKER_FILE)
            if (!marker.exists()) marker.writeText("$SCHEMA\n$sessionFolder\n", Charsets.UTF_8)
            require(validatedSessionDirectory(directory, sessionFolder))
        }
        return directory
    }

    private fun validatedSessionDirectory(directory: File, sessionFolder: String): Boolean = runCatching {
        directory.isDirectory && directory.canonicalFile.parentFile == root.canonicalFile &&
            File(directory, MARKER_FILE).readText(Charsets.UTF_8) == "$SCHEMA\n$sessionFolder\n"
    }.getOrDefault(false)

    private fun safeLeaf(value: String): String = value.map { character ->
        if (character.isLetterOrDigit() || character in "._-") character else '_'
    }.joinToString("").take(180).ifBlank { "report.processing.json" }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun moveAtomic(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val SCHEMA = "astrophoto.jpeg.fallback-report/1"
        private const val ROOT_DIRECTORY = "jpeg-processing-reports"
        private const val MARKER_FILE = ".session-index"
    }
}
