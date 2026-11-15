package ke.tafiti.pumppay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The "Ask anything" assistant. The app never holds a model key: it sends the question and
 * the live readings to the PumpPay server (pumppayAsk), which holds the keys and checks that
 * the person is signed in.
 */
object Assistant {
    /** A button the assistant offers under its answer. */
    class Action(val type: String, val page: String = "", val farm: JSONObject? = null, val litres: Double = 0.0, val lpm: Double = 0.0)

    class Reply(val ok: Boolean, val text: String, val actions: List<Action> = emptyList())

    suspend fun ask(prefs: Prefs, question: String, context: JSONObject, history: List<Pair<String, String>> = emptyList(), voice: Boolean = false, otherHearings: List<String> = emptyList()): Reply = withContext(Dispatchers.IO) {
        val token = Accounts.idToken(prefs) ?: return@withContext Reply(false, "Sign in to ask questions.")
        try {
            val c = URL(Payments.BASE + "/pumppayAsk").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 40_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer $token")
                val h = org.json.JSONArray(); history.forEach { h.put(JSONObject().put("role", it.first).put("content", it.second)) }
                val body = JSONObject().put("question", question).put("context", context).put("history", h)
                if (voice) { body.put("voice", true); if (otherHearings.isNotEmpty()) body.put("otherHearings", org.json.JSONArray(otherHearings)) }
                farm(prefs)?.let { body.put("farm", it) }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (code in 200..299 && j.optString("answer").isNotBlank()) {
                    val arr = j.optJSONArray("actions")
                    val acts = List(arr?.length() ?: 0) { i ->
                        val o = arr!!.getJSONObject(i)
                        Action(o.optString("type"), o.optString("page"), o.optJSONObject("farm"), o.optDouble("litres", 0.0), o.optDouble("lpm", 0.0))
                    }
                    Reply(true, j.optString("answer"), acts)
                }
                else Reply(false, j.optString("error", "The assistant is not available right now."))
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Reply(false, "No connection. Check your internet and try again.")
        }
    }

    /** The farm profile from the Plant page, when it is filled in; the server works out the plan from it. */
    fun farm(prefs: Prefs): JSONObject? {
        if (prefs.crop.isBlank() || prefs.plantedOn.isBlank() || prefs.areaAcres <= 0.0 || prefs.cropLat == 0.0) return null   // flow is optional: without it the plan gives litres but not minutes
        return JSONObject()
            .put("lat", prefs.cropLat).put("lon", prefs.cropLon).put("crop", prefs.crop)
            .put("plantedOn", prefs.plantedOn).put("areaM2", prefs.areaAcres * 4046.86)
            .put("efficiency", prefs.cropEff.toDouble()).put("soil", prefs.soil)
            .put("tankLitres", prefs.tankLitres.toDouble())
            .also { f -> if (prefs.flowLpm > 0f) f.put("flowLpm", prefs.flowLpm.toDouble()) }
            .also { f -> if (prefs.pumpWatts > 0f && prefs.panelCount > 0 && prefs.panelWatts > 0f) { f.put("pumpWatts", prefs.pumpWatts.toDouble()); f.put("panelWp", (prefs.panelCount * prefs.panelWatts).toDouble()) } }
    }

    /** Seconds as "2 days 3 h 23 min", the way a person says it. */
    private fun span(secs: Long): String {
        val d = secs / 86400; val h = (secs % 86400) / 3600; val m = (secs % 3600) / 60
        return listOfNotNull(if (d > 0) "$d days" else null, if (h > 0) "$h h" else null, if (d == 0L) "$m min" else null).joinToString(" ")
    }

    /** What the assistant is told about the system right now. */
    fun context(s: Status, live: Boolean, prefs: Prefs): JSONObject {
        val o = JSONObject()
        o.put("connected", live)
        if (Simulation.active) o.put("simulated", true)
        if (live) {
            o.put("pumpState", s.state)
            if (s.vOk) o.put("pumpVolts", s.volts)
            if (s.aOk) o.put("pumpAmps", s.amps)
            if (s.vOk && s.aOk) o.put("pumpWatts", s.va)
            if (!s.tempC.isNaN()) o.put("controllerTempC", s.tempC)
            o.put("runTodaySecs", s.runTodaySecs)
            o.put("billingOn", s.billingOn)
            if (s.billingOn && s.tokenSecs >= 0) o.put("tokenTimeLeft", span(s.tokenSecs))
            o.put("pumpStatusPlain", when {
                s.manualOff -> "switched off from the app; switch it on in Water usage"
                s.relayLocked -> "locked: the paid time ran out; top up in Billing"
                s.pumping -> "running now"
                else -> "idle and free to run (not locked)"
            })
        } else {
            o.put("pumpStatusPlain", "unknown: the phone is not connected to the controller")
        }
        // Everything the owner has stored, by name, so the assistant never asks for what it already has.
        val saved = JSONObject()
        if (prefs.flowLpm > 0f) saved.put("pumpFlowLitresPerMin", prefs.flowLpm)
        if (prefs.tankLitres > 0f) saved.put("tankLitres", prefs.tankLitres)
        if (prefs.pumpModel.isNotBlank()) saved.put("pumpModel", prefs.pumpModel)
        if (prefs.pumpWatts > 0f) saved.put("pumpRatedWatts", prefs.pumpWatts)
        if (prefs.pumpHeadM > 0f) saved.put("pumpHeadMetres", prefs.pumpHeadM)
        if (prefs.panelCount > 0 && prefs.panelWatts > 0f) {
            saved.put("solarPanels", "" + prefs.panelCount + " x " + prefs.panelWatts + " W")
            saved.put("solarArrayWatts", prefs.panelCount * prefs.panelWatts)
        }
        saved.put("systemVolts", prefs.systemVolts)
        if (prefs.controllerModel.isNotBlank()) saved.put("controllerModel", prefs.controllerModel)
        if (prefs.crop.isNotBlank()) saved.put("crop", prefs.crop)
        if (prefs.plantedOn.isNotBlank()) saved.put("plantedOn", prefs.plantedOn)
        if (prefs.areaAcres > 0.0) saved.put("fieldSizeAcres", prefs.areaAcres)
        if (prefs.crop.isNotBlank()) {
            saved.put("soil", prefs.soil)
            saved.put("wateringMethod", if (prefs.cropEff >= 0.85f) "drip" else if (prefs.cropEff >= 0.7f) "sprinkler" else "flood")
        }
        if (prefs.cropLat != 0.0) saved.put("farmLocation", prefs.cropPlaceName.ifBlank { "" + prefs.cropLat + ", " + prefs.cropLon })
        if (prefs.installationName.isNotBlank()) saved.put("siteName", prefs.installationName)
        o.put("savedSettings", saved)
        val missing = ArrayList<String>()
        if (prefs.crop.isBlank()) missing.add("crop")
        if (prefs.plantedOn.isBlank()) missing.add("plantedOn")
        if (prefs.areaAcres <= 0.0) missing.add("fieldSizeAcres")
        if (prefs.cropLat == 0.0) missing.add("location")
        if (prefs.flowLpm <= 0f) missing.add("pumpFlowLitresPerMin")
        if (prefs.tankLitres <= 0f) missing.add("tankLitres")
        o.put("notSavedYet", org.json.JSONArray(missing))
        return o
    }
}
