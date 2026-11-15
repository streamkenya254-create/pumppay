package ke.tafiti.pumppay

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
  Billing, laid out as: a balance card with one clear action, a status card with a
  bar, then a plain list. One screen for both the real controller (M-Pesa) and the
  real controller.

  Nothing here talks about which supply line the pump is on. To a customer there
  are only two states: the pump is on, or it is locked until they top up.
*/

private val Amber = Color(0xFFF5B301)
private val BigButton = Color(0xFFEDEDED)

@Composable
private fun ListRow(icon: ImageVector, title: String, sub: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = C.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotEmpty()) Text(sub, color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun BillingPage(s: Status, realLive: Boolean, prefs: Prefs, onSync: () -> Unit) {
    val serial = s.deviceId
    val real = realLive && serial.isNotEmpty() && !Simulation.active
    val sim = Simulation.active

    if (!real && !sim) {
        CardBox {
            Text("Connect to see your balance", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (realLive) "This controller has not reported its name yet. Update its firmware, then reconnect."
                else "Connect to your PumpPay controller to see its token balance and top up with M-Pesa.",
                color = C.Dim, fontSize = 14.sp
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    var serverBalance by remember { mutableStateOf(-1L) }
    var phone by remember { mutableStateOf(prefs.mpesaPhone) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var good by remember { mutableStateOf(false) }
    var topping by remember { mutableStateOf(false) }

    suspend fun refresh() {
        if (!real) return
        val r = Payments.balance(serial)
        if (r.ok) serverBalance = r.balanceSecs
    }
    LaunchedEffect(serial, real) { refresh() }

    val balance = if (sim) Simulation.balanceSecs else serverBalance
    val unknown = balance < 0
    val locked = balance == 0L || s.tokenLocked
    val accent = if (locked) Amber else C.Pump

    // ---- Balance --------------------------------------------------------------
    CardBox {
        SectionLabel("TOKEN BALANCE")
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                unknown -> "—"
                balance <= 0L -> "Used up"
                else -> secText(balance)
            },
            color = if (locked) Amber else C.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold
        )
        if (balance > 0) {
            Text("≈ KES ${String.format("%,d", balance * RATE_KES_PER_HOUR / 3600)} of pumping left", color = C.Dim, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(if (topping) "Hide top up" else "Top up") { topping = !topping }

        if (topping) {
            Spacer(Modifier.height(16.dp))
            if (!sim) {
                Field(phone, { phone = it }, "M-Pesa phone, e.g. 0712 345 678", enabled = !busy)
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kes in listOf(20, 50, 100)) {
                    Box(Modifier.weight(1f)) {
                        WideButton("KES $kes", !busy && (sim || phone.isNotBlank()), C.Pump) {
                            if (sim) { Simulation.topUp(kes); return@WideButton }
                            prefs.mpesaPhone = phone.trim()
                            busy = true; good = false
                            message = "Sending the M-Pesa prompt..."
                            scope.launch {
                                val start = Payments.topUp(serial, phone.trim(), kes)
                                if (!start.ok) {
                                    message = start.error.ifBlank { "Could not start the payment" }
                                    busy = false
                                    return@launch
                                }
                                message = "Check your phone and enter your M-Pesa PIN."
                                var settled = false
                                for (i in 1..40) {
                                    delay(3000)
                                    val st = Payments.status(start.paymentId)
                                    when (st.status) {
                                        "completed" -> {
                                            good = true
                                            message = "Payment received. Receipt ${st.receipt}."
                                            if (st.balanceSecs >= 0) serverBalance = st.balanceSecs
                                            onSync()
                                            settled = true
                                        }
                                        "failed" -> { message = st.reason.ifBlank { "The payment was cancelled." }; settled = true }
                                        "mismatch", "duplicate" -> {
                                            message = "The payment could not be applied. Contact support with your M-Pesa message."
                                            settled = true
                                        }
                                    }
                                    if (settled) break
                                }
                                if (!settled) message = "No answer yet. If money was taken, the token will appear shortly."
                                refresh()
                                busy = false
                            }
                        }
                    }
                }
            }
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(message, color = if (good) C.Pump else C.Dim, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "KES $RATE_KES_PER_HOUR buys one hour of pumping.",
                color = C.Dim, fontSize = 12.sp
            )
        }
    }

    // ---- Pump status ----------------------------------------------------------
    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("PUMP")
        Spacer(Modifier.height(8.dp))
        Text(
            if (locked) "Locked" else "Ready",
            color = accent, fontSize = 30.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(C.Line, RoundedCornerShape(3.dp))) {
            val frac = (if (balance < 0) 0f else balance / 3600f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(accent, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                locked -> "Your token is used up, so the pump is switched off. Top up to start it again."
                real && s.tokenSecs >= 0 && balance > 0 && s.billingOn -> "The controller shows ${secText(s.tokenSecs)} left."
                else -> "The pump runs while you have token."
            },
            color = C.Dim, fontSize = 14.sp, lineHeight = 19.sp
        )
        if (real) {
            // The server's balance is the truth; the controller keeps its own copy and
            // refreshes it when it uploads. If they disagree for a moment, say so.
            val behind = balance > 0 && s.tokenLocked
            if (behind) {
                Spacer(Modifier.height(8.dp))
                Text("You have paid, but the controller has not picked it up yet.", color = Amber, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Sync now") {
                onSync()
                message = "Syncing. The app drops for a few seconds, then reconnects."
                good = false
                topping = true
                scope.launch { delay(6000); refresh() }
            }
        }
    }

    // ---- The list -------------------------------------------------------------
    Spacer(Modifier.height(8.dp))
    ListRow(
        Icons.Outlined.Payments, "Payment method",
        if (phone.isBlank()) "M-Pesa · add your number under Top up" else "M-Pesa · $phone"
    ) { topping = true }
    ListRow(Icons.Outlined.Bolt, "Rate", "KES $RATE_KES_PER_HOUR per hour of pumping")
    if (real) {
        ListRow(
            Icons.Outlined.Sync, "Billing on the controller",
            if (s.billingOn) "Active" else "Inactive · turn it on under Device settings"
        )
    }
}
