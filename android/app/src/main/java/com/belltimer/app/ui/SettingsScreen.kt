package com.belltimer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

@Composable
fun SettingsScreen(state: UiState, viewModel: AppViewModel) {
    val settings = state.settings
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var token by rememberSaveable(settings.token) { mutableStateOf(settings.token) }
    var ring by rememberSaveable(settings.ringDefault) { mutableFloatStateOf(settings.ringDefault.toFloat()) }
    var autoSync by rememberSaveable(settings.autoSyncTime) { mutableStateOf(settings.autoSyncTime) }
    val ringSeconds = ring.roundToInt().coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS)
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
            Text(
                text = stringResource(R.string.seconds_value, ringSeconds),
                style = MaterialTheme.typography.headlineMedium.merge(Tabular),
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = ring,
                onValueChange = { ring = it },
                valueRange = MIN_RING_SECONDS.toFloat()..MAX_RING_SECONDS.toFloat(),
            )
            state.status?.let {
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
            onClick = { viewModel.saveSettings(baseUrl, token, ringSeconds, autoSync) },
            enabled = changed,
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
        Spacer(Modifier.height(8.dp))
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
