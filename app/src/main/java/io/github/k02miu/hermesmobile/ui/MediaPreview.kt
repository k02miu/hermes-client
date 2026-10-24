package io.github.k02miu.hermesmobile.ui

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clipToBounds
import androidx.activity.compose.BackHandler
import io.github.k02miu.hermesmobile.data.GatewayFile
import io.github.k02miu.hermesmobile.ui.theme.HermesColors
import io.github.k02miu.hermesmobile.ui.theme.MonoFamily

/** A run of assistant text: Markdown, or a file the agent delivered with a `MEDIA:<path>` line. */
sealed interface TextPart {
    data class Prose(val text: String) : TextPart
    data class Media(val path: String) : TextPart
}

private val MEDIA_LINE = Regex("""^[ \t]*[`*_]*MEDIA:[ \t]*`?([^`\r\n]+?)`?[*_]*[ \t]*$""", RegexOption.MULTILINE)

/** Splits out `MEDIA:` lines (Hermes's file delivery tag) so they render as openable files, not prose. */
fun splitMedia(text: String): List<TextPart> {
    val parts = mutableListOf<TextPart>()
    var from = 0
    for (m in MEDIA_LINE.findAll(text)) {
        text.substring(from, m.range.first).takeIf { it.isNotBlank() }?.let { parts += TextPart.Prose(it) }
        parts += TextPart.Media(m.groupValues[1].trim())
        from = m.range.last + 1
    }
    text.substring(from).takeIf { it.isNotBlank() }?.let { parts += TextPart.Prose(it) }
    return parts
}

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")
private val HTML_EXT = setOf("html", "htm")
private val TEXT_EXT = setOf("txt", "md", "json", "csv", "log", "yaml", "yml", "xml")

private fun extOf(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

/** Whether the phone can show this file itself; anything else is listed without a preview. */
fun canPreview(path: String) = extOf(path).let { it in IMAGE_EXT || it in HTML_EXT || it in TEXT_EXT }

@Composable
fun MediaChip(path: String, onOpen: () -> Unit) {
    val ext = extOf(path)
    val previewable = canPreview(path)
    Row(
        Modifier.widthIn(max = 640.dp).fillMaxWidth()
            .background(HermesColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, HermesColors.Line, RoundedCornerShape(12.dp))
            .clickable(enabled = previewable, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (ext) {
                in HTML_EXT -> Icons.Filled.Code
                in IMAGE_EXT -> Icons.Filled.Image
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            null, tint = HermesColors.Working, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(path.substringAfterLast('/'), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path.substringBeforeLast('/'), fontSize = 11.sp, color = HermesColors.Muted, fontFamily = MonoFamily,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (previewable) Text("プレビュー", fontSize = 13.sp, color = HermesColors.Working, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A gateway file over the chat pane: HTML runs in a WebView, images and text are wrapped in a page. */
@Composable
fun MediaPreview(path: String, load: suspend (String) -> GatewayFile, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var file by remember(path) { mutableStateOf<GatewayFile?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        runCatching { load(path) }.onSuccess { file = it }.onFailure { error = "開けませんでした（${it.message}）" }
    }
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize().background(HermesColors.Ground)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "閉じる") }
                Text(path.substringAfterLast('/'), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val f = file
                when {
                    error != null -> Text(error!!, color = HermesColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                    f == null -> CircularProgressIndicator(color = HermesColors.Working)
                    else -> FileWebView(f, Modifier.fillMaxSize().clipToBounds())
                }
            }
    }
}

@Composable
private fun FileWebView(file: GatewayFile, modifier: Modifier) {
    val page = remember(file) { pageFor(file) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    // Links leave the preview for the browser; in-page anchors stay put.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.scheme == "http" || url.scheme == "https") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
                            return true
                        }
                        return false
                    }
                }
                loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
    )
}

private fun pageFor(file: GatewayFile): String {
    val ext = extOf(file.name)
    return when {
        ext in HTML_EXT || file.mimeType == "text/html" -> file.bytes.decodeToString()
        ext in IMAGE_EXT || file.mimeType.startsWith("image/") -> {
            val data = Base64.encodeToString(file.bytes, Base64.NO_WRAP)
            """<html><head><meta name="viewport" content="width=device-width"></head>""" +
                """<body style="margin:0;background:#888"><img style="max-width:100%" src="data:${file.mimeType};base64,$data"></body></html>"""
        }
        else -> {
            val escaped = file.bytes.decodeToString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            """<html><head><meta name="viewport" content="width=device-width"></head>""" +
                """<body><pre style="white-space:pre-wrap;word-break:break-all;font-size:12px">$escaped</pre></body></html>"""
        }
    }
}
