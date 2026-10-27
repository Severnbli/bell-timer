package com.belltimer.app.data

import java.time.LocalDate
import java.time.LocalDateTime

/** Firmware limits, mirrored in firmware/BellTimer/BellTimer.ino. */
const val MAX_PACKS = 8
const val MAX_PACK_MODULES = 32
const val MAX_PACK_DATES = 24
const val MAX_MODULES = MAX_PACKS * MAX_PACK_MODULES
const val MAX_LABEL_BYTES = 32
const val MAX_PACK_NAME_BYTES = 32
const val MAX_LESSON_MINUTES = 720
const val MAX_PRE_MINUTES = 60
const val MAX_SERIES_STEP = 720

const val MINUTES_PER_DAY = 24 * 60
const val LAST_MINUTE = MINUTES_PER_DAY - 1

val ALL_DAYS: Set<Int> = (1..7).toSet()
val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

/** What kind of building block a module is. */
enum class ModuleType(val wireName: String) {
    /** One bell at [ScheduleModule.start]. */
    Bell("bell"),

    /** A bell at the start and at the end, optionally a warning bell before. */
    Lesson("lesson"),

    /** A bell every [ScheduleModule.every] minutes from start to until. */
    Series("series");

    companion object {
        fun fromWire(value: String?): ModuleType? = when (value) {
            null, "bell" -> Bell
            "lesson" -> Lesson
            "series" -> Series
            else -> null
        }
    }
}

/** What one resolved bell means inside its module. */
enum class BellRole(val wireName: String) {
    Bell("bell"),
    Start("start"),
    End("end"),
    Pre("pre"),
    Step("step");

    companion object {
        fun fromWire(value: String?): BellRole =
            entries.firstOrNull { it.wireName == value } ?: Bell
    }
}

/**
 * A building block of a pack. All fields are kept for every type, so switching
 * the type in the editor does not lose what was typed; only the fields of the
 * current [type] are sent to the device.
 */
data class ScheduleModule(
    val type: ModuleType = ModuleType.Bell,
    /** Minute of day, 0..1439. */
    val start: Int = 8 * 60,
    /** Lesson length in minutes. */
    val length: Int = 45,
    /** Lesson warning bell, minutes before the start; 0 = none. */
    val pre: Int = 0,
    /** Series: last allowed minute of day. */
    val until: Int = 17 * 60,
    /** Series: step in minutes. */
    val every: Int = 60,
    val days: Set<Int> = WEEKDAYS,
    val label: String = "",
) {
    /** Minute of the last bell of the module. */
    val endMinute: Int
        get() = when (type) {
            ModuleType.Bell -> start
            ModuleType.Lesson -> start + length
            ModuleType.Series -> start + ((until - start) / every.coerceAtLeast(1)) * every
        }

    /** Minute of the first bell of the module. */
    val firstMinute: Int get() = if (type == ModuleType.Lesson && pre > 0) start - pre else start

    val isValid: Boolean
        get() {
            if (start !in 0..LAST_MINUTE || days.isEmpty()) return false
            return when (type) {
                ModuleType.Bell -> true
                ModuleType.Lesson -> length in 1..MAX_LESSON_MINUTES &&
                    start + length <= LAST_MINUTE &&
                    pre in 0..MAX_PRE_MINUTES && pre <= start
                ModuleType.Series -> until in (start + 1)..LAST_MINUTE && every in 1..MAX_SERIES_STEP
            }
        }

    /** The bells this module produces, in time order. */
    fun bells(): List<ModuleBell> = when (type) {
        ModuleType.Bell -> listOf(ModuleBell(start, BellRole.Bell))
        ModuleType.Lesson -> buildList {
            if (pre > 0) add(ModuleBell(start - pre, BellRole.Pre))
            add(ModuleBell(start, BellRole.Start))
            add(ModuleBell(start + length, BellRole.End))
        }
        ModuleType.Series -> buildList {
            val step = every.coerceAtLeast(1)
            var minute = start
            while (minute <= until) {
                add(ModuleBell(minute, BellRole.Step))
                minute += step
            }
        }
    }.filter { it.minute in 0..LAST_MINUTE }

    fun shiftedBy(delta: Int): ScheduleModule? {
        val moved = copy(start = start + delta, until = until + delta)
        return moved.takeIf { it.isValid }
    }
}

data class ModuleBell(val minute: Int, val role: BellRole)

enum class PackKind(val wireName: String) {
    Base("base"),
    Correction("correction");

    companion object {
        fun fromWire(value: String?): PackKind =
            if (value.equals("correction", ignoreCase = true)) Correction else Base
    }
}

/**
 * When a pack is allowed to run. [from] and [to] form a date range, [dates]
 * lists individual dates, and [yearly] compares month and day instead of the
 * full date (so a range may wrap over New Year).
 */
data class PackWindow(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val dates: Set<LocalDate> = emptySet(),
    val yearly: Boolean = false,
) {
    val hasRange: Boolean get() = from != null || to != null

    val isSet: Boolean get() = hasRange || dates.isNotEmpty()

    val isValid: Boolean
        get() = yearly || from == null || to == null || !to.isBefore(from)

    fun allows(date: LocalDate): Boolean {
        if (!isSet) return true
        if (hasRange && inRange(date)) return true
        return dates.any { matches(it, date) }
    }

    private fun inRange(date: LocalDate): Boolean {
        if (!yearly) {
            if (from != null && date.isBefore(from)) return false
            if (to != null && date.isAfter(to)) return false
            return true
        }
        val value = monthDay(date)
        val start = from?.let(::monthDay)
        val end = to?.let(::monthDay)
        return when {
            start != null && end != null && start > end -> value >= start || value <= end
            start != null && end != null -> value in start..end
            start != null -> value >= start
            end != null -> value <= end
            else -> false
        }
    }

    private fun matches(expected: LocalDate, date: LocalDate): Boolean =
        if (yearly) monthDay(expected) == monthDay(date) else expected == date

    /** Whether [date] is one of the individual dates (by day and month when yearly). */
    fun hasDate(date: LocalDate): Boolean = dates.any { matches(it, date) }

    /** Whether the date range alone (not the individual dates) covers [date]. */
    fun rangeCovers(date: LocalDate): Boolean = hasRange && inRange(date)

    fun withDate(date: LocalDate): PackWindow = if (hasDate(date)) this else copy(dates = dates + date)

    fun withoutDate(date: LocalDate): PackWindow = copy(dates = dates.filterNot { matches(it, date) }.toSet())

    private fun monthDay(date: LocalDate): Int = date.monthValue * 100 + date.dayOfMonth
}

/**
 * One pack ("пачка") of modules. A base pack adds bells; a correction cancels
 * the bells of every base pack above it and adds its own. A muted correction
 * only cancels: it turns its dates into days without bells.
 */
data class SchedulePack(
    val id: Int,
    val name: String = "",
    val kind: PackKind = PackKind.Base,
    val enabled: Boolean = true,
    val mute: Boolean = false,
    val window: PackWindow = PackWindow(),
    val modules: List<ScheduleModule> = emptyList(),
) {
    fun modulesFor(date: LocalDate): List<ScheduleModule> {
        val weekday = date.dayOfWeek.value
        return modules.filter { weekday in it.days }
    }

    /** Whether the pack takes part in the resolution of [date]. */
    fun appliesOn(date: LocalDate): Boolean {
        if (!enabled || !window.allows(date)) return false
        if (kind == PackKind.Correction && mute) return true
        return modulesFor(date).isNotEmpty()
    }

    fun withModules(newModules: List<ScheduleModule>): SchedulePack =
        copy(modules = newModules.sortedModules())

    val weekdayUnion: Set<Int> get() = modules.flatMap { it.days }.toSet()
}

fun List<ScheduleModule>.sortedModules(): List<ScheduleModule> =
    sortedWith(compareBy<ScheduleModule>({ it.start }, { it.type.ordinal }, { it.label }))

/** Moves every module by [delta] minutes, or returns null when one would leave the day. */
fun List<ScheduleModule>.shiftedBy(delta: Int): List<ScheduleModule>? {
    val moved = map { it.shiftedBy(delta) ?: return null }
    return moved.sortedModules()
}

/** Optional date range in which auto mode is allowed to ring. */
data class AutoWindow(
    val start: LocalDate? = null,
    val stop: LocalDate? = null,
) {
    val isSet: Boolean get() = start != null || stop != null

    val isValid: Boolean
        get() = start == null || stop == null || !stop.isBefore(start)

    fun allows(date: LocalDate): Boolean =
        (start == null || !date.isBefore(start)) &&
            (stop == null || !date.isAfter(stop))
}

/** A bell after resolution: when it rings and which module it came from. */
data class ResolvedBell(
    val minute: Int,
    val role: BellRole,
    val module: ScheduleModule,
    val packId: Int,
    val fromCorrection: Boolean,
) {
    val time: String get() = minutesToTime(minute)
}

data class UpcomingBell(val at: LocalDateTime, val bell: ResolvedBell)

/** A lesson that is running at a given moment. */
data class RunningLesson(val module: ScheduleModule, val startedAt: Int, val endsAt: Int) {
    fun progress(minuteOfDay: Double): Float =
        ((minuteOfDay - startedAt) / (endsAt - startedAt)).toFloat().coerceIn(0f, 1f)
}

/** What a calendar day looks like at a glance. */
enum class DayKind { None, Base, Correction, Muted }

data class BellSchedule(
    val packs: List<SchedulePack> = emptyList(),
    val auto: AutoWindow = AutoWindow(),
) {
    val totalModules: Int get() = packs.sumOf { it.modules.size }

    val isEmpty: Boolean get() = packs.isEmpty()

    fun packById(id: Int?): SchedulePack? =
        if (id == null) null else packs.firstOrNull { it.id == id }

    fun nextPackId(): Int {
        val used = packs.map { it.id }.toSet()
        return (1..255).first { it !in used }
    }

    fun withPack(pack: SchedulePack): BellSchedule {
        val exists = packs.any { it.id == pack.id }
        val updated = if (exists) {
            packs.map { if (it.id == pack.id) pack else it }
        } else {
            packs + pack
        }
        return copy(packs = updated)
    }

    fun withoutPack(id: Int): BellSchedule = copy(packs = packs.filterNot { it.id == id })

    /** Moves a pack by [delta] positions; the order is meaningful for corrections. */
    fun movePack(id: Int, delta: Int): BellSchedule {
        val index = packs.indexOfFirst { it.id == id }
        if (index < 0 || delta == 0) return this
        val target = (index + delta).coerceIn(packs.indices)
        if (target == index) return this
        val updated = packs.toMutableList()
        val pack = updated.removeAt(index)
        updated.add(target, pack)
        return copy(packs = updated)
    }

    /**
     * The single source of truth for what rings on a date. The firmware applies
     * exactly the same rules (collectDayBells(), docs/firmware-api-v5.md):
     *
     * Packs are walked top to bottom. A base pack adds the bells of its modules
     * for this weekday. A correction applies when it is muted or has a module
     * for this weekday; it first drops every base bell collected so far, then
     * adds its own (unless muted). Bells sharing a minute ring once and belong
     * to the module that claimed the minute first.
     */
    fun resolveDay(date: LocalDate): List<ResolvedBell> {
        if (!auto.allows(date)) return emptyList()
        val minutes = arrayOfNulls<ResolvedBell>(MINUTES_PER_DAY)
        for (pack in packs) {
            if (!pack.appliesOn(date)) continue
            val correction = pack.kind == PackKind.Correction
            if (correction) {
                for (m in minutes.indices) {
                    if (minutes[m]?.fromCorrection == false) minutes[m] = null
                }
                if (pack.mute) continue
            }
            for (module in pack.modulesFor(date)) {
                for (bell in module.bells()) {
                    if (minutes[bell.minute] == null) {
                        minutes[bell.minute] = ResolvedBell(
                            minute = bell.minute,
                            role = bell.role,
                            module = module,
                            packId = pack.id,
                            fromCorrection = correction,
                        )
                    }
                }
            }
        }
        return minutes.filterNotNull()
    }

    fun dayKind(date: LocalDate): DayKind {
        if (!auto.allows(date)) return DayKind.None
        var kind = DayKind.None
        for (pack in packs) {
            if (!pack.appliesOn(date)) continue
            kind = when {
                pack.kind == PackKind.Correction && pack.mute -> DayKind.Muted
                pack.kind == PackKind.Correction -> DayKind.Correction
                kind == DayKind.None -> DayKind.Base
                else -> kind
            }
        }
        return kind
    }

    /** The next bell strictly after [after], scanning ahead [horizonDays] days. */
    fun nextBell(after: LocalDateTime, horizonDays: Int = 8): UpcomingBell? {
        var date = after.toLocalDate()
        repeat(horizonDays) {
            for (bell in resolveDay(date)) {
                val at = date.atTime(bell.minute / 60, bell.minute % 60)
                if (at.isAfter(after)) return UpcomingBell(at, bell)
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** The lesson whose start and end bells both ring today around [now]. */
    fun runningLesson(now: LocalDateTime): RunningLesson? {
        val bells = resolveDay(now.toLocalDate())
        val minute = now.hour * 60 + now.minute
        for (bell in bells) {
            if (bell.role != BellRole.Start) continue
            val end = bell.minute + bell.module.length
            if (minute !in bell.minute until end) continue
            val endRings = bells.any { it.minute == end && it.module == bell.module && it.role == BellRole.End }
            if (endRings) return RunningLesson(bell.module, bell.minute, end)
        }
        return null
    }
}

internal fun parseTimeToMinutes(value: String): Int? {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

fun minutesToTime(minutes: Int): String {
    val safe = minutes.coerceIn(0, LAST_MINUTE)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
