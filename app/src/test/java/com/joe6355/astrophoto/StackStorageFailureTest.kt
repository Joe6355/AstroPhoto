package com.joe6355.astrophoto

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.joe6355.astrophoto.processing.jpeg.v2.memory.ImageAllocationEstimate
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot

class StackStorageFailureTest {
    @Test fun realOutOfMemoryIsActionableAndRetainsCauseAfterManualBoundary() = runBlocking {
        val fault = OutOfMemoryError("QA injection; no heap allocation")
        val result = runManualStackingOperation(onAlignmentFailure = {}) { throw fault }
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error.message.orEmpty().contains("оперативной памяти"))
        assertTrue(error.message.orEmpty().contains("Закройте другие приложения"))
        assertSame(fault, error.cause)
        assertSame(error, processingResourceFailure(error))
    }

    @Test fun plannedBudgetRefusalExplainsNeededAndAvailableMemoryWithoutAllocating() = runBlocking {
        val budget = JpegMemoryBudget(RuntimeHeapSnapshot(64L shl 20, 48L shl 20, 16L shl 20))
        val result = runManualStackingOperation(onAlignmentFailure = {}) {
            budget.requireAllocation(ImageAllocationEstimate("fixture", 8L shl 20))
        }
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error.message.orEmpty().contains("оперативной памяти"))
        assertTrue(error.message.orEmpty().contains("не менее 8 МБ"))
        assertTrue(error.message.orEmpty().contains("до 0 МБ"))
        assertTrue(error.cause is IllegalArgumentException)
    }

    @Test fun cancellationWithMemoryCauseStillEscapesAsCancellation() = runBlocking {
        val cancelled = CancellationException("cancelled").apply { initCause(OutOfMemoryError("QA")) }
        try {
            runManualStackingOperation(onAlignmentFailure = {}) { throw cancelled }
            fail("Cancellation must escape")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }

    @Test fun localizedProfileStageIsPreservedAndUnrelatedFailureIsNotReclassified() {
        val nested = IllegalStateException("Ошибка на этапе: запись. $PROCESSING_STORAGE_FULL_MESSAGE",
            IOException(PROCESSING_STORAGE_FULL_MESSAGE, IOException("ENOSPC")))
        assertSame(nested, processingResourceFailure(nested))
        val unrelated = IllegalArgumentException("Could not read memory_card.jpg")
        assertSame(unrelated, processingResourceFailure(unrelated))
    }

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
