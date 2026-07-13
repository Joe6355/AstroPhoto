package com.example.astrophoto

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal data class AstroRawSidecarFile(
    val relativePath: String,
    val file: File
)

class AstroRawSidecarStore(private val context: Context) {
    fun saveCapture(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        dngFileName: String,
        relativeDirectory: String?,
        outputOrientationDegrees: Int
    ): String {
        val cfaArrangement = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        ) ?: error("RAW CFA metadata is unavailable")
        require(cfaArrangement in 0..3) { "Unsupported RAW CFA arrangement" }
        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: error("RAW white level is unavailable")
        val plane = image.planes.singleOrNull() ?: error("RAW image must contain one plane")
        val metadata = AstroRawMetadata(
            width = image.width,
            height = image.height,
            sampleShift = detectRawSampleShift(
                rawLittleEndian16 = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                whiteLevel = whiteLevel
            ),
            cfaArrangement = cfaArrangement,
            whiteLevel = whiteLevel,
            blackLevels = captureBlackLevels(characteristics, captureResult, cfaArrangement),
            colorGains = captureColorGains(captureResult),
            colorTransform = captureColorTransform(captureResult),
            exposureTimeNs = captureResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            sensitivityIso = captureResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            postRawSensitivityBoost = captureResult.get(
                CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST
            ) ?: 100,
            sensorOrientation = outputOrientationDegrees
        )
        val sidecarName = sidecarNameFor(dngFileName)

        val destination = sidecarFile(
            relativeDirectory = relativeDirectory ?: "AstroPhoto",
            fileName = sidecarName
        )
        val destinationDirectory = requireNotNull(destination.parentFile)
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            error("Could not create RAW sidecar directory")
        }
        val temporary = File(
            destinationDirectory,
            ".${destination.name}.${System.nanoTime()}.tmp"
        )
        try {
            FileOutputStream(temporary).use { output ->
                writeAstroRaw(
                    output = output,
                    metadata = metadata,
                    rawLittleEndian16 = plane.buffer,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride
                )
            }
            require(temporary.length() > 0L) { "RAW sidecar is empty" }
            if (destination.exists() && !destination.delete()) {
                error("Could not replace RAW sidecar")
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                require(destination.length() == temporary.length()) {
                    "RAW sidecar copy is incomplete"
                }
                temporary.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        return sidecarName
    }

    fun openForFrame(session: SessionSummary, frame: SessionFrame): InputStream? {
        val sidecarName = sidecarNameFor(frame.fileName)
        val frameDirectory = frame.key.substringBeforeLast('/', "")
        val privateSidecar = sidecarFile(
            relativeDirectory = buildString {
                append("AstroPhoto/")
                append(session.folderName)
                if (frameDirectory.isNotBlank()) {
                    append('/')
                    append(frameDirectory)
                }
            },
            fileName = sidecarName
        )
        if (privateSidecar.isFile) {
            return privateSidecar.inputStream()
        }

        val publicDng = frame.filePath?.let(::File) ?: return null
        return File(publicDng.parentFile, sidecarName)
            .takeIf(File::isFile)
            ?.inputStream()
    }

    internal fun listForSession(folderName: String): List<AstroRawSidecarFile> {
        val directory = sessionDirectory(folderName)
        if (!directory.isDirectory) return emptyList()
        return directory.walkTopDown()
            .filter { it.isFile && it.extension.equals("araw", ignoreCase = true) }
            .map { file ->
                AstroRawSidecarFile(
                    relativePath = file.relativeTo(directory).path.replace('\\', '/'),
                    file = file
                )
            }
            .toList()
    }

    fun renameSession(oldFolderName: String, newFolderName: String) {
        val oldDirectory = sessionDirectory(oldFolderName)
        if (!oldDirectory.exists()) return
        val newDirectory = sessionDirectory(newFolderName)
        require(!newDirectory.exists()) { "RAW sidecar session already exists" }
        val sessionRoot = requireNotNull(newDirectory.parentFile)
        if (!sessionRoot.exists() && !sessionRoot.mkdirs()) {
            error("Could not create RAW sidecar root")
        }
        require(oldDirectory.renameTo(newDirectory)) { "Could not rename RAW sidecar session" }
    }

    fun deleteSession(folderName: String): Pair<Int, Int> {
        val directory = sessionDirectory(folderName)
        if (!directory.exists()) return 0 to 0
        var deleted = 0
        var failed = 0
        directory.walkBottomUp().forEach { file ->
            if (file.isFile) {
                if (file.delete()) deleted++ else failed++
            } else {
                file.delete()
            }
        }
        return deleted to failed
    }

    private fun sessionDirectory(folderName: String): File =
        sidecarDirectory("AstroPhoto/$folderName")

    private fun sidecarFile(relativeDirectory: String, fileName: String): File {
        require('/' !in fileName && '\\' !in fileName && fileName !in listOf(".", "..")) {
            "Invalid RAW sidecar name"
        }
        return File(sidecarDirectory(relativeDirectory), fileName)
    }

    private fun sidecarDirectory(relativeDirectory: String): File {
        val root = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            RAW_SIDECAR_ROOT
        ).canonicalFile
        val segments = relativeDirectory.replace('\\', '/')
            .split('/')
            .filter(String::isNotBlank)
        require(segments.isNotEmpty() && segments.none { it == "." || it == ".." }) {
            "Invalid RAW sidecar path"
        }
        val directory = segments.fold(root) { current, segment -> File(current, segment) }
            .canonicalFile
        require(directory.path.startsWith(root.path + File.separator)) {
            "RAW sidecar path escaped its root"
        }
        return directory
    }
}

private fun captureBlackLevels(
    characteristics: CameraCharacteristics,
    captureResult: TotalCaptureResult,
    cfaArrangement: Int
): List<Float> {
    captureResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { channels ->
        if (channels.size >= 4) return channelValuesToCfaCells(channels.toList(), cfaArrangement)
    }
    val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        ?: return List(4) { 0f }
    return listOf(
        pattern.getOffsetForIndex(0, 0).toFloat(),
        pattern.getOffsetForIndex(1, 0).toFloat(),
        pattern.getOffsetForIndex(0, 1).toFloat(),
        pattern.getOffsetForIndex(1, 1).toFloat()
    )
}

internal fun channelValuesToCfaCells(channels: List<Float>, cfaArrangement: Int): List<Float> {
    require(channels.size == 4)
    val red = channels[0]
    val greenEven = channels[1]
    val greenOdd = channels[2]
    val blue = channels[3]
    return when (cfaArrangement) {
        0 -> listOf(red, greenEven, greenOdd, blue)
        1 -> listOf(greenEven, red, blue, greenOdd)
        2 -> listOf(greenEven, blue, red, greenOdd)
        3 -> listOf(blue, greenEven, greenOdd, red)
        else -> error("Unsupported RAW CFA arrangement")
    }
}

private fun captureColorGains(captureResult: TotalCaptureResult): List<Float> {
    val gains = captureResult.get(CaptureResult.COLOR_CORRECTION_GAINS)
        ?: return List(4) { 1f }
    return listOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
}

private fun captureColorTransform(captureResult: TotalCaptureResult): List<Float> {
    val transform = captureResult.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        ?: return listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    return List(9) { index ->
        transform.getElement(index % 3, index / 3).toFloat()
    }
}

private fun sidecarNameFor(dngFileName: String): String =
    "${dngFileName.substringBeforeLast('.', dngFileName)}.araw"

private const val RAW_SIDECAR_ROOT = "raw-sidecars"
