package br.com.agendazera.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import br.com.agendazera.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Escuta as notificações do WhatsApp / WhatsApp Business, passa a mensagem para o robô
 * e responde usando o botão "Responder" da própria notificação.
 */
class WhatsAppListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    /** Última mensagem tratada por contato, para não responder duas vezes à mesma. */
    private val lastHandled = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        val app = application as App
        if (!app.settings.current.botEnabled) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val incoming = extractMessage(n, sbn.postTime) ?: return
        val reply = findReplyAction(n) ?: return

        scope.launch {
            mutex.withLock {
                if (lastHandled[incoming.sender] == incoming.signature) return@withLock
                lastHandled[incoming.sender] = incoming.signature
                // Sem assinatura ativa (e fora do teste grátis), o robô fica em silêncio
                runCatching { app.subscription.refreshIfStale() }
                if (!app.subscription.canUseBot()) return@withLock
                val answer = try {
                    app.bot.handle(incoming.sender, incoming.text)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no robô", e); null
                } ?: return@withLock
                delay(800) // pequena pausa para parecer mais natural
                sendReply(reply, answer)
            }
        }
    }

    private data class Incoming(val sender: String, val text: String, val signature: String)

    private fun extractMessage(n: Notification, postTime: Long): Incoming? {
        val extras = n.extras
        if (extras.getBoolean("android.isGroupConversation", false)) return null

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            if (style.isGroupConversation) return null
            val last = style.messages.lastOrNull() ?: return null
            val person = last.person ?: return null // null = mensagem enviada por nós
            val text = last.text?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val sender = person.name?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: return null
            if (isSystemSender(sender)) return null
            return Incoming(sender, text, "${last.timestamp}|$text")
        }

        // Fallback para notificações simples
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        if (isSystemSender(title) || title.contains(":") || title.contains("@")) return null // grupos
        return Incoming(title, text, "$postTime|$text")
    }

    private fun isSystemSender(name: String) =
        name.equals("WhatsApp", true) || name.equals("Você", true) || name.equals("You", true)

    private fun findReplyAction(n: Notification): Notification.Action? {
        val candidates = (n.actions?.toList() ?: emptyList()) +
            Notification.WearableExtender(n).actions
        return candidates.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
    }

    private fun sendReply(action: Notification.Action, text: String) {
        val inputs = action.remoteInputs ?: return
        val intent = Intent()
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        try {
            action.actionIntent.send(this, 0, intent)
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Notificação já expirou, não foi possível responder", e)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WhatsAppBot"
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
