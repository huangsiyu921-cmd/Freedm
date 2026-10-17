package app.freedm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedm.data.Repo
import app.freedm.data.TaskState
import app.freedm.engine.Engine
import app.freedm.ui.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(id: Long, onBack: () -> Unit) {
    val tasks by Repo.tasks.collectAsState()
    val allSegments by Repo.segments.collectAsState()
    val progress by Engine.progress.collectAsState()
    val task = tasks.firstOrNull { it.id == id }

    LaunchedEffect(id) { Repo.publishSegments(id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        if (task == null) {
            Text("任务不存在", Modifier.padding(pad).padding(20.dp))
            return@Scaffold
        }
        val segments = allSegments[id].orEmpty()
        val downloaded = progress[id]?.downloaded ?: task.downloaded
        val total = progress[id]?.total?.takeIf { it > 0 } ?: task.totalBytes

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(task.fileName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                val frac = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    if (total > 0) "${(frac * 100).toInt()}% · ${formatBytes(downloaded)} / ${formatBytes(total)}"
                    else formatBytes(downloaded),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    Button(
                        onClick = {
                            when (task.state) {
                                TaskState.RUNNING -> Engine.pause(task.id)
                                TaskState.COMPLETED -> Unit
                                else -> {
                                    Repo.setState(task.id, TaskState.QUEUED)
                                    Engine.pump()
                                }
                            }
                        },
                        enabled = task.state != TaskState.COMPLETED,
                    ) {
                        Text(
                            when (task.state) {
                                TaskState.RUNNING -> "暂停"
                                TaskState.QUEUED -> "排队中"
                                TaskState.PAUSED -> "继续"
                                TaskState.FAILED -> "重试"
                                else -> "已完成"
                            },
                        )
                    }
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(onClick = { Repo.delete(task.id, deleteFiles = true); onBack() }) {
                        Text("删除")
                    }
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                InfoRow("状态", task.state.name)
                InfoRow("链接", task.finalUrl ?: task.url, maxLines = 3)
                InfoRow("保存路径", task.filePath, maxLines = 2)
                InfoRow("类型", task.mime ?: "未知")
                InfoRow("分段下载", if (task.segmented) "是（${task.threadCount} 线程）" else "否（单连接）")
                task.etag?.let { InfoRow("ETag", it, maxLines = 1) }
                task.error?.let { InfoRow("错误", it) }
                Spacer(Modifier.height(16.dp))
                Text("分片", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                if (segments.isEmpty()) {
                    Text(
                        "尚未开始，分片计划会在开始后生成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(segments, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${s.index}", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${formatBytes(s.downloaded)} / ${formatBytes(s.length)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (s.length > 0) (s.downloaded.toFloat() / s.length).coerceIn(0f, 1f) else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, maxLines: Int = 2) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
