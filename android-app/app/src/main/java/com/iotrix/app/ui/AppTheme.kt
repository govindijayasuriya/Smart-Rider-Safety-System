package com.iotrix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Teal = Color(0xFF0F9D8A)
    val TealLight = Color(0xFFE6F6F3)
    val Red = Color(0xFFE5383B)
    val RedLight = Color(0xFFFDECEC)
    val Bg = Color(0xFFFAFBFB)
    val Text = Color(0xFF0F1F2E)
    val Sub = Color(0xFF6B7785)
    val Border = Color(0xFFE8ECEF)
}

@Composable
fun IoTrixAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.Teal,
            onPrimary = Color.White,
            background = C.Bg,
            surface = Color.White,
            onSurface = C.Text,
            error = C.Red,
        ),
        content = content,
    )
}
