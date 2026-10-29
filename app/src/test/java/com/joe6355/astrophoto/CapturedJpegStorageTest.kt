package com.joe6355.astrophoto

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CapturedJpegStorageTest {
    private val bytes = byteArrayOf(0xff.toByte(),0xd8.toByte(),1,2,3,0xff.toByte(),0xd9.toByte())

    @Test fun successfulWriteClosesBeforePublishingAndKeepsEveryByte() {
        var closed = false
        var published = 0
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        writePendingCapturedJpeg(bytes,{ output },{
            assertTrue(closed); published++; 1
        },{ fail("Successful JPEG must not be removed") })
        assertArrayEquals(bytes,output.toByteArray())
        assertEquals(1,published)
    }

    @Test fun zeroUpdatedRowsMustNotCountAsASavedFrame() {
        var discarded = 0
        assertThrows(IllegalStateException::class.java) {
            writePendingCapturedJpeg(bytes,{ ByteArrayOutputStream() },{ 0 },{ discarded++ })
        }
        assertEquals(1,discarded)
    }

    @Test fun noSpaceDuringWriteDiscardsPendingJpegAndKeepsCause() {
        val failure = IOException("write failed: ENOSPC")
        var discarded = false
        val error = assertThrows(IOException::class.java) {
            writePendingCapturedJpeg(bytes,{ object : OutputStream() {
                override fun write(value: Int) { throw failure }
            } },{ fail("Must not publish"); 0 },{ discarded = true })
        }
        assertTrue(discarded)
        assertEquals(CAPTURE_STORAGE_FULL_MESSAGE,error.message)
        assertSame(failure,error.cause)
    }

    @Test fun noSpaceOnCloseAlsoAbortsBeforePublication() {
        val failure = IOException("close failed: No space left on device")
        var discarded = false
        val error = assertThrows(IOException::class.java) {
            writePendingCapturedJpeg(bytes,{ object : ByteArrayOutputStream() {
                override fun close() { throw failure }
            } },{ fail("Must not publish"); 0 },{ discarded = true })
        }
        assertTrue(discarded)
        assertEquals(CAPTURE_STORAGE_FULL_MESSAGE,error.message)
        assertSame(failure,error.cause)
    }

    @Test fun cleanupFailureMustNotHideTheOriginalWriteFailure() {
        val failure = IOException("input/output error")
        val cleanup = IOException("cleanup failed")
        val error = assertThrows(IOException::class.java) {
            writePendingCapturedJpeg(bytes,{ throw failure },{ 1 },{ throw cleanup })
        }
        assertSame(failure,error)
        assertArrayEquals(arrayOf(cleanup),error.suppressed)
    }

    @Test fun emptyJpegIsNeverPublished() {
        var discarded = false
        assertThrows(IllegalArgumentException::class.java) {
            writePendingCapturedJpeg(byteArrayOf(),{ ByteArrayOutputStream() },{
                fail("Must not publish empty JPEG"); 0
            },{ discarded = true })
        }
        assertTrue(discarded)
    }

    @Test fun cancellationIsPreservedAndCleansOnlyNewOutput() {
        val failure = CancellationException("capture cancelled")
        var discarded = 0
        val error = assertThrows(CancellationException::class.java) {
            writePendingCapturedJpeg(bytes,{ throw failure },{ 1 },{ discarded++ })
        }
        assertSame(failure,error)
        assertEquals(1,discarded)
    }

    @Test fun missingOutputAndRejectedPublicationAreCleaned() {
        var discarded = 0
        assertThrows(IllegalStateException::class.java) {
            writePendingCapturedJpeg(bytes,{ null },{ fail("Must not publish"); 0 },{ discarded++ })
        }
        val failure = IOException("provider unavailable")
        assertSame(failure,assertThrows(IOException::class.java) {
            writePendingCapturedJpeg(bytes,{ ByteArrayOutputStream() },{ throw failure },{ discarded++ })
        })
        assertEquals(2,discarded)
    }
}
