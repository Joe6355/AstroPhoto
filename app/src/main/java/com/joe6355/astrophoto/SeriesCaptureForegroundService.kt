package com.joe6355.astrophoto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

class SeriesCaptureForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private var seriesJob: Job? = null
    private var stopRequested = false
    private var cameraController: CameraPreviewView? = null
    private var activeCameraId = "unknown"

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        startInForeground(buildNotification("Подготовка серии...", 0, 1, 0L))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_START -> if (seriesJob?.isActive != true) {
                readRequest(intent)?.let { startSeries(it, startId) }
                    ?: finishWithError("Некорректные параметры фоновой серии")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        seriesJob?.cancel()
        cameraController?.stopHeadlessCapture()
        cameraController = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSeries(request: SeriesCaptureRequest, startId: Int) {
        stopRequested = false
        seriesJob = serviceScope.launch {
            var completed = 0
            var failure: String? = null
            try {
                val initialDuration = estimatedSeriesDurationMillis(
                    request.exposureTimeNs,
                    request.frameCount,
                    request.delaySeconds,
                    request.startTimerSeconds
                )
                for (secondsLeft in request.startTimerSeconds downTo 1) {
                    if (stopRequested) break
                    publishState(0, request.frameCount, "Старт через $secondsLeft...", initialDuration)
                    delay(1_000L)
                }
                if (stopRequested) return@launch

                val ready = CompletableDeferred<Unit>()
                val controller = CameraPreviewView(
                    context = applicationContext,
                    onCameraError = { message ->
                        if (!ready.isCompleted) {
                            ready.completeExceptionally(IllegalStateException(message))
                        }
                    },
                    onCameraStatus = { status ->
                        if (status == "session configured" && !ready.isCompleted) ready.complete(Unit)
                    },
                    onCapabilitiesAvailable = { capabilities ->
                        activeCameraId = capabilities.cameraId
                    }
                )
                cameraController = controller
                controller.setJpegQuality(request.jpegQuality)
                controller.updateManualParameters(
                    exposureTimeNs = request.exposureTimeNs,
                    iso = request.iso,
                    focusDistance = request.focusDistance,
                    focusMode = request.focusMode.toFocusMode(),
                    applyLongExposureToPreview = false
                )
                publishState(0, request.frameCount, "Подготовка камеры...", initialDuration)
                controller.startHeadlessCapture()
                withTimeout(CAMERA_READY_TIMEOUT_MILLIS) { ready.await() }

                val format = request.format.toCaptureType()
                val extension = if (format == UiCaptureType.RAW) "dng" else "jpg"
                val safetyChecker = SeriesSafetyChecker(
                    applicationContext,
                    StorageSpaceChecker(applicationContext)
                )
                val captureStarted = SystemClock.elapsedRealtime()
                for (frameIndex in 1..request.frameCount) {
                    if (stopRequested) break
                    safetyChecker.blockingReason()?.let {
                        failure = it
                        break
                    }
                    publishState(
                        frameIndex,
                        request.frameCount,
                        "Съёмка...",
                        estimatedSeriesDurationMillis(
                            request.exposureTimeNs,
                            request.frameCount - completed,
                            request.delaySeconds,
                            0
                        )
                    )
                    val fileName = "${request.filePrefix}_${
                        frameIndex.toString().padStart(3, '0')
                    }.$extension"
                    val result = captureSeriesFrame(
                        preview = controller,
                        format = format,
                        fileName = fileName,
                        relativeDirectory = request.relativeDirectory,
                        exposureTimeNs = request.exposureTimeNs,
                        onStageChanged = { stage ->
                            val currentRemaining =
                                SeriesCaptureCoordinator.state.value?.remainingMillis ?: 0L
                            publishState(
                                frameIndex,
                                request.frameCount,
                                if (stage == CameraCaptureStage.CAPTURING) {
                                    "Съёмка..."
                                } else {
                                    "Сохранение..."
                                },
                                currentRemaining
                            )
                        }
                    )
                    if (result.isFailure) {
                        failure = result.exceptionOrNull()?.message ?: "кадр не сохранён"
                        break
                    }
                    completed = frameIndex
                    SeriesCaptureRecoveryStore(applicationContext).frameSaved(fileName)
                    recordCapturedFrame(request)
                    val remaining = estimatedRemainingSeriesDurationMillis(
                        SystemClock.elapsedRealtime() - captureStarted,
                        completed,
                        request.frameCount,
                        request.delaySeconds
                    )
                    publishState(completed, request.frameCount, "Кадр сохранён", remaining)
                    if (stopRequested || completed == request.frameCount) break
                    if (request.delaySeconds > 0) {
                        publishState(completed, request.frameCount, "Пауза...", remaining)
                        delay(request.delaySeconds * 1_000L)
                    }
                }
            } catch (_: CancellationException) {
                if (!stopRequested) failure = "Фоновая серия была прервана системой"
            } catch (error: Throwable) {
                failure = error.message ?: "Ошибка фоновой серии"
            } finally {
                cameraController?.stopHeadlessCapture()
                cameraController = null
                val message = when {
                    failure != null -> "Ошибка серии: $failure"
                    stopRequested -> "Серия остановлена после текущего кадра"
                    else -> {
                        notifyCompletionFeedback(
                            applicationContext,
                            request.vibrationAfterSeries,
                            request.soundAfterSeries,
                            completed = true
                        )
                        "Серия завершена: $completed кадров сохранено"
                    }
                }
                val recoveryStatus = when {
                    failure != null -> SeriesRecoveryStatus.FAILED
                    stopRequested -> SeriesRecoveryStatus.STOPPED
                    else -> SeriesRecoveryStatus.COMPLETED
                }
                SeriesCaptureRecoveryStore(applicationContext).finish(recoveryStatus, message)
                failure?.let {
                    CameraDiagnosticEventStore.record(applicationContext, "series", it)
                }
                SeriesCaptureCoordinator.update(
                    completed,
                    request.frameCount,
                    "",
                    0L,
                    running = false,
                    message = message
                )
                seriesJob = null
                stopSelf(startId)
            }
        }
    }

    private fun requestStop() {
        stopRequested = true
        val activeJob = seriesJob
        if (activeJob?.isActive != true) {
            stopSelf()
            return
        }
        val state = SeriesCaptureCoordinator.state.value
        if (state == null) {
            activeJob.cancel()
            stopSelf()
            return
        }
        publishState(
            state.current,
            state.total,
            "Остановка после текущего кадра...",
            state.remainingMillis
        )
        if (state.current == 0 || state.status == "Пауза...") activeJob.cancel()
    }

    private fun recordCapturedFrame(request: SeriesCaptureRequest) {
        val store = ShootingSessionStore(applicationContext)
        val session = store.load()?.takeIf { it.folderName == request.sessionFolder } ?: return
        val updated = session.copy(lightFrames = session.lightFrames + 1)
        store.save(updated)
        store.writeSessionInfo(
            updated,
            SessionCaptureMetadata(
                cameraId = activeCameraId,
                iso = request.iso,
                exposureTimeNs = request.exposureTimeNs,
                focus = request.focusMode,
                selectedFormat = request.format
            )
        )
    }

    private fun publishState(current: Int, total: Int, status: String, remainingMillis: Long) {
        SeriesCaptureCoordinator.update(current, total, status, remainingMillis)
        if (notificationsAllowed()) {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(status, current, total, remainingMillis)
            )
        }
    }

    private fun finishWithError(message: String) {
        CameraDiagnosticEventStore.record(applicationContext, "series", message)
        SeriesCaptureCoordinator.update(0, 1, "", 0L, running = false, message = message)
        stopSelf()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        status: String,
        current: Int,
        total: Int,
        remainingMillis: Long
    ): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SeriesCaptureForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val remaining = if (remainingMillis > 0L) {
            " · осталось ${formatSeriesDuration(remainingMillis)}"
        } else {
            ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("AstroPhoto: серия $current/$total")
            .setContentText(status + remaining)
            .setContentIntent(openPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Остановить", stopPendingIntent)
            .setProgress(total.coerceAtLeast(1), current.coerceAtLeast(0), false)
            .build()
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Серийная съёмка",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun String.toCaptureType(): UiCaptureType =
        runCatching { UiCaptureType.valueOf(uppercase(Locale.US)) }
            .getOrDefault(UiCaptureType.JPEG)

    private fun String.toFocusMode(): CameraFocusMode =
        runCatching { CameraFocusMode.valueOf(uppercase(Locale.US)) }
            .getOrDefault(CameraFocusMode.INFINITY)

    private fun readRequest(intent: Intent): SeriesCaptureRequest? {
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: return null
        val session = intent.getStringExtra(EXTRA_SESSION_FOLDER) ?: return null
        val directory = intent.getStringExtra(EXTRA_RELATIVE_DIRECTORY) ?: return null
        val prefix = intent.getStringExtra(EXTRA_FILE_PREFIX) ?: return null
        val focusMode = intent.getStringExtra(EXTRA_FOCUS_MODE) ?: return null
        val count = intent.getIntExtra(EXTRA_FRAME_COUNT, 0)
        if (count <= 0) return null
        return SeriesCaptureRequest(
            format = format,
            frameCount = count,
            delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 0).coerceAtLeast(0),
            startTimerSeconds = intent.getIntExtra(EXTRA_TIMER_SECONDS, 0).coerceAtLeast(0),
            exposureTimeNs = intent.getLongExtra(EXTRA_EXPOSURE_NS, 33_333_333L),
            iso = intent.getIntExtra(EXTRA_ISO, 400),
            focusDistance = intent.getFloatExtra(EXTRA_FOCUS_DISTANCE, 0f),
            focusMode = focusMode,
            jpegQuality = intent.getIntExtra(EXTRA_JPEG_QUALITY, 92),
            sessionFolder = session,
            relativeDirectory = directory,
            filePrefix = prefix,
            vibrationAfterSeries = intent.getBooleanExtra(EXTRA_VIBRATION, false),
            soundAfterSeries = intent.getBooleanExtra(EXTRA_SOUND, false)
        )
    }

    companion object {
        const val ACTION_START = "com.joe6355.astrophoto.action.START_SERIES"
        const val ACTION_STOP = "com.joe6355.astrophoto.action.STOP_SERIES"
        private const val CHANNEL_ID = "series_capture"
        private const val NOTIFICATION_ID = 2102
        private const val CAMERA_READY_TIMEOUT_MILLIS = 20_000L
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_FRAME_COUNT = "frame_count"
        private const val EXTRA_DELAY_SECONDS = "delay_seconds"
        private const val EXTRA_TIMER_SECONDS = "timer_seconds"
        private const val EXTRA_EXPOSURE_NS = "exposure_ns"
        private const val EXTRA_ISO = "iso"
        private const val EXTRA_FOCUS_DISTANCE = "focus_distance"
        private const val EXTRA_FOCUS_MODE = "focus_mode"
        private const val EXTRA_JPEG_QUALITY = "jpeg_quality"
        private const val EXTRA_SESSION_FOLDER = "session_folder"
        private const val EXTRA_RELATIVE_DIRECTORY = "relative_directory"
        private const val EXTRA_FILE_PREFIX = "file_prefix"
        private const val EXTRA_VIBRATION = "vibration"
        private const val EXTRA_SOUND = "sound"

        fun startIntent(context: Context, request: SeriesCaptureRequest): Intent =
            Intent(context, SeriesCaptureForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FORMAT, request.format)
                .putExtra(EXTRA_FRAME_COUNT, request.frameCount)
                .putExtra(EXTRA_DELAY_SECONDS, request.delaySeconds)
                .putExtra(EXTRA_TIMER_SECONDS, request.startTimerSeconds)
                .putExtra(EXTRA_EXPOSURE_NS, request.exposureTimeNs)
                .putExtra(EXTRA_ISO, request.iso)
                .putExtra(EXTRA_FOCUS_DISTANCE, request.focusDistance)
                .putExtra(EXTRA_FOCUS_MODE, request.focusMode)
                .putExtra(EXTRA_JPEG_QUALITY, request.jpegQuality)
                .putExtra(EXTRA_SESSION_FOLDER, request.sessionFolder)
                .putExtra(EXTRA_RELATIVE_DIRECTORY, request.relativeDirectory)
                .putExtra(EXTRA_FILE_PREFIX, request.filePrefix)
                .putExtra(EXTRA_VIBRATION, request.vibrationAfterSeries)
                .putExtra(EXTRA_SOUND, request.soundAfterSeries)
    }
}
