package ke.tafiti.pumppay

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Water
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/*
  The dashboard.

  A large picture of the system fills the top of the screen: solar array,
  controller and pump on ONE straight vertical line, joined end to end, with the
  status of each beside it. Over its lower part sits a sheet that rests low and can
  be dragged up (or tapped open) to reveal everything; as it rises, the picture
  shrinks smoothly above it instead of being covered.

  Every line of text here is something the board or the app genuinely knows. There
  is no weather, tank level or irrigation advice because nothing on the board
  measures them.

  THE PANEL'S SPARKLES. White glints on the solar panel get brighter and more
  numerous the harder the array is working. The board cannot see the panel - it
  measures the pump line - so "how hard" is worked back from the pump's power
  (see [sunLevel]). It is a visual cue for strength, not a measurement of sunshine.
*/

private val Amber = Color(0xFFF5B301)
private val Steel = Color(0xFF8A94A0)
private val Cable = Color(0xFF3A414A)
private val Panel = Color(0xFF0E1A24)
private val Tile = Color(0xFF16191D)
private val SheetBg = Color.Black

/** 0..1: how hard the array is working. 0 when nothing is being drawn. */
/**
 * One part of the system, drawn large - about half the screen - with no card or
 * frame around it. Used at the top of the Solar, Controller, Pump and Tank pages.
 */
@Composable
fun HeroPicture(kind: Page, s: Status, live: Boolean, valveOpen: Boolean = true, valvePct: Float = 1f) {
    val t = rememberInfiniteTransition(label = "hero")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "flow")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "spin")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "twinkle")
    val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    val running = live && s.pumping
    val height = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.42f).dp

    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        when (kind) {
            Page.Solar -> Box(Modifier.fillMaxWidth(0.9f).fillMaxHeight()) { PanelArt(running, sunLevel(s, live), twinkle) }
            Page.Controller -> Box(Modifier.fillMaxWidth().height(height * 0.55f), contentAlignment = Alignment.Center) {
                ControllerArt(live, running, pulse, flow)
            }
            Page.PumpDetail -> Box(Modifier.fillMaxWidth(0.8f).fillMaxHeight()) { PumpArt(running, spin, flow) }
            else -> TankArt(running, Modifier.fillMaxSize(), valveOpen, valvePct)
        }
    }
}

private fun sunLevel(s: Status, live: Boolean): Float {
    if (!live || !s.pumping || !s.vOk || !s.aOk) return 0f
    val inVa = s.va / CONTROLLER_EFFICIENCY
    // 25 VA is a comfortable full load for the small 12 V systems this is sized
    // for; anything above it saturates at 1. Never fully dark while running.
    return (inVa / 25.0).toFloat().coerceIn(0.15f, 1f)
}

@Composable
fun DashboardScreen(
    s: Status,
    link: Link,
    error: String?,
    live: Boolean,
    flowLpm: Float,
    systemVolts: Float,
    store: UsageStore,
    onConnect: () -> Unit,
    onGo: (Page) -> Unit,
    onAsk: (String) -> Unit
) {
    val running = live && s.pumping
    val level = sunLevel(s, live)


    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // The whole system, as large as the page allows.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SystemPicture(s, live, running, level, onGo)
        }

        if (!live) {
            Box(Modifier.padding(horizontal = 18.dp)) { UnreachableCard(link, error, onConnect) }
            Spacer(Modifier.height(10.dp))
        }

        Box(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 22.dp)) {
            AskBar { onGo(Page.Chat) }
        }
    }
}

// ---------------------------------------------------------------------------
//  The picture: one vertical spine
// ---------------------------------------------------------------------------
@Composable
private fun SystemPicture(s: Status, live: Boolean, running: Boolean, level: Float, onGo: (Page) -> Unit) {
    // One clock drives every animation, so the sparkles, the flowing cable, the
    // spinning impeller and the drips stay in step rather than drifting apart.
    val t = rememberInfiniteTransition(label = "system")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "flow")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "spin")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "twinkle")
    val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    val haveVa = live && s.vOk && s.aOk
    val solarW = if (running && haveVa) (s.va / CONTROLLER_EFFICIENCY).roundToInt() else 0
    val solarWord: Pair<String, Color> = when {
        !live -> "Unknown" to C.Dim
        !running -> "Standing by" to C.Dim
        level >= 0.75f -> "Strong output" to C.Pump
        level >= 0.4f -> "Good output" to C.Pump
        else -> "Low output" to Amber
    }
    val solar: Pair<String, Color> = if (live) (solarWord.first + "\n" + solarW + " W") to solarWord.second else solarWord

    // How the phone and the controller are talking: Bluetooth is the app's own link, and the
    // board may also be on WiFi or Ethernet.
    val net = buildList {
        if (live) add("Bluetooth")
        if (live && s.wifi.isNotEmpty()) add("WiFi")
        if (live && s.eth.isNotEmpty()) add("Ethernet")
    }
    val controller: Pair<String, Color> =
        if (live) ("Online" + (if (net.isNotEmpty()) "\n" + net.joinToString(" · ") else "")) to C.Pump
        else "Offline" to C.Dim
    val pump: Pair<String, Color> = when {
        !live -> "Unknown" to C.Dim
        s.manualOff -> "Switched off" to Amber
        s.tokenLocked -> "Locked · token used up" to Amber
        s.noLoad() -> "Not connected" to C.Bad
        s.state == "NO SIGNAL" -> "Sensor not answering" to C.Bad
        s.pumping -> ("Running · ${secText(s.runCurSecs)}" + (if (haveVa) "\n" + s.va.roundToInt() + " W" else "")) to C.Pump
        else -> "Off, ready" to C.Dim
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
        ) {
            Stage(0.34f, solar, "Solar", { onGo(Page.Solar) }) { PanelArt(running, level, twinkle) }
            Link(0.11f, running, flow)
            Stage(0.16f, controller, "Controller", { onGo(Page.Controller) }) { ControllerArt(live, running, pulse, flow) }
            Link(0.13f, running, flow)
            Stage(0.26f, pump, "Pump", { onGo(Page.PumpDetail) }) { PumpArt(running, spin, flow) }
        }
    }
}

/** One part: its picture, centred in the left part; name and status on the right. */
@Composable
private fun ColumnScope.Stage(
    weight: Float,
    status: Pair<String, Color>,
    name: String,
    onClick: () -> Unit,
    art: @Composable () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .weight(weight)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .weight(0.64f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) { art() }
        Column(
            Modifier
                .weight(0.36f)
                .padding(start = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(status.second, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(name, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text(status.first, color = C.Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/** The cable between two parts, on the spine: grey when idle, green with moving light when running. */
@Composable
private fun ColumnScope.Link(weight: Float, running: Boolean, flow: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .weight(weight)
    ) {
        Box(
            Modifier
                .weight(0.64f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                Modifier
                    .width(8.dp)
                    .fillMaxHeight()
            ) {
                val cx = size.width / 2
                drawLine(Cable, Offset(cx, 0f), Offset(cx, size.height), 5.dp.toPx(), StrokeCap.Butt)
                if (running) {
                    drawLine(C.Pump.copy(alpha = 0.35f), Offset(cx, 0f), Offset(cx, size.height), 5.dp.toPx(), StrokeCap.Butt)
                    drawLine(
                        C.Pump, Offset(cx, 0f), Offset(cx, size.height), 3.dp.toPx(), StrokeCap.Butt,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 13.dp.toPx()), -flow * 20.dp.toPx())
                    )
                }
            }
        }
        Spacer(Modifier.weight(0.36f))
    }
}

// ---------------------------------------------------------------------------
//  Solar panel - white sparkles grow with the array's output
// ---------------------------------------------------------------------------
@Composable
private fun PanelArt(running: Boolean, level: Float, twinkle: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // A symmetric panel, so the stand drops from its exact centre onto the spine.
        val tl = Offset(w * 0.20f, h * 0.08f)
        val tr = Offset(w * 0.80f, h * 0.08f)
        val br = Offset(w * 0.95f, h * 0.76f)
        val bl = Offset(w * 0.05f, h * 0.76f)

        fun at(u: Float, v: Float): Offset {
            val top = Offset(tl.x + (tr.x - tl.x) * u, tl.y + (tr.y - tl.y) * u)
            val bot = Offset(bl.x + (br.x - bl.x) * u, bl.y + (br.y - bl.y) * u)
            return Offset(top.x + (bot.x - top.x) * v, top.y + (bot.y - top.y) * v)
        }

        // The stand: ONE vertical line from the centre of the panel down to the
        // bottom edge, where the cable below picks it up. No base bar - a horizontal
        // piece here looked cut off from the line it was meant to be part of.
        drawLine(Cable, Offset(w * 0.5f, h * 0.76f), Offset(w * 0.5f, h), 5.dp.toPx(), StrokeCap.Butt)

        // The panel face.
        val face = Path().apply { moveTo(tl.x, tl.y); lineTo(tr.x, tr.y); lineTo(br.x, br.y); lineTo(bl.x, bl.y); close() }
        drawPath(face, Brush.verticalGradient(listOf(Color(0xFF2B63B8), Color(0xFF1A3F85)), tl.y, bl.y))

        // The cells stay solar blue and brighten as the output rises - every cell the
        // same colour, a soft wave passing across so the strength reads as motion.
        val cols = 6
        val rows = 3
        if (level > 0f) {
            for (cj in 0 until rows) for (ci in 0 until cols) {
                val wave = 0.5f + 0.5f * sin(twinkle * 2f * PI.toFloat() - (ci + cj * 0.6f) * 0.9f)
                val alpha = (0.06f + 0.40f * level) * (0.65f + 0.35f * wave)
                val u0 = ci / cols.toFloat(); val u1 = (ci + 1) / cols.toFloat()
                val v0 = cj / rows.toFloat(); val v1 = (cj + 1) / rows.toFloat()
                val cell = Path().apply {
                    moveTo(at(u0, v0).x, at(u0, v0).y); lineTo(at(u1, v0).x, at(u1, v0).y)
                    lineTo(at(u1, v1).x, at(u1, v1).y); lineTo(at(u0, v1).x, at(u0, v1).y); close()
                }
                drawPath(cell, Color(0xFFBFE0FF).copy(alpha = alpha))
            }
        }

        // Cell lines.
        val line = Color(0xFF0F2A5E)
        for (i in 1 until cols) drawLine(line, at(i / cols.toFloat(), 0f), at(i / cols.toFloat(), 1f), 1.4.dp.toPx())
        for (j in 1 until rows) drawLine(line, at(0f, j / rows.toFloat()), at(1f, j / rows.toFloat()), 1.4.dp.toPx())

        // A slow sheen sweeping across, stronger with output.
        if (level > 0f) {
            val u0 = twinkle * 1.5f - 0.25f
            val band = Path().apply {
                moveTo(at(u0, 0f).x, at(u0, 0f).y)
                lineTo(at(u0 + 0.16f, 0f).x, at(u0 + 0.16f, 0f).y)
                lineTo(at(u0 + 0.16f, 1f).x, at(u0 + 0.16f, 1f).y)
                lineTo(at(u0, 1f).x, at(u0, 1f).y)
                close()
            }
            clipPath(face) { drawPath(band, Color.White.copy(alpha = 0.10f * level)) }
        }

        // Frame on top.
        drawPath(face, Steel, style = Stroke(width = 2.2.dp.toPx()))
    }
}

// ---------------------------------------------------------------------------
//  Controller
// ---------------------------------------------------------------------------
// The PP mark from the supplied artwork, drawn as a stroke.
private const val PP_MARK = "M287.167 276.442 L287.167 280.393 L287.167 280.393 L287.167 284.426 L287.167 288.38 L287.167 292.334 L287.167 300.155 L287.167 307.975 L287.167 311.885 L287.167 311.886 L287.167 315.796 L291.152 315.796 C291.06 315.793 290.968 315.787 290.876 315.778 C290.969 315.787 291.063 315.793 291.158 315.796 C293.362 315.796 295.149 314.045 295.149 311.886 C295.149 311.859 295.148 311.832 295.148 311.805 L295.149 311.805 L295.149 307.975 L295.149 304.028 L295.149 300.155 L299.14 300.155 L299.14 304.028 L299.14 307.975 L299.14 311.805 L299.14 315.796 L299.14 319.744 L299.14 323.614 L299.14 323.617 C299.143 323.713 299.149 323.809 299.16 323.905 C299.15 323.81 299.143 323.715 299.14 323.619 L299.14 327.49 L302.583 327.49 L303.131 327.49 L303.68 327.49 C305.355 327.262 306.702 326.025 307.041 324.401 L307.041 323.617 L307.041 322.832 L307.041 319.743 L307.041 315.796 L307.041 311.805 L311.114 311.805 L315.105 311.805 L319.05 311.805 L319.052 311.805 C319.067 311.805 319.081 311.805 319.096 311.805 C320.457 311.8 321.806 311.569 323.088 311.124 C324.56 310.611 325.916 309.824 327.079 308.805 C327.378 308.542 327.664 308.266 327.935 307.975 C329.004 306.829 329.83 305.487 330.364 304.028 C330.819 302.784 331.055 301.475 331.063 300.155 C331.063 300.134 331.063 300.113 331.063 300.092 C331.063 298.795 330.842 297.508 330.409 296.281 C329.896 294.827 329.094 293.486 328.05 292.334 C327.745 291.998 327.421 291.679 327.079 291.379 C325.916 290.36 324.56 289.573 323.088 289.061 C321.806 288.615 320.457 288.385 319.096 288.38 L319.096 288.38 C319.096 288.352 319.096 288.325 319.096 288.298 C319.096 286.98 318.874 285.671 318.439 284.425 C317.773 282.516 316.625 280.807 315.105 279.461 C313.941 278.43 312.586 277.635 311.114 277.12 C309.832 276.671 308.482 276.442 307.123 276.442 C307.095 276.442 307.068 276.442 307.041 276.442 L303.131 276.442 L299.14 276.442 L295.149 276.442 L292.003 276.442 L290.393 276.442 Z M295.149 284.425 L295.806 284.425 L299.14 284.425 L303.131 284.425 L307.041 284.425 L307.123 284.425 C309.327 284.426 311.114 286.196 311.114 288.38 L307.041 288.38 L303.131 288.38 L299.14 288.38 L299.14 292.334 L295.864 292.334 L295.149 292.334 L295.149 288.38 Z M319.049 288.38 L319.052 288.38 C319.052 288.38 319.096 288.38 319.049 288.38 C319.257 288.363 319.049 288.38 319.049 288.38 Z M307.041 296.282 L307.695 296.282 L311.114 296.282 L315.105 296.282 L315.975 296.282 L319.091 296.282 L319.096 296.282 L319.101 296.282 C319.389 296.285 319.677 296.318 319.958 296.38 C320.016 296.39 320.074 296.402 320.131 296.414 C321.411 296.751 322.435 297.683 322.864 298.901 C322.952 299.144 323.016 299.394 323.053 299.649 C323.076 299.817 323.088 299.986 323.088 300.155 L323.088 300.155 C323.088 302.294 321.301 304.028 319.097 304.028 L319.096 304.028 L315.105 304.028 L311.114 304.028 L307.74 304.028 L307.041 304.028 L307.041 300.155 L307.041 300.101 L307.041 300.092 L307.041 300.083 Z"

/**
 * The PumpPay controller: a steel enclosure in the tank's grey with three clipped
 * corners, a recessed window, the PP mark and a centre strip that is green while the
 * controller is online and red when it is not.
 */
@Composable
fun ControllerBody(online: Boolean, running: Boolean, pulse: Float, flow: Float, modifier: Modifier = Modifier) {
    val status = if (online) Color(0xFF59F223) else Color(0xFFE5484D)
    val mark = remember { androidx.compose.ui.graphics.vector.PathParser().parsePathString(PP_MARK).toPath() }

    Canvas(modifier.aspectRatio(339f / 251f)) {
        val k = size.width / 339f
        scale(k, k, pivot = Offset.Zero) {
            val c = 20f   // clipped corner size

            // Body: top-right is square (it carries the window); the other three corners are clipped.
            val body = Path().apply {
                moveTo(c, 0f); lineTo(339f, 0f); lineTo(339f, 251f - c)
                lineTo(339f - c, 251f); lineTo(c, 251f); lineTo(0f, 251f - c); lineTo(0f, c); close()
            }
            // soft shadow
            translate(0f, 5f) { drawPath(body, Color.Black.copy(alpha = 0.35f)) }
            drawPath(body, Brush.verticalGradient(listOf(Color(0xFFBDBDBD), Color(0xFF9A9A9A), Color(0xFF858585))))
            drawPath(body, Color(0xFF6E6E6E), style = Stroke(3f, join = StrokeJoin.Round))
            // bevel highlight along the top edge
            drawLine(Color.White.copy(alpha = 0.45f), Offset(c + 4f, 4f), Offset(250f, 4f), 2.5f, StrokeCap.Round)

            // Recessed window, top right, with the status light
            drawRoundRect(Color(0xFF26292D), Offset(252f, 14f), Size(72f, 62f), CornerRadius(8f))
            drawRoundRect(Color(0xFF4A4F55), Offset(252f, 14f), Size(72f, 62f), CornerRadius(8f), style = Stroke(2f))
            val led = status
            drawCircle(status.copy(alpha = 0.25f), 14f, Offset(288f, 45f))
            drawCircle(led, 7f, Offset(288f, 45f))
            drawLine(Color.White.copy(alpha = 0.12f), Offset(262f, 22f), Offset(278f, 22f), 2f, StrokeCap.Round)

            // Vents, bottom left
            for (i in 0..2) drawLine(Color(0xFF6A6A6A), Offset(26f, 204f + i * 9f), Offset(64f, 204f + i * 9f), 3f, StrokeCap.Round)

            // The PP mark
            val s = 1.55f
            withTransform({
                translate(150f - 309f * s, 100f - 302f * s)
                scale(s, s, Offset.Zero)
            }) {
                val ppColor = Color(0xFF59F223).copy(alpha = if (online) 1f else 0.55f)
                mark.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                drawPath(mark, ppColor)
                drawPath(mark, ppColor, style = Stroke(0.7f, join = StrokeJoin.Round))
            }

            // Centre strip: green online, red offline, with a glow
            val sx = 62f; val sw = 176f; val sy = 178f
            for (g in 1..3) {
                drawRoundRect(status.copy(alpha = 0.10f), Offset(sx - g * 3f, sy - g * 3f), Size(sw + g * 6f, 9f + g * 6f), CornerRadius(8f + g * 3f))
            }
            drawRoundRect(Color(0xFF15181B), Offset(sx, sy), Size(sw, 9f), CornerRadius(4.5f))
            drawRoundRect(status, Offset(sx + 1.5f, sy + 1.5f), Size(sw - 3f, 6f), CornerRadius(3f))
        }
    }
}

@Composable
private fun ControllerArt(live: Boolean, running: Boolean, pulse: Float, flow: Float) {
    ControllerBody(live, running, pulse, flow, Modifier.fillMaxHeight(0.92f))
}

// ---------------------------------------------------------------------------
//  Pump: on the spine, casing with a spinning impeller, water leaving straight down
// ---------------------------------------------------------------------------
@Composable
private fun PumpArt(running: Boolean, spin: Float, flow: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val r = minOf(w * 0.22f, h * 0.30f)
        val c = Offset(w * 0.5f, h * 0.45f)

        // The spine comes in from the top and meets the casing.
        drawLine(Cable, Offset(c.x, 0f), Offset(c.x, c.y - r), 5.dp.toPx(), StrokeCap.Butt)
        if (running) {
            drawLine(C.Pump.copy(alpha = 0.35f), Offset(c.x, 0f), Offset(c.x, c.y - r), 5.dp.toPx(), StrokeCap.Butt)
        }

        // Casing.
        drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2F36), Color(0xFF14171B)), c, r), r, c)
        drawCircle(if (running) C.Pump.copy(alpha = 0.8f) else Steel, r, c, style = Stroke(3.dp.toPx()))

        // Impeller: three blades, turning only while the pump runs.
        rotate(if (running) spin else 0f, c) {
            for (k in 0..2) {
                rotate(k * 120f, c) {
                    drawLine(
                        if (running) C.Pump else Steel, c, Offset(c.x, c.y - r * 0.72f),
                        5.dp.toPx(), StrokeCap.Round
                    )
                }
            }
        }
        drawCircle(if (running) C.Pump else Steel, 6.dp.toPx(), c)
    }
}

// ---------------------------------------------------------------------------
//  The four tiles in the sheet's handle: evenly spaced, identical in size
// ---------------------------------------------------------------------------
@Composable
private fun QuickTiles(s: Status, live: Boolean, running: Boolean, onGo: (Page) -> Unit) {
    val billing = when {
        !live -> "—"
        s.tokenLocked -> "Locked"
        s.tokenSecs >= 0 -> secText(s.tokenSecs)
        else -> "Off"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickTile(Icons.Outlined.WaterDrop, "Water", if (live && s.timeKnown) secText(s.runTodaySecs) else "—", live, Modifier.weight(1f)) { onGo(Page.Usage) }
        QuickTile(
            Icons.Outlined.WbSunny, "Solar",
            if (running && s.vOk && s.aOk) "${(s.va / CONTROLLER_EFFICIENCY).roundToInt()} VA" else "Idle",
            running, Modifier.weight(1f)
        ) { onGo(Page.Solar) }
        QuickTile(
            Icons.Outlined.AccountBalanceWallet, "Billing", billing, live && s.tokenSecs >= 0 && !s.tokenLocked,
            Modifier.weight(1f), if (live && s.tokenLocked) Amber else null
        ) { onGo(Page.Billing) }
        QuickTile(Icons.Outlined.MonitorHeart, "Health", if (live) "Check" else "—", live, Modifier.weight(1f)) { onGo(Page.Diagnostics) }
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier,
    accent: Color? = null,
    onClick: () -> Unit
) {
    val tint = accent ?: if (active) C.Pump else C.Dim
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Tile)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
        Text(label, color = C.Dim, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------
//  The summaries
// ---------------------------------------------------------------------------
@Composable
private fun Summaries(
    s: Status,
    live: Boolean,
    flowLpm: Float,
    systemVolts: Float,
    store: UsageStore,
    running: Boolean,
    onGo: (Page) -> Unit
) {
    val checks = remember(s, live, systemVolts, store.version) { runChecks(s, live, systemVolts, store) }
    val problems = checks.filter { it.level == Level.Warn || it.level == Level.Fail }
    val todayText = if (live && s.timeKnown) secText(s.runTodaySecs) else null

    NavRow(
        Icons.Outlined.WaterDrop, "Water usage",
        when {
            !live -> "Connect to see today's pumping"
            todayText == null -> "Waiting for the controller's clock"
            flowLpm > 0f -> "$todayText pumped today · ≈ " +
                String.format("%,d", (s.runTodaySecs / 60.0 * flowLpm).roundToInt()) + " L"
            else -> "$todayText pumped today"
        }
    ) { onGo(Page.Usage) }

    val prefs = Prefs(androidx.compose.ui.platform.LocalContext.current)
    NavRow(
        Icons.Outlined.Grass, "Plant",
        run {
            val d = runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(prefs.plantedOn), java.time.LocalDate.now()) }.getOrNull()
            if (prefs.crop.isBlank()) "Set up your crop"
            else prefs.crop.replaceFirstChar { it.uppercase() } + (if (d != null && d >= 0) " · day $d" else "")
        }
    ) { onGo(Page.Plant) }

    NavRow(
        Icons.Outlined.Notifications, "Alerts",
        when {
            !live -> "Connect to check"
            problems.isEmpty() -> "Nothing needs your attention"
            else -> problems.first().title + " · " + problems.first().detail.take(60)
        },
        subtitleColor = if (live && problems.isNotEmpty()) Amber else null
    ) { onGo(Page.Diagnostics) }

    NavRow(
        Icons.Outlined.MonitorHeart, "Diagnosis",
        when {
            !live -> "Connect to run a check"
            problems.isEmpty() -> "All ${checks.count { it.level == Level.Ok }} checks passed"
            problems.size == 1 -> "1 thing to check"
            else -> "${problems.size} things to check"
        },
        subtitleColor = if (live && problems.isNotEmpty()) Amber else null
    ) { onGo(Page.Diagnostics) }
}

@Composable
private fun AskBar(onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF111316), RoundedCornerShape(32.dp))
            .border(1.dp, Color(0xFF23272D), RoundedCornerShape(32.dp))
            .clickable(onClick = onOpen)
            .padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Ask anything", color = C.Dim, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(46.dp).background(C.Pump, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Open the assistant", tint = Color.Black)
        }
    }
}

/** One summary line. Every icon sits in the same 46 dp rounded tile so they read as a set. */
@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(Tile, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = subtitleColor ?: C.Pump.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = subtitleColor ?: C.Dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFF59616B))
    }
}

