package com.example.astrophoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import com.example.astrophoto.ui.AstroPrimaryButton
import com.example.astrophoto.ui.AstroScaffold
import com.example.astrophoto.ui.AstroSegmentedControl
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.theme.AstroColors
import kotlin.math.max

enum class AutoSelectionSensitivity(val title: String) {
    SOFT("Мягко"),
    NORMAL("Нормально"),
    STRICT("Строго")
}

private data class AutoSelectionThresholds(
    val blackBrightness: Double,
    val darkBrightness: Double,
    val brightBrightness: Double,
    val clippedPercent: Double,
    val blurFactor: Double
)

private enum class AutoFrameStatus(
    val title: String,
    val color: Color,
    val shouldMarkBad: Boolean,
    val mayOverrideFavorite: Boolean
) {
    OK("OK", AstroColors.Success, false, false),
    TOO_DARK("Слишком тёмный", AstroColors.Warning, true, false),
    OVEREXPOSED("Пересвет", AstroColors.Error, true, false),
    BLURRY("Смазан", AstroColors.Warning, true, false),
    BLACK("Чёрный кадр", AstroColors.Error, true, true),
    READ_ERROR(
        "Не удалось прочитать файл",
        AstroColors.Error,
        true,
        true
    )
}

private data class RawFrameMetrics(
    val frame: SessionFrame,
    val brightness: Double,
    val clippedPercent: Double,
    val sharpness: Double,
    val readError: Boolean
)

private data class AutoFrameAnalysis(
    val frame: SessionFrame,
    val brightness: Double,
    val clippedPercent: Double,
    val sharpness: Double,
    val status: AutoFrameStatus
)

private data class AutoSelectionReport(
    val frames: List<AutoFrameAnalysis>,
    val sharpnessComparisonAvailable: Boolean
)

private class JpegAutoSelector(private val context: Context) {
    suspend fun analyze(
        frames: List<SessionFrame>,
        sensitivity: AutoSelectionSensitivity,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): AutoSelectionReport = withContext(Dispatchers.IO) {
        require(frames.isNotEmpty()) { "JPEG кадры не найдены" }
        val rawMetrics = mutableListOf<RawFrameMetrics>()

        frames.forEachIndexed { index, frame ->
            val metrics = runCatching { calculateMetrics(frame) }
                .getOrElse {
                    RawFrameMetrics(
                        frame = frame,
                        brightness = 0.0,
                        clippedPercent = 0.0,
                        sharpness = 0.0,
                        readError = true
                    )
                }
            rawMetrics += metrics
            withContext(Dispatchers.Main.immediate) {
                onProgress(index + 1, frames.size)
            }
        }

        val thresholds = sensitivity.thresholds()
        val sharpnessCandidates = rawMetrics.filter {
            !it.readError && it.brightness > thresholds.blackBrightness
        }
        val sharpnessComparisonAvailable = sharpnessCandidates.size >= 2
        val averageSharpness = sharpnessCandidates
            .map { it.sharpness }
            .average()
            .takeIf { !it.isNaN() }
            ?: 0.0

        AutoSelectionReport(
            frames = rawMetrics.map { metrics ->
                AutoFrameAnalysis(
                    frame = metrics.frame,
                    brightness = metrics.brightness,
                    clippedPercent = metrics.clippedPercent,
                    sharpness = metrics.sharpness,
                    status = classify(
                        metrics = metrics,
                        thresholds = thresholds,
                        averageSharpness = averageSharpness,
                        compareSharpness = sharpnessComparisonAvailable
                    )
                )
            },
            sharpnessComparisonAvailable = sharpnessComparisonAvailable
        )
    }

    private fun calculateMetrics(frame: SessionFrame): RawFrameMetrics {
        val bitmap = decodeSampled(frame, 720)
            ?: error("Не удалось прочитать файл")
        return try {
            val width = bitmap.width
            val height = bitmap.height
            require(width >= 3 && height >= 3) {
                "Не удалось прочитать файл"
            }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val grayscale = IntArray(pixels.size)
            var brightnessSum = 0L
            var clippedPixels = 0L

            pixels.forEachIndexed { index, color ->
                val red = color ushr 16 and 0xFF
                val green = color ushr 8 and 0xFF
                val blue = color and 0xFF
                val luminance = (red * 77 + green * 150 + blue * 29) ushr 8
                grayscale[index] = luminance
                brightnessSum += luminance
                if (red >= 250 && green >= 250 && blue >= 250) {
                    clippedPixels++
                }
            }

            var laplacianSum = 0.0
            var laplacianSquareSum = 0.0
            var laplacianCount = 0
            var y = 1
            while (y < height - 1) {
                var x = 1
                while (x < width - 1) {
                    val index = y * width + x
                    val laplacian =
                        grayscale[index - 1] +
                            grayscale[index + 1] +
                            grayscale[index - width] +
                            grayscale[index + width] -
                            4 * grayscale[index]
                    laplacianSum += laplacian
                    laplacianSquareSum += laplacian.toDouble() * laplacian
                    laplacianCount++
                    x += 2
                }
                y += 2
            }
            val laplacianMean = laplacianSum / max(1, laplacianCount)
            val sharpness = (
                laplacianSquareSum / max(1, laplacianCount) -
                    laplacianMean * laplacianMean
                ).coerceAtLeast(0.0)

            RawFrameMetrics(
                frame = frame,
                brightness = brightnessSum.toDouble() / pixels.size,
                clippedPercent = clippedPixels * 100.0 / pixels.size,
                sharpness = sharpness,
                readError = false
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeSampled(frame: SessionFrame, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openFrame(frame)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }
        return openFrame(frame)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }
    }

    private fun openFrame(frame: SessionFrame): InputStream? =
        if (frame.contentUri != null) {
            context.contentResolver.openInputStream(Uri.parse(frame.contentUri))
        } else {
            frame.filePath?.let { File(it).inputStream() }
        }

    private fun classify(
        metrics: RawFrameMetrics,
        thresholds: AutoSelectionThresholds,
        averageSharpness: Double,
        compareSharpness: Boolean
    ): AutoFrameStatus = when {
        metrics.readError -> AutoFrameStatus.READ_ERROR
        metrics.brightness <= thresholds.blackBrightness -> AutoFrameStatus.BLACK
        metrics.brightness >= thresholds.brightBrightness ||
            metrics.clippedPercent >= thresholds.clippedPercent ->
            AutoFrameStatus.OVEREXPOSED
        metrics.brightness < thresholds.darkBrightness -> AutoFrameStatus.TOO_DARK
        compareSharpness &&
            metrics.sharpness < averageSharpness * thresholds.blurFactor ->
            AutoFrameStatus.BLURRY
        else -> AutoFrameStatus.OK
    }

    private fun AutoSelectionSensitivity.thresholds(): AutoSelectionThresholds =
        when (this) {
            AutoSelectionSensitivity.SOFT -> AutoSelectionThresholds(
                blackBrightness = 3.0,
                darkBrightness = 15.0,
                brightBrightness = 245.0,
                clippedPercent = 20.0,
                blurFactor = 0.35
            )
            AutoSelectionSensitivity.NORMAL -> AutoSelectionThresholds(
                blackBrightness = 5.0,
                darkBrightness = 25.0,
                brightBrightness = 235.0,
                clippedPercent = 10.0,
                blurFactor = 0.55
            )
            AutoSelectionSensitivity.STRICT -> AutoSelectionThresholds(
                blackBrightness = 8.0,
                darkBrightness = 40.0,
                brightBrightness = 220.0,
                clippedPercent = 5.0,
                blurFactor = 0.75
            )
        }
}

@Composable
fun JpegAutoSelectionScreen(
    session: SessionSummary,
    onBack: () -> Unit,
    onApplied: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val framesRepository = remember {
        SessionFramesRepository(context.applicationContext)
    }
    val marksStore = remember { FrameMarksStore(context.applicationContext) }
    val selector = remember { JpegAutoSelector(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var frames by remember { mutableStateOf<List<SessionFrame>>(emptyList()) }
    var marks by remember { mutableStateOf(FrameMarks()) }
    var sensitivity by remember {
        mutableStateOf(AutoSelectionSensitivity.NORMAL)
    }
    var loading by remember { mutableStateOf(true) }
    var analyzing by remember { mutableStateOf(false) }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<AutoSelectionReport?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session.folderName) {
        loading = true
        frames = framesRepository.loadFrames(session)
            .filter { it.category == SessionFrameCategory.LIGHTS_JPEG }
        marks = marksStore.loadOrCreate(session)
        loading = false
        if (frames.isEmpty()) status = "JPEG кадры не найдены"
    }

    val detectedBad = report?.frames
        ?.filter { it.status.shouldMarkBad }
        .orEmpty()
    val protectedFavorites = detectedBad.filter {
        it.frame.key in marks.favorite && !it.status.mayOverrideFavorite
    }
    val markableFrames = detectedBad.filterNot { it in protectedFavorites }

    AstroScaffold(title = "Автоотбор JPEG", onBack = onBack) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AstroSpacing.Lg)
    ) {
        Text(
            text = "Анализируются только Lights/JPEG. Файлы не изменяются.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        AstroSegmentedControl(
            options = AutoSelectionSensitivity.entries,
            selected = sensitivity,
            label = { it.title },
            onSelected = {
                sensitivity = it
                report = null
                status = null
            },
            enabled = !loading && !analyzing,
            modifier = Modifier.padding(top = 10.dp)
        )

        AstroPrimaryButton(
            text = if (analyzing) "Анализ…" else "Анализировать JPEG",
            onClick = {
                if (frames.isEmpty()) {
                    status = "JPEG кадры не найдены"
                } else {
                    analyzing = true
                    report = null
                    progressCurrent = 0
                    progressTotal = frames.size
                    status = "Анализ кадра 0 из ${frames.size}"
                    coroutineScope.launch {
                        val result = runCatching {
                            selector.analyze(frames, sensitivity) { current, total ->
                                progressCurrent = current
                                progressTotal = total
                                status = "Анализ кадра $current из $total"
                            }
                        }
                        analyzing = false
                        result.fold(
                            onSuccess = {
                                report = it
                                status = if (it.sharpnessComparisonAvailable) {
                                    "Анализ завершён"
                                } else {
                                    "Недостаточно кадров для сравнения резкости"
                                }
                            },
                            onFailure = {
                                status = it.message ?: "Не удалось прочитать файл"
                            }
                        )
                    }
                }
            },
            enabled = !loading && !analyzing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )

        if (loading || analyzing) {
            if (analyzing && progressTotal > 0) {
                LinearProgressIndicator(
                    progress = {
                        progressCurrent.toFloat() / progressTotal
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp)
                )
            }
        }
        status?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                color = if (
                    it == "Анализ завершён" ||
                    it.startsWith("Анализ кадра")
                ) {
                    AstroColors.Success
                } else {
                    AstroColors.Warning
                }
            )
        }
        if (protectedFavorites.isNotEmpty()) {
            Text(
                text = "Предупреждение: ${protectedFavorites.size} избранных " +
                    "кадров выглядят сомнительно и не будут автоматически " +
                    "помечены как брак.",
                modifier = Modifier.padding(top = 8.dp),
                color = AstroColors.Warning
            )
        }

        report?.let { analysis ->
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(analysis.frames, key = { it.frame.key }) { result ->
                    AutoAnalysisCard(
                        result = result,
                        favorite = result.frame.key in marks.favorite
                    )
                }
            }

            Button(
                onClick = {
                    coroutineScope.launch {
                        val keysToMark = markableFrames
                            .mapTo(mutableSetOf()) { it.frame.key }
                        val forcedFavoriteKeys = markableFrames
                            .filter {
                                it.status.mayOverrideFavorite &&
                                    it.frame.key in marks.favorite
                            }
                            .mapTo(mutableSetOf()) { it.frame.key }
                        val newAutoKeys = keysToMark - marks.bad
                        val updated = marks.copy(
                            bad = marks.bad + keysToMark,
                            favorite = marks.favorite - forcedFavoriteKeys,
                            autoBad = marks.autoBad + newAutoKeys
                        )
                        marksStore.save(session, updated).fold(
                            onSuccess = { onApplied() },
                            onFailure = {
                                status = "Не удалось сохранить frame_marks.json"
                            }
                        )
                    }
                },
                enabled = !analyzing && markableFrames.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Пометить найденный брак (${markableFrames.size})")
            }
            TextButton(
                onClick = onBack,
                enabled = !analyzing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Отмена")
            }
        }
    }
    }
}

@Composable
private fun AutoAnalysisCard(
    result: AutoFrameAnalysis,
    favorite: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(result.frame.fileName, fontWeight = FontWeight.SemiBold)
            Text(
                text = String.format(
                    Locale.getDefault(),
                    "Яркость: %.1f • Пересвет: %.2f%% • Резкость: %.1f",
                    result.brightness,
                    result.clippedPercent,
                    result.sharpness
                ),
                color = AstroColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = result.status.title,
                color = result.status.color,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (
                favorite &&
                result.status.shouldMarkBad &&
                !result.status.mayOverrideFavorite
            ) {
                Text(
                    text = "Избранный кадр: требуется ручная проверка",
                color = AstroColors.Warning,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
