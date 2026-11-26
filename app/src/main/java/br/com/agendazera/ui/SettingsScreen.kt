package br.com.agendazera.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.agendazera.App
import br.com.agendazera.data.BusinessType

fun parseHm(s: String): Int? {
    val p = s.trim().split(":", "h")
    val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.trim()?.ifEmpty { "0" }?.toIntOrNull() ?: 0
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

fun fmtHm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

@Composable
fun SettingsScreen(app: App, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = remember { app.settings.current }
    var name by remember { mutableStateOf(s.clinicName) }
    var address by remember { mutableStateOf(s.address) }
    var type by remember { mutableStateOf(s.businessType) }
    var daysAhead by remember { mutableStateOf(s.daysAhead.toString()) }
    var lead by remember { mutableStateOf(s.minLeadMinutes.toString()) }
    var pause by remember { mutableStateOf(s.humanPauseMinutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    val number = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SubscriptionCard(app)

        Text("Negócio", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nome (clínica, salão…)") })
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Endereço (vai na confirmação)") })
        Text("Tipo", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = type == BusinessType.CLINICA, onClick = { type = BusinessType.CLINICA },
                label = { Text("Clínica (paciente / consulta)") },
            )
            FilterChip(
                selected = type == BusinessType.SALAO, onClick = { type = BusinessType.SALAO },
                label = { Text("Salão (cliente / horário)") },
            )
        }

        Text("Robô", style = MaterialTheme.typography.titleMedium)
        Text(
            "Dias, horários e duração de atendimento ficam na aba Equipe, em cada profissional.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(daysAhead, { daysAhead = it }, Modifier.weight(1f), label = { Text("Dias à frente") }, keyboardOptions = number)
            OutlinedTextField(lead, { lead = it }, Modifier.weight(1f), label = { Text("Antecedência mín. (min)") }, keyboardOptions = number)
        }
        OutlinedTextField(pause, { pause = it }, Modifier.fillMaxWidth(), label = { Text("Pausa do robô quando pedem atendente (min)") }, keyboardOptions = number)

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(onClick = {
            val da = daysAhead.toIntOrNull(); val ld = lead.toIntOrNull(); val pa = pause.toIntOrNull()
            error = when {
                da == null || da < 1 -> "Dias à frente inválido."
                ld == null || ld < 0 -> "Antecedência inválida."
                pa == null || pa < 1 -> "Pausa inválida."
                else -> null
            }
            if (error == null) {
                app.settings.save(
                    app.settings.current.copy(
                        clinicName = name.trim().ifEmpty { "Clínica" },
                        address = address.trim(),
                        businessType = type,
                        daysAhead = da!!, minLeadMinutes = ld!!, humanPauseMinutes = pa!!,
                    )
                )
                Toast.makeText(ctx, "Configurações salvas", Toast.LENGTH_SHORT).show()
            }
        }, Modifier.fillMaxWidth()) { Text("Salvar") }
    }
}
