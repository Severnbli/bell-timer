package com.belltimer.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.ConnectionState
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.API_MODULES
import com.belltimer.app.data.BellRole
import com.belltimer.app.data.ModuleType
import com.belltimer.app.data.ResolvedBell
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Composable
fun NowScreen(state: UiState, viewModel: AppViewModel, onOpenSchedule: () -> Unit) {
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    val deviceNow = state.deviceNow(nowMs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state.connection)
        Banners(state)
        ClockCard(state, deviceNow, onSync = viewModel::syncTime)
        BellCard(state, deviceNow, onRing = viewModel::ring, onStop = viewModel::stopRing)
        ModeCard(state, onMode = viewModel::setMode)
        TodayCard(state, deviceNow, onOpenSchedule)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(connection: ConnectionState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        ConnectionPill(connection)
    }
}

@Composable
private fun ConnectionPill(connection: ConnectionState) {
    val (color, label) = when (connection) {
        ConnectionState.Online -> AppColors.extra.success to stringResource(R.string.connection_online)
        ConnectionState.Connecting -> AppColors.extra.warning to stringResource(R.string.connection_connecting)
        ConnectionState.Offline -> MaterialTheme.colorScheme.error to stringResource(R.string.connection_offline)
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (connection == ConnectionState.Connecting) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Surface(shape = CircleShape, color = color.copy(alpha = 0.12f)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun Banners(state: UiState) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    if (state.connection == ConnectionState.Offline) {
        Banner(
            icon = Icons.Default.WifiOff,
            text = state.lastError ?: stringResource(R.string.offline_hint),
            container = colors.errorContainer,
            content = colors.onErrorContainer,
            action = {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) { Text(stringResource(R.string.wifi_settings)) }
            },
        )
    }
    val status = state.status ?: return
    if (state.online) {
        for (code in status.errors) {
            Banner(
                icon = Icons.Default.WarningAmber,
                text = deviceErrorLabel(code),
                container = colors.errorContainer,
                content = colors.onErrorContainer,
            )
        }
        if (status.api < API_MODULES) {
            Banner(
                icon = Icons.Default.ErrorOutline,
                text = stringResource(R.string.issue_firmware_too_old),
                container = colors.errorContainer,
                content = colors.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ClockCard(state: UiState, deviceNow: LocalDateTime?, onSync: () -> Unit) {
    AppCard(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Caption(stringResource(R.string.clock_title))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = deviceNow?.clock() ?: "--:--",
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = deviceNow?.clockSeconds() ?: "--",
                style = MaterialTheme.typography.headlineMedium.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        Text(
            text = (deviceNow?.toLocalDate() ?: LocalDate.now()).longDay(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 14.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )

        val status = state.status
        val drift = state.driftSeconds
        val extra = AppColors.extra
        when {
            !state.online || status == null -> StatusLine(
                Icons.Default.Schedule,
                stringResource(R.string.clock_unknown),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !status.rtcOk -> StatusLine(
                Icons.Default.ErrorOutline,
                stringResource(R.string.device_error_rtc_not_found),
                MaterialTheme.colorScheme.error,
            )
            drift == null -> StatusLine(
                Icons.Default.ErrorOutline,
                stringResource(R.string.clock_not_set),
                MaterialTheme.colorScheme.error,
            )
            state.clockInSync -> StatusLine(
                Icons.Default.CheckCircle,
                stringResource(R.string.clock_in_sync),
                extra.success,
            )
            else -> StatusLine(
                Icons.Default.WarningAmber,
                stringResource(
                    if (drift > 0) R.string.clock_ahead else R.string.clock_behind,
                    driftText(abs(drift)),
                ),
                extra.warning,
            )
        }
        status?.lastSync?.let {
            Text(
                text = stringResource(R.string.clock_last_sync, it.shortDateTime()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp, top = 2.dp),
            )
        }
        Spacer(Modifier.height(14.dp))

        val spin = rememberInfiniteTransition(label = "sync")
        val angle by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "sync-angle",
        )
        val highlight = state.online && status?.rtcOk == true && !state.clockInSync
        Button(
            onClick = onSync,
            enabled = state.online && status?.rtcOk == true && !state.syncing,
            colors = if (highlight) {
                ButtonDefaults.buttonColors()
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (state.syncing) -angle else 0f),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (state.syncing) R.string.sync_running else R.string.sync_button))
        }
    }
}

@Composable
private fun BellCard(
    state: UiState,
    deviceNow: LocalDateTime?,
    onRing: () -> Unit,
    onStop: () -> Unit,
) {
    val status = state.status
    val ringing = status?.ringing == true
    val enabled = state.online && !state.busy
    val lesson = remember(state.schedule, deviceNow?.withSecond(0)?.withNano(0)) {
        deviceNow?.let { state.schedule.runningLesson(it) }
    }

    AppCard {
        if (status != null && !status.isAuto) {
            StatusLine(
                Icons.Default.TouchApp,
                stringResource(R.string.next_manual_mode),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (lesson != null && deviceNow != null) {
                val minuteOfDay = deviceNow.hour * 60 + deviceNow.minute + deviceNow.second / 60.0
                Caption(stringResource(R.string.lesson_running))
                Text(
                    text = lesson.module.label.ifBlank { stringResource(R.string.module_lesson) },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { lesson.progress(minuteOfDay) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.secondaryContainer,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.lesson_left,
                        durationText(((lesson.endsAt - minuteOfDay) + 0.999).toInt().coerceAtLeast(0)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
            }
            NextBellLine(state, deviceNow)
        }

        Spacer(Modifier.height(20.dp))
        RingButton(
            ringing = ringing,
            ringLeft = status?.ringLeft ?: 0,
            enabled = enabled,
            onClick = if (ringing) onStop else onRing,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (ringing) {
                stringResource(R.string.ring_tap_to_stop)
            } else {
                stringResource(R.string.ring_hint, state.settings.ringDefault)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun NextBellLine(state: UiState, deviceNow: LocalDateTime?) {
    // The device knows what it will ring; local edits that were not sent yet
    // must not change this line.
    val deviceNext = state.status?.next
    val localNext = remember(state.schedule, deviceNow?.withSecond(0)?.withNano(0)) {
        if (deviceNext == null && state.status?.api?.let { it < API_MODULES } == true) {
            deviceNow?.let { state.schedule.nextBell(it) }
        } else {
            null
        }
    }
    val at = deviceNext?.at ?: localNext?.at
    val label = deviceNext?.label ?: localNext?.bell?.module?.label
    val role = deviceNext?.role ?: localNext?.bell?.role
    val type = deviceNext?.type ?: localNext?.bell?.module?.type ?: ModuleType.Bell

    Caption(stringResource(R.string.next_title))
    if (at == null) {
        Text(
            text = stringResource(R.string.next_none),
            style = MaterialTheme.typography.titleLarge,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ModuleBadge(type, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = at.clock(),
                    style = MaterialTheme.typography.headlineMedium.merge(Tabular),
                )
                if (deviceNow != null) {
                    val minutes = ChronoUnit.MINUTES.between(deviceNow.withSecond(0).withNano(0), at)
                    val dayShift = ChronoUnit.DAYS.between(deviceNow.toLocalDate(), at.toLocalDate())
                    Text(
                        text = if (dayShift > 0) at.toLocalDate().shortDate() else untilText(minutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                    )
                }
            }
            val subtitle = listOfNotNull(
                label?.takeIf { it.isNotBlank() },
                role?.let { roleLabel(it) },
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RingButton(ringing: Boolean, ringLeft: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when {
            ringing -> colors.error
            enabled -> colors.primary
            else -> colors.surfaceContainerHighest
        },
        label = "ring-color",
    )
    val content = when {
        ringing -> colors.onError
        enabled -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    val pulse = rememberInfiniteTransition(label = "ring")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (ringing) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "ring-scale",
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Halo behind the button.
        Box(
            modifier = Modifier
                .size(156.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(container.copy(alpha = 0.14f)),
        )
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = container,
            contentColor = content,
            shadowElevation = if (enabled) 6.dp else 0.dp,
            modifier = Modifier.size(128.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (ringing) Icons.Default.Stop else Icons.Default.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (ringing) {
                        stringResource(R.string.ring_left, ringLeft)
                    } else {
                        stringResource(R.string.ring_button)
                    },
                    style = MaterialTheme.typography.labelLarge.merge(Tabular),
                )
            }
        }
    }
}

@Composable
private fun ModeCard(state: UiState, onMode: (Boolean) -> Unit) {
    val status = state.status
    AppCard {
        Caption(stringResource(R.string.mode_title))
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = status?.isAuto == true,
                onClick = { onMode(true) },
                enabled = state.online && !state.busy,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { SegmentedButtonDefaults.Icon(status?.isAuto == true) { Icon(Icons.Default.Schedule, null, Modifier.size(18.dp)) } },
            ) { Text(stringResource(R.string.mode_auto)) }
            SegmentedButton(
                selected = status?.isAuto == false,
                onClick = { onMode(false) },
                enabled = state.online && !state.busy,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { SegmentedButtonDefaults.Icon(status?.isAuto == false) { Icon(Icons.Default.TouchApp, null, Modifier.size(18.dp)) } },
            ) { Text(stringResource(R.string.mode_manual)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (status?.isAuto == false) R.string.mode_manual_hint else R.string.mode_auto_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodayCard(state: UiState, deviceNow: LocalDateTime?, onOpenSchedule: () -> Unit) {
    val date = deviceNow?.toLocalDate() ?: LocalDate.now()
    val bells = remember(state.schedule, date) { state.schedule.resolveDay(date) }
    val nowMinute = deviceNow?.let { it.hour * 60 + it.minute }
    val nextIndex = nowMinute?.let { minute -> bells.indexOfFirst { it.minute > minute } } ?: -1

    AppCard(onClick = onOpenSchedule) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.today_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(R.plurals.bells_count, bells.size, bells.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.scheduleDirty) {
            Text(
                text = stringResource(R.string.today_unsent),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.extra.warning,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (bells.isEmpty()) {
            Text(
                text = stringResource(R.string.today_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return@AppCard
        }
        // Show the neighbourhood of the next bell rather than the whole day.
        val first = (if (nextIndex >= 0) nextIndex - 2 else bells.size - 4).coerceIn(0, bells.size)
        val visible = bells.drop(first).take(TODAY_VISIBLE)
        for ((offset, bell) in visible.withIndex()) {
            val index = first + offset
            TodayRow(
                bell = bell,
                past = nowMinute != null && bell.minute <= nowMinute,
                next = index == nextIndex,
            )
        }
        val hidden = bells.size - visible.size
        if (hidden > 0) {
            Text(
                text = stringResource(R.string.today_more, hidden),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TodayRow(bell: ResolvedBell, past: Boolean, next: Boolean) {
    val background = if (next) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .alpha(if (past) 0.45f else 1f)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = bell.time,
            style = MaterialTheme.typography.titleMedium.merge(Tabular),
            fontWeight = if (next) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.width(64.dp),
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(AppColors.module(bell.module.type)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = listOfNotNull(
                bell.module.label.takeIf { it.isNotBlank() } ?: moduleTypeName(bell.module.type),
                roleLabel(bell.role).takeIf { bell.role != BellRole.Bell },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(visible = next) {
            Text(
                text = stringResource(R.string.today_next),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private const val TODAY_VISIBLE = 6
