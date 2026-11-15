package ke.tafiti.pumppay

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/*
  The three detail pages behind the dashboard: solar array, controller, pump.

  What each one can honestly show is limited by what the board measures. It has
  ONE current and ONE voltage input, both on the pump line, and an internal chip
  temperature. So:

    - the solar array's numbers are WORKED BACK from the pump line, assuming the
      controller's efficiency. Nothing is measured on the panel itself.
    - the controller's input voltage is the nominal system voltage the owner
      typed in; it is not measured.
    - the controller's temperature is the temperature of the processor chip.
    - the pump has no temperature sensor, so none is shown - a dash, with the
      reason, not a number made to look real.

  Anything that is estimated or assumed says so on the page.
*/

/** One local calendar day of history. */
private class DayTotals(val label: String, val minutes: Int, val vah: Int)

/** Per-day totals for the last [n] days ending today, in the phone's zone. */
private fun dailyTotals(store: UsageStore, n: Int, zone: ZoneId): List<DayTotals> {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(System.currentTimeMillis() / 1000), zone)
    val midnight = now.toLocalDate().atStartOfDay(zone)
    val start = midnight.minusDays((n - 1).toLong())
    val end = midnight.plusDays(1)

    val mins = IntArray(n)
    val vah = IntArray(n)
    var t = start
    while (t.isBefore(end)) {
        val d = ChronoUnit.DAYS.between(start.toLocalDate(), t.toLocalDate()).toInt()
        val hour = t.toEpochSecond() / 3600
        if (d in 0 until n) {
            mins[d] += store.at(hour)
            vah[d] += store.vahAt(hour)
        }
        t = t.plusHours(1)
    }
    return (0 until n).map {
        val day = start.plusDays(it.toLong())
        DayTotals(
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + day.dayOfMonth,
            mins[it], vah[it]
        )
    }
}

/** VAh per local hour of day, 24 values, over the last [days] days. */
private fun energyByHour(store: UsageStore, days: Int, zone: ZoneId): IntArray {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(System.currentTimeMillis() / 1000), zone)
    val midnight = now.toLocalDate().atStartOfDay(zone)
    var t = midnight.minusDays((days - 1).toLong())
    val end = midnight.plusDays(1)
    val out = IntArray(24)
    while (t.isBefore(end)) {
        out[t.hour] += store.vahAt(t.toEpochSecond() / 3600)
        t = t.plusHours(1)
    }
    return out
}

private fun kvah(vah: Double): String =
    if (vah >= 10_000) String.format("%.1f", vah / 1000) else String.format("%.2f", vah / 1000)

private fun hourLabel(h: Int): String = "${if (h % 12 == 0) 12 else h % 12}${if (h < 12) "am" else "pm"}"

@Composable
private fun Row2(label: String, value: String, valueColor: Color = C.Text) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = C.Dim, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
@Composable
fun SolarPage(s: Status, live: Boolean, store: UsageStore, systemVolts: Float, onGo: (Page) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val version = store.version
    val zone = remember { ZoneId.systemDefault() }

    val running = live && s.pumping && s.vOk && s.aOk
    val p = if (running) s.va / CONTROLLER_EFFICIENCY else 0.0
    val volts = if (systemVolts > 0f) systemVolts.toDouble() else 12.0
    val amps = p / volts


    // The panel, large, then its figures. Only the power comes from a
    // measurement; the voltage is the system's nominal one, so the current is an estimate.
    HeroPicture(Page.Solar, s, live)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Tile("V", if (live) String.format("%.0f V", volts) else "—", Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Tile("I", if (running) String.format("%.1f A", amps) else "—", Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Tile("P", if (running) "${p.roundToInt()} W" else "—", Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Text(
        when {
            !live -> "Not connected"
            running -> "Estimated from the pump line · V is the system's nominal voltage"
            else -> "The pump is not running, so no power is being drawn from the array"
        },
        color = C.Dim, fontSize = 11.sp
    )

    SolarSettings()

    val all = dailyTotals(store, 90, zone)
    val hasEnergy = all.any { it.vah > 0 }
    Spacer(Modifier.height(12.dp))

    if (!hasEnergy) {
        CardBox {
            Text("No solar data recorded yet", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "The controller records the energy the pump draws each hour while it runs, and the app " +
                    "collects it each time you connect. Connect while the pump is running, then check back.",
                color = C.Dim, fontSize = 13.sp
            )
        }
        return
    }

    fun sum(n: Int) = all.takeLast(n).sumOf { it.vah } / CONTROLLER_EFFICIENCY
    val today = all.last().vah / CONTROLLER_EFFICIENCY
    val active = all.filter { it.vah > 0 }
    val avg = if (active.isEmpty()) 0.0 else active.sumOf { it.vah } / CONTROLLER_EFFICIENCY / active.size

    CardBox {
        SectionLabel("TODAY")
        Spacer(Modifier.height(6.dp))
        Row {
            Text(kvah(today), color = C.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("  kVAh", color = C.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 22.dp))
        }
        Text("Average per day  ${kvah(avg)} kVAh", color = C.Dim, fontSize = 13.sp)
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("EACH DAY · SWIPE FOR EARLIER")
        Spacer(Modifier.height(14.dp))
        FigureBars(
            all.map { it.vah }, all.map { it.label }, all.size - 1, 3f,
            { v -> kvah(v / CONTROLLER_EFFICIENCY) + " kVAh" },
            scrollable = true
        )
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("SUMMARY")
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("per day", kvah(today), Modifier.weight(1f))
            Tile("per week", kvah(sum(7)), Modifier.weight(1f))
            Tile("per month", kvah(sum(30)), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text("kVAh · today, last 7 days, last 30 days", color = C.Dim, fontSize = 11.sp)
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "The controller measures the power going to the pump, not the panel. The array figure is that power " +
            "divided by the controller's assumed ${(CONTROLLER_EFFICIENCY * 100).roundToInt()}% efficiency, " +
            "recorded only while the pump runs. kVAh is apparent energy, close to kWh but not the same.",
        color = C.Dim, fontSize = 11.sp, lineHeight = 16.sp
    )
}

// ---------------------------------------------------------------------------
@Composable
fun ControllerPage(s: Status, live: Boolean, systemVolts: Float, onDisconnect: () -> Unit = {}, onConnect: () -> Unit = {}) {
    val haveVa = live && s.vOk && s.aOk
    val running = haveVa && s.pumping
    val outVa = if (haveVa) s.va else 0.0
    val inVa = outVa / CONTROLLER_EFFICIENCY
    val hasTemp = live && !s.tempC.isNaN()
    val amber = Color(0xFFF5B301)

    val (tempWord, tempColor) = when {
        !hasTemp -> "—" to C.Dim
        s.tempC < 70 -> "Normal" to C.Pump
        s.tempC < 85 -> "Warm" to C.Text
        else -> "Hot" to C.Bad
    }

    HeroPicture(Page.Controller, s, live)
    Spacer(Modifier.height(8.dp))
    ControllerSettings(s, live, onDisconnect, onConnect)

    // Status at a glance
    CardBox {
        SectionLabel("STATUS")
        Spacer(Modifier.height(6.dp))
        Row2("Controller", if (live) "Online" else "Offline", if (live) C.Pump else C.Dim)
        Row2(
            "Pump",
            when { !live -> "—"; running -> "Pumping"; s.noLoad() -> "Not connected"; else -> "Idle" },
            if (running) C.Pump else if (live && s.noLoad()) C.Bad else C.Text
        )
        Row2(
            "Billing",
            when { !live -> "—"; !s.billingOn -> "Inactive"; s.tokenLocked -> "Active · token used up"; else -> "Active" },
            if (live && s.billingOn && s.tokenLocked) amber else if (live && s.billingOn) C.Pump else C.Dim
        )
        Row2("Efficiency", "${(CONTROLLER_EFFICIENCY * 100).roundToInt()}%  assumed")
        Row2(
            "Temperature",
            if (hasTemp) "${String.format("%.1f", s.tempC)} °C · $tempWord" else "—",
            if (hasTemp) tempColor else C.Dim
        )
    }

    // Power in, power out: the same three figures side by side.
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            CardBox {
                SectionLabel("POWER IN")
                Spacer(Modifier.height(6.dp))
                Row2("V", if (live && systemVolts > 0f) "${trim(systemVolts)} V" else "—")
                Row2("I", if (running && systemVolts > 0f) "${String.format("%.2f", inVa / systemVolts)} A" else "—")
                Row2("P", if (running) "${inVa.roundToInt()} W" else "—")
            }
        }
        Box(Modifier.weight(1f)) {
            CardBox {
                SectionLabel("POWER OUT")
                Spacer(Modifier.height(6.dp))
                Row2("V", fmt(s.volts, live && s.vOk, 1) + if (live && s.vOk) " V" else "")
                Row2("I", fmt(s.amps, live && s.aOk, 2) + if (live && s.aOk) " A" else "")
                Row2("P", fmt(s.va, haveVa, 0) + if (haveVa) " W" else "")
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Power out is measured on the pump line. Power in is worked out from it using the assumed " +
            "efficiency, with the system voltage set under Solar > Advanced settings, so it is an estimate.",
        color = C.Dim, fontSize = 11.sp, lineHeight = 16.sp
    )
}

private fun trim(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

// ---------------------------------------------------------------------------
@Composable
fun PumpDetailPage(s: Status, live: Boolean, store: UsageStore, flowLpm: Float, onGo: (Page) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val version = store.version

    val zone = remember { ZoneId.systemDefault() }
    val haveVa = live && s.vOk && s.aOk

    val condition = when {
        !live -> "—" to C.Dim
        s.manualOff -> "Switched off" to Color(0xFFF5B301)
        s.tokenLocked -> "Token expired" to Color(0xFFF5B301)
        s.noLoad() -> "Pump not connected" to C.Bad
        s.state == "NO SIGNAL" -> "Sensor fault" to C.Bad
        s.pumping -> "Running normally" to C.Pump
        else -> "Stopped" to C.Text
    }

    HeroPicture(Page.PumpDetail, s, live)
    Spacer(Modifier.height(8.dp))
    PumpSettings()

    CardBox {
        SectionLabel("CONDITION")
        Spacer(Modifier.height(8.dp))
        Text(condition.first, color = condition.second, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            when {
                !live -> "Not connected"
                s.tokenLocked -> "Token used up - top up to unlock the pump"
                s.noLoad() -> "No load detected on the pump line - check the pump and its wiring"
                s.pumping -> "Running for ${secText(s.runCurSecs)}"
                s.state == "NO SIGNAL" -> "The current sensor is not answering, so run state is unknown"
                else -> "Waiting to start"
            },
            color = C.Dim,
            fontSize = 13.sp
        )
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("POWER")
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                if (haveVa) String.format("%,d", s.va.roundToInt()) else "—",
                color = if (haveVa && s.pumping) C.Pump else if (haveVa) C.Text else C.Dim,
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold
            )
            if (haveVa) Text("  VA", color = C.Dim, fontSize = 16.sp, modifier = Modifier.padding(top = 26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row2("Voltage", fmt(s.volts, live && s.vOk, 1) + if (live && s.vOk) " V" else "")
        Row2("Current", fmt(s.amps, live && s.aOk, 2) + if (live && s.aOk) " A" else "")
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("TEMPERATURE")
        Spacer(Modifier.height(8.dp))
        Text("—", color = C.Dim, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            "Not measured. There is no temperature sensor on the pump, and the " +
                "controller's own chip temperature says nothing about the motor.",
            color = C.Dim,
            fontSize = 12.sp
        )
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("RUN TIME")
        Spacer(Modifier.height(8.dp))
        Row2("Today", if (live && s.timeKnown) secText(s.runTodaySecs) else "—")
        Row2("Total", if (live) secText(s.runTotalSecs) else "—")
        Row2("Starts", if (live) s.starts.toString() else "—")
        if (flowLpm > 0f && live && s.timeKnown) {
            Row2("Water today", "≈ ${String.format("%,d", (s.runTodaySecs / 60.0 * flowLpm).roundToInt())} litres", C.Pump)
        }
    }

    val series = dailyTotals(store, 7, zone)
    if (series.any { it.vah > 0 }) {
        Spacer(Modifier.height(12.dp))
        CardBox {
            SectionLabel("ENERGY USED, LAST 7 DAYS")
            Spacer(Modifier.height(14.dp))
            FigureBars(
                series.map { it.vah }, series.map { it.label }, series.indices.maxByOrNull { series[it].vah } ?: -1, 3f,
                { v -> kvah(v / CONTROLLER_EFFICIENCY) + " kVAh" }
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                series.forEach { Text(it.label.take(1), color = C.Dim, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${kvah(series.sumOf { it.vah }.toDouble())} kVAh this week",
                color = C.Dim,
                fontSize = 12.sp
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Water usage and charts  ›",
        color = C.Pump,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable { onGo(Page.Usage) }
    )
}
