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
const val MAX_DATE_EXCEPTIONS = 64

/** Firmware accepts 1..60 seconds for a single bell. */
const val MIN_RING_SECONDS = 1
const val MAX_RING_SECONDS = 60

/** Values offered as quick chips before the user has to type a custom one. */
const val QUICK_RING_SECONDS_MAX = 10

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
) {
    val isAuto: Boolean get() = mode == "auto"
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
            if (parts.size != 2) return Int.MAX_VALUE
            val hour = parts[0].toIntOrNull() ?: return Int.MAX_VALUE
            val minute = parts[1].toIntOrNull() ?: return Int.MAX_VALUE
            return hour * 60 + minute
        }

    val daysLabel: String get() = days.sorted().joinToString(",")

    fun toDto(): ScheduleEntryDto = ScheduleEntryDto(
        time = time,
        days = days.sorted(),
        duration = duration,
        label = label,
    )
}

fun List<BellEntry>.sortedSchedule(): List<BellEntry> =
    sortedWith(compareBy<BellEntry>({ it.minutes }, { it.label }))

/**
 * A named set of bells that repeats on the selected weekdays. Only the first
 * group whose weekdays match the current day is used in auto mode.
 */
data class ScheduleGroup(
    val id: Int,
    val name: String = "",
    val days: Set<Int> = ALL_DAYS,
    val entries: List<BellEntry> = emptyList(),
)

val ALL_DAYS: Set<Int> = (1..7).toSet()
val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

/** Per-date override: mute all bells or switch to another group. */
data class DateException(
    val date: LocalDate,
    val mute: Boolean = false,
    val groupId: Int? = null,
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

data class BellSchedule(
    val groups: List<ScheduleGroup> = emptyList(),
    val exceptions: List<DateException> = emptyList(),
    val auto: AutoWindow = AutoWindow(),
) {
    val totalEntries: Int get() = groups.sumOf { it.entries.size }

    val isEmpty: Boolean get() = groups.isEmpty() && exceptions.isEmpty()

    fun groupById(id: Int?): ScheduleGroup? =
        if (id == null) null else groups.firstOrNull { it.id == id }

    fun nextGroupId(): Int {
        val used = groups.map { it.id }.toSet()
        return (1..MAX_SCHEDULE_GROUPS).firstOrNull { it !in used }
            ?: (groups.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun exceptionFor(date: LocalDate): DateException? =
        exceptions.firstOrNull { it.date == date }
}

/**
 * Serializes schedules for the import/export files. The format is the same
 * JSON document the firmware accepts for POST /schedule.
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
)

@Serializable
data class ScheduleDto(
    val version: Int = 2,
    val entries: List<ScheduleEntryDto> = emptyList(),
    val groups: List<ScheduleGroupDto> = emptyList(),
    val exceptions: List<DateExceptionDto> = emptyList(),
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
    val days: List<Int> = emptyList(),
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
)

fun ScheduleDto.toDomain(): BellSchedule {
    // A legacy document only has a flat "entries" array. It becomes a single
    // group that is active every day; the per-entry weekdays still apply.
    val sourceGroups = if (groups.isNotEmpty() || entries.isEmpty()) {
        groups
    } else {
        legacyGroups()
    }
    val usedIds = mutableSetOf<Int>()
    val domainGroups = sourceGroups.map { dto ->
        val groupId = normalizeGroupId(dto.id, usedIds)
        ScheduleGroup(
            id = groupId,
            name = dto.name.trim(),
            days = normalizeDays(dto.days),
            entries = dto.entries.mapNotNull { it.toDomainEntry() }.sortedSchedule(),
        )
    }
    val validIds = domainGroups.map { it.id }.toSet()
    val domainExceptions = exceptions.mapNotNull { dto ->
        val date = parseIsoDate(dto.date) ?: return@mapNotNull null
        val groupId = dto.groupId?.takeIf { it in validIds }
        if (!dto.mute && groupId == null) return@mapNotNull null
        DateException(date = date, mute = dto.mute, groupId = groupId)
    }.distinctBy { it.date }.sortedBy { it.date }
    return BellSchedule(
        groups = domainGroups,
        exceptions = domainExceptions,
        auto = auto?.toDomain() ?: AutoWindow(),
    )
}

fun BellSchedule.toDto(): ScheduleDto = ScheduleDto(
    version = 2,
    groups = groups.map { group ->
        ScheduleGroupDto(
            id = group.id,
            name = group.name,
            days = group.days.sorted(),
            entries = group.entries.map { it.toDto() },
        )
    },
    exceptions = exceptions.map { exception ->
        DateExceptionDto(
            date = exception.date.toString(),
            mute = exception.mute,
            groupId = if (exception.mute) null else exception.groupId,
        )
    },
    auto = if (auto.isSet) AutoWindowDto(auto.start?.toString(), auto.stop?.toString()) else null,
)

private fun ScheduleDto.legacyGroups(): List<ScheduleGroupDto> = listOf(
    ScheduleGroupDto(
        id = 1,
        name = "",
        days = ALL_DAYS.sorted(),
        entries = entries,
    ),
)

private fun normalizeGroupId(raw: Int, used: MutableSet<Int>): Int {
    var id = if (raw > 0 && raw !in used) raw else 1
    while (id in used) id++
    used.add(id)
    return id
}

private fun normalizeDays(raw: List<Int>): Set<Int> =
    raw.filter { it in 1..7 }.toSet().ifEmpty { ALL_DAYS }

private fun ScheduleEntryDto.toDomainEntry(): BellEntry? {
    val normalized = time.trim()
    val parts = normalized.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return BellEntry(
        time = "%02d:%02d".format(hour, minute),
        days = normalizeDays(days),
        duration = duration.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS),
        label = label,
    )
}

private fun AutoWindowDto.toDomain(): AutoWindow = AutoWindow(
    start = parseIsoDate(start),
    stop = parseIsoDate(stop),
)

private fun parseIsoDate(value: String?): LocalDate? {
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
