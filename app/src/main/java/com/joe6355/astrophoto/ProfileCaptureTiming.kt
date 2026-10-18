package com.joe6355.astrophoto

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.joe6355.astrophoto.processing.jpeg.v2.registration.CaptureTimingAxis
import com.joe6355.astrophoto.processing.jpeg.v2.registration.CaptureTimingResolver
import com.joe6355.astrophoto.processing.jpeg.v2.registration.CaptureTimingSample
import com.joe6355.astrophoto.processing.jpeg.v2.registration.ExifCaptureTimeParser
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read headers without decoding or modifying the source JPEGs. Import dates are not capture times. */
internal suspend fun readProfileCaptureTiming(
    context: Context,
    frames: List<SessionFrame>,
    captureIndexByFrameKey: Map<String, Int>
): CaptureTimingAxis = withContext(Dispatchers.IO) {
    val samples = frames.map { frame ->
        currentCoroutineContext().ensureActive()
        val timestamp = try {
            val stream = frame.contentUri?.let { context.contentResolver.openInputStream(Uri.parse(it)) }
                ?: frame.filePath?.let { File(it).inputStream() }
            stream?.use { source ->
                val exif = ExifInterface(source)
                ExifCaptureTimeParser.parse(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        CaptureTimingSample(frame.key, captureIndexByFrameKey.getValue(frame.key), timestamp)
    }
    CaptureTimingResolver.resolve(samples)
}
