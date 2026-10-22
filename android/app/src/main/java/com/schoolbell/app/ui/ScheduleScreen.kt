package com.schoolbell.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schoolbell.app.AppViewModel
import com.schoolbell.app.ConnectionState
import com.schoolbell.app.R
import com.schoolbell.app.UiState
import com.schoolbell.app.data.BellEntry
import com.schoolbell.app.data.MAX_RING_SECONDS
import com.schoolbell.app.data.MAX_SCHEDULE_ENTRIES
import com.schoolbell.app.data.MIN_RING_SECONDS

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
                        state.schedule.size,
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

        Button(
            onClick = {
                editorIndex = null
                showEditor = true
            },
            enabled = state.schedule.size < MAX_SCHEDULE_ENTRIES,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.schedule_add),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showGenerator = true },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.EventRepeat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.generator_button))
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
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.schedule_load))
            }
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
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.schedule_empty),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.schedule_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
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
private fun daysSummary(days: Set<Int>): String = when {
    days.isEmpty() -> stringResource(R.string.schedule_entry_invalid_days)
    days.size == 7 -> stringResource(R.string.days_every_day)
    days == setOf(1, 2, 3, 4, 5) -> stringResource(R.string.days_weekdays)
    else -> days.sorted().map { stringResource(dayLabelRes(it)) }.joinToString(", ")
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
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = entry.time,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label.ifBlank { stringResource(R.string.schedule_no_label) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = daysSummary(entry.days) + " · " +
                        stringResource(R.string.duration_seconds, entry.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                ?: 0x1F,
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
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.generator_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
