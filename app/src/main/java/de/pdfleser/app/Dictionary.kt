package de.pdfleser.app

import android.util.LruCache
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale

data class Sense(val pos: String, val definition: String, val example: String)

data class DictEntry(val word: String, val phonetic: String, val senses: List<Sense>)

/**
 * Englisches Wörterbuch (einsprachig): Definitionen, Aussprache (IPA) und Beispielsätze.
 * Quelle 1: Free Dictionary API (dictionaryapi.dev), Quelle 2 (Ausweich): Wiktionary.
 * Findet auch Grundformen: "cats" → "cat", "running" → "run", "studied" → "study".
 */
object Dictionary {
    private const val UA = "PdfLeser/1.0 (private Android-App; Wörterbuch-Nachschlagen)"
    private val cache = LruCache<String, DictEntry>(200)
    private val notFound = Collections.synchronizedSet(HashSet<String>())

    /** null = nicht gefunden; IOException = keine Verbindung. */
    suspend fun lookup(raw: String): DictEntry? = withContext(Dispatchers.IO) {
        val q = raw.trim().trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        if (q.isEmpty() || q.split(' ').size > 3) return@withContext null
        cache.get(q)?.let { return@withContext it }
        if (q in notFound) return@withContext null

        var answered = false
        var netError: IOException? = null
        for (c in candidates(q)) {
            try {
                val e = freeDict(c)
                answered = true
                if (e != null) {
                    cache.put(q, e)
                    return@withContext e
                }
            } catch (e: IOException) {
                netError = e
            }
        }
        try {
            val e = wiktionary(q)
            answered = true
            if (e != null) {
                cache.put(q, e)
                return@withContext e
            }
        } catch (e: IOException) {
            netError = e
        }
        if (!answered && netError != null) throw netError
        notFound.add(q)
        null
    }

    private fun candidates(w: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(w)
        if (' ' !in w && w.length > 3) {
            if (w.endsWith("'s") || w.endsWith("’s")) out.add(w.dropLast(2))
            if (w.endsWith("ies") || w.endsWith("ied")) out.add(w.dropLast(3) + "y")
            if (w.endsWith("ing")) {
                val s = w.dropLast(3)
                out.add(s)
                out.add(s + "e")
                if (s.length > 2 && s[s.length - 1] == s[s.length - 2]) out.add(s.dropLast(1))
            }
            if (w.endsWith("ed")) {
                val s = w.dropLast(2)
                out.add(w.dropLast(1))
                out.add(s)
                if (s.length > 2 && s[s.length - 1] == s[s.length - 2]) out.add(s.dropLast(1))
            }
            if (w.endsWith("es")) out.add(w.dropLast(2))
            if (w.endsWith("s") && !w.endsWith("ss")) out.add(w.dropLast(1))
            if (w.endsWith("ly")) out.add(w.dropLast(2))
        }
        return out.take(4)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun get(url: URL): Pair<Int, String> {
        val con = url.openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 6000
            con.readTimeout = 8000
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Accept", "application/json")
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Pair(code, body)
        } finally {
            con.disconnect()
        }
    }

    private fun freeDict(w: String): DictEntry? {
        val (code, body) = get(URL("https://api.dictionaryapi.dev/api/v2/entries/en/" + enc(w)))
        if (code == 404) return null
        if (code != 200) throw IOException("HTTP $code")
        val arr = try { JSONArray(body) } catch (e: Exception) { return null }
        var phon = ""
        val senses = ArrayList<Sense>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (phon.isEmpty()) phon = e.optString("phonetic")
            if (phon.isEmpty()) {
                val ps = e.optJSONArray("phonetics")
                if (ps != null) {
                    for (j in 0 until ps.length()) {
                        val t = ps.optJSONObject(j)?.optString("text").orEmpty()
                        if (t.isNotEmpty()) {
                            phon = t
                            break
                        }
                    }
                }
            }
            val ms = e.optJSONArray("meanings") ?: continue
            for (j in 0 until ms.length()) {
                val m = ms.optJSONObject(j) ?: continue
                val pos = m.optString("partOfSpeech")
                val defs = m.optJSONArray("definitions") ?: continue
                for (k in 0 until minOf(defs.length(), 3)) {
                    val d = defs.optJSONObject(k) ?: continue
                    val text = d.optString("definition").trim()
                    if (text.isNotEmpty()) senses.add(Sense(pos, text, d.optString("example").trim()))
                }
            }
        }
        if (senses.isEmpty()) return null
        return DictEntry(w, phon, senses.take(8))
    }

    private fun wiktionary(w: String): DictEntry? {
        val (code, body) = get(URL("https://en.wiktionary.org/api/rest_v1/page/definition/" + enc(w.replace(' ', '_'))))
        if (code == 404) return null
        if (code != 200) throw IOException("HTTP $code")
        val o = try { JSONObject(body) } catch (e: Exception) { return null }
        val en = o.optJSONArray("en") ?: return null
        val senses = ArrayList<Sense>()
        for (i in 0 until en.length()) {
            val item = en.optJSONObject(i) ?: continue
            val pos = item.optString("partOfSpeech").lowercase(Locale.ROOT)
            val defs = item.optJSONArray("definitions") ?: continue
            var taken = 0
            for (k in 0 until defs.length()) {
                if (taken >= 3) break
                val d = defs.optJSONObject(k) ?: continue
                val text = strip(d.optString("definition"))
                if (text.isEmpty()) continue
                val ex = d.optJSONArray("examples")?.optString(0)?.let { strip(it) }.orEmpty()
                senses.add(Sense(pos, text, ex))
                taken++
            }
        }
        if (senses.isEmpty()) return null
        return DictEntry(w, "", senses.take(8))
    }

    private fun strip(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace('\n', ' ').trim()
}
