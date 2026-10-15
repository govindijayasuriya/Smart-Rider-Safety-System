package com.iotrix.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain ASCII only, so each SMS part can hold 160 characters. */
object Messages {
    fun emergency(name: String, fix: LocationHelper.Fix?, stale: Boolean): String {
        val base = "EMERGENCY: $name may have had a serious fall while riding."
        if (fix == null) return "$base Location unavailable. Please call them now."
        val link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", fix.lat, fix.lng)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(fix.timeMs))
        return if (stale) {
            val mins = ((System.currentTimeMillis() - fix.timeMs) / 60_000).coerceAtLeast(1)
            "$base WARNING: location may be OUTDATED (last known, from $time, $mins min ago): $link Please call them now."
        } else {
            "$base Location as of $time: $link Please call them now."
        }
    }

    fun test(name: String) = "[TEST] IoTrix test message from $name. This is not an emergency."
}
