package com.joe6355.astrophoto.processing.jpeg.v2.registration

import android.content.Context
import com.joe6355.astrophoto.AstroProcessingProfile
import com.joe6355.astrophoto.SessionFrame
import com.joe6355.astrophoto.processing.jpeg.v2.storage.CheckpointFiles
import java.io.File
import java.io.Serializable
import java.security.MessageDigest

/** App-private, all-or-nothing checkpoint for the completed registration stage. */
class ProfileRegistrationCheckpointStore private constructor(
    private val root: File,
    private val directory: File,
    private val fingerprint: String
) {
    fun read(): SequenceAwareRegistrationDiagnostics? {
        val file = checkpointFile()
        if (!file.exists()) return null
        return try {
            require(file.isFile && file.length() in 1..MAX_CHECKPOINT_BYTES)
            val envelope = CheckpointFiles.readObject(file) as RegistrationCheckpointEnvelope
            require(envelope.magic == MAGIC && envelope.version == VERSION)
            require(envelope.fingerprint == fingerprint)
            envelope.diagnostics
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun write(diagnostics: SequenceAwareRegistrationDiagnostics) {
        try {
            require(directory.isDirectory || directory.mkdirs())
            val target = checkpointFile()
            CheckpointFiles.writeObject(target,
                RegistrationCheckpointEnvelope(MAGIC, VERSION, fingerprint, diagnostics))
        } catch (error: Exception) {
            clear()
            throw error
        }
    }

    fun clear() {
        checkpointFile().delete()
        deleteDirectory(directory)
        root.listFiles().orEmpty().takeIf { it.isEmpty() }?.let { root.delete() }
    }

    private fun checkpointFile() = File(directory, "registration.bin")

    fun readFullResolution(): FullResolutionCheckpoint? {
        val file = File(directory, "full-resolution.bin")
        if (!file.exists()) return null
        return try {
            CheckpointFiles.readObject(file) as FullResolutionCheckpoint
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun writeFullResolution(checkpoint: FullResolutionCheckpoint) {
        require(directory.isDirectory || directory.mkdirs())
        try {
            CheckpointFiles.writeObject(File(directory, "full-resolution.bin"), checkpoint)
        } catch (error: Exception) {
            clear()
            throw error
        }
    }

    private data class RegistrationCheckpointEnvelope(
        val magic: Int,
        val version: Int,
        val fingerprint: String,
        val diagnostics: SequenceAwareRegistrationDiagnostics
    ) : Serializable

    companion object {
        private const val ROOT_NAME = "jpeg-profile-registration-checkpoints"
        private const val MAGIC = 0x52504350
        private const val VERSION = 2
        private const val MAX_CHECKPOINT_BYTES = 64L * 1024L * 1024L

        fun open(
            context: Context,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            frames: List<SessionFrame>,
            selectedFrameKeys: List<String>,
            analysisWidth: Int,
            analysisHeight: Int
        ): ProfileRegistrationCheckpointStore = openAt(
            context.filesDir,
            sessionFolder,
            profile,
            frames,
            selectedFrameKeys,
            analysisWidth,
            analysisHeight
        )

        internal fun open(
            filesRoot: File,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            frames: List<SessionFrame>,
            selectedFrameKeys: List<String>,
            analysisWidth: Int,
            analysisHeight: Int,
            @Suppress("UNUSED_PARAMETER") forTesting: Boolean
        ): ProfileRegistrationCheckpointStore = openAt(
            filesRoot,
            sessionFolder,
            profile,
            frames,
            selectedFrameKeys,
            analysisWidth,
            analysisHeight
        )

        private fun openAt(
            filesRoot: File,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            frames: List<SessionFrame>,
            selectedFrameKeys: List<String>,
            analysisWidth: Int,
            analysisHeight: Int
        ): ProfileRegistrationCheckpointStore {
            val fingerprint = fingerprint(
                sessionFolder,
                profile,
                frames,
                selectedFrameKeys,
                analysisWidth,
                analysisHeight
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
            return ProfileRegistrationCheckpointStore(root, directory, fingerprint)
        }

        private fun fingerprint(
            sessionFolder: String,
            profile: AstroProcessingProfile,
            frames: List<SessionFrame>,
            selectedFrameKeys: List<String>,
            width: Int,
            height: Int
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            add("registration-v$VERSION")
            add(sessionFolder)
            add(profile.name)
            add("$width:$height")
            add(selectedFrameKeys.joinToString("|"))
            frames.forEach { frame ->
                add(frame.key)
                add(frame.fileName)
                add(frame.sizeBytes.toString())
                add(frame.createdAtMillis.toString())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
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
