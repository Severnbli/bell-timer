package com.belltimer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.belltimer.app.R
import com.belltimer.app.data.BellSchedule
import com.belltimer.app.data.PackKind
import java.time.LocalDate
import java.time.YearMonth

/** Green for days that carry an upcoming base pack. */
private val BaseDayColor = Color(0xFF2E7D32)

/**
 * Month calendar for the schedule sheet. Upcoming base-pack days are green,
 * correction days use the correction colour and past days stay unlit.
 */
@Composable
fun ScheduleCalendar(
    schedule: BellSchedule,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayedMonthKey by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val displayedMonth = YearMonth.parse(displayedMonthKey)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { displayedMonthKey = displayedMonth.minusMonths(1).toString() },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.calendar_prev),
                    )
                }
                Text(
                    text = displayedMonth.atDay(1).monthTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { displayedMonthKey = displayedMonth.plusMonths(1).toString() },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.calendar_next),
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                for (day in 1..7) {
                    Text(
                        text = weekdayName(day),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val first = displayedMonth.atDay(1)
            val leadingBlanks = first.dayOfWeek.value - 1
            val daysInMonth = displayedMonth.lengthOfMonth()
            val today = LocalDate.now()
            val rows = (leadingBlanks + daysInMonth + 6) / 7

            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val dayNumber = row * 7 + col - leadingBlanks + 1
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (dayNumber in 1..daysInMonth) {
                                val date = displayedMonth.atDay(dayNumber)
                                DayCell(
                                    date = date,
                                    dayNumber = dayNumber,
                                    highlight = dayHighlightColor(schedule, date, today),
                                    onClick = { onDayClick(date) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    dayNumber: Int,
    highlight: Color?,
    onClick: () -> Unit,
) {
    val today = LocalDate.now()
    val isPast = date.isBefore(today)
    val isToday = date == today
    val textColor = when {
        highlight != null -> Color.White
        isPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .background(
                color = highlight ?: if (isToday) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = dayNumber.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}

@Composable
private fun dayHighlightColor(
    schedule: BellSchedule,
    date: LocalDate,
    today: LocalDate,
): Color? {
    if (date.isBefore(today)) return null
    val correction = schedule.packs.any {
        it.kind == PackKind.Correction && it.appliesOn(date)
    }
    if (correction) return MaterialTheme.colorScheme.error
    val base = schedule.packs.any {
        it.kind == PackKind.Base && it.appliesOn(date)
    }
    if (base) return BaseDayColor
    return null
}
