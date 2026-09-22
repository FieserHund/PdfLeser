package de.pdfleser.app

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import java.util.Locale
import kotlin.math.max

/** Alles, was den Seitenumbruch beeinflusst. Ändert sich etwas davon, wird neu paginiert. */
data class LayoutSpec(
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
    val textSizePx: Float,
    val lineMult: Float,
    val font: Int,
    val justify: Boolean,
    val lang: String
)

fun typefaceFor(font: Int, bold: Boolean): Typeface {
    val base = when (font) {
        1 -> Typeface.SANS_SERIF
        2 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        else -> Typeface.SERIF
    }
    return if (bold) Typeface.create(base, Typeface.BOLD) else base
}

class LayoutFactory(val spec: LayoutSpec) {
    private val locale: Locale = Locale.forLanguageTag(spec.lang.ifEmpty { "en" })

    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spec.textSizePx
        typeface = typefaceFor(spec.font, false)
        textLocale = locale
    }
    val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spec.textSizePx * 1.3f
        typeface = typefaceFor(spec.font, true)
        textLocale = locale
    }
    val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spec.textSizePx * 0.82f
        typeface = typefaceFor(spec.font, false)
        textLocale = locale
    }
    val lineHeight: Float = (bodyPaint.fontMetrics.descent - bodyPaint.fontMetrics.ascent) * spec.lineMult

    private val paraGap = spec.textSizePx * 0.55f
    private val headGapBefore = spec.textSizePx * 1.4f
    private val headGapAfter = spec.textSizePx * 0.45f
    private val imageGap = spec.textSizePx * 0.9f

    fun build(b: TextBlock): StaticLayout {
        val paint = when {
            b.heading -> headPaint
            b.note -> notePaint
            else -> bodyPaint
        }
        val justify = spec.justify && !b.heading
        return StaticLayout.Builder.obtain(b.text, 0, b.text.length, paint, max(1, spec.width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, if (b.heading) 1.15f else spec.lineMult)
            .setIncludePad(false)
            .setUseLineSpacingFromFallbacks(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(if (justify) HYPHENATION else Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(if (justify) LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .build()
    }

    companion object {
        /** Ab Android 13 gibt es eine deutlich schnellere Silbentrennung. */
        private val HYPHENATION =
            if (Build.VERSION.SDK_INT >= 33) Layout.HYPHENATION_FREQUENCY_NORMAL_FAST
            else Layout.HYPHENATION_FREQUENCY_NORMAL
    }

    fun gapBefore(blocks: List<Block>, i: Int): Float {
        val b = blocks[i]
        val prev = if (i > 0) blocks[i - 1] else null
        return when {
            b is ImageBlock || prev is ImageBlock -> imageGap
            b is TextBlock && b.heading -> headGapBefore
            prev is TextBlock && prev.heading -> headGapAfter
            else -> paraGap
        }
    }
}

/** Verteilt Absätze und Bilder auf Bildschirmseiten (wie bei einem E-Book). */
class Paginator(private val f: LayoutFactory) {

    fun paginate(blocks: List<Block>, from: Pos, out: MutableList<Page>, check: () -> Unit, onPage: () -> Unit) {
        val pageH = f.spec.height.toFloat()
        val pageW = f.spec.width.toFloat()
        var frags = ArrayList<Frag>()
        var y = 0f
        var start = from

        fun flush() {
            if (frags.isEmpty()) return
            out.add(Page(frags, start))
            frags = ArrayList()
            y = 0f
            onPage()
        }

        for (i in from.block until blocks.size) {
            check()
            when (val b = blocks[i]) {
                is TextBlock -> {
                    if (b.text.isEmpty()) continue
                    val layout = f.build(b)
                    val n = layout.lineCount
                    var line = if (i == from.block) layout.getLineForOffset(from.char.coerceIn(0, b.text.length)) else 0
                    while (line < n) {
                        var gap = if (frags.isEmpty() || line > 0) 0f else f.gapBefore(blocks, i)
                        // Überschrift nicht allein am Seitenende stehen lassen.
                        if (b.heading && line == 0 && frags.isNotEmpty() &&
                            y + gap + layout.height + 2f * f.lineHeight > pageH
                        ) {
                            flush()
                            gap = 0f
                        }
                        val top = layout.getLineTop(line).toFloat()
                        val avail = pageH - y - gap
                        var end = line
                        while (end < n && layout.getLineBottom(end) - top <= avail) end++
                        if (end == line) {
                            if (frags.isEmpty()) {
                                end = line + 1
                            } else {
                                flush()
                                continue
                            }
                        }
                        // Keine einzelne erste Zeile eines Absatzes am Seitenende.
                        if (line == 0 && end == 1 && n > 1 && frags.isNotEmpty()) {
                            flush()
                            continue
                        }
                        // Keine einzelne letzte Zeile oben auf der nächsten Seite.
                        if (end == n - 1 && end - line >= 3) end--
                        if (frags.isEmpty()) start = Pos(i, layout.getLineStart(line))
                        frags.add(TextFrag(i, line, end - 1, y + gap))
                        y += gap + (layout.getLineBottom(end - 1) - top)
                        line = end
                        if (line < n) flush()
                    }
                }
                is ImageBlock -> {
                    var w = pageW
                    var h = w / b.aspect
                    if (h > pageH) {
                        h = pageH
                        w = h * b.aspect
                    }
                    var gap = if (frags.isEmpty()) 0f else f.gapBefore(blocks, i)
                    if (frags.isNotEmpty() && y + gap + h > pageH) {
                        val rem = pageH - y - gap
                        if (rem >= h * 0.75f && rem > pageH * 0.3f) {
                            h = rem
                            w = h * b.aspect
                        } else {
                            flush()
                            gap = 0f
                        }
                    }
                    if (frags.isEmpty()) start = Pos(i, 0)
                    frags.add(ImageFrag(i, y + gap, (pageW - w) / 2f, w, h))
                    y += gap + h
                }
            }
        }
        flush()
    }
}

/** Layout-Cache für die Anzeige (Hauptthread). */
class UiLayouts(val spec: LayoutSpec) {
    val factory = LayoutFactory(spec)
    private val cache = LruCache<Int, Pair<TextBlock, StaticLayout>>(120)

    fun get(index: Int, b: TextBlock): StaticLayout {
        val c = cache.get(index)
        if (c != null && c.first === b) return c.second
        val l = factory.build(b)
        cache.put(index, Pair(b, l))
        return l
    }

    fun setColor(color: Int) {
        factory.bodyPaint.color = color
        factory.headPaint.color = color
        factory.notePaint.color = color
    }
}

private val NON_LETTERS = Regex("[^\\p{L}]+")
private val EN_WORDS = hashSetOf("the", "and", "of", "to", "is", "that", "with", "for", "was", "this")
private val DE_WORDS = hashSetOf("der", "die", "das", "und", "ist", "nicht", "mit", "ein", "eine", "zu", "den", "sich")

/** Grobe Spracherkennung für die richtige Silbentrennung. */
fun detectLang(blocks: List<Block>): Pair<String, Int> {
    val sb = StringBuilder()
    for (b in blocks) {
        if (b is TextBlock) {
            sb.append(' ').append(b.text)
            if (sb.length > 40000) break
        }
    }
    var en = 0
    var de = 0
    for (w in NON_LETTERS.split(sb.toString().lowercase())) {
        if (w in EN_WORDS) en++ else if (w in DE_WORDS) de++
    }
    return Pair(if (de > en) "de" else "en", sb.length)
}
