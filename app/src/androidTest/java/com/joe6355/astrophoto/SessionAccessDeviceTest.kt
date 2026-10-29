package com.joe6355.astrophoto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionAccessDeviceTest {
    @Test fun processingRetainsOwnershipThroughCleanupAndReleasesOnEveryExit() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sessionAccessAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        withTimeout(30_000) {
            for (mode in listOf("success", "failure", "cancel")) {
                val folder = "Session_${System.currentTimeMillis()}_QA_ACCESS_$mode"
                val entered = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val cleaning = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val job = withContext(Dispatchers.Main) {
                    requireNotNull(SessionProcessingCoordinator.start(context,folder,"QA access audit","QA",1) {
                        try {
                            entered.complete(Unit)
                            finish.await()
                            if (mode == "failure") error("Expected QA failure")
                        } finally {
                            withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                        }
                    })
                }
                try {
                    entered.await()
                    assertTrue(SessionProcessingCoordinator.isActive(folder))
                    assertNull(sessionAccess.acquire(folder))
                    if (mode == "cancel") SessionProcessingCoordinator.cancel(folder) else finish.complete(Unit)
                    cleaning.await()
                    assertTrue(SessionProcessingCoordinator.isActive(folder))
                    assertNull(sessionAccess.acquire(folder))
                    release.complete(Unit)
                    job.join()
                    assertFalse(SessionProcessingCoordinator.isActive(folder))
                    requireNotNull(sessionAccess.acquire(folder)).close()
                } finally {
                    release.complete(Unit)
                    job.cancelAndJoin()
                }
            }
        }
    }

    @Test fun occupiedSessionRejectsAllFileOperationsBeforeReadingOrWriting() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sessionAccessAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val folder = "Session_${System.currentTimeMillis()}_QA_ACCESS"
        val session = SessionSummary(folder,"QA_ACCESS","Pictures/AstroPhoto/$folder/",0,0,0,0,"")
        val lease = requireNotNull(sessionAccess.acquire(folder))
        try {
            val outcomes = listOf(
                SessionFileManager(context).renameSession(session,"QA_ACCESS_NEW"),
                SessionFileManager(context).deleteSession(session),
                SessionZipExporter(context).export(session),
                SessionTimelapseExporter(context).export(session,5)
            )
            outcomes.forEach { result ->
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()!!.message!!.contains("Сессия занята"))
            }
            assertNull(SessionProcessingCoordinator.start(context,folder,"QA","QA",1) {
                fail("Processing must not start during a file operation")
            })
        } finally { lease.close() }
        requireNotNull(sessionAccess.acquire(folder)).close()
    }
}
