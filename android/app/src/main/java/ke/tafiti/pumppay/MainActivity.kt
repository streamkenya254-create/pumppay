package ke.tafiti.pumppay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private lateinit var bt: BtClient
    private lateinit var prefs: Prefs
    private lateinit var usage: UsageStore

    private var granted by mutableStateOf(false)

    /** False after the user presses Disconnect, so auto-connect does not undo it. */
    private var autoConnect by mutableStateOf(true)
    private var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())

    private val askPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        if (granted) refreshDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bt = BtClient(lifecycleScope)
        prefs = Prefs(this)
        usage = UsageStore(prefs)
        Session.start(prefs)
        Simulation.enabled = prefs.simulate

        // The permission prompt is NOT asked for here. It is asked once the
        // splash finishes - a system dialog over the intro video on first
        // launch looks like a fault, and the answer is not needed until
        // something actually tries to reach the board.

        setContent {
            // Dark only. The board's own web page is dark, its LCD is dark, and
            // a light theme would be the odd one out on a device usually looked
            // at beside a pump.
            MaterialTheme(colorScheme = darkColorScheme()) {
                // One provider rather than a Typography with the family repeated on
                // every style. Text() reads LocalTextStyle, so this reaches every
                // label in the app - including ones written later that forget to ask.
                CompositionLocalProvider(
                    LocalTextStyle provides TextStyle(fontFamily = AppFont)
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = C.Bg) {
                        App()
                    }
                }
            }
        }
    }

    @Composable
    private fun App() {
        val link by bt.link.collectAsState()
        val realStatus by bt.status.collectAsState()
        val error by bt.error.collectAsState()
        val reply by bt.reply.collectAsState()

        // CONNECTS BY ITSELF. Whenever the app is open, has Bluetooth permission and
        // is not linked to the controller, it looks for the paired PUMPPAY board and
        // connects - at launch, after the board restarts, and after a dropped
        // connection - so nobody has to press Connect.
        //
        // Only a device named PUMPPAY is ever tried, never any other paired device:
        // the user's headphones and watch are paired too, and trying them would
        // just fail noisily. A deliberate Disconnect turns this off until the app
        // is reopened or Connect is pressed, otherwise it would undo the disconnect
        // within seconds.
        LaunchedEffect(granted) {
            while (granted) {
                val state = bt.link.value
                if (autoConnect && (state == Link.Idle || state == Link.Failed) && bt.isBluetoothOn()) {
                    devices.firstOrNull { it.name?.contains("PUMPPAY", true) == true }
                        ?.let { bt.connect(it) }
                }
                kotlinx.coroutines.delay(8000)
            }
        }

        // The simulated controller stands in only while the setting is on and no real controller is
        // connected. A real connection always wins, so it can never hide a genuine reading.
        val simOn = Simulation.enabled && link != Link.Connected
        LaunchedEffect(simOn) {
            Simulation.active = simOn
            usage.refresh()
            while (simOn) {
                Simulation.tick()
                kotlinx.coroutines.delay(1000)
            }
        }
        val status = if (simOn) { Simulation.ticks; Simulation.status() } else realStatus
        val hist by bt.hist.collectAsState()

        // Every history frame the board sends is merged into the phone's own
        // store, which is what lets usage run back further than the board's week.
        LaunchedEffect(hist) { hist?.let { usage.merge(it) } }

        var splashDone by remember { mutableStateOf(false) }

        if (!splashDone) {
            SplashScreen(onDone = {
                splashDone = true
                requestBluetoothPermission()
            })
            return
        }

        // Not signed in yet: the account screen comes first.
        androidx.compose.animation.Crossfade(
            targetState = Session.needsAuth,
            animationSpec = androidx.compose.animation.core.tween(350),
            label = "gate"
        ) { needsAuth ->
        if (needsAuth) { AuthScreen(prefs) } else {

        // The shell draws whether or not permission has been granted and
        // whether or not the board is connected. Only the connect action
        // itself needs the permission, so that is where the message lives -
        // hiding the whole app behind a permission the user may still be
        // deciding about tells them nothing about what they are deciding for.
        Shell(
            s = status,
            link = if (simOn) Link.Connected else link,
            error = if (!granted)
                "Bluetooth permission has not been granted. Allow it in " +
                    "Android Settings > Apps > PumpPay > Permissions."
            else error,
            reply = reply,
            devices = devices,
            bluetoothOn = granted && bt.isBluetoothOn(),
            prefs = prefs,
            usage = usage,
            onConnect = { autoConnect = true; if (granted) bt.connect(it) else requestBluetoothPermission() },
            onDisconnect = { autoConnect = false; bt.disconnect() },
            onKey = { bt.pressKey(it) },
            onSaveWifi = { s, p -> bt.clearReply(); bt.setWifi(s, p) },
            onClearWifi = { bt.clearReply(); bt.clearWifi() },
            onSetPin = { bt.clearReply(); bt.setPin(it) },
            onSetLine = { bt.setLine(it) },
            onSetCloudKey = { bt.clearReply(); bt.setCloudKey(it) },
            onClearCloud = { bt.clearReply(); bt.clearCloud() },
            onCloudStatus = { bt.clearReply(); bt.cloudStatus() },
            onSetBilling = { bt.clearReply(); bt.setBilling(it) },
            onSetPump = { if (Simulation.active) Simulation.manualOff = !it else { bt.clearReply(); bt.setPump(it) } },
            onSync = { bt.syncNow() }
        )
        }
        }
    }

    @Composable
    private fun Message(text: String) {
        Box(
            Modifier
                .fillMaxSize()
                .background(C.Bg)
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = C.Dim, fontSize = 15.sp)
        }
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every resume: the user may have gone to Android settings
        // to pair the board and come straight back, and a list that never
        // refreshes looks like the app is broken.
        if (granted) refreshDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        bt.disconnect()
    }

    private fun refreshDevices() {
        devices = try {
            bt.pairedDevices()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * The permission split at API 31 is why this is not one line. Below 31 the
     * Bluetooth permissions are install-time and already held; from 31
     * BLUETOOTH_CONNECT must be requested at runtime.
     */
    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            askPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            granted = true
            refreshDevices()
        }
    }
}
