package com.joe6355.astrophoto.processing.jpeg.v2.analysis

import android.content.Context
import com.joe6355.astrophoto.AstroProcessingProfile
import com.joe6355.astrophoto.SessionFrame
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorCandidateObservation
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorFrameObservation
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMaskResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class CheckpointedProfileAnalysis(
    val frameId: String,
    val analysis: FrameAnalysis,
    val skyMask: SkyMaskResult,
    val sensorObservation: PersistentSensorFrameObservation
)

class ProfileAnalysisCheckpointStore private constructor(
    private val root: File,
    private val directory: File,
    val fingerprint: String
) {
    fun read(frame: SessionFrame, captureIndex: Int): CheckpointedProfileAnalysis? {
        val file = frameFile(captureIndex)
        if (!file.exists()) return null
        return runCatching {
            require(file.isFile && file.length() in 1..MAX_CHECKPOINT_BYTES)
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == VERSION)
                require(input.readUTF() == fingerprint && input.readUTF() == frame.key)
                require(input.readLong() == frame.sizeBytes && input.readLong() == frame.createdAtMillis)
                val analysis = input.readFrameAnalysis()
                val skyMask = input.readSkyMaskResult()
                val observation = input.readSensorObservation()
                require(
                    observation.originalCaptureIndex == captureIndex &&
                        analysis.id == frame.key && observation.frameId == frame.key
                )
                CheckpointedProfileAnalysis(frame.key, analysis, skyMask, observation)
            }
        }.getOrElse {
            clear()
            null
        }
    }

    fun write(
        frame: SessionFrame,
        captureIndex: Int,
        analysis: FrameAnalysis,
        skyMask: SkyMaskResult,
        observation: PersistentSensorFrameObservation
    ) {
        require(analysis.id == frame.key && observation.frameId == frame.key)
        require(observation.originalCaptureIndex == captureIndex)
        require(directory.isDirectory || directory.mkdirs())
        val target = frameFile(captureIndex)
        val temporary = File(directory, "${target.name}.tmp")
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeUTF(fingerprint)
            output.writeUTF(frame.key)
            output.writeLong(frame.sizeBytes)
            output.writeLong(frame.createdAtMillis)
            output.writeFrameAnalysis(analysis)
            output.writeSkyMaskResult(skyMask)
            output.writeSensorObservation(observation)
        }
        require(temporary.length() in 1..MAX_CHECKPOINT_BYTES) {
            "Profile analysis checkpoint is unexpectedly large"
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

    fun clear() {
        deleteDirectory(directory)
        root.listFiles().orEmpty().takeIf { it.isEmpty() }?.let { root.delete() }
    }

    private fun frameFile(captureIndex: Int): File =
        File(directory, "frame-${captureIndex.toString().padStart(4, '0')}.bin")

    companion object {
        private const val ROOT_NAME = "jpeg-profile-analysis-checkpoints"
        private const val MAGIC = 0x41504350
        private const val VERSION = 2
        private const val MAX_CHECKPOINT_BYTES = 32L * 1024L * 1024L
        private const val MAX_STARS = 10_000
        private const val MAX_CANDIDATES = 100_000
        private const val MAX_MASK_PIXELS = 4_000_000

        fun open(
            context: Context,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            frames: List<SessionFrame>,
            analysisWidth: Int,
            analysisHeight: Int
        ): ProfileAnalysisCheckpointStore = openAt(
            filesRoot = context.filesDir,
            sessionFolder = sessionFolder,
            profile = profile,
            frames = frames,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight
        )

        internal fun open(
            filesRoot: File,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            frames: List<SessionFrame>,
            analysisWidth: Int,
            analysisHeight: Int,
            @Suppress("UNUSED_PARAMETER") forTesting: Boolean
        ): ProfileAnalysisCheckpointStore = openAt(
            filesRoot,
            sessionFolder,
            profile,
            frames,
            analysisWidth,
            analysisHeight
        )

        private fun openAt(
            filesRoot: File,
            sessionFolder: String,
            profile: AstroProcessingProfile,
            frames: List<SessionFrame>,
            analysisWidth: Int,
            analysisHeight: Int
        ): ProfileAnalysisCheckpointStore {
            val fingerprint = fingerprint(
                sessionFolder,
                profile,
                frames,
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
            return ProfileAnalysisCheckpointStore(root, directory, fingerprint)
        }

        private fun fingerprint(
            sessionFolder: String,
            profile: AstroProcessingProfile,
            frames: List<SessionFrame>,
            width: Int,
            height: Int
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            add(sessionFolder)
            add(profile.name)
            add("$width:$height")
            frames.forEach { frame ->
                add(frame.key)
                add(frame.fileName)
                add(frame.sizeBytes.toString())
                add(frame.createdAtMillis.toString())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun deleteDirectory(directory: File) {
            if (!directory.isDirectory) return
            directory.walkBottomUp().forEach { entry ->
                if (entry == directory || entry.parentFile == directory) entry.delete()
            }
        }

        private fun DataOutputStream.writeStar(star: DetectedStar) {
            writeFloat(star.x); writeFloat(star.y); writeFloat(star.flux)
            writeFloat(star.localBackground); writeFloat(star.localContrast)
            writeFloat(star.width); writeFloat(star.ellipticity); writeFloat(star.confidence)
        }

        private fun DataInputStream.readStar() = DetectedStar(
            readFloat(), readFloat(), readFloat(), readFloat(),
            readFloat(), readFloat(), readFloat(), readFloat()
        )

        private fun DataOutputStream.writeFrameAnalysis(value: FrameAnalysis) {
            writeUTF(value.id); writeUTF(value.fileName)
            writeInt(value.width); writeInt(value.height)
            writeInt(value.stars.size); value.stars.forEach { writeStar(it) }
            writeInt(value.reliableStarCount)
            writeFloat(value.medianStarContrast); writeFloat(value.medianStarWidth)
            writeFloat(value.medianStarEllipticity); writeFloat(value.backgroundNoise)
            writeFloat(value.clippingPercent); writeFloat(value.exposureSuitability)
            writeBoolean(value.decodeValid); writeFloat(value.alignmentSuitability)
            writeFloat(value.skyMaskConfidence); writeBoolean(value.skyMaskUsedFallback)
            writeFloat(value.backgroundLevel)
        }

        private fun DataInputStream.readFrameAnalysis(): FrameAnalysis {
            val id = readUTF(); val name = readUTF(); val width = readInt(); val height = readInt()
            val starCount = readInt().also { require(it in 0..MAX_STARS) }
            val stars = List(starCount) { readStar() }
            return FrameAnalysis(
                id, name, width, height, stars, readInt(), readFloat(), readFloat(),
                readFloat(), readFloat(), readFloat(), readFloat(), readBoolean(),
                readFloat(), readFloat(), readBoolean(), readFloat()
            )
        }

        private fun DataOutputStream.writeSkyMaskResult(value: SkyMaskResult) {
            writeInt(value.mask.width); writeInt(value.mask.height)
            val pixels = value.mask.copyPixels()
            writeInt(pixels.size)
            pixels.forEach { writeBoolean(it) }
            writeFloat(value.confidence); writeBoolean(value.usedFallback)
        }

        private fun DataInputStream.readSkyMaskResult(): SkyMaskResult {
            val width = readInt(); val height = readInt(); val count = readInt()
            require(width > 0 && height > 0 && count == width * height && count <= MAX_MASK_PIXELS)
            val pixels = BooleanArray(count) { readBoolean() }
            return SkyMaskResult(SkyMask(width, height, pixels), readFloat(), readBoolean())
        }

        private fun DataOutputStream.writeSensorObservation(value: PersistentSensorFrameObservation) {
            writeUTF(value.frameId); writeInt(value.originalCaptureIndex)
            writeInt(value.width); writeInt(value.height); writeInt(value.candidates.size)
            value.candidates.forEach { candidate ->
                writeStar(candidate.feature)
                writeInt(candidate.centerRed); writeInt(candidate.centerGreen); writeInt(candidate.centerBlue)
                writeInt(candidate.backgroundRed); writeInt(candidate.backgroundGreen)
                writeInt(candidate.backgroundBlue); writeInt(candidate.chromaExcess)
            }
            writeLong(value.extractionElapsedNanos); writeLong(value.processedPixelCount)
            writeLong(value.estimatedAllocatedBytes)
        }

        private fun DataInputStream.readSensorObservation(): PersistentSensorFrameObservation {
            val id = readUTF(); val index = readInt(); val width = readInt(); val height = readInt()
            val count = readInt().also { require(it in 0..MAX_CANDIDATES) }
            val candidates = List(count) {
                PersistentSensorCandidateObservation(
                    readStar(), readInt(), readInt(), readInt(),
                    readInt(), readInt(), readInt(), readInt()
                )
            }
            return PersistentSensorFrameObservation(
                id, index, width, height, candidates, readLong(), readLong(), readLong()
            )
        }
    }
}
