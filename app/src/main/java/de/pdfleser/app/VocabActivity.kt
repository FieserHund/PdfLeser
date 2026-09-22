package de.pdfleser.app

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Vokabelliste: alle nachgeschlagenen Wörter, durchsuchbar, antippen = aussprechen, Export als CSV. */
class VocabActivity : AppCompatActivity() {

    private val adapter = VocabAdapter({ speak(it.word) }, { confirmDelete(it) })
    private lateinit var empty: TextView
    private lateinit var countLabel: TextView
    private var all: List<VocabEntry> = emptyList()
    private var query = ""
    private var speaker: ReadAloud? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_vocab)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.vocab)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_export -> export()
                R.id.action_clear -> confirmClear()
            }
            true
        }

        empty = findViewById(R.id.empty)
        countLabel = findViewById(R.id.countLabel)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<TextInputEditText>(R.id.search).doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            applyFilter()
        }

        lifecycleScope.launch {
            VocabStore.changes.collect {
                all = withContext(Dispatchers.IO) { VocabStore.all(applicationContext) }
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query.lowercase()
        val shown = if (q.isEmpty()) all else all.filter {
            it.word.lowercase().contains(q) || it.translation.lowercase().contains(q) || it.bookTitle.lowercase().contains(q)
        }
        adapter.items = shown
        countLabel.text = when {
            all.isEmpty() -> ""
            q.isEmpty() -> if (all.size == 1) "1 Wort" else "${all.size} Wörter"
            else -> "${shown.size} von ${all.size}"
        }
        empty.isVisible = shown.isEmpty()
        empty.text = if (all.isEmpty()) {
            "Noch keine Vokabeln.\n\nWenn du beim Lesen ein Wort lange drückst, wird es übersetzt und hier gespeichert."
        } else {
            "Nichts gefunden."
        }
    }

    private fun speak(word: String) {
        val s = speaker ?: ReadAloud(this).also { r ->
            speaker = r
            lifecycleScope.launch {
                r.errors.collect { Toast.makeText(this@VocabActivity, it, Toast.LENGTH_LONG).show() }
            }
        }
        s.speakWord(word)
    }

    private fun confirmDelete(e: VocabEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle("„${e.word}“ entfernen?")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Entfernen") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { VocabStore.remove(applicationContext, e.key) }
            }
            .show()
    }

    private fun confirmClear() {
        if (all.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Alle Vokabeln löschen?")
            .setMessage("${all.size} Einträge werden entfernt. Tipp: Vorher exportieren.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Alle löschen") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { VocabStore.clear(applicationContext) }
            }
            .show()
    }

    private fun export() {
        if (all.isEmpty()) {
            Toast.makeText(this, "Die Vokabelliste ist noch leer.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { VocabStore.exportCsv(applicationContext) }
            val uri = FileProvider.getUriForFile(this@VocabActivity, "$packageName.files", f)
            val i = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Vokabeln")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri("Vokabeln", uri)
            startActivity(Intent.createChooser(i, "Vokabeln exportieren"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speaker?.shutdown()
    }
}
