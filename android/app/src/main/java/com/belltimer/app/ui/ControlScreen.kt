package com.belltimer.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.belltimer.app.AppViewModel
import com.belltimer.app.ConnectionState
import com.belltimer.app.R
import com.belltimer.app.UiState

@Composable
fun ControlScreen(state: UiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val online = state.connection == ConnectionState.Online
    val status = state.status

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val deviceDefault = state.deviceRingDefault
        AnimatedVisibility(
            visible = online && deviceDefault != null && state.ringSeconds != deviceDefault,
        ) {
            SyncBanner(
                text = stringResource(R.string.control_duration_changed),
                onSync = {
                    viewModel.saveSettings(
                        state.settings.baseUrl,
                        state.settings.token,
                        state.ringSeconds,
                    )
                },
                enabled = !state.busy,
            )
        }

        ConnectionCard(state = state, onRefresh = viewModel::refreshNow)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.device_time),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status?.time != null) {
                    Text(
                        text = status.time.deviceDisplay(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.device_time_unknown),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.mode_label) + ": " + stringResource(
                        if (status?.isAuto == true) R.string.mode_auto else R.string.mode_manual,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.status_entries, status?.entries ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val autoWindow = state.schedule.auto
                if (autoWindow.isSet) {
                    val start = autoWindow.start
                    val stop = autoWindow.stop
                    val period = when {
                        start != null && stop != null -> stringResource(
                            R.string.control_auto_period_both,
                            start.displayDate(),
                            stop.displayDate(),
                        )
                        start != null -> stringResource(
                            R.string.control_auto_period_from,
                            start.displayDate(),
                        )
                        else -> stringResource(
                            R.string.control_auto_period_until,
                            stop?.displayDate().orEmpty(),
                        )
                    }
                    Text(
                        text = period,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mode_toggle),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            if (status?.isAuto == true) R.string.mode_auto else R.string.mode_manual,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = status?.isAuto == true,
                    onCheckedChange = viewModel::setMode,
                    enabled = online && !state.busy,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.ring_duration),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.duration_seconds, state.ringSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                RingDurationPicker(
                    seconds = state.ringSeconds,
                    onSecondsChange = viewModel::setRingSeconds,
                )
                if (status?.ringing == true) {
                    Text(
                        text = stringResource(R.string.ringing_left, status.ringLeft),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = { viewModel.ring(state.ringSeconds) },
                    enabled = online && !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ring_button),
                        fontSize = 18.sp,
                    )
                }
                if (status?.ringing == true) {
                    Button(
                        onClick = viewModel::stopRing,
                        enabled = online && !state.busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.stop_button),
                            fontSize = 18.sp,
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.errors_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                val errors = status?.errors.orEmpty()
                if (errors.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.errors_none))
                    }
                } else {
                    errors.forEach { code ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = deviceErrorLabel(code),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = viewModel::setTimeFromPhone,
            enabled = online && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Sync, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.set_time_button))
        }

        if (!online) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wifi, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.wifi_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        text = stringResource(R.string.wifi_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_wifi_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: UiState, onRefresh: () -> Unit) {
    val color = when (state.connection) {
        ConnectionState.Online -> Color(0xFF4CAF50)
        ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
        ConnectionState.Offline -> MaterialTheme.colorScheme.error
    }
    val label = when (state.connection) {
        ConnectionState.Online -> stringResource(R.string.connection_online)
        ConnectionState.Connecting -> stringResource(R.string.connection_connecting)
        ConnectionState.Offline -> stringResource(R.string.connection_offline)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh_desc),
                    )
                }
            }
            Text(
                text = state.settings.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.connection == ConnectionState.Offline) {
                state.lastError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.device_offline_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onRefresh) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
fun deviceErrorLabel(code: String): String = when (code) {
    "rtc_not_found" -> stringResource(R.string.error_rtc_not_found)
    "time_not_set" -> stringResource(R.string.error_time_not_set)
    "storage_error" -> stringResource(R.string.error_storage)
    "wifi_ap_error" -> stringResource(R.string.error_wifi_ap)
    else -> stringResource(R.string.error_unknown_device, code)
}
