package com.example.astrophoto

import kotlin.random.Random

internal object SyntheticImageTestData {
    fun gray(value: Int): Int {
        val channel = value.coerceIn(0, 255)
        return (0xFF shl 24) or (channel shl 16) or (channel shl 8) or channel
    }

    fun uniform(width: Int = 64, height: Int = 64, value: Int = 20) =
        ArgbPixelImage(width, height, IntArray(width * height) { gray(value) })

    fun texture(width: Int = 72, height: Int = 64): ArgbPixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = gray(20 + (x * 37 + y * 53 + x * y * 7) % 150)
            }
        }
        return ArgbPixelImage(width, height, pixels)
    }

    fun gradient(width: Int = 72, height: Int = 64): ArgbPixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = gray(15 + (x * 2 + y) % 150)
            }
        }
        return ArgbPixelImage(width, height, pixels)
    }

    fun stars(
        width: Int = 64,
        height: Int = 64,
        background: Int = 20,
        points: List<Triple<Int, Int, Int>>
    ): ArgbPixelImage {
        val pixels = IntArray(width * height) { gray(background) }
        points.forEach { (x, y, value) -> pixels[y * width + x] = gray(value) }
        return ArgbPixelImage(width, height, pixels)
    }

    fun noisy(image: ArgbPixelImage, seed: Int, amplitude: Int): ArgbPixelImage {
        val random = Random(seed)
        val pixels = image.pixels.map { color ->
            gray(pixelLuminance(color) + random.nextInt(-amplitude, amplitude + 1))
        }.toIntArray()
        return ArgbPixelImage(image.width, image.height, pixels)
    }

    fun translated(image: ArgbPixelImage, right: Int, down: Int, fill: Int = 0): ArgbPixelImage {
        val output = IntArray(image.pixels.size) { gray(fill) }
        for (y in 0 until image.height) {
            val destinationY = y + down
            if (destinationY !in 0 until image.height) continue
            for (x in 0 until image.width) {
                val destinationX = x + right
                if (destinationX in 0 until image.width) {
                    output[destinationY * image.width + destinationX] = image.pixelAt(x, y)
                }
            }
        }
        return ArgbPixelImage(image.width, image.height, output)
    }
}
