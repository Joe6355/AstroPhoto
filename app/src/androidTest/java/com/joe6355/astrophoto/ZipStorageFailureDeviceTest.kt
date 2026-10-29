package com.joe6355.astrophoto

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Injects errors into its new archive only; never fills storage or edits a prior session. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class ZipStorageFailureDeviceTest {
    @Test fun failedAndCancelledArchivesAreRemovedAndSameSessionCanRetry() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zipStorageAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date(now))
        val folder = "Session_${stamp}_QA_ZIP_STORAGE"
        val info = "sessionName: QA_ZIP_STORAGE\nnote: own ZIP storage failure audit\n"
        val session = SessionSummary(folder,"QA_ZIP_STORAGE","Pictures/AstroPhoto/$folder/",now,1,0,0,info)
        val jpeg = randomJpeg()
        assertTrue(jpeg.size > 128 * 1024)
        val sourceName = "storage_source.jpg"
        val resolver = context.contentResolver
        val sourceUri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,sourceName)
                put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AstroPhoto/$folder/Lights/JPEG/")
                put(MediaStore.Images.Media.IS_PENDING,1)
            }))
        try {
            requireNotNull(resolver.openOutputStream(sourceUri,"w")).use { it.write(jpeg) }
            check(resolver.update(sourceUri,ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING,0)
            },null,null) == 1)
        } catch (error: Throwable) {
            resolver.delete(sourceUri,null,null)
            throw error
        }
        SessionInfoStore(context).update(folder,info) { info }
        val metadata = File(context.filesDir,"session-info/$folder.txt")
        val infoBefore = metadata.readBytes()
        val rowsBefore = exportRows(context)
        val failures = JSONArray()
        for (mode in listOf("one-shot-no-space","persistent-no-space","cancel")) {
            var fault: FailingArchiveOutput? = null
            val exporter = SessionZipExporter(context) { output ->
                FailingArchiveOutput(output,mode).also { fault = it }
            }
            if (mode == "cancel") {
                try {
                    exporter.export(session)
                    fail("Cancellation must propagate")
                } catch (expected: CancellationException) {
                    assertEquals("QA archive cancellation",expected.message)
                }
            } else {
                val result = exporter.export(session)
                assertTrue(result.isFailure)
                assertEquals(ZIP_STORAGE_FULL_MESSAGE,result.exceptionOrNull()!!.message)
            }
            val injected = requireNotNull(fault)
            assertTrue(injected.triggered)
            assertEquals(40 * 1024,injected.acceptedBytes)
            assertEquals("Neither pending nor published failed ZIP may remain",rowsBefore,exportRows(context))
            assertArrayEquals(infoBefore,metadata.readBytes())
            assertArrayEquals(jpeg,requireNotNull(resolver.openInputStream(sourceUri)).use { it.readBytes() })
            requireNotNull(sessionAccess.acquire(folder)).close()
            failures.put(JSONObject().put("mode",mode).put("bytesBeforeFailure",injected.acceptedBytes)
                .put("noPendingOrPublishedArchive",true).put("sourceAndMetadataUnchanged",true))
        }
        val saved = SessionZipExporter(context).export(session).getOrThrow()
        assertEquals(0,saved.skippedFiles)
        assertEquals(3,saved.addedFiles)
        assertTrue(saved.sessionInfoUpdated)
        val archiveUri = requireNotNull(saved.contentUri).let(android.net.Uri::parse)
        val entries = linkedMapOf<String,ByteArray>()
        ZipInputStream(requireNotNull(resolver.openInputStream(archiveUri))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertArrayEquals(jpeg,entries.getValue("QA_ZIP_STORAGE/Lights/JPEG/$sourceName"))
        assertArrayEquals(infoBefore,entries.getValue("QA_ZIP_STORAGE/session_info.txt"))
        assertTrue(entries.containsKey("QA_ZIP_STORAGE/README.txt"))
        val newRows = exportRows(context) - rowsBefore.keys
        assertEquals(1,newRows.size)
        assertTrue(newRows.values.single().endsWith("|0"))
        val hashes = JSONObject()
        entries.forEach { (name,bytes) -> hashes.put(name,sha256(bytes)) }
        val proof = JSONObject().put("folder",folder).put("sourceFile",sourceName)
            .put("sourceUri",sourceUri.toString()).put("sourceBytes",jpeg.size).put("sourceSha256",sha256(jpeg))
            .put("failures",failures).put("retrySuccessful",true).put("archiveFile",saved.fileName)
            .put("archiveUri",saved.contentUri).put("archiveLocation",saved.location)
            .put("archiveBytes",saved.sizeBytes).put("entrySha256",hashes)
        val directory = File(context.cacheDir,"zip_storage_20261030").apply { mkdirs() }
        File(directory,"proof.json").writeText(proof.toString(2))
    }

    private fun exportRows(context: Context): Map<Long,String> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"relative_path=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,arrayOf("Documents/AstroPhoto/Exports/"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE)
        }
        val result = linkedMapOf<Long,String>()
        context.contentResolver.query(MediaStore.Files.getContentUri("external"),
            arrayOf("_id","_display_name","is_pending"),args,null)!!.use { cursor ->
            while (cursor.moveToNext()) result[cursor.getLong(0)] = "${cursor.getString(1)}|${cursor.getInt(2)}"
        }
        return result
    }

    private fun randomJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(512,384,Bitmap.Config.ARGB_8888)
        return try {
            val random = Random(30102026L)
            val pixels = IntArray(512 * 384) { 0xff000000.toInt() or random.nextInt(0x1000000) }
            bitmap.setPixels(pixels,0,512,0,0,512,384)
            ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))
            }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}

private class FailingArchiveOutput(private val output: OutputStream,private val mode: String): OutputStream() {
    var acceptedBytes = 0
        private set
    var triggered = false
        private set

    override fun write(value: Int) = write(byteArrayOf(value.toByte()),0,1)

    override fun write(bytes: ByteArray,offset: Int,length: Int) {
        if (length == 0) return
        if (triggered && mode == "one-shot-no-space") {
            output.write(bytes,offset,length)
            return
        }
        val allowed = minOf(length,(40 * 1024 - acceptedBytes).coerceAtLeast(0))
        if (allowed > 0) {
            output.write(bytes,offset,allowed)
            acceptedBytes += allowed
        }
        if (allowed < length) {
            triggered = true
            if (mode == "cancel") throw CancellationException("QA archive cancellation")
            throw IOException("write failed: ENOSPC (No space left on device)")
        }
    }

    override fun flush() = output.flush()
    override fun close() = output.close()
}
