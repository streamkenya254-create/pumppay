package ke.tafiti.pumppay

import android.content.Context

/**
 * App-local preferences - things about this phone's view of the board, not
 * about the board itself.
 *
 * The distinction matters and is worth keeping. WiFi credentials and the
 * control PIN live in the board's own flash, because they belong to the board
 * and must survive being opened by a different phone. The site name and the
 * pump's flow rate live here, because they are labels and estimates the owner
 * puts on a site and nothing on the board needs to know them.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("pumppay", Context.MODE_PRIVATE)

    var installationName: String
        get() = sp.getString("installation", "") ?: ""
        set(v) = sp.edit().putString("installation", v).apply()

    var ownerName: String
        get() = sp.getString("owner", "") ?: ""
        set(v) = sp.edit().putString("owner", v).apply()

    /** MAC of the last board connected to, so the app can offer it first. */
    var lastDevice: String
        get() = sp.getString("lastDevice", "") ?: ""
        set(v) = sp.edit().putString("lastDevice", v).apply()

    /**
     * How much water the pump delivers, litres per minute.
     *
     * The board has no flow meter - it knows how long the pump ran, not how
     * much it moved. Multiplying run time by this rate gives an ESTIMATE of the
     * water used, and it is only as good as the number typed here. 0 means "not
     * set", and the app then shows run hours only rather than inventing litres.
     */
    var crop: String
        get() = sp.getString("crop", "") ?: ""
        set(v) = sp.edit().putString("crop", v).apply()
    var soil: String
        get() = sp.getString("soil", "loam") ?: "loam"
        set(v) = sp.edit().putString("soil", v).apply()
    var plantedOn: String
        get() = sp.getString("plantedOn", "") ?: ""
        set(v) = sp.edit().putString("plantedOn", v).apply()
    var lastSerial: String
        get() = sp.getString("lastSerial", "") ?: ""
        set(v) = sp.edit().putString("lastSerial", v).apply()
    var areaAcres: Double
        get() = sp.getString("areaAcres", "0")!!.toDouble()
        set(v) = sp.edit().putString("areaAcres", v.toString()).apply()
    var cropEff: Float
        get() = sp.getFloat("cropEff", 0.9f)
        set(v) = sp.edit().putFloat("cropEff", v).apply()
    var cropLat: Double
        get() = sp.getString("cropLat", "0")!!.toDouble()
        set(v) = sp.edit().putString("cropLat", v.toString()).apply()
    var cropLon: Double
        get() = sp.getString("cropLon", "0")!!.toDouble()
        set(v) = sp.edit().putString("cropLon", v.toString()).apply()

    /** Size of the storage tank, litres. 0 = not set. */
    var tankLitres: Float
        get() = sp.getFloat("tankLitres", 0f)
        set(v) = sp.edit().putFloat("tankLitres", v).apply()

    /** The valve that releases tank water to the farm. App setting - see the Water usage page. */
    var valveOpen: Boolean
        get() = sp.getBoolean("valveOpen", true)
        set(v) = sp.edit().putBoolean("valveOpen", v).apply()
    /** How far it is open, 0..1: sets the speed at which water leaves for the farm. */
    var valvePct: Float
        get() = sp.getFloat("valvePct", 0.6f)
        set(v) = sp.edit().putFloat("valvePct", v.coerceIn(0f, 1f)).apply()

    var authEmail: String
        get() = sp.getString("authEmail", "") ?: ""
        set(v) = sp.edit().putString("authEmail", v).apply()
    var authName: String
        get() = sp.getString("authName", "") ?: ""
        set(v) = sp.edit().putString("authName", v).apply()
    var authRefresh: String
        get() = sp.getString("authRefresh", "") ?: ""
        set(v) = sp.edit().putString("authRefresh", v).apply()
    var authUid: String
        get() = sp.getString("authUid", "") ?: ""
        set(v) = sp.edit().putString("authUid", v).apply()
    var authSkipped: Boolean
        get() = sp.getBoolean("authSkipped", false)
        set(v) = sp.edit().putBoolean("authSkipped", v).apply()

    /** Read every assistant answer aloud (answers to spoken questions are always read). */
    var readAloud: Boolean
        get() = sp.getBoolean("readAloud", false)
        set(v) = sp.edit().putBoolean("readAloud", v).apply()

    /** Run the simulated controller while no real one is connected (Settings). */
    var simulate: Boolean
        get() = sp.getBoolean("simulate", false)
        set(v) = sp.edit().putBoolean("simulate", v).apply()

    var pumpModel: String
        get() = sp.getString("pumpModel", "") ?: ""
        set(v) = sp.edit().putString("pumpModel", v).apply()
    var controllerModel: String
        get() = sp.getString("controllerModel", "") ?: ""
        set(v) = sp.edit().putString("controllerModel", v).apply()
    var pumpWatts: Float
        get() = sp.getFloat("pumpWatts", 0f)
        set(v) = sp.edit().putFloat("pumpWatts", v).apply()
    var pumpHeadM: Float
        get() = sp.getFloat("pumpHeadM", 0f)
        set(v) = sp.edit().putFloat("pumpHeadM", v).apply()
    var panelWatts: Float
        get() = sp.getFloat("panelWatts", 0f)
        set(v) = sp.edit().putFloat("panelWatts", v).apply()
    var panelCount: Int
        get() = sp.getInt("panelCount", 0)
        set(v) = sp.edit().putInt("panelCount", v).apply()

    /** A readable name for the farm location ("Kisumu", or the place the phone reported). */
    var cropPlaceName: String
        get() = sp.getString("cropPlaceName", "") ?: ""
        set(v) = sp.edit().putString("cropPlaceName", v).apply()

    var flowLpm: Float
        get() = sp.getFloat("flowLpm", 0f)
        set(v) = sp.edit().putFloat("flowLpm", v).apply()

    /**
     * The nominal voltage of the system feeding the controller, volts. Shown on
     * the controller page as "nominal" - the board does not measure its input.
     */
    var systemVolts: Float
        get() = sp.getFloat("systemVolts", 12f)
        set(v) = sp.edit().putFloat("systemVolts", v).apply()

    /** The M-Pesa number last used for a top-up, so it is not retyped each time. */
    var mpesaPhone: String
        get() = sp.getString("mpesaPhone", "") ?: ""
        set(v) = sp.edit().putString("mpesaPhone", v).apply()

    /** Serialised usage history; see [UsageStore]. */
    var usageHistory: String
        get() = sp.getString("usageHistory", "") ?: ""
        set(v) = sp.edit().putString("usageHistory", v).apply()
}
