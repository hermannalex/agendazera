package br.com.agendazera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.agendazera.App
import br.com.agendazera.bot.SlotCalculator
import br.com.agendazera.data.Appointment
import br.com.agendazera.data.Professional
import br.com.agendazera.data.Service
import br.com.agendazera.data.Status
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ptBR = Locale("pt", "BR")
private val dayTitleFmt = DateTimeFormatter.ofPattern("EEEE, dd 'de' MMMM", ptBR)
private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun AgendaScreen(app: App, listenerOn: Boolean, openPermission: () -> Unit, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val sub by app.subscription.state.collectAsStateWithLifecycle()
    val blockedByBilling = remember(sub) { !app.subscription.canUseBot() }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var showAdd by remember { mutableStateOf(false) }
    var proFilter by remember { mutableStateOf<Long?>(null) }
    val pros by remember { app.db.professionals().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val services by remember { app.db.services().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val proNames = pros.associate { it.id to it.name }

    val range = remember(day) { SlotCalculator.dayRange(day, zone) }
    val allAppointments by remember(range) { app.db.appointments().observeDay(range.first, range.second) }
        .collectAsStateWithLifecycle(emptyList())
    val appointments = proFilter?.let { id -> allAppointments.filter { it.professionalId == id } } ?: allAppointments
    val paused by remember { app.db.conversations().observePaused() }
        .collectAsStateWithLifecycle(emptyList())
    val now = System.currentTimeMillis()
    val waitingHuman = paused.filter { it.pausedUntil > now }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (!listenerOn) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("O robô ainda não pode ler o WhatsApp", fontWeight = FontWeight.Bold)
                            Text("Libere o acesso às notificações para o app \"Agendazera\".")
                            Spacer(Modifier.padding(4.dp))
                            Button(onClick = openPermission) { Text("Liberar acesso") }
                        }
                    }
                } else {
                    Card {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Robô do WhatsApp", fontWeight = FontWeight.Bold)
                                Text(if (settings.botEnabled) "Respondendo automaticamente" else "Desligado")
                            }
                            Switch(
                                checked = settings.botEnabled,
                                onCheckedChange = { app.settings.save(settings.copy(botEnabled = it)) },
                            )
                        }
                    }
                }
            }

            if (listenerOn && blockedByBilling) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Robô pausado: assinatura inativa", fontWeight = FontWeight.Bold)
                            Text("O teste grátis acabou. Escolha um plano em Configurações para o robô voltar a responder.")
                        }
                    }
                }
            }

            if (waitingHuman.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Aguardando atendente", fontWeight = FontWeight.Bold)
                            waitingHuman.forEach { c ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.contactKey, Modifier.weight(1f))
                                    TextButton(onClick = {
                                        scope.launch { app.db.conversations().resume(c.contactKey) }
                                    }) { Text("Devolver ao robô") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { day = day.minusDays(1) }) { Icon(Icons.Default.ChevronLeft, "Dia anterior") }
                    Text(
                        dayTitleFmt.format(day).replaceFirstChar { it.titlecase(ptBR) },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { day = LocalDate.now() }) { Text("Hoje") }
                    IconButton(onClick = { day = day.plusDays(1) }) { Icon(Icons.Default.ChevronRight, "Próximo dia") }
                }
            }

            if (pros.size > 1) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = proFilter == null, onClick = { proFilter = null }, label = { Text("Todos") })
                        pros.forEach { p ->
                            FilterChip(selected = proFilter == p.id, onClick = { proFilter = p.id }, label = { Text(p.name) })
                        }
                    }
                }
            }

            if (appointments.isEmpty()) {
                item { Text("Nenhuma consulta neste dia.", Modifier.padding(8.dp)) }
            }
            items(appointments, key = { it.id }) { a ->
                AppointmentCard(a, zone, if (pros.size > 1) a.professionalId?.let { proNames[it] } else null) {
                    scope.launch { app.appointments.cancel(a.id) }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Novo agendamento") }
    }

    if (showAdd) {
        AddAppointmentDialog(
            app, day, zone,
            pros = pros.filter { it.active },
            services = if (app.subscription.isPro()) services.filter { it.active } else emptyList(),
            preselectedPro = proFilter,
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun AppointmentCard(a: Appointment, zone: ZoneId, proName: String?, onCancel: () -> Unit) {
    val cancelled = a.status == Status.CANCELADO
    Card(
        colors = if (cancelled) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                hmFmt.format(Instant.ofEpochMilli(a.startMillis).atZone(zone)),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(a.patientName, fontWeight = FontWeight.Bold)
                val detail = listOfNotNull(a.serviceName, proName).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium)
                Text(
                    (if (a.source == "MANUAL") "Agendado no app" else "WhatsApp: ${a.contactKey}") +
                        if (cancelled) " · CANCELADO" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!cancelled) TextButton(onClick = onCancel) { Text("Cancelar") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddAppointmentDialog(
    app: App,
    day: LocalDate,
    zone: ZoneId,
    pros: List<Professional>,
    services: List<Service>,
    preselectedPro: Long?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy") }
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(dateFmt.format(day)) }
    var time by remember { mutableStateOf("") }
    var proId by remember { mutableStateOf(preselectedPro ?: pros.firstOrNull()?.id) }
    var serviceId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val pro = pros.firstOrNull { it.id == proId }
    val service = services.firstOrNull { it.id == serviceId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo agendamento") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Data (dd/mm/aaaa)") }, singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("Hora (hh:mm)") }, singleLine = true)
                if (pros.size > 1) {
                    Text("Profissional", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pros.forEach { p ->
                            FilterChip(selected = proId == p.id, onClick = { proId = p.id; serviceId = null }, label = { Text(p.name) })
                        }
                    }
                }
                val offered = services.filter { s -> proId?.let { s.offeredBy(it) } ?: true }
                if (offered.isNotEmpty()) {
                    Text("Serviço (opcional)", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        offered.forEach { s ->
                            FilterChip(
                                selected = serviceId == s.id,
                                onClick = { serviceId = if (serviceId == s.id) null else s.id },
                                label = { Text("${s.name} · ${s.durationMin} min") },
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = runCatching { LocalDate.parse(date.trim(), dateFmt) }.getOrNull()
                val t = runCatching { LocalTime.parse(time.trim(), hmFmt) }.getOrNull()
                when {
                    name.isBlank() -> error = "Informe o nome."
                    d == null -> error = "Data inválida."
                    t == null -> error = "Hora inválida."
                    pro == null -> error = "Cadastre um profissional na aba Equipe."
                    else -> scope.launch {
                        val duration = service?.durationMin ?: pro.slotMinutes
                        val start = d.atTime(t).atZone(zone).toInstant().toEpochMilli()
                        val end = start + duration * 60_000L
                        if (app.appointments.busyFor(pro.id, start, end).isNotEmpty()) {
                            error = "Horário ocupado (consulta ou compromisso na agenda)."
                        } else {
                            app.appointments.create(
                                Appointment(
                                    contactKey = "Manual",
                                    patientName = name.trim(),
                                    startMillis = start,
                                    durationMin = duration,
                                    source = "MANUAL",
                                    professionalId = pro.id,
                                    serviceName = service?.name,
                                )
                            )
                            onDismiss()
                        }
                    }
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
