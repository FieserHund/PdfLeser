package de.pdfleser.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Seitenverwaltung eines gescannten Buchs: Reihenfolge prüfen, mehrere Fotos zu einer Seite
 * zusammenfassen, Seitenzahlen korrigieren und nach ihnen sortieren.
 */
class PagesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "book_id"
    }

    private lateinit var bookId: String
    private lateinit var toolbar: MaterialToolbar
    private lateinit var emptyHint: TextView
    private lateinit var adapter: PageTileAdapter
    private var progress: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_pages)
        bookId = intent.getStringExtra(EXTRA_ID) ?: run {
            finish()
            return
        }

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            if (adapter.selection.isEmpty()) finish() else clearSelection()
        }
        toolbar.inflateMenu(R.menu.pages)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_merge -> doMerge()
                R.id.action_delete -> doDelete()
                R.id.action_sort -> doSort()
                R.id.action_reocr -> doReocr()
            }
            true
        }

        emptyHint = findViewById(R.id.hint)
        val list = findViewById<RecyclerView>(R.id.list)
        val dm = resources.displayMetrics
        val columns = max(2, (dm.widthPixels / dm.density / 140f).toInt())
        list.layoutManager = GridLayoutManager(this, columns)
        adapter = PageTileAdapter(
            act = this,
            bookId = bookId,
            onTap = { p -> if (adapter.selection.isEmpty()) askNumber(p) else toggle(p) },
            onLongTap = { p -> toggle(p) }
        )
        list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ScanEditor.changed.collect { reload() } }
                launch { ScanEditor.busy.collect { showBusy(it) } }
                launch {
                    ScanEditor.lastError.collect { e ->
                        if (e != null) {
                            Toast.makeText(this@PagesActivity, e, Toast.LENGTH_LONG).show()
                            ScanEditor.lastError.value = null
                        }
                    }
                }
            }
        }
    }

    private fun reload() {
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) { ScanEditor.pages(applicationContext, bookId) }
            adapter.submit(pages)
            emptyHint.isVisible = pages.isEmpty()
            updateBars()
        }
    }

    private fun toggle(p: ScanPage) {
        adapter.toggle(p.index)
        updateBars()
    }

    private fun clearSelection() {
        adapter.clearSelection()
        updateBars()
    }

    private fun updateBars() {
        val n = adapter.selection.size
        val menu = toolbar.menu
        menu.findItem(R.id.action_merge)?.isVisible = n >= 2
        menu.findItem(R.id.action_delete)?.isVisible = n >= 1
        menu.findItem(R.id.action_sort)?.isVisible = n == 0
        menu.findItem(R.id.action_reocr)?.isVisible = n == 0
        toolbar.title = if (n == 0) "Seiten" else "$n ausgewählt"
        toolbar.subtitle = if (n == 0) "Antippen zum Korrigieren, lange drücken zum Auswählen" else null
    }

    /* ---------------------------- Aktionen ---------------------------- */

    private fun doMerge() {
        val sel = adapter.selection.sorted()
        if (sel.size < 2) return
        MaterialAlertDialogBuilder(this)
            .setTitle("${sel.size} Fotos zu einer Seite?")
            .setMessage(
                "Die Bilder werden in dieser Reihenfolge untereinander gesetzt und danach neu erkannt: " +
                    sel.joinToString(", ") { "Nr. ${it + 1}" } + ".\n\n" +
                    "Das ist gedacht für eine Buchseite, die du in mehreren Teilen fotografiert hast."
            )
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Zusammenfügen") { _, _ ->
                ScanEditor.merge(this, bookId, sel)
                clearSelection()
            }
            .show()
    }

    private fun doDelete() {
        val sel = adapter.selection.sorted()
        if (sel.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (sel.size == 1) "Seite entfernen?" else "${sel.size} Seiten entfernen?")
            .setMessage("Die Fotos und der erkannte Text werden gelöscht.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Entfernen") { _, _ ->
                ScanEditor.deletePages(this, bookId, sel)
                clearSelection()
            }
            .show()
    }

    private fun doSort() {
        val pages = adapter.items
        val known = pages.count { it.number != null }
        if (known < 2) {
            Toast.makeText(this, "Es wurden zu wenige Seitenzahlen erkannt. Trag sie erst von Hand ein.", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Nach Seitenzahl sortieren?")
            .setMessage(
                "$known von ${pages.size} Seiten haben eine Seitenzahl. Seiten ohne Zahl bleiben " +
                    "hinter ihrem bisherigen Vorgänger stehen.\n\nLesezeichen und Markierungen werden danach neu zugeordnet."
            )
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Sortieren") { _, _ -> ScanEditor.sortByNumber(this, bookId) }
            .show()
    }

    private fun doReocr() {
        val n = adapter.items.size
        if (n == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Alle $n Seiten neu erkennen?")
            .setMessage(
                "Der Text wird aus den gespeicherten Fotos noch einmal gelesen. Sinnvoll nach einer " +
                    "neuen Version der App oder wenn du die Absatz-Einstellung geändert hast.\n\n" +
                    "Deine Seitenzahl-Korrekturen bleiben erhalten; das kann einige Minuten dauern."
            )
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Neu erkennen") { _, _ -> ScanEditor.reocrAll(this, bookId) }
            .show()
    }

    /** Seitenzahl von Hand eintragen oder korrigieren. */
    private fun askNumber(p: ScanPage) {
        val til = TextInputLayout(this).apply {
            hint = "Seitenzahl"
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = TextInputEditText(til.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(p.number?.toString().orEmpty())
            setSingleLine(true)
        }
        til.addView(input)
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("Seite ${p.index + 1}")
            .setMessage(
                if (p.detected != null) "Erkannt: ${p.detected}"
                else "Auf dieser Seite wurde keine Seitenzahl gefunden."
            )
            .setView(til)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Übernehmen") { _, _ ->
                val n = input.text?.toString()?.trim()?.toIntOrNull()
                ScanEditor.setNumber(this, bookId, p.index, n)
            }
        if (p.manual != null) {
            b.setNeutralButton("Korrektur löschen") { _, _ -> ScanEditor.setNumber(this, bookId, p.index, null) }
        }
        b.show()
    }

    private fun showBusy(label: String?) {
        if (label == null) {
            progress?.dismiss()
            progress = null
            return
        }
        if (progress != null) {
            progress?.setTitle(label)
            return
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(20), dp(24), dp(4))
            addView(LinearProgressIndicator(this@PagesActivity).apply { isIndeterminate = true })
        }
        progress = MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setView(box)
            .setCancelable(false)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }
}

/** Kachel je gescannter Seite: Miniaturbild, laufende Nummer und erkannte Seitenzahl. */
class PageTileAdapter(
    private val act: AppCompatActivity,
    private val bookId: String,
    private val onTap: (ScanPage) -> Unit,
    private val onLongTap: (ScanPage) -> Unit
) : RecyclerView.Adapter<PageTileAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: android.widget.ImageView = v.findViewById(R.id.pageThumb)
        val order: TextView = v.findViewById(R.id.pageOrder)
        val number: TextView = v.findViewById(R.id.pageNumber)
        val check: View = v.findViewById(R.id.pageCheck)
        var job: kotlinx.coroutines.Job? = null
    }

    var items: List<ScanPage> = emptyList()
        private set
    val selection = HashSet<Int>()
    private val thumbs = android.util.LruCache<String, Bitmap>(24)

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ScanPage>) {
        // Nach dem Sortieren oder Zusammenfügen zeigt derselbe Platz ein anderes Bild.
        thumbs.evictAll()
        items = list
        selection.retainAll(list.map { it.index }.toSet())
        notifyDataSetChanged()
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun toggle(index: Int) {
        if (!selection.add(index)) selection.remove(index)
        notifyDataSetChanged()
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun clearSelection() {
        selection.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.order.text = "Nr. ${p.index + 1}"
        holder.number.text = when {
            p.manual != null -> "Seite ${p.manual} ✎"
            p.detected != null -> "Seite ${p.detected}"
            else -> "ohne Zahl"
        }
        holder.check.isVisible = p.index in selection
        holder.itemView.alpha = if (p.index in selection) 0.65f else 1f
        holder.itemView.setOnClickListener { onTap(p) }
        holder.itemView.setOnLongClickListener {
            onLongTap(p)
            true
        }
        holder.job?.cancel()
        val key = "${p.index}"
        val cached = thumbs.get(key)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
        } else {
            holder.thumb.setImageDrawable(null)
            holder.job = act.lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val f = Library.scanPages(act, bookId).getOrNull(p.index) ?: return@withContext null
                    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, info)
                    var sample = 1
                    while (info.outWidth / (sample * 2) >= 220) sample *= 2
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                if (bmp != null) {
                    thumbs.put(key, bmp)
                    holder.thumb.setImageBitmap(bmp)
                }
            }
        }
    }
}
