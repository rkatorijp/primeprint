package com.sukoon.autoprint

import org.json.JSONArray
import org.json.JSONObject

/**
 * One print rule. Any number of these can be registered, and each one can be
 * switched on and off independently of the others.
 *
 * keyword  : matched against the subject OR the body (blank = don't filter)
 * sender   : matched against the From header (blank = don't filter)
 * At least one of the two must be filled in, otherwise the trigger would match
 * every unread mail in the inbox.
 */
data class Trigger(
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var keyword: String = "",
    var sender: String = "",
    var enabled: Boolean = true
) {
    val isUsable: Boolean get() = keyword.isNotBlank() || sender.isNotBlank()

    fun describe(): String {
        val parts = mutableListOf<String>()
        if (keyword.isNotBlank()) parts.add("キーワード「$keyword」")
        if (sender.isNotBlank()) parts.add("差出人 $sender")
        return if (parts.isEmpty()) "条件が未設定" else parts.joinToString(" / ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("keyword", keyword)
        put("sender", sender)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = Trigger(
            id = o.optLong("id", System.currentTimeMillis()),
            name = o.optString("name", ""),
            keyword = o.optString("keyword", ""),
            sender = o.optString("sender", ""),
            enabled = o.optBoolean("enabled", true)
        )

        fun listToJson(list: List<Trigger>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): MutableList<Trigger> {
            val out = mutableListOf<Trigger>()
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
