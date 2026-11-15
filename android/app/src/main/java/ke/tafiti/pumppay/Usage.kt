package ke.tafiti.pumppay

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pump run history, accumulated on the phone.
 *
 * The board remembers only the last 7 days. Every time the app connects it is
 * handed those 168 hours, and they are merged here, keyed by UTC clock hour -
 * so the history grows past a week simply by the app being used, with no server
 * and no account. Ninety days are kept; older hours are dropped.
 *
 * The board is authoritative for any hour it reports: a value it sends REPLACES
 * what is stored, including a zero. That is what lets the current, still-growing
 * hour update itself on every fetch instead of freezing at its first value.
 *
 * Stored as "hour:minutes:vah" triples, sparse - hours with no pumping are not
 * written at all, which keeps a quarter's history to a few kilobytes. Older
 * "hour:minutes" pairs, from before energy was recorded, still load.
 */
class UsageStore(private val prefs: Prefs) {

    private val minutes = HashMap<Long, Int>()
    private val energy = HashMap<Long, Int>()   // VAh per hour

    /** Bumped on every merge so Compose knows to redraw; the map itself is not state. */
    var version by mutableStateOf(0)
        private set

    init {
        prefs.usageHistory.split(';').forEach { row ->
            val p = row.split(':')
            val h = p.getOrNull(0)?.toLongOrNull()
            val m = p.getOrNull(1)?.toIntOrNull()
            if (h != null && m != null) {
                minutes[h] = m
                p.getOrNull(2)?.toIntOrNull()?.let { energy[h] = it }
            }
        }
    }

    fun merge(h: Hist) {
        if (h.hourNow <= 0 || h.minutes.isEmpty()) return

        val first = h.hourNow - (h.minutes.size - 1)
        h.minutes.forEachIndexed { i, m ->
            val hour = first + i
            if (m > 0) minutes[hour] = m else minutes.remove(hour)
            val e = h.vah.getOrNull(i) ?: 0
            if (e > 0) energy[hour] = e else energy.remove(hour)
        }

        val cutoff = h.hourNow - 90 * 24
        minutes.keys.removeAll { it < cutoff }
        energy.keys.removeAll { it < cutoff }

        prefs.usageHistory = minutes.entries.joinToString(";") { "${it.key}:${it.value}:${energy[it.key] ?: 0}" }
        version++
    }

    fun at(hour: Long): Int = if (Simulation.active) Simulation.hour(hour).first else minutes[hour] ?: 0
    fun vahAt(hour: Long): Int = if (Simulation.active) Simulation.hour(hour).second else energy[hour] ?: 0
    val isEmpty: Boolean get() = !Simulation.active && minutes.isEmpty()

    /** Redraw the charts, e.g. when the simulation is switched on or off. */
    fun refresh() { version++ }


}

/** Everything the Usage page shows for one range, computed in one pass. */
private class Summary(
    val minutes: Int,
    val prevMinutes: Int,
    val bars: List<Int>,          // minutes per bar: 24 hours, or one per day
    val barLabels: List<String>,
    val byHour: IntArray,         // minutes by local hour of day, over the pattern window
    val busiestBar: Int,          // index into bars, -1 if none
    val peakHour: Int,            // local hour 0..23, -1 if none
    val activeDays: Int
)

private fun summarize(store: UsageStore, days: Int, zone: ZoneId, nowEpoch: Long): Summary {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowEpoch), zone)
    val midnight = now.toLocalDate().atStartOfDay(zone)

    // UTC hour numbers falling inside [from, to), walking whole local hours.
    fun hoursIn(from: ZonedDateTime, to: ZonedDateTime): List<Pair<Long, ZonedDateTime>> {
        val out = ArrayList<Pair<Long, ZonedDateTime>>()
        var t = from
        while (t.isBefore(to)) {
            out.add(t.toEpochSecond() / 3600 to t)
            t = t.plusHours(1)
        }
        return out
    }

    val start = midnight.minusDays((days - 1).toLong())
    val end = midnight.plusDays(1)
    val cur = hoursIn(start, end)
    val prev = hoursIn(start.minusDays(days.toLong()), start)

    val bars: List<Int>
    val labels: List<String>
    if (days == 1) {
        bars = (0 until 24).map { h -> cur.getOrNull(h)?.let { store.at(it.first) } ?: 0 }
        labels = (0 until 24).map { "$it:00" }
    } else {
        val perDay = IntArray(days)
        cur.forEach { (hour, t) ->
            val d = ChronoUnit.DAYS.between(start.toLocalDate(), t.toLocalDate()).toInt()
            if (d in 0 until days) perDay[d] += store.at(hour)
        }
        bars = perDay.toList()
        labels = (0 until days).map {
            val d = start.plusDays(it.toLong())
            d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth
        }
    }

    // When-do-you-pump pattern: at least a week, so a single quiet day is not
    // mistaken for a habit.
    val pDays = max(days, 7)
    val byHour = IntArray(24)
    hoursIn(midnight.minusDays((pDays - 1).toLong()), end).forEach { (hour, t) ->
        byHour[t.hour] += store.at(hour)
    }
    val peak = if (byHour.max() > 0) byHour.indices.maxByOrNull { byHour[it] } ?: -1 else -1
    val total = cur.sumOf { store.at(it.first) }

    return Summary(
        minutes = total,
        prevMinutes = prev.sumOf { store.at(it.first) },
        bars = bars,
        barLabels = labels,
        byHour = byHour,
        busiestBar = if (bars.max() > 0) bars.indices.maxByOrNull { bars[it] } ?: -1 else -1,
        peakHour = peak,
        activeDays = if (days == 1) (if (total > 0) 1 else 0) else bars.count { it > 0 }
    )
}

/** "1h 20m" / "45m" / "0m". */
fun minText(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

/** Seconds as "1h 20m" / "45m" - seconds are not worth reading on a pump log. */
fun secText(s: Long): String = minText((s / 60).toInt())

private fun hourText(h: Int): String = "${if (h % 12 == 0) 12 else h % 12}${if (h < 12) "am" else "pm"}"

private fun litresText(l: Double): String =
    if (l >= 10_000) String.format("%.1fk", l / 1000) else String.format("%,d", l.roundToInt())

@Composable
fun UsagePage(store: UsageStore, flowLpm: Float, onGo: (Page) -> Unit, st: Status, live: Boolean, prefs: Prefs, onSetPump: (Boolean) -> Unit) {
    var range by remember { mutableStateOf(1) }

    // Reading the version makes this recompose whenever a merge lands.
    @Suppress("UNUSED_VARIABLE")
    val version = store.version

    val zone = remember { ZoneId.systemDefault() }
    val s = summarize(store, range, zone, System.currentTimeMillis() / 1000)

    Segmented(range) { range = it }
    Spacer(Modifier.height(12.dp))

    // The tank, then its controls: the pump, and the valve that releases water to the farm.
    run {
        val running = live && st.pumping
        val capL = prefs.tankLitres
        var valveOpen by remember { mutableStateOf(prefs.valveOpen) }
        var valvePct by remember { mutableStateOf(prefs.valvePct) }
        val canSwitch = live

        // The tank scene leads the page, large and with no frame around it.
        HeroPicture(Page.Usage, st, live, valveOpen, valvePct)
        Spacer(Modifier.height(8.dp))
        TankSettings()

        CardBox {
            SectionLabel("TANK")
            Spacer(Modifier.height(6.dp))
            Text(
                if (!live) "Not connected" else if (running) "Filling" else "Not filling",
                color = if (running) C.Pump else C.Dim, fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("capacity", if (capL > 0f) "${String.format("%,d", capL.toInt())} L" else "—", Modifier.weight(1f))
                Tile("flowing in", if (running && flowLpm > 0f) "${flowLpm.toInt()} L/min" else "—", Modifier.weight(1f))
                Tile("time to fill", if (capL > 0f && flowLpm > 0f) minText((capL / flowLpm).toInt()) else "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Time to fill is the capacity divided by the pump's flow rate. The water height is an illustration, not a measured level.",
                color = C.Dim, fontSize = 11.sp, lineHeight = 16.sp
            )
        }

        // ---- Pump on / off ----
        Spacer(Modifier.height(12.dp))
        CardBox {
            SectionLabel("PUMP")
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !live -> "Not connected"
                    st.manualOff -> "Switched off"
                    running -> "Running"
                    st.tokenLocked -> "Locked · token used up"
                    else -> "On · waiting for sun"
                },
                color = if (st.manualOff || (live && st.tokenLocked)) Color(0xFFF5B301) else if (running) C.Pump else C.Dim,
                fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { WideButton("Pump on", canSwitch && st.manualOff, C.Pump) { onSetPump(true) } }
                Box(Modifier.weight(1f)) { WideButton("Pump off", canSwitch && !st.manualOff, Color(0xFFE5484D)) { onSetPump(false) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (canSwitch) "Off cuts the pump's supply through the controller's relay. It is not saved: if the controller restarts, the pump comes back on. Needs the latest controller firmware."
                else "Connect to a real controller to switch the pump.",
                color = C.Dim, fontSize = 11.sp, lineHeight = 16.sp
            )
        }

        // ---- Valve to the farm ----
        Spacer(Modifier.height(12.dp))
        CardBox {
            SectionLabel("VALVE TO THE FARM")
            Spacer(Modifier.height(10.dp))
            ValveGauge(if (valveOpen) valvePct else 0f, valveOpen, Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !valveOpen -> "Closed"
                    flowLpm > 0f -> "Open ${(valvePct * 100).toInt()}% · ≈ ${(valvePct * flowLpm).toInt()} L/min to the farm"
                    else -> "Open ${(valvePct * 100).toInt()}%"
                },
                color = if (valveOpen) C.Pump else Color(0xFFE5484D), fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            androidx.compose.material3.Slider(
                value = valvePct,
                onValueChange = { valvePct = it; prefs.valvePct = it; if (it > 0.01f && !valveOpen) { valveOpen = true; prefs.valveOpen = true } },
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = C.Pump, activeTrackColor = C.Pump, inactiveTrackColor = C.Line
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { WideButton("Open valve", !valveOpen, C.Pump) { valveOpen = true; prefs.valveOpen = true } }
                Box(Modifier.weight(1f)) { WideButton("Close valve", valveOpen, Color(0xFFE5484D)) { valveOpen = false; prefs.valveOpen = false } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "The gauge sets how fast water leaves for the farm: more open, faster. This is an app setting that drives the picture and the estimate above. The controller does not operate a valve yet, so close the real valve by hand.",
                color = C.Dim, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    CardBox {
        SectionLabel("RUN TIME")
        Spacer(Modifier.height(6.dp))
        Text(
            if (store.isEmpty) "—" else minText(s.minutes),
            color = if (store.isEmpty) C.Dim else C.Text,
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold
        )

        if (flowLpm > 0f && !store.isEmpty) {
            Text(
                "≈ ${litresText(s.minutes * flowLpm.toDouble())} litres",
                color = C.Pump,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (s.prevMinutes > 0) {
            val pct = ((s.minutes - s.prevMinutes) * 100.0 / s.prevMinutes).roundToInt()
            Spacer(Modifier.height(4.dp))
            Text(
                "${if (pct >= 0) "▲" else "▼"} ${abs(pct)}% vs " +
                    (if (range == 1) "yesterday" else "previous $range days"),
                color = if (pct > 0) C.Pump else C.Dim,
                fontSize = 13.sp
            )
        }
    }

    if (store.isEmpty) {
        Spacer(Modifier.height(12.dp))
        CardBox {
            Text("No usage recorded yet", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect to your pump controller. It records when the pump runs, " +
                    "and the app collects it each time you connect. Open the app " +
                    "at least once a week to keep a continuous history.",
                color = C.Dim,
                fontSize = 13.sp
            )
        }
        return
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(
            if (range == 1) "hours pumped" else "days pumped",
            if (range == 1) "${s.bars.count { it > 0 }}" else "${s.activeDays}/$range",
            Modifier.weight(1f)
        )
        Tile(
            "avg / day",
            minText((s.minutes.toDouble() / range).roundToInt()),
            Modifier.weight(1f)
        )
        Tile(
            "busiest time",
            if (s.peakHour >= 0) "${hourText(s.peakHour)}–${hourText((s.peakHour + 1) % 24)}" else "—",
            Modifier.weight(1f)
        )
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel(if (range == 1) "TODAY, BY HOUR" else "LAST $range DAYS")
        Spacer(Modifier.height(14.dp))
        FigureBars(s.bars, s.barLabels, s.busiestBar, if (range == 30) 2f else 3f, { m -> minText(m) + (if (flowLpm > 0f) "  ·  ≈ " + litresText(m * flowLpm.toDouble()) + " L" else "") })
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val axis = when (range) {
                1 -> listOf("12am", "6am", "12pm", "6pm", "12am")
                7 -> s.barLabels.map { it.take(1) }
                else -> listOf("30d ago", "20d", "10d", "Today")
            }
            axis.forEach { Text(it, color = C.Dim, fontSize = 11.sp) }
        }
    }

    if (s.peakHour >= 0) {
        Spacer(Modifier.height(12.dp))
        CardBox {
            SectionLabel("WHEN YOU USUALLY PUMP")
            Spacer(Modifier.height(14.dp))
            FigureBars(s.byHour.toList(), (0 until 24).map { hourText(it) + "–" + hourText((it + 1) % 24) }, s.peakHour, 3f, { m -> minText(m) + " pumped in this hour" })
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("12am", "6am", "12pm", "6pm", "12am").forEach {
                    Text(it, color = C.Dim, fontSize = 11.sp)
                }
            }
        }

        val notes = insights(s, range, flowLpm)
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CardBox {
                SectionLabel("INSIGHTS")
                Spacer(Modifier.height(10.dp))
                notes.forEach {
                    Text("•  $it", color = C.Text, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (flowLpm <= 0f) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Set your pump's flow rate on the Pump page (Advanced settings) to see estimated litres.",
            color = C.Dim,
            fontSize = 12.sp,
            modifier = Modifier.clickable { onGo(Page.PumpDetail) }
        )
    }
}

private fun insights(s: Summary, range: Int, flowLpm: Float): List<String> {
    val out = ArrayList<String>()
    val tot = s.byHour.sum()
    if (tot > 0) {
        fun share(a: Int, b: Int) = (100.0 * (a until b).sumOf { s.byHour[it] } / tot).roundToInt()
        val morning = share(5, 12)
        val afternoon = share(12, 18)
        val evening = 100 - morning - afternoon
        val top = listOf(
            "morning (5am–12pm)" to morning,
            "afternoon (12pm–6pm)" to afternoon,
            "evening and night" to evening
        ).maxByOrNull { it.second }!!
        out.add("Most of your pumping (${top.second}%) is in the ${top.first}.")
        if (share(10, 15) < 25 && range >= 7) {
            out.add("Solar is strongest from 10am to 3pm - pumping then gives the most water per hour.")
        }
    }
    if (range > 1 && s.busiestBar >= 0) {
        val m = s.bars[s.busiestBar]
        out.add(
            "Busiest day: ${s.barLabels[s.busiestBar]}, " + minText(m) +
                (if (flowLpm > 0f) " (≈ ${litresText(m * flowLpm.toDouble())} litres)" else "") + "."
        )
    }
    return out
}

@Composable
fun Segmented(selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(C.Field, RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        listOf(1 to "Today", 7 to "7 days", 30 to "30 days").forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) C.Line else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { onPick(v) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) C.Text else C.Dim,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun Tile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .padding(vertical = 6.dp)
    ) {
        Text(value, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = C.Dim, fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * A bar chart that shows its figures. The readout above names the picked bar and its value;
 * tap a bar (or drag across a short chart) to read another. A long chart scrolls sideways,
 * starts at today, and puts the day number under every bar.
 */
@Composable
fun FigureBars(
    values: List<Int>,
    labels: List<String>,
    highlight: Int,
    gapDp: Float,
    format: (Int) -> String,
    scrollable: Boolean = false,
    barWidthDp: Int = 16
) {
    if (values.isEmpty()) return
    var picked by remember(values) { mutableStateOf(highlight.coerceIn(0, values.size - 1)) }
    val i = picked.coerceIn(0, values.size - 1)
    val best = highlight == i

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(labels.getOrElse(i) { "" } + (if (best && highlight >= 0 && values.max() > 0) "  ·  highest" else ""), color = C.Dim, fontSize = 12.sp)
            Text(format(values[i]), color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("Tap a bar for its figure", color = C.Dim, fontSize = 11.sp)
    }
    Spacer(Modifier.height(12.dp))

    if (scrollable) {
        val scroll = rememberScrollState()
        LaunchedEffect(values.size) { scroll.scrollTo(scroll.maxValue) }
        Box(Modifier.horizontalScroll(scroll)) {
            Column(Modifier.width((values.size * barWidthDp).dp)) {
                Bars(values, i, gapDp, onPick = { picked = it })
                Spacer(Modifier.height(4.dp))
                Row {
                    labels.forEachIndexed { k, l ->
                        Box(Modifier.width(barWidthDp.dp), contentAlignment = Alignment.Center) {
                            Text(
                                l.substringAfter(' ', l).take(2), fontSize = 9.sp, maxLines = 1,
                                color = if (k == i) C.Text else C.Dim
                            )
                        }
                    }
                }
            }
        }
    } else {
        Bars(values, i, gapDp, onPick = { picked = it }, scrub = true)
    }
}

/** A plain bar chart. The tallest bar is picked out in the accent colour. */
@Composable
fun Bars(values: List<Int>, highlight: Int, gapDp: Float, onPick: ((Int) -> Unit)? = null, scrub: Boolean = false) {
    // The bars grow in as a wave from left to right whenever the data changes.
    // Each bar starts a little after the one before it, so the chart sweeps up
    // rather than all snapping to height at once.
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .pointerInput(values.size, onPick != null, scrub) {
                if (onPick == null || values.isEmpty()) return@pointerInput
                val n = values.size
                fun idx(x: Float) = ((x / size.width) * n).toInt().coerceIn(0, n - 1)
                detectTapGestures { onPick(idx(it.x)) }
            }
            .pointerInput(values.size, onPick != null, scrub) {
                if (onPick == null || !scrub || values.isEmpty()) return@pointerInput
                val n = values.size
                detectHorizontalDragGestures { change, _ ->
                    onPick(((change.position.x / size.width) * n).toInt().coerceIn(0, n - 1))
                }
            }
    ) {
        val n = values.size
        if (n == 0) return@Canvas
        val gap = gapDp.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val top = values.max().coerceAtLeast(1)

        val lead = 8f   // how many bars the wave spans
        values.forEachIndexed { i, v ->
            val p = ((grow.value * (n + lead) - i) / lead).coerceIn(0f, 1f)
            val full = if (v == 0) 2.dp.toPx() else (v.toFloat() / top) * size.height
            val h = (2.dp.toPx() + (full - 2.dp.toPx()) * p).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = if (i == highlight) C.Pump else C.Line,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}


/** A half-circle gauge for the valve: the needle sweeps from shut (left) to fully open (right). */
@Composable
fun ValveGauge(pct: Float, open: Boolean, modifier: Modifier = Modifier) {
    val sweep by androidx.compose.animation.core.animateFloatAsState(pct, androidx.compose.animation.core.tween(350), label = "gauge")
    val col = if (open) C.Pump else Color(0xFFE5484D)
    androidx.compose.foundation.Canvas(modifier) {
        val r = minOf(size.width / 2f - 12.dp.toPx(), size.height - 12.dp.toPx())
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height - 6.dp.toPx())
        val tl = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r)
        val sz = androidx.compose.ui.geometry.Size(r * 2f, r * 2f)
        drawArc(C.Line, 180f, 180f, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        if (sweep > 0.005f) drawArc(col, 180f, 180f * sweep, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        // ticks every 25 %
        for (i in 0..4) {
            val ang = Math.toRadians(180.0 + 180.0 * i / 4)
            val p0 = androidx.compose.ui.geometry.Offset(c.x + (r - 18.dp.toPx()) * Math.cos(ang).toFloat(), c.y + (r - 18.dp.toPx()) * Math.sin(ang).toFloat())
            val p1 = androidx.compose.ui.geometry.Offset(c.x + (r - 28.dp.toPx()) * Math.cos(ang).toFloat(), c.y + (r - 28.dp.toPx()) * Math.sin(ang).toFloat())
            drawLine(C.Dim, p0, p1, 1.5.dp.toPx())
        }
        // needle
        val ang = Math.toRadians(180.0 + 180.0 * sweep)
        val tip = androidx.compose.ui.geometry.Offset(c.x + (r - 22.dp.toPx()) * Math.cos(ang).toFloat(), c.y + (r - 22.dp.toPx()) * Math.sin(ang).toFloat())
        drawLine(C.Text, c, tip, 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(C.Text, 6.dp.toPx(), c)
    }
}
