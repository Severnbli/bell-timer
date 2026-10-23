package com.belltimer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleResolutionTest {

    private val monday = LocalDate.of(2026, 11, 2)
    private val saturday = LocalDate.of(2026, 11, 7)

    private fun t(value: String) = parseTimeToMinutes(value)!!

    private fun lesson(start: String, length: Int = 45, pre: Int = 0, label: String = "", days: Set<Int> = WEEKDAYS) =
        ScheduleModule(type = ModuleType.Lesson, start = t(start), length = length, pre = pre, days = days, label = label)

    private fun bell(time: String, label: String = "", days: Set<Int> = WEEKDAYS) =
        ScheduleModule(type = ModuleType.Bell, start = t(time), days = days, label = label)

    private fun List<ResolvedBell>.times() = map { it.time }

    @Test
    fun lessonRingsAtStartAndEnd() {
        val module = lesson("08:30", length = 45)
        assertEquals(listOf(BellRole.Start, BellRole.End), module.bells().map { it.role })
        assertEquals(listOf(t("08:30"), t("09:15")), module.bells().map { it.minute })
    }

    @Test
    fun lessonWarningBellComesFirst() {
        val bells = lesson("08:30", pre = 2).bells()
        assertEquals(listOf("08:28", "08:30", "09:15"), bells.map { minutesToTime(it.minute) })
        assertEquals(BellRole.Pre, bells.first().role)
    }

    @Test
    fun seriesStopsAtUntil() {
        val series = ScheduleModule(type = ModuleType.Series, start = t("09:00"), until = t("10:40"), every = 30)
        assertEquals(listOf("09:00", "09:30", "10:00", "10:30"), series.bells().map { minutesToTime(it.minute) })
        assertEquals(t("10:30"), series.endMinute)
    }

    @Test
    fun invalidModulesAreDetected() {
        assertFalse(lesson("23:30", length = 45).isValid)
        assertFalse(lesson("00:01", pre = 5).isValid)
        assertFalse(ScheduleModule(type = ModuleType.Series, start = t("10:00"), until = t("09:00")).isValid)
        assertFalse(bell("08:00", days = emptySet()).isValid)
        assertTrue(lesson("08:00").isValid)
    }

    @Test
    fun weekdaysFilterModules() {
        val schedule = BellSchedule(
            packs = listOf(SchedulePack(id = 1, modules = listOf(lesson("08:30"), bell("10:00", days = setOf(6))))),
        )
        assertEquals(listOf("08:30", "09:15"), schedule.resolveDay(monday).times())
        assertEquals(listOf("10:00"), schedule.resolveDay(saturday).times())
    }

    @Test
    fun sameMinuteRingsOnceAndFirstModuleOwnsIt() {
        // A zero-minute break: the end of lesson 1 is the start of lesson 2.
        val schedule = BellSchedule(
            packs = listOf(
                SchedulePack(
                    id = 1,
                    modules = listOf(lesson("08:00", label = "1"), lesson("08:45", label = "2")),
                ),
            ),
        )
        val bells = schedule.resolveDay(monday)
        assertEquals(listOf("08:00", "08:45", "09:30"), bells.times())
        assertEquals("1", bells[1].module.label)
        assertEquals(BellRole.End, bells[1].role)
    }

    @Test
    fun correctionReplacesBaseAboveButNotBelow() {
        val schedule = BellSchedule(
            packs = listOf(
                SchedulePack(id = 1, modules = listOf(lesson("08:00"))),
                SchedulePack(
                    id = 2,
                    kind = PackKind.Correction,
                    window = PackWindow(dates = setOf(monday)),
                    modules = listOf(bell("10:00")),
                ),
                SchedulePack(id = 3, modules = listOf(bell("15:00"))),
            ),
        )
        assertEquals(listOf("10:00", "15:00"), schedule.resolveDay(monday).times())
        assertEquals(listOf("08:00", "08:45", "15:00"), schedule.resolveDay(monday.plusDays(1)).times())
    }

    @Test
    fun correctionWithoutModulesForTheWeekdayDoesNotApply() {
        val schedule = BellSchedule(
            packs = listOf(
                SchedulePack(id = 1, modules = listOf(lesson("08:00"))),
                SchedulePack(id = 2, kind = PackKind.Correction, modules = listOf(bell("10:00", days = setOf(6)))),
            ),
        )
        assertEquals(listOf("08:00", "08:45"), schedule.resolveDay(monday).times())
        assertEquals(listOf("10:00"), schedule.resolveDay(saturday).times())
    }

    @Test
    fun mutedCorrectionIsAHoliday() {
        val holiday = SchedulePack(
            id = 2,
            kind = PackKind.Correction,
            mute = true,
            window = PackWindow(dates = setOf(monday)),
        )
        val schedule = BellSchedule(
            packs = listOf(SchedulePack(id = 1, modules = listOf(lesson("08:00"))), holiday),
        )
        assertTrue(schedule.resolveDay(monday).isEmpty())
        assertEquals(DayKind.Muted, schedule.dayKind(monday))
        assertEquals(DayKind.Base, schedule.dayKind(monday.plusDays(1)))
    }

    @Test
    fun laterCorrectionKeepsEarlierCorrectionBells() {
        val schedule = BellSchedule(
            packs = listOf(
                SchedulePack(id = 1, modules = listOf(bell("08:00"))),
                SchedulePack(id = 2, kind = PackKind.Correction, modules = listOf(bell("09:00"))),
                SchedulePack(id = 3, modules = listOf(bell("10:00"))),
                SchedulePack(id = 4, kind = PackKind.Correction, modules = listOf(bell("11:00"))),
            ),
        )
        assertEquals(listOf("09:00", "11:00"), schedule.resolveDay(monday).times())
    }

    @Test
    fun disabledPackAndAutoWindowAreRespected() {
        val schedule = BellSchedule(
            packs = listOf(SchedulePack(id = 1, enabled = false, modules = listOf(bell("08:00")))),
        )
        assertTrue(schedule.resolveDay(monday).isEmpty())
        val windowed = BellSchedule(
            packs = listOf(SchedulePack(id = 1, modules = listOf(bell("08:00")))),
            auto = AutoWindow(start = monday.plusDays(1)),
        )
        assertTrue(windowed.resolveDay(monday).isEmpty())
        assertEquals(1, windowed.resolveDay(monday.plusDays(1)).size)
    }

    @Test
    fun yearlyRangeWrapsOverNewYear() {
        val window = PackWindow(from = LocalDate.of(2026, 12, 25), to = LocalDate.of(2027, 1, 8), yearly = true)
        assertTrue(window.allows(LocalDate.of(2030, 1, 3)))
        assertTrue(window.allows(LocalDate.of(2030, 12, 31)))
        assertFalse(window.allows(LocalDate.of(2030, 2, 1)))
    }

    @Test
    fun nextBellAndRunningLesson() {
        val schedule = BellSchedule(packs = listOf(SchedulePack(id = 1, modules = listOf(lesson("08:30")))))
        val now = monday.atTime(8, 40)
        val next = schedule.nextBell(now)
        assertNotNull(next)
        assertEquals(LocalDateTime.of(monday, java.time.LocalTime.of(9, 15)), next!!.at)
        val running = schedule.runningLesson(now)
        assertNotNull(running)
        assertEquals(10f / 45f, running!!.progress(8 * 60 + 40.0), 0.001f)
        assertNull(schedule.runningLesson(monday.atTime(9, 20)))
        // After the last bell of Friday the next one is on Monday.
        val friday = monday.plusDays(4).atTime(10, 0)
        assertEquals(monday.plusDays(7), schedule.nextBell(friday)!!.at.toLocalDate())
    }

    @Test
    fun shiftMovesEverythingOrNothing() {
        val modules = listOf(lesson("08:00"), bell("23:50"))
        assertEquals(t("08:05"), modules.shiftedBy(5)!!.first().start)
        assertNull(modules.shiftedBy(15))
    }

    @Test
    fun templateUsesItsOwnBreakAfterEachLesson() {
        val template = LessonTemplate(
            id = "t",
            name = "Школа",
            start = t("08:00"),
            lessons = listOf(TemplateLesson(45, 10), TemplateLesson(45, 20), TemplateLesson(40, 15), TemplateLesson(45, 99)),
        )
        val lessons = template.build(WEEKDAYS) { "$it урок" }!!
        assertEquals(listOf("08:00", "08:55", "10:00", "10:55"), lessons.map { minutesToTime(it.start) })
        assertEquals(40, lessons[2].length)
        assertEquals("4 урок", lessons.last().label)
        assertEquals(t("11:40"), template.endMinute)
        assertNull(template.copy(start = t("22:00")).build(WEEKDAYS) { "" })
        assertNull(template.copy(name = " ").build(WEEKDAYS) { "" })
    }

    @Test
    fun templateIsReadBackFromPackLessons() {
        val source = BuiltInTemplates.all.first()
        val modules = source.build(WEEKDAYS) { "$it" }!! + bell("12:00")
        val read = LessonTemplate.fromModules("copy", "Копия", modules)!!
        assertEquals(source.start, read.start)
        assertEquals(source.lessons.dropLast(1), read.lessons.dropLast(1))
        assertEquals(source.lessons.last().length, read.lessons.last().length)
        assertNull(LessonTemplate.fromModules("x", "x", listOf(bell("12:00"))))
    }

    @Test
    fun applyingTemplateReplacesOnlyLessons() {
        val pack = SchedulePack(id = 1, modules = listOf(lesson("08:00"), lesson("09:00"), bell("12:00")))
        val grid = BuiltInTemplates.all.first().build(WEEKDAYS) { "" }!!
        val updated = pack.withLessons(grid, replace = true)
        assertEquals(grid.size + 1, updated.modules.size)
        assertEquals(1, updated.modules.count { it.type == ModuleType.Bell })
        assertEquals(pack.modules.size + grid.size, pack.withLessons(grid, replace = false).modules.size)
    }

    @Test
    fun builtInTemplatesAreValid() {
        for (template in BuiltInTemplates.all) {
            assertTrue(template.name, template.isValid)
            assertTrue(template.builtIn)
        }
    }

    @Test
    fun addingAndRemovingSingleDates() {
        val window = PackWindow(from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30))
        val added = window.withDate(monday)
        assertTrue(added.hasDate(monday))
        assertTrue(added.allows(monday))
        assertEquals(added, added.withDate(monday))
        assertFalse(added.withoutDate(monday).allows(monday))
        assertTrue(window.rangeCovers(LocalDate.of(2026, 9, 15)))
        assertFalse(window.withDate(monday).rangeCovers(monday))

        val yearly = PackWindow(dates = setOf(LocalDate.of(2026, 11, 4)), yearly = true)
        val nextYear = LocalDate.of(2027, 11, 4)
        assertTrue(yearly.hasDate(nextYear))
        assertTrue(yearly.withoutDate(nextYear).dates.isEmpty())
    }
}
