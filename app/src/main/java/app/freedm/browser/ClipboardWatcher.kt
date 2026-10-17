package app.freedm.browser

import android.content.ClipboardManager
import android.content.Context
import app.freedm.data.Repo

/**
 * Optional clipboard link detection.
 *
 * Privacy constraints baked into the design (see specs/behavior-spec.md §3.2):
 *  - **Off by default.** Nothing is registered unless the user opts in.
 *  - Registered only while the UI is in the foreground; [stop] is called from
 *    `onPause`, so the listener never survives into the background.
 *  - The clipboard text is inspected for an http(s) URL and then discarded —
 *    it is never stored, logged, or transmitted.
 */
class ClipboardWatcher(
    private val context: Context,
    private val onLink: (String) -> Unit,
) {
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastSeen: String? = null

    val enabled: Boolean
        get() = Repo.prefs().getBoolean("clipboard_watch", false)

    fun start() {
        if (!enabled || listener != null) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val l = ClipboardManager.OnPrimaryClipChangedListener { handle(cm) }
        listener = l
        runCatching { cm.addPrimaryClipChangedListener(l) }
    }

    fun stop() {
        val l = listener ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { cm?.removePrimaryClipChangedListener(l) }
        listener = null
    }

    private fun handle(cm: ClipboardManager) {
        // hasPrimaryClip()/getPrimaryClip() only succeed for a foreground app on
        // Android 10+; the surrounding try swallows the SecurityException that
        // fires if the system disagrees about visibility.
        val text = runCatching {
            if (!cm.hasPrimaryClip()) return
            val desc = cm.primaryClipDescription ?: return
            if (!desc.hasMimeType("text/plain")) return
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull() ?: return

        if (text == lastSeen) return
        lastSeen = text
        val url = app.freedm.ui.firstUrl(text) ?: return
        onLink(url)
    }
}
