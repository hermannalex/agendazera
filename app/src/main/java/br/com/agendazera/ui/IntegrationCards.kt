package br.com.agendazera.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.agendazera.App
import br.com.agendazera.billing.Plan
import br.com.agendazera.billing.SubscriptionManager
import br.com.agendazera.calendar.CalendarSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- Assinatura ----------------

private val BASIC_FEATURES = listOf(
    "Robô de agendamento no WhatsApp",
    "1 agenda com seus horários",
    "Agenda Google vinculada",
)
private val PRO_FEATURES = listOf(
    "Tudo do Basic",
    "Vários profissionais, cada um com sua agenda e horários",
    "Serviços com duração e preço (ex.: corte 40 min, R$ 50)",
    "Cliente escolhe o profissional ou \"sem preferência\"",
    "Agenda Google separada para cada profissional",
)

@Composable
fun SubscriptionCard(app: App) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sub by app.subscription.state.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf(sub.email) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { app.subscription.refreshIfStale() }

    fun checkout(plan: String) {
        scope.launch {
            busy = true; message = null
            app.subscription.startCheckout(email.trim(), plan)
                .onSuccess { url ->
                    message = "Depois de pagar, volte aqui e toque em \"Já paguei\"."
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                .onFailure { message = "Não foi possível abrir o pagamento: ${it.message}" }
            busy = false
        }
    }

    val activePlan = if (sub.active) sub.plan ?: Plan.BASIC else null

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Assinatura", fontWeight = FontWeight.Bold)
            when {
                !app.subscription.isConfigured ->
                    Text("Cobrança ainda não configurada (modo desenvolvimento): todos os recursos do Pro estão liberados.")
                activePlan == Plan.PRO -> Text("✅ Plano Pro ativo")
                activePlan == Plan.BASIC -> Text("✅ Plano Basic ativo")
                app.subscription.trialDaysLeft() > 0 ->
                    Text("Teste grátis: ${app.subscription.trialDaysLeft()} dia(s) restante(s), com todos os recursos do Pro.")
                else -> Text(
                    "O teste grátis acabou. Escolha um plano para o robô voltar a responder no WhatsApp.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (sub.active) sub.nextPaymentDate?.let {
                Text("Próxima cobrança: ${it.take(10).split("-").reversed().joinToString("/")}", style = MaterialTheme.typography.bodySmall)
            }

            if (app.subscription.isConfigured && activePlan != Plan.PRO) {
                OutlinedTextField(
                    email, { email = it }, Modifier.fillMaxWidth(),
                    label = { Text("E-mail da sua conta Mercado Pago") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                if (activePlan == null) {
                    PlanOption("Basic", SubscriptionManager.BASIC_PRICE, BASIC_FEATURES, "Assinar Basic", busy, highlight = false) {
                        checkout(Plan.BASIC)
                    }
                }
                PlanOption(
                    "Pro", SubscriptionManager.PRO_PRICE, PRO_FEATURES,
                    if (activePlan == Plan.BASIC) "Mudar para o Pro" else "Assinar Pro", busy, highlight = true,
                ) { checkout(Plan.PRO) }
                if (activePlan == Plan.BASIC) {
                    Text(
                        "Ao confirmar o Pro, a assinatura Basic é cancelada automaticamente.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (app.subscription.isConfigured) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            app.subscription.refresh()
                                .onSuccess {
                                    message = if (it.active) "Assinatura ${if (it.plan == Plan.PRO) "Pro" else "Basic"} confirmada! 🎉"
                                    else "Pagamento ainda não confirmado (status: ${it.status})."
                                }
                                .onFailure { message = "Não foi possível verificar: ${it.message}" }
                            busy = false
                        }
                    }) { Text(if (sub.active) "Verificar" else "Já paguei") }
                    if (sub.active) {
                        TextButton(onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.mercadopago.com.br/subscriptions")))
                        }) { Text("Gerenciar no Mercado Pago") }
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PlanOption(
    name: String, price: String, features: List<String>, button: String,
    busy: Boolean, highlight: Boolean, onClick: () -> Unit,
) {
    OutlinedCard(
        colors = if (highlight) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.outlinedCardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(price, fontWeight = FontWeight.Bold)
            }
            features.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(button) }
        }
    }
}

/** Aviso de recurso exclusivo do Pro, com atalho para a assinatura. */
@Composable
fun ProUpsell(text: String, onOpenPlans: (() -> Unit)? = null) {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Recurso do plano Pro · ${SubscriptionManager.PRO_PRICE}", fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (onOpenPlans != null) TextButton(onClick = onOpenPlans) { Text("Ver planos") }
        }
    }
}

// ---------------- Agenda Google (por profissional) ----------------

/**
 * Vincula uma agenda do celular (Google Agenda).
 * [onChange] recebe (id da agenda ou -1, nome, se bloqueia horários).
 */
@Composable
fun CalendarLinkSection(
    app: App,
    calendarId: Long,
    calendarName: String,
    blocks: Boolean,
    onChange: (Long, String, Boolean) -> Unit,
    onSyncNow: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var calendars by remember { mutableStateOf<List<CalendarSync.CalendarInfo>?>(null) }

    fun openPicker() {
        scope.launch { calendars = withContext(Dispatchers.IO) { app.calendar.listCalendars() } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) openPicker()
        else Toast.makeText(ctx, "Sem acesso à agenda não dá para vincular.", Toast.LENGTH_LONG).show()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Agenda Google", fontWeight = FontWeight.Bold)
        if (calendarId > 0) {
            Text("Vinculada: $calendarName", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Compromissos da agenda bloqueiam horários", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = blocks, onCheckedChange = { onChange(calendarId, calendarName, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onSyncNow != null) OutlinedButton(onClick = onSyncNow) { Text("Enviar consultas") }
                TextButton(onClick = { openPicker() }) { Text("Trocar") }
                TextButton(onClick = { onChange(-1, "", blocks) }) { Text("Desvincular") }
            }
        } else {
            Text(
                "As consultas aparecem na agenda Google, e os compromissos dela bloqueiam horários no robô.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                if (app.calendar.hasPermission()) openPicker() else permissionLauncher.launch(CalendarSync.PERMISSIONS)
            }) { Text("Vincular agenda Google") }
        }
    }

    calendars?.let { list ->
        AlertDialog(
            onDismissRequest = { calendars = null },
            title = { Text("Escolha a agenda") },
            text = {
                if (list.isEmpty()) {
                    Text(
                        "Nenhuma agenda encontrada. Adicione a conta Google em " +
                            "Configurações do Android → Contas e ative a sincronização da Agenda."
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.id }) { cal ->
                            TextButton(
                                onClick = {
                                    val label = if (cal.name == cal.account) cal.name else "${cal.name} (${cal.account})"
                                    onChange(cal.id, label, blocks)
                                    calendars = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(cal.name + if (cal.isGoogle) "  · Google" else "")
                                    Text(cal.account, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { calendars = null }) { Text("Fechar") } },
        )
    }
}
