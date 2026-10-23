package com.belltimer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    val online = state.connection == ConnectionState.Online
    val status = state.status
    val ringing = status?.ringing == true
    val deviceTime = status?.time
    val next = remember(state.schedule, deviceTime) {
        deviceTime?.let { state.schedule.nextBell(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.control_mode_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                Text(
                    text = stringResource(
                        if (status?.isAuto == true) {
                            R.string.control_mode_auto_hint
                        } else {
                            R.string.control_mode_manual_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (status?.isAuto == true) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.next_bell_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = nextBellText(next, deviceTime),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                        ),
                        fontWeight = FontWeight.SemiBold,
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
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        .heightIn(min = 64.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (ringing) R.string.stop_button else R.string.ring_button,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (ringing) {
                    Text(
                        text = stringResource(R.string.ringing_left, status?.ringLeft ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        val errorLine = state.lastError ?: status?.errors?.firstOrNull()?.let { deviceErrorLabel(it) }
        if (errorLine != null) {
            Text(
                text = errorLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!online) {
            Text(
                text = stringResource(R.string.device_offline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.heightIn(min = 8.dp))
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
