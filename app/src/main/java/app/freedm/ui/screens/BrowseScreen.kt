package app.freedm.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.freedm.browser.SniffedMedia
import app.freedm.browser.Sniffer
import app.freedm.data.Repo
import app.freedm.engine.Engine
import app.freedm.engine.FileNamer
import app.freedm.engine.Prober
import app.freedm.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowseScreen() {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val media = remember { mutableStateListOf<SniffedMedia>() }
    var showMedia by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val snifferEnabled = remember { Repo.prefs().getBoolean("sniff", true) }

    fun absorb(list: List<SniffedMedia>) {
        val existing = media.map { it.key }.toHashSet()
        list.forEach { if (existing.add(it.key)) media.add(it) }
    }

    fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val target = if (text.startsWith("http://") || text.startsWith("https://")) {
            text
        } else if (text.contains('.') && !text.contains(' ')) {
            "https://$text"
        } else {
            val engine = Repo.prefs().getString("search_engine", "https://duckduckgo.com/?q=")!!
            engine + java.net.URLEncoder.encode(text, "UTF-8")
        }
        webView?.loadUrl(target)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "后退")
            }
            IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                Icon(Icons.Default.ArrowForward, contentDescription = "前进")
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                placeholder = { Text("搜索或输入网址") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { navigate(address) }),
            )
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = {
                webView?.loadUrl("about:blank")
                address = ""
                media.clear()
            }) {
                Icon(Icons.Default.Home, contentDescription = "主页")
            }
            TextButton(onClick = { showMedia = true }) {
                Text("${media.size}")
            }
        }

        if (loading > 0f && loading < 1f) {
            LinearProgressIndicator(progress = { loading }, modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Prober.UA
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webView = this

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                            address = url ?: address
                            canGoBack = v?.canGoBack() ?: false
                            canGoForward = v?.canGoForward() ?: false
                            media.clear()
                        }

                        override fun onPageFinished(v: WebView?, url: String?) {
                            canGoBack = v?.canGoBack() ?: false
                            canGoForward = v?.canGoForward() ?: false
                            if (!snifferEnabled || v == null) return
                            v.evaluateJavascript(Sniffer.PROBE_JS) { raw ->
                                absorb(Sniffer.parseProbeResult(raw))
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            if (snifferEnabled && request != null) {
                                val u = request.url.toString()
                                val kind = SniffedMedia.kindOf(u, null)
                                if (kind == SniffedMedia.Kind.VIDEO ||
                                    kind == SniffedMedia.Kind.AUDIO ||
                                    kind == SniffedMedia.Kind.HLS ||
                                    kind == SniffedMedia.Kind.DASH
                                ) {
                                    absorb(
                                        listOf(
                                            SniffedMedia(
                                                url = u,
                                                kind = kind,
                                                title = u.substringAfterLast('/').substringBefore('?'),
                                                fromNetwork = true,
                                            ),
                                        ),
                                    )
                                }
                            }
                            return null
                        }
                    }
                }
            },
            update = { webView = it },
        )
    }

    if (showMedia) {
        ModalBottomSheet(onDismissRequest = { showMedia = false }) {
            Column(Modifier.padding(16.dp)) {
                Text("检测到的媒体（${media.size}）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (media.isEmpty()) {
                    Text(
                        "当前页面还没检测到媒体。试着播放一下视频，或滚动页面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(media.toList(), key = { it.key }) { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AssistChip(
                                            onClick = {},
                                            label = {
                                                Text(
                                                    when (item.kind) {
                                                        SniffedMedia.Kind.VIDEO -> "VIDEO"
                                                        SniffedMedia.Kind.AUDIO -> "AUDIO"
                                                        SniffedMedia.Kind.HLS -> "HLS"
                                                        SniffedMedia.Kind.DASH -> "DASH"
                                                        SniffedMedia.Kind.FILE -> "FILE"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            item.title.take(60),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        item.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (item.kind == SniffedMedia.Kind.HLS || item.kind == SniffedMedia.Kind.DASH) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "流媒体清单，将按单个文件下载（不含分片合并）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(onClick = {
                                        scope.launch {
                                            val probe = withContext(Dispatchers.IO) {
                                                runCatching { Prober.probe(item.url) }.getOrNull()
                                            }
                                            val dir = Repo.downloadsDir
                                            val nm = FileNamer.uniqueIn(
                                                dir,
                                                FileNamer.sanitize(
                                                    probe?.suggestedName ?: item.title.ifBlank { "media" },
                                                ),
                                            )
                                            val id = Repo.create(
                                                item.url, nm, dir.absolutePath,
                                                Engine.defaultThreads(), false, probe,
                                            )
                                            DownloadService.start(Repo.context)
                                            Engine.start(id)
                                            showMedia = false
                                        }
                                    }) { Text("下载") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
