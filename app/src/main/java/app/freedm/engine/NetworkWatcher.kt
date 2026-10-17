package app.freedm.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.freedm.data.Repo
import app.freedm.data.TaskState
import app.freedm.diag.Diag
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

    /** Last transport combination written to the log; keeps the file readable. */
    @Volatile
    private var lastTransport: String? = null

    fun start(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val waiting = Repo.tasks.value.filter {
                        it.state == TaskState.PAUSED &&
                            (it.error == Engine.WIFI_WAIT || it.error == Engine.NET_WAIT)
                    }
                    Diag.log("NET_AVAILABLE", "waiting=${waiting.size}")
                    if (waiting.isEmpty()) return
                    waiting.forEach { Repo.setState(it.id, TaskState.QUEUED) }
                    DownloadService.start(app)
                    Engine.pump()
                }

                override fun onLost(network: Network) {
                    Diag.log("NET_LOST", "wifi=${onWifi(app)}")
                }

                // The default network keeps its Network object across a Wi-Fi
                // hand-off, so transport changes only show up here — this is what
                // makes a VPN (or a Wi-Fi→cellular switch) visible in the log.
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    logTransports(caps)
                }
            })
            registered = true
        }
    }

    private fun logTransports(caps: NetworkCapabilities) {
        val key = buildString {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) append("wifi ")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) append("cell ")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) append("vpn ")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) append("eth ")
        }.trim().ifEmpty { "none" }
        if (key == lastTransport) return
        lastTransport = key
        Diag.log("NET_TRANSPORT", key)
    }

    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
