package com.iotrix.app.core

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/** Sends a normal SMS from the phone's SIM (works without mobile data). */
object SmsSender {
    private var counter = 0

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** onDone(true) only if the radio accepted every part of the message. */
    fun send(ctx: Context, phone: String, text: String, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        try {
            val sms = manager(app)
            val parts = sms.divideMessage(text)
            val id = ++counter
            val action = "com.iotrix.app.SMS_SENT_$id"
            var remaining = parts.size
            var allOk = true

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (resultCode != Activity.RESULT_OK) allOk = false
                    remaining--
                    if (remaining <= 0) {
                        try { app.unregisterReceiver(this) } catch (_: Exception) {}
                        onDone(allOk)
                    }
                }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

            val pi = PendingIntent.getBroadcast(
                app, id, Intent(action).setPackage(app.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val sent = ArrayList<PendingIntent>(parts.map { pi })
            sms.sendMultipartTextMessage(phone.replace(" ", ""), null, parts, sent, null)
        } catch (e: Exception) {
            onDone(false)
        }
    }
}
