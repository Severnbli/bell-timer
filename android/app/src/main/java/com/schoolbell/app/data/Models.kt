package com.schoolbell.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

const val DEFAULT_BASE_URL = "http://192.168.4.1"
const val DEFAULT_RING_SECONDS = 5
const val MAX_SCHEDULE_ENTRIES = 64

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
    val entries: List<ScheduleEntryDto> = emptyList(),
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

fun ScheduleDto.toDomain(): List<BellEntry> = entries.mapNotNull { dto ->
    val normalized = dto.time.trim()
    val parts = normalized.split(':')
    if (parts.size != 2) return@mapNotNull null
    val hour = parts[0].toIntOrNull() ?: return@mapNotNull null
    val minute = parts[1].toIntOrNull() ?: return@mapNotNull null
    if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
    val days = dto.days.filter { it in 1..7 }.toSet().ifEmpty { (1..7).toSet() }
    BellEntry(
        time = "%02d:%02d".format(hour, minute),
        days = days,
        duration = dto.duration.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS),
        label = dto.label,
    )
}.sortedSchedule()

private fun parseDeviceTime(value: String?): LocalDateTime? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDateTime.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}
