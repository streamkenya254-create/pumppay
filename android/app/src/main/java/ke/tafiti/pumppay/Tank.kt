package ke.tafiti.pumppay

import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/*
  The water tank on its stand, drawn to match the supplied picture.

  While the pump runs, water climbs the pipe on the right and the level in the
  tank rises. When it stops the level eases back down and a drip leaves the
  tank's outlet. This is an ILLUSTRATION of what the pump is doing: the board has
  no level sensor, so the height of the water is not a measurement.
*/

private val Steel = Color(0xFFA0A0A0)
private val WaterBlue = Color(0xFF4FC3F7)

// The tank scene, from the supplied artwork: the tank on its stand, an input pipe on
// the left, an output pipe on the right that runs to the crops.
private val TANK_BODY = "M297.335 469.522 C296.112 474.743 291.339 478.587 285.638 478.587 L249.694 478.587 C243.067 478.587 237.694 473.308 237.694 466.796 L237.694 400.7 C237.694 394.188 243.067 388.909 249.694 388.909 L285.638 388.909 C292.265 388.909 297.638 394.188 297.638 400.7 L297.638 465.268"
private val STRUCT3 = listOf(
    "M249.694 382.636 L285.638 382.636 L285.638 388.909 L249.694 388.909 Z"
)
private val STRUCT2 = listOf(
    "M224.485 606.936 L224.485 486.449 L228.485 486.449 L228.485 606.936 Z",
    "M301.963 606.936 L301.963 486.449 L305.963 486.449 L305.963 606.936 Z",
    "M216.994 480.176 L314.848 480.176 L314.848 486.218 L216.994 486.218 Z",
    "M212.058 606.936 L240.912 606.936 L240.912 611.671 L212.058 611.671 Z",
    "M289.536 606.936 L318.39 606.936 L318.39 611.671 L289.536 611.671 Z",
    "M312.706 465.486 L320.807 465.486 C327.108 465.486 332.216 470.594 332.216 476.895 L332.216 602.975 C332.216 607.067 335.533 610.384 339.625 610.384 L356.561 610.384",
    "M356.561 614.384 L339.625 614.384 C333.324 614.384 328.216 609.276 328.216 602.975 L328.216 476.895 C328.216 472.803 324.899 469.486 320.807 469.486 L312.706 469.486",
    "M308.199 469.486 L295.862 469.486",
    "M299.125 465.486 L308.152 465.486",
    "M307.952 459.512 L312.914 459.512 L312.914 475.031 L307.952 475.031 Z",
    "M228.485 578.011 L301.963 578.011",
    "M228.485 573.619 L301.963 573.619",
    "M231.323 573.619 L301.963 489.114",
    "M228.485 570.722 L299.125 486.217"
)
private val STRIPES = listOf(461.335f, 441.301f, 421.267f, 401.233f)
private val LEFT_PIPE = listOf(
    "M170.37 613.159 L170.37 431.513 C170.37 414.79 181.768 401.233 195.829 401.233 L238.104 401.233 L238.104 405.219 L195.829 405.219 C183.619 405.219 173.722 416.991 173.722 431.513 L173.722 613.159",
    "M173.722 604.095 C190.179 604.626 203.019 609.03 203.019 614.384 C203.019 620.096 188.402 624.727 170.37 624.727 C152.338 624.727 137.721 620.096 137.721 614.384 C137.721 608.672 152.338 604.041 170.37 604.041",
    "M170.37 609.303 C155.209 609.303 142.461 612.577 138.787 617.015",
    "M173.722 609.357 C187.384 609.799 198.553 612.909 201.953 617.015"
)
private val LEAVES = listOf(
    "M378.735 606.467 C378.068 606.082 378.527 604.038 379.761 601.902 C380.994 599.766 382.535 598.346 383.202 598.731 C383.869 599.117 383.409 601.16 382.176 603.297 C380.943 605.433 379.402 606.852 378.735 606.467 Z",
    "M403.92 606.467 C403.253 606.082 403.712 604.038 404.945 601.902 C406.179 599.766 407.719 598.346 408.386 598.731 C409.053 599.117 408.594 601.16 407.361 603.297 C406.128 605.433 404.587 606.852 403.92 606.467 Z",
    "M366.277 600.098 C365.074 599.403 365.902 595.715 368.128 591.86 C370.353 588.006 373.133 585.444 374.336 586.139 C375.54 586.834 374.712 590.522 372.486 594.377 C370.261 598.231 367.481 600.793 366.277 600.098 Z",
    "M391.781 600.098 C390.577 599.403 391.405 595.715 393.631 591.86 C395.856 588.006 398.636 585.444 399.84 586.139 C401.043 586.834 400.215 590.522 397.989 594.377 C395.764 598.231 392.984 600.793 391.781 600.098 Z",
    "M415.73 600.098 C414.526 599.403 415.355 595.715 417.58 591.86 C419.806 588.006 422.585 585.444 423.789 586.139 C424.992 586.834 424.164 590.522 421.939 594.377 C419.713 598.231 416.933 600.793 415.73 600.098 Z",
    "M373.657 598.731 C374.324 598.346 375.864 599.766 377.098 601.902 C378.331 604.038 378.79 606.082 378.123 606.467 C377.456 606.852 375.916 605.433 374.682 603.297 C373.449 601.16 372.99 599.117 373.657 598.731 Z",
    "M398.842 598.731 C399.509 598.346 401.049 599.766 402.282 601.902 C403.516 604.038 403.975 606.082 403.308 606.467 C402.641 606.852 401.1 605.433 399.867 603.297 C398.634 601.16 398.175 599.117 398.842 598.731 Z",
    "M357.113 586.139 C358.317 585.444 361.097 588.006 363.322 591.86 C365.548 595.715 366.376 599.403 365.173 600.098 C363.969 600.793 361.189 598.231 358.964 594.377 C356.738 590.522 355.91 586.834 357.113 586.139 Z",
    "M382.617 586.139 C383.82 585.444 386.6 588.006 388.825 591.86 C391.051 595.715 391.879 599.403 390.676 600.098 C389.472 600.793 386.692 598.231 384.467 594.377 C382.241 590.522 381.413 586.834 382.617 586.139 Z",
    "M406.566 586.139 C407.769 585.444 410.549 588.006 412.775 591.86 C415 595.715 415.828 599.403 414.625 600.098 C413.421 600.793 410.642 598.231 408.416 594.377 C406.191 590.522 405.362 586.834 406.566 586.139 Z"
)
private val STEMS = listOf(378.307f to 604.771f, 403.492f to 604.771f, 365.504f to 597.037f, 391.007f to 597.037f, 414.956f to 597.037f)

// Water's route: in along the left pipe up into the tank; out along the right pipe to the crops.
private const val IN_ROUTE = "M172 612 L172 431.5 C172 415.6 183.7 403.2 195.8 403.2 L238 403.2"
private const val OUT_ROUTE = "M312.7 467.5 L320.8 467.5 C326 467.5 330.2 471.6 330.2 476.9 L330.2 603 C330.2 608.3 334.4 612.4 339.6 612.4 L356.5 612.4"

/** The last water level drawn, so leaving the page and coming back does not reset the picture. */
object TankState { var level = 0.30f }

@Composable
fun TankArt(running: Boolean, modifier: Modifier = Modifier, valveOpen: Boolean = true, valvePct: Float = 1f) {
    val t = rememberInfiniteTransition(label = "tank")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "flow")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "wave")
    val drip by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "drip")
    val sway by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sway")

    // Rises while pumping, drains slowly otherwise.
    val level = remember { Animatable(TankState.level) }
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { level.value }.collect { TankState.level = it } }
    // Filling while the pump runs. With the pump stopped the level only falls if the valve
    // to the farm is open, and the more open it is, the faster.
    val outflow = valveOpen && valvePct > 0.01f
    LaunchedEffect(running, outflow, valvePct) {
        if (running) level.animateTo(0.92f, tween(((0.92f - level.value) * 40_000).toInt().coerceAtLeast(500), easing = LinearEasing))
        else if (outflow) level.animateTo(0.18f, tween(((level.value - 0.18f) * 60_000 / valvePct.coerceAtLeast(0.1f)).toInt().coerceAtLeast(500), easing = LinearEasing))
    }

    val parse = { d: String -> androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath() }
    val body = remember { parse(TANK_BODY) }
    val s3 = remember { STRUCT3.map(parse) }
    val s2 = remember { STRUCT2.map(parse) }
    val lp = remember { LEFT_PIPE.map(parse) }
    val leaves = remember { LEAVES.map(parse) }
    val inRoute = remember { parse(IN_ROUTE) }
    val outRoute = remember { parse(OUT_ROUTE) }

    Canvas(modifier) {
        // The artwork spans x 137..430, y 382..626.
        val k = min(size.width / 300f, size.height / 250f)
        translate((size.width - 300f * k) / 2f, (size.height - 250f * k) / 2f) {
            scale(k, k, pivot = Offset.Zero) {
                translate(-134f, -378f) {
                    val draining = outflow && level.value > 0.2f

                    // Water inside the tank
                    val top = 389f; val bottom = 478.5f
                    val surface = bottom - level.value * (bottom - top)
                    clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(238.5f, top, 297f, bottom, CornerRadius(11f))) }) {
                        val w = Path().apply {
                            moveTo(236f, bottom + 4f)
                            lineTo(236f, surface)
                            var x = 236f
                            while (x <= 300f) {
                                lineTo(x, surface + 1.6f * sin(((x / 14f) + wave * 2f) * PI.toFloat()))
                                x += 2f
                            }
                            lineTo(300f, bottom + 4f)
                            close()
                        }
                        drawPath(w, WaterBlue.copy(alpha = 0.8f))
                    }

                    // The borehole: the small ellipse at the foot of the input pipe is full of water,
                    // and ripples while the pump is drawing from it.
                    drawOval(WaterBlue.copy(alpha = 0.55f), Offset(139f, 605.5f), Size(63f, 18f))
                    if (running) {
                        for (off in listOf(0f, 0.5f)) {
                            val p = (flow + off) % 1f
                            val rx = 6f + 24f * p
                            val ry = 2f + 6.5f * p
                            drawOval(Color.White.copy(alpha = 0.7f * (1f - p)), Offset(170.4f - rx, 614.4f - ry), Size(rx * 2f, ry * 2f), style = Stroke(0.9f))
                        }
                    }

                    // Structure
                    val line = { w: Float -> Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
                    for (y in STRIPES) drawLine(Steel, Offset(237.7f, y), Offset(297.6f, y), 1.5f)
                    drawPath(body, Steel, style = line(3f))
                    s3.forEach { drawPath(it, Steel, style = line(3f)) }
                    s2.forEach { drawPath(it, Steel, style = line(2f)) }
                    lp.forEach { drawPath(it, Steel, style = line(1.2f)) }

                    // Input (left): water climbs the pipe into the tank while the pump runs
                    if (running) {
                        drawPath(
                            inRoute, WaterBlue,
                            style = Stroke(2f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), -flow * 18f))
                        )
                    }

                    // The valve on the output pipe: a wheel on a stem. Green and open, or red and shut.
                    run {
                        val vx = 330.2f; val vy = 520f
                        val col = if (valveOpen) Color(0xFF59F223) else Color(0xFFE5484D)
                        drawLine(Steel, Offset(vx, vy), Offset(vx + 14f, vy), 2f, StrokeCap.Round)
                        drawCircle(Color.Black, 6f, Offset(vx, vy))
                        drawCircle(col, 6f, Offset(vx, vy), style = Stroke(2f))
                        // the handle turns as the valve is opened
                        val ang = if (valveOpen) 0f else 90f
                        rotate(ang, Offset(vx + 14f, vy)) { drawLine(col, Offset(vx + 14f, vy - 7f), Offset(vx + 14f, vy + 7f), 2.4f, StrokeCap.Round) }
                    }

                    // Output (right): water runs along the pipe and spreads sideways over the farm
                    if (draining) {
                        val speed = 0.4f + 1.6f * valvePct
                        drawPath(
                            outRoute, WaterBlue,
                            style = Stroke(2f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), -flow * 18f * speed))
                        )
                        // out of the pipe and along the ground, widening as it goes
                        val reach = 22f + 50f * valvePct
                        val front = 356.5f + reach * ((drip * speed) % 1f)
                        for (k in 0..2) {
                            val x0 = 356.5f + reach * (((drip * speed) + k * 0.33f) % 1f)
                            val p = (x0 - 356.5f) / reach
                            drawLine(WaterBlue.copy(alpha = 0.9f * (1f - p)), Offset(x0, 613.4f), Offset(x0 + 7f, 613.4f), 2.2f + 1.6f * p, StrokeCap.Round)
                        }
                        drawLine(WaterBlue.copy(alpha = 0.35f), Offset(356.5f, 614.8f), Offset(356.5f + reach, 614.8f), 2.4f, StrokeCap.Round)
                    }

                    // Crops: green when watered, a gentle sway
                    val cropColor = if (draining) Color(0xFF59F223) else Color(0xFF59F223).copy(alpha = 0.55f)
                    val angle = 2.2f * sin(sway * 2f * PI.toFloat())
                    drawLine(Steel.copy(alpha = 0.6f), Offset(356f, 614.4f), Offset(430f, 614.4f), 1.2f)
                    rotate(angle, pivot = Offset(390f, 614f)) {
                        leaves.forEach {
                            drawPath(it, cropColor.copy(alpha = cropColor.alpha * 0.35f))
                            drawPath(it, cropColor, style = line(1.1f))
                        }
                        STEMS.forEach { (x, y) -> drawLine(cropColor, Offset(x, y), Offset(x, 614.4f), 1.2f, StrokeCap.Round) }
                    }
                }
            }
        }
    }
}
