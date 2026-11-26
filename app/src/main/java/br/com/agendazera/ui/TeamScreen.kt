package br.com.agendazera.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.agendazera.App
import br.com.agendazera.data.Professional
import br.com.agendazera.data.Service
import kotlinx.coroutines.launch

val DAY_LABELS = listOf(1 to "Seg", 2 to "Ter", 3 to "Qua", 4 to "Qui", 5 to "Sex", 6 to "Sáb", 7 to "Dom")

/** Ex.: "Seg–Sex · 08:00–18:00 · 30 min". */
fun Professional.scheduleSummary(): String {
    val days = workDaySet().sorted()
    val dayText = when {
        days.isEmpty() -> "Sem dias"
        days == (days.first()..days.last()).toList() && days.size > 2 ->
            "${DAY_LABELS[days.first() - 1].second}–${DAY_LABELS[days.last() - 1].second}"
        else -> days.joinToString(", ") { DAY_LABELS[it - 1].second }
    }
    return "$dayText · ${fmtHm(startMinutes)}–${fmtHm(endMinutes)} · $slotMinutes min"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(app: App, onOpenPlans: () -> Unit, modifier: Modifier = Modifier) {
    val pros by remember { app.db.professionals().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val services by remember { app.db.services().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val sub by app.subscription.state.collectAsStateWithLifecycle()
    val isPro = remember(sub) { app.subscription.isPro() }
    var editingPro by remember { mutableStateOf<Professional?>(null) }
    var editingService by remember { mutableStateOf<Service?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Profissionais", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cada profissional tem seus dias, horários, duração de atendimento e agenda Google.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(pros, key = { "p${it.id}" }) { p ->
            val index = pros.indexOf(p)
            Card(onClick = { editingPro = p }) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(p.label(), fontWeight = FontWeight.Bold)
                    Text(p.scheduleSummary(), style = MaterialTheme.typography.bodySmall)
                    if (p.calendarId > 0) Text("Agenda Google: ${p.calendarName}", style = MaterialTheme.typography.bodySmall)
                    when {
                        !p.active -> Text("Inativo (não aparece no robô)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        !isPro && index > 0 -> Text("No plano Basic só o primeiro profissional atende pelo robô.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            if (isPro || pros.isEmpty()) {
                OutlinedButton(onClick = { editingPro = Professional(name = "") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Adicionar profissional")
                }
            } else {
                ProUpsell(
                    "Adicione vários médicos, dentistas, cabeleireiros ou manicures, cada um com sua agenda e horários.",
                    onOpenPlans,
                )
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Serviços", style = MaterialTheme.typography.titleMedium)
            Text(
                "O cliente escolhe o serviço no WhatsApp, e o robô reserva o tempo certo para ele.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (isPro) {
            items(services, key = { "s${it.id}" }) { s ->
                Card(onClick = { editingService = s }) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(s.name + if (!s.active) " (inativo)" else "", fontWeight = FontWeight.Bold)
                        val ids = s.professionalIdSet()
                        val who = if (ids.isEmpty()) "Todos os profissionais"
                        else pros.filter { it.id in ids }.joinToString(", ") { it.name }
                        Text(listOfNotNull("${s.durationMin} min", s.priceLabel(), who).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                OutlinedButton(onClick = { editingService = Service(name = "", durationMin = 30) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Adicionar serviço")
                }
            }
        } else {
            item { ProUpsell("Cadastre serviços com duração e preço, como consulta 30 min ou corte + escova 90 min.", onOpenPlans) }
        }
    }

    editingPro?.let { ProfessionalEditor(app, it) { editingPro = null } }
    editingService?.let { ServiceEditor(app, it, pros) { editingService = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfessionalEditor(app: App, original: Professional, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(original.name) }
    var specialty by remember { mutableStateOf(original.specialty) }
    var days by remember { mutableStateOf(original.workDaySet()) }
    var start by remember { mutableStateOf(fmtHm(original.startMinutes)) }
    var end by remember { mutableStateOf(fmtHm(original.endMinutes)) }
    val hasLunch = original.lunchStart < original.lunchEnd
    var lunchStart by remember { mutableStateOf(if (hasLunch) fmtHm(original.lunchStart) else "") }
    var lunchEnd by remember { mutableStateOf(if (hasLunch) fmtHm(original.lunchEnd) else "") }
    var slot by remember { mutableStateOf(original.slotMinutes.toString()) }
    var active by remember { mutableStateOf(original.active) }
    var calId by remember { mutableStateOf(original.calendarId) }
    var calName by remember { mutableStateOf(original.calendarName) }
    var calBlocks by remember { mutableStateOf(original.calendarBlocks) }
    var error by remember { mutableStateOf<String?>(null) }
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (original.id == 0L) "Novo profissional" else "Editar profissional", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nome (ex.: Dra. Ana)") }, singleLine = true)
                OutlinedTextField(specialty, { specialty = it }, Modifier.fillMaxWidth(), label = { Text("Especialidade ou função (opcional)") }, singleLine = true)

                Text("Dias de atendimento", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_LABELS.forEach { (d, label) ->
                        FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(label) })
                    }
                }
                Text("Horários", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Início") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("Fim") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lunchStart, { lunchStart = it }, Modifier.weight(1f), label = { Text("Intervalo início") }, singleLine = true)
                    OutlinedTextField(lunchEnd, { lunchEnd = it }, Modifier.weight(1f), label = { Text("Intervalo fim") }, singleLine = true)
                }
                OutlinedTextField(
                    slot, { slot = it }, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = number,
                    label = { Text("Duração do atendimento (min)") },
                    supportingText = { Text("Também define de quanto em quanto tempo os horários são oferecidos.") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ativo (aparece no robô)", Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                HorizontalDivider()
                CalendarLinkSection(app, calId, calName, calBlocks, onChange = { id, n, b -> calId = id; calName = n; calBlocks = b })

                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (original.id != 0L) {
                        TextButton(onClick = {
                            scope.launch {
                                val deleted = app.appointments.removeProfessional(original)
                                Toast.makeText(
                                    ctx,
                                    if (deleted) "Profissional removido." else "Tem consultas no histórico: foi desativado.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                            }
                        }) { Text("Remover", color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Button(onClick = {
                        val st = parseHm(start); val en = parseHm(end)
                        val ls = if (lunchStart.isBlank()) 0 else parseHm(lunchStart)
                        val le = if (lunchEnd.isBlank()) 0 else parseHm(lunchEnd)
                        val sl = slot.toIntOrNull()
                        error = when {
                            name.isBlank() -> "Informe o nome."
                            days.isEmpty() -> "Escolha ao menos um dia."
                            st == null || en == null || st >= en -> "Horário de início/fim inválido."
                            ls == null || le == null -> "Intervalo inválido (deixe em branco se não houver)."
                            sl == null || sl < 5 -> "Duração inválida."
                            else -> null
                        }
                        if (error == null) scope.launch {
                            val saved = original.copy(
                                name = name.trim(), specialty = specialty.trim(), active = active,
                                workDays = days.sorted().joinToString(","),
                                startMinutes = st!!, endMinutes = en!!, lunchStart = ls!!, lunchEnd = le!!, slotMinutes = sl!!,
                                calendarId = calId, calendarName = calName, calendarBlocks = calBlocks,
                            )
                            val id = app.appointments.saveProfessional(saved)
                            if (calId > 0 && calId != original.calendarId) {
                                val n = app.appointments.syncFuture(id)
                                if (n > 0) Toast.makeText(ctx, "$n consulta(s) enviada(s) para a agenda.", Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        }
                    }) { Text("Salvar") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceEditor(app: App, original: Service, pros: List<Professional>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(original.name) }
    var duration by remember { mutableStateOf(original.durationMin.toString()) }
    var price by remember { mutableStateOf(original.priceCents?.let { "%d,%02d".format(it / 100, it % 100) } ?: "") }
    var selected by remember { mutableStateOf(original.professionalIdSet()) }
    var active by remember { mutableStateOf(original.active) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original.id == 0L) "Novo serviço" else "Editar serviço") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome (ex.: Corte feminino)") }, singleLine = true)
                OutlinedTextField(
                    duration, { duration = it }, label = { Text("Duração (min)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    price, { price = it }, label = { Text("Preço em R$ (opcional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text("Quem faz (nenhum marcado = todos)", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pros.forEach { p ->
                        FilterChip(
                            selected = p.id in selected,
                            onClick = { selected = if (p.id in selected) selected - p.id else selected + p.id },
                            label = { Text(p.name) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ativo", Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (original.id != 0L) {
                    TextButton(onClick = { scope.launch { app.db.services().delete(original); onDismiss() } }) {
                        Text("Excluir serviço", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = duration.toIntOrNull()
                // Aceita "50", "50,00", "1.200,00" e "50.00"
                val cents = price.replace("R$", "").trim().takeIf { it.isNotEmpty() }?.let {
                    val normalized = if (it.contains(",")) it.replace(".", "").replace(",", ".") else it
                    normalized.toDoubleOrNull()?.let { v -> Math.round(v * 100).toInt() } ?: -1
                }
                error = when {
                    name.isBlank() -> "Informe o nome."
                    d == null || d < 5 -> "Duração inválida."
                    cents != null && cents < 0 -> "Preço inválido."
                    else -> null
                }
                if (error == null) scope.launch {
                    app.db.services().upsert(
                        original.copy(
                            name = name.trim(), durationMin = d!!, priceCents = cents,
                            professionalIds = selected.sorted().joinToString(","), active = active,
                        )
                    )
                    onDismiss()
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
