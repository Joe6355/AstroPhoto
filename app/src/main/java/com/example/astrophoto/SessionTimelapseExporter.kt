package com.example.astrophoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

data class SessionTimelapseProgress(
    val message: String,
    val current: Int,
    val total: Int
)

data class SessionTimelapseResult(
    val fileName: String,
    val location: String,
    val contentUri: String?,
    val filePath: String?,
    val sizeBytes: Long,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val durationMillis: Long
)

internal fun eligibleTimelapseFrames(
    frames: List<SessionFrame>,
    marks: FrameMarks
): List<SessionFrame> = frames
    .asSequence()
    .filter { it.category == SessionFrameCategory.LIGHTS_JPEG }
    .filterNot { it.markKey in marks.bad }
    .sortedWith(compareBy<SessionFrame> { it.createdAtMillis }.thenBy { it.fileName })
    .toList()

internal fun timelapseOutputSize(
    sourceWidth: Int,
    sourceHeight: Int
): Pair<Int, Int> {
    require(sourceWidth > 0 && sourceHeight > 0)
    val maxWidth = if (sourceWidth >= sourceHeight) 1920 else 1080
    val maxHeight = if (sourceWidth >= sourceHeight) 1080 else 1920
    val scale = min(
        1f,
        min(maxWidth.toFloat() / sourceWidth, maxHeight.toFloat() / sourceHeight)
    )
    val width = ((sourceWidth * scale).roundToInt().coerceAtLeast(2) / 2) * 2
    val height = ((sourceHeight * scale).roundToInt().coerceAtLeast(2) / 2) * 2
    return width to height
}

class SessionTimelapseExporter(private val context: Context) {
    private val framesRepository = SessionFramesRepository(context)
    private val marksStore = FrameMarksStore(context)

    suspend fun export(
        session: SessionSummary,
        fps: Int,
        onProgress: suspend (SessionTimelapseProgress) -> Unit = {}
    ): Result<SessionTimelapseResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(fps in SUPPORTED_FPS) {
                "Поддерживается ${SUPPORTED_FPS.sorted().joinToString()} кадров/с"
            }
            onProgress(SessionTimelapseProgress("Подготовка кадров", 0, 1))
            val frames = eligibleTimelapseFrames(
                framesRepository.loadFrames(session),
                marksStore.loadOrCreate(session)
            )
            require(frames.size >= MIN_FRAME_COUNT) {
                "Для таймлапса нужно минимум $MIN_FRAME_COUNT подходящих JPEG-кадра"
            }

            val firstSize = readOrientedJpegDimensions { openFrame(frames.first()) }
                ?: error("Не удалось прочитать первый JPEG-кадр")
            val (width, height) = timelapseOutputSize(firstSize.first, firstSize.second)
            val destination = createDestination(session)
            try {
                encode(
                    frames = frames,
                    width = width,
                    height = height,
                    fps = fps,
                    muxer = destination.muxer,
                    onProgress = onProgress
                )
                destination.finish()
                SessionTimelapseResult(
                    fileName = destination.fileName,
                    location = destination.location,
                    contentUri = destination.contentUri?.toString(),
                    filePath = destination.file?.absolutePath,
                    sizeBytes = destination.sizeBytes(),
                    frameCount = frames.size,
                    width = width,
                    height = height,
                    fps = fps,
                    durationMillis = frames.size * 1_000L / fps
                )
            } catch (error: Throwable) {
                destination.abort()
                throw error
            } finally {
                destination.close()
            }
        }
    }

    private suspend fun encode(
        frames: List<SessionFrame>,
        width: Int,
        height: Int,
        fps: Int,
        muxer: MediaMuxer,
        onProgress: suspend (SessionTimelapseProgress) -> Unit
    ) {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate(width, height, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        var inputSurface: CodecInputSurface? = null
        var renderer: BitmapRenderer? = null
        var targetBitmap: Bitmap? = null
        var codecStarted = false
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = CodecInputSurface(codec.createInputSurface())
            codec.start()
            codecStarted = true
            inputSurface.makeCurrent()
            renderer = BitmapRenderer()
            targetBitmap = createBitmap(width, height)
            val canvas = Canvas(targetBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1

            frames.forEachIndexed { index, frame ->
                currentCoroutineContext().ensureActive()
                val dimensions = readOrientedJpegDimensions { openFrame(frame) }
                    ?: error("Не удалось прочитать ${frame.fileName}")
                val sampleSize = decodeSampleSize(
                    dimensions.first,
                    dimensions.second,
                    width,
                    height
                )
                val decoded = decodeOrientedJpeg(
                    openStream = { openFrame(frame) },
                    sampleSize = sampleSize
                ) ?: error("Не удалось декодировать ${frame.fileName}")
                try {
                    drawFittedFrame(canvas, targetBitmap, decoded, paint)
                    renderer.draw(targetBitmap)
                    inputSurface.setPresentationTime(index * 1_000_000_000L / fps)
                    inputSurface.swapBuffers()
                } finally {
                    decoded.recycle()
                }
                val drain = drainEncoder(codec, muxer, bufferInfo, false, trackIndex)
                trackIndex = drain.trackIndex
                muxerStarted = muxerStarted || drain.muxerStarted
                onProgress(
                    SessionTimelapseProgress(
                        message = "Кодирование кадра ${index + 1} из ${frames.size}",
                        current = index + 1,
                        total = frames.size
                    )
                )
            }

            codec.signalEndOfInputStream()
            var endReached = false
            while (!endReached) {
                val drain = drainEncoder(codec, muxer, bufferInfo, true, trackIndex)
                trackIndex = drain.trackIndex
                muxerStarted = muxerStarted || drain.muxerStarted
                endReached = drain.endReached
            }
            check(muxerStarted) { "Кодек не создал видеодорожку" }
        } finally {
            targetBitmap?.recycle()
            renderer?.release()
            inputSurface?.release()
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        currentTrackIndex: Int
    ): DrainResult {
        var trackIndex = currentTrackIndex
        var muxerStarted = trackIndex >= 0
        while (true) {
            val status = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return DrainResult(trackIndex, muxerStarted, false)
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Формат видеодорожки изменился повторно" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                status >= 0 -> {
                    val encoded = codec.getOutputBuffer(status)
                        ?: error("Кодек вернул пустой выходной буфер")
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        check(muxerStarted) { "Видеодорожка ещё не создана" }
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    val endReached = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(status, false)
                    if (endReached) return DrainResult(trackIndex, muxerStarted, true)
                }
            }
        }
    }

    private fun openFrame(frame: SessionFrame): InputStream? = when {
        frame.contentUri != null -> context.contentResolver.openInputStream(frame.contentUri.toUri())
        frame.filePath != null -> FileInputStream(frame.filePath)
        else -> null
    }

    private fun createDestination(session: SessionSummary): VideoDestination {
        val safeName = session.sessionName
            .replace(Regex("[^A-Za-zА-Яа-я0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "Session" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "AstroPhoto_${safeName}_$timestamp.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreDestination(fileName)
        } else {
            createFileDestination(fileName)
        }
    }

    private fun createMediaStoreDestination(fileName: String): VideoDestination {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_MOVIES}/AstroPhoto/"
        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        ) ?: error("Не удалось создать MP4 в MediaStore")
        val descriptor = resolver.openFileDescriptor(uri, "w")
            ?: run {
                resolver.delete(uri, null, null)
                error("Не удалось открыть MP4 для записи")
            }
        return VideoDestination(
            context = context,
            fileName = fileName,
            location = "${relativePath.trimEnd('/')}/$fileName",
            contentUri = uri,
            file = null,
            descriptor = descriptor,
            muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        )
    }

    @Suppress("DEPRECATION")
    private fun createFileDestination(fileName: String): VideoDestination {
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val directory = File(movies, "AstroPhoto")
        check(directory.exists() || directory.mkdirs()) { "Не удалось создать папку Movies/AstroPhoto" }
        var file = File(directory, fileName)
        var suffix = 2
        while (file.exists()) {
            file = File(directory, fileName.removeSuffix(".mp4") + "_$suffix.mp4")
            suffix++
        }
        return VideoDestination(
            context = context,
            fileName = file.name,
            location = file.absolutePath,
            contentUri = null,
            file = file,
            descriptor = null,
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        )
    }

    companion object {
        val SUPPORTED_FPS = setOf(2, 5, 10, 20, 30)
        const val MIN_FRAME_COUNT = 2
        private const val MIME_TYPE = "video/avc"

        private fun videoBitRate(width: Int, height: Int, fps: Int): Int =
            (width.toLong() * height * fps / 3L)
                .coerceIn(2_000_000L, 16_000_000L)
                .toInt()

        private fun decodeSampleSize(
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int
        ): Int {
            var sample = 1
            while (
                sourceWidth / (sample * 2) >= targetWidth &&
                sourceHeight / (sample * 2) >= targetHeight
            ) {
                sample *= 2
            }
            return sample
        }

        private fun drawFittedFrame(
            canvas: Canvas,
            target: Bitmap,
            source: Bitmap,
            paint: Paint
        ) {
            canvas.drawColor(Color.BLACK)
            val scale = min(
                target.width.toFloat() / source.width,
                target.height.toFloat() / source.height
            )
            val drawWidth = source.width * scale
            val drawHeight = source.height * scale
            val left = (target.width - drawWidth) / 2f
            val top = (target.height - drawHeight) / 2f
            canvas.drawBitmap(
                source,
                null,
                android.graphics.RectF(left, top, left + drawWidth, top + drawHeight),
                paint
            )
        }
    }
}

private data class DrainResult(
    val trackIndex: Int,
    val muxerStarted: Boolean,
    val endReached: Boolean
)

private class VideoDestination(
    private val context: Context,
    val fileName: String,
    val location: String,
    val contentUri: Uri?,
    val file: File?,
    private val descriptor: ParcelFileDescriptor?,
    val muxer: MediaMuxer
) {
    private var completed = false

    fun finish() {
        if (contentUri != null) {
            context.contentResolver.update(
                contentUri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
        } else if (file != null) {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }
        completed = true
    }

    fun sizeBytes(): Long = contentUri?.let { uri ->
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.SIZE),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L }
    } ?: file?.length() ?: 0L

    fun abort() {
        if (contentUri != null) context.contentResolver.delete(contentUri, null, null)
        file?.delete()
    }

    fun close() {
        descriptor?.close()
        if (!completed) abort()
    }
}

private class CodecInputSurface(private val surface: Surface) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display недоступен" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "Ошибка EGL initialize" }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
            "Ошибка выбора EGL config"
        }
        val config = configs[0] ?: error("EGL config не найден")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Ошибка создания EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Ошибка создания EGL surface" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "Ошибка активации EGL surface"
        }
    }

    fun setPresentationTime(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
    }

    fun swapBuffers() {
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "Ошибка записи видеокадра" }
    }

    fun release() {
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        surface.release()
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

private class BitmapRenderer {
    private val vertices: FloatBuffer = floatBufferOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )
    private val program: Int
    private val texture = IntArray(1)
    private val positionLocation: Int
    private val textureLocation: Int

    init {
        program = GLES20.glCreateProgram().also { value ->
            GLES20.glAttachShader(value, compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
            GLES20.glAttachShader(value, compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
            GLES20.glLinkProgram(value)
            val status = IntArray(1)
            GLES20.glGetProgramiv(value, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Ошибка OpenGL link: ${GLES20.glGetProgramInfoLog(value)}" }
        }
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun draw(bitmap: Bitmap) {
        GLES20.glViewport(0, 0, bitmap.width, bitmap.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 4 * 4, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(textureLocation)
        GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 4 * 4, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glFinish()
    }

    fun release() {
        GLES20.glDeleteTextures(1, texture, 0)
        GLES20.glDeleteProgram(program)
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Ошибка OpenGL shader: ${GLES20.glGetShaderInfoLog(shader)}"
            }
        }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
