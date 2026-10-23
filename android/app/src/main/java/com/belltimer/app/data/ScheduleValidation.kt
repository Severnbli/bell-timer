package com.belltimer.app.data

/** A problem that prevents sending the schedule to the device. */
sealed interface ScheduleIssue {
    data object TooManyPacks : ScheduleIssue
    data object TooManyPackEntries : ScheduleIssue
    data object TooManyPackDates : ScheduleIssue
    data object PackWindowInvalid : ScheduleIssue
    data object AutoWindowInvalid : ScheduleIssue
    data class FirmwareTooOld(val api: Int) : ScheduleIssue
}

/** Everything the device would reject, checked before a single request is made. */
fun BellSchedule.validate(deviceApi: Int): ScheduleIssue? {
    if (!auto.isValid) return ScheduleIssue.AutoWindowInvalid
    if (packs.size > MAX_PACKS) return ScheduleIssue.TooManyPacks
    packs.firstOrNull { it.entries.size > MAX_PACK_ENTRIES }
        ?.let { return ScheduleIssue.TooManyPackEntries }
    packs.firstOrNull { it.window.dates.size > MAX_PACK_DATES }
        ?.let { return ScheduleIssue.TooManyPackDates }
    packs.firstOrNull { !it.window.isValid }
        ?.let { return ScheduleIssue.PackWindowInvalid }
    if (totalEntries > MAX_SCHEDULE_ENTRIES) return ScheduleIssue.TooManyPackEntries
    if (packs.isNotEmpty() && deviceApi < API_PACKS) return ScheduleIssue.FirmwareTooOld(deviceApi)
    return null
}

fun BellSchedule.canAddPack(): Boolean = packs.size < MAX_PACKS

fun SchedulePack.canAddEntry(): Boolean = entries.size < MAX_PACK_ENTRIES

fun SchedulePack.canAddDate(): Boolean = window.dates.size < MAX_PACK_DATES

/** Labels are capped by the firmware in UTF-8 bytes; the editor caps characters. */
fun labelTooLong(label: String): Boolean =
    label.toByteArray(Charsets.UTF_8).size > MAX_LABEL_BYTES

fun packNameTooLong(name: String): Boolean =
    name.toByteArray(Charsets.UTF_8).size > MAX_PACK_NAME_BYTES
