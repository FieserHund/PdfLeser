package de.pdfleser.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

data class BookEntry(
    val id: String,
    val title: String,
    val pageCount: Int,
    val size: Long,
    val added: Long,
    val lastOpened: Long
)

/** Bücher werden beim Import in den App-Speicher kopiert – so bleiben sie auch nach Neustarts lesbar. */
object Library {
    private val lock = Any()

    fun booksDir(ctx: Context): File = File(ctx.filesDir, "books").apply { mkdirs() }
    fun pdfFile(ctx: Context, id: String) = File(booksDir(ctx), "$id.pdf")
    fun coverFile(ctx: Context, id: String) = File(booksDir(ctx), "$id.cover.jpg")
    fun contentFile(ctx: Context, id: String) = File(booksDir(ctx), "$id.blocks.jsonl")
    fun metaFile(ctx: Context, id: String) = File(booksDir(ctx), "$id.meta.json")
    private fun indexFile(ctx: Context) = File(ctx.filesDir, "library.json")

    fun all(ctx: Context): List<BookEntry> = synchronized(lock) { read(ctx) }

    fun get(ctx: Context, id: String): BookEntry? = all(ctx).firstOrNull { it.id == id }

    fun touch(ctx: Context, id: String) {
        synchronized(lock) {
            val list = read(ctx)
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) {
                list[i] = list[i].copy(lastOpened = System.currentTimeMillis())
                write(ctx, list)
            }
        }
    }

    fun delete(ctx: Context, id: String) {
        synchronized(lock) {
            val list = read(ctx)
            list.removeAll { it.id == id }
            write(ctx, list)
        }
        booksDir(ctx).listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
        val sp = Prefs.sp(ctx)
        val e = sp.edit()
        sp.all.keys.filter { it.endsWith("_$id") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Kopiert das PDF in den App-Speicher, prüft es und erzeugt ein Cover. Läuft im Hintergrund. */
    fun importPdf(ctx: Context, uri: Uri): BookEntry {
        val cr = ctx.contentResolver
        var name: String? = null
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            // Manche Anbieter unterstützen keine Abfrage – dann eben ohne Namen.
        }
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Dokument.pdf"
        val title = fileName.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "").replace('_', ' ').trim()
            .ifEmpty { "Dokument" }

        if (size > 0) {
            all(ctx).firstOrNull { it.size == size && it.title == title }?.let {
                if (pdfFile(ctx, it.id).exists()) return it
            }
        }

        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val target = pdfFile(ctx, id)
        val tmp = File(target.path + ".tmp")
        val input = cr.openInputStream(uri) ?: throw IOException("Die Datei konnte nicht gelesen werden.")
        input.use { inp -> FileOutputStream(tmp).use { out -> inp.copyTo(out, 256 * 1024) } }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Die Datei konnte nicht gespeichert werden.")
        }

        val pages = try {
            renderCoverAndCount(ctx, target, id)
        } catch (e: Exception) {
            target.delete()
            throw IOException("Das PDF ist beschädigt oder passwortgeschützt.")
        }

        val entry = BookEntry(id, title, pages, target.length(), System.currentTimeMillis(), 0L)
        synchronized(lock) {
            val list = read(ctx)
            list.add(entry)
            write(ctx, list)
        }
        return entry
    }

    private fun renderCoverAndCount(ctx: Context, file: File, id: String): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return synchronized(PdfPages.LOCK) {
            val r = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
            try {
                val count = r.pageCount
                if (count > 0) {
                    val p = r.openPage(0)
                    try {
                        val w = 360
                        val h = (w * p.height.toFloat() / p.width.coerceAtLeast(1)).toInt().coerceIn(120, 720)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(coverFile(ctx, id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        bmp.recycle()
                    } finally {
                        p.close()
                    }
                }
                count
            } finally {
                r.close()
                try { pfd.close() } catch (e: Exception) { }
            }
        }
    }

    private fun read(ctx: Context): MutableList<BookEntry> {
        val f = indexFile(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                BookEntry(
                    o.getString("id"), o.optString("title"), o.optInt("pages"),
                    o.optLong("size"), o.optLong("added"), o.optLong("opened")
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<BookEntry>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(
                JSONObject().put("id", b.id).put("title", b.title).put("pages", b.pageCount)
                    .put("size", b.size).put("added", b.added).put("opened", b.lastOpened)
            )
        }
        val f = indexFile(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
