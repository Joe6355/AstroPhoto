package com.example.astrophoto

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.astrophoto.ui.AstroLoadingState
import com.example.astrophoto.ui.AstroPrimaryButton
import com.example.astrophoto.ui.AstroScaffold
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.theme.AstroColors

enum class SelfCheckStatus(val title: String, val color: Color) {
    OK("ОК", AstroColors.Success),
    WARNING("Предупреждение", AstroColors.Warning),
    ERROR("Ошибка", AstroColors.Error),
    NOT_CHECKED("Не проверено", AstroColors.TextSecondary)
}

enum class SelfCheckId(val title: String) {
    CAMERA("Камера"),
    CAMERA_PERMISSION("Разрешение камеры"),
    MANUAL_SENSOR("Manual Sensor"),
    RAW("RAW/DNG"),
    JPEG("JPEG-съёмка"),
    STORAGE_FOLDER("Папка сохранения"),
    ACTIVE_SESSION("Активная сессия"),
    FREE_SPACE("Свободное место"),
    SOUND("Звук"),
    VIBRATION("Вибрация"),
    STACKING("Stacking"),
    ZIP_EXPORT("Экспорт ZIP")
}

data class SelfCheckItem(
    val id: SelfCheckId,
    val status: SelfCheckStatus = SelfCheckStatus.NOT_CHECKED,
    val details: String = "Проверка ещё не запускалась.",
    val recommendation: String? = null
)

data class SelfCheckReport(
    val checkedAtMillis: Long = 0L,
    val items: List<SelfCheckItem> = SelfCheckId.entries.map(::SelfCheckItem),
    val cameraId: String = "неизвестно",
    val hardwareLevel: String = "неизвестно",
    val manualSensor: String = "неизвестно",
    val raw: String = "неизвестно",
    val isoRange: String = "неизвестно",
    val exposureRange: String = "неизвестно",
    val freeStorage: String = "неизвестно",
    val activeSession: String = "не выбрана"
)

@Composable
fun SelfCheckScreen(
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runner = remember { SelfCheckRunner(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(SelfCheckReport()) }
    var running by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<SelfCheckId?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun startCheck() {
        if (running) return
        running = true
        message = null
        report = SelfCheckReport()
        scope.launch {
            try {
                report = runner.run(cameraPermissionGranted) { updated ->
                    report = updated
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "Самопроверка прервана: ${
                    error.message ?: "неизвестная ошибка"
                }"
                report = report.copy(checkedAtMillis = System.currentTimeMillis())
            } finally {
                running = false
            }
        }
    }

    val overallStatus = when {
        report.items.any { it.status == SelfCheckStatus.ERROR } ->
            "Есть ошибки" to AstroColors.Error
        report.items.any { it.status == SelfCheckStatus.WARNING } ->
            "Есть предупреждения" to AstroColors.Warning
        report.checkedAtMillis > 0L ->
            "Готово к съёмке" to AstroColors.Success
        else -> "Самопроверка не запускалась" to AstroColors.TextSecondary
    }

    AstroScaffold(title = "Самопроверка", onBack = onBack) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            AstroSpacing.Lg,
            AstroSpacing.Md,
            AstroSpacing.Lg,
            AstroSpacing.Xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = overallStatus.first,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = overallStatus.second
            )
            AstroPrimaryButton(
                text = if (running) "Проверка…" else "Запустить самопроверку",
                onClick = ::startCheck,
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            if (running) {
                AstroLoadingState(
                    message = "Проверяем камеру и хранилище…",
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        items(report.items, key = { it.id.name }) { item ->
            val expanded = expandedId == item.id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expandedId = if (expanded) null else item.id
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.id.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = item.status.title,
                            color = item.status.color,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (expanded) {
                        Text(
                            text = item.details,
                            modifier = Modifier.padding(top = 8.dp),
                            color = AstroColors.TextSecondary
                        )
                        item.recommendation?.let {
                            Text(
                                text = it,
                                modifier = Modifier.padding(top = 6.dp),
                                color = AstroColors.Warning
                            )
                        }
                        when (item.id) {
                            SelfCheckId.CAMERA_PERMISSION -> {
                                if (!cameraPermissionGranted) {
                                    Button(
                                        onClick = onRequestCameraPermission,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 52.dp)
                                            .padding(top = 8.dp)
                                    ) {
                                        Text("Запросить разрешение")
                                    }
                                }
                            }
                            SelfCheckId.SOUND -> {
                                Button(
                                    onClick = {
                                        message = playSelfCheckSound()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .padding(top = 8.dp)
                                ) {
                                    Text("Проверить звук")
                                }
                            }
                            SelfCheckId.VIBRATION -> {
                                Button(
                                    onClick = {
                                        message = playSelfCheckVibration(context)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .padding(top = 8.dp)
                                ) {
                                    Text("Проверить вибрацию")
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val text = formatSelfCheckReport(
                        context = context,
                        report = report
                    )
                    val clipboard = context.getSystemService(
                        ClipboardManager::class.java
                    )
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("AstroPhoto self-check", text)
                    )
                    message = if (clipboard != null) {
                        "Отчёт самопроверки скопирован"
                    } else {
                        "Буфер обмена недоступен"
                    }
                },
                enabled = report.checkedAtMillis > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text("Скопировать отчёт")
            }
            message?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 8.dp),
                    color = if (
                        it.contains("скопирован") ||
                        it.contains("выполнен") ||
                        it.contains("проигран")
                    ) {
                        AstroColors.Success
                    } else {
                        AstroColors.Error
                    }
                )
            }
        }
    }
    }
}

private class SelfCheckRunner(private val context: Context) {
    private val settingsStore = CameraSettingsStore(context)
    private val sessionStore = ShootingSessionStore(context)
    private val storageChecker = StorageSpaceChecker(context)

    suspend fun run(
        cameraPermissionGranted: Boolean,
        onUpdate: suspend (SelfCheckReport) -> Unit
    ): SelfCheckReport = withContext(Dispatchers.IO) {
        var report = SelfCheckReport()
        suspend fun update(
            id: SelfCheckId,
            status: SelfCheckStatus,
            details: String,
            recommendation: String? = null
        ) {
            report = report.copy(
                items = report.items.map {
                    if (it.id == id) {
                        it.copy(
                            status = status,
                            details = details,
                            recommendation = recommendation
                        )
                    } else {
                        it
                    }
                }
            )
            withContext(Dispatchers.Main.immediate) {
                onUpdate(report)
            }
            delay(45L)
        }

        update(
            SelfCheckId.CAMERA_PERMISSION,
            if (cameraPermissionGranted) SelfCheckStatus.OK else SelfCheckStatus.ERROR,
            if (cameraPermissionGranted) {
                "Разрешение CAMERA предоставлено."
            } else {
                "Разрешение CAMERA не предоставлено."
            },
            if (!cameraPermissionGranted) "Разрешите доступ к камере." else null
        )

        var cameraId = "неизвестно"
        var hardwareLevel = "неизвестно"
        var manualSensorText = "неизвестно"
        var rawText = "неизвестно"
        var isoText = "неизвестно"
        var exposureText = "неизвестно"
        val cameraResult = runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
                ?: error("CameraManager недоступен")
            cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: error("Основная задняя камера не найдена")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: intArrayOf()
            val manual = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            )
            val raw = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            )
            val iso = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val exposure = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            val jpegAvailable = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )?.getOutputSizes(ImageFormat.JPEG)?.isNotEmpty() == true
            hardwareLevel = selfCheckHardwareLevel(
                characteristics.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
                )
            )
            manualSensorText = if (manual) "Да" else "Нет"
            rawText = if (raw) "Да" else "Нет"
            isoText = iso?.let { "${it.lower} - ${it.upper}" } ?: "неизвестно"
            exposureText = exposure?.let {
                "${formatSeconds(it.lower)} - ${formatSeconds(it.upper)}"
            } ?: "неизвестно"
            CameraProbe(manual, raw, jpegAvailable)
        }

        cameraResult.fold(
            onSuccess = { probe ->
                update(
                    SelfCheckId.CAMERA,
                    SelfCheckStatus.OK,
                    "Задняя Camera2-камера найдена. cameraId=$cameraId, " +
                        "hardware=$hardwareLevel."
                )
                update(
                    SelfCheckId.MANUAL_SENSOR,
                    if (probe.manualSensor) SelfCheckStatus.OK else SelfCheckStatus.WARNING,
                    "Manual Sensor: ${if (probe.manualSensor) "доступен" else "недоступен"}.",
                    if (!probe.manualSensor) {
                        "Ручная выдержка и ISO могут работать ограниченно."
                    } else null
                )
                update(
                    SelfCheckId.RAW,
                    if (probe.raw) SelfCheckStatus.OK else SelfCheckStatus.WARNING,
                    "RAW/DNG: ${if (probe.raw) "доступен" else "недоступен"}.",
                    if (!probe.raw) {
                        "Можно снимать JPEG, но RAW/DNG на этом устройстве недоступен."
                    } else null
                )
                update(
                    SelfCheckId.JPEG,
                    if (probe.jpeg) SelfCheckStatus.OK else SelfCheckStatus.ERROR,
                    if (probe.jpeg) {
                        "JPEG output доступен. Реальный снимок не выполнялся."
                    } else {
                        "Камера не сообщила JPEG output."
                    }
                )
            },
            onFailure = {
                update(
                    SelfCheckId.CAMERA,
                    SelfCheckStatus.ERROR,
                    it.message ?: "Не удалось прочитать Camera2.",
                    "Проверьте разрешение камеры и перезапустите приложение."
                )
                update(
                    SelfCheckId.MANUAL_SENSOR,
                    SelfCheckStatus.NOT_CHECKED,
                    "Camera2 characteristics недоступны."
                )
                update(
                    SelfCheckId.RAW,
                    SelfCheckStatus.NOT_CHECKED,
                    "Camera2 characteristics недоступны."
                )
                update(
                    SelfCheckId.JPEG,
                    SelfCheckStatus.NOT_CHECKED,
                    "JPEG не проверен."
                )
            }
        )

        val activeSession = sessionStore.load()
        update(
            SelfCheckId.ACTIVE_SESSION,
            if (activeSession != null) SelfCheckStatus.OK else SelfCheckStatus.WARNING,
            activeSession?.let {
                "Активная сессия: ${it.sessionName}."
            } ?: "Активная сессия не выбрана, будет создана автоматически.",
            if (activeSession == null) {
                "Создайте сессию перед съёмкой или приложение создаст её автоматически."
            } else null
        )

        val saveProbe = probeTemporaryWrite(
            relativePath = "${Environment.DIRECTORY_PICTURES}/AstroPhoto/SelfCheck/"
        )
        val sessionStructure = inspectSessionStructure(activeSession)
        update(
            SelfCheckId.STORAGE_FOLDER,
            when {
                !saveProbe.written -> SelfCheckStatus.ERROR
                !saveProbe.deleted -> SelfCheckStatus.WARNING
                else -> SelfCheckStatus.OK
            },
            "${saveProbe.message} $sessionStructure",
            if (!saveProbe.written) {
                "Проверьте доступ к хранилищу."
            } else if (!saveProbe.deleted) {
                "Временный файл не удалился; удалите папку SelfCheck вручную."
            } else null
        )

        val storage = storageChecker.readAvailableSpace()
        val freeStorageText = storage.availableBytes?.let(::formatStorageSize)
            ?: "неизвестно"
        val storageStatus = when {
            storage.availableBytes == null -> SelfCheckStatus.WARNING
            storage.availableBytes < 500L * 1024L * 1024L -> SelfCheckStatus.ERROR
            storage.availableBytes < 2L * 1024L * 1024L * 1024L ->
                SelfCheckStatus.WARNING
            else -> SelfCheckStatus.OK
        }
        update(
            SelfCheckId.FREE_SPACE,
            storageStatus,
            "Свободно: $freeStorageText.",
            if (storageStatus != SelfCheckStatus.OK) {
                "Освободите память или уменьшите количество кадров."
            } else null
        )

        val settings = settingsStore.load()
        update(
            SelfCheckId.SOUND,
            if (settings.soundAfterSeries) SelfCheckStatus.OK else SelfCheckStatus.WARNING,
            if (settings.soundAfterSeries) {
                "Звук после серии включён. Автоматический сигнал не проигрывался."
            } else {
                "Звук после серии выключен в настройках."
            },
            if (!settings.soundAfterSeries) "При необходимости включите звук." else null
        )

        val vibratorAvailable = selfCheckVibrator(context)?.hasVibrator() == true
        val vibrationOk = settings.vibrationAfterSeries && vibratorAvailable
        update(
            SelfCheckId.VIBRATION,
            if (vibrationOk) SelfCheckStatus.OK else SelfCheckStatus.WARNING,
            when {
                !vibratorAvailable -> "Вибратор устройства недоступен."
                !settings.vibrationAfterSeries ->
                    "Вибрация после серии выключена в настройках."
                else -> "Вибрация доступна. Автоматический тест не выполнялся."
            }
        )

        val stackerAvailable = runCatching {
            JpegStacker(context)
        }.isSuccess
        update(
            SelfCheckId.STACKING,
            if (stackerAvailable) SelfCheckStatus.OK else SelfCheckStatus.ERROR,
            if (stackerAvailable) {
                "JpegStacker доступен. ОК, реальные данные для теста не использовались."
            } else {
                "Не удалось подготовить JpegStacker."
            }
        )

        val exportProbe = probeTemporaryWrite(
            relativePath = "${Environment.DIRECTORY_DOCUMENTS}/AstroPhoto/Exports/"
        )
        update(
            SelfCheckId.ZIP_EXPORT,
            when {
                !exportProbe.written -> SelfCheckStatus.ERROR
                !exportProbe.deleted -> SelfCheckStatus.WARNING
                else -> SelfCheckStatus.OK
            },
            "Тест каталога экспорта: ${exportProbe.message}"
        )

        report = report.copy(
            checkedAtMillis = System.currentTimeMillis(),
            cameraId = cameraId,
            hardwareLevel = hardwareLevel,
            manualSensor = manualSensorText,
            raw = rawText,
            isoRange = isoText,
            exposureRange = exposureText,
            freeStorage = freeStorageText,
            activeSession = activeSession?.sessionName ?: "не выбрана"
        )
        withContext(Dispatchers.Main.immediate) {
            onUpdate(report)
        }
        report
    }

    private fun probeTemporaryWrite(relativePath: String): WriteProbe {
        val name = "AstroPhoto_selfcheck_${System.currentTimeMillis()}.txt"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            probeMediaStore(relativePath, name)
        } else {
            probeLegacy(relativePath, name)
        }
    }

    private fun inspectSessionStructure(session: ShootingSession?): String {
        session ?: return "Активной сессии для проверки структуры нет."
        val expected = listOf(
            "Lights/JPEG/",
            "Lights/RAW/",
            "Darks/JPEG/",
            "Darks/RAW/",
            "Tests/JPEG/",
            "Processed/"
        )
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val base =
                "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
            val paths = mutableSetOf<String>()
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH),
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$base%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    paths += cursor.getString(0).orEmpty().removePrefix(base)
                }
            }
            expected.count { part ->
                paths.any { it.startsWith(part, ignoreCase = true) }
            }
        } else {
            @Suppress("DEPRECATION")
            val pictures = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )
            val base = File(pictures, "AstroPhoto/${session.folderName}")
            expected.count { File(base, it).exists() }
        }
        return "Структура активной сессии: найдено $found из ${expected.size} " +
            "разделов; отсутствующие создаются при первой записи."
    }

    private fun probeMediaStore(relativePath: String, name: String): WriteProbe {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        var uri: Uri? = null
        return try {
            uri = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
            ) ?: error("MediaStore не создал временный файл")
            resolver.openOutputStream(uri, "w")?.use {
                it.write("AstroPhoto self-check".toByteArray())
            } ?: error("Не удалось записать временный файл")
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                },
                null,
                null
            )
            val deleted = resolver.delete(uri, null, null) > 0
            WriteProbe(
                written = true,
                deleted = deleted,
                message = if (deleted) {
                    "Запись и удаление временного файла выполнены."
                } else {
                    "Запись выполнена, но временный файл не удалился."
                }
            )
        } catch (error: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            WriteProbe(false, true, error.message ?: "Ошибка тестовой записи")
        }
    }

    @Suppress("DEPRECATION")
    private fun probeLegacy(relativePath: String, name: String): WriteProbe {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return WriteProbe(false, true, "Нет разрешения на запись.")
        }
        return runCatching {
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val directory = File(root, relativePath).canonicalFile
            require(directory.path.startsWith(root.path + File.separator)) {
                "Недопустимый тестовый путь"
            }
            if (!directory.exists() && !directory.mkdirs()) {
                error("Не удалось создать тестовую папку")
            }
            val file = File(directory, name)
            file.writeText("AstroPhoto self-check")
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("text/plain"),
                null
            )
            val deleted = file.delete()
            directory.delete()
            WriteProbe(
                true,
                deleted,
                if (deleted) {
                    "Запись и удаление временного файла выполнены."
                } else {
                    "Запись выполнена, но временный файл не удалился."
                }
            )
        }.getOrElse {
            WriteProbe(false, true, it.message ?: "Ошибка тестовой записи")
        }
    }

    private data class CameraProbe(
        val manualSensor: Boolean,
        val raw: Boolean,
        val jpeg: Boolean
    )

    private data class WriteProbe(
        val written: Boolean,
        val deleted: Boolean,
        val message: String
    )
}

private fun playSelfCheckSound(): String = runCatching {
    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
    Thread {
        Thread.sleep(230L)
        tone.release()
    }.start()
    "Тестовый звук проигран"
}.getOrElse {
    "Звук недоступен: ${it.message ?: "неизвестная ошибка"}"
}

private fun playSelfCheckVibration(context: Context): String = runCatching {
    val vibrator = selfCheckVibrator(context)
        ?: error("Вибратор недоступен")
    if (!vibrator.hasVibrator()) error("Устройство не поддерживает вибрацию")
    vibrator.vibrate(
        VibrationEffect.createOneShot(
            100L,
            VibrationEffect.DEFAULT_AMPLITUDE
        )
    )
    "Тестовая вибрация выполнена"
}.getOrElse {
    "Вибрация недоступна: ${it.message ?: "неизвестная ошибка"}"
}

private fun selfCheckVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

private fun formatSelfCheckReport(
    context: Context,
    report: SelfCheckReport
): String {
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName.orEmpty()
    }.getOrDefault("неизвестно")
    val checkedAt = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date(report.checkedAtMillis))
    return buildString {
        appendLine("AstroPhoto self-check")
        appendLine("Checked at: $checkedAt")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App version: $version")
        appendLine("Camera ID: ${report.cameraId}")
        appendLine("Hardware level: ${report.hardwareLevel}")
        appendLine("Manual Sensor: ${report.manualSensor}")
        appendLine("RAW/DNG: ${report.raw}")
        appendLine("ISO range: ${report.isoRange}")
        appendLine("Exposure range: ${report.exposureRange}")
        appendLine("Free storage: ${report.freeStorage}")
        appendLine("Active session: ${report.activeSession}")
        appendLine()
        report.items.forEach {
            appendLine("${it.id.title}: ${it.status.title} — ${it.details}")
            it.recommendation?.let { recommendation ->
                appendLine("  Recommendation: $recommendation")
            }
        }
    }
}

private fun selfCheckHardwareLevel(level: Int?): String = when (level) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "UNKNOWN"
}

private fun formatSeconds(nanoseconds: Long): String =
    String.format(
        Locale.getDefault(),
        "%.6f сек",
        nanoseconds / 1_000_000_000.0
    )
