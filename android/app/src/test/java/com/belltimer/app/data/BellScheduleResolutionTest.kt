package com.belltimer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BellScheduleResolutionTest {

    private val monday = LocalDate.of(2026, 10, 19)
    private val tuesday = LocalDate.of(2026, 10, 20)
    private val saturday = LocalDate.of(2026, 10, 24)

    private fun bell(time: String, duration: Int = 5, label: String = "") =
        BellEntry(time = time, days = emptySet(), duration = duration, label = label)

    private fun weekdays(vararg times: String) = ScheduleGroup(
        id = 1,
        name = "Будни",
        days = WEEKDAYS,
        entries = times.map { bell(it) },
    )

    private fun times(date: LocalDate, schedule: BellSchedule): List<String> =
        schedule.bellsFor(date).map { it.time }

    @Test
    fun `auto window outside range rings nothing`() {
        val schedule = BellSchedule(
            groups = listOf(weekdays("08:00")),
            auto = AutoWindow(start = tuesday, stop = LocalDate.of(2026, 10, 23)),
        )
        assertTrue(schedule.bellsFor(monday).isEmpty())
        assertEquals(listOf("08:00"), times(tuesday, schedule))
        assertTrue(schedule.bellsFor(saturday).isEmpty())
    }

    @Test
    fun `weekday match picks the schedule`() {
        val schedule = BellSchedule(groups = listOf(weekdays("08:00", "09:00")))
        assertEquals(listOf("08:00", "09:00"), times(monday, schedule))
        assertTrue(schedule.bellsFor(saturday).isEmpty())
    }

    @Test
    fun `mute override removes every bell`() {
        val schedule = BellSchedule(
            groups = listOf(weekdays("08:00")),
            overrides = listOf(DayOverride(tuesday, DayOverrideMode.Mute)),
        )
        assertEquals(listOf("08:00"), times(monday, schedule))
        assertTrue(schedule.bellsFor(tuesday).isEmpty())
    }

    @Test
    fun `replace override ignores the weekday schedule`() {
        val schedule = BellSchedule(
            groups = listOf(weekdays("08:00", "09:00")),
            overrides = listOf(
                DayOverride(
                    date = saturday,
                    mode = DayOverrideMode.Replace,
                    entries = listOf(bell("10:00", 10)),
                    basedOnGroupId = 1,
                ),
            ),
        )
        assertEquals(listOf("10:00"), times(saturday, schedule))
        assertEquals(listOf("08:00", "09:00"), times(monday, schedule))
    }

    @Test
    fun `add override appends extra bells to the normal schedule`() {
        val schedule = BellSchedule(
            groups = listOf(weekdays("08:00", "09:00")),
            overrides = listOf(
                DayOverride(
                    date = monday,
                    mode = DayOverrideMode.Add,
                    entries = listOf(bell("12:30", 10, "Доп. звонок")),
                ),
            ),
        )
        assertEquals(listOf("08:00", "09:00", "12:30"), times(monday, schedule))
        assertEquals(10, schedule.bellsFor(monday).last().duration)
    }

    @Test
    fun `same minute merge keeps the longer duration`() {
        val schedule = BellSchedule(
            groups = listOf(ScheduleGroup(id = 1, days = WEEKDAYS, entries = listOf(bell("08:00", 5)))),
            overrides = listOf(
                DayOverride(monday, DayOverrideMode.Add, listOf(bell("08:00", 25))),
            ),
        )
        val bells = schedule.bellsFor(monday)
        assertEquals(1, bells.size)
        assertEquals(25, bells.first().duration)
    }

    @Test
    fun `entry level weekdays still restrict a legacy bell`() {
        val group = ScheduleGroup(
            id = 1,
            days = WEEKDAYS,
            entries = listOf(bell("08:00").copy(days = setOf(1))),
        )
        val schedule = BellSchedule(groups = listOf(group))
        assertEquals(listOf("08:00"), times(monday, schedule))
        assertTrue(schedule.bellsFor(tuesday).isEmpty())
    }

    @Test
    fun `next bell looks past today`() {
        val schedule = BellSchedule(groups = listOf(weekdays("08:00", "09:00")))
        val next = schedule.nextBell(LocalDateTime.of(2026, 10, 19, 8, 30))
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), next?.at)

        val afterFriday = schedule.nextBell(LocalDateTime.of(2026, 10, 23, 10, 0))
        assertEquals(LocalDateTime.of(2026, 10, 26, 8, 0), afterFriday?.at)
    }

    @Test
    fun `overlapping weekdays are reported`() {
        val schedule = BellSchedule(
            groups = listOf(
                ScheduleGroup(id = 1, days = setOf(1, 2)),
                ScheduleGroup(id = 2, days = setOf(2, 3)),
            ),
        )
        val overlap = schedule.firstOverlap()
        assertEquals(2, overlap?.day)
        assertEquals(1, overlap?.groupId)
        assertEquals(2, overlap?.otherGroupId)
        assertTrue(schedule.validate(deviceApi = 3) is ScheduleIssue.OverlappingWeekdays)
    }

    @Test
    fun `special days need firmware api 3`() {
        val withOverride = BellSchedule(
            groups = listOf(weekdays("08:00")),
            overrides = listOf(DayOverride(saturday, DayOverrideMode.Mute)),
        )
        assertNull(withOverride.validate(deviceApi = 3))
        assertTrue(withOverride.validate(deviceApi = 2) is ScheduleIssue.FirmwareTooOld)
    }
}
