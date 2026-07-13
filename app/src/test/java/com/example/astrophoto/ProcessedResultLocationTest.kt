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
            byName["Stacked.jpg" to relativePath] = provider("content://images/7", "Stacked.jpg")
        }
        val resolved = resolveImageSource(reference("Stacked.jpg"), lookup)
        assertEquals("content://images/7", resolved?.pathOrUri)
        assertEquals(listOf("Stacked.jpg" to relativePath), lookup.nameQueries)
    }

    @Test
    fun relativePathIsNormalizedWithTrailingSlash() {
        val lookup = FakeLookup().apply {
            byName["Stacked.jpg" to relativePath] = provider("content://images/7", "Stacked.jpg")
        }
        val resolved = resolveImageSource(
            reference("Stacked.jpg").copy(relativePath = relativePath.removeSuffix("/")),
            lookup
        )
        assertEquals("content://images/7", resolved?.pathOrUri)
    }

    @Test
    fun sameFileNameInAnotherSessionIsNotSelected() {
        val otherPath = "Pictures/AstroPhoto/Other/Processed/"
        val lookup = FakeLookup().apply {
            byName["Stacked.jpg" to otherPath] =
                ProviderImageRecord("content://images/8", "Stacked.jpg", otherPath)
        }
        assertNull(resolveImageSource(reference("Stacked.jpg"), lookup))
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
        assertEquals(ProviderOrigin.STORED, resolved.origin)
        assertTrue(lookup.nameQueries.isEmpty())
    }

    @Test
    fun legacyMediaStoreFilesRowIsReadOnlyFallback() {
        val lookup = FakeLookup().apply {
            filesByName["Old.jpg" to relativePath] =
                provider("content://media/external/file/9", "Old.jpg")
        }
        val resolved = resolveImageSource(reference("Old.jpg"), lookup)
            as ResolvedImageSource.Provider
        assertEquals("content://media/external/file/9", resolved.record.uri)
        assertEquals(ProviderOrigin.FILES, resolved.origin)
        assertEquals(listOf("Old.jpg" to relativePath), lookup.nameQueries)
        assertEquals(listOf("Old.jpg" to relativePath), lookup.filesQueries)
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

    @Test
    fun resultConsumersKeepTheSameStoredIdentity() {
        val result = ProcessedResult(
            key = "result",
            fileName = "Stacked.jpg",
            type = ProcessedResultType.STACK,
            createdAtMillis = 1L,
            sizeBytes = 10L,
            displayPath = "${relativePath}Stacked.jpg",
            contentUri = "content://media/external_primary/images/media/42",
            filePath = null,
            relativePath = relativePath
        )
        val source = result.imageSource()
        assertEquals(result.contentUri, source.providerUri)
        assertEquals(result.relativePath, source.relativePath)
        assertEquals(result.fileName, source.displayName)
    }

    private fun reference(name: String) = ImageSourceReference(name, relativePath, null, null)

    private fun provider(uri: String, name: String) =
        ProviderImageRecord(uri, name, relativePath)
}

internal class FakeLookup : ImageSourceLookup {
    val byUri = mutableMapOf<String, ProviderImageRecord>()
    val byName = mutableMapOf<Pair<String, String>, ProviderImageRecord>()
    val filesByName = mutableMapOf<Pair<String, String>, ProviderImageRecord>()
    val legacy = mutableSetOf<String>()
    val nameQueries = mutableListOf<Pair<String, String>>()
    val filesQueries = mutableListOf<Pair<String, String>>()
    val legacyQueries = mutableListOf<String>()

    override fun providerByUri(uri: String): ProviderImageRecord? = byUri[uri]

    override fun imagesProviderByNameAndPath(
        displayName: String,
        relativePath: String
    ): ProviderImageRecord? {
        nameQueries += displayName to relativePath
        return byName[displayName to relativePath]
    }

    override fun filesProviderByNameAndPath(
        displayName: String,
        relativePath: String
    ): ProviderImageRecord? {
        filesQueries += displayName to relativePath
        return filesByName[displayName to relativePath]
    }

    override fun readableLegacyFile(absolutePath: String): Boolean {
        legacyQueries += absolutePath
        return absolutePath in legacy
    }
}
