package de.pdfleser.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VocabEntry(
    val key: String,
    val word: String,
    val translation: String,
    val meanings: String,
    val phonetic: String,
    val definition: String,
    val context: String,
    val bookId: String,
    val bookTitle: String,
    val added: Long,
    val updated: Long,
    val count: Int
)

/** Vokabelliste aller nachgeschlagenen Wörter (buchübergreifend). */
object VocabStore {
    private var cache: ArrayList<VocabEntry>? = null
    val changes = MutableStateFlow(0)

    fun key(word: String): String = word.trim().lowercase(Locale.ROOT)

    private fun file(ctx: Context) = File(ctx.filesDir, "vocab.json")

    @Synchronized
    private fun list(ctx: Context): ArrayList<VocabEntry> {
        cache?.let { return it }
        val l = ArrayList<VocabEntry>()
        val f = file(ctx)
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    l.add(
                        VocabEntry(
                            o.optString("k"), o.optString("w"), o.optString("tr"), o.optString("m"),
                            o.optString("ph"), o.optString("d"), o.optString("c"), o.optString("b"),
                            o.optString("bt"), o.optLong("a"), o.optLong("u"), o.optInt("n", 1)
                        )
                    )
                }
            } catch (e: Exception) { }
        }
        cache = l
        return l
    }

    private fun save(ctx: Context, l: List<VocabEntry>) {
        val arr = JSONArray()
        for (e in l) {
            arr.put(
                JSONObject().put("k", e.key).put("w", e.word).put("tr", e.translation).put("m", e.meanings)
                    .put("ph", e.phonetic).put("d", e.definition).put("c", e.context).put("b", e.bookId)
                    .put("bt", e.bookTitle).put("a", e.added).put("u", e.updated).put("n", e.count)
            )
        }
        try {
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun all(ctx: Context): List<VocabEntry> = list(ctx).sortedByDescending { it.updated }

    @Synchronized
    fun contains(ctx: Context, word: String): Boolean {
        val k = key(word)
        return list(ctx).any { it.key == k }
    }

    @Synchronized
    fun upsert(ctx: Context, e: VocabEntry, countUp: Boolean) {
        val l = list(ctx)
        val i = l.indexOfFirst { it.key == e.key }
        if (i >= 0) {
            val o = l[i]
            l[i] = o.copy(
                word = e.word.ifEmpty { o.word },
                translation = e.translation.ifEmpty { o.translation },
                meanings = e.meanings.ifEmpty { o.meanings },
                phonetic = e.phonetic.ifEmpty { o.phonetic },
                definition = e.definition.ifEmpty { o.definition },
                context = e.context.ifEmpty { o.context },
                bookId = e.bookId.ifEmpty { o.bookId },
                bookTitle = e.bookTitle.ifEmpty { o.bookTitle },
                updated = e.updated,
                count = o.count + (if (countUp) 1 else 0)
            )
        } else {
            l.add(e)
        }
        save(ctx, l)
        changes.update { it + 1 }
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        if (list(ctx).removeAll { it.key == key }) {
            save(ctx, list(ctx))
            changes.update { it + 1 }
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        list(ctx).clear()
        save(ctx, emptyList())
        changes.update { it + 1 }
    }

    /** CSV (Semikolon, UTF-8) – lässt sich in Excel, Google Tabellen oder Anki importieren. */
    fun exportCsv(ctx: Context): File {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, "Vokabeln.csv")
        val df = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        val sb = StringBuilder("\uFEFF")
        sb.append(
            listOf(
                "Wort", "Übersetzung", "Weitere Bedeutungen", "Aussprache", "Definition (Englisch)",
                "Kontext", "Buch", "Nachgeschlagen", "Datum"
            ).joinToString(";") { q(it) }
        ).append("\r\n")
        for (e in all(ctx)) {
            sb.append(
                listOf(
                    e.word, e.translation, e.meanings, e.phonetic, e.definition, e.context,
                    e.bookTitle, e.count.toString(), df.format(Date(e.updated))
                ).joinToString(";") { q(it) }
            ).append("\r\n")
        }
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    private fun q(s: String): String = "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\""
}
