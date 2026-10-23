package com.belltimer.app.data

/** Result of shifting a bell list by a number of minutes. */
data class ShiftResult(
    val entries: List<BellEntry> = emptyList(),
    val overflow: Boolean = false,
)

/** Moves every bell (and its pair) by [deltaMinutes]; nothing moves on overflow. */
fun List<BellEntry>.shiftBy(deltaMinutes: Int): ShiftResult {
    if (deltaMinutes == 0) return ShiftResult(entries = sortedSchedule())
    val shifted = mapNotNull { entry ->
        val minute = entry.minutes
        if (!entry.isValid) return@mapNotNull entry
        val moved = minute + deltaMinutes
        if (moved !in 0..ScheduleBuilder.LAST_MINUTE) return@mapNotNull null
        val pairMoved = entry.pairMinutes?.let { it + deltaMinutes }
        if (pairMoved != null && pairMoved !in 0..ScheduleBuilder.LAST_MINUTE) {
            return@mapNotNull null
        }
        entry.copy(
            time = minutesToTime(moved),
            pair = pairMoved?.let { minutesToTime(it) },
        )
    }
    val overflow = shifted.size != size
    return if (overflow) {
        ShiftResult(overflow = true)
    } else {
        ShiftResult(entries = shifted.sortedSchedule())
    }
}
