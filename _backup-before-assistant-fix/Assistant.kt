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

    suspend fun ask(prefs: Prefs, question: String, context: JSONObject, history: List<Pair<String, String>> = emptyList()): Reply = withContext(Dispatchers.IO) {
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
        if (prefs.crop.isBlank() || prefs.plantedOn.isBlank() || prefs.areaAcres <= 0.0 || prefs.cropLat == 0.0 || prefs.flowLpm <= 0f) return null
        return JSONObject()
            .put("lat", prefs.cropLat).put("lon", prefs.cropLon).put("crop", prefs.crop)
            .put("plantedOn", prefs.plantedOn).put("areaM2", prefs.areaAcres * 4046.86)
            .put("flowLpm", prefs.flowLpm.toDouble()).put("efficiency", prefs.cropEff.toDouble()).put("soil", prefs.soil)
            .put("tankLitres", prefs.tankLitres.toDouble())
            .also { f -> if (prefs.pumpWatts > 0f && prefs.panelCount > 0 && prefs.panelWatts > 0f) { f.put("pumpWatts", prefs.pumpWatts.toDouble()); f.put("panelWp", (prefs.panelCount * prefs.panelWatts).toDouble()) } }
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
            if (s.tokenSecs >= 0) o.put("tokenSecsLeft", s.tokenSecs)
            o.put("pumpLocked", s.relayLocked)
            o.put("pumpSwitchedOff", s.manualOff)
        }
        if (prefs.flowLpm > 0f) o.put("pumpFlowLitresPerMin", prefs.flowLpm)
        if (prefs.tankLitres > 0f) o.put("tankLitres", prefs.tankLitres)
        if (prefs.pumpModel.isNotBlank()) o.put("pumpModel", prefs.pumpModel)
        if (prefs.pumpWatts > 0f) o.put("pumpRatedWatts", prefs.pumpWatts)
        if (prefs.pumpHeadM > 0f) o.put("pumpHeadMetres", prefs.pumpHeadM)
        if (prefs.panelCount > 0 && prefs.panelWatts > 0f) o.put("solarArrayWatts", prefs.panelCount * prefs.panelWatts)
        o.put("systemVolts", prefs.systemVolts)
        if (prefs.controllerModel.isNotBlank()) o.put("controllerModel", prefs.controllerModel)
        if (prefs.crop.isNotBlank()) o.put("crop", prefs.crop)
        val missing = ArrayList<String>()
        if (prefs.crop.isBlank()) missing.add("crop")
        if (prefs.plantedOn.isBlank()) missing.add("plantedOn")
        if (prefs.areaAcres <= 0.0) missing.add("fieldSizeAcres")
        if (prefs.cropLat == 0.0) missing.add("location")
        if (prefs.flowLpm <= 0f) missing.add("pumpFlowLitresPerMin")
        o.put("farmDetailsMissing", org.json.JSONArray(missing))
        return o
    }
}
