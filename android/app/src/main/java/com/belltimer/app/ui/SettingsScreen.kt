package com.belltimer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.MAX_RING_SECONDS
import com.belltimer.app.data.MIN_RING_SECONDS
import kotlin.math.roundToInt

/** The slider covers the usual durations; longer ones are typed in. */
private const val SLIDER_MAX_SECONDS = 10

@Composable
fun SettingsScreen(state: UiState, viewModel: AppViewModel) {
    val settings = state.settings
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var token by rememberSaveable(settings.token) { mutableStateOf(settings.token) }
    var autoSync by rememberSaveable(settings.autoSyncTime) { mutableStateOf(settings.autoSyncTime) }
    var sliderSeconds by rememberSaveable(settings.ringDefault) {
        mutableIntStateOf(settings.ringDefault.coerceAtMost(SLIDER_MAX_SECONDS))
    }
    var custom by rememberSaveable(settings.ringDefault) { mutableStateOf(settings.ringDefault > SLIDER_MAX_SECONDS) }
    var customText by rememberSaveable(settings.ringDefault) { mutableStateOf(settings.ringDefault.toString()) }
    var confirmReset by remember { mutableStateOf(false) }

    val customSeconds = customText.toIntOrNull()?.takeIf { it in MIN_RING_SECONDS..MAX_RING_SECONDS }
    val ringSeconds = if (custom) customSeconds else sliderSeconds
    val changed = baseUrl != settings.baseUrl || token != settings.token ||
        ringSeconds != settings.ringDefault || autoSync != settings.autoSyncTime

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        AppCard {
            Text(stringResource(R.string.settings_ring_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_ring_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ringSeconds?.let { stringResource(R.string.seconds_value, it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium.merge(Tabular),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                QuickChip(stringResource(R.string.settings_ring_custom), custom) {
                    custom = !custom
                    if (custom) customText = sliderSeconds.toString()
                }
            }
            if (custom) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { value -> customText = value.filter { it.isDigit() }.take(2) },
                    label = { Text(stringResource(R.string.settings_ring_custom_label)) },
                    suffix = { Text(stringResource(R.string.seconds_suffix)) },
                    isError = customSeconds == null,
                    supportingText = {
                        Text(stringResource(R.string.settings_ring_custom_range, MIN_RING_SECONDS, MAX_RING_SECONDS))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            } else {
                Slider(
                    value = sliderSeconds.toFloat(),
                    onValueChange = { sliderSeconds = it.roundToInt() },
                    valueRange = MIN_RING_SECONDS.toFloat()..SLIDER_MAX_SECONDS.toFloat(),
                    steps = SLIDER_MAX_SECONDS - MIN_RING_SECONDS - 1,
                )
                Row {
                    Caption("$MIN_RING_SECONDS", modifier = Modifier.weight(1f))
                    Caption("$SLIDER_MAX_SECONDS")
                }
            }
            state.status?.let {
                Spacer(Modifier.height(4.dp))
                Caption(stringResource(R.string.settings_ring_device, it.ringDefault))
            }
        }

        AppCard {
            Text(stringResource(R.string.settings_time_title), style = MaterialTheme.typography.titleMedium)
            SettingRow(
                title = stringResource(R.string.settings_auto_sync),
                subtitle = stringResource(R.string.settings_auto_sync_hint),
                onClick = { autoSync = !autoSync },
                trailing = { Switch(checked = autoSync, onCheckedChange = { autoSync = it }) },
            )
        }

        AppCard {
            Text(stringResource(R.string.settings_connection_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.settings_base_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.settings_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(
            onClick = { ringSeconds?.let { viewModel.saveSettings(baseUrl, token, it, autoSync) } },
            enabled = changed && ringSeconds != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) { Text(stringResource(R.string.save)) }

        AppCard {
            Text(stringResource(R.string.settings_about_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val status = state.status
            AboutLine(stringResource(R.string.settings_app_version), appVersionName())
            AboutLine(
                stringResource(R.string.settings_fw_version),
                status?.let { "${it.fw} · API ${it.api}" } ?: stringResource(R.string.unknown),
            )
            status?.let {
                AboutLine(stringResource(R.string.settings_modules), it.modules.toString())
            }
        }

        AppCard {
            Text(stringResource(R.string.reset_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.reset_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { confirmReset = true },
                enabled = state.online && !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reset_button))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.reset_confirm_title),
            text = stringResource(R.string.reset_confirm_text),
            confirm = stringResource(R.string.reset_confirm),
            onConfirm = viewModel::factoryReset,
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun AboutLine(title: String, value: String) {
    SettingRow(
        title = title,
        trailing = {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
