package de.pdfleser.app

/**
 * Findet zu einem Quellenverweis die passende Quelle: zuerst eine Fußnote auf derselben Seite,
 * sonst einen Eintrag im Literaturverzeichnis bzw. in den Anmerkungen (Endnoten) des Buchs.
 */
object NoteResolver {

    /** Überschriften, unter denen Quellen gesammelt stehen. */
    private val HEADING = Regex(
        """^(?:[\dIVX]+(?:\.\d+)*\.?\s+)?(references?(?:\s+and\s+notes)?|notes(?:\s+and\s+references)?|""" +
            """literatur(?:verzeichnis|angaben)?|zitierte\s+literatur|bibliogra(?:phy|phie|fie)|""" +
            """anmerkungen|endnoten|fußnoten|quellen(?:verzeichnis|angaben)?|works\s+cited|""" +
            """literature\s+cited|referenzen|notes)\s*:?$""",
        RegexOption.IGNORE_CASE
    )

    /** So beginnt ein Eintrag: [12] · (12) · 12. · 12) · 12 Großbuchstabe */
    private val ENTRY = listOf(
        Regex("""^\[(\d{1,4})]\s*"""),
        Regex("""^\((\d{1,4})\)\s*"""),
        Regex("""^(\d{1,4})[.)]\s+"""),
        Regex("""^(\d{1,4})\s+(?=\p{Lu})""")
    )
    private val STRONG_ENTRY = Regex("""^\[(\d{1,4})]\s*""")

    /** Weitere Einträge mitten im selben Absatz („… 2019. [13] Müller …“). */
    private val INNER = Regex("""(?<=\s)\[(\d{1,4})]\s""")

    private val RANGE = Regex("""^(\d{1,4})\s*[–—-]\s*(\d{1,4})$""")

    /**
     * Zerlegt einen Verweis in einzelne Nummern: „12“ → [12], „1,2“ → [1, 2], „3–5“ → [3, 4, 5],
     * „[7]“ → [7], „*“ → [*].
     */
    fun markers(raw: String): List<String> {
        val s = raw.filterNot { it == '[' || it == ']' || it == '(' || it == ')' || it == ' ' }.trim(',', ';', '.')
        if (s.isEmpty()) return emptyList()
        if (s.all { it in "*†‡§¶" }) return listOf(s)
        val out = ArrayList<String>()
        for (part in s.split(',', ';')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val r = RANGE.find(p)
            if (r != null) {
                val a = r.groupValues[1].toInt()
                val b = r.groupValues[2].toInt()
                if (b >= a && b - a <= 12) for (n in a..b) out.add(n.toString())
                else {
                    out.add(a.toString())
                    out.add(b.toString())
                }
            } else if (p.all { it.isDigit() }) {
                out.add(p.toInt().toString())
            } else {
                out.add(p)
            }
        }
        return out.distinct().take(12)
    }

    private fun entryStart(t: String, strongOnly: Boolean): Pair<String, Int>? {
        val list = if (strongOnly) listOf(STRONG_ENTRY) else ENTRY
        for (re in list) {
            val m = re.find(t) ?: continue
            return Pair(m.groupValues[1].toInt().toString(), m.value.length)
        }
        return null
    }

    /**
     * Sammelt alle Einträge aus Literaturverzeichnissen und Anmerkungsteilen.
     * Ohne passende Überschrift zählen nur unverwechselbare „[12] …“-Absätze im hinteren Teil.
     */
    fun build(blocks: List<Block>): List<Footnote> {
        val out = ArrayList<Footnote>()
        var inSection = false
        var lastWasEntry = false
        val tailStart = (blocks.size * 0.6f).toInt()
        for ((i, b) in blocks.withIndex()) {
            if (b !is TextBlock) continue
            val t = b.text.trim()
            if (t.length <= 60 && HEADING.matches(t)) {
                inSection = true
                lastWasEntry = false
                continue
            }
            if (inSection && b.heading) inSection = false
            if (!inSection && i < tailStart) {
                lastWasEntry = false
                continue
            }

            // Nummer am Absatzanfang: hochgestellt (als Verweis an Position 0) oder als Text.
            var marker: String? = null
            var bodyStart = 0
            val lead = b.refs.firstOrNull { it.start == 0 && !it.sub }
            if (lead != null) {
                val m = markers(lead.marker).singleOrNull()
                if (m != null) {
                    marker = m
                    bodyStart = lead.end
                }
            }
            if (marker == null) {
                entryStart(b.text, strongOnly = !inSection)?.let {
                    marker = it.first
                    bodyStart = it.second
                }
            }

            if (marker == null) {
                // Fortsetzung des vorigen Eintrags (Seitenwechsel mitten in einer Quelle).
                if (inSection && lastWasEntry && out.isNotEmpty() && !b.heading) {
                    val prev = out[out.size - 1]
                    out[out.size - 1] = prev.copy(text = TextUtil.join(prev.text, t))
                }
                continue
            }

            // Mehrere Einträge in einem Absatz an „[n]“ trennen (aufsteigende Nummern).
            var current = marker!!
            var from = bodyStart.coerceIn(0, b.text.length)
            var expect = current.toIntOrNull() ?: -1
            for (m in INNER.findAll(b.text)) {
                if (m.range.first < from) continue
                val n = m.groupValues[1].toInt()
                if (expect < 0 || n != expect + 1) continue
                val piece = b.text.substring(from, m.range.first).trim()
                if (piece.isNotEmpty()) out.add(Footnote(b.pdfPage, current, piece))
                current = n.toString()
                expect = n
                from = m.range.last + 1
            }
            val rest = b.text.substring(from).trim()
            if (rest.isNotEmpty()) out.add(Footnote(b.pdfPage, current, rest))
            lastWasEntry = true
        }
        return out
    }

    /**
     * Die passende Quelle zu einer Nummer: Fußnote auf derselben oder einer Nachbarseite,
     * dann der nächste Eintrag im Literaturverzeichnis hinter der Stelle, zuletzt irgendein Treffer.
     */
    fun pick(marker: String, page: Int, pageNotes: List<Footnote>, endNotes: List<Footnote>): Footnote? {
        var best: Footnote? = null
        var bestDist = Int.MAX_VALUE
        for (f in pageNotes) {
            if (f.marker != marker) continue
            val d = kotlin.math.abs(f.pdfPage - page)
            if (d < bestDist) {
                bestDist = d
                best = f
            }
        }
        if (best != null && bestDist <= 2) return best
        val following = endNotes.firstOrNull { it.marker == marker && it.pdfPage >= page }
        if (following != null) return following
        val anyEnd = endNotes.lastOrNull { it.marker == marker }
        return anyEnd ?: best
    }
}
