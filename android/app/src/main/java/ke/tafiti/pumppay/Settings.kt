package ke.tafiti.pumppay

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings: one list that leads to every configuration page. */
@Composable
fun SettingsPage(onGo: (Page) -> Unit, prefs: Prefs) {
    val rows = listOf(
        Triple(Page.Network, "Network", "WiFi, Bluetooth and the controller's connection"),
        Triple(Page.Device, "Device settings", "Controller options, billing switch, cloud"),
        Triple(Page.Support, "Help & support", "Search the built-in help")
    )
    // Simulation: try every screen with a simulated controller, no pump needed.
    var simulate by remember { mutableStateOf(prefs.simulate) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.PlayArrow, null, tint = C.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Simulation", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (simulate) "Running a simulated controller. It steps aside when a real one connects."
                else "Start a simulated controller to try every screen.",
                color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp
            )
        }
        androidx.compose.material3.Switch(
            checked = simulate,
            onCheckedChange = { simulate = it; prefs.simulate = it; Simulation.enabled = it },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = C.Bg, checkedTrackColor = C.Ok,
                uncheckedThumbColor = C.Dim, uncheckedTrackColor = C.Field
            )
        )
    }
    rows.forEach { (page, title, sub) ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onGo(page) }
                .padding(vertical = 14.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(iconFor(page), null, tint = C.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(sub, color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Dim)
        }
    }
}

/** Profile: who the installation belongs to. Kept on this phone. */
@Composable
fun ProfilePage(prefs: Prefs) {
    var name by remember { mutableStateOf(prefs.installationName) }
    var owner by remember { mutableStateOf(prefs.ownerName) }
    var phone by remember { mutableStateOf(prefs.mpesaPhone) }
    var saved by remember { mutableStateOf(false) }

    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(54.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(owner.ifBlank { "Your name" }, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(name.ifBlank { "Unnamed installation" }, color = C.Dim, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("PROFILE")
        Spacer(Modifier.height(12.dp))
        Field(owner, { owner = it; saved = false }, "Your name")
        Spacer(Modifier.height(8.dp))
        Field(name, { name = it; saved = false }, "Site name")
        Spacer(Modifier.height(8.dp))
        Field(phone, { phone = it; saved = false }, "M-Pesa phone, e.g. 0712 345 678")
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (saved) "Saved" else "Save") {
            prefs.ownerName = owner.trim()
            prefs.installationName = name.trim()
            prefs.mpesaPhone = phone.trim()
            saved = true
        }
        Spacer(Modifier.height(8.dp))
        Text("Stored on this phone only.", color = C.Dim, fontSize = 11.sp)
    }

    // The account
    Spacer(Modifier.height(12.dp))
    CardBox {
        SectionLabel("ACCOUNT")
        Spacer(Modifier.height(8.dp))
        if (prefs.authRefresh.isNotBlank()) {
            Text(prefs.authEmail, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Signed in", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            WideButton("Sign out", true, C.Dim) { Session.signOut(prefs) }
        }
    }
}
