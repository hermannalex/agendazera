package br.com.agendazera

import android.app.Application
import br.com.agendazera.billing.SubscriptionManager
import br.com.agendazera.bot.BotEngine
import br.com.agendazera.bot.BotStore
import br.com.agendazera.calendar.CalendarSync
import br.com.agendazera.data.AppDatabase
import br.com.agendazera.data.Appointment
import br.com.agendazera.data.AppointmentRepository
import br.com.agendazera.data.ConversationState
import br.com.agendazera.data.Professional
import br.com.agendazera.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var calendar: CalendarSync
        private set
    lateinit var appointments: AppointmentRepository
        private set
    lateinit var subscription: SubscriptionManager
        private set
    lateinit var bot: BotEngine
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        settings = SettingsRepository(this)
        calendar = CalendarSync(this)
        appointments = AppointmentRepository(db, settings, calendar)
        subscription = SubscriptionManager(this)
        bot = BotEngine(RoomBotStore(this))
        appScope.launch { appointments.ensureDefaultProfessional() }
    }
}

private class RoomBotStore(private val app: App) : BotStore {
    override suspend fun getState(key: String) = app.db.conversations().get(key)
    override suspend fun saveState(state: ConversationState) = app.db.conversations().upsert(state)

    override suspend fun professionals(): List<Professional> {
        val list = app.appointments.activeProfessionals()
        if (list.isNotEmpty()) return list
        app.appointments.ensureDefaultProfessional()
        return app.appointments.activeProfessionals()
    }

    override suspend fun services() = app.db.services().all().filter { it.active }
    override suspend fun busyFor(professionalId: Long, from: Long, to: Long) =
        app.appointments.busyFor(professionalId, from, to)
    override suspend fun insertAppointment(a: Appointment) = app.appointments.create(a)
    override suspend fun upcomingFor(key: String, now: Long) = app.db.appointments().upcomingFor(key, now)
    override suspend fun cancelAppointment(id: Long) = app.appointments.cancel(id)
    override fun settings() = app.settings.current
    override fun isPro() = app.subscription.isPro()
}
