package app.freedm

import android.app.Application
import android.os.Build
import app.freedm.data.Repo
import app.freedm.diag.Diag

/**
 * The single place that initialises process-wide state.
 *
 * Every entry point needs [Repo] ready: the Activity, the network callback, and —
 * the case that used to break — the download service when the system restarts it
 * (`START_STICKY`). With initialisation living only in `MainActivity.onCreate`, a
 * service restart saw an empty task list and nothing drove the queue.
 */
class FreedmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Diag.init(this)
        Diag.log("APP_START", "sdk=${Build.VERSION.SDK_INT}")
    }
}
