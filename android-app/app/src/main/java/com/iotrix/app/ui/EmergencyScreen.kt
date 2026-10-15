package com.iotrix.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iotrix.app.core.EmergencyInfo
import com.iotrix.app.core.RideMonitor
import com.iotrix.app.core.SendState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shown after the board reports CONFIRMED: SMS progress, location info, retry. */
@Composable
fun EmergencyScreen(e: EmergencyInfo) {
    val ctx = LocalContext.current
    val time = SimpleDateFormat("HH:mm", Locale.US)
    val failed = e.results.any { it.state == SendState.Failed }
    val sent = e.results.count { it.state == SendState.Sent }

    Column(
        Modifier.fillMaxSize().background(C.Red).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Emergency alert", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            when {
                e.noContacts -> "No emergency contacts are saved. Call someone for help now."
                !e.done -> "Sending messages to your contacts..."
                failed && sent == 0 -> "The messages could not be sent. Call your contacts directly."
                failed -> "Some messages could not be sent."
                else -> "Your contacts have been alerted."
            },
            color = Color(0xFFFFE3E3),
        )

        AppCard {
            Text("Location", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            val fix = e.fix
            val fixTime = if (fix != null) time.format(Date(fix.timeMs)) else ""
            Text(
                when {
                    e.noContacts -> "-"
                    e.message.isEmpty() -> "Finding your location..."
                    fix == null -> "Location unavailable. The message was sent without a location."
                    e.stale -> "Using your last known location from $fixTime. It may be outdated, and the message says so."
                    else -> "Location shared (as of $fixTime)."
                },
                color = if (e.stale || (fix == null && e.message.isNotEmpty())) C.Red else C.Sub,
                fontSize = 14.sp,
            )
            if (fix != null) {
                Spacer(Modifier.height(8.dp))
                AppButton(
                    "Open in Maps",
                    {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("geo:${fix.lat},${fix.lng}?q=${fix.lat},${fix.lng}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    Modifier.fillMaxWidth(), kind = BtnKind.Outline,
                )
            }
        }

        if (!e.noContacts) {
            AppCard {
                Text("Messages", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                e.results.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.contact.name)
                        when (r.state) {
                            SendState.Sending -> Text("Sending...", color = C.Sub)
                            SendState.Sent -> Text("Sent", color = C.Teal, fontWeight = FontWeight.Bold)
                            SendState.Failed -> Text("Failed", color = C.Red, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (failed && e.done) {
            AppButton("Retry failed messages", { RideMonitor.retryFailed() }, Modifier.fillMaxWidth(), kind = BtnKind.Outline)
        }
        AppButton("Close \u2014 I am OK", { RideMonitor.dismissEmergency() }, Modifier.fillMaxWidth(), kind = BtnKind.Outline, height = 60.dp)
    }
}
