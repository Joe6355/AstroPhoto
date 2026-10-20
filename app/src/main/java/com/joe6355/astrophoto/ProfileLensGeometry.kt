package com.joe6355.astrophoto

import android.content.Context
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.joe6355.astrophoto.processing.jpeg.v2.registration.PerspectiveRotationGeometry
import java.io.File
import kotlinx.coroutines.CancellationException

/** Optional header-only estimate for original JPEGs; absent/invalid metadata keeps the old path. */
internal fun readProfileFocalLengthPixels(context: Context, frame: SessionFrame, width: Int, height: Int): Double? {
    if (frame.category != SessionFrameCategory.LIGHTS_JPEG) return null
    return try {
        val stream = frame.contentUri?.let { context.contentResolver.openInputStream(it.toUri()) }
            ?: frame.filePath?.let { File(it).inputStream() }
        stream?.use { source ->
            val exif = ExifInterface(source)
            // A digital crop needs its own calibration; do not infer it from the lens tag.
            val zoom = exif.getAttributeDouble(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, 0.0)
            if (!zoom.isFinite() || zoom != 0.0 && kotlin.math.abs(zoom - 1.0) > 0.001) return@use null
            PerspectiveRotationGeometry.from35mmEquivalent(width, height,
                exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0))?.focalPixels
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }
}
