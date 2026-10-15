package com.iotrix.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Perms {
    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasSms(ctx: Context) = has(ctx, Manifest.permission.SEND_SMS)
    fun hasLocation(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasRide(ctx: Context) = hasSms(ctx) && hasLocation(ctx)
}
