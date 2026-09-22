package de.pdfleser.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Translation(val source: String, val text: String, val dict: List<Pair<String, String>>)

/**
 * Übersetzung Englisch → Deutsch über den Google-Translate-Webdienst (kein API-Schlüssel nötig).
 * Liefert neben der Übersetzung auch Wörterbuch-Einträge (weitere Bedeutungen nach Wortart).
 */
object Translator {
    private const val SOURCE = "en"
    private const val TARGET = "de"
    private val cache = LruCache<String, Translation>(300)

    suspend fun translate(query: String): Translation = withContext(Dispatchers.IO) {
        val q = query.trim()
        cache.get(q)?.let { return@withContext it }
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$SOURCE&tl=$TARGET" +
                "&dt=t&dt=bd&dj=1&q=" + URLEncoder.encode(q, "UTF-8")
        )
        val con = url.openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 6000
            con.readTimeout = 8000
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) PdfLeser")
            if (con.responseCode != 200) throw IOException("HTTP ${con.responseCode}")
            val body = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(body)

            val sb = StringBuilder()
            val sentences = o.optJSONArray("sentences")
            if (sentences != null) {
                for (i in 0 until sentences.length()) {
                    sb.append(sentences.optJSONObject(i)?.optString("trans").orEmpty())
                }
            }

            val dict = ArrayList<Pair<String, String>>()
            val d = o.optJSONArray("dict")
            if (d != null) {
                for (i in 0 until d.length()) {
                    val e = d.optJSONObject(i) ?: continue
                    val terms = e.optJSONArray("terms") ?: continue
                    val list = (0 until minOf(terms.length(), 6)).map { terms.optString(it) }.filter { it.isNotBlank() }
                    if (list.isNotEmpty()) dict.add(Pair(posDe(e.optString("pos")), list.joinToString(", ")))
                }
            }
            val t = Translation(q, sb.toString().trim(), dict)
            cache.put(q, t)
            t
        } finally {
            con.disconnect()
        }
    }

    private fun posDe(pos: String): String = when (pos.lowercase()) {
        "noun" -> "Substantiv"
        "verb" -> "Verb"
        "adjective" -> "Adjektiv"
        "adverb" -> "Adverb"
        "preposition" -> "Präposition"
        "pronoun" -> "Pronomen"
        "conjunction" -> "Konjunktion"
        "interjection" -> "Interjektion"
        "article" -> "Artikel"
        "abbreviation" -> "Abkürzung"
        "phrase" -> "Wendung"
        "" -> "Bedeutungen"
        else -> pos
    }

    /** Öffnet den Text in der Google-Übersetzer-App (oder, falls nicht installiert, im Browser). */
    fun openExternal(ctx: Context, text: String) {
        val app = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .setPackage("com.google.android.apps.translate")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        try {
            ctx.startActivity(app)
        } catch (e: ActivityNotFoundException) {
            val web = Uri.parse(
                "https://translate.google.com/?sl=$SOURCE&tl=$TARGET&op=translate&text=" + Uri.encode(text)
            )
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, web))
            } catch (e2: Exception) {
                // Kein Browser – nichts zu tun.
            }
        }
    }
}
