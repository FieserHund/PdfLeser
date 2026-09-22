package de.pdfleser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/** Zeigt eine PDF-Seite als Bild (für Scans oder wenn man das Originallayout sehen will). */
@SuppressLint("ViewConstructor")
class OriginalPageView(context: Context) : View(context) {

    interface Host {
        val colors: ThemeColors
        val originalInsets: Rect
        fun onTapZone(zone: Int)
        fun consumeTapForPopup(): Boolean
        fun cachedOriginal(index: Int, w: Int, h: Int): Bitmap?
        fun requestOriginal(view: OriginalPageView, index: Int, w: Int, h: Int)
        fun onOriginalLongPress()
        fun originalHasBookmark(index: Int): Boolean
    }

    var host: Host? = null
    var pageIndex = -1
        private set
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonPath = Path()
    private val density = resources.displayMetrics.density

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val h = host ?: return true
            if (h.consumeTapForPopup()) return true
            val zone = when {
                e.x < width * 0.3f -> -1
                e.x > width * 0.7f -> 1
                else -> 0
            }
            h.onTapZone(zone)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            host?.onOriginalLongPress()
        }
    })

    fun bind(index: Int) {
        pageIndex = index
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val h = host ?: return
        canvas.drawColor(h.colors.bg)
        if (pageIndex < 0 || width == 0 || height == 0) return
        val ins = h.originalInsets
        val aw = width - ins.left - ins.right
        val ah = height - ins.top - ins.bottom
        if (aw <= 0 || ah <= 0) return
        val bmp = h.cachedOriginal(pageIndex, aw, ah)
        if (bmp == null) {
            h.requestOriginal(this, pageIndex, aw, ah)
            return
        }
        paint.colorFilter = h.colors.pageFilter
        canvas.drawBitmap(bmp, ins.left + (aw - bmp.width) / 2f, ins.top + (ah - bmp.height) / 2f, paint)
        if (h.originalHasBookmark(pageIndex)) {
            val cx = width - ins.right - 24f * density
            val bottom = ins.top + 22f * density
            val w2 = 7f * density
            ribbonPath.reset()
            ribbonPath.moveTo(cx - w2, 0f)
            ribbonPath.lineTo(cx + w2, 0f)
            ribbonPath.lineTo(cx + w2, bottom)
            ribbonPath.lineTo(cx, bottom - 5f * density)
            ribbonPath.lineTo(cx - w2, bottom)
            ribbonPath.close()
            ribbonPaint.color = h.colors.ribbon
            canvas.drawPath(ribbonPath, ribbonPaint)
        }
    }
}
