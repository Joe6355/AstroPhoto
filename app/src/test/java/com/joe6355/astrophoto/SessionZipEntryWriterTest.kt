package com.joe6355.astrophoto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SessionZipEntryWriterTest {
    @Test fun completeSourceIsIncludedWithoutChangingAnyBytes() {
        val bytes = ByteArray(137_321) { (it * 19).toByte() }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            assertTrue(writeSessionZipEntry(zip,"Lights/JPEG/source.jpg") { bytes.inputStream() })
        }
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals("Lights/JPEG/source.jpg",zip.nextEntry.name)
            assertArrayEquals(bytes,zip.readBytes())
            assertNull(zip.nextEntry)
        }
    }

    @Test fun unreadableOrMissingSourceCanBeSkippedBeforeAnEntryStarts() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            assertFalse(writeSessionZipEntry(zip,"missing") { null })
            assertFalse(writeSessionZipEntry(zip,"denied") { throw IOException("source unavailable") })
            assertTrue(writeSessionZipEntry(zip,"good") { byteArrayOf(1,2,3).inputStream() })
        }
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals("good",zip.nextEntry.name)
            assertArrayEquals(byteArrayOf(1,2,3),zip.readBytes())
            assertNull(zip.nextEntry)
        }
    }

    @Test fun failureReadingAnOpenedSourceMustAbortInsteadOfPublishingATruncatedEntry() {
        var closed = false
        val failure = IOException("source read failed")
        val input = object : InputStream() {
            private var count = 0
            override fun read(): Int = if (count++ < 17) 42 else throw failure
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (count != 0) throw failure
                bytes[offset] = 42; count++; return 1
            }
            override fun close() { closed = true }
        }
        ZipOutputStream(ByteArrayOutputStream()).use { zip ->
            assertSame(failure,assertThrows(IOException::class.java) {
                writeSessionZipEntry(zip,"broken") { input }
            })
        }
        assertTrue(closed)
    }

    @Test fun recoverableDestinationWriteFailureMustStillAbortTheWholeArchive() {
        val failure = IOException("write failed: ENOSPC (No space left on device)")
        val zip = object : ZipOutputStream(ByteArrayOutputStream()) {
            private var failed = false
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (!failed) { failed = true; throw failure }
                super.write(bytes,offset,length)
            }
        }
        zip.use {
            assertSame(failure,assertThrows(IOException::class.java) {
                writeSessionZipEntry(zip,"photo") { ByteArrayInputStream(byteArrayOf(1,2,3)) }
            })
        }
    }

    @Test fun failureClosingEntryMustNotBeReportedAsAnOrdinarySkippedSource() {
        val failure = IOException("close failed: ENOSPC")
        val zip = object : ZipOutputStream(ByteArrayOutputStream()) {
            private var failed = false
            override fun closeEntry() {
                if (!failed) { failed = true; throw failure }
                super.closeEntry()
            }
        }
        zip.use {
            assertSame(failure,assertThrows(IOException::class.java) {
                writeSessionZipEntry(zip,"photo") { byteArrayOf(1).inputStream() }
            })
        }
    }

    @Test fun cancellationIsNeverConvertedIntoASkippedFile() {
        val failure = CancellationException("cancel")
        ZipOutputStream(ByteArrayOutputStream()).use { zip ->
            assertSame(failure,assertThrows(CancellationException::class.java) {
                writeSessionZipEntry(zip,"photo") { throw failure }
            })
        }
    }
}
