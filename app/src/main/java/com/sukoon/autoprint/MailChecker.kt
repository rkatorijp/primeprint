package com.sukoon.autoprint

import android.content.Context
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.MimeMultipart
import javax.mail.search.FlagTerm
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

    private fun msgKey(message: Message): String =
        message.getHeader("Message-ID")?.firstOrNull() ?: message.messageNumber.toString()

    /**
     * All unread messages in the inbox. UNSEEN is the only thing we ever ask
     * Gmail's IMAP SEARCH to evaluate — it's a plain flag, always ASCII, so it
     * never runs into the problem below.
     */
    private fun fetchUnseen(inbox: Folder): List<Message> =
        try {
            inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false)).toList()
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Keyword/sender matching used to be done via Gmail IMAP SEARCH (SUBJECT/BODY/
     * FROM terms). That broke in two ways once the keyword was Japanese (or any
     * non-ASCII text):
     *
     * 1. A single combined query — AndTerm(UNSEEN, OrTerm(SUBJECT, BODY)) — made
     *    Gmail reply "BAD Could not parse command" whenever the literal had
     *    non-ASCII text in it, which aborted the whole loop and silently stopped
     *    every trigger after the broken one, forever. That was fixed by splitting
     *    into per-criterion searches (see git history) — but a second, separate
     *    problem remained:
     * 2. Even a lone SUBJECT/BODY search with a Japanese literal doesn't throw,
     *    but JavaMail's IMAP client encodes non-ASCII search literals in a way
     *    Gmail's SEARCH does not reliably match against, so the search just comes
     *    back with zero hits — no error, it just never matches. This is exactly
     *    why an English keyword worked and a Japanese one silently never did.
     *
     * Fix: never send free-text keywords to Gmail's SEARCH at all. Only UNSEEN
     * (always ASCII) is sent over IMAP; subject/body/from matching is done here,
     * locally, as a plain Kotlin substring check, which works identically for
     * Japanese and English text.
     */
    private fun matches(message: Message, trigger: Trigger, bodyCache: MutableMap<String, String>): Boolean {
        val keyword = trigger.keyword.trim()
        val sender = trigger.sender.trim()

        if (sender.isNotBlank()) {
            val from = try {
                message.from?.joinToString(" ") { it.toString() } ?: ""
            } catch (e: Exception) {
                ""
            }
            if (!from.contains(sender, ignoreCase = true)) return false
        }

        if (keyword.isNotBlank()) {
            val subject = try {
                message.subject ?: ""
            } catch (e: Exception) {
                ""
            }
            if (!subject.contains(keyword, ignoreCase = true)) {
                val body = bodyCache.getOrPut(msgKey(message)) {
                    try { extractPlainText(message) } catch (e: Exception) { "" }
                }
                if (!body.contains(keyword, ignoreCase = true)) return false
            }
        }

        return keyword.isNotBlank() || sender.isNotBlank()
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

        if (address.isBlank() || password.isBlank()) return "設定が未完了です"

        val triggers = Prefs.loadTriggers(context).filter { it.enabled && it.isUsable }
        if (triggers.isEmpty()) return "有効なトリガーがありません"

        val printers = Prefs.loadPrinters(context).filter { it.enabled && it.isUsable }
        if (printers.isEmpty()) return "有効なプリンターがありません"

        var printed = 0
        val store = openStore(address, password)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            val unseen = fetchUnseen(inbox)
            val bodyCache = HashMap<String, String>()
            val alreadyDone = HashSet<String>()
            for (trigger in triggers) {
                // Isolated per trigger: one trigger's matching failing must not
                // stop the other triggers from being checked this cycle.
                for (message in unseen) {
                    val key = msgKey(message)
                    if (key in alreadyDone) continue // matched by an earlier trigger already
                    val hit = try {
                        matches(message, trigger, bodyCache)
                    } catch (e: Exception) {
                        false
                    }
                    if (!hit) continue
                    val body = buildPrintText(message, trigger, Prefs.bodyOnly(context))
                    val wrapped = PrinterHelper.wrap(PrinterHelper.tidy(body), Prefs.columns(context))
                    val fitted = PrinterHelper.limitLines(wrapped, trigger.maxLines)
                    // Sent to every ENABLED printer — one enabled = that one only,
                    // two enabled = both. One printer being offline must not stop
                    // the others, so each is tried independently.
                    var anyOk = false
                    for (printer in printers) {
                        val ok = try {
                            PrinterHelper.printText(printer.ip, printer.port, fitted, Prefs.charset(context))
                        } catch (e: Exception) {
                            false
                        }
                        if (ok) anyOk = true
                    }
                    if (anyOk) {
                        message.setFlag(Flags.Flag.SEEN, true)
                        alreadyDone.add(key)
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
        val printers = Prefs.loadPrinters(context)
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
            val unread = fetchUnseen(inbox)
            lines.append("OK 受信トレイを読めました（未読 ${unread.size}件）\n")

            val triggers = Prefs.loadTriggers(context)
            if (triggers.isEmpty()) lines.append("NG トリガーが1件も登録されていません\n")

            val bodyCache = HashMap<String, String>()
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
                val hits = try {
                    unread.filter { matches(it, t, bodyCache) }
                } catch (e: Exception) {
                    lines.append("NG ${t.name}: 検索でエラー（${e.message}）\n")
                    continue
                }
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

        if (printers.isEmpty()) {
            lines.append("NG プリンターが1台も登録されていません")
        } else {
            val enabled = printers.filter { it.enabled && it.isUsable }
            if (enabled.isEmpty()) {
                lines.append("NG 有効なプリンターがありません（すべてオフ、またはIP未入力）")
            }
            for (printer in printers) {
                if (!printer.enabled) {
                    lines.append("-- ${printer.describe()}: オフ（スキップ）\n")
                    continue
                }
                if (!printer.isUsable) {
                    lines.append("NG ${printer.name.ifBlank { "プリンター" }}: IPが未入力です\n")
                    continue
                }
                val ok = try {
                    PrinterHelper.printText(printer.ip, printer.port, "=== PRIME AUTO PRINT ===\n接続チェック ${nowLabel()}\n", Prefs.charset(context))
                } catch (e: Exception) {
                    false
                }
                lines.append(
                    if (ok) "OK ${printer.describe()} に1枚送信しました\n"
                    else "NG ${printer.describe()} に送れません（IP・ポート・電源・同じWi-Fiか確認）\n"
                )
            }
        }
        return lines.toString().trimEnd('\n')
    }

    /**
     * Timestamp, title (subject), then the mail body — nothing else. No
     * 差出人/受信 header lines and no trailing trigger-name line: those used to
     * read like an extra "signature" tacked onto the ticket. The timestamp
     * goes at the very top so it's the first thing on the receipt, not an
     * afterthought at the bottom.
     */
    private fun buildPrintText(message: Message, trigger: Trigger, bodyOnly: Boolean): String {
        val subject = message.subject ?: "(件名なし)"
        val body = extractPlainText(message).take(2000)
        return buildString {
            append(nowLabel())
            append("\n")
            append(subject)
            append("\n")
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
