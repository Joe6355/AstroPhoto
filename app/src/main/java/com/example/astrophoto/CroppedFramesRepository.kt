package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CropBatchResult(val processed: Int, val skipped: Int, val failed: Int)

class CroppedFramesRepository(private val context: Context) {
    suspend fun loadManifest(session: SessionSummary): CropManifest = withContext(Dispatchers.IO) {
        runCatching { readManifest(session)?.let(CropManifest::decode) ?: CropManifest() }
            .getOrDefault(CropManifest())
    }

    fun records(
        manifest: CropManifest,
        frames: List<SessionFrame>
    ): List<CroppedFrameRecord> {
        val byName = frames.filter { it.category == SessionFrameCategory.CROPPED_JPEG }
            .associateBy { it.fileName }
        return manifest.entries.mapNotNull { entry ->
            byName[entry.croppedFileName]?.let { frame ->
                CroppedFrameRecord(entry, frame.copy(originalKey = entry.originalKey))
            }
        }.sortedBy { it.entry.originalKey }
    }

    suspend fun loadEditorPreview(frame: SessionFrame, maxSize: Int = 2048): Bitmap? =
        withContext(Dispatchers.IO) {
            val dimensions = readOrientedJpegDimensions { openFrame(frame) } ?: return@withContext null
            var sample = 1
            while (dimensions.first / (sample * 2) >= maxSize ||
                dimensions.second / (sample * 2) >= maxSize
            ) {
                sample *= 2
            }
            decodeOrientedJpeg({ openFrame(frame) }, sample)
        }

    suspend fun saveCrop(
        session: SessionSummary,
        original: SessionFrame,
        normalizedRect: NormalizedCropRect
    ): CropManifestEntry = withContext(Dispatchers.IO) {
        require(original.category == SessionFrameCategory.LIGHTS_JPEG) {
            "Only original Light JPEG frames can be cropped"
        }
        val bitmap = decodeOrientedJpeg(openStream = { openFrame(original) })
            ?: error("Не удалось прочитать JPEG: ${original.fileName}")
        try {
            val rect = normalizedRect.toPixelRect(bitmap.width, bitmap.height)
            val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
            val fileName = deterministicCropFileName(original.key)
            try {
                writeBitmapAtomically(session, fileName, cropped)
            } finally {
                cropped.recycle()
            }
            val entry = CropManifestEntry(
                originalKey = original.key,
                originalFileName = original.fileName,
                croppedFileName = fileName,
                normalizedRect = normalizedRect.validated(),
                pixelRect = rect,
                originalWidth = bitmap.width,
                originalHeight = bitmap.height,
                croppedWidth = rect.width,
                croppedHeight = rect.height,
                updatedAtMillis = System.currentTimeMillis()
            )
            val manifest = (readManifest(session)?.let(CropManifest::decode) ?: CropManifest())
                .replace(entry)
            writeManifestAtomically(session, manifest.encode())
            entry
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun applyToAll(
        session: SessionSummary,
        originals: List<SessionFrame>,
        normalizedRect: NormalizedCropRect
    ): CropBatchResult = withContext(Dispatchers.IO) {
        var processed = 0
        var skipped = 0
        var failed = 0
        originals.forEach { frame ->
            if (frame.category != SessionFrameCategory.LIGHTS_JPEG) {
                skipped++
            } else {
                runCatching { saveCrop(session, frame, normalizedRect) }
                    .onSuccess { processed++ }
                    .onFailure { failed++ }
            }
        }
        CropBatchResult(processed, skipped, failed)
    }

    suspend fun deleteCrop(session: SessionSummary, entry: CropManifestEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                deleteStoredFile(session, entry.croppedFileName)
                val manifest = (readManifest(session)?.let(CropManifest::decode) ?: CropManifest())
                    .remove(entry.originalKey)
                writeManifestAtomically(session, manifest.encode())
            }
        }

    private fun openFrame(frame: SessionFrame) = when {
        frame.contentUri != null -> context.contentResolver.openInputStream(Uri.parse(frame.contentUri))
        frame.filePath != null -> File(frame.filePath).inputStream()
        else -> null
    }

    private fun readManifest(session: SessionSummary): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findMediaStoreFile(session, MANIFEST_NAME) ?: return null
            return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }
        return legacyFile(session, MANIFEST_NAME).takeIf(File::exists)?.readText()
    }

    private fun writeBitmapAtomically(session: SessionSummary, fileName: String, bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val temporaryName = "tmp_${System.nanoTime()}_$fileName"
            val uri = insertPending(session, temporaryName, "image/jpeg")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                } ?: error("Не удалось записать crop JPEG")
                replacePending(session, uri, fileName)
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
            return
        }
        val target = legacyFile(session, fileName)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.fd.sync()
            }
            moveReplacing(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun writeManifestAtomically(session: SessionSummary, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val temporaryName = "tmp_${System.nanoTime()}_$MANIFEST_NAME"
            val uri = insertPending(session, temporaryName, "text/tab-separated-values")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                    it.write(content)
                } ?: error("Не удалось записать crop manifest")
                replacePending(session, uri, MANIFEST_NAME)
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
            return
        }
        val target = legacyFile(session, MANIFEST_NAME)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).bufferedWriter().use { it.write(content) }
            moveReplacing(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun insertPending(session: SessionSummary, name: String, mime: String): Uri =
        context.contentResolver.insert(
            MediaStore.Files.getContentUri("external"),
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
                put(MediaStore.Files.FileColumns.MIME_TYPE, mime)
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, mediaStorePath(session))
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
        ) ?: error("Не удалось создать derived crop file")

    private fun publishPending(uri: Uri, finalName: String) {
        val updated = context.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, finalName)
                put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            },
            null,
            null
        )
        check(updated == 1) { "Не удалось опубликовать derived crop file" }
    }

    private fun replacePending(session: SessionSummary, pending: Uri, finalName: String) {
        val existing = findMediaStoreFile(session, finalName)
        val backupName = existing?.let { "old_${System.nanoTime()}_$finalName" }
        if (existing != null && backupName != null) renameMediaStoreFile(existing, backupName)
        try {
            publishPending(pending, finalName)
            existing?.let { context.contentResolver.delete(it, null, null) }
        } catch (error: Throwable) {
            if (existing != null) renameMediaStoreFile(existing, finalName)
            throw error
        }
    }

    private fun renameMediaStoreFile(uri: Uri, name: String) {
        val updated = context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Files.FileColumns.DISPLAY_NAME, name) },
            null,
            null
        )
        check(updated == 1) { "Не удалось переименовать derived crop file" }
    }

    private fun findMediaStoreFile(session: SessionSummary, name: String): Uri? {
        val collection = MediaStore.Files.getContentUri("external")
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf(name, mediaStorePath(session)),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    private fun deleteStoredFile(session: SessionSummary, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findMediaStoreFile(session, name)?.let { context.contentResolver.delete(it, null, null) }
        } else {
            legacyFile(session, name).delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(session: SessionSummary, name: String): File {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return File(pictures, "AstroPhoto/${session.folderName}/Lights/Cropped/$name")
    }

    private fun mediaStorePath(session: SessionSummary) =
        "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/Lights/Cropped/"

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MANIFEST_NAME = "crop_manifest.tsv"
    }
}
