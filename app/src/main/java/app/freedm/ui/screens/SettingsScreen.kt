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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import app.freedm.browser.AdBlocker
import app.freedm.data.Repo
import app.freedm.diag.Diag
import app.freedm.engine.Engine
import app.freedm.ui.theme.START_TAB
import app.freedm.ui.theme.THEME_DYNAMIC
import app.freedm.ui.theme.THEME_MODE
import app.freedm.ui.formatBytes
import kotlin.math.ln

/** 0..1 slider position ↔ 1 MB/s … 100 MB/s, logarithmic. */
private const val MB = 1024.0 * 1024.0

private fun posToBps(pos: Float): Long =
    (Math.pow(100.0, pos.toDouble().coerceIn(0.0, 1.0)) * MB).toLong()

private fun bpsToPos(bps: Long): Float =
    if (bps <= 0L) 0.6f else (ln(bps / MB) / ln(100.0)).toFloat().coerceIn(0f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenPrivacy: () -> Unit, onOpenSiteData: () -> Unit) {
    val prefs = remember { Repo.prefs() }
    var maxConcurrent by remember { mutableStateOf(prefs.getInt("max_concurrent", 3).toFloat()) }
    var threads by remember { mutableStateOf(prefs.getInt("threads", 8).toFloat()) }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("wifi_only", false)) }
    var autoResume by remember { mutableStateOf(prefs.getBoolean("auto_resume", true)) }
    var exportPublic by remember { mutableStateOf(prefs.getBoolean("export_public", true)) }
    var autoCategorize by remember { mutableStateOf(prefs.getBoolean("auto_categorize", true)) }
    var sniff by remember { mutableStateOf(prefs.getBoolean("sniff", true)) }
    var adblock by remember { mutableStateOf(AdBlocker.isEnabled()) }
    var desktopUa by remember { mutableStateOf(prefs.getBoolean("desktop_ua", false)) }
    var jsEnabled by remember { mutableStateOf(prefs.getBoolean("js_enabled", true)) }
    // On by default: login and captcha flows need it, and WebView's own default
    // (off) is what silently broke them.
    var thirdPartyCookies by remember {
        mutableStateOf(prefs.getBoolean("third_party_cookies", true))
    }
    var rememberPage by remember { mutableStateOf(prefs.getBoolean("remember_page", false)) }
    var clipWatch by remember { mutableStateOf(prefs.getBoolean("clipboard_watch", false)) }
    var themeMode by remember { mutableStateOf(prefs.getString(THEME_MODE, "system") ?: "system") }
    var dynamicColor by remember { mutableStateOf(prefs.getBoolean(THEME_DYNAMIC, false)) }
    var startTab by remember { mutableStateOf(prefs.getString(START_TAB, "downloads") ?: "downloads") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var diagOn by remember { mutableStateOf(Diag.isEnabled()) }
    var diagSize by remember { mutableStateOf(Diag.sizeBytes()) }
    var userRuleCount by remember {
        mutableStateOf(run {
            AdBlocker.ensureLoaded(ctx)
            AdBlocker.userRuleCount()
        })
    }
    val importRules = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val added = AdBlocker.importFrom(ctx, uri)
            userRuleCount = AdBlocker.userRuleCount()
            android.widget.Toast.makeText(
                ctx,
                if (added > 0) "导入 $added 条" else "没读到有效规则",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { GroupTitle("个性化") }
            item {
                // One row per setting: label on the left, choices inline.
                ChoiceRow(
                    label = "主题",
                    options = listOf(
                        "system" to "跟随系统",
                        "light" to "亮色",
                        "dark" to "暗色",
                    ),
                    selected = themeMode,
                    onSelect = {
                        themeMode = it
                        prefs.edit().putString(THEME_MODE, it).apply()
                        Repo.bumpTheme()
                    },
                )
            }
            item {
                val supported = android.os.Build.VERSION.SDK_INT >= 31
                SwitchRow(
                    label = "动态取色",
                    description = if (supported) "用壁纸配色" else "需要 Android 12 及以上",
                    checked = dynamicColor && supported,
                    onChange = {
                        if (supported) {
                            dynamicColor = it
                            prefs.edit().putBoolean(THEME_DYNAMIC, it).apply()
                            Repo.bumpTheme()
                        }
                    },
                )
            }
            item {
                ChoiceRow(
                    label = "启动页",
                    options = listOf(
                        "downloads" to "下载",
                        "browse" to "浏览",
                        "settings" to "设置",
                    ),
                    selected = startTab,
                    onSelect = {
                        startTab = it
                        prefs.edit().putString(START_TAB, it).apply()
                    },
                )
            }

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
                // Logarithmic 1 MB/s … 100 MB/s: equal drag distance per decade,
                // so the slow end stays adjustable.
                var limitOn by remember { mutableStateOf(prefs.getLong("speed_limit", 0L) > 0L) }
                var pos by remember {
                    mutableStateOf(bpsToPos(prefs.getLong("speed_limit", 0L)))
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("全局限速", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (limitOn) "${formatBytes(posToBps(pos))}/s" else "关闭",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = limitOn,
                            onCheckedChange = {
                                limitOn = it
                                val bps = if (it) posToBps(pos) else 0L
                                prefs.edit().putLong("speed_limit", bps).apply()
                                app.freedm.engine.RateLimiter.setRate(bps)
                            },
                        )
                    }
                    if (limitOn) {
                        Slider(
                            value = pos,
                            onValueChange = { pos = it },
                            valueRange = 0f..1f,
                            onValueChangeFinished = {
                                val bps = posToBps(pos)
                                prefs.edit().putLong("speed_limit", bps).apply()
                                app.freedm.engine.RateLimiter.setRate(bps)
                            },
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "1 MB/s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "100 MB/s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
            item {
                SwitchRow(
                    label = "按类型归档",
                    description = "新任务按文件类型放进 Video / Music / Documents 等子目录",
                    checked = autoCategorize,
                    onChange = {
                        autoCategorize = it
                        prefs.edit().putBoolean("auto_categorize", it).apply()
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
            item {
                SwitchRow(
                    label = "广告过滤",
                    description = "拦截广告与统计域名，可在浏览页按站点关闭",
                    checked = adblock,
                    onChange = {
                        adblock = it
                        AdBlocker.setEnabled(it)
                    },
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { importRules.launch(arrayOf("text/plain", "*/*")) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("导入规则文件", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (userRuleCount > 0) {
                                "已导入 $userRuleCount 条 · 只接受域名格式"
                            } else {
                                "只接受域名或 hosts 格式，路径与正则会被忽略"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (userRuleCount > 0) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val n = AdBlocker.clearUserRules(ctx)
                                userRuleCount = 0
                                android.widget.Toast.makeText(
                                    ctx, "已清除 $n 条", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "清空导入的规则",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item {
                SwitchRow(
                    label = "桌面版页面",
                    description = "请求网站的桌面版页面",
                    checked = desktopUa,
                    onChange = {
                        desktopUa = it
                        prefs.edit().putBoolean("desktop_ua", it).apply()
                    },
                )
            }
            item {
                SwitchRow(
                    label = "启用 JavaScript",
                    description = "关闭后多数网站无法正常显示",
                    checked = jsEnabled,
                    onChange = {
                        jsEnabled = it
                        prefs.edit().putBoolean("js_enabled", it).apply()
                    },
                )
            }
            item {
                SwitchRow(
                    label = "允许第三方 Cookie",
                    description = "登录、验证码这类页面需要；关掉可能登不上",
                    checked = thirdPartyCookies,
                    onChange = {
                        thirdPartyCookies = it
                        prefs.edit().putBoolean("third_party_cookies", it).apply()
                    },
                )
            }
            item {
                SwitchRow(
                    label = "记住上次页面",
                    description = "打开浏览页时恢复上次访问的网址",
                    checked = rememberPage,
                    onChange = {
                        rememberPage = it
                        prefs.edit().putBoolean("remember_page", it).apply()
                    },
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSiteData() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("管理网站 Cookies", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "按站点查看与清除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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

            item { GroupTitle("诊断") }
            item {
                SwitchRow(
                    label = "记录诊断日志",
                    description = "只记域名与错误码，存在应用外部目录，不上传",
                    checked = diagOn,
                    onChange = {
                        diagOn = it
                        Diag.setEnabled(it)
                    },
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Diag.clear()
                            diagSize = 0L
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("清空诊断日志", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            Diag.location() ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        formatBytes(diagSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { GroupTitle("关于") }
            item {
                // Read the version from the package instead of hard-coding it: the
                // last hard-coded one said 0.1.0 two releases later.
                val version = remember {
                    runCatching {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                    }.getOrNull().orEmpty()
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        if (version.isBlank()) "FreeDM" else "FreeDM $version",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "开源下载管理器，GPL-3.0",
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
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(64.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            options.forEachIndexed { i, (value, text) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) {
                    Text(text, maxLines = 1, fontSize = 12.sp)
                }
            }
        }
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
