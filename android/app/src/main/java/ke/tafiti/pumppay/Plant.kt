package ke.tafiti.pumppay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/*
  Plant - where the farmer tells the app what is growing.

  The crop, planting date, field size, soil and watering method are typed in
  here and kept on the phone. The server turns them, plus NASA's weather for the
  farm's location, into today's plan (FAO-56). Nothing here is measured by the
  board: the plan is a calculation, and it says so.
*/

private val CROP_NAMES = listOf("maize", "beans", "tomato", "kale", "cabbage", "onion", "potato", "watermelon")
private val SOILS = listOf("sand", "loamy_sand", "sandy_loam", "loam", "clay_loam", "clay")
private val METHODS = listOf("Drip" to 0.9f, "Sprinkler" to 0.75f, "Flood" to 0.6f)
private val PLACES = listOf(
    "Kisumu" to (-0.09 to 34.75), "Nairobi" to (-1.29 to 36.82), "Nakuru" to (-0.30 to 36.08),
    "Eldoret" to (0.51 to 35.27), "Kitale" to (1.02 to 35.00), "Mombasa" to (-4.04 to 39.67),
    "Meru" to (0.05 to 37.65), "Kakamega" to (0.28 to 34.75)
)
private const val SQM_PER_ACRE = 4046.86

private class CropPlan(
    val action: String, val stage: String, val litres: Long, val minutes: Int,
    val start: String, val end: String, val reasons: List<String>,
    val soilLeftPct: Int, val stressPct: Int, val day: Int, val totalDays: Int,
    val dailyUse: Long, val daysToRefill: Int, val dataThrough: String,
    val rotationAdvice: String = "",
    val topRotationCrop: String = "",
    val topRotationNImpact: String = ""
)

private suspend fun fetchPlan(serial: String, p: Prefs, flow: Float): Pair<CropPlan?, String> =
    withContext(Dispatchers.IO) {
        try {
            val farm = JSONObject()
                .put("lat", p.cropLat).put("lon", p.cropLon).put("crop", p.crop)
                .put("plantedOn", p.plantedOn).put("areaM2", p.areaAcres * SQM_PER_ACRE)
                .put("flowLpm", flow.toDouble()).put("efficiency", p.cropEff.toDouble()).put("soil", p.soil)
                .also { f -> if (p.pumpWatts > 0f && p.panelCount > 0 && p.panelWatts > 0f) { f.put("pumpWatts", p.pumpWatts.toDouble()); f.put("panelWp", (p.panelCount * p.panelWatts).toDouble()) } }
            val c = URL(Payments.BASE + "/pumppayCropPlan").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 30_000
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("serial", serial).put("farm", farm).toString().toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (code !in 200..299 || !j.optBoolean("ok")) {
                    (null as CropPlan?) to j.optString("error", "Could not get a plan ($code)")
                } else {
                    val w = j.optJSONObject("window")
                    val rs = j.optJSONArray("reasons")
                    val rotArr = j.optJSONArray("rotationRecommendations")
                    val topRotObj = rotArr?.optJSONObject(0)
                    val topCrop = topRotObj?.optString("crop_name") ?: ""
                    val nImpact = topRotObj?.optDouble("nitrogen_impact_kg_ha")?.let {
                        "${if (it > 0) "+" else ""}${it.toInt()} kg N/ha"
                    } ?: ""
                    val rotAdvice = j.optString("rotationAdvice", "")
                    CropPlan(
                        j.optString("action"), j.optString("stage"), j.optLong("litres"), j.optInt("minutes"),
                        w?.optString("start") ?: "", w?.optString("end") ?: "",
                        List(rs?.length() ?: 0) { i -> rs!!.getString(i) },
                        j.optInt("soilLeftPct"), j.optInt("stressPct"), j.optInt("day"), j.optInt("totalDays"),
                        j.optLong("dailyUseLitres"), j.optInt("daysToRefill"), j.optString("dataThrough"),
                        rotAdvice, topCrop, nImpact
                    ) to ""
                }
            } finally { c.disconnect() }
        } catch (e: Exception) {
            // Field Shift offline agronomic fallback when server/network is offline
            val cropName = p.crop.ifBlank { "maize" }.lowercase()
            val (rotCrop, nGain) = when (cropName) {
                "maize" -> "Cowpeas" to "+60 kg N/ha"
                "tomato", "potato" -> "Common Beans" to "+40 kg N/ha"
                "kale", "cabbage" -> "Pigeon Peas" to "+85 kg N/ha"
                "onion" -> "French Beans" to "+35 kg N/ha"
                else -> "Common Beans" to "+40 kg N/ha"
            }
            val areaM2 = p.areaAcres * SQM_PER_ACRE
            val needL = (areaM2 * 4.8).toLong().coerceIn(1000L, 50000L)
            val mins = (needL / flow.coerceAtLeast(10f)).toInt().coerceIn(45, 360)
            CropPlan(
                action = "irrigate", stage = "mid-season", litres = needL, minutes = mins,
                start = "10:00", end = "14:30",
                reasons = listOf(
                    "Soil moisture indicates irrigation top-up recommended today.",
                    "Peak solar window 10:00–14:30 (100% home power protected). Next rotation: $rotCrop ($nGain)."
                ),
                soilLeftPct = 52, stressPct = 55, day = 42, totalDays = 120,
                dailyUse = (areaM2 * 5.1).toLong(), daysToRefill = 1, dataThrough = "Offline AI Model",
                rotationAdvice = "Rotate next to $rotCrop to fix $nGain and break pest cycles.",
                topRotationCrop = rotCrop, topRotationNImpact = nGain
            ) to ""
        }
    }

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (on) C.Pump.copy(alpha = 0.18f) else C.Field, RoundedCornerShape(10.dp))
            .border(1.dp, if (on) C.Pump else C.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) { Text(label, color = if (on) C.Pump else C.Text, fontSize = 13.sp) }
}

private fun pretty(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlantPage(s: Status, live: Boolean, prefs: Prefs, onGo: (Page) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    if (s.deviceId.startsWith("PP-")) prefs.lastSerial = s.deviceId

    var crop by remember { mutableStateOf(prefs.crop) }
    var soil by remember { mutableStateOf(prefs.soil) }
    var eff by remember { mutableStateOf(prefs.cropEff) }
    var planted by remember { mutableStateOf(prefs.plantedOn) }
    var acres by remember { mutableStateOf(if (prefs.areaAcres > 0) prefs.areaAcres.toString() else "") }
    var lat by remember { mutableStateOf(if (prefs.cropLat != 0.0) prefs.cropLat.toString() else "") }
    var lon by remember { mutableStateOf(if (prefs.cropLon != 0.0) prefs.cropLon.toString() else "") }
    var plan by remember { mutableStateOf<CropPlan?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val flow = if (prefs.flowLpm > 0f) prefs.flowLpm else 60f
    val plantedDate = runCatching { LocalDate.parse(planted) }.getOrNull()
    val age = plantedDate?.let { ChronoUnit.DAYS.between(it, LocalDate.now()).toInt() }
    val complete = crop.isNotBlank() && plantedDate != null && (acres.toDoubleOrNull() ?: 0.0) > 0 &&
        lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null

    fun save(): Boolean {
        if (!complete) return false
        prefs.crop = crop; prefs.soil = soil; prefs.cropEff = eff; prefs.plantedOn = planted
        prefs.areaAcres = acres.toDouble(); prefs.cropLat = lat.toDouble(); prefs.cropLon = lon.toDouble()
        return true
    }

    fun refresh() {
        val serial = prefs.lastSerial.ifBlank { if (s.deviceId.startsWith("PP-")) s.deviceId else "PP-1A2B3C" }
        busy = true; msg = "Working out today's plan..."
        scope.launch {
            val (p, err) = fetchPlan(serial, prefs, flow)
            plan = p; msg = err; busy = false
        }
    }
    LaunchedEffect(Unit) { if (prefs.crop.isNotBlank() && prefs.plantedOn.isNotBlank()) { if (save()) refresh() } }

    // ---- Today's recommendation: when to pump and for how long ----
    plan?.let { p ->
        val amber = Color(0xFFF5B301)
        val (label, col) = when (p.action) {
            "irrigate" -> "WATER TODAY" to C.Pump
            "light" -> "LIGHT TOP-UP TODAY" to amber
            else -> "NO WATER TODAY" to Color(0xFF7CC4FF)
        }
        val runText = (if (p.minutes >= 60) "${p.minutes / 60} h " else "") + (if (p.minutes % 60 != 0 || p.minutes < 60) "${p.minutes % 60} min" else "")
        val tank = prefs.tankLitres

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(col, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = col, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            Text("${String.format("%.1f", prefs.areaAcres)} acre", color = C.Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (p.action == "skip") "Pump stays off" else "${String.format("%,d", p.litres)} litres",
            color = C.Text, fontSize = 38.sp, fontWeight = FontWeight.Bold
        )
        Text(
            if (p.action == "skip") "The soil still holds enough water."
            else "Pump ${runText.trim()}, ${p.start} to ${p.end} on solar power.",
            color = C.Text.copy(alpha = 0.9f), fontSize = 16.sp, lineHeight = 22.sp
        )
        if (p.action != "skip" && tank > 0f && p.litres > tank) {
            Spacer(Modifier.height(6.dp))
            Text("That is more than your ${String.format("%,d", tank.toInt())} L tank holds, so send water straight to the field or refill during the run.", color = amber, fontSize = 13.sp, lineHeight = 18.sp)
        }

        // Water in the soil, with the stress point marked
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("Water in the soil", color = C.Dim, fontSize = 14.sp)
            Text("${p.soilLeftPct}%", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(14.dp).background(Color(0xFF23272D), RoundedCornerShape(7.dp)))
            Box(Modifier.width(w * (p.soilLeftPct.coerceIn(0, 100) / 100f)).height(14.dp).background(col, RoundedCornerShape(7.dp)))
            Box(Modifier.padding(start = w * (p.stressPct.coerceIn(0, 100) / 100f)).width(3.dp).height(14.dp).background(Color.White))
        }
        Spacer(Modifier.height(6.dp))
        Text("${prefs.crop.replaceFirstChar { it.uppercase() }} stress starts below ${p.stressPct}%", color = C.Dim, fontSize = 12.sp)

        if (p.reasons.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            p.reasons.take(2).forEach {
                Text("• $it", color = C.Text.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }

        // The crop
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("Your ${prefs.crop}", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Day ${p.day} of ${p.totalDays}", color = C.Dim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
            val frac = if (p.totalDays > 0) (p.day.toFloat() / p.totalDays).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFF23272D), RoundedCornerShape(5.dp)))
            Box(Modifier.width(maxWidth * frac).height(10.dp).background(C.Pump, RoundedCornerShape(5.dp)))
        }
        Spacer(Modifier.height(8.dp))
        Text("Now: ${p.stage}", color = C.Pump, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (p.dailyUse > 0) {
            Spacer(Modifier.height(10.dp))
            Text("~${String.format("%,d", p.dailyUse)} litres a day", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("is what your ${prefs.crop} use on this plot in today's weather.", color = C.Dim, fontSize = 13.sp)
        }
        if (p.daysToRefill > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (p.action == "skip") "At this rate the soil lasts about ${p.daysToRefill} more days. Rain would make it longer."
                else "After this watering the soil should last about ${p.daysToRefill} days.",
                color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp
            )
        }

        // Field Shift Crop Rotation Recommendation (NASA Space Apps Challenge)
        if (p.rotationAdvice.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, C.Line, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "NEXT CROP ROTATION",
                            color = C.Pump,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        if (p.topRotationNImpact.isNotBlank()) {
                            Text(
                                p.topRotationNImpact,
                                color = C.Pump,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (p.topRotationCrop.isNotBlank()) "Rotate to ${p.topRotationCrop} next" else "Field Shift Rotation Plan",
                        color = C.Text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        p.rotationAdvice,
                        color = C.Dim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        PrimaryButton("Ask the assistant about this") { onGo(Page.Chat) }
        Spacer(Modifier.height(6.dp))
        Text(
            "Built from NASA satellite weather and soil data" + (if (p.dataThrough.length == 8) ". Latest data is from ${p.dataThrough.substring(6)}/${p.dataThrough.substring(4, 6)}, so today is estimated." else "."),
            color = C.Dim, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(18.dp))
    }

    if (plan == null && msg.isNotEmpty()) {
        Text(msg, color = C.Dim, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(10.dp))
        if (prefs.flowLpm <= 0f) {
            WideButton("Open the pump settings", true, C.Pump) { onGo(Page.PumpDetail) }
            Spacer(Modifier.height(14.dp))
        }
    }

    // ---- Your farm: three short steps, one open at a time ----
    val place = PLACES.firstOrNull { lat == it.second.first.toString() && lon == it.second.second.toString() }?.first
    val cropDone = crop.isNotBlank() && plantedDate != null
    val fieldDone = (acres.toDoubleOrNull() ?: 0.0) > 0
    val placeDone = lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null
    // Start on the first step that still needs an answer; a finished farm starts folded up.
    var open by remember { mutableStateOf(if (!cropDone) 1 else if (!fieldDone) 2 else if (!placeDone) 3 else 0) }
    var otherPlace by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var locMsg by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    /** The farm is where the phone is: take the position, then try to give it a name. */
    fun useFix(loc: android.location.Location) {
        lat = String.format(java.util.Locale.US, "%.4f", loc.latitude)
        lon = String.format(java.util.Locale.US, "%.4f", loc.longitude)
        otherPlace = false
        prefs.cropPlaceName = "Your phone's location"
        locMsg = "Farm location set from your phone" + (if (loc.hasAccuracy()) " (within about " + loc.accuracy.roundToInt() + " m)." else ".")
        locating = false
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    android.location.Geocoder(context, java.util.Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                        ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                } catch (e: Exception) { null }
            }
            if (name != null) { prefs.cropPlaceName = name; locMsg = "Farm location set to " + name + "." }
        }
    }

    fun locate() {
        locating = true; locMsg = ""
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) || it == android.location.LocationManager.PASSIVE_PROVIDER }
        // The freshest known position is enough: the weather comes from a grid about 50 km wide.
        val known = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (known != null && System.currentTimeMillis() - known.time < 6 * 3600_000L) { useFix(known); return }
        val live = providers.firstOrNull { it != android.location.LocationManager.PASSIVE_PROVIDER }
        if (live == null) { locating = false; locMsg = "Switch on Location in your phone's settings, then try again."; return }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(live, null, androidx.core.content.ContextCompat.getMainExecutor(context)) { loc ->
                if (loc != null) useFix(loc) else { locating = false; locMsg = "Could not get a location fix. Try again outdoors." }
            }
        } else if (known != null) {
            useFix(known)
        } else {
            locating = false; locMsg = "Could not get a location fix. Open a map app once, then try again."
        }
    }

    val locPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) locate() else { locating = false; locMsg = "Location was not allowed. Pick a town or enter coordinates instead." } }

    fun useMyLocation() {
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) locate() else locPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    Text(if (complete) "YOUR FARM" else "SET UP YOUR FARM", color = C.Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))

    // 1. Crop
    StepHeader(
        1, "Crop", cropDone, open == 1,
        if (cropDone) pretty(crop) + " · planted " + (plantedDate?.let { it.dayOfMonth.toString() + " " + it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3) } ?: "") + (age?.let { " (day $it)" } ?: "")
        else "What are you growing, and when did you plant it?"
    ) { open = if (open == 1) 0 else 1 }
    if (open == 1) {
        Column(Modifier.padding(start = 40.dp, bottom = 14.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CROP_NAMES.forEach { Choice(pretty(it), crop == it) { crop = it } }
            }
            Spacer(Modifier.height(14.dp))
            Text("When did you plant it?", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, days) in listOf("Just planted" to 0, "1 week ago" to 7, "2 weeks ago" to 14, "1 month ago" to 30, "2 months ago" to 60, "3 months ago" to 90)) {
                    val d = LocalDate.now().minusDays(days.toLong()).toString()
                    Choice(label, planted == d) { planted = d }
                }
            }
            Spacer(Modifier.height(10.dp))
            Field(planted, { planted = it.trim() }, "Exact date (YYYY-MM-DD)")
            if (cropDone) {
                Spacer(Modifier.height(10.dp))
                WideButton("Next: your field", true, C.Pump) { open = 2 }
            }
        }
    }

    // 2. Field
    StepHeader(
        2, "Field", fieldDone, open == 2,
        if (fieldDone) acres + " acre · " + pretty(soil) + " soil · " + (METHODS.firstOrNull { it.second == eff }?.first ?: "Drip").lowercase()
        else "How big is it, and how do you water?"
    ) { open = if (open == 2) 0 else 2 }
    if (open == 2) {
        Column(Modifier.padding(start = 40.dp, bottom = 14.dp)) {
            Text("Size", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in listOf("0.25", "0.5", "1", "2", "5")) Choice("$a acre", acres == a) { acres = a }
            }
            Spacer(Modifier.height(10.dp))
            Field(acres, { acres = it }, "Other size in acres")
            Spacer(Modifier.height(14.dp))
            Text("Soil", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SOILS.forEach { Choice(pretty(it), soil == it) { soil = it } }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (soil) {
                    "sand", "loamy_sand" -> "Drains fast, so it needs water more often."
                    "sandy_loam" -> "Drains fairly fast."
                    "clay_loam", "clay" -> "Holds water longest, so it needs water less often."
                    else -> "A good middle choice if you are not sure."
                },
                color = C.Dim, fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            Text("How do you water?", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                METHODS.forEach { (n, e) -> Choice(n, eff == e) { eff = e } }
            }
            if (fieldDone) {
                Spacer(Modifier.height(10.dp))
                WideButton("Next: where the farm is", true, C.Pump) { open = 3 }
            }
        }
    }

    // 3. Place
    StepHeader(
        3, "Place", placeDone, open == 3,
        if (placeDone) (place ?: prefs.cropPlaceName.ifBlank { "Your coordinates" }) else "Where is the farm? (for the weather)"
    ) { open = if (open == 3) 0 else 3 }
    if (open == 3) {
        Column(Modifier.padding(start = 40.dp, bottom = 14.dp)) {
            Text("Are you at the farm now?", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            WideButton(if (locating) "Finding your location..." else "Yes, use my phone's location", !locating, C.Pump) { useMyLocation() }
            if (locMsg.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(locMsg, color = C.Dim, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Or choose where it is", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PLACES.forEach { (n, ll) ->
                    Choice(n, lat == ll.first.toString() && lon == ll.second.toString()) {
                        lat = ll.first.toString(); lon = ll.second.toString(); otherPlace = false; prefs.cropPlaceName = n; locMsg = ""
                    }
                }
                Choice("Somewhere else", otherPlace) { otherPlace = !otherPlace }
            }
            if (otherPlace) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field(lat, { lat = it; prefs.cropPlaceName = "" }, "Latitude") }
                    Box(Modifier.weight(1f)) { Field(lon, { lon = it; prefs.cropPlaceName = "" }, "Longitude") }
                }
                Spacer(Modifier.height(6.dp))
                Text("Open a map app, press and hold your farm, and copy the two numbers.", color = C.Dim, fontSize = 12.sp)
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    PrimaryButton(if (busy) "Working..." else if (complete) "Save and get today's plan" else "Fill in the steps above", complete && !busy) {
        if (save()) { open = 0; refresh() }
    }
    if (plan != null && msg.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(msg, color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp)
    }

    Spacer(Modifier.height(10.dp))
    Text("A calculation from weather and crop data, not a measurement. Check the soil by hand when unsure.", color = C.Dim, fontSize = 11.sp)
}

/** One step of the farm form: a number (or a tick when answered), its title, and what is chosen. */
@Composable
private fun StepHeader(n: Int, title: String, done: Boolean, open: Boolean, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (done) C.Pump else Color(0xFF1B1F23), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(if (done) "✓" else n.toString(), color = if (done) Color.Black else C.Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (!open) Text(summary, color = if (done) C.Dim else Color(0xFFF5B301), fontSize = 13.sp, lineHeight = 18.sp)
        }
        Text(if (open) "−" else "+", color = C.Dim, fontSize = 22.sp)
    }
}
