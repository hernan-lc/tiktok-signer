package dev.nglmercer.tiktools.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.nglmercer.tiktools.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nglmercer.tiktools.data.preferences.ThemeMode
import dev.nglmercer.tiktools.tts.TtsEngine
import dev.nglmercer.tiktools.ui.components.*

@Composable
fun SettingsScreen(vm: SettingsViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val p = vm.preferences.collectAsStateWithLifecycle().value
    val diagnostics = vm.diagnostics.collectAsStateWithLifecycle().value
    val error = vm.error.collectAsStateWithLifecycle().value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(tr(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            ErrorBanner(error, vm::dismissError)
        }
        item {
            SectionHeader(tr(R.string.settings_live))
            SettingSwitch(
                tr(R.string.automatic_reconnect),
                p.reconnect,
                vm::reconnect,
                tr(R.string.native_retry_policy),
            )
            SettingSwitch(
                tr(R.string.background_connection),
                p.backgroundConnection,
                vm::background,
                tr(R.string.background_connection_detail),
            )
            HorizontalDivider()
        }
        item {
            SectionHeader(tr(R.string.settings_speech))
            SettingItem(
                tr(R.string.settings_engine),
                when (p.engine) {
                    TtsEngine.OFF -> tr(R.string.speech_off)
                    TtsEngine.DEVICE -> tr(R.string.speech_device_voice)
                    TtsEngine.SUPERTONIC -> tr(R.string.speech_engine_super)
                },
            ) {
                navigate("speech")
            }
            HorizontalDivider()
        }
        item {
            SectionHeader(tr(R.string.appearance))
            for (mode in ThemeMode.entries) Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                RadioButton(p.theme == mode, { vm.theme(mode) })
                Text(
                    tr(
                        when (mode) {
                            ThemeMode.SYSTEM -> R.string.theme_system
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                        }
                    )
                )
            }
            SettingItem(
                tr(R.string.app_language),
                tr(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        R.string.app_language_system_settings
                    else R.string.app_language_follows_device
                ),
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }
            }
            HorizontalDivider()
        }
        item {
            SectionHeader(tr(R.string.settings_session))
            SettingItem(tr(R.string.guest_session), diagnostics.guest)
            TextButton(onClick = vm::resetSession) { Text(tr(R.string.reset_guest_session)) }
            HorizontalDivider()
        }
        item {
            SectionHeader(tr(R.string.settings_signer_section))
            SettingItem(tr(R.string.settings_engine), diagnostics.native)
            SettingItem(tr(R.string.settings_bundle_label), diagnostics.bundle)
            TextButton(onClick = vm::redownloadBundle) { Text(tr(R.string.redownload_bundle)) }
            HorizontalDivider()
        }
        item {
            SectionHeader(tr(R.string.settings_storage))
            TextButton(onClick = vm::clearBundle) { Text(tr(R.string.clear_cached_bundle)) }
            TextButton(onClick = { navigate("speech") }) {
                Text(tr(R.string.manage_offline_speech))
            }
            HorizontalDivider()
        }
        item {
            SectionHeader(tr(R.string.settings_developer))
            SettingItem(tr(R.string.diagnostics), tr(R.string.diagnostics_summary)) {
                navigate("diagnostics")
            }
            HorizontalDivider()
        }
        item {
            SectionHeader(tr(R.string.settings_about))
            SettingItem(tr(R.string.about_product_name), tr(R.string.about_version))
            Spacer(Modifier.height(24.dp))
        }
    }
}
