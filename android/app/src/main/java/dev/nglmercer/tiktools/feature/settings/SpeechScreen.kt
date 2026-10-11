package dev.nglmercer.tiktools.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nglmercer.tiktools.R
import dev.nglmercer.tiktools.data.preferences.*
import dev.nglmercer.tiktools.tts.TtsEngine
import dev.nglmercer.tiktools.ui.components.*
import kotlinx.coroutines.delay

@Composable
fun SpeechControls(
    prefs: UserPreferences,
    onEngine: (TtsEngine) -> Unit,
    onSpeech: (SpeechPreferences) -> Unit,
    onRepeat: () -> Unit,
    onSkip: () -> Unit,
    canRepeat: Boolean,
) {
    for (engine in TtsEngine.entries) Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onEngine(engine) },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(prefs.engine == engine, onClick = { onEngine(engine) })
        Text(
            when (engine) {
                TtsEngine.OFF -> tr(R.string.speech_off)
                TtsEngine.DEVICE -> tr(R.string.speech_device_voice)
                TtsEngine.SUPERTONIC -> tr(R.string.speech_engine_super)
            }
        )
    }
    if (prefs.engine != TtsEngine.OFF)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRepeat, enabled = canRepeat) {
                Text(tr(R.string.repeat_speech))
            }
            OutlinedButton(onClick = onSkip) { Text(tr(R.string.skip_speech)) }
        }
    SectionHeader(tr(R.string.speech_announce))
    val p = prefs.speech
    var chatPrefix by rememberSaveable { mutableStateOf(p.chatPrefix) }
    val currentSpeech by rememberUpdatedState(p)
    LaunchedEffect(chatPrefix) {
        delay(300)
        if (chatPrefix != currentSpeech.chatPrefix)
            onSpeech(currentSpeech.copy(chatPrefix = chatPrefix))
    }
    SettingSwitch(tr(R.string.speech_chat), p.chat, { onSpeech(p.copy(chat = it)) })
    SettingSwitch(tr(R.string.speech_gifts), p.gifts, { onSpeech(p.copy(gifts = it)) })
    SettingSwitch(tr(R.string.speech_follows), p.follows, { onSpeech(p.copy(follows = it)) })
    SettingSwitch(tr(R.string.speech_shares), p.shares, { onSpeech(p.copy(shares = it)) })
    SettingSwitch(tr(R.string.speech_joins), p.joins, { onSpeech(p.copy(joins = it)) })
    SectionHeader(tr(R.string.speech_chat_name_mode))
    for (mode in ChatNameMode.entries) {
        val label =
            tr(
                when (mode) {
                    ChatNameMode.ALWAYS -> R.string.speech_chat_name_always
                    ChatNameMode.WHEN_CHANGED -> R.string.speech_chat_name_changed
                    ChatNameMode.NEVER -> R.string.speech_chat_name_never
                }
            )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                onSpeech(p.copy(chatNameMode = mode))
            },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(p.chatNameMode == mode, { onSpeech(p.copy(chatNameMode = mode)) })
            Text(label)
        }
    }
    if (p.chatNameMode == ChatNameMode.WHEN_CHANGED)
        Text(
            tr(R.string.speech_chat_name_changed_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    OutlinedTextField(
        value = chatPrefix,
        onValueChange = { chatPrefix = it.take(40) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(tr(R.string.speech_chat_prefix)) },
        placeholder = { Text(tr(R.string.speech_chat_prefix_placeholder)) },
        supportingText = { Text(tr(R.string.speech_chat_prefix_hint)) },
        singleLine = true,
    )
}

@Composable
fun SpeechScreen(vm: SettingsViewModel) {
    val prefs = vm.preferences.collectAsStateWithLifecycle().value
    val model = vm.models.collectAsStateWithLifecycle().value
    val last = vm.lastSpoken.collectAsStateWithLifecycle().value
    val error = vm.error.collectAsStateWithLifecycle().value
    var deleting by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(tr(R.string.speech_title), style = MaterialTheme.typography.headlineMedium)
            ErrorBanner(error, vm::dismissError)
        }
        item { SpeechControls(prefs, vm::engine, vm::speech, vm::repeat, vm::skip, last != null) }
        item {
            HorizontalDivider()
            SectionHeader(tr(R.string.speech_engine_super))
            Text(tr(R.string.speech_offline_quality), style = MaterialTheme.typography.titleMedium)
            Text(
                tr(R.string.speech_model_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(model.detail)
            if (model.bytes > 0)
                Text(
                    tr(R.string.speech_model_bytes, localizedAmount(model.bytes / 1048576.0)),
                    style = MaterialTheme.typography.bodySmall,
                )
            ErrorBanner(model.error)
        }
        item {
            when {
                model.downloading -> {
                    LinearProgressIndicator(
                        progress = { model.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${((model.progress ?: 0f)*100).toInt()}%")
                    TextButton(onClick = vm::cancelDownload) {
                        Text(tr(R.string.speech_cancel_download))
                    }
                }
                model.ready ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = vm::testVoice,
                            enabled = prefs.engine != TtsEngine.OFF,
                        ) {
                            Text(tr(R.string.speech_test_voice))
                        }
                        TextButton(onClick = { deleting = true }) {
                            Text(tr(R.string.speech_delete_model))
                        }
                    }
                else -> Button(onClick = vm::download) { Text(tr(R.string.supertonic_download)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(tr(R.string.speech_delete_title)) },
            text = { Text(tr(R.string.speech_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteModel()
                        deleting = false
                    }
                ) {
                    Text(tr(R.string.actions_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(tr(R.string.cancel)) }
            },
        )
}
