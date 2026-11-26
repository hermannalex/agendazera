package br.com.agendazera.bot

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Intervalo ocupado [start, end) em epoch ms. */
data class Busy(val start: Long, val end: Long)

/** Horário de trabalho de um profissional. Minutos desde 00:00; dias 1 = segunda … 7 = domingo. */
data class Schedule(
    val workDays: Set<Int>,
    val startMinutes: Int,
    val endMinutes: Int,
    val lunchStart: Int,
    val lunchEnd: Int,
    /** De quanto em quanto tempo os horários são oferecidos (ex.: 30 = 08:00, 08:30…). */
    val stepMinutes: Int,
)

object SlotCalculator {

    /**
     * Horários livres (epoch ms de início) em que cabe um atendimento de [durationMin] minutos,
     * sem invadir o almoço, o fim do expediente, o passado ou outro compromisso.
     */
    fun freeSlots(
        date: LocalDate,
        schedule: Schedule,
        durationMin: Int,
        minLeadMinutes: Int,
        busy: List<Busy>,
        now: Instant,
        zone: ZoneId,
    ): List<Long> {
        if (date.dayOfWeek.value !in schedule.workDays || schedule.stepMinutes <= 0 || durationMin <= 0) return emptyList()
        val earliest = now.toEpochMilli() + minLeadMinutes * 60_000L
        val hasLunch = schedule.lunchStart < schedule.lunchEnd
        val result = mutableListOf<Long>()
        var m = schedule.startMinutes
        while (m + durationMin <= schedule.endMinutes) {
            val endM = m + durationMin
            val inLunch = hasLunch && m < schedule.lunchEnd && endM > schedule.lunchStart
            if (!inLunch) {
                val start = date.atTime(LocalTime.of(m / 60, m % 60)).atZone(zone).toInstant().toEpochMilli()
                val end = start + durationMin * 60_000L
                if (start >= earliest && busy.none { it.start < end && it.end > start }) result += start
            }
            m += schedule.stepMinutes
        }
        return result
    }

    /** Intervalo [início, fim) do dia em epoch ms. */
    fun dayRange(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
