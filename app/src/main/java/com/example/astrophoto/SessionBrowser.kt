package com.example.astrophoto

import android.content.ContentUris
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.astrophoto.ui.AstroEmptyState
import com.example.astrophoto.ui.AstroExpandableSection
import com.example.astrophoto.ui.AstroLoadingState
import com.example.astrophoto.ui.AstroPrimaryButton
import com.example.astrophoto.ui.AstroScaffold
import com.example.astrophoto.ui.AstroSecondaryButton
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.AstroTopBar
import com.example.astrophoto.ui.theme.AstroColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionSummary(
    val folderName: String,
    val sessionName: String,
    val relativePath: String,
    val createdAtMillis: Long,
    val lightsJpeg: Int,
    val lightsRaw: Int,
    val darksJpeg: Int,
    val darksRaw: Int,
    val totalSizeBytes: Long,
    val infoContent: String
) {
    val lightFrames: Int get() = lightsJpeg + lightsRaw
    val darkFrames: Int get() = darksJpeg + darksRaw

    fun toShootingSession(): ShootingSession = ShootingSession(
        sessionName = sessionName,
        folderName = folderName,
        createdAtMillis = createdAtMillis,
        note = infoContent.lineSequence()
            .firstOrNull { it.startsWith("note:") }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty(),
        lightFrames = lightFrames,
        darkFrames = darkFrames,
        testShots = infoContent.sessionInfoInt("testShots"),
        lastTestShotStatus = infoContent.sessionInfoValue("lastTestShotStatus"),
        lastTestShotAtMillis = 0L
    )
}

private fun String.sessionInfoValue(key: String): String =
    lineSequence()
        .firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')
        ?.trim()
        .orEmpty()

private fun String.sessionInfoInt(key: String): Int =
    sessionInfoValue(key).toIntOrNull() ?: 0

class SessionBrowserRepository(private val context: Context) {
    suspend fun loadSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadFromMediaStore()
        } else {
            loadFromFiles()
        }
    }

    private fun loadFromMediaStore(): List<SessionSummary> {
        val collection = MediaStore.Files.getContentUri("external")
        val basePath = "${Environment.DIRECTORY_PICTURES}/AstroPhoto/"
        val builders = linkedMapOf<String, MutableSessionSummary>()
        val resolver = context.contentResolver
        resolver.query(
            collection,
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_ADDED
            ),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$basePath%"),
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val pathIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.DATE_ADDED
            )

            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(pathIndex) ?: continue
                val remainder = relativePath.removePrefix(basePath)
                val folderName = remainder.substringBefore('/').takeIf { it.isNotBlank() }
                    ?: continue
                val builder = builders.getOrPut(folderName) {
                    MutableSessionSummary(folderName, "$basePath$folderName/")
                }
                val displayName = cursor.getString(nameIndex).orEmpty()
                val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                val dateMillis = cursor.getLong(dateIndex).coerceAtLeast(0L) * 1_000L
                builder.totalSizeBytes += size
                if (builder.createdAtMillis == 0L ||
                    dateMillis < builder.createdAtMillis
                ) {
                    builder.createdAtMillis = dateMillis
                }
                builder.countFile(relativePath, displayName)

                if (displayName == "session_info.txt") {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    builder.infoContent = runCatching {
                        resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                }
            }
        }
        return builders.values.map { it.build() }
            .sortedByDescending { it.createdAtMillis }
    }

    @Suppress("DEPRECATION")
    private fun loadFromFiles(): List<SessionSummary> {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val root = File(pictures, "AstroPhoto")
        if (!root.exists()) return emptyList()

        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.map { directory ->
                val builder = MutableSessionSummary(
                    folderName = directory.name,
                    relativePath = "Pictures/AstroPhoto/${directory.name}/"
                )
                builder.createdAtMillis = directory.lastModified()
                directory.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        builder.totalSizeBytes += file.length()
                        builder.countFile(
                            file.parentFile?.relativeTo(directory)?.path.orEmpty(),
                            file.name
                        )
                        if (file.name == "session_info.txt") {
                            builder.infoContent = runCatching { file.readText() }
                                .getOrDefault("")
                        }
                    }
                builder.build()
            }
            ?.sortedByDescending { it.createdAtMillis }
            .orEmpty()
    }

    private class MutableSessionSummary(
        val folderName: String,
        val relativePath: String
    ) {
        var createdAtMillis: Long = 0L
        var lightsJpeg: Int = 0
        var lightsRaw: Int = 0
        var darksJpeg: Int = 0
        var darksRaw: Int = 0
        var totalSizeBytes: Long = 0L
        var infoContent: String = ""

        fun countFile(path: String, name: String) {
            if (name == "session_info.txt") return
            val normalized = path.replace('\\', '/')
            when {
                normalized.contains("/Lights/JPEG/") ||
                    normalized.endsWith("/Lights/JPEG") -> lightsJpeg++
                normalized.contains("/Lights/RAW/") ||
                    normalized.endsWith("/Lights/RAW") -> lightsRaw++
                normalized.contains("/Darks/JPEG/") ||
                    normalized.endsWith("/Darks/JPEG") -> darksJpeg++
                normalized.contains("/Darks/RAW/") ||
                    normalized.endsWith("/Darks/RAW") -> darksRaw++
            }
        }

        fun build(): SessionSummary {
            val parsedName = infoContent.lineSequence()
                .firstOrNull { it.startsWith("sessionName:") }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: folderName
            return SessionSummary(
                folderName = folderName,
                sessionName = parsedName,
                relativePath = relativePath,
                createdAtMillis = createdAtMillis,
                lightsJpeg = lightsJpeg,
                lightsRaw = lightsRaw,
                darksJpeg = darksJpeg,
                darksRaw = darksRaw,
                totalSizeBytes = totalSizeBytes,
                infoContent = infoContent.ifBlank { "Нет информации" }
            )
        }
    }
}

private enum class SessionQualityStatus(
    val title: String,
    val color: Color
) {
    READY("Готово к обработке", AstroColors.Success),
    IMPROVE("Можно снимать лучше", AstroColors.Warning),
    PROBLEM("Проблема", AstroColors.Error)
}

private data class SessionQualityReport(
    val status: SessionQualityStatus,
    val recommendations: List<String>,
    val metadataSummary: String?
)

private fun analyzeSession(
    session: SessionSummary,
    rawSupported: Boolean,
    badFrameCount: Int
): SessionQualityReport {
    val hasInfo = session.infoContent.isNotBlank() &&
        session.infoContent != "Нет информации"
    val empty = session.lightFrames == 0 &&
        session.darkFrames == 0 &&
        !hasInfo
    val formatsMatch =
        (session.lightsRaw > 0 && session.darksRaw > 0) ||
            (session.lightsJpeg > 0 && session.darksJpeg > 0)
    val onlyJpegWhileRawAvailable = rawSupported &&
        session.lightsRaw == 0 &&
        session.darksRaw == 0 &&
        session.lightsJpeg + session.darksJpeg > 0

    val status = when {
        empty ||
            session.lightFrames == 0 ||
            session.darkFrames == 0 ||
            !hasInfo -> SessionQualityStatus.PROBLEM
        onlyJpegWhileRawAvailable -> SessionQualityStatus.IMPROVE
        session.lightFrames >= 3 &&
            session.darkFrames >= 3 &&
            formatsMatch -> SessionQualityStatus.READY
        else -> SessionQualityStatus.IMPROVE
    }

    val recommendations = buildList {
        if (session.darkFrames < 3 || !formatsMatch) {
            add("Снимите dark frames с теми же ISO и выдержкой.")
        }
        if (rawSupported && session.lightsRaw == 0) {
            add("Для астрофото лучше использовать RAW/DNG.")
        }
        if (session.lightFrames < 5) {
            add("Для слабых звёзд увеличьте количество кадров.")
        }
        val totalFrames = session.lightFrames + session.darkFrames
        if (badFrameCount >= 3 && badFrameCount * 3 >= totalFrames.coerceAtLeast(1)) {
            add("Много кадров помечено как брак. Лучше переснять серию.")
        }
        add("Если кадры пересвечены, уменьшите ISO или выдержку.")
        add("Если ничего не видно, увеличьте ISO или выдержку.")
    }

    val iso = session.infoContent.metadataValue("ISO")
    val exposure = session.infoContent.metadataValue("exposureTimeNs")
    val focus = session.infoContent.metadataValue("focus")
    val metadataSummary = if (iso != null || exposure != null || focus != null) {
        buildString {
            iso?.let { append("ISO $it") }
            exposure?.let {
                if (isNotEmpty()) append(" • ")
                append("выдержка $it нс")
            }
            focus?.let {
                if (isNotEmpty()) append(" • ")
                append("фокус $it")
            }
        }
    } else {
        null
    }

    return SessionQualityReport(
        status = status,
        recommendations = recommendations,
        metadataSummary = metadataSummary
    )
}

private fun String.metadataValue(key: String): String? =
    lineSequence()
        .firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    statusMessage: String? = null,
    onOpenDetails: (SessionSummary) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SessionBrowserRepository(context.applicationContext) }
    val sessionStore = remember { ShootingSessionStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var activeFolder by remember { mutableStateOf(sessionStore.load()?.folderName) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var sessionName by remember { mutableStateOf("") }
    var sessionNote by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        loading = true
        sessions = repository.loadSessions()
        activeFolder = sessionStore.load()?.folderName
        loading = false
    }

    AstroScaffold(title = "Сессии", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AstroSpacing.Lg)
        ) {
        AstroPrimaryButton(
            text = "Новая сессия",
            onClick = {
                sessionName = ""
                sessionNote = ""
                showCreateDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        )
        statusMessage?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = AstroSpacing.Sm),
                color = if (it.contains("не удалось", ignoreCase = true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    AstroColors.Success
                }
            )
        }

        when {
            loading -> {
                AstroLoadingState(
                    message = "Загружаем сессии…",
                    modifier = Modifier.weight(1f)
                )
            }

            sessions.isEmpty() -> {
                AstroEmptyState(
                    title = "Сессий пока нет",
                    message = "Создайте сессию перед съёмкой серии кадров",
                    modifier = Modifier.weight(1f)
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(AstroSpacing.Md)
                ) {
                    items(sessions, key = { it.folderName }) { session ->
                        SessionSummaryCard(
                            session = session,
                            active = session.folderName == activeFolder,
                            onClick = { onOpenDetails(session) }
                        )
                    }
                }
            }
        }
        }
    }

    if (showCreateDialog) {
        SessionCreateDialog(
            name = sessionName,
            note = sessionNote,
            onNameChanged = { sessionName = it },
            onNoteChanged = { sessionNote = it },
            onCreate = {
                val created = sessionStore.create(sessionName, sessionNote)
                activeFolder = created.folderName
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        sessionStore.writeSessionInfo(
                            created,
                            SessionCaptureMetadata(
                                cameraId = "unknown",
                                iso = 0,
                                exposureTimeNs = 0L,
                                focus = "unknown",
                                selectedFormat = "unknown"
                            )
                        )
                    }
                    refreshKey++
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun SessionSummaryCard(
    session: SessionSummary,
    active: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = session.sessionName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (active) {
                    Text("Активная", color = AstroColors.Success)
                }
            }
            Text(
                text = formatSessionDate(session.createdAtMillis),
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Lights: ${session.lightFrames}  ·  Darks: ${session.darkFrames}",
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Размер: ${formatFileSize(session.totalSizeBytes)}",
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Открыть",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun SessionDetailsScreen(
    session: SessionSummary,
    onBack: () -> Unit,
    onActivated: () -> Unit,
    onRenamed: (SessionSummary) -> Unit,
    onDeleted: (String) -> Unit,
    onOpenHelp: (HelpTopic) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionStore = remember { ShootingSessionStore(context.applicationContext) }
    val repository = remember { SessionBrowserRepository(context.applicationContext) }
    val framesRepository = remember {
        SessionFramesRepository(context.applicationContext)
    }
    val marksStore = remember { FrameMarksStore(context.applicationContext) }
    val exporter = remember { SessionZipExporter(context.applicationContext) }
    val storageChecker = remember { StorageSpaceChecker(context.applicationContext) }
    val fileManager = remember { SessionFileManager(context.applicationContext) }
    val deletionProtectionEnabled = remember {
        CameraSettingsStore(context.applicationContext)
            .load()
            .deletionProtectionEnabled
    }
    val coroutineScope = rememberCoroutineScope()
    var currentSummary by remember(session.folderName) { mutableStateOf(session) }
    var active by remember {
        mutableStateOf(sessionStore.load()?.folderName == session.folderName)
    }
    var rawSupported by remember { mutableStateOf(false) }
    var checkInProgress by remember { mutableStateOf(false) }
    var checkRefreshKey by remember { mutableIntStateOf(0) }
    var badFrameCount by remember { mutableIntStateOf(0) }
    var manualBadFrameCount by remember { mutableIntStateOf(0) }
    var autoBadFrameCount by remember { mutableIntStateOf(0) }
    var stackingFrameCount by remember { mutableIntStateOf(0) }
    var showingFrames by remember { mutableStateOf(false) }
    var showingProcessedResults by remember { mutableStateOf(false) }
    var exportInProgress by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exportProgress by remember { mutableStateOf<SessionZipProgress?>(null) }
    var lastExportResult by remember {
        mutableStateOf<SessionZipExportResult?>(null)
    }
    var exportStorageWarning by remember {
        mutableStateOf<StorageSpaceInfo?>(null)
    }
    var exportPrecheckWarning by remember { mutableStateOf<String?>(null) }
    var renameDialogVisible by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf(session.sessionName) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var deleteStep by remember { mutableIntStateOf(1) }
    var deleteConfirmation by remember { mutableStateOf("") }
    var managementInProgress by remember { mutableStateOf(false) }
    var managementStatus by remember { mutableStateOf<String?>(null) }
    var stackingInProgress by remember { mutableStateOf(false) }
    var deleteProgressCurrent by remember { mutableIntStateOf(0) }
    var deleteProgressTotal by remember { mutableIntStateOf(0) }

    fun startExport() {
        if (exportInProgress) return
        exportInProgress = true
        exportStatus = "Экспорт..."
        exportProgress = SessionZipProgress("Подготовка README", 0, 1)
        lastExportResult = null
        coroutineScope.launch {
            val result = exporter.export(currentSummary) { progress ->
                exportProgress = progress
            }
            exportInProgress = false
            exportProgress = null
            exportStatus = result.fold(
                onSuccess = {
                    lastExportResult = it
                    buildString {
                        append("ZIP сохранён: ${it.fileName}\n${it.location}")
                        if (it.skippedFiles > 0) {
                            append(
                                "\nЭкспорт завершён, пропущено " +
                                    "${it.skippedFiles} файлов"
                            )
                        }
                        if (!it.sessionInfoUpdated) {
                            append("\nНе удалось обновить session_info.txt")
                        }
                        exportPrecheckWarning?.let { warning ->
                            append("\n$warning")
                        }
                    }
                },
                onFailure = {
                    "Ошибка экспорта: ${it.message ?: "неизвестная ошибка"}"
                }
            )
            if (result.isSuccess) checkRefreshKey++
        }
    }

    fun requestExport() {
        if (exportInProgress) return
        coroutineScope.launch {
            exportStatus = "Проверка свободного места..."
            val available = storageChecker.readAvailableSpace()
            if (available.availableBytes == null) {
                exportPrecheckWarning =
                    available.errorMessage ?: "Не удалось определить свободное место"
                startExport()
                return@launch
            }
            exportPrecheckWarning = null
            val estimatedBytes = (
                currentSummary.totalSizeBytes.toDouble() * 1.1 +
                    64.0 * 1024.0
                ).toLong().coerceAtLeast(64L * 1024L)
            val check = available.copy(estimatedBytes = estimatedBytes)
            if (check.mayBeInsufficient) {
                exportStorageWarning = check
                exportStatus = null
            } else {
                startExport()
            }
        }
    }

    fun renameCurrentSession() {
        if (managementInProgress) return
        managementInProgress = true
        coroutineScope.launch {
            val result = fileManager.renameSession(currentSummary, renameInput)
            managementInProgress = false
            result.fold(
                onSuccess = { renamed ->
                    renameDialogVisible = false
                    val refreshed = repository.loadSessions()
                        .firstOrNull { it.folderName == renamed.newFolderName }
                        ?: currentSummary.copy(
                            folderName = renamed.newFolderName,
                            sessionName = renamed.newSessionName,
                            relativePath =
                                "Pictures/AstroPhoto/${renamed.newFolderName}/"
                        )
                    val renamedSummary = refreshed.copy(
                        folderName = renamed.newFolderName,
                        sessionName = renamed.newSessionName,
                        relativePath =
                            "Pictures/AstroPhoto/${renamed.newFolderName}/"
                    )
                    currentSummary = renamedSummary
                    active = sessionStore.load()?.folderName == renamed.newFolderName
                    managementStatus = buildString {
                        append("Сессия переименована: ${renamed.newSessionName}")
                        if (!renamed.metadataUpdated) {
                            append("\nНе удалось обновить session_info.txt")
                        }
                    }
                    onRenamed(renamedSummary)
                },
                onFailure = {
                    managementStatus =
                        "Ошибка переименования: ${it.message ?: "неизвестная ошибка"}"
                }
            )
        }
    }

    fun deleteCurrentSession() {
        if (managementInProgress) return
        managementInProgress = true
        coroutineScope.launch {
            deleteProgressCurrent = 0
            deleteProgressTotal = 0
            val result = fileManager.deleteSession(currentSummary) { current, total ->
                deleteProgressCurrent = current
                deleteProgressTotal = total
            }
            managementInProgress = false
            result.fold(
                onSuccess = {
                    deleteDialogVisible = false
                    onDeleted(
                        "Сессия удалена. Удалено ${it.deletedFiles} файлов, " +
                            "не удалось удалить ${it.failedFiles} файлов" +
                            if (it.activeSessionCleared) {
                                ". Активная сессия сброшена"
                            } else {
                                ""
                            }
                    )
                },
                onFailure = {
                    managementStatus =
                        "Ошибка удаления: ${it.message ?: "неизвестная ошибка"}"
                }
            )
        }
    }

    LaunchedEffect(checkRefreshKey) {
        checkInProgress = true
        val updatedSessions = repository.loadSessions()
        val refreshedSummary = updatedSessions
            .firstOrNull { it.folderName == session.folderName }
            ?: currentSummary
        currentSummary = refreshedSummary
        val frames = framesRepository.loadFrames(refreshedSummary)
        val marks = marksStore.loadOrCreate(refreshedSummary)
        val existingKeys = frames.mapTo(mutableSetOf()) { it.key }
        val existingBadKeys = marks.bad.intersect(existingKeys)
        val existingAutoBadKeys = marks.autoBad
            .intersect(existingKeys)
            .intersect(existingBadKeys)
        badFrameCount = existingBadKeys.size
        autoBadFrameCount = existingAutoBadKeys.size
        manualBadFrameCount = existingBadKeys.size - existingAutoBadKeys.size
        stackingFrameCount = frames.count {
            it.category == SessionFrameCategory.LIGHTS_JPEG &&
                it.key !in existingBadKeys
        }
        rawSupported = withContext(Dispatchers.IO) {
            deviceSupportsRaw(context.applicationContext)
        }
        checkInProgress = false
    }

    if (showingFrames) {
        SessionFramesScreen(
            session = currentSummary,
            onBack = {
                showingFrames = false
                checkRefreshKey++
            }
        )
        return
    }

    if (showingProcessedResults) {
        ProcessedResultsScreen(
            session = currentSummary,
            onBack = {
                showingProcessedResults = false
                checkRefreshKey++
            }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AstroTopBar(
                title = currentSummary.sessionName,
                onBack = onBack,
                actions = {
                    TextButton(onClick = { onOpenHelp(HelpTopic.SESSIONS) }) {
                        Text("?")
                    }
                }
            )
            if (active) {
                Text(
                    "Активная сессия",
                    modifier = Modifier.padding(horizontal = AstroSpacing.Lg),
                    color = AstroColors.Success
                )
            }
        }
        item {
            DetailCard(
                "Файлы",
                "Lights JPEG: ${currentSummary.lightsJpeg}\n" +
                    "Lights RAW: ${currentSummary.lightsRaw}\n" +
                    "Darks JPEG: ${currentSummary.darksJpeg}\n" +
                    "Darks RAW: ${currentSummary.darksRaw}\n" +
                    "Общий размер: ${formatFileSize(currentSummary.totalSizeBytes)}"
            )
        }
        item {
            AstroExpandableSection(title = "Технические сведения") {
                DetailCard("Путь", currentSummary.relativePath)
                DetailCard("session_info.txt", currentSummary.infoContent)
            }
        }
        item {
            AstroExpandableSection(title = "Управление сессией") {
                Button(
                    onClick = {
                        if (exportInProgress || stackingInProgress) {
                            managementStatus = "Сначала завершите текущую операцию"
                        } else {
                            renameInput = currentSummary.sessionName
                            managementStatus = null
                            renameDialogVisible = true
                        }
                    },
                    enabled = !managementInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Text("Переименовать")
                }
                Button(
                    onClick = {
                        if (exportInProgress || stackingInProgress) {
                            managementStatus = "Сначала завершите текущую операцию"
                        } else {
                            deleteStep = 1
                            deleteConfirmation = ""
                            managementStatus = null
                            deleteDialogVisible = true
                        }
                    },
                    enabled = !managementInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Text("Удалить сессию")
                }
            }
            managementStatus?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 8.dp),
                    color = if (it.startsWith("Ошибка")) {
                        AstroColors.Error
                    } else {
                        AstroColors.Success
                    }
                )
            }
        }
        item {
            AstroPrimaryButton(
                text = "Кадры сессии",
                onClick = { showingFrames = true },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            AstroSecondaryButton(
                text = "Результаты обработки",
                onClick = { showingProcessedResults = true },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            SessionQualityBlock(
                session = currentSummary,
                rawSupported = rawSupported,
                badFrameCount = badFrameCount,
                manualBadFrameCount = manualBadFrameCount,
                autoBadFrameCount = autoBadFrameCount,
                stackingFrameCount = stackingFrameCount,
                loading = checkInProgress,
                onRefresh = { checkRefreshKey++ }
            )
        }
        item {
            JpegStackingBlock(
                session = currentSummary,
                refreshKey = checkRefreshKey,
                onStackCompleted = { checkRefreshKey++ },
                onOpenHelp = onOpenHelp,
                onOpenResults = { showingProcessedResults = true },
                operationsEnabled = !exportInProgress && !managementInProgress,
                onOperationStateChanged = { stackingInProgress = it }
            )
        }
        item {
            Button(
                onClick = {
                    if (stackingInProgress || managementInProgress) {
                        exportStatus = "Сначала завершите текущую операцию"
                    } else {
                        requestExport()
                    }
                },
                enabled = !exportInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text(if (exportInProgress) "Экспорт..." else "Экспорт ZIP")
            }
            if (exportInProgress) {
                exportProgress?.let { progress ->
                    Text(
                        text = progress.message,
                        modifier = Modifier.padding(top = 8.dp),
                        color = AstroColors.TextSecondary
                    )
                }
                LinearProgressIndicator(
                    progress = {
                        val progress = exportProgress
                        if (progress != null && progress.total > 0) {
                            progress.current.toFloat() / progress.total
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            exportStatus?.let { status ->
                Text(
                    text = status,
                    modifier = Modifier.padding(top = 8.dp),
                    color = if (status.startsWith("Ошибка")) {
                        AstroColors.Error
                    } else {
                        AstroColors.Success
                    }
                )
            }
            lastExportResult?.let { exported ->
                Button(
                    onClick = {
                        shareSessionZip(context, exported)?.let {
                            exportStatus = it
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("Поделиться ZIP")
                }
            }
        }
        item {
            Button(
                onClick = {
                    sessionStore.save(currentSummary.toShootingSession())
                    active = true
                    onActivated()
                },
                enabled = !active,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (active) "Сессия активна" else "Сделать активной")
            }
        }
    }

    exportStorageWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { exportStorageWarning = null },
            title = { Text("Может не хватить места") },
            text = {
                Text(
                    "Для экспорта может не хватить места. Нужно примерно ${
                        formatStorageSize(warning.estimatedBytes)
                    }, свободно ${
                        warning.availableBytes?.let(::formatStorageSize)
                            ?: "неизвестно"
                    }."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        exportStorageWarning = null
                        startExport()
                    }
                ) {
                    Text("Экспортировать всё равно")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { exportStorageWarning = null }
                ) {
                    Text("Отмена")
                }
            }
        )
    }
    if (renameDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                if (!managementInProgress) renameDialogVisible = false
            },
            title = { Text("Переименовать сессию") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        label = { Text("Новое имя") },
                        singleLine = true
                    )
                    Text(
                        text = "Будет сохранено как: ${
                            sanitizeManagedName(renameInput).ifBlank { "—" }
                        }",
                        modifier = Modifier.padding(top = 6.dp),
                        color = AstroColors.TextSecondary
                    )
                    managementStatus?.takeIf {
                        it.startsWith("Ошибка переименования")
                    }?.let {
                        Text(
                            text = it,
                            modifier = Modifier.padding(top = 8.dp),
                            color = AstroColors.Error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = ::renameCurrentSession,
                    enabled = !managementInProgress &&
                        sanitizeManagedName(renameInput).isNotBlank()
                ) {
                    Text(if (managementInProgress) "Переименование..." else "Сохранить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { renameDialogVisible = false },
                    enabled = !managementInProgress
                ) {
                    Text("Отмена")
                }
            }
        )
    }
    if (deleteDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                if (!managementInProgress) deleteDialogVisible = false
            },
            title = { Text("Удалить сессию ${currentSummary.sessionName}?") },
            text = {
                Column {
                    if (deleteStep == 1) {
                        Text(
                            "Будут удалены все Lights, Darks, Tests, Processed " +
                                "и metadata этой сессии.\nРазмер сессии: ${
                                    formatFileSize(currentSummary.totalSizeBytes)
                                }" +
                                if (active) {
                                    "\nЭто активная сессия. После удаления она будет сброшена."
                                } else {
                                    ""
                                }
                        )
                    } else {
                        Text(
                            "Это окончательное подтверждение. Восстановить файлы " +
                                "средствами приложения будет нельзя."
                        )
                    }
                    if (deleteStep == 2 && deletionProtectionEnabled) {
                        OutlinedTextField(
                            value = deleteConfirmation,
                            onValueChange = { deleteConfirmation = it },
                            label = { Text("Введите УДАЛИТЬ") },
                            singleLine = true,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    if (managementInProgress && deleteProgressTotal > 0) {
                        Text(
                            text = "Удаление файла $deleteProgressCurrent " +
                                "из $deleteProgressTotal",
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        LinearProgressIndicator(
                            progress = {
                                deleteProgressCurrent.toFloat() /
                                    deleteProgressTotal.coerceAtLeast(1)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        )
                    }
                    managementStatus?.takeIf {
                        it.startsWith("Ошибка удаления")
                    }?.let {
                        Text(
                            text = it,
                            modifier = Modifier.padding(top = 8.dp),
                            color = AstroColors.Error
                        )
                    }
                }
            },
            confirmButton = {
                if (deleteStep == 1) {
                    Button(
                        onClick = { deleteStep = 2 },
                        enabled = !managementInProgress
                    ) {
                        Text("Продолжить")
                    }
                } else {
                    Button(
                        onClick = ::deleteCurrentSession,
                        enabled = !managementInProgress &&
                            (
                                !deletionProtectionEnabled ||
                                    deleteConfirmation.trim() == "УДАЛИТЬ"
                                ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = Color.White
                        )
                    ) {
                        Text(if (managementInProgress) "Удаление..." else "Удалить")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteDialogVisible = false },
                    enabled = !managementInProgress
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun SessionQualityBlock(
    session: SessionSummary,
    rawSupported: Boolean,
    badFrameCount: Int,
    manualBadFrameCount: Int,
    autoBadFrameCount: Int,
    stackingFrameCount: Int,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    val report = remember(session, rawSupported, badFrameCount) {
        analyzeSession(session, rawSupported, badFrameCount)
    }
    val statusContainer = when (report.status) {
        SessionQualityStatus.READY -> AstroColors.SuccessSurface
        SessionQualityStatus.IMPROVE -> AstroColors.WarningSurface
        SessionQualityStatus.PROBLEM -> AstroColors.ErrorSurface
    }
    val hasInfo = session.infoContent.isNotBlank() &&
        session.infoContent != "Нет информации"

    Card(
        colors = CardDefaults.cardColors(containerColor = statusContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Проверка сессии",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = report.status.title,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = report.status.color
            )

            Text(
                text = "Найдено",
                modifier = Modifier.padding(top = 14.dp),
                fontWeight = FontWeight.SemiBold
            )
            Text("Lights/JPEG: ${session.lightsJpeg}")
            Text("Lights/RAW: ${session.lightsRaw}")
            Text("Darks/JPEG: ${session.darksJpeg}")
            Text("Darks/RAW: ${session.darksRaw}")
            Text("Всего light frames: ${session.lightFrames}")
            Text("Всего dark frames: ${session.darkFrames}")
            Text("Всего кадров: ${session.lightFrames + session.darkFrames}")
            Text(
                "Кадров без брака: ${
                    (session.lightFrames + session.darkFrames - badFrameCount).coerceAtLeast(0)
                }"
            )
            Text("Брак вручную: $manualBadFrameCount")
            Text("Найдено автоотбором: $autoBadFrameCount")
            Text("Будет использовано в JPEG stacking: $stackingFrameCount")
            Text("session_info.txt: ${if (hasInfo) "есть" else "нет"}")
            Text("RAW/DNG: ${if (session.lightsRaw + session.darksRaw > 0) "есть" else "нет"}")
            Text("JPEG: ${if (session.lightsJpeg + session.darksJpeg > 0) "есть" else "нет"}")
            Text("Размер: ${formatFileSize(session.totalSizeBytes)}")

            report.metadataSummary?.let {
                Text(
                    text = "Параметры: $it",
                    modifier = Modifier.padding(top = 10.dp),
                    color = AstroColors.TextSecondary
                )
            }

            Text(
                text = "Рекомендации",
                modifier = Modifier.padding(top = 14.dp),
                fontWeight = FontWeight.SemiBold
            )
            report.recommendations.forEach { recommendation ->
                Text(
                    text = "• $recommendation",
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            Button(
                onClick = onRefresh,
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            ) {
                Text(if (loading) "Проверка..." else "Обновить проверку")
            }
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                text = content,
                modifier = Modifier.padding(top = 6.dp),
                color = AstroColors.TextSecondary
            )
        }
    }
}

@Composable
private fun SessionCreateDialog(
    name: String,
    note: String,
    onNameChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая сессия") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChanged,
                    label = { Text("Имя сессии") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChanged,
                    label = { Text("Заметка") },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = onCreate) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

private fun formatSessionDate(timestamp: Long): String =
    if (timestamp <= 0L) {
        "Дата неизвестна"
    } else {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

private fun shareSessionZip(
    context: Context,
    result: SessionZipExportResult
): String? = runCatching {
    val uri = result.contentUri?.let(Uri::parse)
        ?: result.filePath?.let { path ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(path)
            )
        }
        ?: error("Не удалось получить доступ к ZIP")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(result.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться ZIP"))
}.exceptionOrNull()?.let {
    "Не удалось поделиться ZIP: ${it.message ?: "приложение не найдено"}"
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(
        Locale.getDefault(),
        "%.2f ГБ",
        bytes / 1_073_741_824.0
    )
    bytes >= 1_048_576L -> String.format(
        Locale.getDefault(),
        "%.1f МБ",
        bytes / 1_048_576.0
    )
    bytes >= 1_024L -> String.format(
        Locale.getDefault(),
        "%.1f КБ",
        bytes / 1_024.0
    )
    else -> "$bytes Б"
}

private fun deviceSupportsRaw(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(CameraManager::class.java)
    manager.cameraIdList.any { cameraId ->
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val rearCamera = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        )
        rearCamera && capabilities?.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        ) == true
    }
}.getOrDefault(false)
