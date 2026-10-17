package app.freedm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedm.data.DownloadTask
import app.freedm.data.Repo
import app.freedm.data.TaskState
import app.freedm.engine.Engine
import app.freedm.engine.FileNamer
import app.freedm.engine.Prober
import app.freedm.service.DownloadService
import app.freedm.ui.firstUrl
import app.freedm.ui.formatBytes
import app.freedm.ui.formatEta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    scope: CoroutineScope,
    onOpenDetail: (Long) -> Unit,
) {
    val tasks by Repo.tasks.collectAsState()
    val progress by Engine.progress.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<String?>(null) }

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
        if (tasks.isEmpty()) {
            EmptyState(Modifier.padding(pad))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { t ->
                    TaskCard(
                        task = t,
                        downloaded = progress[t.id]?.downloaded ?: t.downloaded,
                        total = progress[t.id]?.total?.takeIf { it > 0 } ?: t.totalBytes,
                        bps = progress[t.id]?.bytesPerSec ?: 0L,
                        onClick = { onOpenDetail(t.id) },
                        onToggle = {
                            if (t.state == TaskState.RUNNING) {
                                Engine.pause(t.id)
                            } else {
                                Repo.setState(t.id, TaskState.QUEUED)
                                DownloadService.start(Repo.context)
                                Engine.pump()
                            }
                        },
                        onDelete = { Repo.delete(t.id, deleteFiles = true) },
                    )
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

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有下载任务", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "点右下角 + 新建，或从浏览器分享链接过来",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    downloaded: Long,
    total: Long,
    bps: Long,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                task.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))

            when (task.state) {
                TaskState.COMPLETED -> {
                    LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已完成 · ${formatBytes(if (total > 0) total else downloaded)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                TaskState.FAILED -> {
                    Text(
                        "失败：${task.error ?: "未知错误"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    val frac = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (total > 0) {
                                "${(frac * 100).toInt()}% · ${formatBytes(downloaded)} / ${formatBytes(total)}"
                            } else {
                                formatBytes(downloaded)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (bps > 0) {
                            Text(
                                " · ${formatBytes(bps)}/s",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val eta = formatEta(total - downloaded, bps)
                            if (eta.isNotEmpty()) {
                                Text(" · 剩 $eta", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when (task.state) {
                                TaskState.RUNNING -> "下载中"
                                TaskState.QUEUED -> "排队中"
                                TaskState.PAUSED -> "已暂停"
                                TaskState.COMPLETED -> "已完成"
                                TaskState.FAILED -> "失败"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                Spacer(Modifier.weight(1f))
                if (task.state != TaskState.COMPLETED) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (task.state == TaskState.RUNNING) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = if (task.state == TaskState.RUNNING) "暂停" else "继续",
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
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
