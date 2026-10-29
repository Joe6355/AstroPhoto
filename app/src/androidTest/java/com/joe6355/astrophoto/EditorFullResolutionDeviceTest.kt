package com.joe6355.astrophoto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.io.IOException
import com.joe6355.astrophoto.processing.jpeg.v2.output.PngImageSource
import com.joe6355.astrophoto.processing.jpeg.v2.output.PngStreamEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit QA-only editor audit; never edits or deletes an existing session. */
@RunWith(AndroidJUnit4::class)
class EditorFullResolutionDeviceTest {
    @Test fun recordPreviousFullSizeRendering() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("editorReference") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == EDITOR_QA_PACKAGE)
        val directory = File(context.cacheDir, EDITOR_AUDIT_DIRECTORY).apply { mkdirs() }
        val output = File(directory, "reference.json")
        require(!output.exists()) { "Do not overwrite the original renderer reference" }
        val fixture = Bitmap.createBitmap(513, 419, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(fixture.width * fixture.height) { i ->
                val x = i % fixture.width
                val y = i / fixture.width
                val alpha = if ((x+y) % 17 == 0) 113 else 255
                (alpha shl 24) or (((x*73+y*17) and 255) shl 16) or
                    (((x*11+y*151) and 255) shl 8) or ((x*19+y*29+x*y) and 255)
            }
            fixture.setPixels(pixels, 0, fixture.width, 0, 0, fixture.width, fixture.height)
            for ((name, format) in listOf("source.png" to Bitmap.CompressFormat.PNG,
                                         "source.jpg" to Bitmap.CompressFormat.JPEG)) {
                File(directory, name).outputStream().use { assertTrue(fixture.compress(format, 92, it)) }
            }
        } finally {
            fixture.recycle()
        }
        val editor = ProcessedImageEditor(context)
        val cases = JSONArray()
        for (name in listOf("source.png", "source.jpg")) {
            val original = requireNotNull(BitmapFactory.decodeFile(File(directory, name).path))
            try {
                for (case in editorAuditCases()) {
                    val rendered = editor.renderPreview(original, case.adjustments, case.crop)
                    assertNotNull(rendered)
                    requireNotNull(rendered)
                    try {
                        cases.put(JSONObject().apply {
                            put("source", name)
                            put("case", case.name)
                            put("width", rendered.width)
                            put("height", rendered.height)
                            put("pixelSha256", editorPixelHash(rendered))
                        })
                    } finally {
                        rendered.recycle()
                    }
                }
            } finally {
                original.recycle()
            }
        }
        output.writeText(JSONObject().put("cases", cases).toString(2))
        assertTrue(cases.length() == 8)
    }

    @Test fun streamedExportsMatchAllPreviousPixelsAndProviderSources() = runBlocking {
        val context = editorAuditContext()
        val directory = File(context.cacheDir, EDITOR_AUDIT_DIRECTORY)
        val references = JSONObject(File(directory,"reference.json").readText()).getJSONArray("cases")
        assertEquals(8,references.length())
        val session = editorAuditSession("STREAM")
        val editor = ProcessedImageEditor(context)
        val results = JSONArray()
        var firstSaved: ProcessedResult? = null
        for (i in 0 until references.length()) {
            val reference = references.getJSONObject(i)
            val source = editorFileSource(File(directory,reference.getString("source")))
            val case = editorAuditCases().single { it.name == reference.getString("case") }
            val saved = editor.saveEditedCopy(session,source,case.adjustments,case.crop).getOrThrow()
            assertTrue(saved.sessionInfoUpdated)
            assertEquals(reference.getInt("width"),saved.width)
            assertEquals(reference.getInt("height"),saved.height)
            val result = editorSavedSource(context,session,saved)
            if (firstSaved == null) firstSaved = result
            val decoded = editorDecode(context,result)
            try {
                assertEquals(reference.getString("pixelSha256"),editorPixelHash(decoded))
                results.put(JSONObject().put("file",saved.fileName).put("source",source.fileName)
                    .put("case",case.name).put("width",saved.width).put("height",saved.height)
                    .put("pixelSha256",editorPixelHash(decoded)))
            } finally { decoded.recycle() }
            val original = requireNotNull(BitmapFactory.decodeFile(source.filePath))
            try {
                val preview = requireNotNull(editor.renderPreview(original,case.adjustments,case.crop))
                try { assertEquals(reference.getString("pixelSha256"),editorPixelHash(preview)) }
                finally { preview.recycle() }
            } finally { original.recycle() }
        }
        // Reopen an actual published MediaStore URI, with no legacy file fallback.
        val providerSource = requireNotNull(firstSaved)
        val providerBitmap = editorDecode(context,providerSource)
        val expectedProvider = try {
            requireNotNull(editor.renderPreview(providerBitmap,ImageAdjustments(),CropSettings()))
        } finally { providerBitmap.recycle() }
        try {
            val saved = editor.saveEditedCopy(session,providerSource,ImageAdjustments(),CropSettings()).getOrThrow()
            val decoded = editorDecode(context,editorSavedSource(context,session,saved))
            try { assertEquals(editorPixelHash(expectedProvider),editorPixelHash(decoded)) }
            finally { decoded.recycle() }
        } finally { expectedProvider.recycle() }
        File(directory,"stream-proof.json").writeText(JSONObject().put("folder",session.folderName)
            .put("legacyCases",results).put("providerRoundTripPassed",true).put("outputs",9).toString(2))
    }

    @Test fun largeImageRetainsNativeDimensionsWithSmallDecoderStripes() = runBlocking {
        val context = editorAuditContext()
        val directory = File(context.cacheDir, EDITOR_AUDIT_DIRECTORY)
        val input = File(directory,"large-source.png")
        input.outputStream().use { output ->
            PngStreamEncoder.encode(object : PngImageSource {
                override val width = EDITOR_LARGE_WIDTH
                override val height = EDITOR_LARGE_HEIGHT
                override fun readArgbRow(y: Int,destination: IntArray) {
                    for (x in destination.indices) destination[x] = editorLargeColor(x,y)
                }
            },output)
        }
        val runtime = Runtime.getRuntime()
        val initialJava = runtime.totalMemory()-runtime.freeMemory()
        val initialNative = Debug.getNativeHeapAllocatedSize()
        var maximumJava = initialJava
        var maximumNative = initialNative
        var region: RegionEditorExportSource? = null
        val measured = EditorExportSourceFactory { _,adjustments,crop ->
            val actual = RegionEditorExportSource.open({ input.inputStream() },adjustments,crop)
            region = actual
            object : EditorExportSource by actual {
                override fun readArgbRow(y: Int,destination: IntArray) {
                    actual.readArgbRow(y,destination)
                    maximumJava = maxOf(maximumJava,runtime.totalMemory()-runtime.freeMemory())
                    maximumNative = maxOf(maximumNative,Debug.getNativeHeapAllocatedSize())
                }
            }
        }
        val session = editorAuditSession("LARGE")
        val editor = ProcessedImageEditor(context,measured)
        val adjustments = ImageAdjustments(sharpness=67f)
        val started = SystemClock.elapsedRealtime()
        val saved = editor.saveEditedCopy(session,editorFileSource(input),adjustments,CropSettings()).getOrThrow()
        val elapsed = SystemClock.elapsedRealtime()-started
        assertEquals(EDITOR_LARGE_WIDTH,saved.width)
        assertEquals(EDITOR_LARGE_HEIGHT,saved.height)
        assertTrue(requireNotNull(region).maximumDecodedRows in 1..32)
        val source = editorSavedSource(context,session,saved)
        @Suppress("DEPRECATION")
        val decoder = context.contentResolver.openInputStream(Uri.parse(source.contentUri))!!.use {
            requireNotNull(android.graphics.BitmapRegionDecoder.newInstance(it,false))
        }
        try {
            assertEquals(EDITOR_LARGE_WIDTH,decoder.width)
            assertEquals(EDITOR_LARGE_HEIGHT,decoder.height)
            for ((x,y) in listOf(0 to 0,32 to 32,2048 to 1536,4095 to 3072)) {
                val left=(x-100).coerceIn(0,EDITOR_LARGE_WIDTH-200)
                val top=(y-100).coerceIn(0,EDITOR_LARGE_HEIGHT-200)
                val patch=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
                try {
                    val pixels=IntArray(200*200) { i -> editorLargeColor(left+i%200,top+i/200) }
                    patch.setPixels(pixels,0,200,0,0,200,200)
                    val expected=requireNotNull(ProcessedImageEditor(context).renderPreview(patch,adjustments,CropSettings()))
                    val actual=requireNotNull(decoder.decodeRegion(android.graphics.Rect(x,y,x+1,y+1),
                        BitmapFactory.Options().apply { inSampleSize=1;inPreferredConfig=Bitmap.Config.ARGB_8888 }))
                    try { assertEquals(expected.getPixel(x-left,y-top),actual.getPixel(0,0)) }
                    finally { expected.recycle();actual.recycle() }
                } finally { patch.recycle() }
            }
        } finally { decoder.recycle() }
        File(directory,"large-proof.json").writeText(JSONObject().put("folder",session.folderName)
            .put("file",saved.fileName).put("width",saved.width).put("height",saved.height)
            .put("maximumDecodedRows",requireNotNull(region).maximumDecodedRows)
            .put("elapsedMillis",elapsed).put("sampledPixelsMatch",true)
            .put("observedJavaHeapIncrease",maximumJava-initialJava)
            .put("observedNativeHeapIncrease",maximumNative-initialNative).toString(2))
    }

    @Test fun rowFailuresLeaveNoPreviewPartialImageOrSessionEdit() = runBlocking {
        val context=editorAuditContext()
        val directory=File(context.cacheDir,EDITOR_AUDIT_DIRECTORY)
        val errors=listOf(OutOfMemoryError("synthetic_editor_memory"),
            CancellationException("synthetic_editor_cancel"),IOException("synthetic_editor_io"))
        val proof=JSONArray()
        for ((index,failure) in errors.withIndex()) {
            val session=editorAuditSession("FAIL$index")
            val info=File(context.filesDir,"session-info/${session.folderName}.txt")
            val infoBefore=if(info.exists()) info.readBytes() else null
            val existing=editorOutputIds(context,session)
            var closed=false
            val factory=EditorExportSourceFactory { _,_,_ ->
                object : EditorExportSource {
                    override val crop=CropPixels(0,0,0,0,320,240)
                    override val width=320
                    override val height=240
                    override fun readArgbRow(y: Int,destination: IntArray) {
                        if(y==7) throw failure
                        destination.fill(0xFF102030.toInt())
                    }
                    override fun metrics(): EditorImageMetrics=error("Incomplete export must not append metrics")
                    override fun close() { closed=true }
                }
            }
            val outcome=runCatching {
                ProcessedImageEditor(context,factory).saveEditedCopy(session,editorFileSource(File(directory,"source.png")),
                    ImageAdjustments(),CropSettings())
            }
            val error=outcome.exceptionOrNull() ?: outcome.getOrThrow().exceptionOrNull()
            assertNotNull(error)
            if(failure is OutOfMemoryError) {
                assertTrue(requireNotNull(error).message.orEmpty().contains("полном размере"))
                assertSame(failure,error.cause)
            } else assertSame(failure,error)
            assertTrue(closed)
            assertEquals(existing,editorOutputIds(context,session))
            assertArrayEquals(infoBefore,if(info.exists()) info.readBytes() else null)
            proof.put(JSONObject().put("failure",failure.javaClass.simpleName)
                .put("pendingAndPublishedRowsUnchanged",true).put("sessionMetadataUnchanged",true)
                .put("sourceClosed",true))
        }
        File(directory,"failure-proof.json").writeText(JSONObject().put("cases",proof).toString(2))
    }
}

private const val EDITOR_QA_PACKAGE = "com.joe6355.astrophoto.qa20261017"
private const val EDITOR_AUDIT_DIRECTORY = "editor_full_resolution_20261029"
private const val EDITOR_LARGE_WIDTH = 4096
private const val EDITOR_LARGE_HEIGHT = 3073

private fun editorAuditContext(): Context {
    assumeTrue(InstrumentationRegistry.getArguments().getString("editorStreaming") == "true")
    val context=InstrumentationRegistry.getInstrumentation().targetContext
    require(context.packageName==EDITOR_QA_PACKAGE)
    return context
}

private fun editorAuditSession(suffix: String): SessionSummary {
    val now=System.currentTimeMillis()
    val timestamp=java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.US).format(java.util.Date(now))
    val folder="Session_${timestamp}_QA_EDITOR_$suffix"
    return SessionSummary(folder,"QA_EDITOR_$suffix","Pictures/AstroPhoto/$folder/",now,0,0,0,"")
}

private fun editorFileSource(file: File)=ProcessedResult(file.path,file.name,ProcessedResultType.UNKNOWN,
    file.lastModified(),file.length(),file.path,null,file.path)

private fun editorSavedSource(context: Context,session: SessionSummary,saved: EditedImageSaveResult): ProcessedResult {
    val collection=processedImagesCollection(ProcessedMediaCollection.IMAGES)
    val path=processedImageDestination(session.folderName,"image/png").relativePath
    val uri=context.contentResolver.query(collection,arrayOf("_id"),"relative_path=? AND _display_name=?",
        arrayOf(path,saved.fileName),null)!!.use { cursor ->
        check(cursor.moveToFirst()) { "Saved editor PNG is missing" }
        ContentUris.withAppendedId(collection,cursor.getLong(0)).toString()
    }
    return ProcessedResult(uri,saved.fileName,ProcessedResultType.EDITED,System.currentTimeMillis(),0,
        saved.displayPath,uri,null,path)
}

@Suppress("DEPRECATION")
private fun editorOutputIds(context: Context,session: SessionSummary): Set<Long> {
    val collection=processedImagesCollection(ProcessedMediaCollection.IMAGES)
    val includingPending=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) MediaStore.setIncludePending(collection) else collection
    val path=processedImageDestination(session.folderName,"image/png").relativePath
    return context.contentResolver.query(includingPending,arrayOf("_id"),"relative_path=?",arrayOf(path),null)!!.use { cursor ->
        buildSet { while(cursor.moveToNext()) add(cursor.getLong(0)) }
    }
}

private fun editorDecode(context: Context,source: ProcessedResult): Bitmap =
    context.contentResolver.openInputStream(Uri.parse(source.contentUri))!!.use { requireNotNull(BitmapFactory.decodeStream(it)) }

private fun editorLargeColor(x: Int,y: Int): Int = 0xFF000000.toInt() or
    ((x*255/(EDITOR_LARGE_WIDTH-1)) shl 16) or ((y*255/(EDITOR_LARGE_HEIGHT-1)) shl 8) or
    (48+((x/31+y/17)%2)*120)

private data class EditorAuditCase(val name: String, val adjustments: ImageAdjustments, val crop: CropSettings)

private fun editorAuditCases() = listOf(
    EditorAuditCase("identity", ImageAdjustments(), CropSettings()),
    EditorAuditCase("tone-crop", ImageAdjustments(brightness=8f, contrast=18f, gamma=1.35f,
        blackPoint=5f, whitePoint=245f, saturation=112f), CropSettings(13f,7f,11f,5f)),
    EditorAuditCase("sharpen-edges", ImageAdjustments(sharpness=67f), CropSettings()),
    EditorAuditCase("sharpen-crop", ImageAdjustments(brightness=-6f, contrast=100f, gamma=2.5f,
        blackPoint=25f, whitePoint=200f, saturation=200f, sharpness=100f), CropSettings(5.4f,8.6f,12.3f,6.4f))
)

private fun editorPixelHash(bitmap: Bitmap): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val row = IntArray(bitmap.width)
    val bytes = ByteArray(bitmap.width*4)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
        for (x in row.indices) {
            val color = row[x]
            bytes[x*4] = (color ushr 24).toByte()
            bytes[x*4+1] = (color ushr 16).toByte()
            bytes[x*4+2] = (color ushr 8).toByte()
            bytes[x*4+3] = color.toByte()
        }
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
