package com.belltimer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.MAX_PACK_MODULES
import com.belltimer.app.data.PackKind
import com.belltimer.app.data.PackWindow
import com.belltimer.app.data.ScheduleModule
import com.belltimer.app.data.SchedulePack
import com.belltimer.app.data.canAddDate
import com.belltimer.app.data.canAddModules
import com.belltimer.app.data.canAddPack
import com.belltimer.app.data.packNameTooLong
import com.belltimer.app.data.shiftedBy
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackEditorScreen(
    state: UiState,
    viewModel: AppViewModel,
    packId: Int,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val schedule = state.schedule
    val pack = schedule.packById(packId) ?: return
    fun update(newPack: SchedulePack, undoable: Boolean = false, message: String? = null) =
        viewModel.updateSchedule(schedule.withPack(newPack), undoable, message)

    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableIntStateOf(NOT_EDITING) }
    var typeChooser by rememberSaveable { mutableStateOf(false) }
    var gridSheet by rememberSaveable { mutableStateOf(false) }
    var windowDialog by rememberSaveable { mutableStateOf(false) }
    var shiftDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val packDeleted = stringResource(R.string.pack_deleted)
    val moduleDeleted = stringResource(R.string.module_deleted)
    val cleared = stringResource(R.string.pack_cleared)
    val tooManyModules = stringResource(R.string.error_too_many_pack_modules)
    val tooManyPacks = stringResource(R.string.error_too_many_packs)
    val copySuffix = stringResource(R.string.pack_copy_suffix)
    val shiftOverflow = stringResource(R.string.shift_overflow)
    val muted = pack.kind == PackKind.Correction && pack.mute

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = { Text(pack.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pack_duplicate)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menu = false
                                if (!schedule.canAddPack()) {
                                    viewModel.reportMessage(tooManyPacks)
                                } else {
                                    val copy = pack.copy(
                                        id = schedule.nextPackId(),
                                        name = "${pack.name} $copySuffix".trim(),
                                    )
                                    viewModel.updateSchedule(schedule.withPack(copy))
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pack_shift)) },
                            leadingIcon = { Icon(Icons.Default.SwapVert, null) },
                            enabled = pack.modules.isNotEmpty(),
                            onClick = {
                                menu = false
                                shiftDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pack_clear)) },
                            leadingIcon = { Icon(Icons.Default.ClearAll, null) },
                            enabled = pack.modules.isNotEmpty(),
                            onClick = {
                                menu = false
                                confirmClear = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pack_delete)) },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = {
                                menu = false
                                confirmDelete = true
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!muted) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (pack.canAddModules()) typeChooser = true else viewModel.reportMessage(tooManyModules)
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.module_add)) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PackSettingsCard(pack = pack, onChange = { update(it) }, onWindow = { windowDialog = true })

            if (muted) {
                Banner(
                    icon = Icons.Default.BeachAccess,
                    text = stringResource(R.string.pack_holiday_hint),
                    container = AppColors.extra.correctionContainer,
                    content = AppColors.extra.onCorrectionContainer,
                )
            } else {
                SectionTitle(
                    text = stringResource(R.string.modules_title),
                    trailing = {
                        Text(
                            text = stringResource(R.string.modules_count, pack.modules.size, MAX_PACK_MODULES),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                if (pack.modules.isEmpty()) {
                    EmptyModules(onGrid = { gridSheet = true }, onAdd = { typeChooser = true })
                }
                pack.modules.forEachIndexed { index, module ->
                    ModuleCard(module = module, onClick = { editing = index })
                }
                if (pack.modules.isNotEmpty()) {
                    TextButton(onClick = { gridSheet = true }) {
                        Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.grid_open))
                    }
                }
            }
            // Room for the floating button.
            Spacer(Modifier.height(88.dp))
        }
    }

    if (typeChooser) {
        ModuleTypeSheet(
            onDismiss = { typeChooser = false },
            onPick = { type ->
                typeChooser = false
                editing = NEW_MODULE_BASE + type.ordinal
            },
            onGrid = {
                typeChooser = false
                gridSheet = true
            },
        )
    }
    if (editing != NOT_EDITING) {
        val isNew = editing >= NEW_MODULE_BASE
        val initial = if (isNew) {
            newModule(pack, com.belltimer.app.data.ModuleType.entries[editing - NEW_MODULE_BASE])
        } else {
            pack.modules.getOrNull(editing)
        }
        if (initial == null) {
            editing = NOT_EDITING
        } else {
            ModuleSheet(
                initial = initial,
                isNew = isNew,
                onDismiss = { editing = NOT_EDITING },
                onSave = { module ->
                    val index = editing
                    editing = NOT_EDITING
                    val modules = pack.modules.toMutableList()
                    if (isNew) modules += module else modules[index] = module
                    update(pack.withModules(modules))
                },
                onDelete = {
                    val index = editing
                    editing = NOT_EDITING
                    update(
                        pack.withModules(pack.modules.filterIndexed { i, _ -> i != index }),
                        undoable = true,
                        message = moduleDeleted,
                    )
                },
            )
        }
    }
    if (gridSheet) {
        LessonGridSheet(
            pack = pack,
            onDismiss = { gridSheet = false },
            onApply = { modules, replace ->
                gridSheet = false
                val result = if (replace) modules else pack.modules + modules
                update(pack.withModules(result), undoable = true)
            },
        )
    }
    if (windowDialog) {
        PackWindowDialog(
            pack = pack,
            onDismiss = { windowDialog = false },
            onSave = {
                windowDialog = false
                update(pack.copy(window = it))
            },
        )
    }
    if (shiftDialog) {
        ShiftDialog(
            onDismiss = { shiftDialog = false },
            onShift = { delta ->
                shiftDialog = false
                val moved = pack.modules.shiftedBy(delta)
                if (moved == null) viewModel.reportMessage(shiftOverflow) else update(pack.copy(modules = moved), undoable = true)
            },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.pack_clear_title),
            text = stringResource(R.string.pack_clear_text),
            confirm = stringResource(R.string.pack_clear),
            onConfirm = { update(pack.withModules(emptyList()), undoable = true, message = cleared) },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.pack_delete_title),
            text = stringResource(R.string.pack_delete_text, pack.displayName()),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                viewModel.updateSchedule(schedule.withoutPack(pack.id), undoable = true, message = packDeleted)
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private const val NOT_EDITING = -1
private const val NEW_MODULE_BASE = 1000

/** A new module that continues after the last one of the pack. */
private fun newModule(pack: SchedulePack, type: com.belltimer.app.data.ModuleType): ScheduleModule {
    val last = pack.modules.maxByOrNull { it.endMinute }
    val days = pack.modules.lastOrNull()?.days ?: com.belltimer.app.data.WEEKDAYS
    val start = last?.let { (it.endMinute + 10).coerceAtMost(22 * 60) } ?: (8 * 60)
    return ScheduleModule(
        type = type,
        start = start,
        until = (start + 8 * 60).coerceAtMost(23 * 60),
        days = days,
        length = last?.takeIf { it.type == com.belltimer.app.data.ModuleType.Lesson }?.length ?: 45,
    )
}

@Composable
private fun PackSettingsCard(pack: SchedulePack, onChange: (SchedulePack) -> Unit, onWindow: () -> Unit) {
    var name by remember(pack.id) { mutableStateOf(pack.name) }
    AppCard {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (!packNameTooLong(it)) onChange(pack.copy(name = it))
            },
            label = { Text(stringResource(R.string.pack_name)) },
            singleLine = true,
            isError = packNameTooLong(name),
            supportingText = if (packNameTooLong(name)) {
                { Text(stringResource(R.string.label_too_long)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = pack.kind == PackKind.Base,
                onClick = { onChange(pack.copy(kind = PackKind.Base, mute = false)) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text(stringResource(R.string.pack_kind_base)) }
            SegmentedButton(
                selected = pack.kind == PackKind.Correction,
                onClick = { onChange(pack.copy(kind = PackKind.Correction)) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text(stringResource(R.string.pack_kind_correction)) }
        }
        Text(
            text = stringResource(
                if (pack.kind == PackKind.Base) R.string.pack_kind_base_hint else R.string.pack_kind_correction_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (pack.kind == PackKind.Correction) {
            SettingRow(
                title = stringResource(R.string.pack_mute),
                subtitle = stringResource(R.string.pack_mute_hint),
                onClick = { onChange(pack.copy(mute = !pack.mute)) },
                trailing = { Switch(checked = pack.mute, onCheckedChange = { onChange(pack.copy(mute = it)) }) },
            )
        }
        SettingRow(
            title = stringResource(R.string.pack_enabled),
            subtitle = stringResource(if (pack.enabled) R.string.pack_enabled_on else R.string.pack_enabled_off),
            onClick = { onChange(pack.copy(enabled = !pack.enabled)) },
            trailing = { Switch(checked = pack.enabled, onCheckedChange = { onChange(pack.copy(enabled = it)) }) },
        )
        ThinDivider()
        SettingRow(
            title = stringResource(R.string.window_title),
            subtitle = windowSummary(pack.window),
            onClick = onWindow,
            trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        )
    }
}

@Composable
private fun ModuleCard(module: ScheduleModule, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ModuleBadge(module.type, dimmed = !module.isValid)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.timeRange(),
                    style = MaterialTheme.typography.titleMedium.merge(Tabular),
                )
                Text(
                    text = module.label.ifBlank { moduleTypeName(module.type) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = module.details(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (module.isValid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyModules(onGrid: () -> Unit, onAdd: () -> Unit) {
    AppCard {
        Text(stringResource(R.string.modules_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.modules_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallTonalButton(stringResource(R.string.grid_open), Icons.Default.GridView, onGrid)
            SmallTonalButton(stringResource(R.string.module_add), Icons.Default.Add, onAdd)
        }
    }
}

@Composable
private fun PackWindowDialog(pack: SchedulePack, onDismiss: () -> Unit, onSave: (PackWindow) -> Unit) {
    var window by remember { mutableStateOf(pack.window) }
    var picking by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.window_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.window_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DateRow(stringResource(R.string.date_from), window.from, { picking = 0 }, { window = window.copy(from = null) })
                DateRow(stringResource(R.string.date_to), window.to, { picking = 1 }, { window = window.copy(to = null) })
                SettingRow(
                    title = stringResource(R.string.window_yearly),
                    subtitle = stringResource(R.string.window_yearly_hint),
                    onClick = { window = window.copy(yearly = !window.yearly) },
                    trailing = { Switch(checked = window.yearly, onCheckedChange = { window = window.copy(yearly = it) }) },
                )
                Caption(stringResource(R.string.window_dates))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (date in window.dates.sorted()) {
                        InputChip(
                            selected = false,
                            onClick = { window = window.copy(dates = window.dates - date) },
                            label = { Text(if (window.yearly) date.shortDate() else date.fullDate()) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
                        )
                    }
                }
                TextButton(
                    onClick = { picking = 2 },
                    enabled = pack.copy(window = window).canAddDate(),
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.window_add_date))
                }
                if (!window.isValid) {
                    Text(
                        stringResource(R.string.issue_pack_window),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(window) }, enabled = window.isValid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (window.isSet) {
                    TextButton(onClick = { window = PackWindow() }) { Text(stringResource(R.string.clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    picking?.let { which ->
        DatePickerDialog(
            initial = when (which) {
                0 -> window.from
                1 -> window.to
                else -> window.dates.maxOrNull()?.plusDays(1) ?: LocalDate.now()
            },
            onDismiss = { picking = null },
            onConfirm = {
                window = when (which) {
                    0 -> window.copy(from = it)
                    1 -> window.copy(to = it)
                    else -> window.copy(dates = window.dates + it)
                }
                picking = null
            },
        )
    }
}

@Composable
private fun ShiftDialog(onDismiss: () -> Unit, onShift: (Int) -> Unit) {
    var delta by rememberSaveable { mutableIntStateOf(5) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shift_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.shift_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Stepper(
                    label = stringResource(R.string.shift_minutes),
                    value = delta,
                    range = -180..180,
                    step = 5,
                    onChange = { delta = it },
                    format = { if (it > 0) "+$it" else "$it" },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onShift(delta) }, enabled = delta != 0) { Text(stringResource(R.string.shift_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
