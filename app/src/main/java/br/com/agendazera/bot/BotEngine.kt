package br.com.agendazera.bot

import br.com.agendazera.data.ANY_PROFESSIONAL
import br.com.agendazera.data.Appointment
import br.com.agendazera.data.BusinessType
import br.com.agendazera.data.ClinicSettings
import br.com.agendazera.data.ConversationState
import br.com.agendazera.data.Professional
import br.com.agendazera.data.Service
import br.com.agendazera.data.Step
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** O que o robô precisa do armazenamento — permite testar sem Android. */
interface BotStore {
    suspend fun getState(key: String): ConversationState?
    suspend fun saveState(state: ConversationState)
    /** Profissionais ativos, na ordem da equipe. */
    suspend fun professionals(): List<Professional>
    /** Serviços ativos. */
    suspend fun services(): List<Service>
    suspend fun busyFor(professionalId: Long, from: Long, to: Long): List<Busy>
    suspend fun insertAppointment(a: Appointment): Long
    suspend fun upcomingFor(key: String, now: Long): List<Appointment>
    suspend fun cancelAppointment(id: Long)
    fun settings(): ClinicSettings
    /** Plano Pro (ou teste grátis): vários profissionais e serviços. */
    fun isPro(): Boolean
}

/**
 * Robô de atendimento por menu numérico.
 * Recebe a mensagem do contato e devolve o texto da resposta (ou null para ficar em silêncio).
 */
class BotEngine(
    private val store: BotStore,
    private val clock: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val locale = Locale("pt", "BR")
    private val dayFmt = DateTimeFormatter.ofPattern("EEE dd/MM", locale)
    private val fullFmt = DateTimeFormatter.ofPattern("EEEE, dd/MM 'às' HH:mm", locale)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", locale)

    /** Depois desse tempo sem mensagens, a conversa recomeça do menu. */
    private val sessionTimeoutMs = 2 * 60 * 60 * 1000L

    /** Palavras conforme o tipo de negócio. */
    private class Words(val client: String, val booking: String, val bookings: String, val a: String)

    private fun words(): Words =
        if (store.settings().businessType == BusinessType.SALAO) Words("cliente", "horário", "horários", "o")
        else Words("paciente", "consulta", "consultas", "a")

    suspend fun handle(contactKey: String, rawText: String): String? {
        val now = clock().toEpochMilli()
        val text = normalize(rawText)
        val saved = store.getState(contactKey)

        // Atendente humano assumiu: robô não responde
        if (saved != null && saved.pausedUntil > now) return null

        val expired = saved == null || now - saved.updatedAt > sessionTimeoutMs || saved.pausedUntil != 0L
        if (expired) {
            val fresh = ConversationState(contactKey, Step.MENU, patientName = saved?.patientName, updatedAt = now)
            store.saveState(fresh)
            // Se a primeira mensagem já for uma opção do menu, atende direto
            if (text in setOf("1", "2", "3", "4")) return onMenu(fresh, text, now)
            return greeting() + "\n\n" + menuText()
        }

        val state = saved!!
        if (text in setOf("menu", "0", "inicio", "voltar", "oi", "ola")) {
            store.saveState(resetFlow(state, now))
            return menuText()
        }

        return when (state.step) {
            Step.MENU -> onMenu(state, text, now)
            Step.ASK_NAME -> onName(state, rawText.trim(), text, now)
            Step.PICK_SERVICE -> onPickService(state, text, now)
            Step.PICK_PRO -> onPickPro(state, text, now)
            Step.PICK_DAY -> onPickDay(state, text, now)
            Step.PICK_TIME -> onPickTime(state, text, now)
            Step.CONFIRM -> onConfirm(state, text, now)
            Step.PICK_CANCEL -> onPickCancel(state, text, now)
            else -> menuText()
        }
    }

    private fun resetFlow(state: ConversationState, now: Long) = state.copy(
        step = Step.MENU, options = "", pendingSlot = null, pendingProfessionalId = null,
        serviceId = null, professionalId = null, updatedAt = now,
    )

    // ---------- menu ----------

    private suspend fun onMenu(state: ConversationState, text: String, now: Long): String {
        val w = words()
        return when {
            text == "1" || "agend" in text || "marcar" in text -> {
                val name = state.patientName
                store.saveState(resetFlow(state, now).copy(step = Step.ASK_NAME))
                if (name != null)
                    "O agendamento é para *$name*?\nResponda *1* para confirmar ou escreva o nome completo de outr${w.a} ${w.client}."
                else
                    "Ótimo! Qual o *nome completo* d${w.a} ${w.client}?"
            }
            text == "2" -> {
                store.saveState(state.copy(updatedAt = now))
                myAppointments(state.contactKey, now)
            }
            text == "3" || "cancel" in text || "desmarc" in text -> offerCancel(state, now)
            text == "4" || "atendente" in text || "humano" in text -> {
                val s = store.settings()
                store.saveState(state.copy(step = Step.MENU, pausedUntil = now + s.humanPauseMinutes * 60_000L, updatedAt = now))
                "Certo! Um atendente vai continuar a conversa com você em breve. 🙂"
            }
            else -> "Desculpe, não entendi.\n\n" + menuText()
        }
    }

    // ---------- agendamento ----------

    private suspend fun onName(state: ConversationState, original: String, text: String, now: Long): String {
        val known = state.patientName
        val name = if (text == "1" && known != null) known else original
        if (name.length < 3 || name.all { it.isDigit() }) return "Por favor, digite o *nome completo* d${words().a} ${words().client}."
        val withName = state.copy(patientName = name.toTitle())
        val services = if (store.isPro()) store.services() else emptyList()
        return if (services.isNotEmpty()) offerServices(withName, services, now) else afterService(withName, now)
    }

    private suspend fun offerServices(state: ConversationState, services: List<Service>, now: Long): String {
        store.saveState(state.copy(step = Step.PICK_SERVICE, options = services.joinToString("|") { it.id.toString() }, updatedAt = now))
        val lines = services.mapIndexed { i, s ->
            val extra = listOfNotNull("${s.durationMin} min", s.priceLabel()).joinToString(" · ")
            "*${i + 1}* - ${s.name} ($extra)"
        }
        return "Qual serviço você deseja?\n" + lines.joinToString("\n") + "\n\n_0 para voltar ao menu_"
    }

    private suspend fun onPickService(state: ConversationState, text: String, now: Long): String {
        val opts = state.optionList()
        val idx = text.toIntOrNull()?.minus(1)
        if (idx == null || idx !in opts.indices) return "Digite apenas o *número* do serviço (1 a ${opts.size})."
        return afterService(state.copy(serviceId = opts[idx].toLong()), now)
    }

    /** Depois do serviço: escolhe o profissional (se houver mais de um) e segue para os dias. */
    private suspend fun afterService(state: ConversationState, now: Long): String {
        val pros = eligiblePros(state)
        if (pros.isEmpty()) {
            store.saveState(resetFlow(state, now))
            return "No momento não há profissionais disponíveis para esse atendimento. 😕\nDigite *4* para falar com um atendente."
        }
        if (pros.size == 1) return offerDays(state.copy(professionalId = pros[0].id), now)

        // Opções: cada profissional + "sem preferência"
        val ids = pros.map { it.id.toString() } + ANY_PROFESSIONAL.toString()
        store.saveState(state.copy(step = Step.PICK_PRO, options = ids.joinToString("|"), updatedAt = now))
        val lines = pros.mapIndexed { i, p -> "*${i + 1}* - ${p.label()}" } +
            "*${pros.size + 1}* - Sem preferência (primeiro horário livre)"
        return "Com quem você prefere?\n" + lines.joinToString("\n") + "\n\n_0 para voltar ao menu_"
    }

    private suspend fun onPickPro(state: ConversationState, text: String, now: Long): String {
        val opts = state.optionList()
        val idx = text.toIntOrNull()?.minus(1)
        if (idx == null || idx !in opts.indices) return "Digite apenas o *número* da opção (1 a ${opts.size})."
        return offerDays(state.copy(professionalId = opts[idx].toLong()), now)
    }

    private suspend fun offerDays(state: ConversationState, now: Long): String {
        val s = store.settings()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var d = today
        val last = today.plusDays(s.daysAhead.toLong())
        while (!d.isAfter(last) && days.size < 7) {
            if (slotsOnDay(state, d, now).isNotEmpty()) days += d
            d = d.plusDays(1)
        }
        if (days.isEmpty()) {
            store.saveState(resetFlow(state, now))
            return "No momento não há horários livres nos próximos ${s.daysAhead} dias. 😕\nDigite *4* para falar com um atendente."
        }
        store.saveState(state.copy(step = Step.PICK_DAY, options = days.joinToString("|"), updatedAt = now))
        val lines = days.mapIndexed { i, day -> "*${i + 1}* - ${dayFmt.format(day).cap()}" }
        return "Escolha o dia:\n" + lines.joinToString("\n") + "\n\n_0 para voltar ao menu_"
    }

    private suspend fun onPickDay(state: ConversationState, text: String, now: Long): String {
        val opts = state.optionList()
        val idx = text.toIntOrNull()?.minus(1)
        if (idx == null || idx !in opts.indices) return "Digite apenas o *número* do dia (1 a ${opts.size})."
        val day = LocalDate.parse(opts[idx])
        val slots = slotsOnDay(state, day, now)
        if (slots.isEmpty()) return "Esse dia acabou de lotar. " + offerDays(state, now)

        val showPro = state.professionalId == ANY_PROFESSIONAL
        val names = if (showPro) store.professionals().associate { it.id to it.name } else emptyMap()
        store.saveState(state.copy(
            step = Step.PICK_TIME,
            options = slots.joinToString("|") { "${it.first}:${it.second}" },
            updatedAt = now,
        ))
        val lines = slots.mapIndexed { i, (ms, proId) ->
            val time = timeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
            if (showPro) "*${i + 1}* - $time (${names[proId] ?: ""})" else "*${i + 1}* - $time"
        }
        return "Horários livres em *${dayFmt.format(day).cap()}*:\n" + lines.joinToString("\n") + "\n\n_0 para voltar ao menu_"
    }

    private suspend fun onPickTime(state: ConversationState, text: String, now: Long): String {
        val opts = state.optionList()
        val idx = text.toIntOrNull()?.minus(1)
        if (idx == null || idx !in opts.indices) return "Digite apenas o *número* do horário (1 a ${opts.size})."
        val (slot, proId) = opts[idx].split(":").let { it[0].toLong() to it[1].toLong() }
        store.saveState(state.copy(step = Step.CONFIRM, pendingSlot = slot, pendingProfessionalId = proId, updatedAt = now))
        return "Confirma?\n\n" + summary(state.patientName, slot, findPro(proId), findService(state.serviceId)) +
            "\n\nResponda *S* para confirmar ou *N* para escolher outro horário."
    }

    private suspend fun onConfirm(state: ConversationState, text: String, now: Long): String {
        val slot = state.pendingSlot
        val pro = state.pendingProfessionalId?.let { findPro(it) }
        if (slot == null || pro == null) return offerDays(state, now)
        val w = words()
        return when {
            text.startsWith("s") -> {
                val service = findService(state.serviceId)
                val duration = service?.durationMin ?: pro.slotMinutes
                val end = slot + duration * 60_000L
                if (slot < now || store.busyFor(pro.id, slot, end).isNotEmpty()) {
                    return "Que pena, esse horário acabou de ser ocupado. " + offerDays(state, now)
                }
                store.insertAppointment(
                    Appointment(
                        contactKey = state.contactKey,
                        patientName = state.patientName ?: state.contactKey,
                        startMillis = slot,
                        durationMin = duration,
                        professionalId = pro.id,
                        serviceName = service?.name,
                    )
                )
                store.saveState(resetFlow(state, now))
                val s = store.settings()
                buildString {
                    append("✅ ${w.booking.cap()} confirmad${w.a}!\n\n")
                    append(summary(state.patientName, slot, pro, service))
                    if (s.address.isNotBlank()) append("\n*Local:* ${s.address}")
                    append("\n\nSe precisar remarcar ou cancelar, é só mandar *menu*.")
                }
            }
            text.startsWith("n") -> offerDays(state.copy(pendingSlot = null, pendingProfessionalId = null), now)
            else -> "Responda *S* para confirmar ou *N* para escolher outro horário."
        }
    }

    // ---------- consultar / cancelar ----------

    private suspend fun offerCancel(state: ConversationState, now: Long): String {
        val w = words()
        val list = store.upcomingFor(state.contactKey, now)
        if (list.isEmpty()) {
            store.saveState(state.copy(step = Step.MENU, updatedAt = now))
            return "Você não tem ${w.bookings} agendad${w.a}s. Digite *1* para agendar."
        }
        val names = proNames()
        store.saveState(state.copy(step = Step.PICK_CANCEL, options = list.joinToString("|") { it.id.toString() }, updatedAt = now))
        val lines = list.mapIndexed { i, a -> "*${i + 1}* - ${describe(a, names)}" }
        return "Qual ${w.booking} deseja cancelar?\n" + lines.joinToString("\n") + "\n\n_0 para voltar ao menu_"
    }

    private suspend fun onPickCancel(state: ConversationState, text: String, now: Long): String {
        val opts = state.optionList()
        val idx = text.toIntOrNull()?.minus(1)
        if (idx == null || idx !in opts.indices) return "Digite apenas o *número* (1 a ${opts.size})."
        store.cancelAppointment(opts[idx].toLong())
        store.saveState(state.copy(step = Step.MENU, options = "", updatedAt = now))
        val w = words()
        return "${w.booking.cap()} cancelad${w.a}. ❌\nSe quiser marcar outro horário, digite *1*."
    }

    private suspend fun myAppointments(key: String, now: Long): String {
        val w = words()
        val list = store.upcomingFor(key, now)
        if (list.isEmpty()) return "Você não tem ${w.bookings} agendad${w.a}s. Digite *1* para agendar."
        val names = proNames()
        return "Seus próximos agendamentos:\n" +
            list.joinToString("\n") { "• ${describe(it, names)}" } +
            "\n\nDigite *3* para cancelar ou *menu* para voltar."
    }

    // ---------- auxiliares ----------

    /** Profissionais que podem atender este pedido (no Basic, só o primeiro). */
    private suspend fun eligiblePros(state: ConversationState): List<Professional> {
        var pros = store.professionals()
        if (!store.isPro()) pros = pros.take(1)
        val service = findService(state.serviceId)
        if (service != null) pros = pros.filter { service.offeredBy(it.id) }
        return pros
    }

    /** Horários livres no dia: lista de (início, profissional), sem repetir o mesmo horário. */
    private suspend fun slotsOnDay(state: ConversationState, day: LocalDate, now: Long): List<Pair<Long, Long>> {
        val eligible = eligiblePros(state)
        val chosen = state.professionalId
        val candidates = if (chosen == null || chosen == ANY_PROFESSIONAL) eligible else eligible.filter { it.id == chosen }
        val service = findService(state.serviceId)
        val s = store.settings()
        val (from, to) = SlotCalculator.dayRange(day, zone)
        val result = sortedMapOf<Long, Long>()
        for (p in candidates) {
            val duration = service?.durationMin ?: p.slotMinutes
            val slots = SlotCalculator.freeSlots(
                day, p.schedule(), duration, s.minLeadMinutes, store.busyFor(p.id, from, to), Instant.ofEpochMilli(now), zone,
            )
            for (ms in slots) if (ms !in result) result[ms] = p.id
        }
        return result.map { it.key to it.value }
    }

    private suspend fun findPro(id: Long): Professional? = store.professionals().firstOrNull { it.id == id }
    private suspend fun findService(id: Long?): Service? = id?.let { sid -> store.services().firstOrNull { it.id == sid } }
    private suspend fun proNames(): Map<Long, String> =
        if (store.isPro()) store.professionals().associate { it.id to it.name } else emptyMap()

    private fun summary(patient: String?, slot: Long, pro: Professional?, service: Service?): String {
        val w = words()
        return buildString {
            append("*${w.client.cap()}:* $patient\n")
            if (service != null) append("*Serviço:* ${service.name}${service.priceLabel()?.let { " ($it)" } ?: ""}\n")
            if (pro != null && store.isPro()) append("*Profissional:* ${pro.label()}\n")
            append("*Data:* ${fmtFull(slot)}")
        }
    }

    private fun describe(a: Appointment, names: Map<Long, String>): String = buildString {
        append(fmtFull(a.startMillis))
        a.serviceName?.let { append(" · $it") }
        a.professionalId?.let { id -> names[id]?.let { append(" com $it") } }
        append(" — ${a.patientName}")
    }

    private fun greeting(): String = "Olá! 👋 Você está falando com o atendimento automático da *${store.settings().clinicName}*."

    fun menuText(): String {
        val w = words()
        return "Como posso ajudar? Responda com o número:\n" +
            "*1* - Agendar ${w.booking}\n" +
            "*2* - Meus agendamentos\n" +
            "*3* - Cancelar ${w.booking}\n" +
            "*4* - Falar com atendente"
    }

    private fun fmtFull(ms: Long) = fullFmt.format(Instant.ofEpochMilli(ms).atZone(zone)).cap()

    private fun String.cap() = replaceFirstChar { it.titlecase(locale) }

    private fun String.toTitle() = split(" ").filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.lowercase(locale).replaceFirstChar { it.titlecase(locale) } }

    companion object {
        fun normalize(s: String): String =
            Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .trim(' ', '.', '!', '?')
    }
}
