package com.belltimer.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleCodecTest {

    private val lenient = Json { ignoreUnknownKeys = true }

    private val sample = BellSchedule(
        packs = listOf(
            SchedulePack(
                id = 1,
                name = "Будни",
                window = PackWindow(from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2027, 5, 31)),
                modules = listOf(
                    ScheduleModule(type = ModuleType.Lesson, start = 8 * 60, length = 45, pre = 2, label = "1 урок"),
                    ScheduleModule(type = ModuleType.Bell, start = 12 * 60, days = ALL_DAYS, label = "Обед"),
                    ScheduleModule(type = ModuleType.Series, start = 13 * 60, until = 17 * 60, every = 60),
                ),
            ),
            SchedulePack(
                id = 2,
                name = "Праздник",
                kind = PackKind.Correction,
                mute = true,
                window = PackWindow(dates = setOf(LocalDate.of(2026, 11, 4)), yearly = true),
            ),
        ),
        auto = AutoWindow(start = LocalDate.of(2026, 9, 1)),
    )

    @Test
    fun roundTripKeepsEverything() {
        // Fields a module type does not use are not written, so compare what
        // the device would receive and what would ring.
        val text = ScheduleCodec.encode(sample)
        val decoded = ScheduleCodec.decode(text)
        assertEquals(text, ScheduleCodec.encode(decoded))
        val day = LocalDate.of(2026, 11, 2)
        assertEquals(sample.resolveDay(day).map { it.minute }, decoded.resolveDay(day).map { it.minute })
    }

    @Test
    fun encodesOnlyTheFieldsOfEachType() {
        val root = Json.parseToJsonElement(ScheduleCodec.encode(sample)).jsonObject
        assertEquals("5", root["version"]!!.jsonPrimitive.content)
        val modules = root["packs"]!!.jsonArray[0].jsonObject["modules"]!!.jsonArray
        val lesson = modules[0].jsonObject
        assertEquals("lesson", lesson["type"]!!.jsonPrimitive.content)
        assertEquals("45", lesson["length"]!!.jsonPrimitive.content)
        assertNull(lesson["until"])
        val bell = modules[1].jsonObject
        assertNull(bell["length"])
        assertNull(bell["pre"])
        val series = modules[2].jsonObject
        assertEquals("17:00", series["until"]!!.jsonPrimitive.content)
        assertEquals("60", series["every"]!!.jsonPrimitive.content)
        assertTrue(root["packs"]!!.jsonArray[1].jsonObject["mute"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun version4EntriesBecomeModules() {
        val v4 = """
            {"version":4,"packs":[{"id":3,"name":"Старое","kind":"base","entries":[
              {"time":"08:30","pair":"09:15","days":[1,2,3,4,5],"label":"1 урок"},
              {"time":"12:00","days":[],"label":"Обед"}
            ]}]}
        """.trimIndent()
        val pack = ScheduleCodec.decode(v4).packs.single()
        assertEquals(3, pack.id)
        val lesson = pack.modules[0]
        assertEquals(ModuleType.Lesson, lesson.type)
        assertEquals(8 * 60 + 30, lesson.start)
        assertEquals(45, lesson.length)
        assertEquals(WEEKDAYS, lesson.days)
        val bell = pack.modules[1]
        assertEquals(ModuleType.Bell, bell.type)
        assertEquals(ALL_DAYS, bell.days)
    }

    @Test
    fun unknownModuleTypesAndBadValuesAreSkipped() {
        val text = """
            {"version":5,"packs":[{"id":1,"modules":[
              {"type":"siren","time":"08:00"},
              {"type":"lesson","time":"23:50","length":45},
              {"type":"bell","time":"07:00"}
            ]}]}
        """.trimIndent()
        val modules = ScheduleCodec.decode(text).packs.single().modules
        assertEquals(1, modules.size)
        assertEquals(7 * 60, modules.single().start)
    }

    @Test
    fun muteIsDroppedOnBasePacks() {
        val text = """{"packs":[{"id":1,"kind":"base","mute":true}]}"""
        assertFalse(ScheduleCodec.decode(text).packs.single().mute)
    }

    @Test
    fun duplicatePackIdsAreRenumbered() {
        val text = """{"packs":[{"id":2},{"id":2}]}"""
        assertEquals(listOf(2, 1), ScheduleCodec.decode(text).packs.map { it.id })
    }

    @Test
    fun validationReportsLimitsAndOldFirmware() {
        assertNull(sample.validate(API_MODULES))
        assertEquals(ScheduleIssue.FirmwareTooOld(4), sample.validate(4))
        val tooMany = SchedulePack(id = 1, modules = List(MAX_PACK_MODULES + 1) { ScheduleModule(start = it) })
        assertEquals(ScheduleIssue.TooManyPackModules, BellSchedule(listOf(tooMany)).validate(API_MODULES))
        val broken = SchedulePack(id = 1, modules = listOf(ScheduleModule(type = ModuleType.Lesson, start = 23 * 60 + 50)))
        assertEquals(ScheduleIssue.InvalidModule, BellSchedule(listOf(broken)).validate(API_MODULES))
    }

    @Test
    fun statusMapsNextBellAndSync() {
        val dto = lenient.decodeFromString<StatusDto>(
            """
            {"time":"2026-10-23T08:40:00","rtc_ok":true,"mode":"auto","modules":7,"today_bells":12,
             "synced":"2026-10-22T19:00:05","next":{"date":"2026-10-23","time":"09:15","label":"1 урок",
             "type":"lesson","role":"end"},"errors":[],"fw":"2.0.0","api":5}
            """.trimIndent(),
        )
        val status = dto.toDomain()
        assertEquals(7, status.modules)
        val next = status.next!!
        assertEquals(BellRole.End, next.role)
        assertEquals(ModuleType.Lesson, next.type)
        assertEquals(9, next.at.hour)
        assertEquals(2026, status.lastSync!!.year)
        assertTrue(status.supportsModules)
    }
}
