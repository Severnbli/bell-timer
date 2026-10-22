package com.belltimer.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.BellEntry
import com.belltimer.app.data.DayOverride
import com.belltimer.app.data.DayOverrideMode
import com.belltimer.app.data.canAddEntries
import com.belltimer.app.data.sortedSchedule
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DayEditorScreen(
    state: UiState,
    viewModel: AppViewModel,
    date: LocalDate,
    onBack: () -> Unit,
) {
    val schedule = state.schedule
    val stored = schedule.overrideFor(date)
    val baseGroup = schedule.baseGroupFor(date)
    val defaultCopy = baseGroup?.entries.orEmpty()

    var mode by remember(date) { mutableStateOf(stored?.mode ?: DayOverrideMode.Replace) }
    var entries by remember(date) { mutableStateOf(stored?.entries ?: defaultCopy) }
    var replaceDraft by remember(date) {
        mutableStateOf(
            if (stored?.mode == DayOverrideMode.Replace) stored.entries else defaultCopy,
        )
    }
    var entryIndex by remember { mutableStateOf<Int?>(null) }
    var addingEntry by remember { mutableStateOf(false) }
    var showBuilder by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var repeatSheet by remember { mutableStateOf(false) }

    val basedOnId = if (mode == DayOverrideMode.Replace) baseGroup?.id else stored?.basedOnGroupId
    val basedOnGroup = schedule.groupById(basedOnId)

    BackHandler { onBack() }

    fun commit(newMode: DayOverrideMode, newEntries: List<BellEntry>) {
        viewModel.updateSchedule(
            viewModel.state.value.schedule.withOverride(
                DayOverride(
                    date = date,
                    mode = newMode,
                    entries = if (newMode == DayOverrideMode.Mute) emptyList() else newEntries,
                    basedOnGroupId = basedOnId,
                ),
            ),
            undoable = true,
        )
    }

    fun switchMode(newMode: DayOverrideMode) {
        if (newMode == mode) return
        if (mode == DayOverrideMode.Replace) replaceDraft = entries
        val next = when (newMode) {
            DayOverrideMode.Mute -> emptyList()
            DayOverrideMode.Replace -> replaceDraft
            DayOverrideMode.Add -> emptyList()
        }
        mode = newMode
        entries = next
        commit(newMode, next)
    }

    fun updateEntries(newEntries: List<BellEntry>) {
        entries = newEntries
        commit(mode, newEntries)
    }

    val preview = schedule.withOverride(
        DayOverride(
            date = date,
            mode = mode,
            entries = if (mode == DayOverrideMode.Mute) emptyList() else entries,
            basedOnGroupId = basedOnId,
        ),
    ).bellsFor(date)
    val extraTimes = if (mode == DayOverrideMode.Add) entries.map { it.time }.toSet() else emptySet()

    if (showBuilder) {
        ScheduleBuilderScreen(
            defaultDuration = state.ringSeconds,
            existingEntries = entries.size,
            onDismiss = { showBuilder = false },
            onApply = { built ->
                showBuilder = false
                updateEntries((entries + built).sortedSchedule())
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.day_title,
                            date.fullDate(),
                            weekdayName(date.dayOfWeek.value),
                        ),
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
                                text = { Text(stringResource(R.string.day_repeat)) },
                                onClick = {
                                    menuOpen = false
                                    repeatSheet = true
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
            item(key = "normal") {
                Text(
                    text = if (baseGroup != null) {
                        stringResource(
                            R.string.day_normal_schedule,
                            baseGroup.displayName(),
                            baseGroup.entries.size,
                        )
                    } else {
                        stringResource(R.string.day_normal_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item(key = "modes") {
                ModeRow(
                    label = stringResource(R.string.day_mode_mute),
                    selected = mode == DayOverrideMode.Mute,
                    onClick = { switchMode(DayOverrideMode.Mute) },
                )
                ModeRow(
                    label = stringResource(R.string.day_mode_replace),
                    selected = mode == DayOverrideMode.Replace,
                    onClick = { switchMode(DayOverrideMode.Replace) },
                )
                ModeRow(
                    label = stringResource(R.string.day_mode_add),
                    selected = mode == DayOverrideMode.Add,
                    onClick = { switchMode(DayOverrideMode.Add) },
                )
            }

            if (mode != DayOverrideMode.Mute) {
                if (mode == DayOverrideMode.Replace && basedOnGroup != null) {
                    item(key = "based-on") {
                        Text(
                            text = stringResource(
                                R.string.day_based_on,
                                basedOnGroup.displayName(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }

                item(key = "entries-header") {
                    val titleRes = if (mode == DayOverrideMode.Add) {
                        R.string.day_extra_title
                    } else {
                        R.string.schedule_bells_title
                    }
                    SectionHeader(
                        text = stringResource(titleRes) + " · " +
                            stringResource(R.string.schedule_bells_count, entries.size),
                    )
                }

                itemsIndexed(
                    entries,
                    key = { index, entry -> "day-bell-$index-${entry.time}" },
                ) { index, entry ->
                    SwipeToDeleteBox(
                        onDelete = {
                            updateEntries(entries.filterIndexed { at, _ -> at != index })
                        },
                    ) {
                        BellRow(entry = entry, onClick = { entryIndex = index })
                    }
                    RowDivider()
                }

                item(key = "add-entry") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { addingEntry = true },
                            enabled = schedule.canAddEntries(1),
                        ) {
                            Text(stringResource(R.string.schedule_add_bell))
                        }
                        TextButton(onClick = { showBuilder = true }) {
                            Text(stringResource(R.string.schedule_build))
                        }
                    }
                }
            }

            item(key = "preview") {
                SectionHeader(text = stringResource(R.string.day_preview_title))
                if (preview.isEmpty()) {
                    Text(
                        text = stringResource(R.string.schedule_timeline_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        preview.forEach { entry ->
                            val extra = entry.time in extraTimes
                            Text(
                                text = entry.time,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                                ),
                                fontWeight = if (extra) FontWeight.Bold else FontWeight.Normal,
                                color = if (extra) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }

            item(key = "bottom-space") { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }

    if (addingEntry) {
        BellEntrySheet(
            initial = null,
            defaultDuration = state.ringSeconds,
            days = emptySet(),
            onDismiss = { addingEntry = false },
            onConfirm = { entry ->
                addingEntry = false
                updateEntries((entries + entry).sortedSchedule())
            },
        )
    }

    entryIndex?.let { index ->
        val entry = entries.getOrNull(index)
        if (entry != null) {
            BellEntrySheet(
                initial = entry,
                defaultDuration = state.ringSeconds,
                days = emptySet(),
                onDismiss = { entryIndex = null },
                onConfirm = { updated ->
                    entryIndex = null
                    updateEntries(
                        entries.mapIndexed { at, item ->
                            if (at == index) updated else item
                        }.sortedSchedule(),
                    )
                },
            )
        }
    }

    if (repeatSheet) {
        RepeatDatesSheet(
            initialDate = date,
            onDismiss = { repeatSheet = false },
            onCreate = { dates ->
                repeatSheet = false
                viewModel.addOverrides(
                    template = DayOverride(
                        date = date,
                        mode = mode,
                        entries = if (mode == DayOverrideMode.Mute) {
                            emptyList()
                        } else {
                            entries.sortedSchedule()
                        },
                        basedOnGroupId = basedOnId,
                    ),
                    dates = dates,
                )
            },
        )
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RepeatDatesSheet(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onCreate: (List<LocalDate>) -> Unit,
) {
    var picked by remember { mutableStateOf(listOf<LocalDate>()) }
    var picking by remember { mutableStateOf(false) }

    ModalSheetText(title = stringResource(R.string.day_repeat), onDismiss = onDismiss) {
        if (picked.isEmpty()) {
            Text(
                text = stringResource(R.string.day_repeat_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                picked.forEach { date ->
                    TextButton(onClick = { picked = picked - date }) {
                        Text(date.fullDate())
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { picking = true }) {
                Text(stringResource(R.string.day_repeat_add))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onCreate(picked) }, enabled = picked.isNotEmpty()) {
                Text(stringResource(R.string.day_repeat_create))
            }
        }
    }

    if (picking) {
        DateSheet(
            initial = picked.lastOrNull() ?: initialDate.plusDays(1),
            onDismiss = { picking = false },
            onPick = { date ->
                picking = false
                if (date !in picked) picked = picked + date
            },
        )
    }
}
