package com.example.astrophoto

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ManualCameraCapabilities(
    val cameraId: String,
    val exposureRangeNs: LongRange?,
    val isoRange: IntRange?,
    val minimumFocusDistance: Float?,
    val supportsManualSensor: Boolean,
    val supportsManualFocus: Boolean,
    val supportsJpegCapture: Boolean,
    val supportsRawCapture: Boolean
)

data class ManualCameraParameters(
    val exposureTimeNs: Long,
    val iso: Int,
    val focusDistance: Float,
    val focusMode: CameraFocusMode,
    val applyLongExposureToPreview: Boolean
)

enum class CameraFocusMode {
    AF,
    MF,
    INFINITY
}

enum class CameraCaptureStage {
    CAPTURING,
    SAVING
}

private enum class CaptureType {
    JPEG,
    TEST_JPEG,
    RAW
}

class CameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val onCameraError: (String) -> Unit = {},
    private val onCapabilitiesAvailable: (ManualCameraCapabilities) -> Unit = {},
    private val onCameraStatus: (String) -> Unit = {},
    private val onExposureAnalysis: (ExposureAnalysis) -> Unit = {},
    private val onExposureAnalyzerUnavailable: (String) -> Unit = {}
) : TextureView(context, attrs), DefaultLifecycleObserver {

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val lifecycleOwner = context.findActivity()
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var sensorOrientation = 0
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var manualCapabilities: ManualCameraCapabilities? = null
    private var manualParameters = ManualCameraParameters(
        exposureTimeNs = 33_333_333L,
        iso = 400,
        focusDistance = 0f,
        focusMode = CameraFocusMode.INFINITY,
        applyLongExposureToPreview = false
    )
    private var active = false
    private var openingCamera = false
    private var previewStarted = false
    private var captureInProgress = false
    private var activeCaptureType: CaptureType? = null
    private var captureResultCallback: ((Result<String>) -> Unit)? = null
    private var testShotResultCallback: ((Result<ByteArray>) -> Unit)? = null
    private var captureStageCallback: ((CameraCaptureStage) -> Unit)? = null
    private var requestedCaptureFileName: String? = null
    private var requestedCaptureRelativeDirectory: String? = null
    private var jpegQuality = 92
    private var pendingRawImage: Image? = null
    private var pendingRawResult: TotalCaptureResult? = null
    private var exposureAnalysisEnabled = true
    private val exposureAnalyzer = ExposureAnalyzer(
        textureView = this,
        onAnalysis = onExposureAnalysis,
        onUnavailable = { message ->
            exposureAnalysisEnabled = false
            onExposureAnalyzerUnavailable(message)
        }
    )

    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                startCamera()
                startExposureAnalyzerIfNeeded()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                previewSize?.let { configureTransform(width, height, it) }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                exposureAnalyzer.stop()
                closeCamera()
                stopBackgroundThread()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleOwner?.lifecycle?.addObserver(this)
        if (lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            active = true
            startCamera()
            startExposureAnalyzerIfNeeded()
        }
    }

    override fun onDetachedFromWindow() {
        active = false
        exposureAnalyzer.stop()
        lifecycleOwner?.lifecycle?.removeObserver(this)
        closeCamera()
        stopBackgroundThread()
        super.onDetachedFromWindow()
    }

    override fun onResume(owner: LifecycleOwner) {
        active = true
        startCamera()
        startExposureAnalyzerIfNeeded()
    }

    override fun onPause(owner: LifecycleOwner) {
        active = false
        exposureAnalyzer.stop()
        closeCamera()
        stopBackgroundThread()
    }

    fun updateManualParameters(
        exposureTimeNs: Long,
        iso: Int,
        focusDistance: Float,
        focusMode: CameraFocusMode,
        applyLongExposureToPreview: Boolean
    ) {
        val updatedParameters = ManualCameraParameters(
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            focusDistance = focusDistance,
            focusMode = focusMode,
            applyLongExposureToPreview = applyLongExposureToPreview
        )
        if (updatedParameters == manualParameters) return
        manualParameters = updatedParameters

        backgroundHandler?.removeCallbacks(previewUpdateRunnable)
        backgroundHandler?.postDelayed(previewUpdateRunnable, 200L)
    }

    fun setExposureAnalysisEnabled(enabled: Boolean) {
        if (exposureAnalysisEnabled == enabled) return
        exposureAnalysisEnabled = enabled
        if (enabled) {
            startExposureAnalyzerIfNeeded()
        } else {
            exposureAnalyzer.stop()
        }
    }

    fun setJpegQuality(quality: Int) {
        jpegQuality = quality.coerceIn(1, 100)
    }

    private fun startExposureAnalyzerIfNeeded() {
        if (exposureAnalysisEnabled && active && isAvailable && previewStarted) {
            exposureAnalyzer.start()
        }
    }

    private val previewUpdateRunnable = Runnable {
        val requestBuilder = previewRequestBuilder ?: return@Runnable
        applyManualParameters(requestBuilder, forPreview = true)
        submitRepeatingRequest(requestBuilder)
    }

    fun captureJpeg(
        fileName: String? = null,
        relativeDirectory: String? = null,
        onStageChanged: (CameraCaptureStage) -> Unit = {},
        onResult: (Result<String>) -> Unit
    ) {
        if (captureInProgress) {
            onResult(Result.failure(IllegalStateException("Съёмка уже выполняется")))
            return
        }

        val handler = backgroundHandler
        if (handler == null) {
            onResult(Result.failure(IllegalStateException("Камера ещё не готова")))
            return
        }

        captureInProgress = true
        activeCaptureType = CaptureType.JPEG
        requestedCaptureFileName = fileName
        requestedCaptureRelativeDirectory = relativeDirectory
        captureStageCallback = onStageChanged
        captureResultCallback = onResult
        reportStatus("capture started: JPEG")
        notifyCaptureStage(CameraCaptureStage.CAPTURING)
        handler.post {
            val camera = cameraDevice
            val session = captureSession
            val jpegReader = imageReader
            if (camera == null || session == null || jpegReader == null || !active) {
                finishCapture(Result.failure(IllegalStateException("Камера не готова к съёмке")))
                return@post
            }

            try {
                val captureRequest = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
                ).apply {
                    addTarget(jpegReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, calculateJpegOrientation())
                }
                applyManualParameters(captureRequest)

                session.capture(
                    captureRequest.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            finishCapture(
                                Result.failure(
                                    IllegalStateException("Камера не смогла сделать JPEG-снимок")
                                )
                            )
                        }

                        override fun onCaptureSequenceAborted(
                            session: CameraCaptureSession,
                            sequenceId: Int
                        ) {
                            finishCapture(
                                Result.failure(IllegalStateException("Съёмка была прервана"))
                            )
                        }
                    },
                    handler
                )
            } catch (exception: Exception) {
                finishCapture(
                    Result.failure(
                        IllegalStateException(
                            exception.message ?: "Не удалось запустить JPEG-съёмку",
                            exception
                        )
                    )
                )
            }
        }
    }

    fun captureRawDng(
        fileName: String? = null,
        relativeDirectory: String? = null,
        onStageChanged: (CameraCaptureStage) -> Unit = {},
        onResult: (Result<String>) -> Unit
    ) {
        if (captureInProgress) {
            onResult(Result.failure(IllegalStateException("Съёмка уже выполняется")))
            return
        }

        val handler = backgroundHandler
        if (handler == null) {
            onResult(Result.failure(IllegalStateException("Камера ещё не готова")))
            return
        }

        captureInProgress = true
        activeCaptureType = CaptureType.RAW
        requestedCaptureFileName = fileName
        requestedCaptureRelativeDirectory = relativeDirectory
        captureStageCallback = onStageChanged
        captureResultCallback = onResult
        reportStatus("capture started: RAW/DNG")
        notifyCaptureStage(CameraCaptureStage.CAPTURING)
        handler.post {
            pendingRawImage?.close()
            pendingRawImage = null
            pendingRawResult = null

            val camera = cameraDevice
            val session = captureSession
            val reader = rawImageReader
            if (camera == null || session == null || reader == null || !active) {
                finishCapture(
                    Result.failure(IllegalStateException("RAW-съёмка недоступна"))
                )
                return@post
            }

            try {
                val captureRequest = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
                ).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }
                applyManualParameters(captureRequest)

                session.capture(
                    captureRequest.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            if (activeCaptureType != CaptureType.RAW) return
                            pendingRawResult = result
                            saveRawWhenReady()
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            clearPendingRaw()
                            finishCapture(
                                Result.failure(
                                    IllegalStateException("Камера не смогла сделать RAW-снимок")
                                )
                            )
                        }

                        override fun onCaptureSequenceAborted(
                            session: CameraCaptureSession,
                            sequenceId: Int
                        ) {
                            clearPendingRaw()
                            finishCapture(
                                Result.failure(IllegalStateException("RAW-съёмка была прервана"))
                            )
                        }
                    },
                    handler
                )
            } catch (exception: Exception) {
                clearPendingRaw()
                finishCapture(
                    Result.failure(
                        IllegalStateException(
                            exception.message ?: "Не удалось запустить RAW-съёмку",
                            exception
                        )
                    )
                )
            }
        }
    }

    fun captureTestJpeg(
        onStageChanged: (CameraCaptureStage) -> Unit = {},
        onResult: (Result<ByteArray>) -> Unit
    ) {
        if (captureInProgress) {
            onResult(Result.failure(IllegalStateException("Съёмка уже выполняется")))
            return
        }
        val handler = backgroundHandler
        if (handler == null) {
            onResult(Result.failure(IllegalStateException("Камера ещё не готова")))
            return
        }

        captureInProgress = true
        activeCaptureType = CaptureType.TEST_JPEG
        testShotResultCallback = onResult
        captureStageCallback = onStageChanged
        reportStatus("capture started: test JPEG")
        notifyCaptureStage(CameraCaptureStage.CAPTURING)
        handler.post {
            val camera = cameraDevice
            val session = captureSession
            val jpegReader = imageReader
            if (camera == null || session == null || jpegReader == null || !active) {
                finishTestCapture(
                    Result.failure(
                        IllegalStateException("Камера не готова к пробному кадру")
                    )
                )
                return@post
            }

            try {
                val captureRequest = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
                ).apply {
                    addTarget(jpegReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, calculateJpegOrientation())
                }
                applyManualParameters(captureRequest)
                session.capture(
                    captureRequest.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            finishTestCapture(
                                Result.failure(
                                    IllegalStateException(
                                        "Камера не смогла сделать пробный кадр"
                                    )
                                )
                            )
                        }

                        override fun onCaptureSequenceAborted(
                            session: CameraCaptureSession,
                            sequenceId: Int
                        ) {
                            finishTestCapture(
                                Result.failure(
                                    IllegalStateException(
                                        "Пробный кадр был прерван"
                                    )
                                )
                            )
                        }
                    },
                    handler
                )
            } catch (exception: Exception) {
                finishTestCapture(
                    Result.failure(
                        IllegalStateException(
                            exception.message
                                ?: "Не удалось запустить пробный кадр",
                            exception
                        )
                    )
                )
            }
        }
    }

    private fun startCamera() {
        if (!active || !isAvailable || cameraDevice != null || openingCamera) return
        startBackgroundThread()
        openRearCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openRearCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            reportError("Нет разрешения CAMERA")
            return
        }

        val manager = cameraManager ?: run {
            reportError("Системная служба камеры недоступна")
            return
        }

        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                reportError("Основная задняя камера не найдена")
                return
            }

            val characteristics = manager.getCameraCharacteristics(cameraId)
            cameraCharacteristics = characteristics
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val exposureRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            val isoRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val minimumFocusDistance = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            )
            sensorOrientation = characteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION
            ) ?: 0
            val streamConfigurationMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            val jpegSize = streamConfigurationMap
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height }
            val jpegCaptureAvailable = jpegSize?.let(::createJpegImageReader) == true
            val rawCapabilityAvailable = capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            ) == true
            val rawSize = if (rawCapabilityAvailable) {
                streamConfigurationMap
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height }
            } else {
                null
            }
            val rawCaptureAvailable = rawSize?.let(::createRawImageReader) == true
            val cameraCapabilities = ManualCameraCapabilities(
                cameraId = cameraId,
                exposureRangeNs = exposureRange?.let { it.lower..it.upper },
                isoRange = isoRange?.let { it.lower..it.upper },
                minimumFocusDistance = minimumFocusDistance,
                supportsManualSensor = capabilities?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                ) == true,
                supportsManualFocus = (minimumFocusDistance ?: 0f) > 0f,
                supportsJpegCapture = jpegCaptureAvailable,
                supportsRawCapture = rawCaptureAvailable
            )
            manualCapabilities = cameraCapabilities
            post { onCapabilitiesAvailable(cameraCapabilities) }

            val sizes = streamConfigurationMap?.getOutputSizes(SurfaceTexture::class.java)
            val selectedSize = choosePreviewSize(sizes) ?: run {
                reportError("Камера не предоставила размер для preview")
                return
            }

            previewSize = selectedSize
            surfaceTexture?.setDefaultBufferSize(selectedSize.width, selectedSize.height)
            configureTransform(width, height, selectedSize)

            openingCamera = true
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (exception: Exception) {
            openingCamera = false
            reportError(
                exception.message ?: "Не удалось открыть заднюю камеру"
            )
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            openingCamera = false
            if (!active || !isAvailable) {
                camera.close()
                return
            }
            cameraDevice = camera
            reportStatus("camera opened")
            createPreviewSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            openingCamera = false
            camera.close()
            closeCamera()
            reportError("Камера была отключена")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            openingCamera = false
            camera.close()
            closeCamera()
            reportError("Ошибка открытия камеры: ${cameraErrorName(error)}")
        }
    }

    private fun createJpegImageReader(size: Size): Boolean {
        return try {
            imageReader?.close()
            imageReader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.JPEG,
                2
            ).apply {
                setOnImageAvailableListener(
                    { reader -> saveAvailableJpeg(reader) },
                    backgroundHandler
                )
            }
            true
        } catch (exception: Exception) {
            imageReader?.close()
            imageReader = null
            reportError(
                exception.message ?: "Не удалось подготовить JPEG-съёмку"
            )
            false
        }
    }

    private fun createRawImageReader(size: Size): Boolean {
        return try {
            rawImageReader?.close()
            rawImageReader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.RAW_SENSOR,
                2
            ).apply {
                setOnImageAvailableListener(
                    { reader -> handleAvailableRaw(reader) },
                    backgroundHandler
                )
            }
            true
        } catch (exception: Exception) {
            rawImageReader?.close()
            rawImageReader = null
            reportError(
                exception.message ?: "Не удалось подготовить RAW-съёмку"
            )
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun createPreviewSession(camera: CameraDevice) {
        val texture = surfaceTexture ?: return
        val surface = Surface(texture)
        previewSurface = surface

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
            previewRequestBuilder = requestBuilder
            applyManualParameters(requestBuilder, forPreview = true)

            val outputSurfaces = buildList {
                add(surface)
                imageReader?.surface?.let(::add)
                rawImageReader?.surface?.let(::add)
            }
            camera.createCaptureSession(
                outputSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice != camera || !active) {
                            session.close()
                            return
                        }
                        captureSession = session
                        reportStatus("session configured")
                        submitRepeatingRequest(requestBuilder)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        reportError("Не удалось настроить поток preview")
                    }
                },
                backgroundHandler
            )
        } catch (exception: Exception) {
            surface.release()
            previewSurface = null
            reportError(exception.message ?: "Не удалось создать поток preview")
        }
    }

    private fun applyManualParameters(
        requestBuilder: CaptureRequest.Builder,
        forPreview: Boolean = false
    ) {
        val capabilities = manualCapabilities ?: return
        val parameters = manualParameters
        val exposureRange = capabilities.exposureRangeNs
        val isoRange = capabilities.isoRange
        val automaticPreviewExposure = forPreview &&
            !parameters.applyLongExposureToPreview

        requestBuilder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )

        if (automaticPreviewExposure) {
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
        } else if (
            capabilities.supportsManualSensor &&
            exposureRange != null &&
            isoRange != null
        ) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            requestBuilder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                parameters.exposureTimeNs.coerceIn(exposureRange.first, exposureRange.last)
            )
            requestBuilder.set(
                CaptureRequest.SENSOR_SENSITIVITY,
                parameters.iso.coerceIn(isoRange.first, isoRange.last)
            )
        } else {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        val minimumFocusDistance = capabilities.minimumFocusDistance
        when (parameters.focusMode) {
            CameraFocusMode.AF -> {
                requestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            CameraFocusMode.MF -> {
                if (capabilities.supportsManualFocus && minimumFocusDistance != null) {
                    requestBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    requestBuilder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        parameters.focusDistance.coerceIn(0f, minimumFocusDistance)
                    )
                }
            }

            CameraFocusMode.INFINITY -> {
                requestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
        }
    }

    private fun submitRepeatingRequest(requestBuilder: CaptureRequest.Builder) {
        val session = captureSession ?: return
        try {
            session.setRepeatingRequest(
                requestBuilder.build(),
                null,
                backgroundHandler
            )
            if (!previewStarted) {
                previewStarted = true
                reportStatus("preview repeating started")
            }
            startExposureAnalyzerIfNeeded()
        } catch (exception: Exception) {
            reportError(exception.message ?: "Не удалось обновить параметры preview")
        }
    }

    private fun saveAvailableJpeg(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (exception: Exception) {
            finishActiveJpegCaptureWithError(exception)
            return
        } ?: return

        try {
            val captureType = activeCaptureType
            if (
                captureType != CaptureType.JPEG &&
                captureType != CaptureType.TEST_JPEG
            ) {
                return
            }
            val buffer = image.planes.firstOrNull()?.buffer
                ?: error("Камера вернула пустой JPEG")
            val jpegBytes = ByteArray(buffer.remaining())
            buffer.get(jpegBytes)
            if (captureType == CaptureType.TEST_JPEG) {
                notifyCaptureStage(CameraCaptureStage.SAVING)
                reportStatus("test JPEG captured")
                finishTestCapture(Result.success(jpegBytes))
                return
            }
            notifyCaptureStage(CameraCaptureStage.SAVING)
            val fileName = saveJpegToGallery(
                jpegBytes,
                requestedCaptureFileName,
                requestedCaptureRelativeDirectory
            )
            reportStatus("capture saved: $fileName")
            finishCapture(Result.success(fileName))
        } catch (exception: Exception) {
            finishActiveJpegCaptureWithError(
                IllegalStateException(
                    exception.message ?: "Не удалось обработать JPEG",
                    exception
                )
            )
        } finally {
            image.close()
        }
    }

    private fun handleAvailableRaw(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (exception: Exception) {
            finishCapture(Result.failure(exception))
            return
        } ?: return

        if (activeCaptureType != CaptureType.RAW) {
            image.close()
            return
        }

        pendingRawImage?.close()
        pendingRawImage = image
        saveRawWhenReady()
    }

    private fun saveRawWhenReady() {
        val image = pendingRawImage ?: return
        val captureResult = pendingRawResult ?: return
        val characteristics = cameraCharacteristics
        if (characteristics == null) {
            clearPendingRaw()
            finishCapture(
                Result.failure(IllegalStateException("Характеристики RAW недоступны"))
            )
            return
        }

        pendingRawImage = null
        pendingRawResult = null
        try {
            notifyCaptureStage(CameraCaptureStage.SAVING)
            val fileName = saveDngToGallery(
                image,
                characteristics,
                captureResult,
                requestedCaptureFileName,
                requestedCaptureRelativeDirectory
            )
            reportStatus("capture saved: $fileName")
            finishCapture(Result.success(fileName))
        } catch (exception: Exception) {
            finishCapture(
                Result.failure(
                    IllegalStateException(
                        exception.message ?: "Не удалось сохранить DNG",
                        exception
                    )
                )
            )
        } finally {
            image.close()
        }
    }

    private fun saveDngToGallery(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        requestedFileName: String?,
        relativeDirectory: String?
    ): String {
        val fileName = requestedFileName ?: "AstroPhoto_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.dng"
        val dngCreator = DngCreator(characteristics, captureResult)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        galleryRelativePath(relativeDirectory)
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val imageUri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: error("Не удалось создать DNG в MediaStore")

                try {
                    resolver.openOutputStream(imageUri)?.use { output ->
                        dngCreator.writeImage(output, image)
                    } ?: error("Не удалось открыть DNG для записи")

                    val completedValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(imageUri, completedValues, null, null)
                } catch (exception: Exception) {
                    resolver.delete(imageUri, null, null)
                    throw exception
                }
            } else {
                saveLegacyDng(image, fileName, dngCreator, relativeDirectory)
            }
        } finally {
            dngCreator.close()
        }

        return fileName
    }

    @Suppress("DEPRECATION")
    private fun saveLegacyDng(
        image: Image,
        fileName: String,
        dngCreator: DngCreator,
        relativeDirectory: String?
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            error("Нет разрешения на сохранение DNG")
        }

        val picturesDirectory = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val astroPhotoDirectory = File(
            picturesDirectory,
            relativeDirectory ?: "AstroPhoto"
        )
        if (!astroPhotoDirectory.exists() && !astroPhotoDirectory.mkdirs()) {
            error("Не удалось создать папку Pictures/AstroPhoto")
        }

        val dngFile = File(astroPhotoDirectory, fileName)
        FileOutputStream(dngFile).use { output ->
            dngCreator.writeImage(output, image)
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(dngFile.absolutePath),
            arrayOf("image/x-adobe-dng"),
            null
        )
    }

    private fun clearPendingRaw() {
        pendingRawImage?.close()
        pendingRawImage = null
        pendingRawResult = null
    }

    private fun saveJpegToGallery(
        jpegBytes: ByteArray,
        requestedFileName: String?,
        relativeDirectory: String?
    ): String {
        val fileName = requestedFileName ?: "AstroPhoto_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    galleryRelativePath(relativeDirectory)
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val imageUri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Галерея не предоставила место для файла")

            try {
                resolver.openOutputStream(imageUri)?.use { output ->
                    output.write(jpegBytes)
                } ?: error("Не удалось открыть файл для записи")

                val completedValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(imageUri, completedValues, null, null)
            } catch (exception: Exception) {
                resolver.delete(imageUri, null, null)
                throw exception
            }
        } else {
            saveLegacyJpeg(jpegBytes, fileName, relativeDirectory)
        }

        return fileName
    }

    private fun galleryRelativePath(relativeDirectory: String?): String =
        "${Environment.DIRECTORY_PICTURES}/${relativeDirectory ?: "AstroPhoto"}"

    @Suppress("DEPRECATION")
    private fun saveLegacyJpeg(
        jpegBytes: ByteArray,
        fileName: String,
        relativeDirectory: String?
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            error("Нет разрешения на сохранение фото")
        }

        val picturesDirectory = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val astroPhotoDirectory = File(
            picturesDirectory,
            relativeDirectory ?: "AstroPhoto"
        )
        if (!astroPhotoDirectory.exists() && !astroPhotoDirectory.mkdirs()) {
            error("Не удалось создать папку Pictures/AstroPhoto")
        }

        val photoFile = File(astroPhotoDirectory, fileName)
        FileOutputStream(photoFile).use { output ->
            output.write(jpegBytes)
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(photoFile.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
    }

    @Synchronized
    private fun finishCapture(result: Result<String>) {
        if (!captureInProgress) return
        captureInProgress = false
        activeCaptureType = null
        val callback = captureResultCallback
        captureResultCallback = null
        captureStageCallback = null
        requestedCaptureFileName = null
        requestedCaptureRelativeDirectory = null
        post { callback?.invoke(result) }
    }

    @Synchronized
    private fun finishTestCapture(result: Result<ByteArray>) {
        if (!captureInProgress || activeCaptureType != CaptureType.TEST_JPEG) return
        captureInProgress = false
        activeCaptureType = null
        val callback = testShotResultCallback
        testShotResultCallback = null
        captureStageCallback = null
        requestedCaptureFileName = null
        requestedCaptureRelativeDirectory = null
        post { callback?.invoke(result) }
    }

    private fun finishActiveJpegCaptureWithError(error: Throwable) {
        if (activeCaptureType == CaptureType.TEST_JPEG) {
            finishTestCapture(Result.failure(error))
        } else {
            finishCapture(Result.failure(error))
        }
    }

    private fun notifyCaptureStage(stage: CameraCaptureStage) {
        val callback = captureStageCallback ?: return
        post { callback(stage) }
    }

    private fun calculateJpegOrientation(): Int {
        val displayRotation = display?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - displayDegrees + 360) % 360
    }

    private fun choosePreviewSize(sizes: Array<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        val reasonableSizes = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        return (reasonableSizes.ifEmpty { sizes.toList() })
            .maxByOrNull { it.width.toLong() * it.height }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int, size: Size) {
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                val bufferRect = RectF(
                    0f,
                    0f,
                    size.height.toFloat(),
                    size.width.toFloat()
                )
                bufferRect.offset(
                    centerX - bufferRect.centerX(),
                    centerY - bufferRect.centerY()
                )
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = maxOf(
                    viewHeight.toFloat() / size.height,
                    viewWidth.toFloat() / size.width
                )
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(90f * (rotation - 2), centerX, centerY)
            }

            Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
        }

        setTransform(matrix)
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("AstroPhotoCamera").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        val thread = backgroundThread ?: return
        thread.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun closeCamera() {
        openingCamera = false
        previewStarted = false
        exposureAnalyzer.stop()
        backgroundHandler?.removeCallbacks(previewUpdateRunnable)
        if (activeCaptureType == CaptureType.TEST_JPEG) {
            finishTestCapture(
                Result.failure(IllegalStateException("Пробный кадр остановлен"))
            )
        } else {
            finishCapture(Result.failure(IllegalStateException("Съёмка остановлена")))
        }
        captureSession?.close()
        captureSession = null
        previewRequestBuilder = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        rawImageReader?.close()
        rawImageReader = null
        val handler = backgroundHandler
        if (handler != null) {
            handler.post { clearPendingRaw() }
        } else {
            clearPendingRaw()
        }
        cameraCharacteristics = null
        previewSurface?.release()
        previewSurface = null
    }

    private fun reportError(message: String) {
        Log.e("AstroPhotoCamera", message)
        post { onCameraError(message) }
    }

    private fun reportStatus(message: String) {
        Log.d("AstroPhotoCamera", message)
        post { onCameraStatus(message) }
    }

    private fun cameraErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "камера уже используется"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "открыто слишком много камер"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "камера отключена системой"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ошибка устройства камеры"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ошибка службы камеры"
        else -> "код $error"
    }
}

private fun Context.findActivity(): ComponentActivity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is ComponentActivity) return currentContext
        currentContext = currentContext.baseContext
    }
    return currentContext as? ComponentActivity
}
