package ke.tafiti.pumppay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
  Advanced settings, one set per page.

  The controller measures the power going to the pump and how long it runs. It cannot see the
  pump's rating, the size of the panels or the tank, so the owner says so - on the page the
  equipment belongs to: panels on Solar, the pump on Pump, the tank on Water usage, the
  controller on Controller. Each is a folded row at the foot of its page that opens in place.

  These figures turn run time into litres, size the pumping window to what the panels can carry,
  and let the assistant reason about the real installation instead of a typical one.
*/

object Hardware {
    /**
     * Hours of the day the panels can carry the pump, from the array against the pump. Plenty of
     * spare panel (1.5x the pump or more) runs it through the whole sunny middle of the day; less
     * starts it later and stops it earlier; below the pump's own rating it only just manages.
     * An estimate, not a measurement.
     */
    fun windowHours(panelWp: Float, pumpWatts: Float): Float {
        if (panelWp <= 0f || pumpWatts <= 0f) return 6f
        val ratio = panelWp / pumpWatts
        return when {
            ratio >= 1.5f -> 6f
            ratio >= 1.0f -> 3f + (ratio - 1.0f) / 0.5f * 3f
            else -> 2f
        }
    }
}

private fun num(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()
private fun shown(f: Float): String = if (f <= 0f) "" else if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

/** A folded "Advanced settings" row that opens in place. [summary] says what is set while it is folded. */
@Composable
private fun AdvancedSection(title: String, summary: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (!open) Text(summary, color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Text(if (open) "−" else "+", color = C.Dim, fontSize = 22.sp)
        }
        if (open) {
            Column(Modifier.padding(bottom = 10.dp)) { content() }
        }
    }
}

// ---- Solar page: the panels ----------------------------------------------------------
@Composable
fun SolarSettings() {
    val prefs = Prefs(LocalContext.current)
    var panels by remember { mutableStateOf(if (prefs.panelCount > 0) prefs.panelCount.toString() else "") }
    var panelW by remember { mutableStateOf(shown(prefs.panelWatts)) }
    var sysV by remember { mutableStateOf(shown(prefs.systemVolts)) }
    var saved by remember { mutableStateOf(false) }
    val totalWp = (panels.trim().toIntOrNull() ?: 0) * (num(panelW) ?: 0f)
    val pumpW = prefs.pumpWatts

    AdvancedSection(
        "Advanced settings · Solar panels",
        if (prefs.panelCount > 0 && prefs.panelWatts > 0f) "${prefs.panelCount} × ${shown(prefs.panelWatts)} W · ${shown(prefs.systemVolts)} V system"
        else "Set up your panels so the plan fits your sunshine"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Field(panels, { panels = it; saved = false }, "Number of panels", keyboard = KeyboardType.Number) }
            Box(Modifier.weight(1f)) { Field(panelW, { panelW = it; saved = false }, "Watts per panel", keyboard = KeyboardType.Decimal) }
        }
        Spacer(Modifier.height(10.dp))
        Field(sysV, { sysV = it; saved = false }, "System voltage (V)", keyboard = KeyboardType.Decimal)
        if (totalWp > 0f) {
            Spacer(Modifier.height(10.dp))
            Text("Array: ${totalWp.roundToInt()} W in total", color = C.Text, fontSize = 15.sp)
            if (pumpW > 0f) {
                Text(
                    "That is " + String.format("%.1f", totalWp / pumpW) + "x the pump's rating, so expect about " +
                        String.format("%.0f", Hardware.windowHours(totalWp, pumpW)) + " hours of pumping on a sunny day.",
                    color = C.Dim, fontSize = 12.sp, lineHeight = 17.sp
                )
            } else {
                Text("Add the pump's rated power on the Pump page to see how many hours of pumping this gives.", color = C.Dim, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (saved) "Saved" else "Save") {
            prefs.panelCount = panels.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            prefs.panelWatts = num(panelW)?.coerceAtLeast(0f) ?: 0f
            prefs.systemVolts = num(sysV)?.takeIf { it > 0f } ?: 12f
            saved = true
        }
    }
}

// ---- Pump page: the pump ------------------------------------------------------------
@Composable
fun PumpSettings() {
    val prefs = Prefs(LocalContext.current)
    var model by remember { mutableStateOf(prefs.pumpModel) }
    var watts by remember { mutableStateOf(shown(prefs.pumpWatts)) }
    var flow by remember { mutableStateOf(shown(prefs.flowLpm)) }
    var head by remember { mutableStateOf(shown(prefs.pumpHeadM)) }
    var saved by remember { mutableStateOf(false) }

    AdvancedSection(
        "Advanced settings · Pump",
        if (prefs.flowLpm > 0f) (prefs.pumpModel.ifBlank { "Pump" } + " · " + shown(prefs.flowLpm) + " L/min" + (if (prefs.pumpWatts > 0f) " · " + shown(prefs.pumpWatts) + " W" else ""))
        else "Set the pump's flow rate to see litres"
    ) {
        Field(model, { model = it; saved = false }, "Model or name, e.g. Lorentz PS2-150")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Field(watts, { watts = it; saved = false }, "Rated power (W)", keyboard = KeyboardType.Decimal) }
            Box(Modifier.weight(1f)) { Field(flow, { flow = it; saved = false }, "Flow (litres/min)", keyboard = KeyboardType.Decimal) }
        }
        Spacer(Modifier.height(10.dp))
        Field(head, { head = it; saved = false }, "Total head / lift (metres)", keyboard = KeyboardType.Decimal)
        Spacer(Modifier.height(6.dp))
        Text(
            "The controller measures how long the pump runs, not how much water it moves. The flow figure turns run time into litres; " +
                "take it from the pump's label, or time how long a known container takes to fill.",
            color = C.Dim, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (saved) "Saved" else "Save") {
            prefs.pumpModel = model.trim()
            prefs.pumpWatts = num(watts)?.coerceAtLeast(0f) ?: 0f
            prefs.flowLpm = num(flow)?.coerceAtLeast(0f) ?: 0f
            prefs.pumpHeadM = num(head)?.coerceAtLeast(0f) ?: 0f
            saved = true
        }
    }
}

// ---- Water usage page: the tank -----------------------------------------------------
@Composable
fun TankSettings() {
    val prefs = Prefs(LocalContext.current)
    var tank by remember { mutableStateOf(shown(prefs.tankLitres)) }
    var saved by remember { mutableStateOf(false) }

    AdvancedSection(
        "Advanced settings · Tank",
        if (prefs.tankLitres > 0f) String.format("%,d", prefs.tankLitres.toInt()) + " litres" else "Set the tank's size"
    ) {
        Field(tank, { tank = it; saved = false }, "Capacity (litres)", keyboard = KeyboardType.Number)
        Spacer(Modifier.height(6.dp))
        Text("Used for the time-to-fill figure and to warn when a day's watering is more than the tank holds.", color = C.Dim, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (saved) "Saved" else "Save") {
            prefs.tankLitres = num(tank)?.coerceAtLeast(0f) ?: 0f
            saved = true
        }
    }
}

// ---- Controller page: the controller ------------------------------------------------
@Composable
fun ControllerSettings(s: Status, live: Boolean, onDisconnect: () -> Unit, onConnect: () -> Unit) {
    val prefs = Prefs(LocalContext.current)
    var model by remember { mutableStateOf(prefs.controllerModel) }
    var saved by remember { mutableStateOf(false) }
    val serial = s.deviceId.ifBlank { prefs.lastSerial }

    AdvancedSection(
        "Advanced settings · Controller",
        serial.ifBlank { "Model, serial and connection" }
    ) {
        Field(model, { model = it; saved = false }, "Controller model or label")
        Spacer(Modifier.height(10.dp))
        Text("Serial", color = C.Dim, fontSize = 12.sp)
        Text(serial.ifBlank { "Connect once to read it" }, color = C.Text, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (saved) "Saved" else "Save") { prefs.controllerModel = model.trim(); saved = true }
        Spacer(Modifier.height(10.dp))
        if (live) WideButton("Disconnect", true, C.Bad) { onDisconnect() }
        else WideButton("Connect to board") { onConnect() }
    }
}
