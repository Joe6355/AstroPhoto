package com.example.astrophoto

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.InputStream

data class ImageSourceReference(
    val displayName: String,
    val relativePath: String?,
    val providerUri: String?,
    val legacyFilePath: String?
)

internal data class ProviderImageRecord(
    val uri: String,
    val displayName: String,
    val relativePath: String
)

internal enum class ProviderOrigin(val logName: String) {
    STORED("stored"),
    IMAGES("images"),
    FILES("files")
}

internal sealed interface ResolvedImageSource {
    data class Provider(
        val record: ProviderImageRecord,
        val origin: ProviderOrigin
    ) : ResolvedImageSource

    data class LegacyFile(val absolutePath: String) : ResolvedImageSource

    val pathOrUri: String
        get() = when (this) {
            is Provider -> record.uri
            is LegacyFile -> absolutePath
        }
}

internal interface ImageSourceLookup {
    fun providerByUri(uri: String): ProviderImageRecord?
    fun imagesProviderByNameAndPath(
        displayName: String,
        relativePath: String
    ): ProviderImageRecord?
    fun filesProviderByNameAndPath(
        displayName: String,
        relativePath: String
    ): ProviderImageRecord?
    fun readableLegacyFile(absolutePath: String): Boolean
}

internal fun resolveImageSource(
    reference: ImageSourceReference,
    lookup: ImageSourceLookup
): ResolvedImageSource? {
    reference.providerUri
        ?.takeIf(String::isNotBlank)
        ?.let(lookup::providerByUri)
        ?.let { return ResolvedImageSource.Provider(it, ProviderOrigin.STORED) }

    val relativePath = normalizeMediaStoreRelativePath(reference.relativePath)
    if (reference.displayName.isNotBlank() && relativePath != null) {
        lookup.imagesProviderByNameAndPath(reference.displayName, relativePath)
            ?.let { return ResolvedImageSource.Provider(it, ProviderOrigin.IMAGES) }
        lookup.filesProviderByNameAndPath(reference.displayName, relativePath)
            ?.let { return ResolvedImageSource.Provider(it, ProviderOrigin.FILES) }
    }

    val legacyPath = reference.legacyFilePath
        ?.takeIf(::looksLikeAbsoluteFilePath)
        ?.takeIf(lookup::readableLegacyFile)
    return legacyPath?.let(ResolvedImageSource::LegacyFile)
}

internal fun resolveImagePair(
    first: ImageSourceReference,
    second: ImageSourceReference,
    lookup: ImageSourceLookup
): Pair<ResolvedImageSource, ResolvedImageSource>? {
    val resolvedFirst = resolveImageSource(first, lookup) ?: return null
    val resolvedSecond = resolveImageSource(second, lookup) ?: return null
    return resolvedFirst to resolvedSecond
}

internal fun logicalRelativePath(
    displayPath: String,
    displayName: String
): String? {
    if (displayPath.isBlank() || displayName.isBlank()) return null
    val normalized = displayPath.replace('\\', '/').trim().trimStart('/')
    val suffix = "/$displayName"
    if (!normalized.endsWith(suffix, ignoreCase = true)) return null
    val parent = normalized.dropLast(displayName.length).trimEnd('/') + "/"
    return parent.takeIf {
        it.startsWith("Pictures/", ignoreCase = true) ||
            it.startsWith("DCIM/", ignoreCase = true)
    }
}

internal fun imageSourceReference(
    displayName: String,
    displayPath: String,
    relativePath: String?,
    providerUri: String?,
    legacyFilePath: String?
) = ImageSourceReference(
    displayName = displayName,
    relativePath = normalizeMediaStoreRelativePath(relativePath)
        ?: logicalRelativePath(displayPath, displayName),
    providerUri = providerUri,
    legacyFilePath = legacyFilePath
)

internal class ProcessedImageSourceResolver(context: Context) {
    private val resolver = context.contentResolver

    fun resolve(reference: ImageSourceReference): ResolvedImageSource? {
        val diagnostics = ResolutionDiagnostics()
        val resolved = resolveImageSource(reference, lookup(diagnostics))
        val resolvedCollection = when (resolved) {
            is ResolvedImageSource.Provider -> resolved.origin.logName
            is ResolvedImageSource.LegacyFile -> "legacy"
            null -> "missing"
        }
        Log.d(
            RESULT_LOG_TAG,
            "resultName=${reference.displayName} storedUri=${reference.providerUri.orEmpty()} " +
                "relativePath=${reference.relativePath.orEmpty()} " +
                "imagesRowsFound=${diagnostics.imagesRowsFound} " +
                "filesRowsFound=${diagnostics.filesRowsFound} " +
                "resolvedUri=${resolved?.pathOrUri.orEmpty()} " +
                "resolvedCollection=$resolvedCollection " +
                "failureReason=${diagnostics.failureReason.orEmpty()}"
        )
        return resolved
    }

    fun openInputStream(reference: ImageSourceReference): InputStream? {
        val source = resolve(reference)
        return runCatching {
            when (source) {
                is ResolvedImageSource.Provider ->
                    resolver.openInputStream(Uri.parse(source.record.uri))
                is ResolvedImageSource.LegacyFile -> File(source.absolutePath).inputStream()
                null -> null
            }
        }.onSuccess { stream ->
            Log.d(
                RESULT_LOG_TAG,
                "resultName=${reference.displayName} openInputStream=" +
                    if (stream != null) "success" else "failure"
            )
        }.onFailure { error ->
            Log.w(
                RESULT_LOG_TAG,
                "resultName=${reference.displayName} openInputStream=failure " +
                    "failureReason=${error.message.orEmpty()}"
            )
        }.getOrNull()
    }

    private fun lookup(diagnostics: ResolutionDiagnostics) = object : ImageSourceLookup {
        override fun providerByUri(uri: String): ProviderImageRecord? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
                ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
                ?: return null
            if (!canOpenProvider(parsed)) {
                diagnostics.failureReason = "stored URI could not be opened"
                return null
            }
            return queryProviderRecord(parsed) ?: ProviderImageRecord(
                uri = parsed.toString(),
                displayName = "",
                relativePath = ""
            )
        }

        override fun imagesProviderByNameAndPath(
            displayName: String,
            relativePath: String
        ): ProviderImageRecord? = queryByNameAndPath(
            collection = processedImagesCollection(),
            displayName = displayName,
            relativePath = relativePath,
            idColumn = MediaStore.Images.Media._ID,
            nameColumn = MediaStore.Images.Media.DISPLAY_NAME,
            pathColumn = MediaStore.Images.Media.RELATIVE_PATH,
            onRowsFound = { diagnostics.imagesRowsFound = it },
            onFailure = { diagnostics.failureReason = it }
        )

        override fun filesProviderByNameAndPath(
            displayName: String,
            relativePath: String
        ): ProviderImageRecord? = queryByNameAndPath(
            collection = MediaStore.Files.getContentUri("external"),
            displayName = displayName,
            relativePath = relativePath,
            idColumn = MediaStore.Files.FileColumns._ID,
            nameColumn = MediaStore.Files.FileColumns.DISPLAY_NAME,
            pathColumn = MediaStore.Files.FileColumns.RELATIVE_PATH,
            onRowsFound = { diagnostics.filesRowsFound = it },
            onFailure = { diagnostics.failureReason = it }
        )

        override fun readableLegacyFile(absolutePath: String): Boolean =
            File(absolutePath).let {
                it.exists() && it.isFile && it.canRead() && it.length() > 0L
            }
    }

    private fun queryByNameAndPath(
        collection: Uri,
        displayName: String,
        relativePath: String,
        idColumn: String,
        nameColumn: String,
        pathColumn: String,
        onRowsFound: (Int) -> Unit,
        onFailure: (String) -> Unit
    ): ProviderImageRecord? = runCatching {
        resolver.query(
            collection,
            arrayOf(idColumn, nameColumn, pathColumn),
            "$nameColumn=? AND $pathColumn=?",
            arrayOf(displayName, relativePath),
            null
        )?.use { cursor ->
            onRowsFound(cursor.count)
            if (!cursor.moveToFirst()) return@use null
            val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
            val record = ProviderImageRecord(
                uri = uri.toString(),
                displayName = cursor.getString(1).orEmpty(),
                relativePath = cursor.getString(2).orEmpty()
            )
            if (canOpenProvider(uri)) {
                record
            } else {
                onFailure("$collection row could not be opened")
                null
            }
        }
    }.onFailure { onFailure(it.message.orEmpty()) }.getOrNull()

    private fun queryProviderRecord(uri: Uri): ProviderImageRecord? = runCatching {
        resolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ProviderImageRecord(
                uri = uri.toString(),
                displayName = cursor.getString(0).orEmpty(),
                relativePath = cursor.getString(1).orEmpty()
            )
        }
    }.getOrNull()

    private fun canOpenProvider(uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { true } == true
    }.getOrDefault(false)

    private data class ResolutionDiagnostics(
        var imagesRowsFound: Int = 0,
        var filesRowsFound: Int = 0,
        var failureReason: String? = null
    )

    private companion object {
        const val RESULT_LOG_TAG = "ProcessedResult"
    }
}

internal fun processedImagesCollection(
    collection: ProcessedMediaCollection = ProcessedMediaCollection.IMAGES
): Uri = when (collection) {
    ProcessedMediaCollection.IMAGES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

private fun normalizeMediaStoreRelativePath(path: String?): String? = path
    ?.replace('\\', '/')
    ?.trim()
    ?.trimStart('/')
    ?.takeIf(String::isNotBlank)
    ?.let { if (it.endsWith('/')) it else "$it/" }

private fun looksLikeAbsoluteFilePath(path: String): Boolean =
    path.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)
