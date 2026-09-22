package de.pdfleser.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Long, val title: String, val notes: String, val apkUrl: String, val size: Long)

/**
 * Updates ohne Kabel: GitHub baut bei jeder Änderung automatisch eine neue APK und
 * veröffentlicht sie als „Release“. Die App prüft dort, lädt die APK und startet die Installation.
 */
object Updater {
    private const val UA = "PdfLeser-Updater"

    /** GitHub-Repository im Format „benutzer/repo“ (aus dem Build oder in der App eingestellt). */
    fun repo(ctx: Context): String =
        Prefs.sp(ctx).getString("update_repo", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.UPDATE_REPO

    fun setRepo(ctx: Context, repo: String) {
        Prefs.sp(ctx).edit().putString("update_repo", repo.trim().trim('/')).apply()
    }

    fun currentVersion(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    /** Blockierend – im Hintergrund aufrufen. Rückgabe null = App ist aktuell. */
    fun check(ctx: Context, repo: String): UpdateInfo? {
        val con = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 8000
            con.readTimeout = 10000
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.setRequestProperty("User-Agent", UA)
            val code = con.responseCode
            if (code == 404) throw IOException("Kein Release gefunden. Stimmt „$repo“ und ist das Repository öffentlich?")
            if (code != 200) throw IOException("GitHub antwortet mit Fehler $code.")
            val o = JSONObject(con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = o.optString("tag_name")
            val vc = Regex("\\d+").findAll(tag).lastOrNull()?.value?.toLongOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            var url = ""
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
            if (url.isEmpty() || vc <= currentVersion(ctx)) return null
            val title = if (o.isNull("name")) tag else o.optString("name").ifEmpty { tag }
            val notes = if (o.isNull("body")) "" else o.optString("body").trim()
            return UpdateInfo(vc, title, notes, url, size)
        } finally {
            con.disconnect()
        }
    }

    /** Blockierend – lädt die APK in den Cache und prüft, dass es wirklich der PDF Leser ist. */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "PdfLeser-${info.versionCode}.apk")
        val con = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 10000
            con.readTimeout = 30000
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Accept", "application/octet-stream")
            if (con.responseCode != 200) throw IOException("Download fehlgeschlagen (Fehler ${con.responseCode}).")
            val total = if (con.contentLengthLong > 0) con.contentLengthLong else info.size
            con.inputStream.use { inp ->
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
        } finally {
            con.disconnect()
        }
        val pi = ctx.packageManager.getPackageArchiveInfo(f.path, 0)
        if (pi == null || pi.packageName != ctx.packageName) {
            f.delete()
            throw IOException("Die heruntergeladene Datei ist keine gültige PDF-Leser-App.")
        }
        return f
    }

    /** Darf die App Updates installieren? (Einmalig in den Einstellungen erlauben.) */
    fun canInstall(act: Activity): Boolean = act.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(act: Activity) {
        act.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
        )
    }

    fun install(act: Activity, file: File) {
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
    }
}
