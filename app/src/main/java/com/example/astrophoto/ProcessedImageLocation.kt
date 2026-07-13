package com.example.astrophoto

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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

internal sealed interface ResolvedImageSource {
    data class Provider(val record: ProviderImageRecord) : ResolvedImageSource
    data class LegacyFile(val absolutePath: String) : ResolvedImageSource

    val pathOrUri: String
        get() = when (this) {
            is Provider -> record.uri
            is LegacyFile -> absolutePath
        }
}

internal interface ImageSourceLookup {
    fun providerByUri(uri: String): ProviderImageRecord?
    fun providerByNameAndPath(displayName: String, relativePath: String): ProviderImageRecord?
    fun readableLegacyFile(absolutePath: String): Boolean
}

internal fun resolveImageSource(
    reference: ImageSourceReference,
    lookup: ImageSourceLookup
): ResolvedImageSource? {
    reference.providerUri
        ?.takeIf(String::isNotBlank)
        ?.let(lookup::providerByUri)
        ?.let { return ResolvedImageSource.Provider(it) }

    val relativePath = normalizeMediaStoreRelativePath(reference.relativePath)
    if (reference.displayName.isNotBlank() && relativePath != null) {
        lookup.providerByNameAndPath(reference.displayName, relativePath)
            ?.let { return ResolvedImageSource.Provider(it) }
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
    private val lookup = object : ImageSourceLookup {
        override fun providerByUri(uri: String): ProviderImageRecord? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
                ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
                ?: return null
            return queryProviderRecord(parsed)
                ?.takeIf { canOpenProvider(parsed) }
        }

        override fun providerByNameAndPath(
            displayName: String,
            relativePath: String
        ): ProviderImageRecord? {
            val collection = processedImagesCollection()
            return runCatching {
                resolver.query(
                    collection,
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.RELATIVE_PATH
                    ),
                    "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                        "${MediaStore.Images.Media.RELATIVE_PATH}=?",
                    arrayOf(displayName, relativePath),
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    ProviderImageRecord(
                        uri = uri.toString(),
                        displayName = cursor.getString(1).orEmpty(),
                        relativePath = cursor.getString(2).orEmpty()
                    ).takeIf { canOpenProvider(uri) }
                }
            }.getOrNull()
        }

        override fun readableLegacyFile(absolutePath: String): Boolean =
            File(absolutePath).let { it.exists() && it.isFile && it.canRead() && it.length() > 0L }
    }

    fun resolve(reference: ImageSourceReference): ResolvedImageSource? =
        resolveImageSource(reference, lookup)

    fun openInputStream(reference: ImageSourceReference): InputStream? =
        when (val source = resolve(reference)) {
            is ResolvedImageSource.Provider ->
                resolver.openInputStream(Uri.parse(source.record.uri))
            is ResolvedImageSource.LegacyFile -> File(source.absolutePath).inputStream()
            null -> null
        }

    private fun queryProviderRecord(uri: Uri): ProviderImageRecord? = runCatching {
        resolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
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
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length != 0L
        } == true
    }.getOrDefault(false)
}

internal fun processedImagesCollection(): Uri =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

private fun normalizeMediaStoreRelativePath(path: String?): String? = path
    ?.replace('\\', '/')
    ?.trim()
    ?.trimStart('/')
    ?.takeIf(String::isNotBlank)
    ?.let { if (it.endsWith('/')) it else "$it/" }

private fun looksLikeAbsoluteFilePath(path: String): Boolean =
    path.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)
