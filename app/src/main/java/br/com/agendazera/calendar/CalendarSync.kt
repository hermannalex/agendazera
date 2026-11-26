package br.com.agendazera.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.core.content.ContextCompat
import br.com.agendazera.bot.Busy
import br.com.agendazera.data.Appointment
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Integração com a agenda do celular (CalendarContract).
 * Agendas da conta Google aparecem aqui e o próprio Android sincroniza com o Google Agenda,
 * sem precisar de login extra, chave de API ou servidor.
 */
class CalendarSync(private val context: Context) {

    data class CalendarInfo(val id: Long, val name: String, val account: String, val isGoogle: Boolean)

    fun hasPermission(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Agendas em que o app pode gravar. As do Google vêm primeiro. */
    fun listCalendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val result = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
        )
        try {
            context.contentResolver.query(
                Calendars.CONTENT_URI, projection,
                "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    result += CalendarInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "Agenda",
                        account = c.getString(2) ?: "",
                        isGoogle = c.getString(3) == "com.google",
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão de agenda", e)
        }
        return result.sortedWith(compareByDescending<CalendarInfo> { it.isGoogle }.thenBy { it.account })
    }

    /** Cria o evento da consulta e devolve o ID dele (ou null se falhar). */
    fun insertEvent(calendarId: Long, a: Appointment, zone: ZoneId): Long? {
        if (!hasPermission()) return null
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.DTSTART, a.startMillis)
            put(Events.DTEND, a.endMillis)
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.TITLE, (a.serviceName ?: "Consulta") + " – ${a.patientName}")
            put(
                Events.DESCRIPTION,
                if (a.source == "MANUAL") "Agendado no app Agendazera"
                else "Agendado pelo WhatsApp (contato: ${a.contactKey})",
            )
            put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        }
        return try {
            context.contentResolver.insert(Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao criar evento", e); null
        }
    }

    fun deleteEvent(eventId: Long) {
        if (!hasPermission()) return
        try {
            context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao apagar evento", e)
        }
    }

    /** Compromissos "ocupado" da agenda no intervalo, incluindo eventos de dia inteiro. */
    fun busyBetween(calendarId: Long, from: Long, to: Long, zone: ZoneId): List<Busy> {
        if (!hasPermission()) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.AVAILABILITY, Instances.STATUS)
        val result = mutableListOf<Busy>()
        try {
            context.contentResolver.query(
                uri, projection, "${Instances.CALENDAR_ID} = ?", arrayOf(calendarId.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) == Events.AVAILABILITY_FREE) continue
                    if (!c.isNull(4) && c.getInt(4) == Events.STATUS_CANCELED) continue
                    var begin = c.getLong(0)
                    var end = c.getLong(1)
                    if (c.getInt(2) == 1) {
                        // Dia inteiro: o Android guarda em UTC; converte para o dia local
                        begin = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                        end = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    if (end > begin) result += Busy(begin, end)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão de agenda", e)
        }
        return result
    }

    companion object {
        private const val TAG = "CalendarSync"
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}
