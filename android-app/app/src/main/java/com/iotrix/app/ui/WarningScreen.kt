package com.iotrix.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iotrix.app.core.CancelUi
import com.iotrix.app.core.Conn
import com.iotrix.app.core.RideMonitor
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ceil

/** Shown while the board reports PENDING. The time shown comes from the board's remainingMs. */
@Composable
fun WarningScreen() {
    val s by RideMonitor.status.collectAsState()
    val at by RideMonitor.statusAt.collectAsState()
    val cancel by RideMonitor.cancelUi.collectAsState()
    val conn by RideMonitor.conn.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            now = SystemClock.elapsedRealtime()
        }
    }

    val total = 15_000f
    val remaining = ((s?.remainingMs ?: 0L) - (now - at)).coerceAtLeast(0L)
    val secs = ceil(remaining / 1000.0).toInt()
    val sending = cancel is CancelUi.Sending

    Column(
        Modifier.fillMaxSize().background(C.RedLight).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(84.dp).clip(CircleShape).background(Color(0xFFFAD3D4)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(C.Red), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Warning, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Possible fall detected", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = C.Red)
        Spacer(Modifier.height(8.dp))
        Text(
            "Are you OK? If you don't cancel, your emergency contacts will be alerted with your location.",
            color = C.Sub, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp)) {
                val stroke = 10.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(
                    color = Color(0xFFF6C4C5), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(width = stroke),
                )
                drawArc(
                    color = C.Red, startAngle = -90f, sweepAngle = 360f * (remaining / total).coerceIn(0f, 1f),
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$secs", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = C.Red)
                Text("seconds to alert", color = C.Red)
            }
        }

        Spacer(Modifier.height(10.dp))
        s?.let {
            Text("Tilt " + String.format(Locale.US, "%.0f", it.tiltDeg) + "\u00B0", color = C.Sub, fontSize = 13.sp)
        }
        if (conn != Conn.Connected) {
            Spacer(Modifier.height(6.dp))
            Text("Lost connection to the board. Reconnecting...", color = C.Red, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "You can also press the handlebar button to cancel.",
            color = C.Text, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(Color(0xFFFADADB)).padding(14.dp),
        )

        (cancel as? CancelUi.Failed)?.let {
            Spacer(Modifier.height(12.dp))
            Text(it.message, color = C.Red, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))
        AppButton(
            text = if (sending) "Cancelling..." else "Cancel \u2014 I am safe",
            onClick = { RideMonitor.cancel() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !sending,
            height = 80.dp,
        )
        Spacer(Modifier.height(8.dp))
    }
}
