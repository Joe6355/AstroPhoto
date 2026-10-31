package com.joe6355.astrophoto

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StackStorageFailureTest {
    @Test fun manualNoSpaceFailureHasActionableMessageAndKeepsItsCause() = runBlocking {
        val fault = IOException("write failed: ENOSPC")
        val result = runManualStackingOperation(onAlignmentFailure = { fail("Not an alignment error") }) {
            throw fault
        }
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error is IOException)
        assertEquals(PROCESSING_STORAGE_FULL_MESSAGE, error.message)
        assertSame(fault, error.cause)
    }

    @Test fun nestedNoSpaceCauseIsRecognizedWithoutLosingTheOriginalChain() = runBlocking {
        val lowLevel = IOException("No space left on device")
        val fault = IllegalStateException("Unable to write a temporary image", lowLevel)
        val result = runManualStackingOperation(onAlignmentFailure = {}) { throw fault }
        val error = requireNotNull(result.exceptionOrNull())
        assertEquals(PROCESSING_STORAGE_FULL_MESSAGE, error.message)
        assertSame(fault, error.cause)
        assertSame(lowLevel, error.cause!!.cause)
    }

    @Test fun unrelatedIoFailureIsNotReportedAsFullStorage() = runBlocking {
        val fault = IOException("Permission denied")
        val result = runManualStackingOperation(onAlignmentFailure = {}) { throw fault }
        assertSame(fault, result.exceptionOrNull())
    }

    @Test fun cancellationWithNoSpaceTextStillPropagatesAsCancellation() = runBlocking {
        val cancelled = CancellationException("cancel after ENOSPC")
        try {
            runManualStackingOperation(onAlignmentFailure = {}) { throw cancelled }
            fail("Cancellation must escape the Result boundary")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }

    @Test fun anAlreadyLocalizedFailureIsNotWrappedAgain() = runBlocking {
        val fault = IOException(PROCESSING_STORAGE_FULL_MESSAGE, IOException("ENOSPC"))
        val result = runManualStackingOperation(onAlignmentFailure = {}) { throw fault }
        assertSame(fault, result.exceptionOrNull())
    }
}
