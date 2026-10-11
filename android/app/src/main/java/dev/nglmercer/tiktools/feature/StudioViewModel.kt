package dev.nglmercer.tiktools.feature

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nglmercer.tiktools.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

abstract class StudioViewModel(private val context: Context) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun dismissError() {
        _error.value = null
    }

    protected fun task(block: suspend () -> Unit) =
        viewModelScope.launch {
            _error.value = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = localizeError(e.message)
            }
        }

    private fun localizeError(message: String?): String {
        val resource =
            when (message) {
                "Enter a username",
                "Enter a TikTok username",
                "a handle is required" -> R.string.error_username_required
                "Name is required" -> R.string.error_name_required
                "Select at least one trigger" -> R.string.error_trigger_required
                "Enter an HTTP or HTTPS URL" -> R.string.error_http_url_required
                "Cooldown must be between 0 and 86400 seconds" -> R.string.error_cooldown_range
                "Enter a finite amount" -> R.string.error_amount_invalid
                "No creators are LIVE right now" -> R.string.error_no_creators_live
                "Could not remove cached bundle" -> R.string.error_remove_bundle
                "LIVE engine unavailable on this device" -> R.string.error_live_engine_unavailable
                else -> null
            }
        return resource?.let(context::getString)
            ?: message?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.error_operation_failed)
    }
}
