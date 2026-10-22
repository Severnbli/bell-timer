package com.schoolbell.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schoolbell.app.AppViewModel
import com.schoolbell.app.ConnectionState
import com.schoolbell.app.R
import com.schoolbell.app.UiState
import com.schoolbell.app.data.BellEntry
import com.schoolbell.app.data.MAX_SCHEDULE_ENTRIES

@Composable
fun ScheduleScreen(state: UiState, viewModel: AppViewModel) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editorIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleteIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var showGenerator by rememberSaveable { mutableStateOf(false) }
    var showLoadConfirm by rememberSaveable { mutableStateOf(false) }
    val online = state.connection == ConnectionState.Online

    if (showEditor) {
        ScheduleEntryDialog(
            initial = editorIndex?.let { state.schedule.getOrNull(it) },
            defaultDuration = state.ringSeconds,
            onDismiss = {
                showEditor = false
                editorIndex = null
            },
            onConfirm = { entry ->
                val updated = state.schedule.toMutableList()
                val index = editorIndex
                if (index != null && index in updated.indices) {
                    updated[index] = entry
                } else {
                    updated.add(entry)
                }
                viewModel.updateSchedule(updated)
                showEditor = false
                editorIndex = null
            },
        )
    }

    deleteIndex?.let { index ->
        val entry = state.schedule.getOrNull(index)
        if (entry != null) {
            val title = "${entry.time} ${entry.label.ifBlank { stringResource(R.string.schedule_no_label) }}"
            AlertDialog(
                onDismissRequest = { deleteIndex = null },
                title = { Text(stringResource(R.string.schedule_delete_title)) },
                text = { Text(stringResource(R.string.schedule_delete_text, title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val updated = state.schedule.toMutableList()
                            if (index in updated.indices) updated.removeAt(index)
                            viewModel.updateSchedule(updated)
                            deleteIndex = null
                        },
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteIndex = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    if (showGenerator) {
        GeneratorDialog(
            defaultDuration = state.ringSeconds,
            existingCount = state.schedule.size,
            onDismiss = { showGenerator = false },
            onConfirm = { generated ->
                viewModel.updateSchedule(state.schedule + generated)
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.schedule_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (state.scheduleDirty) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (state.scheduleDirty) showLoadConfirm = true else viewModel.loadSchedule()
                },
                enabled = !state.loadingSchedule,
            ) {
                Text(stringResource(R.string.schedule_load))
            }
            OutlinedButton(onClick = { showGenerator = true }) {
                Text(stringResource(R.string.generator_button))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    editorIndex = null
                    showEditor = true
                },
                enabled = state.schedule.size < MAX_SCHEDULE_ENTRIES,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.schedule_add))
            }
        }

        Button(
            onClick = viewModel::saveSchedule,
            enabled = state.scheduleDirty && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.schedule_save))
        }

        if (state.loadingSchedule) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.schedule.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.schedule_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.schedule) { index, entry ->
                    ScheduleEntryCard(
                        entry = entry,
                        onEdit = {
                            editorIndex = index
                            showEditor = true
                        },
                        onDelete = { deleteIndex = index },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleEntryCard(
    entry: BellEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.time,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.label.ifBlank { stringResource(R.string.schedule_no_label) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                val daysText = entry.days.sorted()
                    .map { stringResource(dayLabelRes(it)) }
                    .joinToString(", ")
                Text(
                    text = daysText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.duration_seconds, entry.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit_desc),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_desc),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEntryDialog(
    initial: BellEntry?,
    defaultDuration: Int,
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
                ?: 0x7F,
        )
    }
    val days = (1..7).filter { daysMask and (1 shl (it - 1)) != 0 }.toSet()
    var durationText by rememberSaveable {
        mutableStateOf((initial?.duration ?: defaultDuration).toString())
    }
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var showError by rememberSaveable { mutableStateOf(false) }

    val duration = durationText.toIntOrNull()
    val durationValid = duration != null && duration in 1..60
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
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.schedule_entry_time),
                    style = MaterialTheme.typography.labelLarge,
                )
                TimeInput(state = timeState)

                Text(
                    text = stringResource(R.string.schedule_entry_days),
                    style = MaterialTheme.typography.labelLarge,
                )
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

                OutlinedTextField(
                    value = durationText,
                    onValueChange = { value ->
                        if (value.length <= 2 && value.all { it.isDigit() }) {
                            durationText = value
                        }
                    },
                    label = { Text(stringResource(R.string.schedule_entry_duration)) },
                    isError = durationText.isNotEmpty() && !durationValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                                duration = checkNotNull(duration),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorDialog(
    defaultDuration: Int,
    existingCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<BellEntry>) -> Unit,
) {
    val timeState = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
    var lessonText by rememberSaveable { mutableStateOf("45") }
    var breakText by rememberSaveable { mutableStateOf("10") }
    var countText by rememberSaveable { mutableStateOf("7") }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    val invalidMessage = stringResource(R.string.generator_invalid)
    val tooManyMessage = stringResource(R.string.generator_too_many)
    val tooLongMessage = stringResource(R.string.generator_too_long)
    val lessonStartLabels = (1..32).map { stringResource(R.string.generator_lesson_start, it) }
    val lessonEndLabels = (1..32).map { stringResource(R.string.generator_lesson_end, it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generator_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    if (existingCount + count * 2 > MAX_SCHEDULE_ENTRIES) {
                        errorText = tooManyMessage
                        return@TextButton
                    }
                    val first = timeState.hour * 60 + timeState.minute
                    val lastEnd = first + (count - 1) * (lesson + breakLength) + lesson
                    if (lastEnd > 1439) {
                        errorText = tooLongMessage
                        return@TextButton
                    }
                    val allDays = (1..7).toSet()
                    val generated = mutableListOf<BellEntry>()
                    for (index in 0 until count) {
                        val start = first + index * (lesson + breakLength)
                        val end = start + lesson
                        generated += BellEntry(
                            time = minutesToTime(start),
                            days = allDays,
                            duration = defaultDuration,
                            label = lessonStartLabels[index],
                        )
                        generated += BellEntry(
                            time = minutesToTime(end),
                            days = allDays,
                            duration = defaultDuration,
                            label = lessonEndLabels[index],
                        )
                    }
                    onConfirm(generated)
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
