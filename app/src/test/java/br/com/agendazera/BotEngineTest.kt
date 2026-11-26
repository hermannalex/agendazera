package br.com.agendazera

import br.com.agendazera.bot.BotEngine
import br.com.agendazera.bot.BotStore
import br.com.agendazera.bot.Busy
import br.com.agendazera.bot.SlotCalculator
import br.com.agendazera.data.Appointment
import br.com.agendazera.data.BusinessType
import br.com.agendazera.data.ClinicSettings
import br.com.agendazera.data.ConversationState
import br.com.agendazera.data.Professional
import br.com.agendazera.data.Service
import br.com.agendazera.data.Status
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class FakeStore(
    var s: ClinicSettings = ClinicSettings(),
    var pro: Boolean = false,
    val pros: MutableList<Professional> = mutableListOf(Professional(id = 1, name = "Dra. Ana")),
    val svc: MutableList<Service> = mutableListOf(),
) : BotStore {
    val states = HashMap<String, ConversationState>()
    val appts = mutableListOf<Appointment>()
    override suspend fun getState(key: String) = states[key]
    override suspend fun saveState(state: ConversationState) { states[state.contactKey] = state }
    override suspend fun professionals() = pros.filter { it.active }
    override suspend fun services() = svc.filter { it.active }
    override suspend fun busyFor(professionalId: Long, from: Long, to: Long) = appts
        .filter { it.professionalId == professionalId && it.status == Status.CONFIRMADO && it.startMillis < to && it.endMillis > from }
        .map { Busy(it.startMillis, it.endMillis) }
    override suspend fun insertAppointment(a: Appointment): Long {
        val id = appts.size + 1L; appts += a.copy(id = id); return id
    }
    override suspend fun upcomingFor(key: String, now: Long) =
        appts.filter { it.contactKey == key && it.status == Status.CONFIRMADO && it.startMillis >= now }
    override suspend fun cancelAppointment(id: Long) {
        val i = appts.indexOfFirst { it.id == id }; appts[i] = appts[i].copy(status = Status.CANCELADO)
    }
    override fun settings() = s
    override fun isPro() = pro
}

class BotEngineTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    // Segunda-feira, 07:00
    private val now = ZonedDateTime.of(2026, 11, 16, 7, 0, 0, 0, zone).toInstant()
    private val monday = LocalDate.of(2026, 11, 16)

    @Test
    fun slots_skipLunchAndBusy() {
        val sched = Professional(name = "x").schedule()
        val all = SlotCalculator.freeSlots(monday, sched, 30, 0, emptyList(), now, zone)
        assertEquals(18, all.size) // 8–12 (8) + 13–18 (10)
        val busy = listOf(Busy(all[0], all[0] + 30 * 60_000L))
        assertEquals(17, SlotCalculator.freeSlots(monday, sched, 30, 0, busy, now, zone).size)
        // Domingo não atende
        assertTrue(SlotCalculator.freeSlots(monday.plusDays(6), sched, 30, 0, emptyList(), now, zone).isEmpty())
    }

    @Test
    fun slots_longServiceFitsBeforeLunchAndClose() {
        val sched = Professional(name = "x").schedule()
        // 90 min, de 30 em 30: manhã 08:00–10:30 (6), tarde 13:00–16:30 (8)
        assertEquals(14, SlotCalculator.freeSlots(monday, sched, 90, 0, emptyList(), now, zone).size)
    }

    @Test
    fun basicFlow_singleProfessional() = runTest {
        val store = FakeStore()
        val bot = BotEngine(store, { now }, zone)
        assertTrue(bot.handle("Maria", "oi")!!.contains("*1* - Agendar consulta"))
        assertTrue(bot.handle("Maria", "1")!!.contains("nome completo"))
        assertTrue(bot.handle("Maria", "maria da silva")!!.contains("Escolha o dia"))
        assertTrue(bot.handle("Maria", "1")!!.contains("Horários livres"))
        assertTrue(bot.handle("Maria", "1")!!.contains("Confirma"))
        assertTrue(bot.handle("Maria", "sim")!!.contains("confirmada"))
        assertEquals(1, store.appts.size)
        assertEquals("Maria Da Silva", store.appts[0].patientName)
        assertEquals(1L, store.appts[0].professionalId)

        assertTrue(bot.handle("Maria", "3")!!.contains("Qual consulta"))
        assertTrue(bot.handle("Maria", "1")!!.contains("cancelada"))
        assertEquals(Status.CANCELADO, store.appts[0].status)
    }

    @Test
    fun basicPlan_ignoresExtraProfessionalsAndServices() = runTest {
        val store = FakeStore(
            pros = mutableListOf(Professional(id = 1, name = "Ana"), Professional(id = 2, name = "Bia")),
            svc = mutableListOf(Service(id = 1, name = "Corte", durationMin = 60)),
        )
        val bot = BotEngine(store, { now }, zone)
        bot.handle("C", "oi"); bot.handle("C", "1")
        val reply = bot.handle("C", "Carla Souza")!!
        assertTrue(reply.contains("Escolha o dia")) // pulou serviço e profissional
    }

    @Test
    fun proFlow_serviceAndProfessional() = runTest {
        val store = FakeStore(
            pro = true,
            s = ClinicSettings(businessType = BusinessType.SALAO, minLeadMinutes = 0),
            pros = mutableListOf(
                Professional(id = 1, name = "Ana", specialty = "Cabelo"),
                Professional(id = 2, name = "Bia", specialty = "Unhas", startMinutes = 14 * 60, lunchStart = 0, lunchEnd = 0),
            ),
            svc = mutableListOf(
                Service(id = 10, name = "Corte", durationMin = 60, priceCents = 5000, professionalIds = "1"),
                Service(id = 11, name = "Manicure", durationMin = 45),
            ),
        )
        val bot = BotEngine(store, { now }, zone)
        assertTrue(bot.handle("C", "oi")!!.contains("Agendar horário"))
        assertTrue(bot.handle("C", "1")!!.contains("cliente"))
        val services = bot.handle("C", "Carla Souza")!!
        assertTrue(services.contains("Corte (60 min · R$ 50,00)"))

        // Manicure: as duas fazem → pergunta com quem
        val who = bot.handle("C", "2")!!
        assertTrue(who.contains("Bia (Unhas)") && who.contains("Sem preferência"))
        bot.handle("C", "2")                       // Bia
        val times = bot.handle("C", "1")!!         // segunda
        assertTrue(times.contains("14:00"))
        assertFalse(times.contains("08:00"))       // Bia só à tarde
        assertTrue(bot.handle("C", "1")!!.contains("*Profissional:* Bia (Unhas)"))
        assertTrue(bot.handle("C", "s")!!.contains("Horário confirmado"))
        val a = store.appts.single()
        assertEquals(2L, a.professionalId); assertEquals(45, a.durationMin); assertEquals("Manicure", a.serviceName)
    }

    @Test
    fun proFlow_anyProfessionalPicksFirstFree() = runTest {
        val store = FakeStore(
            pro = true,
            s = ClinicSettings(minLeadMinutes = 0),
            pros = mutableListOf(Professional(id = 1, name = "Ana"), Professional(id = 2, name = "Bia")),
        )
        // Ana ocupada às 08:00
        val eight = ZonedDateTime.of(2026, 11, 16, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        store.appts += Appointment(id = 99, contactKey = "x", patientName = "x", startMillis = eight, durationMin = 30, professionalId = 1)
        val bot = BotEngine(store, { now }, zone)
        bot.handle("D", "oi"); bot.handle("D", "1"); bot.handle("D", "Daniel Lima")
        bot.handle("D", "3")                       // sem preferência
        val times = bot.handle("D", "1")!!
        assertTrue(times.contains("*1* - 08:00 (Bia)"))
        bot.handle("D", "1"); bot.handle("D", "s")
        assertEquals(2L, store.appts.last().professionalId)
    }

    @Test
    fun humanHandoffSilencesBot() = runTest {
        val store = FakeStore()
        val bot = BotEngine(store, { now }, zone)
        bot.handle("João", "oi")
        bot.handle("João", "4")
        assertNull(bot.handle("João", "alguém aí?"))
    }
}
