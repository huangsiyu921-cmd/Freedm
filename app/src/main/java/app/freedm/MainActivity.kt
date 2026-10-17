package app.freedm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import app.freedm.data.Repo
import app.freedm.ui.ShareBus
import app.freedm.ui.firstUrl
import app.freedm.ui.screens.BrowseScreen
import app.freedm.ui.screens.DownloadsScreen
import app.freedm.ui.screens.PrivacyScreen
import app.freedm.ui.screens.SettingsScreen
import app.freedm.ui.screens.TaskDetailScreen
import app.freedm.ui.theme.FreeDmTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {

    private val scope: CoroutineScope = MainScope()
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        setContent {
            FreeDmTheme {
                // Window background is white (§themes.xml), so paint the whole
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

private enum class Tab { DOWNLOADS, BROWSE, SETTINGS }

@Composable
private fun AppRoot(scope: CoroutineScope) {
    var tab by remember { mutableStateOf(Tab.DOWNLOADS) }
    var detailId by remember { mutableStateOf<Long?>(null) }
    var showPrivacy by remember { mutableStateOf(false) }

    // System back (button or edge gesture): unwind one layer at a time. The
    // innermost handler registered last wins, so overlays beat the tab reset.
    BackHandler(enabled = tab != Tab.DOWNLOADS) { tab = Tab.DOWNLOADS }
    BackHandler(enabled = detailId != null) { detailId = null }
    BackHandler(enabled = showPrivacy) { showPrivacy = false }

    if (showPrivacy) {
        PrivacyScreen(onBack = { showPrivacy = false })
        return
    }
    detailId?.let { id ->
        TaskDetailScreen(id = id, onBack = { detailId = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.DOWNLOADS,
                    onClick = { tab = Tab.DOWNLOADS },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("下载") },
                )
                NavigationBarItem(
                    selected = tab == Tab.BROWSE,
                    onClick = { tab = Tab.BROWSE },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("浏览") },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            when (tab) {
                Tab.DOWNLOADS -> DownloadsScreen(scope = scope, onOpenDetail = { detailId = it })
                Tab.BROWSE -> BrowseScreen()
                Tab.SETTINGS -> SettingsScreen(onOpenPrivacy = { showPrivacy = true })
            }
        }
    }
}
