package app.freedm.ui.screens

import android.webkit.CookieManager
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
import androidx.compose.ui.unit.dp
import app.freedm.data.Repo

/**
 * Per-site cookie management instead of one blunt "clear everything".
 *
 * There is no API to enumerate the cookie store, so the hosts are the ones the
 * browser actually visited (recorded on page finish). Each row can be cleared on
 * its own; the footer still offers the nuclear option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteDataScreen(onBack: () -> Unit) {
    val prefs = remember { Repo.prefs() }
    var hosts by remember {
        mutableStateOf(prefs.getStringSet("visited_hosts", emptySet()).orEmpty().sorted())
    }
    var tick by remember { mutableStateOf(0) } // forces a recompose after clearing

    fun cookieCount(host: String): Int {
        // Touch the change counter so the count is re-read after a clear.
        @Suppress("UNUSED_EXPRESSION") tick
        val raw = CookieManager.getInstance().getCookie("https://$host")
        return if (raw.isNullOrBlank()) 0 else raw.split(';').count { it.isNotBlank() }
    }

    fun clearHost(host: String) {
        val cm = CookieManager.getInstance()
        cm.getCookie("https://$host")
            ?.split(';')
            ?.map { it.substringBefore('=').trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { name ->
                cm.setCookie("https://$host", "$name=; Max-Age=0; Path=/")
                cm.setCookie("https://$host", "$name=; Max-Age=0; Path=/; Domain=$host")
            }
        runCatching { cm.flush() }
        hosts = hosts.filterNot { it == host }
        prefs.edit().putStringSet("visited_hosts", hosts.toSet()).apply()
        tick++
    }

    fun clearAll() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        hosts = emptyList()
        prefs.edit().remove("visited_hosts").remove("last_page").apply()
        tick++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网站数据") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        if (hosts.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("没有访问过的网站", style = MaterialTheme.typography.titleMedium)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(hosts) { host ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { clearHost(host) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(host, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Cookie ${cookieCount(host)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "清除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider()
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("全部清除", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { clearAll() }) { Text("清除") }
                }
            }
        }
    }
}
