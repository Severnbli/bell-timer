package com.belltimer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belltimer.app.AppViewModel
import com.belltimer.app.R
import com.belltimer.app.UiState
import com.belltimer.app.data.AutoWindow
import com.belltimer.app.data.BellRole
import com.belltimer.app.data.BellSchedule
import com.belltimer.app.data.DayKind
import com.belltimer.app.data.MAX_PACKS
import com.belltimer.app.data.ModuleType
import com.belltimer.app.data.PackKind
import com.belltimer.app.data.ResolvedBell
import com.belltimer.app.data.ScheduleModule
import com.belltimer.app.data.SchedulePack
import com.belltimer.app.data.canAddPack
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun ScheduleScreen(state: UiState, viewModel: AppViewModel, onOpenPack: (Int) -> Unit) {
    val schedule = state.schedule
    var selectedKey by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selected = LocalDate.parse(selectedKey)
    var newPackDialog by remember { mutableStateOf(false) }
    var autoDialog by remember { mutableStateOf(false) }
    val tooManyPacks = stringResource(R.string.error_too_many_packs)

    fun addPack(pack: SchedulePack) {
        viewModel.updateSchedule(schedule.withPack(pack))
        onOpenPack(pack.id)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScheduleHeader(
            state = state,
            viewModel = viewModel,
            onAutoWindow = { autoDialog = true },
        )
        if (state.loadingSchedule) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WeekStrip(
                schedule = schedule,
                selected = selected,
                onSelect = { selectedKey = it.toString() },
            )
            DayAgenda(schedule = schedule, date = selected)

            SectionTitle(
                text = stringResource(R.string.packs_title),
                trailing = {
                    Text(
                        text = stringResource(R.string.packs_count, schedule.packs.size, MAX_PACKS),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (schedule.auto.isSet) {
                AutoWindowNote(schedule.auto, onClick = { autoDialog = true })
            }
            if (schedule.packs.isEmpty()) {
                EmptyPacks(
                    loaded = state.scheduleLoaded,
                    onCreate = {
                        addPack(
                            SchedulePack(
                                id = schedule.nextPackId(),
                                name = it,
                                kind = PackKind.Base,
                            ),
                        )
                    },
                )
            } else {
                schedule.packs.forEachIndexed { index, pack ->
                    PackCard(
                        pack = pack,
                        index = index,
                        count = schedule.packs.size,
                        appliesOnSelected = pack.appliesOn(selected),
                        onOpen = { onOpenPack(pack.id) },
                        onToggle = { enabled -> viewModel.updateSchedule(schedule.withPack(pack.copy(enabled = enabled))) },
                        onMove = { delta -> viewModel.updateSchedule(schedule.movePack(pack.id, delta)) },
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    if (schedule.canAddPack()) newPackDialog = true else viewModel.reportMessage(tooManyPacks)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.pack_add))
            }
            Spacer(Modifier.height(16.dp))
        }
        if (state.scheduleDirty) {
            UnsentBar(
                busy = state.busy,
                onSend = viewModel::sendSchedule,
                onDiscard = viewModel::discardScheduleChanges,
            )
        }
    }

    if (newPackDialog) {
        NewPackDialog(
            selected = selected,
            onDismiss = { newPackDialog = false },
            onCreate = { kind, mute, name, date ->
                newPackDialog = false
                addPack(
                    SchedulePack(
                        id = schedule.nextPackId(),
                        name = name,
                        kind = kind,
                        mute = mute,
                        window = if (date != null) {
                            com.belltimer.app.data.PackWindow(dates = setOf(date))
                        } else {
                            com.belltimer.app.data.PackWindow()
                        },
                    ),
                )
            },
        )
    }
    if (autoDialog) {
        AutoWindowDialog(
            window = schedule.auto,
            onDismiss = { autoDialog = false },
            onSave = {
                autoDialog = false
                viewModel.updateSchedule(schedule.copy(auto = it))
            },
        )
    }
}

@Composable
private fun ScheduleHeader(state: UiState, viewModel: AppViewModel, onAutoWindow: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val importError = stringResource(R.string.schedule_import_error)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) viewModel.reportMessage(importError) else viewModel.importScheduleText(text)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val text = viewModel.exportScheduleText()
            val ok = text != null && runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("no stream")
            }.isSuccess
            viewModel.reportExportResult(ok)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.tab_schedule),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = viewModel::loadSchedule, enabled = state.online && !state.loadingSchedule) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.schedule_reload))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.auto_window_title)) },
                    leadingIcon = { Icon(Icons.Default.DateRange, null) },
                    onClick = {
                        menu = false
                        onAutoWindow()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.schedule_import)) },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = {
                        menu = false
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.schedule_export)) },
                    leadingIcon = { Icon(Icons.Default.Upload, null) },
                    enabled = !state.schedule.isEmpty,
                    onClick = {
                        menu = false
                        exportLauncher.launch("bell-schedule-${LocalDate.now()}.json")
                    },
                )
            }
        }
    }
}

// ------------------------------- Week strip --------------------------------

@Composable
private fun WeekStrip(schedule: BellSchedule, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    var monthDialog by remember { mutableStateOf(false) }
    val monday = selected.minusDays(selected.dayOfWeek.value - 1L)
    val today = LocalDate.now()

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelect(selected.minusWeeks(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.week_prev))
            }
            TextButton(onClick = { monthDialog = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.EditCalendar, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(selected.monthTitle(), style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = { onSelect(selected.plusWeeks(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.week_next))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (offset in 0..6) {
                val date = monday.plusDays(offset.toLong())
                DayCell(
                    date = date,
                    kind = schedule.dayKind(date),
                    selected = date == selected,
                    today = date == today,
                    showWeekday = true,
                    onClick = { onSelect(date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (selected != today) {
            TextButton(
                onClick = { onSelect(today) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.week_today)) }
        }
        DayKindLegend()
    }

    if (monthDialog) {
        MonthDialog(
            schedule = schedule,
            initial = selected,
            onDismiss = { monthDialog = false },
            onSelect = {
                monthDialog = false
                onSelect(it)
            },
        )
    }
}

@Composable
private fun dayKindColor(kind: DayKind): Color? = when (kind) {
    DayKind.None -> null
    DayKind.Base -> MaterialTheme.colorScheme.primary
    DayKind.Correction -> AppColors.extra.correction
    DayKind.Muted -> MaterialTheme.colorScheme.outline
}

@Composable
private fun DayCell(
    date: LocalDate,
    kind: DayKind,
    selected: Boolean,
    today: Boolean,
    showWeekday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dot = dayKindColor(kind)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primary else Color.Transparent,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
        border = if (today && !selected) BorderStroke(1.5.dp, colors.primary) else null,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showWeekday) {
                Text(
                    text = weekdayShort(date.dayOfWeek.value),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                )
            }
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium.merge(Tabular),
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            dot == null -> Color.Transparent
                            selected -> colors.onPrimary
                            else -> dot
                        },
                    ),
            )
        }
    }
}

@Composable
private fun DayKindLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        LegendDot(MaterialTheme.colorScheme.primary, stringResource(R.string.legend_base))
        Spacer(Modifier.width(16.dp))
        LegendDot(AppColors.extra.correction, stringResource(R.string.legend_correction))
        Spacer(Modifier.width(16.dp))
        LegendDot(MaterialTheme.colorScheme.outline, stringResource(R.string.legend_muted))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthDialog(
    schedule: BellSchedule,
    initial: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.from(initial).toString()) }
    val month = YearMonth.parse(monthKey)
    val today = LocalDate.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSelect(today) }) { Text(stringResource(R.string.week_today)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthKey = month.minusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.month_prev))
                }
                Text(
                    text = month.atDay(1).monthTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { monthKey = month.plusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.month_next))
                }
            }
        },
        text = {
            Column {
                Row {
                    for (day in 1..7) {
                        Text(
                            text = weekdayShort(day),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                val leading = month.atDay(1).dayOfWeek.value - 1
                val rows = (leading + month.lengthOfMonth() + 6) / 7
                for (row in 0 until rows) {
                    Row {
                        for (col in 0 until 7) {
                            val day = row * 7 + col - leading + 1
                            Box(modifier = Modifier.weight(1f).aspectRatio(0.8f)) {
                                if (day in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(day)
                                    DayCell(
                                        date = date,
                                        kind = schedule.dayKind(date),
                                        selected = date == initial,
                                        today = date == today,
                                        showWeekday = false,
                                        onClick = { onSelect(date) },
                                        modifier = Modifier.fillMaxSize().padding(1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                DayKindLegend()
            }
        },
    )
}

// ------------------------------- Day agenda --------------------------------

/** Bells of one module on one day, shown as a single timeline item. */
private data class AgendaItem(
    val module: ScheduleModule,
    val packId: Int,
    val fromCorrection: Boolean,
    val bells: List<ResolvedBell>,
)

private fun List<ResolvedBell>.toAgenda(): List<AgendaItem> =
    groupBy { it.packId to it.module }.map { (key, bells) ->
        AgendaItem(key.second, key.first, bells.first().fromCorrection, bells)
    }

@Composable
private fun DayAgenda(schedule: BellSchedule, date: LocalDate) {
    val bells = remember(schedule, date) { schedule.resolveDay(date) }
    val items = remember(bells) { bells.toAgenda() }
    val kind = remember(schedule, date) { schedule.dayKind(date) }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(date.longDay(), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        !schedule.auto.allows(date) -> stringResource(R.string.day_outside_auto)
                        kind == DayKind.Muted && bells.isEmpty() -> stringResource(R.string.day_muted)
                        else -> pluralStringResource(R.plurals.bells_count, bells.size, bells.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (kind == DayKind.Correction || kind == DayKind.Muted) {
                Surface(shape = CircleShape, color = AppColors.extra.correctionContainer) {
                    Text(
                        text = stringResource(if (kind == DayKind.Muted) R.string.legend_muted else R.string.legend_correction),
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.extra.onCorrectionContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (items.isEmpty()) {
            Row(
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (kind == DayKind.Muted) Icons.Default.BeachAccess else Icons.Default.EventBusy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@AppCard
        }
        Spacer(Modifier.height(12.dp))
        val now = LocalDate.now()
        val nowMinute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        for (item in items) {
            val past = date.isBefore(now) || (date == now && item.bells.last().minute < nowMinute)
            AgendaRow(item, past)
        }
    }
}

@Composable
private fun AgendaRow(item: AgendaItem, past: Boolean) {
    val module = item.module
    val color = AppColors.module(module.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .alpha(if (past) 0.5f else 1f)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = item.bells.first().time,
            style = MaterialTheme.typography.titleSmall.merge(Tabular),
            modifier = Modifier
                .width(52.dp)
                .padding(top = 10.dp),
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(12.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = AppColors.moduleContainer(module.type).copy(alpha = 0.55f),
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        moduleIcon(module.type),
                        contentDescription = null,
                        tint = AppColors.onModuleContainer(module.type),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = module.label.ifBlank { moduleTypeName(module.type) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.onModuleContainer(module.type),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.fromCorrection) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AppColors.extra.correction),
                        )
                    }
                }
                Text(
                    text = agendaDetails(item),
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = AppColors.onModuleContainer(module.type).copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun agendaDetails(item: AgendaItem): String {
    val module = item.module
    return when (module.type) {
        ModuleType.Lesson -> {
            val start = item.bells.firstOrNull { it.role == BellRole.Start }
            val end = item.bells.firstOrNull { it.role == BellRole.End }
            val pre = item.bells.firstOrNull { it.role == BellRole.Pre }
            buildList {
                if (start != null && end != null) {
                    add("${start.time} – ${end.time} · ${durationText(module.length)}")
                } else {
                    add(item.bells.joinToString(", ") { it.time })
                }
                if (pre != null) add(stringResource(R.string.agenda_pre, pre.time))
            }.joinToString(" · ")
        }
        ModuleType.Series -> listOf(
            "${item.bells.first().time} – ${item.bells.last().time}",
            stringResource(R.string.module_every_short, durationText(module.every)),
            pluralStringResource(R.plurals.bells_count, item.bells.size, item.bells.size),
        ).joinToString(" · ")
        ModuleType.Bell -> moduleTypeName(module.type)
    }
}

// ---------------------------------- Packs ----------------------------------

@Composable
private fun AutoWindowNote(window: AutoWindow, onClick: () -> Unit) {
    val from = window.start?.fullDate()
    val to = window.stop?.fullDate()
    Banner(
        icon = Icons.Default.DateRange,
        text = when {
            from != null && to != null -> stringResource(R.string.auto_window_range, from, to)
            from != null -> stringResource(R.string.auto_window_from, from)
            else -> stringResource(R.string.auto_window_to, to.orEmpty())
        },
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        action = { TextButton(onClick = onClick) { Text(stringResource(R.string.change)) } },
    )
}

@Composable
private fun EmptyPacks(loaded: Boolean, onCreate: (String) -> Unit) {
    val defaultName = stringResource(R.string.pack_default_name)
    AppCard {
        Icon(
            Icons.Default.Layers,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.packs_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(if (loaded) R.string.packs_empty_text else R.string.packs_not_loaded),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onCreate(defaultName) }) { Text(stringResource(R.string.packs_empty_action)) }
    }
}

@Composable
private fun PackCard(
    pack: SchedulePack,
    index: Int,
    count: Int,
    appliesOnSelected: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val accent = AppColors.pack(pack.kind)
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (pack.enabled) 1f else 0.6f),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = pack.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (appliesOnSelected && pack.enabled) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AppColors.extra.success),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                PackKindChip(pack)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = packSummary(pack),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pack.modules.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ModuleStrip(pack.modules)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Switch(checked = pack.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pack_move_up)) },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                            enabled = index > 0,
                            onClick = {
                                menu = false
                                onMove(-1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pack_move_down)) },
                            leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                            enabled = index < count - 1,
                            onClick = {
                                menu = false
                                onMove(1)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PackKindChip(pack: SchedulePack) {
    val (container, content, label, icon) = when {
        pack.kind == PackKind.Base -> Quad(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            stringResource(R.string.pack_kind_base),
            Icons.Default.Layers,
        )
        pack.mute -> Quad(
            AppColors.extra.correctionContainer,
            AppColors.extra.onCorrectionContainer,
            stringResource(R.string.pack_kind_holiday),
            Icons.Default.BeachAccess,
        )
        else -> Quad(
            AppColors.extra.correctionContainer,
            AppColors.extra.onCorrectionContainer,
            stringResource(R.string.pack_kind_correction),
            Icons.Default.ContentCopy,
        )
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private data class Quad(val a: Color, val b: Color, val c: String, val d: ImageVector)

@Composable
private fun packSummary(pack: SchedulePack): String {
    val parts = mutableListOf<String>()
    if (!(pack.kind == PackKind.Correction && pack.mute)) {
        parts += pluralStringResource(R.plurals.modules_count, pack.modules.size, pack.modules.size)
        if (pack.modules.isNotEmpty()) parts += daysSummary(pack.weekdayUnion)
    }
    parts += windowSummary(pack.window)
    return parts.joinToString(" · ")
}

/** A compact row of coloured dots, one per module type count. */
@Composable
private fun ModuleStrip(modules: List<ScheduleModule>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (type in ModuleType.entries) {
            val count = modules.count { it.type == type }
            if (count == 0) continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    moduleIcon(type),
                    contentDescription = null,
                    tint = AppColors.module(type),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UnsentBar(busy: Boolean, onSend: () -> Unit, onDiscard: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.unsent_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDiscard, enabled = !busy) {
                Text(stringResource(R.string.unsent_discard), color = MaterialTheme.colorScheme.inverseOnSurface)
            }
            Button(onClick = onSend, enabled = !busy) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.unsent_send))
            }
        }
    }
}

// --------------------------------- Dialogs ---------------------------------

@Composable
private fun NewPackDialog(
    selected: LocalDate,
    onDismiss: () -> Unit,
    onCreate: (PackKind, Boolean, String, LocalDate?) -> Unit,
) {
    val baseName = stringResource(R.string.pack_new_base_name)
    val correctionName = stringResource(R.string.pack_new_correction_name, selected.shortDate())
    val holidayName = stringResource(R.string.pack_new_holiday_name, selected.shortDate())
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.pack_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NewPackOption(
                    icon = Icons.Default.Layers,
                    color = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.pack_kind_base),
                    text = stringResource(R.string.pack_kind_base_hint),
                    onClick = { onCreate(PackKind.Base, false, baseName, null) },
                )
                NewPackOption(
                    icon = Icons.Default.ContentCopy,
                    color = AppColors.extra.correction,
                    title = stringResource(R.string.pack_kind_correction),
                    text = stringResource(R.string.pack_new_correction_hint, selected.shortDate()),
                    onClick = { onCreate(PackKind.Correction, false, correctionName, selected) },
                )
                NewPackOption(
                    icon = Icons.Default.BeachAccess,
                    color = MaterialTheme.colorScheme.outline,
                    title = stringResource(R.string.pack_kind_holiday),
                    text = stringResource(R.string.pack_new_holiday_hint, selected.shortDate()),
                    onClick = { onCreate(PackKind.Correction, true, holidayName, selected) },
                )
            }
        },
    )
}

@Composable
private fun NewPackOption(icon: ImageVector, color: Color, title: String, text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = color) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AutoWindowDialog(window: AutoWindow, onDismiss: () -> Unit, onSave: (AutoWindow) -> Unit) {
    var start by remember { mutableStateOf(window.start) }
    var stop by remember { mutableStateOf(window.stop) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val valid = AutoWindow(start, stop).isValid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_window_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.auto_window_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DateRow(stringResource(R.string.date_from), start, onPick = { picking = 0 }, onClear = { start = null })
                DateRow(stringResource(R.string.date_to), stop, onPick = { picking = 1 }, onClear = { stop = null })
                if (!valid) {
                    Text(
                        stringResource(R.string.issue_auto_window),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(AutoWindow(start, stop)) }, enabled = valid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    picking?.let { which ->
        DatePickerDialog(
            initial = if (which == 0) start else stop,
            onDismiss = { picking = null },
            onConfirm = {
                if (which == 0) start = it else stop = it
                picking = null
            },
        )
    }
}

/** "С: 1 сентября 2026  [×]" with a tap to change. */
@Composable
fun DateRow(label: String, date: LocalDate?, onPick: () -> Unit, onClear: () -> Unit) {
    Surface(
        onClick = onPick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Caption(label)
                Text(
                    text = date?.fullDate() ?: stringResource(R.string.date_not_set),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (date != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.clear))
                }
            }
        }
    }
}

@Composable
fun ThinDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
fun SmallTonalButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}
