package com.joe6355.astrophoto.processing.jpeg.v2.storage

import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidateType

class ResultCandidateStore(
    private val files: TemporaryPipelineFiles,
    private val pixelFormat: FileBackedPixelFormat = FileBackedPixelFormat.LINEAR_RGB_16
) {
    private val candidates = linkedMapOf<ResultCandidateType, FileBackedImage>()
    private var sequence = 0

    fun createWriter(type: ResultCandidateType, width: Int, height: Int): FileBackedImageWriter {
        require(type !in candidates) { "Candidate $type already exists" }
        files.requireSpace(width.toLong() * height * pixelFormat.bytesPerPixel)
        return FileBackedImageWriter(
            files.file("candidate-${type.name.lowercase()}.argb"),
            width,
            height,
            pixelFormat
        )
    }

    fun register(type: ResultCandidateType, image: FileBackedImage): FileBackedImage {
        require(type !in candidates) { "Candidate $type already exists" }
        candidates[type] = image.validate()
        return image
    }

    fun createTemporaryWriter(label: String, width: Int, height: Int): FileBackedImageWriter {
        require(label.matches(Regex("[a-z0-9-]+")))
        files.requireSpace(width.toLong() * height * pixelFormat.bytesPerPixel)
        sequence++
        return FileBackedImageWriter(
            files.file("work-${sequence.toString().padStart(2, '0')}-$label.argb"),
            width,
            height,
            pixelFormat
        )
    }

    fun createFloatPlaneWriter(label: String, width: Int, height: Int): FileBackedFloatPlaneWriter {
        require(label.matches(Regex("[a-z0-9-]+")))
        files.requireSpace(width.toLong() * height * 4L)
        sequence++
        return FileBackedFloatPlaneWriter(
            files.file("plane-${sequence.toString().padStart(2, '0')}-$label.f32"),
            width,
            height
        )
    }

    fun deleteTemporary(image: FileBackedImage) {
        require(image !in candidates.values) { "Registered result candidates are owned until run cleanup" }
        files.deleteFile(image)
    }

    fun deleteTemporary(plane: FileBackedFloatPlane) {
        files.deleteFile(plane)
    }
}
