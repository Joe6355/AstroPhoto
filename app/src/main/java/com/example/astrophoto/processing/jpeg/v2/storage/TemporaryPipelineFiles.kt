package com.example.astrophoto.processing.jpeg.v2.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class TemporaryPipelineFiles private constructor(
    private val cacheRoot: File,
    val directory: File,
    val runId: String
) : AutoCloseable {
    private var closed = false

    fun file(name: String): File {
        require(SAFE_FILE_NAME.matches(name)) { "Unsafe temporary file name" }
        return File(directory, name)
    }

    fun atomicTextFile(name: String, text: String): File {
        check(!closed)
        val target = file(name)
        val temporary = file("$name.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    fun deleteFile(handle: FileBackedImage) {
        require(handle.file.parentFile?.canonicalFile == directory.canonicalFile)
        handle.file.delete()
    }

    fun deleteFile(handle: FileBackedFloatPlane) {
        require(handle.file.parentFile?.canonicalFile == directory.canonicalFile)
        handle.file.delete()
    }

    override fun close() {
        if (closed) return
        closed = true
        deleteValidatedRun(cacheRoot, directory)
    }

    companion object {
        const val DIRECTORY_PREFIX = "jpeg-stage7-"
        const val MARKER_FILE_NAME = ".astrophoto-stage7-run"
        private const val MARKER_VERSION = "astrophoto.jpeg.stage7.temp/1"
        private const val STALE_AGE_MILLIS = 6L * 60L * 60L * 1000L
        private val RUN_NAME = Regex("${DIRECTORY_PREFIX}[0-9a-f]{32}")
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]+")

        fun create(cacheRoot: File): TemporaryPipelineFiles {
            require(cacheRoot.isDirectory || cacheRoot.mkdirs())
            val runId = UUID.randomUUID().toString().replace("-", "")
            val directory = File(cacheRoot, "$DIRECTORY_PREFIX$runId")
            require(directory.mkdir()) { "Unable to create Stage 7 temporary directory" }
            val marker = File(directory, MARKER_FILE_NAME)
            marker.writeText(markerText(directory.name, runId), Charsets.UTF_8)
            return TemporaryPipelineFiles(cacheRoot.canonicalFile, directory.canonicalFile, runId)
        }

        fun cleanupStale(cacheRoot: File, nowMillis: Long = System.currentTimeMillis()): Int {
            if (!cacheRoot.isDirectory) return 0
            var deleted = 0
            cacheRoot.listFiles().orEmpty().forEach { candidate ->
                if (!candidate.isDirectory || !RUN_NAME.matches(candidate.name)) return@forEach
                if (nowMillis - candidate.lastModified() < STALE_AGE_MILLIS) return@forEach
                if (deleteValidatedRun(cacheRoot, candidate)) deleted++
            }
            return deleted
        }

        internal fun isValidatedRun(cacheRoot: File, directory: File): Boolean {
            val canonicalRoot = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return false
            val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return false
            if (canonicalDirectory.parentFile != canonicalRoot || !RUN_NAME.matches(canonicalDirectory.name)) return false
            val runId = canonicalDirectory.name.removePrefix(DIRECTORY_PREFIX)
            val marker = File(canonicalDirectory, MARKER_FILE_NAME)
            return marker.isFile && runCatching {
                marker.readText(Charsets.UTF_8) == markerText(canonicalDirectory.name, runId)
            }.getOrDefault(false)
        }

        private fun deleteValidatedRun(cacheRoot: File, directory: File): Boolean {
            if (!isValidatedRun(cacheRoot, directory)) return false
            directory.walkBottomUp().forEach { entry ->
                if (entry != directory && entry.parentFile?.canonicalFile != directory.canonicalFile) return@forEach
                entry.delete()
            }
            return !directory.exists()
        }

        private fun markerText(directoryName: String, runId: String): String =
            "$MARKER_VERSION\nrunId=$runId\ndirectory=$directoryName\n"
    }
}
