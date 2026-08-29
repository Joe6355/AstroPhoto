package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionTimelapseExporterDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val folderName = "timelapse_test_${UUID.randomUUID()}"
    private var resultUri: Uri? = null

    @After
    fun cleanUp() {
        resultUri?.let { context.contentResolver.delete(it, null, null) }
        val collection = MediaStore.Files.getContentUri("external")
        val basePath = "${Environment.DIRECTORY_PICTURES}/AstroPhoto/$folderName/"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$basePath%"),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                context.contentResolver.delete(
                    ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    null,
                    null
                )
            }
        }
    }

    @Test
    fun exportsPlayableMp4WithCorrectOrientation() = runBlocking {
        repeat(3) { index -> insertTestJpeg(index + 1) }
        val session = SessionSummary(
            folderName = folderName,
            sessionName = "Device test",
            relativePath = "Pictures/AstroPhoto/$folderName/",
            createdAtMillis = System.currentTimeMillis(),
            lightsJpeg = 3,
            lightsRaw = 0,
            darksJpeg = 0,
            darksRaw = 0,
            totalSizeBytes = 0,
            infoContent = ""
        )

        val result = SessionTimelapseExporter(context).export(session, fps = 2).getOrThrow()
        val uri = assertNotNull(result.contentUri).let { result.contentUri!!.toUri() }
        resultUri = uri
        assertTrue(result.sizeBytes > 0)
        assertEquals(3, result.frameCount)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            assertEquals(
                "320",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            )
            assertEquals(
                "240",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            )
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertNotNull(frame)
            frame!!
            val top = frame.getPixel(frame.width / 2, frame.height / 4)
            val bottom = frame.getPixel(frame.width / 2, frame.height * 3 / 4)
            assertTrue("Верх видео должен оставаться красным", Color.red(top) > Color.blue(top))
            assertTrue("Низ видео должен оставаться синим", Color.blue(bottom) > Color.red(bottom))
            frame.recycle()
        } finally {
            retriever.release()
        }
    }

    private fun insertTestJpeg(number: Int) {
        val resolver = context.contentResolver
        val relativePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/$folderName/Lights/JPEG/"
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "frame_$number.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        ) ?: error("Не удалось создать тестовый JPEG")
        try {
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(320 * 240) { index ->
                if (index / 320 < 120) Color.RED else Color.BLUE
            }
            bitmap.setPixels(pixels, 0, 320, 0, 0, 320, 240)
            resolver.openOutputStream(uri, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            } ?: error("Не удалось записать тестовый JPEG")
            bitmap.recycle()
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
