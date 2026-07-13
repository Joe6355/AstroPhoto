package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CropBatchFailure(val fileName: String, val reason: String)

data class CropBatchResult(
    val processed: Int,
    val skipped: Int,
    val failed: Int,
    val failures: List<CropBatchFailure> = emptyList()
)

class CroppedFramesRepository(private val context: Context) {
    suspend fun loadManifest(session: SessionSummary): CropManifest = withContext(Dispatchers.IO) {
        runCatching { readManifest(session)?.let(CropManifest::decode) ?: CropManifest() }
            .getOrDefault(CropManifest())
    }

    fun records(
        manifest: CropManifest,
        frames: List<SessionFrame>
    ): List<CroppedFrameRecord> = resolveCroppedFrameRecords(manifest, frames)

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
            val validatedRect = normalizedRect.validated()
            val rect = validatedRect.toPixelRect(bitmap.width, bitmap.height)
            val currentManifest = readManifest(session)?.let(CropManifest::decode) ?: CropManifest()
            val previousCropName = currentManifest.find(original.key)?.croppedFileName
            val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
            try {
                var savedEntry: CropManifestEntry? = null
                persistCropAfterVerifiedWrite(
                    writeImage = {
                        stageBitmapReplacement(
                            session,
                            deterministicCropFileName(original.key),
                            cropped,
                            previousCropName
                        )
                    },
                    updateManifest = { stored ->
                        val entry = CropManifestEntry(
                            originalKey = original.key,
                            originalFileName = original.fileName,
                            croppedFileName = stored.fileName,
                            normalizedRect = validatedRect,
                            pixelRect = rect,
                            originalWidth = bitmap.width,
                            originalHeight = bitmap.height,
                            croppedWidth = rect.width,
                            croppedHeight = rect.height,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                        val manifest = currentManifest.replace(entry)
                        writeManifestAtomically(session, manifest.encode())
                        savedEntry = entry
                    },
                    commitImage = StagedStoredFile::commit,
                    rollbackImage = StagedStoredFile::rollback
                )
                checkNotNull(savedEntry)
            } finally {
                cropped.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun applyToAll(
        session: SessionSummary,
        originals: List<SessionFrame>,
        normalizedRect: NormalizedCropRect
    ): CropBatchResult = withContext(Dispatchers.IO) {
        processCropBatch(originals) { frame ->
            saveCrop(session, frame, normalizedRect)
        }
    }

    suspend fun deleteCrop(session: SessionSummary, entry: CropManifestEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stagedDelete = stageStoredFileDeletion(session, entry.croppedFileName)
                try {
                    val manifest = (
                        readManifest(session)?.let(CropManifest::decode) ?: CropManifest()
                    ).remove(entry.originalKey)
                    writeManifestAtomically(session, manifest.encode())
                    stagedDelete.commit()
                } catch (error: Throwable) {
                    runCatching(stagedDelete::rollback).onFailure(error::addSuppressed)
                    throw error
                }
            }
        }

    private fun openFrame(frame: SessionFrame) = when {
        frame.contentUri != null -> context.contentResolver.openInputStream(Uri.parse(frame.contentUri))
        frame.filePath != null -> File(frame.filePath).inputStream()
        else -> null
    }

    private fun readManifest(session: SessionSummary): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val privateManifest = privateManifestFile(session)
            if (privateManifest.exists()) return privateManifest.readText()
            val legacyUri = findLegacyMediaStoreManifest(session) ?: return null
            return context.contentResolver.openInputStream(legacyUri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }
        return legacyFile(session, MANIFEST_NAME).takeIf(File::exists)?.readText()
    }

    private fun stageBitmapReplacement(
        session: SessionSummary,
        fileName: String,
        bitmap: Bitmap,
        previousFileName: String?
    ): StagedStoredFile {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val temporaryName = "tmp_${System.nanoTime()}_$fileName"
            val uri = insertPending(session, temporaryName)
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                } ?: error("Не удалось записать crop JPEG")
                return stageMediaStoreReplacement(
                    session = session,
                    pending = uri,
                    finalName = fileName,
                    requireExactName = false,
                    existingName = previousFileName ?: fileName
                )
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val target = legacyFile(session, fileName)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.fd.sync()
            }
            val previous = previousFileName?.let { legacyFile(session, it) } ?: target
            return stageLegacyReplacement(temporary, target, previous)
        } finally {
            temporary.delete()
        }
    }

    private fun writeManifestAtomically(session: SessionSummary, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = privateManifestFile(session)
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temporary).bufferedWriter().use { writer ->
                    writer.write(content)
                }
                moveReplacing(temporary, target)
            } finally {
                temporary.delete()
            }
            return
        }
        val target = legacyFile(session, MANIFEST_NAME)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).bufferedWriter().use { it.write(content) }
            stageLegacyReplacement(temporary, target, target).commit()
        } finally {
            temporary.delete()
        }
    }

    private fun insertPending(session: SessionSummary, name: String): Uri =
        context.contentResolver.insert(
            cropImagesCollection(),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, mediaStorePath(session))
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        ) ?: error("Не удалось создать derived crop file")

    private fun publishPending(uri: Uri, finalName: String) {
        val updated = context.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
                put(MediaStore.Images.Media.IS_PENDING, 0)
            },
            null,
            null
        )
        check(updated == 1) { "Не удалось опубликовать derived crop file" }
    }

    private fun stageMediaStoreReplacement(
        session: SessionSummary,
        pending: Uri,
        finalName: String,
        requireExactName: Boolean,
        existingName: String
    ): StagedStoredFile {
        val existing = findMediaStoreFile(session, existingName)
        val backupName = existing?.let { "old_${System.nanoTime()}_$finalName" }
        if (existing != null && backupName != null) renameMediaStoreFile(existing, backupName)
        try {
            publishPending(pending, finalName)
            val saved = storedMediaFile(pending)
                ?: error("Не удалось проверить derived crop file")
            check(saved.relativePath == mediaStorePath(session)) {
                "Derived crop сохранён вне папки Cropped"
            }
            check(saved.sizeBytes > 0L) { "Derived crop file пуст" }
            check(canReopenImage(pending)) { "Derived crop JPEG не открывается после сохранения" }
            if (requireExactName) {
                check(saved.displayName == finalName) {
                    "MediaStore изменил имя служебного файла"
                }
            }
            return object : StagedStoredFile {
                override val fileName = saved.displayName

                override fun commit() {
                    existing?.let { old ->
                        runCatching { context.contentResolver.delete(old, null, null) }
                            .onFailure { error ->
                                Log.w("AstroPhotoCrop", "Failed to remove replaced crop backup", error)
                            }
                    }
                }

                override fun rollback() {
                    context.contentResolver.delete(pending, null, null)
                    if (existing != null) renameMediaStoreFile(existing, existingName)
                }
            }
        } catch (error: Throwable) {
            context.contentResolver.delete(pending, null, null)
            if (existing != null) {
                runCatching { renameMediaStoreFile(existing, existingName) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun renameMediaStoreFile(uri: Uri, name: String) {
        val updated = context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, name) },
            null,
            null
        )
        check(updated == 1) { "Не удалось переименовать derived crop file" }
    }

    private fun findMediaStoreFile(session: SessionSummary, name: String): Uri? {
        val collection = cropImagesCollection()
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(name, mediaStorePath(session)),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    private fun storedMediaFile(uri: Uri): StoredMediaFile? =
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            StoredMediaFile(
                displayName = cursor.getString(0).orEmpty(),
                relativePath = cursor.getString(1).orEmpty(),
                sizeBytes = cursor.getLong(2)
            )
        }

    private fun stageStoredFileDeletion(
        session: SessionSummary,
        name: String
    ): StagedDeletion {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val existing = findMediaStoreFile(session, name) ?: return StagedDeletion.None
            renameMediaStoreFile(existing, "deleted_${System.nanoTime()}_$name")
            return object : StagedDeletion {
                override fun commit() {
                    runCatching { context.contentResolver.delete(existing, null, null) }
                        .onFailure { error ->
                            Log.w("AstroPhotoCrop", "Failed to remove deleted crop backup", error)
                        }
                }

                override fun rollback() {
                    renameMediaStoreFile(existing, name)
                }
            }
        }
        val target = legacyFile(session, name)
        if (!target.exists()) return StagedDeletion.None
        val backup = File(target.parentFile, ".deleted.${System.nanoTime()}.${target.name}")
        moveReplacing(target, backup)
        return object : StagedDeletion {
            override fun commit() {
                backup.delete()
            }

            override fun rollback() {
                if (backup.exists()) moveReplacing(backup, target)
            }
        }
    }

    private fun stageLegacyReplacement(
        source: File,
        target: File,
        previous: File
    ): StagedStoredFile {
        val backup = previous.takeIf(File::exists)?.let {
            File(previous.parentFile, ".old.${System.nanoTime()}.${previous.name}")
        }
        if (backup != null) moveReplacing(previous, backup)
        try {
            moveReplacing(source, target)
            check(target.exists() && target.isFile && target.length() > 0L) {
                "Derived crop file не был сохранён"
            }
        } catch (error: Throwable) {
            target.delete()
            if (backup?.exists() == true) {
                runCatching { moveReplacing(backup, previous) }.onFailure(error::addSuppressed)
            }
            throw error
        }
        return object : StagedStoredFile {
            override val fileName = target.name

            override fun commit() {
                backup?.delete()
            }

            override fun rollback() {
                target.delete()
                if (backup?.exists() == true) moveReplacing(backup, previous)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(session: SessionSummary, name: String): File {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return File(pictures, "AstroPhoto/${session.folderName}/Lights/Cropped/$name")
    }

    private fun mediaStorePath(session: SessionSummary) = cropRelativePath(session.folderName)

    private fun privateManifestFile(session: SessionSummary): File = File(
        context.filesDir,
        "crop_manifests/${session.folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.tsv"
    )

    private fun canReopenImage(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
    }.getOrDefault(false)

    private fun findLegacyMediaStoreManifest(session: SessionSummary): Uri? {
        val collection = MediaStore.Files.getContentUri("external")
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
                arrayOf(MANIFEST_NAME, mediaStorePath(session)),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        }.getOrNull()
    }

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

    private data class StoredMediaFile(
        val displayName: String,
        val relativePath: String,
        val sizeBytes: Long
    )

    private interface StagedStoredFile {
        val fileName: String
        fun commit()
        fun rollback()
    }

    private interface StagedDeletion {
        fun commit()
        fun rollback()

        data object None : StagedDeletion {
            override fun commit() = Unit
            override fun rollback() = Unit
        }
    }

    companion object {
        const val MANIFEST_NAME = "crop_manifest.tsv"
    }
}

internal fun cropImagesCollection(): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

internal fun cropRelativePath(sessionFolderName: String): String =
    "Pictures/AstroPhoto/$sessionFolderName/Lights/Cropped/"
