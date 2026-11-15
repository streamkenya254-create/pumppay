package ke.tafiti.pumppay

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/*
  Talking to the system.

  Listening uses the phone's own speech recogniser and speaking uses its text-to-speech
  engine: nothing here records or stores audio, and nothing leaves the phone except the
  recognised words, which go to the assistant exactly as if they had been typed.

  A spoken command that CHANGES something is never carried out on a single hearing.
  Switching the pump off asks first and waits for "yes" or a tap, because a speech
  recogniser mishears and an unrequested "pump off" costs someone their water.
*/

/** Reads text aloud. Markdown marks are removed so it never says "asterisk". */
class Speaker(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: String? = null

    /** True when the phone has no usable text-to-speech engine (or it could not start). */
    var failed = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                failed = true
            } else {
                val r = tts?.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.ENGLISH)
                tts?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ready = true
                waiting?.let { speak(it); waiting = null }
            }
        }
    }

    fun speak(text: String) {
        if (failed) return
        if (!ready) { waiting = text; return }      // the engine is still starting: say it when it is up
        tts?.speak(spoken(text), TextToSpeech.QUEUE_FLUSH, null, "pumppay")
    }

    fun stop() { waiting = null; tts?.stop() }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }

    companion object {
        /** Plain sentences for the voice: no bold marks, bullets read as separate sentences. */
        fun spoken(markdown: String): String =
            markdown.replace("**", "").replace("`", "")
                .lines()
                .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").trimStart('#', ' ') }
                .filter { it.isNotEmpty() }
                .joinToString(". ") { it.trimEnd('.', ' ') }
                .take(700)
    }
}

/** What a spoken or typed sentence is asking the system to do, when it is an order and not a question. */
sealed class Command {
    object PumpOn : Command()
    object PumpOff : Command()
    object ValveOpen : Command()
    object ValveClose : Command()
    class ValveSet(val pct: Int) : Command()
    class Open(val page: Page) : Command()
    object Yes : Command()
    object No : Command()
}

object Commands {
    private val questionWords = Regex("^(when|how|what|why|should|is|are|can|could|do|does|will|which|where|ni|je|lini|vipi)\\b")
    private val offWords = Regex("\\b(off|stop|shut|kill|zima|simamisha)\\b")
    private val onWords = Regex("\\b(on|start|run|begin|washa|anza)\\b")

    fun parse(raw: String): Command? {
        val t = raw.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) return null

        if (t in setOf("yes", "yeah", "yep", "confirm", "confirmed", "go ahead", "do it", "ok do it", "ndiyo", "sawa")) return Command.Yes
        if (t in setOf("no", "nope", "cancel", "never mind", "dont", "don't", "hapana", "acha")) return Command.No

        // "when should I turn the pump on?" is a question for the assistant, not an order.
        if (questionWords.containsMatchIn(t)) return null

        // "take me to the plant page", "open billing", "go to water usage"
        if (Regex("^(please )?(open|go to|take me to|show me|show|nenda)\\b").containsMatchIn(t) && !Regex("\\b(valve|vali)\\b").containsMatchIn(t)) {
            val page = when {
                Regex("\\b(plant|crop|farm|mmea)\\b").containsMatchIn(t) -> Page.Plant
                Regex("\\b(billing|bill|top ?up|balance|token)\\b").containsMatchIn(t) -> Page.Billing
                Regex("\\b(water|tank|usage)\\b").containsMatchIn(t) -> Page.Usage
                Regex("\\b(diagnos|health|check)").containsMatchIn(t) -> Page.Diagnostics
                Regex("\\b(solar|panel|sun)\\b").containsMatchIn(t) -> Page.Solar
                Regex("\\bcontroller\\b").containsMatchIn(t) -> Page.Controller
                Regex("\\bpump\\b").containsMatchIn(t) -> Page.PumpDetail
                Regex("\\b(setting|settings|install)").containsMatchIn(t) -> Page.Settings
                Regex("\\bprofile\\b").containsMatchIn(t) -> Page.Profile
                else -> null
            }
            if (page != null) return Command.Open(page)
        }

        if (Regex("\\b(valve|vali)\\b").containsMatchIn(t)) {
            Regex("(\\d{1,3})\\s*(percent|%|per cent|asilimia)").find(t)?.let {
                return Command.ValveSet(it.groupValues[1].toInt().coerceIn(0, 100))
            }
            if (Regex("\\b(open|fungua)\\b").containsMatchIn(t)) return Command.ValveOpen
            if (Regex("\\b(close|shut|funga)\\b").containsMatchIn(t)) return Command.ValveClose
            return null
        }

        if (Regex("\\b(pump|pampu)\\b").containsMatchIn(t)) {
            val off = offWords.containsMatchIn(t)
            val on = onWords.containsMatchIn(t)
            if (off && !on) return Command.PumpOff
            if (on && !off) return Command.PumpOn
        }
        return null
    }
}
