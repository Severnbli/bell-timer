package com.belltimer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.ConnectionState
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.BellEntry
import com.belltimer.app.data.MAX_DATE_EXCEPTIONS
import com.belltimer.app.data.MAX_RING_SECONDS
import com.belltimer.app.data.MAX_SCHEDULE_ENTRIES
import com.belltimer.app.data.MAX_SCHEDULE_GROUPS
import com.belltimer.app.data.MIN_RING_SECONDS
import com.belltimer.app.data.ScheduleGroup
import com.belltimer.app.data.sortedSchedule
import java.time.LocalDate

@Composable
fun ScheduleScreen(state: UiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val schedule = state.schedule
    val online = state.connection == ConnectionState.Online
    val importErrorMessage = stringResource(R.string.schedule_import_error)
    val tooManyGroupsMessage = stringResource(R.string.schedule_too_many_groups)

    var showGroupDialog by remember { mutableStateOf(false) }
    var editingGroupId by remember { mutableStateOf<Int?>(null) }
    var showEntryDialog by remember { mutableStateOf(false) }
    var entryGroupId by remember { mutableStateOf<Int?>(null) }
    var entryIndex by remember { mutableStateOf<Int?>(null) }
    var deleteGroupId by remember { mutableStateOf<Int?>(null) }
    var deleteEntry by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showExceptionDialog by remember { mutableStateOf(false) }
    var editingExceptionDate by remember { mutableStateOf<LocalDate?>(null) }
    var deleteExceptionDate by remember { mutableStateOf<LocalDate?>(null) }
    var showGenerator by remember { mutableStateOf(false) }
    var showLoadConfirm by remember { mutableStateOf(false) }

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

    if (showGroupDialog) {
        val editing = schedule.groupById(editingGroupId)
        GroupDialog(
            initial = editing,
            onDismiss = {
                showGroupDialog = false
                editingGroupId = null
            },
            onConfirm = { name, days ->
                val current = state.schedule
                when {
                    editing == null && current.groups.size >= MAX_SCHEDULE_GROUPS ->
                        viewModel.reportMessage(tooManyGroupsMessage)

                    else -> {
                        val updated = if (editing == null) {
                            current.copy(
                                groups = current.groups + ScheduleGroup(
                                    id = current.nextGroupId(),
                                    name = name,
                                    days = days,
                                ),
                            )
                        } else {
                            current.copy(
                                groups = current.groups.map { group ->
                                    if (group.id == editing.id) {
                                        group.copy(name = name, days = days)
                                    } else {
                                        group
                                    }
                                },
                            )
                        }
                        viewModel.updateSchedule(updated)
                    }
                }
                showGroupDialog = false
                editingGroupId = null
            },
        )
    }

    if (showEntryDialog) {
        val group = schedule.groupById(entryGroupId)
        if (group != null) {
            ScheduleEntryDialog(
                initial = entryIndex?.let { group.entries.getOrNull(it) },
                defaultDuration = state.ringSeconds,
                defaultDays = group.days,
                onDismiss = {
                    showEntryDialog = false
                    entryGroupId = null
                    entryIndex = null
                },
                onConfirm = { entry ->
                    val current = state.schedule
                    val target = current.groupById(group.id)
                    if (target != null) {
                        val entries = target.entries.toMutableList()
                        val index = entryIndex
                        if (index != null && index in entries.indices) {
                            entries[index] = entry
                        } else if (current.totalEntries < MAX_SCHEDULE_ENTRIES) {
                            entries.add(entry)
                        }
                        viewModel.updateSchedule(
                            current.copy(
                                groups = current.groups.map {
                                    if (it.id == target.id) {
                                        it.copy(entries = entries.sortedSchedule())
                                    } else {
                                        it
                                    }
                                },
                            ),
                        )
                    }
                    showEntryDialog = false
                    entryGroupId = null
                    entryIndex = null
                },
            )
        }
    }

    deleteEntry?.let { (groupId, index) ->
        val group = schedule.groupById(groupId)
        val entry = group?.entries?.getOrNull(index)
        if (group != null && entry != null) {
            val title = "${entry.time} " +
                entry.label.ifBlank { stringResource(R.string.schedule_no_label) }
            AlertDialog(
                onDismissRequest = { deleteEntry = null },
                title = { Text(stringResource(R.string.schedule_delete_title)) },
                text = { Text(stringResource(R.string.schedule_delete_text, title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val current = state.schedule
                            val target = current.groupById(groupId)
                            if (target != null) {
                                val entries = target.entries.toMutableList()
                                if (index in entries.indices) entries.removeAt(index)
                                viewModel.updateSchedule(
                                    current.copy(
                                        groups = current.groups.map {
                                            if (it.id == target.id) {
                                                it.copy(entries = entries)
                                            } else {
                                                it
                                            }
                                        },
                                    ),
                                )
                            }
                            deleteEntry = null
                        },
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteEntry = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    deleteGroupId?.let { groupId ->
        val group = schedule.groupById(groupId)
        if (group != null) {
            val title = group.name.ifBlank { stringResource(R.string.schedule_group_untitled) }
            AlertDialog(
                onDismissRequest = { deleteGroupId = null },
                title = { Text(stringResource(R.string.schedule_group_delete_title)) },
                text = { Text(stringResource(R.string.schedule_group_delete_text, title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val current = state.schedule
                            viewModel.updateSchedule(
                                current.copy(
                                    groups = current.groups.filterNot { it.id == groupId },
                                    exceptions = current.exceptions.filterNot {
                                        !it.mute && it.groupId == groupId
                                    },
                                ),
                            )
                            deleteGroupId = null
                        },
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteGroupId = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    if (showExceptionDialog) {
        DateExceptionDialog(
            initial = editingExceptionDate?.let { date -> schedule.exceptionFor(date) },
            groups = schedule.groups,
            takenDates = schedule.exceptions.map { it.date }.toSet(),
            onDismiss = {
                showExceptionDialog = false
                editingExceptionDate = null
            },
            onConfirm = { exception ->
                val current = state.schedule
                viewModel.updateSchedule(
                    current.copy(
                        exceptions = (current.exceptions
                            .filterNot { it.date == exception.date } + exception)
                            .sortedBy { it.date },
                    ),
                )
                showExceptionDialog = false
                editingExceptionDate = null
            },
        )
    }

    deleteExceptionDate?.let { date ->
        AlertDialog(
            onDismissRequest = { deleteExceptionDate = null },
            title = { Text(stringResource(R.string.schedule_exception_delete_title)) },
            text = { Text(stringResource(R.string.schedule_exception_delete_text, date.displayDate())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val current = state.schedule
                        viewModel.updateSchedule(
                            current.copy(exceptions = current.exceptions.filterNot { it.date == date }),
                        )
                        deleteExceptionDate = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteExceptionDate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showGenerator) {
        GeneratorDialog(
            defaultDuration = state.ringSeconds,
            existingGroups = schedule.groups.size,
            existingEntries = schedule.totalEntries,
            onDismiss = { showGenerator = false },
            onConfirm = { name, days, entries ->
                val current = state.schedule
                viewModel.updateSchedule(
                    current.copy(
                        groups = current.groups + ScheduleGroup(
                            id = current.nextGroupId(),
                            name = name,
                            days = days,
                            entries = entries.sortedSchedule(),
                        ),
                    ),
                )
                showGenerator = false
            },
        )
    }

    if (showLoadConfirm) {
        AlertDialog(
            onDismissRequest = { showLoadConfirm = false },
            title = { Text(stringResource(R.string.schedule_unsaved_confirm_title)) },
            text = { Text(stringResource(R.string.schedule_unsaved_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLoadConfirm = false
                        viewModel.loadSchedule()
                    },
                ) {
                    Text(stringResource(R.string.schedule_unsaved_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLoadConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.schedule_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(
                        R.string.schedule_count,
                        schedule.totalEntries,
                        MAX_SCHEDULE_ENTRIES,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.scheduleDirty) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(R.string.schedule_dirty_short),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (!online) {
            Text(
                text = stringResource(R.string.device_offline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        AnimatedVisibility(visible = state.scheduleDirty) {
            SyncBanner(
                text = stringResource(R.string.schedule_dirty_banner),
                onSync = viewModel::saveSchedule,
                enabled = !state.busy,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    editingGroupId = null
                    showGroupDialog = true
                },
                enabled = schedule.groups.size < MAX_SCHEDULE_GROUPS,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.schedule_group_add))
            }
            OutlinedButton(
                onClick = { showGenerator = true },
                enabled = schedule.groups.size < MAX_SCHEDULE_GROUPS,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.Default.EventRepeat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.generator_button))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.schedule_import))
            }
            OutlinedButton(
                onClick = { exportLauncher.launch(exportFileName()) },
                enabled = !schedule.isEmpty,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.schedule_export))
            }
            OutlinedButton(
                onClick = {
                    if (state.scheduleDirty) showLoadConfirm = true else viewModel.loadSchedule()
                },
                enabled = !state.loadingSchedule,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.schedule_load))
            }
        }

        if (state.loadingSchedule) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "auto") {
                AutoWindowCard(
                    auto = schedule.auto,
                    onChange = { viewModel.updateSchedule(schedule.copy(auto = it)) },
                )
            }

            item(key = "groups-header") {
                ScheduleSectionHeader(
                    title = stringResource(R.string.schedule_groups_title),
                    hint = stringResource(R.string.schedule_groups_hint),
                )
            }

            if (schedule.groups.isEmpty()) {
                item(key = "groups-empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.schedule_no_groups),
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.schedule_no_groups_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(schedule.groups, key = { "group-${it.id}" }) { group ->
                    ScheduleGroupCard(
                        group = group,
                        canAddEntry = schedule.totalEntries < MAX_SCHEDULE_ENTRIES,
                        onEdit = {
                            editingGroupId = group.id
                            showGroupDialog = true
                        },
                        onDelete = { deleteGroupId = group.id },
                        onAddEntry = {
                            entryGroupId = group.id
                            entryIndex = null
                            showEntryDialog = true
                        },
                        onEditEntry = { index ->
                            entryGroupId = group.id
                            entryIndex = index
                            showEntryDialog = true
                        },
                        onDeleteEntry = { index -> deleteEntry = group.id to index },
                    )
                }
            }

            item(key = "exceptions-header") {
                ScheduleSectionHeader(
                    title = stringResource(R.string.schedule_exceptions_title),
                    hint = stringResource(R.string.schedule_exceptions_hint),
                )
            }

            if (schedule.exceptions.isEmpty()) {
                item(key = "exceptions-empty") {
                    Text(
                        text = stringResource(R.string.schedule_exceptions_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(schedule.exceptions, key = { "exception-${it.date}" }) { exception ->
                    DateExceptionCard(
                        exception = exception,
                        groupName = schedule.groupById(exception.groupId)?.name,
                        onEdit = {
                            editingExceptionDate = exception.date
                            showExceptionDialog = true
                        },
                        onDelete = { deleteExceptionDate = exception.date },
                    )
                }
            }

            item(key = "add-exception") {
                OutlinedButton(
                    onClick = {
                        editingExceptionDate = null
                        showExceptionDialog = true
                    },
                    enabled = schedule.exceptions.size < MAX_DATE_EXCEPTIONS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.schedule_exception_add))
                }
            }

            item(key = "bottom-space") { Spacer(Modifier.padding(bottom = 8.dp)) }
        }
    }
}

private fun exportFileName(): String =
    "belltimer-schedule-${LocalDate.now()}.json"

@Composable
fun daysSummary(days: Set<Int>): String = when {
    days.isEmpty() -> stringResource(R.string.schedule_entry_invalid_days)
    days.size == 7 -> stringResource(R.string.days_every_day)
    days == setOf(1, 2, 3, 4, 5) -> stringResource(R.string.days_weekdays)
    else -> days.sorted().map { stringResource(dayLabelRes(it)) }.joinToString(", ")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEntryDialog(
    initial: BellEntry?,
    defaultDuration: Int,
    defaultDays: Set<Int> = (1..7).toSet(),
    onDismiss: () -> Unit,
    onConfirm: (BellEntry) -> Unit,
) {
    val initialMinutes = initial?.let { timeToMinutes(it.time) }
    val timeState = rememberTimePickerState(
        initialHour = initialMinutes?.div(60) ?: 8,
        initialMinute = initialMinutes?.rem(60) ?: 0,
        is24Hour = true,
    )
    var daysMask by rememberSaveable {
        mutableIntStateOf(
            initial?.days?.fold(0) { mask, day -> mask or (1 shl (day - 1)) }
                ?: defaultDays.fold(0) { mask, day -> mask or (1 shl (day - 1)) },
        )
    }
    val days = (1..7).filter { daysMask and (1 shl (it - 1)) != 0 }.toSet()
    var duration by rememberSaveable {
        mutableIntStateOf(initial?.duration ?: defaultDuration)
    }
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var showError by rememberSaveable { mutableStateOf(false) }

    val durationValid = duration in MIN_RING_SECONDS..MAX_RING_SECONDS
    val daysValid = days.isNotEmpty()
    val valid = durationValid && daysValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) {
                        R.string.schedule_entry_add_title
                    } else {
                        R.string.schedule_entry_edit_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.schedule_entry_time),
                    style = MaterialTheme.typography.labelLarge,
                )
                TimeInput(state = timeState)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.schedule_entry_days),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { daysMask = 0x1F },
                            label = { Text(stringResource(R.string.days_weekdays)) },
                        )
                        AssistChip(
                            onClick = { daysMask = 0x7F },
                            label = { Text(stringResource(R.string.days_every_day)) },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (day in 1..7) {
                            FilterChip(
                                selected = day in days,
                                onClick = {
                                    daysMask = daysMask xor (1 shl (day - 1))
                                },
                                label = { Text(stringResource(dayLabelRes(day))) },
                            )
                        }
                    }
                }

                RingDurationPicker(
                    seconds = duration,
                    onSecondsChange = { duration = it },
                    label = stringResource(R.string.schedule_entry_duration),
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.label_optional)) },
                    placeholder = { Text(stringResource(R.string.schedule_entry_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (showError && !valid) {
                    Text(
                        text = stringResource(
                            if (!daysValid) {
                                R.string.schedule_entry_invalid_days
                            } else {
                                R.string.schedule_entry_invalid_duration
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid) {
                        val minuteOfDay = timeState.hour * 60 + timeState.minute
                        onConfirm(
                            BellEntry(
                                time = minutesToTime(minuteOfDay),
                                days = days,
                                duration = duration,
                                label = label.trim(),
                            ),
                        )
                    } else {
                        showError = true
                    }
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GeneratorDialog(
    defaultDuration: Int,
    existingGroups: Int,
    existingEntries: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, days: Set<Int>, entries: List<BellEntry>) -> Unit,
) {
    val timeState = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
    var template by rememberSaveable { mutableStateOf(ScheduleTemplate.School45) }
    var customName by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var lessonText by rememberSaveable { mutableStateOf(template.lessonMinutes.toString()) }
    var breakText by rememberSaveable { mutableStateOf(template.breakMinutes.toString()) }
    var countText by rememberSaveable { mutableStateOf(template.lessonCount.toString()) }
    var daysMask by rememberSaveable { mutableIntStateOf(0x1F) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    val templateName = stringResource(template.groupNameRes)
    val groupName = if (nameEdited) customName else templateName
    val days = (1..7).filter { daysMask and (1 shl (it - 1)) != 0 }.toSet()

    val invalidMessage = stringResource(R.string.generator_invalid)
    val tooManyMessage = stringResource(R.string.generator_too_many)
    val tooLongMessage = stringResource(R.string.generator_too_long)
    val tooManyGroupsMessage = stringResource(R.string.generator_too_many_groups)
    val lessonStartLabels = (1..32).map { stringResource(R.string.generator_lesson_start, it) }
    val lessonEndLabels = (1..32).map { stringResource(R.string.generator_lesson_end, it) }

    // Apply the selected preset to the editable fields.
    LaunchedEffect(template) {
        lessonText = template.lessonMinutes.toString()
        breakText = template.breakMinutes.toString()
        countText = template.lessonCount.toString()
        nameEdited = false
        daysMask = template.days.fold(0) { mask, day -> mask or (1 shl (day - 1)) }
        template.firstLessonMinutes?.let { first ->
            timeState.hour = first / 60
            timeState.minute = first % 60
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generator_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.generator_template),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleTemplate.entries.forEach { option ->
                        FilterChip(
                            selected = option == template,
                            onClick = { template = option },
                            label = { Text(stringResource(option.titleRes)) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.generator_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = groupName,
                    onValueChange = {
                        customName = it
                        nameEdited = true
                    },
                    label = { Text(stringResource(R.string.generator_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.generator_first_lesson),
                    style = MaterialTheme.typography.labelLarge,
                )
                TimeInput(state = timeState)

                OutlinedTextField(
                    value = lessonText,
                    onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) lessonText = it },
                    label = { Text(stringResource(R.string.generator_lesson_length)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = breakText,
                    onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) breakText = it },
                    label = { Text(stringResource(R.string.generator_break_length)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = countText,
                    onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) countText = it },
                    label = { Text(stringResource(R.string.generator_count)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.generator_days),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { daysMask = 0x1F },
                        label = { Text(stringResource(R.string.days_weekdays)) },
                    )
                    AssistChip(
                        onClick = { daysMask = 0x7F },
                        label = { Text(stringResource(R.string.days_every_day)) },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (day in 1..7) {
                        FilterChip(
                            selected = day in days,
                            onClick = { daysMask = daysMask xor (1 shl (day - 1)) },
                            label = { Text(stringResource(dayLabelRes(day))) },
                        )
                    }
                }

                countText.toIntOrNull()?.takeIf { it > 0 }?.let { count ->
                    Text(
                        text = stringResource(R.string.generator_preview, count * 2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                errorText?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lesson = lessonText.toIntOrNull()
                    val breakLength = breakText.toIntOrNull()
                    val count = countText.toIntOrNull()
                    if (lesson == null || breakLength == null || count == null ||
                        lesson <= 0 || breakLength < 0 || count <= 0
                    ) {
                        errorText = invalidMessage
                        return@TextButton
                    }
                    if (days.isEmpty() || groupName.isBlank()) {
                        errorText = invalidMessage
                        return@TextButton
                    }
                    if (existingGroups >= MAX_SCHEDULE_GROUPS) {
                        errorText = tooManyGroupsMessage
                        return@TextButton
                    }
                    if (existingEntries + count * 2 > MAX_SCHEDULE_ENTRIES) {
                        errorText = tooManyMessage
                        return@TextButton
                    }
                    val first = timeState.hour * 60 + timeState.minute
                    val lastEnd = first + (count - 1) * (lesson + breakLength) + lesson
                    if (lastEnd > 1439) {
                        errorText = tooLongMessage
                        return@TextButton
                    }
                    val generated = mutableListOf<BellEntry>()
                    for (index in 0 until count) {
                        val start = first + index * (lesson + breakLength)
                        val end = start + lesson
                        generated += BellEntry(
                            time = minutesToTime(start),
                            days = days,
                            duration = defaultDuration,
                            label = lessonStartLabels[index],
                        )
                        generated += BellEntry(
                            time = minutesToTime(end),
                            days = days,
                            duration = defaultDuration,
                            label = lessonEndLabels[index],
                        )
                    }
                    onConfirm(groupName.trim(), days, generated)
                },
            ) {
                Text(stringResource(R.string.generator_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
