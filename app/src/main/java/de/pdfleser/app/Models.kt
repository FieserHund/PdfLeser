package de.pdfleser.app

/** Ein Absatz oder eine Abbildung aus dem PDF – die Grundeinheit des Fließtexts. */
sealed class Block {
    abstract val pdfPage: Int
}

data class TextBlock(
    val text: String,
    val heading: Boolean,
    /** true, wenn dieser Absatz die Fortsetzung des letzten Absatzes der Vorseite sein kann. */
    val joinPrev: Boolean,
    override val pdfPage: Int,
    /** Fußnote/Bildunterschrift (kleinere Schrift am Seitenende) */
    val note: Boolean = false,
    /** letzte Zeile reicht bis zum rechten Rand → Absatz läuft vermutlich auf der nächsten Seite weiter */
    val openEnd: Boolean = false
) : Block()

/** Abbildung als Ausschnitt einer PDF-Seite (relative Koordinaten 0..1). */
data class ImageBlock(
    override val pdfPage: Int,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    /** Breite / Höhe */
    val aspect: Float
) : Block()

/** Leseposition: Block-Index + Zeichen-Offset im Block. */
data class Pos(val block: Int, val char: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int =
        if (block != other.block) block.compareTo(other.block) else char.compareTo(other.char)

    override fun toString(): String = "$block:$char"

    companion object {
        fun parse(s: String?): Pos {
            if (s == null) return Pos(0, 0)
            val p = s.split(':')
            return Pos(p.getOrNull(0)?.toIntOrNull() ?: 0, p.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }
}

sealed class Frag {
    abstract val block: Int
    abstract val y: Float
}

/** Zeilen [startLine..endLine] des Layouts von Block [block], gezeichnet ab y. */
data class TextFrag(override val block: Int, val startLine: Int, val endLine: Int, override val y: Float) : Frag()

data class ImageFrag(override val block: Int, override val y: Float, val x: Float, val w: Float, val h: Float) : Frag()

data class Page(val frags: List<Frag>, val start: Pos)

/** Aktuelle Textauswahl: Block, Zeichenbereich, Text und der Satz, in dem sie steht. */
data class Selection(val block: Int, val start: Int, val end: Int, val text: String, val context: String)

data class TextRange(val block: Int, val start: Int, val end: Int)

data class TocEntry(val title: String, val pdfPage: Int, val level: Int)

/** Ergebnis einer Paginierung: Seiten + genau der Block-Stand, auf dem sie beruhen. */
class PageSet(val pages: List<Page>, val blocks: List<Block>, val spec: LayoutSpec?, val complete: Boolean) {
    companion object {
        val EMPTY = PageSet(emptyList(), emptyList(), null, false)
    }
}

fun pageIndexOf(pages: List<Page>, pos: Pos): Int {
    if (pages.isEmpty()) return 0
    var lo = 0
    var hi = pages.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (pages[mid].start <= pos) lo = mid else hi = mid - 1
    }
    return lo
}
