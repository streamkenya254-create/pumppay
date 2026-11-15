package ke.tafiti.pumppay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * One reading of the board, parsed from a JSON line.
 *
 * Every numeric field carries its own "ok" flag rather than using a sentinel
 * like 0 or -1. A zero and a missing reading mean opposite things on a meter,
 * and collapsing them into one value is how a display ends up confidently
 * showing 0.00 V for a channel it simply has not measured yet.
 */
data class Status(
    /** "PUMPING", "IDLE" or "NO SIGNAL" - exactly as the board words it. */
    val state: String = "NO SIGNAL",
    /** Which measurement line the controller is reading: 1 or 2. */
    val line: Int = 1,
    /** The controller's own name, "PP-" and six hex digits; what payments are made against. */
    val deviceId: String = "",
    /** The controller is serving its web page. Off in this build, to save memory. */
    val webOn: Boolean = false,
    /** Prepaid billing is being enforced by the controller. */
    val billingOn: Boolean = false,
    /** Prepaid token left, seconds of pumping; -1 when billing does not apply. */
    val tokenSecs: Long = -1,
    /** The relay has cut the pump off because the token ran out. */
    val relayLocked: Boolean = false,
    /** Switched off from the app ("pump off"). Not saved on the controller. */
    val manualOff: Boolean = false,
    val volts: Double = 0.0,
    val amps: Double = 0.0,
    val va: Double = 0.0,
    val vOk: Boolean = false,
    val aOk: Boolean = false,
    /** Seconds the pump has been running in the current run, 0 when stopped. */
    val runCurSecs: Long = 0,
    /** Run seconds since local midnight. Meaningful only when [timeKnown]. */
    val runTodaySecs: Long = 0,
    val runTotalSecs: Long = 0,
    val starts: Long = 0,
    /** The board's clock, UTC seconds; 0 until it has been told the time. */
    val epoch: Long = 0,
    /** Temperature of the controller's processor chip in deg C; NaN if not reported. */
    val tempC: Double = Double.NaN,
    val adcOk: Boolean = false,
    val eth: String = "",
    val wifi: String = "",
    val upSeconds: Long = 0,
    val lcd: List<String> = emptyList()
) {
    val pumping: Boolean get() = state == "PUMPING"
    val timeKnown: Boolean get() = epoch > 0
}

/**
 * The board's last 168 hours of pump run time, oldest first.
 *
 * [hourNow] is the UTC hour number (epoch / 3600) of the LAST entry, so entry
 * i belongs to hour (hourNow - (size - 1 - i)). Minutes, not seconds: nobody
 * reading a usage chart wants the seconds, and it keeps each frame short.
 * [vah] is the apparent energy drawn in each of those hours, volt-amp hours.
 */
data class Hist(val hourNow: Long, val minutes: List<Int>, val vah: List<Int>)

enum class Link { Idle, Connecting, Connected, Failed }

/**
 * Bluetooth SPP client for the PumpPay board.
 *
 * Only bonded devices are used - pairing happens in Android settings, not
 * here. That keeps the app out of the scanning permission entirely, and
 * pairing is a one-off the platform already has a good UI for.
 */
@SuppressLint("MissingPermission")   // callers gate on BLUETOOTH_CONNECT
class BtClient(private val scope: CoroutineScope) {

    companion object {
        /** The well-known Serial Port Profile UUID. Not ours to choose. */
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Board advertises itself under this name; see bt.cpp. */
        const val DEVICE_NAME = "PUMPPAY"
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _hist = MutableStateFlow<Hist?>(null)
    val hist: StateFlow<Hist?> = _hist

    private val _link = MutableStateFlow(Link.Idle)
    val link: StateFlow<Link> = _link

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Last plain-text line from the board - command replies, help, warnings. */
    private val _reply = MutableStateFlow<String?>(null)
    val reply: StateFlow<String?> = _reply

    fun clearReply() { _reply.value = null }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    private val adapter: BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /**
     * Paired devices, board first. The board is not required to be named
     * exactly - a second unit renamed by its owner should still be reachable -
     * so everything bonded is offered and the likely one is simply sorted up.
     */
    fun pairedDevices(): List<BluetoothDevice> {
        val all = adapter?.bondedDevices?.toList() ?: emptyList()
        return all.sortedByDescending { it.name?.contains("PUMPPAY", true) == true }
    }

    fun connect(device: BluetoothDevice) {
        if (_link.value == Link.Connecting || _link.value == Link.Connected) return

        _link.value = Link.Connecting
        _error.value = null

        scope.launch(Dispatchers.IO) {
            try {
                // No cancelDiscovery() here. It is the usual boilerplate before
                // an RFCOMM connect, but it needs BLUETOOTH_SCAN - which this
                // app deliberately does not ask for - and there is nothing to
                // cancel anyway, because nothing here ever starts a discovery.
                // Calling it threw SecurityException and killed the app on
                // every single connection attempt.
                val s = device.createRfcommSocketToServiceRecord(SPP)
                s.connect()

                socket = s
                out = s.outputStream
                _link.value = Link.Connected

                // Switch the board to the machine-readable feed. The firmware
                // starts every connection in human text so a plain terminal is
                // usable, so this has to be asked for each time.
                send("json on")

                // The board has no clock. Tell it the time and the phone's zone
                // so it can file run time under the right hour of the day, then
                // ask for the history it already holds.
                val now = System.currentTimeMillis()
                val tzMin = java.util.TimeZone.getDefault().getOffset(now) / 60000
                send("time ${now / 1000} $tzMin")
                send("hist")
                startHistPoll()

                readLoop(s.inputStream)
            } catch (e: Exception) {
                // Deliberately broad. A failed connection must never take the
                // app down with it - the user is picking devices from a list
                // and most of them will not speak SPP at all. IOException is
                // the expected case; SecurityException and the platform's own
                // odd throws are not, and an uncaught one here is a crash on a
                // background thread with no UI to report it.
                _error.value = friendly(e, device.name)
                _link.value = Link.Failed
                closeQuietly()
            }
        }
    }

    private fun readLoop(input: InputStream) {
        val buf = StringBuilder()
        val chunk = ByteArray(512)

        try {
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break

                buf.append(String(chunk, 0, n, Charsets.UTF_8))

                // Frames are newline-delimited. Anything that is not a JSON
                // object is ignored rather than treated as an error: command
                // replies and the help text share this stream, and an app that
                // fell over on seeing "staged ssid: x" would be fragile for no
                // reason.
                var nl = buf.indexOf("\n")
                while (nl >= 0) {
                    val line = buf.substring(0, nl).trim()
                    buf.delete(0, nl + 1)
                    if (line.startsWith("{")) {
                        parse(line)
                    } else if (line.isNotEmpty() && line != "clock set") {
                        // Command replies share this stream with the status
                        // frames. They are surfaced rather than dropped so a
                        // "rejected - 4 to 8 digits" reaches the user instead
                        // of a save appearing to have worked.
                        _reply.value = line
                    }
                    nl = buf.indexOf("\n")
                }

                // A run of non-newline noise must not grow without bound.
                if (buf.length > 4096) buf.setLength(0)
            }
        } catch (e: Exception) {
            // Broad for the same reason as connect(): this runs on a
            // background thread, so anything escaping it is a crash rather
            // than an error the user could act on.
            _error.value = "Connection lost"
        } finally {
            _link.value = Link.Idle
            closeQuietly()
        }
    }

    private fun parse(line: String) {
        try {
            val o = JSONObject(line)

            // History frames and status frames share the stream; tell them apart
            // by their one top-level key.
            o.optJSONObject("hist")?.let { h ->
                val arr = h.optJSONArray("m")
                val mins = ArrayList<Int>(arr?.length() ?: 0)
                if (arr != null) for (i in 0 until arr.length()) mins.add(arr.optInt(i, 0))
                val ea = h.optJSONArray("e")
                val vah = ArrayList<Int>(ea?.length() ?: 0)
                if (ea != null) for (i in 0 until ea.length()) vah.add(ea.optInt(i, 0))
                _hist.value = Hist(h.optLong("t", 0), mins, vah)
                return
            }

            val p = o.getJSONObject("pump")
            val run = o.getJSONObject("run")

            val rows = mutableListOf<String>()
            o.optJSONArray("lcd")?.let { arr ->
                for (i in 0 until arr.length()) rows.add(arr.optString(i, ""))
            }

            _status.value = Status(
                state = o.optString("state", "NO SIGNAL"),
                line = o.optInt("line", 1),
                deviceId = o.optString("id", ""),
                webOn = o.optInt("web", 0) == 1,
                billingOn = o.optJSONObject("bill")?.optInt("on", 0) == 1,
                // -1 means "billing does not apply": it is off, or no balance is known yet.
                tokenSecs = o.optJSONObject("bill")?.optLong("rem", -1) ?: -1,
                relayLocked = o.optJSONObject("bill")?.optInt("lock", 0) == 1,
                manualOff = o.optJSONObject("bill")?.optInt("off", 0) == 1,
                volts = p.optDouble("v", 0.0),
                amps = p.optDouble("a", 0.0),
                va = p.optDouble("va", 0.0),
                vOk = p.optInt("vok", 0) == 1,
                aOk = p.optInt("aok", 0) == 1,
                runCurSecs = run.optLong("cur", 0),
                runTodaySecs = run.optLong("today", 0),
                runTotalSecs = run.optLong("total", 0),
                starts = run.optLong("starts", 0),
                epoch = o.optLong("t", 0),
                tempC = o.optDouble("temp", Double.NaN),
                adcOk = o.optInt("adc", 0) == 1,
                eth = o.optString("eth", ""),
                wifi = o.optString("wifi", ""),
                upSeconds = o.optLong("up", 0),
                lcd = rows
            )
        } catch (e: Exception) {
            // A malformed frame is dropped. The next one is a second away, and
            // showing an error for a single bad line would make the app look
            // broken during ordinary radio noise.
        }
    }

    /**
     * Re-asks for the history once a minute while connected. The board files
     * run time continuously, so the current hour keeps growing; a one-off fetch
     * at connect would leave the chart a minute behind forever.
     */
    private var histJob: Job? = null

    private fun startHistPoll() {
        histJob?.cancel()
        histJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(60_000)
                send("hist")
            }
        }
    }

    /**
     * Turns a platform exception into something a person can act on.
     *
     * The message that matters is "read failed, socket might closed or timeout,
     * read ret: -1". That is what Android says when RFCOMM finds no serial
     * service at the other end - which is not a fault, it is what happens when
     * you point a serial app at headphones. Passing that string through
     * verbatim tells the user nothing except that something technical broke.
     */
    private fun friendly(e: Exception, name: String?): String {
        val who = name ?: "That device"
        val raw = e.message ?: ""

        return when {
            e is SecurityException ->
                "Bluetooth permission was refused. Grant it in Android " +
                    "Settings > Apps > PumpPay > Permissions."

            raw.contains("read failed", true) ||
                raw.contains("socket might closed", true) ||
                raw.contains("Connection refused", true) ->
                "$who is not a serial device, so there is nothing to talk to. " +
                    "Pick ${DEVICE_NAME} - and if it is not listed, the board " +
                    "has not been flashed or paired yet."

            raw.contains("Device or resource busy", true) ->
                "$who is already connected to something else. Close the other " +
                    "app, or unpair and pair again."

            raw.contains("Bluetooth is off", true) || adapter?.isEnabled != true ->
                "Bluetooth is off. Turn it on and try again."

            else -> "Could not connect to $who: $raw"
        }
    }

    fun send(cmd: String) {
        scope.launch(Dispatchers.IO) {
            try {
                out?.write((cmd + "\n").toByteArray(Charsets.UTF_8))
                out?.flush()
            } catch (e: IOException) {
                _error.value = "Send failed"
            }
        }
    }

    /**
     * Panel keys 1..4, the same events the board's own buttons produce.
     *
     * The immediate "s" afterwards is the point: status otherwise streams once
     * a second, so the mirrored LCD lagged up to a full second behind the press
     * and the panel felt broken. Asking for a frame right after the key makes
     * the screen move when the button does.
     */
    fun pressKey(n: Int) {
        send(n.toString())
        send("s")
    }

    /**
     * WiFi credentials, staged then committed - three commands, in order.
     *
     * The board stages them the same way rather than taking one long line,
     * which keeps each command short and means a half-finished change never
     * reaches its flash. Sending all three back to back is safe because the
     * socket preserves order.
     */
    fun setWifi(ssid: String, password: String) {
        send("ssid $ssid")
        send("pass $password")
        send("wifisave")
    }

    fun clearWifi() = send("wifioff")

    /** The shared key that lets this controller upload to the cloud. 12 to 64 characters. */
    fun setCloudKey(key: String) = send("cloudkey $key")

    fun clearCloud() = send("cloud off")

    /** Asks the controller to upload now and fetch the paid balance, instead of waiting. */
    fun syncNow() = send("sync")

    /** Turns prepaid billing on or off. On, the relay follows the paid balance. */
    fun setBilling(on: Boolean) = send(if (on) "billing on" else "billing off")

    /** Switch the pump off or back on. Needs the controller firmware that understands "pump on|off". */
    fun setPump(on: Boolean) = send(if (on) "pump on" else "pump off")

    /** Asks for the cloud status line; the reply shows on the Device page. */
    fun cloudStatus() = send("cloud")

    /** Switches the controller to measure line 1 or 2; it applies at once. */
    fun setLine(n: Int) = send("line $n")

    /** Four to eight digits. The board rejects anything else and says so. */
    fun setPin(pin: String) = send("pin $pin")

    fun disconnect() {
        closeQuietly()
        _link.value = Link.Idle
    }

    private fun closeQuietly() {
        histJob?.cancel()
        histJob = null
        try { socket?.close() } catch (_: IOException) { }
        socket = null
        out = null
    }
}
