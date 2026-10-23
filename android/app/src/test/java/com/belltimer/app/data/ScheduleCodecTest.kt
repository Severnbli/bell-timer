package com.belltimer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleCodecTest {

    private fun bell(
        time: String,
        pair: String? = null,
        days: Set<Int> = WEEKDAYS,
        label: String = "",
    ) = BellEntry(time = time, pair = pair, days = days, label = label)

    @Test
    fun `decodes version 1 flat entries into one base pack`() {
        val json = """
            {"version":1,"entries":[
              {"time":"08:00","days":[1,2,3],"duration":7,"label":"Урок 1"},
              {"time":"9:05","days":[1,2,3],"duration":5,"label":""}
            ]}
        """.trimIndent()

        val schedule = ScheduleCodec.decode(json)
        val pack = schedule.packs.single()
        assertEquals(PackKind.Base, pack.kind)
        assertEquals(listOf("08:00", "09:05"), pack.entries.map { it.time })
        assertEquals(setOf(1, 2, 3), pack.entries.first().days)
        assertEquals(setOf(1, 2, 3), pack.weekdayUnion)
    }

    @Test
    fun `decodes version 2 exceptions into corrections`() {
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
        assertEquals(4, schedule.packs.size)
        assertEquals(2, schedule.packs.count { it.kind == PackKind.Base })

        val muted = schedule.packs.first { it.mute }
        assertEquals(setOf(LocalDate.of(2026, 11, 4)), muted.window.dates)

        val replaced = schedule.packs.last()
        assertEquals(PackKind.Correction, replaced.kind)
        assertEquals(listOf("09:00"), replaced.entries.map { it.time })
        assertEquals(setOf(LocalDate.of(2026, 11, 7)), replaced.window.dates)
    }

    @Test
    fun `decodes version 3 groups and overrides`() {
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

        // base group, add override as a date-limited base pack, then corrections
        assertEquals(4, schedule.packs.size)
        assertEquals(listOf(PackKind.Base, PackKind.Base, PackKind.Correction, PackKind.Correction),
            schedule.packs.map { it.kind })

        val added = schedule.packs[1]
        assertEquals(setOf(LocalDate.of(2026, 11, 13)), added.window.dates)
        assertEquals(listOf("12:30"), added.entries.map { it.time })

        assertTrue(schedule.packs[2].mute)
        assertEquals(setOf(LocalDate.of(2026, 11, 14)), schedule.packs[2].window.dates)
        assertEquals(listOf("09:00"), schedule.packs[3].entries.map { it.time })
        assertEquals(LocalDate.of(2026, 9, 1), schedule.auto.start)
    }

    @Test
    fun `empty replace override becomes a mute correction`() {
        val json = """
            {"version":3,
             "groups":[{"id":1,"name":"Будни","days":[1,2,3,4,5],
                        "entries":[{"time":"08:00","duration":5}]}],
             "overrides":[{"date":"2026-11-04","mode":"replace","entries":[]}]}
        """.trimIndent()

        val schedule = ScheduleCodec.decode(json)
        val correction = schedule.packs.last()
        assertEquals(PackKind.Correction, correction.kind)
        assertTrue(correction.mute)
        assertTrue(correction.entries.isEmpty())
        assertTrue(schedule.bellsFor(LocalDate.of(2026, 11, 4)).isEmpty())
    }

    @Test
    fun `ignores unknown keys`() {
        val json = """
            {"version":4,"future_field":true,
             "packs":[{"id":1,"kind":"base","entries":[{"time":"08:00","extra":1}]}]}
        """.trimIndent()
        val schedule = ScheduleCodec.decode(json)
        assertEquals(listOf("08:00"), schedule.packs.single().entries.map { it.time })
    }

    @Test
    fun `encodes version 4 and round-trips`() {
        val schedule = BellSchedule(
            packs = listOf(
                SchedulePack(
                    id = 1,
                    name = "Будни",
                    kind = PackKind.Base,
                    window = PackWindow(
                        from = LocalDate.of(2026, 9, 1),
                        to = LocalDate.of(2027, 5, 31),
                        dates = setOf(LocalDate.of(2026, 11, 7)),
                        yearly = true,
                    ),
                    entries = listOf(
                        bell("08:00", pair = "08:45", days = WEEKDAYS, label = "Урок 1"),
                    ),
                ),
                SchedulePack(
                    id = 2,
                    name = "Коррекция",
                    kind = PackKind.Correction,
                    enabled = false,
                    window = PackWindow(dates = setOf(LocalDate.of(2026, 11, 4))),
                    entries = listOf(bell("10:00", days = ALL_DAYS)),
                ),
            ),
            auto = AutoWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 5, 31)),
        )

        val text = ScheduleCodec.encode(schedule)
        assertTrue(text.contains("\"version\": 4"))
        assertTrue(text.contains("\"kind\": \"correction\""))
        assertTrue(text.contains("\"pair\": \"08:45\""))
        assertTrue(text.contains("\"enabled\": false"))
        assertFalse(text.contains("\"groups\""))
        assertFalse(text.contains("\"overrides\""))
        assertFalse(text.contains("\"duration\""))

        val decoded = ScheduleCodec.decode(text)
        assertEquals(schedule, decoded)

        val again = ScheduleCodec.decode(ScheduleCodec.encode(decoded))
        assertNotNull(again)
        assertEquals(decoded, again)
    }
}
