package de.pdfleser.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale

data class Translation(val source: String, val text: String, val dict: List<Pair<String, String>>)

/** Das Handy hat gerade gar keine Verbindung. */
class NoNetworkException : IOException("Keine Internetverbindung.")

/** Der Dienst blockiert vorübergehend (zu viele Anfragen von dieser IP-Adresse). */
class RateLimitedException :
    IOException("Der Übersetzer blockiert gerade weitere Anfragen. In ein paar Minuten geht es wieder.")

class ServiceException(msg: String) : IOException(msg)

/**
 * Übersetzung Englisch → Deutsch, ohne API-Schlüssel.
 *
 * Es werden nacheinander mehrere Dienste versucht, denn die kostenlosen Google-Endpunkte
 * sperren eine IP-Adresse zeitweise, wenn in kurzer Zeit viele Wörter nachgeschlagen werden
 * (Antwort 429). Fällt einer aus, springt der nächste ein.
 *
 * Jede erfolgreiche Übersetzung wird dauerhaft auf dem Gerät gespeichert; bereits
 * nachgeschlagene Wörter funktionieren dadurch auch ohne Internet.
 */
object Translator {
    private const val SOURCE = "en"
    private const val TARGET = "de"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val MAX_DISK = 4000

    private val cache = LruCache<String, Translation>(300)
    private var disk: HashMap<String, Translation>? = null
    private val lock = Any()

    private fun key(s: String): String = s.trim().lowercase(Locale.ROOT)

    /* ------------------------------------------------------------------ */
    /*  Öffentliche Schnittstelle                                          */
    /* ------------------------------------------------------------------ */

    suspend fun translate(ctx: Context, query: String): Translation = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext Translation(q, "", emptyList())
        val k = key(q)

        cache.get(k)?.let { return@withContext it }
        diskGet(ctx, k)?.let {
            cache.put(k, it)
            return@withContext it
        }
        if (!online(ctx)) throw NoNetworkException()

        var rateLimited = false
        var offline = false
        var lastError: String? = null

        for (provider in PROVIDERS) {
            try {
                val t = provider(q)
                if (t != null && t.text.isNotEmpty()) {
                    cache.put(k, t)
                    diskPut(ctx, k, t)
                    return@withContext t
                }
                lastError = "Der Dienst hat keine Übersetzung geliefert."
            } catch (e: RateLimitedException) {
                rateLimited = true
            } catch (e: UnknownHostException) {
                offline = true
            } catch (e: IOException) {
                lastError = e.message
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        when {
            offline && lastError == null && !rateLimited -> throw NoNetworkException()
            rateLimited -> throw RateLimitedException()
            else -> throw ServiceException(lastError ?: "Übersetzung gerade nicht möglich.")
        }
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

    /** Hat das Handy gerade überhaupt eine nutzbare Verbindung? */
    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        true // Im Zweifel einfach versuchen.
    }

    /* ------------------------------------------------------------------ */
    /*  Dienste (in dieser Reihenfolge)                                    */
    /* ------------------------------------------------------------------ */

    private val PROVIDERS: List<(String) -> Translation?> = listOf(
        { q -> google("https://translate.googleapis.com/translate_a/single", q) },
        { q -> google("https://clients5.google.com/translate_a/single", q) },
        { q -> myMemory(q) }
    )

    /** Google-Webdienst: liefert Übersetzung und Wörterbuch-Einträge nach Wortart. */
    private fun google(host: String, q: String): Translation? {
        val body = get(
            "$host?client=gtx&sl=$SOURCE&tl=$TARGET&dt=t&dt=bd&dj=1&q=" + enc(q)
        )
        val o = JSONObject(body)
        val sb = StringBuilder()
        val sentences = o.optJSONArray("sentences")
        if (sentences != null) {
            for (i in 0 until sentences.length()) {
                sb.append(sentences.optJSONObject(i)?.optString("trans").orEmpty())
            }
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) return null

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
        return Translation(q, text, dict)
    }

    /** MyMemory: freier Übersetzungsspeicher, springt ein, wenn Google blockt. */
    private fun myMemory(q: String): Translation? {
        val body = get("https://api.mymemory.translated.net/get?langpair=$SOURCE%7C$TARGET&q=" + enc(q))
        val o = JSONObject(body)
        val status = o.opt("responseStatus")
        val code = (status as? Int) ?: (status as? String)?.toIntOrNull() ?: 200
        if (code == 429) throw RateLimitedException()
        if (code >= 400) throw ServiceException("Übersetzungsdienst meldet Fehler $code.")
        val text = unescape(o.optJSONObject("responseData")?.optString("translatedText").orEmpty()).trim()
        if (text.isEmpty() || text.equals(q, ignoreCase = true)) return null

        // Weitere Varianten aus den Treffern als "Bedeutungen" anbieten.
        val others = LinkedHashSet<String>()
        val matches = o.optJSONArray("matches")
        if (matches != null) {
            for (i in 0 until minOf(matches.length(), 8)) {
                val m = matches.optJSONObject(i) ?: continue
                val t = unescape(m.optString("translation")).trim()
                if (t.isNotEmpty() && !t.equals(text, ignoreCase = true) && t.length < 60) others.add(t)
            }
        }
        val dict = if (others.isEmpty()) emptyList()
        else listOf(Pair("Weitere Treffer", others.take(5).joinToString(", ")))
        return Translation(q, text, dict)
    }

    /* ------------------------------------------------------------------ */
    /*  HTTP                                                               */
    /* ------------------------------------------------------------------ */

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 7000
            con.readTimeout = 9000
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Accept", "application/json, text/plain, */*")
            con.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
            val code = con.responseCode
            if (code == 429 || code == 403 || code == 503) throw RateLimitedException()
            if (code != 200) throw ServiceException("Dienst antwortet mit Fehler $code.")
            return con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    private fun unescape(s: String): String = s
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun posDe(pos: String): String = when (pos.lowercase(Locale.ROOT)) {
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

    /* ------------------------------------------------------------------ */
    /*  Dauerhafter Speicher: einmal übersetzt, immer verfügbar            */
    /* ------------------------------------------------------------------ */

    private fun file(ctx: Context) = File(ctx.filesDir, "translations.json")

    private fun load(ctx: Context): HashMap<String, Translation> {
        disk?.let { return it }
        val map = HashMap<String, Translation>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) {
                    val e = o.optJSONObject(k) ?: continue
                    val dict = ArrayList<Pair<String, String>>()
                    val d = e.optJSONArray("d")
                    if (d != null) {
                        for (i in 0 until d.length()) {
                            val p = d.optJSONArray(i) ?: continue
                            dict.add(Pair(p.optString(0), p.optString(1)))
                        }
                    }
                    map[k] = Translation(e.optString("s", k), e.optString("t"), dict)
                }
            }
        } catch (e: Exception) {
            // Beschädigte Datei einfach verwerfen.
        }
        disk = map
        return map
    }

    private fun diskGet(ctx: Context, k: String): Translation? = synchronized(lock) { load(ctx)[k] }

    private fun diskPut(ctx: Context, k: String, t: Translation) {
        synchronized(lock) {
            val map = load(ctx)
            map[k] = t
            if (map.size > MAX_DISK) {
                val drop = map.keys.take(map.size - MAX_DISK * 3 / 4)
                drop.forEach { map.remove(it) }
            }
            try {
                val o = JSONObject()
                for ((key, v) in map) {
                    val d = JSONArray()
                    for (p in v.dict) d.put(JSONArray().put(p.first).put(p.second))
                    o.put(key, JSONObject().put("s", v.source).put("t", v.text).put("d", d))
                }
                val f = file(ctx)
                val tmp = File(f.path + ".tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (e: Exception) {
                // Der Speicher ist nur eine Beschleunigung – Fehler sind egal.
            }
        }
    }
}
