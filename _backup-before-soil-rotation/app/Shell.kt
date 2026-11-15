package ke.tafiti.pumppay

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Receipt
import android.bluetooth.BluetoothDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Water
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The pages reachable from the drawer.
 *
 * Every one of them renders whether or not the board is connected. That is the
 * central decision of this screen: being disconnected is a STATE shown inside
 * the app, not a wall placed in front of it. An app that hides its own
 * navigation until a radio link succeeds cannot tell you what it is for, cannot
 * show you what it last knew, and cannot be explored before you own the
 * hardware.
 */
enum class Page(val title: String) {
    Dashboard("Dashboard"),
    Profile("Profile"),
    Chat("Assistant"),
    Settings("Settings"),
    Plant("Plant"),
    Usage("Water usage"),
    Solar("Solar array"),
    Controller("Controller"),
    PumpDetail("Pump"),
    Billing("Billing"),
    Diagnostics("Diagnostics"),
    Panel("Panel"),
    Network("Network"),
    Device("Device settings"),
    Shop("Shop"),
    About("About us"),
    Support("Help & support")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Shell(
    s: Status,
    link: Link,
    error: String?,
    reply: String?,
    devices: List<BluetoothDevice>,
    bluetoothOn: Boolean,
    prefs: Prefs,
    usage: UsageStore,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onKey: (Int) -> Unit,
    onSaveWifi: (String, String) -> Unit,
    onClearWifi: () -> Unit,
    onSetPin: (String) -> Unit,
    onSetLine: (Int) -> Unit,
    onSetCloudKey: (String) -> Unit,
    onClearCloud: () -> Unit,
    onCloudStatus: () -> Unit,
    onSetBilling: (Boolean) -> Unit,
    onSetPump: (Boolean) -> Unit,
    onSync: () -> Unit
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.Dashboard) }

    // A back stack, because the diagram turned the dashboard into a hub: every
    // node taps through to a page, and without somewhere to go back TO, the
    // only way out was the drawer and the system back button quit the app.
    val backStack = remember { mutableStateListOf<Page>() }

    // Drawer items are top-level destinations, so choosing one resets the
    // stack rather than piling up. Taps from inside a page push, so back
    // retraces the route actually taken.
    fun navigate(p: Page, fromDrawer: Boolean) {
        if (p == page) return
        if (fromDrawer) {
            backStack.clear()
            if (p != Page.Dashboard) backStack.add(Page.Dashboard)
        } else {
            backStack.add(page)
        }
        page = p
    }

    fun goBack() {
        if (backStack.isNotEmpty()) page = backStack.removeAt(backStack.lastIndex)
    }

    // System back, in the order a phone user expects: close the drawer first,
    // then retrace pages, and only leave the app once there is nowhere left to
    // go. Disabled at the root so Android handles the exit itself rather than
    // this swallowing it.
    BackHandler(enabled = drawer.isOpen || backStack.isNotEmpty()) {
        if (drawer.isOpen) scope.launch { drawer.close() } else goBack()
    }

    val connected = link == Link.Connected

    // What was typed in the dashboard's Ask box. Asking opens Help & support with it
    // already in the search field.
    var helpQuery by remember { mutableStateOf("") }
    var connectTick by remember { mutableStateOf(0) }

    // The web pages host a WebView, which wants every horizontal gesture for
    // its own panning and its own links. The drawer edge-swipe sits on top of
    // exactly that area, so on those pages it is turned off and the hamburger
    // becomes the only way in - deliberate, because a menu that hijacks a
    // scroll is worse than one that needs a tap.
    //
    // Still enabled while the drawer is OPEN, so it can be swiped shut rather
    // than only dismissed by tapping the scrim.
    val webPage = page == Page.Shop || page == Page.About

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !webPage || drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color.Black) {
                DrawerHeader(prefs, link, s)

                // The home screen is just the picture and the search box; everything else is here.
                listOf(Page.Profile, Page.Billing, Page.Usage, Page.Plant, Page.Diagnostics, Page.Shop, Page.About, Page.Settings).forEach { p ->
                    DrawerRow(
                        page = p,
                        selected = p == page
                    ) {
                        navigate(p, fromDrawer = true)
                        scope.launch { drawer.close() }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            containerColor = C.Bg,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = C.Bg,
                        titleContentColor = C.Text,
                        navigationIconContentColor = C.Text
                    ),
                    // Hamburger at the root, back arrow anywhere you can
                    // return from. One slot, two meanings, decided by whether
                    // there is actually somewhere to go back to - a back arrow
                    // that does nothing is worse than no back arrow.
                    navigationIcon = {
                        if (backStack.isEmpty()) {
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        } else {
                            IconButton(onClick = { goBack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    },
                    // One line, not two. A stacked title crams a 64dp bar and
                    // leaves the name touching both the hamburger above it and
                    // the status beneath. The status lives in the actions slot,
                    // which is where the bar already has room.
                    //
                    // At the root the title is the installation; inside a page
                    // it is the page, because that is what you need to know
                    // once you have navigated somewhere.
                    title = {
                        Text(
                            if (page == Page.Dashboard)
                                prefs.installationName.ifBlank { "PumpPay" }
                            else page.title,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        // With no real controller connected, every page offers Connect here.
                        if (link != Link.Connected) {
                            ConnectPill(link == Link.Connecting, Modifier.padding(end = 14.dp)) { connectTick++ }
                        } else {
                            StatusPill(link, Modifier.padding(end = 14.dp))
                        }
                    }
                )
            }
        ) { pad ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(C.Bg)
                    .padding(pad)
            ) {
                PageContent(
                    page = page,
                    s = s,
                    link = link,
                    error = error,
                    reply = reply,
                    devices = devices,
                    bluetoothOn = bluetoothOn,
                    prefs = prefs,
                    usage = usage,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onKey = onKey,
                    onSaveWifi = onSaveWifi,
                    onClearWifi = onClearWifi,
                    onSetPin = onSetPin,
                    onSetLine = onSetLine,
                    onSetCloudKey = onSetCloudKey,
                    onClearCloud = onClearCloud,
                    onCloudStatus = onCloudStatus,
                    onSetBilling = onSetBilling,
                    onSetPump = onSetPump,
                    onSync = onSync,
                    helpQuery = helpQuery,
                    onAsk = { helpQuery = it; navigate(Page.Support, fromDrawer = false) },
                    onGo = { navigate(it, fromDrawer = false) },
                    connectTick = connectTick
                )
            }
        }
    }
}

/**
 * One line of the menu.
 *
 * Written by hand rather than using NavigationDrawerItem, which draws every
 * entry as a rounded pill and fills the selected one - ten stacked tiles in a
 * menu, on a black app that has no tiles anywhere else. Here the row is just a
 * row: the current page is marked by a short accent bar at the edge and by its
 * own colour, which is enough to find at a glance and adds no boxes.
 */
@Composable
private fun DrawerRow(page: Page, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (page) {
                Page.Billing -> Icons.Outlined.Receipt
                else -> iconFor(page)
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(20.dp))
        Text(
            page.title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (selected) C.Ok else Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun DrawerHeader(prefs: Prefs, link: Link, s: Status) {
    // The account: the owner's name in capitals, and the controller's serial beneath.
    val serial = s.deviceId.ifBlank { prefs.lastSerial }
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 12.dp)) {
        Text(
            prefs.ownerName.ifBlank { prefs.installationName.ifBlank { "PumpPay" } }.uppercase(),
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            serial.ifBlank { "Solar pump monitor" },
            color = Color(0xFFA9A9A9),
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.height(8.dp))
}

fun iconFor(p: Page) = when (p) {
    Page.Chat -> Icons.Outlined.ChatBubbleOutline
    Page.Profile -> Icons.Outlined.Person
    Page.Settings -> Icons.Outlined.Settings
    Page.Dashboard -> Icons.Outlined.Dashboard
    Page.Plant -> Icons.Outlined.Grass
    Page.Usage -> Icons.Outlined.WaterDrop
    Page.Solar -> Icons.Outlined.WbSunny
    Page.Controller -> Icons.Outlined.Memory
    Page.PumpDetail -> Icons.Outlined.Bolt
    Page.Billing -> Icons.Outlined.AccountBalanceWallet
    Page.Diagnostics -> Icons.Outlined.MonitorHeart
    Page.Panel -> Icons.Outlined.Tune
    Page.Network -> Icons.Outlined.Wifi
    Page.Device -> Icons.Outlined.Settings
    Page.Shop -> Icons.Outlined.ShoppingBag
    Page.About -> Icons.Outlined.Info
    Page.Support -> Icons.Outlined.HelpOutline
}

/** Shared padding for every page body, so they all sit on the same grid. */
val PagePad = PaddingValues(16.dp)

/** Vertical rhythm between cards. */
val CardGap = 12.dp

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = C.Dim,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/** Neutral placeholder colour for values that are not currently known. */
val Unknown: Color = C.Dim
