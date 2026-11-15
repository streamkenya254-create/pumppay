package ke.tafiti.pumppay

import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ArrowUpward
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One line of the conversation with the assistant. */
class Msg(val fromUser: Boolean, val text: String, val error: Boolean = false, val actions: List<Assistant.Action> = emptyList()) {
    /** Buttons are used once. */
    val used = androidx.compose.runtime.mutableStateListOf<Boolean>().also { l -> repeat(actions.size) { l.add(false) } }
}

/** **bold** inside a line becomes bold; stray markdown marks are dropped. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    val clean = text.replace("`", "")
    var i = 0
    while (i < clean.length) {
        val open = clean.indexOf("**", i)
        val close = if (open >= 0) clean.indexOf("**", open + 2) else -1
        if (open < 0 || close < 0) { append(clean.substring(i).replace("**", "")); break }
        append(clean.substring(i, open))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(clean.substring(open + 2, close)) }
        i = close + 2
    }
}

/**
 * The assistant's reply, laid out: short paragraphs, bullet points with a hanging indent,
 * numbered steps, and bold for the figures that matter. Headings and tables are flattened
 * so nothing shows up as stray symbols.
 */
@Composable
fun AnswerText(text: String, color: Color) {
    val lines = text.replace("\r", "").split("\n")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.all { it == '-' || it == '|' || it == ' ' || it == ':' }) continue
            val bullet = Regex("^[-*•]\\s+(.*)").matchEntire(line)
            val numbered = Regex("^(\\d+)[.)]\\s+(.*)").matchEntire(line)
            when {
                bullet != null -> Row {
                    Text("•", color = C.Pump, fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
                    Text(inline(bullet.groupValues[1]), color = color, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                }
                numbered != null -> Row {
                    Text(numbered.groupValues[1] + ".", color = C.Pump, fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
                    Text(inline(numbered.groupValues[2]), color = color, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                }
                line.startsWith("#") -> Text(
                    inline(line.trimStart('#', ' ')), color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp)
                )
                line.startsWith("|") -> Text(inline(line.trim('|').replace("|", "  ·  ")), color = color, fontSize = 14.sp, lineHeight = 20.sp)
                else -> Text(inline(line), color = color, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
    }
}

/** The conversation lives here so it survives leaving the chat and coming back. */
/** An order that changes something, waiting for "yes" or a tap. [done] is what is said afterwards. */
class Pending(val label: String, val done: String, val run: () -> Unit)

object ChatState {
    val messages = androidx.compose.runtime.mutableStateListOf<Msg>()
    var thinking by androidx.compose.runtime.mutableStateOf(false)
    var pending by androidx.compose.runtime.mutableStateOf<Pending?>(null)
}

private val SUGGESTIONS = listOf(
    "When should I pump today?",
    "How long should I pump?",
    "Is my crop getting enough water?",
    "Why is my pump slow?",
    "How do I top up?"
)

/** Three dots that pulse while the assistant is working out its answer. */
@Composable
private fun TypingDots() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val a by t.animateFloat(
                0.25f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(600),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                    androidx.compose.animation.core.StartOffset(i * 180)
                ), label = "d$i"
            )
            Box(Modifier.size(7.dp).background(C.Pump.copy(alpha = a), androidx.compose.foundation.shape.CircleShape))
        }
    }
}

@Composable
private fun Avatar() {
    Box(
        Modifier.size(28.dp).background(Color(0xFF14171A), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_logo), null, Modifier.size(16.dp))
    }
}

/**
 * The assistant as a full-screen chat: your questions as bubbles on the right, answers on the
 * left with the PumpPay mark, suggestions to start from, and the message bar fixed at the bottom.
 */
private fun pageFor(name: String): Page? = when (name) {
    "plant" -> Page.Plant
    "billing" -> Page.Billing
    "water" -> Page.Usage
    "diagnostics" -> Page.Diagnostics
    "installation" -> Page.PumpDetail
    "profile" -> Page.Profile
    "solar" -> Page.Solar
    "controller" -> Page.Controller
    "pump" -> Page.PumpDetail
    "settings" -> Page.Settings
    else -> null
}

private fun actionLabel(a: Assistant.Action): String = when (a.type) {
    "open" -> "Open " + (pageFor(a.page)?.title ?: "page")
    "setFarm" -> "Save to my farm"
    "setTank" -> "Save tank size"
    "setFlow" -> "Save pump flow"
    else -> "Do it"
}

private fun farmSummary(a: Assistant.Action): String {
    val f = a.farm ?: return ""
    val parts = ArrayList<String>()
    if (f.has("crop")) parts.add("Crop: " + f.getString("crop").replaceFirstChar { it.uppercase() })
    if (f.has("plantedOn")) parts.add("Planted: " + f.getString("plantedOn"))
    if (f.has("areaAcres")) parts.add("Field: " + f.getDouble("areaAcres") + " acre")
    if (f.has("soil")) parts.add("Soil: " + f.getString("soil").replace('_', ' '))
    if (f.has("method")) parts.add("Watering: " + f.getString("method"))
    if (f.has("place")) parts.add("Place: " + f.getString("place"))
    return parts.joinToString("\n")
}

@Composable
fun ChatScreen(s: Status, live: Boolean, prefs: Prefs, onSetPump: (Boolean) -> Unit = {}, onGo: (Page) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scroll = rememberScrollState()
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var readAloud by remember { mutableStateOf(prefs.readAloud) }
    val messages = ChatState.messages
    val thinking = ChatState.thinking
    val canSwitch = live

    val speaker = remember { Speaker(context) }
    var recognizer by remember { mutableStateOf<android.speech.SpeechRecognizer?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { speaker.shutdown(); recognizer?.destroy(); recognizer = null }
    }

    /** An answer from the system itself (not the model). Spoken if the question was spoken. */
    fun reply(t: String, voice: Boolean) {
        messages.add(Msg(false, t))
        if (voice || readAloud) speaker.speak(t)
    }

    fun handle(cmd: Command, voice: Boolean) {
        when (cmd) {
            is Command.Open -> { onGo(cmd.page); reply("Opening " + cmd.page.title + ".", voice) }
            Command.Yes -> {
                val p = ChatState.pending
                ChatState.pending = null
                if (p != null) { p.run(); reply("Done. " + p.done, voice) } else reply("There is nothing waiting to be confirmed.", voice)
            }
            Command.No -> { ChatState.pending = null; reply("Okay, cancelled.", voice) }
            Command.PumpOff, Command.PumpOn -> {
                val off = cmd == Command.PumpOff
                if (!canSwitch) {
                    reply("Connect to your controller to switch the pump.", voice)
                } else {
                    ChatState.pending = Pending(
                        if (off) "Switch the pump off" else "Switch the pump on",
                        if (off) "The pump is switched off. It comes back on if the controller restarts." else "The pump is back under billing control.",
                    ) { onSetPump(!off) }
                    reply((if (off) "Switch the pump off? " else "Switch the pump on? ") + "Say yes or tap Confirm.", voice)
                }
            }
            Command.ValveOpen -> { prefs.valveOpen = true; reply("Valve open at " + (prefs.valvePct * 100).toInt() + " percent. This is an app setting; the controller does not turn a real valve.", voice) }
            Command.ValveClose -> { prefs.valveOpen = false; reply("Valve closed. This is an app setting; close the real valve by hand.", voice) }
            is Command.ValveSet -> {
                prefs.valvePct = cmd.pct / 100f; prefs.valveOpen = cmd.pct > 0
                reply("Valve set to " + cmd.pct + " percent. This is an app setting; the controller does not turn a real valve.", voice)
            }
        }
    }

    fun send(q: String, voice: Boolean = false, otherHearings: List<String> = emptyList()) {
        val question = q.trim()
        if (question.isEmpty() || ChatState.thinking) return
        messages.add(Msg(true, question)); text = ""

        val cmd = Commands.parse(question)
        if (cmd != null) { handle(cmd, voice); return }

        ChatState.pending = null      // a new question drops anything waiting to be confirmed
        val history = messages.filter { !it.error }.takeLast(7).dropLast(1).map { (if (it.fromUser) "user" else "assistant") to it.text }
        ChatState.thinking = true
        scope.launch {
            val r = Assistant.ask(prefs, question, Assistant.context(s, live, prefs), history, voice, otherHearings)
            ChatState.thinking = false
            messages.add(Msg(false, r.text, error = !r.ok, actions = r.actions))
            if (voice || readAloud) speaker.speak(if (r.ok) r.text else "Sorry. " + r.text)
        }
    }

    /** A button under an answer was tapped. Saving anything is explicit: nothing is written until this. */
    fun act(m: Msg, index: Int) {
        val a = m.actions[index]
        if (m.used[index]) return
        m.used[index] = true
        when (a.type) {
            "open" -> pageFor(a.page)?.let { onGo(it) }
            "setTank" -> {
                prefs.tankLitres = a.litres.toFloat()
                val stored = Prefs(context).tankLitres      // read it back: say what is stored, not what was attempted
                reply(if (stored > 0f) "Saved and checked: tank capacity is " + String.format("%,d", stored.toInt()) + " litres." else "That did not save. Set it on the Water usage page.", false)
            }
            "setFlow" -> {
                prefs.flowLpm = a.lpm.toFloat()
                val stored = Prefs(context).flowLpm
                reply(if (stored > 0f) "Saved and checked: pump flow is " + stored.toInt() + " litres per minute. Ask me again when to pump and I will give you minutes." else "That did not save. Set it on the Pump page.", false)
            }
            "setFarm" -> {
                val f = a.farm ?: return
                if (f.has("crop")) prefs.crop = f.getString("crop")
                if (f.has("plantedOn")) prefs.plantedOn = f.getString("plantedOn")
                if (f.has("areaAcres")) prefs.areaAcres = f.getDouble("areaAcres")
                if (f.has("soil")) prefs.soil = f.getString("soil")
                if (f.has("method")) prefs.cropEff = when (f.getString("method")) { "sprinkler" -> 0.75f; "flood" -> 0.6f; else -> 0.9f }
                if (f.has("lat") && f.has("lon")) { prefs.cropLat = f.getDouble("lat"); prefs.cropLon = f.getDouble("lon") }
                reply("Saved your farm details. Opening your plan.", false)
                onGo(Page.Plant)
            }
        }
    }

    fun startListening() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            reply("Voice input is not available on this phone.", false); return
        }
        speaker.stop()
        val r = recognizer ?: android.speech.SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true; partial = "" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                partial = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onResults(results: android.os.Bundle?) {
                listening = false; partial = ""
                val all = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val heard = all.firstOrNull()
                // The recogniser's other guesses go along too: "pump flow rate" is often in there when the first guess is not.
                if (!heard.isNullOrBlank()) send(heard, voice = true, otherHearings = all.drop(1).take(3))
            }
            override fun onError(error: Int) {
                listening = false; partial = ""
                when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> reply("I did not catch that. Tap the microphone and try again.", false)
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> reply("Allow the microphone for PumpPay in Android Settings to use voice.", false)
                    android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> reply("Voice recognition needs a connection right now.", false)
                    else -> reply("Voice input stopped. Try again.", false)
                }
            }
        })
        r.startListening(
            android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 4)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
        )
    }

    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() else reply("Allow the microphone for PumpPay in Android Settings to use voice.", false) }

    fun toggleMic() {
        if (listening) { recognizer?.stopListening(); return }
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) startListening() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(messages.size, thinking, ChatState.pending) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        // read-aloud switch
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    readAloud = !readAloud; prefs.readAloud = readAloud
                    if (!readAloud) speaker.stop()
                    else if (speaker.failed) reply("This phone has no speech engine I can use. Install or enable Google Speech Services (Android Settings > Languages & input > Text-to-speech output).", false)
                    else speaker.speak("I will read answers aloud.")
                }
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                if (readAloud) androidx.compose.material.icons.Icons.Filled.VolumeUp else androidx.compose.material.icons.Icons.Filled.VolumeOff,
                null, tint = if (readAloud) C.Pump else C.Dim, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(if (readAloud) "Reading answers aloud" else "Read answers aloud", color = if (readAloud) C.Pump else C.Dim, fontSize = 13.sp)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (messages.isEmpty() && !thinking) {
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).background(Color(0xFF111316), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_logo), null, Modifier.size(34.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Ask me about your pump", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Type, or tap the microphone and speak. I use your farm and what the controller is reporting right now.",
                        color = C.Dim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                for (q in SUGGESTIONS) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F1215), RoundedCornerShape(16.dp))
                            .clickable { send(q) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) { Text(q, color = Color.White, fontSize = 15.sp) }
                }
                Text("You can also say: \"switch the pump off\", \"open the valve\", \"set the valve to 40 percent\".", color = C.Dim, fontSize = 12.sp, lineHeight = 17.sp)
            }

            for (m in messages) {
                if (m.fromUser) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            m.text, color = Color.Black, fontSize = 15.sp, lineHeight = 21.sp,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .background(Color(0xFFEDEDED), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                                .padding(horizontal = 16.dp, vertical = 11.dp)
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Avatar()
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AnswerText(m.text, if (m.error) Color(0xFFF5B301) else C.Text.copy(alpha = 0.95f))
                            m.actions.forEachIndexed { i, a ->
                                if (!m.used[i]) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0F1215), RoundedCornerShape(16.dp))
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (a.type == "setFarm") Text(farmSummary(a), color = C.Text, fontSize = 14.sp, lineHeight = 20.sp)
                                        if (a.type == "setTank") Text("Tank capacity: " + String.format("%,d", a.litres.toInt()) + " litres", color = C.Text, fontSize = 14.sp)
                                        if (a.type == "setFlow") Text("Pump flow: " + a.lpm.toInt() + " litres per minute", color = C.Text, fontSize = 14.sp)
                                        PrimaryButton(actionLabel(a)) { act(m, i) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (thinking) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(); Spacer(Modifier.width(12.dp)); TypingDots()
                }
            }

            // An order that changes something waits here for a yes.
            ChatState.pending?.let { p ->
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF14110A), RoundedCornerShape(18.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(p.label + "?", color = Color(0xFFF5B301), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { PrimaryButton("Confirm") { handle(Command.Yes, false) } }
                        Box(Modifier.weight(1f)) { WideButton("Cancel", true, C.Dim) { handle(Command.No, false) } }
                    }
                }
            }
        }

        // ---- the message bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp)
                .navigationBarsPadding()
                .background(Color(0xFF111316), RoundedCornerShape(30.dp))
                .padding(start = 20.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).padding(vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (listening) {
                    Text(partial.ifEmpty { "Listening..." }, color = C.Pump, fontSize = 16.sp, maxLines = 2)
                } else {
                    if (text.isEmpty()) Text("Message or tap the mic", color = C.Dim, fontSize = 16.sp)
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        maxLines = 4,
                        textStyle = androidx.compose.ui.text.TextStyle(color = C.Text, fontSize = 16.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(C.Pump),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send(text) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val hasText = text.isNotBlank() && !listening
            if (hasText) {
                Box(
                    Modifier.size(44.dp).background(if (!thinking) C.Pump else Color(0xFF23272D), androidx.compose.foundation.shape.CircleShape)
                        .clickable(enabled = !thinking) { send(text) },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.ArrowUpward, "Send", tint = Color.Black)
                }
            } else {
                Box(
                    Modifier.size(44.dp)
                        .background(if (listening) Color(0xFFE5484D) else Color(0xFF23272D), androidx.compose.foundation.shape.CircleShape)
                        .clickable(enabled = !thinking) { toggleMic() },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        if (listening) androidx.compose.material.icons.Icons.Filled.Stop else androidx.compose.material.icons.Icons.Filled.Mic,
                        if (listening) "Stop listening" else "Speak", tint = Color.White
                    )
                }
            }
        }
    }
}
