package de.pdfleser.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Bibliothek: zuletzt gelesene Bücher mit Cover und Fortschritt. */
class MainActivity : AppCompatActivity() {

    private lateinit var adapter: BookAdapter
    private lateinit var empty: TextView
    private var pendingInstall: File? = null

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importAndOpen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_vocab -> startActivity(Intent(this, VocabActivity::class.java))
                R.id.action_update -> checkForUpdates(manual = true)
                R.id.action_update_source -> askRepo()
            }
            true
        }

        val list = findViewById<RecyclerView>(R.id.list)
        empty = findViewById(R.id.empty)
        adapter = BookAdapter(this, { openBook(it.id) }, { confirmDelete(it) })
        val dm = resources.displayMetrics
        val columns = max(2, (dm.widthPixels / dm.density / 130f).toInt())
        list.layoutManager = GridLayoutManager(this, columns)
        list.adapter = adapter

        findViewById<View>(R.id.fab).setOnClickListener { openDoc.launch(arrayOf("application/pdf")) }

        if (savedInstanceState == null) {
            handleIntent(intent)
            // Übrig gebliebene Update-Dateien wegräumen (nach erfolgreichem oder fehlgeschlagenem Update).
            lifecycleScope.launch(Dispatchers.IO) { Updater.cleanup(applicationContext) }
            checkForUpdates(manual = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Zurück aus den Einstellungen („Aus dieser Quelle erlauben“) → Installation fortsetzen.
        val f = pendingInstall
        if (f != null && f.exists() && Updater.canInstall(this)) {
            pendingInstall = null
            Updater.install(this, f)
        }
    }

    /* ---------------------------- Updates ---------------------------- */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun checkForUpdates(manual: Boolean) {
        val repo = Updater.repo(this)
        if (repo.isEmpty()) {
            if (manual) askRepo()
            return
        }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Updater.check(applicationContext, repo) } }
            res.onSuccess { info ->
                if (info != null) {
                    showUpdateDialog(info)
                } else if (manual) {
                    toast("Du hast bereits die neueste Version (Build ${Updater.currentVersion(this@MainActivity)}).")
                }
            }.onFailure {
                if (manual) toast("Update-Prüfung fehlgeschlagen: ${it.message ?: "keine Verbindung"}")
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val mb = if (info.size > 0) String.format(java.util.Locale.GERMANY, " · %.1f MB", info.size / 1_048_576f) else ""
        val msg = buildString {
            append(info.title).append(mb)
            if (info.notes.isNotEmpty()) append("\n\n").append(info.notes.take(600))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Update verfügbar")
            .setMessage(msg)
            .setNegativeButton("Später", null)
            .setPositiveButton("Installieren") { _, _ -> downloadAndInstall(info) }
            .show()
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = 100
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(20), dp(24), dp(4))
            addView(bar)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Update wird geladen …")
            .setView(box)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    Updater.download(applicationContext, info) { p -> runOnUiThread { bar.setProgressCompat(p, true) } }
                }
            }
            dlg.dismiss()
            res.onSuccess { f ->
                if (Updater.canInstall(this@MainActivity)) {
                    Updater.install(this@MainActivity, f)
                } else {
                    pendingInstall = f
                    toast("Einmalig erlauben: „Aus dieser Quelle erlauben“ einschalten und zurückkehren.")
                    Updater.openInstallPermission(this@MainActivity)
                }
            }.onFailure {
                if (it is SignatureMismatchException) showSignatureProblem(it)
                else toast(it.message ?: "Download fehlgeschlagen.")
            }
        }
    }

    private fun showSignatureProblem(e: SignatureMismatchException) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Update passt nicht zur installierten App")
            .setMessage(
                "Das Update von GitHub ist mit einem anderen Schlüssel unterschrieben als deine App. " +
                    "Android würde es deshalb ablehnen – die Datei wurde gleich wieder gelöscht.\n\n" +
                    "Installierte App:\n${e.installed.take(23)}…\n\nUpdate:\n${e.update.take(23)}…\n\n" +
                    "Im GitHub-Protokoll (Schritt „Signaturschlüssel einrichten“) steht, mit welchem Schlüssel gebaut wurde."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** GitHub-Repository festlegen, aus dem Updates geladen werden. */
    private fun askRepo() {
        val til = TextInputLayout(this).apply {
            hint = "GitHub: benutzername/PdfLeser"
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = TextInputEditText(til.context).apply {
            setText(Updater.repo(this@MainActivity))
            setSingleLine(true)
        }
        til.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle("Update-Quelle")
            .setMessage("Aus welchem GitHub-Repository sollen Updates geladen werden?")
            .setView(til)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                val v = input.text?.toString().orEmpty().trim()
                    .removePrefix("https://github.com/").removePrefix("github.com/").trim('/')
                Updater.setRepo(this, v)
                if (v.isNotEmpty()) checkForUpdates(manual = true)
            }
            .show()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) {
                Library.all(applicationContext).sortedByDescending { max(it.lastOpened, it.added) }
            }
            adapter.submit(books)
            empty.isVisible = books.isEmpty()
        }
    }

    /** PDF aus einer anderen App (Dateien, Chrome, Mail …) mit dieser App geöffnet oder geteilt. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            i.action = null
            importAndOpen(uri)
        }
    }

    private fun importAndOpen(uri: Uri) {
        val dlg = MaterialAlertDialogBuilder(this)
            .setMessage("PDF wird importiert …")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val e = Library.importPdf(applicationContext, uri)
                    ContentManager.get(applicationContext, e.id) // Extraktion sofort starten
                    e
                }
            }
            dlg.dismiss()
            res.onSuccess { openBook(it.id) }
                .onFailure {
                    Toast.makeText(this@MainActivity, it.message ?: "Import fehlgeschlagen.", Toast.LENGTH_LONG).show()
                }
            refresh()
        }
    }

    private fun openBook(id: String) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, id))
    }

    private fun confirmDelete(b: BookEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Aus der Bibliothek entfernen?")
            .setMessage("„${b.title}“ wird aus der App gelöscht. Deine Originaldatei bleibt erhalten.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Entfernen") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ContentManager.cancel(b.id)
                        Library.delete(applicationContext, b.id)
                    }
                    refresh()
                }
            }
            .show()
    }
}
