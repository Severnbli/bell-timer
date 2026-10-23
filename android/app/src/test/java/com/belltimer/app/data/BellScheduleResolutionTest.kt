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
    private val sunday = LocalDate.of(2026, 10, 25)

    private fun bell(
        time: String,
        pair: String? = null,
        days: Set<Int> = WEEKDAYS,
        label: String = "",
    ) = BellEntry(time = time, pair = pair, days = days, label = label)

    private fun base(
        id: Int = 1,
        entries: List<BellEntry> = emptyList(),
        enabled: Boolean = true,
        window: PackWindow = PackWindow(),
        name: String = "",
    ) = SchedulePack(
        id = id,
        name = name,
        kind = PackKind.Base,
        enabled = enabled,
        window = window,
        entries = entries,
    )

    private fun correction(
        id: Int = 2,
        entries: List<BellEntry> = emptyList(),
        enabled: Boolean = true,
        window: PackWindow = PackWindow(),
        mute: Boolean = false,
    ) = SchedulePack(
        id = id,
        name = "Коррекция",
        kind = PackKind.Correction,
        enabled = enabled,
        window = window,
        entries = entries,
        mute = mute,
    )

    private fun times(date: LocalDate, schedule: BellSchedule): List<String> =
        schedule.bellsFor(date).map { it.time }

    @Test
    fun `base pack rings only on the weekdays of its entries`() {
        val schedule = BellSchedule(
            packs = listOf(base(entries = listOf(bell("08:00"), bell("09:00")))),
        )
        assertEquals(listOf("08:00", "09:00"), times(monday, schedule))
        assertTrue(schedule.bellsFor(saturday).isEmpty())
    }

    @Test
    fun `disabled pack is ignored`() {
        val schedule = BellSchedule(
            packs = listOf(base(entries = listOf(bell("08:00")), enabled = false)),
        )
        assertTrue(schedule.bellsFor(monday).isEmpty())
    }

    @Test
    fun `correction cancels the base packs above it`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(id = 1, entries = listOf(bell("08:00", days = ALL_DAYS))),
                correction(id = 2, entries = listOf(bell("10:00", days = setOf(1)))),
            ),
        )
        assertEquals(listOf("10:00"), times(monday, schedule))
        assertEquals(listOf("08:00"), times(saturday, schedule))
    }

    @Test
    fun `base pack below a correction still rings`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(id = 1, entries = listOf(bell("08:00"))),
                correction(id = 2, entries = listOf(bell("10:00"))),
                base(id = 3, entries = listOf(bell("11:00"))),
            ),
        )
        assertEquals(listOf("10:00", "11:00"), times(monday, schedule))
    }

    @Test
    fun `correction without bells for today does not cancel`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(id = 1, entries = listOf(bell("08:00", days = ALL_DAYS))),
                correction(id = 2, entries = listOf(bell("10:00", days = setOf(6)))),
            ),
        )
        assertEquals(listOf("08:00"), times(monday, schedule))
        assertEquals(listOf("10:00"), times(saturday, schedule))
    }

    @Test
    fun `mute correction cancels the base packs above it`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(id = 1, entries = listOf(bell("08:00"))),
                correction(id = 2, mute = true, window = PackWindow(dates = setOf(monday))),
            ),
        )
        assertTrue(schedule.bellsFor(monday).isEmpty())
        assertEquals(listOf("08:00"), times(tuesday, schedule))
    }

    @Test
    fun `date range limits a pack`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(
                    entries = listOf(bell("08:00", days = ALL_DAYS)),
                    window = PackWindow(from = tuesday, to = saturday),
                ),
            ),
        )
        assertTrue(schedule.bellsFor(monday).isEmpty())
        assertEquals(listOf("08:00"), times(tuesday, schedule))
        assertEquals(listOf("08:00"), times(saturday, schedule))
        assertTrue(schedule.bellsFor(sunday).isEmpty())
    }

    @Test
    fun `individual dates limit a pack`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(
                    entries = listOf(bell("08:00", days = ALL_DAYS)),
                    window = PackWindow(dates = setOf(saturday)),
                ),
            ),
        )
        assertTrue(schedule.bellsFor(monday).isEmpty())
        assertEquals(listOf("08:00"), times(saturday, schedule))
    }

    @Test
    fun `yearly range wraps over new year`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(
                    entries = listOf(bell("08:00", days = ALL_DAYS)),
                    window = PackWindow(
                        from = LocalDate.of(2025, 12, 1),
                        to = LocalDate.of(2026, 2, 28),
                        yearly = true,
                    ),
                ),
            ),
        )
        assertEquals(listOf("08:00"), times(LocalDate.of(2027, 1, 15), schedule))
        assertEquals(listOf("08:00"), times(LocalDate.of(2027, 12, 5), schedule))
        assertTrue(schedule.bellsFor(LocalDate.of(2027, 3, 1)).isEmpty())
    }

    @Test
    fun `yearly individual date repeats by month and day`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(
                    entries = listOf(bell("08:00", days = ALL_DAYS)),
                    window = PackWindow(dates = setOf(LocalDate.of(2025, 10, 19)), yearly = true),
                ),
            ),
        )
        assertEquals(listOf("08:00"), times(monday, schedule))
        assertTrue(schedule.bellsFor(tuesday).isEmpty())
    }

    @Test
    fun `pair expands into a second bell with the same weekdays`() {
        val schedule = BellSchedule(
            packs = listOf(base(entries = listOf(bell("08:00", pair = "08:45")))),
        )
        assertEquals(listOf("08:00", "08:45"), times(monday, schedule))
        assertTrue(schedule.bellsFor(saturday).isEmpty())
    }

    @Test
    fun `same minute merge keeps one bell`() {
        val schedule = BellSchedule(
            packs = listOf(
                base(
                    entries = listOf(
                        bell("08:00", pair = "08:45", label = "первый"),
                        bell("08:45", label = "второй"),
                    ),
                ),
            ),
        )
        val bells = schedule.bellsFor(monday)
        assertEquals(listOf("08:00", "08:45"), bells.map { it.time })
        assertEquals(1, bells.count { it.time == "08:45" })
    }

    @Test
    fun `appliesOn reflects enable, window and weekday`() {
        val pack = base(
            entries = listOf(bell("08:00", days = setOf(1))),
            window = PackWindow(dates = setOf(monday)),
        )
        assertTrue(pack.appliesOn(monday))
        assertTrue(!pack.appliesOn(tuesday))
        assertTrue(!pack.copy(enabled = false).appliesOn(monday))
    }

    @Test
    fun `next bell looks past today`() {
        val schedule = BellSchedule(
            packs = listOf(base(entries = listOf(bell("08:00"), bell("09:00")))),
        )
        val next = schedule.nextBell(LocalDateTime.of(2026, 10, 19, 8, 30))
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), next?.at)

        val afterFriday = schedule.nextBell(LocalDateTime.of(2026, 10, 23, 10, 0))
        assertEquals(LocalDateTime.of(2026, 10, 26, 8, 0), afterFriday?.at)
    }

    @Test
    fun `packs need firmware api 4`() {
        val schedule = BellSchedule(packs = listOf(base(entries = listOf(bell("08:00")))))
        assertNull(schedule.validate(deviceApi = 4))
        assertTrue(schedule.validate(deviceApi = 3) is ScheduleIssue.FirmwareTooOld)
    }

    @Test
    fun `limits are validated`() {
        val tooManyPacks = BellSchedule(
            packs = (1..MAX_PACKS + 1).map { base(id = it) },
        )
        assertTrue(tooManyPacks.validate(4) is ScheduleIssue.TooManyPacks)

        val tooManyEntries = BellSchedule(
            packs = listOf(
                base(entries = (1..MAX_PACK_ENTRIES + 1).map { bell("08:00") }),
            ),
        )
        assertTrue(tooManyEntries.validate(4) is ScheduleIssue.TooManyPackEntries)

        val tooManyDates = BellSchedule(
            packs = listOf(
                base(
                    window = PackWindow(
                        dates = (1..MAX_PACK_DATES + 1)
                            .map { monday.plusDays(it.toLong()) }
                            .toSet(),
                    ),
                ),
            ),
        )
        assertTrue(tooManyDates.validate(4) is ScheduleIssue.TooManyPackDates)

        val invalidWindow = BellSchedule(
            packs = listOf(base(window = PackWindow(from = saturday, to = monday))),
        )
        assertTrue(invalidWindow.validate(4) is ScheduleIssue.PackWindowInvalid)
    }
}
