package com.joe6355.astrophoto

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StorageSpaceCheckerDeviceTest {
    @Test fun neighbouringSessionMustNotChangeFrameSizeEstimate() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val folder = "Session_20261027_000000_QA_space_${suffix}_a_b"
        val neighbour = folder.replace("_a_b", "_aXb")
        val created = mutableListOf<Uri>()
        fun jpeg(destination: String, size: Int): Long {
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "space_$suffix.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AstroPhoto/$destination/Lights/JPEG/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
            created += uri
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                val random = Random(271026)
                val pixels = IntArray(size * size) { 0xff000000.toInt() or random.nextInt(0x1000000) }
                bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
                requireNotNull(resolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                }
            } finally {
                bitmap.recycle()
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return requireNotNull(resolver.openInputStream(uri)).use { it.readBytes().size.toLong() }
        }
        try {
            val actualBytes = jpeg(folder, 8)
            val neighbourBytes = jpeg(neighbour, 256)
            assertTrue(neighbourBytes > actualBytes * 10)
            val session = ShootingSession("QA space", folder, System.currentTimeMillis(), "QA only")
            assertEquals(actualBytes, StorageSpaceChecker(context)
                .estimate(session, StorageCaptureFormat.JPEG, 3).averageFrameBytes)
        } finally {
            // Only the exact two URIs created here; never a user session or a wildcard deletion.
            created.forEach { resolver.delete(it, null, null) }
        }
    }
}
