package com.example.astrophoto

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class StorageCaptureFormat {
    JPEG,
    RAW
}

data class StorageSpaceInfo(
    val availableBytes: Long?,
    val estimatedBytes: Long = 0L,
    val averageFrameBytes: Long = 0L,
    val errorMessage: String? = null
) {
    val criticallyLow: Boolean
        get() = availableBytes != null && availableBytes < CRITICAL_BYTES

    val mayBeInsufficient: Boolean
        get() = availableBytes != null &&
            (criticallyLow || estimatedBytes > availableBytes)

    companion object {
        const val CRITICAL_BYTES = 500L * 1024L * 1024L
    }
}

class StorageSpaceChecker(private val context: Context) {
    suspend fun readAvailableSpace(): StorageSpaceInfo = withContext(Dispatchers.IO) {
        runCatching {
            val storagePath = Environment.getExternalStorageDirectory()
            val statFs = StatFs(storagePath.absolutePath)
            StorageSpaceInfo(availableBytes = statFs.availableBytes.coerceAtLeast(0L))
        }.getOrElse {
            StorageSpaceInfo(
                availableBytes = null,
                errorMessage = "Не удалось определить свободное место"
            )
        }
    }

    suspend fun estimate(
        session: ShootingSession?,
        format: StorageCaptureFormat,
        frameCount: Int
    ): StorageSpaceInfo = withContext(Dispatchers.IO) {
        val available = readAvailableSpace()
        val average = averageFrameSize(session, format)
            ?: if (format == StorageCaptureFormat.RAW) {
                DEFAULT_RAW_BYTES
            } else {
                DEFAULT_JPEG_BYTES
            }
        val estimated = (average.toDouble() * frameCount.coerceAtLeast(1) * 1.2)
            .toLong()
        available.copy(
            estimatedBytes = estimated,
            averageFrameBytes = average
        )
    }

    private fun averageFrameSize(
        session: ShootingSession?,
        format: StorageCaptureFormat
    ): Long? {
        session ?: return null
        val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreFrameSizes(session, format)
        } else {
            legacyFrameSizes(session, format)
        }
        return sizes.takeIf { it.isNotEmpty() }?.average()?.toLong()
    }

    private fun mediaStoreFrameSizes(
        session: ShootingSession,
        format: StorageCaptureFormat
    ): List<Long> {
        val extension = if (format == StorageCaptureFormat.RAW) ".dng" else ".jpg"
        val pathPrefix =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        val sizes = mutableListOf<Long>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            ),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$pathPrefix%"),
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val sizeIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.SIZE
            )
            val pathIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty()
                val size = cursor.getLong(sizeIndex)
                val path = cursor.getString(pathIndex).orEmpty()
                val captureDirectory = path.contains("/Lights/") ||
                    path.contains("/Darks/")
                if (
                    captureDirectory &&
                    name.endsWith(extension, ignoreCase = true) &&
                    size > 0L
                ) {
                    sizes += size
                }
            }
        }
        return sizes
    }

    @Suppress("DEPRECATION")
    private fun legacyFrameSizes(
        session: ShootingSession,
        format: StorageCaptureFormat
    ): List<Long> {
        val extension = if (format == StorageCaptureFormat.RAW) "dng" else "jpg"
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val directory = File(pictures, "AstroPhoto/${session.folderName}")
        return directory.walkTopDown()
            .filter {
                it.isFile &&
                    it.extension.equals(extension, ignoreCase = true) &&
                    (
                        it.path.contains("${File.separator}Lights${File.separator}") ||
                            it.path.contains("${File.separator}Darks${File.separator}")
                        )
            }
            .map { it.length() }
            .filter { it > 0L }
            .toList()
    }

    companion object {
        private const val DEFAULT_RAW_BYTES = 25L * 1024L * 1024L
        private const val DEFAULT_JPEG_BYTES = 5L * 1024L * 1024L
    }
}

fun formatStorageSize(bytes: Long): String {
    val gibibyte = 1024.0 * 1024.0 * 1024.0
    val mebibyte = 1024.0 * 1024.0
    return if (bytes >= gibibyte) {
        String.format(Locale.getDefault(), "%.1f GB", bytes / gibibyte)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", bytes / mebibyte)
    }
}
