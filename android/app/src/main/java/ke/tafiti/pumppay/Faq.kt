package ke.tafiti.pumppay

/**
 * The built-in help, and the home for every explanation that would otherwise
 * clutter the readings pages.
 *
 * A dashboard should show the state of the pump. The moment it also starts
 * explaining how run detection works, someone reading a voltage has to skip
 * three paragraphs to find it. All of that reasoning is still worth having - it
 * just belongs where people go when they have a question, not where they go
 * when they want a number.
 */
data class Faq(val category: String, val q: String, val a: String)

object Help {

    const val CAT_CONNECT = "Connecting"
    const val CAT_READING = "Readings"
    const val CAT_USAGE = "Pump & water usage"
    const val CAT_NETWORK = "Network & security"
    const val CAT_PANEL = "Panel & buttons"
    const val CAT_TROUBLE = "Troubleshooting"

    val categories = listOf(
        CAT_CONNECT, CAT_READING, CAT_USAGE, CAT_NETWORK, CAT_PANEL, CAT_TROUBLE
    )

    val items = listOf(

        // ---- Connecting ---------------------------------------------------
        Faq(
            CAT_CONNECT,
            "How do I pair the controller?",
            "Android Settings > Bluetooth, and pair ${BtClient.DEVICE_NAME}. If it " +
                "asks for a code it is usually 1234 or 0000.\n\n" +
                "That code is Android's pairing code. It is NOT the controller's " +
                "web control PIN - two different things that unhappily share a name."
        ),
        Faq(
            CAT_CONNECT,
            "Why does the app not pair for me?",
            "Pairing in-app would require the Bluetooth scanning permission, " +
                "which is a reasonable thing to refuse for a device you pair " +
                "once and then forget. Android already has a good interface for " +
                "it, so the app uses devices you have already paired and asks " +
                "for nothing more."
        ),
        Faq(
            CAT_CONNECT,
            "The controller is not in the list.",
            "It has not been paired, or it has not been flashed with firmware " +
                "that advertises over Bluetooth. Only paired devices appear."
        ),
        Faq(
            CAT_CONNECT,
            "It says a device is not a serial device.",
            "You picked something that is not the controller - headphones, a " +
                "watch, a speaker. Only the controller runs a serial service for " +
                "this app to talk to. Pick ${BtClient.DEVICE_NAME}."
        ),
        Faq(
            CAT_CONNECT,
            "Does this app work on iPhone?",
            "No, and it cannot. Apple does not allow third-party apps to use " +
                "Bluetooth Classic serial. There is no version of this app that " +
                "runs on iOS.\n\n" +
                "iPhone users have the controller's built-in web page, which " +
                "shows the same readings and needs nothing installed."
        ),

        // ---- Readings -----------------------------------------------------
        Faq(
            CAT_READING,
            "What does the controller actually measure?",
            "One thing: the electricity going to the pump. It reads the line " +
                "voltage and the current the pump draws. Everything else - " +
                "whether the pump is running, for how long, and when - is worked " +
                "out from that current."
        ),
        Faq(
            CAT_READING,
            "Why apparent power (VA) and not watts?",
            "Real power needs the phase angle between voltage and current. This " +
                "board measures both magnitudes but not the phase between them, " +
                "and the filtering on the current channel shifts phase anyway.\n\n" +
                "Reporting VA as though it were watts would be wrong whenever " +
                "the load is not purely resistive, and a pump motor is not, so it " +
                "is labelled honestly."
        ),
        Faq(
            CAT_READING,
            "Why does a reading show a dash instead of a number?",
            "Because the controller has not measured it yet, or the current " +
                "sensor is not answering.\n\n" +
                "A dash is never shown as 0.00 on purpose. A zero and a " +
                "not-measured mean opposite things on a meter, and a display " +
                "that renders them alike eventually has someone read 0.00 A off " +
                "a pump that was never being measured."
        ),
        Faq(
            CAT_READING,
            "Current above about 7 A reads low. Why?",
            "The current sensor is a unidirectional part. It rests near the " +
                "bottom of its range with only about 10 A of room below that " +
                "point, so on alternating current the negative half of the " +
                "waveform runs into its floor and clips.\n\n" +
                "The result is a reading that falls progressively short as " +
                "current rises. It is a hardware limit - a bidirectional sensor " +
                "would remove it. Run detection is unaffected, because a pump " +
                "drawing that much is obviously running."
        ),

        // ---- Pump & usage -------------------------------------------------
        Faq(
            CAT_USAGE,
            "How does it know the pump is running?",
            "When the current rises above 1 A and stays there for 3 seconds, the " +
                "pump counts as running. When it falls below 0.6 A and stays " +
                "there for 8 seconds, it counts as stopped.\n\n" +
                "Two thresholds and a hold time on each, so a start-up surge, a " +
                "cloud passing over the solar panels or one noisy reading cannot " +
                "start or stop the clock."
        ),
        Faq(
            CAT_USAGE,
            "Does it measure litres?",
            "No - it measures how long the pump runs. The litres you see are an " +
                "estimate: run time multiplied by the flow rate you enter under " +
                "Installation.\n\n" +
                "It is only as accurate as that number. A pump's flow changes " +
                "with how high it lifts, how full the source is and how much sun " +
                "there is, so treat litres as a good guide, not a meter reading."
        ),
        Faq(
            CAT_USAGE,
            "How do I find my pump's flow rate?",
            "Look for litres per minute (or cubic metres per hour) on the pump's " +
                "label or datasheet. Better, time it: fill a container of known " +
                "size from the outlet and divide the litres by the minutes it " +
                "took. 1 cubic metre per hour is about 16.7 litres per minute."
        ),
        Faq(
            CAT_USAGE,
            "Why are usage charts empty?",
            "The controller records when the pump runs, but it has no clock of " +
                "its own. The app sets its clock each time you connect - until " +
                "then the controller can count run time but not WHEN.\n\n" +
                "Connect once with the pump running, and the charts start " +
                "filling from that moment."
        ),
        Faq(
            CAT_USAGE,
            "How far back does the history go?",
            "The controller keeps the last 7 days. Each time the app connects it " +
                "copies those into the phone, so the history grows past a week " +
                "just by using the app - up to 90 days.\n\n" +
                "If you do not open the app for more than a week, the days in " +
                "between are lost: the controller has already forgotten them."
        ),
        Faq(
            CAT_USAGE,
            "What happens if the controller loses power?",
            "The lifetime run time and the hourly history are saved to its " +
                "memory every ten minutes and when the pump stops, so a power " +
                "cut loses at most the last ten minutes. The pump itself is not " +
                "switched by this controller."
        ),
        Faq(
            CAT_USAGE,
            "Why does Today say a dash?",
            "The controller does not know the time yet, so it cannot say where " +
                "today began. A dash means not known - it is never shown as zero " +
                "hours, which would claim the pump had not run."
        ),

        // ---- Network & security -------------------------------------------
        Faq(
            CAT_NETWORK,
            "Should I use this app or the controller's web page?",
            "The web page works on iPhone and any computer and shows the same " +
                "readings. Use it when the controller is on Ethernet or WiFi.\n\n" +
                "This app's real advantage is that Bluetooth needs no network " +
                "at all, and it keeps a longer usage history on your phone. That " +
                "is what makes it worth having at a pump with no signal."
        ),
        Faq(
            CAT_NETWORK,
            "What is the web control PIN for?",
            "It guards the settings and panel buttons on the controller's web " +
                "page, so somebody who simply opens the page cannot change its " +
                "WiFi or PIN."
        ),
        Faq(
            CAT_NETWORK,
            "How secure is that PIN, really?",
            "It is a guard against accident and against a casual visitor on the " +
                "network. It is not security against anyone determined.\n\n" +
                "The page is plain HTTP, so the PIN crosses the network in the " +
                "clear and anyone able to capture traffic can read it. It is one " +
                "shared PIN, so it cannot be revoked for one person.\n\n" +
                "On a network you do not control, turn web control off in the " +
                "firmware and use the buttons or a paired phone."
        ),
        Faq(
            CAT_NETWORK,
            "Where are the WiFi credentials stored?",
            "In the controller's own flash, not in this app and not in the " +
                "firmware source. They survive a reflash - a firmware update will " +
                "not clear them.\n\n" +
                "That storage is not encrypted, so anyone who can read the flash " +
                "can read the password. It is convenience, not secrecy."
        ),
        Faq(
            CAT_NETWORK,
            "Why does saving WiFi need a reboot?",
            "The radio is started once when the controller boots, using " +
                "whatever credentials were stored at that moment. Saving new " +
                "ones writes them for next time rather than reconnecting " +
                "underneath a running system."
        ),

        // ---- Panel --------------------------------------------------------
        Faq(
            CAT_PANEL,
            "What do B1 to B4 do?",
            "B1 up, B2 down, B3 OK, B4 back. UP and DOWN move through the menu, " +
                "OK opens an item, BACK returns. Nothing is changed by scrolling " +
                "past it - the PIN editor only saves on OK after the last digit."
        ),
        Faq(
            CAT_PANEL,
            "Are the app buttons the same as the controller's?",
            "Yes, exactly. A press in the app is sent as the same event the " +
                "physical resistor ladder produces, so the controller cannot tell " +
                "the difference and there is only one piece of code deciding what " +
                "a button means."
        ),
        Faq(
            CAT_PANEL,
            "The panel shows 'no signal'.",
            "The app is not connected, so there is nothing to mirror. The " +
                "controller's own screen is unaffected either way."
        ),

        // ---- Troubleshooting ----------------------------------------------
        Faq(
            CAT_TROUBLE,
            "The app says PumpPay unreachable.",
            "It is not connected over Bluetooth. Check the controller is " +
                "powered and flashed, that it is paired in Android settings, and " +
                "then tap Connect.\n\n" +
                "Every page still works while unreachable - readings show " +
                "dashes and controls are disabled."
        ),
        Faq(
            CAT_TROUBLE,
            "The pump runs but the app says IDLE.",
            "The current is below 1 A, so the controller does not count it as " +
                "running. That can be a very small pump, or a current sensor " +
                "that needs calibrating. Check the amps reading on the " +
                "dashboard while the pump runs - it should be close to the " +
                "pump's rated current."
        ),
        Faq(
            CAT_TROUBLE,
            "It says NO SIGNAL.",
            "The controller cannot read its current sensor, so it does not know " +
                "whether the pump is running. It stops counting run time rather " +
                "than guess. Check the sensor board and its I2C wiring."
        ),
        Faq(
            CAT_TROUBLE,
            "The controller's LCD is blank or shows nonsense.",
            "A blank backlit screen usually means the display is not receiving " +
                "commands - check its wiring to the controller's I2C lines.\n\n" +
                "Readable-but-garbled text usually means the expander wiring " +
                "differs from the common layout, which is a firmware constant " +
                "rather than a fault."
        ),
        Faq(
            CAT_TROUBLE,
            "Ethernet says down.",
            "The controller tries two PHY addresses at boot and stops if neither " +
                "answers - retrying costs a system resource it never gets back.\n\n" +
                "If it stays down, the cause is the 50 MHz clock line, the PHY " +
                "reset pin, or PHY power. It needs a scope, not a setting."
        ),
        Faq(
            CAT_TROUBLE,
            "Readings look plausible but wrong.",
            "The most common cause is a voltage channel trimmed too hot. A " +
                "clipped waveform still produces a believable RMS number, just a " +
                "low one, and calibrating on top of it bakes the error in where " +
                "nothing later can see it."
        )
    )
}
