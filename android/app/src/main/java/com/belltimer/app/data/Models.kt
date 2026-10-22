package com.belltimer.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

const val DEFAULT_BASE_URL = "http://192.168.4.1"
const val DEFAULT_RING_SECONDS = 5

/** Firmware limits, mirrored in firmware/BellTimer/BellTimer.ino. */
const val MAX_SCHEDULE_ENTRIES = 256
const val MAX_SCHEDULE_GROUPS = 12
const val MAX_DATE_OVERRIDES = 90
const val MAX_LABEL_BYTES = 32

/** Firmware accepts 1..60 seconds for a single bell. */
const val MIN_RING_SECONDS = 1
const val MAX_RING_SECONDS = 60

/** Largest value the compact slider offers before switching to the custom field. */
const val SLIDER_MAX_SECONDS = 10

/** Schedule document version written by this app. */
const val SCHEDULE_VERSION = 3

/** Device API level that understands schedule version 3 (special days). */
const val API_SPECIAL_DAYS = 3

data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = "",
    val ringDefault: Int = DEFAULT_RING_SECONDS,
)

data class DeviceStatus(
    val time: LocalDateTime?,
    val rtcOk: Boolean,
    val mode: String,
    val ringing: Boolean,
    val ringLeft: Int,
    val ringDefault: Int,
    val entries: Int,
    val errors: List<String>,
    val fw: String,
    val api: Int = 2,
) {
    val isAuto: Boolean get() = mode == "auto"
    val supportsSpecialDays: Boolean get() = api >= API_SPECIAL_DAYS
}

data class BellEntry(
    val time: String,
    val days: Set<Int>,
    val duration: Int,
    val label: String,
) {
    val minutes: Int
        get() {
            val parts = time.split(':')
            if (parts.size != 2) return INVALID_MINUTE
            val hour = parts[0].toIntOrNull() ?: return INVALID_MINUTE
            val minute = parts[1].toIntOrNull() ?: return INVALID_MINUTE
            if (hour !in 0..23 || minute !in 0..59) return INVALID_MINUTE
            return hour * 60 + minute
        }

    val isValid: Boolean get() = minutes != INVALID_MINUTE

    fun toDto(days: List<Int>?): ScheduleEntryDto = ScheduleEntryDto(
        time = time,
        days = days,
        duration = duration,
        label = label,
    )

    companion object {
        const val INVALID_MINUTE = Int.MAX_VALUE
    }
}

fun List<BellEntry>.sortedSchedule(): List<BellEntry> =
    sortedWith(compareBy<BellEntry>({ it.minutes }, { it.label }))

/**
 * Collapses bells that fall on the same minute into one: the longer duration
 * wins and the earliest label is kept. The firmware applies the same rule when
 * a schedule bell and an extra bell land on the same HH:MM.
 */
fun List<BellEntry>.mergeSameMinute(): List<BellEntry> {
    val merged = LinkedHashMap<Int, BellEntry>()
    for (entry in this) {
        val key = entry.minutes
        val existing = merged[key]
        merged[key] = when {
            existing == null -> entry
            entry.duration > existing.duration ->
                entry.copy(label = existing.label.ifBlank { entry.label })
            else -> existing
        }
    }
    return merged.values.toList()
}

/**
 * A named set of bells that repeats on the selected weekdays. Each weekday
 * belongs to at most one schedule; the device still uses the first match.
 */
data class ScheduleGroup(
    val id: Int,
    val name: String = "",
    val days: Set<Int> = ALL_DAYS,
    val entries: List<BellEntry> = emptyList(),
) {
    /** Bells inherit the weekdays of their schedule. */
    fun withDays(newDays: Set<Int>): ScheduleGroup =
        copy(days = newDays, entries = entries.map { it.copy(days = newDays) })

    fun withEntries(newEntries: List<BellEntry>): ScheduleGroup =
        copy(entries = newEntries.sortedSchedule())

    fun withEntry(index: Int, entry: BellEntry): ScheduleGroup {
        if (index !in entries.indices) return this
        val updated = entries.toMutableList()
        updated[index] = entry
        return withEntries(updated)
    }

    fun withoutEntry(index: Int): ScheduleGroup {
        if (index !in entries.indices) return this
        val updated = entries.toMutableList()
        updated.removeAt(index)
        return withEntries(updated)
    }

    val firstMinute: Int?
        get() = entries.minOfOrNull { it.minutes }?.takeIf { it != BellEntry.INVALID_MINUTE }

    val lastMinute: Int?
        get() = entries.maxOfOrNull { it.minutes }?.takeIf { it != BellEntry.INVALID_MINUTE }
}

val ALL_DAYS: Set<Int> = (1..7).toSet()
val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

enum class DayOverrideMode { Mute, Replace, Add }

/** A bell list for one specific calendar date. */
data class DayOverride(
    val date: LocalDate,
    val mode: DayOverrideMode,
    /** Mute: empty. Replace: the full bell list. Add: extra bells on top of the schedule. */
    val entries: List<BellEntry> = emptyList(),
    /** UI only: the schedule this override was created from. The firmware ignores it. */
    val basedOnGroupId: Int? = null,
)

/** Optional date range in which auto mode is allowed to ring. */
data class AutoWindow(
    val start: LocalDate? = null,
    val stop: LocalDate? = null,
) {
    val isSet: Boolean get() = start != null || stop != null

    val isValid: Boolean
        get() = start == null || stop == null || !stop.isBefore(start)

    fun allows(date: LocalDate): Boolean =
        (start == null || !date.isBefore(start)) &&
            (stop == null || !date.isAfter(stop))
}

data class WeekdayConflict(val day: Int, val group: ScheduleGroup)

data class UpcomingBell(val at: LocalDateTime, val entry: BellEntry)

data class BellSchedule(
    val groups: List<ScheduleGroup> = emptyList(),
    val overrides: List<DayOverride> = emptyList(),
    val auto: AutoWindow = AutoWindow(),
) {
    val totalEntries: Int
        get() = groups.sumOf { it.entries.size } + overrides.sumOf { it.entries.size }

    val isEmpty: Boolean get() = groups.isEmpty() && overrides.isEmpty()

    fun groupById(id: Int?): ScheduleGroup? =
        if (id == null) null else groups.firstOrNull { it.id == id }

    fun nextGroupId(): Int {
        val used = groups.map { it.id }.toSet()
        return (1..MAX_SCHEDULE_GROUPS).firstOrNull { it !in used }
            ?: (groups.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun overrideFor(date: LocalDate): DayOverride? = overrides.firstOrNull { it.date == date }

    /** The schedule that normally rings on [date], ignoring overrides. */
    fun baseGroupFor(date: LocalDate): ScheduleGroup? =
        groups.firstOrNull { date.dayOfWeek.value in it.days }

    fun withGroup(group: ScheduleGroup): BellSchedule {
        val exists = groups.any { it.id == group.id }
        val updated = if (exists) {
            groups.map { if (it.id == group.id) group else it }
        } else {
            groups + group
        }
        return copy(groups = updated)
    }

    fun withoutGroup(id: Int): BellSchedule = copy(groups = groups.filterNot { it.id == id })

    fun withOverride(override: DayOverride): BellSchedule = copy(
        overrides = (overrides.filterNot { it.date == override.date } + override)
            .sortedBy { it.date },
    )

    fun withoutOverride(date: LocalDate): BellSchedule =
        copy(overrides = overrides.filterNot { it.date == date })

    /** Weekdays in [days] that another schedule already owns. */
    fun conflictingDays(days: Set<Int>, excludeGroupId: Int? = null): List<WeekdayConflict> =
        days.sorted().mapNotNull { day ->
            groups.firstOrNull { it.id != excludeGroupId && day in it.days }
                ?.let { WeekdayConflict(day, it) }
        }

    /**
     * The single source of truth for what rings on a date. The firmware applies
     * exactly the same rules (see docs/firmware-api-v3.md).
     */
    fun bellsFor(date: LocalDate): List<BellEntry> {
        if (!auto.allows(date)) return emptyList()
        val weekday = date.dayOfWeek.value
        val base = baseGroupFor(date)
            ?.entries
            ?.filter { it.days.isEmpty() || weekday in it.days }
            .orEmpty()
        val override = overrideFor(date) ?: return base.sortedSchedule()
        return when (override.mode) {
            DayOverrideMode.Mute -> emptyList()
            DayOverrideMode.Replace -> override.entries.mergeSameMinute().sortedSchedule()
            DayOverrideMode.Add -> (base + override.entries).mergeSameMinute().sortedSchedule()
        }
    }

    /** The next bell strictly after [after], scanning ahead [horizonDays] days. */
    fun nextBell(after: LocalDateTime, horizonDays: Int = 14): UpcomingBell? {
        var date = after.toLocalDate()
        repeat(horizonDays) {
            for (entry in bellsFor(date)) {
                if (!entry.isValid) continue
                val at = date.atTime(entry.minutes / 60, entry.minutes % 60)
                if (at.isAfter(after)) return UpcomingBell(at, entry)
            }
            date = date.plusDays(1)
        }
        return null
    }
}

/**
 * Serializes schedules for the import/export files. The format is the same
 * JSON document the firmware accepts for POST /schedule.
 *
 * Encoding always writes version 3. Decoding accepts version 1 (a flat
 * "entries" array), version 2 ("exceptions" with mute/group_id) and version 3
 * ("overrides" with mute/replace/add).
 */
object ScheduleCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(schedule: BellSchedule): String =
        json.encodeToString(schedule.toDto())

    fun decode(text: String): BellSchedule =
        json.decodeFromString<ScheduleDto>(text).toDomain()
}

/**
 * Normalizes user input into a canonical base URL, or returns null when the
 * value cannot be used as a device address.
 */
fun normalizeBaseUrl(raw: String): String? {
    var value = raw.trim()
    if (value.isEmpty()) return null
    if (!value.startsWith("http://") && !value.startsWith("https://")) {
        value = "http://$value"
    }
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.encodedPath != "/" || url.query != null || url.fragment != null) return null
    return url.newBuilder().build().toString().trimEnd('/')
}

@Serializable
data class StatusDto(
    val time: String? = null,
    @SerialName("rtc_ok") val rtcOk: Boolean = false,
    val mode: String = "auto",
    val ringing: Boolean = false,
    @SerialName("ring_left") val ringLeft: Int = 0,
    @SerialName("ring_default") val ringDefault: Int = DEFAULT_RING_SECONDS,
    val entries: Int = 0,
    val errors: List<String> = emptyList(),
    val fw: String = "",
    /** API level of the firmware; absent on older builds, which speak level 2. */
    val api: Int = 2,
)

@Serializable
data class ScheduleDto(
    val version: Int = 2,
    val groups: List<ScheduleGroupDto> = emptyList(),
    /** Legacy v1 flat list, and a read-only view the firmware still returns. */
    val entries: List<ScheduleEntryDto>? = null,
    val overrides: List<DayOverrideDto> = emptyList(),
    /** Legacy v2 exceptions, read-only on decode. */
    val exceptions: List<DateExceptionDto>? = null,
    val auto: AutoWindowDto? = null,
)

@Serializable
data class ScheduleGroupDto(
    val id: Int = 0,
    val name: String = "",
    val days: List<Int> = emptyList(),
    val entries: List<ScheduleEntryDto> = emptyList(),
)

@Serializable
data class DayOverrideDto(
    val date: String,
    val mode: String,
    @SerialName("based_on") val basedOn: Int? = null,
    val entries: List<ScheduleEntryDto> = emptyList(),
)

@Serializable
data class DateExceptionDto(
    val date: String,
    val mute: Boolean = false,
    @SerialName("group_id") val groupId: Int? = null,
)

@Serializable
data class AutoWindowDto(
    val start: String? = null,
    val stop: String? = null,
)

@Serializable
data class ScheduleEntryDto(
    val time: String,
    val days: List<Int>? = null,
    val duration: Int = DEFAULT_RING_SECONDS,
    val label: String = "",
)

@Serializable
data class RingRequest(val sec: Int)

@Serializable
data class ModeRequest(val mode: String)

@Serializable
data class TimeRequest(val datetime: String)

@Serializable
data class SettingsRequest(@SerialName("ring_default") val ringDefault: Int)

@Serializable
data class SettingsDto(@SerialName("ring_default") val ringDefault: Int = DEFAULT_RING_SECONDS)

@Serializable
data class ApiResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val entries: Int? = null,
    val mode: String? = null,
    val time: String? = null,
    val ringing: Boolean? = null,
    @SerialName("ring_left") val ringLeft: Int? = null,
    @SerialName("ring_default") val ringDefault: Int? = null,
)

fun StatusDto.toDomain(): DeviceStatus = DeviceStatus(
    time = parseDeviceTime(time),
    rtcOk = rtcOk,
    mode = mode,
    ringing = ringing,
    ringLeft = ringLeft,
    ringDefault = ringDefault,
    entries = entries,
    errors = errors,
    fw = fw,
    api = api,
)

fun ScheduleDto.toDomain(): BellSchedule {
    // A legacy document only has a flat "entries" array. It becomes a single
    // group that is active every day; the per-entry weekdays still apply.
    val sourceGroups = when {
        groups.isNotEmpty() -> groups
        !entries.isNullOrEmpty() -> legacyGroups()
        else -> emptyList()
    }
    val usedIds = mutableSetOf<Int>()
    val idMap = mutableMapOf<Int, Int>()
    val domainGroups = sourceGroups.map { dto ->
        val groupId = normalizeGroupId(dto.id, usedIds)
        if (dto.id > 0 && dto.id !in idMap) idMap[dto.id] = groupId
        val groupDays = normalizeDays(dto.days)
        ScheduleGroup(
            id = groupId,
            name = dto.name.trim(),
            days = groupDays,
            entries = dto.entries
                .mapNotNull { it.toDomainEntry(groupDays) }
                .sortedSchedule(),
        )
    }

    val modern = overrides.mapNotNull { it.toDomain() }
    val legacy = exceptions.orEmpty().mapNotNull { it.toDomain(idMap, domainGroups) }
    val domainOverrides = (modern + legacy)
        .distinctBy { it.date }
        .sortedBy { it.date }

    return BellSchedule(
        groups = domainGroups,
        overrides = domainOverrides,
        auto = auto?.toDomain() ?: AutoWindow(),
    )
}

fun BellSchedule.toDto(): ScheduleDto = ScheduleDto(
    version = SCHEDULE_VERSION,
    groups = groups.map { group ->
        ScheduleGroupDto(
            id = group.id,
            name = group.name,
            days = group.days.sorted(),
            entries = group.entries.map { it.toDto(group.days.sorted()) },
        )
    },
    entries = null,
    overrides = overrides.sortedBy { it.date }.map { override ->
        DayOverrideDto(
            date = override.date.toString(),
            mode = override.mode.wireName(),
            basedOn = override.basedOnGroupId,
            entries = if (override.mode == DayOverrideMode.Mute) {
                emptyList()
            } else {
                override.entries.sortedSchedule().map { it.toDto(days = null) }
            },
        )
    },
    exceptions = null,
    auto = if (auto.isSet) AutoWindowDto(auto.start?.toString(), auto.stop?.toString()) else null,
)

private fun DayOverrideMode.wireName(): String = when (this) {
    DayOverrideMode.Mute -> "mute"
    DayOverrideMode.Replace -> "replace"
    DayOverrideMode.Add -> "add"
}

private fun ScheduleDto.legacyGroups(): List<ScheduleGroupDto> = listOf(
    ScheduleGroupDto(
        id = 1,
        name = "",
        days = ALL_DAYS.sorted(),
        entries = entries.orEmpty(),
    ),
)

private fun DayOverrideDto.toDomain(): DayOverride? {
    val parsedDate = parseIsoDate(date) ?: return null
    val parsedMode = when (mode.lowercase()) {
        "mute" -> DayOverrideMode.Mute
        "replace" -> DayOverrideMode.Replace
        "add" -> DayOverrideMode.Add
        else -> return null
    }
    val parsedEntries = if (parsedMode == DayOverrideMode.Mute) {
        emptyList()
    } else {
        entries.mapNotNull { it.toOverrideEntry() }.sortedSchedule()
    }
    return DayOverride(
        date = parsedDate,
        mode = parsedMode,
        entries = parsedEntries,
        basedOnGroupId = basedOn,
    )
}

/** Migrates a version 2 exception: mute stays mute, group_id becomes a copy. */
private fun DateExceptionDto.toDomain(
    idMap: Map<Int, Int>,
    groups: List<ScheduleGroup>,
): DayOverride? {
    val parsedDate = parseIsoDate(date) ?: return null
    if (mute) return DayOverride(date = parsedDate, mode = DayOverrideMode.Mute)
    val sourceId = groupId ?: return null
    val targetId = idMap[sourceId] ?: return null
    val group = groups.firstOrNull { it.id == targetId } ?: return null
    return DayOverride(
        date = parsedDate,
        mode = DayOverrideMode.Replace,
        entries = group.entries,
        basedOnGroupId = targetId,
    )
}

private fun normalizeGroupId(raw: Int, used: MutableSet<Int>): Int {
    var id = if (raw > 0 && raw !in used) raw else 1
    while (id in used) id++
    used.add(id)
    return id
}

private fun normalizeDays(raw: List<Int>): Set<Int> =
    raw.filter { it in 1..7 }.toSet().ifEmpty { ALL_DAYS }

/** Parses a bell inside a schedule: missing weekdays fall back to the schedule. */
private fun ScheduleEntryDto.toDomainEntry(fallbackDays: Set<Int>): BellEntry? {
    val parsedMinutes = parseTimeToMinutes(time) ?: return null
    val entryDays = when {
        days.isNullOrEmpty() -> fallbackDays
        else -> normalizeDays(days)
    }
    return BellEntry(
        time = minutesToTime(parsedMinutes),
        days = entryDays,
        duration = duration.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS),
        label = label,
    )
}

/** Parses a bell inside a date override: weekdays are irrelevant there. */
private fun ScheduleEntryDto.toOverrideEntry(): BellEntry? {
    val parsedMinutes = parseTimeToMinutes(time) ?: return null
    return BellEntry(
        time = minutesToTime(parsedMinutes),
        days = emptySet(),
        duration = duration.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS),
        label = label,
    )
}

internal fun parseTimeToMinutes(value: String): Int? {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

internal fun minutesToTime(minutes: Int): String {
    val safe = minutes.coerceIn(0, 24 * 60 - 1)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

private fun AutoWindowDto.toDomain(): AutoWindow = AutoWindow(
    start = parseIsoDate(start),
    stop = parseIsoDate(stop),
)

internal fun parseIsoDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun parseDeviceTime(value: String?): LocalDateTime? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDateTime.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}
