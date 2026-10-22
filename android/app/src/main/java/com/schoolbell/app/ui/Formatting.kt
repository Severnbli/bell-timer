package com.schoolbell.app.ui

import com.schoolbell.app.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val DEVICE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

fun LocalDateTime.deviceDisplay(): String = format(DEVICE_TIME_FORMATTER)

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
