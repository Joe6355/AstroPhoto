package com.joe6355.astrophoto

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Read-only verification of an explicitly supplied export copied into the separate QA cache. */
@RunWith(AndroidJUnit4::class)
class ExportedMp4DecodeDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(90)

    @Test fun decodesEveryFrameOfOwnExport() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit own-export audit only", arguments.getString("decodeOwnExports") == "true")
        assumeTrue("Frame-index fallback requires API 28", Build.VERSION.SDK_INT >= 28)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == QA_PACKAGE) { "This audit may run only in the separate QA package" }
        val basename = requireNotNull(arguments.getString("exportFile")) { "exportFile is required" }
        require(basename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,179}\\.mp4"))) {
            "exportFile must be a safe ASCII MP4 basename, without a path"
        }
        val expectedFps = requireNotNull(arguments.getString("expectedFps")?.toIntOrNull()) {
            "expectedFps is required"
        }
        require(expectedFps in setOf(2, 5, 10, 20, 30))
        val expectedFrames = requireNotNull(arguments.getString("expectedFrames")?.toIntOrNull()) {
            "expectedFrames is required"
        }
        require(expectedFrames in 2..100_000)
        val cache = context.cacheDir.canonicalFile
        val file = File(cache, basename).canonicalFile
        require(file.parentFile == cache && file.isFile && file.length() > 0) {
            "Only an existing regular MP4 directly inside QA cache may be inspected"
        }
        val byteCount = file.length()
        val hash = sha256(file)
        val track = inspectTrack(file, expectedFps, expectedFrames)
        val decoded = try {
            decodeSequentially(file, track, expectedFps)
        } catch (unavailable: BufferDecodingUnavailable) {
            // A fallback still decodes every indexed frame. It never claims sequential EOS success.
            decodeEveryIndexedFrame(file, track, unavailable)
        }
        assertEquals("Every encoded sample must produce a decoded frame", expectedFrames, decoded.frameCount)
        assertEquals("The audit must not alter its input", byteCount, file.length())
        assertEquals("Input changed while it was being decoded", hash, sha256(file))
        val proof = JSONObject().apply {
            put("status", "passed")
            put("exportFile", basename)
            put("sha256", hash)
            put("bytes", byteCount)
            put("expectedFps", expectedFps)
            put("expectedFrames", expectedFrames)
            put("sampleCount", track.samplePtsUs.size)
            put("samplePtsUs", JSONArray(track.samplePtsUs))
            put("sampleSizesBytes", JSONArray(track.sampleSizesBytes))
            put("ptsToleranceUs", PTS_TOLERANCE_US)
            put("width", track.width)
            put("height", track.height)
            put("rotationDegrees", track.rotation)
            put("orientation", if (track.width < track.height) "portrait" else if (track.width > track.height) "landscape" else "square")
            put("mime", MIME)
            put("mode", decoded.mode)
            put("codecName", decoded.codecName ?: JSONObject.NULL)
            put("decodedFrameCount", decoded.frameCount)
            put("decodedPtsUs", JSONArray(decoded.ptsUs))
            put("decodedFrameBytes", JSONArray(decoded.frameBytes))
            put("allFramesDecoded", true)
            put("endOfStream", decoded.endOfStream)
            put("sequentialDecodeVerified", decoded.endOfStream)
            put("fallbackReason", decoded.fallbackReason ?: JSONObject.NULL)
            put("bufferAttemptDecodedFrames", decoded.bufferAttemptDecodedFrames)
            put("verifiedAtMillis", System.currentTimeMillis())
        }
        val proofFile = File(cache, "decode-proof-$basename.json")
        proofFile.writeText(proof.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("proofPath", proofFile.absolutePath)
            putString("stream", "MP4_DECODE_PASS file=$basename fps=$expectedFps frames=${decoded.frameCount} " +
                "mode=${decoded.mode} endOfStream=${decoded.endOfStream} sha256=$hash\n")
        })
    }

    private fun inspectTrack(file: File, fps: Int, expectedFrames: Int): Track {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals("Export must contain exactly one video track", 1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MIME, format.getString(MediaFormat.KEY_MIME))
            val (width, height) = displayDimensions(format)
            assertTrue("Invalid video dimensions", width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            assertEquals("Exporter bakes JPEG orientation into pixels", 0, rotation)
            extractor.selectTrack(0)
            val times = mutableListOf<Long>()
            val sizes = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                assertTrue("Unexpected extra compressed sample", times.size < expectedFrames)
                assertEquals("Encrypted media is not an AstroPhoto export", 0,
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
                val expectedTime = times.size * 1_000_000L / fps
                assertPts("Compressed sample ${times.size}", expectedTime, extractor.sampleTime)
                assertTrue("Empty compressed sample", extractor.sampleSize > 0)
                times += extractor.sampleTime
                sizes += extractor.sampleSize
                if (!extractor.advance()) break
            }
            assertEquals("Eligible capture count must match MP4 sample count", expectedFrames, times.size)
            return Track(width, height, rotation, times, sizes)
        } finally {
            extractor.release()
        }
    }

    private fun decodeSequentially(file: File, track: Track, fps: Int): Decoded {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        var codecName: String? = null
        val outputTimes = mutableListOf<Long>()
        val frameBytes = mutableListOf<Int>()
        val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(0)
            try {
                val decoder = MediaCodec.createDecoderByType(MIME)
                codec = decoder
                codecName = decoder.name
                decoder.configure(extractor.getTrackFormat(0), null, null, 0)
                decoder.start()
                started = true
            } catch (error: Exception) {
                if (error !is IOException && error !is IllegalArgumentException && error !is IllegalStateException) throw error
                throw BufferDecodingUnavailable("Buffer decoder initialization failed: ${error.javaClass.name}: ${error.message}",
                    codecName, 0, error)
            }
            val decoder = requireNotNull(codec)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var queuedFrames = 0
            while (!outputEnded) {
                assertTrue("Sequential decoder did not reach EOS within 30 seconds",
                    SystemClock.elapsedRealtime() < deadline)
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(BUFFER_WAIT_US)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                            ?: throw BufferDecodingUnavailable("Decoder supplied no readable input buffer", codecName, outputTimes.size)
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, track.samplePtsUs.last() + 1_000_000L / fps,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            assertTrue("Unexpected extra decoder input", queuedFrames < track.samplePtsUs.size)
                            assertPts("Decoder input $queuedFrames", track.samplePtsUs[queuedFrames], extractor.sampleTime)
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            queuedFrames++
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, BUFFER_WAIT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> assertDimensions(decoder.outputFormat, track)
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        assertTrue("Unexpected decoder status $outputIndex", outputIndex >= 0)
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                assertTrue("Unexpected extra decoded frame", outputTimes.size < track.samplePtsUs.size)
                                assertDimensions(decoder.getOutputFormat(outputIndex), track)
                                val output = decoder.getOutputBuffer(outputIndex)
                                    ?: throw BufferDecodingUnavailable("Decoder supplied no readable output buffer", codecName, outputTimes.size)
                                assertTrue("Decoded frame has invalid buffer bounds", info.offset >= 0 &&
                                    info.size.toLong() + info.offset <= output.capacity().toLong())
                                // Read decoded pixels, rather than treating a codec callback alone as a frame.
                                val pixels = output.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
                                assertTrue("Decoded frame has no accessible pixel data", pixels.hasRemaining())
                                pixels.get()
                                assertPts("Decoded frame ${outputTimes.size}", track.samplePtsUs[outputTimes.size], info.presentationTimeUs)
                                outputTimes += info.presentationTimeUs
                                frameBytes += info.size
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            assertTrue("Output EOS preceded input EOS", inputEnded)
            assertEquals("All samples must be fed to the decoder", track.samplePtsUs.size, queuedFrames)
            assertEquals("EOS must follow every decoded frame", track.samplePtsUs.size, outputTimes.size)
            assertTrue("Sequential decoder exceeded 30 seconds", SystemClock.elapsedRealtime() < deadline)
            return Decoded("media-codec-byte-buffer", codecName, outputTimes.size, outputTimes, frameBytes, true,
                bufferAttemptDecodedFrames = outputTimes.size)
        } finally {
            codec?.let { decoder ->
                if (started) runCatching { decoder.stop() }
                decoder.release()
            }
            extractor.release()
        }
    }

    private fun decodeEveryIndexedFrame(file: File, track: Track, unavailable: BufferDecodingUnavailable): Decoded {
        val retriever = MediaMetadataRetriever()
        val frameBytes = mutableListOf<Int>()
        val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
        try {
            retriever.setDataSource(file.absolutePath)
            assertEquals("Retriever width", track.width,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull())
            assertEquals("Retriever height", track.height,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull())
            assertEquals("Retriever rotation", 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.let { count ->
                assertEquals("Retriever frame count", track.samplePtsUs.size, count.toInt())
            }
            for (index in track.samplePtsUs.indices) {
                assertTrue("All-frame fallback exceeded 30 seconds", SystemClock.elapsedRealtime() < deadline)
                val frame = requireNotNull(retriever.getFrameAtIndex(index)) { "Fallback failed to decode frame $index" }
                try {
                    assertEquals("Fallback frame $index width", track.width, frame.width)
                    assertEquals("Fallback frame $index height", track.height, frame.height)
                    assertTrue("Fallback frame $index has no decoded pixels", frame.byteCount > 0)
                    frameBytes += frame.byteCount
                } finally {
                    frame.recycle()
                }
            }
            assertTrue("All-frame fallback exceeded 30 seconds", SystemClock.elapsedRealtime() < deadline)
            return Decoded("media-metadata-retriever-all-frames-fallback", unavailable.codecName, frameBytes.size,
                emptyList(), frameBytes, false, unavailable.message, unavailable.decodedFrames)
        } finally {
            retriever.release()
        }
    }

    private fun assertDimensions(format: MediaFormat, track: Track) {
        val (width, height) = displayDimensions(format)
        assertEquals("Decoded display width", track.width, width)
        assertEquals("Decoded display height", track.height, height)
    }

    private fun displayDimensions(format: MediaFormat): Pair<Int, Int> {
        val width = if (format.containsKey("crop-left") && format.containsKey("crop-right"))
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1 else format.getInteger(MediaFormat.KEY_WIDTH)
        val height = if (format.containsKey("crop-top") && format.containsKey("crop-bottom"))
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1 else format.getInteger(MediaFormat.KEY_HEIGHT)
        return width to height
    }

    private fun assertPts(label: String, expected: Long, actual: Long) {
        assertTrue("$label PTS: expected $expected us, actual $actual us",
            abs(actual - expected) <= PTS_TOLERANCE_US)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class Track(val width: Int, val height: Int, val rotation: Int,
        val samplePtsUs: List<Long>, val sampleSizesBytes: List<Long>)

    private data class Decoded(val mode: String, val codecName: String?, val frameCount: Int,
        val ptsUs: List<Long>, val frameBytes: List<Int>, val endOfStream: Boolean,
        val fallbackReason: String? = null, val bufferAttemptDecodedFrames: Int = 0)

    private class BufferDecodingUnavailable(message: String, val codecName: String?, val decodedFrames: Int,
        cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val QA_PACKAGE = "com.joe6355.astrophoto.qa20261017"
        const val MIME = "video/avc"
        const val PTS_TOLERANCE_US = 5L
        const val DECODE_TIMEOUT_MS = 30_000L
        const val BUFFER_WAIT_US = 10_000L
    }
}
