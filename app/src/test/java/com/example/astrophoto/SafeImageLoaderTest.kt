package com.example.astrophoto

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeImageLoaderTest {
    @Test
    fun nullableDecoderResultDoesNotMakeExistingSourceMissing() {
        val decoded = useRequiredImageStream(
            ByteArrayInputStream(byteArrayOf(1)),
            "content://existing"
        ) { null }

        assertNull(decoded)
    }

    @Test
    fun missingSourceStillReportsFileNotFound() {
        assertThrows(FileNotFoundException::class.java) {
            useRequiredImageStream(null, "content://missing") { Unit }
        }
    }
}
