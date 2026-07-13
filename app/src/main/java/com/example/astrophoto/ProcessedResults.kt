package com.example.astrophoto

import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.astrophoto.ui.AstroEmptyState
import com.example.astrophoto.ui.AstroErrorState
import com.example.astrophoto.ui.AstroExpandableSection
import com.example.astrophoto.ui.AstroLoadingState
import com.example.astrophoto.ui.AstroScaffold
import com.example.astrophoto.ui.AstroSecondaryButton
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.AstroTestTags
import com.example.astrophoto.ui.theme.AstroColors

enum class ProcessedResultType(val title: String) {
    STACK("Обычный stack"),
    DARK_STACK("Dark stack"),
    ALIGNED_STACK("Aligned stack"),
    DARK_ALIGNED_STACK("Dark + aligned stack"),
    MASTER_DARK("Master dark"),
    MEDIAN_STACK("Median stack"),
    MEDIAN_ALIGNED_STACK("Median + aligned stack"),
    SIGMA_STACK("Sigma clipping"),
    SIGMA_ALIGNED_STACK("Sigma + aligned"),
    DEEP_SKY("Чистое небо"),
    DEEP_SKY_ALIGNED("Чистое небо + alignment"),
    URBAN_SKY("Город / окно"),
    URBAN_SKY_STRONG("Город / окно — сильная обработка"),
    MAX_STARS("Максимум звёзд"),
    RECOVERED_STARS("Восстановление звёзд"),
    BACKGROUND_REMOVED("Удаление засветки"),
    STARS_ONLY_PREVIEW("Превью звёзд"),
    EDITED("Отредактировано"),
    UNKNOWN("Неизвестный результат")
}

data class ProcessedResult(
    val key: String,
    val fileName: String,
    val type: ProcessedResultType,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val displayPath: String,
    val contentUri: String?,
    val filePath: String?,
    val relativePath: String? = null,
    val isReadable: Boolean = true,
    val errorMessage: String? = null
)

private data class ProcessedRenameResult(
    val fileName: String,
    val metadataUpdated: Boolean
)

private data class ComparisonImage(
    val label: String,
    val displayPath: String,
    val contentUri: String?,
    val filePath: String?,
    val relativePath: String?
)

private sealed interface ViewerImageState {
    data object Loading : ViewerImageState
    data class Loaded(val image: ImageLoadResult.Success) : ViewerImageState
    data class Error(val error: ImageLoadResult.Error) : ViewerImageState
}

private const val LOAD_PROCESSED_THUMBNAILS = false

private class ProcessedResultsRepository(private val context: Context) {
    private val imageLoader = SafeImageLoader(context)

    suspend fun loadResults(session: SessionSummary): List<ProcessedResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    loadMediaStoreResults(session)
                } else {
                    loadLegacyResults(session)
                }
            }.onFailure { error ->
                Log.e(
                    "AstroPhotoResults",
                    "Failed to load processed results: ${error.message}",
                    error
                )
            }.getOrDefault(emptyList())
        }

    suspend fun deleteResult(
        session: SessionSummary,
        result: ProcessedResult
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val deleted = if (result.contentUri != null) {
                require(
                    mediaStoreResultBelongsToSession(
                        Uri.parse(result.contentUri),
                        session
                    )
                ) {
                    "Файл не принадлежит Processed текущей сессии"
                }
                context.contentResolver.delete(
                    Uri.parse(result.contentUri),
                    null,
                    null
                ) > 0
            } else {
                result.filePath?.let(::File)?.let { file ->
                    require(legacyResultBelongsToSession(file, session)) {
                        "Файл не принадлежит Processed текущей сессии"
                    }
                    !file.exists() || file.delete()
                } == true
            }
            require(deleted) { "Файл уже удалён или недоступен" }
            runCatching {
                appendDeletedResultInfo(session, result.fileName)
            }.isSuccess
        }
    }

    suspend fun renameResult(
        session: SessionSummary,
        result: ProcessedResult,
        requestedName: String
    ): Result<ProcessedRenameResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeBase = sanitizeManagedName(
                requestedName.removeSuffix(".jpg").removeSuffix(".JPG")
            )
            require(safeBase.isNotBlank()) { "Имя файла не может быть пустым" }
            val newName = "$safeBase.jpg"
            require(!newName.equals(result.fileName, ignoreCase = true)) {
                "Новое имя совпадает с текущим"
            }
            if (result.contentUri != null) {
                require(
                    mediaStoreResultBelongsToSession(
                        Uri.parse(result.contentUri),
                        session
                    )
                ) {
                    "Файл не принадлежит Processed текущей сессии"
                }
                val path =
                    "${Environment.DIRECTORY_PICTURES}/AstroPhoto/" +
                        "${session.folderName}/Processed/"
                require(!mediaStoreResultExists(path, newName)) {
                    "Файл с таким именем уже существует"
                }
                val updated = context.contentResolver.update(
                    Uri.parse(result.contentUri),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, newName)
                    },
                    null,
                    null
                )
                require(updated == 1) { "Не удалось переименовать файл" }
            } else {
                val source = result.filePath?.let(::File)
                    ?: error("Файл недоступен")
                require(legacyResultBelongsToSession(source, session)) {
                    "Файл не принадлежит Processed текущей сессии"
                }
                val target = File(source.parentFile, newName)
                require(!target.exists()) { "Файл с таким именем уже существует" }
                require(source.exists() && source.renameTo(target)) {
                    "Не удалось переименовать файл"
                }
            }
            val metadataUpdated = runCatching {
                appendRenamedResultInfo(
                    session = session,
                    oldName = result.fileName,
                    newName = newName
                )
            }.isSuccess
            ProcessedRenameResult(newName, metadataUpdated)
        }
    }

    suspend fun loadPreview(
        result: ProcessedResult,
        maxSize: Int
    ): Bitmap? =
        when (val loaded = loadProcessedImage(result, maxSize)) {
            is ImageLoadResult.Success -> loaded.bitmap
            is ImageLoadResult.Error -> null
        }

    suspend fun loadComparisonPreview(
        image: ComparisonImage,
        maxSize: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        when (val loaded = loadComparisonImage(image, maxSize)) {
            is ImageLoadResult.Success -> loaded.bitmap
            is ImageLoadResult.Error -> null
        }
    }

    suspend fun loadProcessedImage(
        result: ProcessedResult,
        maxSize: Int = 3072
    ): ImageLoadResult {
        return imageLoader.loadBitmapForViewer(result.imageSource(), maxSize)
    }

    suspend fun loadComparisonImage(
        image: ComparisonImage,
        maxSize: Int = 1400
    ): ImageLoadResult {
        return imageLoader.loadBitmapForViewer(image.imageSource(), maxSize)
    }

    private fun loadMediaStoreResults(
        session: SessionSummary
    ): List<ProcessedResult> {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val relativePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/" +
                "${session.folderName}/Processed/"
        val results = mutableListOf<ProcessedResult>()

        resolver.query(
            collection,
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.MIME_TYPE
            ),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf(relativePath),
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns._ID
            )
            val nameIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val sizeIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.SIZE
            )
            val dateIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.DATE_ADDED
            )
            val mimeIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.MIME_TYPE
            )

            while (cursor.moveToNext()) {
                val fileName = cursor.getString(nameIndex).orEmpty()
                val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                val mimeType = cursor.getString(mimeIndex).orEmpty()
                val type = processedResultType(fileName)
                val uri = ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(idIndex)
                )
                val readable = isSupportedProcessedImage(fileName, mimeType) &&
                    size > 0L
                results += ProcessedResult(
                    key = uri.toString(),
                    fileName = fileName,
                    type = type,
                    createdAtMillis =
                        cursor.getLong(dateIndex).coerceAtLeast(0L) * 1_000L,
                    sizeBytes = size,
                    displayPath =
                        "Pictures/AstroPhoto/${session.folderName}/" +
                            "Processed/$fileName",
                    contentUri = uri.toString(),
                    filePath = null,
                    relativePath = relativePath,
                    isReadable = readable,
                    errorMessage = processedFileProblem(fileName, mimeType, size)
                )
            }
        }
        return results
    }

    @Suppress("DEPRECATION")
    private fun loadLegacyResults(
        session: SessionSummary
    ): List<ProcessedResult> {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val directory = File(
            pictures,
            "AstroPhoto/${session.folderName}/Processed"
        )
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.map { file ->
                val type = processedResultType(file.name)
                val size = file.length().coerceAtLeast(0L)
                val readable = isSupportedProcessedImage(file.name, null) &&
                    size > 0L &&
                    file.canRead()
                ProcessedResult(
                    key = file.absolutePath,
                    fileName = file.name,
                    type = type,
                    createdAtMillis = file.lastModified(),
                    sizeBytes = size,
                    displayPath = file.absolutePath,
                    contentUri = null,
                    filePath = file.absolutePath,
                    relativePath = null,
                    isReadable = readable,
                    errorMessage = processedFileProblem(file.name, null, size)
                        ?: if (!file.canRead()) {
                            "Файл не удалось открыть"
                        } else {
                            null
                        }
                )
            }
            ?.sortedWith(
                compareByDescending<ProcessedResult> { it.createdAtMillis }
                    .thenBy { it.fileName }
            )
            ?.toList()
            .orEmpty()
    }

    private fun processedResultType(fileName: String): ProcessedResultType =
        when {
            fileName.startsWith("StackedDarkAligned_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.DARK_ALIGNED_STACK
            fileName.startsWith("StackedAligned_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.ALIGNED_STACK
            fileName.startsWith("StackedDark_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.DARK_STACK
            fileName.startsWith("Stacked_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.STACK
            fileName.startsWith("MasterDark_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.MASTER_DARK
            fileName.startsWith("MedianAligned_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.MEDIAN_ALIGNED_STACK
            fileName.startsWith("Median_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.MEDIAN_STACK
            fileName.startsWith("SigmaAligned_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.SIGMA_ALIGNED_STACK
            fileName.startsWith("Sigma_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.SIGMA_STACK
            fileName.startsWith("DeepSkyAligned_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.DEEP_SKY_ALIGNED
            fileName.startsWith("DeepSky_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.DEEP_SKY
            fileName.startsWith("UrbanSkyStrong_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.URBAN_SKY_STRONG
            fileName.startsWith("UrbanSky_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.URBAN_SKY
            fileName.startsWith("MaxStars_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.MAX_STARS
            fileName.startsWith("RecoveredStars_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.RECOVERED_STARS
            fileName.startsWith("BackgroundRemoved_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.BACKGROUND_REMOVED
            fileName.startsWith("StarsOnlyPreview_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.STARS_ONLY_PREVIEW
            fileName.startsWith("Edited_") &&
                fileName.endsWith(".jpg", ignoreCase = true) ->
                ProcessedResultType.EDITED
            else -> ProcessedResultType.UNKNOWN
        }

    private fun isSupportedProcessedImage(
        fileName: String,
        mimeType: String?
    ): Boolean {
        val jpgName = fileName.endsWith(".jpg", ignoreCase = true) ||
            fileName.endsWith(".jpeg", ignoreCase = true)
        val jpgMime = mimeType.isNullOrBlank() ||
            mimeType.equals("image/jpeg", ignoreCase = true)
        return jpgName && jpgMime
    }

    private fun processedFileProblem(
        fileName: String,
        mimeType: String?,
        sizeBytes: Long
    ): String? = when {
        sizeBytes <= 0L -> "Файл не удалось открыть"
        !isSupportedProcessedImage(fileName, mimeType) ->
            "Файл не является JPEG"
        else -> null
    }

    private fun mediaStoreResultExists(path: String, name: String): Boolean =
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf(path),
            null
        )?.use { cursor ->
            var exists = false
            while (cursor.moveToNext() && !exists) {
                exists = cursor.getString(0).equals(name, ignoreCase = true)
            }
            exists
        } == true

    private fun mediaStoreResultBelongsToSession(
        uri: Uri,
        session: SessionSummary
    ): Boolean {
        val expected =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/" +
                "${session.folderName}/Processed/"
        return context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == expected
        } == true
    }

    @Suppress("DEPRECATION")
    private fun legacyResultBelongsToSession(
        file: File,
        session: SessionSummary
    ): Boolean {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val expected = File(
            pictures,
            "AstroPhoto/${session.folderName}/Processed"
        ).canonicalFile
        return file.canonicalFile.parentFile == expected
    }

    private fun appendDeletedResultInfo(
        session: SessionSummary,
        fileName: String
    ) {
        val deletedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
        val block = buildString {
            appendLine()
            appendLine("deletedProcessedFile: $fileName")
            appendLine("deletedAt: $deletedAt")
        }
        appendProcessedInfo(session, block)
    }

    private fun appendProcessedInfo(
        session: SessionSummary,
        block: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri("external")
            val path =
                "${Environment.DIRECTORY_PICTURES}/AstroPhoto/" +
                    "${session.folderName}/"
            val uri = resolver.query(
                collection,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
                arrayOf("session_info.txt", path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            } ?: error("session_info.txt не найден")
            val existing = resolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                it.write(existing.trimEnd())
                it.write(block)
            } ?: error("Не удалось обновить session_info.txt")
        } else {
            @Suppress("DEPRECATION")
            val pictures = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )
            val info = File(
                pictures,
                "AstroPhoto/${session.folderName}/session_info.txt"
            )
            info.parentFile?.mkdirs()
            if (!info.exists()) {
                info.writeText("sessionName: ${session.sessionName}\n")
            }
            info.appendText(block)
        }
    }

    private fun appendRenamedResultInfo(
        session: SessionSummary,
        oldName: String,
        newName: String
    ) {
        val renamedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
        appendProcessedInfo(
            session,
            buildString {
                appendLine()
                appendLine("renamedProcessedFileOld: $oldName")
                appendLine("renamedProcessedFileNew: $newName")
                appendLine("renamedProcessedAt: $renamedAt")
            }
        )
    }

    private fun missingImageSource(label: String): ImageLoadResult.Error {
        val technicalMessage = "No path or uri for $label"
        Log.e("AstroPhotoImageOpen", technicalMessage)
        return ImageLoadResult.Error(
            userTitle = "Файл не найден",
            userMessage = "Возможно, он был удалён.",
            technicalMessage = technicalMessage
        )
    }
}

@Composable
fun ProcessedResultsScreen(
    session: SessionSummary,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember {
        ProcessedResultsRepository(context.applicationContext)
    }
    val framesRepository = remember {
        SessionFramesRepository(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var results by remember(session.folderName) {
        mutableStateOf<List<ProcessedResult>>(emptyList())
    }
    var firstLight by remember(session.folderName) {
        mutableStateOf<SessionFrame?>(null)
    }
    var loading by remember(session.folderName) { mutableStateOf(true) }
    var compareMode by remember { mutableStateOf(false) }
    var comparisonSelection by remember {
        mutableStateOf<List<ProcessedResult>>(emptyList())
    }
    var selectedResult by remember { mutableStateOf<ProcessedResult?>(null) }
    var editingResult by remember { mutableStateOf<ProcessedResult?>(null) }
    var pendingDelete by remember { mutableStateOf<ProcessedResult?>(null) }
    var pendingRename by remember { mutableStateOf<ProcessedResult?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var operationRunning by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }
    var loadError by remember(session.folderName) { mutableStateOf<String?>(null) }
    var refreshKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var comparison by remember {
        mutableStateOf<Pair<ComparisonImage, ComparisonImage>?>(null)
    }

    LaunchedEffect(session.folderName, refreshKey) {
        loading = true
        loadError = null
        runCatching {
            val loadedResults = repository.loadResults(session).toList()
            val loadedFirstLight = runCatching {
                framesRepository.loadFrames(session)
                    .filter { it.category == SessionFrameCategory.LIGHTS_JPEG }
                    .minWithOrNull(
                        compareBy<SessionFrame> { it.createdAtMillis }
                            .thenBy { it.fileName }
                    )
            }.onFailure { error ->
                Log.e(
                    "AstroPhotoResults",
                    "Failed to load first light for comparison: ${error.message}",
                    error
                )
            }.getOrNull()
            loadedResults to loadedFirstLight
        }.onSuccess { loaded ->
            results = loaded.first
            firstLight = loaded.second
            comparisonSelection = emptyList()
        }.onFailure { error ->
            Log.e(
                "AstroPhotoResults",
                "Failed to open processed results screen: ${error.message}",
                error
            )
            results = emptyList()
            firstLight = null
            loadError =
                "Не удалось открыть результаты. Один из файлов повреждён или не читается."
        }
        loading = false
    }

    val displayedResults = results.toList()

    editingResult?.let { result ->
        ProcessedImageEditorScreen(
            session = session,
            source = result,
            onBack = {
                editingResult = null
                refreshKey++
            },
            onSaved = { refreshKey++ }
        )
        return
    }

    AstroScaffold(title = "Результаты обработки", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AstroSpacing.Lg)
        ) {
        operationStatus?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 6.dp),
                color = if (it.startsWith("Ошибка")) {
                    AstroColors.Error
                } else {
                    AstroColors.Success
                }
            )
        }

        if (displayedResults.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AstroSecondaryButton(
                    text = if (compareMode) "Отменить сравнение" else "Сравнить",
                    onClick = {
                        compareMode = !compareMode
                        comparisonSelection = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (compareMode) {
                    Button(
                        onClick = {
                            if (comparisonSelection.size == 2) {
                                comparison =
                                    comparisonSelection[0].toComparisonImage() to
                                        comparisonSelection[1].toComparisonImage()
                            }
                        },
                        enabled = comparisonSelection.size == 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Text("Открыть (${comparisonSelection.size}/2)")
                    }
                }
            }
            if (compareMode) {
                Text(
                    text = "Выберите два результата",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when {
            loading -> {
                AstroLoadingState(
                    message = "Загружаем результаты…",
                    modifier = Modifier.weight(1f)
                )
            }
            loadError != null -> {
                AstroErrorState(
                    title = "Не удалось открыть результаты",
                    message = loadError.orEmpty(),
                    modifier = Modifier.weight(1f),
                    action = {
                        AstroSecondaryButton(
                            text = "Повторить",
                            onClick = { refreshKey++ }
                        )
                    }
                )
            }
            displayedResults.isEmpty() -> {
                AstroEmptyState(
                    title = "Результатов пока нет",
                    message = "Запустите обработку кадров этой сессии",
                    modifier = Modifier.weight(1f)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        displayedResults,
                        key = { index, result ->
                            result.key.ifBlank {
                                "$index-${result.fileName}-${result.sizeBytes}-${result.createdAtMillis}"
                            }
                        }
                    ) { _, result ->
                        ProcessedResultCard(
                            result = result,
                            repository = repository,
                            compareMode = compareMode,
                            selectedForComparison = result in comparisonSelection,
                            onEdit = { editingResult = result },
                            onRename = {
                                renameInput = result.fileName.substringBeforeLast('.')
                                operationStatus = null
                                pendingRename = result
                            },
                            onDelete = {
                                operationStatus = null
                                pendingDelete = result
                            },
                            onExport = {
                                operationStatus = shareResult(context, result)
                            },
                            onClick = {
                                if (compareMode) {
                                    comparisonSelection = when {
                                        result in comparisonSelection ->
                                            comparisonSelection - result
                                        comparisonSelection.size < 2 ->
                                            comparisonSelection + result
                                        else -> listOf(
                                            comparisonSelection.last(),
                                            result
                                        )
                                    }
                                } else {
                                    selectedResult = result
                                }
                            }
                        )
                    }
                }
            }
        }
        }
    }

    selectedResult?.let { result ->
        ProcessedResultViewer(
            result = result,
            firstLight = firstLight,
            repository = repository,
            onCompareWithFirst = { light ->
                selectedResult = null
                comparison = light.toComparisonImage() to result.toComparisonImage()
            },
            onDismiss = { selectedResult = null }
        )
    }

    comparison?.let { images ->
        ProcessedComparisonViewer(
            first = images.first,
            second = images.second,
            repository = repository,
            onDismiss = { comparison = null }
        )
    }

    pendingRename?.let { result ->
        AlertDialog(
            onDismissRequest = {
                if (!operationRunning) pendingRename = null
            },
            title = { Text("Переименовать результат") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        label = { Text("Новое имя") },
                        singleLine = true
                    )
                    Text(
                        text = "Расширение .jpg будет сохранено.",
                        modifier = Modifier.padding(top = 6.dp),
                        color = AstroColors.TextSecondary
                    )
                    operationStatus?.takeIf {
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
                    onClick = {
                        operationRunning = true
                        coroutineScope.launch {
                            val renamed = repository.renameResult(
                                session,
                                result,
                                renameInput
                            )
                            operationRunning = false
                            renamed.fold(
                                onSuccess = {
                                    pendingRename = null
                                    operationStatus = buildString {
                                        append("Файл переименован: ${it.fileName}")
                                        if (!it.metadataUpdated) {
                                            append(
                                                "\nНе удалось обновить " +
                                                    "session_info.txt"
                                            )
                                        }
                                    }
                                    refreshKey++
                                },
                                onFailure = {
                                    operationStatus =
                                        "Ошибка переименования: ${
                                            it.message ?: "неизвестная ошибка"
                                        }"
                                }
                            )
                        }
                    },
                    enabled = !operationRunning &&
                        sanitizeManagedName(renameInput).isNotBlank()
                ) {
                    Text(if (operationRunning) "Переименование..." else "Сохранить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRename = null },
                    enabled = !operationRunning
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    pendingDelete?.let { result ->
        AlertDialog(
            onDismissRequest = {
                if (!operationRunning) pendingDelete = null
            },
            title = { Text("Удалить ${result.fileName}?") },
            text = {
                Column {
                    Text(
                        "Будет удалён только этот файл из Processed. " +
                            "Lights, Darks и RAW не изменятся."
                    )
                    operationStatus?.takeIf {
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
                Button(
                    onClick = {
                        operationRunning = true
                        coroutineScope.launch {
                            val deleted = repository.deleteResult(session, result)
                            operationRunning = false
                            deleted.fold(
                                onSuccess = { infoUpdated ->
                                    pendingDelete = null
                                    selectedResult = null
                                    operationStatus = buildString {
                                        append("Файл удалён: ${result.fileName}")
                                        if (!infoUpdated) {
                                            append(
                                                "\nНе удалось обновить " +
                                                    "session_info.txt"
                                            )
                                        }
                                    }
                                    refreshKey++
                                },
                                onFailure = {
                                    operationStatus =
                                        "Ошибка удаления: ${
                                            it.message ?: "неизвестная ошибка"
                                        }"
                                }
                            )
                        }
                    },
                    enabled = !operationRunning,
                    colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text(if (operationRunning) "Удаление..." else "Удалить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    enabled = !operationRunning
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun ProcessedResultCard(
    result: ProcessedResult,
    repository: ProcessedResultsRepository,
    compareMode: Boolean,
    selectedForComparison: Boolean,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onClick: () -> Unit
) {
    var thumbnail by remember(result.key) { mutableStateOf<Bitmap?>(null) }
    var thumbnailFailed by remember(result.key) { mutableStateOf(false) }

    LaunchedEffect(result.key) {
        if (!LOAD_PROCESSED_THUMBNAILS) {
            thumbnail = null
            thumbnailFailed = false
        } else if (result.isReadable) {
            thumbnail = repository.loadPreview(result, 320)
            thumbnailFailed = thumbnail == null
        } else {
            thumbnail = null
            thumbnailFailed = true
        }
    }
    DisposableEffect(thumbnail) {
        onDispose {
            thumbnail?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    val thumbnailLoading = LOAD_PROCESSED_THUMBNAILS &&
        result.isReadable &&
        thumbnail == null &&
        !thumbnailFailed
    val hasKnownProblem = !result.isReadable || result.errorMessage != null
    val source = result.imageSource()
    val canOpen = !source.providerUri.isNullOrBlank() ||
        !source.relativePath.isNullOrBlank() ||
        !source.legacyFilePath.isNullOrBlank()
    val canEdit = result.isReadable && canOpen && !thumbnailLoading
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AstroTestTags.ResultCard),
        colors = CardDefaults.cardColors(
            containerColor = if (selectedForComparison) {
                MaterialTheme.colorScheme.primaryContainer
            } else if (hasKnownProblem) {
                AstroColors.ErrorSurface
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(82.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    thumbnail?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = result.fileName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } ?: if (thumbnailFailed || hasKnownProblem) {
                        Text("Ошибка", color = AstroColors.Error)
                    } else if (thumbnailLoading) {
                        CircularProgressIndicator()
                    } else {
                        Text("JPEG", color = AstroColors.TextSecondary)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = result.fileName,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = result.type.title,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (result.type == ProcessedResultType.RECOVERED_STARS) {
                        Text(
                            text = "Проверьте возможные артефакты",
                            color = AstroColors.Warning,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (hasKnownProblem) {
                        Text(
                            text = result.errorMessage ?: "Файл не удалось открыть",
                            color = AstroColors.Error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "${formatProcessedDate(result.createdAtMillis)} • " +
                            formatProcessedSize(result.sizeBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (selectedForComparison) {
                        Text(
                            text = "Выбран для сравнения",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (compareMode) {
                    Checkbox(
                        checked = selectedForComparison,
                        onCheckedChange = { if (canOpen) onClick() },
                        enabled = canOpen
                    )
                }
            }
            if (!compareMode) {
                ProcessedResultActions(
                    canOpen = canOpen,
                    canEdit = canEdit,
                    onOpen = onClick,
                    onEdit = onEdit,
                    onExport = onExport,
                    onRename = onRename,
                    onDelete = onDelete,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun ProcessedResultActions(
    canOpen: Boolean,
    canEdit: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AstroTestTags.ResultActions),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onOpen,
            enabled = canOpen,
            modifier = Modifier.weight(1f)
        ) {
            Text("Открыть")
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .testTag(AstroTestTags.ResultActionsMenu)
                    .semantics { contentDescription = "Действия с результатом" }
            ) {
                Text("⋮", style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Редактировать") },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                    enabled = canEdit
                )
                DropdownMenuItem(
                    text = { Text("Экспорт / поделиться") },
                    onClick = {
                        menuExpanded = false
                        onExport()
                    },
                    enabled = canOpen
                )
                DropdownMenuItem(
                    text = { Text("Переименовать") },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Удалить", color = AstroColors.Error) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun ProcessedResultViewer(
    result: ProcessedResult,
    firstLight: SessionFrame?,
    repository: ProcessedResultsRepository,
    onCompareWithFirst: (SessionFrame) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var imageState by remember(result.key) {
        mutableStateOf<ViewerImageState>(ViewerImageState.Loading)
    }
    var actionError by remember(result.key) { mutableStateOf<String?>(null) }

    LaunchedEffect(result.key) {
        imageState = ViewerImageState.Loading
        imageState = when (val loaded = repository.loadProcessedImage(result, 3072)) {
            is ImageLoadResult.Success -> ViewerImageState.Loaded(loaded)
            is ImageLoadResult.Error -> ViewerImageState.Error(loaded)
        }
    }
    val loadedBitmap = (imageState as? ViewerImageState.Loaded)?.image?.bitmap
    DisposableEffect(loadedBitmap) {
        onDispose {
            loadedBitmap
                ?.takeUnless(Bitmap::isRecycled)
                ?.recycle()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(16.dp)
        ) {
            com.example.astrophoto.ui.AstroTopBar(
                title = "Просмотр результата",
                onBack = onDismiss
            )
            Text(
                text = result.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                when (val state = imageState) {
                    ViewerImageState.Loading -> CircularProgressIndicator()
                    is ViewerImageState.Loaded -> Image(
                        bitmap = state.image.bitmap.asImageBitmap(),
                        contentDescription = result.fileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    is ViewerImageState.Error -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = state.error.userTitle,
                            color = AstroColors.Error,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = state.error.userMessage,
                            color = AstroColors.TextSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                        ) {
                            Text("Назад")
                        }
                    }
                }
            }

            (imageState as? ViewerImageState.Loaded)?.image?.let { loaded ->
                if (loaded.warningTitle != null || loaded.warningMessage != null) {
                    Text(
                        text = listOfNotNull(
                            loaded.warningTitle,
                            loaded.warningMessage
                        ).joinToString("\n"),
                        color = AstroColors.Warning
                    )
                }
            }
            actionError?.let {
                Text(it, color = AstroColors.Error)
            }
            if (imageState is ViewerImageState.Loaded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            actionError = openResultInGallery(context, result)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Открыть в галерее")
                    }
                    Button(
                        onClick = {
                            actionError = shareResult(context, result)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Поделиться")
                    }
                }
                Button(
                    onClick = {
                        firstLight?.let(onCompareWithFirst)
                    },
                    enabled = firstLight != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        if (firstLight != null) {
                            "Сравнить с первым light frame"
                        } else {
                            "Исходный кадр не найден"
                        }
                    )
                }
            }
            AstroExpandableSection(title = "Технические сведения") {
                Text(
                    text = result.displayPath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ProcessedComparisonViewer(
    first: ComparisonImage,
    second: ComparisonImage,
    repository: ProcessedResultsRepository,
    onDismiss: () -> Unit
) {
    var firstState by remember(first) {
        mutableStateOf<ViewerImageState>(ViewerImageState.Loading)
    }
    var secondState by remember(second) {
        mutableStateOf<ViewerImageState>(ViewerImageState.Loading)
    }
    var loading by remember(first, second) { mutableStateOf(true) }

    LaunchedEffect(first, second) {
        loading = true
        firstState = ViewerImageState.Loading
        secondState = ViewerImageState.Loading
        firstState = when (val loaded = repository.loadComparisonImage(first, 1400)) {
            is ImageLoadResult.Success -> ViewerImageState.Loaded(loaded)
            is ImageLoadResult.Error -> ViewerImageState.Error(loaded)
        }
        secondState = when (val loaded = repository.loadComparisonImage(second, 1400)) {
            is ImageLoadResult.Success -> ViewerImageState.Loaded(loaded)
            is ImageLoadResult.Error -> ViewerImageState.Error(loaded)
        }
        loading = false
    }
    val firstBitmap = (firstState as? ViewerImageState.Loaded)?.image?.bitmap
    val secondBitmap = (secondState as? ViewerImageState.Loaded)?.image?.bitmap
    DisposableEffect(firstBitmap, secondBitmap) {
        onDispose {
            firstBitmap
                ?.takeUnless(Bitmap::isRecycled)
                ?.recycle()
            secondBitmap
                ?.takeUnless(Bitmap::isRecycled)
                ?.recycle()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(12.dp)
        ) {
            com.example.astrophoto.ui.AstroTopBar(
                title = "Сравнение",
                onBack = onDismiss
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (maxWidth > maxHeight) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ComparisonPane(
                            state = firstState,
                            label = first.label,
                            modifier = Modifier.weight(1f)
                        )
                        ComparisonPane(
                            state = secondState,
                            label = second.label,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ComparisonPane(
                            state = firstState,
                            label = first.label,
                            modifier = Modifier.weight(1f)
                        )
                        ComparisonPane(
                            state = secondState,
                            label = second.label,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (!loading && (
                    firstState is ViewerImageState.Error ||
                        secondState is ViewerImageState.Error
                )
            ) {
                Text(
                    text = "Один из файлов не удалось прочитать",
                    color = AstroColors.Error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ComparisonPane(
    state: ViewerImageState,
    label: String,
    modifier: Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                ViewerImageState.Loading -> CircularProgressIndicator()
                is ViewerImageState.Loaded -> Image(
                    bitmap = state.image.bitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                is ViewerImageState.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = state.error.userTitle,
                        color = AstroColors.Error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.error.userMessage,
                        color = AstroColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private fun ProcessedResult.toComparisonImage(): ComparisonImage =
    ComparisonImage(
        label = fileName,
        displayPath = displayPath,
        contentUri = contentUri,
        filePath = filePath,
        relativePath = relativePath
    )

private fun ProcessedResult.imageSource(): ImageSourceReference = imageSourceReference(
    displayName = fileName,
    displayPath = displayPath,
    relativePath = relativePath,
    providerUri = contentUri,
    legacyFilePath = filePath
)

private fun ComparisonImage.imageSource(): ImageSourceReference = imageSourceReference(
    displayName = label,
    displayPath = displayPath,
    relativePath = relativePath,
    providerUri = contentUri,
    legacyFilePath = filePath
)

private fun SessionFrame.toComparisonImage(): ComparisonImage =
    ComparisonImage(
        label = fileName,
        displayPath = displayPath,
        contentUri = contentUri,
        filePath = filePath,
        relativePath = null
    )

private fun resultContentUri(context: Context, result: ProcessedResult): Uri? =
    result.contentUri?.let(Uri::parse)
        ?: result.filePath?.let { path ->
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(path)
                )
            }.getOrNull()
        }

private fun openResultInGallery(
    context: Context,
    result: ProcessedResult
): String? = runCatching {
    val uri = resultContentUri(context, result)
        ?: error("Не удалось получить доступ к файлу")
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
}.exceptionOrNull()?.let {
    "Не удалось открыть галерею: ${it.message ?: "приложение не найдено"}"
}

private fun shareResult(
    context: Context,
    result: ProcessedResult
): String? = runCatching {
    val uri = resultContentUri(context, result)
        ?: error("Не удалось получить доступ к файлу")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(result.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться результатом"))
}.exceptionOrNull()?.let {
    "Не удалось поделиться: ${it.message ?: "приложение не найдено"}"
}

private fun formatProcessedSize(bytes: Long): String = when {
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

private fun formatProcessedDate(timestamp: Long): String =
    if (timestamp <= 0L) {
        "дата неизвестна"
    } else {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
    }
