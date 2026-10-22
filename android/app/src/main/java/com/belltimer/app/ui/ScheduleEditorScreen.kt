package com.belltimer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.ALL_DAYS
import com.belltimer.app.data.BellEntry
import com.belltimer.app.data.BuilderApplyMode
import com.belltimer.app.data.MAX_LABEL_BYTES
import com.belltimer.app.data.ScheduleGroup
import com.belltimer.app.data.WEEKDAYS
import com.belltimer.app.data.WeekdayConflict
import com.belltimer.app.data.canAddEntries
import com.belltimer.app.data.firstFreeDays
import com.belltimer.app.data.firstOverlap
import com.belltimer.app.data.inheritDays
import com.belltimer.app.data.labelTooLong
import com.belltimer.app.data.shiftBy
import com.belltimer.app.data.sortedSchedule

private data class EntryTarget(val index: Int?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorScreen(
    state: UiState,
    viewModel: AppViewModel,
    groupId: Int,
    startWithBuilder: Boolean,
    onStartWithBuilderConsumed: () -> Unit,
    onBack: () -> Unit,
) {
    val schedule = state.schedule
    val group = schedule.groupById(groupId)

    if (group == null) {
        LaunchedEffect(groupId) { onBack() }
        return
    }

    var entryTarget by remember { mutableStateOf<EntryTarget?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var builderChoice by remember { mutableStateOf(false) }
    var builderMode by remember { mutableStateOf(BuilderApplyMode.Replace) }
    var showBuilder by rememberSaveable { mutableStateOf(false) }
    var shiftSheet by remember { mutableStateOf(false) }
    var dayConflict by remember { mutableStateOf<List<WeekdayConflict>>(emptyList()) }
    var pendingDays by remember { mutableStateOf<Set<Int>?>(null) }

    val entryDeletedMessage = stringResource(R.string.schedule_entry_deleted)
    val overlapMessage = stringResource(R.string.schedule_overlap_error)
    val noDaysMessage = stringResource(R.string.schedule_without_days)
    val copySuffix = stringResource(R.string.schedule_copy_suffix)
    val shiftOverflowMessage = stringResource(R.string.schedule_shift_overflow)

    BackHandler { onBack() }

    LaunchedEffect(startWithBuilder) {
        if (startWithBuilder) {
            builderMode = BuilderApplyMode.Replace
            showBuilder = true
            onStartWithBuilderConsumed()
        }
    }

    fun commitDays(target: Set<Int>) {
        val updated = schedule.groups.fold(schedule) { acc, item ->
            when {
                item.id == group.id -> acc.withGroup(item.withDays(target))
                else -> {
                    val remaining = item.days - target
                    if (remaining == item.days) acc else acc.withGroup(item.withDays(remaining))
                }
            }
        }
        viewModel.updateSchedule(updated)
    }

    fun requestDays(target: Set<Int>) {
        val conflicts = schedule.conflictingDays(target - group.days, group.id)
        if (conflicts.isEmpty()) {
            commitDays(target)
        } else {
            pendingDays = target
            dayConflict = conflicts
        }
    }

    if (showBuilder) {
        ScheduleBuilderScreen(
            defaultDuration = state.ringSeconds,
            existingEntries = if (builderMode == BuilderApplyMode.Append) group.entries.size else 0,
            onDismiss = { showBuilder = false },
            onApply = { entries ->
                val current = viewModel.state.value.schedule.groupById(groupId)
                if (current != null) {
                    val merged = when (builderMode) {
                        BuilderApplyMode.Replace -> entries
                        BuilderApplyMode.Append -> current.entries + entries
                    }
                    viewModel.updateSchedule(
                        viewModel.state.value.schedule.withGroup(
                            current.withEntries(merged.inheritDays(current.days).sortedSchedule()),
                        ),
                        undoable = true,
                    )
                }
                showBuilder = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    BasicTextField(
                        value = group.name,
                        onValueChange = { newName ->
                            viewModel.updateSchedule(
                                viewModel.state.value.schedule.withGroup(
                                    (viewModel.state.value.schedule.groupById(groupId) ?: group)
                                        .copy(name = newName),
                                ),
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_desc),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_desc),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schedule_duplicate)) },
                                onClick = {
                                    menuOpen = false
                                    val clone = group
                                        .withDays(schedule.firstFreeDays())
                                        .copy(
                                            id = schedule.nextGroupId(),
                                            name = group.name + " " + copySuffix,
                                        )
                                    viewModel.updateSchedule(schedule.withGroup(clone))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.updateSchedule(
                                        schedule.withoutGroup(group.id),
                                        undoable = true,
                                    )
                                    onBack()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item(key = "weekdays") {
                SectionHeader(text = stringResource(R.string.schedule_weekdays_title))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (day in 1..7) {
                        FilterChip(
                            selected = day in group.days,
                            onClick = {
                                val target = if (day in group.days) {
                                    group.days - day
                                } else {
                                    group.days + day
                                }
                                requestDays(target)
                            },
                            label = { Text(weekdayName(day)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { requestDays(WEEKDAYS) },
                        label = { Text(stringResource(R.string.days_weekdays)) },
                    )
                    AssistChip(
                        onClick = { requestDays(ALL_DAYS) },
                        label = { Text(stringResource(R.string.days_every_day)) },
                    )
                }
                if (group.days.isEmpty()) {
                    Text(
                        text = noDaysMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (schedule.firstOverlap()?.let { it.groupId == group.id || it.otherGroupId == group.id } == true) {
                    Text(
                        text = overlapMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item(key = "bells-header") {
                SectionHeader(
                    text = stringResource(R.string.schedule_bells_title) + " · " +
                        stringResource(R.string.schedule_bells_count, group.entries.size),
                )
            }

            itemsIndexed(group.entries, key = { index, entry -> "bell-$index-${entry.time}" }) { index, entry ->
                SwipeToDeleteBox(
                    onDelete = {
                        val current = viewModel.state.value.schedule.groupById(groupId) ?: return@SwipeToDeleteBox
                        viewModel.updateSchedule(
                            viewModel.state.value.schedule.withGroup(current.withoutEntry(index)),
                            undoable = true,
                        )
                        viewModel.reportMessage(entryDeletedMessage)
                    },
                ) {
                    BellRow(entry = entry, onClick = { entryTarget = EntryTarget(index) })
                }
                RowDivider()
            }

            item(key = "add-bell") {
                TextButton(
                    onClick = { entryTarget = EntryTarget(null) },
                    enabled = schedule.canAddEntries(1),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.schedule_add_bell))
                }
            }

            item(key = "builder") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { builderChoice = true }) {
                        Text(stringResource(R.string.schedule_build))
                    }
                    TextButton(onClick = { shiftSheet = true }) {
                        Text(stringResource(R.string.schedule_shift))
                    }
                }
            }

            item(key = "bottom-space") { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }

    entryTarget?.let { target ->
        BellEntrySheet(
            initial = target.index?.let { group.entries.getOrNull(it) },
            defaultDuration = state.ringSeconds,
            days = group.days,
            onDismiss = { entryTarget = null },
            onConfirm = { entry ->
                val current = viewModel.state.value.schedule.groupById(groupId)
                if (current != null) {
                    val updated = if (target.index != null) {
                        current.withEntry(target.index, entry)
                    } else {
                        current.withEntries(current.entries + entry)
                    }
                    viewModel.updateSchedule(
                        viewModel.state.value.schedule.withGroup(updated),
                        undoable = true,
                    )
                }
                entryTarget = null
            },
        )
    }

    if (builderChoice) {
        ChoiceSheet(
            title = stringResource(R.string.schedule_build_mode),
            options = listOf(
                stringResource(R.string.schedule_build_replace),
                stringResource(R.string.schedule_build_append),
            ),
            selectedIndex = -1,
            onDismiss = { builderChoice = false },
            onSelect = { index ->
                builderChoice = false
                builderMode = if (index == 0) BuilderApplyMode.Replace else BuilderApplyMode.Append
                showBuilder = true
            },
        )
    }

    if (shiftSheet) {
        NumberSheet(
            title = stringResource(R.string.schedule_shift_title),
            initial = 5,
            range = -240..240,
            fieldLabel = stringResource(R.string.schedule_shift_field),
            allowNegative = true,
            onDismiss = { shiftSheet = false },
            onPick = { delta ->
                shiftSheet = false
                val current = viewModel.state.value.schedule.groupById(groupId)
                if (current != null) {
                    val shifted = current.entries.shiftBy(delta)
                    if (shifted.overflow) {
                        viewModel.reportMessage(shiftOverflowMessage)
                    } else {
                        viewModel.updateSchedule(
                            viewModel.state.value.schedule.withGroup(
                                current.withEntries(shifted.entries),
                            ),
                            undoable = true,
                        )
                    }
                }
            },
        )
    }

    pendingDays?.let { target ->
        val conflictMessage = dayConflict
            .map { conflict ->
                stringResource(
                    R.string.schedule_overlap_move,
                    weekdayName(conflict.day),
                    conflict.group.displayName(),
                )
            }
            .joinToString("\n")
        ConfirmDialog(
            title = stringResource(R.string.schedule_overlap_title),
            message = conflictMessage,
            confirmLabel = stringResource(R.string.schedule_overlap_confirm),
            onConfirm = {
                pendingDays = null
                dayConflict = emptyList()
                commitDays(target)
            },
            onDismiss = {
                pendingDays = null
                dayConflict = emptyList()
            },
        )
    }
}

/** Time, label and duration of one bell. Shared by the editor and day editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BellEntrySheet(
    initial: BellEntry?,
    defaultDuration: Int,
    days: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (BellEntry) -> Unit,
) {
    var minute by rememberSaveable {
        mutableIntStateOf(
            initial?.minutes?.takeIf { it != BellEntry.INVALID_MINUTE } ?: 8 * 60,
        )
    }
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var duration by rememberSaveable { mutableIntStateOf(initial?.duration ?: defaultDuration) }
    var timeSheet by remember { mutableStateOf(false) }
    val labelError = labelTooLong(label)
    val labelErrorMessage = stringResource(R.string.schedule_label_too_long, MAX_LABEL_BYTES)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (initial == null) R.string.schedule_entry_add_title
                    else R.string.schedule_entry_edit_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = { timeSheet = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = minutesToTime(minute),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.label_optional)) },
                singleLine = true,
                isError = labelError,
                modifier = Modifier.fillMaxWidth(),
            )
            if (labelError) {
                Text(
                    text = labelErrorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            RingDurationPicker(
                seconds = duration,
                onSecondsChange = { duration = it },
                label = stringResource(R.string.schedule_entry_duration),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !labelError,
                    onClick = {
                        onConfirm(
                            BellEntry(
                                time = minutesToTime(minute),
                                days = days,
                                duration = duration,
                                label = label.trim(),
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (timeSheet) {
        TimeSheet(
            title = stringResource(R.string.schedule_entry_time),
            initialMinute = minute,
            onDismiss = { timeSheet = false },
            onPick = { picked ->
                minute = picked
                timeSheet = false
            },
        )
    }
}

