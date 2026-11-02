package com.joe6355.astrophoto

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Opt-in diagnostic matrix, not a passing hardware acceptance claim. Writes only new QA cache files. */
class ManualCameraRequestProbeDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(120)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    @Test fun recordsManualRequestVariantsOnExposedRearCameras() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualCameraProbe") == "true")
        require(Build.MODEL == args.getString("manualCameraExpectedModel"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val tag = requireNotNull(args.getString("manualCameraTag"))
        require(tag.matches(Regex("[A-Za-z0-9_]{1,40}")))
        val directory = File(context.cacheDir, "manual-camera-probe/$tag")
        check(!directory.exists() && directory.mkdirs())
        val manager = context.getSystemService(CameraManager::class.java)
        val thread = HandlerThread("ManualCameraProbe").apply { start() }
        val handler = Handler(thread.looper)
        val rows = JSONArray()
        val proof = JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("diagnosticOnly", true).put("rows", rows)
        data class Variant(val name: String, val template: Int, val manualPreview: Boolean = false,
            val allControlsOff: Boolean = false)
        val variants = listOf(
            Variant("still", CameraDevice.TEMPLATE_STILL_CAPTURE),
            Variant("manual", CameraDevice.TEMPLATE_MANUAL),
            Variant("manual_off", CameraDevice.TEMPLATE_MANUAL, allControlsOff = true),
            Variant("still_manual_preview", CameraDevice.TEMPLATE_STILL_CAPTURE, manualPreview = true)
        )
        try {
            for (id in manager.cameraIdList.filter {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }) {
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) != true) continue
                val sizes = requireNotNull(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                val size = requireNotNull(sizes.getOutputSizes(ImageFormat.JPEG)).maxBy { it.width.toLong() * it.height }
                for (variant in variants) {
                    val row = JSONObject().put("cameraId", id).put("variant", variant.name)
                    rows.put(row)
                    val opened = CompletableFuture<CameraDevice>()
                    val configured = CompletableFuture<CameraCaptureSession>()
                    val firstPreview = CompletableFuture<TotalCaptureResult>()
                    val captured = CompletableFuture<TotalCaptureResult>()
                    val jpeg = CompletableFuture<ByteArray>()
                    val texture = SurfaceTexture(false).apply { setDefaultBufferSize(1280, 720) }
                    val surface = Surface(texture)
                    val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
                    var camera: CameraDevice? = null
                    var session: CameraCaptureSession? = null
                    fun manual(builder: CaptureRequest.Builder) {
                        builder.set(CaptureRequest.CONTROL_MODE, if (variant.allControlsOff)
                            CaptureRequest.CONTROL_MODE_OFF else CaptureRequest.CONTROL_MODE_AUTO)
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        builder.set(CaptureRequest.CONTROL_AF_MODE, if (variant.allControlsOff)
                            CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 16_666_667L)
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, 100)
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 16_666_667L)
                        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_MANUAL)
                        if (chars.availableCaptureRequestKeys.contains(CaptureRequest.CONTROL_ENABLE_ZSL)) {
                            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                        }
                    }
                    try {
                        reader.setOnImageAvailableListener({ source ->
                            source.acquireNextImage()?.use { image ->
                                val buffer = image.planes[0].buffer
                                jpeg.complete(ByteArray(buffer.remaining()).also { buffer.get(it) })
                                row.put("imageTimestamp", image.timestamp)
                            }
                        }, handler)
                        manager.openCamera(id, object : CameraDevice.StateCallback() {
                            override fun onOpened(device: CameraDevice) { opened.complete(device) }
                            override fun onDisconnected(device: CameraDevice) { device.close(); opened.completeExceptionally(IllegalStateException("Disconnected")) }
                            override fun onError(device: CameraDevice, error: Int) { device.close(); opened.completeExceptionally(IllegalStateException("Camera error $error")) }
                        }, handler)
                        camera = opened.get(10, TimeUnit.SECONDS)
                        camera.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) { configured.complete(value) }
                            override fun onConfigureFailed(value: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configuration failed")) }
                        }, handler)
                        session = configured.get(10, TimeUnit.SECONDS)
                        val preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            if (variant.manualPreview) manual(this)
                        }
                        session.setRepeatingRequest(preview.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, value: TotalCaptureResult) { firstPreview.complete(value) }
                        }, handler)
                        val previewResult = firstPreview.get(10, TimeUnit.SECONDS)
                        row.put("previewIso", previewResult.get(CaptureResult.SENSOR_SENSITIVITY))
                            .put("previewExposureNs", previewResult.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                        val request = camera.createCaptureRequest(variant.template).apply {
                            addTarget(reader.surface); manual(this)
                        }
                        session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, value: TotalCaptureResult) { captured.complete(value) }
                        }, handler)
                        val bytes = jpeg.get(10, TimeUnit.SECONDS)
                        val result = captured.get(10, TimeUnit.SECONDS)
                        File(directory, "$id-${variant.name}-${UUID.randomUUID()}.jpg").writeBytes(bytes)
                        val exif = ExifInterface(ByteArrayInputStream(bytes))
                        row.put("actualExposureNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                            .put("actualIso", result.get(CaptureResult.SENSOR_SENSITIVITY))
                            .put("actualAe", result.get(CaptureResult.CONTROL_AE_MODE))
                            .put("actualMode", result.get(CaptureResult.CONTROL_MODE))
                            .put("physicalCamera", if (Build.VERSION.SDK_INT >= 28) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null)
                            .put("resultTimestamp", result.get(CaptureResult.SENSOR_TIMESTAMP))
                            .put("exifIso", exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1))
                            .put("exifExposure", exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN))
                    } catch (error: Throwable) { row.put("failure", error.toString()) }
                    finally { session?.close(); camera?.close(); reader.close(); surface.release(); texture.release() }
                    File(directory, "proof.json").writeText(proof.toString(2))
                }
            }
        } finally { thread.quitSafely(); thread.join(3_000) }
    }
}
