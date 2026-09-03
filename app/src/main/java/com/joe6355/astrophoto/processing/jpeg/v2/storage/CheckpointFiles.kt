package com.joe6355.astrophoto.processing.jpeg.v2.storage

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Streaming integrity checks: never load a full-resolution image into the heap. */
internal object CheckpointFiles {
    const val MAX_METADATA_BYTES = 64L * 1024L * 1024L

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expectedBytes: Long, expectedHash: String) {
        require(file.isFile && file.length() == expectedBytes) { "Incomplete checkpoint ${file.name}" }
        require(expectedHash.matches(Regex("[a-f0-9]{64}")) && sha256(file) == expectedHash) {
            "Corrupted checkpoint ${file.name}"
        }
    }

    fun writeObject(file: File, value: Serializable) {
        checksumFile(file).delete()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().buffered().use { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
        }
        require(temporary.length() in 1..MAX_METADATA_BYTES)
        atomicReplace(temporary, file)
        seal(file)
    }

    fun seal(file: File) {
        val hash = sha256(file)
        val checksum = checksumFile(file)
        val checksumTemporary = File(file.parentFile, "${checksum.name}.tmp")
        checksumTemporary.writeText(hash, Charsets.US_ASCII)
        atomicReplace(checksumTemporary, checksum)
    }

    fun readObject(file: File): Any {
        require(file.isFile && file.length() in 1..MAX_METADATA_BYTES)
        verifySealed(file)
        return file.inputStream().buffered().use { input ->
            ObjectInputStream(input).use { it.readObject() }
        }
    }

    fun verifySealed(file: File) {
        val checksum = checksumFile(file)
        require(checksum.isFile && checksum.length() == 64L)
        verify(file, file.length(), checksum.readText(Charsets.US_ASCII))
    }

    fun copyAtomic(source: File, target: File, expectedBytes: Long): String {
        require(source.isFile && source.length() == expectedBytes)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered().use { input ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        }
        require(temporary.length() == expectedBytes)
        atomicReplace(temporary, target)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun atomicReplace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun checksumFile(file: File) = File(file.parentFile, "${file.name}.sha256")
}
