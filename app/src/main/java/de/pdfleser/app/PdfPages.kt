package de.pdfleser.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Dünner Wrapper um Androids eingebauten PDF-Renderer (pdfium).
 * Alle Aufrufe laufen über ein globales Lock, damit nie zwei Threads gleichzeitig rendern.
 */
class PdfPages(file: File) : Closeable {

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
    val pageCount: Int = renderer.pageCount
    private var closed = false

    override fun close() {
        synchronized(LOCK) {
            if (closed) return
            closed = true
            try { renderer.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
    }

    /** Ganze Seite, eingepasst in maxW × maxH. */
    fun renderFit(index: Int, maxW: Int, maxH: Int): Bitmap? = synchronized(LOCK) { renderFitLocked(index, maxW, maxH) }

    /** Ausschnitt rc (relativ 0..1) einer Seite in genau outW × outH Pixeln. */
    fun renderRegion(index: Int, rc: RectF, outW: Int, outH: Int): Bitmap? =
        synchronized(LOCK) { renderRegionLocked(index, rc, outW, outH) }

    /**
     * Rendert rc klein und schneidet weißen Rand ab.
     * Liefert null, wenn in dem Bereich praktisch nichts zu sehen ist.
     */
    fun trimRegion(index: Int, rc: RectF): RectF? = synchronized(LOCK) { trimRegionLocked(index, rc) }

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
