package com.belltimer.app.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import com.belltimer.app.AppLocale
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalUriHandler
import com.belltimer.app.APP_VERSION
import com.belltimer.app.AppViewModel
import com.belltimer.app.InstallPhase
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.DeviceConfigDto
import com.belltimer.app.data.MAX_RING_SECONDS
import com.belltimer.app.data.MIN_RING_SECONDS
import com.belltimer.app.data.isNewer
import com.belltimer.app.data.meetsMinimum
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

    LaunchedEffect(state.online, state.status?.api) {
        if (state.online) {
            viewModel.loadNetwork()
            viewModel.loadDevice()
        }
    }

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

        if (state.online && state.network != null) {
            NetworkCard(state = state, onSave = viewModel::saveNetwork)
        }

        UpdatesCard(state = state, viewModel = viewModel)

        if (state.online && state.device != null) {
            DeviceCard(device = state.device, busy = state.busy, onSave = viewModel::saveDevice)
        }

        LanguageCard()

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
        ResetDialog(
            onConfirm = viewModel::factoryReset,
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun ResetDialog(onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var network by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.reset_confirm_text))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable { network = !network },
                ) {
                    Checkbox(checked = network, onCheckedChange = { network = it })
                    Text(stringResource(R.string.reset_network), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm(network)
            }) { Text(stringResource(R.string.reset_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Access point name, password and API token stored on the device. */
@Composable
private fun NetworkCard(state: UiState, onSave: (String, String?, String?, Boolean?) -> Unit) {
    val network = state.network ?: return
    var ssid by rememberSaveable(network.ssid) { mutableStateOf(network.ssid) }
    var password by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable(network.open) { mutableStateOf(network.open) }
    var showPassword by remember { mutableStateOf(false) }
    var token by rememberSaveable { mutableStateOf("") }
    var noToken by rememberSaveable(network.tokenSet) { mutableStateOf(!network.tokenSet) }
    var localOnly by rememberSaveable(network.localOnly) { mutableStateOf(network.localOnly == true) }
    var confirm by remember { mutableStateOf(false) }

    val ssidError = ssid.isBlank() || ssid.toByteArray(Charsets.UTF_8).size > 32
    val passwordError = !open && password.isNotEmpty() && password.length !in 8..63
    // Switching from an open network to a protected one needs a password.
    val passwordMissing = !open && network.open && password.isEmpty()
    // Turning the token on needs a value.
    val tokenMissing = !noToken && !network.tokenSet && token.isEmpty()
    val tokenError = !noToken && token.isNotEmpty() && (token.length > 64 || token.any { it.code !in 0x21..0x7E })
    val newPassword = when {
        open -> "".takeIf { !network.open }
        password.isNotEmpty() -> password
        else -> null
    }
    val newToken = when {
        noToken -> "".takeIf { network.tokenSet }
        token.isNotEmpty() -> token
        else -> null
    }
    val ssidChanged = ssid.trim() != network.ssid
    val newLocalOnly = localOnly.takeIf { network.localOnly != null && it != network.localOnly }
    val changed = ssidChanged || newPassword != null || newToken != null || newLocalOnly != null
    val valid = !ssidError && !passwordError && !passwordMissing && !tokenError && !tokenMissing

    AppCard {
        Text(stringResource(R.string.network_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                R.string.network_current,
                network.ssid,
                stringResource(if (network.open) R.string.network_state_open else R.string.network_state_protected),
                stringResource(if (network.tokenSet) R.string.network_state_token else R.string.network_state_no_token),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text(stringResource(R.string.network_ssid)) },
            singleLine = true,
            isError = ssidError,
            supportingText = if (ssidError) {
                { Text(stringResource(R.string.network_invalid_ssid)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SettingRow(
            title = stringResource(R.string.network_open),
            subtitle = stringResource(R.string.network_open_hint),
            onClick = { open = !open },
            trailing = { Switch(checked = open, onCheckedChange = { open = it }) },
        )
        if (!open) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.network_password)) },
                placeholder = {
                    Text(stringResource(if (network.open) R.string.network_password_required else R.string.network_keep_current))
                },
                singleLine = true,
                isError = passwordError || passwordMissing,
                supportingText = { Text(stringResource(R.string.network_invalid_password)) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.network_show_password),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (network.localOnly != null) {
            ThinDivider()
            SettingRow(
                title = stringResource(R.string.network_local_only),
                subtitle = stringResource(
                    if (network.localOnlyTrial) R.string.network_local_only_trial else R.string.network_local_only_hint,
                ),
                onClick = { localOnly = !localOnly },
                trailing = { Switch(checked = localOnly, onCheckedChange = { localOnly = it }) },
            )
        }
        ThinDivider()
        SettingRow(
            title = stringResource(R.string.network_no_token),
            subtitle = stringResource(R.string.network_no_token_hint),
            onClick = { noToken = !noToken },
            trailing = { Switch(checked = noToken, onCheckedChange = { noToken = it }) },
        )
        if (!noToken) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.network_token)) },
                placeholder = {
                    Text(stringResource(if (network.tokenSet) R.string.network_keep_current else R.string.network_token_required))
                },
                singleLine = true,
                isError = tokenError || tokenMissing,
                supportingText = { Text(stringResource(R.string.network_invalid_token)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { confirm = true },
            enabled = changed && valid && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.network_apply)) }
    }

    if (confirm) {
        val restart = ssidChanged || newPassword != null || newLocalOnly != null
        ConfirmDialog(
            title = stringResource(R.string.network_confirm_title),
            text = when {
                newLocalOnly != null -> stringResource(R.string.network_confirm_local_only, ssid.trim())
                restart -> stringResource(R.string.network_confirm_restart, ssid.trim())
                else -> stringResource(R.string.network_confirm_token)
            },
            confirm = stringResource(R.string.network_apply),
            onConfirm = {
                onSave(ssid.trim(), newPassword, newToken, newLocalOnly)
                password = ""
                token = ""
            },
            onDismiss = { confirm = false },
        )
    }
}

/** New app and firmware releases, and installing firmware on the device. */
@Composable
private fun UpdatesCard(state: UiState, viewModel: AppViewModel) {
    val uriHandler = LocalUriHandler.current
    val updates = state.updates
    val compatibility = state.compatibility
    val status = state.status
    var confirmInstall by remember { mutableStateOf(false) }

    AppCard {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)

        // App
        AboutLine(
            stringResource(R.string.settings_app_version),
            updates.app?.let {
                if (compatibility.appUpdate != null) {
                    stringResource(R.string.update_version_arrow, APP_VERSION, it.version)
                } else {
                    stringResource(R.string.update_version_current, APP_VERSION)
                }
            } ?: APP_VERSION,
        )
        compatibility.appUpdate?.let { app ->
            compatibility.appUpdateNeedsFirmware?.let {
                UpdateNote(stringResource(R.string.update_app_needs_firmware, it), error = true)
            }
            OutlinedButton(onClick = { uriHandler.openUri(app.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_open_app_page, app.version))
            }
        }
        ThinDivider()

        // Firmware
        val deviceFw = status?.fw?.takeIf { it.isNotBlank() }
        val latestFw = updates.firmware
        AboutLine(
            stringResource(R.string.settings_fw_version),
            when {
                deviceFw != null && latestFw != null && isNewer(latestFw.version, deviceFw) ->
                    stringResource(R.string.update_version_arrow, deviceFw, latestFw.version)
                deviceFw != null -> deviceFw
                latestFw != null -> stringResource(R.string.update_latest, latestFw.version)
                else -> stringResource(R.string.unknown)
            },
        )
        val stored = updates.stored?.takeIf { deviceFw == null || isNewer(it.release.version, deviceFw) }
        // The firmware the card offers: one already on the phone, else the newest release.
        val candidate = stored?.release ?: latestFw?.takeIf { deviceFw == null || isNewer(it.version, deviceFw) }
        val needsApp = candidate?.minApp?.takeIf { !meetsMinimum(APP_VERSION, it) }
        when {
            updates.installPhase != InstallPhase.Idle -> {
                Text(
                    stringResource(
                        if (updates.installPhase == InstallPhase.Uploading) R.string.update_uploading else R.string.update_restarting,
                        updates.installTarget.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (updates.installPhase == InstallPhase.Uploading) {
                    LinearProgressIndicator(progress = { updates.installProgress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            needsApp != null -> UpdateNote(stringResource(R.string.update_firmware_needs_app, needsApp), error = true)
            stored != null -> {
                when {
                    status == null || !state.online -> UpdateNote(stringResource(R.string.update_ready_connect, stored.release.version))
                    !status.supportsUpdates -> UpdateNote(stringResource(R.string.update_needs_usb), error = true)
                    state.network?.tokenSet == false -> UpdateNote(stringResource(R.string.update_error_token_required), error = true)
                }
                Button(
                    onClick = { confirmInstall = true },
                    enabled = state.online && status?.supportsUpdates == true && state.network?.tokenSet != false && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.update_install, stored.release.version))
                }
            }
            latestFw != null && (deviceFw == null || isNewer(latestFw.version, deviceFw)) -> {
                val progress = updates.downloadProgress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedButton(onClick = viewModel::downloadFirmware, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_download, latestFw.version))
                    }
                }
            }
        }
        ThinDivider()

        updates.error?.let { UpdateNote(it, error = true) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    when {
                        updates.checking -> R.string.update_checking
                        updates.checked -> R.string.update_checked
                        else -> R.string.update_not_checked
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.checkForUpdates(userInitiated = true) }, enabled = !updates.checking) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.update_check))
            }
        }
    }

    if (confirmInstall) {
        val version = updates.stored?.release?.version.orEmpty()
        ConfirmDialog(
            title = stringResource(R.string.update_confirm_title, version),
            text = stringResource(R.string.update_confirm_text),
            confirm = stringResource(R.string.update_install_short),
            onConfirm = viewModel::installFirmware,
            onDismiss = { confirmInstall = false },
        )
    }
}

@Composable
private fun UpdateNote(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** GPIO wiring of the relay, the LED and the clock, stored on the device. */
@Composable
private fun DeviceCard(device: DeviceConfigDto, busy: Boolean, onSave: (DeviceConfigDto) -> Unit) {
    var relay by rememberSaveable(device) { mutableStateOf(device.relay.toString()) }
    var green by rememberSaveable(device) { mutableStateOf(device.ledGreen.toString()) }
    var red by rememberSaveable(device) { mutableStateOf(device.ledRed.toString()) }
    var sda by rememberSaveable(device) { mutableStateOf(device.sda.toString()) }
    var scl by rememberSaveable(device) { mutableStateOf(device.scl.toString()) }
    var activeLow by rememberSaveable(device) { mutableStateOf(device.ledActiveLow) }
    var confirm by remember { mutableStateOf(false) }

    val edited = DeviceConfigDto(
        relay = relay.toIntOrNull() ?: -1,
        ledGreen = green.toIntOrNull() ?: -1,
        ledRed = red.toIntOrNull() ?: -1,
        sda = sda.toIntOrNull() ?: -1,
        scl = scl.toIntOrNull() ?: -1,
        ledActiveLow = activeLow,
    )
    val changed = edited != device.copy(error = false)

    AppCard {
        Text(stringResource(R.string.device_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.device_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (device.error) UpdateNote(stringResource(R.string.device_stored_error), error = true)
        Spacer(Modifier.height(8.dp))
        val fields = listOf(
            Triple(R.string.device_relay, relay) { v: String -> relay = v },
            Triple(R.string.device_led_green, green) { v: String -> green = v },
            Triple(R.string.device_led_red, red) { v: String -> red = v },
            Triple(R.string.device_sda, sda) { v: String -> sda = v },
            Triple(R.string.device_scl, scl) { v: String -> scl = v },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value, set) in fields) {
                val pin = value.toIntOrNull()
                OutlinedTextField(
                    value = value,
                    onValueChange = { text -> set(text.filter { it.isDigit() }.take(2)) },
                    label = { Text(stringResource(label)) },
                    prefix = { Text("GPIO") },
                    isError = pin == null || pin !in DeviceConfigDto.OUTPUT_PINS,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(150.dp),
                )
            }
        }
        SettingRow(
            title = stringResource(R.string.device_led_active_low),
            subtitle = stringResource(R.string.device_led_active_low_hint),
            onClick = { activeLow = !activeLow },
            trailing = { Switch(checked = activeLow, onCheckedChange = { activeLow = it }) },
        )
        if (!edited.isValid) UpdateNote(stringResource(R.string.device_invalid_pins), error = true)
        Button(
            onClick = { confirm = true },
            enabled = changed && edited.isValid && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.network_apply)) }
    }

    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.device_confirm_title),
            text = stringResource(R.string.device_confirm_text),
            confirm = stringResource(R.string.network_apply),
            onConfirm = { onSave(edited) },
            onDismiss = { confirm = false },
        )
    }
}

/** The app language: the system one or a fixed choice. */
@Composable
private fun LanguageCard() {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val current = remember { AppLocale.tag(context) }
    AppCard {
        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tag in AppLocale.SUPPORTED) {
                QuickChip(languageName(tag), tag == current) {
                    if (tag != current) {
                        AppLocale.save(context, tag)
                        activity?.recreate()
                    }
                }
            }
        }
    }
}

/** Each language is named in itself, so it can be found from any language. */
@Composable
private fun languageName(tag: String): String = when (tag) {
    "ru" -> "Русский"
    "be" -> "Беларуская"
    "en" -> "English"
    else -> stringResource(R.string.language_system)
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
