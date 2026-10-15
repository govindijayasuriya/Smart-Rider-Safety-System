package com.iotrix.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Keeps a recent location while riding, so an emergency message can still carry a
 * location (marked as old) even if a fresh GPS fix is not available in time.
 */
object LocationHelper {
    data class Fix(val lat: Double, val lng: Double, val timeMs: Long)

    private lateinit var fused: FusedLocationProviderClient
    @Volatile var lastFix: Fix? = null
    private var callback: LocationCallback? = null

    fun init(ctx: Context) {
        fused = LocationServices.getFusedLocationProviderClient(ctx.applicationContext)
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        stopTracking()
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000)
            .setMinUpdateIntervalMillis(5_000)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { lastFix = it.toFix() }
            }
        }
        callback = cb
        try {
            fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
        }
    }

    fun stopTracking() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    private fun Location.toFix() = Fix(latitude, longitude, if (time > 0) time else System.currentTimeMillis())

    /** Fresh fix if possible (8 s), otherwise the last cached fix, otherwise Android's last known location. */
    suspend fun bestFix(): Fix? {
        val fresh = withTimeoutOrNull(8_000) { currentLocation() }
        if (fresh != null) {
            val f = fresh.toFix()
            lastFix = f
            return f
        }
        lastFix?.let { return it }
        return withTimeoutOrNull(3_000) { lastKnown() }?.toFix()
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? = suspendCancellableCoroutine { cont ->
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(): Location? = suspendCancellableCoroutine { cont ->
        try {
            fused.lastLocation
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
