package com.belltimer.app.data

/**
 * Generates a day of lessons: a start time, a number of lessons of equal
 * length and the breaks between them, with an optional long break.
 */
data class LessonGrid(
    val start: Int = 8 * 60,
    val count: Int = 6,
    val length: Int = 45,
    val breakMinutes: Int = 10,
    /** Lesson number after which [longBreakMinutes] is used instead; 0 = none. */
    val longBreakAfter: Int = 0,
    val longBreakMinutes: Int = 20,
    val pre: Int = 0,
    val days: Set<Int> = WEEKDAYS,
) {
    /** Lesson modules, or null when a lesson would end after midnight. */
    fun build(label: (Int) -> String): List<ScheduleModule>? {
        if (count !in 1..MAX_PACK_MODULES || length !in 1..MAX_LESSON_MINUTES) return null
        if (breakMinutes !in 0..MAX_LESSON_MINUTES || longBreakMinutes !in 0..MAX_LESSON_MINUTES) return null
        if (days.isEmpty()) return null
        var cursor = start
        val lessons = ArrayList<ScheduleModule>(count)
        for (number in 1..count) {
            val module = ScheduleModule(
                type = ModuleType.Lesson,
                start = cursor,
                length = length,
                pre = pre,
                days = days,
                label = label(number),
            )
            if (!module.isValid) return null
            lessons += module
            cursor += length + if (number == longBreakAfter) longBreakMinutes else breakMinutes
        }
        return lessons
    }
}
