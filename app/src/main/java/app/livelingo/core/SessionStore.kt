package app.livelingo.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for the live session. The service writes, and the UI, tile,
 * notification and overlay read.
 */
class SessionStore {
    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _captions = MutableStateFlow(CaptionDisplay())
    val captions: StateFlow<CaptionDisplay> = _captions.asStateFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    fun updateStatus(block: (SessionStatus) -> SessionStatus) = _status.update(block)
    fun setCaptions(c: CaptionDisplay) { _captions.value = c }
    fun updateDiagnostics(block: (Diagnostics) -> Diagnostics) = _diagnostics.update(block)

    fun reset(target: String) {
        _status.value = SessionStatus(targetLanguage = target)
        _captions.value = CaptionDisplay()
        _diagnostics.value = Diagnostics()
    }
}
