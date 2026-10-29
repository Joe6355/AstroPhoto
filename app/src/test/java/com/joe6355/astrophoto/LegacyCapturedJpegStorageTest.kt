package com.joe6355.astrophoto

import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyCapturedJpegStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = ByteArray(8192) { (it * 31).toByte() }

    @Test fun completedJpegContainsTheOriginalBytes() {
        val requested = temporary.root.resolve("frame.jpg")
        val saved = writeLegacyCapturedJpeg(bytes,requested)
        assertEquals(requested,saved)
        assertArrayEquals(bytes,saved.readBytes())
        assertEquals(listOf("frame.jpg"),temporary.root.list()!!.toList())
    }

    @Test fun partialWriteIsCleanedWithoutChangingExistingPhotos() {
        val neighbour = temporary.newFile("old.jpg").apply { writeText("previous photo") }
        val requested = temporary.root.resolve("new.jpg")
        val failure = IOException("write failed: ENOSPC")
        val error = assertThrows(IOException::class.java) {
            writeLegacyCapturedJpeg(bytes,requested) { path ->
                object : OutputStream() {
                    private val output = FileOutputStream(path)
                    override fun write(value: Int) { output.write(value); throw failure }
                    override fun close() = output.close()
                }
            }
        }
        assertEquals(CAPTURE_STORAGE_FULL_MESSAGE,error.message)
        assertFalse(requested.exists())
        assertEquals("previous photo",neighbour.readText())
        assertEquals(listOf("old.jpg"),temporary.root.list()!!.toList())
    }

    @Test fun existingRequestedNameIsNeverOverwritten() {
        val requested = temporary.newFile("frame.jpg").apply { writeText("previous photo") }
        val saved = writeLegacyCapturedJpeg(bytes,requested)
        assertEquals("previous photo",requested.readText())
        assertNotEquals(requested,saved)
        assertArrayEquals(bytes,saved.readBytes())
    }

    @Test fun fileAppearingDuringWriteIsAlsoPreserved() {
        val requested = temporary.root.resolve("frame.jpg")
        val saved = writeLegacyCapturedJpeg(bytes,requested) { output ->
            requested.writeText("concurrent photo")
            FileOutputStream(output)
        }
        assertEquals("concurrent photo",requested.readText())
        assertNotEquals(requested,saved)
        assertArrayEquals(bytes,saved.readBytes())
    }

    @Test fun closeFailureDoesNotLeavePartialPhotoOrTemporaryFile() {
        val requested = temporary.root.resolve("frame.jpg")
        assertThrows(IOException::class.java) {
            writeLegacyCapturedJpeg(bytes,requested) { output ->
                object : FileOutputStream(output) {
                    override fun close() { super.close(); throw IOException("close failed: ENOSPC") }
                }
            }
        }
        assertFalse(requested.exists())
        assertEquals(0,temporary.root.list()!!.size)
    }

    @Test fun cancellationCleansPartialPhotoAndPreservesCancellation() {
        val requested = temporary.root.resolve("frame.jpg")
        val failure = CancellationException("cancelled")
        val error = assertThrows(CancellationException::class.java) {
            writeLegacyCapturedJpeg(bytes,requested) { output ->
                object : FileOutputStream(output) {
                    override fun write(bytes: ByteArray) { super.write(bytes,0,3); throw failure }
                }
            }
        }
        assertSame(failure,error)
        assertFalse(requested.exists())
        assertEquals(0,temporary.root.list()!!.size)
    }

    @Test fun emptyJpegCreatesNoFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            writeLegacyCapturedJpeg(byteArrayOf(),temporary.root.resolve("frame.jpg"))
        }
        assertEquals(0,temporary.root.list()!!.size)
    }
}
