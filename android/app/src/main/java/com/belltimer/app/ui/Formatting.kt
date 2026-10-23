package com.belltimer.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.School
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.belltimer.app.R
import com.belltimer.app.data.ALL_DAYS
import com.belltimer.app.data.BellRole
import com.belltimer.app.data.ModuleType
import com.belltimer.app.data.PackWindow
import com.belltimer.app.data.ScheduleModule
import com.belltimer.app.data.SchedulePack
import com.belltimer.app.data.WEEKDAYS
import com.belltimer.app.data.minutesToTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private val RU = Locale.forLanguageTag("ru")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val CLOCK_SECONDS = DateTimeFormatter.ofPattern("ss")
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", RU)
private val FULL_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)
private val MONTH_YEAR = DateTimeFormatter.ofPattern("LLLL yyyy", RU)
private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)

fun LocalDateTime.clock(): String = format(CLOCK)

fun LocalDateTime.clockSeconds(): String = format(CLOCK_SECONDS)

fun LocalDateTime.shortDateTime(): String = format(DATE_TIME)

fun LocalDate.shortDate(): String = format(SHORT_DATE)

fun LocalDate.fullDate(): String = format(FULL_DATE)

fun LocalDate.longDay(): String = format(LONG_DAY).replaceFirstChar { it.titlecase(RU) }

fun LocalDate.monthTitle(): String = format(MONTH_YEAR).replaceFirstChar { it.titlecase(RU) }

fun weekdayShort(day: Int): String =
    java.time.DayOfWeek.of(day).getDisplayName(TextStyle.SHORT_STANDALONE, RU)
        .replaceFirstChar { it.titlecase(RU) }

/** "Пн–Пт", "Каждый день", "Пн, Ср, Пт". */
@Composable
fun daysSummary(days: Set<Int>): String = when {
    days == ALL_DAYS -> stringResource(R.string.days_every_day)
    days == WEEKDAYS -> stringResource(R.string.days_weekdays)
    days == setOf(6, 7) -> stringResource(R.string.days_weekend)
    days.isEmpty() -> stringResource(R.string.days_none)
    else -> days.sorted().joinToString(", ") { weekdayShort(it) }
}

fun moduleIcon(type: ModuleType): ImageVector = when (type) {
    ModuleType.Lesson -> Icons.Outlined.School
    ModuleType.Bell -> Icons.Outlined.NotificationsActive
    ModuleType.Series -> Icons.Outlined.Repeat
}

@Composable
fun moduleTypeName(type: ModuleType): String = stringResource(
    when (type) {
        ModuleType.Lesson -> R.string.module_lesson
        ModuleType.Bell -> R.string.module_bell
        ModuleType.Series -> R.string.module_series
    },
)

/** "08:30 – 09:15" for a lesson, "09:00 – 17:00" for a series, "12:00" for a bell. */
fun ScheduleModule.timeRange(): String = when (type) {
    ModuleType.Bell -> minutesToTime(start)
    ModuleType.Lesson -> "${minutesToTime(start)} – ${minutesToTime(start + length)}"
    ModuleType.Series -> "${minutesToTime(start)} – ${minutesToTime(until)}"
}

/** Second line of a module card: what it does in words. */
@Composable
fun ScheduleModule.details(): String {
    val parts = mutableListOf<String>()
    when (type) {
        ModuleType.Lesson -> {
            parts += durationText(length)
            if (pre > 0) parts += stringResource(R.string.module_pre_short, pre)
        }
        ModuleType.Series -> {
            parts += stringResource(R.string.module_every_short, durationText(every))
            parts += pluralStringResource(R.plurals.bells_count, bells().size, bells().size)
        }
        ModuleType.Bell -> Unit
    }
    parts += daysSummary(days)
    return parts.joinToString(" · ")
}

@Composable
fun roleLabel(role: BellRole): String? = when (role) {
    BellRole.Start -> stringResource(R.string.role_start)
    BellRole.End -> stringResource(R.string.role_end)
    BellRole.Pre -> stringResource(R.string.role_pre)
    BellRole.Step, BellRole.Bell -> null
}

/** "45 мин", "1 ч 30 мин". */
@Composable
fun durationText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.duration_min, rest)
        rest == 0 -> stringResource(R.string.duration_h, hours)
        else -> stringResource(R.string.duration_h_min, hours, rest)
    }
}

/** "через 12 мин", "через 2 ч 5 мин", "сейчас". */
@Composable
fun untilText(minutes: Long): String = when {
    minutes <= 0 -> stringResource(R.string.until_now)
    minutes < 60 * 24 -> stringResource(R.string.until_in, durationText(minutes.toInt()))
    else -> stringResource(R.string.until_days, minutes / (60 * 24))
}

@Composable
fun driftText(seconds: Long): String {
    val total = abs(seconds)
    val minutes = total / 60
    val rest = total % 60
    return when {
        minutes >= 60 -> stringResource(R.string.drift_hours, minutes / 60, minutes % 60)
        minutes > 0 -> stringResource(R.string.drift_minutes, minutes, rest)
        else -> stringResource(R.string.drift_seconds, rest)
    }
}

/** One line describing when a pack runs. */
@Composable
fun windowSummary(window: PackWindow): String {
    if (!window.isSet) return stringResource(R.string.window_always)
    val parts = mutableListOf<String>()
    if (window.hasRange) {
        val from = window.from?.let { if (window.yearly) it.shortDate() else it.fullDate() }
        val to = window.to?.let { if (window.yearly) it.shortDate() else it.fullDate() }
        parts += when {
            from != null && to != null -> "$from – $to"
            from != null -> stringResource(R.string.window_from, from)
            else -> stringResource(R.string.window_to, to.orEmpty())
        }
    }
    if (window.dates.isNotEmpty()) {
        parts += pluralStringResource(R.plurals.dates_count, window.dates.size, window.dates.size)
    }
    if (window.yearly) parts += stringResource(R.string.window_yearly_short)
    return parts.joinToString(" · ")
}

@Composable
fun SchedulePack.displayName(): String =
    name.ifBlank { stringResource(R.string.pack_untitled) }

@Composable
fun deviceErrorLabel(code: String): String = when (code) {
    "rtc_not_found" -> stringResource(R.string.device_error_rtc_not_found)
    "time_not_set" -> stringResource(R.string.device_error_time_not_set)
    "storage_error" -> stringResource(R.string.device_error_storage)
    "wifi_ap_error" -> stringResource(R.string.device_error_wifi_ap)
    else -> stringResource(R.string.device_error_unknown, code)
}

@Composable
fun appVersionName(): String {
    val context = LocalContext.current
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
}
