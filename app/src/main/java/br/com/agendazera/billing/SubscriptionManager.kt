package br.com.agendazera.billing

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import br.com.agendazera.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

object Plan {
    const val BASIC = "basic"
    const val PRO = "pro"
}

data class SubscriptionState(
    val trialEndsAt: Long,
    val active: Boolean = false,
    /** Plano ativo: "basic", "pro" ou null. */
    val plan: String? = null,
    /** Status do Mercado Pago: authorized, pending, paused, cancelled ou none. */
    val status: String = "none",
    val nextPaymentDate: String? = null,
    val checkedAt: Long = 0,
    val email: String = "",
)

/**
 * Assinatura mensal pelo Mercado Pago.
 * O app nunca guarda o token do Mercado Pago: ele fala com o servidor da pasta /backend,
 * que cria a assinatura e diz se ela está paga.
 */
class SubscriptionManager(context: Context) {

    private val prefs = context.getSharedPreferences("subscription", Context.MODE_PRIVATE)

    /** Identifica este celular. ANDROID_ID se mantém mesmo reinstalando o app. */
    @SuppressLint("HardwareIds")
    val deviceId: String = prefs.getString("deviceId", null) ?: run {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val id = "ac-" + (androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
        prefs.edit().putString("deviceId", id).apply()
        id
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SubscriptionState> = _state

    /** Enquanto o servidor não estiver configurado, a cobrança fica desligada (modo desenvolvimento). */
    val isConfigured: Boolean =
        BuildConfig.BILLING_API_URL.startsWith("https://") && !BuildConfig.BILLING_API_URL.contains("SEU-PROJETO")

    fun canUseBot(now: Long = System.currentTimeMillis()): Boolean {
        if (!isConfigured) return true
        val s = _state.value
        if (now < s.trialEndsAt) return true
        // Assinatura ativa vale até 3 dias sem conseguir consultar o servidor (celular sem internet)
        return s.active && now - s.checkedAt < GRACE_MS
    }

    /** Recursos Pro (vários profissionais e serviços): no teste grátis, no plano Pro ou sem cobrança configurada. */
    fun isPro(now: Long = System.currentTimeMillis()): Boolean {
        if (!isConfigured) return true
        val s = _state.value
        if (now < s.trialEndsAt) return true
        return s.active && s.plan == Plan.PRO && now - s.checkedAt < GRACE_MS
    }

    fun inTrial(now: Long = System.currentTimeMillis()) = isConfigured && now < _state.value.trialEndsAt && !_state.value.active

    fun trialDaysLeft(now: Long = System.currentTimeMillis()): Int =
        ((_state.value.trialEndsAt - now + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(0)

    /** Consulta o servidor se a última verificação tiver mais de 6 horas. */
    suspend fun refreshIfStale() {
        if (!isConfigured) return
        if (System.currentTimeMillis() - _state.value.checkedAt > STALE_MS) refresh()
    }

    suspend fun refresh(): Result<SubscriptionState> = withContext(Dispatchers.IO) {
        runCatching {
            check(isConfigured) { "Cobrança não configurada" }
            val json = http("GET", "/api/status?deviceId=" + URLEncoder.encode(deviceId, "UTF-8"), null)
            val updated = _state.value.copy(
                active = json.optBoolean("active", false),
                plan = json.optString("plan").takeIf { it == Plan.BASIC || it == Plan.PRO },
                status = json.optString("status", "none"),
                nextPaymentDate = json.optString("nextPaymentDate").takeIf { it.isNotBlank() && it != "null" },
                checkedAt = System.currentTimeMillis(),
            )
            save(updated)
            updated
        }
    }

    /** Cria a assinatura do plano no Mercado Pago e devolve o link de pagamento. */
    suspend fun startCheckout(email: String, plan: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(isConfigured) { "Cobrança não configurada" }
            require(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) { "E-mail inválido" }
            save(_state.value.copy(email = email))
            val body = JSONObject().put("deviceId", deviceId).put("email", email).put("plan", plan)
            val json = http("POST", "/api/assinar", body)
            json.optString("init_point").takeIf { it.startsWith("https://") }
                ?: error(json.optString("error", "Resposta inválida do servidor"))
        }
    }

    private fun http(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(BuildConfig.BILLING_API_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-App-Key", BuildConfig.BILLING_APP_KEY)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) error(json.optString("error", "Erro $code no servidor"))
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun load(): SubscriptionState {
        // Guarda o início do teste; o fim é calculado, assim mudar TRIAL_DAYS vale para todos
        if (!prefs.contains("trialStartedAt")) {
            val start = if (prefs.contains("trialEndsAt")) prefs.getLong("trialEndsAt", 0) - 7 * DAY_MS // versão antiga (7 dias)
            else System.currentTimeMillis()
            prefs.edit().putLong("trialStartedAt", start).apply()
        }
        return SubscriptionState(
            trialEndsAt = prefs.getLong("trialStartedAt", 0) + TRIAL_DAYS * DAY_MS,
            active = prefs.getBoolean("active", false),
            plan = prefs.getString("plan", null),
            status = prefs.getString("status", "none")!!,
            nextPaymentDate = prefs.getString("nextPaymentDate", null),
            checkedAt = prefs.getLong("checkedAt", 0),
            email = prefs.getString("email", "")!!,
        )
    }

    private fun save(s: SubscriptionState) {
        prefs.edit()
            .putBoolean("active", s.active)
            .putString("plan", s.plan)
            .putString("status", s.status)
            .putString("nextPaymentDate", s.nextPaymentDate)
            .putLong("checkedAt", s.checkedAt)
            .putString("email", s.email)
            .apply()
        _state.value = s
    }

    companion object {
        const val BASIC_PRICE = "R$ 9,90/mês"
        const val PRO_PRICE = "R$ 49,90/mês"
        const val TRIAL_DAYS = 30
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val STALE_MS = 6 * 60 * 60 * 1000L
        private const val GRACE_MS = 3 * DAY_MS
    }
}
