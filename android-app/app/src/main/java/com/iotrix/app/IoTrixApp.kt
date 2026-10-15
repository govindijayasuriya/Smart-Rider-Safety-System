package com.iotrix.app

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.iotrix.app.board.BoardClient
import com.iotrix.app.board.BoardStatus
import com.iotrix.app.core.Conn
import com.iotrix.app.core.Messages
import com.iotrix.app.core.Perms
import com.iotrix.app.core.RideMonitor
import com.iotrix.app.core.RideService
import com.iotrix.app.core.SmsSender
import com.iotrix.app.data.Contact
import com.iotrix.app.data.Prefs
import com.iotrix.app.ui.C
import com.iotrix.app.ui.ContactsScreen
import com.iotrix.app.ui.DashboardScreen
import com.iotrix.app.ui.EmergencyScreen
import com.iotrix.app.ui.SettingsScreen
import com.iotrix.app.ui.WarningScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun IoTrixApp() {
    val ctx = LocalContext.current

    var tab by remember { mutableIntStateOf(0) }
    var contacts by remember { mutableStateOf(Prefs.contacts()) }
    var rideActive by remember { mutableStateOf(Prefs.rideActive) }
    var previewConn by remember { mutableStateOf(Conn.Off) }
    var previewStatus by remember { mutableStateOf<BoardStatus?>(null) }
    var afterPerm by remember { mutableStateOf<(() -> Unit)?>(null) }

    val liveConn by RideMonitor.conn.collectAsState()
    val liveStatus by RideMonitor.status.collectAsState()
    val pending by RideMonitor.pendingEvent.collectAsState()
    val emergency by RideMonitor.emergency.collectAsState()
    val notice by RideMonitor.notice.collectAsState()

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val action = afterPerm
        afterPerm = null
        action?.invoke()
    }

    fun withPermissions(needLocation: Boolean, action: () -> Unit) {
        fun ok() = Perms.hasSms(ctx) && (!needLocation || Perms.hasLocation(ctx))
        if (ok()) {
            action()
        } else {
            afterPerm = {
                if (ok()) action()
                else toast(if (needLocation) "SMS and location permissions are needed." else "SMS permission is needed.")
            }
            val list = mutableListOf(Manifest.permission.SEND_SMS)
            if (needLocation) {
                list.add(Manifest.permission.ACCESS_FINE_LOCATION)
                list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(list.toTypedArray())
        }
    }

    fun startRide() {
        when {
            Prefs.boardIp.isBlank() -> { toast("Enter the board IP in Settings first."); tab = 2 }
            contacts.isEmpty() -> { toast("Add an emergency contact first."); tab = 1 }
            else -> withPermissions(true) {
                Prefs.rideActive = true
                rideActive = true
                RideService.start(ctx)
            }
        }
    }

    fun stopRide() {
        Prefs.rideActive = false
        rideActive = false
        RideService.stop(ctx)
    }

    fun saveContact(c: Contact) {
        contacts = if (contacts.any { it.id == c.id }) contacts.map { if (it.id == c.id) c else it } else contacts + c
        Prefs.saveContacts(contacts)
    }

    fun deleteContact(c: Contact) {
        contacts = contacts.filter { it.id != c.id }
        Prefs.saveContacts(contacts)
    }

    fun sendTestSms() {
        if (contacts.isEmpty()) {
            toast("Add a contact first.")
            return
        }
        withPermissions(false) {
            val name = Prefs.riderName.ifBlank { "A rider" }
            contacts.forEach { c ->
                SmsSender.send(ctx, c.phone, Messages.test(name)) { ok ->
                    toast(if (ok) "Test SMS sent to ${c.name}" else "Could not send to ${c.name}")
                }
            }
        }
    }

    // Resume a ride that was running when the app was closed.
    LaunchedEffect(Unit) {
        if (Prefs.rideActive) {
            if (Perms.hasRide(ctx)) {
                if (!RideService.running) RideService.start(ctx)
            } else {
                Prefs.rideActive = false
                rideActive = false
            }
        }
    }

    // Connection status before a ride starts (only while the app is open).
    LaunchedEffect(rideActive) {
        if (!rideActive) {
            while (true) {
                try {
                    previewStatus = BoardClient.status()
                    previewConn = Conn.Connected
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    previewStatus = null
                    previewConn = if (Prefs.boardIp.isBlank()) Conn.Off else Conn.Disconnected
                }
                delay(2000)
            }
        }
    }

    val em = emergency
    when {
        em != null -> EmergencyScreen(em)
        pending != null -> WarningScreen()
        else -> Scaffold(
            containerColor = C.Bg,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = C.Teal, selectedTextColor = C.Teal, indicatorColor = C.TealLight,
                    )
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Home") }, colors = colors)
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.People, null) }, label = { Text("Contacts") }, colors = colors)
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") }, colors = colors)
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> DashboardScreen(
                        rideActive = rideActive,
                        conn = if (rideActive) liveConn else previewConn,
                        status = if (rideActive) liveStatus else previewStatus,
                        contactCount = contacts.size,
                        notice = notice,
                        onToggleRide = { if (rideActive) stopRide() else startRide() },
                        onOpenSettings = { tab = 2 },
                        onOpenContacts = { tab = 1 },
                    )
                    1 -> ContactsScreen(contacts, ::saveContact, ::deleteContact)
                    else -> SettingsScreen(onSendTestSms = { sendTestSms() })
                }
            }
        }
    }
}
