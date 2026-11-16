package de.pdfleser.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

data class ScanState(
    val bookId: String,
    val title: String,
    val done: Int,
    val total: Int,
    val finished: Boolean = false,
    val error: String? = null
)

/**
 * Nimmt fotografierte Buchseiten auf: Bilder werden begradigt gespeichert, der Text wird auf dem
 * Gerät erkannt und als fertig aufbereitetes Buch abgelegt. Dadurch funktionieren danach alle
 * Lesefunktionen (Nachtmodus, Übersetzen, Vorlesen, Quellen) genauso wie bei einem PDF.
 *
 * Läuft in einem eigenen Gültigkeitsbereich weiter, auch wenn der Bildschirm gedreht wird.
 */
object ScanImport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = MutableStateFlow<ScanState?>(null)
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun clear() {
        if (state.value?.finished == true || state.value?.error != null) state.value = null
    }

    fun cancel() {
        job?.cancel()
        job = null
        state.value = null
    }

    /**
     * @param existingId Seiten an ein vorhandenes Scan-Buch anhängen, sonst null für ein neues Buch.
     */
    fun start(ctx: Context, uris: List<Uri>, title: String, existingId: String? = null, partsPerPage: Int = 1) {
        if (running || uris.isEmpty()) return
        val app = ctx.applicationContext
        job = scope.launch {
            var book: BookEntry? = null
            try {
                book = if (existingId != null) Library.get(app, existingId) else Library.createScan(app, title)
                val entry = book ?: throw IllegalStateException("Das Buch wurde nicht gefunden.")
                val groups = uris.chunked(partsPerPage.coerceIn(1, 4))
                state.value = ScanState(entry.id, entry.title, 0, groups.size)
                run(app, entry, groups)
                state.value = ScanState(entry.id, entry.title, groups.size, groups.size, finished = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val id = book?.id.orEmpty()
                // Ein fehlgeschlagener neuer Scan ohne Seiten hinterlässt keine Karteileiche.
                if (existingId == null && id.isNotEmpty() && Library.scanPages(app, id).isEmpty()) {
                    Library.delete(app, id)
                }
                state.value = ScanState(id, title, 0, uris.size, finished = true, error = e.message ?: "Fehler beim Scannen.")
            } finally {
                job = null
            }
        }
    }

    private suspend fun run(ctx: Context, entry: BookEntry, groups: List<List<Uri>>) {
        val id = entry.id
        // Reste eines abgebrochenen Laufs aufräumen, damit die Seiten lückenlos durchnummeriert bleiben.
        var index = prepareAppend(ctx, id)
        val analyzer = PageAnalyzer(
            ImagePages.live(ctx, id),
            continuous = Prefs.sp(ctx).getInt("para_$id", 0) == 1,
            photo = true
        )
        val ocr = Ocr()
        try {
            BufferedWriter(
                OutputStreamWriter(FileOutputStream(Library.contentFile(ctx, id), true), Charsets.UTF_8)
            ).use { w ->
                for ((n, group) in groups.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    // Mehrere Fotos einer Seite werden untereinander zu einem Bild zusammengesetzt.
                    val parts = ArrayList<Bitmap>(group.size)
                    for (u in group) loadUpright(ctx, u)?.let { parts.add(it) }
                    val bmp = try {
                        Ocr.mergeVertical(parts)
                    } catch (e: Throwable) {
                        null
                    }
                    if (bmp == null) {
                        parts.forEach { it.recycle() }
                        state.value = state.value?.copy(done = n + 1)
                        continue
                    }
                    parts.forEach { if (it !== bmp) it.recycle() }
                    try {
                        // Erst erkennen (in voller Auflösung), dann platzsparend ablegen.
                        val lines = try {
                            ocr.recognize(bmp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            ArrayList()
                        }
                        val pw = bmp.width.toFloat()
                        val ph = bmp.height.toFloat()
                        val store = Ocr.copyScaled(bmp, Ocr.STORE_EDGE)
                        try {
                            FileOutputStream(Library.scanPageFile(ctx, id, index)).use {
                                store.compress(Bitmap.CompressFormat.JPEG, 82, it)
                            }
                        } finally {
                            if (store !== bmp) store.recycle()
                        }
                        val result = analyzer.analyze(lines, emptyList(), pw, ph, index, allowFigures = true)
                        w.write(ContentStore.pageLine(index, result))
                        w.write("\n")
                        w.flush()
                        index++
                    } finally {
                        bmp.recycle()
                    }
                    state.value = state.value?.copy(done = n + 1)
                }
            }
        } finally {
            ocr.close()
            ContentStore.saveMeta(ctx, id, index, true, emptyList())
            Library.refreshScan(ctx, id)
            ContentManager.invalidate(ctx, id)
        }
    }

    /**
     * Zählt die bereits erkannten Seiten, schneidet eine abgebrochene letzte Zeile ab und
     * verwirft Seitenfotos, zu denen noch kein Text gespeichert wurde.
     */
    private fun prepareAppend(ctx: Context, id: String): Int {
        val f = Library.contentFile(ctx, id)
        var valid = 0L
        var n = 0
        if (f.exists()) {
            try {
                f.bufferedReader(Charsets.UTF_8).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        val o = try { org.json.JSONObject(line) } catch (e: Exception) { null } ?: break
                        if (o.optInt("p", -1) != n) break
                        n++
                        valid += line.toByteArray(Charsets.UTF_8).size + 1
                    }
                }
                if (valid < f.length()) {
                    java.io.RandomAccessFile(f, "rw").use { it.setLength(valid) }
                }
            } catch (e: Exception) {
                // Unlesbar – dann lieber von vorn.
                f.delete()
                n = 0
            }
        }
        val imgs = Library.scanPages(ctx, id)
        if (imgs.size > n) imgs.drop(n).forEach { it.delete() }
        return n
    }

    /** Lädt ein Foto verkleinert und dreht es nach den EXIF-Daten aufrecht. */
    private fun loadUpright(ctx: Context, uri: Uri): Bitmap? {
        val cr = ctx.contentResolver
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, info) }
        } catch (e: Exception) {
            return null
        }
        if (info.outWidth <= 0 || info.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = Ocr.sampleSize(info.outWidth, info.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = try {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Throwable) {
            null
        } ?: return null

        val rotation = try {
            cr.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            val r = try {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            } catch (e: Throwable) {
                null
            }
            if (r != null && r !== bmp) {
                bmp.recycle()
                bmp = r
            }
        }
        return Ocr.scaleDown(bmp)
    }
}
