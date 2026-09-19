package com.sukoon.autoprint

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val PREFS_NAME = "autoprint_prefs"

    const val KEY_GMAIL_ADDRESS = "gmail_address"
    const val KEY_APP_PASSWORD = "app_password"
    const val KEY_SERVICE_ENABLED = "service_enabled"
    const val KEY_TRIGGERS = "triggers_json"
    const val KEY_PRINTERS = "printers_json"
    const val KEY_INTERVAL_SEC = "interval_sec"
    const val KEY_CHARSET = "printer_charset"
    const val KEY_BODY_ONLY = "body_only"
    const val KEY_COLUMNS = "printer_columns"

    // Legacy single-trigger keys — read once, then migrated into KEY_TRIGGERS.
    private const val KEY_TRIGGER_KEYWORD = "trigger_keyword"
    private const val KEY_SENDER_FILTER = "sender_filter"

    // Legacy single-printer keys — read once, then migrated into KEY_PRINTERS.
    private const val KEY_PRINTER_IP = "printer_ip"
    private const val KEY_PRINTER_PORT = "printer_port"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun intervalSec(context: Context): Int = get(context).getInt(KEY_INTERVAL_SEC, 10)

    /** true = print the mail body only, without the from/subject header block. */
    fun bodyOnly(context: Context): Boolean = get(context).getBoolean(KEY_BODY_ONLY, false)

    /** Characters per line: 32 for 58mm paper, 48 for 80mm. */
    fun columns(context: Context): Int = get(context).getInt(KEY_COLUMNS, 48)

    /** "MS932" (Shift_JIS) for Japanese Star/Epson printers, "UTF-8" for most others. */
    fun charset(context: Context): String = get(context).getString(KEY_CHARSET, "MS932") ?: "MS932"

    fun loadTriggers(context: Context): MutableList<Trigger> {
        val prefs = get(context)
        val stored = prefs.getString(KEY_TRIGGERS, null)
        if (stored != null) return Trigger.listFromJson(stored)

        // First run after the update: carry the old single keyword/sender over.
        val keyword = prefs.getString(KEY_TRIGGER_KEYWORD, "") ?: ""
        val sender = prefs.getString(KEY_SENDER_FILTER, "") ?: ""
        val migrated = mutableListOf<Trigger>()
        if (keyword.isNotBlank() || sender.isNotBlank()) {
            migrated.add(Trigger(name = "以前の設定", keyword = keyword, sender = sender, enabled = true))
            saveTriggers(context, migrated)
        }
        return migrated
    }

    fun saveTriggers(context: Context, list: List<Trigger>) {
        get(context).edit().putString(KEY_TRIGGERS, Trigger.listToJson(list)).apply()
    }

    fun loadPrinters(context: Context): MutableList<Printer> {
        val prefs = get(context)
        val stored = prefs.getString(KEY_PRINTERS, null)
        if (stored != null) return Printer.listFromJson(stored)

        // First run after the multi-printer update: carry the old single
        // printer IP/port over as printer #1.
        val ip = prefs.getString(KEY_PRINTER_IP, "") ?: ""
        val port = (prefs.getString(KEY_PRINTER_PORT, "9100") ?: "9100").toIntOrNull() ?: 9100
        val migrated = mutableListOf<Printer>()
        if (ip.isNotBlank()) {
            migrated.add(Printer(name = "プリンター1", ip = ip, port = port, enabled = true))
            savePrinters(context, migrated)
        }
        return migrated
    }

    fun savePrinters(context: Context, list: List<Printer>) {
        get(context).edit().putString(KEY_PRINTERS, Printer.listToJson(list)).apply()
    }
}
