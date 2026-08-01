package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object ReplayDiagnosticHashing {
    fun argbBytes(image: ArgbPixelImage): ByteArray {
        val buffer = ByteBuffer.allocate(image.pixels.size * Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        image.pixels.forEach(buffer::putInt)
        return buffer.array()
    }

    fun alphaFloat32LittleEndianBytes(alpha: AlphaMask): ByteArray {
        val buffer = ByteBuffer.allocate(alpha.width * alpha.height * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until alpha.height) for (x in 0 until alpha.width) {
            buffer.putFloat(alpha.alphaAt(x, y))
        }
        return buffer.array()
    }

    fun maskBytes(mask: SkyMask): ByteArray = mask.copyPixels().let { pixels ->
        ByteArray(pixels.size) { if (pixels[it]) 1 else 0 }
    }

    fun sha256Argb(image: ArgbPixelImage): String = sha256(argbBytes(image))

    fun sha256Alpha(alpha: AlphaMask): String = sha256(alphaFloat32LittleEndianBytes(alpha))

    fun sha256Mask(mask: SkyMask): String = sha256(maskBytes(mask))

    fun sha256File(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
