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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* =====================================================================
 *  Buchinhalt: wächst Seite für Seite, während im Hintergrund extrahiert wird.
 * ===================================================================== */

class BookContent(val id: String) {
    private val blocks = ArrayList<Block>()

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
    fun addPage(page: List<Block>, notify: Boolean = true) {
        appendMerged(blocks, page, continuous)
        for (b in page) if (b is TextBlock) textChars += b.text.length
        pagesDone++
        if (notify) bump()
    }

    @Synchronized
    fun snapshot(): List<Block> = ArrayList(blocks)

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
                (continuous || last.openEnd || !TextUtil.endsSentence(last.text))
            ) {
                val cap = if (continuous) 4000 else 8000
                if (last.text.length + first.text.length < cap) {
                    blocks[li] = last.copy(text = TextUtil.join(last.text, first.text), openEnd = first.openEnd)
                    for (i in page.indices) if (i != ft) blocks.add(page[i])
                    return
                }
                // Zu lang für einen Block: am letzten Satzende trennen, damit kein Satz zerrissen wird.
                if (!TextUtil.endsSentence(last.text)) {
                    val cut = TextUtil.lastSentenceEnd(last.text, last.text.length / 2)
                    if (cut > 0) {
                        blocks[li] = last.copy(text = last.text.substring(0, cut).trim(), openEnd = false)
                        val carried = first.copy(
                            text = TextUtil.join(last.text.substring(cut).trim(), first.text),
                            joinPrev = false
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
    const val FORMAT = 3

    fun pageLine(page: Int, blocks: List<Block>): String {
        val arr = JSONArray()
        for (b in blocks) {
            when (b) {
                is TextBlock -> arr.put(
                    JSONObject().put("t", b.text).put("h", b.heading).put("j", b.joinPrev)
                        .put("n", b.note).put("o", b.openEnd)
                )
                is ImageBlock -> arr.put(
                    JSONObject()
                        .put("i", JSONArray().put(b.l.toDouble()).put(b.t.toDouble()).put(b.r.toDouble()).put(b.b.toDouble()))
                        .put("a", b.aspect.toDouble())
                )
            }
        }
        return JSONObject().put("p", page).put("b", arr).toString()
    }

    private fun parseBlocks(arr: JSONArray?, page: Int): List<Block> {
        if (arr == null) return emptyList()
        val out = ArrayList<Block>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("t")) {
                out.add(
                    TextBlock(
                        o.getString("t"), o.optBoolean("h"), o.optBoolean("j"), page,
                        o.optBoolean("n"), o.optBoolean("o")
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
        if (version != FORMAT) {
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
                        c.addPage(parseBlocks(o.optJSONArray("b"), expected), notify = false)
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
        c.totalPages = total
        c.toc = toc
        c.finished = done && total > 0 && expected >= total
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
                val blocks = try {
                    analyzer.process(d, stripper, i)
                } catch (e: Throwable) {
                    emptyList()
                }
                w.write(ContentStore.pageLine(i, blocks))
                w.write("\n")
                w.flush()
                c.addPage(blocks)
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
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val l = ligatures[ch]
            when {
                l != null -> sb.append(l)
                ch == '\u0000' || ch == '\uFFFD' || ch == '\u200B' || ch == '\u2060' || ch == '\uFEFF' -> Unit
                ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return multiSpace.replace(sb, " ").trim()
    }

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
        val t = s.trimEnd()
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

    fun join(a: String, b: String): String {
        val sb = StringBuilder(a)
        append(sb, b)
        return sb.toString()
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
    val lines = ArrayList<RawLine>()
    val images = ArrayList<RawImage>()

    /** Ein Zeichen mit Position. sepBefore = PDFBox hat davor eine Worttrennung erkannt. */
    private class Glyph(val text: String, val x: Float, val right: Float, val sepBefore: Boolean, val space: Boolean)

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
            glyphs.add(Glyph(u, x, x + tp.widthDirAdj, sepPending, space))
            sepPending = false
            if (!space) {
                val key = (glyphSize(tp) * 4f).roundToInt()
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
            val text = TextUtil.clean(assemble(size))
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
    private fun assemble(size: Float): String {
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
        for (g in glyphs) {
            if (g.space) {
                space = true
                continue
            }
            val p = prev
            if (p != null && (space || g.sepBefore || g.x - p.right > threshold || g.x < p.x - size * 0.5f)) {
                sb.append(' ')
            }
            sb.append(g.text)
            prev = g
            space = false
        }
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

class PageAnalyzer(private val pdf: PdfPages?, private val continuous: Boolean = false) {

    private val sizeHist = HashMap<Int, Int>()
    private val recentTop = ArrayList<String>()
    private val recentBottom = ArrayList<String>()
    private val headerYs = ArrayList<Float>()
    private val footerYs = ArrayList<Float>()

    private class Para(first: RawLine, idx: Int, val firstIndented: Boolean, val bullet: Boolean) {
        val lines = ArrayList<RawLine>().apply { add(first) }
        var lastIdx = idx
        var avgSize = 0f
        var heading = false
        var note = false
        var lastFull = false
        fun canJoin(continuous: Boolean): Boolean = !heading && !note && (continuous || (!firstIndented && !bullet))
    }

    fun process(doc: PDDocument, s: LineStripper, index: Int): List<Block> {
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

        val lines = removeHeaderFooter(mergeFragments(s.lines), h)
        for (l in lines) {
            val k = (l.size * 2f).roundToInt()
            sizeHist[k] = (sizeHist[k] ?: 0) + l.text.length
        }
        val body = bodySize()
        val paras = buildParagraphs(lines, w, body)
        markNotes(paras, h, body)
        val figures = if (rotated || w <= 1f || h <= 1f || pdf == null) emptyList()
        else findFigures(index, lines, s.images, crop, w, h)
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
                val text = sb.toString().trim()
                if (text.isNotEmpty()) {
                    out.add(
                        TextBlock(
                            text, p.heading, pi == 0 && p.canJoin(continuous), index,
                            note = p.note, openEnd = pi == lastBody && p.lastFull
                        )
                    )
                }
            }
        }
        return out
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
                    out[out.size - 1] = RawLine(a.text + sep + l.text, min(a.x, l.x), max(a.right, l.right), main.y, main.size)
                    continue
                }
            }
            out.add(l)
        }
        return out
    }

    /** Kopf-/Fußzeilen: Seitenzahlen, wiederkehrende Texte oder Zeilen an der bekannten Kopfzeilen-Position. */
    private fun removeHeaderFooter(lines: ArrayList<RawLine>, h: Float): ArrayList<RawLine> {
        if (lines.isEmpty()) return lines
        val byY = lines.sortedBy { it.y }
        val top = byY.first()
        val bottom = byY.last()
        val topGap = if (byY.size > 1) byY[1].y - top.y else Float.MAX_VALUE
        val botGap = if (byY.size > 1) bottom.y - byY[byY.size - 2].y else Float.MAX_VALUE

        val topKey = if (top.y < h * 0.12f && top.text.length < 150) norm(top.text) else null
        if (topKey != null) {
            val atKnownY = headerYs.any { abs(it - top.y) < 2f }
            if (PAGE_NUM.matches(top.text) || recentTop.contains(topKey) || (atKnownY && topGap > top.size * 1.6f)) {
                lines.remove(top)
                rememberY(headerYs, top.y)
            }
        }
        val botKey = if (bottom !== top && bottom.y > h * 0.88f && bottom.text.length < 150) norm(bottom.text) else null
        if (botKey != null) {
            val atKnownY = footerYs.any { abs(it - bottom.y) < 2f }
            if (PAGE_NUM.matches(bottom.text) || recentBottom.contains(botKey) || (atKnownY && botGap > bottom.size * 1.6f)) {
                lines.remove(bottom)
                rememberY(footerYs, bottom.y)
            }
        }
        remember(recentTop, topKey)
        remember(recentBottom, botKey)
        return lines
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
                            (bullet && (aListEnd || fit))
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
        index: Int, lines: List<RawLine>, imgs: List<RawImage>, crop: PDRectangle, w: Float, h: Float
    ): List<RectF> {
        val cand = ArrayList<RectF>()
        for (im in imgs) {
            val l = (im.x0 - crop.lowerLeftX) / w
            val r = (im.x1 - crop.lowerLeftX) / w
            val t = (crop.upperRightY - im.y1) / h
            val b = (crop.upperRightY - im.y0) / h
            val rc = RectF(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
            if (rc.width() < 0.08f || rc.height() < 0.04f || rc.width() * rc.height() < 0.015f) continue
            // Hintergrundbilder hinter Fließtext ignorieren.
            val inside = lines.count { rc.contains((it.x + it.right) / 2f / w, (it.y - it.size * 0.35f) / h) }
            if (inside > 2) continue
            cand.add(rc)
        }
        if (lines.isNotEmpty()) {
            val sorted = lines.sortedBy { it.y }
            val minX = (sorted.minOf { it.x } / w - 0.02f).coerceAtLeast(0f)
            val maxX = (sorted.maxOf { it.right } / w + 0.02f).coerceAtMost(1f)
            val first = sorted.first()
            val last = sorted.last()
            if (first.top / h > 0.18f) cand.add(RectF(minX, 0.03f, maxX, first.top / h - 0.005f))
            for (k in 1 until sorted.size) {
                val g0 = sorted[k - 1].bottom / h
                val g1 = sorted[k].top / h
                if (g1 - g0 > 0.08f) cand.add(RectF(minX, g0 + 0.003f, maxX, g1 - 0.003f))
            }
            if (1f - last.bottom / h > 0.18f) cand.add(RectF(minX, last.bottom / h + 0.005f, maxX, 0.97f))
        } else {
            cand.add(RectF(0f, 0f, 1f, 1f)) // Seite ohne Text (Scan, Bildseite)
        }
        val out = ArrayList<RectF>()
        for (rc in mergeRects(cand)) {
            if (rc.width() <= 0.01f || rc.height() <= 0.01f) continue
            val t = pdf?.trimRegion(index, rc) ?: continue
            if (t.width() > 0.05f && t.height() > 0.03f) out.add(t)
        }
        return mergeRects(out).sortedBy { it.top }
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
        val BULLET = Regex("""^([•●▪■◦‣∙·*]|[-–—]\s|\(?\d{1,3}[.)]\s|\(?[a-z][.)]\s)""")
        val PAGE_NUM = Regex(
            """^[\s\-–—|•]*((page|seite|s\.)\s*)?(\d{1,4}|[ivxlcdm]{1,6})(\s*(of|von|/)\s*\d{1,4})?[\s\-–—|•]*$""",
            RegexOption.IGNORE_CASE
        )
        private val DIGITS = Regex("\\d+")
        private val SPACES = Regex("\\s+")
        fun norm(t: String): String = SPACES.replace(DIGITS.replace(t.lowercase(), "#"), " ").trim()
    }
}
