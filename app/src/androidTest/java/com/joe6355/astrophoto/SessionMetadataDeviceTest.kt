package com.joe6355.astrophoto

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in scoped-storage round trip; only creates/deletes its own tiny QA files. */
@RunWith(AndroidJUnit4::class)
class SessionMetadataDeviceTest {
    @Test fun metadataSurvivesRefreshRenameAndZipWithoutTouchingNeighbourSession() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assumeTrue("Explicit metadata audit only",
            InstrumentationRegistry.getArguments().getString("metadataAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val store = SessionInfoStore(context)
        val sessions = ShootingSessionStore(context)
        val activeBefore = sessions.load()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val folder = "Session_QA_metadata_$suffix"
        // '_' in SQL LIKE must not cause this neighbouring folder to be moved/exported/deleted.
        val neighbour = folder.replaceFirst("_", "X")
        val touchedUris = mutableListOf<Uri>()
        val touchedFolders = mutableSetOf(folder, neighbour)
        fun createJpeg(destination: String, name: String): Uri {
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AstroPhoto/$destination/Lights/JPEG/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
            touchedUris += uri
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff405060.toInt())
                requireNotNull(resolver.openOutputStream(uri)).use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                }
            } finally { bitmap.recycle() }
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null))
            return uri
        }
        fun assertReadable(uri: Uri) {
            requireNotNull(resolver.openInputStream(uri)).use { assertTrue(it.read() >= 0) }
        }
        try {
            val originalUri = createJpeg(folder, "original.jpg")
            val neighbourUri = createJpeg(neighbour, "neighbour.jpg")
            val session = ShootingSession("QA metadata $suffix", folder, System.currentTimeMillis(), "QA only")
            val metadata = SessionCaptureMetadata("test", 400, 1_000_000_000L, "infinity", "JPEG")
            sessions.writeSessionInfo(session, metadata).getOrThrow()
            store.update(folder) { it + "outputFile: Processed/old.png\n" }
            sessions.writeSessionInfo(session.copy(lightFrames = 1), metadata).getOrThrow()
            val beforeRename = requireNotNull(store.read(folder))
            assertTrue(beforeRename.contains("outputFile: Processed/old.png"))
            val repository = SessionBrowserRepository(context)
            val summary = repository.loadSessions().single { it.folderName == folder }
            assertEquals(session.sessionName, summary.sessionName)
            assertEquals(1, summary.lightsJpeg)

            val archive = SessionZipExporter(context).export(summary).getOrThrow()
            val archiveUri = Uri.parse(requireNotNull(archive.contentUri)).also { touchedUris += it }
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(requireNotNull(resolver.openInputStream(archiveUri))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    assertFalse("Duplicate ZIP entry", entries.containsKey(entry.name))
                    entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            assertEquals(1, entries.keys.count { it.endsWith("/session_info.txt") })
            assertEquals(beforeRename, entries.entries.single { it.key.endsWith("/session_info.txt") }
                .value.toString(Charsets.UTF_8))
            assertTrue(entries.keys.any { it.endsWith("/Lights/JPEG/original.jpg") })
            assertFalse(entries.keys.any { it.endsWith("neighbour.jpg") })

            val manager = SessionFileManager(context)
            val renamed = manager.renameSession(summary, "QA_renamed_$suffix").getOrThrow()
            touchedFolders += renamed.newFolderName
            assertTrue(renamed.metadataUpdated)
            assertNull(store.read(folder, readLegacyIfMissing = false))
            assertTrue(requireNotNull(store.read(renamed.newFolderName)).contains("outputFile: Processed/old.png"))
            assertReadable(originalUri)
            assertReadable(neighbourUri)
            val renamedSummary = repository.loadSessions().single { it.folderName == renamed.newFolderName }
            assertEquals(1, renamedSummary.lightsJpeg)
            assertEquals(renamed.newSessionName, renamedSummary.sessionName)
            val deleted = manager.deleteSession(renamedSummary).getOrThrow()
            assertEquals(0, deleted.failedFiles)
            assertFalse(deleted.activeSessionCleared)
            assertNull(store.read(renamed.newFolderName, readLegacyIfMissing = false))
            assertReadable(neighbourUri)
            assertEquals(activeBefore, sessions.load())
        } finally {
            // Exact URIs and unique metadata names created by this test, never a user's session tree.
            touchedUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            touchedFolders.forEach { store.delete(it) }
        }
    }
}
