package ke.tafiti.pumppay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
//  Palette
// ---------------------------------------------------------------------------
// Deliberately the same colours as the board's web page: black, white and the
// PumpPay green. Someone who uses both should not have to relearn anything.
object C {
    // Pure black, not a dark grey. On the OLED panels these phones use, black
    // pixels are simply off - so the surround genuinely disappears and the
    // numbers are the only thing emitting light.
    val Bg = Color(0xFF000000)

    // Nothing is filled any more. Cards are outlines on the same black, so the
    // page reads as one surface with structure drawn on it rather than a stack
    // of floating tiles.
    val Card = Color(0xFF000000)
    val Line = Color(0xFF232830)

    val Text = Color(0xFFF2F5F8)
    val Dim = Color(0xFF79848F)

    // The PumpPay green, rgb(89, 242, 35). The one accent: pump running, live,
    // highlighted bars. Everything else is white, grey or black.
    val Pump = Color(0xFF59F223)
    val Ok = Pump
    val Bad = Color(0xFFFF5252)

    // The mirrored screen, as the board's own module actually looks: a blue
    // backlight with pale characters burnt through it. Matching the hardware
    // is the whole job of that panel - someone glancing between the phone and
    // the box should see the same screen twice, not a green terminal on one
    // and a blue LCD on the other.
    val Lcd = Color(0xFFF2F7FF)
    val LcdBg = Color(0xFF0B46C8)

    // The unlit glass, for when there is no signal to mirror. A backlight that
    // stays bright with nothing on it looks like a screen showing blanks; this
    // looks like a screen that is off, which is the truth.
    val LcdOff = Color(0xFF0A1B33)

    // Inputs get the faintest possible lift - just enough to read as a place
    // you can type, not enough to become a tile.
    val Field = Color(0xFF0A0C0F)
}

/**
 * A value the board has not reported is an em dash, never a number.
 *
 * This is the most important formatting rule in the app. A zero and a "not
 * measured" mean opposite things on a meter, and any display that renders them
 * alike will eventually have someone read 0.00 V off a channel that was never
 * live at all.
 */
fun fmt(v: Double, ok: Boolean, dp: Int): String =
    if (!ok) "—" else String.format("%.${dp}f", v)

fun uptimeText(s: Long): String {
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${s % 60}s"
}

// ---------------------------------------------------------------------------
//  Building blocks
// ---------------------------------------------------------------------------
/**
 * The one card shape every page uses.
 *
 * Content sits centred rather than pinned to the top, which matters because
 * callers now stretch cards to share the height of the screen. Left top-
 * aligned, a stretched card puts three lines of text at the top and leaves a
 * hand's width of black under them - which reads as something failing to load
 * rather than as a page with little on it.
 */
@Composable
fun CardBox(modifier: Modifier = Modifier, body: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column { body() }
    }
}

/**
 * How tall each of [count] cards should be to share the visible page.
 *
 * Pages carrying two facts looked broken on a tall phone - a pair of small
 * boxes marooned in black. Rather than inventing filler to pad them out, the
 * cards are simply told how much room is going spare and allowed to grow into
 * it. The cap stops a very tall screen from stretching two lines of text over
 * half its height, and the floor keeps this from ever making a card smaller
 * than it would have been on its own.
 */
fun shareOf(room: Dp, count: Int, gap: Dp = 12.dp, max: Dp = 230.dp): Dp {
    if (count <= 0) return 0.dp
    val each = (room - gap * (count - 1)) / count
    return each.coerceIn(0.dp, max)
}

/**
 * Connection state, as one pill.
 *
 * Defined once and used in both the top bar and the drawer, so the two can
 * never drift into saying the same thing two different ways - which is exactly
 * what happened when the drawer spelled out "Board not connected" while the bar
 * said "Offline" two inches above it.
 */
/** The Connect button that sits where the status badge was, at the top right of the home screen. */
@Composable
fun ConnectPill(connecting: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(C.Ok.copy(alpha = 0.14f), RoundedCornerShape(50))
            .border(1.dp, C.Ok.copy(alpha = 0.7f), RoundedCornerShape(50))
            .clickable(enabled = !connecting, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Text(if (connecting) "Connecting" else "Connect", color = C.Ok, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusPill(link: Link, modifier: Modifier = Modifier) {
    val connected = link == Link.Connected
    val tint = if (connected) C.Ok else C.Dim

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(tint.copy(alpha = 0.13f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(tint, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            when (link) {
                Link.Connected -> "Live"
                Link.Connecting -> "Connecting"
                else -> "Offline"
            },
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        color = C.Dim,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )
}

/**
 * The dashboard's offline notice, and the way into the device picker.
 *
 * Deliberately on the dashboard ONLY. It used to head every page, which meant
 * walking through the app while disconnected was six identical blocks of the
 * same paragraph - the app telling you something you already knew, once per
 * screen, above the data you came to look at. The top bar's Offline pill says
 * it everywhere; this says it once, where you land, and gives you the button.
 */
@Composable
fun UnreachableCard(link: Link, error: String?, onConnect: () -> Unit) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(20.dp)
                    .border(1.5.dp, C.Dim, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("i", color = C.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (link == Link.Connecting) "Connecting to the board"
                else "PumpPay unreachable",
                color = C.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            error ?: "The app is not connected to the board over Bluetooth. " +
                "Readings and controls stay unavailable until it is.",
            color = C.Dim,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Text,
                contentColor = C.Bg
            )
        ) {
            Text("Connect", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun Metric(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = C.Dim, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = if (value == "—") C.Dim else C.Text,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            if (value != "—") {
                Spacer(Modifier.width(2.dp))
                Text(unit, color = C.Dim, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun LcdMirror(rows: List<String>, modifier: Modifier = Modifier) {
    val lit = rows.isNotEmpty()

    // The blue glass IS the mirror. Everything else on the panel sits on the
    // app's black, so the one lit rectangle is unmistakably the board's screen
    // rather than another block of text about it.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Type sized so twenty columns fill the width available, rather than
        // picked as a number. The board's screen is twenty characters wide and
        // that is the one dimension worth matching - pinning the font instead
        // meant the glass was as wide as 20 sp happened to make it, which on a
        // phone left a third of the screen unused. Rows never wrap, so this
        // deliberately errs small: the estimate below is monospace advance
        // (0.6 em) plus the letter spacing (0.06 em), with a few per cent in
        // hand for fonts whose metrics differ.
        val pad = 14.dp
        val perChar = (maxWidth - pad * 2) / LCD_COLS
        val size = (perChar.value / 0.68f).coerceIn(14f, 34f).sp

        Box(
            Modifier
                .fillMaxWidth()
                .background(if (lit) C.LcdBg else C.LcdOff, RoundedCornerShape(12.dp))
                .border(1.dp, if (lit) C.LcdBg else C.Line, RoundedCornerShape(12.dp))
                .padding(horizontal = pad, vertical = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!lit) {
                    // Two blank rows rather than one line of text, so the panel
                    // keeps the shape of the real 20x2 screen whether or not
                    // the board is talking. A mirror that changes size when the
                    // signal drops does not look like a mirror.
                    repeat(LCD_ROWS) {
                        LcdRow(if (it == 0) "  no signal" else "", size, dim = true)
                    }
                } else {
                    rows.forEach { LcdRow(it, size, dim = false) }
                }
            }
        }
    }
}

/** The board's screen is twenty characters by two. See display.cpp. */
private const val LCD_COLS = 20
private const val LCD_ROWS = 2

/**
 * One line of the mirrored screen.
 *
 * Padded out to the full twenty columns so the block of glass stays the same
 * width from frame to frame. Without it the panel visibly breathed in and out
 * as the text behind it changed length, which no physical LCD does.
 */
@Composable
private fun LcdRow(text: String, size: TextUnit, dim: Boolean) {
    Text(
        text.take(LCD_COLS).padEnd(LCD_COLS),
        color = if (dim) C.Lcd.copy(alpha = 0.28f) else C.Lcd,
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        // Spacing in em, not sp, so it tracks the size the glass was measured
        // for. A fixed 1.2 sp would have thrown off the width estimate above
        // the moment the font grew.
        letterSpacing = 0.06.em,
        maxLines = 1,
        softWrap = false
    )
}

/**
 * The board's four buttons, as a directional pad.
 *
 * Four equal buttons in a row was the wrong shape for the job. UP and DOWN are
 * a pair and belong on an axis; OK is the thing you commit with and belongs in
 * the middle; BACK is the way out and belongs off to the side. Laid out that
 * way the cluster can be worked by position - which is how a physical panel is
 * actually used, and no row of identical rectangles ever manages.
 *
 * Key numbers match the board: 1 UP, 2 DOWN, 3 OK, 4 BACK.
 */
@Composable
fun KeyPad(enabled: Boolean, onKey: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Sized from the room it is actually given rather than a fixed number of
    // dp. This is a stand-in for a physical panel, and a physical panel is
    // worked with a thumb without looking at it - so the keys want to be as
    // large as the screen can afford, not a polite 58 dp. The cap keeps a
    // tablet from drawing a dinner plate.
    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val dim = minOf(maxWidth, 460.dp)
        val key = dim * 0.32f
        val ok = dim * 0.40f

        Box(Modifier.size(dim)) {
            // The ring the cluster sits on. Purely to read as one control
            // rather than four loose buttons that happen to be near each other.
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.dp, C.Line, CircleShape)
            )

            PadKey(
                icon = Icons.Filled.KeyboardArrowUp,
                label = "UP",
                enabled = enabled,
                size = key,
                modifier = Modifier.align(Alignment.TopCenter)
            ) { onKey(1) }

            PadKey(
                icon = Icons.Filled.KeyboardArrowDown,
                label = "DOWN",
                enabled = enabled,
                size = key,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) { onKey(2) }

            PadKey(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "BACK",
                enabled = enabled,
                size = key,
                modifier = Modifier.align(Alignment.CenterStart)
            ) { onKey(4) }

            // OK is the only filled key, because it is the only one that
            // commits anything.
            OkKey(enabled, ok, Modifier.align(Alignment.Center)) { onKey(3) }
        }
    }
}

@Composable
private fun PadKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    size: Dp,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) C.Text else C.Dim.copy(alpha = 0.35f),
            modifier = Modifier.size(size * 0.46f)
        )
        Text(
            label,
            color = if (enabled) C.Dim else C.Dim.copy(alpha = 0.35f),
            fontSize = (size.value * 0.16f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun OkKey(enabled: Boolean, size: Dp, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) C.Ok.copy(alpha = 0.16f) else C.Field)
            .border(1.5.dp, if (enabled) C.Ok else C.Line, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "OK",
            color = if (enabled) C.Ok else C.Dim.copy(alpha = 0.35f),
            fontSize = (size.value * 0.26f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun ModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) C.Ok.copy(alpha = 0.10f) else C.Field,
            contentColor = if (selected) C.Ok else C.Text,
            disabledContainerColor = C.Field,
            disabledContentColor = C.Dim.copy(alpha = 0.4f)
        )
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusLine(label: String, ok: Boolean, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (ok) C.Ok else C.Bad, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = C.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.Text, fontSize = 13.sp, textAlign = TextAlign.End)
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(label, color = C.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.Text, fontSize = 13.sp, textAlign = TextAlign.End)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    secret: Boolean = false,
    keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label, color = C.Dim, fontSize = 13.sp) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else keyboard),
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text,
            unfocusedTextColor = C.Text,
            disabledTextColor = C.Dim,
            focusedContainerColor = C.Field,
            unfocusedContainerColor = C.Field,
            disabledContainerColor = C.Field,
            focusedBorderColor = C.Ok,
            unfocusedBorderColor = C.Line,
            disabledBorderColor = C.Line,
            cursorColor = C.Ok
        )
    )
}

/** The one main action on a screen: solid light, black text. Everything else is a WideButton. */
@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (enabled) androidx.compose.ui.graphics.Color(0xFFEDEDED) else androidx.compose.ui.graphics.Color(0xFFEDEDED).copy(alpha = 0.3f),
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = androidx.compose.ui.graphics.Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun WideButton(
    label: String,
    enabled: Boolean = true,
    accent: Color = C.Ok,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = C.Field,
            contentColor = accent,
            disabledContainerColor = C.Field,
            disabledContentColor = C.Dim.copy(alpha = 0.4f)
        )
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
