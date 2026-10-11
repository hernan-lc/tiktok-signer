package dev.nglmercer.tiktools.feature.events

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.nglmercer.tiktools.R
import dev.nglmercer.tiktools.core.model.*
import dev.nglmercer.tiktools.ui.components.*
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    state: EventsUiState,
    onFilter: (EventFilter) -> Unit,
    onDisplay: (EventDisplayConfig) -> Unit,
    onPause: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf(state.preferences.filter.query.isNotBlank()) }
    var menu by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var density by rememberSaveable { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<LiveEvent?>(null) }
    val filter = state.preferences.filter
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("events-screen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.events_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { search = !search }) {
                Icon(Icons.Outlined.Search, tr(R.string.events_search))
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, tr(R.string.events_more_options))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                tr(
                                    if (state.paused) R.string.events_resume
                                    else R.string.events_pause
                                )
                            )
                        },
                        onClick = {
                            menu = false
                            onPause(!state.paused)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr(R.string.events_display_density)) },
                        onClick = {
                            menu = false
                            density = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr(R.string.events_advanced_filters)) },
                        onClick = {
                            menu = false
                            advanced = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr(R.string.events_clear_history)) },
                        onClick = {
                            menu = false
                            clear = true
                        },
                    )
                }
            }
        }
        if (search)
            OutlinedTextField(
                filter.query,
                { onFilter(filter.copy(query = it)) },
                Modifier.fillMaxWidth(),
                label = { Text(tr(R.string.events_search_hint)) },
                singleLine = true,
            )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = filter.enabled.size == LiveEvent.Category.entries.size,
                    onClick = { onFilter(filter.selectAll()) },
                    label = { Text(tr(R.string.events_all)) },
                )
            }
            items(LiveEvent.Category.entries) { c ->
                FilterChip(
                    c in filter.enabled,
                    { onFilter(filter.copy(enabled = setOf(c))) },
                    label = { Text(c.localizedName()) },
                    leadingIcon = { Icon(c.icon(), null, Modifier.size(16.dp)) },
                )
            }
        }
        if (state.paused)
            Text(
                tr(R.string.events_paused_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        LazyColumn(Modifier.weight(1f)) {
            if (state.events.isEmpty())
                item {
                    EmptyState(
                        tr(R.string.events_no_match),
                        tr(R.string.events_no_match_hint),
                    )
                }
            items(state.events) { event ->
                EventRow(event, state.preferences.display) { detail = event }
                HorizontalDivider()
            }
        }
    }
    if (advanced)
        ModalBottomSheet(onDismissRequest = { advanced = false }) {
            Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                SectionHeader(tr(R.string.events_advanced_filters))
                Row {
                    TextButton(onClick = { onFilter(filter.selectAll()) }) {
                        Text(tr(R.string.select_all))
                    }
                    TextButton(onClick = { onFilter(filter.clearSelection()) }) {
                        Text(tr(R.string.clear_selection))
                    }
                }
                for (c in LiveEvent.Category.entries) SettingSwitch(
                    c.localizedName(),
                    c in filter.enabled,
                    { checked ->
                        onFilter(
                            filter.copy(
                                enabled = if (checked) filter.enabled + c else filter.enabled - c
                            )
                        )
                    },
                )
            }
        }
    if (density)
        ModalBottomSheet(onDismissRequest = { density = false }) {
            val d = state.preferences.display
            Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                SectionHeader(tr(R.string.display_density))
                SettingSwitch(
                    tr(R.string.display_compact_rows),
                    d.compact,
                    { onDisplay(d.copy(compact = it)) },
                )
                SettingSwitch(
                    tr(R.string.display_event_icons),
                    d.showBadge,
                    { onDisplay(d.copy(showBadge = it)) },
                )
                SettingSwitch(
                    tr(R.string.display_timestamps),
                    d.showTime,
                    { onDisplay(d.copy(showTime = it)) },
                )
                SettingSwitch(
                    tr(R.string.display_single_line),
                    d.singleLine,
                    { onDisplay(d.copy(singleLine = it)) },
                )
            }
        }
    if (clear)
        AlertDialog(
            onDismissRequest = { clear = false },
            title = { Text(tr(R.string.events_clear_confirm_title)) },
            text = {
                Text(tr(R.string.events_clear_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        clear = false
                    }
                ) {
                    Text(tr(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { clear = false }) { Text(tr(R.string.cancel)) }
            },
        )
    detail?.let { event ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item {
                    Text(
                        event.category.localizedName(),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    CreatorAvatar(event.avatarUrl, event.nickname)
                    Text(event.nickname.ifEmpty { event.user })
                    Text("@${event.user}")
                    Text(event.summary())
                    SettingItem(tr(R.string.event_received), localizedEventTime(event.at))
                    val noMessageId = tr(R.string.not_provided)
                    val messageId =
                        runCatching { JSONObject(event.raw).optString("msg_id", "") }
                            .getOrDefault("")
                            .ifBlank { noMessageId }
                    SettingItem(
                        tr(R.string.event_message_id),
                        messageId,
                    )
                    Button(onClick = { clipboard.setText(AnnotatedString(event.raw)) }) {
                        Text(tr(R.string.copy_json))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
