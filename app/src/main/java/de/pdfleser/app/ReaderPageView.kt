package de.pdfleser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Magnifier
import kotlin.math.max
import kotlin.math.min

/**
 * Zeichnet eine Buchseite (Text, Bilder, Markierungen) direkt auf die Canvas.
 * Tippen links/rechts = blättern, Mitte = Menü, auf Markierung tippen = Markierung öffnen,
 * lange drücken = Wort auswählen (ziehen erweitert die Auswahl).
 */
@SuppressLint("ViewConstructor")
class ReaderPageView(context: Context) : View(context) {

    interface Host {
        val ui: UiLayouts?
        val pageSet: PageSet
        val colors: ThemeColors
        fun onTapZone(zone: Int)
        fun consumeTapForPopup(): Boolean
        fun onSelectionStarted(view: ReaderPageView)
        fun onSelectionCleared()
        fun onWordSelected(view: ReaderPageView, sel: Selection, top: Float, bottom: Float)
        fun highlightsFor(block: Int): List<Highlight>
        fun speakingRange(): TextRange?
        fun pageHasBookmark(pageIndex: Int): Boolean
        fun cachedImage(frag: ImageFrag, block: ImageBlock): Bitmap?
        fun requestImage(view: ReaderPageView, frag: ImageFrag, block: ImageBlock)
    }

    var host: Host? = null
    var pageIndex = -1
        private set
    private var page: Page? = null

    private var selFrag = -1
    private var selStart = 0
    private var selEnd = 0
    private var anchorStart = 0
    private var anchorEnd = 0
    private var selecting = false
    private var magnifier: Magnifier? = null

    private val density = resources.displayMetrics.density
    private val rangePath = Path()
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint()
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonPath = Path()
    private val dst = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            startSelection(e.x, e.y)
        }
    })

    fun bind(index: Int, p: Page?) {
        if (index == pageIndex && p == page) {
            invalidate()
            return
        }
        pageIndex = index
        page = p
        resetSelection()
        invalidate()
    }

    fun clearSelection() {
        resetSelection()
        invalidate()
    }

    private fun resetSelection() {
        selFrag = -1
        selStart = 0
        selEnd = 0
        if (selecting) parent?.requestDisallowInterceptTouchEvent(false)
        selecting = false
        magnifier?.dismiss()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        magnifier?.dismiss()
    }

    private fun handleTap(x: Float, y: Float) {
        val h = host ?: return
        if (selFrag >= 0) {
            clearSelection()
            h.onSelectionCleared()
            h.consumeTapForPopup()
            return
        }
        if (h.consumeTapForPopup()) return
        if (tapOnHighlight(x, y)) return
        val zone = when {
            x < width * 0.3f -> -1
            x > width * 0.7f -> 1
            else -> 0
        }
        h.onTapZone(zone)
    }

    /** Tippen auf eine Markierung öffnet sie (Übersetzung + „Markierung entfernen“). */
    private fun tapOnHighlight(x: Float, y: Float): Boolean {
        val h = host ?: return false
        val hit = hit(x, y, -1) ?: return false
        if (!hit.onText) return false
        val f = page?.frags?.getOrNull(hit.frag) as? TextFrag ?: return false
        val blocks = h.pageSet.blocks
        val hl = h.highlightsFor(f.block).firstOrNull {
            it.matches(blocks) && hit.offset >= it.start && hit.offset < it.end
        } ?: return false
        val lo = hit.layout.getLineStart(f.startLine)
        val hi = hit.layout.getLineEnd(f.endLine)
        selFrag = hit.frag
        selStart = max(hl.start, lo)
        selEnd = min(hl.end, hi)
        anchorStart = selStart
        anchorEnd = selEnd
        h.onSelectionStarted(this)
        invalidate()
        finishSelection()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    extendSelection(event.x, event.y)
                    showMagnifier(event.x, event.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    magnifier?.dismiss()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    finishSelection()
                }
            }
        }
        return true
    }

    private class Hit(val frag: Int, val layout: StaticLayout, val offset: Int, val onText: Boolean)

    private fun hit(x: Float, y: Float, onlyFrag: Int): Hit? {
        val p = page ?: return null
        val h = host ?: return null
        val ui = h.ui ?: return null
        val cx = x - ui.spec.left
        val cy = y - ui.spec.top
        for ((fi, f) in p.frags.withIndex()) {
            if (f !is TextFrag) continue
            if (onlyFrag >= 0 && fi != onlyFrag) continue
            val b = h.pageSet.blocks.getOrNull(f.block) as? TextBlock ?: continue
            val layout = ui.get(f.block, b)
            if (f.endLine >= layout.lineCount) continue
            val top = layout.getLineTop(f.startLine).toFloat()
            val fragH = layout.getLineBottom(f.endLine) - top
            if (onlyFrag < 0 && (cy < f.y || cy > f.y + fragH)) continue
            val ly = (cy - f.y + top).coerceIn(top, top + fragH - 1f)
            val line = layout.getLineForVertical(ly.toInt()).coerceIn(f.startLine, f.endLine)
            val off = layout.getOffsetForHorizontal(line, cx)
            val slack = 6f * density
            val onText = cx >= layout.getLineLeft(line) - slack && cx <= layout.getLineRight(line) + slack
            return Hit(fi, layout, off, onText)
        }
        return null
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    private fun wordAt(text: CharSequence, offset: Int): IntArray? {
        if (text.isEmpty()) return null
        var o = offset.coerceIn(0, text.length - 1)
        if (!isWordChar(text[o]) && o > 0 && isWordChar(text[o - 1])) o--
        if (!isWordChar(text[o])) return null
        var s = o
        var e = o + 1
        while (s > 0 && isWordChar(text[s - 1])) s--
        while (e < text.length && isWordChar(text[e])) e++
        while (s < e && !text[s].isLetterOrDigit()) s++
        while (e > s && !text[e - 1].isLetterOrDigit()) e--
        return if (e > s) intArrayOf(s, e) else null
    }

    private fun startSelection(x: Float, y: Float) {
        val h = host ?: return
        val hit = hit(x, y, -1) ?: return
        val w = wordAt(hit.layout.text, hit.offset) ?: return
        selFrag = hit.frag
        anchorStart = w[0]
        anchorEnd = w[1]
        selStart = w[0]
        selEnd = w[1]
        selecting = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        h.onSelectionStarted(this)
        showMagnifier(x, y)
        invalidate()
    }

    private fun extendSelection(x: Float, y: Float) {
        if (selFrag < 0) return
        val f = page?.frags?.getOrNull(selFrag) as? TextFrag ?: return
        val hit = hit(x, y, selFrag) ?: return
        val layout = hit.layout
        val lo = layout.getLineStart(f.startLine)
        val hi = layout.getLineEnd(f.endLine)
        val w = wordAt(layout.text, hit.offset.coerceIn(lo, maxOf(lo, hi - 1)))
        val ws = w?.get(0) ?: hit.offset
        val we = w?.get(1) ?: hit.offset
        val ns = minOf(anchorStart, ws).coerceAtLeast(lo)
        val ne = maxOf(anchorEnd, we).coerceAtMost(hi)
        if (ns != selStart || ne != selEnd) {
            selStart = ns
            selEnd = ne
            invalidate()
        }
    }

    private fun finishSelection() {
        val h = host ?: return
        val ui = h.ui ?: return
        val f = page?.frags?.getOrNull(selFrag) as? TextFrag ?: return
        val b = h.pageSet.blocks.getOrNull(f.block) as? TextBlock ?: return
        if (selEnd <= selStart || selEnd > b.text.length) return
        val layout = ui.get(f.block, b)
        val text = b.text.substring(selStart, selEnd).replace('\n', ' ').trim()
        if (text.isEmpty()) return
        val base = layout.getLineTop(f.startLine)
        val l0 = layout.getLineForOffset(selStart)
        val l1 = layout.getLineForOffset(maxOf(selStart, selEnd - 1))
        val top = ui.spec.top + f.y + layout.getLineTop(l0) - base
        val bottom = ui.spec.top + f.y + layout.getLineBottom(l1) - base
        val sel = Selection(f.block, selStart, selEnd, text, TextUtil.contextSentence(b.text, selStart, selEnd))
        h.onWordSelected(this, sel, top, bottom)
    }

    private fun showMagnifier(x: Float, y: Float) {
        val m = magnifier ?: Magnifier.Builder(this).build().also { magnifier = it }
        m.show(x, y)
    }

    private fun drawRange(canvas: Canvas, layout: StaticLayout, lo: Int, hi: Int, s: Int, e: Int, color: Int) {
        val a = max(s, lo)
        val b = min(e, hi)
        if (b <= a) return
        rangePath.reset()
        layout.getSelectionPath(a, b, rangePath)
        rangePaint.color = color
        canvas.drawPath(rangePath, rangePaint)
    }

    override fun onDraw(canvas: Canvas) {
        val h = host ?: return
        val c = h.colors
        canvas.drawColor(c.bg)
        val p = page ?: return
        val ui = h.ui ?: return
        val blocks = h.pageSet.blocks
        val left = ui.spec.left.toFloat()
        val top0 = ui.spec.top.toFloat()
        val speaking = h.speakingRange()
        for (fi in p.frags.indices) {
            val f = p.frags[fi]
            if (f is TextFrag) {
                val b = blocks.getOrNull(f.block) as? TextBlock ?: continue
                val layout = ui.get(f.block, b)
                if (f.endLine >= layout.lineCount) continue
                val lt = layout.getLineTop(f.startLine).toFloat()
                val lb = layout.getLineBottom(f.endLine).toFloat()
                val lo = layout.getLineStart(f.startLine)
                val hi = layout.getLineEnd(f.endLine)
                canvas.save()
                canvas.translate(left, top0 + f.y - lt)
                canvas.clipRect(0f, lt, layout.width.toFloat(), lb)
                for (hl in h.highlightsFor(f.block)) {
                    if (hl.matches(blocks)) drawRange(canvas, layout, lo, hi, hl.start, hl.end, c.highlight)
                }
                if (speaking != null && speaking.block == f.block) {
                    drawRange(canvas, layout, lo, hi, speaking.start, speaking.end, c.speaking)
                }
                if (fi == selFrag && selEnd > selStart) {
                    drawRange(canvas, layout, lo, hi, selStart, selEnd, c.selection)
                }
                layout.draw(canvas)
                canvas.restore()
            } else if (f is ImageFrag) {
                val b = blocks.getOrNull(f.block) as? ImageBlock ?: continue
                dst.set(left + f.x, top0 + f.y, left + f.x + f.w, top0 + f.y + f.h)
                val bmp = h.cachedImage(f, b)
                if (bmp != null) {
                    imgPaint.colorFilter = c.imageFilter
                    canvas.drawBitmap(bmp, null, dst, imgPaint)
                } else {
                    placeholderPaint.color = c.placeholder
                    canvas.drawRect(dst, placeholderPaint)
                    h.requestImage(this, f, b)
                }
            }
        }
        if (h.pageHasBookmark(pageIndex)) {
            val rightMargin = width - ui.spec.left - ui.spec.width
            drawRibbon(canvas, width - max(rightMargin / 2f, 18f * density), top0 + 6f * density, c.ribbon)
        }
    }

    private fun drawRibbon(canvas: Canvas, cx: Float, bottom: Float, color: Int) {
        val w2 = 7f * density
        val notch = 5f * density
        ribbonPath.reset()
        ribbonPath.moveTo(cx - w2, 0f)
        ribbonPath.lineTo(cx + w2, 0f)
        ribbonPath.lineTo(cx + w2, bottom)
        ribbonPath.lineTo(cx, bottom - notch)
        ribbonPath.lineTo(cx - w2, bottom)
        ribbonPath.close()
        ribbonPaint.color = color
        canvas.drawPath(ribbonPath, ribbonPaint)
    }
}
