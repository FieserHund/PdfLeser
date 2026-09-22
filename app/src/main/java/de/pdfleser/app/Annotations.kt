package de.pdfleser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Bookmark(
    val id: String,
    val pos: Pos,
    val pdfPage: Int,
    val snippet: String,
    val created: Long
)

data class Highlight(
    val id: String,
    val block: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val pdfPage: Int,
    val created: Long
) {
    /** Steht der markierte Text noch genau an dieser Stelle? (Sicherheitsprüfung vor dem Zeichnen) */
    fun matches(blocks: List<Block>): Boolean {
        val b = blocks.getOrNull(block) as? TextBlock ?: return false
        return start >= 0 && end <= b.text.length && end > start && b.text.regionMatches(start, text, 0, text.length)
    }
}

/** Lesezeichen und Markierungen eines Buchs. Nur auf dem Hauptthread verändern. */
class Annotations(val bookId: String) {
    val bookmarks = ArrayList<Bookmark>()
    val highlights = ArrayList<Highlight>()
    var needsVerify = true
    private var byBlock: Map<Int, List<Highlight>> = emptyMap()

    fun reindex() {
        byBlock = highlights.groupBy { it.block }
    }

    fun highlightsFor(block: Int): List<Highlight> = byBlock[block] ?: emptyList()

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
}

object AnnotationStore {
    private fun file(ctx: Context, id: String) = File(Library.booksDir(ctx), "$id.notes.json")

    fun load(ctx: Context, id: String): Annotations {
        val a = Annotations(id)
        val f = file(ctx, id)
        if (f.exists()) {
            try {
                val o = JSONObject(f.readText())
                val bms = o.optJSONArray("bookmarks")
                if (bms != null) {
                    for (i in 0 until bms.length()) {
                        val b = bms.optJSONObject(i) ?: continue
                        a.bookmarks.add(
                            Bookmark(
                                b.optString("id"), Pos(b.optInt("b"), b.optInt("c")),
                                b.optInt("p"), b.optString("s"), b.optLong("t")
                            )
                        )
                    }
                }
                val hls = o.optJSONArray("highlights")
                if (hls != null) {
                    for (i in 0 until hls.length()) {
                        val h = hls.optJSONObject(i) ?: continue
                        a.highlights.add(
                            Highlight(
                                h.optString("id"), h.optInt("b"), h.optInt("s"), h.optInt("e"),
                                h.optString("x"), h.optInt("p"), h.optLong("t")
                            )
                        )
                    }
                }
            } catch (e: Exception) { }
        }
        a.reindex()
        return a
    }

    fun toJson(a: Annotations): String {
        val bms = JSONArray()
        for (b in a.bookmarks) {
            bms.put(
                JSONObject().put("id", b.id).put("b", b.pos.block).put("c", b.pos.char)
                    .put("p", b.pdfPage).put("s", b.snippet).put("t", b.created)
            )
        }
        val hls = JSONArray()
        for (h in a.highlights) {
            hls.put(
                JSONObject().put("id", h.id).put("b", h.block).put("s", h.start).put("e", h.end)
                    .put("x", h.text).put("p", h.pdfPage).put("t", h.created)
            )
        }
        return JSONObject().put("fmt", ContentStore.FORMAT).put("bookmarks", bms).put("highlights", hls).toString()
    }

    fun write(ctx: Context, id: String, json: String) {
        try {
            val f = file(ctx, id)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) { }
    }
}
