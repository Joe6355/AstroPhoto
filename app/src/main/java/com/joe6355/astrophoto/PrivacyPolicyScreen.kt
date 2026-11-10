package com.joe6355.astrophoto

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.joe6355.astrophoto.ui.AstroScaffold
import com.joe6355.astrophoto.ui.AstroSecondaryButton
import com.joe6355.astrophoto.ui.AstroSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val PRIVACY_POLICY_URL =
    "https://github.com/Joe6355/AstroPhoto/blob/main/docs/PRIVACY_POLICY_RU.md"

@Composable
internal fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var document by remember { mutableStateOf<String?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        document = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("PRIVACY_POLICY_RU.md").bufferedReader().use { it.readText() }
            }.getOrNull()
        }
        readFailed = document == null
    }
    val paragraphs = remember(document) {
        document.orEmpty().trim().split(Regex("\\n\\s*\\n")).map(String::trim).filter { it.isNotBlank() }
    }
    val linkStyle = TextLinkStyles(SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    ))
    fun openLink(url: String) { linkError = !openPrivacyLink(context, url) }

    AstroScaffold(title = "Конфиденциальность", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("privacy-policy-screen"),
            contentPadding = PaddingValues(AstroSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AstroSpacing.Md)
        ) {
            item {
                AstroSecondaryButton("Открыть в браузере", { openLink(PRIVACY_POLICY_URL) },
                    modifier = Modifier.fillMaxWidth())
            }
            if (linkError) item { Text("Не удалось открыть ссылку. Проверьте, установлен ли браузер или почтовое приложение.") }
            if (document == null) item {
                Text(if (readFailed) "Не удалось прочитать политику. Доступна версия по ссылке выше."
                    else "Загрузка документа…")
            }
            items(paragraphs) { paragraph ->
                val heading = paragraph.startsWith("# ") || paragraph.startsWith("## ")
                val content = paragraph.removePrefix("## ").removePrefix("# ")
                    .replace(Regex("\\s*\\n\\s*"), " ")
                val richText = buildAnnotatedString {
                    var position = 0
                    POLICY_LINK.findAll(content).forEach { match ->
                        append(content.substring(position, match.range.first))
                        val url = match.groupValues[2]
                        withLink(LinkAnnotation.Clickable(url, linkStyle) { openLink(url) }) {
                            append(match.groupValues[1])
                        }
                        position = match.range.last + 1
                    }
                    append(content.substring(position))
                }
                Text(richText,
                    style = if (heading) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private val POLICY_LINK = Regex("\\[([^\\]]+)]\\((https://[^\\s)]+|mailto:[^\\s)]+)\\)")

private fun openPrivacyLink(context: Context, url: String): Boolean = runCatching {
    val uri = Uri.parse(url)
    require(uri.scheme == "https" || uri.scheme == "mailto")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    true
}.getOrDefault(false)
