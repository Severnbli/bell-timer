package com.belltimer.app.data

/** A problem that prevents sending the schedule to the device. */
sealed interface ScheduleIssue {
    data object TooManyPacks : ScheduleIssue
    data object TooManyPackModules : ScheduleIssue
    data object TooManyPackDates : ScheduleIssue
    data object TooManyPackRanges : ScheduleIssue
    data object InvalidModule : ScheduleIssue
    data object PackWindowInvalid : ScheduleIssue
    data object AutoWindowInvalid : ScheduleIssue
    data class FirmwareTooOld(val api: Int) : ScheduleIssue
    data object RangesNeedFirmware : ScheduleIssue
}

/** Everything the device would reject, checked before a single request is made. */
fun BellSchedule.validate(deviceApi: Int): ScheduleIssue? {
    if (!auto.isValid) return ScheduleIssue.AutoWindowInvalid
    if (packs.size > MAX_PACKS) return ScheduleIssue.TooManyPacks
    if (packs.any { it.modules.size > MAX_PACK_MODULES }) return ScheduleIssue.TooManyPackModules
    if (totalModules > MAX_MODULES) return ScheduleIssue.TooManyPackModules
    if (packs.any { it.window.dates.size > MAX_PACK_DATES }) return ScheduleIssue.TooManyPackDates
    if (packs.any { it.window.ranges.size > MAX_PACK_RANGES }) return ScheduleIssue.TooManyPackRanges
    if (packs.any { !it.window.isValid }) return ScheduleIssue.PackWindowInvalid
    if (packs.any { pack -> pack.modules.any { !it.isValid } }) return ScheduleIssue.InvalidModule
    if (deviceApi < API_MODULES) return ScheduleIssue.FirmwareTooOld(deviceApi)
    // Older firmware ignores "ranges" and would run such a pack every day.
    if (deviceApi < API_UPDATES && packs.any { it.window.ranges.size > 1 }) {
        return ScheduleIssue.RangesNeedFirmware
    }
    return null
}

/** Whether a decoded document fits into the device at all. */
fun BellSchedule.withinDeviceLimits(): Boolean =
    packs.size <= MAX_PACKS &&
        totalModules <= MAX_MODULES &&
        packs.all {
            it.modules.size <= MAX_PACK_MODULES &&
                it.window.dates.size <= MAX_PACK_DATES &&
                it.window.ranges.size <= MAX_PACK_RANGES
        }

fun BellSchedule.canAddPack(): Boolean = packs.size < MAX_PACKS

fun SchedulePack.canAddModules(count: Int = 1): Boolean = modules.size + count <= MAX_PACK_MODULES

fun SchedulePack.canAddDate(): Boolean = window.dates.size < MAX_PACK_DATES

fun PackWindow.canAddRange(): Boolean = ranges.size < MAX_PACK_RANGES

/** Labels are capped by the firmware in UTF-8 bytes. */
fun labelTooLong(label: String): Boolean =
    label.toByteArray(Charsets.UTF_8).size > MAX_LABEL_BYTES

fun packNameTooLong(name: String): Boolean =
    name.toByteArray(Charsets.UTF_8).size > MAX_PACK_NAME_BYTES
