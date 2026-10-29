package com.joe6355.astrophoto

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionAccessGuardTest {
    @Test fun differentSessionsRemainIndependentAndOldLeaseCannotUnlockNewOwner() {
        val registry = SessionAccessRegistry()
        val first = requireNotNull(registry.acquire("a"))
        assertNull(registry.acquire("a"))
        requireNotNull(registry.acquire("b")).close()
        first.close()
        val second = requireNotNull(registry.acquire("a"))
        first.close()
        assertNull(registry.acquire("a"))
        second.close()
        requireNotNull(registry.acquire("a")).close()
    }

    @Test fun blockedOperationNeverEntersItsFileAccessBlock() = runBlocking {
        val lease = requireNotNull(sessionAccess.acquire("blocked"))
        try {
            val result = guardedSessionOperation("blocked") { fail("File access must not start") }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Сессия занята"))
        } finally { lease.close() }
    }

    @Test fun successAndFailureBothReleaseOwnership() = runBlocking {
        assertEquals(7, guardedSessionOperation("finish") { 7 }.getOrThrow())
        assertTrue(guardedSessionOperation<Int>("finish") { error("disk failure") }.isFailure)
        requireNotNull(sessionAccess.acquire("finish")).close()
    }

    @Test fun cancellationRetainsOwnershipUntilSuspendedCleanupFinishes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            guardedSessionOperation("cancel") {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            }
        }
        entered.await(); job.cancel(); cleaning.await()
        assertNull(sessionAccess.acquire("cancel"))
        release.complete(Unit); job.join()
        assertTrue(job.isCancelled)
        requireNotNull(sessionAccess.acquire("cancel")).close()
    }
}
