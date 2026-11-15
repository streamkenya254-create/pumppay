package ke.tafiti.pumppay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/*
  The simulated controller.

  Switched on from Settings, it stands in for the controller while none is connected, and the
  whole app then behaves as it does with a real one: live readings, a pump that runs and spends
  its token, a relay that cuts the pump off when the token is gone, history for the charts, and
  a Top up that adds token. It exists so every screen can be tried with no pump to hand, so it
  never touches money: a top-up here only adds simulated token, and nothing is sent anywhere.

  A real controller connecting always wins: the moment one is connected the simulation steps aside.
*/
object Simulation {
    /** The setting, saved in Prefs. */
    var enabled by mutableStateOf(false)

    /** The simulated controller is the one on screen right now (setting on, no real controller). */
    var active by mutableStateOf(false)

    /** "Pump off" given to the simulated controller. */
    var manualOff by mutableStateOf(false)

    /** Bumped every second so screens reading the readings redraw. */
    var ticks by mutableStateOf(0L)
        private set

    private var credited = 2 * 3600L      // pumping time bought, seconds
    private var spent = 0L
    private var runCur = 0L
    private var runToday = 4 * 3600L + 10 * 60   // a morning's pumping already behind it
    private var runTotal = 140 * 3600L
    private var starts = 143L
    private var wasPumping = false
    private var startedAt = System.currentTimeMillis()

    val balanceSecs: Long get() = (credited - spent).coerceAtLeast(0)
    val locked: Boolean get() = balanceSecs <= 0

    private fun pumping() = !locked && !manualOff

    /** Called once a second while the simulation is on screen. */
    fun tick() {
        if (pumping()) {
            runCur++; runToday++; runTotal++; spent++
            if (!wasPumping) starts++
            wasPumping = true
        } else {
            runCur = 0; wasPumping = false
        }
        ticks++
    }

    fun topUp(kes: Int) { credited += kes.toLong() * 3600 / RATE_KES_PER_HOUR }

    fun status(): Status {
        val now = System.currentTimeMillis()
        val t = now / 1000.0
        val run = pumping()
        // A bright site: output drifts a little as clouds pass.
        val sun = 0.78 + 0.18 * sin(t / 47.0) + 0.05 * sin(t / 11.0)
        val volts = 11.9 + 0.12 * sin(t / 9.0)
        val amps = if (run) 1.1 + 1.0 * sun.coerceIn(0.0, 1.0) + 0.05 * sin(t / 3.0) else 0.0
        val va = volts * amps
        return Status(
            state = if (run) "PUMPING" else "IDLE",
            line = 1,
            deviceId = "",
            billingOn = true,
            tokenSecs = balanceSecs,
            relayLocked = locked || manualOff,
            manualOff = manualOff,
            volts = volts, amps = amps, va = va, vOk = true, aOk = true,
            runCurSecs = runCur,
            runTodaySecs = runToday,
            runTotalSecs = runTotal,
            starts = starts,
            epoch = now / 1000,
            tempC = 29.5 + 1.5 * sin(t / 120.0),
            adcOk = true,
            upSeconds = (now - startedAt) / 1000 + 262_000,
            lcd = listOf(if (run) "PUMPING" else if (locked) "TOKEN USED UP" else "PUMP OFF", String.format("%.1f V  %.2f A", volts, amps))
        )
    }

    /**
     * A believable solar pumping day for the history charts: the pump runs while the sun is up,
     * scaled by a per-day "cloud" factor so some days are strong and some weak. A pure function
     * of the hour, so the same hour always gives the same answer and the charts do not shuffle.
     * Returns (minutes run, apparent energy in VAh) for that UTC hour.
     */
    fun hour(hour: Long): Pair<Int, Int> {
        val nowHour = System.currentTimeMillis() / 3_600_000
        if (hour > nowHour) return 0 to 0
        val zone = java.time.ZoneId.systemDefault()
        val t = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(hour * 3600), zone)
        val h = t.hour
        if (h < 6 || h >= 18) return 0 to 0
        val day = t.toLocalDate().toEpochDay()
        val roll = abs(sin(day * 12.9898) * 43758.5453) % 1.0
        val cloud = 0.30 + 0.70 * roll
        val sun = sin(PI * (h - 6 + 0.5) / 12.0)
        val strength = sun * cloud
        if (strength < 0.20) return 0 to 0
        val mins = (strength * 60).roundToInt().coerceIn(1, 60)
        return mins to (mins * 15 * (0.85 + 0.3 * strength)).roundToInt()
    }
}
