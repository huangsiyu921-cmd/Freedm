package app.freedm.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands a URL to the browser tab — used by the history page, whose rows open the
 * page they point at. In-memory only.
 */
object BrowserBus {

    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> = _open.asStateFlow()

    fun post(url: String) {
        _open.value = url
    }

    fun consume() {
        _open.value = null
    }
}
