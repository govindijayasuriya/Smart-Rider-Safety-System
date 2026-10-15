package com.iotrix.app.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.iotrix.app.MainActivity
import com.iotrix.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Start Ride" starts this foreground service. It keeps the process, the CPU, the Wi-Fi radio
 * and GPS alive with the screen locked, and runs RideMonitor (the board polling loop).
 */
class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannels(this)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, 1, monitorNotification("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            Prefs.rideActive = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Prefs.rideActive) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iotrix:ride").apply { acquire() }
        }
        if (wifiLock == null) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iotrix:wifi").apply { acquire() }
            } catch (_: Exception) {
            }
        }

        LocationHelper.startTracking()
        RideMonitor.start()

        if (collectJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            collectJob = scope.launch {
                RideMonitor.conn.collect { c ->
                    val text = when (c) {
                        Conn.Connected -> "Ride monitoring active. Board connected."
                        Conn.Disconnected -> "Board disconnected. Reconnecting..."
                        else -> "Connecting to the board..."
                    }
                    nm.notify(1, monitorNotification(text))
                }
            }
        }
        return START_STICKY
    }

    private fun monitorNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("IoTrix ride")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        RideMonitor.stop()
        LocationHelper.stopTracking()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
        running = false
        super.onDestroy()
    }

    companion object {
        const val MONITOR_CHANNEL = "monitor"
        const val ALERT_CHANNEL = "alerts"
        private const val PENDING_ID = 2
        private const val EMERGENCY_ID = 3

        @Volatile
        var running = false

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RideService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RideService::class.java))
        }

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(MONITOR_CHANNEL, "Ride monitoring", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "Fall alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // the alarm is played by AlertPlayer
                }
            )
        }

        private fun openApp(ctx: Context, code: Int): PendingIntent {
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        @SuppressLint("MissingPermission")
        fun notifyPending(ctx: Context) {
            val pi = openApp(ctx, 1)
            val n = NotificationCompat.Builder(ctx, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Possible fall detected")
                .setContentText("Tap to cancel if you are safe")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(PENDING_ID, n) } catch (_: Exception) {}
        }

        fun clearPending(ctx: Context) {
            try { NotificationManagerCompat.from(ctx).cancel(PENDING_ID) } catch (_: Exception) {}
        }

        @SuppressLint("MissingPermission")
        fun notifyEmergency(ctx: Context, text: String) {
            val pi = openApp(ctx, 2)
            val n = NotificationCompat.Builder(ctx, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("IoTrix emergency")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(EMERGENCY_ID, n) } catch (_: Exception) {}
        }

        fun clearEmergency(ctx: Context) {
            try { NotificationManagerCompat.from(ctx).cancel(EMERGENCY_ID) } catch (_: Exception) {}
        }
    }
}
