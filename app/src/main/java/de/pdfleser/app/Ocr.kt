package de.pdfleser.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/**
 * Texterkennung für gescannte Buchseiten – komplett auf dem Gerät (ML Kit, lateinische Schrift).
 * Liefert dieselben Zeilen-Objekte wie der PDF-Weg, damit Absätze, Überschriften und Fußnoten
 * danach mit genau derselben Logik erkannt werden.
 */
class Ocr : Closeable {

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            // Schon geschlossen – egal.
        }
    }

    /** Blockierend; nur im Hintergrund aufrufen. */
    fun recognize(bitmap: Bitmap): ArrayList<RawLine> {
        val text: Text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val out = ArrayList<RawLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                convert(line)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun convert(line: Text.Line): RawLine? {
        val box = line.boundingBox ?: return null
        if (box.width() <= 1 || box.height() <= 1) return null
        val words = line.elements.filter { (it.boundingBox?.height() ?: 0) > 0 }
        // Übliche Buchstabenhöhe der Zeile: der Median schützt vor einzelnen Ausreißern.
        val heights = words.mapNotNull { it.boundingBox?.height() }.sorted()
        val medianH = if (heights.isEmpty()) box.height() else heights[heights.size / 2]
        val size = medianH * 0.82f
        // Grundlinie ungefähr: Unterkante abzüglich der Unterlängen.
        val baseline = bottomOf(words, box) - medianH * 0.20f

        val raw = if (words.isEmpty()) line.text else assemble(words, medianH, baseline)
        val text = TextUtil.clean(raw)
        if (text.isEmpty()) return null
        val left = words.minOfOrNull { it.boundingBox?.left ?: box.left } ?: box.left
        val right = words.maxOfOrNull { it.boundingBox?.right ?: box.right } ?: box.right
        return RawLine(text, left.toFloat(), max(right.toFloat(), left + 1f), baseline, max(size, 1f))
    }

    private fun bottomOf(words: List<Text.Element>, box: Rect): Float {
        if (words.isEmpty()) return box.bottom.toFloat()
        // Der häufigste Unterkanten-Wert, damit hochgestellte Zeichen die Grundlinie nicht verziehen.
        val bottoms = words.mapNotNull { it.boundingBox?.bottom }.sorted()
        return bottoms[bottoms.size / 2].toFloat()
    }

    /**
     * Setzt die Zeile aus den erkannten Wörtern zusammen und markiert dabei hochgestellte
     * Ziffern als Quellenverweis: deutlich kleiner als der Rest und über der Grundlinie.
     */
    private fun assemble(words: List<Text.Element>, medianH: Int, baseline: Float): String {
        val sb = StringBuilder()
        for ((i, w) in words.withIndex()) {
            val b = w.boundingBox
            val t = w.text
            if (t.isEmpty()) continue
            val sup = b != null && t.length <= 6 && t.all { it.isDigit() || it in LineStripper.SUP_CHARS } &&
                t.any { it.isDigit() || it in "*†‡§¶+" } &&
                b.height() < medianH * 0.78f && b.bottom < baseline - medianH * 0.12f
            if (i > 0) {
                // Hochgestelltes klebt am Wort davor, sonst normaler Wortabstand.
                val prev = words[i - 1].boundingBox
                val tight = sup || (b != null && prev != null && b.left - prev.right < medianH * 0.25f)
                if (!tight) sb.append(' ')
            }
            if (sup) sb.append(TextUtil.REF_OPEN).append(t).append(TextUtil.REF_CLOSE) else sb.append(t)
        }
        return sb.toString()
    }

    companion object {
        /** Längste Kante, auf die Seitenfotos vor Speicherung und Erkennung verkleinert werden. */
        const val MAX_EDGE = 2200

        fun scaleDown(src: Bitmap, maxEdge: Int = MAX_EDGE): Bitmap {
            val longest = max(src.width, src.height)
            if (longest <= maxEdge) return src
            val f = maxEdge.toFloat() / longest
            val w = max(1, (src.width * f).toInt())
            val h = max(1, (src.height * f).toInt())
            val out = Bitmap.createScaledBitmap(src, w, h, true)
            if (out !== src) src.recycle()
            return out
        }

        /** Längste Kante der gespeicherten Seitenbilder (die Erkennung läuft vorher in voller Größe). */
        const val STORE_EDGE = 2600

        /** Verkleinerte Kopie; das Original bleibt unangetastet. */
        fun copyScaled(src: Bitmap, maxEdge: Int): Bitmap {
            val longest = max(src.width, src.height)
            if (longest <= maxEdge) return src
            val f = maxEdge.toFloat() / longest
            return Bitmap.createScaledBitmap(src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true)
        }

        /** Obergrenze für zusammengesetzte Seiten (mehrere Fotos übereinander). */
        const val MAX_MERGED_PIXELS = 6_000_000

        /**
         * Setzt mehrere Fotos einer Buchseite untereinander zu einem Bild zusammen.
         * Alle Teile werden auf dieselbe Breite gebracht; die Gesamtfläche bleibt begrenzt,
         * damit auch ein Mittelklasse-Handy die Texterkennung schafft.
         */
        fun mergeVertical(parts: List<Bitmap>): Bitmap? {
            if (parts.isEmpty()) return null
            if (parts.size == 1) return parts[0]
            var width = parts.minOf { it.width }
            if (width <= 0) return null
            var height = parts.sumOf { ((it.height.toLong() * width) / it.width).toInt() }
            if (height <= 0) return null
            val pixels = width.toLong() * height
            if (pixels > MAX_MERGED_PIXELS) {
                val f = kotlin.math.sqrt(MAX_MERGED_PIXELS.toDouble() / pixels).toFloat()
                width = max(1, (width * f).toInt())
                height = parts.sumOf { ((it.height.toLong() * width) / it.width).toInt() }
            }
            val out = Bitmap.createBitmap(width, max(1, height), Bitmap.Config.ARGB_8888)
            out.eraseColor(android.graphics.Color.WHITE)
            val canvas = android.graphics.Canvas(out)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
            var y = 0
            for (p in parts) {
                val hh = ((p.height.toLong() * width) / p.width).toInt()
                canvas.drawBitmap(
                    p, null,
                    android.graphics.RectF(0f, y.toFloat(), width.toFloat(), (y + hh).toFloat()),
                    paint
                )
                y += hh
            }
            return out
        }

        /** Verkleinerungsfaktor fürs Laden, damit nie das volle Kamerafoto im Speicher landet. */
        fun sampleSize(w: Int, h: Int, maxEdge: Int = MAX_EDGE): Int {
            var s = 1
            while (min(w, h) / (s * 2) >= 1 && max(w, h) / (s * 2) >= maxEdge) s *= 2
            return s
        }
    }
}
