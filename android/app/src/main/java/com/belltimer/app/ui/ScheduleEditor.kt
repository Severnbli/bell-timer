package com.belltimer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belltimer.app.R
import com.belltimer.app.data.AutoWindow
import com.belltimer.app.data.BellEntry
import com.belltimer.app.data.DateException
import com.belltimer.app.data.ScheduleGroup
import com.belltimer.app.data.WEEKDAYS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun LocalDate.displayDate(): String = format(DATE_FORMATTER)

@Composable
fun ScheduleSectionHeader(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ScheduleGroupCard(
    group: ScheduleGroup,
    canAddEntry: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddEntry: () -> Unit,
    onEditEntry: (Int) -> Unit,
    onDeleteEntry: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name.ifBlank { stringResource(R.string.schedule_group_untitled) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = daysSummary(group.days) + " · " + stringResource(
                            R.string.schedule_group_entries,
                            group.entries.size,
                        ),
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

            if (group.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedule_group_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            } else {
                group.entries.forEachIndexed { index, entry ->
                    ScheduleEntryRow(
                        entry = entry,
                        groupDays = group.days,
                        onEdit = { onEditEntry(index) },
                        onDelete = { onDeleteEntry(index) },
                    )
                }
            }

            OutlinedButton(
                onClick = onAddEntry,
                enabled = canAddEntry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.schedule_group_add_bell))
            }
        }
    }
}

@Composable
private fun ScheduleEntryRow(
    entry: BellEntry,
    groupDays: Set<Int>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = entry.time,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label.ifBlank { stringResource(R.string.schedule_no_label) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (entry.days != groupDays) append(daysSummary(entry.days)).append(" · ")
                    append(stringResource(R.string.duration_seconds, entry.duration))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.edit_desc),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete_desc),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun DateExceptionCard(
    exception: DateException,
    groupName: String?,
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
            Icon(
                imageVector = if (exception.mute) Icons.Default.EventBusy else Icons.Default.EventRepeat,
                contentDescription = null,
                tint = if (exception.mute) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exception.date.displayDate(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        exception.mute -> stringResource(R.string.schedule_exception_muted)
                        groupName != null -> stringResource(R.string.schedule_exception_uses_group, groupName)
                        else -> stringResource(R.string.schedule_exception_invalid)
                    },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupDialog(
    initial: ScheduleGroup?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, days: Set<Int>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var daysMask by rememberSaveable {
        mutableIntStateOf(initial?.days?.fold(0) { mask, day -> mask or (1 shl (day - 1)) } ?: 0x1F)
    }
    var showError by rememberSaveable { mutableStateOf(false) }
    val days = (1..7).filter { daysMask and (1 shl (it - 1)) != 0 }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.schedule_group_title_new
                    else R.string.schedule_group_title_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.schedule_group_name)) },
                    placeholder = { Text(stringResource(R.string.schedule_group_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.schedule_group_days),
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
                if (showError && (name.isBlank() || days.isEmpty())) {
                    Text(
                        text = stringResource(
                            if (name.isBlank()) R.string.schedule_group_name_required
                            else R.string.schedule_group_invalid_days,
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
                    if (name.isBlank() || days.isEmpty()) {
                        showError = true
                    } else {
                        onConfirm(name.trim(), days)
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
fun DateExceptionDialog(
    initial: DateException?,
    groups: List<ScheduleGroup>,
    takenDates: Set<LocalDate>,
    onDismiss: () -> Unit,
    onConfirm: (DateException) -> Unit,
) {
    var dateEpochDay by rememberSaveable {
        mutableIntStateOf((initial?.date ?: LocalDate.now()).toEpochDay().toInt())
    }
    val date = LocalDate.ofEpochDay(dateEpochDay.toLong())
    var mute by rememberSaveable { mutableStateOf(initial?.mute ?: false) }
    var groupId by rememberSaveable {
        mutableIntStateOf(initial?.groupId ?: groups.firstOrNull()?.id ?: 0)
    }
    var showPicker by remember { mutableStateOf(false) }
    var showError by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_exception_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.schedule_exception_date),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(date.displayDate())
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.schedule_exception_mute),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = mute, onCheckedChange = { mute = it })
                }

                if (!mute) {
                    Text(
                        text = stringResource(R.string.schedule_exception_group),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (groups.isEmpty()) {
                        Text(
                            text = stringResource(R.string.schedule_no_groups),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            groups.forEach { group ->
                                FilterChip(
                                    selected = group.id == groupId,
                                    onClick = { groupId = group.id },
                                    label = {
                                        Text(
                                            group.name.ifBlank {
                                                stringResource(R.string.schedule_group_untitled)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                val duplicate = date != initial?.date && date in takenDates
                if (duplicate || (showError && !mute && groupId == 0)) {
                    Text(
                        text = stringResource(
                            if (duplicate) R.string.schedule_exception_duplicate
                            else R.string.schedule_exception_no_group,
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
                    val duplicate = date != initial?.date && date in takenDates
                    if (duplicate) {
                        showError = true
                    } else if (!mute && groupId == 0) {
                        showError = true
                    } else {
                        onConfirm(
                            DateException(
                                date = date,
                                mute = mute,
                                groupId = if (mute) null else groupId,
                            ),
                        )
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

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            dateEpochDay = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay()
                                .toInt()
                        }
                        showPicker = false
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
fun AutoWindowCard(
    auto: AutoWindow,
    onChange: (AutoWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingStart by remember { mutableStateOf(false) }
    var editingStop by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.schedule_auto_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (auto.isSet) {
                    TextButton(onClick = { onChange(AutoWindow()) }) {
                        Text(stringResource(R.string.schedule_auto_clear))
                    }
                }
            }
            Text(
                text = stringResource(R.string.schedule_auto_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { editingStart = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.schedule_auto_start),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = auto.start?.displayDate()
                                ?: stringResource(R.string.schedule_auto_not_set),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { editingStop = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.schedule_auto_stop),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = auto.stop?.displayDate()
                                ?: stringResource(R.string.schedule_auto_not_set),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (!auto.isValid) {
                Text(
                    text = stringResource(R.string.schedule_auto_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (editingStart) {
        DatePickerSheet(
            initial = auto.start ?: LocalDate.now(),
            onDismiss = { editingStart = false },
            onPick = { picked ->
                onChange(auto.copy(start = picked))
                editingStart = false
            },
        )
    }
    if (editingStop) {
        DatePickerSheet(
            initial = auto.stop ?: LocalDate.now(),
            onDismiss = { editingStop = false },
            onPick = { picked ->
                onChange(auto.copy(stop = picked))
                editingStop = false
            },
        )
    }
}

/** Shared date picker dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    } ?: onDismiss()
                },
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/** Template presets for the schedule generator. */
enum class ScheduleTemplate(
    val titleRes: Int,
    val groupNameRes: Int,
    val lessonMinutes: Int,
    val breakMinutes: Int,
    val lessonCount: Int,
    val firstLessonMinutes: Int?,
    val days: Set<Int>,
) {
    Custom(
        R.string.generator_template_custom,
        R.string.generator_group_default,
        45,
        10,
        7,
        null,
        WEEKDAYS,
    ),
    School45(
        R.string.generator_template_school,
        R.string.generator_group_school,
        45,
        10,
        7,
        8 * 60,
        WEEKDAYS,
    ),
    School40(
        R.string.generator_template_school_short,
        R.string.generator_group_school,
        40,
        10,
        6,
        8 * 60,
        WEEKDAYS,
    ),
    College(
        R.string.generator_template_college,
        R.string.generator_group_college,
        45,
        5,
        8,
        8 * 60,
        WEEKDAYS,
    ),
    University(
        R.string.generator_template_university,
        R.string.generator_group_university,
        90,
        10,
        4,
        9 * 60,
        WEEKDAYS,
    ),
}
