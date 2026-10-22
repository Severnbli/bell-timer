package com.belltimer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleCodecTest {

    private fun bell(time: String, duration: Int = 5, label: String = "") =
        BellEntry(time = time, days = emptySet(), duration = duration, label = label)

    @Test
    fun `decodes version 1 flat entries`() {
        val json = """
            {"version":1,"entries":[
              {"time":"08:00","days":[1,2,3],"duration":7,"label":"Урок 1"},
              {"time":"9:05","days":[1,2,3],"duration":5,"label":""}
            ]}
        """.trimIndent()

        val schedule = ScheduleCodec.decode(json)
        assertEquals(1, schedule.groups.size)
        val group = schedule.groups.single()
        assertEquals(ALL_DAYS, group.days)
        assertEquals(listOf("08:00", "09:05"), group.entries.map { it.time })
        assertEquals(setOf(1, 2, 3), group.entries.first().days)
        assertEquals(7, group.entries.first().duration)
    }

    @Test
    fun `decodes version 2 exceptions into overrides`() {
        val json = """
            {"version":2,
             "groups":[
               {"id":1,"name":"Будни","days":[1,2,3,4,5],
                "entries":[{"time":"08:00","duration":5,"label":"Урок 1"},
                           {"time":"08:45","duration":5,"label":"Урок 1 конец"}]},
               {"id":2,"name":"Суббота","days":[6],"entries":[{"time":"09:00","duration":5}]}
             ],
             "exceptions":[
               {"date":"2026-11-04","mute":true},
               {"date":"2026-11-07","group_id":2}
             ]}
        """.trimIndent()

        val schedule = ScheduleCodec.decode(json)
        assertEquals(2, schedule.overrides.size)

        val muted = schedule.overrideFor(LocalDate.of(2026, 11, 4))
        assertEquals(DayOverrideMode.Mute, muted?.mode)
        assertTrue(muted?.entries?.isEmpty() == true)

        val replaced = schedule.overrideFor(LocalDate.of(2026, 11, 7))
        assertEquals(DayOverrideMode.Replace, replaced?.mode)
        assertEquals(2, replaced?.basedOnGroupId)
        assertEquals(listOf("09:00"), replaced?.entries?.map { it.time })
    }

    @Test
    fun `decodes version 3 overrides`() {
        val json = """
            {"version":3,
             "groups":[{"id":1,"name":"Будни","days":[1,2,3,4,5],
                        "entries":[{"time":"08:00","duration":5,"label":"Урок 1"}]}],
             "overrides":[
               {"date":"2026-11-13","mode":"add",
                "entries":[{"time":"12:30","duration":10,"label":"Доп. звонок"}]},
               {"date":"2026-11-14","mode":"mute","entries":[{"time":"10:00"}]},
               {"date":"2026-11-16","mode":"replace","based_on":1,
                "entries":[{"time":"09:00","duration":5,"label":"Урок 1"}]}
             ],
             "auto":{"start":"2026-09-01","stop":"2027-05-31"}}
        """.trimIndent()

        val schedule = ScheduleCodec.decode(json)
        assertEquals(3, schedule.overrides.size)
        assertEquals(DayOverrideMode.Add, schedule.overrideFor(LocalDate.of(2026, 11, 13))?.mode)
        assertEquals(DayOverrideMode.Mute, schedule.overrideFor(LocalDate.of(2026, 11, 14))?.mode)
        assertTrue(schedule.overrideFor(LocalDate.of(2026, 11, 14))?.entries?.isEmpty() == true)
        assertEquals(1, schedule.overrideFor(LocalDate.of(2026, 11, 16))?.basedOnGroupId)
        assertEquals(LocalDate.of(2026, 9, 1), schedule.auto.start)
    }

    @Test
    fun `ignores unknown keys`() {
        val json = """
            {"version":3,"future_field":true,
             "groups":[{"id":1,"days":[1],"entries":[{"time":"08:00","duration":5,"extra":1}]}]}
        """.trimIndent()
        val schedule = ScheduleCodec.decode(json)
        assertEquals(listOf("08:00"), schedule.groups.single().entries.map { it.time })
    }

    @Test
    fun `encodes version 3 and round-trips`() {
        val schedule = BellSchedule(
            groups = listOf(
                ScheduleGroup(
                    id = 1,
                    name = "Будни",
                    days = WEEKDAYS,
                    entries = listOf(
                        bell("08:00", 5, "Урок 1 — начало").copy(days = WEEKDAYS),
                        bell("08:45", 7, "Урок 1 — конец").copy(days = WEEKDAYS),
                    ),
                ),
            ),
            overrides = listOf(
                DayOverride(LocalDate.of(2026, 11, 4), DayOverrideMode.Mute),
                DayOverride(
                    date = LocalDate.of(2026, 11, 13),
                    mode = DayOverrideMode.Add,
                    entries = listOf(bell("12:30", 10, "Доп. звонок")),
                ),
                DayOverride(
                    date = LocalDate.of(2026, 11, 7),
                    mode = DayOverrideMode.Replace,
                    entries = listOf(bell("09:00", 5, "Урок 1 — начало")),
                    basedOnGroupId = 1,
                ),
            ).sortedBy { it.date },
            auto = AutoWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 5, 31)),
        )

        val text = ScheduleCodec.encode(schedule)
        assertTrue(text.contains("\"version\": 3"))
        assertTrue(text.contains("\"mode\": \"mute\""))
        assertTrue(text.contains("\"mode\": \"add\""))
        assertTrue(text.contains("\"based_on\": 1"))
        assertFalse(text.contains("\"exceptions\""))

        val decoded = ScheduleCodec.decode(text)
        assertEquals(schedule, decoded)

        val again = ScheduleCodec.decode(ScheduleCodec.encode(decoded))
        assertNotNull(again)
        assertEquals(decoded, again)
    }
}
