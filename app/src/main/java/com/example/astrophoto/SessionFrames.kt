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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SessionFrameCategory(val title: String) {
    LIGHTS_JPEG("Lights JPEG"),
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
    val filePath: String?
) {
    val isJpeg: Boolean
        get() = category == SessionFrameCategory.LIGHTS_JPEG ||
            category == SessionFrameCategory.DARKS_JPEG
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
        val collection = MediaStore.Files.getContentUri("external")
        val basePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        val frames = mutableListOf<SessionFrame>()

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
            "${MediaStore.Files.FileColumns.DATE_ADDED} ASC"
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
                val path = cursor.getString(pathIndex).orEmpty()
                val category = frameCategory(path) ?: continue
                val name = cursor.getString(nameIndex).orEmpty()
                if (name.isBlank()) continue
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
            normalized.contains("Lights/JPEG") -> SessionFrameCategory.LIGHTS_JPEG
            normalized.contains("Lights/RAW") -> SessionFrameCategory.LIGHTS_RAW
            normalized.contains("Darks/JPEG") -> SessionFrameCategory.DARKS_JPEG
            normalized.contains("Darks/RAW") -> SessionFrameCategory.DARKS_RAW
            else -> null
        }
    }

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
    val marksStore = remember { FrameMarksStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var frames by remember { mutableStateOf<List<SessionFrame>>(emptyList()) }
    var marks by remember { mutableStateOf(FrameMarks()) }
    var loading by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf(SessionFrameCategory.LIGHTS_JPEG) }
    var selectedFrame by remember { mutableStateOf<SessionFrame?>(null) }
    var showingAutoSelection by remember { mutableStateOf(false) }
    var refreshKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(session.folderName, refreshKey) {
        loading = true
        frames = repository.loadFrames(session)
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

    val visibleFrames = frames.filter { it.category == selectedCategory }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("← Назад")
        }
        Text(
            text = "Кадры сессии",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Button(
            onClick = { showingAutoSelection = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Автоотбор JPEG")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SessionFrameCategory.entries.forEach { category ->
                val count = frames.count { it.category == category }
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text("${category.title} ($count)") }
                )
            }
        }

        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 30.dp)
                )
            }
            visibleFrames.isEmpty() -> {
                Text(
                    text = "В этой категории файлов нет",
                    modifier = Modifier.padding(top = 24.dp),
                    color = Color(0xFFB8BECC)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleFrames, key = { it.key }) { frame ->
                        SessionFrameCard(
                            frame = frame,
                            marks = marks,
                            repository = repository,
                            onClick = { selectedFrame = frame },
                            onToggleBad = { updateMarks(marks.toggleBad(frame.key)) },
                            onToggleFavorite = {
                                updateMarks(marks.toggleFavorite(frame.key))
                            }
                        )
                    }
                }
            }
        }
    }

    selectedFrame?.let { frame ->
        val categoryFrames = frames.filter { it.category == frame.category }
        val index = categoryFrames.indexOfFirst { it.key == frame.key }
        FrameViewerDialog(
            frame = frame,
            marks = marks,
            repository = repository,
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
            onToggleBad = { updateMarks(marks.toggleBad(frame.key)) },
            onToggleFavorite = { updateMarks(marks.toggleFavorite(frame.key)) },
            onDismiss = { selectedFrame = null }
        )
    }
}

@Composable
private fun SessionFrameCard(
    frame: SessionFrame,
    marks: FrameMarks,
    repository: SessionFramesRepository,
    onClick: () -> Unit,
    onToggleBad: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var thumbnail by remember(frame.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(frame.key) {
        if (frame.isJpeg) thumbnail = repository.loadPreview(frame, 320)
    }
    val status = frameStatus(frame, marks)

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
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
                Text(frame.fileName, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatFrameSize(frame.sizeBytes)} • ${
                        formatFrameDate(frame.createdAtMillis)
                    }",
                    color = Color(0xFFB8BECC),
                    style = MaterialTheme.typography.bodySmall
                )
                if (!frame.isJpeg) {
                    Text(
                        "RAW/DNG preview может не поддерживаться Android",
                        modifier = Modifier.padding(top = 4.dp),
                        color = Color(0xFFFFCC80),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = status.first,
                    modifier = Modifier.padding(top = 4.dp),
                    color = status.second
                )
                Row {
                    TextButton(onClick = onToggleBad) {
                        Text("Брак")
                    }
                    TextButton(onClick = onToggleFavorite) {
                        Text("Избранное")
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameViewerDialog(
    frame: SessionFrame,
    marks: FrameMarks,
    repository: SessionFramesRepository,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleBad: () -> Unit,
    onToggleFavorite: () -> Unit,
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
                .background(Color(0xFF080B12))
                .safeDrawingPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss) {
                    Text("← Назад")
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
                            color = Color(0xFFB8BECC)
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
                    Text(if (frame.key in marks.bad) "Снять брак" else "Брак")
                }
                Button(
                    onClick = onToggleFavorite,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (frame.key in marks.favorite) {
                            "Снять избранное"
                        } else {
                            "Избранное"
                        }
                    )
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
    when (frame.key) {
        in marks.autoBad -> "Брак (авто)" to Color(0xFFEF9A9A)
        in marks.bad -> "Брак" to Color(0xFFEF9A9A)
        in marks.favorite -> "Избранный" to Color(0xFFFFD54F)
        else -> "Нормальный" to Color(0xFF81C784)
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
