package app.freedm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedm.data.HistoryStore
import app.freedm.data.Repo
import app.freedm.ui.BrowserBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browsing history as its own page instead of a dialog.
 *
 * A page has room for the whole list, a per-row delete and the clear-all action
 * without squeezing three buttons into a dialog footer. Rows open the page they
 * point at through [BrowserBus], which lands back on the browser tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val prefs = remember { Repo.prefs() }
    var rows by remember { mutableStateOf(HistoryStore.load(prefs)) }
    val stamp = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("浏览历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = {
                            HistoryStore.clear(prefs)
                            rows = emptyList()
                        }) { Text("清空") }
                    }
                },
            )
        },
    ) { pad ->
        if (rows.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("没有浏览记录", style = MaterialTheme.typography.titleMedium)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(rows) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            BrowserBus.post(row.url)
                            onBack()
                        }
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title.ifBlank { row.url },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            row.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (row.at > 0) stamp.format(Date(row.at)) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    TextButton(onClick = {
                        HistoryStore.remove(prefs, row.url)
                        rows = rows.filterNot { it.url == row.url }
                    }) { Text("删除") }
                }
                HorizontalDivider()
            }
        }
    }
}
