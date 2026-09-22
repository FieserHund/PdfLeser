package de.pdfleser.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.BreakIterator
import java.util.Locale

/**
 * Vorlesen mit der Android-Sprachausgabe (Samsung- oder Google-Stimmen).
 * Liest Satz für Satz, meldet den aktuellen Satz (zum Hervorheben und Weiterblättern)
 * und hält immer ein paar Sätze im Voraus in der Warteschlange.
 */
class ReadAloud(context: Context) {

    data class State(
        val active: Boolean = false,
        val playing: Boolean = false,
        val block: Int = -1,
        val start: Int = 0,
        val end: Int = 0
    )

    private class Seg(val id: String, val block: Int, val start: Int, val end: Int, val text: String)

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val state = MutableStateFlow(State())
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<() -> Unit>()
    private val queue = ArrayList<Seg>()
    private var gen = 0
    private var cursor = Pos(0, 0)
    private var readLocale: Locale = Locale.US
    private var rate = 1f
    private var blocks: () -> List<Block> = { emptyList() }
    private var finished: () -> Boolean = { true }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val id = utteranceId ?: return
            main.post { onSegStart(id) }
        }

        override fun onDone(utteranceId: String?) {
            val id = utteranceId ?: return
            main.post { onSegDone(id) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            val id = utteranceId ?: return
            main.post { onSegDone(id) }
        }
    }

    fun bind(blocks: () -> List<Block>, finished: () -> Boolean) {
        this.blocks = blocks
        this.finished = finished
    }

    fun start(from: Pos, lang: String, rate: Float) {
        stopInternal()
        cursor = from
        readLocale = if (lang == "de") Locale.GERMANY else Locale.US
        this.rate = rate
        state.value = State(active = true, playing = true)
        withTts { if (applyVoice(readLocale)) enqueueMore() else stop() }
    }

    fun toggle() {
        if (state.value.playing) pause() else resume()
    }

    fun pause() {
        val st = state.value
        if (!st.active || !st.playing) return
        queue.firstOrNull()?.let { cursor = Pos(it.block, it.start) }
        stopInternal()
        state.value = st.copy(playing = false)
    }

    fun resume() {
        val st = state.value
        if (!st.active || st.playing) return
        state.value = st.copy(playing = true)
        withTts { if (applyVoice(readLocale)) enqueueMore() else stop() }
    }

    fun stop() {
        stopInternal()
        state.value = State()
    }

    /** Neues Tempo wirkt sofort: der aktuelle Satz beginnt neu. */
    fun setRate(r: Float) {
        rate = r
        if (state.value.playing) {
            pause()
            resume()
        }
    }

    /** Einzelnes (englisches) Wort oder Satzstück aussprechen. */
    fun speakWord(text: String) {
        if (state.value.playing) pause()
        withTts {
            if (applyVoice(Locale.US)) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word")
        }
    }

    fun shutdown() {
        stopInternal()
        pending.clear()
        try { tts?.shutdown() } catch (e: Exception) { }
        tts = null
        ready = false
        state.value = State()
    }

    private fun stopInternal() {
        gen++
        queue.clear()
        try { tts?.stop() } catch (e: Exception) { }
    }

    private fun withTts(action: () -> Unit) {
        if (ready) {
            action()
            return
        }
        pending.add(action)
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(listener)
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    val actions = ArrayList(pending)
                    pending.clear()
                    actions.forEach { it() }
                } else {
                    pending.clear()
                    try { tts?.shutdown() } catch (e: Exception) { }
                    tts = null
                    state.value = State()
                    errors.tryEmit("Die Sprachausgabe ist auf diesem Gerät nicht verfügbar.")
                }
            }
        }
    }

    private fun applyVoice(locale: Locale): Boolean {
        val t = tts ?: return false
        val r = t.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            errors.tryEmit(
                "Für ${locale.getDisplayLanguage(Locale.GERMAN)} ist keine Stimme installiert. " +
                    "Suche in den Einstellungen nach „Text-in-Sprache“ und lade die Sprache herunter."
            )
            return false
        }
        t.setSpeechRate(rate)
        return true
    }

    private fun enqueueMore() {
        val t = tts ?: return
        val st = state.value
        if (!ready || !st.active || !st.playing) return
        val bl = blocks()
        while (queue.size < 3) {
            val seg = nextSegment(bl) ?: break
            queue.add(seg)
            t.speak(seg.text, TextToSpeech.QUEUE_ADD, null, seg.id)
        }
        if (queue.isEmpty()) {
            if (finished()) {
                stop()
            } else {
                // Buch wird noch aufbereitet – gleich noch einmal versuchen.
                val g = gen
                main.postDelayed({ if (g == gen) enqueueMore() }, 1500)
            }
        }
    }

    private fun nextSegment(bl: List<Block>): Seg? {
        var b = cursor.block
        var c = cursor.char
        while (b < bl.size) {
            val block = bl[b]
            if (block is TextBlock && !block.note && c < block.text.length) {
                val text = block.text
                val bi = BreakIterator.getSentenceInstance(readLocale)
                bi.setText(text)
                var end = bi.following(c)
                if (end == BreakIterator.DONE || end <= c) end = text.length
                if (end - c > 600) {
                    val cut = text.lastIndexOf(' ', c + 600)
                    end = if (cut > c + 100) cut + 1 else c + 600
                }
                cursor = Pos(b, end)
                val raw = text.substring(c, end)
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed.none { ch -> ch.isLetterOrDigit() }) {
                    c = end
                    continue
                }
                val s = c + (raw.length - raw.trimStart().length)
                return Seg("$gen:$b:$s", b, s, s + trimmed.length, trimmed)
            }
            b++
            c = 0
            cursor = Pos(b, 0)
        }
        return null
    }

    private fun onSegStart(id: String) {
        val seg = queue.firstOrNull { it.id == id } ?: return
        val st = state.value
        if (!st.active) return
        state.value = st.copy(block = seg.block, start = seg.start, end = seg.end)
    }

    private fun onSegDone(id: String) {
        val i = queue.indexOfFirst { it.id == id }
        if (i < 0) return
        queue.removeAt(i)
        enqueueMore()
    }
}
