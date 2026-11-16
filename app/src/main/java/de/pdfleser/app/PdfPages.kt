package de.pdfleser.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Woher die Seitenbilder eines Buchs kommen: aus einem PDF oder aus gescannten Fotos.
 * Für alles andere in der App macht das keinen Unterschied.
 */
interface PageSource : Closeable {
    val pageCount: Int

    /** Ganze Seite, eingepasst in maxW × maxH. */
    fun renderFit(index: Int, maxW: Int, maxH: Int): Bitmap?

    /** Ausschnitt rc (relativ 0..1) einer Seite in genau outW × outH Pixeln. */
    fun renderRegion(index: Int, rc: RectF, outW: Int, outH: Int): Bitmap?

    /**
     * Rendert rc klein und schneidet weißen Rand ab.
     * Liefert null, wenn in dem Bereich praktisch nichts zu sehen ist.
     */
    fun trimRegion(index: Int, rc: RectF): RectF?
}

/** Öffnet das passende Seitenmaterial eines Buchs (gescannte Bilder oder PDF). */
fun openPages(ctx: Context, id: String): PageSource? = try {
    val scan = Library.scanPages(ctx, id)
    if (scan.isNotEmpty()) ImagePages(scan) else PdfPages(Library.pdfFile(ctx, id))
} catch (e: Exception) {
    null
}

/**
 * Dünner Wrapper um Androids eingebauten PDF-Renderer (pdfium).
 * Alle Aufrufe laufen über ein globales Lock, damit nie zwei Threads gleichzeitig rendern.
 */
class PdfPages(file: File) : PageSource {

    companion object {
        val LOCK = Any()
    }

    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        synchronized(LOCK) { PdfRenderer(pfd) }
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
    override val pageCount: Int = renderer.pageCount
    private var closed = false

    override fun close() {
        synchronized(LOCK) {
            if (closed) return
            closed = true
            try { renderer.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
    }

    override fun renderFit(index: Int, maxW: Int, maxH: Int): Bitmap? = synchronized(LOCK) { renderFitLocked(index, maxW, maxH) }

    override fun renderRegion(index: Int, rc: RectF, outW: Int, outH: Int): Bitmap? =
        synchronized(LOCK) { renderRegionLocked(index, rc, outW, outH) }

    override fun trimRegion(index: Int, rc: RectF): RectF? = synchronized(LOCK) { trimRegionLocked(index, rc) }

    private fun open(index: Int): PdfRenderer.Page? {
        if (closed || index < 0 || index >= pageCount) return null
        return try { renderer.openPage(index) } catch (e: Exception) { null }
    }

    private fun renderFitLocked(index: Int, maxW: Int, maxH: Int): Bitmap? {
        if (maxW <= 0 || maxH <= 0) return null
        val page = open(index) ?: return null
        try {
            val pw = page.width.toFloat()
            val ph = page.height.toFloat()
            val scale = min(maxW / pw, maxH / ph)
            val w = max(1, (pw * scale).toInt())
            val h = max(1, (ph * scale).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setScale(scale, scale)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } catch (e: Throwable) {
            return null
        } finally {
            page.close()
        }
    }

    private fun renderRegionLocked(index: Int, rc: RectF, outW: Int, outH: Int): Bitmap? {
        if (outW <= 0 || outH <= 0) return null
        val page = open(index) ?: return null
        try {
            val pw = page.width.toFloat()
            val ph = page.height.toFloat()
            val rw = max(rc.width() * pw, 1f)
            val rh = max(rc.height() * ph, 1f)
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setScale(outW / rw, outH / rh)
            m.preTranslate(-rc.left * pw, -rc.top * ph)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } catch (e: Throwable) {
            return null
        } finally {
            page.close()
        }
    }

    private fun trimRegionLocked(index: Int, rc: RectF): RectF? {
        val page = open(index) ?: return null
        try {
            val pw = page.width.toFloat()
            val ph = page.height.toFloat()
            val rw = rc.width() * pw
            val rh = rc.height() * ph
            if (rw < 2f || rh < 2f) return null
            val scale = min(160f / rw, 320f / rh)
            val bw = max(1, (rw * scale).toInt())
            val bh = max(1, (rh * scale).toInt())
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setScale(scale, scale)
            m.preTranslate(-rc.left * pw, -rc.top * ph)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val px = IntArray(bw * bh)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            bmp.recycle()
            var x0 = bw
            var y0 = bh
            var x1 = -1
            var y1 = -1
            var count = 0
            for (y in 0 until bh) {
                val row = y * bw
                for (x in 0 until bw) {
                    val c = px[row + x]
                    if (Color.red(c) < 225 || Color.green(c) < 225 || Color.blue(c) < 225) {
                        count++
                        if (x < x0) x0 = x
                        if (x > x1) x1 = x
                        if (y < y0) y0 = y
                        if (y > y1) y1 = y
                    }
                }
            }
            if (x1 < 0 || count < 12) return null
            val padX = 0.01f
            val padY = 0.006f
            val l = rc.left + rc.width() * x0 / bw - padX
            val t = rc.top + rc.height() * y0 / bh - padY
            val r = rc.left + rc.width() * (x1 + 1) / bw + padX
            val b = rc.top + rc.height() * (y1 + 1) / bh + padY
            return RectF(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        } catch (e: Throwable) {
            return null
        } finally {
            page.close()
        }
    }
}

/**
 * Seitenmaterial eines gescannten Buchs: eine JPEG-Datei pro Seite.
 * Lädt beim Zeichnen nur den gebrauchten Ausschnitt in der gebrauchten Größe,
 * damit auch lange Scans wenig Arbeitsspeicher brauchen.
 */
class ImagePages private constructor(private val provider: () -> List<File>) : PageSource {

    /** Feste Seitenliste (beim Lesen). */
    constructor(files: List<File>) : this({ files })

    companion object {
        /** Seitenliste, die sich noch ändert – während des Scannens kommen Seiten dazu. */
        fun live(ctx: Context, id: String) = ImagePages { Library.scanPages(ctx, id) }
    }

    override val pageCount: Int
        get() = provider().size

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun close() {}

    private fun bounds(index: Int): Pair<Int, Int>? {
        val f = provider().getOrNull(index) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return Pair(o.outWidth, o.outHeight)
    }

    /** Lädt das Bild gleich verkleinert (Zweierpotenz), damit nie das volle Foto im Speicher liegt. */
    private fun decode(index: Int, targetW: Int, targetH: Int): Bitmap? {
        val f = provider().getOrNull(index) ?: return null
        val (w, h) = bounds(index) ?: return null
        var sample = 1
        while (targetW > 0 && targetH > 0 && w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(f.path, o)
        } catch (e: Throwable) {
            null
        }
    }

    override fun renderFit(index: Int, maxW: Int, maxH: Int): Bitmap? {
        if (maxW <= 0 || maxH <= 0) return null
        val (w, h) = bounds(index) ?: return null
        val scale = min(maxW / w.toFloat(), maxH / h.toFloat())
        val outW = max(1, (w * scale).toInt())
        val outH = max(1, (h * scale).toInt())
        val src = decode(index, outW, outH) ?: return null
        if (src.width == outW && src.height == outH) return src
        return try {
            Bitmap.createScaledBitmap(src, outW, outH, true).also { if (it !== src) src.recycle() }
        } catch (e: Throwable) {
            src
        }
    }

    override fun renderRegion(index: Int, rc: RectF, outW: Int, outH: Int): Bitmap? {
        if (outW <= 0 || outH <= 0) return null
        val (w, h) = bounds(index) ?: return null
        // So stark verkleinern, dass der Ausschnitt gerade noch die gewünschte Größe hat.
        val needW = max(1, (outW / max(rc.width(), 0.01f)).toInt())
        val needH = max(1, (outH / max(rc.height(), 0.01f)).toInt())
        val src = decode(index, min(needW, w), min(needH, h)) ?: return null
        return try {
            val sx = src.width.toFloat()
            val sy = src.height.toFloat()
            val srcRect = Rect(
                (rc.left * sx).toInt().coerceIn(0, src.width - 1),
                (rc.top * sy).toInt().coerceIn(0, src.height - 1),
                (rc.right * sx).toInt().coerceIn(1, src.width),
                (rc.bottom * sy).toInt().coerceIn(1, src.height)
            )
            if (srcRect.width() <= 0 || srcRect.height() <= 0) return null
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.WHITE)
            Canvas(out).drawBitmap(src, srcRect, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint)
            out
        } catch (e: Throwable) {
            null
        } finally {
            src.recycle()
        }
    }

    override fun trimRegion(index: Int, rc: RectF): RectF? {
        val small = renderRegion(index, rc, 160, 320) ?: return null
        try {
            val bw = small.width
            val bh = small.height
            val px = IntArray(bw * bh)
            small.getPixels(px, 0, bw, 0, 0, bw, bh)

            // Fotos sind nie rein weiß: Die Papierhelligkeit wird aus dem Bild selbst geschätzt,
            // damit ungleichmäßiges Licht nicht als Inhalt zählt.
            val lum = IntArray(px.size)
            for (i in px.indices) {
                val c = px[i]
                lum[i] = (Color.red(c) * 77 + Color.green(c) * 151 + Color.blue(c) * 28) shr 8
            }
            val sorted = lum.clone()
            sorted.sort()
            val paper = sorted[(sorted.size * 0.88f).toInt().coerceIn(0, sorted.size - 1)]
            val limit = (paper * 0.78f).toInt().coerceIn(30, 225)

            var x0 = bw
            var y0 = bh
            var x1 = -1
            var y1 = -1
            var count = 0
            for (y in 0 until bh) {
                val row = y * bw
                for (x in 0 until bw) {
                    if (lum[row + x] < limit) {
                        count++
                        if (x < x0) x0 = x
                        if (x > x1) x1 = x
                        if (y < y0) y0 = y
                        if (y > y1) y1 = y
                    }
                }
            }
            // Mindestens 1,5 % dunkle Fläche – darunter ist es Rauschen oder ein Schatten.
            if (x1 < 0 || count < max(20, px.size * 15 / 1000)) return null
            val l = rc.left + rc.width() * x0 / bw - 0.01f
            val t = rc.top + rc.height() * y0 / bh - 0.006f
            val r = rc.left + rc.width() * (x1 + 1) / bw + 0.01f
            val b = rc.top + rc.height() * (y1 + 1) / bh + 0.006f
            return RectF(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        } catch (e: Throwable) {
            return null
        } finally {
            small.recycle()
        }
    }
}
