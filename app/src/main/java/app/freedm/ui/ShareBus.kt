package app.freedm.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands a URL to the downloads UI — from a share intent, a VIEW intent, or the
 * optional clipboard watcher. In-memory only; nothing is persisted or logged.
 */
object ShareBus {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun post(url: String) {
        _pending.value = url
    }

    fun consume() {
        _pending.value = null
    }
}
