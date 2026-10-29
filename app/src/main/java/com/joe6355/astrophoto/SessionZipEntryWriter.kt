package com.joe6355.astrophoto

import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

/** False means that the source could not be opened before its ZIP entry started. */
internal fun writeSessionZipEntry(
    zip: ZipOutputStream,
    entryName: String,
    openSource: () -> InputStream?
): Boolean {
    val input = try {
        openSource()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return false
    // Once an entry starts, a read/write/close failure must abort the archive.
    // Closing a partial entry can produce a valid ZIP containing a truncated file.
    input.buffered(32 * 1024).use {
        zip.putNextEntry(ZipEntry(entryName))
        it.copyTo(zip, 32 * 1024)
    }
    zip.closeEntry()
    return true
}
