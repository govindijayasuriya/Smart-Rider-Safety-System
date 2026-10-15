package com.iotrix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iotrix.app.board.BoardStatus
import com.iotrix.app.core.Conn
import java.util.Locale

@Composable
fun DashboardScreen(
    rideActive: Boolean,
    conn: Conn,
    status: BoardStatus?,
    contactCount: Int,
    notice: String?,
    onToggleRide: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContacts: () -> Unit,
) {
    val connected = conn == Conn.Connected
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text("IoTrix", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = C.Text)
            Text(
                if (rideActive) "Ride monitoring is on" else if (connected) "Ready to ride" else "Connect your board to start",
                color = C.Sub,
            )
        }

        notice?.let {
            AppCard(container = C.TealLight) { Text(it, color = C.Text, fontWeight = FontWeight.SemiBold) }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = null,
                tint = if (connected) C.Teal else Color(0xFFB8C2CC), modifier = Modifier.size(140.dp),
            )
        }

        val (title, sub) = when (conn) {
            Conn.Connected -> "Board connected" to (
                (status?.deviceId ?: "") + "   tilt " +
                    String.format(Locale.US, "%.0f", status?.tiltDeg ?: 0.0) + "\u00B0"
                )
            Conn.Connecting -> "Connecting to the board..." to "Please wait"
            Conn.Disconnected -> "Board disconnected" to
                (if (rideActive) "Trying to reconnect..." else "Check the board IP in Settings")
            Conn.Off -> "Board not set up" to "Tap to enter the board IP"
        }
        AppCard(onClick = onOpenSettings, container = if (connected) C.TealLight else Color.White) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (connected) C.Teal else if (conn == Conn.Disconnected) C.Red else Color(0xFFB8C2CC)),
                    contentAlignment = Alignment.Center,
                ) {
                    when (conn) {
                        Conn.Connecting -> CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                        Conn.Connected -> Icon(Icons.Filled.Check, null, tint = Color.White)
                        Conn.Disconnected -> Icon(Icons.Filled.Warning, null, tint = Color.White)
                        Conn.Off -> Icon(Icons.Filled.Sensors, null, tint = Color.White)
                    }
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(sub, color = C.Sub, fontSize = 13.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Sub)
            }
        }

        AppCard(onClick = onOpenContacts) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.People, null, tint = C.Teal, modifier = Modifier.size(30.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Emergency contacts", fontWeight = FontWeight.Bold)
                        Text("$contactCount", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (contactCount > 0) "They get an SMS with your location" else "Add someone to alert in an emergency",
                        color = C.Sub, fontSize = 12.sp,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Sub)
            }
        }

        Spacer(Modifier.height(4.dp))
        AppButton(
            text = if (rideActive) "Stop Ride" else "Start Ride",
            onClick = onToggleRide,
            modifier = Modifier.fillMaxWidth(),
            kind = if (rideActive) BtnKind.Outline else BtnKind.Primary,
            height = 64.dp,
        )
        Text(
            "With a ride running, monitoring keeps working when the screen is locked.",
            color = C.Sub, fontSize = 12.sp,
        )
    }
}
