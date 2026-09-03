package com.joe6355.astrophoto.processing.jpeg.v2.integration

import android.content.Context
import com.joe6355.astrophoto.AstroProcessingProfile
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.IntegrationDiagnostics
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class IntegrationCheckpointFrameSignature(
    val frameId: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val registration: RegistrationResult,
    val normalizedWeight: Float
)

internal data class ProfileIntegrationRun(
    val diagnostics: IntegrationDiagnostics,
    val stackedSky: FileBackedImage,
    val validCoverage: FileBackedFloatPlane,
    val sensorDefectAffectedOutput: FileBackedFloatPlane?,
    val sensorDefectFiltering: SensorDefectFilteringReport,
    val totalDurationMillis: Long
)

/** App-private, all-or-nothing checkpoint for the expensive full-resolution integration. */
internal class ProfileIntegrationCheckpointStore private constructor(
    private val root: File,
    private val directory: File,
    private val fingerprint: String
) {
    fun readInto(temporaryFiles: TemporaryPipelineFiles): ProfileIntegrationRun? {
        val restoredFiles = mutableListOf<File>()
        return runCatching {
            val manifestFile = File(directory, MANIFEST_NAME)
            require(manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES)
            val manifest = BufferedInputStream(manifestFile.inputStream()).use { input ->
                ObjectInputStream(input).use { it.readObject() as Manifest }
            }
            require(manifest.magic == MAGIC && manifest.version == VERSION)
            require(manifest.fingerprint == fingerprint)

            fun restore(name: String, targetName: String, expectedBytes: Long): File {
                val source = File(directory, name)
                require(source.isFile && source.length() == expectedBytes)
                val target = temporaryFiles.file(targetName)
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                require(target.length() == expectedBytes)
                restoredFiles += target
                return target
            }

            val stacked = FileBackedImage(
                restore(STACKED_NAME, "checkpoint-integrated-sky.argb", manifest.stackedBytes),
                manifest.width,
                manifest.height,
                rowStrideBytes = manifest.stackedRowStrideBytes
            ).validate()
            val coverage = FileBackedFloatPlane(
                restore(COVERAGE_NAME, "checkpoint-valid-coverage.f32", manifest.coverageBytes),
                manifest.width,
                manifest.height,
                rowStrideBytes = manifest.coverageRowStrideBytes
            ).validate()
            val affected = if (manifest.affectedBytes != null) {
                FileBackedFloatPlane(
                    restore(
                        AFFECTED_NAME,
                        "checkpoint-sensor-defect-affected.f32",
                        manifest.affectedBytes
                    ),
                    manifest.width,
                    manifest.height,
                    rowStrideBytes = checkNotNull(manifest.affectedRowStrideBytes)
                ).validate()
            } else {
                null
            }
            ProfileIntegrationRun(
                diagnostics = manifest.diagnostics,
                stackedSky = stacked,
                validCoverage = coverage,
                sensorDefectAffectedOutput = affected,
                sensorDefectFiltering = manifest.sensorDefectFiltering,
                totalDurationMillis = manifest.totalDurationMillis
            )
        }.getOrElse {
            restoredFiles.forEach(File::delete)
            clear()
            null
        }
    }

    fun write(run: ProfileIntegrationRun) {
        require(directory.isDirectory || directory.mkdirs())
        try {
            copyAtomic(run.stackedSky.file, File(directory, STACKED_NAME), run.stackedSky.expectedBytes)
            copyAtomic(
                run.validCoverage.file,
                File(directory, COVERAGE_NAME),
                run.validCoverage.expectedBytes
            )
            run.sensorDefectAffectedOutput?.let { affected ->
                copyAtomic(affected.file, File(directory, AFFECTED_NAME), affected.expectedBytes)
            } ?: File(directory, AFFECTED_NAME).delete()
            val manifest = Manifest(
                magic = MAGIC,
                version = VERSION,
                fingerprint = fingerprint,
                width = run.stackedSky.width,
                height = run.stackedSky.height,
                stackedBytes = run.stackedSky.expectedBytes,
                stackedRowStrideBytes = run.stackedSky.rowStrideBytes,
                coverageBytes = run.validCoverage.expectedBytes,
                coverageRowStrideBytes = run.validCoverage.rowStrideBytes,
                affectedBytes = run.sensorDefectAffectedOutput?.expectedBytes,
                affectedRowStrideBytes = run.sensorDefectAffectedOutput?.rowStrideBytes,
                diagnostics = run.diagnostics,
                sensorDefectFiltering = run.sensorDefectFiltering,
                totalDurationMillis = run.totalDurationMillis
            )
            val temporary = File(directory, "$MANIFEST_NAME.tmp")
            BufferedOutputStream(temporary.outputStream()).use { output ->
                ObjectOutputStream(output).use { it.writeObject(manifest) }
            }
            require(temporary.length() in 1..MAX_MANIFEST_BYTES)
            atomicReplace(temporary, File(directory, MANIFEST_NAME))
        } catch (error: Throwable) {
            clear()
            throw error
        }
    }

    fun clear() {
        deleteDirectory(directory)
        if (root.listFiles().orEmpty().isEmpty()) root.delete()
    }

    private data class Manifest(
        val magic: Int,
        val version: Int,
        val fingerprint: String,
        val width: Int,
        val height: Int,
        val stackedBytes: Long,
        val stackedRowStrideBytes: Int,
        val coverageBytes: Long,
        val coverageRowStrideBytes: Int,
        val affectedBytes: Long?,
        val affectedRowStrideBytes: Int?,
        val diagnostics: IntegrationDiagnostics,
        val sensorDefectFiltering: SensorDefectFilteringReport,
        val totalDurationMillis: Long
    ) : Serializable

    companion object {
        private const val ROOT_NAME = "jpeg-profile-integration-checkpoints"
        private const val MANIFEST_NAME = "manifest.bin"
        private const val STACKED_NAME = "stacked.argb"
        private const val COVERAGE_NAME = "coverage.f32"
        private const val AFFECTED_NAME = "affected.f32"
        private const val MAGIC = 0x49504350
        private const val VERSION = 1
        private const val MAX_MANIFEST_BYTES = 16L * 1024L * 1024L

        fun open(
            context: Context,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            width: Int,
            height: Int,
            frames: List<IntegrationCheckpointFrameSignature>,
            sensorDefectMask: SensorDefectMask,
            integrationSkyMask: SkyMask
        ): ProfileIntegrationCheckpointStore = openAt(
            context.filesDir,
            sessionFolder,
            profile,
            width,
            height,
            frames,
            sensorDefectMask,
            integrationSkyMask
        )

        internal fun open(
            filesRoot: File,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            width: Int,
            height: Int,
            frames: List<IntegrationCheckpointFrameSignature>,
            sensorDefectMask: SensorDefectMask,
            integrationSkyMask: SkyMask,
            @Suppress("UNUSED_PARAMETER") forTesting: Boolean
        ): ProfileIntegrationCheckpointStore = openAt(
            filesRoot,
            sessionFolder,
            profile,
            width,
            height,
            frames,
            sensorDefectMask,
            integrationSkyMask
        )

        private fun openAt(
            filesRoot: File,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            width: Int,
            height: Int,
            frames: List<IntegrationCheckpointFrameSignature>,
            sensorDefectMask: SensorDefectMask,
            integrationSkyMask: SkyMask
        ): ProfileIntegrationCheckpointStore {
            val fingerprint = fingerprint(
                sessionFolder,
                profile,
                width,
                height,
                frames,
                sensorDefectMask,
                integrationSkyMask
            )
            val root = File(filesRoot, ROOT_NAME)
            require(root.isDirectory || root.mkdirs())
            val directory = File(root, fingerprint)
            require(directory.isDirectory || directory.mkdir())
            root.listFiles().orEmpty()
                .filter { it.isDirectory && it != directory }
                .sortedByDescending(File::lastModified)
                .drop(2)
                .forEach(::deleteDirectory)
            directory.setLastModified(System.currentTimeMillis())
            return ProfileIntegrationCheckpointStore(root, directory, fingerprint)
        }

        private fun fingerprint(
            sessionFolder: String,
            profile: AstroProcessingProfile,
            width: Int,
            height: Int,
            frames: List<IntegrationCheckpointFrameSignature>,
            mask: SensorDefectMask,
            skyMask: SkyMask
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            add("integration-v$VERSION")
            add(sessionFolder)
            add(profile.name)
            add("$width:$height")
            frames.forEach { frame ->
                add(frame.frameId)
                add(frame.fileName)
                add(frame.sizeBytes.toString())
                add(frame.createdAtMillis.toString())
                add(frame.normalizedWeight.toRawBits().toString())
                with(frame.registration) {
                    add(dx.toRawBits().toString())
                    add(dy.toRawBits().toString())
                    add(rotationRadians.toRawBits().toString())
                    add(scale.toRawBits().toString())
                }
            }
            add("sensor:${mask.width}:${mask.height}:${mask.enabled}:${mask.rejectionReason}")
            mask.regions.forEach { region ->
                add(region.stableRegionId)
                region.footprintPixels.forEach { add("${it.x}:${it.y}") }
            }
            add("sky:${skyMask.width}:${skyMask.height}")
            val row = ByteArray(skyMask.width)
            for (y in 0 until skyMask.height) {
                for (x in row.indices) row[x] = if (skyMask.contains(x, y)) 1 else 0
                digest.update(row)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun copyAtomic(source: File, target: File, expectedBytes: Long) {
            require(source.isFile && source.length() == expectedBytes)
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.delete()
            source.inputStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            require(temporary.length() == expectedBytes)
            atomicReplace(temporary, target)
        }

        private fun atomicReplace(source: File, target: File) {
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

        private fun deleteDirectory(directory: File) {
            if (!directory.exists()) return
            directory.listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) deleteDirectory(child) else child.delete()
            }
            directory.delete()
        }
    }
}
