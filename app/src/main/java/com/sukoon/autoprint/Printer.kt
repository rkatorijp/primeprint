package com.sukoon.autoprint

import org.json.JSONArray
import org.json.JSONObject

/**
 * One network printer. Any number of these can be registered, and each one
 * can be switched on and off independently — with one printer enabled a
 * match prints to just that one, with two enabled it prints to both, and
 * disabling one stops it without touching the other.
 */
data class Printer(
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var ip: String = "",
    var port: Int = 9100,
    var enabled: Boolean = true
) {
    val isUsable: Boolean get() = ip.isNotBlank()

    fun describe(): String = if (ip.isBlank()) "IP未設定" else "$ip:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ip", ip)
        put("port", port)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = Printer(
            id = o.optLong("id", System.currentTimeMillis()),
            name = o.optString("name", ""),
            ip = o.optString("ip", ""),
            port = o.optInt("port", 9100),
            enabled = o.optBoolean("enabled", true)
        )

        fun listToJson(list: List<Printer>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): MutableList<Printer> {
            val out = mutableListOf<Printer>()
            if (raw.isNullOrBlank()) return out
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            } catch (_: Exception) {
            }
            return out
        }
    }
}
