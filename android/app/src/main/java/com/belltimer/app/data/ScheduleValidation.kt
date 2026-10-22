package com.belltimer.app.data

/** A problem that prevents sending the schedule to the device. */
sealed interface ScheduleIssue {
    data object TooManyEntries : ScheduleIssue
    data object TooManyGroups : ScheduleIssue
    data object TooManyOverrides : ScheduleIssue
    data class OverlappingWeekdays(
        val day: Int,
        val groupId: Int,
        val otherGroupId: Int,
    ) : ScheduleIssue

    data object AutoWindowInvalid : ScheduleIssue
    data class ScheduleWithoutDays(val groupId: Int) : ScheduleIssue
    data class FirmwareTooOld(val api: Int) : ScheduleIssue
}

/** Everything the device would reject, checked before a single request is made. */
fun BellSchedule.validate(deviceApi: Int): ScheduleIssue? {
    if (!auto.isValid) return ScheduleIssue.AutoWindowInvalid
    if (groups.size > MAX_SCHEDULE_GROUPS) return ScheduleIssue.TooManyGroups
    groups.firstOrNull { it.days.isEmpty() }?.let { return ScheduleIssue.ScheduleWithoutDays(it.id) }
    if (totalEntries > MAX_SCHEDULE_ENTRIES) return ScheduleIssue.TooManyEntries
    if (overrides.size > MAX_DATE_OVERRIDES) return ScheduleIssue.TooManyOverrides
    firstOverlap()?.let { return it }
    if (deviceApi < API_SPECIAL_DAYS && overrides.isNotEmpty()) {
        return ScheduleIssue.FirmwareTooOld(deviceApi)
    }
    return null
}

/** The first weekday claimed by more than one schedule. */
fun BellSchedule.firstOverlap(): ScheduleIssue.OverlappingWeekdays? {
    for (day in 1..7) {
        val owners = groups.filter { day in it.days }
        if (owners.size > 1) {
            return ScheduleIssue.OverlappingWeekdays(day, owners[0].id, owners[1].id)
        }
    }
    return null
}

fun BellSchedule.canAddEntries(count: Int): Boolean = totalEntries + count <= MAX_SCHEDULE_ENTRIES

fun BellSchedule.canAddGroup(): Boolean = groups.size < MAX_SCHEDULE_GROUPS

fun BellSchedule.canAddOverride(): Boolean = overrides.size < MAX_DATE_OVERRIDES

/** Labels are capped by the firmware in UTF-8 bytes; the editor caps characters. */
fun labelTooLong(label: String): Boolean =
    label.toByteArray(Charsets.UTF_8).size > MAX_LABEL_BYTES
