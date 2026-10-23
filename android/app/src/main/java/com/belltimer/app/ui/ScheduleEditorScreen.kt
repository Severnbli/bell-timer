package com.belltimer.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import com.belltimer.app.data.MAX_LABEL_BYTES
import com.belltimer.app.data.MAX_PACK_ENTRIES
import com.belltimer.app.data.PackKind
import com.belltimer.app.data.SchedulePack
import com.belltimer.app.data.WEEKDAYS
import com.belltimer.app.data.canAddDate
import com.belltimer.app.data.canAddEntry
import com.belltimer.app.data.canAddPack
import com.belltimer.app.data.labelTooLong
import com.belltimer.app.data.packNameTooLong
import com.belltimer.app.data.shiftBy
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorScreen(
    state: UiState,
    viewModel: AppViewModel,
    packId: Int,
    onBack: () -> Unit,
) {
    val schedule = state.schedule
    val pack = schedule.packById(packId)

    if (pack == null) {
        LaunchedEffect(packId) { onBack() }
        return
    }

    var addingEntry by remember { mutableStateOf(false) }
    var entryIndex by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showBuilder by remember { mutableStateOf(false) }
    var shiftSheet by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var editFrom by remember { mutableStateOf(false) }
    var editTo by remember { mutableStateOf(false) }
    var addDate by remember { mutableStateOf(false) }

    val entryDeletedMessage = stringResource(R.string.schedule_entry_deleted)
    val clearedMessage = stringResource(R.string.schedule_pack_cleared)
    val shiftOverflowMessage = stringResource(R.string.schedule_shift_overflow)
    val copySuffix = stringResource(R.string.schedule_copy_suffix)
    val tooManyPacksMessage = stringResource(R.string.error_too_many_packs)

    BackHandler { onBack() }

    fun commit(updated: SchedulePack, undoable: Boolean = false) {
        val current = viewModel.state.value.schedule
        viewModel.updateSchedule(current.withPack(updated), undoable = undoable)
    }

    fun currentPack(): SchedulePack? = viewModel.state.value.schedule.packById(packId)

    val newEntryDays = pack.weekdayUnion.ifEmpty { WEEKDAYS }

    if (showBuilder) {
        ScheduleBuilderScreen(
            existingEntries = 0,
            days = newEntryDays,
            onDismiss = { showBuilder = false },
            onApply = { entries ->
                currentPack()?.let { commit(it.withEntries(entries), undoable = true) }
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
                        value = pack.name,
                        onValueChange = { newName ->
                            val current = currentPack()
                            if (current != null && !packNameTooLong(newName)) {
                                commit(current.copy(name = newName))
                            }
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
                                    val current = currentPack()
                                    if (current != null) {
                                        if (!schedule.canAddPack()) {
                                            viewModel.reportMessage(tooManyPacksMessage)
                                        } else {
                                            val clone = current.copy(
                                                id = schedule.nextPackId(),
                                                name = current.name + " " + copySuffix,
                                            )
                                            viewModel.updateSchedule(schedule.withPack(clone))
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.updateSchedule(
                                        schedule.withoutPack(pack.id),
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
            item(key = "status") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.schedule_pack_enabled),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val active = pack.appliesOn(LocalDate.now())
                        Text(
                            text = stringResource(
                                if (active) {
                                    R.string.schedule_pack_status_active
                                } else {
                                    R.string.schedule_pack_status_inactive
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Switch(
                        checked = pack.enabled,
                        onCheckedChange = { enabled ->
                            currentPack()?.let { commit(it.copy(enabled = enabled)) }
                        },
                    )
                }
            }

            item(key = "kind") {
                SectionHeader(text = stringResource(R.string.schedule_pack_type_title))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = pack.kind == PackKind.Base,
                        onClick = {
                            currentPack()?.let {
                                commit(it.copy(kind = PackKind.Base, mute = false))
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.schedule_pack_kind_base))
                    }
                    SegmentedButton(
                        selected = pack.kind == PackKind.Correction,
                        onClick = {
                            currentPack()?.let { commit(it.copy(kind = PackKind.Correction)) }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.schedule_pack_kind_correction))
                    }
                }
                Text(
                    text = stringResource(
                        if (pack.kind == PackKind.Correction) {
                            R.string.schedule_pack_kind_correction_hint
                        } else {
                            R.string.schedule_pack_kind_base_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item(key = "validity") {
                SectionHeader(text = stringResource(R.string.schedule_validity_title))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButtonBlock(
                        label = stringResource(R.string.schedule_validity_from),
                        value = pack.window.from?.fullDate()
                            ?: stringResource(R.string.auto_window_not_set),
                        onClick = { editFrom = true },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButtonBlock(
                        label = stringResource(R.string.schedule_validity_to),
                        value = pack.window.to?.fullDate()
                            ?: stringResource(R.string.auto_window_not_set),
                        onClick = { editTo = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = pack.window.yearly,
                        onCheckedChange = { yearly ->
                            currentPack()?.let {
                                commit(it.copy(window = it.window.copy(yearly = yearly)))
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.schedule_validity_yearly),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!pack.window.isValid) {
                    Text(
                        text = stringResource(R.string.schedule_validity_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.schedule_validity_dates),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val current = currentPack()
                            if (current != null && current.canAddDate()) {
                                addDate = true
                            }
                        },
                    ) {
                        Text(stringResource(R.string.schedule_validity_add_date))
                    }
                }
                if (pack.window.dates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.auto_window_not_set),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pack.window.dates.sorted().forEach { date ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    currentPack()?.let {
                                        commit(
                                            it.copy(
                                                window = it.window.copy(
                                                    dates = it.window.dates - date,
                                                ),
                                            ),
                                        )
                                    }
                                },
                                label = { Text(date.fullDate()) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.delete),
                                    )
                                },
                            )
                        }
                    }
                }
                if (pack.window.isSet) {
                    TextButton(
                        onClick = {
                            currentPack()?.let {
                                commit(
                                    it.copy(
                                        window = it.window.copy(
                                            from = null,
                                            to = null,
                                            dates = emptySet(),
                                        ),
                                    ),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.schedule_validity_clear))
                    }
                }
            }

            item(key = "entries-header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(
                        text = stringResource(R.string.schedule_entries_title) + " · " +
                            stringResource(
                                R.string.schedule_pack_entries_count,
                                pack.entries.size,
                                MAX_PACK_ENTRIES,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { clearConfirm = true },
                        enabled = pack.entries.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.schedule_pack_clear))
                    }
                }
            }

            itemsIndexed(pack.entries, key = { index, entry -> "entry-$index-${entry.time}" }) { index, entry ->
                SwipeToDeleteBox(
                    onDelete = {
                        val current = currentPack()
                        if (current != null) {
                            commit(current.withoutEntry(index), undoable = true)
                            viewModel.reportMessage(entryDeletedMessage)
                        }
                    },
                ) {
                    BellRow(entry = entry, onClick = { entryIndex = index })
                }
                RowDivider()
            }

            item(key = "add-entry") {
                TextButton(
                    onClick = { addingEntry = true },
                    enabled = pack.canAddEntry(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.schedule_add_entry))
                }
            }

            item(key = "tools") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showBuilder = true }) {
                        Text(stringResource(R.string.schedule_build))
                    }
                    TextButton(
                        onClick = { shiftSheet = true },
                        enabled = pack.entries.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.schedule_shift))
                    }
                }
            }

            item(key = "bottom-space") { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }

    if (addingEntry) {
        BellEntrySheet(
            initial = null,
            defaultDays = newEntryDays,
            onDismiss = { addingEntry = false },
            onConfirm = { entry ->
                addingEntry = false
                currentPack()?.let { commit(it.withEntries(it.entries + entry), undoable = true) }
            },
        )
    }

    entryIndex?.let { index ->
        val entry = pack.entries.getOrNull(index)
        if (entry != null) {
            BellEntrySheet(
                initial = entry,
                defaultDays = entry.days.ifEmpty { newEntryDays },
                onDismiss = { entryIndex = null },
                onConfirm = { updated ->
                    entryIndex = null
                    currentPack()?.let { commit(it.withEntry(index, updated), undoable = true) }
                },
            )
        }
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
                val current = currentPack()
                if (current != null) {
                    val shifted = current.entries.shiftBy(delta)
                    if (shifted.overflow) {
                        viewModel.reportMessage(shiftOverflowMessage)
                    } else {
                        commit(current.withEntries(shifted.entries), undoable = true)
                    }
                }
            },
        )
    }

    if (clearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.schedule_pack_clear_title),
            message = stringResource(R.string.schedule_pack_clear_text),
            confirmLabel = stringResource(R.string.schedule_pack_clear),
            onConfirm = {
                clearConfirm = false
                currentPack()?.let { commit(it.withEntries(emptyList()), undoable = true) }
                viewModel.reportMessage(clearedMessage)
            },
            onDismiss = { clearConfirm = false },
        )
    }

    if (editFrom) {
        DateSheet(
            initial = pack.window.from ?: LocalDate.now(),
            onDismiss = { editFrom = false },
            onPick = { date ->
                editFrom = false
                currentPack()?.let {
                    commit(it.copy(window = it.window.copy(from = date)))
                }
            },
        )
    }

    if (editTo) {
        DateSheet(
            initial = pack.window.to ?: LocalDate.now(),
            onDismiss = { editTo = false },
            onPick = { date ->
                editTo = false
                currentPack()?.let {
                    commit(it.copy(window = it.window.copy(to = date)))
                }
            },
        )
    }

    if (addDate) {
        DateSheet(
            initial = pack.window.dates.maxOrNull() ?: LocalDate.now(),
            onDismiss = { addDate = false },
            onPick = { date ->
                addDate = false
                currentPack()?.let {
                    commit(it.copy(window = it.window.copy(dates = it.window.dates + date)))
                }
            },
        )
    }
}

/** Time, optional pair, weekdays and label of one entry. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BellEntrySheet(
    initial: BellEntry?,
    defaultDays: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (BellEntry) -> Unit,
) {
    var minute by rememberSaveable(initial) {
        mutableIntStateOf(
            initial?.minutes?.takeIf { it != BellEntry.INVALID_MINUTE } ?: 8 * 60,
        )
    }
    var pairEnabled by rememberSaveable(initial) { mutableStateOf(initial?.pair != null) }
    var pairMinute by rememberSaveable(initial) {
        mutableIntStateOf(
            initial?.pairMinutes ?: ((minute + 45) % (24 * 60)),
        )
    }
    var days by remember(initial) {
        mutableStateOf(initial?.days?.ifEmpty { defaultDays } ?: defaultDays)
    }
    var label by rememberSaveable(initial) { mutableStateOf(initial?.label.orEmpty()) }
    var timeSheet by remember { mutableStateOf(false) }
    var pairSheet by remember { mutableStateOf(false) }

    val labelError = labelTooLong(label)
    val labelErrorMessage = stringResource(R.string.schedule_label_too_long, MAX_LABEL_BYTES)
    val noDays = days.isEmpty()
    val samePair = pairEnabled && pairMinute == minute

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
                    if (initial == null) {
                        R.string.schedule_entry_add_title
                    } else {
                        R.string.schedule_entry_edit_title
                    },
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.schedule_entry_pair),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = pairEnabled, onCheckedChange = { pairEnabled = it })
            }
            if (pairEnabled) {
                OutlinedButton(
                    onClick = { pairSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.schedule_entry_pair_time),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = minutesToTime(pairMinute),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                            ),
                        )
                    }
                }
                if (samePair) {
                    Text(
                        text = stringResource(R.string.schedule_entry_pair_same),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                text = stringResource(R.string.schedule_entry_days),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (day in 1..7) {
                    FilterChip(
                        selected = day in days,
                        onClick = {
                            days = if (day in days) days - day else days + day
                        },
                        label = { Text(weekdayName(day)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { days = WEEKDAYS },
                    label = { Text(stringResource(R.string.days_weekdays)) },
                )
                AssistChip(
                    onClick = { days = ALL_DAYS },
                    label = { Text(stringResource(R.string.days_every_day)) },
                )
            }
            if (noDays) {
                Text(
                    text = stringResource(R.string.schedule_entry_days_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !labelError && !noDays && !samePair,
                    onClick = {
                        onConfirm(
                            BellEntry(
                                time = minutesToTime(minute),
                                pair = if (pairEnabled) minutesToTime(pairMinute) else null,
                                days = days,
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
    if (pairSheet) {
        TimeSheet(
            title = stringResource(R.string.schedule_entry_pair_time),
            initialMinute = pairMinute,
            onDismiss = { pairSheet = false },
            onPick = { picked ->
                pairMinute = picked
                pairSheet = false
            },
        )
    }
}
