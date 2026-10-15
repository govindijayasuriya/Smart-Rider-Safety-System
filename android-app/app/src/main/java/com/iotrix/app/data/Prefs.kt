package com.iotrix.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Contact(val id: String, val name: String, val relation: String, val phone: String)

/** Everything is stored on the phone. No server / login needed. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("iotrix", Context.MODE_PRIVATE)
    }

    var riderName: String
        get() = sp.getString("riderName", "") ?: ""
        set(v) = sp.edit().putString("riderName", v.trim()).apply()

    /** Board IP such as 192.168.43.57  (or "demo" for the built-in fake board). */
    var boardIp: String
        get() = sp.getString("boardIp", "") ?: ""
        set(v) = sp.edit().putString(
            "boardIp",
            v.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        ).apply()

    var pairingToken: String
        get() = sp.getString("pairingToken", "") ?: ""
        set(v) = sp.edit().putString("pairingToken", v.trim()).apply()

    var rideActive: Boolean
        get() = sp.getBoolean("rideActive", false)
        set(v) = sp.edit().putBoolean("rideActive", v).apply()

    // ---- contacts ----
    fun contacts(): List<Contact> {
        val raw = sp.getString("contacts", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Contact(o.getString("id"), o.getString("name"), o.optString("relation", ""), o.getString("phone"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveContacts(list: List<Contact>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("relation", it.relation).put("phone", it.phone))
        }
        sp.edit().putString("contacts", arr.toString()).apply()
    }

    // ---- events whose emergency message was already sent (prevents duplicates) ----
    fun isHandled(eventId: String): Boolean =
        (sp.getString("handled", "") ?: "").split(",").contains(eventId)

    fun markHandled(eventId: String) {
        val list = ((sp.getString("handled", "") ?: "").split(",").filter { it.isNotEmpty() } + eventId)
            .distinct().takeLast(40)
        sp.edit().putString("handled", list.joinToString(",")).apply()
    }
}
