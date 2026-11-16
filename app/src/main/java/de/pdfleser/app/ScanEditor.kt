package de.pdfleser.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Eine Seite eines gescannten Buchs, so wie sie in der Seitenverwaltung erscheint. */
data class ScanPage(val index: Int, val detected: Int?, val manual: Int?) {
    /** Die gültige Seitenzahl: eine von Hand eingetragene schlägt die erkannte. */
    val number: Int? get() = manual ?: detected
}

/**
 * Nachträgliches Bearbeiten gescannter Bücher: Seiten zu einer zusammenfassen, löschen,
 * nach gedruckter Seitenzahl sortieren und Seitenzahlen korrigieren.
 *
 * Alle Eingriffe schreiben Bilddateien und Textdatei gemeinsam neu, damit beide
 * immer dieselbe Reihenfolge haben.
 */
object ScanEditor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Laufende Arbeit (Text für den Fortschrittsdialog) oder null. */
    val busy = MutableStateFlow<String?>(null)
    /** Zählt hoch, sobald sich an den Seiten etwas geändert hat. */
    val changed = MutableStateFlow(0)
    val lastError = MutableStateFlow<String?>(null)
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /* ------------------------------------------------------------------ */
    /*  Lesen                                                              */
    /* ------------------------------------------------------------------ */

    private fun numbersFile(ctx: Context, id: String) = File(Library.booksDir(ctx), "$id.pageno.json")

    private fun loadManual(ctx: Context, id: String): HashMap<Int, Int> {
        val map = HashMap<Int, Int>()
        val f = numbersFile(ctx, id)
        if (!f.exists()) return map
        try {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val i = k.toIntOrNull() ?: continue
                map[i] = o.optInt(k)
            }
        } catch (e: Exception) {
            // Unbrauchbar – dann eben ohne Korrekturen.
        }
        return map
    }

    private fun saveManual(ctx: Context, id: String, map: Map<Int, Int>) {
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k.toString(), v)
            val f = numbersFile(ctx, id)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // Nicht schlimm – die Korrektur ist dann beim nächsten Mal wieder weg.
        }
    }

    /** Die erkannte Seitenzahl je Seite aus der Textdatei. */
    private fun detectedNumbers(ctx: Context, id: String): HashMap<Int, Int> {
        val map = HashMap<Int, Int>()
        val f = Library.contentFile(ctx, id)
        if (!f.exists()) return map
        try {
            f.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val o = try { JSONObject(line) } catch (e: Exception) { null } ?: return@forEachLine
                val p = o.optInt("p", -1)
                if (p >= 0 && o.has("pn")) map[p] = o.optInt("pn")
            }
        } catch (e: Exception) {
            // Teilweise gelesen reicht.
        }
        return map
    }

    fun pages(ctx: Context, id: String): List<ScanPage> {
        val manual = loadManual(ctx, id)
        val detected = detectedNumbers(ctx, id)
        return Library.scanPages(ctx, id).indices.map { ScanPage(it, detected[it], manual[it]) }
    }

    /** Seitenzahl von Hand setzen (null = Korrektur zurücknehmen). */
    fun setNumber(ctx: Context, id: String, index: Int, number: Int?) {
        val map = loadManual(ctx, id)
        if (number == null) map.remove(index) else map[index] = number
        saveManual(ctx, id, map)
        changed.update { it + 1 }
    }

    /* ------------------------------------------------------------------ */
    /*  Ändern                                                             */
    /* ------------------------------------------------------------------ */

    private fun start(label: String, work: suspend () -> Unit) {
        if (running) return
        busy.value = label
        lastError.value = null
        job = scope.launch {
            try {
                work()
                changed.update { it + 1 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError.value = e.message ?: "Die Seiten konnten nicht geändert werden."
            } finally {
                busy.value = null
                job = null
            }
        }
    }

    /** Mehrere Fotos zu einer Seite zusammenfassen (untereinander, in Seitenreihenfolge). */
    fun merge(ctx: Context, id: String, indices: List<Int>) {
        val app = ctx.applicationContext
        val sel = indices.distinct().sorted()
        if (sel.size < 2) return
        start("Seiten werden zusammengefügt …") {
            val files = Library.scanPages(app, id)
            val parts = ArrayList<Bitmap>()
            try {
                for (i in sel) {
                    val f = files.getOrNull(i) ?: continue
                    BitmapFactory.decodeFile(f.path)?.let { parts.add(it) }
                }
                if (parts.size < 2) throw IllegalStateException("Die Seitenbilder konnten nicht geladen werden.")
                val merged = Ocr.mergeVertical(parts) ?: throw IllegalStateException("Zusammenfügen fehlgeschlagen.")
                try {
                    // Die zusammengesetzte Seite ersetzt die erste Auswahl, die übrigen fallen weg.
                    val target = File(Library.scanDir(app, id), "merge.part")
                    val store = Ocr.copyScaled(merged, Ocr.STORE_EDGE)
                    try {
                        java.io.FileOutputStream(target).use { store.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    } finally {
                        if (store !== merged) store.recycle()
                    }
                    val order = files.indices.filter { it == sel.first() || it !in sel }
                    rebuild(app, id, order, replace = mapOf(sel.first() to target))
                } finally {
                    if (merged !in parts) merged.recycle()
                }
            } finally {
                parts.forEach { it.recycle() }
            }
        }
    }

    /** Alle Seiten neu erkennen – nach einer neuen Programmversion oder geänderten Absatz-Regeln. */
    fun reocrAll(ctx: Context, id: String) {
        val app = ctx.applicationContext
        start("Seiten werden neu erkannt …") {
            val order = Library.scanPages(app, id).indices.toList()
            if (order.isEmpty()) return@start
            rebuild(app, id, order, emptyMap(), redoAll = true)
        }
    }

    fun deletePages(ctx: Context, id: String, indices: List<Int>) {
        val app = ctx.applicationContext
        val drop = indices.toHashSet()
        if (drop.isEmpty()) return
        start("Seiten werden entfernt …") {
            val order = Library.scanPages(app, id).indices.filter { it !in drop }
            if (order.isEmpty()) throw IllegalStateException("Es muss mindestens eine Seite übrig bleiben.")
            rebuild(app, id, order, emptyMap())
        }
    }

    /**
     * Nach gedruckter Seitenzahl sortieren. Seiten ohne Zahl bleiben zwischen ihren
     * bisherigen Nachbarn stehen, damit nichts unkontrolliert ans Ende rutscht.
     */
    fun sortByNumber(ctx: Context, id: String) {
        val app = ctx.applicationContext
        start("Seiten werden sortiert …") {
            val list = pages(app, id)
            if (list.size < 2) return@start
            // Jeder Seite einen Sortierwert geben: die eigene Zahl oder die der letzten bekannten.
            var last = 0.0
            var step = 0.0
            val keys = DoubleArray(list.size)
            for ((i, p) in list.withIndex()) {
                val n = p.number
                if (n != null) {
                    last = n.toDouble()
                    step = 0.0
                } else {
                    step += 0.001
                }
                keys[i] = last + step
            }
            val order = list.indices.sortedWith(compareBy({ keys[it] }, { it }))
            if (order == list.indices.toList()) return@start
            rebuild(app, id, order, emptyMap())
        }
    }

    /**
     * Schreibt Bilder und Textdatei in der Reihenfolge [order] neu.
     * [replace] ersetzt dabei das Bild einer alten Seite durch eine andere Datei;
     * solche Seiten werden danach neu erkannt.
     */
    private suspend fun rebuild(
        ctx: Context, id: String, order: List<Int>, replace: Map<Int, File>, redoAll: Boolean = false
    ) {
        val dir = Library.scanDir(ctx, id)
        val files = Library.scanPages(ctx, id)
        val oldLines = readLines(ctx, id)
        val manual = loadManual(ctx, id)

        // 1) Bilder unter Zwischennamen in die neue Reihenfolge bringen. Es wird noch nichts gelöscht.
        val staged = ArrayList<File>(order.size)
        for ((newIndex, oldIndex) in order.withIndex()) {
            val src = replace[oldIndex] ?: files.getOrNull(oldIndex) ?: continue
            val tmp = File(dir, String.format(java.util.Locale.ROOT, "stage_%04d.part", newIndex))
            if (!src.renameTo(tmp)) {
                src.copyTo(tmp, overwrite = true)
                if (replace.containsKey(oldIndex)) src.delete()
            }
            staged.add(tmp)
        }
        if (staged.isEmpty()) throw IllegalStateException("Es blieb keine Seite übrig.")

        // 2) Neuen Text erzeugen – noch immer ohne etwas zu löschen, denn das dauert am längsten.
        val redo = if (redoAll) order.toSet() else replace.keys
        val out = StringBuilder()
        val newManual = HashMap<Int, Int>()
        val ocr = if (redo.isEmpty()) null else Ocr()
        val analyzer = PageAnalyzer(
            ImagePages(staged),
            continuous = Prefs.sp(ctx).getInt("para_$id", 0) == 1,
            photo = true
        )
        try {
            for ((newIndex, oldIndex) in order.withIndex()) {
                manual[oldIndex]?.let { newManual[newIndex] = it }
                if (redoAll) busy.value = "Seite ${newIndex + 1} von ${order.size} wird erkannt …"
                var fresh: String? = null
                if (oldIndex in redo) {
                    val f = staged.getOrNull(newIndex)
                    val bmp = if (f == null) null else try {
                        BitmapFactory.decodeFile(f.path)
                    } catch (e: Throwable) {
                        null
                    }
                    if (bmp != null) {
                        try {
                            val lines = try {
                                ocr?.recognize(bmp) ?: ArrayList()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                ArrayList()
                            }
                            val result = analyzer.analyze(
                                lines, emptyList(), bmp.width.toFloat(), bmp.height.toFloat(),
                                newIndex, allowFigures = true
                            )
                            fresh = ContentStore.pageLine(newIndex, result)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
                // Lässt sich eine Seite nicht neu erkennen, bleibt lieber der alte Text stehen.
                val line = fresh
                    ?: oldLines[oldIndex]?.let { renumber(it, newIndex) }
                    ?: ContentStore.pageLine(newIndex, PageResult(emptyList(), emptyList()))
                out.append(line).append('\n')
            }
        } finally {
            ocr?.close()
        }

        // 3) Erst jetzt die alten Bilder löschen und die Zwischennamen übernehmen.
        dir.listFiles()?.filter { it.name.endsWith(".jpg") }?.forEach { it.delete() }
        for ((i, tmp) in staged.withIndex()) {
            tmp.renameTo(Library.scanPageFile(ctx, id, i))
        }

        // 4) Textdatei austauschen.
        val cf = Library.contentFile(ctx, id)
        val tmp = File(cf.path + ".tmp")
        tmp.writeText(out.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(cf)) {
            cf.delete()
            tmp.renameTo(cf)
        }
        saveManual(ctx, id, newManual)
        ContentStore.saveMeta(ctx, id, staged.size, true, emptyList())

        // 5) Leseposition auf die neue Stelle schieben, Umbruch-Zwischenspeicher verwerfen.
        val sp = Prefs.sp(ctx)
        val oldPage = sp.getInt("ppage_$id", -1)
        if (oldPage >= 0) {
            val newPage = order.indexOf(oldPage).let { if (it >= 0) it else 0 }
            sp.edit().putInt("resume_$id", newPage).apply()
        }
        Library.coverFile(ctx, id).delete()
        Library.refreshScan(ctx, id)
        ContentManager.invalidate(ctx, id)
    }

    /** Alle gespeicherten Seitenzeilen, nach Seitenindex. */
    private fun readLines(ctx: Context, id: String): HashMap<Int, String> {
        val map = HashMap<Int, String>()
        val f = Library.contentFile(ctx, id)
        if (!f.exists()) return map
        try {
            f.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val o = try { JSONObject(line) } catch (e: Exception) { null } ?: return@forEachLine
                val p = o.optInt("p", -1)
                if (p >= 0) map[p] = line
            }
        } catch (e: Exception) {
            // Was gelesen wurde, reicht.
        }
        return map
    }

    private fun renumber(line: String, newIndex: Int): String = try {
        JSONObject(line).put("p", newIndex).toString()
    } catch (e: Exception) {
        line
    }
}
