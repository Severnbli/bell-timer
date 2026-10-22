package com.belltimer.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.belltimer.app.data.DayOverride
import com.belltimer.app.data.DayOverrideMode
import com.belltimer.app.data.ScheduleGroup
import com.belltimer.app.data.ScheduleIssue
import com.belltimer.app.data.canAddGroup
import com.belltimer.app.data.canAddOverride
import com.belltimer.app.data.firstFreeDays
import java.time.LocalDate

/** Plain state-based navigation inside the schedule tab. */
sealed interface ScheduleRoute {
    data object Week : ScheduleRoute
    data object Overrides : ScheduleRoute
    data class Editor(val groupId: Int) : ScheduleRoute
    data class Day(val date: LocalDate) : ScheduleRoute
}

private val ScheduleRouteSaver = listSaver<ScheduleRoute, Any>(
    save = { route ->
        when (route) {
            ScheduleRoute.Week -> listOf("week")
            ScheduleRoute.Overrides -> listOf("overrides")
            is ScheduleRoute.Editor -> listOf("editor", route.groupId)
            is ScheduleRoute.Day -> listOf("day", route.date.toEpochDay())
        }
    },
    restore = { saved ->
        when (saved.firstOrNull() as? String) {
            "editor" -> ScheduleRoute.Editor(saved.getOrNull(1) as? Int ?: 0)
            "day" -> ScheduleRoute.Day(LocalDate.ofEpochDay(saved.getOrNull(1) as? Long ?: 0L))
            "overrides" -> ScheduleRoute.Overrides
            else -> ScheduleRoute.Week
        }
    },
)

@Composable
fun ScheduleGroup.displayName(): String =
    name.ifBlank { stringResource(R.string.schedule_untitled) }

@Composable
fun ScheduleScreen(state: UiState, viewModel: AppViewModel) {
    val schedule = state.schedule
    var route by rememberSaveable(stateSaver = ScheduleRouteSaver) {
        mutableStateOf<ScheduleRoute>(ScheduleRoute.Week)
    }
    var selectedDay by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var weekdaySheet by remember { mutableStateOf<Int?>(null) }
    var newScheduleSheet by remember { mutableStateOf(false) }
    var pendingBuilder by rememberSaveable { mutableStateOf(false) }
    var autoSheet by remember { mutableStateOf(false) }
    var addDate by remember { mutableStateOf(false) }
    var checkDate by remember { mutableStateOf(false) }
    var checkedDate by remember { mutableStateOf<LocalDate?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val importErrorMessage = stringResource(R.string.schedule_import_error)
    val tooManyGroupsMessage = stringResource(R.string.error_too_many_groups)
    val newScheduleName = stringResource(R.string.schedule_new_name)
    val overrideDeletedMessage = stringResource(R.string.schedule_override_deleted)
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

    // A failed send points at the offending item instead of showing a hint.
    LaunchedEffect(state.scheduleIssue) {
        when (val issue = state.scheduleIssue) {
            is ScheduleIssue.OverlappingWeekdays -> {
                route = ScheduleRoute.Editor(issue.groupId)
                viewModel.consumeScheduleIssue()
            }
            is ScheduleIssue.ScheduleWithoutDays -> {
                route = ScheduleRoute.Editor(issue.groupId)
                viewModel.consumeScheduleIssue()
            }
            is ScheduleIssue.AutoWindowInvalid -> {
                route = ScheduleRoute.Week
                autoSheet = true
                viewModel.consumeScheduleIssue()
            }
            else -> Unit
        }
    }

    when (val current = route) {
        is ScheduleRoute.Editor -> {
            ScheduleEditorScreen(
                state = state,
                viewModel = viewModel,
                groupId = current.groupId,
                startWithBuilder = pendingBuilder,
                onStartWithBuilderConsumed = { pendingBuilder = false },
                onBack = { route = ScheduleRoute.Week },
            )
            return
        }
        is ScheduleRoute.Day -> {
            DayEditorScreen(
                state = state,
                viewModel = viewModel,
                date = current.date,
                onBack = { route = ScheduleRoute.Week },
            )
            return
        }
        else -> Unit
    }

    BackHandler(enabled = route == ScheduleRoute.Overrides) { route = ScheduleRoute.Week }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                SegmentedButton(
                    selected = route == ScheduleRoute.Week,
                    onClick = { route = ScheduleRoute.Week },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.schedule_tab_week))
                }
                SegmentedButton(
                    selected = route == ScheduleRoute.Overrides,
                    onClick = { route = ScheduleRoute.Overrides },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.schedule_tab_overrides))
                }
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

        when (route) {
            ScheduleRoute.Overrides -> OverridesContent(
                state = state,
                onAdd = { addDate = true },
                onCheckDate = { checkDate = true },
                onEdit = { date -> route = ScheduleRoute.Day(date) },
                onDelete = { date ->
                    viewModel.updateSchedule(state.schedule.withoutOverride(date), undoable = true)
                    viewModel.reportMessage(overrideDeletedMessage)
                },
            )
            else -> WeekContent(
                state = state,
                selectedDay = selectedDay,
                onSelectDay = { selectedDay = it },
                onWeekdayClick = { weekdaySheet = it },
                onScheduleClick = { groupId -> route = ScheduleRoute.Editor(groupId) },
                onAddSchedule = {
                    if (schedule.canAddGroup()) {
                        newScheduleSheet = true
                    } else {
                        viewModel.reportMessage(tooManyGroupsMessage)
                    }
                },
            )
        }
    }

    weekdaySheet?.let { day ->
        val owner = schedule.groups.firstOrNull { day in it.days }
        val options = schedule.groups.map { it.displayName() } +
            stringResource(R.string.schedule_no_bells)
        val selectedIndex = schedule.groups.indexOf(owner).takeIf { it >= 0 }
            ?: schedule.groups.size
        ChoiceSheet(
            title = stringResource(R.string.schedule_weekday_sheet, weekdayName(day)),
            options = options,
            selectedIndex = selectedIndex,
            onDismiss = { weekdaySheet = null },
            onSelect = { index ->
                weekdaySheet = null
                val updated = if (index >= schedule.groups.size) {
                    schedule.groups.fold(schedule) { acc, group ->
                        if (day in group.days) {
                            acc.withGroup(group.withDays(group.days - day))
                        } else {
                            acc
                        }
                    }
                } else {
                    val target = schedule.groups[index]
                    schedule.groups.fold(schedule) { acc, group ->
                        when {
                            group.id == target.id ->
                                acc.withGroup(group.withDays(group.days + day))
                            day in group.days ->
                                acc.withGroup(group.withDays(group.days - day))
                            else -> acc
                        }
                    }
                }
                viewModel.updateSchedule(updated)
            },
        )
    }

    if (newScheduleSheet) {
        val builderIndex = 0
        val emptyIndex = 1
        val importIndex = 2
        ChoiceSheet(
            title = stringResource(R.string.schedule_new_title),
            options = listOf(
                stringResource(R.string.schedule_new_builder),
                stringResource(R.string.schedule_new_empty),
                stringResource(R.string.schedule_import_file),
            ),
            selectedIndex = -1,
            onDismiss = { newScheduleSheet = false },
            onSelect = { index ->
                newScheduleSheet = false
                when {
                    index == importIndex -> importLauncher.launch(
                        arrayOf("application/json", "text/plain", "*/*"),
                    )
                    !schedule.canAddGroup() -> viewModel.reportMessage(tooManyGroupsMessage)
                    else -> {
                        val group = ScheduleGroup(
                            id = schedule.nextGroupId(),
                            name = newScheduleName,
                            days = schedule.firstFreeDays(),
                        )
                        pendingBuilder = index == builderIndex
                        viewModel.updateSchedule(schedule.withGroup(group))
                        route = ScheduleRoute.Editor(group.id)
                    }
                }
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

    if (addDate) {
        DateSheet(
            initial = LocalDate.now(),
            onDismiss = { addDate = false },
            onPick = { date ->
                addDate = false
                route = ScheduleRoute.Day(date)
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
private fun WeekContent(
    state: UiState,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    onWeekdayClick: (Int) -> Unit,
    onScheduleClick: (Int) -> Unit,
    onAddSchedule: () -> Unit,
) {
    val schedule = state.schedule
    val dayDate = remember(selectedDay) { LocalDate.now().nextDateFor(selectedDay) }
    val dayBells = schedule.bellsFor(dayDate)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "strip") {
            WeekStrip(
                schedule = schedule,
                selectedDay = selectedDay,
                onClickDay = { day ->
                    onSelectDay(day)
                    onWeekdayClick(day)
                },
            )
        }
        item(key = "timeline") {
            if (dayBells.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedule_timeline_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                FlowRow(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    dayBells.forEach { entry ->
                        Text(
                            text = entry.time,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        item(key = "schedules-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(
                    text = stringResource(R.string.schedule_schedules_title),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddSchedule) {
                    Text(stringResource(R.string.schedule_add))
                }
            }
        }
        if (schedule.groups.isEmpty()) {
            item(key = "schedules-empty") {
                EmptyState(
                    text = stringResource(R.string.schedule_no_schedules),
                    actionLabel = stringResource(R.string.schedule_add),
                    onAction = onAddSchedule,
                )
            }
        } else {
            items(schedule.groups, key = { "group-${it.id}" }) { group ->
                ScheduleGroupRow(group = group, onClick = { onScheduleClick(group.id) })
                RowDivider()
            }
        }
        item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekStrip(
    schedule: BellSchedule,
    selectedDay: Int,
    onClickDay: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        for (day in 1..7) {
            val owner = schedule.groups.firstOrNull { day in it.days }
            val selected = day == selectedDay
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onClickDay(day) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = weekdayName(day),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = owner?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.schedule_no_bells),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (owner == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .width(24.dp)
                        .height(2.dp)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun ScheduleGroupRow(group: ScheduleGroup, onClick: () -> Unit) {
    val first = group.firstMinute
    val last = group.lastMinute
    val range = if (first != null && last != null) {
        stringResource(R.string.schedule_range, minutesToTime(first), minutesToTime(last))
    } else {
        stringResource(R.string.schedule_timeline_empty)
    }
    val subtitle = range + " · " +
        stringResource(R.string.schedule_bells_count, group.entries.size) + " · " +
        daysSummary(group.days)
    ListRow(title = group.displayName(), subtitle = subtitle, onClick = onClick)
}

@Composable
fun daysSummary(days: Set<Int>): String = when {
    days.isEmpty() -> stringResource(R.string.schedule_no_bells)
    days.size == 7 -> stringResource(R.string.days_every_day)
    days == setOf(1, 2, 3, 4, 5) -> stringResource(R.string.days_weekdays)
    else -> days.sorted().map { stringResource(dayLabelRes(it)) }.joinToString(", ")
}

@Composable
private fun OverridesContent(
    state: UiState,
    onAdd: () -> Unit,
    onCheckDate: () -> Unit,
    onEdit: (LocalDate) -> Unit,
    onDelete: (LocalDate) -> Unit,
) {
    val schedule = state.schedule
    val today = LocalDate.now()
    val upcoming = schedule.overrides.filterNot { it.date.isBefore(today) }
    val past = schedule.overrides.filter { it.date.isBefore(today) }
    var showPast by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "overrides-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(
                    text = stringResource(R.string.schedule_overrides_title),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCheckDate) {
                    Text(stringResource(R.string.schedule_check_date))
                }
            }
        }

        if (!state.supportsSpecialDays) {
            item(key = "overrides-firmware") {
                Text(
                    text = stringResource(R.string.schedule_needs_new_firmware),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        item(key = "overrides-add") {
            Button(
                onClick = onAdd,
                enabled = state.supportsSpecialDays && schedule.canAddOverride(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.schedule_override_add_action))
            }
        }

        if (upcoming.isEmpty() && past.isEmpty()) {
            item(key = "overrides-empty") {
                EmptyState(text = stringResource(R.string.schedule_overrides_empty))
            }
        }

        var lastMonth: String? = null
        upcoming.forEach { override ->
            val month = override.date.monthTitle()
            if (month != lastMonth) {
                lastMonth = month
                item(key = "month-$month") { SectionHeader(text = month) }
            }
            item(key = "override-${override.date}") {
                OverrideRow(
                    override = override,
                    groups = schedule.groups,
                    onClick = { onEdit(override.date) },
                    onDelete = { onDelete(override.date) },
                )
                RowDivider()
            }
        }

        if (past.isNotEmpty()) {
            item(key = "past-header") {
                ListRow(
                    title = stringResource(R.string.schedule_overrides_past, past.size),
                    onClick = { showPast = !showPast },
                )
            }
            if (showPast) {
                past.sortedByDescending { it.date }.forEach { override ->
                    item(key = "past-${override.date}") {
                        OverrideRow(
                            override = override,
                            groups = schedule.groups,
                            onClick = { onEdit(override.date) },
                            onDelete = { onDelete(override.date) },
                        )
                        RowDivider()
                    }
                }
            }
        }
        item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun OverrideRow(
    override: DayOverride,
    groups: List<ScheduleGroup>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val modeLabel = when (override.mode) {
        DayOverrideMode.Mute -> stringResource(R.string.schedule_override_mute)
        DayOverrideMode.Replace -> stringResource(R.string.schedule_override_replace)
        DayOverrideMode.Add -> stringResource(R.string.schedule_override_add)
    }
    val count = if (override.mode == DayOverrideMode.Mute) 0 else override.entries.size
    val basedOn = groups.firstOrNull { it.id == override.basedOnGroupId }
    val subtitle = buildString {
        append(modeLabel)
        if (count > 0) {
            append(" · ")
            append(stringResource(R.string.schedule_bells_count, count))
        }
        if (basedOn != null) {
            append(" · ")
            append(stringResource(R.string.schedule_based_on, basedOn.displayName()))
        }
    }
    SwipeToDeleteBox(onDelete = onDelete) {
        ListRow(
            title = override.date.fullDate() + " · " +
                weekdayName(override.date.dayOfWeek.value),
            subtitle = subtitle,
            onClick = onClick,
        )
    }
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
fun OutlinedButtonBlock(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Column {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalSheetText(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun dayReason(schedule: BellSchedule, date: LocalDate): String {
    if (!schedule.auto.allows(date)) return stringResource(R.string.day_reason_outside)
    if (schedule.overrideFor(date) != null) return stringResource(R.string.day_reason_override)
    val group = schedule.baseGroupFor(date)
    return if (group != null) {
        stringResource(R.string.day_reason_schedule, group.displayName())
    } else {
        stringResource(R.string.day_reason_none)
    }
}

@Composable
fun weekdayName(day: Int): String = stringResource(dayLabelRes(day))

fun LocalDate.nextDateFor(weekday: Int): LocalDate {
    var date = this
    while (date.dayOfWeek.value != weekday) date = date.plusDays(1)
    return date
}

private fun exportFileName(): String =
    "belltimer-schedule-${LocalDate.now()}.json"
