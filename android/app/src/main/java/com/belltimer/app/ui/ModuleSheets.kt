package com.belltimer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.belltimer.app.R
import com.belltimer.app.data.LAST_MINUTE
import com.belltimer.app.data.LessonGrid
import com.belltimer.app.data.MAX_LESSON_MINUTES
import com.belltimer.app.data.MAX_PACK_MODULES
import com.belltimer.app.data.MAX_PRE_MINUTES
import com.belltimer.app.data.MAX_SERIES_STEP
import com.belltimer.app.data.ModuleType
import com.belltimer.app.data.ScheduleModule
import com.belltimer.app.data.SchedulePack
import com.belltimer.app.data.WEEKDAYS
import com.belltimer.app.data.labelTooLong
import com.belltimer.app.data.minutesToTime

/** The first step of "add module": what kind of block to add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleTypeSheet(onDismiss: () -> Unit, onPick: (ModuleType) -> Unit, onGrid: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.module_add_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            TypeOption(ModuleType.Lesson, stringResource(R.string.module_lesson_hint)) { onPick(ModuleType.Lesson) }
            TypeOption(ModuleType.Bell, stringResource(R.string.module_bell_hint)) { onPick(ModuleType.Bell) }
            TypeOption(ModuleType.Series, stringResource(R.string.module_series_hint)) { onPick(ModuleType.Series) }
            OptionRow(
                icon = Icons.Default.GridView,
                iconTint = MaterialTheme.colorScheme.secondary,
                title = stringResource(R.string.grid_title),
                text = stringResource(R.string.grid_hint),
                onClick = onGrid,
            )
        }
    }
}

@Composable
private fun TypeOption(type: ModuleType, hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ModuleBadge(type, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(moduleTypeName(type), style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = iconTint) }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

/** Creates or edits one module. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleSheet(
    initial: ScheduleModule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ScheduleModule) -> Unit,
    onDelete: () -> Unit,
) {
    var module by remember(initial) { mutableStateOf(initial) }
    val labelError = labelTooLong(module.label)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModuleBadge(module.type)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(if (isNew) R.string.module_new_title else R.string.module_edit_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val types = listOf(ModuleType.Lesson, ModuleType.Bell, ModuleType.Series)
                types.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = module.type == type,
                        onClick = { module = module.copy(type = type) },
                        shape = SegmentedButtonDefaults.itemShape(index, types.size),
                        icon = {},
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(moduleIcon(type), contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(moduleTypeName(type))
                        }
                    }
                }
            }

            when (module.type) {
                ModuleType.Bell -> TimeField(
                    label = stringResource(R.string.module_time),
                    minute = module.start,
                    onChange = { module = module.copy(start = it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ModuleType.Lesson -> LessonFields(module) { module = it }
                ModuleType.Series -> SeriesFields(module) { module = it }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption(stringResource(R.string.module_days))
                DayPicker(days = module.days, onChange = { module = module.copy(days = it) })
            }

            OutlinedTextField(
                value = module.label,
                onValueChange = { module = module.copy(label = it) },
                label = { Text(stringResource(R.string.module_label)) },
                placeholder = { Text(stringResource(R.string.module_label_placeholder)) },
                singleLine = true,
                isError = labelError,
                supportingText = if (labelError) {
                    { Text(stringResource(R.string.label_too_long)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            ModulePreview(module)

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(module) }, enabled = module.isValid && !labelError) {
                    Text(stringResource(if (isNew) R.string.add else R.string.save))
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun LessonFields(module: ScheduleModule, onChange: (ScheduleModule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField(
                label = stringResource(R.string.module_start),
                minute = module.start,
                onChange = { onChange(module.copy(start = it)) },
                modifier = Modifier.weight(1f),
            )
            TimeField(
                label = stringResource(R.string.module_end),
                minute = (module.start + module.length).coerceAtMost(LAST_MINUTE),
                onChange = { end -> if (end > module.start) onChange(module.copy(length = end - module.start)) },
                modifier = Modifier.weight(1f),
            )
        }
        Stepper(
            label = stringResource(R.string.module_length),
            value = module.length,
            range = 1..MAX_LESSON_MINUTES,
            step = 5,
            onChange = { onChange(module.copy(length = it)) },
            format = { durationText(it) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in listOf(30, 40, 45, 60, 90)) {
                QuickChip(durationText(preset), module.length == preset) { onChange(module.copy(length = preset)) }
            }
        }
        SettingRow(
            title = stringResource(R.string.module_pre),
            subtitle = stringResource(R.string.module_pre_hint),
            onClick = { onChange(module.copy(pre = if (module.pre > 0) 0 else 2)) },
            trailing = {
                Switch(checked = module.pre > 0, onCheckedChange = { onChange(module.copy(pre = if (it) 2 else 0)) })
            },
        )
        if (module.pre > 0) {
            Stepper(
                label = stringResource(R.string.module_pre_minutes),
                value = module.pre,
                range = 1..MAX_PRE_MINUTES,
                onChange = { onChange(module.copy(pre = it)) },
                format = { durationText(it) },
            )
        }
    }
}

@Composable
private fun SeriesFields(module: ScheduleModule, onChange: (ScheduleModule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField(
                label = stringResource(R.string.module_from),
                minute = module.start,
                onChange = { onChange(module.copy(start = it)) },
                modifier = Modifier.weight(1f),
            )
            TimeField(
                label = stringResource(R.string.module_until),
                minute = module.until,
                onChange = { onChange(module.copy(until = it)) },
                modifier = Modifier.weight(1f),
            )
        }
        Stepper(
            label = stringResource(R.string.module_every),
            value = module.every,
            range = 1..MAX_SERIES_STEP,
            step = 5,
            onChange = { onChange(module.copy(every = it)) },
            format = { durationText(it) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in listOf(15, 30, 60, 90, 120)) {
                QuickChip(durationText(preset), module.every == preset) { onChange(module.copy(every = preset)) }
            }
        }
    }
}

/** Shows exactly which bells the module will ring, or why it cannot. */
@Composable
private fun ModulePreview(module: ScheduleModule) {
    Surface(shape = MaterialTheme.shapes.medium, color = AppColors.moduleContainer(module.type).copy(alpha = 0.5f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Caption(stringResource(R.string.module_preview), color = AppColors.onModuleContainer(module.type))
            Spacer(Modifier.height(4.dp))
            val text = if (module.isValid) {
                val bells = module.bells()
                val shown = bells.take(PREVIEW_BELLS).joinToString("  ") { bell ->
                    minutesToTime(bell.minute)
                }
                if (bells.size > PREVIEW_BELLS) "$shown  …" else shown
            } else {
                moduleProblem(module)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                color = if (module.isValid) AppColors.onModuleContainer(module.type) else MaterialTheme.colorScheme.error,
            )
            if (module.isValid) {
                Text(
                    text = pluralStringResource(R.plurals.bells_count, module.bells().size, module.bells().size) +
                        " · " + daysSummary(module.days),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onModuleContainer(module.type).copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun moduleProblem(module: ScheduleModule): String = when {
    module.days.isEmpty() -> stringResource(R.string.module_problem_days)
    module.type == ModuleType.Lesson && module.pre > module.start -> stringResource(R.string.module_problem_pre)
    module.type == ModuleType.Lesson -> stringResource(R.string.module_problem_midnight)
    module.type == ModuleType.Series -> stringResource(R.string.module_problem_until)
    else -> stringResource(R.string.module_problem_generic)
}

private const val PREVIEW_BELLS = 12

/** Generates a series of lessons in one go. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonGridSheet(
    pack: SchedulePack,
    onDismiss: () -> Unit,
    onApply: (List<ScheduleModule>, Boolean) -> Unit,
) {
    var start by rememberSaveable { mutableIntStateOf(8 * 60) }
    var count by rememberSaveable { mutableIntStateOf(6) }
    var length by rememberSaveable { mutableIntStateOf(45) }
    var breakMinutes by rememberSaveable { mutableIntStateOf(10) }
    var longAfter by rememberSaveable { mutableIntStateOf(0) }
    var longMinutes by rememberSaveable { mutableIntStateOf(20) }
    var pre by rememberSaveable { mutableIntStateOf(0) }
    var days by remember { mutableStateOf(pack.modules.lastOrNull()?.days ?: WEEKDAYS) }

    val labelFormat = stringResource(R.string.grid_label)
    val grid = LessonGrid(start, count, length, breakMinutes, longAfter, longMinutes, pre, days)
    val lessons = grid.build { labelFormat.format(it) }
    val fitsAppend = lessons != null && pack.modules.size + lessons.size <= MAX_PACK_MODULES
    val fitsReplace = lessons != null && lessons.size <= MAX_PACK_MODULES

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.grid_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.grid_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TimeField(
                label = stringResource(R.string.grid_start),
                minute = start,
                onChange = { start = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Stepper(stringResource(R.string.grid_count), count, 1..16, { count = it; if (longAfter >= it) longAfter = 0 })
            Stepper(stringResource(R.string.grid_length), length, 5..240, { length = it }, step = 5, format = { durationText(it) })
            Stepper(stringResource(R.string.grid_break), breakMinutes, 0..60, { breakMinutes = it }, format = { durationText(it) })
            Stepper(
                label = stringResource(R.string.grid_long_after),
                value = longAfter,
                range = 0..(count - 1).coerceAtLeast(0),
                onChange = { longAfter = it },
                format = { if (it == 0) stringResource(R.string.grid_long_none) else stringResource(R.string.grid_long_after_value, it) },
            )
            if (longAfter > 0) {
                Stepper(
                    stringResource(R.string.grid_long_minutes),
                    longMinutes,
                    0..120,
                    { longMinutes = it },
                    step = 5,
                    format = { durationText(it) },
                )
            }
            Stepper(
                label = stringResource(R.string.module_pre),
                value = pre,
                range = 0..10,
                onChange = { pre = it },
                format = { if (it == 0) stringResource(R.string.grid_pre_none) else durationText(it) },
            )
            Caption(stringResource(R.string.module_days))
            DayPicker(days = days, onChange = { days = it })

            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Caption(stringResource(R.string.module_preview), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (lessons == null) {
                        Text(
                            stringResource(R.string.grid_invalid),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        for (lesson in lessons) {
                            Row {
                                Text(
                                    text = lesson.timeRange(),
                                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = lesson.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }
            if (lessons != null && !fitsAppend && pack.modules.isNotEmpty()) {
                Text(
                    stringResource(R.string.grid_too_many, MAX_PACK_MODULES),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (pack.modules.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { lessons?.let { onApply(it, false) } },
                        enabled = fitsAppend,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.grid_append)) }
                }
                Button(
                    onClick = { lessons?.let { onApply(it, true) } },
                    enabled = fitsReplace,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (pack.modules.isEmpty()) R.string.grid_create else R.string.grid_replace))
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
