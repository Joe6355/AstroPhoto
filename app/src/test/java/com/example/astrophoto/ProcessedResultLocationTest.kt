package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedResultLocationTest {
    private val relativePath = "Pictures/AstroPhoto/Session/Processed/"

    @Test
    fun mediaStoreResultResolvesByDisplayNameAndRelativePath() {
        val lookup = FakeLookup().apply {
            byName["Stacked.jpg" to relativePath] = provider("content://files/7", "Stacked.jpg")
        }
        val resolved = resolveImageSource(reference("Stacked.jpg"), lookup)
        assertEquals("content://files/7", resolved?.pathOrUri)
        assertEquals(listOf("Stacked.jpg" to relativePath), lookup.nameQueries)
    }

    @Test
    fun readableProviderUriIsPreferredOverNameLookup() {
        val lookup = FakeLookup().apply {
            byUri["content://images/3"] = provider("content://images/3", "Actual.jpg")
            byName["Old.jpg" to relativePath] = provider("content://files/9", "Old.jpg")
        }
        val resolved = resolveImageSource(
            reference("Old.jpg").copy(providerUri = "content://images/3"),
            lookup
        ) as ResolvedImageSource.Provider
        assertEquals("content://images/3", resolved.record.uri)
        assertTrue(lookup.nameQueries.isEmpty())
    }

    @Test
    fun providerRenamedResultKeepsActualProviderName() {
        val lookup = FakeLookup().apply {
            byUri["content://images/4"] = provider("content://images/4", "DeepSky (1).jpg")
        }
        val resolved = resolveImageSource(
            reference("DeepSky.jpg").copy(providerUri = "content://images/4"),
            lookup
        ) as ResolvedImageSource.Provider
        assertEquals("DeepSky (1).jpg", resolved.record.displayName)
    }

    @Test
    fun missingProviderFallsBackToRealLegacyFile() {
        val path = "C:\\Pictures\\AstroPhoto\\Session\\Processed\\Stacked.jpg"
        val lookup = FakeLookup().apply { legacy += path }
        val resolved = resolveImageSource(
            reference("Stacked.jpg").copy(legacyFilePath = path),
            lookup
        )
        assertEquals(path, (resolved as ResolvedImageSource.LegacyFile).absolutePath)
    }

    @Test
    fun missingResultReturnsNull() {
        assertNull(resolveImageSource(reference("Missing.jpg"), FakeLookup()))
    }

    @Test
    fun logicalRelativePathIsNeverUsedAsFakeLegacyFile() {
        val logical = "Pictures/AstroPhoto/Session/Processed/Stacked.jpg"
        val lookup = FakeLookup().apply { legacy += logical }
        val resolved = resolveImageSource(
            reference("Stacked.jpg").copy(relativePath = null, legacyFilePath = logical),
            lookup
        )
        assertNull(resolved)
        assertTrue(lookup.legacyQueries.isEmpty())
        assertEquals(relativePath, logicalRelativePath(logical, "Stacked.jpg"))
    }

    private fun reference(name: String) = ImageSourceReference(name, relativePath, null, null)

    private fun provider(uri: String, name: String) =
        ProviderImageRecord(uri, name, relativePath)
}

internal class FakeLookup : ImageSourceLookup {
    val byUri = mutableMapOf<String, ProviderImageRecord>()
    val byName = mutableMapOf<Pair<String, String>, ProviderImageRecord>()
    val legacy = mutableSetOf<String>()
    val nameQueries = mutableListOf<Pair<String, String>>()
    val legacyQueries = mutableListOf<String>()

    override fun providerByUri(uri: String): ProviderImageRecord? = byUri[uri]

    override fun providerByNameAndPath(
        displayName: String,
        relativePath: String
    ): ProviderImageRecord? {
        nameQueries += displayName to relativePath
        return byName[displayName to relativePath]
    }

    override fun readableLegacyFile(absolutePath: String): Boolean {
        legacyQueries += absolutePath
        return absolutePath in legacy
    }
}
