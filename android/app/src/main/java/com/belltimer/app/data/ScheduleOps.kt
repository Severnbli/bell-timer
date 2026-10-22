package com.belltimer.app.data

/** How the builder result is merged into the list being edited. */
enum class BuilderApplyMode { Replace, Append }

/** Result of shifting a bell list by a number of minutes. */
data class ShiftResult(
    val entries: List<BellEntry> = emptyList(),
    val overflow: Boolean = false,
)

/** Moves every bell by [deltaMinutes]; nothing moves if a bell would leave the day. */
fun List<BellEntry>.shiftBy(deltaMinutes: Int): ShiftResult {
    if (deltaMinutes == 0) return ShiftResult(entries = sortedSchedule())
    val shifted = mapNotNull { entry ->
        val minute = entry.minutes
        if (!entry.isValid) return@mapNotNull entry
        val moved = minute + deltaMinutes
        if (moved !in 0..ScheduleBuilder.LAST_MINUTE) return@mapNotNull null
        entry.copy(time = minutesToTime(moved))
    }
    val overflow = shifted.size != size
    return if (overflow) ShiftResult(overflow = true) else ShiftResult(entries = shifted.sortedSchedule())
}

/** Bells created inside a schedule inherit its weekdays; override bells keep none. */
fun List<BellEntry>.inheritDays(days: Set<Int>): List<BellEntry> =
    map { if (it.days.isEmpty()) it.copy(days = days) else it }

/** Picks the first weekday no schedule owns yet, falling back to weekdays. */
fun BellSchedule.firstFreeDays(): Set<Int> {
    val used = groups.flatMap { it.days }.toSet()
    val free = (1..7).firstOrNull { it !in used } ?: return WEEKDAYS
    return setOf(free)
}
