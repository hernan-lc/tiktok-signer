package dev.nglmercer.tiktools.feature.home

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import dev.nglmercer.tiktools.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.nglmercer.tiktools.core.model.Creator
import dev.nglmercer.tiktools.live.*
import dev.nglmercer.tiktools.tts.TtsEngine
import dev.nglmercer.tiktools.ui.components.*

sealed interface HomeAction {
    data class Connect(val username: String) : HomeAction

    data class SelectCreator(val creator: Creator) : HomeAction

    data object Disconnect : HomeAction

    data object Refresh : HomeAction

    data object Random : HomeAction

    data object Speech : HomeAction

    data object ToggleTts : HomeAction
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    error: String? = null,
    onDismiss: () -> Unit = {},
) {
    var username by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().testTag("home-screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(tr(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
            LiveStatusBadge(state.connection)
        }
        item {
            ErrorBanner(error ?: (state.connection as? LiveSessionState.Failed)?.message, onDismiss)
        }
        val creator = state.connection.creator()
        if (creator != null) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CreatorAvatar(
                        creator.avatarUrl,
                        creator.nickname.ifEmpty { creator.uniqueId },
                        64.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("@${creator.uniqueId}", style = MaterialTheme.typography.titleLarge)
                        Text(
                            creator.title.ifBlank { creator.nickname },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            trPlural(
                                R.plurals.event_viewers,
                                (if (state.stats.viewers > 0) state.stats.viewers else creator.viewers)
                                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                localizedCompact(
                                    if (state.stats.viewers > 0) state.stats.viewers
                                    else creator.viewers
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            if (!state.connection.isActive) {
                OutlinedTextField(
                    username,
                    { username = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(tr(R.string.tiktok_username)) },
                    placeholder = { Text(tr(R.string.creator_placeholder)) },
                    singleLine = true,
                )
                Button(
                    onClick = { onAction(HomeAction.Connect(username)) },
                    enabled = username.isNotBlank(),
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 48.dp)
                            .testTag("connect-button"),
                ) {
                    Text(tr(R.string.connect))
                }
            } else
                OutlinedButton(
                    onClick = { onAction(HomeAction.Disconnect) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (state.connection is LiveSessionState.Connected) tr(R.string.disconnect)
                        else tr(R.string.cancel_connection)
                    )
                }
        }
        if (state.connection.creator() != null || state.stats.events > 0) {
            item {
                HorizontalDivider()
                Row {
                    StatItem(localizedCompact(state.stats.events), tr(R.string.stat_events), Modifier.weight(1f))
                    StatItem(localizedCompact(state.stats.gifts), tr(R.string.stat_gifts), Modifier.weight(1f))
                    StatItem(localizedCompact(state.stats.likes), tr(R.string.stat_likes), Modifier.weight(1f))
                }
                Row {
                    StatItem(localizedCompact(state.stats.followers), tr(R.string.stat_followers), Modifier.weight(1f))
                    StatItem(
                        localizedAmount(state.stats.points),
                        state.preferences.rewards.currencyName,
                        Modifier.weight(2f),
                    )
                }
                HorizontalDivider()
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f)
                        .clickable { onAction(HomeAction.Speech) }
                        .padding(vertical = 12.dp)
                ) {
                    Text(tr(R.string.speech_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (state.preferences.engine) {
                            TtsEngine.OFF -> tr(R.string.speech_off)
                            TtsEngine.DEVICE -> tr(R.string.speech_device_voice)
                            TtsEngine.SUPERTONIC -> tr(R.string.speech_engine_super)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onAction(HomeAction.ToggleTts) }) {
                    Icon(
                        if (state.preferences.engine == TtsEngine.OFF) Icons.Outlined.VolumeOff
                        else Icons.Outlined.VolumeUp,
                        tr(R.string.speech_toggle),
                    )
                }
                IconButton(onClick = { onAction(HomeAction.Speech) }) {
                    Icon(Icons.Outlined.Tune, tr(R.string.speech_controls))
                }
            }
        }
        if (state.recent.isNotEmpty()) {
            item { SectionHeader(tr(R.string.recent_activity)) }
            items(state.recent) { EventRow(it, state.preferences.display) }
        }
        if (!state.connection.isActive) {
            if (state.preferences.recentCreators.isNotEmpty()) {
                item { SectionHeader(tr(R.string.recently_watched)) }
                items(state.preferences.recentCreators) { id ->
                    SettingItem("@$id", tr(R.string.connect_check_live)) {
                        onAction(HomeAction.Connect(id))
                    }
                }
            }
            item {
                SectionHeader(tr(R.string.live_now)) {
                    Row {
                        IconButton(
                            onClick = { onAction(HomeAction.Random) },
                            enabled = !state.feed.loading,
                        ) {
                            Icon(Icons.Outlined.Shuffle, tr(R.string.connect_random_creator))
                        }
                        IconButton(
                            onClick = { onAction(HomeAction.Refresh) },
                            enabled = !state.feed.loading,
                        ) {
                            Icon(Icons.Outlined.Refresh, tr(R.string.refresh_live_creators))
                        }
                    }
                }
            }
            if (state.feed.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.feed.error != null) item { ErrorBanner(state.feed.error) }
            if (!state.feed.loading && state.feed.rooms.isEmpty())
                item { EmptyState(tr(R.string.no_live_creators), tr(R.string.try_refresh_feed)) }
            items(state.feed.rooms, key = { it.uniqueId }) { room ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onAction(HomeAction.SelectCreator(room)) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (room.coverUrl.isNotBlank())
                        AsyncImage(
                            room.coverUrl,
                            null,
                            Modifier.size(72.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    else CreatorAvatar(room.avatarUrl, room.uniqueId, 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text("@${room.uniqueId}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            room.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            trPlural(
                                R.plurals.event_viewers,
                                room.viewers.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                localizedCompact(room.viewers),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
                HorizontalDivider()
            }
        }
    }
}
