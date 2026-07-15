package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessedMediaStoreTest {
    @Test
    fun newProcessedJpegUsesImagesDestination() {
        val destination = processedImageDestination("Session_1")
        assertEquals(ProcessedMediaCollection.IMAGES, destination.collection)
        assertEquals("image/jpeg", destination.mimeType)
        assertEquals(
            "Pictures/AstroPhoto/Session_1/Processed/",
            destination.relativePath
        )
    }

    @Test
    fun newProcessedPngUsesSameSessionDestinationWithPngMime() {
        val destination = processedImageDestination("Session_1", "image/png")
        assertEquals(ProcessedMediaCollection.IMAGES, destination.collection)
        assertEquals("image/png", destination.mimeType)
        assertEquals(
            "Pictures/AstroPhoto/Session_1/Processed/",
            destination.relativePath
        )
    }

    @Test
    fun insertedUriIsReturnedWithoutReconstruction() {
        val insertedUri = "content://media/external_primary/images/media/42"
        val saved = retainedMediaStoreImage(
            destination = processedImageDestination("Session_1"),
            actualFileName = "Stacked_1.jpg",
            insertedUri = insertedUri
        )
        assertEquals(insertedUri, saved.contentUri)
        assertEquals("Stacked_1.jpg", saved.fileName)
        assertNull(saved.filePath)
    }
}
