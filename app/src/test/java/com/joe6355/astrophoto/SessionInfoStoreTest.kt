package com.joe6355.astrophoto

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionInfoStoreTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun migratesLegacyInfoAndKeepsHistoryAcrossStoreInstances() {
        val first = SessionInfoStore(files.root) { "sessionName: Ночь\nISO: 400\n" }
        first.update("Session_1") { it + "outputFile: Processed/stack.png\n" }
        val second = SessionInfoStore(files.root) { "stale legacy" }
        assertEquals("sessionName: Ночь\nISO: 400\noutputFile: Processed/stack.png\n", second.read("Session_1"))
        assertEquals(listOf("Session_1"), second.folders())
    }

    @Test fun failedUpdateRetainsPreviousCommittedContents() {
        val store = SessionInfoStore(files.root)
        store.update("Session_1") { "original" }
        assertTrue(runCatching { store.update("Session_1") { error("injected failure") } }.isFailure)
        assertEquals("original", store.read("Session_1"))
    }

    @Test fun concurrentAppendsFromDifferentInstancesDoNotLoseUpdates() {
        val threads = (1..8).map { value ->
            Thread { SessionInfoStore(files.root).update("Session_1") { it + "$value\n" } }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals((1..8).map { "$it" }.toSet(), SessionInfoStore(files.root).read("Session_1")!!.trim().lines().toSet())
    }

    @Test fun renameAndDeletionAffectOnlyRequestedMetadata() {
        val store = SessionInfoStore(files.root)
        store.update("Session_old") { "captured metadata" }
        store.update("Session_unrelated") { "keep" }
        store.prepareRename("Session_old", "Session_new", "fallback")
        assertEquals("captured metadata", store.read("Session_old"))
        assertEquals("captured metadata", store.read("Session_new"))
        assertTrue(store.delete("Session_old"))
        assertNull(store.read("Session_old"))
        assertTrue(store.delete("Session_new"))
        assertEquals("keep", store.read("Session_unrelated"))
    }

    @Test fun rejectsTraversalAndDoesNotOverwriteExistingRenameTarget() {
        val store = SessionInfoStore(files.root)
        listOf("..", "../outside", "sub/../../outside", "sub\\outside", "").forEach {
            assertTrue(runCatching { store.update(it) { "bad" } }.isFailure)
        }
        store.update("old") { "one" }
        store.update("new") { "two" }
        assertTrue(runCatching { store.prepareRename("old", "new", "") }.isFailure)
        assertEquals("one", store.read("old"))
        assertEquals("two", store.read("new"))
    }

    @Test fun failedRenamePreparationKeepsOriginalAndDoesNotPublishNewMetadata() {
        val store = SessionInfoStore(files.root)
        store.update("old") { "original" }
        assertTrue(runCatching { store.prepareRename("old", "new", "") { error("injected failure") } }.isFailure)
        assertEquals("original", store.read("old"))
        assertNull(store.read("new"))
        store.prepareRename("old", "old", "") { "$it\nnew name" }
        assertEquals("original\nnew name", store.read("old"))
    }
}
