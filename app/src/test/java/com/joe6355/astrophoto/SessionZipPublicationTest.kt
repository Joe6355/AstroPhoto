package com.joe6355.astrophoto

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SessionZipPublicationTest {
    @Test fun successPublishesOnceWithoutDiscardingTheOutput() {
        val events = mutableListOf<String>()
        val value = publishSessionZip(
            write = { events += "write"; 42 },
            publish = { events += "publish" },
            discard = { events += "discard" }
        )
        assertEquals(42,value)
        assertEquals(listOf("write","publish"),events)
    }

    @Test fun noSpaceWhileWritingDiscardsOutputAndKeepsCauseWithClearMessage() {
        val failure = IOException("write failed: ENOSPC")
        var discarded = 0
        val error = assertThrows(IOException::class.java) {
            publishSessionZip(write = { throw failure },
                publish = { fail("Must not publish") }, discard = { discarded++ })
        }
        assertEquals(1,discarded)
        assertEquals(ZIP_STORAGE_FULL_MESSAGE,error.message)
        assertSame(failure,error.cause)
    }

    @Test fun failedPublicationAlsoDiscardsTheNewArchive() {
        val failure = IOException("provider rejected publication")
        var discarded = false
        val error = assertThrows(IOException::class.java) {
            publishSessionZip(write = { 7 },publish = { throw failure },discard = { discarded = true })
        }
        assertSame(failure,error)
        assertTrue(discarded)
    }

    @Test fun cleanupErrorDoesNotHideOriginalWriteError() {
        val failure = IOException("permission denied")
        val cleanup = IOException("provider unavailable")
        val error = assertThrows(IOException::class.java) {
            publishSessionZip(write = { throw failure },publish = {},discard = { throw cleanup })
        }
        assertSame(failure,error)
        assertArrayEquals(arrayOf(cleanup),error.suppressed)
    }

    @Test fun cancellationDiscardsOutputAndRemainsCancellation() {
        val failure = CancellationException("cancel")
        var discarded = false
        val error = assertThrows(CancellationException::class.java) {
            publishSessionZip(write = { throw failure },publish = {},discard = { discarded = true })
        }
        assertSame(failure,error)
        assertTrue(discarded)
    }

    @Test fun noSpaceInNestedCauseIsRecognizedButUnrelatedMessagesArePreserved() {
        val lowLevel = IOException("No space left on device")
        val failure = IOException("write failed",lowLevel)
        assertEquals(ZIP_STORAGE_FULL_MESSAGE,zipPublicationFailure(failure).message)
        assertSame(failure,zipPublicationFailure(failure).cause)
        val unrelated = IOException("input unavailable")
        assertSame(unrelated,zipPublicationFailure(unrelated))
        val cancel = CancellationException("ENOSPC")
        assertSame(cancel,zipPublicationFailure(cancel))
    }
}
