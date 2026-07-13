package com.example.astrophoto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

data class AstroRawMetadata(
    val width: Int,
    val height: Int,
    val sampleShift: Int,
    val cfaArrangement: Int,
    val whiteLevel: Int,
    val blackLevels: List<Float>,
    val colorGains: List<Float>,
    val colorTransform: List<Float>,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val postRawSensitivityBoost: Int,
    val sensorOrientation: Int
) {
    init {
        require(width > 0 && height > 0) { "RAW dimensions must be positive" }
        require(width.toLong() * height <= MAX_ASTRO_RAW_PIXELS) { "RAW frame is too large" }
        require(sampleShift in 0..15) { "RAW sample shift is invalid" }
        require(cfaArrangement in 0..3) { "Unsupported RAW CFA arrangement" }
        require(whiteLevel > 0) { "RAW white level must be positive" }
        require(blackLevels.size == 4 && blackLevels.all(Float::isFinite)) {
            "RAW black levels must contain four finite values"
        }
        require(colorGains.size == 4 && colorGains.all { it.isFinite() && it > 0f }) {
            "RAW color gains must contain four positive values"
        }
        require(colorTransform.size == 9 && colorTransform.all(Float::isFinite)) {
            "RAW color transform must contain nine finite values"
        }
        require(exposureTimeNs >= 0L && sensitivityIso >= 0) { "RAW exposure metadata is invalid" }
        require(postRawSensitivityBoost > 0) { "RAW sensitivity boost must be positive" }
    }

    val pixelCount: Int
        get() = width * height
}

data class AstroRawFrame(
    val metadata: AstroRawMetadata,
    val samples: ShortArray
) {
    init {
        require(samples.size == metadata.pixelCount) { "RAW sample count does not match dimensions" }
    }

    fun sampleAt(x: Int, y: Int): Int =
        (samples[y * metadata.width + x].toInt() and 0xFFFF) ushr metadata.sampleShift
}

fun writeAstroRaw(
    output: OutputStream,
    metadata: AstroRawMetadata,
    rawLittleEndian16: ByteBuffer,
    rowStride: Int = metadata.width * 2,
    pixelStride: Int = 2
) {
    val requiredBytes = metadata.pixelCount.toLong() * 2L
    require(requiredBytes <= Int.MAX_VALUE) { "RAW payload is too large" }
    require(rowStride >= metadata.width * pixelStride && pixelStride >= 2) {
        "RAW plane strides are invalid"
    }
    val source = rawLittleEndian16.duplicate()
    val sourceBytes = (metadata.height - 1).toLong() * rowStride +
        (metadata.width - 1).toLong() * pixelStride + 2L
    require(sourceBytes <= Int.MAX_VALUE) { "RAW source plane is too large" }
    if (source.remaining() < sourceBytes.toInt()) source.clear()
    require(source.remaining() >= sourceBytes.toInt()) { "RAW buffer is shorter than expected" }
    val sourceStart = source.position()

    val data = DataOutputStream(BufferedOutputStream(output, RAW_IO_BUFFER_SIZE))
    data.write(ASTRO_RAW_MAGIC)
    data.writeInt(ASTRO_RAW_VERSION)
    data.writeInt(metadata.width)
    data.writeInt(metadata.height)
    data.writeInt(metadata.sampleShift)
    data.writeInt(metadata.cfaArrangement)
    data.writeInt(metadata.whiteLevel)
    metadata.blackLevels.forEach(data::writeFloat)
    metadata.colorGains.forEach(data::writeFloat)
    metadata.colorTransform.forEach(data::writeFloat)
    data.writeLong(metadata.exposureTimeNs)
    data.writeInt(metadata.sensitivityIso)
    data.writeInt(metadata.postRawSensitivityBoost)
    data.writeInt(metadata.sensorOrientation)
    data.writeInt(requiredBytes.toInt())
    data.flush()

    val deflater = Deflater(Deflater.BEST_SPEED)
    try {
        val compressed = DeflaterOutputStream(
            data,
            deflater,
            RAW_IO_BUFFER_SIZE,
            true
        )
        val chunk = ByteArray(RAW_IO_BUFFER_SIZE)
        var chunkSize = 0
        for (y in 0 until metadata.height) {
            val rowStart = sourceStart + y * rowStride
            for (x in 0 until metadata.width) {
                val sampleOffset = rowStart + x * pixelStride
                chunk[chunkSize++] = source.get(sampleOffset)
                chunk[chunkSize++] = source.get(sampleOffset + 1)
                if (chunkSize == chunk.size) {
                    compressed.write(chunk)
                    chunkSize = 0
                }
            }
        }
        if (chunkSize > 0) {
            compressed.write(chunk, 0, chunkSize)
        }
        compressed.finish()
        compressed.flush()
    } finally {
        deflater.end()
    }
}

fun readAstroRaw(input: InputStream): AstroRawFrame {
    val data = DataInputStream(BufferedInputStream(input, RAW_IO_BUFFER_SIZE))
    val magic = ByteArray(ASTRO_RAW_MAGIC.size)
    data.readFully(magic)
    require(magic.contentEquals(ASTRO_RAW_MAGIC)) { "Not an AstroPhoto RAW sidecar" }
    require(data.readInt() == ASTRO_RAW_VERSION) { "Unsupported AstroPhoto RAW version" }
    val metadata = AstroRawMetadata(
        width = data.readInt(),
        height = data.readInt(),
        sampleShift = data.readInt(),
        cfaArrangement = data.readInt(),
        whiteLevel = data.readInt(),
        blackLevels = List(4) { data.readFloat() },
        colorGains = List(4) { data.readFloat() },
        colorTransform = List(9) { data.readFloat() },
        exposureTimeNs = data.readLong(),
        sensitivityIso = data.readInt(),
        postRawSensitivityBoost = data.readInt(),
        sensorOrientation = data.readInt()
    )
    val payloadBytes = data.readInt()
    require(payloadBytes == metadata.pixelCount * 2) { "RAW payload length is invalid" }

    val samples = ShortArray(metadata.pixelCount)
    val inflated = InflaterInputStream(data)
    val chunk = ByteArray(RAW_IO_BUFFER_SIZE)
    var sampleIndex = 0
    var pendingLowByte = -1
    while (sampleIndex < samples.size) {
        val count = inflated.read(chunk)
        require(count >= 0) { "RAW payload ended unexpectedly" }
        var index = 0
        if (pendingLowByte >= 0 && count > 0) {
            samples[sampleIndex++] = (
                pendingLowByte or ((chunk[0].toInt() and 0xFF) shl 8)
                ).toShort()
            pendingLowByte = -1
            index = 1
        }
        while (index + 1 < count && sampleIndex < samples.size) {
            val low = chunk[index].toInt() and 0xFF
            val high = chunk[index + 1].toInt() and 0xFF
            samples[sampleIndex++] = (low or (high shl 8)).toShort()
            index += 2
        }
        if (index < count && sampleIndex < samples.size) {
            pendingLowByte = chunk[index].toInt() and 0xFF
        }
    }
    require(pendingLowByte < 0) { "RAW payload has an incomplete sample" }
    return AstroRawFrame(metadata, samples)
}

internal fun rawSampleShiftForWhiteLevel(whiteLevel: Int): Int {
    require(whiteLevel > 0)
    val significantBits = Int.SIZE_BITS - whiteLevel.countLeadingZeroBits()
    return (16 - significantBits).coerceIn(0, 15)
}

internal fun detectRawSampleShift(
    rawLittleEndian16: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    whiteLevel: Int
): Int {
    val packedShift = rawSampleShiftForWhiteLevel(whiteLevel)
    if (packedShift == 0) return 0
    require(width > 0 && height > 0 && rowStride >= width * pixelStride && pixelStride >= 2)
    val source = rawLittleEndian16.duplicate()
    val sourceBytes = (height - 1).toLong() * rowStride +
        (width - 1).toLong() * pixelStride + 2L
    if (source.remaining() < sourceBytes.toInt()) source.clear()
    require(source.remaining() >= sourceBytes.toInt()) { "RAW buffer is shorter than expected" }
    val start = source.position()
    val sampleStep = kotlin.math.sqrt(width.toLong() * height / 4096.0)
        .toInt()
        .coerceAtLeast(1)
    val lowMask = (1 shl packedShift) - 1
    var maximum = 0
    var sampled = 0
    var alignedSamples = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val offset = start + y * rowStride + x * pixelStride
            val value = (source.get(offset).toInt() and 0xFF) or
                ((source.get(offset + 1).toInt() and 0xFF) shl 8)
            maximum = maxOf(maximum, value)
            if (value and lowMask == 0) alignedSamples++
            sampled++
            x += sampleStep
        }
        y += sampleStep
    }
    val appearsLeftAligned = maximum > whiteLevel * 2L &&
        (maximum ushr packedShift) <= whiteLevel * 2L &&
        alignedSamples * 10L >= sampled * 9L
    return if (appearsLeftAligned) packedShift else 0
}

private const val MAX_ASTRO_RAW_PIXELS = 100_000_000L
private const val RAW_IO_BUFFER_SIZE = 64 * 1024
private const val ASTRO_RAW_VERSION = 1
private val ASTRO_RAW_MAGIC = "ASTRAW01".encodeToByteArray()
