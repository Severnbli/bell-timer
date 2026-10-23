package com.belltimer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleBuilderTest {

    private fun spec(
        rows: List<LessonRow>,
        pattern: BellPattern = BellPattern.StartAndEnd,
        startMinute: Int = 8 * 60,
        maxEntries: Int = MAX_PACK_ENTRIES,
        existingEntries: Int = 0,
    ) = BuilderSpec(
        startMinute = startMinute,
        rows = rows,
        pattern = pattern,
        existingEntries = existingEntries,
        maxEntries = maxEntries,
    )

    @Test
    fun `variable breaks produce the expected bell times`() {
        val result = ScheduleBuilder.build(
            spec(
                listOf(
                    LessonRow(45, 10),
                    LessonRow(45, 10),
                    LessonRow(45, 20),
                ),
            ),
        )

        assertTrue(result.isValid)
        assertEquals(
            listOf("08:00", "08:45", "08:55", "09:40", "09:50", "10:35"),
            result.entries.map { it.time },
        )
        assertEquals(6, result.count)
    }

    @Test
    fun `zero break rings one bell at the boundary`() {
        val result = ScheduleBuilder.build(
            spec(
                listOf(
                    LessonRow(45, 0),
                    LessonRow(45, 0),
                ),
            ),
        )

        assertTrue(result.isValid)
        assertEquals(listOf("08:00", "08:45", "09:30"), result.entries.map { it.time })
        assertEquals(3, result.count)
    }

    @Test
    fun `start only pattern rings once per lesson`() {
        val result = ScheduleBuilder.build(
            spec(listOf(LessonRow(45, 10), LessonRow(45, 10)), pattern = BellPattern.StartOnly),
        )
        assertEquals(listOf("08:00", "08:55"), result.entries.map { it.time })
    }

    @Test
    fun `end only pattern rings once per lesson`() {
        val result = ScheduleBuilder.build(
            spec(listOf(LessonRow(45, 10), LessonRow(45, 10)), pattern = BellPattern.EndOnly),
        )
        assertEquals(listOf("08:45", "09:40"), result.entries.map { it.time })
    }

    @Test
    fun `labels come from the caller`() {
        val result = ScheduleBuilder.build(
            spec(listOf(LessonRow(45, 10))),
            labelStart = { "Урок $it — начало" },
            labelEnd = { "Урок $it — конец" },
        )
        assertEquals(listOf("Урок 1 — начало", "Урок 1 — конец"), result.entries.map { it.label })
    }

    @Test
    fun `generated entries get the requested weekdays`() {
        val result = ScheduleBuilder.build(
            spec(listOf(LessonRow(45, 10))),
            days = setOf(1, 3, 5),
        )
        assertEquals(setOf(1, 3, 5), result.entries.first().days)
        assertEquals(setOf(1, 3, 5), result.entries.last().days)
    }

    @Test
    fun `schedule running past midnight is rejected`() {
        val result = ScheduleBuilder.build(
            spec(listOf(LessonRow(45, 10), LessonRow(45, 10)), startMinute = 23 * 60),
        )
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it is BuilderIssue.PastMidnight })
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `bell limit overflow is rejected`() {
        val result = ScheduleBuilder.build(
            spec(
                listOf(LessonRow(45, 10), LessonRow(45, 10), LessonRow(45, 10)),
                maxEntries = 4,
            ),
        )
        assertFalse(result.isValid)
        val issue = result.issues.filterIsInstance<BuilderIssue.TooManyBells>().single()
        assertEquals(6, issue.total)
    }

    @Test
    fun `existing bells count against the pack limit`() {
        val result = ScheduleBuilder.build(
            spec(listOf(LessonRow(45, 10)), maxEntries = 6, existingEntries = 4),
        )
        assertTrue(result.isValid)
        assertEquals(2, result.count)

        val overflow = ScheduleBuilder.build(
            spec(listOf(LessonRow(45, 10)), maxEntries = 6, existingEntries = 5),
        )
        assertFalse(overflow.isValid)
    }

    @Test
    fun `empty table is not applicable`() {
        val result = ScheduleBuilder.build(spec(emptyList()))
        assertFalse(result.isValid)
        assertTrue(result.issues.contains(BuilderIssue.NoLessons))
    }

    @Test
    fun `shift moves times and pairs and reports overflow`() {
        val bells = listOf(
            BellEntry(time = "08:00", pair = "08:45", days = WEEKDAYS),
            BellEntry(time = "09:00", days = WEEKDAYS),
        )

        val shifted = bells.shiftBy(10)
        assertFalse(shifted.overflow)
        assertEquals(listOf("08:10", "09:10"), shifted.entries.map { it.time })
        assertEquals("08:55", shifted.entries.first().pair)

        val broken = listOf(BellEntry(time = "23:55", days = WEEKDAYS)).shiftBy(10)
        assertTrue(broken.overflow)
        assertTrue(broken.entries.isEmpty())
    }
}
