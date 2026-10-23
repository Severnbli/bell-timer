package com.belltimer.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.ConnectionState
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.UpcomingBell
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Composable
fun ControlScreen(state: UiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val online = state.connection == ConnectionState.Online
    val status = state.status
    val ringing = status?.ringing == true
    val deviceTime = status?.time
    val next = remember(state.schedule, deviceTime) {
        deviceTime?.let { state.schedule.nextBell(it) }
    }
    val clockOff = !online && status == null ||
        status?.rtcOk == false ||
        status?.time == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.device_time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = deviceTime?.deviceClock()
                        ?: stringResource(R.string.device_time_unknown),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            ConnectionIndicator(state.connection)
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = status?.isAuto == true,
                onClick = { viewModel.setMode(true) },
                enabled = online && !state.busy,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.mode_auto))
            }
            SegmentedButton(
                selected = status?.isAuto == false,
                onClick = { viewModel.setMode(false) },
                enabled = online && !state.busy,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.mode_manual))
            }
        }

        if (status?.isAuto == true) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.next_bell_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = nextBellText(next, deviceTime),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                    ),
                )
                next?.entry?.label?.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (ringing) {
            Text(
                text = stringResource(R.string.ringing_left, status?.ringLeft ?: 0),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(
            onClick = {
                if (ringing) {
                    viewModel.stopRing()
                } else {
                    viewModel.ring(state.ringSeconds)
                }
            },
            enabled = online && !state.busy,
            colors = if (ringing) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text(
                text = stringResource(
                    if (ringing) R.string.stop_button else R.string.ring_button,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        val errorLine = state.lastError ?: status?.errors?.firstOrNull()?.let { deviceErrorLabel(it) }
        if (errorLine != null) {
            Text(
                text = errorLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (clockOff && online) {
            TextButton(onClick = viewModel::setTimeFromPhone, enabled = !state.busy) {
                Text(stringResource(R.string.time_sync_button))
            }
        }

        if (!online) {
            Text(
                text = stringResource(R.string.device_offline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                },
            ) {
                Text(stringResource(R.string.wifi_settings_button))
            }
        }
        Spacer(Modifier.heightIn(min = 8.dp))
    }
}

@Composable
private fun ConnectionIndicator(connection: ConnectionState) {
    val color = when (connection) {
        ConnectionState.Online -> Color(0xFF2E7D32)
        ConnectionState.Connecting -> MaterialTheme.colorScheme.primary
        ConnectionState.Offline -> MaterialTheme.colorScheme.error
    }
    val label = when (connection) {
        ConnectionState.Online -> stringResource(R.string.connection_online)
        ConnectionState.Connecting -> stringResource(R.string.connection_connecting)
        ConnectionState.Offline -> stringResource(R.string.connection_offline)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun nextBellText(next: UpcomingBell?, deviceTime: LocalDateTime?): String {
    if (next == null) return stringResource(R.string.next_bell_none)
    val time = next.at.toLocalTime().toString().take(5)
    if (deviceTime == null) return time
    val minutes = ChronoUnit.MINUTES.between(deviceTime, next.at)
    return if (minutes <= 0) {
        stringResource(R.string.next_bell_now, time)
    } else {
        stringResource(R.string.next_bell_in, time, minutes)
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
