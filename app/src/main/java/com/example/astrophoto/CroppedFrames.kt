package com.example.astrophoto

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.roundToInt

data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun validated(): NormalizedCropRect {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Crop coordinates must be finite"
        }
        val clamped = NormalizedCropRect(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f)
        )
        require(clamped.right > clamped.left && clamped.bottom > clamped.top) {
            "Crop rectangle must have a positive size"
        }
        return clamped
    }

    fun toPixelRect(width: Int, height: Int): PixelRect {
        require(width > 0 && height > 0) { "Crop image dimensions must be positive" }
        val crop = validated()
        val leftPx = (crop.left * width).roundToInt().coerceIn(0, width - 1)
        val topPx = (crop.top * height).roundToInt().coerceIn(0, height - 1)
        val rightPx = (crop.right * width).roundToInt().coerceIn(leftPx + 1, width)
        val bottomPx = (crop.bottom * height).roundToInt().coerceIn(topPx + 1, height)
        return PixelRect(leftPx, topPx, rightPx, bottomPx)
    }

    companion object {
        val Full = NormalizedCropRect(0f, 0f, 1f, 1f)
    }
}

data class CropManifestEntry(
    val originalKey: String,
    val originalFileName: String,
    val croppedFileName: String,
    val normalizedRect: NormalizedCropRect,
    val pixelRect: PixelRect,
    val originalWidth: Int,
    val originalHeight: Int,
    val croppedWidth: Int,
    val croppedHeight: Int,
    val updatedAtMillis: Long
) {
    init {
        require(originalKey.isNotBlank() && originalFileName.isNotBlank() && croppedFileName.isNotBlank())
        require(originalWidth > 0 && originalHeight > 0)
        require(croppedWidth == pixelRect.width && croppedHeight == pixelRect.height)
        require(pixelRect.left >= 0 && pixelRect.top >= 0)
        require(pixelRect.right <= originalWidth && pixelRect.bottom <= originalHeight)
        normalizedRect.validated()
    }
}

data class CropManifest(val entries: List<CropManifestEntry> = emptyList()) {
    fun replace(entry: CropManifestEntry): CropManifest = CropManifest(
        (entries.filterNot { it.originalKey == entry.originalKey } + entry)
            .sortedBy { it.originalKey }
    )

    fun remove(originalKey: String): CropManifest =
        CropManifest(entries.filterNot { it.originalKey == originalKey })

    fun find(originalKey: String): CropManifestEntry? =
        entries.firstOrNull { it.originalKey == originalKey }

    fun encode(): String = buildString {
        appendLine(MANIFEST_VERSION)
        entries.sortedBy { it.originalKey }.forEach { entry ->
            appendLine(
                listOf(
                    encodeText(entry.originalKey),
                    encodeText(entry.originalFileName),
                    encodeText(entry.croppedFileName),
                    entry.normalizedRect.left,
                    entry.normalizedRect.top,
                    entry.normalizedRect.right,
                    entry.normalizedRect.bottom,
                    entry.pixelRect.left,
                    entry.pixelRect.top,
                    entry.pixelRect.right,
                    entry.pixelRect.bottom,
                    entry.originalWidth,
                    entry.originalHeight,
                    entry.croppedWidth,
                    entry.croppedHeight,
                    entry.updatedAtMillis
                ).joinToString("\t")
            )
        }
    }

    companion object {
        fun decode(content: String): CropManifest {
            val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) return CropManifest()
            require(lines.first() == MANIFEST_VERSION) { "Unsupported crop manifest version" }
            return CropManifest(lines.drop(1).map(::decodeEntry).sortedBy { it.originalKey })
        }

        private fun decodeEntry(line: String): CropManifestEntry {
            val values = line.split('\t')
            require(values.size == 16) { "Invalid crop manifest entry" }
            val normalized = NormalizedCropRect(
                values[3].toFloat(), values[4].toFloat(), values[5].toFloat(), values[6].toFloat()
            )
            val pixels = PixelRect(
                values[7].toInt(), values[8].toInt(), values[9].toInt(), values[10].toInt()
            )
            return CropManifestEntry(
                decodeText(values[0]),
                decodeText(values[1]),
                decodeText(values[2]),
                normalized,
                pixels,
                values[11].toInt(),
                values[12].toInt(),
                values[13].toInt(),
                values[14].toInt(),
                values[15].toLong()
            )
        }
    }
}

data class CroppedFrameRecord(
    val entry: CropManifestEntry,
    val frame: SessionFrame
)

enum class ManualStackingSource(val metadataValue: String) {
    ORIGINAL("original"),
    CROPPED("cropped")
}

sealed interface StackingSourceSelection {
    data class Valid(
        val frames: List<SessionFrame>,
        val source: ManualStackingSource,
        val entries: List<CropManifestEntry> = emptyList(),
        val missingCropCount: Int = 0
    ) : StackingSourceSelection

    data class Invalid(val message: String) : StackingSourceSelection
}

fun resolveStackingSource(
    originals: List<SessionFrame>,
    crops: List<CroppedFrameRecord>,
    marks: FrameMarks,
    favoritesOnly: Boolean,
    source: ManualStackingSource
): StackingSourceSelection {
    val eligibleOriginals = originals.filter { frame ->
        frame.category == SessionFrameCategory.LIGHTS_JPEG &&
            frame.key !in marks.bad && frame.key !in marks.autoBad &&
            (!favoritesOnly || frame.key in marks.favorite)
    }
    if (source == ManualStackingSource.ORIGINAL) {
        return StackingSourceSelection.Valid(eligibleOriginals, source)
    }
    val cropByOriginal = crops.associateBy { it.entry.originalKey }
    val selected = eligibleOriginals.mapNotNull { cropByOriginal[it.key] }
    val missing = eligibleOriginals.size - selected.size
    if (selected.size < 2) {
        return StackingSourceSelection.Invalid(
            "Недостаточно обрезанных JPEG кадров для стеккинга"
        )
    }
    val dimensions = selected.map { it.entry.croppedWidth to it.entry.croppedHeight }.distinct()
    if (dimensions.size != 1) {
        return StackingSourceSelection.Invalid(
            "Selected cropped frames have different dimensions"
        )
    }
    return StackingSourceSelection.Valid(
        frames = selected.map { record ->
            record.frame.copy(
                key = record.entry.originalKey,
                category = SessionFrameCategory.LIGHTS_JPEG,
                originalKey = record.entry.originalKey
            )
        },
        source = source,
        entries = selected.map { it.entry },
        missingCropCount = missing
    )
}

fun commonDarkCrop(entries: List<CropManifestEntry>): CropManifestEntry {
    require(entries.isNotEmpty()) { "Cropped light frames are required" }
    val first = entries.first()
    val sameArea = entries.all {
        it.normalizedRect == first.normalizedRect &&
            it.pixelRect == first.pixelRect &&
            it.originalWidth == first.originalWidth &&
            it.originalHeight == first.originalHeight
    }
    require(sameArea) {
        "Average + Dark requires the same crop area for all selected frames. " +
            "Use Apply to all or process without Dark Frames."
    }
    return first
}

fun deterministicCropFileName(originalKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(originalKey.toByteArray(StandardCharsets.UTF_8))
        .take(10)
        .joinToString("") { byte -> "%02x".format(byte) }
    return "crop_$digest.jpg"
}

internal fun cropArgbImage(
    image: ArgbPixelImage,
    normalizedRect: NormalizedCropRect
): ArgbPixelImage {
    val rect = normalizedRect.toPixelRect(image.width, image.height)
    val pixels = IntArray(rect.width * rect.height)
    for (row in 0 until rect.height) {
        image.pixels.copyInto(
            pixels,
            row * rect.width,
            (rect.top + row) * image.width + rect.left,
            (rect.top + row) * image.width + rect.right
        )
    }
    return ArgbPixelImage(rect.width, rect.height, pixels)
}

private const val MANIFEST_VERSION = "ASTROPHOTO_CROPS_V1"

private fun encodeText(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeText(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
