package com.belltimer.app.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.belltimer.app.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DEVICE_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
private val DEVICE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
private val SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM")
private val FULL_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val MONTH_YEAR = DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))

/** Times and countdowns use tabular digits so they do not jitter. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

val TabularLarge = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
)

fun LocalDateTime.deviceDisplay(): String = format(DEVICE_DATE_TIME)

fun LocalDateTime.deviceClock(): String = format(DEVICE_TIME)

fun LocalDate.shortDate(): String = format(SHORT_DATE)

fun LocalDate.fullDate(): String = format(FULL_DATE)

fun LocalDate.monthTitle(): String = format(MONTH_YEAR)

fun dayLabelRes(day: Int): Int = when (day) {
    1 -> R.string.days_mon
    2 -> R.string.days_tue
    3 -> R.string.days_wed
    4 -> R.string.days_thu
    5 -> R.string.days_fri
    6 -> R.string.days_sat
    else -> R.string.days_sun
}

fun timeToMinutes(time: String): Int? {
    val parts = time.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

fun minutesToTime(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)
