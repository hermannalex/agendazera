package br.com.agendazera.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import br.com.agendazera.bot.Schedule

object Status {
    const val CONFIRMADO = "CONFIRMADO"
    const val CANCELADO = "CANCELADO"
}

@Entity(tableName = "appointments", indices = [Index("startMillis"), Index("contactKey")])
data class Appointment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Nome do contato como aparece no WhatsApp (ou "Manual" se criado no app). */
    val contactKey: String,
    val patientName: String,
    val startMillis: Long,
    val durationMin: Int,
    val status: String = Status.CONFIRMADO,
    val source: String = "WHATSAPP",
    val createdAt: Long = System.currentTimeMillis(),
    /** ID do evento criado na agenda do celular (Google Agenda), se vinculada. */
    val calendarEventId: Long? = null,
    /** Profissional que atende (null só em dados muito antigos). */
    val professionalId: Long? = null,
    /** Nome do serviço escolhido (plano Pro), guardado como texto para o histórico. */
    val serviceName: String? = null,
) {
    val endMillis: Long get() = startMillis + durationMin * 60_000L
}

/** Quem atende: médico, dentista, cabeleireira… Cada um com sua agenda e horários. */
@Entity(tableName = "professionals")
data class Professional(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Especialidade ou função, ex.: "Dermatologia", "Manicure". */
    val specialty: String = "",
    val active: Boolean = true,
    /** Dias de atendimento: 1 = segunda … 7 = domingo, separados por vírgula. */
    val workDays: String = "1,2,3,4,5",
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 18 * 60,
    val lunchStart: Int = 12 * 60,
    val lunchEnd: Int = 13 * 60,
    /** Duração padrão do atendimento e intervalo entre horários oferecidos. */
    val slotMinutes: Int = 30,
    /** Agenda do celular (Google Agenda) deste profissional, -1 = nenhuma. */
    val calendarId: Long = -1,
    val calendarName: String = "",
    val calendarBlocks: Boolean = true,
    val sortOrder: Int = 0,
) {
    fun workDaySet(): Set<Int> = workDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun schedule() = Schedule(workDaySet(), startMinutes, endMinutes, lunchStart, lunchEnd, slotMinutes)

    /** Nome com a especialidade, para mostrar ao cliente. */
    fun label() = if (specialty.isBlank()) name else "$name ($specialty)"
}

/** Serviço com duração e preço próprios (plano Pro). Ex.: "Corte – 40 min – R$ 50". */
@Entity(tableName = "services")
data class Service(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val durationMin: Int,
    val priceCents: Int? = null,
    /** IDs dos profissionais que fazem o serviço, separados por vírgula. Vazio = todos. */
    val professionalIds: String = "",
    val active: Boolean = true,
) {
    fun professionalIdSet(): Set<Long> = professionalIds.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
    fun offeredBy(professionalId: Long) = professionalIdSet().let { it.isEmpty() || professionalId in it }
    fun priceLabel(): String? = priceCents?.let { "R$ %d,%02d".format(it / 100, it % 100) }
}

/** Em que ponto da conversa cada contato está. */
@Entity(tableName = "conversations")
data class ConversationState(
    @PrimaryKey val contactKey: String,
    val step: String = Step.MENU,
    val patientName: String? = null,
    /** Opções numeradas que o contato recebeu por último, separadas por "|". */
    val options: String = "",
    /** Horário escolhido (epoch ms) aguardando confirmação. */
    val pendingSlot: Long? = null,
    /** Enquanto for maior que agora, o robô fica em silêncio (atendente humano). */
    val pausedUntil: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Serviço escolhido (plano Pro). */
    val serviceId: Long? = null,
    /** Profissional escolhido. 0 = sem preferência. */
    val professionalId: Long? = null,
    /** Profissional do horário escolhido, aguardando confirmação. */
    val pendingProfessionalId: Long? = null,
) {
    fun optionList(): List<String> = if (options.isBlank()) emptyList() else options.split("|")
}

object Step {
    const val MENU = "MENU"
    const val ASK_NAME = "ASK_NAME"
    const val PICK_SERVICE = "PICK_SERVICE"
    const val PICK_PRO = "PICK_PRO"
    const val PICK_DAY = "PICK_DAY"
    const val PICK_TIME = "PICK_TIME"
    const val CONFIRM = "CONFIRM"
    const val PICK_CANCEL = "PICK_CANCEL"
}

/** 0 em ConversationState.professionalId significa "sem preferência". */
const val ANY_PROFESSIONAL = 0L
