package app.freedm.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.freedm.R
import app.freedm.data.DownloadTask
import app.freedm.data.Repo
import app.freedm.data.Segment
import app.freedm.data.TaskState
import app.freedm.engine.Engine
import app.freedm.engine.FileNamer
import app.freedm.engine.Prober
import app.freedm.service.DownloadService
import app.freedm.ui.components.SegmentProgressBar
import app.freedm.ui.firstUrl
import app.freedm.ui.formatBytes
import app.freedm.ui.formatEta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One list, four views — the old separate 已完成 page is folded in here. */
private enum class Filter(val label: String) {
    ALL("全部"), ACTIVE("进行中"), DONE("已完成"), FAILED("错误");

    fun matches(t: DownloadTask): Boolean = when (this) {
        ALL -> true
        ACTIVE -> !t.state.isFinished
        DONE -> t.state == TaskState.COMPLETED
        FAILED -> t.state == TaskState.FAILED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    scope: CoroutineScope,
    onOpenDetail: (Long) -> Unit,
) {
    val tasks by Repo.tasks.collectAsState()
    val progress by Engine.progress.collectAsState()
    val segmentMap by Repo.segments.collectAsState()
    var filter by remember { mutableStateOf(Filter.ALL) }
    var showAdd by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<String?>(null) }
    val ctx = Repo.context

    // Paused and finished tasks have no live counters, so load their persisted
    // segment rows once per task set and drive the bar from those.
    LaunchedEffect(tasks.map { it.id }) {
        withContext(Dispatchers.IO) { tasks.forEach { Repo.publishSegments(it.id) } }
    }

    // A link shared into the app, or caught by the clipboard watcher, opens the
    // sheet pre-filled. Consumed immediately so it never fires twice.
    val shared by app.freedm.ui.ShareBus.pending.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shared) {
        if (shared != null) {
            prefill = shared
            showAdd = true
            app.freedm.ui.ShareBus.consume()
        }
    }

    val visible = tasks.filter { filter.matches(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载") },
                actions = {
                    TextButton(onClick = { Engine.resumeAll() }) { Text("全部开始") }
                    TextButton(onClick = { Engine.pauseAll() }) { Text("全部暂停") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { prefill = null; showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建下载")
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            TabRow(selectedTabIndex = filter.ordinal) {
                Filter.entries.forEach { f ->
                    Tab(
                        selected = filter == f,
                        onClick = { filter = f },
                        text = {
                            Text(
                                "${f.label} ${tasks.count { t -> f.matches(t) }}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            if (visible.isEmpty()) {
                EmptyState(filter)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.id }) { t ->
                        val live = progress[t.id]
                        TaskCard(
                            task = t,
                            downloaded = live?.downloaded ?: t.downloaded,
                            total = live?.total?.takeIf { it > 0 } ?: t.totalBytes,
                            bps = live?.bytesPerSec ?: 0L,
                            segmentPercents = live?.segmentPercents?.takeIf { it.isNotEmpty() }
                                ?: persistedPercents(t, segmentMap[t.id]),
                            onClick = { onOpenDetail(t.id) },
                            onToggle = { toggle(t) },
                            onOpen = if (t.state == TaskState.COMPLETED) {
                                { openFile(ctx, t) }
                            } else {
                                null
                            },
                            onRedownload = { redownload(t) },
                            onDelete = { Repo.delete(t.id, deleteFiles = true) },
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    if (showAdd) {
        AddDownloadSheet(
            initialUrl = prefill ?: "",
            onDismiss = { showAdd = false },
            scope = scope,
        )
    }
}

private fun toggle(t: DownloadTask) {
    if (t.state == TaskState.RUNNING) {
        Engine.pause(t.id)
    } else {
        Repo.setState(t.id, TaskState.QUEUED)
        DownloadService.start(Repo.context)
        Engine.pump()
    }
}

/** Re-queues a finished task under a fresh name, as the old 已完成 page did. */
private fun redownload(t: DownloadTask) {
    val dir = File(t.dirPath)
    val nm = File(t.fileName).nameWithoutExtension
    val ext = File(t.fileName).extension
    val copy = FileNamer.uniqueIn(dir, if (ext.isEmpty()) nm else "$nm.$ext")
    Repo.create(t.url, copy, t.dirPath, t.threadCount, t.wifiOnly, null)
    DownloadService.start(Repo.context)
    Engine.pump()
}

private fun openFile(ctx: Context, t: DownloadTask) {
    val f = File(t.filePath)
    if (!f.exists()) {
        Toast.makeText(ctx, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val ok = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, t.mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!ok) Toast.makeText(ctx, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
}

/**
 * Per-segment completion read from SQLite (§16.3). A segment whose length is
 * still the unknown sentinel collapses the row back to one bar.
 */
private fun persistedPercents(t: DownloadTask, segs: List<Segment>?): List<Float> {
    if (t.state == TaskState.COMPLETED) return emptyList()
    val list = segs.orEmpty()
    if (list.size <= 1) return emptyList()
    val out = ArrayList<Float>(list.size)
    for (s in list) {
        if (s.endByte == Long.MAX_VALUE || s.length <= 0) return emptyList()
        out.add((s.downloaded.toFloat() / s.length).coerceIn(0f, 1f))
    }
    return out
}

@Composable
private fun EmptyState(filter: Filter) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (filter) {
                    Filter.ALL -> "还没有下载任务"
                    Filter.ACTIVE -> "没有进行中的任务"
                    Filter.DONE -> "没有已完成的任务"
                    Filter.FAILED -> "没有失败的任务"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (filter == Filter.ALL) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "点右下角 + 新建，或从浏览器分享链接过来",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    downloaded: Long,
    total: Long,
    bps: Long,
    segmentPercents: List<Float>,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)?,
    onRedownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val overall = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val partCount = if (segmentPercents.size > 1) segmentPercents.size else 1

    val done = task.state == TaskState.COMPLETED
    val failed = task.state == TaskState.FAILED
    val progressColor = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        // Same colour whether running or finished, so a full row reads as the
        // progression of the same bar rather than a different state colour.
        else -> MaterialTheme.colorScheme.primary
    }
    val onTop = if (done) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // A finished row opens the file directly; anything still in flight
            // opens the detail page instead.
            .clickable(onClick = if (done && onOpen != null) onOpen else onClick)
            .heightIn(min = 64.dp),
    ) {
        // Progress is the row background, not a separate strip.
        SegmentProgressBar(
            partCount = partCount,
            percents = segmentPercents,
            overall = overall,
            color = progressColor,
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_file),
                contentDescription = null,
                tint = onTop.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(32.dp)
                    .padding(6.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = task.fileName,
                    fontSize = 14.sp,
                    color = onTop,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (total > 0) {
                            "${(overall * 100).toInt()}% | ${formatBytes(if (done) total else downloaded)}"
                        } else {
                            formatBytes(downloaded)
                        },
                        fontSize = 11.sp,
                        color = onTop.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    if (bps > 0) {
                        Text(
                            text = "${formatBytes(bps)}/s",
                            fontSize = 11.sp,
                            color = onTop.copy(alpha = 0.85f),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                    } else if (failed) {
                        Text(
                            text = task.error ?: "失败",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val eta = if (bps > 0) formatEta(total - downloaded, bps) else ""
                    if (eta.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = eta,
                            fontSize = 11.sp,
                            color = onTop.copy(alpha = 0.85f),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.widthIn(min = 50.dp),
                        )
                    }
                }
            }
            if (!done) {
                IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (task.state == TaskState.RUNNING) Icons.Default.Close
                        else Icons.Default.PlayArrow,
                        contentDescription = if (task.state == TaskState.RUNNING) "暂停" else "继续",
                        tint = onTop,
                    )
                }
            } else {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = onTop)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("详细信息") },
                            onClick = { menu = false; onClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("重新下载") },
                            onClick = { menu = false; onRedownload() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDownloadSheet(
    initialUrl: String,
    onDismiss: () -> Unit,
    scope: CoroutineScope,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf("") }
    var threads by remember { mutableStateOf(Engine.defaultThreads().toFloat()) }
    var wifiOnly by remember { mutableStateOf(Repo.prefs().getBoolean("wifi_only", false)) }
    var probing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("新建下载", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; note = null },
                label = { Text("链接") },
                placeholder = { Text("https://…") },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("文件名（留空自动识别）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Text("线程数 ${threads.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = threads,
                onValueChange = { threads = it },
                valueRange = 1f..16f,
                steps = 14,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("仅 Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(10.dp))
                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                Spacer(Modifier.weight(1f))
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                enabled = firstUrl(url) != null && !probing,
                onClick = {
                    val clean = firstUrl(url) ?: return@Button
                    probing = true
                    scope.launch {
                        val probe = withContext(Dispatchers.IO) {
                            runCatching { Prober.probe(clean) }.getOrNull()
                        }
                        val dir = Repo.downloadsDir
                        val suggested = name.ifBlank {
                            probe?.suggestedName ?: "download"
                        }
                        val finalName = FileNamer.uniqueIn(dir, FileNamer.sanitize(suggested))
                        val id = Repo.create(
                            url = clean,
                            fileName = finalName,
                            dirPath = dir.absolutePath,
                            threadCount = threads.toInt(),
                            wifiOnly = wifiOnly,
                            probe = probe,
                        )
                        if (probe == null) note = "无法探测服务器信息，将按单连接重试"
                        probing = false
                        DownloadService.start(Repo.context)
                        Engine.start(id)
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (probing) "探测中…" else "开始下载")
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
