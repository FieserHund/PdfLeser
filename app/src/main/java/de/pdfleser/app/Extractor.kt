package de.pdfleser.app

import android.content.Context
import android.os.Process
import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.yield
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.io.Writer
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* =====================================================================
 *  Buchinhalt: wächst Seite für Seite, während im Hintergrund extrahiert wird.
 * ===================================================================== */

class BookContent(val id: String) {
    private val blocks = ArrayList<Block>()
    private val notes = ArrayList<Footnote>()

    @Volatile var pagesDone = 0
        private set
    @Volatile var totalPages = 0
    @Volatile var finished = false
    @Volatile var error: String? = null
    @Volatile var textChars = 0L
        private set
    @Volatile var toc: List<TocEntry> = emptyList()
    /** Absätze aus: der Text läuft durch, nur Überschriften und große Lücken trennen. */
    @Volatile var continuous = false
    /** true, wenn das Buch wegen einer neueren Texterkennung neu aufbereitet wird. */
    @Volatile var formatReset = false

    /** Wird bei jeder Änderung erhöht – die Leseansicht hört darauf. */
    val version = MutableStateFlow(0)

    @Synchronized
    fun addPage(r: PageResult, notify: Boolean = true) {
        appendMerged(blocks, r.blocks, continuous)
        notes.addAll(r.footnotes)
        for (b in r.blocks) if (b is TextBlock) textChars += b.text.length
        pagesDone++
        if (notify) bump()
    }

    @Synchronized
    fun snapshot(): List<Block> = ArrayList(blocks)

    @Synchronized
    fun footnotes(): List<Footnote> = ArrayList(notes)

    private var endCache: List<Footnote> = emptyList()
    private var endCacheBlocks = -1

    /** Einträge aus Literaturverzeichnis/Anmerkungen; wird nur neu berechnet, wenn Text dazukam. */
    @Synchronized
    fun endnotes(): List<Footnote> {
        if (endCacheBlocks != pagesDone) {
            endCache = try {
                NoteResolver.build(blocks)
            } catch (e: Exception) {
                emptyList()
            }
            endCacheBlocks = pagesDone
        }
        return endCache
    }

    /** Alle Quellen für die Übersicht: Fußnoten der Seiten und Einträge des Literaturverzeichnisses. */
    @Synchronized
    fun allNotes(): List<Footnote> {
        val seen = HashSet<String>()
        val out = ArrayList<Footnote>()
        for (f in notes + endnotes()) {
            if (seen.add("${f.pdfPage}|${f.marker}|${f.text.take(40)}")) out.add(f)
        }
        return out.sortedWith(compareBy({ it.pdfPage }, { it.marker.toIntOrNull() ?: Int.MAX_VALUE }))
    }

    /**
     * Die Quellen zu einem Verweis. Ein Verweis kann mehrere Nummern tragen („1,2“, „3–5“);
     * jede wird zuerst unter den Fußnoten der Seite gesucht, dann im Literaturverzeichnis.
     */
    @Synchronized
    fun resolve(rawMarker: String, page: Int): List<Footnote> {
        val keys = NoteResolver.markers(rawMarker)
        if (keys.isEmpty()) return emptyList()
        val ends = endnotes()
        return keys.mapNotNull { NoteResolver.pick(it, page, notes, ends) }.distinct()
    }

    fun bump() = version.update { it + 1 }
}

/**
 * Hängt die Blöcke einer Seite an. Ein über den Seitenwechsel laufender Absatz wird zusammengefügt –
 * dabei werden Fußnoten und Bilder am Ende der Vorseite übersprungen (sie rutschen hinter den Absatz).
 */
fun appendMerged(blocks: MutableList<Block>, page: List<Block>, continuous: Boolean = false) {
    val ft = page.indexOfFirst { it is TextBlock }
    if (ft >= 0 && (0 until ft).all { page[it] is ImageBlock }) {
        val first = page[ft] as TextBlock
        if (first.joinPrev) {
            var li = blocks.size - 1
            var steps = 0
            while (li >= 0 && steps < 8) {
                val b = blocks[li]
                if (b is ImageBlock || (b is TextBlock && b.note)) {
                    li--
                    steps++
                } else {
                    break
                }
            }
            val last = blocks.getOrNull(li)
            if (last is TextBlock && !last.heading && !last.note &&
                (continuous || last.openEnd || !TextUtil.endsSentence(last))
            ) {
                val cap = if (continuous) 4000 else 8000
                if (last.text.length + first.text.length < cap) {
                    val (merged, off) = TextUtil.joinWithOffset(last.text, first.text)
                    blocks[li] = last.copy(
                        text = merged,
                        openEnd = first.openEnd,
                        refs = last.refs + TextUtil.shift(first.refs, off, merged.length)
                    )
                    for (i in page.indices) if (i != ft) blocks.add(page[i])
                    return
                }
                // Zu lang für einen Block: am letzten Satzende trennen, damit kein Satz zerrissen wird.
                if (!TextUtil.endsSentence(last)) {
                    val cut = TextUtil.lastSentenceEnd(last.text, last.text.length / 2)
                    if (cut > 0) {
                        val head = last.text.substring(0, cut).trim()
                        val tail = last.text.substring(cut).trim()
                        val (merged, off) = TextUtil.joinWithOffset(tail, first.text)
                        blocks[li] = last.copy(
                            text = head,
                            openEnd = false,
                            refs = last.refs.filter { it.end <= head.length }
                        )
                        val tailOff = -(last.text.length - tail.length)
                        val carried = first.copy(
                            text = merged,
                            joinPrev = false,
                            refs = TextUtil.shift(last.refs.filter { it.start >= cut }, tailOff, merged.length) +
                                TextUtil.shift(first.refs, off, merged.length)
                        )
                        for (i in page.indices) blocks.add(if (i == ft) carried else page[i])
                        return
                    }
                }
            }
        }
    }
    blocks.addAll(page)
}

/* =====================================================================
 *  Speicherung auf Disk (JSON Lines, eine Zeile pro PDF-Seite).
 *  Einmal extrahiert, öffnet sich ein Buch danach sofort.
 * ===================================================================== */

object ContentStore {
    /** Wird erhöht, wenn sich die Texterkennung ändert → vorhandene Bücher werden neu aufbereitet. */
    const val FORMAT = 5

    fun pageLine(page: Int, r: PageResult): String {
        val arr = JSONArray()
        for (b in r.blocks) {
            when (b) {
                is TextBlock -> arr.put(
                    JSONObject().put("t", b.text).put("h", b.heading).put("j", b.joinPrev)
                        .put("n", b.note).put("o", b.openEnd)
                        .apply {
                            if (b.refs.isNotEmpty()) {
                                val rr = JSONArray()
                                for (x in b.refs) rr.put(JSONArray().put(x.start).put(x.end).put(x.marker).put(x.kind))
                                put("r", rr)
                            }
                        }
                )
                is ImageBlock -> arr.put(
                    JSONObject()
                        .put("i", JSONArray().put(b.l.toDouble()).put(b.t.toDouble()).put(b.r.toDouble()).put(b.b.toDouble()))
                        .put("a", b.aspect.toDouble())
                )
            }
        }
        val fn = JSONArray()
        for (f in r.footnotes) fn.put(JSONObject().put("m", f.marker).put("t", f.text))
        val o = JSONObject().put("p", page).put("b", arr)
        if (fn.length() > 0) o.put("f", fn)
        r.pageNumber?.let { o.put("pn", it) }
        return o.toString()
    }

    private fun parseFootnotes(arr: JSONArray?, page: Int): List<Footnote> {
        if (arr == null) return emptyList()
        val out = ArrayList<Footnote>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Footnote(page, o.optString("m"), o.optString("t")))
        }
        return out
    }

    private fun parseBlocks(arr: JSONArray?, page: Int): List<Block> {
        if (arr == null) return emptyList()
        val out = ArrayList<Block>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("t")) {
                val refs = ArrayList<Ref>()
                val rr = o.optJSONArray("r")
                if (rr != null) {
                    for (k in 0 until rr.length()) {
                        val a = rr.optJSONArray(k) ?: continue
                        refs.add(Ref(a.optInt(0), a.optInt(1), a.optString(2), a.optInt(3, Ref.KIND_CITE)))
                    }
                }
                out.add(
                    TextBlock(
                        o.getString("t"), o.optBoolean("h"), o.optBoolean("j"), page,
                        o.optBoolean("n"), o.optBoolean("o"), refs
                    )
                )
            } else {
                val r = o.optJSONArray("i") ?: continue
                out.add(
                    ImageBlock(
                        page,
                        r.getDouble(0).toFloat(), r.getDouble(1).toFloat(),
                        r.getDouble(2).toFloat(), r.getDouble(3).toFloat(),
                        o.optDouble("a", 1.0).toFloat()
                    )
                )
            }
        }
        return out
    }

    fun load(ctx: Context, c: BookContent) {
        c.continuous = Prefs.sp(ctx).getInt("para_${c.id}", 0) == 1
        var total = 0
        var done = false
        var version = FORMAT
        val toc = ArrayList<TocEntry>()
        val meta = Library.metaFile(ctx, c.id)
        if (meta.exists()) {
            try {
                val o = JSONObject(meta.readText())
                version = o.optInt("v", 1)
                total = o.optInt("total")
                done = o.optBoolean("done")
                val a = o.optJSONArray("toc")
                if (a != null) for (k in 0 until a.length()) {
                    val e = a.getJSONObject(k)
                    toc.add(TocEntry(e.getString("t"), e.getInt("p"), e.optInt("l")))
                }
            } catch (e: Exception) { }
        }
        val f = Library.contentFile(ctx, c.id)
        // Gescannte Bücher haben kein PDF: ihr erkannter Text ist die einzige Quelle und
        // darf bei einer neuen Programmversion niemals gelöscht werden.
        val isScan = Library.scanPages(ctx, c.id).isNotEmpty()
        if (version != FORMAT && !isScan) {
            // Mit älterer Texterkennung aufbereitet → verwerfen und neu aufbereiten.
            // Vorher die Leseposition in eine PDF-Seitenzahl übersetzen, damit sie erhalten bleibt.
            val sp = Prefs.sp(ctx)
            val oldPos = sp.getString("pos_${c.id}", null)
            // Ab Format 2 speichert die App die PDF-Seite ohnehin mit (ppage_…).
            if (version == 1 && oldPos != null && f.exists()) {
                val pp = try { legacyPdfPage(f, Pos.parse(oldPos).block) } catch (e: Exception) { -1 }
                if (pp >= 0) sp.edit().putInt("ppage_${c.id}", pp).apply()
            }
            c.formatReset = true
            f.delete()
            meta.delete()
            Library.booksDir(ctx).listFiles()?.filter { it.name.startsWith("${c.id}.pages.") }?.forEach { it.delete() }
            total = 0
            done = false
            toc.clear()
        }
        var valid = 0L
        var expected = 0
        if (f.exists()) {
            try {
                f.bufferedReader(Charsets.UTF_8).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        val o = (try { JSONObject(line) } catch (e: Exception) { null }) ?: break
                        if (o.optInt("p", -1) != expected) break
                        c.addPage(
                            PageResult(
                                parseBlocks(o.optJSONArray("b"), expected),
                                parseFootnotes(o.optJSONArray("f"), expected),
                                if (o.has("pn")) o.optInt("pn") else null
                            ),
                            notify = false
                        )
                        expected++
                        valid += line.toByteArray(Charsets.UTF_8).size + 1
                    }
                }
            } catch (e: Exception) { }
            // Abgebrochene letzte Zeile (z. B. App wurde beendet) abschneiden.
            if (valid < f.length()) {
                try { RandomAccessFile(f, "rw").use { it.setLength(valid) } } catch (e: Exception) { }
            }
        }
        c.totalPages = if (isScan) max(total, expected) else total
        c.toc = toc
        c.finished = if (isScan) expected > 0 else (done && total > 0 && expected >= total)
        c.bump()
    }

    /** Auf welcher PDF-Seite lag Block Nr. oldBlock in der alten (Format 1) Aufbereitung? */
    private fun legacyPdfPage(f: java.io.File, oldBlock: Int): Int {
        var count = 0
        var lastText: String? = null
        var lastHeading = false
        var page = 0
        f.bufferedReader(Charsets.UTF_8).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                val o = try { JSONObject(line) } catch (e: Exception) { break }
                page = o.optInt("p", page)
                val blocks = parseBlocks(o.optJSONArray("b"), page)
                val ft = blocks.indexOfFirst { it is TextBlock }
                var merged = false
                if (ft >= 0) {
                    val first = blocks[ft] as TextBlock
                    val lt = lastText
                    merged = first.joinPrev && lt != null && !lastHeading &&
                        (0 until ft).all { blocks[it] is ImageBlock } &&
                        !TextUtil.endsSentence(lt) && lt.length + first.text.length < 6000
                    if (merged && lt != null && ft == blocks.size - 1) {
                        lastText = TextUtil.join(lt, first.text)
                    }
                }
                val added = if (merged) blocks.size - 1 else blocks.size
                if (oldBlock < count + added) return page
                count += added
                if (!(merged && ft == blocks.size - 1)) {
                    val lb = blocks.lastOrNull()
                    if (lb is TextBlock) {
                        lastText = lb.text
                        lastHeading = lb.heading
                    } else if (lb != null) {
                        lastText = null
                    }
                }
            }
        }
        return page
    }

    fun saveMeta(ctx: Context, id: String, total: Int, done: Boolean, toc: List<TocEntry>) {
        val arr = JSONArray()
        for (t in toc) arr.put(JSONObject().put("t", t.title).put("p", t.pdfPage).put("l", t.level))
        val o = JSONObject().put("v", FORMAT).put("total", total).put("done", done).put("toc", arr)
        try {
            val f = Library.metaFile(ctx, id)
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) { }
    }
}

/* =====================================================================
 *  Verwaltung: pro Buch höchstens eine laufende Extraktion.
 * ===================================================================== */

object ContentManager {
    /**
     * Eigener Thread mit etwas niedrigerer Priorität: Die Aufbereitung läuft im Hintergrund,
     * ohne dem Blättern und Zeichnen Rechenzeit wegzunehmen (wichtig auf Mittelklasse-Chips).
     */
    private val extractDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 5)
            r.run()
        }, "pdf-extract")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + extractDispatcher)
    private val contents = HashMap<String, BookContent>()
    private val jobs = HashMap<String, Job>()

    @Synchronized
    fun get(ctx: Context, id: String): BookContent {
        val app = ctx.applicationContext
        val existing = contents[id]
        if (existing != null) {
            ensureRunning(app, existing)
            return existing
        }
        val c = BookContent(id)
        ContentStore.load(app, c)
        // Andere, fertige Bücher aus dem Speicher werfen.
        val stale = contents.keys.filter { jobs[it]?.isActive != true }
        stale.forEach { contents.remove(it) }
        contents[id] = c
        ensureRunning(app, c)
        return c
    }

    /** Nach neuen Seiten (Scan): Inhalt behalten, aber frisch einlesen und Umbruch verwerfen. */
    @Synchronized
    fun invalidate(ctx: Context, id: String) {
        jobs.remove(id)?.cancel()
        contents.remove(id)
        Library.booksDir(ctx).listFiles()?.filter { it.name.startsWith("$id.pages.") }?.forEach { it.delete() }
    }

    /** Buch komplett neu aufbereiten (z. B. nach Wechsel des Absatz-Modus). */
    @Synchronized
    fun reextract(ctx: Context, id: String) {
        jobs.remove(id)?.cancel()
        contents.remove(id)
        Library.contentFile(ctx, id).delete()
        Library.metaFile(ctx, id).delete()
        Library.booksDir(ctx).listFiles()?.filter { it.name.startsWith("$id.pages.") }?.forEach { it.delete() }
    }

    @Synchronized
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        contents.remove(id)
    }

    private fun ensureRunning(app: Context, c: BookContent) {
        if (c.finished || c.error != null) return
        if (Library.scanPages(app, c.id).isNotEmpty()) {
            // Gescanntes Buch: Der Text entsteht beim Scannen, hier gibt es nichts zu extrahieren.
            if (c.pagesDone == 0) c.error = "Für dieses Buch wurde noch kein Text erkannt."
            c.finished = true
            c.bump()
            return
        }
        if (jobs[c.id]?.isActive == true) return
        jobs[c.id] = scope.launch { Extractor(app, c).run() }
    }
}

private object NullWriter : Writer() {
    override fun write(cbuf: CharArray, off: Int, len: Int) {}
    override fun flush() {}
    override fun close() {}
}

class Extractor(private val ctx: Context, private val c: BookContent) {

    suspend fun run() {
        val file = Library.pdfFile(ctx, c.id)
        var doc: PDDocument? = null
        var pages: PdfPages? = null
        var writer: BufferedWriter? = null
        try {
            val d = PDDocument.load(file, MemoryUsageSetting.setupMixed(64L * 1024 * 1024).setTempDir(ctx.cacheDir))
            doc = d
            val total = d.numberOfPages
            c.totalPages = total
            if (c.pagesDone == 0) c.toc = readToc(d)
            ContentStore.saveMeta(ctx, c.id, total, false, c.toc)
            c.bump()

            pages = try { PdfPages(file) } catch (e: Exception) { null }
            val w = BufferedWriter(OutputStreamWriter(FileOutputStream(Library.contentFile(ctx, c.id), true), Charsets.UTF_8))
            writer = w
            val stripper = LineStripper()
            val analyzer = PageAnalyzer(pages, c.continuous)

            for (i in c.pagesDone until total) {
                currentCoroutineContext().ensureActive()
                yield() // mehrere Bücher gleichzeitig: seitenweise abwechseln
                val result = try {
                    analyzer.process(d, stripper, i)
                } catch (e: Throwable) {
                    PageResult(emptyList(), emptyList())
                }
                w.write(ContentStore.pageLine(i, result))
                w.write("\n")
                w.flush()
                c.addPage(result)
            }
            ContentStore.saveMeta(ctx, c.id, total, true, c.toc)
            c.finished = true
            c.bump()
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidPasswordException) {
            c.error = "Das PDF ist passwortgeschützt."
            c.bump()
        } catch (e: Throwable) {
            c.error = e.message ?: e.javaClass.simpleName
            c.bump()
        } finally {
            try { writer?.close() } catch (e: Exception) { }
            try { doc?.close() } catch (e: Exception) { }
            pages?.close()
        }
    }

    private fun readToc(doc: PDDocument): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        try {
            val outline = doc.documentCatalog.documentOutline ?: return out
            walk(doc, outline.firstChild, 0, out)
        } catch (e: Exception) { }
        return out
    }

    private fun walk(doc: PDDocument, start: PDOutlineItem?, level: Int, out: MutableList<TocEntry>) {
        var node = start
        var guard = 0
        while (node != null && out.size < 800 && guard < 5000) {
            guard++
            val title = node.title?.trim().orEmpty()
            val page = try { node.findDestinationPage(doc) } catch (e: Exception) { null }
            val idx = if (page != null) doc.pages.indexOf(page) else -1
            if (idx >= 0 && title.isNotEmpty()) out.add(TocEntry(title, idx, level))
            if (level < 2) walk(doc, node.firstChild, level + 1, out)
            node = node.nextSibling
        }
    }
}

/* =====================================================================
 *  Text-Hilfen
 * ===================================================================== */

object TextUtil {
    private val ligatures = mapOf(
        '\uFB00' to "ff", '\uFB01' to "fi", '\uFB02' to "fl",
        '\uFB03' to "ffi", '\uFB04' to "ffl", '\uFB05' to "st", '\uFB06' to "st"
    )
    private val multiSpace = Regex(" {2,}")
    private const val TERMINAL = ".!?:…\"”’»)]"

    /**
     * Bereinigt Text aus dem PDF: Ligaturen auflösen, unsichtbare Zeichen entfernen und ALLE
     * Leerzeichen-Varianten (geschütztes, schmales, Halbgeviert-Leerzeichen, Tab …) zu einem normalen
     * Leerzeichen machen. Nur normale Leerzeichen werden beim Blocksatz gedehnt – sonst entstehen Löcher.
     */
    /** Klammern um einen hochgestellten Quellenverweis (nur intern, nie sichtbar). */
    /** Interne Klammern um hochgestellte bzw. tiefgestellte Stellen (nie sichtbar). */
    const val REF_OPEN = '\uE000'
    const val REF_CLOSE = '\uE001'
    const val SUB_OPEN = '\uE002'
    const val SUB_CLOSE = '\uE003'

    private const val SUPER = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079"
    private const val SUBSC = "\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089"
    private const val CLOSERS = ".,;:!?)]\"”’»"
    private val UNITS = hashSetOf("m", "cm", "mm", "km", "dm", "nm", "pm", "µm", "μm", "s", "ft", "in", "Å")

    fun isMarkChar(c: Char): Boolean = c in '\uE000'..'\uE003'

    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val l = ligatures[ch]
            val sup = SUPER.indexOf(ch)
            val sub = SUBSC.indexOf(ch)
            when {
                l != null -> sb.append(l)
                // Echte Unicode-Hoch-/Tiefstellungen (¹² ⁺ ₂) in die interne Form bringen.
                sup >= 0 -> sb.append(REF_OPEN).append('0' + sup).append(REF_CLOSE)
                ch == '\u207A' -> sb.append(REF_OPEN).append('+').append(REF_CLOSE)
                ch == '\u207B' -> sb.append(REF_OPEN).append('\u2212').append(REF_CLOSE)
                sub >= 0 -> sb.append(SUB_OPEN).append('0' + sub).append(SUB_CLOSE)
                ch == '\u0000' || ch == '\uFFFD' || ch == '\u200B' || ch == '\u2060' || ch == '\uFEFF' -> Unit
                ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return multiSpace.replace(sb, " ").trim()
    }

    /** Text ohne die internen Markierungen (Inhalt bleibt). */
    fun stripRefs(s: String): String =
        if (s.indexOfFirst { isMarkChar(it) } < 0) s else s.filterNot { isMarkChar(it) }

    /** Text ohne markierte Stellen samt Inhalt – „Satz.¹²“ → „Satz.“ (für die Satzende-Prüfung). */
    fun dropMarked(s: String): String {
        if (s.indexOfFirst { isMarkChar(it) } < 0) return s
        val sb = StringBuilder(s.length)
        var inside = false
        for (c in s) {
            when (c) {
                REF_OPEN, SUB_OPEN -> inside = true
                REF_CLOSE, SUB_CLOSE -> inside = false
                else -> if (!inside) sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Endet der Absatz mit einem Satzende – Fußnotennummern am Ende zählen nicht mit. */
    fun endsSentence(b: TextBlock): Boolean {
        var t = b.text.trimEnd()
        for (round in 0 until 3) {
            val r = b.refs.lastOrNull { it.kind != Ref.KIND_SUB && it.end >= t.length && it.start < t.length } ?: break
            t = t.substring(0, r.start).trimEnd()
        }
        return endsSentence(t)
    }

    /**
     * Trennt markierte Stellen vom Text und ordnet sie ein:
     * Quellenverweis (antippbar), bloß hochgestellt (Ladung, Isotop, Potenz, Einheit) oder tiefgestellt.
     * Direkt benachbarte Markierungen gleicher Art (¹²) werden zusammengefasst.
     */
    fun extractRefs(raw: String): Pair<String, List<Ref>> {
        if (raw.indexOfFirst { isMarkChar(it) } < 0) return Pair(raw, emptyList())
        val sb = StringBuilder(raw.length)
        // Läufe: [Anfang, Ende, Art] – Art 1 = hochgestellt, 2 = tiefgestellt
        val runs = ArrayList<IntArray>()
        var open = -1
        var openKind = 0
        for (c in raw) {
            when (c) {
                REF_OPEN, SUB_OPEN -> {
                    open = sb.length
                    openKind = if (c == REF_OPEN) 1 else 2
                }
                REF_CLOSE, SUB_CLOSE -> {
                    if (open >= 0 && sb.length > open) {
                        val last = runs.lastOrNull()
                        if (last != null && last[1] == open && last[2] == openKind) last[1] = sb.length
                        else runs.add(intArrayOf(open, sb.length, openKind))
                    }
                    open = -1
                }
                else -> sb.append(c)
            }
        }
        val refs = ArrayList<Ref>()
        var removed = 0
        for (r in runs) {
            var s = r[0] - removed
            var e = r[1] - removed
            while (s < e && sb[s] == ' ') s++
            while (e > s && sb[e - 1] == ' ') e--
            if (e <= s) continue
            val content = sb.substring(s, e)
            if (r[2] == 2) {
                refs.add(Ref(s, e, content, Ref.KIND_SUB))
                continue
            }
            val cite = isCitation(sb, s, e, content)
            // „Wort ¹²“ → „Wort¹²“: Ein Leerzeichen vor einem Quellenverweis gehört nicht dorthin.
            if (cite && s >= 2 && sb[s - 1] == ' ' && (sb[s - 2].isLetter() || sb[s - 2] in CLOSERS)) {
                sb.deleteCharAt(s - 1)
                s--
                e--
                removed++
            }
            refs.add(Ref(s, e, content, if (cite) Ref.KIND_CITE else Ref.KIND_SUP))
        }
        return Pair(sb.toString(), refs)
    }

    /**
     * Ist eine hochgestellte Stelle ein Quellenverweis? Nein bei Ladungen (Ca²⁺), Potenzen (10⁻³, 10⁵),
     * Isotopen (¹³C, ¹H), Einheiten (cm³) und bei einer Nummer ganz am Absatzanfang (Listeneintrag).
     */
    private fun isCitation(t: CharSequence, s: Int, e: Int, content: String): Boolean {
        if (content.none { it.isDigit() || it in "*†‡§¶" }) return false
        if (content.any { it == '+' || it == '\u2212' || it == '±' }) return false
        if (content[0] == '-' || content[0] == '–') return false
        if (s == 0) return false
        val before = t[s - 1]
        val after = if (e < t.length) t[e] else ' '
        if (after.isLetter()) return false
        if (before.isDigit()) return false
        if (content.length == 1 && (content[0] == '2' || content[0] == '3')) {
            var k = s
            while (k > 0 && (t[k - 1].isLetter() || t[k - 1] == 'µ')) k--
            if (t.subSequence(k, s).toString() in UNITS) return false
        }
        return true
    }

    /** Führende Fußnotennummer einer Quellenzeile („12 Smith, J. …“ → „12“). */
    fun leadingMarker(raw: String): Pair<String, String>? {
        var t = raw
        if (t.startsWith(REF_OPEN)) {
            val e = t.indexOf(REF_CLOSE)
            if (e > 1) {
                val m = t.substring(1, e).filter { it.isDigit() || it in "*†‡§¶" }
                if (m.isNotEmpty() && m.length <= 4) {
                    return Pair(m, t.substring(e + 1).trimStart(' ', '.', ')', ']'))
                }
            }
            return null
        }
        t = stripRefs(t)
        val m = NOTE_START.find(t) ?: return null
        val marker = m.groupValues[1]
        return Pair(marker, t.substring(m.value.length))
    }

    val NOTE_START = Regex("""^([0-9]{1,3}|[*†‡§¶]{1,3})[.)\]]?[  \t]+""")

    /** Besteht der Text nur aus (evtl. unsichtbaren) Leerzeichen? */
    fun isSpaceLike(u: String): Boolean =
        u.all { it.isWhitespace() || it == '\u200B' || it == '\u2060' || it == '\uFEFF' }

    /** Position direkt nach dem letzten Satzende (ab minIndex), oder -1. */
    fun lastSentenceEnd(t: String, minIndex: Int): Int {
        var i = t.length - 2
        while (i >= minIndex && i >= 0) {
            val c = t[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < t.length && t[j] in "\"”’»)") j++
                if (j < t.length && t[j] == ' ') return j
            }
            i--
        }
        return -1
    }

    /** Sucht needle in hay und ignoriert dabei Leerzeichen (für Markierungen nach Neu-Aufbereitung). */
    fun findLoose(hay: String, needle: String): IntRange? {
        val n = needle.filterNot { it.isWhitespace() }
        if (n.isEmpty()) return null
        for (i in hay.indices) {
            if (hay[i] != n[0]) continue
            var j = i
            var k = 0
            var last = i
            while (j < hay.length && k < n.length) {
                val c = hay[j]
                if (c.isWhitespace()) {
                    j++
                    continue
                }
                if (c != n[k]) break
                last = j
                k++
                j++
            }
            if (k == n.length) return i..last
        }
        return null
    }

    fun endsSentence(s: String): Boolean {
        val t = dropMarked(s).trimEnd()
        return t.isNotEmpty() && t.last() in TERMINAL
    }

    /** Hängt eine Zeile an und entfernt dabei Trennstriche am Zeilenende („be-\nhaviour“ → „behaviour“). */
    fun append(sb: StringBuilder, nextRaw: String) {
        val next = nextRaw.trim()
        if (next.isEmpty()) return
        if (sb.isEmpty()) {
            sb.append(next)
            return
        }
        val last = sb[sb.length - 1]
        when {
            last == '\u00AD' -> {
                sb.setLength(sb.length - 1)
                sb.append(next)
            }
            (last == '-' || last == '\u2010') && sb.length >= 2 && sb[sb.length - 2].isLetter() && next[0].isLowerCase() -> {
                sb.setLength(sb.length - 1)
                sb.append(next)
            }
            last == '-' || last == '\u2010' || last == '/' || last == '—' -> sb.append(next)
            else -> sb.append(' ').append(next)
        }
    }

    /** Der Satz rund um eine Auswahl (für die Vokabelliste). */
    fun contextSentence(t: String, s: Int, e: Int): String {
        if (t.isEmpty()) return ""
        val bi = java.text.BreakIterator.getSentenceInstance(java.util.Locale.ENGLISH)
        bi.setText(t)
        var a = bi.preceding((s + 1).coerceIn(1, t.length))
        if (a == java.text.BreakIterator.DONE) a = 0
        var b = bi.following((e - 1).coerceIn(0, t.length - 1))
        if (b == java.text.BreakIterator.DONE) b = t.length
        var r = t.substring(a, b).trim()
        if (r.length > 320) {
            val from = maxOf(a, s - 150)
            val to = minOf(b, e + 150)
            r = "…" + t.substring(from, to).trim() + "…"
        }
        return r
    }

    fun join(a: String, b: String): String = joinWithOffset(a, b).first

    /** Fügt zwei Textstücke zusammen und sagt, an welcher Stelle das zweite beginnt. */
    fun joinWithOffset(a: String, b: String): Pair<String, Int> {
        val sb = StringBuilder(a)
        val before = sb.length
        append(sb, b)
        val added = b.trim().length
        val offset = (sb.length - added).coerceIn(0, sb.length)
        return Pair(sb.toString(), if (added == 0) before else offset)
    }

    /** Verschiebt Verweise um [offset] Zeichen (nach dem Zusammenfügen zweier Absätze). */
    fun shift(refs: List<Ref>, offset: Int, limit: Int): List<Ref> =
        refs.mapNotNull {
            val s = it.start + offset
            val e = it.end + offset
            if (s in 0..limit && e in 0..limit && e > s) it.copy(start = s, end = e) else null
        }
}

/* =====================================================================
 *  PDFBox: Zeilen mit Position + Schriftgröße sowie Bildpositionen sammeln
 * ===================================================================== */

class RawLine(
    val text: String,
    val x: Float,
    val right: Float,
    /** Grundlinie der Haupt-Schriftgröße der Zeile, von oben gemessen (PDF-Punkte) */
    val y: Float,
    /** häufigste tatsächliche Schriftgröße der Zeile (Hoch-/Tiefgestelltes zählt nicht) */
    val size: Float
) {
    val top: Float get() = y - size * 0.9f
    val bottom: Float get() = y + size * 0.3f
}

class RawImage(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

class LineStripper : PDFTextStripper() {
    companion object {
        /** Zeichen, die in einer hochgestellten Stelle vorkommen dürfen (Verweise, Ladungen). */
        const val SUP_CHARS = "*†‡§¶,;–—-\u2212+[]() "
        /** Zeichen in tiefgestellten Stellen (Formelindizes). */
        const val SUB_CHARS = "+\u2212"
    }

    val lines = ArrayList<RawLine>()
    val images = ArrayList<RawImage>()

    /** Ein Zeichen mit Position. sepBefore = PDFBox hat davor eine Worttrennung erkannt. */
    private class Glyph(
        val text: String, val x: Float, val right: Float, val y: Float, val size: Float,
        val sepBefore: Boolean, val space: Boolean
    )

    private val glyphs = ArrayList<Glyph>()
    private var sepPending = false
    private val sizeCount = HashMap<Int, Int>()
    private val sizeY = HashMap<Int, Float>()

    fun reset() {
        lines.clear()
        images.clear()
        clearLine()
    }

    private fun clearLine() {
        glyphs.clear()
        sepPending = false
        sizeCount.clear()
        sizeY.clear()
    }

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        if (textPositions.isNullOrEmpty()) return
        for (tp in textPositions) {
            val u = tp.unicode ?: continue
            if (u.isEmpty()) continue
            val space = TextUtil.isSpaceLike(u)
            val x = tp.xDirAdj
            val gs = glyphSize(tp)
            glyphs.add(Glyph(u, x, x + tp.widthDirAdj, tp.yDirAdj, gs, sepPending, space))
            sepPending = false
            if (!space) {
                val key = (gs * 4f).roundToInt()
                sizeCount[key] = (sizeCount[key] ?: 0) + 1
                if (!sizeY.containsKey(key)) sizeY[key] = tp.yDirAdj
            }
        }
    }

    /** Tatsächliche Schriftgröße inkl. aller Skalierungen (die Angabe im PDF allein ist oft 1 pt). */
    private fun glyphSize(tp: TextPosition): Float {
        val m = try { tp.textMatrix.scalingFactorY } catch (e: Exception) { 0f }
        if (m > 0.5f && m < 500f) return m
        val fs = tp.fontSizeInPt
        if (fs > 0.5f) return fs
        return max(tp.heightDir * 1.4f, 1f)
    }

    override fun writeString(text: String?) {}

    override fun writeWordSeparator() {
        sepPending = true
    }

    override fun writeLineSeparator() {
        finishLine()
    }

    // PDFBox' eigene Absatz-Erkennung wird bewusst ignoriert (siehe PageAnalyzer).
    override fun writeParagraphStart() {
        finishLine()
    }

    override fun writeParagraphEnd() {
        finishLine()
    }

    override fun writePageStart() {}

    override fun writePageEnd() {
        finishLine()
    }

    fun finishLine() {
        if (sizeCount.isNotEmpty()) {
            var best = -1
            var bestN = -1
            for ((k, cnt) in sizeCount) {
                if (cnt > bestN) {
                    bestN = cnt
                    best = k
                }
            }
            val size = best / 4f
            val text = TextUtil.clean(assemble(size, sizeY[best] ?: 0f))
            if (text.isNotEmpty()) {
                var lx = Float.MAX_VALUE
                var lr = -Float.MAX_VALUE
                for (g in glyphs) {
                    if (g.space) continue
                    if (g.x < lx) lx = g.x
                    if (g.right > lr) lr = g.right
                }
                lines.add(RawLine(text, lx, max(lr, lx + 1f), sizeY[best] ?: 0f, size))
            }
        }
        clearLine()
    }

    /**
     * Setzt die Zeile aus den Zeichen zusammen und entscheidet selbst, wo Leerzeichen hingehören.
     * Viele PDFs speichern Wortabstände nicht als Leerzeichen, sondern nur als Lücke zwischen den
     * Buchstaben – oder mit exotischen Leerzeichen-Codes. Maßstab ist hier der übliche Buchstaben-
     * abstand der Zeile selbst: Eine deutlich größere Lücke ist ein Wortabstand.
     */
    private fun assemble(size: Float, baseline: Float): String {
        val gaps = ArrayList<Float>()
        var prev: Glyph? = null
        for (g in glyphs) {
            if (g.space) {
                prev = null
                continue
            }
            val p = prev
            if (p != null && !g.sepBefore) {
                val d = g.x - p.right
                if (d > -size && d < size) gaps.add(d)
            }
            prev = g
        }
        gaps.sort()
        val median = if (gaps.isEmpty()) 0f else gaps[gaps.size / 2]
        val threshold = median + 0.13f * size
        val sb = StringBuilder()
        prev = null
        var space = false
        var run = 0 // 0 = normal, 1 = hochgestellt, 2 = tiefgestellt
        for (g in glyphs) {
            if (g.space) {
                space = true
                continue
            }
            val p = prev
            // Kleiner als die Zeile und deutlich angehoben (Fußnote, Ladung) bzw. abgesenkt (H₂O).
            // Manche PDFs heben Verweise nur an, ohne die Schrift zu verkleinern – auch das zählt.
            val small = g.size < size * 0.86f
            val kind = when {
                g.text.all { it.isDigit() || it in SUP_CHARS } &&
                    ((small && g.y < baseline - size * 0.14f) || (g.size <= size * 1.01f && g.y < baseline - size * 0.28f)) -> 1
                small && g.y > baseline + size * 0.06f && g.text.all { it.isDigit() || it in SUB_CHARS } -> 2
                else -> 0
            }
            val gap = p != null && (space || g.sepBefore || g.x - p.right > threshold || g.x < p.x - size * 0.5f)
            if (run != 0 && kind == run) {
                // Gleiche Art läuft weiter („1, 2“ oder „3–5“) – Leerzeichen gehören in die Markierung.
                if (gap) sb.append(' ')
            } else {
                if (run != 0) {
                    sb.append(if (run == 1) TextUtil.REF_CLOSE else TextUtil.SUB_CLOSE)
                    run = 0
                }
                if (gap) sb.append(' ')
                if (kind != 0) {
                    sb.append(if (kind == 1) TextUtil.REF_OPEN else TextUtil.SUB_OPEN)
                    run = kind
                }
            }
            sb.append(g.text)
            prev = g
            space = false
        }
        if (run != 0) sb.append(if (run == 1) TextUtil.REF_CLOSE else TextUtil.SUB_CLOSE)
        return sb.toString()
    }

    override fun processOperator(operator: Operator?, operands: MutableList<COSBase>?) {
        if (operator != null && operator.name == "Do" && !operands.isNullOrEmpty()) {
            val name = operands[0]
            if (name is COSName) {
                try {
                    val xo = resources?.getXObject(name)
                    if (xo is PDImageXObject) addImage(graphicsState.currentTransformationMatrix)
                } catch (e: Exception) { }
            }
        }
        super.processOperator(operator, operands)
    }

    private fun addImage(m: Matrix) {
        val a = m.scaleX
        val b = m.shearY
        val c = m.shearX
        val d = m.scaleY
        val e = m.translateX
        val f = m.translateY
        val x1 = e; val x2 = a + e; val x3 = c + e; val x4 = a + c + e
        val y1 = f; val y2 = b + f; val y3 = d + f; val y4 = b + d + f
        images.add(
            RawImage(
                min(min(x1, x2), min(x3, x4)), min(min(y1, y2), min(y3, y4)),
                max(max(x1, x2), max(x3, x4)), max(max(y1, y2), max(y3, y4))
            )
        )
    }
}

/* =====================================================================
 *  Seitenanalyse: Kopf-/Fußzeilen entfernen, Absätze, Überschriften und
 *  Fußnoten erkennen, Abbildungen finden und richtig einsortieren.
 *
 *  Ein PDF enthält keine Absätze, nur positionierte Zeilen. Ein neuer Absatz
 *  wird deshalb nur angenommen, wenn es dafür sichtbare Hinweise gibt:
 *  zusätzlicher Abstand, Einzug, eine Zeile endet früher als nötig
 *  (das nächste Wort hätte noch gepasst), andere Schriftgröße, Aufzählung.
 * ===================================================================== */

/** Ergebnis einer Seite: Textblöcke/Abbildungen im Fluss plus die Fußnoten vom Seitenende. */
class PageResult(
    val blocks: List<Block>,
    val footnotes: List<Footnote>,
    /** Die auf der Seite gedruckte Seitenzahl, falls erkannt. */
    val pageNumber: Int? = null
)

class PageAnalyzer(
    private val pdf: PageSource?,
    private val continuous: Boolean = false,
    /** true bei fotografierten Seiten: Papierränder und Schatten brauchen mehr Nachsicht. */
    private val photo: Boolean = false
) {

    private val sizeHist = HashMap<Int, Int>()
    private val recentTop = ArrayList<String>()
    private val recentBottom = ArrayList<String>()
    private val headerYs = ArrayList<Float>()
    private val footerYs = ArrayList<Float>()

    /** Bereich der aktuellen Seite, in dem wirklich Inhalt steht (ohne Kopf-/Fußzeile). */
    private var cutTop = 0f
    private var cutBottom = 1f
    private var foundPageNumber: Int? = null

    private class Para(first: RawLine, idx: Int, val firstIndented: Boolean, val bullet: Boolean) {
        val lines = ArrayList<RawLine>().apply { add(first) }
        var lastIdx = idx
        var avgSize = 0f
        var heading = false
        var note = false
        /** true, wenn in diesem Absatz wirklich eine Fußnotennummer steht. */
        var hasMarker = false
        var lastFull = false
        fun canJoin(continuous: Boolean): Boolean = !heading && !note && (continuous || (!firstIndented && !bullet))
    }

    fun process(doc: PDDocument, s: LineStripper, index: Int): PageResult {
        val page = doc.getPage(index)
        val crop = page.cropBox
        val rotated = page.rotation % 180 != 0
        val w = if (rotated) crop.height else crop.width
        val h = if (rotated) crop.width else crop.height

        s.reset()
        s.setStartPage(index + 1)
        s.setEndPage(index + 1)
        s.writeText(doc, NullWriter)
        s.finishLine()

        // Eingebettete Rasterbilder in relative Seitenkoordinaten umrechnen.
        val embedded = ArrayList<RectF>()
        if (!rotated && w > 1f && h > 1f) {
            for (im in s.images) {
                embedded.add(
                    RectF(
                        ((im.x0 - crop.lowerLeftX) / w).coerceIn(0f, 1f),
                        ((crop.upperRightY - im.y1) / h).coerceIn(0f, 1f),
                        ((im.x1 - crop.lowerLeftX) / w).coerceIn(0f, 1f),
                        ((crop.upperRightY - im.y0) / h).coerceIn(0f, 1f)
                    )
                )
            }
        }
        return analyze(mergeFragments(s.lines), embedded, w, h, index, !rotated && w > 1f && h > 1f)
    }

    /**
     * Gemeinsamer Teil für PDF und Scan: Kopf-/Fußzeilen entfernen, Absätze und Überschriften
     * bilden, Fußnoten vom Seitenende abtrennen und Quellenverweise im Text verankern.
     */
    fun analyze(
        raw: ArrayList<RawLine>, embedded: List<RectF>, w: Float, h: Float, index: Int, allowFigures: Boolean
    ): PageResult {
        val lines = removeHeaderFooter(raw, h)
        for (l in lines) {
            val k = (l.size * 2f).roundToInt()
            sizeHist[k] = (sizeHist[k] ?: 0) + l.text.length
        }
        val body = bodySize()
        val paras = buildParagraphs(lines, w, body)
        markNotes(paras, h, body)
        val notes = collectFootnotes(paras, index)
        // Nur was eine Fußnotennummer trägt, verschwindet aus dem Lesefluss. Kleingedrucktes
        // ohne Nummer (Tabellen, Bildunterschriften) bleibt lieber sichtbar.
        for (p in paras) p.note = p.note && p.hasMarker
        val markers = notes.mapNotNull { it.marker.ifEmpty { null } }.toHashSet()

        val figures = if (!allowFigures || pdf == null) emptyList<RectF>()
        else findFigures(index, lines, embedded, w, h)
        val insertAt = IntArray(figures.size) { placeFigure(figures[it], paras, h) }
        val lastBody = paras.indexOfLast { !it.note }

        val out = ArrayList<Block>()
        for (pi in 0..paras.size) {
            for (fi in figures.indices) {
                if (insertAt[fi] != pi) continue
                val rc = figures[fi]
                val aspect = (rc.width() * w / max(rc.height() * h, 1f)).coerceIn(0.1f, 10f)
                out.add(ImageBlock(index, rc.left, rc.top, rc.right, rc.bottom, aspect))
            }
            if (pi < paras.size) {
                val p = paras[pi]
                val sb = StringBuilder()
                for (l in p.lines) TextUtil.append(sb, l.text)
                val (text, found) = TextUtil.extractRefs(sb.toString().trim())
                if (text.isNotEmpty()) {
                    val refs = if (p.note || p.heading) found.filter { !it.cite }
                    else withBracketRefs(text, withPlainRefs(text, found, markers))
                    out.add(
                        TextBlock(
                            text, p.heading, pi == 0 && p.canJoin(continuous), index,
                            note = p.note, openEnd = pi == lastBody && p.lastFull, refs = refs
                        )
                    )
                }
            }
        }
        return PageResult(out, notes, foundPageNumber)
    }

    /**
     * Trennt die Absätze am Seitenende in einzelne Fußnoten auf.
     * Eine neue Fußnote beginnt bei einer Zeile, die mit einer Nummer oder einem Fußnotenzeichen anfängt.
     */
    private fun collectFootnotes(paras: List<Para>, index: Int): List<Footnote> {
        val out = ArrayList<Footnote>()
        for (p in paras) {
            if (!p.note) continue
            var marker = ""
            val sb = StringBuilder()
            // Erst sammeln: Nur wenn der Absatz wirklich eine Fußnotennummer enthält, zählt er
            // als Quelle – sonst bliebe er doppelt (im Text und in der Quellenliste) stehen.
            val local = ArrayList<Footnote>()
            fun flush() {
                val (t, _) = TextUtil.extractRefs(sb.toString().trim())
                if (t.isNotEmpty()) local.add(Footnote(index, marker, t))
                sb.setLength(0)
            }
            for ((li, l) in p.lines.withIndex()) {
                val lead = TextUtil.leadingMarker(l.text)
                if (lead != null && (li == 0 || sb.isNotEmpty())) {
                    if (li > 0) flush()
                    marker = lead.first
                    if (marker.isNotEmpty()) p.hasMarker = true
                    sb.append(lead.second)
                } else {
                    TextUtil.append(sb, l.text)
                }
            }
            flush()
            if (p.hasMarker) out.addAll(local)
        }
        return out
    }

    /**
     * Zitate im Fließtext wie „[12]“, „[3, 4]“ oder „[5–7]“ antippbar machen.
     * Steht so eine Klammer ganz am Absatzanfang, ist es ein Eintrag der Literaturliste selbst.
     */
    private fun withBracketRefs(text: String, found: List<Ref>): List<Ref> {
        if (text.indexOf('[') < 0) return found
        val extra = ArrayList<Ref>()
        for (m in BRACKET_REF.findAll(text)) {
            val a = m.range.first
            val b = m.range.last + 1
            if (a == 0) continue
            if (found.any { it.start < b && a < it.end }) continue
            extra.add(Ref(a, b, m.value, Ref.KIND_INLINE))
        }
        if (extra.isEmpty()) return found
        return (found + extra).sortedBy { it.start }
    }

    /**
     * Ergänzt Verweise, die nicht hochgestellt gesetzt sind: eine Zahl, die ohne Leerzeichen
     * an einem Wort klebt und zu einer Fußnote dieser Seite passt.
     */
    private fun withPlainRefs(text: String, found: List<Ref>, markers: Set<String>): List<Ref> {
        if (markers.isEmpty()) return found
        val extra = ArrayList<Ref>()
        for (m in PLAIN_REF.findAll(text)) {
            val g = m.groups[1] ?: continue
            val marker = g.value
            if (marker !in markers) continue
            if (found.any { it.start < g.range.last + 1 && g.range.first < it.end }) continue
            extra.add(Ref(g.range.first, g.range.last + 1, marker))
        }
        if (extra.isEmpty()) return found
        return (found + extra).sortedBy { it.start }
    }

    private fun bodySize(): Float {
        var bestK = -1
        var bestV = -1
        for ((k, v) in sizeHist) {
            if (v > bestV) {
                bestV = v
                bestK = k
            }
        }
        return if (bestK < 0) 10f else bestK / 2f
    }

    /**
     * PDFBox zerlegt eine sichtbare Zeile manchmal in Stücke – typisch bei hoch-/tiefgestellten
     * Zeichen (CO₂, x², Fußnotenziffern). Solche Stücke werden wieder zu einer Zeile verbunden.
     */
    /** Ist [piece] gegenüber der Zeile [line] klein und angehoben – also eine Fußnotennummer? */
    private fun isRaisedPiece(piece: RawLine, line: RawLine): Boolean {
        val t = TextUtil.stripRefs(piece.text)
        if (t.isEmpty() || t.length > 8 || t.none { it.isDigit() || it in "*†‡§¶" }) return false
        if (!t.all { it.isDigit() || it in LineStripper.SUP_CHARS }) return false
        if (piece.text.indexOf(TextUtil.REF_OPEN) >= 0) return false
        val raised = piece.y < line.y - line.size * 0.14f
        return raised && (piece.size < line.size * 0.86f || piece.y < line.y - line.size * 0.28f)
    }

    private fun wrapSup(t: String): String = "${TextUtil.REF_OPEN}${TextUtil.stripRefs(t)}${TextUtil.REF_CLOSE}"

    private fun mergeFragments(input: List<RawLine>): ArrayList<RawLine> {
        val out = ArrayList<RawLine>()
        for (l in input) {
            val a = out.lastOrNull()
            if (a != null) {
                val big = max(a.size, l.size)
                val sameLine = abs(l.y - a.y) < big * 0.55f
                val gap = l.x - a.right
                if (sameLine && gap > -big * 0.3f && gap < big * 1.0f) {
                    val sep = if (gap < big * 0.18f) "" else " "
                    val main = if (a.text.length >= l.text.length) a else l
                    // Hat PDFBox eine hochgestellte Zahl als eigenes Stück abgetrennt, wäre die
                    // Hochstellung sonst verloren – hier wird sie wieder als solche markiert.
                    val at = if (main !== a && isRaisedPiece(a, l)) wrapSup(a.text) else a.text
                    val lt = if (main !== l && isRaisedPiece(l, a)) wrapSup(l.text) else l.text
                    out[out.size - 1] = RawLine(at + sep + lt, min(a.x, l.x), max(a.right, l.right), main.y, main.size)
                    continue
                }
            }
            out.add(l)
        }
        return out
    }

    /** Kopf-/Fußzeilen: Seitenzahlen, wiederkehrende Texte oder Zeilen an der bekannten Kopfzeilen-Position. */
    private fun removeHeaderFooter(lines: ArrayList<RawLine>, h: Float): ArrayList<RawLine> {
        cutTop = 0f
        cutBottom = 1f
        foundPageNumber = null
        if (lines.isEmpty()) return lines
        val byY = lines.sortedBy { it.y }
        val top = byY.first()
        val bottom = byY.last()
        val topGap = if (byY.size > 1) byY[1].y - top.y else Float.MAX_VALUE
        val botGap = if (byY.size > 1) bottom.y - byY[byY.size - 2].y else Float.MAX_VALUE

        val topKey = if (top.y < h * 0.12f && top.text.length < 150) norm(top.text) else null
        if (topKey != null) {
            val atKnownY = headerYs.any { abs(it - top.y) < 2f }
            val isNum = PAGE_NUM.matches(TextUtil.stripRefs(top.text))
            if (isNum || recentTop.contains(topKey) || (atKnownY && topGap > top.size * 1.6f)) {
                lines.remove(top)
                rememberY(headerYs, top.y)
                cutTop = max(cutTop, (top.bottom / h + 0.004f).coerceIn(0f, 0.3f))
                if (isNum) foundPageNumber = parsePageNumber(top.text)
            }
        }
        val botKey = if (bottom !== top && bottom.y > h * 0.88f && bottom.text.length < 150) norm(bottom.text) else null
        if (botKey != null) {
            val atKnownY = footerYs.any { abs(it - bottom.y) < 2f }
            val isNum = PAGE_NUM.matches(TextUtil.stripRefs(bottom.text))
            if (isNum || recentBottom.contains(botKey) || (atKnownY && botGap > bottom.size * 1.6f)) {
                lines.remove(bottom)
                rememberY(footerYs, bottom.y)
                cutBottom = min(cutBottom, (bottom.top / h - 0.004f).coerceIn(0.7f, 1f))
                // Die Seitenzahl unten ist verlässlicher als eine oben in der Kopfzeile.
                if (isNum) parsePageNumber(bottom.text)?.let { foundPageNumber = it }
            }
        }
        remember(recentTop, topKey)
        remember(recentBottom, botKey)
        return lines
    }

    /** Liest die gedruckte Seitenzahl – arabisch („12“, „Seite 12“) oder römisch („xiv“). */
    private fun parsePageNumber(raw: String): Int? {
        val t = TextUtil.stripRefs(raw).trim().trim('-', '–', '—', '|', '•', '[', ']', '(', ')', ' ')
        ARABIC.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..9999) return n
        }
        val roman = t.lowercase(Locale.ROOT).filter { it in "ivxlcdm" }
        if (roman.isNotEmpty() && roman.length == t.count { it.isLetter() }) {
            val n = romanToInt(roman)
            if (n in 1..3999) return n
        }
        return null
    }

    private fun remember(list: ArrayList<String>, key: String?) {
        if (key == null) return
        list.add(key)
        while (list.size > 4) list.removeAt(0)
    }

    private fun rememberY(list: ArrayList<Float>, y: Float) {
        if (list.any { abs(it - y) < 2f }) return
        list.add(y)
        while (list.size > 6) list.removeAt(0)
    }

    private fun isHyphenEnd(t: String): Boolean =
        t.endsWith("-") || t.endsWith("\u00AD") || t.endsWith("\u2010") || t.endsWith("—")

    private fun buildParagraphs(lines: List<RawLine>, w: Float, body: Float): List<Para> {
        val n = lines.size
        if (n == 0) return emptyList()

        // Üblicher Zeilenabstand auf dieser Seite
        val gaps = ArrayList<Float>()
        for (k in 1 until n) {
            val d = lines[k].y - lines[k - 1].y
            val sz = lines[k].size
            if (d > 0.5f * sz && d < 3f * sz) gaps.add(d)
        }
        gaps.sort()
        val lineGap = if (gaps.isEmpty()) 0f else gaps[gaps.size / 2]

        // Linke/rechte Kante der Spalte, in der die jeweilige Zeile steht.
        val colL = FloatArray(n)
        val colR = FloatArray(n)
        val reliable = BooleanArray(n)
        for (k in 0 until n) {
            val xs = ArrayList<Float>()
            val rs = ArrayList<Float>()
            for (o in lines) {
                if (abs(o.x - lines[k].x) < w * 0.12f) {
                    xs.add(o.x)
                    rs.add(o.right)
                }
            }
            xs.sort()
            rs.sort()
            colL[k] = xs[xs.size / 10]
            colR[k] = rs[min(rs.size - 1, rs.size * 9 / 10)]
            reliable[k] = xs.size >= 4 && colR[k] - colL[k] > w * 0.2f
        }

        fun charW(l: RawLine): Float = if (l.text.isNotEmpty()) (l.right - l.x) / l.text.length else l.size * 0.5f

        /** Hätte das erste Wort von b noch in die Zeile a gepasst? Dann wurde dort absichtlich umbrochen. */
        fun fits(ka: Int, a: RawLine, b: RawLine): Boolean {
            if (!reliable[ka]) return false
            val firstWord = b.text.substringBefore(' ')
            return colR[ka] - a.right > charW(b) * (firstWord.length + 1)
        }

        /** Reicht die Zeile bis zum rechten Rand (bzw. endet mit Trennstrich)? */
        fun isFull(k: Int): Boolean {
            val l = lines[k]
            if (isHyphenEnd(l.text)) return true
            if (!reliable[k]) return false
            return colR[k] - l.right < charW(l) * 3f
        }

        val paras = ArrayList<Para>()
        var cur: Para? = null
        for (k in 0 until n) {
            val b = lines[k]
            val indentAmt = b.x - colL[k]
            val indented = reliable[k] && indentAmt > b.size * 0.8f && indentAmt < w * 0.12f
            val bullet = BULLET.containsMatchIn(b.text)
            val bracketEntry = BRACKET_START.containsMatchIn(b.text)
            var brk = k == 0
            if (!brk) {
                val a = lines[k - 1]
                // Endet die Zeile mit Trennstrich/Gedankenstrich, geht der Absatz immer weiter.
                if (!isHyphenEnd(a.text)) {
                    val dy = b.y - a.y
                    val aEnds = TextUtil.endsSentence(a.text)
                    val aListEnd = aEnds || a.text.endsWith(";") || a.text.endsWith(",")
                    val c = b.text.firstOrNull()
                    val bUpper = c != null && (c.isUpperCase() || c.isDigit())
                    val bLower = c != null && c.isLowerCase()
                    val fit = fits(k - 1, a, b)
                    val sizeJump = abs(b.size - a.size) > 0.15f * max(a.size, b.size)
                    val bigGap = lineGap > 0f && dy > lineGap * 1.4f
                    // Ist die vorige Zeile selbst eine eingerückte erste Zeile (einzeiliger Absatz, typisch
                    // für Dialoge), zählt der Einzug trotzdem – nur bei Blockzitaten (alle Zeilen gleich
                    // eingerückt) muss die Zeile davor sichtbar kürzer sein.
                    val sameIndent = abs(a.x - b.x) < b.size * 0.3f
                    val aIsParaStart = cur?.lines?.size == 1
                    val indentBreak = indented && (if (sameIndent && !aIsParaStart) fit else (aEnds || fit))
                    brk = if (continuous) {
                        sizeJump || (bigGap && aEnds && dy > lineGap * 2.2f)
                    } else {
                        sizeJump ||
                            (bigGap && (aEnds || fit || !bLower)) ||
                            (fit && (aEnds || bUpper)) ||
                            indentBreak ||
                            (bullet && (aListEnd || fit)) ||
                            // „[12] …“ beginnt nur dann einen neuen Absatz, wenn die Zeile davor früh
                            // endet oder selbst ein Eintrag ist – im Fließtext ist es bloß ein Zitat.
                            (bracketEntry && (fit || a.text.startsWith("[")))
                    }
                }
            }
            val c0 = cur
            if (brk || c0 == null) {
                val np = Para(b, k, indented, bullet)
                paras.add(np)
                cur = np
            } else {
                c0.lines.add(b)
                c0.lastIdx = k
            }
        }
        for (p in paras) {
            var sum = 0f
            var len = 0
            for (l in p.lines) {
                sum += l.size * l.text.length
                len += l.text.length
            }
            p.avgSize = if (len > 0) sum / len else body
            p.heading = p.avgSize > body * 1.18f && len < 250
            p.lastFull = isFull(p.lastIdx)
        }
        return paras
    }

    /** Kleingedrucktes am Seitenende (Fußnoten, Bildunterschriften) markieren. */
    private fun markNotes(paras: List<Para>, h: Float, body: Float) {
        if (paras.none { it.avgSize >= body * 0.95f }) return
        for (i in paras.indices.reversed()) {
            val p = paras[i]
            if (p.avgSize < body * 0.88f && p.lines[0].top > h * 0.5f) {
                p.note = true
                p.heading = false
            } else {
                break
            }
        }
    }

    /** Index des Absatzes, vor dem die Abbildung eingefügt wird. */
    private fun placeFigure(f: RectF, paras: List<Para>, h: Float): Int {
        for ((pi, p) in paras.withIndex()) {
            for ((li, l) in p.lines.withIndex()) {
                if (l.top / h >= f.bottom - 0.01f) return if (li == 0) pi else pi + 1
            }
        }
        return paras.size
    }

    /**
     * Findet Abbildungen: (1) eingebettete Rasterbilder, (2) textfreie Bereiche,
     * in denen tatsächlich etwas gezeichnet ist (Vektorgrafiken, Formeln, Diagramme).
     */
    private fun findFigures(
        index: Int, lines: List<RawLine>, embedded: List<RectF>, w: Float, h: Float
    ): List<RectF> {
        val cand = ArrayList<RectF>()
        for (rc in embedded) {
            if (rc.width() < 0.08f || rc.height() < 0.04f || rc.width() * rc.height() < 0.015f) continue
            // Hintergrundbilder hinter Fließtext ignorieren.
            val inside = lines.count { rc.contains((it.x + it.right) / 2f / w, (it.y - it.size * 0.35f) / h) }
            if (inside > 2) continue
            cand.add(RectF(rc))
        }
        if (lines.isNotEmpty()) {
            val sorted = lines.sortedBy { it.y }
            val minX = (sorted.minOf { it.x } / w - 0.02f).coerceAtLeast(0f)
            val maxX = (sorted.maxOf { it.right } / w + 0.02f).coerceAtMost(1f)
            val first = sorted.first()
            val last = sorted.last()
            // Oberhalb/unterhalb nur bis zur Kopf- bzw. Fußzeile suchen – sonst wird die
            // herausgefilterte Seitenzahl fälschlich als Abbildung erkannt.
            val upper = max(0.03f, cutTop)
            val lower = min(0.97f, cutBottom)
            if (first.top / h - upper > 0.15f) cand.add(RectF(minX, upper, maxX, first.top / h - 0.005f))
            for (k in 1 until sorted.size) {
                val g0 = sorted[k - 1].bottom / h
                val g1 = sorted[k].top / h
                if (g1 - g0 > 0.08f) cand.add(RectF(minX, g0 + 0.003f, maxX, g1 - 0.003f))
            }
            if (lower - last.bottom / h > 0.15f) cand.add(RectF(minX, last.bottom / h + 0.005f, maxX, lower))
        } else {
            cand.add(RectF(0f, 0f, 1f, 1f)) // Seite ohne Text (Scan, Bildseite)
        }
        val out = ArrayList<RectF>()
        for (rc in mergeRects(cand)) {
            if (rc.width() <= 0.01f || rc.height() <= 0.01f) continue
            val t = pdf?.trimRegion(index, rc) ?: continue
            if (t.width() <= 0.05f || t.height() <= 0.03f) continue
            if (photo) {
                // Auf Fotos sind Papierkanten und Schatten keine Abbildungen.
                var touches = 0
                if (t.left < 0.025f) touches++
                if (t.top < 0.025f) touches++
                if (t.right > 0.975f) touches++
                if (t.bottom > 0.975f) touches++
                if (touches >= 2 || t.height() < 0.07f || t.width() < 0.12f) continue
            }
            out.add(t)
        }
        val merged = mergeRects(out).sortedBy { it.top }
        // Auf Fotos lieber eine Abbildung übersehen als Flecken und Falten einzubauen.
        return if (photo) merged.filter { it.width() * it.height() >= 0.04f }.take(3) else merged
    }

    private fun mergeRects(list: List<RectF>): List<RectF> {
        val rs = list.map { RectF(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (a in 0 until rs.size) {
                for (b in a + 1 until rs.size) {
                    if (RectF.intersects(rs[a], rs[b])) {
                        rs[a].union(rs[b])
                        rs.removeAt(b)
                        changed = true
                        break@loop
                    }
                }
            }
        }
        return rs
    }

    companion object {
        private val ARABIC = Regex("""(?:^|\s)(?:page|seite|s\.)?\s*(\d{1,4})(?:\s*(?:of|von|/)\s*\d{1,4})?$""", RegexOption.IGNORE_CASE)

        /** Römische Zahl in einen Wert umrechnen (Vorworte sind oft so nummeriert). */
        fun romanToInt(s: String): Int {
            val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
            var total = 0
            var prev = 0
            for (c in s.reversed()) {
                val n = v[c] ?: return 0
                total += if (n < prev) -n else n
                if (n > prev) prev = n
            }
            return total
        }

        /** Zahl, die ohne Leerzeichen an einem Wort oder Satzzeichen klebt – möglicher Quellenverweis. */
        val PLAIN_REF = Regex("""(?<=[\p{L}\.,;:!?\)\]"”’»])(\d{1,3})(?![\d\p{L}])""")
        /** Zitat im Fließtext: [12], [3, 4], [5–7] */
        val BRACKET_REF = Regex("""\[\d{1,4}(?:\s*[,;–—-]\s*\d{1,4}){0,12}\]""")
        val BULLET = Regex("""^([•●▪■◦‣∙·*]|[-–—]\s|\(?\d{1,3}[.)]\s|\(?[a-z][.)]\s)""")
        /** Eintrag einer Literaturliste: „[12] Autor …“ */
        val BRACKET_START = Regex("""^\[\d{1,4}]\s?""")
        val PAGE_NUM = Regex(
            """^[\s\-–—|•]*((page|seite|s\.)\s*)?(\d{1,4}|[ivxlcdm]{1,6})(\s*(of|von|/)\s*\d{1,4})?[\s\-–—|•]*$""",
            RegexOption.IGNORE_CASE
        )
        private val DIGITS = Regex("\\d+")
        private val SPACES = Regex("\\s+")
        fun norm(t: String): String = SPACES.replace(DIGITS.replace(t.lowercase(), "#"), " ").trim()
    }
}
