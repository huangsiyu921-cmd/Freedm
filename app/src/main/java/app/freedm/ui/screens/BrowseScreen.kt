package app.freedm.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import app.freedm.ui.firstUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val UA_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowseScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Repo.prefs() }
    val scope = rememberCoroutineScope()

    var address by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val media = remember { mutableStateListOf<SniffedMedia>() }
    var showMedia by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The start page is a Compose overlay rather than a loaded URL, so the first
    // thing on screen is never a blank WebView.
    var showStart by remember { mutableStateOf(true) }

    var menuOpen by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var sourceText by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var historyItems by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var snifferEnabled by remember { mutableStateOf(prefs.getBoolean("sniff", true)) }
    var desktopUa by remember { mutableStateOf(prefs.getBoolean("desktop_ua", false)) }

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
            val engine = prefs.getString("search_engine", SEARCH_DEFAULT) ?: SEARCH_DEFAULT
            engine + java.net.URLEncoder.encode(text, "UTF-8")
        }
        showStart = false
        webView?.loadUrl(target)
    }

    fun showStartPage() {
        webView?.loadUrl("about:blank")
        address = ""
        media.clear()
        findOpen = false
        showStart = true
    }

    fun setDesktop(on: Boolean) {
        desktopUa = on
        prefs.edit().putBoolean("desktop_ua", on).apply()
        webView?.let {
            it.settings.userAgentString = if (on) UA_DESKTOP else Prober.UA
            it.reload()
        }
    }

    fun saveCurrentPage() {
        val url = address
        if (url.isBlank()) return
        scope.launch {
            val probe = withContext(Dispatchers.IO) {
                runCatching { Prober.probe(url) }.getOrNull()
            }
            val dir = Repo.downloadsDir
            val fallback = url.substringAfterLast('/').substringBefore('?').ifBlank { "page" }
            val name = FileNamer.uniqueIn(
                dir,
                FileNamer.sanitize(probe?.suggestedName ?: fallback),
            )
            val id = Repo.create(url, name, dir.absolutePath, Engine.defaultThreads(), false, probe)
            DownloadService.start(Repo.context)
            Engine.start(id)
            Toast.makeText(ctx, "已加入下载", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearBrowsingData() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView?.clearHistory()
            webView?.clearCache(true)
            webView?.clearFormData()
            prefs.edit().remove("last_page").apply()
        }
        showStartPage()
        Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
    }

    // Back closes the find bar first, then walks WebView history, then returns
    // to the start page. Only after that does the system leave the tab.
    BackHandler(enabled = findOpen) { findOpen = false }
    BackHandler(enabled = canGoBack) { webView?.goBack() }
    BackHandler(enabled = !canGoBack && !showStart) { showStartPage() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                IconButton(onClick = { webView?.goBack() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "后退",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // Compact address bar: the field owns the reload action, so the bar
            // stays one line on a phone instead of crowding six icons.
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (showStart) {
                        Text(
                            "搜索或输入网址",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = if (showStart) "" else address,
                        onValueChange = { address = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { navigate(address) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(
                    onClick = { webView?.reload() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "菜单",
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("主页") {
                        menuOpen = false
                        showStartPage()
                    }
                    MenuItem("页内查找") {
                        menuOpen = false
                        findOpen = true
                    }
                    MenuItem("查看网页源码") {
                        menuOpen = false
                        webView?.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                            sourceText = unquoteJs(raw)
                        }
                    }
                    MenuItem("打印页面") {
                        menuOpen = false
                        val pm = ctx.getSystemService(PrintManager::class.java)
                        val wv = webView
                        if (pm != null && wv != null) {
                            val job = wv.createPrintDocumentAdapter("FreeDM")
                            runCatching { pm.print("FreeDM", job, null) }
                        }
                    }
                    MenuItem("保存网页") {
                        menuOpen = false
                        saveCurrentPage()
                    }
                    MenuItem("浏览历史") {
                        menuOpen = false
                        val list = webView?.copyBackForwardList()
                        historyItems = if (list == null) {
                            emptyList()
                        } else {
                            (0 until list.size).map { i ->
                                val item = list.getItemAtIndex(i)
                                (item.title ?: item.url ?: "") to (item.url ?: "")
                            }
                        }
                        historyOpen = true
                    }
                    HorizontalDivider()
                    MenuItem(if (snifferEnabled) "☑ 嗅探音频/视频" else "☐ 嗅探音频/视频") {
                        menuOpen = false
                        snifferEnabled = !snifferEnabled
                        prefs.edit().putBoolean("sniff", snifferEnabled).apply()
                        if (!snifferEnabled) media.clear()
                    }
                    MenuItem("查看检测到的媒体 (${media.size})") {
                        menuOpen = false
                        showMedia = true
                    }
                    HorizontalDivider()
                    MenuItem("复制链接", enabled = address.isNotBlank()) {
                        menuOpen = false
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("url", address))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    MenuItem("分享链接", enabled = address.isNotBlank()) {
                        menuOpen = false
                        runCatching {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, address)
                                    },
                                    null,
                                ),
                            )
                        }
                    }
                    MenuItem("用系统浏览器打开", enabled = address.isNotBlank()) {
                        menuOpen = false
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address)),
                            )
                        }
                    }
                    MenuItem("打开剪贴板中的链接") {
                        menuOpen = false
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        val raw = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.text?.toString()
                        val url = firstUrl(raw)
                        if (url == null) {
                            Toast.makeText(ctx, "剪贴板里没有链接", Toast.LENGTH_SHORT).show()
                        } else {
                            showStart = false
                            webView?.loadUrl(url)
                        }
                    }
                    HorizontalDivider()
                    MenuItem(if (desktopUa) "切换到移动版" else "切换到桌面版") {
                        menuOpen = false
                        setDesktop(!desktopUa)
                    }
                    MenuItem("清除浏览数据") {
                        menuOpen = false
                        clearBrowsingData()
                    }
                }
            }
        }

        if (findOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = {
                        findQuery = it
                        webView?.findAllAsync(it)
                    },
                    placeholder = { Text("页内查找") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { webView?.findNext(true) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "上一个")
                }
                IconButton(onClick = { webView?.findNext(false) }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "下一个")
                }
                IconButton(onClick = {
                    webView?.clearMatches()
                    findOpen = false
                }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭查找")
                }
            }
        }

        if (loading > 0f && loading < 1f) {
            LinearProgressIndicator(progress = { loading }, modifier = Modifier.fillMaxWidth())
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = prefs.getBoolean("js_enabled", true)
                        settings.domStorageEnabled = true
                        settings.userAgentString = if (desktopUa) UA_DESKTOP else Prober.UA
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webView = this

                        // Restore the last page when the user asked for it.
                        if (prefs.getBoolean("remember_page", false)) {
                            prefs.getString("last_page", null)?.takeIf { it.isNotBlank() }?.let {
                                showStart = false
                                loadUrl(it)
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                // Backing out to about:blank must land on the start
                                // page, not on an empty white WebView.
                                val blank = url == "about:blank"
                                address = if (blank) "" else url ?: address
                                if (blank) showStart = true
                                canGoBack = v?.canGoBack() ?: false
                                canGoForward = v?.canGoForward() ?: false
                                media.clear()
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                canGoBack = v?.canGoBack() ?: false
                                canGoForward = v?.canGoForward() ?: false
                                if (url != null && url != "about:blank" &&
                                    prefs.getBoolean("remember_page", false)
                                ) {
                                    prefs.edit().putString("last_page", url).apply()
                                }
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

            if (showStart) {
                StartPage(
                    onSubmit = { navigate(it) },
                    onOpen = { url ->
                        showStart = false
                        webView?.loadUrl(url)
                    },
                )
            }
        }
    }

    sourceText?.let { html ->
        AlertDialog(
            onDismissRequest = { sourceText = null },
            title = { Text("网页源码") },
            text = {
                Box(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        html,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("html", html))
                    sourceText = null
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { sourceText = null }) { Text("关闭") }
            },
        )
    }

    if (historyOpen) {
        AlertDialog(
            onDismissRequest = { historyOpen = false },
            title = { Text("浏览历史") },
            text = {
                if (historyItems.isEmpty()) {
                    Text("暂无历史")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(historyItems) { row ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        historyOpen = false
                                        showStart = false
                                        webView?.loadUrl(row.second)
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    row.first.ifBlank { row.second },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    row.second,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyOpen = false }) { Text("关闭") }
            },
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

@Composable
private fun MenuItem(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** evaluateJavascript hands back a JSON-encoded string, not raw HTML. */
private fun unquoteJs(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    val body = raw.removeSurrounding("\"")
    val sb = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            when (val n = body[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    val hex = body.substring(i + 2, minOf(i + 6, body.length))
                    runCatching { sb.append(hex.toInt(16).toChar()) }.onFailure { sb.append(hex) }
                    i += 4
                }

                else -> sb.append(n)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
