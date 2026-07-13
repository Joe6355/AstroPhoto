package com.example.astrophoto

internal enum class ProcessedMediaCollection {
    IMAGES
}

internal data class ProcessedImageDestination(
    val collection: ProcessedMediaCollection,
    val relativePath: String,
    val mimeType: String
)

internal data class SavedProcessedImage(
    val fileName: String,
    val displayPath: String,
    val contentUri: String?,
    val filePath: String?
)

internal fun processedImageDestination(sessionFolderName: String) =
    ProcessedImageDestination(
        collection = ProcessedMediaCollection.IMAGES,
        relativePath = "Pictures/AstroPhoto/$sessionFolderName/Processed/",
        mimeType = "image/jpeg"
    )

internal fun retainedMediaStoreImage(
    destination: ProcessedImageDestination,
    actualFileName: String,
    insertedUri: String
) = SavedProcessedImage(
    fileName = actualFileName,
    displayPath = "${destination.relativePath}$actualFileName",
    contentUri = insertedUri,
    filePath = null
)
