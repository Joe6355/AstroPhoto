package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.astrophoto.ui.AstroEmptyState
import com.example.astrophoto.ui.AstroLoadingState
import com.example.astrophoto.ui.AstroScaffold
import com.example.astrophoto.ui.AstroSecondaryButton
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.AstroTabRow
import com.example.astrophoto.ui.AstroTestTags
import com.example.astrophoto.ui.theme.AstroColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SessionFrameCategory(val title: String) {
    LIGHTS_JPEG("Lights JPEG"),
    CROPPED_JPEG("Cropped"),
    LIGHTS_RAW("Lights RAW"),
    DARKS_JPEG("Darks JPEG"),
    DARKS_RAW("Darks RAW")
}

data class SessionFrame(
    val key: String,
    val fileName: String,
    val category: SessionFrameCategory,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val displayPath: String,
    val contentUri: String?,
    val filePath: String?,
    val originalKey: String? = null
) {
    val isJpeg: Boolean
        get() = category == SessionFrameCategory.LIGHTS_JPEG ||
            category == SessionFrameCategory.DARKS_JPEG ||
            category == SessionFrameCategory.CROPPED_JPEG

    val markKey: String
        get() = originalKey ?: key
}

data class FrameMarks(
    val bad: Set<String> = emptySet(),
    val favorite: Set<String> = emptySet(),
    val autoBad: Set<String> = emptySet()
) {
    fun toggleBad(key: String): FrameMarks =
        if (key in bad) {
            copy(bad = bad - key, autoBad = autoBad - key)
        } else {
            copy(
                bad = bad + key,
                favorite = favorite - key,
                autoBad = autoBad - key
            )
        }

    fun toggleFavorite(key: String): FrameMarks =
        if (key in favorite) {
            copy(favorite = favorite - key)
        } else {
            copy(
                favorite = favorite + key,
                bad = bad - key,
                autoBad = autoBad - key
            )
        }
}

class SessionFramesRepository(private val context: Context) {
    suspend fun loadFrames(session: SessionSummary): List<SessionFrame> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    loadMediaStoreFrames(session)
                } else {
                    loadFileFrames(session)
                }
            }.getOrDefault(emptyList())
        }

    suspend fun loadPreview(frame: SessionFrame, maxSize: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (!frame.isJpeg) return@withContext null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                frame.contentUri != null
            ) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        Uri.parse(frame.contentUri),
                        Size(maxSize, maxSize),
                        null
                    )
                }.getOrNull()
            } else {
                frame.filePath?.let { decodeSampledBitmap(it, maxSize) }
            }
        }

    private fun loadMediaStoreFrames(session: SessionSummary): List<SessionFrame> {
        val resolver = context.contentResolver
        val collection = processedImagesCollection()
        val basePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        val frames = mutableListOf<SessionFrame>()

        resolver.query(
            collection,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED
            ),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$basePath%"),
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val pathIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.DATE_ADDED
            )

            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIndex).orEmpty()
                val category = frameCategory(path) ?: continue
                val name = cursor.getString(nameIndex).orEmpty()
                if (name.isBlank()) continue
                if (category == SessionFrameCategory.CROPPED_JPEG && !name.isJpegFileName()) continue
                if (category.isRaw && !name.isDngFileName()) continue
                val relative = "${path.removePrefix(basePath)}$name"
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                frames += SessionFrame(
                    key = relative,
                    fileName = name,
                    category = category,
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    createdAtMillis = cursor.getLong(dateIndex).coerceAtLeast(0L) * 1_000L,
                    displayPath = "Pictures/AstroPhoto/${session.folderName}/$relative",
                    contentUri = uri.toString(),
                    filePath = null
                )
            }
        }
        return frames
    }

    @Suppress("DEPRECATION")
    private fun loadFileFrames(session: SessionSummary): List<SessionFrame> {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val root = File(pictures, "AstroPhoto/${session.folderName}")
        if (!root.exists()) return emptyList()

        return root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).path.replace('\\', '/')
                val category = frameCategory(relative) ?: return@mapNotNull null
                if (category == SessionFrameCategory.CROPPED_JPEG && !file.name.isJpegFileName()) {
                    return@mapNotNull null
                }
                if (category.isRaw && !file.name.isDngFileName()) return@mapNotNull null
                SessionFrame(
                    key = relative,
                    fileName = file.name,
                    category = category,
                    sizeBytes = file.length(),
                    createdAtMillis = file.lastModified(),
                    displayPath = file.absolutePath,
                    contentUri = null,
                    filePath = file.absolutePath
                )
            }
            .toList()
    }

    private fun frameCategory(path: String): SessionFrameCategory? {
        val normalized = path.replace('\\', '/')
        return when {
            normalized.contains("Lights/Cropped") -> SessionFrameCategory.CROPPED_JPEG
            normalized.contains("Lights/JPEG") -> SessionFrameCategory.LIGHTS_JPEG
            normalized.contains("Lights/RAW") -> SessionFrameCategory.LIGHTS_RAW
            normalized.contains("Darks/JPEG") -> SessionFrameCategory.DARKS_JPEG
            normalized.contains("Darks/RAW") -> SessionFrameCategory.DARKS_RAW
            else -> null
        }
    }

    private fun String.isJpegFileName(): Boolean {
        val extension = substringAfterLast('.', "").lowercase(Locale.US)
        return extension == "jpg" || extension == "jpeg"
    }

    private fun String.isDngFileName(): Boolean =
        substringAfterLast('.', "").equals("dng", ignoreCase = true)

    private val SessionFrameCategory.isRaw: Boolean
        get() = this == SessionFrameCategory.LIGHTS_RAW || this == SessionFrameCategory.DARKS_RAW

    private fun decodeSampledBitmap(path: String, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxSize ||
            bounds.outHeight / sampleSize > maxSize
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }
}

class FrameMarksStore(private val context: Context) {
    suspend fun loadOrCreate(session: SessionSummary): FrameMarks =
        withContext(Dispatchers.IO) {
            runCatching {
                val content = readContent(session)
                if (content == null) {
                    val empty = FrameMarks()
                    saveInternal(session, empty)
                    empty
                } else {
                    parse(content)
                }
            }.getOrDefault(FrameMarks())
        }

    suspend fun save(session: SessionSummary, marks: FrameMarks): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { saveInternal(session, marks) }
        }

    private fun parse(content: String): FrameMarks = runCatching {
        val json = JSONObject(content)
        val bad = json.optJSONArray("bad").toStringSet()
        FrameMarks(
            bad = bad,
            favorite = json.optJSONArray("favorite").toStringSet() - bad,
            autoBad = json.optJSONArray("autoBad").toStringSet().intersect(bad)
        )
    }.getOrDefault(FrameMarks())

    private fun encode(marks: FrameMarks): String = JSONObject().apply {
        put("bad", JSONArray(marks.bad.sorted()))
        put("favorite", JSONArray((marks.favorite - marks.bad).sorted()))
        put("autoBad", JSONArray(marks.autoBad.intersect(marks.bad).sorted()))
    }.toString(2)

    private fun readContent(session: SessionSummary): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findMediaStoreUri(session) ?: return null
            return context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }
        return legacyFile(session).takeIf { it.exists() }?.readText()
    }

    private fun saveInternal(session: SessionSummary, marks: FrameMarks) {
        val content = encode(marks)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri("external")
            val relativePath =
                "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
            var inserted = false
            val uri = findMediaStoreUri(session) ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, "frame_marks.json")
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
            )?.also { inserted = true } ?: error("Не удалось создать frame_marks.json")

            try {
                resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                    it.write(content)
                } ?: error("Не удалось записать frame_marks.json")
                if (inserted) {
                    resolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                        },
                        null,
                        null
                    )
                }
            } catch (exception: Exception) {
                if (inserted) resolver.delete(uri, null, null)
                throw exception
            }
        } else {
            val file = legacyFile(session)
            file.parentFile?.mkdirs()
            FileOutputStream(file).bufferedWriter().use { it.write(content) }
        }
    }

    private fun findMediaStoreUri(session: SessionSummary): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val relativePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        return resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf("frame_marks.json", relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(session: SessionSummary): File {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        return File(
            pictures,
            "AstroPhoto/${session.folderName}/frame_marks.json"
        )
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}

@Composable
fun SessionFramesScreen(
    session: SessionSummary,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SessionFramesRepository(context.applicationContext) }
    val cropsRepository = remember { CroppedFramesRepository(context.applicationContext) }
    val marksStore = remember { FrameMarksStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var frames by remember { mutableStateOf<List<SessionFrame>>(emptyList()) }
    var marks by remember { mutableStateOf(FrameMarks()) }
    var loading by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf(SessionFrameCategory.LIGHTS_JPEG) }
    var selectedFrame by remember { mutableStateOf<SessionFrame?>(null) }
    var cropManifest by remember { mutableStateOf(CropManifest()) }
    var cropOriginal by remember { mutableStateOf<SessionFrame?>(null) }
    var cropMessage by remember { mutableStateOf<String?>(null) }
    var showingAutoSelection by remember { mutableStateOf(false) }
    var refreshKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(session.folderName, refreshKey) {
        loading = true
        frames = repository.loadFrames(session)
        cropManifest = cropsRepository.loadManifest(session)
        marks = marksStore.loadOrCreate(session)
        val existingKeys = frames.mapTo(mutableSetOf()) { it.key }
        marks = FrameMarks(
            bad = marks.bad.intersect(existingKeys),
            favorite = marks.favorite.intersect(existingKeys),
            autoBad = marks.autoBad.intersect(existingKeys)
        )
        if (frames.none { it.category == selectedCategory }) {
            frames.firstOrNull()?.let { selectedCategory = it.category }
        }
        loading = false
    }

    if (showingAutoSelection) {
        JpegAutoSelectionScreen(
            session = session,
            onBack = { showingAutoSelection = false },
            onApplied = {
                showingAutoSelection = false
                refreshKey++
            }
        )
        return
    }

    fun updateMarks(updated: FrameMarks) {
        marks = updated
        coroutineScope.launch {
            marksStore.save(session, updated)
        }
    }

    val cropRecords = cropsRepository.records(cropManifest, frames)
    val originalLights = frames.filter { it.category == SessionFrameCategory.LIGHTS_JPEG }
    val originalsByKey = originalLights.associateBy { it.key }
    val visibleFrames = if (selectedCategory == SessionFrameCategory.CROPPED_JPEG) {
        cropRecords.map { it.frame }
    } else {
        frames.filter { it.category == selectedCategory }
    }

    fun categoryCount(category: SessionFrameCategory): Int =
        if (category == SessionFrameCategory.CROPPED_JPEG) {
            cropRecords.size
        } else {
            frames.count { it.category == category }
        }
    val availableCategories = SessionFrameCategory.entries.filter {
        categoryCount(it) > 0
    }

    AstroScaffold(title = "Кадры сессии", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AstroSpacing.Lg)
        ) {
        AstroSecondaryButton(
            text = "Автоотбор JPEG",
            onClick = { showingAutoSelection = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AstroSpacing.Sm)
        )
        if (availableCategories.isNotEmpty()) {
            AstroTabRow(
                tabs = availableCategories,
                selected = selectedCategory,
                label = { "${it.title} (${categoryCount(it)})" },
                onSelected = { selectedCategory = it }
            )
        }
        cropMessage?.let {
            Text(
                it,
                modifier = Modifier.padding(top = AstroSpacing.Sm),
                color = AstroColors.Warning,
                style = MaterialTheme.typography.bodySmall
            )
        }

        when {
            loading -> {
                AstroLoadingState(
                    message = "Загружаем кадры…",
                    modifier = Modifier.weight(1f)
                )
            }
            visibleFrames.isEmpty() -> {
                AstroEmptyState(
                    title = "Кадров нет",
                    message = "В выбранной категории пока нет файлов",
                    modifier = Modifier.weight(1f)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleFrames, key = { it.key }) { frame ->
                        val cropEntry = if (frame.category == SessionFrameCategory.CROPPED_JPEG) {
                            cropManifest.entries.firstOrNull { it.croppedFileName == frame.fileName }
                        } else {
                            cropManifest.find(frame.key)
                        }
                        val original = cropEntry?.let { originalsByKey[it.originalKey] }
                            ?: frame.takeIf { it.category == SessionFrameCategory.LIGHTS_JPEG }
                        SessionFrameCard(
                            frame = frame,
                            marks = marks,
                            repository = repository,
                            cropEntry = cropEntry,
                            onClick = { selectedFrame = frame },
                            onToggleBad = { updateMarks(marks.toggleBad(frame.markKey)) },
                            onToggleFavorite = {
                                updateMarks(marks.toggleFavorite(frame.markKey))
                            },
                            onCrop = { original?.let { cropOriginal = it } },
                            onDeleteCrop = {
                                cropEntry?.let { entry ->
                                    coroutineScope.launch {
                                        cropsRepository.deleteCrop(session, entry)
                                            .onSuccess {
                                                cropMessage = "Обрезка удалена; оригинал сохранён"
                                                refreshKey++
                                            }
                                            .onFailure { cropMessage = it.message }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        }
    }

    selectedFrame?.let { frame ->
        val categoryFrames = frames.filter { it.category == frame.category }
        val index = categoryFrames.indexOfFirst { it.key == frame.key }
        val cropEntry = if (frame.category == SessionFrameCategory.CROPPED_JPEG) {
            cropManifest.entries.firstOrNull { it.croppedFileName == frame.fileName }
        } else {
            cropManifest.find(frame.key)
        }
        val cropSource = cropEntry?.let { originalsByKey[it.originalKey] }
            ?: frame.takeIf { it.category == SessionFrameCategory.LIGHTS_JPEG }
        FrameViewerDialog(
            frame = frame,
            marks = marks,
            repository = repository,
            cropEntry = cropEntry,
            hasPrevious = index > 0,
            hasNext = index >= 0 && index < categoryFrames.lastIndex,
            onPrevious = {
                if (index > 0) selectedFrame = categoryFrames[index - 1]
            },
            onNext = {
                if (index >= 0 && index < categoryFrames.lastIndex) {
                    selectedFrame = categoryFrames[index + 1]
                }
            },
            onToggleBad = { updateMarks(marks.toggleBad(frame.markKey)) },
            onToggleFavorite = { updateMarks(marks.toggleFavorite(frame.markKey)) },
            onCrop = {
                selectedFrame = null
                cropSource?.let { cropOriginal = it }
            },
            onDeleteCrop = {
                cropEntry?.let { entry ->
                    coroutineScope.launch {
                        cropsRepository.deleteCrop(session, entry)
                            .onSuccess {
                                cropMessage = "Обрезка удалена; оригинал сохранён"
                                selectedFrame = null
                                refreshKey++
                            }
                            .onFailure { cropMessage = it.message ?: "Не удалось удалить обрезку" }
                    }
                }
            },
            onDismiss = { selectedFrame = null }
        )
    }

    cropOriginal?.let { original ->
        CropEditorDialog(
            session = session,
            original = original,
            originals = originalLights,
            repository = cropsRepository,
            initialRect = cropManifest.find(original.key)?.normalizedRect ?: NormalizedCropRect.Full,
            onSaved = { message ->
                cropMessage = message
                cropOriginal = null
                refreshKey++
            },
            onDismiss = { cropOriginal = null }
        )
    }
}

@Composable
private fun SessionFrameCard(
    frame: SessionFrame,
    marks: FrameMarks,
    repository: SessionFramesRepository,
    cropEntry: CropManifestEntry?,
    onClick: () -> Unit,
    onToggleBad: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCrop: () -> Unit,
    onDeleteCrop: () -> Unit
) {
    var thumbnail by remember(frame.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(frame.key) {
        if (frame.isJpeg) thumbnail = repository.loadPreview(frame, 320)
    }
    val status = frameStatus(frame, marks)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            if (frame.isJpeg) {
                Box(
                    modifier = Modifier
                        .width(88.dp)
                        .height(88.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    thumbnail?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = frame.fileName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } ?: CircularProgressIndicator()
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (frame.isJpeg) 12.dp else 0.dp)
            ) {
                Text(
                    text = frame.fileName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFrameSize(frame.sizeBytes)} • ${
                        formatFrameDate(frame.createdAtMillis)
                    }",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                if (!frame.isJpeg) {
                    Text(
                        "RAW/DNG preview может не поддерживаться Android",
                        modifier = Modifier.padding(top = 4.dp),
                        color = AstroColors.Warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = status.first,
                    modifier = Modifier.padding(top = 4.dp),
                    color = status.second
                )
            }
        }
        val canCrop = frame.category == SessionFrameCategory.LIGHTS_JPEG ||
            frame.category == SessionFrameCategory.CROPPED_JPEG
        FrameActionRow(
            bad = frame.markKey in marks.bad,
            favorite = frame.markKey in marks.favorite,
            canCrop = canCrop,
            cropExists = cropEntry != null,
            onToggleBad = onToggleBad,
            onToggleFavorite = onToggleFavorite,
            onCrop = onCrop,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (cropEntry != null) {
            TextButton(
                onClick = onDeleteCrop,
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                Text("Удалить обрезку", maxLines = 1, softWrap = false)
            }
        }
        }
    }
}

@Composable
fun FrameActionRow(
    bad: Boolean,
    favorite: Boolean,
    canCrop: Boolean,
    cropExists: Boolean,
    onToggleBad: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCrop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AstroTestTags.FrameActionRow),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        TextButton(
            onClick = onToggleBad,
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier
                .weight(1f)
                .testTag(AstroTestTags.FrameBadAction)
                .semantics { selected = bad }
        ) {
            Text(
                if (bad) "Брак ✓" else "Брак",
                maxLines = 1,
                softWrap = false,
                color = if (bad) AstroColors.Error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelSmall
            )
        }
        TextButton(
            onClick = onToggleFavorite,
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier
                .weight(1.45f)
                .testTag(AstroTestTags.FrameFavoriteAction)
                .semantics { selected = favorite }
        ) {
            Text(
                if (favorite) "Избранное ✓" else "Избранное",
                maxLines = 1,
                softWrap = false,
                color = if (favorite) AstroColors.Warning else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (canCrop) {
            TextButton(
                onClick = onCrop,
                contentPadding = PaddingValues(horizontal = 2.dp),
                modifier = Modifier
                    .weight(1.35f)
                    .testTag(AstroTestTags.FrameCropAction)
            ) {
                Text(
                    if (cropExists) "Заново" else "Обрезать",
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun FrameViewerDialog(
    frame: SessionFrame,
    marks: FrameMarks,
    repository: SessionFramesRepository,
    cropEntry: CropManifestEntry?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleBad: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCrop: () -> Unit,
    onDeleteCrop: () -> Unit,
    onDismiss: () -> Unit
) {
    var preview by remember(frame.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(frame.key) {
        preview = if (frame.isJpeg) repository.loadPreview(frame, 1800) else null
    }
    val status = frameStatus(frame, marks)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AstroColors.Background)
                .safeDrawingPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Назад")
                }
                Text(
                    text = status.first,
                    color = status.second,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            Text(
                text = frame.fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (frame.isJpeg) {
                    preview?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = frame.fileName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } ?: CircularProgressIndicator()
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text("RAW/DNG preview может не поддерживаться Android")
                        Text(
                            frame.displayPath,
                            modifier = Modifier.padding(top = 12.dp),
                            color = AstroColors.TextSecondary
                        )
                        Text(
                            formatFrameSize(frame.sizeBytes),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleBad,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (frame.markKey in marks.bad) "Снять брак" else "Брак")
                }
                Button(
                    onClick = onToggleFavorite,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (frame.markKey in marks.favorite) {
                            "Снять избранное"
                        } else {
                            "Избранное"
                        }
                    )
                }
            }
            if (frame.category == SessionFrameCategory.LIGHTS_JPEG ||
                frame.category == SessionFrameCategory.CROPPED_JPEG
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onCrop, modifier = Modifier.weight(1f)) {
                        Text(if (cropEntry == null) "Обрезать" else "Обрезать заново")
                    }
                    if (cropEntry != null) {
                        Button(onClick = onDeleteCrop, modifier = Modifier.weight(1f)) {
                            Text("Удалить обрезку")
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onPrevious, enabled = hasPrevious) {
                    Text("← Предыдущий")
                }
                TextButton(onClick = onNext, enabled = hasNext) {
                    Text("Следующий →")
                }
            }
        }
    }
}

private fun frameStatus(frame: SessionFrame, marks: FrameMarks): Pair<String, Color> =
    when (frame.markKey) {
        in marks.autoBad -> "Брак (авто)" to AstroColors.Error
        in marks.bad -> "Брак" to AstroColors.Error
        in marks.favorite -> "Избранный" to AstroColors.Warning
        else -> "Нормальный" to AstroColors.Success
    }

private fun formatFrameSize(bytes: Long): String = when {
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

private fun formatFrameDate(timestamp: Long): String =
    if (timestamp <= 0L) {
        "дата неизвестна"
    } else {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
    }
