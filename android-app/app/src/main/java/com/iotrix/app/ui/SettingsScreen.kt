package com.iotrix.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iotrix.app.board.BoardClient
import com.iotrix.app.board.FakeBoard
import com.iotrix.app.board.boardMessage
import com.iotrix.app.data.Prefs
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(onSendTestSms: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(Prefs.riderName) }
    var ip by remember { mutableStateOf(Prefs.boardIp) }
    var token by remember { mutableStateOf(Prefs.pairingToken) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var calResult by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        AppCard {
            Text("Your name", fontWeight = FontWeight.Bold)
            Text("Written in the SMS your contacts receive.", color = C.Sub, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                name, { name = it; Prefs.riderName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        AppCard {
            Text("ESP8266 board", fontWeight = FontWeight.Bold)
            Text(
                "Turn on your phone's 2.4 GHz hotspot. The board joins it. Read the board's IP in the Arduino Serial Monitor.",
                color = C.Sub, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                ip, { ip = it; Prefs.boardIp = it }, label = { Text("Board IP address") }, singleLine = true,
                placeholder = { Text("192.168.43.57") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                token, { token = it; Prefs.pairingToken = it }, label = { Text("Pairing token") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            AppButton(
                if (busy) "Testing..." else "Test connection",
                {
                    scope.launch {
                        busy = true
                        testResult = try {
                            val s = BoardClient.status()
                            "Connected: " + s.deviceId + ", state " + s.state + ", tilt " +
                                String.format(Locale.US, "%.0f", s.tiltDeg) + "\u00B0"
                        } catch (e: Exception) {
                            "Failed: " + e.boardMessage()
                        }
                        busy = false
                    }
                },
                Modifier.fillMaxWidth(), enabled = !busy,
            )
            testResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = if (it.startsWith("Connected")) C.Teal else C.Red, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            AppButton(
                "Calibrate (bike upright)",
                {
                    scope.launch {
                        calResult = try { BoardClient.calibrate() } catch (e: Exception) { "Failed: " + e.boardMessage() }
                    }
                },
                Modifier.fillMaxWidth(), kind = BtnKind.Outline,
            )
            Text(
                "Hold the bike upright and still, then press Calibrate. The board treats that position as 0 degrees.",
                color = C.Sub, fontSize = 12.sp,
            )
            calResult?.let {
                Text(it, color = if (it.startsWith("Failed")) C.Red else C.Teal, fontSize = 13.sp)
            }
        }

        AppCard {
            Text("Try it without the board", fontWeight = FontWeight.Bold)
            Text(
                "Demo mode uses a fake board inside the app that follows the same rules as the real one.",
                color = C.Sub, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            AppButton(
                "Use demo board",
                { ip = "demo"; Prefs.boardIp = "demo" },
                Modifier.fillMaxWidth(), kind = BtnKind.Outline,
            )
            if (ip.equals("demo", ignoreCase = true)) {
                Spacer(Modifier.height(8.dp))
                AppButton("Simulate fall (demo)", { FakeBoard.trigger() }, Modifier.fillMaxWidth(), kind = BtnKind.Outline)
                Text(
                    "Start a ride first, then press this. The real alarm, countdown and SMS flow run.",
                    color = C.Sub, fontSize = 12.sp,
                )
            }
        }

        AppCard {
            Text("Check SMS", fontWeight = FontWeight.Bold)
            Text("Sends a clearly marked test SMS to every contact from your SIM.", color = C.Sub, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            AppButton("Send test SMS", onSendTestSms, Modifier.fillMaxWidth(), kind = BtnKind.Outline)
        }

        AppCard {
            Text("Keep monitoring alive", fontWeight = FontWeight.Bold)
            Text(
                "Some phones stop background apps to save battery. Allow IoTrix to run in the background.",
                color = C.Sub, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            AppButton(
                "Battery settings",
                {
                    ctx.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                Modifier.fillMaxWidth(), kind = BtnKind.Outline,
            )
        }
    }
}
