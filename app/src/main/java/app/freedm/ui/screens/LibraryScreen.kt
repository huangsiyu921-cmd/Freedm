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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedm.data.Repo
import app.freedm.data.TaskState
import app.freedm.engine.Engine
import app.freedm.ui.formatBytes
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val tasks by Repo.tasks.collectAsState()
    val done = tasks.filter { it.state == TaskState.COMPLETED }
    val failed = tasks.filter { it.state == TaskState.FAILED }

    Scaffold(topBar = { TopAppBar(title = { Text("已完成") }) }) { pad ->
        if (done.isEmpty() && failed.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                Text("还没有完成的下载", style = MaterialTheme.typography.titleMedium)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (done.isNotEmpty()) {
                item { SectionHeader("已下载 ${done.size} 个") }
                items(done, key = { it.id }) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.fileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                // After a successful export the private copy is gone,
                                // so fall back to the recorded size instead of "0 B".
                                val f = File(t.filePath)
                                val size = if (f.exists()) f.length() else t.totalBytes
                                Text(
                                    formatBytes(size) + " · " + t.dirPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = {
                                val dir = File(t.dirPath)
                                val nm = File(t.fileName).nameWithoutExtension
                                val ext = File(t.fileName).extension
                                val copy = app.freedm.engine.FileNamer.uniqueIn(
                                    dir, if (ext.isEmpty()) nm else "$nm.$ext",
                                )
                                Repo.create(
                                    t.url, copy, t.dirPath, t.threadCount, t.wifiOnly, null,
                                )
                                Engine.pump()
                            }) { Text("重新下载") }
                            IconButton(onClick = { Repo.delete(t.id, deleteFiles = true) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
            if (failed.isNotEmpty()) {
                item { SectionHeader("失败 / 取消 ${failed.size} 个") }
                items(failed, key = { it.id }) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.fileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    t.error ?: "已取消",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            TextButton(onClick = {
                                Repo.setState(t.id, TaskState.QUEUED)
                                Engine.pump()
                            }) { Text("重试") }
                            IconButton(onClick = { Repo.delete(t.id, deleteFiles = false) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除记录")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
