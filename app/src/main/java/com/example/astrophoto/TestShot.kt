package com.example.astrophoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TestShotStatus(val title: String) {
    TOO_DARK("Слишком тёмный"),
    NORMAL("Нормально"),
    TOO_BRIGHT("Слишком яркий"),
    OVEREXPOSED("Пересвет"),
    BLURRY("Смазано/не в фокусе"),
    UNKNOWN("Не удалось оценить")
}

data class TestShotResult(
    val exposure: ExposureAnalysis,
    val sharpness: Float,
    val status: TestShotStatus,
    val analyzedAtMillis: Long,
    val savedFileName: String? = null
) {
    val isGood: Boolean get() = status == TestShotStatus.NORMAL
}

class TestShotProcessor(private val context: Context) {
    suspend fun analyze(jpegBytes: ByteArray): Result<TestShotResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = decodeSampled(jpegBytes, 960)
                    ?: error("Не удалось оценить пробный кадр")
                try {
                    val exposure = ExposureMetricsCalculator.calculate(bitmap)
                    val sharpness = calculateSharpness(bitmap)
                    val status = when (exposure.status) {
                        ExposureStatus.OVEREXPOSED -> TestShotStatus.OVEREXPOSED
                        ExposureStatus.TOO_DARK -> TestShotStatus.TOO_DARK
                        ExposureStatus.TOO_BRIGHT -> TestShotStatus.TOO_BRIGHT
                        ExposureStatus.UNKNOWN -> TestShotStatus.UNKNOWN
                        ExposureStatus.OK -> if (sharpness < BLUR_THRESHOLD) {
                            TestShotStatus.BLURRY
                        } else {
                            TestShotStatus.NORMAL
                        }
                    }
                    TestShotResult(
                        exposure = exposure,
                        sharpness = sharpness,
                        status = status,
                        analyzedAtMillis = System.currentTimeMillis()
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }

    suspend fun save(
        jpegBytes: ByteArray,
        session: ShootingSession,
        analyzedAtMillis: Long
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date(analyzedAtMillis))
            val fileName = "TestShot_$timestamp.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(jpegBytes, session, fileName)
            } else {
                saveLegacy(jpegBytes, session, fileName)
            }
            fileName
        }
    }

    private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun calculateSharpness(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return 0f
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val grayscale = IntArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            grayscale[index] = (red * 77 + green * 150 + blue * 29) ushr 8
        }

        var sum = 0.0
        var squareSum = 0.0
        var count = 0
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
                sum += laplacian
                squareSum += laplacian.toDouble() * laplacian
                count++
                x += 2
            }
            y += 2
        }
        if (count == 0) return 0f
        val mean = sum / count
        return (squareSum / count - mean * mean)
            .coerceAtLeast(0.0)
            .toFloat()
    }

    private fun saveWithMediaStore(
        bytes: ByteArray,
        session: ShootingSession,
        fileName: String
    ) {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/AstroPhoto/" +
                        "${session.folderName}/Tests/JPEG/"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        ) ?: error("Не удалось сохранить пробный кадр")

        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: error("Не удалось сохранить пробный кадр")
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                },
                null,
                null
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        bytes: ByteArray,
        session: ShootingSession,
        fileName: String
    ) {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val directory = File(
            pictures,
            "AstroPhoto/${session.folderName}/Tests/JPEG"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("Не удалось создать папку Tests/JPEG")
        }
        val file = File(directory, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
    }

    companion object {
        private const val BLUR_THRESHOLD = 40f
    }
}

@Composable
fun TestShotResultCard(
    result: TestShotResult?,
    statusMessage: String?,
    running: Boolean,
    onDarker: () -> Unit,
    onBrighter: () -> Unit,
    onInfinityFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Результат пробного кадра",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (running) {
                Text(
                    text = statusMessage ?: "Пробный кадр снимается...",
                    modifier = Modifier.padding(top = 6.dp),
                    color = Color(0xFFB7C9FF)
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            } else if (result == null) {
                Text(
                    text = statusMessage ?: "Пробный кадр не сделан",
                    modifier = Modifier.padding(top = 6.dp),
                    color = Color(0xFFB8BECC)
                )
            } else {
                Text(
                    text = "${result.status.title}, ${
                        SimpleDateFormat(
                            "HH:mm",
                            Locale.getDefault()
                        ).format(Date(result.analyzedAtMillis))
                    }",
                    modifier = Modifier.padding(top = 6.dp),
                    color = result.status.color(),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        "Яркость: %.1f • Пересвет: %.2f%% • Резкость: %.1f",
                        result.exposure.averageBrightness,
                        result.exposure.highlightPercent,
                        result.sharpness
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD5DBE8)
                )
                TestShotHistogram(
                    bins = result.exposure.histogram,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(top = 6.dp)
                )
                result.status.recommendations().forEach {
                    Text(
                        text = "• $it",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                result.savedFileName?.let {
                    Text(
                        text = "Сохранён: $it",
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA5D6A7)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onDarker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Темнее")
                    }
                    Button(
                        onClick = onBrighter,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Светлее")
                    }
                }
                Button(
                    onClick = onInfinityFocus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Text("Фокус ∞")
                }
            }
        }
    }
}

@Composable
private fun TestShotHistogram(
    bins: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (bins.isEmpty()) return@Canvas
        val barWidth = size.width / bins.size
        bins.forEachIndexed { index, value ->
            val barHeight = size.height * value.coerceIn(0f, 1f)
            drawLine(
                color = Color(0xFFB39DDB),
                start = Offset(index * barWidth + barWidth / 2f, size.height),
                end = Offset(
                    index * barWidth + barWidth / 2f,
                    size.height - barHeight
                ),
                strokeWidth = (barWidth * 0.7f).coerceAtLeast(1f)
            )
        }
    }
}

fun TestShotStatus.recommendations(): List<String> = when (this) {
    TestShotStatus.TOO_DARK -> listOf(
        "Увеличьте ISO",
        "Увеличьте выдержку",
        "Используйте Astro Mode"
    )
    TestShotStatus.OVEREXPOSED,
    TestShotStatus.TOO_BRIGHT -> listOf(
        "Уменьшите ISO",
        "Уменьшите выдержку",
        "Попробуйте пресет «Если пересвечивает»"
    )
    TestShotStatus.BLURRY -> listOf(
        "Поставьте телефон на штатив",
        "Используйте таймер",
        "Установите фокус ∞ для звёзд"
    )
    TestShotStatus.NORMAL -> listOf(
        "Настройки подходят. Можно запускать серию."
    )
    TestShotStatus.UNKNOWN -> listOf(
        "Повторите пробный кадр."
    )
}

fun TestShotStatus.color(): Color = when (this) {
    TestShotStatus.NORMAL -> Color(0xFF81C784)
    TestShotStatus.TOO_DARK -> Color(0xFF90CAF9)
    TestShotStatus.TOO_BRIGHT -> Color(0xFFFFCC80)
    TestShotStatus.OVEREXPOSED -> Color(0xFFFF8A80)
    TestShotStatus.BLURRY -> Color(0xFFFFCC80)
    TestShotStatus.UNKNOWN -> Color(0xFFB8BECC)
}
