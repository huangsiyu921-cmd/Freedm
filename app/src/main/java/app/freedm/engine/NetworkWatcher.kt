package app.freedm.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.freedm.data.Repo
import app.freedm.data.TaskState
import app.freedm.service.DownloadService

/**
 * Watches for a usable network coming back and resumes tasks that paused
 * *themselves* because of the "Wi-Fi only" constraint.
 *
 * Only tasks whose `error` equals [Engine.WIFI_WAIT] are touched, so a task the
 * user paused by hand is never resumed behind their back — the same distinction
 * as the RUNNING/PAUSED mapping in [Repo.init].
 */
object NetworkWatcher {

    private var registered = false

    fun start(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val waiting = Repo.tasks.value.filter {
                        it.state == TaskState.PAUSED && it.error == Engine.WIFI_WAIT
                    }
                    if (waiting.isEmpty()) return
                    waiting.forEach { Repo.setState(it.id, TaskState.QUEUED) }
                    DownloadService.start(app)
                    Engine.pump()
                }
            })
            registered = true
        }
    }
}
