package dev.nglmercer.tiktools.live

import android.content.Context
import dev.nglmercer.tiktools.R
import dev.nglmercer.tiktools.core.model.Creator

sealed interface LiveSessionState {
    data object Disconnected : LiveSessionState

    data class Resolving(val username: String) : LiveSessionState

    data class Preparing(val creator: Creator) : LiveSessionState

    data class Connecting(val creator: Creator) : LiveSessionState

    data class Connected(val creator: Creator, val since: Long = System.currentTimeMillis()) :
        LiveSessionState

    data class Reconnecting(val creator: Creator, val attempt: Int) : LiveSessionState

    data class Failed(val message: String) : LiveSessionState
}

fun LiveSessionState.creator(): Creator? =
    when (this) {
        is LiveSessionState.Preparing -> creator
        is LiveSessionState.Connecting -> creator
        is LiveSessionState.Connected -> creator
        is LiveSessionState.Reconnecting -> creator
        else -> null
    }
val LiveSessionState.isActive: Boolean
    get() = this !is LiveSessionState.Disconnected && this !is LiveSessionState.Failed

fun LiveSessionState.localizedLabel(context: Context): String =
    when (this) {
        LiveSessionState.Disconnected -> context.getString(R.string.status_offline)
        is LiveSessionState.Resolving -> context.getString(R.string.status_resolving, username)
        is LiveSessionState.Preparing -> context.getString(R.string.status_preparing)
        is LiveSessionState.Connecting -> context.getString(R.string.status_connecting)
        is LiveSessionState.Connected -> context.getString(R.string.status_live)
        is LiveSessionState.Reconnecting -> context.getString(R.string.status_reconnecting, attempt)
        is LiveSessionState.Failed -> context.getString(R.string.status_failed)
    }
