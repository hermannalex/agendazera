package br.com.agendazera.data

import br.com.agendazera.bot.Busy
import br.com.agendazera.calendar.CalendarSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Único ponto para criar e cancelar consultas e para cuidar da equipe.
 * Mantém o banco e a agenda Google de cada profissional em sincronia.
 */
class AppointmentRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val calendar: CalendarSync,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val dao get() = db.appointments()
    private val pros get() = db.professionals()

    /**
     * Garante que exista ao menos um profissional. Na primeira vez, cria a partir das
     * configurações antigas (horários e agenda Google) e passa as consultas antigas para ele.
     */
    suspend fun ensureDefaultProfessional() = withContext(Dispatchers.IO) {
        if (pros.count() == 0) {
            val s = settings.current
            val id = pros.insert(
                Professional(
                    name = s.clinicName.ifBlank { "Atendimento" },
                    workDays = s.workDays.sorted().joinToString(","),
                    startMinutes = s.startMinutes, endMinutes = s.endMinutes,
                    lunchStart = s.lunchStart, lunchEnd = s.lunchEnd,
                    slotMinutes = s.slotMinutes,
                    calendarId = s.calendarId, calendarName = s.calendarName, calendarBlocks = s.calendarBlocks,
                )
            )
            dao.assignOrphans(id)
        } else {
            pros.all().firstOrNull()?.let { dao.assignOrphans(it.id) }
        }
    }

    suspend fun activeProfessionals(): List<Professional> = withContext(Dispatchers.IO) {
        pros.all().filter { it.active }
    }

    suspend fun saveProfessional(p: Professional): Long = withContext(Dispatchers.IO) {
        if (p.id == 0L) pros.insert(p.copy(sortOrder = pros.count())) else { pros.update(p); p.id }
    }

    /** Remove o profissional. Se ele já tem consultas, só desativa para não perder o histórico. */
    suspend fun removeProfessional(p: Professional): Boolean = withContext(Dispatchers.IO) {
        if (dao.countFor(p.id) > 0) { pros.update(p.copy(active = false)); false } else { pros.delete(p); true }
    }

    suspend fun create(a: Appointment): Long = withContext(Dispatchers.IO) {
        val id = dao.insert(a)
        pushToCalendar(a.copy(id = id))
        id
    }

    suspend fun cancel(id: Long) {
        withContext(Dispatchers.IO) {
            val a = dao.getById(id) ?: return@withContext
            dao.setStatus(id, Status.CANCELADO)
            val eventId = a.calendarEventId
            if (eventId != null) {
                calendar.deleteEvent(eventId)
                dao.setEventId(id, null)
            }
        }
    }

    /** Horários ocupados de um profissional: consultas dele + compromissos da agenda Google dele. */
    suspend fun busyFor(professionalId: Long, from: Long, to: Long): List<Busy> = withContext(Dispatchers.IO) {
        val own = dao.activeBetweenFor(professionalId, from, to).map { Busy(it.startMillis, it.endMillis) }
        val p = pros.getById(professionalId)
        val external = if (p != null && p.calendarId > 0 && p.calendarBlocks)
            calendar.busyBetween(p.calendarId, from, to, zone) else emptyList()
        own + external
    }

    /** Envia para a agenda do profissional as consultas futuras que ainda não estão lá. */
    suspend fun syncFuture(professionalId: Long): Int = withContext(Dispatchers.IO) {
        dao.futureWithoutEvent(professionalId, System.currentTimeMillis()).count { pushToCalendar(it) }
    }

    private suspend fun pushToCalendar(a: Appointment): Boolean {
        val p = a.professionalId?.let { pros.getById(it) } ?: return false
        if (p.calendarId <= 0) return false
        val eventId = calendar.insertEvent(p.calendarId, a, zone) ?: return false
        dao.setEventId(a.id, eventId)
        return true
    }
}
