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
const val MAX_PACKS = 6
const val MAX_PACK_ENTRIES = 32
const val MAX_PACK_DATES = 24
const val MAX_SCHEDULE_ENTRIES = MAX_PACKS * MAX_PACK_ENTRIES
const val MAX_LABEL_BYTES = 32
const val MAX_PACK_NAME_BYTES = 32

/** Firmware accepts 1..60 seconds for a single bell. */
const val MIN_RING_SECONDS = 1
const val MAX_RING_SECONDS = 60

/** Largest value the compact slider offers before switching to the custom field. */
const val SLIDER_MAX_SECONDS = 10

/** Schedule document version written by this app. */
const val SCHEDULE_VERSION = 4

/** Device API level that understands schedule version 4 (packs). */
const val API_PACKS = 4

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
    val supportsPacks: Boolean get() = api >= API_PACKS
}

data class BellEntry(
    val time: String,
    val pair: String? = null,
    val days: Set<Int> = ALL_DAYS,
    val label: String = "",
) {
    val minutes: Int get() = parseTimeToMinutes(time) ?: INVALID_MINUTE

    val pairMinutes: Int? get() = pair?.let { parseTimeToMinutes(it) }

    val isValid: Boolean get() = minutes != INVALID_MINUTE && (pair == null || pairMinutes != null)

    /** A paired bell expands into one more bell with the same days and label. */
    fun resolved(): List<BellEntry> {
        val second = pair ?: return listOf(this)
        return listOf(copy(pair = null), copy(time = second, pair = null))
    }

    companion object {
        const val INVALID_MINUTE = Int.MAX_VALUE
    }
}

fun List<BellEntry>.sortedSchedule(): List<BellEntry> =
    sortedWith(compareBy<BellEntry>({ it.minutes }, { it.label }))

/**
 * Collapses bells that share a minute. The first bell wins; a later bell only
 * contributes a label when the first one has none. The firmware applies the
 * same rule.
 */
fun List<BellEntry>.mergeSameMinute(): List<BellEntry> {
    val merged = LinkedHashMap<Int, BellEntry>()
    for (entry in this) {
        val existing = merged[entry.minutes]
        merged[entry.minutes] = when {
            existing == null -> entry
            existing.label.isBlank() && entry.label.isNotBlank() -> entry
            else -> existing
        }
    }
    return merged.values.toList()
}

val ALL_DAYS: Set<Int> = (1..7).toSet()
val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

enum class PackKind {
    Base,
    Correction;

    val wireName: String get() = if (this == Base) "base" else "correction"

    companion object {
        fun fromWire(value: String?): PackKind =
            if (value.equals("correction", ignoreCase = true)) Correction else Base
    }
}

/**
 * When a pack is allowed to run. [from] and [to] form a date range, [dates]
 * lists individual dates, and [yearly] compares month and day instead of the
 * full date (so a range may wrap over New Year).
 */
data class PackWindow(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val dates: Set<LocalDate> = emptySet(),
    val yearly: Boolean = false,
) {
    val hasRange: Boolean get() = from != null || to != null

    val isSet: Boolean get() = hasRange || dates.isNotEmpty()

    val isValid: Boolean
        get() = yearly || from == null || to == null || !to.isBefore(from)

    fun allows(date: LocalDate): Boolean {
        if (!isSet) return true
        if (hasRange && inRange(date)) return true
        return dates.any { matches(it, date) }
    }

    private fun inRange(date: LocalDate): Boolean {
        if (!yearly) {
            if (from != null && date.isBefore(from)) return false
            if (to != null && date.isAfter(to)) return false
            return true
        }
        val value = monthDay(date)
        val start = from?.let(::monthDay)
        val end = to?.let(::monthDay)
        return when {
            start != null && end != null && start > end -> value >= start || value <= end
            start != null && end != null -> value in start..end
            start != null -> value >= start
            end != null -> value <= end
            else -> false
        }
    }

    private fun matches(expected: LocalDate, date: LocalDate): Boolean =
        if (yearly) monthDay(expected) == monthDay(date) else expected == date

    private fun monthDay(date: LocalDate): Int = date.monthValue * 100 + date.dayOfMonth
}

/**
 * One pack ("пачка") of ranked bell entries. Base packs add bells; a
 * correction cancels the bells of every base pack above it and adds its own.
 */
data class SchedulePack(
    val id: Int,
    val name: String = "",
    val kind: PackKind = PackKind.Base,
    val enabled: Boolean = true,
    val window: PackWindow = PackWindow(),
    val entries: List<BellEntry> = emptyList(),
    /** Legacy migration only: a correction that just cancels the packs above it. */
    val mute: Boolean = false,
) {
    fun entriesFor(date: LocalDate): List<BellEntry> {
        val weekday = date.dayOfWeek.value
        return entries.filter { weekday in it.days }.flatMap { it.resolved() }
    }

    fun hasEntriesFor(date: LocalDate): Boolean =
        entries.any { date.dayOfWeek.value in it.days }

    /** Whether the pack takes part in today's resolution. */
    fun appliesOn(date: LocalDate): Boolean =
        enabled && window.allows(date) && (mute || hasEntriesFor(date))

    fun withEntries(newEntries: List<BellEntry>): SchedulePack =
        copy(
            entries = newEntries.sortedSchedule(),
            // A migrated mute correction becomes a normal correction as soon
            // as it gets bells of its own.
            mute = if (newEntries.isEmpty()) mute else false,
        )

    fun withEntry(index: Int, entry: BellEntry): SchedulePack {
        if (index !in entries.indices) return this
        val updated = entries.toMutableList()
        updated[index] = entry
        return withEntries(updated)
    }

    fun withoutEntry(index: Int): SchedulePack {
        if (index !in entries.indices) return this
        val updated = entries.toMutableList()
        updated.removeAt(index)
        return withEntries(updated)
    }

    val weekdayUnion: Set<Int> get() = entries.flatMap { it.days }.toSet()

    val firstMinute: Int?
        get() = entries.flatMap { entry ->
            listOfNotNull(
                entry.minutes.takeIf { it != BellEntry.INVALID_MINUTE },
                entry.pairMinutes?.takeIf { it != BellEntry.INVALID_MINUTE },
            )
        }.minOrNull()

    val lastMinute: Int?
        get() = entries.flatMap { entry ->
            listOfNotNull(
                entry.minutes.takeIf { it != BellEntry.INVALID_MINUTE },
                entry.pairMinutes?.takeIf { it != BellEntry.INVALID_MINUTE },
            )
        }.maxOrNull()
}

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

data class UpcomingBell(val at: LocalDateTime, val entry: BellEntry)

data class BellSchedule(
    val packs: List<SchedulePack> = emptyList(),
    val auto: AutoWindow = AutoWindow(),
) {
    val totalEntries: Int get() = packs.sumOf { it.entries.size }

    val isEmpty: Boolean get() = packs.isEmpty()

    fun packById(id: Int?): SchedulePack? =
        if (id == null) null else packs.firstOrNull { it.id == id }

    fun nextPackId(): Int {
        val used = packs.map { it.id }.toSet()
        return (1..MAX_PACKS).firstOrNull { it !in used }
            ?: (packs.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun withPack(pack: SchedulePack): BellSchedule {
        val exists = packs.any { it.id == pack.id }
        val updated = if (exists) {
            packs.map { if (it.id == pack.id) pack else it }
        } else {
            packs + pack
        }
        return copy(packs = updated)
    }

    fun withoutPack(id: Int): BellSchedule = copy(packs = packs.filterNot { it.id == id })

    /** Moves a pack by [delta] positions; the order is meaningful for corrections. */
    fun movePack(id: Int, delta: Int): BellSchedule {
        val index = packs.indexOfFirst { it.id == id }
        if (index < 0 || delta == 0) return this
        val target = (index + delta).coerceIn(packs.indices)
        if (target == index) return this
        val updated = packs.toMutableList()
        val pack = updated.removeAt(index)
        updated.add(target, pack)
        return copy(packs = updated)
    }

    /**
     * The single source of truth for what rings on a date. The firmware applies
     * exactly the same rules (see docs/firmware-api-v4.md).
     *
     * Packs are evaluated top to bottom. A base pack contributes its bells; a
     * correction that has bells for this weekday first cancels every base bell
     * collected above it and then contributes its own.
     */
    fun bellsFor(date: LocalDate): List<BellEntry> {
        if (!auto.allows(date)) return emptyList()
        val base = mutableListOf<BellEntry>()
        val corrections = mutableListOf<BellEntry>()
        for (pack in packs) {
            if (!pack.enabled || !pack.window.allows(date)) continue
            val today = pack.entriesFor(date)
            when (pack.kind) {
                PackKind.Base -> base += today
                PackKind.Correction -> {
                    if (pack.mute) {
                        base.clear()
                    } else if (today.isNotEmpty()) {
                        base.clear()
                        corrections += today
                    }
                }
            }
        }
        return (base + corrections).mergeSameMinute().sortedSchedule()
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
 * Encoding always writes version 4 ("packs"). Decoding accepts version 1 (a
 * flat "entries" array), version 2 ("exceptions"), version 3 ("groups" plus
 * "overrides") and version 4.
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
    val version: Int = SCHEDULE_VERSION,
    /** Version 4 pack list. When null, the legacy fields below are migrated. */
    val packs: List<SchedulePackDto>? = null,
    val auto: AutoWindowDto? = null,
    /** Legacy v3 groups, read-only on decode. */
    val groups: List<ScheduleGroupDto>? = null,
    /** Legacy v1 flat list, read-only on decode. */
    val entries: List<ScheduleEntryDto>? = null,
    /** Legacy v3 overrides, read-only on decode. */
    val overrides: List<DayOverrideDto>? = null,
    /** Legacy v2 exceptions, read-only on decode. */
    val exceptions: List<DateExceptionDto>? = null,
)

@Serializable
data class SchedulePackDto(
    val id: Int = 0,
    val name: String = "",
    val kind: String = "base",
    val enabled: Boolean = true,
    val from: String? = null,
    val to: String? = null,
    val dates: List<String> = emptyList(),
    val yearly: Boolean = false,
    /** Legacy migration only; not written for normal packs. */
    val mute: Boolean = false,
    val entries: List<ScheduleEntryDto> = emptyList(),
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
    val pair: String? = null,
    val days: List<Int>? = null,
    /** Read-only legacy field: the ring duration now lives in the settings. */
    val duration: Int? = null,
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
    val domainPacks = packs?.let { decodePacks(it) } ?: decodeLegacyPacks()
    return BellSchedule(
        packs = domainPacks,
        auto = auto?.toDomain() ?: AutoWindow(),
    )
}

private fun decodePacks(source: List<SchedulePackDto>): List<SchedulePack> {
    val used = mutableSetOf<Int>()
    return source.map { dto ->
        val kind = PackKind.fromWire(dto.kind)
        SchedulePack(
            id = normalizePackId(dto.id, used),
            name = dto.name.trim(),
            kind = kind,
            enabled = dto.enabled,
            window = PackWindow(
                from = parseIsoDate(dto.from),
                to = parseIsoDate(dto.to),
                dates = dto.dates.mapNotNull(::parseIsoDate).toSet(),
                yearly = dto.yearly,
            ),
            entries = dto.entries.mapNotNull { it.toDomainEntry(ALL_DAYS) }.sortedSchedule(),
            mute = kind == PackKind.Correction && dto.mute,
        )
    }
}

/**
 * Turns v1/v2/v3 documents into packs. Groups become base packs; "add"
 * overrides become date-limited base packs; "mute"/"replace" overrides become
 * corrections placed after every base pack so they cancel all of them.
 */
private fun ScheduleDto.decodeLegacyPacks(): List<SchedulePack> {
    val base = mutableListOf<SchedulePack>()
    val added = mutableListOf<SchedulePack>()
    val corrections = mutableListOf<SchedulePack>()
    val used = mutableSetOf<Int>()
    var nextId = 1

    fun freshId(): Int {
        while (nextId in used) nextId++
        used += nextId
        return nextId
    }

    val sourceGroups = when {
        !groups.isNullOrEmpty() -> groups.orEmpty()
        !entries.isNullOrEmpty() -> listOf(
            ScheduleGroupDto(
                id = 1,
                name = "",
                days = ALL_DAYS.sorted(),
                entries = entries.orEmpty(),
            ),
        )
        else -> emptyList()
    }
    val idMap = mutableMapOf<Int, Int>()
    for (dto in sourceGroups) {
        val id = freshId()
        if (dto.id > 0 && dto.id !in idMap) idMap[dto.id] = id
        val groupDays = normalizeDays(dto.days)
        base += SchedulePack(
            id = id,
            name = dto.name.trim(),
            kind = PackKind.Base,
            entries = dto.entries.mapNotNull { it.toDomainEntry(groupDays) }.sortedSchedule(),
        )
    }

    for (dto in overrides.orEmpty()) {
        val date = parseIsoDate(dto.date) ?: continue
        val overrideEntries = dto.entries
            .mapNotNull { it.toDomainEntry(ALL_DAYS) }
            // Date overrides ignore weekdays in v1-v3.
            .map { it.copy(days = ALL_DAYS) }
            .sortedSchedule()
        when (dto.mode.lowercase()) {
            "mute" -> corrections += SchedulePack(
                id = freshId(),
                kind = PackKind.Correction,
                window = PackWindow(dates = setOf(date)),
                mute = true,
            )
            "replace" -> corrections += SchedulePack(
                id = freshId(),
                kind = PackKind.Correction,
                window = PackWindow(dates = setOf(date)),
                entries = overrideEntries,
                // A v3 replace with an empty list means "no bells".
                mute = overrideEntries.isEmpty(),
            )
            "add" -> added += SchedulePack(
                id = freshId(),
                kind = PackKind.Base,
                window = PackWindow(dates = setOf(date)),
                entries = overrideEntries,
            )
        }
    }

    for (dto in exceptions.orEmpty()) {
        val date = parseIsoDate(dto.date) ?: continue
        if (dto.mute) {
            corrections += SchedulePack(
                id = freshId(),
                kind = PackKind.Correction,
                window = PackWindow(dates = setOf(date)),
                mute = true,
            )
        } else {
            val targetId = dto.groupId?.let { idMap[it] } ?: continue
            val group = base.firstOrNull { it.id == targetId } ?: continue
            // A date exception ignores weekdays in v2.
            val copied = group.entries.map { it.copy(days = ALL_DAYS) }
            corrections += SchedulePack(
                id = freshId(),
                kind = PackKind.Correction,
                window = PackWindow(dates = setOf(date)),
                entries = copied,
                // Copying an empty group means "no bells".
                mute = copied.isEmpty(),
            )
        }
    }

    return base + added + corrections
}

fun BellSchedule.toDto(): ScheduleDto = ScheduleDto(
    version = SCHEDULE_VERSION,
    packs = packs.map { pack ->
        SchedulePackDto(
            id = pack.id,
            name = pack.name,
            kind = pack.kind.wireName,
            enabled = pack.enabled,
            from = pack.window.from?.toString(),
            to = pack.window.to?.toString(),
            dates = pack.window.dates.sorted().map { it.toString() },
            yearly = pack.window.yearly,
            mute = pack.mute,
            entries = pack.entries.sortedSchedule().map { it.toDto() },
        )
    },
    auto = if (auto.isSet) AutoWindowDto(auto.start?.toString(), auto.stop?.toString()) else null,
)

private fun BellEntry.toDto(): ScheduleEntryDto = ScheduleEntryDto(
    time = time,
    pair = pair,
    days = days.sorted(),
    label = label,
)

private fun normalizePackId(raw: Int, used: MutableSet<Int>): Int {
    var id = if (raw > 0 && raw !in used) raw else 1
    while (id in used) id++
    used.add(id)
    return id
}

private fun normalizeDays(raw: List<Int>): Set<Int> =
    raw.filter { it in 1..7 }.toSet().ifEmpty { ALL_DAYS }

/** Parses a bell: missing weekdays fall back to the pack's defaults. */
private fun ScheduleEntryDto.toDomainEntry(fallbackDays: Set<Int>): BellEntry? {
    val parsedMinutes = parseTimeToMinutes(time) ?: return null
    val parsedPair = if (pair.isNullOrBlank()) {
        null
    } else {
        parseTimeToMinutes(pair) ?: return null
    }
    val entryDays = when {
        days.isNullOrEmpty() -> fallbackDays
        else -> normalizeDays(days) intersect fallbackDays
    }
    return BellEntry(
        time = minutesToTime(parsedMinutes),
        pair = parsedPair?.let { minutesToTime(it) },
        days = entryDays,
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
