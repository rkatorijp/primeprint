package com.sukoon.autoprint

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * Sends plain text to a network (LAN/Wi-Fi) ESC/POS thermal printer.
 * Most Japanese receipt printers (Star, Epson) listen on raw TCP port 9100.
 *
 * Garbled Japanese ("文字化け") is almost always one of three things:
 *   1. the printer was never put into Kanji mode, so Shift_JIS byte pairs are
 *      printed as two random single-byte glyphs  -> we send FS & every time
 *   2. the text contains characters the printer's font simply does not have
 *      (emoji, ✓, →, ①, accented latin)          -> sanitize() maps or drops them
 *   3. the printer expects UTF-8 (common on Chinese-made models) while we sent
 *      Shift_JIS, or vice versa                  -> selectable in 設定 (文字コード)
 */
object PrinterHelper {

    private const val CONNECT_TIMEOUT_MS = 5000

    // ── ESC/POS control sequences ────────────────────────────────────────
    private val INIT = byteArrayOf(0x1B, 0x40)               // ESC @   initialize
    private val INTL_JAPAN = byteArrayOf(0x1B, 0x52, 0x08)   // ESC R 8 international char set = Japan
    private val CODEPAGE_KATAKANA = byteArrayOf(0x1B, 0x74, 0x01) // ESC t 1  single-byte page = Katakana
    // FS C 1  Kanji code system = Shift_JIS. Epson's own ESC/POS reference: the printer's
    // Kanji code system defaults to JIS (FS C 0) on power-up, and in that default state
    // "FS &" alone is NOT enough — the printer decodes the following 2-byte pairs as raw
    // JIS, not Shift_JIS. Since we always encode with Shift_JIS (MS932), skipping this
    // command is exactly why real receipts came out garbled even with 文字コード=Shift_JIS
    // selected: kanji mode was on, but the printer was reading our Shift_JIS bytes as JIS.
    private val KANJI_CODE_SHIFTJIS = byteArrayOf(0x1C, 0x43, 0x01)
    private val KANJI_ON = byteArrayOf(0x1C, 0x26)           // FS &    enter Kanji (2-byte) mode
    private val KANJI_OFF = byteArrayOf(0x1C, 0x2E)          // FS .    leave Kanji mode
    private val FEED_AND_CUT = byteArrayOf(
        0x1B, 0x64, 0x03,                                     // ESC d 3  feed 3 lines
        0x1D, 0x56, 0x42, 0x00                                // GS V B 0 partial cut
    )

    /**
     * A one-page sample that shows at a glance whether the encoding is right:
     * if any of these lines is garbled, switch 文字コード and print it again.
     */
    fun testPattern(): String = buildString {
        append("=== PRIME AUTO PRINT ===\n")
        append("文字テスト / CHARSET TEST\n")
        append("------------------------\n")
        append("ひらがな: あいうえお がぎぐげご\n")
        append("カタカナ: アイウエオ パピプペポ\n")
        append("漢字: 注文 合計 個数 領収 予約\n")
        append("英数: ABCDEfghij 0123456789\n")
        append("記号: \\1,234 ¥1,234 ()#*-+/.:\n")
        append("半角ｶﾅ: ﾃｽﾄ ｲﾝｻﾂ\n")
        append("------------------------\n")
        append("全部読めればOKです\n")
    }

    /**
     * Replaces characters a receipt printer's font cannot render, instead of
     * letting them come out as random glyphs.
     */
    fun sanitize(raw: String): String {
        // NFKC folds full-width latin/digits and compatibility forms into the
        // plain forms every printer font has.
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)

        val map = mapOf(
            '\u2018' to "'", '\u2019' to "'", '\u201C' to "\"", '\u201D' to "\"",
            '\u2013' to "-", '\u2014' to "-", '\u2015' to "-", '\u2212' to "-",
            '\u2022' to "*", '\u00B7' to "*", '\u2026' to "...",
            '\u2192' to "->", '\u2190' to "<-", '\u21D2' to "=>",
            '\u2713' to "[OK]", '\u2714' to "[OK]", '\u2717' to "[NG]", '\u274C' to "[NG]",
            '\u25CB' to "O", '\u25CF' to "*", '\u25A0' to "*", '\u25A1' to "[]",
            '\u3000' to " ", '\u00A0' to " ", '\u00A5' to "\\", '\uFFE5' to "\\"
        )

        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val code = s.codePointAt(i)
            val charCount = Character.charCount(code)
            when {
                // emoji, pictographs, variation selectors, ZWJ — no printer font has these
                code >= 0x1F000 -> sb.append("")
                ch == '\uFE0F' || ch == '\uFE0E' || ch == '\u200D' || ch == '\u200B' -> {}
                map.containsKey(ch) -> sb.append(map[ch])
                ch == '\r' -> {}
                ch == '\n' || ch == '\t' -> sb.append(ch)
                ch.code < 0x20 -> {}   // other control bytes would be read as commands
                else -> sb.append(s, i, i + charCount)
            }
            i += charCount
        }
        return sb.toString()
    }

    /** Printed width of one character: full-width (kana/kanji) takes 2 columns. */
    private fun charWidth(ch: Char): Int {
        val c = ch.code
        val wide = (c in 0x1100..0x115F) || (c in 0x2E80..0xA4CF) ||
            (c in 0xAC00..0xD7A3) || (c in 0xF900..0xFAFF) ||
            (c in 0xFE30..0xFE6F) || (c in 0xFF00..0xFF60) || (c in 0xFFE0..0xFFE6)
        return if (wide) 2 else 1
    }

    /**
     * Hard-wraps text to the paper's column count so nothing is cut off at the
     * right edge (58mm paper = 32 columns, 80mm = 48 at font A).
     * Existing line breaks are kept; over-long lines are broken on a space when
     * there is one, otherwise mid-string (normal for Japanese).
     */
    fun wrap(text: String, columns: Int): String {
        if (columns <= 0) return text
        // Sanitize FIRST: it can change a character's printed width (half-width
        // kana folds to full-width), so wrapping the raw text would overflow.
        val src = sanitize(text)
        val out = StringBuilder()
        for (line in src.split("\n")) {
            if (line.isEmpty()) { out.append('\n'); continue }
            var width = 0
            var lastSpace = -1
            val cur = StringBuilder()
            for (ch in line) {
                val w = charWidth(ch)
                if (width + w > columns) {
                    if (lastSpace > 0 && lastSpace > cur.length - 12) {
                        out.append(cur, 0, lastSpace).append('\n')
                        val rest = cur.substring(lastSpace).trimStart()
                        cur.setLength(0); cur.append(rest)
                        width = rest.sumOf { charWidth(it) }
                    } else {
                        out.append(cur).append('\n')
                        cur.setLength(0)
                        width = 0
                    }
                    lastSpace = -1
                }
                if (ch == ' ') lastSpace = cur.length
                cur.append(ch)
                width += w
            }
            out.append(cur).append('\n')
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    /**
     * Paper-saving cap: keeps only the first [maxLines] printed lines of
     * already-wrapped text, dropping the rest instead of printing the whole
     * mail. Call this AFTER [wrap], since "line" here means an actual printed
     * line, not a source line that might still get split in two. maxLines <= 0
     * means no limit — the text is returned unchanged.
     */
    fun limitLines(text: String, maxLines: Int): String {
        if (maxLines <= 0) return text
        val lines = text.split("\n")
        if (lines.size <= maxLines) return text
        return lines.take(maxLines).joinToString("\n") + "\n---以下省略---\n"
    }

    /** Collapses the blank-line noise typical of order-notification mails. */
    fun tidy(text: String): String = text
        .split("\n")
        .map { it.trimEnd() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Encodes with the requested charset, substituting '?' for anything unmappable. */
    private fun encode(text: String, charsetName: String): ByteArray {
        val charset = try {
            Charset.forName(charsetName)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        return try {
            val encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val buf = encoder.encode(CharBuffer.wrap(text))
            ByteArray(buf.remaining()).also { buf.get(it) }
        } catch (e: Exception) {
            text.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * @param charsetName "MS932" (Shift_JIS — Japanese Star/Epson) or "UTF-8".
     * @return true if the text was sent successfully.
     */
    @JvmOverloads
    fun printText(ip: String, port: Int, text: String, charsetName: String = "MS932"): Boolean {
        var socket: Socket? = null
        var out: OutputStream? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            out = socket.getOutputStream()

            val shiftJis = !charsetName.uppercase().contains("UTF")

            out.write(INIT)
            if (shiftJis) {
                out.write(INTL_JAPAN)
                out.write(CODEPAGE_KATAKANA)
                out.write(KANJI_CODE_SHIFTJIS) // tell the printer our kanji bytes are Shift_JIS, not JIS
                out.write(KANJI_ON)          // without this, kanji print as garbage
            }

            out.write(encode(sanitize(text), charsetName))
            out.write(byteArrayOf(0x0A, 0x0A))

            if (shiftJis) out.write(KANJI_OFF)
            out.write(FEED_AND_CUT)
            out.flush()
            true
        } catch (e: Exception) {
            false
        } finally {
            try { out?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
