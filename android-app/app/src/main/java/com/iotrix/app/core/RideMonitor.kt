package com.iotrix.app.core

import android.content.Context
import android.os.SystemClock
import com.iotrix.app.board.BoardClient
import com.iotrix.app.board.BoardStatus
import com.iotrix.app.board.CancelResult
import com.iotrix.app.board.boardMessage
import com.iotrix.app.data.Contact
import com.iotrix.app.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class Conn { Off, Connecting, Connected, Disconnected }
enum class SendState { Sending, Sent, Failed }

data class SmsResult(val contact: Contact, val state: SendState)

data class EmergencyInfo(
    val eventId: String,
    val results: List<SmsResult>,
    val fix: LocationHelper.Fix?,
    val stale: Boolean,
    val message: String,
    val done: Boolean,
    val noContacts: Boolean,
)

sealed class CancelUi {
    object Idle : CancelUi()
    object Sending : CancelUi()
    data class Failed(val message: String) : CancelUi()
}

/**
 * The app side of the ESP8266 protocol (see API.md).
 * The board owns the tilt maths and the 15 s timer. The app only:
 *  - polls GET /status (one request at a time, every 500 ms),
 *  - shows the warning while the board says PENDING,
 *  - cancels via POST /cancel,
 *  - sends the SMS when the board says CONFIRMED (once per eventId), then POST /ack.
 */
object RideMonitor {
    private const val STALE_MS = 120_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    val conn = MutableStateFlow(Conn.Off)
    val status = MutableStateFlow<BoardStatus?>(null)
    val statusAt = MutableStateFlow(0L) // SystemClock.elapsedRealtime() of the last good status
    val pendingEvent = MutableStateFlow<String?>(null)
    val cancelUi = MutableStateFlow<CancelUi>(CancelUi.Idle)
    val emergency = MutableStateFlow<EmergencyInfo?>(null)
    val notice = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)

    private val dismissed = mutableSetOf<String>()   // warnings already ended
    private val processing = mutableSetOf<String>()  // confirmed events already being handled

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun start() {
        if (loop?.isActive == true) return
        conn.value = Conn.Connecting
        loop = scope.launch { pollLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        endWarning(null)
        conn.value = Conn.Off
        status.value = null
    }

    // ------------------------------------------------------------------ polling

    private suspend fun pollLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val s = BoardClient.status()
                failures = 0
                status.value = s
                statusAt.value = SystemClock.elapsedRealtime()
                lastError.value = null
                conn.value = Conn.Connected
                onStatus(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                lastError.value = e.boardMessage()
                if (failures >= 3) conn.value = Conn.Disconnected
            }
            val interval = if (conn.value == Conn.Connected) 500L else 1000L
            delay((interval - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(50L))
        }
    }

    private fun onStatus(s: BoardStatus) {
        when (s.state) {
            "PENDING" -> {
                if (s.eventId in dismissed) return
                if (pendingEvent.value != s.eventId) {
                    pendingEvent.value = s.eventId
                    cancelUi.value = CancelUi.Idle
                    AlertPlayer.start(app)
                    RideService.notifyPending(app)
                }
            }
            "CANCELLED" -> if (pendingEvent.value != null) endWarning("Cancelled. You are marked safe.")
            "CONFIRMED" -> {
                if (pendingEvent.value != null) endWarning(null)
                handleConfirmed(s.eventId)
            }
            else -> if (pendingEvent.value != null) endWarning("The event ended without an alert.")
        }
    }

    private fun endWarning(msg: String?) {
        pendingEvent.value?.let { dismissed.add(it) }
        pendingEvent.value = null
        cancelUi.value = CancelUi.Idle
        AlertPlayer.stop()
        RideService.clearPending(app)
        if (msg != null) showNotice(msg)
    }

    private fun showNotice(msg: String) {
        notice.value = msg
        scope.launch {
            delay(6_000)
            if (notice.value == msg) notice.value = null
        }
    }

    // ------------------------------------------------------------------ cancel

    /** "Cancelled" is shown only after the board itself answers 200 + CANCELLED. */
    fun cancel() {
        val id = pendingEvent.value ?: return
        if (cancelUi.value is CancelUi.Sending) return
        cancelUi.value = CancelUi.Sending
        scope.launch {
            when (val r = BoardClient.cancel(id)) {
                is CancelResult.Cancelled -> endWarning("Cancelled. You are marked safe.")
                is CancelResult.TooLate ->
                    cancelUi.value = CancelUi.Failed("Too late: the countdown already ended and the alert is being sent.")
                is CancelResult.Failed -> cancelUi.value = CancelUi.Failed("Cancel failed: ${r.message}")
            }
        }
    }

    // ------------------------------------------------------------------ confirmed

    private fun handleConfirmed(eventId: String) {
        if (eventId.isEmpty() || eventId in processing) return
        processing.add(eventId)
        if (Prefs.isHandled(eventId)) {
            // Message was already sent earlier (ack probably got lost). Never send it twice.
            scope.launch {
                if (!ackWithRetry(eventId)) {
                    delay(3_000)
                    processing.remove(eventId)
                }
            }
        } else {
            scope.launch { runEmergency(eventId) }
        }
    }

    private suspend fun runEmergency(eventId: String) {
        val contacts = Prefs.contacts()
        val name = Prefs.riderName.ifBlank { "A rider" }
        AlertPlayer.stop()
        emergency.value = EmergencyInfo(
            eventId, contacts.map { SmsResult(it, SendState.Sending) }, null, false, "", false, contacts.isEmpty()
        )
        if (contacts.isEmpty()) {
            RideService.notifyEmergency(app, "Emergency detected, but no contacts are saved.")
            return
        }
        RideService.notifyEmergency(app, "Emergency detected. Sending alerts...")

        // Never drop the event because of a missing location.
        val fix = LocationHelper.bestFix()
        val stale = fix != null && System.currentTimeMillis() - fix.timeMs > STALE_MS
        val message = Messages.emergency(name, fix, stale)
        updateEmergency(eventId) { it.copy(fix = fix, stale = stale, message = message) }

        val okIds = mutableSetOf<String>()
        var acked = false
        var targets = contacts
        var attempt = 0
        while (targets.isNotEmpty() && attempt < 3) {
            attempt++
            okIds += sendBatch(eventId, targets, message)
            if (okIds.isNotEmpty() && !acked) {
                Prefs.markHandled(eventId) // from now on this eventId can never be sent again
                acked = ackWithRetry(eventId)
            }
            targets = contacts.filter { it.id !in okIds }
            if (targets.isNotEmpty() && attempt < 3) delay(10_000)
        }
        updateEmergency(eventId) { it.copy(done = true) }
        notifyResult(contacts.size, okIds.size)
        if (okIds.isNotEmpty() && !acked) {
            delay(3_000)
            processing.remove(eventId) // next poll will only retry the ack
        }
    }

    fun retryFailed() {
        val e = emergency.value ?: return
        val targets = e.results.filter { it.state == SendState.Failed }.map { it.contact }
        if (targets.isEmpty() || e.message.isEmpty()) return
        updateEmergency(e.eventId) { it.copy(done = false) }
        scope.launch {
            val ok = sendBatch(e.eventId, targets, e.message)
            if (ok.isNotEmpty() && !Prefs.isHandled(e.eventId)) {
                Prefs.markHandled(e.eventId)
                ackWithRetry(e.eventId)
            }
            updateEmergency(e.eventId) { it.copy(done = true) }
            val after = emergency.value
            if (after != null && after.eventId == e.eventId) {
                notifyResult(after.results.size, after.results.count { it.state == SendState.Sent })
            }
        }
    }

    /** Rider closes the emergency screen. */
    fun dismissEmergency() {
        val e = emergency.value ?: return
        emergency.value = null
        AlertPlayer.stop()
        RideService.clearEmergency(app)
        Prefs.markHandled(e.eventId) // the rider has seen it; do not alert again for this event
        scope.launch {
            if (!ackWithRetry(e.eventId)) {
                delay(3_000)
                processing.remove(e.eventId)
            }
        }
    }

    private fun notifyResult(total: Int, sent: Int) {
        RideService.notifyEmergency(
            app,
            when {
                sent == total -> "Emergency alert sent to $sent contact(s)."
                sent > 0 -> "Alert sent to $sent of $total contacts. Some failed."
                else -> "Could not send the alert. Call your contacts directly!"
            }
        )
    }

    private suspend fun sendBatch(eventId: String, targets: List<Contact>, msg: String): Set<String> =
        coroutineScope {
            targets.forEach { setResult(eventId, it.id, SendState.Sending) }
            targets.map { c ->
                async {
                    val ok = sendOne(c, msg)
                    setResult(eventId, c.id, if (ok) SendState.Sent else SendState.Failed)
                    if (ok) c.id else null
                }
            }.awaitAll().filterNotNull().toSet()
        }

    private suspend fun sendOne(c: Contact, msg: String): Boolean = suspendCancellableCoroutine { cont ->
        SmsSender.send(app, c.phone, msg) { ok -> if (cont.isActive) cont.resume(ok) }
    }

    private suspend fun ackWithRetry(eventId: String): Boolean {
        repeat(3) {
            try {
                if (BoardClient.ack(eventId)) return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(1_000)
        }
        return false
    }

    private fun setResult(eventId: String, contactId: String, state: SendState) {
        updateEmergency(eventId) { e ->
            e.copy(results = e.results.map { if (it.contact.id == contactId) it.copy(state = state) else it })
        }
    }

    private fun updateEmergency(eventId: String, f: (EmergencyInfo) -> EmergencyInfo) {
        val e = emergency.value ?: return
        if (e.eventId == eventId) emergency.value = f(e)
    }
}
