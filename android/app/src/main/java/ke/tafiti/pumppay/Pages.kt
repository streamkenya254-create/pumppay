package ke.tafiti.pumppay

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Routes the drawer selection to a page body.
 *
 * Pages show state. Explanations live in Help & support - a dashboard that
 * also explains how run detection works makes someone reading a voltage skip
 * three paragraphs to find it.
 */
@Composable
fun PageContent(
    page: Page,
    s: Status,
    link: Link,
    error: String?,
    reply: String?,
    devices: List<BluetoothDevice>,
    bluetoothOn: Boolean,
    prefs: Prefs,
    usage: UsageStore,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onKey: (Int) -> Unit,
    onSaveWifi: (String, String) -> Unit,
    onClearWifi: () -> Unit,
    onSetPin: (String) -> Unit,
    onSetLine: (Int) -> Unit,
    onSetCloudKey: (String) -> Unit,
    onClearCloud: () -> Unit,
    onCloudStatus: () -> Unit,
    onSetBilling: (Boolean) -> Unit,
    onSetPump: (Boolean) -> Unit,
    onSync: () -> Unit,
    helpQuery: String,
    onAsk: (String) -> Unit,
    onGo: (Page) -> Unit,
    connectTick: Int = 0
) {
    val live = link == Link.Connected
    var picking by remember { mutableStateOf(false) }
    // The Connect button in the top bar asks for the device list by bumping this.
    androidx.compose.runtime.LaunchedEffect(connectTick) { if (connectTick > 0) picking = true }

    if (picking) {
        DevicePicker(
            devices = devices,
            bluetoothOn = bluetoothOn,
            link = link,
            onPick = { picking = false; onConnect(it) },
            onDismiss = { picking = false }
        )
    }

    // Web pages fill the screen themselves. No outer padding, and above all
    // no verticalScroll - nesting a WebView in one gives it unbounded height,
    // so it renders its whole document at once and scrolls neither properly.
    if (page == Page.Shop || page == Page.About) {
        WebPage(if (page == Page.Shop) Site.SHOP else Site.ABOUT)
        return
    }

    // The panel is full-bleed blue, so it is routed out here, ahead of the
    // padded body every other page shares. Sixteen dp of black down each side
    // would have framed the colour instead of letting it be the screen - and
    // that same 32 dp is what the key cluster grows into.
    if (page == Page.Panel) {
        PanelPage(s, live, onKey)
        return
    }

    // The assistant is a full-screen chat with its own message bar.
    if (page == Page.Chat) {
        ChatScreen(s, live, prefs, onSetPump, onGo)
        return
    }

    // The dashboard lays itself out: a big fixed picture with a list that scrolls
    // inside its own panel. Inside the shared scrolling body below, the list would
    // slide up over the picture.
    if (page == Page.Dashboard) {
        DashboardScreen(
            s, link, error, live, prefs.flowLpm, prefs.systemVolts, usage,
            { picking = true }, onGo, onAsk
        )
        return
    }

    // The page body scrolls, so nothing inside it can use weight() - a scrolling
    // column offers its children unbounded height and a weight of an infinity is
    // meaningless. Measuring the viewport out here instead gives the sparse
    // pages a real number to stretch their cards to, while keeping the scroll
    // for the pages that genuinely overflow.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Viewport less the body padding (16 dp top and bottom) and the 28 dp
        // tail spacer, so a card told to fill the room lands exactly on the
        // bottom edge instead of pushing the page into a 28 dp scroll.
        val room = maxHeight - 60.dp

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (page) {
                Page.Plant -> PlantPage(s, live, prefs, onGo)
                Page.Profile -> ProfilePage(prefs)
                Page.Settings -> SettingsPage(onGo, prefs)
                Page.Usage -> UsagePage(usage, prefs.flowLpm, onGo, s, live, prefs, onSetPump)
                Page.Solar -> SolarPage(s, live, usage, prefs.systemVolts, onGo)
                Page.Billing -> BillingPage(s, live, prefs, onSync)
                Page.Diagnostics -> DiagnosticsPage(s, live, prefs.systemVolts, usage)
                Page.Controller -> ControllerPage(s, live, prefs.systemVolts, onDisconnect) { picking = true }
                Page.PumpDetail -> PumpDetailPage(s, live, usage, prefs.flowLpm, onGo)
                Page.Network -> NetworkPage(s, live, room)
                Page.Device -> DevicePage(s, live, reply, onSaveWifi, onClearWifi, onSetPin, onSetLine, onSetCloudKey, onClearCloud, onCloudStatus, onSetBilling)
                Page.Support -> SupportPage(s, live, onGo, helpQuery)

                // Returned above, before this scrolling container. Listed so the
                // compiler keeps this exhaustive - a new page added later fails
                // to build rather than silently rendering nothing.
                Page.Dashboard, Page.Chat, Page.Panel, Page.Shop, Page.About -> Unit
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// One dash plus one gap. The animation runs the phase across exactly this
// distance and restarts, so the pattern repeats seamlessly instead of jumping.
private const val DASH_LEN = 9f
private const val GAP_LEN = 15f
private const val DASH_PERIOD = DASH_LEN + GAP_LEN

/**
 * The system as a single-line diagram: solar panel, solar pump controller, pump.
 *
 * Three pictures joined by two feeders, because that is what is actually on the
 * site and a farmer recognises it at a glance. The feeders are dull grey while
 * the pump is stopped and carry moving green dashes while it runs; that motion
 * is what says "water is being pumped" before a single number is read.
 *
 * Only the controller measures anything - it sits on the pump line - so the
 * readings live on its card. The panel and the pump are shown as they are.
 */
@Composable
private fun PumpLine(s: Status, live: Boolean, flowLpm: Float, onGo: (Page) -> Unit) {
    val running = live && s.pumping

    // The board measures only the pump line, which is the controller's OUTPUT.
    // What the array delivered is worked back from that, assuming the controller
    // is CONTROLLER_EFFICIENCY efficient. It is an estimate, and labelled as one.
    val haveVa = live && s.vOk && s.aOk
    val outVa = if (haveVa) s.va else 0.0
    val inVa = outVa / CONTROLLER_EFFICIENCY

    val flow = rememberInfiniteTransition(label = "flow")
    val phase by flow.animateFloat(
        initialValue = 0f,
        targetValue = DASH_PERIOD,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // ---- Solar panel -------------------------------------------------------
    Node(
        art = { PanelArt(running) },
        title = "SOLAR ARRAY  ·  INPUT",
        active = running,
        onClick = { onGo(Page.Solar) }
    ) {
        PowerFigure(if (haveVa) inVa else null, running)
        Text(
            if (running) "estimated from the pump line" else "Solar array",
            color = C.Dim,
            fontSize = 12.sp
        )
    }

    Feeder(running, phase)

    // ---- Solar pump controller ---------------------------------------------
    Node(
        art = { ControllerArt(running) },
        title = "CONTROLLER  ·  ${(CONTROLLER_EFFICIENCY * 100).roundToInt()}% EFFICIENT",
        active = running,
        onClick = { onGo(Page.Controller) }
    ) {
        Row(Modifier.fillMaxWidth()) {
            Metric("VOLTS", fmt(s.volts, live && s.vOk, 0), "V", Modifier.weight(1f))
            Metric("AMPS", fmt(s.amps, live && s.aOk, 2), "A", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (haveVa) "Loss  " + String.format("%,d", (inVa - outVa).roundToInt()) + " VA as heat"
            else "Loss  —",
            color = C.Dim,
            fontSize = 12.sp
        )
    }

    Feeder(running, phase)

    // ---- The pump ----------------------------------------------------------
    Node(
        art = { PumpArt(running) },
        title = "PUMP",
        active = running,
        onClick = { onGo(Page.PumpDetail) }
    ) {
        PowerFigure(if (haveVa) outVa else null, running)
        Text(
            if (live && s.tokenLocked) "TOKEN EXPIRED" else if (live && s.noLoad()) "NOT CONNECTED" else if (live) s.state else "—",
            color = when {
                !live -> C.Dim
                s.tokenLocked -> Color(0xFFF5B301)
                s.noLoad() -> C.Bad
                s.pumping -> C.Pump
                s.state == "NO SIGNAL" -> C.Bad
                else -> C.Text
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            when {
                !live -> "Not connected"
                s.tokenLocked -> "Pump locked - top up to unlock"
                s.noLoad() -> "No load on the pump line"
                s.pumping -> "Running for ${secText(s.runCurSecs)}"
                s.state == "NO SIGNAL" -> "Current sensor not answering"
                else -> "Waiting to start"
            },
            color = C.Dim,
            fontSize = 12.sp
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RunTile(
            "TODAY",
            if (live && s.timeKnown) secText(s.runTodaySecs) else "—",
            Modifier.weight(1f)
        )
        RunTile("TOTAL", if (live) secText(s.runTotalSecs) else "—", Modifier.weight(1f))
        RunTile("STARTS", if (live) s.starts.toString() else "—", Modifier.weight(1f))
    }

    // Litres only when the owner has said how fast the pump moves water.
    // Without that number this would be a made-up figure.
    if (live && s.timeKnown && flowLpm > 0f) {
        Spacer(Modifier.height(12.dp))
        Text(
            "≈ ${String.format("%,d", (s.runTodaySecs / 60.0 * flowLpm).roundToInt())} litres today",
            color = C.Pump,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }

    if (live && s.tokenSecs >= 0) {
        Spacer(Modifier.height(14.dp))
        Text(
            (if (s.tokenLocked) "Token used up  ·  pump locked" else "Token ${secText(s.tokenSecs)} left") + "  ›",
            color = if (s.tokenLocked) Color(0xFFF5B301) else C.Pump,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onGo(Page.Billing) }
        )
    }

    Spacer(Modifier.height(14.dp))
    Text(
        "Run system diagnostics  ›",
        color = C.Pump,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable { onGo(Page.Diagnostics) }
    )
}

/** Efficiency assumed for the controller between array and pump. */
const val CONTROLLER_EFFICIENCY = 0.95

/** A power reading, big, in W-scale units; a dash when it is not known. */
@Composable
private fun PowerFigure(va: Double?, active: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            if (va == null) "—" else String.format("%,d", va.roundToInt()),
            color = if (va == null) C.Dim else if (active) C.Pump else C.Text,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
        if (va != null) {
            Spacer(Modifier.width(4.dp))
            Text("VA", color = C.Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/**
 * The three pictures, each a still base with one animated layer on top.
 *
 * Animation runs only while the pump runs: a spinning impeller on a stopped
 * pump would be a lie, and the stillness is itself information. Each overlay is
 * its own drawable so it can rotate or fade about the right point without
 * redrawing the rest.
 */
@Composable
fun PanelArt(running: Boolean) {
    val t = rememberInfiniteTransition(label = "sun")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    val glow by t.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow"
    )
    Box(Modifier.size(84.dp)) {
        Image(painterResource(R.drawable.ic_solar_panel), null, Modifier.fillMaxSize())
        Image(
            painterResource(R.drawable.ic_sun), null,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.84f, 0.12f)
                    rotationZ = if (running) spin else 0f
                    alpha = if (running) glow else 0.45f
                }
        )
    }
}

@Composable
fun ControllerArt(running: Boolean) {
    ControllerBody(true, running, 1f, 0f, Modifier.size(84.dp))
}

@Composable
fun PumpArt(running: Boolean) {
    val t = rememberInfiniteTransition(label = "impeller")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(650, easing = LinearEasing)), label = "spin")

    // Two drops leave the pipe half a cycle apart, so the outlet is never idle
    // for long. Each falls to just above the ground line and fades as it lands.
    val fallA by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "dropA")
    val fallB by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1000, easing = LinearEasing), initialStartOffset = StartOffset(500)),
        label = "dropB"
    )

    Box(Modifier.size(84.dp)) {
        Image(painterResource(R.drawable.ic_pump), null, Modifier.fillMaxSize())
        Image(
            painterResource(R.drawable.ic_pump_impeller), null,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.64f, 0.61f)
                    rotationZ = if (running) spin else 0f
                }
        )
        if (running) {
            for (p in listOf(fallA, fallB)) {
                Image(
                    painterResource(R.drawable.ic_pump_drop), null,
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Speeds up as it falls, and fades over the last third.
                            translationY = p * p * size.height * 0.46f
                            alpha = if (p < 0.65f) 1f else (1f - (p - 0.65f) / 0.35f).coerceIn(0f, 1f)
                        }
                )
            }
        }
    }
}

/** One stage of the line: its picture on the left, what it is and says on the right. */
@Composable
private fun Node(
    art: @Composable () -> Unit,
    title: String,
    active: Boolean,
    onClick: (() -> Unit)? = null,
    body: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .border(if (active) 1.5.dp else 1.dp, if (active) C.Pump else C.Line, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        art()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            SectionLabel(title)
            Spacer(Modifier.height(8.dp))
            body()
        }
    }
}

/** The conductor between two stages; green dashes travel down it while pumping. */
@Composable
private fun Feeder(running: Boolean, phase: Float) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
    ) {
        val cx = size.width * 0.5f
        if (!running) {
            drawLine(C.Line, Offset(cx, 0f), Offset(cx, size.height), 2.dp.toPx(), StrokeCap.Round)
            return@Canvas
        }
        val thick = 3.dp.toPx()
        // Dim solid underlay so the conductor reads as live between the dashes.
        // The phase is negated so the dashes travel the way the power goes.
        drawLine(C.Pump.copy(alpha = 0.30f), Offset(cx, 0f), Offset(cx, size.height), thick, StrokeCap.Round)
        drawLine(
            C.Pump, Offset(cx, 0f), Offset(cx, size.height), thick, StrokeCap.Round,
            PathEffect.dashPathEffect(
                floatArrayOf(DASH_LEN.dp.toPx(), GAP_LEN.dp.toPx()),
                -phase.dp.toPx()
            )
        )
    }
}

@Composable
private fun RunTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(C.Field, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(label, color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = if (value == "—") C.Dim else C.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun PanelPage(s: Status, live: Boolean, onKey: (Int) -> Unit) {
    // No card around any of this. The page IS the panel - a frame drawn round
    // it only said "here is a picture of a panel", which is one border too many
    // on a screen whose point is that the black is the surround. The lit blue
    // glass is the only filled thing here, and the keys sit under it the way
    // round the hardware has them.
    //
    // Routed outside the shared page body so it keeps the full screen width:
    // that extra 32 dp is what the key cluster and the screen grow into.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight

        // Centred on a phone, scrollable on a short screen or at a large font
        // scale. The minimum height is what lets Arrangement.Center mean
        // anything: inside a scroll a column is only as tall as its content,
        // so without it the cluster would sit at the top with the blue empty
        // beneath it.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = 10.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LcdMirror(if (live) s.lcd else emptyList())

            Spacer(Modifier.height(44.dp))
            KeyPad(live, onKey)
            Spacer(Modifier.height(32.dp))

            Text(
                if (live) "These press the board's own buttons"
                else "Connect to use the board's buttons",
                color = C.Dim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun NetworkPage(s: Status, live: Boolean, room: Dp) {
    val addr = if (s.eth.isNotEmpty()) s.eth else s.wifi
    val hasWeb = live && addr.isNotEmpty() && s.webOn
    val each = shareOf(room, if (hasWeb) 2 else 1)

    CardBox {
        SectionLabel("BOARD NETWORK")
        Spacer(Modifier.height(14.dp))
        StatusLine(
            "Ethernet",
            live && s.eth.isNotEmpty(),
            if (live) s.eth.ifEmpty { "down" } else "—"
        )
        StatusLine(
            "WiFi",
            live && s.wifi.isNotEmpty(),
            if (live) s.wifi.ifEmpty { "off" } else "—"
        )
        StatusLine(
            "Current sensor",
            live && s.adcOk,
            if (live) (if (s.adcOk) "ok" else "no reply") else "—"
        )
    }

    if (hasWeb) {
        Spacer(Modifier.height(12.dp))
        CardBox {
            SectionLabel("WEB PAGE")
            Spacer(Modifier.height(12.dp))
            Text("http://$addr", color = C.Ok, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Any browser on the same network", color = C.Dim, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun DevicePage(
    s: Status,
    live: Boolean,
    reply: String?,
    onSaveWifi: (String, String) -> Unit,
    onClearWifi: () -> Unit,
    onSetPin: (String) -> Unit,
    onSetLine: (Int) -> Unit,
    onSetCloudKey: (String) -> Unit,
    onClearCloud: () -> Unit,
    onCloudStatus: () -> Unit,
    onSetBilling: (Boolean) -> Unit
) {
    var confirmBilling by remember { mutableStateOf(false) }
    var cloudKey by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }

    CardBox {
        SectionLabel("MEASURED LINE")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in 1..2) {
                val on = live && s.line == n
                Box(Modifier.weight(1f)) {
                    WideButton("Line $n", live, if (on) C.Pump else C.Dim) { onSetLine(n) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (live) "The controller is reading line ${s.line}. Switching takes effect at once."
            else "Connect to choose which of the board's two lines is measured.",
            color = C.Dim,
            fontSize = 11.sp
        )
        Text(
            "Run time and history are kept for the board as a whole, not per line.",
            color = C.Dim,
            fontSize = 11.sp
        )
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("BOARD WIFI")
        Spacer(Modifier.height(12.dp))
        Field(ssid, { ssid = it }, "Network name (SSID)", enabled = live)
        Spacer(Modifier.height(8.dp))
        Field(pass, { pass = it }, "Password", enabled = live, secret = true)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                PrimaryButton("Save", live && ssid.isNotBlank()) {
                    onSaveWifi(ssid.trim(), pass); pass = ""
                }
            }
            Box(Modifier.weight(1f)) {
                WideButton("Turn off", live, C.Dim) { onClearWifi() }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Applies on the board's next reboot", color = C.Dim, fontSize = 11.sp)
    }

    if (confirmBilling) {
        AlertDialog(
            onDismissRequest = { confirmBilling = false },
            containerColor = Color(0xFF0C0F13),
            titleContentColor = C.Text,
            textContentColor = C.Dim,
            title = { Text("Turn on prepaid billing?") },
            text = {
                Text(
                    "The pump will run only on paid time. When the token is used up the " +
                        "pump is switched off until a top-up.\n\n" +
                        "Nothing locks until the controller has received a balance from the server. " +
                        "A controller with no credit locks as soon as it does."
                )
            },
            confirmButton = {
                TextButton(onClick = { onSetBilling(true); confirmBilling = false }) {
                    Text("Turn on", color = C.Pump)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBilling = false }) { Text("Cancel", color = C.Dim) }
            }
        )
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("PREPAID BILLING")
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                !live -> "Connect to the controller to change this."
                !s.billingOn -> "Off. The pump runs whether or not it has been paid for."
                s.tokenLocked -> "On. Token used up - pump locked."
                s.tokenSecs >= 0 -> "On. ${secText(s.tokenSecs)} of pumping left."
                else -> "On. Waiting for the first balance from the server - nothing is locked until then."
            },
            color = if (s.tokenLocked) Color(0xFFF5B301) else C.Dim,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                WideButton("Turn on", live && !s.billingOn, C.Pump) { confirmBilling = true }
            }
            Box(Modifier.weight(1f)) {
                WideButton("Turn off", live && s.billingOn, C.Dim) { onSetBilling(false) }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("CLOUD UPLOAD")
        Spacer(Modifier.height(12.dp))
        Field(cloudKey, { cloudKey = it }, "Upload key", enabled = live, secret = true)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                WideButton("Save key", live && cloudKey.trim().length >= 12) {
                    onSetCloudKey(cloudKey.trim()); cloudKey = ""
                }
            }
            Box(Modifier.weight(1f)) {
                WideButton("Status", live, C.Dim) { onCloudStatus() }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Lets the controller report to Firebase over WiFi, which is what makes " +
                "M-Pesa top-ups possible. It uploads only once WiFi is joined and its clock is set. " +
                "The key is never shown again after saving.",
            color = C.Dim,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        WideButton("Turn upload off", live, C.Dim) { onClearCloud() }
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("WEB CONTROL PIN")
        Spacer(Modifier.height(12.dp))
        Field(pin, { pin = it }, "New PIN (4-8 digits)", enabled = live, secret = true)
        Spacer(Modifier.height(12.dp))
        WideButton("Change PIN", live && pin.length >= 4) {
            onSetPin(pin.trim()); pin = ""
        }
        Spacer(Modifier.height(8.dp))
        Text("Not the Bluetooth pairing code", color = C.Dim, fontSize = 11.sp)
    }

    if (reply != null) {
        Spacer(Modifier.height(12.dp))
        // Echoed verbatim: the board words its own rejections precisely, and
        // paraphrasing them here would only lose detail.
        Text(reply, color = C.Ok, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
//  Help & support
// ---------------------------------------------------------------------------
@Composable
private fun SupportPage(s: Status, live: Boolean, onGo: (Page) -> Unit, initialQuery: String) {
    // Starts from whatever was typed in the dashboard's Ask box, and follows it if
    // a new question is asked while this page is already open.
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var open by remember { mutableStateOf<String?>(null) }

    val q = query.trim()
    val matches = remember(q) {
        if (q.isEmpty()) emptyList()
        else Help.items.filter {
            it.q.contains(q, true) || it.a.contains(q, true) ||
                it.category.contains(q, true)
        }
    }

    CardBox {
        SectionLabel("ASK")
        Spacer(Modifier.height(10.dp))
        Field(query, { query = it }, "Search the answers")
        Spacer(Modifier.height(8.dp))
        Text(
            "Searches the built-in answers below. Not a chatbot - the board is " +
                "offline hardware and this app talks to nothing but it.",
            color = C.Dim,
            fontSize = 11.sp
        )
    }

    Spacer(Modifier.height(12.dp))

    if (q.isNotEmpty()) {
        if (matches.isEmpty()) {
            CardBox {
                Text("Nothing matched \"$q\".", color = C.Text, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Try a single word - pump, litres, pair, wifi, pin, iphone.",
                    color = C.Dim,
                    fontSize = 12.sp
                )
            }
        } else {
            CardBox {
                SectionLabel("${matches.size} RESULT${if (matches.size == 1) "" else "S"}")
                Spacer(Modifier.height(6.dp))
                matches.forEach { item ->
                    FaqRow(item, open == item.q) { open = if (open == item.q) null else item.q }
                }
            }
        }
    } else {
        Help.categories.forEach { cat ->
            val inCat = Help.items.filter { it.category == cat }
            CardBox {
                SectionLabel(cat.uppercase())
                Spacer(Modifier.height(6.dp))
                inCat.forEach { item ->
                    FaqRow(item, open == item.q) { open = if (open == item.q) null else item.q }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    Spacer(Modifier.height(4.dp))
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(38.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("PumpPay 2.0", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (live) "Board up ${uptimeText(s.upSeconds)}" else "Board not connected",
                    color = C.Dim,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun FaqRow(item: Faq, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                item.q,
                color = if (expanded) C.Ok else C.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (expanded) "−" else "+",
                color = C.Dim,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(item.a, color = C.Dim, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun DevicePicker(
    devices: List<BluetoothDevice>,
    bluetoothOn: Boolean,
    link: Link,
    onPick: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0C0F13),
        titleContentColor = C.Text,
        textContentColor = C.Dim,
        title = { Text("Connect to board") },
        text = {
            Column {
                if (!bluetoothOn) {
                    Text("Bluetooth is off. Turn it on in Android settings.", color = C.Bad)
                } else if (devices.isEmpty()) {
                    Text(
                        "No paired devices. Pair ${BtClient.DEVICE_NAME} in " +
                            "Android Settings > Bluetooth first."
                    )
                } else {
                    if (link == Link.Connecting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                color = C.Ok,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Connecting...", color = C.Dim, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    devices.forEach { d ->
                        val isBoard = d.name?.contains("PUMPPAY", true) == true
                        TextButton(
                            onClick = { onPick(d) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    d.name ?: "(unnamed)",
                                    color = if (isBoard) C.Ok else C.Text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(d.address, color = C.Dim, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = C.Dim) }
        }
    )
}
