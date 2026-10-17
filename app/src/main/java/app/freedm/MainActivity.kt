package app.freedm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.AlertDialog
import android.app.role.RoleManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.freedm.data.Repo
import app.freedm.ui.ShareBus
import app.freedm.ui.firstUrl
import app.freedm.ui.screens.BrowseScreen
import app.freedm.ui.screens.DownloadsScreen
import app.freedm.ui.screens.PrivacyScreen
import app.freedm.ui.screens.SettingsScreen
import app.freedm.ui.screens.SiteDataScreen
import app.freedm.ui.screens.TaskDetailScreen
import app.freedm.ui.theme.FreeDmTheme
import app.freedm.ui.theme.START_TAB
import app.freedm.ui.theme.THEME_DYNAMIC
import app.freedm.ui.theme.THEME_MODE
import app.freedm.ui.theme.isDarkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {

    private val scope: CoroutineScope = MainScope()
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val browserRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        consumeIntent(intent)
        app.freedm.engine.NetworkWatcher.start(this)

        // Tasks that were mid-transfer when the process died are QUEUED again;
        // resume them unless the user turned automatic resume off.
        if (Repo.prefs().getBoolean("auto_resume", true) &&
            Repo.tasks.value.any { it.state == app.freedm.data.TaskState.QUEUED }
        ) {
            app.freedm.service.DownloadService.start(this)
            app.freedm.engine.Engine.pump()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        askDefaultBrowserOnce()
        askAllFilesOnce()

        setContent {
            val tick by Repo.themeTick.collectAsState()
            val prefs = Repo.prefs()
            val mode = remember(tick) { prefs.getString(THEME_MODE, "system") ?: "system" }
            val dynamic = remember(tick) { prefs.getBoolean(THEME_DYNAMIC, false) }

            FreeDmTheme(mode = mode, dynamicColor = dynamic) {
                // System bar icons must follow what Compose paints, not what the
                // XML theme guessed: otherwise a dark UI gets black status icons.
                val dark = isDarkTheme(mode)
                LaunchedEffect(dark) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                // Window background is white (themes.xml), so paint the whole
                // root with the theme colour rather than letting it show through
                // around native views.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(scope)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    /** Asks once to become the default browser (Android 10+ role request). */
    private fun askDefaultBrowserOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) return
        if (rm.isRoleHeld(RoleManager.ROLE_BROWSER)) return
        val prefs = Repo.prefs()
        if (prefs.getBoolean("asked_browser_role", false)) return
        prefs.edit().putBoolean("asked_browser_role", true).apply()
        runCatching { browserRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)) }
    }

    /**
     * Asks once for "all files access" so downloads can land in the public
     * Download folder (and be openable from any file manager).
     */
    private fun askAllFilesOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (Environment.isExternalStorageManager()) return
        val prefs = Repo.prefs()
        if (prefs.getBoolean("asked_all_files", false)) return
        prefs.edit().putBoolean("asked_all_files", true).apply()
        AlertDialog.Builder(this)
            .setMessage("让下载直接保存到 Download 目录")
            .setPositiveButton("去开启") { _, _ ->
                runCatching {
                    allFilesLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** Picks up a shared link without storing anything about the sender. */
    private fun consumeIntent(intent: Intent?) {
        if (intent == null) return
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        firstUrl(raw)?.let { ShareBus.post(it) }
    }

    private val clipboardWatcher by lazy {
        app.freedm.browser.ClipboardWatcher(this) { url -> ShareBus.post(url) }
    }

    override fun onResume() {
        super.onResume()
        // Registered only while visible; onPause tears it down again.
        clipboardWatcher.start()
    }

    override fun onPause() {
        clipboardWatcher.stop()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    DOWNLOADS("下载", Icons.Default.Home),
    BROWSE("浏览", Icons.Default.Search),
    SETTINGS("设置", Icons.Default.Settings),
}

@Composable
private fun AppRoot(scope: CoroutineScope) {
    val startTab = remember { Repo.prefs().getString(START_TAB, "downloads") ?: "downloads" }
    var tab by remember {
        mutableStateOf(
            when (startTab) {
                "browse" -> Tab.BROWSE
                "settings" -> Tab.SETTINGS
                else -> Tab.DOWNLOADS
            },
        )
    }
    var detailId by remember { mutableStateOf<Long?>(null) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showSiteData by remember { mutableStateOf(false) }

    // A link shared in from another app (or the clipboard watcher) must land on
    // the download tab, otherwise the sheet never composes and the link is lost.
    val shared by ShareBus.pending.collectAsState()
    LaunchedEffect(shared) {
        if (shared != null) {
            showPrivacy = false
            detailId = null
            tab = Tab.DOWNLOADS
        }
    }

    // System back (button or edge gesture): unwind one layer at a time. The
    // innermost handler registered last wins, so overlays beat the tab reset.
    BackHandler(enabled = tab != Tab.DOWNLOADS) { tab = Tab.DOWNLOADS }
    BackHandler(enabled = detailId != null) { detailId = null }
    BackHandler(enabled = showPrivacy) { showPrivacy = false }
    BackHandler(enabled = showSiteData) { showSiteData = false }

    if (showSiteData) {
        SiteDataScreen(onBack = { showSiteData = false })
        return
    }
    if (showPrivacy) {
        PrivacyScreen(onBack = { showPrivacy = false })
        return
    }
    detailId?.let { id ->
        TaskDetailScreen(id = id, onBack = { detailId = null })
        return
    }

    Scaffold(
        bottomBar = { SlimBottomBar(tab) { tab = it } },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            // All three tabs stay composed: tearing the browser down on every
            // tab switch lost the open page, the tab list and the scroll state.
            TabHost(visible = tab == Tab.DOWNLOADS, index = 0) {
                DownloadsScreen(scope = scope, onOpenDetail = { detailId = it })
            }
            TabHost(visible = tab == Tab.BROWSE, index = 1) {
                BrowseScreen()
            }
            TabHost(visible = tab == Tab.SETTINGS, index = 2) {
                SettingsScreen(
                    onOpenPrivacy = { showPrivacy = true },
                    onOpenSiteData = { showSiteData = true },
                )
            }
        }
    }
}

/**
 * Keeps a tab's composition (and therefore its WebView / lists / scroll state)
 * alive while it is not selected.
 *
 * Hidden tabs are pushed far off-screen rather than just made transparent: an
 * alpha-0 layer still takes part in hit testing, and its event-swallowing child
 * was eating the drag gestures meant for the visible page (WebView could not
 * scroll).
 */
@Composable
private fun TabHost(
    visible: Boolean,
    index: Int,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .graphicsLayer {
                alpha = if (visible) 1f else 0f
                if (!visible) translationY = -20_000f
            },
    ) {
        content()
    }
}

/** A slimmer bar than M3's NavigationBar (80dp + insets → 52dp + insets). */
@Composable
private fun SlimBottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer)
            .navigationBarsPadding()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { item ->
            val selected = item == current
            val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.label,
                    fontSize = 10.sp,
                    color = tint,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}
