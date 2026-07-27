package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun enhancedPngIsDiscoveredAndKeepsTheSameStoredIdentityWhenOpened() {
        val fileName = "Enhanced_20260727_120000.png"
        assertEquals(ProcessedResultType.ENHANCED, processedResultTypeForFileName(fileName))

        val result = ProcessedResult(
            key = "enhanced",
            fileName = fileName,
            type = ProcessedResultType.ENHANCED,
            createdAtMillis = 1L,
            sizeBytes = 10L,
            displayPath = "$relativePath$fileName",
            contentUri = "content://media/external_primary/images/media/84",
            filePath = null,
            relativePath = relativePath
        )

        val source = result.imageSource()
        assertEquals(result.contentUri, source.providerUri)
        assertEquals(result.relativePath, source.relativePath)
        assertEquals(result.fileName, source.displayName)
    }

    @Test
    fun acceptedEnhancedResolvesThroughItsStoredProviderIdentity() {
        val fileName = "Enhanced_20260727_120000.png"
        val storedUri = "content://media/external_primary/images/media/84"
        val lookup = FakeLookup().apply {
            byUri[storedUri] = provider(storedUri, fileName)
        }
        val result = processedResult(
            fileName = fileName,
            type = ProcessedResultType.ENHANCED,
            contentUri = storedUri
        )

        val resolved = resolveImageSource(result.imageSource(), lookup)

        assertEquals(storedUri, resolved?.pathOrUri)
        assertTrue(resolved is ResolvedImageSource.Provider)
        assertEquals(ProviderOrigin.STORED, (resolved as ResolvedImageSource.Provider).origin)
        assertTrue(lookup.nameQueries.isEmpty())
    }

    @Test
    fun zeroLengthOrMissingEnhancedRemainsUnavailableWithoutCrashing() {
        val fileName = "Enhanced_20260727_120000.png"
        val unavailable = processedResult(
            fileName = fileName,
            type = ProcessedResultType.ENHANCED,
            contentUri = "content://media/external_primary/images/media/404",
            sizeBytes = 0L,
            isReadable = false,
            errorMessage = "File could not be opened"
        )

        assertFalse(unavailable.isReadable)
        assertNull(resolveImageSource(unavailable.imageSource(), FakeLookup()))
        val pairLookup = FakeLookup().apply {
            byName["RecoveredStars_20260727_120000.png" to relativePath] =
                provider(
                    "content://media/external_primary/images/media/83",
                    "RecoveredStars_20260727_120000.png"
                )
        }
        assertNull(
            resolveImagePair(
                first = processedResult(
                    fileName = "RecoveredStars_20260727_120000.png",
                    type = ProcessedResultType.RECOVERED_STARS
                ).imageSource(),
                second = unavailable.imageSource(),
                lookup = pairLookup
            )
        )
    }

    @Test
    fun partialOrMismatchedEnhancedNameIsNotAcceptedAsSupportedPng() {
        assertTrue(
            isSupportedProcessedImageFile(
                "Enhanced_20260727_120000.png",
                "image/png"
            )
        )
        assertFalse(
            isSupportedProcessedImageFile(
                "Enhanced_20260727_120000.png",
                "image/jpeg"
            )
        )
        assertFalse(
            isSupportedProcessedImageFile(
                "Enhanced_20260727_120000.png.part",
                "image/png"
            )
        )
    }

    @Test
    fun oldSessionWithoutEnhancedKeepsItsExistingResultsDiscoverable() {
        val oldSessionFiles = listOf(
            "RecoveredStars_20260717_081217.png",
            "DeepSky_20260717_081217.png",
            "UrbanSky_20260717_081528.png"
        )

        val discoveredTypes = oldSessionFiles.map(::processedResultTypeForFileName)

        assertEquals(
            listOf(
                ProcessedResultType.RECOVERED_STARS,
                ProcessedResultType.DEEP_SKY,
                ProcessedResultType.URBAN_SKY
            ),
            discoveredTypes
        )
        assertFalse(ProcessedResultType.ENHANCED in discoveredTypes)
    }

    @Test
    fun unknownFutureProcessedImageStaysOpenableAsUnknownWithoutTypeCollision() {
        val fileName = "FutureAstroV3_20270101.png"
        assertEquals(ProcessedResultType.UNKNOWN, processedResultTypeForFileName(fileName))
        assertTrue(isSupportedProcessedImageFile(fileName, "image/png"))

        val lookup = FakeLookup().apply {
            byName[fileName to relativePath] = provider("content://images/203", fileName)
        }
        val resolved = resolveImageSource(reference(fileName), lookup)
        assertEquals("content://images/203", resolved?.pathOrUri)
    }

    @Test
    fun displayOrderingStaysStableAcrossEnhancedLegacyUnknownAndUnreadableResults() {
        val duplicateName = "RecoveredStars_duplicate.png"
        val input = listOf(
            processedResult(
                key = "duplicate-first",
                fileName = duplicateName,
                type = ProcessedResultType.RECOVERED_STARS,
                createdAtMillis = 10L
            ),
            processedResult(
                key = "unknown",
                fileName = "FutureAstroV3_1.png",
                type = ProcessedResultType.UNKNOWN,
                createdAtMillis = 20L
            ),
            processedResult(
                key = "missing-enhanced",
                fileName = "Enhanced_missing.png",
                type = ProcessedResultType.ENHANCED,
                createdAtMillis = 30L,
                sizeBytes = 0L,
                isReadable = false
            ),
            processedResult(
                key = "recovered",
                fileName = "RecoveredStars_1.png",
                type = ProcessedResultType.RECOVERED_STARS,
                createdAtMillis = 20L
            ),
            processedResult(
                key = "enhanced",
                fileName = "Enhanced_1.png",
                type = ProcessedResultType.ENHANCED,
                createdAtMillis = 20L
            ),
            processedResult(
                key = "duplicate-second",
                fileName = duplicateName,
                type = ProcessedResultType.RECOVERED_STARS,
                createdAtMillis = 10L
            )
        )

        assertEquals(
            listOf(
                "missing-enhanced",
                "enhanced",
                "unknown",
                "recovered",
                "duplicate-first",
                "duplicate-second"
            ),
            sortProcessedResultsForDisplay(input).map(ProcessedResult::key)
        )
    }

    @Test
    fun existingProcessedResultPrefixesRemainCompatible() {
        val existingResults = listOf(
            "StackedDarkAligned_1.jpg" to ProcessedResultType.DARK_ALIGNED_STACK,
            "StackedAligned_1.jpg" to ProcessedResultType.ALIGNED_STACK,
            "StackedDark_1.jpg" to ProcessedResultType.DARK_STACK,
            "Stacked_1.jpg" to ProcessedResultType.STACK,
            "MasterDark_1.jpg" to ProcessedResultType.MASTER_DARK,
            "MedianAligned_1.jpg" to ProcessedResultType.MEDIAN_ALIGNED_STACK,
            "Median_1.jpg" to ProcessedResultType.MEDIAN_STACK,
            "SigmaAligned_1.jpg" to ProcessedResultType.SIGMA_ALIGNED_STACK,
            "Sigma_1.jpg" to ProcessedResultType.SIGMA_STACK,
            "DeepSkyAligned_1.jpg" to ProcessedResultType.DEEP_SKY_ALIGNED,
            "DeepSky_1.png" to ProcessedResultType.DEEP_SKY,
            "UrbanSkyStrong_1.png" to ProcessedResultType.URBAN_SKY_STRONG,
            "UrbanSky_1.jpg" to ProcessedResultType.URBAN_SKY,
            "MaxStars_1.jpeg" to ProcessedResultType.MAX_STARS,
            "RecoveredStars_1.png" to ProcessedResultType.RECOVERED_STARS,
            "BackgroundRemoved_1.jpg" to ProcessedResultType.BACKGROUND_REMOVED,
            "StarsOnlyPreview_1.jpg" to ProcessedResultType.STARS_ONLY_PREVIEW,
            "Edited_1.jpg" to ProcessedResultType.EDITED
        )

        existingResults.forEach { (fileName, expectedType) ->
            assertEquals(fileName, expectedType, processedResultTypeForFileName(fileName))
        }
    }

    private fun reference(name: String) = ImageSourceReference(name, relativePath, null, null)

    private fun processedResult(
        fileName: String,
        key: String = fileName,
        type: ProcessedResultType,
        contentUri: String? = null,
        sizeBytes: Long = 10L,
        isReadable: Boolean = true,
        errorMessage: String? = null,
        createdAtMillis: Long = 1L
    ) = ProcessedResult(
        key = key,
        fileName = fileName,
        type = type,
        createdAtMillis = createdAtMillis,
        sizeBytes = sizeBytes,
        displayPath = "$relativePath$fileName",
        contentUri = contentUri,
        filePath = null,
        relativePath = relativePath,
        isReadable = isReadable,
        errorMessage = errorMessage
    )

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
