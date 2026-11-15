package ke.tafiti.pumppay

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/*
  System diagnostics.

  Everything here is judged from what the controller already reports - there is
  no separate test mode on the board. So a check can only say what that data can
  support: it can tell that the current sensor is not answering, but not WHY; it
  can tell that no load is drawing, but not whether the pump or its cable is at
  fault. Each result says what was seen and, where there is one, the first thing
  worth trying.

  A check that cannot be made (not connected, no clock) is reported as such. It
  is never quietly marked as passing.
*/

enum class Level { Ok, Warn, Fail, Info }

class Check(
    val title: String,
    val level: Level,
    val detail: String,
    val fix: String? = null
)

private val Amber = Color(0xFFF5B301)

private fun Level.color(): Color = when (this) {
    Level.Ok -> C.Pump
    Level.Warn -> Amber
    Level.Fail -> C.Bad
    Level.Info -> C.Dim
}

private fun Level.word(): String = when (this) {
    Level.Ok -> "PASS"
    Level.Warn -> "WARNING"
    Level.Fail -> "FAULT"
    Level.Info -> "INFO"
}

/** Runs every check against one snapshot, in the order they are shown. */
fun runChecks(s: Status, connected: Boolean, systemVolts: Float, store: UsageStore): List<Check> {
    val out = ArrayList<Check>()

    // ---- Link ---------------------------------------------------------------
    if (!connected) {
        out.add(
            Check(
                "Controller link", Level.Fail,
                "The app is not connected to the controller, so nothing else can be checked.",
                "Pair PUMPPAY in Android Settings > Bluetooth, then tap Connect on the dashboard."
            )
        )
        return out
    }
    out.add(Check("Controller link", Level.Ok, "Connected over Bluetooth and receiving live readings."))

    // ---- Measurement chain --------------------------------------------------
    out.add(
        if (s.adcOk) Check("Current sensor", Level.Ok, "The current-sense ADC is answering.")
        else Check(
            "Current sensor", Level.Fail,
            "The current-sense ADC is not answering, so the controller cannot tell whether the pump is running.",
            "Check the sensor board and its I2C wiring (SDA/SCL), then restart the controller."
        )
    )
    out.add(
        if (s.vOk) Check("Line voltage", Level.Ok, "Reading ${fmt(s.volts, true, 1)} V.")
        else Check(
            "Line voltage", Level.Fail, "No voltage reading yet.",
            "Wait a few seconds after power-up. If it persists, check the voltage sense input."
        )
    )

    // Voltage against the nominal system voltage. The board's voltage channel is
    // calibrated for one specific range, so a wild mismatch usually means the
    // calibration, not the supply.
    if (s.vOk && systemVolts > 0f) {
        val ratio = s.volts / systemVolts
        out.add(
            when {
                ratio in 0.85..1.25 -> Check(
                    "Voltage vs nominal", Level.Ok,
                    "${fmt(s.volts, true, 1)} V against ${systemVolts.roundToInt()} V nominal."
                )
                ratio > 3 || ratio < 0.3 -> Check(
                    "Voltage vs nominal", Level.Warn,
                    "${fmt(s.volts, true, 1)} V is far from the ${systemVolts.roundToInt()} V nominal system voltage.",
                    "Either the nominal voltage under Solar > Advanced settings is wrong, or the controller's voltage channel is calibrated for a different supply and needs recalibrating."
                )
                else -> Check(
                    "Voltage vs nominal", Level.Warn,
                    "${fmt(s.volts, true, 1)} V is ${abs(((ratio - 1) * 100).roundToInt())}% " +
                        (if (ratio < 1) "below" else "above") + " the ${systemVolts.roundToInt()} V nominal.",
                    if (ratio < 1) "Check the battery or array, and the cable run between them and the controller."
                    else "Check the charge settings and the supply."
                )
            }
        )
    }

    // ---- Pump ----------------------------------------------------------------
    when {
        s.manualOff -> out.add(
            Check(
                "Pump", Level.Warn,
                "The pump was switched off from the app.",
                "Tap Pump on under Water usage. It also comes back on by itself if the controller restarts."
            )
        )
        s.tokenLocked -> out.add(
            Check(
                "Pump", Level.Warn,
                "The prepaid token has run out, so the pump is locked until you top up.",
                "Top up under Billing. The relay switches back to line 1 and the pump starts."
            )
        )
        s.state == "NO SIGNAL" -> out.add(
            Check(
                "Pump", Level.Fail, "Run state is unknown because the current sensor is not answering.",
                "Fix the current sensor first."
            )
        )
        s.noLoad() -> out.add(
            Check(
                "Pump connection", Level.Fail,
                "No load on the pump line (${fmt(s.amps, s.aOk, 2)} A). The pump is not connected, or its supply is switched off.",
                "Check the pump wiring and connectors, any float or pressure switch in the line, and the pump's own isolator."
            )
        )
        s.pumping -> out.add(
            if (s.amps >= 1.0) Check("Pump", Level.Ok, "Running and drawing ${fmt(s.amps, true, 2)} A.")
            else Check(
                "Pump", Level.Warn,
                "Running but drawing only ${fmt(s.amps, true, 2)} A, which is low.",
                "The pump may be running dry or the supply is weak. Check the water level and the voltage."
            )
        )
        else -> out.add(Check("Pump", Level.Ok, "Connected and stopped, waiting to start."))
    }

    // ---- Controller health ----------------------------------------------------
    out.add(
        when {
            s.tempC.isNaN() -> Check("Controller temperature", Level.Info, "Not reported by this firmware.")
            s.tempC < 70 -> Check("Controller temperature", Level.Ok, "${String.format("%.1f", s.tempC)} °C chip temperature, normal.")
            s.tempC < 85 -> Check(
                "Controller temperature", Level.Warn,
                "${String.format("%.1f", s.tempC)} °C chip temperature, warm.",
                "Shade the enclosure and make sure it can shed heat."
            )
            else -> Check(
                "Controller temperature", Level.Fail,
                "${String.format("%.1f", s.tempC)} °C chip temperature, too hot.",
                "Switch off and let it cool. Move it out of direct sun and improve ventilation."
            )
        }
    )
    out.add(
        if (s.upSeconds < 120) Check(
            "Controller uptime", Level.Warn,
            "The controller restarted ${s.upSeconds} seconds ago.",
            "A restart is normal after power-up. If it keeps happening, check the supply for brownouts."
        )
        else Check("Controller uptime", Level.Ok, "Up for ${uptimeText(s.upSeconds)} without restarting.")
    )

    // ---- Records --------------------------------------------------------------
    out.add(
        if (s.timeKnown) Check("Controller clock", Level.Ok, "Set, so run time is filed under the right hour.")
        else Check(
            "Controller clock", Level.Warn,
            "The controller does not know the time, so hourly history and \"today\" are blank.",
            "Reconnect from the app, which sets the clock on every connection."
        )
    )
    out.add(
        if (!store.isEmpty) Check("Usage history", Level.Ok, "Collected on this phone.")
        else Check(
            "Usage history", Level.Info, "Nothing recorded yet.",
            "Leave the app connected while the pump runs."
        )
    )

    // Short cycling: a pump that starts far more often than it should is being
    // switched by something - a float switch chattering, a weak supply browning
    // out, a loose connection - and that wears the pump faster than running does.
    val hours = s.runTotalSecs / 3600.0
    if (hours >= 2.0) {
        val rate = s.starts / hours
        out.add(
            if (rate > 6.0) Check(
                "Start frequency", Level.Warn,
                "${String.format("%.1f", rate)} starts per run-hour, which is high.",
                "Look for a chattering float switch, a loose connection, or a supply that sags when the pump starts."
            )
            else Check("Start frequency", Level.Ok, "${String.format("%.1f", rate)} starts per run-hour.")
        )
    }

    // ---- Network (information only) -------------------------------------------
    out.add(
        Check(
            "Network", Level.Info,
            when {
                s.eth.isNotEmpty() -> "Ethernet ${s.eth}"
                s.wifi.isNotEmpty() -> "WiFi ${s.wifi}"
                else -> "No Ethernet or WiFi. Bluetooth is the only way in, which is fine."
            }
        )
    )

    return out
}

@Composable
fun DiagnosticsPage(
    s: Status,
    connected: Boolean,
    systemVolts: Float,
    store: UsageStore
) {
    val context = LocalContext.current

    // The checks are frozen at the moment Run is pressed. Re-evaluating them
    // live while they are being revealed would let a result change under the
    // reader, and a report that shifts as you read it is not a report.
    var results by remember { mutableStateOf<List<Check>?>(null) }
    var shown by remember { mutableStateOf(0) }
    var runId by remember { mutableStateOf(0) }

    LaunchedEffect(runId) {
        val r = results ?: return@LaunchedEffect
        shown = 0
        for (i in r.indices) {
            delay(380)
            shown = i + 1
        }
    }

    val list = results
    val running = list != null && shown < list.size


    CardBox {
        SectionLabel("SYSTEM DIAGNOSTICS")
        Spacer(Modifier.height(8.dp))
        Text(
            "Checks the link, the sensors, the pump, the controller's health and " +
                "its records, using what the controller reports. Nothing is switched " +
                "or changed.",
            color = C.Dim,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(14.dp))
        PrimaryButton(if (running) "Checking..." else if (list == null) "Run diagnostics" else "Run again", !running) {
            results = runChecks(s, connected, systemVolts, store)
            runId++
        }
    }

    if (list == null) return

    Spacer(Modifier.height(12.dp))
    CardBox {
        list.forEachIndexed { i, c ->
            if (i < shown) {
                CheckRow(c)
            } else if (i == shown) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = C.Pump, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(c.title, color = C.Dim, fontSize = 14.sp)
                }
            }
        }
    }

    if (!running) {
        val fails = list.count { it.level == Level.Fail }
        val warns = list.count { it.level == Level.Warn }
        val oks = list.count { it.level == Level.Ok }

        Spacer(Modifier.height(12.dp))
        CardBox {
            SectionLabel("RESULT")
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    fails > 0 -> "$fails fault${if (fails == 1) "" else "s"} found"
                    warns > 0 -> "Working, $warns warning${if (warns == 1) "" else "s"}"
                    else -> "All checks passed"
                },
                color = when {
                    fails > 0 -> C.Bad
                    warns > 0 -> Amber
                    else -> C.Pump
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text("$oks passed  ·  $warns warning  ·  $fails fault", color = C.Dim, fontSize = 13.sp)

            Spacer(Modifier.height(14.dp))
            WideButton("Share report", accent = C.Dim) {
                val body = buildString {
                    appendLine("PumpPay diagnostics")
                    appendLine(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
                    appendLine()
                    list.forEach {
                        appendLine("[${it.level.word()}] ${it.title}: ${it.detail}")
                        it.fix?.let { f -> appendLine("    Try: $f") }
                    }
                }
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "PumpPay diagnostics")
                            putExtra(Intent.EXTRA_TEXT, body)
                        },
                        "Share report"
                    )
                )
            }
        }
    }
}

@Composable
private fun CheckRow(c: Check) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(c.level.color(), CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                c.title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                c.level.word(), color = c.level.color(), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(c.detail, color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 21.dp))
        c.fix?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "Try: $it", color = c.level.color(), fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 21.dp)
            )
        }
    }
}
