package com.example.astrophoto.processing.jpeg.v2.diagnostics

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

data class ProcessingRunRecord(
    val runId: String,
    val sessionFolder: String,
    val preset: String,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val lastCompletedStage: String,
    val completed: Boolean,
    val recovered: Boolean,
    val runtimeMaxHeapBytes: Long,
    val heapUsedAtStartBytes: Long,
    val safeWorkingBudgetBytes: Long,
    val failureKind: String?,
    val artifactSessionId: String? = null,
    val outputFileName: String? = null,
    val outputContentUri: String? = null,
    val outputFilePath: String? = null,
    val reportFileName: String? = null,
    val reportContentUri: String? = null,
    val reportFilePath: String? = null
)

class ProcessingRunJournal private constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))
    constructor(filesRoot: File, forTesting: Boolean) : this(File(filesRoot, DIRECTORY_NAME)) {
        @Suppress("UNUSED_VARIABLE") val ignored = forTesting
    }

    init {
        require(directory.isDirectory || directory.mkdirs()) { "Unable to create JPEG processing journal directory" }
    }

    fun start(
        sessionFolder: String,
        preset: String,
        runtimeMaxHeapBytes: Long,
        heapUsedAtStartBytes: Long,
        safeWorkingBudgetBytes: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): ProcessingRunRecord {
        val record = ProcessingRunRecord(
            runId = UUID.randomUUID().toString().replace("-", ""),
            sessionFolder = sessionFolder,
            preset = preset,
            startedAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            lastCompletedStage = "journal_created",
            completed = false,
            recovered = false,
            runtimeMaxHeapBytes = runtimeMaxHeapBytes,
            heapUsedAtStartBytes = heapUsedAtStartBytes,
            safeWorkingBudgetBytes = safeWorkingBudgetBytes,
            failureKind = null
        )
        writeAtomic(record)
        return record
    }

    fun update(runId: String, completedStage: String, nowMillis: Long = System.currentTimeMillis()): ProcessingRunRecord {
        require(completedStage.isNotBlank())
        val updated = checkNotNull(read(runId)) { "Processing journal was not found" }.copy(
            updatedAtMillis = nowMillis,
            lastCompletedStage = completedStage
        )
        writeAtomic(updated)
        return updated
    }

    fun updatePublishedArtifacts(
        runId: String,
        artifactSessionId: String,
        outputFileName: String,
        outputContentUri: String?,
        outputFilePath: String?,
        reportFileName: String,
        reportContentUri: String?,
        reportFilePath: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): ProcessingRunRecord {
        require(artifactSessionId.isNotBlank() && outputFileName.isNotBlank() && reportFileName.isNotBlank())
        val updated = checkNotNull(read(runId)) { "Processing journal was not found" }.copy(
            updatedAtMillis = nowMillis,
            lastCompletedStage = "report_published",
            artifactSessionId = artifactSessionId,
            outputFileName = outputFileName,
            outputContentUri = outputContentUri,
            outputFilePath = outputFilePath,
            reportFileName = reportFileName,
            reportContentUri = reportContentUri,
            reportFilePath = reportFilePath
        )
        writeAtomic(updated)
        return updated
    }

    fun recordFailure(
        runId: String,
        completedStage: String,
        failureKind: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ProcessingRunRecord {
        val updated = checkNotNull(read(runId)).copy(
            updatedAtMillis = nowMillis,
            lastCompletedStage = completedStage,
            completed = true,
            failureKind = failureKind.take(80)
        )
        writeAtomic(updated)
        return updated
    }

    fun markCompleted(
        runId: String,
        completedStage: String = "completed",
        nowMillis: Long = System.currentTimeMillis()
    ): ProcessingRunRecord {
        val updated = checkNotNull(read(runId)).copy(
            updatedAtMillis = nowMillis,
            lastCompletedStage = completedStage,
            completed = true
        )
        writeAtomic(updated)
        pruneCompleted()
        return updated
    }

    fun markRecovered(runId: String, nowMillis: Long = System.currentTimeMillis()) {
        val existing = read(runId) ?: return
        writeAtomic(existing.copy(updatedAtMillis = nowMillis, recovered = true))
    }

    fun latestUnfinished(): ProcessingRunRecord? = directory.listFiles { file ->
        file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
    }.orEmpty().mapNotNull(::readFile).filter { !it.completed && !it.recovered }
        .maxByOrNull { it.updatedAtMillis }

    fun read(runId: String): ProcessingRunRecord? {
        require(RUN_ID.matches(runId))
        return readFile(file(runId))
    }

    private fun writeAtomic(record: ProcessingRunRecord) {
        val target = file(record.runId)
        val temporary = File(directory, "${target.name}.tmp")
        val properties = Properties().apply {
            setProperty("schema", SCHEMA)
            setProperty("runId", record.runId)
            setProperty("sessionFolder", record.sessionFolder)
            setProperty("preset", record.preset)
            setProperty("startedAtMillis", record.startedAtMillis.toString())
            setProperty("updatedAtMillis", record.updatedAtMillis.toString())
            setProperty("lastCompletedStage", record.lastCompletedStage)
            setProperty("completed", record.completed.toString())
            setProperty("recovered", record.recovered.toString())
            setProperty("runtimeMaxHeapBytes", record.runtimeMaxHeapBytes.toString())
            setProperty("heapUsedAtStartBytes", record.heapUsedAtStartBytes.toString())
            setProperty("safeWorkingBudgetBytes", record.safeWorkingBudgetBytes.toString())
            record.failureKind?.let { setProperty("failureKind", it) }
            record.artifactSessionId?.let { setProperty("artifactSessionId", it) }
            record.outputFileName?.let { setProperty("outputFileName", it) }
            record.outputContentUri?.let { setProperty("outputContentUri", it) }
            record.outputFilePath?.let { setProperty("outputFilePath", it) }
            record.reportFileName?.let { setProperty("reportFileName", it) }
            record.reportContentUri?.let { setProperty("reportContentUri", it) }
            record.reportFilePath?.let { setProperty("reportFilePath", it) }
        }
        FileOutputStream(temporary, false).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
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
    }

    private fun readFile(file: File): ProcessingRunRecord? = runCatching {
        if (!file.isFile) return@runCatching null
        val properties = Properties().apply { file.inputStream().use(::load) }
        if (properties.getProperty("schema") != SCHEMA) return@runCatching null
        ProcessingRunRecord(
            runId = properties.required("runId"),
            sessionFolder = properties.required("sessionFolder"),
            preset = properties.required("preset"),
            startedAtMillis = properties.required("startedAtMillis").toLong(),
            updatedAtMillis = properties.required("updatedAtMillis").toLong(),
            lastCompletedStage = properties.required("lastCompletedStage"),
            completed = properties.required("completed").toBooleanStrict(),
            recovered = properties.required("recovered").toBooleanStrict(),
            runtimeMaxHeapBytes = properties.required("runtimeMaxHeapBytes").toLong(),
            heapUsedAtStartBytes = properties.required("heapUsedAtStartBytes").toLong(),
            safeWorkingBudgetBytes = properties.required("safeWorkingBudgetBytes").toLong(),
            failureKind = properties.getProperty("failureKind"),
            artifactSessionId = properties.getProperty("artifactSessionId"),
            outputFileName = properties.getProperty("outputFileName"),
            outputContentUri = properties.getProperty("outputContentUri"),
            outputFilePath = properties.getProperty("outputFilePath"),
            reportFileName = properties.getProperty("reportFileName"),
            reportContentUri = properties.getProperty("reportContentUri"),
            reportFilePath = properties.getProperty("reportFilePath")
        )
    }.getOrNull()

    private fun Properties.required(name: String): String =
        getProperty(name)?.takeIf { it.isNotBlank() } ?: error("Missing journal field $name")

    private fun file(runId: String) = File(directory, "$FILE_PREFIX$runId$FILE_SUFFIX")

    private fun pruneCompleted() {
        val completed = directory.listFiles().orEmpty().mapNotNull { file ->
            readFile(file)?.takeIf { it.completed }?.let { it to file }
        }.sortedByDescending { it.first.updatedAtMillis }
        completed.drop(MAX_COMPLETED_RECORDS).forEach { (_, file) -> file.delete() }
    }

    companion object {
        const val SCHEMA = "astrophoto.jpeg.run-journal/1"
        private const val DIRECTORY_NAME = "jpeg-processing-journal"
        private const val FILE_PREFIX = "run-"
        private const val FILE_SUFFIX = ".properties"
        private const val MAX_COMPLETED_RECORDS = 8
        private val RUN_ID = Regex("[0-9a-f]{32}")
    }
}
