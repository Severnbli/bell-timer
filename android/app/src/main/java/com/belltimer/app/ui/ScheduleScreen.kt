package com.belltimer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.AutoWindow
import com.belltimer.app.data.BellSchedule
import com.belltimer.app.data.MAX_PACK_ENTRIES
import com.belltimer.app.data.PackKind
import com.belltimer.app.data.PackWindow
import com.belltimer.app.data.ScheduleIssue
import com.belltimer.app.data.SchedulePack
import com.belltimer.app.data.canAddPack
import java.time.LocalDate

/** Plain state-based navigation inside the schedule tab. */
sealed interface ScheduleRoute {
    data object Packs : ScheduleRoute
    data class Editor(val packId: Int) : ScheduleRoute
}

private val ScheduleRouteSaver = listSaver<ScheduleRoute, Any>(
    save = { route ->
        when (route) {
            ScheduleRoute.Packs -> listOf("packs")
            is ScheduleRoute.Editor -> listOf("editor", route.packId)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull() as? String) {
            "editor" -> ScheduleRoute.Editor(saved.getOrNull(1) as? Int ?: 0)
            else -> ScheduleRoute.Packs
        }
    },
)

@Composable
fun SchedulePack.displayName(): String =
    name.ifBlank { stringResource(R.string.schedule_untitled) }

@Composable
fun ScheduleScreen(state: UiState, viewModel: AppViewModel) {
    val schedule = state.schedule
    var route by rememberSaveable(stateSaver = ScheduleRouteSaver) {
        mutableStateOf<ScheduleRoute>(ScheduleRoute.Packs)
    }
    var newPackSheet by remember { mutableStateOf(false) }
    var checkDate by remember { mutableStateOf(false) }
    var checkedDate by remember { mutableStateOf<LocalDate?>(null) }
    var autoSheet by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val importErrorMessage = stringResource(R.string.schedule_import_error)
    val tooManyPacksMessage = stringResource(R.string.error_too_many_packs)
    val newPackName = stringResource(R.string.schedule_new_pack_name)
    val newCorrectionName = stringResource(R.string.schedule_new_correction_name)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                viewModel.reportMessage(importErrorMessage)
            } else {
                viewModel.importScheduleText(text)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val text = viewModel.exportScheduleText()
            val ok = text != null && runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            viewModel.reportExportResult(ok)
        }
    }

    LaunchedEffect(state.scheduleIssue) {
        if (state.scheduleIssue is ScheduleIssue.FirmwareTooOld) {
            viewModel.consumeScheduleIssue()
        }
    }

    when (val current = route) {
        is ScheduleRoute.Editor -> {
            ScheduleEditorScreen(
                state = state,
                viewModel = viewModel,
                packId = current.packId,
                onBack = { route = ScheduleRoute.Packs },
            )
            return
        }
        ScheduleRoute.Packs -> Unit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
                text = stringResource(R.string.schedule_packs_title),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (schedule.canAddPack()) {
                        newPackSheet = true
                    } else {
                        viewModel.reportMessage(tooManyPacksMessage)
                    }
                },
            ) {
                Text(stringResource(R.string.schedule_add_pack))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_desc),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_import)) },
                        onClick = {
                            menuOpen = false
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_export)) },
                        enabled = !schedule.isEmpty,
                        onClick = {
                            menuOpen = false
                            exportLauncher.launch(exportFileName())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_check_date)) },
                        onClick = {
                            menuOpen = false
                            checkDate = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.auto_window_title)) },
                        onClick = {
                            menuOpen = false
                            autoSheet = true
                        },
                    )
                }
            }
        }

        TodaySummary(schedule)

        if (!state.supportsPacks) {
            Text(
                text = stringResource(R.string.schedule_needs_new_firmware),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (schedule.packs.isEmpty()) {
                item(key = "packs-empty") {
                    EmptyState(
                        text = stringResource(R.string.schedule_packs_empty),
                        actionLabel = stringResource(R.string.schedule_add_pack),
                        onAction = {
                            if (schedule.canAddPack()) {
                                newPackSheet = true
                            } else {
                                viewModel.reportMessage(tooManyPacksMessage)
                            }
                        },
                    )
                }
            } else {
                itemsIndexed(schedule.packs, key = { _, pack -> "pack-${pack.id}" }) { index, pack ->
                    PackRow(
                        pack = pack,
                        index = index,
                        lastIndex = schedule.packs.lastIndex,
                        onOpen = { route = ScheduleRoute.Editor(pack.id) },
                        onToggle = { enabled ->
                            viewModel.updateSchedule(
                                schedule.withPack(pack.copy(enabled = enabled)),
                            )
                        },
                        onMove = { delta ->
                            viewModel.updateSchedule(schedule.movePack(pack.id, delta))
                        },
                    )
                    RowDivider()
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (newPackSheet) {
        ChoiceSheet(
            title = stringResource(R.string.schedule_pack_new_title),
            options = listOf(
                stringResource(R.string.schedule_pack_kind_base),
                stringResource(R.string.schedule_pack_kind_correction),
            ),
            selectedIndex = -1,
            onDismiss = { newPackSheet = false },
            onSelect = { index ->
                newPackSheet = false
                val kind = if (index == 1) PackKind.Correction else PackKind.Base
                val pack = SchedulePack(
                    id = schedule.nextPackId(),
                    name = if (kind == PackKind.Base) newPackName else newCorrectionName,
                    kind = kind,
                )
                viewModel.updateSchedule(schedule.withPack(pack))
                route = ScheduleRoute.Editor(pack.id)
            },
        )
    }

    if (autoSheet) {
        AutoWindowSheet(
            initial = schedule.auto,
            onDismiss = { autoSheet = false },
            onConfirm = { window ->
                autoSheet = false
                viewModel.updateSchedule(state.schedule.copy(auto = window))
            },
        )
    }

    if (checkDate) {
        DateSheet(
            initial = LocalDate.now(),
            onDismiss = { checkDate = false },
            onPick = { date ->
                checkDate = false
                checkedDate = date
            },
        )
    }

    checkedDate?.let { date ->
        val resolved = schedule.bellsFor(date)
        ModalSheetText(
            title = stringResource(R.string.schedule_check_date_title, date.fullDate()),
            onDismiss = { checkedDate = null },
        ) {
            Text(
                text = dayReason(schedule, date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (resolved.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedule_timeline_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                resolved.forEach { entry ->
                    BellRow(entry = entry, onClick = {})
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodaySummary(schedule: BellSchedule) {
    val today = LocalDate.now()
    val bells = remember(schedule, today) { schedule.bellsFor(today) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.schedule_today_title) + " · " + today.fullDate(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.schedule_today_count, bells.size),
            style = MaterialTheme.typography.titleMedium,
        )
        if (bells.isEmpty()) {
            Text(
                text = stringResource(R.string.schedule_timeline_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                bells.forEach { entry ->
                    Text(
                        text = entry.time,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PackRow(
    pack: SchedulePack,
    index: Int,
    lastIndex: Int,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val today = LocalDate.now()
    val active = pack.appliesOn(today)
    val kindLabel = stringResource(
        if (pack.kind == PackKind.Correction) {
            R.string.schedule_pack_kind_correction
        } else {
            R.string.schedule_pack_kind_base
        },
    )
    val statusLabel = stringResource(
        if (active) R.string.schedule_pack_status_active else R.string.schedule_pack_status_inactive,
    )
    val statusColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val count = stringResource(
        R.string.schedule_pack_entries_count,
        pack.entries.size,
        MAX_PACK_ENTRIES,
    )
    val days = daysSummary(pack.weekdayUnion)
    val windowText = windowSummary(pack.window)
    val subtitle = listOf(count, days, windowText)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(end = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pack.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pack.kind == PackKind.Correction) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = { onMove(-1) },
                enabled = index > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.schedule_pack_move_up),
                )
            }
            IconButton(
                onClick = { onMove(1) },
                enabled = index < lastIndex,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.schedule_pack_move_down),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Switch(checked = pack.enabled, onCheckedChange = onToggle)
    }
}

@Composable
fun daysSummary(days: Set<Int>): String = when {
    days.isEmpty() -> stringResource(R.string.schedule_pack_no_days)
    days.size == 7 -> stringResource(R.string.days_every_day)
    days == setOf(1, 2, 3, 4, 5) -> stringResource(R.string.days_weekdays)
    else -> days.sorted().map { stringResource(dayLabelRes(it)) }.joinToString(", ")
}

@Composable
private fun windowSummary(window: PackWindow): String {
    if (!window.isSet) return stringResource(R.string.schedule_validity_none)
    val parts = mutableListOf<String>()
    if (window.from != null || window.to != null) {
        parts += (window.from?.shortDate() ?: "…") + "–" + (window.to?.shortDate() ?: "…")
    }
    if (window.dates.isNotEmpty()) {
        parts += stringResource(R.string.schedule_validity_dates) + ": " + window.dates.size
    }
    if (window.yearly) parts += stringResource(R.string.schedule_validity_yearly)
    return parts.joinToString(" · ")
}

@Composable
private fun AutoWindowSheet(
    initial: AutoWindow,
    onDismiss: () -> Unit,
    onConfirm: (AutoWindow) -> Unit,
) {
    var window by remember { mutableStateOf(initial) }
    var editingStart by remember { mutableStateOf(false) }
    var editingStop by remember { mutableStateOf(false) }

    ModalSheetText(title = stringResource(R.string.auto_window_title), onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButtonBlock(
                label = stringResource(R.string.auto_window_start),
                value = window.start?.fullDate() ?: stringResource(R.string.auto_window_not_set),
                onClick = { editingStart = true },
                modifier = Modifier.weight(1f),
            )
            OutlinedButtonBlock(
                label = stringResource(R.string.auto_window_stop),
                value = window.stop?.fullDate() ?: stringResource(R.string.auto_window_not_set),
                onClick = { editingStop = true },
                modifier = Modifier.weight(1f),
            )
        }
        if (!window.isValid) {
            Text(
                text = stringResource(R.string.schedule_auto_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { window = AutoWindow() }) {
                Text(stringResource(R.string.auto_window_clear))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onConfirm(window) }, enabled = window.isValid) {
                Text(stringResource(R.string.ok))
            }
        }
    }

    if (editingStart) {
        DateSheet(
            initial = window.start ?: LocalDate.now(),
            onDismiss = { editingStart = false },
            onPick = { date ->
                window = window.copy(start = date)
                editingStart = false
            },
        )
    }
    if (editingStop) {
        DateSheet(
            initial = window.stop ?: LocalDate.now(),
            onDismiss = { editingStop = false },
            onPick = { date ->
                window = window.copy(stop = date)
                editingStop = false
            },
        )
    }
}

@Composable
fun dayReason(schedule: BellSchedule, date: LocalDate): String {
    if (!schedule.auto.allows(date)) return stringResource(R.string.day_reason_outside)
    val names = mutableListOf<String>()
    for (pack in schedule.packs) {
        if (pack.appliesOn(date)) names += pack.displayName()
    }
    return if (names.isEmpty()) {
        stringResource(R.string.day_reason_none)
    } else {
        stringResource(R.string.day_reason_packs, names.joinToString(", "))
    }
}

@Composable
fun weekdayName(day: Int): String = stringResource(dayLabelRes(day))

private fun exportFileName(): String =
    "belltimer-schedule-${LocalDate.now()}.json"
