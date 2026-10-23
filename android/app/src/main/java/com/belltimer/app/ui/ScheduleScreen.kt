package com.belltimer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.belltimer.app.AppViewModel
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.AutoWindow
import com.belltimer.app.data.BellSchedule
import com.belltimer.app.data.MAX_PACKS
import com.belltimer.app.data.MAX_PACK_ENTRIES
import com.belltimer.app.data.PackKind
import com.belltimer.app.data.PackWindow
import com.belltimer.app.data.ScheduleIssue
import com.belltimer.app.data.SchedulePack
import com.belltimer.app.data.canAddPack
import java.time.LocalDate

@Composable
fun SchedulePack.displayName(): String =
    name.ifBlank { stringResource(R.string.schedule_untitled) }

@Composable
fun ScheduleScreen(
    state: UiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenPack: (Int) -> Unit,
) {
    val schedule = state.schedule
    var newPackSheet by remember { mutableStateOf(false) }
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

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.back_desc),
                )
            }
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
                        text = { Text(stringResource(R.string.auto_window_title)) },
                        onClick = {
                            menuOpen = false
                            autoSheet = true
                        },
                    )
                }
            }
        }

        if (state.scheduleDirty) {
            DirtyBar(
                onSend = viewModel::sendSchedule,
                onDiscard = viewModel::discardScheduleChanges,
                enabled = !state.busy,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            ScheduleCalendar(
                schedule = schedule,
                onDayClick = { date -> checkedDate = date },
                modifier = Modifier.padding(top = 4.dp),
            )

            if (!state.supportsPacks) {
                Text(
                    text = stringResource(R.string.schedule_needs_new_firmware),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionHeader(
                text = stringResource(R.string.schedule_packs_count, schedule.packs.size, MAX_PACKS),
            )

            if (schedule.packs.isEmpty()) {
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
            } else {
                ReorderablePackList(
                    packs = schedule.packs,
                    onReorder = { ordered ->
                        if (ordered != schedule.packs) {
                            viewModel.updateSchedule(schedule.copy(packs = ordered))
                        }
                    },
                    onOpen = onOpenPack,
                    onToggle = { id, enabled ->
                        schedule.packById(id)?.let { pack ->
                            viewModel.updateSchedule(schedule.withPack(pack.copy(enabled = enabled)))
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
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
                onOpenPack(pack.id)
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

@Composable
private fun PackRow(
    pack: SchedulePack,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    dragHandle: @Composable () -> Unit,
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
        dragHandle()
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(start = 4.dp, end = 8.dp),
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
        Spacer(Modifier.width(4.dp))
        Switch(checked = pack.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ReorderablePackList(
    packs: List<SchedulePack>,
    onReorder: (List<SchedulePack>) -> Unit,
    onOpen: (Int) -> Unit,
    onToggle: (Int, Boolean) -> Unit,
) {
    val ordered = remember(packs) { packs.toMutableStateList() }
    var draggingId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<Int, Int>() }
    val currentOnReorder by rememberUpdatedState(onReorder)

    Column {
        ordered.forEach { pack ->
            val isDragging = draggingId == pack.id
            Box(
                modifier = Modifier
                    .onSizeChanged { rowHeights[pack.id] = it.height }
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .zIndex(if (isDragging) 1f else 0f),
            ) {
                PackRow(
                    pack = pack,
                    onOpen = { onOpen(pack.id) },
                    onToggle = { enabled -> onToggle(pack.id, enabled) },
                    dragHandle = {
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = stringResource(R.string.schedule_pack_reorder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .pointerInput(pack.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingId = pack.id
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            draggingId = null
                                            dragOffset = 0f
                                            currentOnReorder(ordered.toList())
                                        },
                                        onDragCancel = {
                                            draggingId = null
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            val heightPx = (rowHeights[pack.id] ?: 0).toFloat()
                                            if (heightPx > 0f) {
                                                val current = ordered.indexOfFirst { it.id == pack.id }
                                                var target = current
                                                if (dragOffset > heightPx / 2f && current < ordered.lastIndex) {
                                                    target = current + 1
                                                } else if (dragOffset < -heightPx / 2f && current > 0) {
                                                    target = current - 1
                                                }
                                                if (target != current) {
                                                    val moved = ordered.removeAt(current)
                                                    ordered.add(target, moved)
                                                    dragOffset -= (target - current) * heightPx
                                                }
                                            }
                                        },
                                    )
                                },
                        )
                    },
                )
            }
            RowDivider()
        }
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
