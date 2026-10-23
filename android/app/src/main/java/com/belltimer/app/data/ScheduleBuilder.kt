package com.belltimer.app.data

/** Which boundary of a lesson rings. */
enum class BellPattern { StartAndEnd, StartOnly, EndOnly }

/** One lesson in the builder table: its length and the break that follows it. */
data class LessonRow(
    val lengthMinutes: Int = ScheduleBuilder.DEFAULT_LESSON_MINUTES,
    val breakMinutes: Int = ScheduleBuilder.DEFAULT_BREAK_MINUTES,
)

/** Computed start/end of one lesson. */
data class LessonSpan(
    val number: Int,
    val start: Int,
    val end: Int,
)

sealed interface BuilderIssue {
    data object NoLessons : BuilderIssue
    data object InvalidValues : BuilderIssue
    data class PastMidnight(val lesson: Int) : BuilderIssue
    data class TooManyBells(val total: Int) : BuilderIssue
}

data class BuilderSpec(
    val startMinute: Int = 8 * 60,
    val rows: List<LessonRow> = emptyList(),
    val pattern: BellPattern = BellPattern.StartAndEnd,
    val existingEntries: Int = 0,
    val maxEntries: Int = MAX_PACK_ENTRIES,
)

data class BuilderResult(
    val entries: List<BellEntry> = emptyList(),
    val issues: List<BuilderIssue> = emptyList(),
) {
    val isValid: Boolean get() = issues.isEmpty() && entries.isNotEmpty()
    val count: Int get() = entries.size
}

/**
 * Builds a bell list from a per-lesson table. Pure: the caller supplies the
 * label formatting, so the same code drives the UI and the unit tests.
 */
object ScheduleBuilder {

    const val LAST_MINUTE = 24 * 60 - 1
    const val MAX_LESSONS = 16
    const val MIN_LESSON_MINUTES = 5
    const val MAX_LESSON_MINUTES = 240
    const val MAX_BREAK_MINUTES = 120
    const val DEFAULT_LESSON_MINUTES = 45
    const val DEFAULT_BREAK_MINUTES = 10

    /** Start and end of every lesson, or null when a value is unusable. */
    fun spans(startMinute: Int, rows: List<LessonRow>): List<LessonSpan>? {
        if (startMinute !in 0..LAST_MINUTE) return null
        if (rows.isEmpty()) return emptyList()
        var cursor = startMinute
        val out = ArrayList<LessonSpan>(rows.size)
        rows.forEachIndexed { index, row ->
            if (row.lengthMinutes !in MIN_LESSON_MINUTES..MAX_LESSON_MINUTES) return null
            if (row.breakMinutes !in 0..MAX_BREAK_MINUTES) return null
            val start = cursor
            val end = start + row.lengthMinutes
            out += LessonSpan(number = index + 1, start = start, end = end)
            cursor = end + row.breakMinutes
        }
        return out
    }

    fun build(
        spec: BuilderSpec,
        days: Set<Int> = ALL_DAYS,
        labelStart: (Int) -> String = { "" },
        labelEnd: (Int) -> String = { "" },
    ): BuilderResult {
        val issues = mutableListOf<BuilderIssue>()
        if (spec.rows.isEmpty()) issues += BuilderIssue.NoLessons
        val spans = spans(spec.startMinute, spec.rows)
        if (spans == null) issues += BuilderIssue.InvalidValues
        if (spans != null) {
            spans.firstOrNull { it.end > LAST_MINUTE }?.let { issues += BuilderIssue.PastMidnight(it.number) }
        }
        if (issues.isNotEmpty() || spans == null) {
            return BuilderResult(issues = issues)
        }

        val raw = mutableListOf<BellEntry>()
        for (span in spans) {
            when (spec.pattern) {
                BellPattern.StartAndEnd -> {
                    raw += bell(span.start, labelStart(span.number), days)
                    raw += bell(span.end, labelEnd(span.number), days)
                }
                BellPattern.StartOnly -> raw += bell(span.start, labelStart(span.number), days)
                BellPattern.EndOnly -> raw += bell(span.end, labelEnd(span.number), days)
            }
        }
        // A zero-minute break puts the end of one lesson on the same minute as
        // the start of the next one; that boundary rings once.
        val entries = raw.mergeSameMinute().sortedSchedule()
        val total = spec.existingEntries + entries.size
        if (total > spec.maxEntries) issues += BuilderIssue.TooManyBells(total)
        return BuilderResult(entries = entries, issues = issues)
    }

    private fun bell(minute: Int, label: String, days: Set<Int>): BellEntry =
        BellEntry(time = minutesToTime(minute), days = days, label = label)
}
