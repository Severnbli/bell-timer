package com.belltimer.app.data

import kotlinx.serialization.Serializable

/** One lesson of a template and the break that follows it. */
@Serializable
data class TemplateLesson(
    val length: Int = 45,
    /** Minutes until the next lesson; ignored after the last one. */
    val breakAfter: Int = 10,
)

/**
 * A reusable day of lessons ("шаблон сетки"). Every lesson has its own length
 * and every break its own duration, so a long break can sit anywhere.
 * Built-in templates are read-only; the user's own ones are stored on the
 * phone by [TemplateStore].
 */
@Serializable
data class LessonTemplate(
    val id: String,
    val name: String,
    val start: Int = 8 * 60,
    /** Warning bell before every lesson, minutes; 0 = none. */
    val pre: Int = 0,
    val lessons: List<TemplateLesson> = emptyList(),
) {
    val builtIn: Boolean get() = id.startsWith(BUILT_IN_PREFIX)

    /** Start and end minute of every lesson. */
    fun spans(): List<IntRange> {
        var cursor = start
        return lessons.map { lesson ->
            val span = cursor..(cursor + lesson.length)
            cursor += lesson.length + lesson.breakAfter
            span
        }
    }

    val endMinute: Int get() = spans().lastOrNull()?.last ?: start

    val isValid: Boolean
        get() = name.isNotBlank() &&
            lessons.size in 1..MAX_PACK_MODULES &&
            start in 0..LAST_MINUTE &&
            pre in 0..MAX_PRE_MINUTES && pre <= start &&
            lessons.all { it.length in 1..MAX_LESSON_MINUTES && it.breakAfter in 0..MAX_LESSON_MINUTES } &&
            endMinute <= LAST_MINUTE

    /** Lesson modules for [days], or null when the template is not usable. */
    fun build(days: Set<Int>, label: (Int) -> String): List<ScheduleModule>? {
        if (!isValid || days.isEmpty()) return null
        return spans().mapIndexed { index, span ->
            ScheduleModule(
                type = ModuleType.Lesson,
                start = span.first,
                length = span.last - span.first,
                pre = pre,
                days = days,
                label = label(index + 1),
            )
        }
    }

    companion object {
        const val BUILT_IN_PREFIX = "builtin:"

        /**
         * Reads the lessons of a pack back into a template. Lessons are taken
         * in time order; overlapping ones are skipped. Null without lessons.
         */
        fun fromModules(id: String, name: String, modules: List<ScheduleModule>): LessonTemplate? {
            val lessons = modules.filter { it.type == ModuleType.Lesson }.sortedBy { it.start }
            if (lessons.isEmpty()) return null
            val kept = mutableListOf(lessons.first())
            for (lesson in lessons.drop(1)) {
                val previous = kept.last()
                if (lesson.start >= previous.start + previous.length) kept += lesson
            }
            val items = kept.mapIndexed { index, lesson ->
                val next = kept.getOrNull(index + 1)
                TemplateLesson(
                    length = lesson.length,
                    breakAfter = next?.let { it.start - (lesson.start + lesson.length) } ?: 10,
                )
            }
            return LessonTemplate(
                id = id,
                name = name,
                start = kept.first().start,
                pre = kept.first().pre,
                lessons = items,
            )
        }
    }
}

/** Templates that ship with the app. */
object BuiltInTemplates {
    private fun lessons(lengths: List<Int>, breaks: List<Int>) =
        lengths.mapIndexed { index, length -> TemplateLesson(length, breaks.getOrElse(index) { 10 }) }

    val all: List<LessonTemplate> = listOf(
        LessonTemplate(
            id = "${LessonTemplate.BUILT_IN_PREFIX}school45",
            name = "Школа, 45 мин",
            start = 8 * 60 + 30,
            lessons = lessons(List(7) { 45 }, listOf(10, 20, 20, 10, 10, 10)),
        ),
        LessonTemplate(
            id = "${LessonTemplate.BUILT_IN_PREFIX}school40",
            name = "Школа, 40 мин",
            start = 8 * 60 + 30,
            lessons = lessons(List(6) { 40 }, listOf(10, 15, 15, 10, 10)),
        ),
        LessonTemplate(
            id = "${LessonTemplate.BUILT_IN_PREFIX}college",
            name = "Колледж, пары 2×45",
            start = 8 * 60 + 30,
            lessons = lessons(List(8) { 45 }, listOf(5, 15, 5, 40, 5, 15, 5)),
        ),
        LessonTemplate(
            id = "${LessonTemplate.BUILT_IN_PREFIX}university",
            name = "Вуз, пары 90 мин",
            start = 9 * 60,
            lessons = lessons(List(5) { 90 }, listOf(10, 40, 10, 10)),
        ),
    )
}

/** Replaces the lessons of a pack (keeping bells and series) or adds to them. */
fun SchedulePack.withLessons(lessons: List<ScheduleModule>, replace: Boolean): SchedulePack {
    val kept = if (replace) modules.filter { it.type != ModuleType.Lesson } else modules
    return withModules(kept + lessons)
}
