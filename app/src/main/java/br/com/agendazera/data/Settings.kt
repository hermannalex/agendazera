package br.com.agendazera.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tipo de negócio: muda as palavras que o robô usa. */
object BusinessType {
    const val CLINICA = "CLINICA"   // paciente / consulta
    const val SALAO = "SALAO"       // cliente / horário
}

/**
 * Configurações gerais. Horários em minutos desde 00:00 (ex.: 8h = 480). Dias: 1 = segunda … 7 = domingo.
 * Os campos de horário e agenda daqui só são usados para criar o primeiro profissional
 * (versões antigas do app); hoje cada profissional tem os seus.
 */
data class ClinicSettings(
    val clinicName: String = "Clínica",
    val address: String = "",
    val businessType: String = BusinessType.CLINICA,
    val botEnabled: Boolean = true,
    val workDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 18 * 60,
    val lunchStart: Int = 12 * 60,
    val lunchEnd: Int = 13 * 60,
    val slotMinutes: Int = 30,
    val daysAhead: Int = 14,
    val minLeadMinutes: Int = 60,
    val humanPauseMinutes: Int = 120,
    /** Agenda do celular vinculada (-1 = nenhuma). */
    val calendarId: Long = -1,
    val calendarName: String = "",
    /** Compromissos da agenda vinculada bloqueiam horários do robô. */
    val calendarBlocks: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("clinic_settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<ClinicSettings> = _flow
    val current: ClinicSettings get() = _flow.value

    private fun load(): ClinicSettings {
        val d = ClinicSettings()
        return ClinicSettings(
            clinicName = prefs.getString("clinicName", d.clinicName)!!,
            address = prefs.getString("address", d.address)!!,
            businessType = prefs.getString("businessType", d.businessType)!!,
            botEnabled = prefs.getBoolean("botEnabled", d.botEnabled),
            workDays = prefs.getString("workDays", null)
                ?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.workDays,
            startMinutes = prefs.getInt("startMinutes", d.startMinutes),
            endMinutes = prefs.getInt("endMinutes", d.endMinutes),
            lunchStart = prefs.getInt("lunchStart", d.lunchStart),
            lunchEnd = prefs.getInt("lunchEnd", d.lunchEnd),
            slotMinutes = prefs.getInt("slotMinutes", d.slotMinutes),
            daysAhead = prefs.getInt("daysAhead", d.daysAhead),
            minLeadMinutes = prefs.getInt("minLeadMinutes", d.minLeadMinutes),
            humanPauseMinutes = prefs.getInt("humanPauseMinutes", d.humanPauseMinutes),
            calendarId = prefs.getLong("calendarId", d.calendarId),
            calendarName = prefs.getString("calendarName", d.calendarName)!!,
            calendarBlocks = prefs.getBoolean("calendarBlocks", d.calendarBlocks),
        )
    }

    fun save(s: ClinicSettings) {
        prefs.edit()
            .putString("clinicName", s.clinicName)
            .putString("address", s.address)
            .putString("businessType", s.businessType)
            .putBoolean("botEnabled", s.botEnabled)
            .putString("workDays", s.workDays.sorted().joinToString(","))
            .putInt("startMinutes", s.startMinutes)
            .putInt("endMinutes", s.endMinutes)
            .putInt("lunchStart", s.lunchStart)
            .putInt("lunchEnd", s.lunchEnd)
            .putInt("slotMinutes", s.slotMinutes)
            .putInt("daysAhead", s.daysAhead)
            .putInt("minLeadMinutes", s.minLeadMinutes)
            .putInt("humanPauseMinutes", s.humanPauseMinutes)
            .putLong("calendarId", s.calendarId)
            .putString("calendarName", s.calendarName)
            .putBoolean("calendarBlocks", s.calendarBlocks)
            .apply()
        _flow.value = s
    }
}
