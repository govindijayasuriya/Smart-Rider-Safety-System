package com.iotrix.app.board

import android.os.SystemClock
import com.iotrix.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class BoardStatus(
    val deviceId: String,
    val eventId: String,
    val state: String, // ARMED | PENDING | CANCELLED | CONFIRMED
    val tiltDeg: Double,
    val remainingMs: Long,
    val fw: String,
)

sealed class CancelResult {
    object Cancelled : CancelResult()
    object TooLate : CancelResult()
    data class Failed(val message: String) : CancelResult()
}

fun Throwable.boardMessage(): String = when (this) {
    is SocketTimeoutException -> "The board did not respond."
    is ConnectException -> "Can't connect to the board. Check its IP address and that it is on the hotspot."
    else -> message ?: "Connection problem."
}

/** Talks to the ESP8266 using the API in API.md. All calls are plain HTTP on the hotspot. */
object BoardClient {
    private val demo get() = Prefs.boardIp.equals("demo", ignoreCase = true)

    suspend fun status(): BoardStatus = withContext(Dispatchers.IO) {
        if (demo) return@withContext FakeBoard.status()
        val (code, body) = request("GET", "/status", null, 1000)
        if (code != 200) throw IOException(describe(code, body))
        val o = JSONObject(body)
        BoardStatus(
            deviceId = o.optString("deviceId", ""),
            eventId = o.optString("eventId", ""),
            state = o.getString("state"),
            tiltDeg = o.optDouble("tiltDeg", 0.0),
            remainingMs = o.optLong("remainingMs", 0L),
            fw = o.optString("fw", ""),
        )
    }

    suspend fun cancel(eventId: String): CancelResult = withContext(Dispatchers.IO) {
        try {
            if (demo) return@withContext FakeBoard.cancel(eventId)
            val (code, body) = request("POST", "/cancel", JSONObject().put("eventId", eventId).toString(), 2500)
            val state = try { JSONObject(body).optString("state", "") } catch (e: Exception) { "" }
            when {
                code == 200 && state == "CANCELLED" -> CancelResult.Cancelled
                code == 409 && state == "CONFIRMED" -> CancelResult.TooLate
                else -> CancelResult.Failed(describe(code, body))
            }
        } catch (e: Exception) {
            CancelResult.Failed(e.boardMessage())
        }
    }

    /** Tells the board the confirmed event was handled. Returns true on HTTP 200. */
    suspend fun ack(eventId: String): Boolean = withContext(Dispatchers.IO) {
        if (demo) return@withContext FakeBoard.ack(eventId)
        val (code, _) = request("POST", "/ack", JSONObject().put("eventId", eventId).toString(), 2500)
        code == 200
    }

    /** Makes the board treat the current position as upright (0 degrees). */
    suspend fun calibrate(): String = withContext(Dispatchers.IO) {
        if (demo) return@withContext "Calibrated (demo board)."
        val (code, body) = request("POST", "/calibrate", "{}", 4000)
        if (code != 200) throw IOException(describe(code, body))
        "Calibrated. This position is now 0 degrees."
    }

    private fun describe(code: Int, body: String): String {
        val err = try { JSONObject(body).optString("error", "") } catch (e: Exception) { "" }
        return when {
            code == 401 -> "Wrong pairing token."
            err == "not_armed" -> "The board is busy with an event. Try again when it is idle."
            err.isNotEmpty() -> "Board error: $err"
            else -> "Board returned HTTP $code."
        }
    }

    private fun request(method: String, path: String, json: String?, timeoutMs: Int): Pair<Int, String> {
        val ip = Prefs.boardIp
        if (ip.isEmpty()) throw IOException("Board IP is not set.")
        val conn = URL("http://$ip$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer ${Prefs.pairingToken}")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Connection", "close")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Built-in fake board so the whole app can be tested without hardware.
 * Set the board IP to "demo" in Settings, then use "Simulate fall".
 * It follows exactly the same state machine as the real firmware.
 */
object FakeBoard {
    private var state = "ARMED"
    private var eventId = ""
    private var counter = 0
    private var deadline = 0L
    private var cancelledUntil = 0L

    @Synchronized
    fun trigger() {
        if (state == "ARMED") {
            counter++
            eventId = "demo-$counter"
            state = "PENDING"
            deadline = SystemClock.elapsedRealtime() + 15_000
        }
    }

    @Synchronized
    fun status(): BoardStatus {
        val now = SystemClock.elapsedRealtime()
        if (state == "PENDING" && now >= deadline) state = "CONFIRMED"
        if (state == "CANCELLED" && now >= cancelledUntil) {
            state = "ARMED"
            eventId = ""
        }
        val remaining = if (state == "PENDING") deadline - now else 0L
        return BoardStatus("DEMO-BOARD", eventId, state, if (state == "ARMED") 4.0 else 66.0, remaining, "demo")
    }

    @Synchronized
    fun cancel(id: String): CancelResult {
        status()
        val now = SystemClock.elapsedRealtime()
        return when {
            state == "PENDING" && id == eventId -> {
                state = "CANCELLED"
                cancelledUntil = now + 5_000
                CancelResult.Cancelled
            }
            state == "CANCELLED" && id == eventId -> CancelResult.Cancelled
            state == "CONFIRMED" -> CancelResult.TooLate
            else -> CancelResult.Failed("No such event.")
        }
    }

    @Synchronized
    fun ack(id: String): Boolean {
        if (state == "CONFIRMED" && id == eventId) {
            state = "ARMED"
            eventId = ""
        }
        return true
    }
}
