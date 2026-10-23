package com.belltimer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belltimer.app.R
import com.belltimer.app.data.LessonTemplate
import com.belltimer.app.data.MAX_LESSON_MINUTES
import com.belltimer.app.data.MAX_PACK_MODULES
import com.belltimer.app.data.MAX_PRE_MINUTES
import com.belltimer.app.data.ModuleType
import com.belltimer.app.data.ScheduleModule
import com.belltimer.app.data.SchedulePack
import com.belltimer.app.data.TemplateLesson
import com.belltimer.app.data.WEEKDAYS
import com.belltimer.app.data.minutesToTime
import com.belltimer.app.data.withLessons
import java.util.UUID

/** A fresh id for a user template. */
fun newTemplateId(): String = UUID.randomUUID().toString()

/** "7 уроков · 08:30 – 14:40". */
@Composable
private fun templateSummary(template: LessonTemplate): String =
    pluralStringResource(R.plurals.lessons_count, template.lessons.size, template.lessons.size) +
        " · ${minutesToTime(template.start)} – ${minutesToTime(template.endMinute)}"

/**
 * A proportional strip of the day: lessons as blocks, breaks as gaps, so
 * different break lengths are visible at a glance.
 */
@Composable
private fun TemplateStrip(template: LessonTemplate, modifier: Modifier = Modifier) {
    val total = (template.endMinute - template.start).coerceAtLeast(1)
    val lessonColor = AppColors.module(ModuleType.Lesson)
    Row(modifier = modifier.fillMaxWidth().height(10.dp)) {
        template.lessons.forEachIndexed { index, lesson ->
            Box(
                modifier = Modifier
                    .weight(lesson.length.toFloat() / total)
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(lessonColor),
            )
            if (index < template.lessons.lastIndex && lesson.breakAfter > 0) {
                Spacer(Modifier.weight(lesson.breakAfter.toFloat() / total))
            }
        }
    }
}

/** Chooses a template to apply, copy or edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatePickerSheet(
    pack: SchedulePack,
    templates: List<LessonTemplate>,
    onDismiss: () -> Unit,
    onApply: (LessonTemplate) -> Unit,
    onCreateFrom: (LessonTemplate) -> Unit,
    onEdit: (LessonTemplate) -> Unit,
    onDelete: (LessonTemplate) -> Unit,
    onSaveFromPack: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<LessonTemplate?>(null) }
    val hasLessons = pack.modules.any { it.type == ModuleType.Lesson }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.templates_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(if (hasLessons) R.string.templates_hint_update else R.string.templates_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasLessons) {
                OutlinedButton(onClick = onSaveFromPack, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.templates_save_from_pack))
                }
            }
            for (template in templates) {
                TemplateCard(
                    template = template,
                    applyLabel = stringResource(if (hasLessons) R.string.templates_apply_update else R.string.templates_apply),
                    onApply = { onApply(template) },
                    onCreateFrom = { onCreateFrom(template) },
                    onEdit = { onEdit(template) },
                    onDelete = { confirmDelete = template },
                )
            }
        }
    }

    confirmDelete?.let { template ->
        ConfirmDialog(
            title = stringResource(R.string.template_delete_title),
            text = stringResource(R.string.template_delete_text, template.displayName()),
            confirm = stringResource(R.string.delete),
            onConfirm = { onDelete(template) },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun TemplateCard(
    template: LessonTemplate,
    applyLabel: String,
    onApply: () -> Unit,
    onCreateFrom: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        template.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        templateSummary(template),
                        style = MaterialTheme.typography.bodySmall.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = if (template.builtIn) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        stringResource(if (template.builtIn) R.string.template_builtin else R.string.template_own),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TemplateStrip(template, modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onApply) { Text(applyLabel) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCreateFrom) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.template_create_from))
                }
                if (!template.builtIn) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.template_edit))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}

/** Full-screen editor of a user template: each lesson and each break separately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorDialog(
    initial: LessonTemplate,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (LessonTemplate) -> Unit,
) {
    var template by remember(initial) { mutableStateOf(initial) }
    fun updateLesson(index: Int, lesson: TemplateLesson) {
        template = template.copy(lessons = template.lessons.toMutableList().also { it[index] = lesson })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                    },
                    title = { Text(stringResource(if (isNew) R.string.template_new_title else R.string.template_edit_title)) },
                    actions = {
                        TextButton(onClick = { onSave(template) }, enabled = template.isValid) {
                            Text(stringResource(R.string.save))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = template.name,
                    onValueChange = { template = template.copy(name = it) },
                    label = { Text(stringResource(R.string.template_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TimeField(
                    label = stringResource(R.string.grid_start),
                    minute = template.start,
                    onChange = { template = template.copy(start = it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Stepper(
                    label = stringResource(R.string.module_pre),
                    value = template.pre,
                    range = 0..MAX_PRE_MINUTES,
                    onChange = { template = template.copy(pre = it) },
                    format = { if (it == 0) stringResource(R.string.grid_pre_none) else durationText(it) },
                )
                AppCard {
                    Caption(stringResource(R.string.template_overview))
                    Spacer(Modifier.height(8.dp))
                    TemplateStrip(template)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        templateSummary(template),
                        style = MaterialTheme.typography.bodySmall.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val spans = template.spans()
                template.lessons.forEachIndexed { index, lesson ->
                    val span = spans[index]
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ModuleBadge(ModuleType.Lesson, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.template_lesson_number, index + 1),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "${minutesToTime(span.first)} – ${minutesToTime(span.last)}",
                                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = {
                                    template = template.copy(lessons = template.lessons.filterIndexed { i, _ -> i != index })
                                },
                                enabled = template.lessons.size > 1,
                            ) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete)) }
                        }
                        Stepper(
                            label = stringResource(R.string.grid_length),
                            value = lesson.length,
                            range = 5..MAX_LESSON_MINUTES,
                            step = 5,
                            onChange = { updateLesson(index, lesson.copy(length = it)) },
                            format = { durationText(it) },
                        )
                    }
                    if (index < template.lessons.lastIndex) {
                        BreakRow(
                            minutes = lesson.breakAfter,
                            onChange = { updateLesson(index, lesson.copy(breakAfter = it)) },
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        val last = template.lessons.lastOrNull() ?: TemplateLesson()
                        template = template.copy(lessons = template.lessons + last)
                    },
                    enabled = template.lessons.size < MAX_PACK_MODULES,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.template_add_lesson))
                }
                if (!template.isValid) {
                    Text(
                        stringResource(
                            if (template.name.isBlank()) R.string.template_problem_name else R.string.grid_invalid,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

/** The break between two lessons, drawn between their cards. */
@Composable
private fun BreakRow(minutes: Int, onChange: (Int) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Stepper(
                label = stringResource(R.string.grid_break),
                value = minutes,
                range = 0..MAX_LESSON_MINUTES,
                step = 5,
                onChange = onChange,
                format = { durationText(it) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                for (preset in listOf(5, 10, 15, 20, 30, 40)) {
                    QuickChip("$preset", minutes == preset) { onChange(preset) }
                }
            }
        }
    }
}

/** Applies a template to a pack: creates its grid or updates the existing one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplyTemplateSheet(
    pack: SchedulePack,
    template: LessonTemplate,
    onDismiss: () -> Unit,
    onApply: (SchedulePack) -> Unit,
) {
    val existingLessons = pack.modules.filter { it.type == ModuleType.Lesson }
    var days by remember { mutableStateOf(existingLessons.lastOrNull()?.days ?: pack.modules.lastOrNull()?.days ?: WEEKDAYS) }
    val labelFormat = stringResource(R.string.grid_label)
    val lessons = template.build(days) { labelFormat.format(it) }
    val updated = lessons?.let { pack.withLessons(it, replace = true) }
    val appended = lessons?.let { pack.withLessons(it, replace = false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(template.displayName(), style = MaterialTheme.typography.titleLarge)
            if (existingLessons.isNotEmpty()) {
                Text(
                    stringResource(R.string.templates_update_hint, existingLessons.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Caption(stringResource(R.string.module_days))
            DayPicker(days = days, onChange = { days = it })
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Caption(stringResource(R.string.module_preview), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.height(4.dp))
                    if (lessons == null) {
                        Text(stringResource(R.string.grid_invalid), color = MaterialTheme.colorScheme.error)
                    } else {
                        TemplatePreview(lessons)
                    }
                }
            }
            val fitsUpdate = updated != null && updated.modules.size <= MAX_PACK_MODULES
            val fitsAppend = appended != null && appended.modules.size <= MAX_PACK_MODULES
            if (lessons != null && (!fitsUpdate || (existingLessons.isNotEmpty() && !fitsAppend))) {
                Text(
                    stringResource(R.string.grid_too_many, MAX_PACK_MODULES),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (existingLessons.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { appended?.let(onApply) },
                        enabled = fitsAppend,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.grid_append)) }
                }
                Button(
                    onClick = { updated?.let(onApply) },
                    enabled = fitsUpdate,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (existingLessons.isEmpty()) R.string.grid_create else R.string.templates_apply_update))
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** Lessons with the breaks between them. */
@Composable
private fun TemplatePreview(lessons: List<ScheduleModule>) {
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    lessons.forEachIndexed { index, lesson ->
        Row {
            Text(
                lesson.timeRange(),
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                color = content,
                modifier = Modifier.weight(1f),
            )
            Text(lesson.label, style = MaterialTheme.typography.bodyMedium, color = content)
        }
        val next = lessons.getOrNull(index + 1)
        if (next != null) {
            val gap = next.start - (lesson.start + lesson.length)
            Text(
                stringResource(R.string.template_break_line, durationText(gap)),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
