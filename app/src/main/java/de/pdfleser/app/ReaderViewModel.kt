package de.pdfleser.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/** Alles, was die Nachschlage-Karte anzeigt. */
data class LookupState(
    val sel: Selection,
    val transLoading: Boolean = true,
    val translation: Translation? = null,
    val transError: String? = null,
    val defWanted: Boolean = false,
    val defLoading: Boolean = false,
    val definition: DictEntry? = null,
    val defError: String? = null,
    val saved: Boolean = false,
    val highlight: Highlight? = null
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    var bookId: String? = null
        private set
    var book: BookEntry? = null
        private set
    var content: BookContent? = null
        private set
    var pdf: PdfPages? = null
        private set

    var settings: ReaderSettings = ReaderSettings.load(app)
    var pos = Pos(0, 0)
    var origPage = 0
    var mode = 0            // 0 = Lesemodus (Fließtext), 1 = Original-PDF
    var autoSwitched = false
    /** PDF-Seite, an die nach einer Neu-Aufbereitung gesprungen werden soll (-1 = keine). */
    var resumePdfPage = -1
    var panelOpen = false
    private var lang = ""

    /** Eigener Thread zum Rendern von Bildern, damit der Hauptthread nie blockiert. */
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val renderDispatcher = renderExecutor.asCoroutineDispatcher()

    val bitmaps: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    val pageSet = MutableStateFlow(PageSet.EMPTY)
    val ready = MutableStateFlow(false)
    val progress = MutableStateFlow(0)
    val lookup = MutableStateFlow<LookupState?>(null)
    val annotationsVersion = MutableStateFlow(0)
    var annotations: Annotations? = null
        private set
    val readAloud = ReadAloud(app)

    /** Einzelner Thread für Schreibzugriffe (Reihenfolge bleibt erhalten). */
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val ioSerial = ioExecutor.asCoroutineDispatcher()

    private var baseSpec: LayoutSpec? = null
    private var pagJob: Job? = null
    private var pagCount = 0
    private var pendingIncremental = false
    private var lookupJob: Job? = null

    init {
        readAloud.bind({ pageSet.value.blocks }, { content?.let { it.finished || it.error != null } ?: true })
    }

    fun open(id: String) {
        if (bookId != null) return
        bookId = id
        val app = getApplication<Application>()
        book = Library.get(app, id)
        val sp = Prefs.sp(app)
        pos = Pos.parse(sp.getString("pos_$id", null))
        origPage = sp.getInt("orig_$id", 0)
        mode = sp.getInt("mode_$id", 0)
        autoSwitched = sp.getBoolean("auto_$id", false)
        lang = sp.getString("lang_$id", null).orEmpty()
        // Nach einem Wechsel des Absatz-Modus: an dieselbe PDF-Seite zurückspringen.
        val resume = sp.getInt("resume_$id", -1)
        if (resume >= 0) {
            resumePdfPage = resume
            pos = Pos(0, 0)
            sp.edit().remove("resume_$id").apply()
        }
        viewModelScope.launch {
            val c = withContext(Dispatchers.IO) { ContentManager.get(app, id) }
            val p = withContext(Dispatchers.IO) {
                try { PdfPages(Library.pdfFile(app, id)) } catch (e: Exception) { null }
            }
            annotations = withContext(Dispatchers.IO) { AnnotationStore.load(app, id) }
            if (c.formatReset) {
                c.formatReset = false
                val pp = sp.getInt("ppage_$id", -1)
                if (pp >= 0) {
                    resumePdfPage = pp
                    pos = Pos(0, 0)
                }
            }
            content = c
            pdf = p
            ready.value = true
            c.version.collect {
                progress.value = it
                requestPagination(false)
            }
        }
    }

    fun setSpec(base: LayoutSpec) {
        if (base == baseSpec) return
        baseSpec = base
        requestPagination(true)
    }

    private fun requestPagination(reset: Boolean) {
        val c = content ?: return
        val base = baseSpec ?: return
        if (!reset && pagJob?.isActive == true) {
            pendingIncremental = true
            return
        }
        pagJob?.cancel()
        pendingIncremental = false
        val prev = if (reset) PageSet.EMPTY else pageSet.value
        val prevCount = if (reset) 0 else pagCount

        pagJob = viewModelScope.launch {
            val done = c.finished || c.error != null
            val blocks = c.snapshot()
            if (lang.isEmpty()) {
                val (l, n) = detectLang(blocks)
                if (n >= 3000 || done) {
                    lang = l
                    Prefs.sp(getApplication<Application>()).edit().putString("lang_$bookId", l).apply()
                }
            }
            val spec = base.copy(lang = lang.ifEmpty { "en" })
            val specChanged = prev.spec != null && prev.spec != spec
            val (keep, from) = if (reset || specChanged) Pair(0, Pos(0, 0)) else resumePoint(prev.pages, prevCount)
            val full = keep == 0

            // Schon einmal mit genau diesen Einstellungen umbrochen? Dann aus dem Cache laden.
            if (full && done) {
                val cached = withContext(Dispatchers.IO) { loadPageCache(spec, blocks.size) }
                if (cached != null) {
                    pagCount = blocks.size
                    pageSet.value = PageSet(cached, blocks, spec, true)
                    verifyAnnotations(blocks, true)
                    if (pendingIncremental) {
                        pendingIncremental = false
                        requestPagination(false)
                    }
                    return@launch
                }
            }

            val result = withContext(Dispatchers.Default) {
                val out = ArrayList<Page>(prev.pages.subList(0, keep))
                var lastPub = 0L
                var published = false
                Paginator(LayoutFactory(spec)).paginate(blocks, from, out, check = { ensureActive() }) {
                    // Bei kompletter Neuberechnung Zwischenstände zeigen, damit die Seite sofort da ist.
                    if (full && isActive) {
                        val now = SystemClock.uptimeMillis()
                        if ((!published && out.size >= 2) || now - lastPub > 150) {
                            published = true
                            lastPub = now
                            pageSet.value = PageSet(ArrayList(out), blocks, spec, false)
                        }
                    }
                }
                out
            }
            pagCount = blocks.size
            pageSet.value = PageSet(result, blocks, spec, done)
            verifyAnnotations(blocks, done)
            if (done) {
                val count = blocks.size
                viewModelScope.launch(Dispatchers.IO) { savePageCache(spec, count, result) }
            }
            if (pendingIncremental) {
                pendingIncremental = false
                requestPagination(false)
            }
        }
    }

    /** Ab welcher Seite muss nach neuen Blöcken neu umbrochen werden? */
    private fun resumePoint(pages: List<Page>, prevCount: Int): Pair<Int, Pos> {
        if (pages.isEmpty() || prevCount <= 0) return Pair(0, Pos(0, 0))
        val changed = prevCount - 1
        var first = pages.size
        for (k in pages.indices.reversed()) {
            if (pages[k].frags.any { it.block >= changed }) first = k else break
        }
        if (first >= pages.size) first = pages.size - 1
        return Pair(first, pages[first].start)
    }

    /* ---------- Seiten-Cache (eine Datei pro Buch und Darstellung) ---------- */

    private fun cacheKey(spec: LayoutSpec): String = "v${ContentStore.FORMAT}|$spec"

    private fun pageCacheFile(spec: LayoutSpec): File {
        val h = cacheKey(spec).hashCode().toUInt().toString(16)
        return File(Library.booksDir(getApplication<Application>()), "$bookId.pages.$h.txt")
    }

    private fun loadPageCache(spec: LayoutSpec, blockCount: Int): List<Page>? {
        val f = pageCacheFile(spec)
        if (!f.exists()) return null
        return try {
            f.bufferedReader(Charsets.UTF_8).use { r ->
                if (r.readLine() != cacheKey(spec)) return null
                if (r.readLine()?.toIntOrNull() != blockCount) return null
                val out = ArrayList<Page>()
                while (true) {
                    val line = r.readLine() ?: break
                    out.add(decodePage(line) ?: return null)
                }
                if (out.isEmpty()) null else out
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun savePageCache(spec: LayoutSpec, blockCount: Int, pages: List<Page>) {
        try {
            val f = pageCacheFile(spec)
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(cacheKey(spec))
                w.write("\n")
                w.write(blockCount.toString())
                w.write("\n")
                for (p in pages) {
                    w.write(encodePage(p))
                    w.write("\n")
                }
            }
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
            // Cache ist nur eine Beschleunigung – Fehler sind egal.
        }
    }

    private fun encodePage(p: Page): String {
        val sb = StringBuilder()
        sb.append(p.start.block).append(':').append(p.start.char).append('|')
        for ((i, f) in p.frags.withIndex()) {
            if (i > 0) sb.append(';')
            when (f) {
                is TextFrag -> sb.append("T,").append(f.block).append(',').append(f.startLine)
                    .append(',').append(f.endLine).append(',').append(f.y)
                is ImageFrag -> sb.append("I,").append(f.block).append(',').append(f.y)
                    .append(',').append(f.x).append(',').append(f.w).append(',').append(f.h)
            }
        }
        return sb.toString()
    }

    private fun decodePage(line: String): Page? {
        val bar = line.indexOf('|')
        if (bar < 0) return null
        val start = Pos.parse(line.substring(0, bar))
        val frags = ArrayList<Frag>()
        for (part in line.substring(bar + 1).split(';')) {
            val a = part.split(',')
            when (a.getOrNull(0)) {
                "T" -> {
                    if (a.size < 5) return null
                    frags.add(TextFrag(a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4].toFloat()))
                }
                "I" -> {
                    if (a.size < 6) return null
                    frags.add(ImageFrag(a[1].toInt(), a[2].toFloat(), a[3].toFloat(), a[4].toFloat(), a[5].toFloat()))
                }
                else -> return null
            }
        }
        return if (frags.isEmpty()) null else Page(frags, start)
    }

    suspend fun loadRegion(key: String, b: ImageBlock, w: Int, h: Int): Bitmap? {
        bitmaps.get(key)?.let { return it }
        val p = pdf ?: return null
        val bmp = withContext(renderDispatcher) {
            p.renderRegion(b.pdfPage, RectF(b.l, b.t, b.r, b.b), w, h)
        } ?: return null
        bitmaps.put(key, bmp)
        return bmp
    }

    suspend fun loadOriginal(key: String, index: Int, maxW: Int, maxH: Int): Bitmap? {
        bitmaps.get(key)?.let { return it }
        val p = pdf ?: return null
        val bmp = withContext(renderDispatcher) { p.renderFit(index, maxW, maxH) } ?: return null
        bitmaps.put(key, bmp)
        return bmp
    }

    /* ------------------------------------------------------------------ */
    /*  Nachschlagen: Übersetzung + englische Definition + Vokabelliste     */
    /* ------------------------------------------------------------------ */

    private inline fun updateLookup(f: (LookupState) -> LookupState) {
        val cur = lookup.value ?: return
        lookup.value = f(cur)
    }

    fun startLookup(sel: Selection) {
        lookupJob?.cancel()
        val app = getApplication<Application>()
        val words = sel.text.trim().split(Regex("\\s+")).size
        val defWanted = settings.showDefinition && words <= 3
        val hl = annotations?.highlightsFor(sel.block)?.firstOrNull {
            it.start < sel.end && sel.start < it.end && it.matches(pageSet.value.blocks)
        }
        lookup.value = LookupState(sel, defWanted = defWanted, defLoading = defWanted, highlight = hl)
        lookupJob = viewModelScope.launch {
            val already = withContext(Dispatchers.IO) { VocabStore.contains(app, sel.text) }
            updateLookup { it.copy(saved = already) }

            val tJob = async {
                try {
                    Result.success(Translator.translate(sel.text))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure<Translation>(e)
                }
            }
            val dJob = if (defWanted) async {
                try {
                    Result.success(Dictionary.lookup(sel.text))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure<DictEntry?>(e)
                }
            } else null

            val t = tJob.await()
            updateLookup {
                it.copy(
                    transLoading = false,
                    translation = t.getOrNull(),
                    transError = if (t.isFailure) "Keine Verbindung – Übersetzung gerade nicht möglich." else null
                )
            }
            if (dJob != null) {
                val d = dJob.await()
                updateLookup {
                    it.copy(
                        defLoading = false,
                        definition = d.getOrNull(),
                        defError = when {
                            d.isFailure -> "Keine Verbindung zum Wörterbuch."
                            d.getOrNull() == null -> "Keine englische Definition gefunden."
                            else -> null
                        }
                    )
                }
            }
            if (settings.autoVocab && words <= 4 && t.isSuccess) saveVocab(countUp = true)
        }
    }

    private suspend fun saveVocab(countUp: Boolean) {
        val st = lookup.value ?: return
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val e = VocabEntry(
            key = VocabStore.key(st.sel.text),
            word = st.sel.text.trim(),
            translation = st.translation?.text.orEmpty(),
            meanings = st.translation?.dict.orEmpty().joinToString("; ") { "${it.first}: ${it.second}" },
            phonetic = st.definition?.phonetic.orEmpty(),
            definition = st.definition?.senses.orEmpty().take(2).joinToString(" | ") { "(${it.pos}) ${it.definition}" },
            context = st.sel.context,
            bookId = bookId.orEmpty(),
            bookTitle = book?.title.orEmpty(),
            added = now,
            updated = now,
            count = 1
        )
        withContext(Dispatchers.IO) { VocabStore.upsert(app, e, countUp) }
        updateLookup { it.copy(saved = true) }
    }

    /** Stern in der Karte: Wort in die Vokabelliste aufnehmen bzw. daraus entfernen. */
    fun toggleVocab() {
        val st = lookup.value ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (st.saved) {
                withContext(Dispatchers.IO) { VocabStore.remove(app, VocabStore.key(st.sel.text)) }
                updateLookup { it.copy(saved = false) }
            } else {
                saveVocab(countUp = false)
            }
        }
    }

    fun closeLookup() {
        lookupJob?.cancel()
        lookup.value = null
    }

    /* ------------------------------------------------------------------ */
    /*  Lesezeichen & Markierungen                                         */
    /* ------------------------------------------------------------------ */

    private fun saveAnnotations() {
        val a = annotations ?: return
        a.reindex()
        annotationsVersion.update { it + 1 }
        val json = AnnotationStore.toJson(a)
        val app = getApplication<Application>()
        viewModelScope.launch(ioSerial) { AnnotationStore.write(app, a.bookId, json) }
    }

    fun bookmarkOnPage(ps: PageSet, pageIndex: Int): Bookmark? {
        val a = annotations ?: return null
        val p = ps.pages.getOrNull(pageIndex) ?: return null
        val next = ps.pages.getOrNull(pageIndex + 1)?.start
        return a.bookmarks.firstOrNull { bm ->
            bm.pos >= p.start && (next == null || bm.pos < next) &&
                ps.blocks.getOrNull(bm.pos.block)?.pdfPage == bm.pdfPage
        }
    }

    fun bookmarkOnPdfPage(index: Int): Bookmark? = annotations?.bookmarks?.firstOrNull { it.pdfPage == index }

    /** Lesezeichen auf der aktuellen Seite setzen oder entfernen. Rückgabe: neuer Zustand. */
    fun toggleBookmark(ps: PageSet, pageIndex: Int): Boolean? {
        val a = annotations ?: return null
        val now = System.currentTimeMillis()
        if (mode == 0) {
            val existing = bookmarkOnPage(ps, pageIndex)
            if (existing != null) {
                a.bookmarks.remove(existing)
                saveAnnotations()
                return false
            }
            val p = ps.pages.getOrNull(pageIndex) ?: return null
            val blk = ps.blocks.getOrNull(p.start.block) ?: return null
            val snippet = (blk as? TextBlock)?.text?.let { t ->
                t.substring(p.start.char.coerceIn(0, t.length)).take(90).trim()
            } ?: "Abbildung"
            a.bookmarks.add(Bookmark(a.newId(), p.start, blk.pdfPage, snippet, now))
        } else {
            val existing = bookmarkOnPdfPage(origPage)
            if (existing != null) {
                a.bookmarks.remove(existing)
                saveAnnotations()
                return false
            }
            val bi = ps.blocks.indexOfFirst { it.pdfPage >= origPage }
            val snippet = (ps.blocks.getOrNull(bi) as? TextBlock)?.text?.take(90)?.trim() ?: "Seite ${origPage + 1}"
            a.bookmarks.add(Bookmark(a.newId(), Pos(max(bi, 0), 0), origPage, snippet, now))
        }
        saveAnnotations()
        return true
    }

    fun removeBookmark(id: String) {
        val a = annotations ?: return
        if (a.bookmarks.removeAll { it.id == id }) saveAnnotations()
    }

    /** Aktuelle Auswahl markieren (überlappende Markierungen werden ersetzt). */
    fun addHighlight() {
        val st = lookup.value ?: return
        val a = annotations ?: return
        val s = st.sel
        val blk = pageSet.value.blocks.getOrNull(s.block) as? TextBlock ?: return
        if (s.start < 0 || s.end > blk.text.length || s.end <= s.start) return
        val h = Highlight(a.newId(), s.block, s.start, s.end, blk.text.substring(s.start, s.end), blk.pdfPage, System.currentTimeMillis())
        a.highlights.removeAll { it.block == s.block && it.start < s.end && s.start < it.end }
        a.highlights.add(h)
        saveAnnotations()
        lookup.value = st.copy(highlight = h)
    }

    fun removeHighlight(id: String) {
        val a = annotations ?: return
        if (a.highlights.removeAll { it.id == id }) saveAnnotations()
        updateLookup { if (it.highlight?.id == id) it.copy(highlight = null) else it }
    }

    /**
     * Prüft, ob Markierungen noch zum Text passen (z. B. nach einer verbesserten Texterkennung),
     * und verankert sie sonst über den markierten Text neu.
     */
    private fun verifyAnnotations(blocks: List<Block>, done: Boolean) {
        val a = annotations ?: return
        if (!a.needsVerify || blocks.isEmpty()) return
        var changed = false
        var pending = false
        val lastPage = blocks.last().pdfPage
        for (i in a.highlights.indices) {
            val h = a.highlights[i]
            if (h.matches(blocks)) continue
            var found: Highlight? = null
            for ((bi, bl) in blocks.withIndex()) {
                if (bl !is TextBlock || abs(bl.pdfPage - h.pdfPage) > 1) continue
                val idx = bl.text.indexOf(h.text)
                if (idx >= 0) {
                    found = h.copy(block = bi, start = idx, end = idx + h.text.length)
                    break
                }
                // Text kann sich in Leerzeichen unterscheiden (verbesserte Worttrennung).
                val r = TextUtil.findLoose(bl.text, h.text)
                if (r != null) {
                    found = h.copy(block = bi, start = r.first, end = r.last + 1, text = bl.text.substring(r.first, r.last + 1))
                    break
                }
            }
            if (found != null) {
                a.highlights[i] = found
                changed = true
            } else if (!done && lastPage <= h.pdfPage + 1) {
                pending = true
            }
        }
        for (i in a.bookmarks.indices) {
            val bm = a.bookmarks[i]
            if (blocks.getOrNull(bm.pos.block)?.pdfPage == bm.pdfPage) continue
            if (!done && lastPage <= bm.pdfPage) {
                pending = true
                continue
            }
            val head = bm.snippet.take(40)
            var newPos: Pos? = null
            if (head.length >= 8) {
                for ((bi, bl) in blocks.withIndex()) {
                    if (bl !is TextBlock || abs(bl.pdfPage - bm.pdfPage) > 1) continue
                    val r = TextUtil.findLoose(bl.text, head)
                    if (r != null) {
                        newPos = Pos(bi, r.first)
                        break
                    }
                }
            }
            if (newPos == null) {
                val bi = blocks.indexOfFirst { it.pdfPage >= bm.pdfPage }
                if (bi >= 0) newPos = Pos(bi, 0)
            }
            if (newPos != null) {
                a.bookmarks[i] = bm.copy(pos = newPos, pdfPage = blocks[newPos.block].pdfPage)
                changed = true
            }
        }
        if (done && !pending) a.needsVerify = false
        if (changed) saveAnnotations()
    }

    /* ------------------------------------------------------------------ */
    /*  Vorlesen                                                           */
    /* ------------------------------------------------------------------ */

    /** 0 = Absätze automatisch erkennen, 1 = Absätze aus (Fließtext). Gilt pro Buch. */
    val paraMode: Int
        get() = bookId?.let { Prefs.sp(getApplication<Application>()).getInt("para_$it", 0) } ?: 0

    /** Absatz-Modus ändern: Buch wird neu aufbereitet, die Leseposition bleibt erhalten. */
    fun setParagraphMode(m: Int, done: () -> Unit) {
        val id = bookId ?: return
        val app = getApplication<Application>()
        readAloud.stop()
        val page = currentPdfPage()
        savePosition()
        Prefs.sp(app).edit().putInt("para_$id", m).putInt("resume_$id", page).apply()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ContentManager.reextract(app, id) }
            done()
        }
    }

    fun startReading() {
        val blocks = pageSet.value.blocks
        val from = if (mode == 0) pos else Pos(max(0, blocks.indexOfFirst { it.pdfPage >= origPage }), 0)
        readAloud.start(from, lang.ifEmpty { "en" }, settings.ttsRate)
    }

    fun currentPdfPage(): Int =
        if (mode == 0) pageSet.value.blocks.getOrNull(pos.block)?.pdfPage ?: 0 else origPage

    fun percent(): Int {
        val total = content?.totalPages?.takeIf { it > 0 } ?: book?.pageCount ?: 0
        if (total <= 0) return 0
        val p = if (mode == 0) pageSet.value.blocks.getOrNull(pos.block)?.pdfPage ?: 0 else origPage
        return ((p + 1) * 100 / total).coerceIn(0, 100)
    }

    fun savePosition() {
        val id = bookId ?: return
        Prefs.sp(getApplication<Application>()).edit()
            .putString("pos_$id", pos.toString())
            .putInt("orig_$id", origPage)
            .putInt("mode_$id", mode)
            .putBoolean("auto_$id", autoSwitched)
            .putInt("prog_$id", percent())
            .putInt("ppage_$id", currentPdfPage())
            .apply()
    }

    override fun onCleared() {
        super.onCleared()
        readAloud.shutdown()
        ioExecutor.shutdown()
        val p = pdf
        renderExecutor.execute {
            try { p?.close() } catch (e: Exception) { }
        }
        renderExecutor.shutdown()
    }
}
