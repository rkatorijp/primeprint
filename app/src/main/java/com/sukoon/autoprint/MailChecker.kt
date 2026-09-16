package com.sukoon.autoprint

import android.content.Context
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.MimeMultipart
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

/**
 * All Gmail (IMAP) access lives here so the background service and the
 * on-screen connection check run exactly the same code path — if the check
 * says the mail was found, the service finds it too.
 */
object MailChecker {

    private const val HOST = "imap.gmail.com"

    private fun session(): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", HOST)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "15000")
        }
        return Session.getInstance(props)
    }

    private fun openStore(address: String, password: String): Store {
        val store = session().getStore("imaps")
        // Gmail app passwords are displayed with spaces; they are not part of the secret.
        store.connect(HOST, address, password.replace(" ", ""))
        return store
    }

    /** Unread + (subject OR body contains keyword) + (from contains sender). */
    private fun termFor(trigger: Trigger): SearchTerm {
        var term: SearchTerm = FlagTerm(Flags(Flags.Flag.SEEN), false)
        if (trigger.keyword.isNotBlank()) {
            term = AndTerm(term, OrTerm(SubjectTerm(trigger.keyword), BodyTerm(trigger.keyword)))
        }
        if (trigger.sender.isNotBlank()) {
            term = AndTerm(term, FromStringTerm(trigger.sender))
        }
        return term
    }

    fun nowLabel(): String = SimpleDateFormat("HH:mm:ss").format(Date())

    /**
     * Prints every unread mail matching any ENABLED trigger, then marks it read.
     * @return a short status line for the notification, or null to keep the default.
     */
    fun printMatching(context: Context): String? {
        val prefs = Prefs.get(context)
        val address = prefs.getString(Prefs.KEY_GMAIL_ADDRESS, "") ?: ""
        val password = prefs.getString(Prefs.KEY_APP_PASSWORD, "") ?: ""
        val ip = prefs.getString(Prefs.KEY_PRINTER_IP, "") ?: ""
        val port = (prefs.getString(Prefs.KEY_PRINTER_PORT, "9100") ?: "9100").toIntOrNull() ?: 9100

        if (address.isBlank() || password.isBlank() || ip.isBlank()) return "設定が未完了です"

        val triggers = Prefs.loadTriggers(context).filter { it.enabled && it.isUsable }
        if (triggers.isEmpty()) return "有効なトリガーがありません"

        var printed = 0
        val store = openStore(address, password)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            val alreadyDone = HashSet<String>()
            for (trigger in triggers) {
                val messages = inbox.search(termFor(trigger))
                for (message in messages) {
                    val key = message.getHeader("Message-ID")?.firstOrNull() ?: message.messageNumber.toString()
                    if (!alreadyDone.add(key)) continue // matched by an earlier trigger already
                    val body = buildPrintText(message, trigger, Prefs.bodyOnly(context))
                    val fitted = PrinterHelper.wrap(PrinterHelper.tidy(body), Prefs.columns(context))
                    val ok = PrinterHelper.printText(ip, port, fitted, Prefs.charset(context))
                    if (ok) {
                        message.setFlag(Flags.Flag.SEEN, true)
                        printed++
                    }
                }
            }
            inbox.close(false)
        } finally {
            try { if (store.isConnected) store.close() } catch (_: Exception) {}
        }
        return if (printed > 0) "${printed}件印刷しました (${nowLabel()})" else null
    }

    /**
     * Step-by-step report shown on the 接続チェック screen: login, inbox read,
     * per-trigger hit counts, printer test. Never throws — every failure
     * becomes a readable line.
     */
    fun diagnose(context: Context): String {
        val prefs = Prefs.get(context)
        val address = prefs.getString(Prefs.KEY_GMAIL_ADDRESS, "") ?: ""
        val password = prefs.getString(Prefs.KEY_APP_PASSWORD, "") ?: ""
        val ip = prefs.getString(Prefs.KEY_PRINTER_IP, "") ?: ""
        val port = (prefs.getString(Prefs.KEY_PRINTER_PORT, "9100") ?: "9100").toIntOrNull() ?: 9100
        val lines = StringBuilder()

        if (address.isBlank() || password.isBlank()) {
            return "NG Gmailアドレスとアプリパスワードを入力してください"
        }

        val store: Store
        try {
            store = openStore(address, password)
            lines.append("OK Gmailにログインできました（$address）\n")
        } catch (e: Exception) {
            lines.append("NG ログインできません: ${e.message}\n")
            lines.append("   ・普通のGmailパスワードでは入れません（16桁のアプリパスワードが必要）\n")
            lines.append("   ・2段階認証がオンになっているか確認してください\n")
            return lines.toString()
        }

        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val unread = inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            lines.append("OK 受信トレイを読めました（未読 ${unread.size}件）\n")

            val triggers = Prefs.loadTriggers(context)
            if (triggers.isEmpty()) lines.append("NG トリガーが1件も登録されていません\n")

            var totalHits = 0
            for (t in triggers) {
                if (!t.enabled) {
                    lines.append("-- ${t.name}: オフ（スキップ）\n")
                    continue
                }
                if (!t.isUsable) {
                    lines.append("NG ${t.name}: キーワードも送信元も空です\n")
                    continue
                }
                val hits = inbox.search(termFor(t))
                totalHits += hits.size
                val mark = if (hits.isNotEmpty()) "OK" else "--"
                lines.append("$mark ${t.name}: ${hits.size}件一致（${t.describe()}）\n")
                hits.take(3).forEach { lines.append("     ・${it.subject ?: "(件名なし)"}\n") }
            }
            if (totalHits == 0) {
                lines.append("→ 一致0件でした。よくある原因:\n")
                lines.append("   ・対象のメールをすでに既読にしている（未読のみ対象です）\n")
                lines.append("   ・キーワードの全角/半角やスペースが実際の文面と違う\n")
                lines.append("   ・注文メールがラベルで振り分けられ受信トレイにない\n")
            }
            inbox.close(false)
        } catch (e: Exception) {
            lines.append("NG 受信トレイを開けません: ${e.message}\n")
            lines.append("   ・GmailのウェブでIMAPが有効になっているか確認してください\n")
        } finally {
            try { if (store.isConnected) store.close() } catch (_: Exception) {}
        }

        if (ip.isBlank()) {
            lines.append("NG プリンターIPが未入力です")
        } else {
            val ok = PrinterHelper.printText(ip, port, "=== PRIME AUTO PRINT ===\n接続チェック ${nowLabel()}\n", Prefs.charset(context))
            lines.append(
                if (ok) "OK プリンターに1枚送信しました（$ip:$port）"
                else "NG プリンターに送れません（IP・ポート・電源・同じWi-Fiか確認）"
            )
        }
        return lines.toString()
    }

    private fun buildPrintText(message: Message, trigger: Trigger, bodyOnly: Boolean): String {
        val subject = message.subject ?: "(件名なし)"
        val from = message.from?.joinToString(", ") { it.toString() } ?: "(不明)"
        val date = message.sentDate?.toString() ?: ""
        val body = extractPlainText(message).take(2000)
        if (bodyOnly) {
            // Just the mail body, nothing else — one short time stamp so the
            // kitchen can tell two tickets apart.
            return buildString {
                append(body)
                append("\n")
                append("${nowLabel()}  ${if (trigger.name.isNotBlank()) trigger.name else ""}\n")
            }
        }
        return buildString {
            append("=== PRIME 注文通知 ===\n")
            if (trigger.name.isNotBlank()) append("種別: ${trigger.name}\n")
            append("差出人: $from\n")
            append("件名: $subject\n")
            if (date.isNotBlank()) append("受信: $date\n")
            append("------------------------\n")
            append(body)
            append("\n")
        }
    }

    private fun extractPlainText(message: Message): String {
        return try {
            when (val content = message.content) {
                is String -> content
                is MimeMultipart -> {
                    val sb = StringBuilder()
                    for (i in 0 until content.count) {
                        val part = content.getBodyPart(i)
                        if (part.isMimeType("text/plain")) {
                            sb.append(part.content.toString())
                        } else if (part.isMimeType("text/html") && sb.isEmpty()) {
                            sb.append(part.content.toString().replace(Regex("<[^>]*>"), ""))
                        }
                    }
                    sb.toString()
                }
                else -> content?.toString() ?: ""
            }
        } catch (e: Exception) {
            "(本文の取得に失敗しました)"
        }
    }
}
