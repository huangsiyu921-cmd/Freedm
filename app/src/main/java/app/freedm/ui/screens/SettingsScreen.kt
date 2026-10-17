package app.freedm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import app.freedm.engine.Engine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenPrivacy: () -> Unit) {
    val prefs = remember { Repo.prefs() }
    var maxConcurrent by remember { mutableStateOf(prefs.getInt("max_concurrent", 3).toFloat()) }
    var threads by remember { mutableStateOf(prefs.getInt("threads", 8).toFloat()) }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("wifi_only", false)) }
    var autoResume by remember { mutableStateOf(prefs.getBoolean("auto_resume", true)) }
    var exportPublic by remember { mutableStateOf(prefs.getBoolean("export_public", false)) }
    var speedLimitMb by remember {
        mutableStateOf((prefs.getLong("speed_limit", 0L) / (1024.0 * 1024.0)).toFloat())
    }
    var sniff by remember { mutableStateOf(prefs.getBoolean("sniff", true)) }
    var clipWatch by remember { mutableStateOf(prefs.getBoolean("clipboard_watch", false)) }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { GroupTitle("下载") }
            item {
                SliderRow(
                    label = "最大同时下载数",
                    value = maxConcurrent,
                    range = 1f..8f,
                    steps = 6,
                    display = "${maxConcurrent.toInt()}",
                    onChange = {
                        maxConcurrent = it
                        prefs.edit().putInt("max_concurrent", it.toInt()).apply()
                    },
                )
            }
            item {
                SliderRow(
                    label = "每任务线程数",
                    value = threads,
                    range = 1f..16f,
                    steps = 14,
                    display = "${threads.toInt()}",
                    onChange = {
                        threads = it
                        prefs.edit().putInt("threads", it.toInt()).apply()
                    },
                )
            }
            item {
                SwitchRow(
                    label = "仅 Wi-Fi 下载",
                    description = "蜂窝网络下自动暂停，回到 Wi-Fi 继续",
                    checked = wifiOnly,
                    onChange = {
                        wifiOnly = it
                        prefs.edit().putBoolean("wifi_only", it).apply()
                    },
                )
            }
            item {
                SliderRow(
                    label = "全局限速",
                    value = speedLimitMb,
                    range = 0f..10f,
                    steps = 9,
                    display = if (speedLimitMb < 0.5f) "关闭" else "${speedLimitMb.toInt()} MB/s",
                    onChange = {
                        speedLimitMb = it
                        val bps = if (it < 0.5f) 0L else (it * 1024 * 1024).toLong()
                        prefs.edit().putLong("speed_limit", bps).apply()
                        app.freedm.engine.RateLimiter.setRate(bps)
                    },
                )
            }
            item {
                SwitchRow(
                    label = "自动恢复未完成任务",
                    description = "进程被系统结束后，重新打开时继续下载",
                    checked = autoResume,
                    onChange = {
                        autoResume = it
                        prefs.edit().putBoolean("auto_resume", it).apply()
                    },
                )
            }
            item {
                SwitchRow(
                    label = "保存到公共下载目录",
                    description = "完成后复制到系统 Downloads；下载过程仍在应用内进行",
                    checked = exportPublic,
                    onChange = {
                        exportPublic = it
                        prefs.edit().putBoolean("export_public", it).apply()
                    },
                )
            }

            item { GroupTitle("浏览与嗅探") }
            item {
                val engines = listOf(
                    "Bing" to "https://cn.bing.com/search?q=",
                    "百度" to "https://www.baidu.com/s?wd=",
                    "Google" to "https://www.google.com/search?q=",
                    "DuckDuckGo" to "https://duckduckgo.com/?q=",
                )
                val saved = prefs.getString("search_engine", engines[0].second)
                var idx by remember {
                    mutableStateOf(engines.indexOfFirst { it.second == saved }.coerceAtLeast(0))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            idx = (idx + 1) % engines.size
                            prefs.edit().putString("search_engine", engines[idx].second).apply()
                        }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("默认搜索引擎", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "点击切换",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(engines[idx].first, color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                SwitchRow(
                    label = "自动嗅探页面媒体",
                    description = "扫描页面与网络请求中的音视频资源",
                    checked = sniff,
                    onChange = {
                        sniff = it
                        prefs.edit().putBoolean("sniff", it).apply()
                    },
                )
            }

            item { GroupTitle("隐私") }
            item {
                SwitchRow(
                    label = "自动识别复制的链接",
                    description = "默认关闭。仅在前台时读取剪贴板，且不保存内容",
                    checked = clipWatch,
                    onChange = {
                        clipWatch = it
                        prefs.edit().putBoolean("clipboard_watch", it).apply()
                    },
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPrivacy() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text("隐私说明", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "查看",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item { GroupTitle("关于") }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("FreeDM 0.1.0", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "开源下载管理器，Apache-2.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "本应用不收集任何数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "默认下载目录：${Repo.downloadsDir.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "本机标识（随机生成，仅存本地）：${Repo.installId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val entries = listOf(
        "不读取设备标识" to "不访问 ANDROID_ID、IMEI、MEID、序列号、MAC 地址",
        "不读取 SIM 或运营商信息" to "不做国家/地区画像",
        "不集成统计与广告 SDK" to "没有 Firebase Analytics、崩溃上报、广告或渠道归因组件",
        "不申请敏感权限" to "不使用相机、麦克风、定位、应用使用情况统计、读取电话状态",
        "应用内标识" to "唯一的本机标识是安装时随机生成的 UUID，仅存于本地",
        "不联网上报" to "除你主动发起的下载与网页浏览外，应用不发出任何网络请求",
        "剪贴板" to "默认关闭；开启后仅在前台读取，仅用于识别链接，不保存内容",
        "网页浏览" to "内置浏览器会像普通浏览器一样向你所访问的网站发出请求",
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("隐私说明") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(entries.size) { i ->
                val (title, body) = entries[i]
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("✓  $title", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (i != entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
