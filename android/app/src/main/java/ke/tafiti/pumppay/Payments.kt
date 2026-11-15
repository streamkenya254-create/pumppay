package ke.tafiti.pumppay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
  Real M-Pesa top-ups.

  The app never talks to Safaricom and holds no payment credentials. It asks the
  PumpPay Cloud Functions to prompt the customer's phone, then asks them how it
  went. Everything that matters - the price, the Till, the secret that makes a
  payment count - lives on the server (see firebase-ingest/functions/mpesa.js).

  What the app sends is only WHICH package, never an amount: the server owns the
  price list, so a modified app cannot buy a token for less.
*/

object Payments {
    /** The Cloud Functions project the controllers report to. */
    const val BASE = "https://us-central1-paperless-receipt-ke.cloudfunctions.net"

    class Reply(
        val ok: Boolean,
        val error: String = "",
        val paymentId: String = "",
        val status: String = "",
        val reason: String = "",
        val receipt: String = "",
        val balanceSecs: Long = -1
    )

    private fun call(method: String, path: String, body: JSONObject?): Reply {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            // The server answers errors with a JSON body too; read whichever stream has it.
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            return Reply(
                ok = j.optBoolean("ok", false),
                error = j.optString("error", if (code in 200..299) "" else "Server error ($code)"),
                paymentId = j.optString("paymentId", ""),
                status = j.optString("status", ""),
                reason = j.optString("reason", ""),
                receipt = j.optString("receipt", ""),
                balanceSecs = if (j.has("balanceSecs")) j.optLong("balanceSecs") else -1
            )
        } finally {
            c.disconnect()
        }
    }

    private suspend fun safely(block: () -> Reply): Reply = withContext(Dispatchers.IO) {
        try { block() } catch (e: Exception) { Reply(false, "No connection to the payment server") }
    }

    suspend fun topUp(serial: String, phone: String, kes: Int): Reply = safely {
        call("POST", "/pumppayTopUp", JSONObject().put("serial", serial).put("phone", phone).put("kes", kes))
    }

    suspend fun status(paymentId: String): Reply = safely { call("GET", "/pumppayStatus?id=$paymentId", null) }

    suspend fun balance(serial: String): Reply = safely { call("GET", "/pumppayStatus?serial=$serial", null) }
}
