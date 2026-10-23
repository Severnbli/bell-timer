package com.belltimer.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.belltimer.app.R
import com.belltimer.app.data.BellEntry
import com.belltimer.app.data.BellPattern
import com.belltimer.app.data.BuilderIssue
import com.belltimer.app.data.BuilderSpec
import com.belltimer.app.data.LessonRow
import com.belltimer.app.data.MAX_PACK_ENTRIES
import com.belltimer.app.data.ScheduleBuilder
import com.belltimer.app.data.WEEKDAYS

private data class BuilderPreset(
    @StringRes val labelRes: Int,
    val startMinute: Int,
    val lengths: List<Int>,
    val breaks: List<Int>,
)

private val PRESETS = listOf(
    BuilderPreset(R.string.schedule_preset_custom, 8 * 60, List(6) { 45 }, List(5) { 10 }),
    BuilderPreset(
        R.string.schedule_preset_school45,
        8 * 60,
        List(7) { 45 },
        listOf(10, 10, 20, 20, 10, 10),
    ),
    BuilderPreset(
        R.string.schedule_preset_school40,
        8 * 60 + 30,
        List(6) { 40 },
        listOf(10, 10, 20, 15, 10),
    ),
    BuilderPreset(
        R.string.schedule_preset_college,
        8 * 60 + 30,
        List(8) { 45 },
        listOf(5, 5, 5, 20, 5, 5, 5),
    ),
    BuilderPreset(
        R.string.schedule_preset_university,
        9 * 60,
        List(5) { 90 },
        listOf(10, 10, 40, 10),
    ),
)

private fun BuilderPreset.toRows(): List<LessonRow> = lengths.mapIndexed { index, length ->
    LessonRow(lengthMinutes = length, breakMinutes = breaks.getOrElse(index) { 0 })
}

private val RowsSaver = listSaver<List<LessonRow>, Int>(
    save = { rows -> rows.flatMap { listOf(it.lengthMinutes, it.breakMinutes) } },
    restore = { flat ->
        flat.chunked(2).filter { it.size == 2 }.map { LessonRow(it[0], it[1]) }
    },
)

private const val QUICK_MINUTES = "5,10,15,20,30,40"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ScheduleBuilderScreen(
    existingEntries: Int,
    days: Set<Int> = WEEKDAYS,
    onDismiss: () -> Unit,
    onApply: (List<BellEntry>) -> Unit,
) {
    val initial = PRESETS.first()
    var presetIndex by rememberSaveable { mutableIntStateOf(0) }
    var startMinute by rememberSaveable { mutableIntStateOf(initial.startMinute) }
    var defaultLength by rememberSaveable {
        mutableIntStateOf(initial.lengths.firstOrNull() ?: ScheduleBuilder.DEFAULT_LESSON_MINUTES)
    }
    var defaultBreak by rememberSaveable {
        mutableIntStateOf(initial.breaks.firstOrNull() ?: ScheduleBuilder.DEFAULT_BREAK_MINUTES)
    }
    var rows by rememberSaveable(stateSaver = RowsSaver) {
        mutableStateOf(initial.toRows())
    }
    var pattern by rememberSaveable { mutableStateOf(BellPattern.StartAndEnd) }

    var startSheet by remember { mutableStateOf(false) }
    var countSheet by remember { mutableStateOf(false) }
    var defaultLengthSheet by remember { mutableStateOf(false) }
    var defaultBreakSheet by remember { mutableStateOf(false) }
    var lengthSheetIndex by remember { mutableStateOf<Int?>(null) }
    var breakSheetIndex by remember { mutableStateOf<Int?>(null) }
    var applyAllBreak by remember { mutableStateOf<Int?>(null) }

    val labelStart = stringResource(R.string.builder_label_start)
    val labelEnd = stringResource(R.string.builder_label_end)
    val quickValues = remember { QUICK_MINUTES.split(',').map { it.toInt() } }

    val result = remember(rows, pattern, startMinute, existingEntries, days) {
        ScheduleBuilder.build(
            BuilderSpec(
                startMinute = startMinute,
                rows = rows,
                pattern = pattern,
                existingEntries = existingEntries,
                maxEntries = MAX_PACK_ENTRIES,
            ),
            days = days,
            labelStart = { number -> labelStart.format(number) },
            labelEnd = { number -> labelEnd.format(number) },
        )
    }

    BackHandler { onDismiss() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedule_build_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_desc),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEachIndexed { index, preset ->
                    FilterChip(
                        selected = index == presetIndex,
                        onClick = {
                            presetIndex = index
                            startMinute = preset.startMinute
                            rows = preset.toRows()
                            preset.lengths.firstOrNull()?.let { defaultLength = it }
                            preset.breaks.firstOrNull()?.let { defaultBreak = it }
                        },
                        label = { Text(stringResource(preset.labelRes)) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButtonBlock(
                    label = stringResource(R.string.builder_start),
                    value = minutesToTime(startMinute),
                    onClick = { startSheet = true },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButtonBlock(
                    label = stringResource(R.string.builder_count),
                    value = rows.size.toString(),
                    onClick = { countSheet = true },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButtonBlock(
                    label = stringResource(R.string.builder_default_length),
                    value = defaultLength.toString(),
                    onClick = { defaultLengthSheet = true },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButtonBlock(
                    label = stringResource(R.string.builder_default_break),
                    value = defaultBreak.toString(),
                    onClick = { defaultBreakSheet = true },
                    modifier = Modifier.weight(1f),
                )
            }

            SectionHeader(text = stringResource(R.string.builder_pattern_title))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val patterns = listOf(
                    BellPattern.StartAndEnd to R.string.builder_pattern_both,
                    BellPattern.StartOnly to R.string.builder_pattern_start,
                    BellPattern.EndOnly to R.string.builder_pattern_end,
                )
                patterns.forEachIndexed { index, (value, labelRes) ->
                    SegmentedButton(
                        selected = pattern == value,
                        onClick = { pattern = value },
                        shape = SegmentedButtonDefaults.itemShape(index, patterns.size),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.builder_table_number),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    text = stringResource(R.string.builder_table_time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.builder_table_length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    text = stringResource(R.string.builder_table_break),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(68.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val spans = ScheduleBuilder.spans(startMinute, rows)
            rows.forEachIndexed { index, row ->
                val span = spans?.getOrNull(index)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp),
                    )
                    Text(
                        text = if (span != null && span.end <= ScheduleBuilder.LAST_MINUTE) {
                            minutesToTime(span.start) + "–" + minutesToTime(span.end)
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.lengthMinutes.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .width(56.dp)
                            .combinedClickable(
                                onClick = { lengthSheetIndex = index },
                                onLongClick = { },
                            ),
                    )
                    Text(
                        text = if (index == rows.lastIndex) "—" else row.breakMinutes.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (index == rows.lastIndex) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .width(68.dp)
                            .combinedClickable(
                                onClick = {
                                    if (index != rows.lastIndex) breakSheetIndex = index
                                },
                                onLongClick = {
                                    if (index != rows.lastIndex) applyAllBreak = row.breakMinutes
                                },
                            ),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            SectionHeader(text = stringResource(R.string.builder_preview_title))
            if (result.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.builder_preview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    result.entries.forEach { entry ->
                        Text(
                            text = entry.time,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                            ),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.builder_preview_count, result.entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            result.issues.forEach { issue ->
                Text(
                    text = builderIssueText(issue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.heightIn(min = 8.dp))
            Button(
                onClick = { onApply(result.entries) },
                enabled = result.isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.schedule_apply))
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }

    if (startSheet) {
        TimeSheet(
            title = stringResource(R.string.builder_start),
            initialMinute = startMinute,
            onDismiss = { startSheet = false },
            onPick = {
                startMinute = it
                startSheet = false
            },
        )
    }

    if (countSheet) {
        NumberSheet(
            title = stringResource(R.string.builder_count),
            initial = rows.size,
            range = 1..ScheduleBuilder.MAX_LESSONS,
            fieldLabel = stringResource(R.string.builder_count_field),
            onDismiss = { countSheet = false },
            onPick = { count ->
                countSheet = false
                val last = rows.lastOrNull() ?: LessonRow(defaultLength, defaultBreak)
                rows = List(count) { index -> rows.getOrElse(index) { last } }
            },
        )
    }

    if (defaultLengthSheet) {
        NumberSheet(
            title = stringResource(R.string.builder_default_length),
            initial = defaultLength,
            range = ScheduleBuilder.MIN_LESSON_MINUTES..ScheduleBuilder.MAX_LESSON_MINUTES,
            fieldLabel = stringResource(R.string.builder_length_field),
            quickValues = quickValues,
            onDismiss = { defaultLengthSheet = false },
            onPick = { value ->
                defaultLengthSheet = false
                defaultLength = value
                rows = rows.map { it.copy(lengthMinutes = value) }
            },
        )
    }

    if (defaultBreakSheet) {
        NumberSheet(
            title = stringResource(R.string.builder_default_break),
            initial = defaultBreak,
            range = 0..ScheduleBuilder.MAX_BREAK_MINUTES,
            fieldLabel = stringResource(R.string.builder_break_field),
            quickValues = quickValues,
            onDismiss = { defaultBreakSheet = false },
            onPick = { value ->
                defaultBreakSheet = false
                defaultBreak = value
                rows = rows.map { it.copy(breakMinutes = value) }
            },
        )
    }

    applyAllBreak?.let { value ->
        ConfirmDialog(
            title = stringResource(R.string.builder_apply_all_title),
            message = stringResource(R.string.builder_apply_all_text, value),
            confirmLabel = stringResource(R.string.schedule_apply),
            onConfirm = {
                applyAllBreak = null
                rows = rows.map { it.copy(breakMinutes = value) }
                defaultBreak = value
            },
            onDismiss = { applyAllBreak = null },
        )
    }

    lengthSheetIndex?.let { index ->
        val row = rows.getOrNull(index)
        if (row != null) {
            NumberSheet(
                title = stringResource(R.string.builder_length_title, index + 1),
                initial = row.lengthMinutes,
                range = ScheduleBuilder.MIN_LESSON_MINUTES..ScheduleBuilder.MAX_LESSON_MINUTES,
                fieldLabel = stringResource(R.string.builder_length_field),
                quickValues = quickValues,
                onDismiss = { lengthSheetIndex = null },
                onPick = { value ->
                    lengthSheetIndex = null
                    rows = rows.mapIndexed { position, item ->
                        if (position == index) item.copy(lengthMinutes = value) else item
                    }
                },
            )
        }
    }

    breakSheetIndex?.let { index ->
        val row = rows.getOrNull(index)
        if (row != null) {
            NumberSheet(
                title = stringResource(R.string.builder_break_title, index + 1),
                initial = row.breakMinutes,
                range = 0..ScheduleBuilder.MAX_BREAK_MINUTES,
                fieldLabel = stringResource(R.string.builder_break_field),
                quickValues = quickValues,
                onDismiss = { breakSheetIndex = null },
                onPick = { value ->
                    breakSheetIndex = null
                    rows = rows.mapIndexed { position, item ->
                        if (position == index) item.copy(breakMinutes = value) else item
                    }
                },
            )
        }
    }
}

@Composable
private fun builderIssueText(issue: BuilderIssue): String = when (issue) {
    BuilderIssue.NoLessons -> stringResource(R.string.builder_error_no_lessons)
    BuilderIssue.InvalidValues -> stringResource(R.string.builder_error_values)
    is BuilderIssue.PastMidnight -> stringResource(R.string.builder_error_past_midnight, issue.lesson)
    is BuilderIssue.TooManyBells ->
        stringResource(R.string.builder_error_too_many, issue.total, MAX_PACK_ENTRIES)
}
