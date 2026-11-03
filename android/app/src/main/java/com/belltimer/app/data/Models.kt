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

/** Firmware accepts 1..60 seconds for a single bell. */
const val MIN_RING_SECONDS = 1
const val MAX_RING_SECONDS = 60

/** Schedule document version written by this app. */
const val SCHEDULE_VERSION = 6

/** Device API level that understands schedule version 5 (modules). */
const val API_MODULES = 5

/**
 * Device API level with firmware updates over Wi-Fi, the wiring settings, the
 * local-only access point and several date ranges per pack.
 */
const val API_UPDATES = 6

/**
 * Oldest firmware this app can drive. The release workflow copies it into
 * app.json, so an app update is not suggested to a phone whose device runs
 * older firmware. Raise it whenever the app stops supporting a firmware.
 */
const val MIN_FIRMWARE_VERSION = "2.0.0"

data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = "",
    val ringDefault: Int = DEFAULT_RING_SECONDS,
    /** Set the device clock from the phone when it is unset or drifts. */
    val autoSyncTime: Boolean = true,
)

/** The next bell as the device sees it. */
data class DeviceNextBell(
    val at: LocalDateTime,
    val label: String,
    val type: ModuleType,
    val role: BellRole,
)

data class DeviceStatus(
    val time: LocalDateTime?,
    val rtcOk: Boolean,
    val mode: String,
    val ringing: Boolean,
    val ringLeft: Int,
    val ringDefault: Int,
    val modules: Int,
    val todayBells: Int?,
    val next: DeviceNextBell?,
    val lastSync: LocalDateTime?,
    val errors: List<String>,
    val fw: String,
    val api: Int,
    /** Access point name; null on firmware before the network settings. */
    val ssid: String? = null,
    /** Oldest app the firmware supports; null on firmware before API 6. */
    val minApp: String? = null,
) {
    val isAuto: Boolean get() = mode == "auto"
    val supportsModules: Boolean get() = api >= API_MODULES
    val supportsUpdates: Boolean get() = api >= API_UPDATES
}

/**
 * Serializes schedules for the import/export files. The format is the same
 * JSON document the firmware accepts for POST /schedule.
 *
 * Encoding always writes version 5 ("modules"). Decoding also accepts version
 * 4 packs, whose "entries" become bells, or lessons when they had a pair.
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

// ------------------------------- Wire format -------------------------------

@Serializable
data class StatusDto(
    val time: String? = null,
    @SerialName("rtc_ok") val rtcOk: Boolean = false,
    val mode: String = "auto",
    val ringing: Boolean = false,
    @SerialName("ring_left") val ringLeft: Int = 0,
    @SerialName("ring_default") val ringDefault: Int = DEFAULT_RING_SECONDS,
    val modules: Int = 0,
    /** Firmware 1.x reported entries instead of modules. */
    val entries: Int = 0,
    @SerialName("today_bells") val todayBells: Int? = null,
    val next: NextBellDto? = null,
    val synced: String? = null,
    val errors: List<String> = emptyList(),
    val fw: String = "",
    val ssid: String? = null,
    /** API level of the firmware; absent on the oldest builds, which speak level 2. */
    val api: Int = 2,
    @SerialName("min_app") val minApp: String? = null,
)

@Serializable
data class NextBellDto(
    val date: String,
    val time: String,
    val label: String = "",
    val type: String = "bell",
    val role: String = "bell",
)

@Serializable
data class ScheduleDto(
    val version: Int = SCHEDULE_VERSION,
    val packs: List<SchedulePackDto> = emptyList(),
    val auto: AutoWindowDto? = null,
)

@Serializable
data class SchedulePackDto(
    val id: Int = 0,
    val name: String = "",
    val kind: String = "base",
    val enabled: Boolean = true,
    val mute: Boolean = false,
    /** Version 5: the single range. Version 6 writes it when there is one range. */
    val from: String? = null,
    val to: String? = null,
    /** Version 6: several ranges. */
    val ranges: List<DateRangeDto>? = null,
    val dates: List<String> = emptyList(),
    val yearly: Boolean = false,
    val modules: List<ModuleDto>? = null,
    /** Version 4 entries, read-only on decode. */
    val entries: List<LegacyEntryDto>? = null,
)

@Serializable
data class DateRangeDto(
    val from: String? = null,
    val to: String? = null,
)

@Serializable
data class ModuleDto(
    val type: String = "bell",
    val time: String,
    val length: Int? = null,
    val pre: Int? = null,
    val until: String? = null,
    val every: Int? = null,
    val days: List<Int>? = null,
    val label: String = "",
)

@Serializable
data class LegacyEntryDto(
    val time: String,
    val pair: String? = null,
    val days: List<Int>? = null,
    val label: String = "",
)

@Serializable
data class AutoWindowDto(
    val start: String? = null,
    val stop: String? = null,
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
data class NetworkDto(
    val ssid: String = "",
    val channel: Int = 0,
    val open: Boolean = false,
    @SerialName("token_set") val tokenSet: Boolean = false,
    /** Null on firmware before API 6, which always announces a gateway. */
    @SerialName("local_only") val localOnly: Boolean? = null,
    /** A local-only switch waits for a client to come back before it is stored. */
    @SerialName("local_only_trial") val localOnlyTrial: Boolean = false,
)

/** Only the fields that are not null are changed on the device. */
@Serializable
data class NetworkUpdateDto(
    val ssid: String? = null,
    val password: String? = null,
    val token: String? = null,
    @SerialName("local_only") val localOnly: Boolean? = null,
)

/** GPIO wiring stored on the device (API 6). */
@Serializable
data class DeviceConfigDto(
    val relay: Int = 26,
    @SerialName("led_green") val ledGreen: Int = 27,
    @SerialName("led_red") val ledRed: Int = 25,
    val sda: Int = 21,
    val scl: Int = 22,
    @SerialName("led_active_low") val ledActiveLow: Boolean = false,
    /** Read-only: the stored wiring could not be used, defaults are active. */
    val error: Boolean = false,
) {
    val pins: List<Int> get() = listOf(relay, ledGreen, ledRed, sda, scl)

    /** Mirrors validDeviceConfig() in the firmware. */
    val isValid: Boolean get() = pins.all { it in OUTPUT_PINS } && pins.toSet().size == pins.size

    companion object {
        /** GPIOs that can drive an output and are not flash, UART or input-only. */
        val OUTPUT_PINS: Set<Int> =
            setOf(0, 2, 4, 5, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33)
    }
}

@Serializable
data class ResetRequest(val network: Boolean)

@Serializable
data class SettingsDto(@SerialName("ring_default") val ringDefault: Int = DEFAULT_RING_SECONDS)

@Serializable
data class ApiResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val modules: Int? = null,
    val mode: String? = null,
    val time: String? = null,
    val ringing: Boolean? = null,
    @SerialName("ring_left") val ringLeft: Int? = null,
    @SerialName("ring_default") val ringDefault: Int? = null,
    /** POST /network and /reset: the access point restarts shortly. */
    val restart: Boolean? = null,
    val ssid: String? = null,
    @SerialName("local_only") val localOnly: Boolean? = null,
    @SerialName("local_only_trial") val localOnlyTrial: Boolean? = null,
)

// ------------------------------- Mapping -----------------------------------

fun StatusDto.toDomain(): DeviceStatus = DeviceStatus(
    time = parseDeviceTime(time),
    rtcOk = rtcOk,
    mode = mode,
    ringing = ringing,
    ringLeft = ringLeft,
    ringDefault = ringDefault,
    modules = if (api >= API_MODULES) modules else entries,
    todayBells = todayBells,
    next = next?.toDomain(),
    lastSync = parseDeviceTime(synced),
    errors = errors,
    fw = fw,
    api = api,
    ssid = ssid,
    minApp = minApp,
)

private fun NextBellDto.toDomain(): DeviceNextBell? {
    val date = parseIsoDate(date) ?: return null
    val minute = parseTimeToMinutes(time) ?: return null
    return DeviceNextBell(
        at = date.atTime(minute / 60, minute % 60),
        label = label,
        type = ModuleType.fromWire(type) ?: ModuleType.Bell,
        role = BellRole.fromWire(role),
    )
}

fun ScheduleDto.toDomain(): BellSchedule {
    val used = mutableSetOf<Int>()
    return BellSchedule(
        packs = packs.map { it.toDomain(used) },
        auto = auto?.toDomain() ?: AutoWindow(),
    )
}

private fun SchedulePackDto.toDomain(used: MutableSet<Int>): SchedulePack {
    val kind = PackKind.fromWire(kind)
    val decoded = modules?.mapNotNull { it.toDomain() }
        ?: entries.orEmpty().mapNotNull { it.toDomain() }
    return SchedulePack(
        id = normalizePackId(id, used),
        name = name.trim(),
        kind = kind,
        enabled = enabled,
        mute = kind == PackKind.Correction && mute,
        window = PackWindow(
            ranges = decodeRanges(),
            dates = dates.mapNotNull(::parseIsoDate).toSet(),
            yearly = yearly,
        ),
        modules = decoded.sortedModules(),
    )
}

/** "ranges" wins over the version 5 "from"/"to"; empty ranges are dropped. */
private fun SchedulePackDto.decodeRanges(): List<DateRange> {
    val raw = ranges ?: listOf(DateRangeDto(from, to))
    return raw.map { DateRange(parseIsoDate(it.from), parseIsoDate(it.to)) }.filter { it.isSet }
}

private fun ModuleDto.toDomain(): ScheduleModule? {
    val moduleType = ModuleType.fromWire(type) ?: return null
    val start = parseTimeToMinutes(time) ?: return null
    val defaults = ScheduleModule()
    val module = ScheduleModule(
        type = moduleType,
        start = start,
        length = length ?: defaults.length,
        pre = pre ?: 0,
        until = until?.let(::parseTimeToMinutes) ?: (start + 60).coerceAtMost(LAST_MINUTE),
        every = every ?: defaults.every,
        days = normalizeDays(days),
        label = label,
    )
    return module.takeIf { it.isValid }
}

/** A v4 entry with a pair becomes a lesson spanning both times. */
private fun LegacyEntryDto.toDomain(): ScheduleModule? {
    val start = parseTimeToMinutes(time) ?: return null
    val second = pair?.takeIf { it.isNotBlank() }?.let { parseTimeToMinutes(it) ?: return null }
    val days = normalizeDays(days)
    if (second == null || second == start || kotlin.math.abs(second - start) > MAX_LESSON_MINUTES) {
        return ScheduleModule(type = ModuleType.Bell, start = start, days = days, label = label)
    }
    return ScheduleModule(
        type = ModuleType.Lesson,
        start = minOf(start, second),
        length = kotlin.math.abs(second - start),
        days = days,
        label = label,
    )
}

fun BellSchedule.toDto(): ScheduleDto = ScheduleDto(
    version = SCHEDULE_VERSION,
    packs = packs.map { pack ->
        SchedulePackDto(
            id = pack.id,
            name = pack.name,
            kind = pack.kind.wireName,
            enabled = pack.enabled,
            mute = pack.kind == PackKind.Correction && pack.mute,
            // One range keeps the version 5 shape, readable by any firmware.
            from = pack.window.ranges.singleOrNull()?.from?.toString(),
            to = pack.window.ranges.singleOrNull()?.to?.toString(),
            ranges = pack.window.ranges
                .takeIf { it.size > 1 }
                ?.map { DateRangeDto(it.from?.toString(), it.to?.toString()) },
            dates = pack.window.dates.sorted().map { it.toString() },
            yearly = pack.window.yearly,
            modules = pack.modules.sortedModules().map { it.toDto() },
        )
    },
    auto = if (auto.isSet) AutoWindowDto(auto.start?.toString(), auto.stop?.toString()) else null,
)

private fun ScheduleModule.toDto(): ModuleDto = ModuleDto(
    type = type.wireName,
    time = minutesToTime(start),
    length = length.takeIf { type == ModuleType.Lesson },
    pre = pre.takeIf { type == ModuleType.Lesson && it > 0 },
    until = minutesToTime(until).takeIf { type == ModuleType.Series },
    every = every.takeIf { type == ModuleType.Series },
    days = days.sorted(),
    label = label,
)

private fun normalizePackId(raw: Int, used: MutableSet<Int>): Int {
    var id = if (raw in 1..255 && raw !in used) raw else 1
    while (id in used) id++
    used.add(id)
    return id
}

private fun normalizeDays(raw: List<Int>?): Set<Int> =
    raw.orEmpty().filter { it in 1..7 }.toSet().ifEmpty { ALL_DAYS }

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
