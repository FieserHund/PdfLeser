package de.pdfleser.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReflowAdapter(private val host: ReaderPageView.Host) : RecyclerView.Adapter<ReflowAdapter.VH>() {

    class VH(val v: ReaderPageView) : RecyclerView.ViewHolder(v)

    var pages: List<Page> = emptyList()
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun replace(newPages: List<Page>) {
        pages = newPages
        notifyDataSetChanged()
    }

    /** Nur geänderte/neue Seiten neu binden – Blättern bleibt flüssig, während das Buch noch wächst. */
    fun submit(newPages: List<Page>) {
        val old = pages
        pages = newPages
        val m = minOf(old.size, newPages.size)
        var k = 0
        while (k < m && old[k] == newPages[k]) k++
        if (k < m) notifyItemRangeChanged(k, m - k)
        if (newPages.size > old.size) notifyItemRangeInserted(old.size, newPages.size - old.size)
        else if (newPages.size < old.size) notifyItemRangeRemoved(newPages.size, old.size - newPages.size)
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ReaderPageView(parent.context)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        v.host = host
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.v.bind(position, pages.getOrNull(position))
    }
}

class OriginalAdapter(private val host: OriginalPageView.Host) : RecyclerView.Adapter<OriginalAdapter.VH>() {

    class VH(val v: OriginalPageView) : RecyclerView.ViewHolder(v)

    @set:SuppressLint("NotifyDataSetChanged")
    var count = 0
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = count

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = OriginalPageView(parent.context)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        v.host = host
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.v.bind(position)
    }
}

class BookAdapter(
    private val act: AppCompatActivity,
    private val onClick: (BookEntry) -> Unit,
    private val onLongClick: (BookEntry) -> Unit
) : RecyclerView.Adapter<BookAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val title: TextView = v.findViewById(R.id.title)
        val info: TextView = v.findViewById(R.id.info)
        var job: Job? = null
    }

    private var items: List<BookEntry> = emptyList()
    private val covers = LruCache<String, Bitmap>(48)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<BookEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = items[position]
        holder.title.text = b.title
        val prog = Prefs.sp(act).getInt("prog_${b.id}", -1)
        holder.info.text = if (prog < 0) "Neu" else "$prog % gelesen"
        holder.itemView.setOnClickListener { onClick(b) }
        holder.itemView.setOnLongClickListener {
            onLongClick(b)
            true
        }
        holder.job?.cancel()
        val cached = covers.get(b.id)
        if (cached != null) {
            holder.cover.setImageBitmap(cached)
        } else {
            holder.cover.setImageDrawable(null)
            holder.job = act.lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try { BitmapFactory.decodeFile(Library.coverFile(act, b.id).path) } catch (e: Exception) { null }
                }
                if (bmp != null) {
                    covers.put(b.id, bmp)
                    holder.cover.setImageBitmap(bmp)
                }
            }
        }
    }
}

/** Eintrag im Navigationsblatt (Inhalt, Lesezeichen, Markierungen). */
class NavItem(
    val title: CharSequence,
    val subtitle: String,
    val level: Int,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null
)

class NavAdapter : RecyclerView.Adapter<NavAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.navTitle)
        val subtitle: TextView = v.findViewById(R.id.navSubtitle)
    }

    var items: List<NavItem> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_nav, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val d = holder.itemView.resources.displayMetrics.density
        holder.itemView.setPaddingRelative(
            ((20 + 16 * item.level) * d).toInt(), holder.itemView.paddingTop,
            holder.itemView.paddingEnd, holder.itemView.paddingBottom
        )
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.subtitle.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { item.onClick() }
        val long = item.onLongClick
        if (long != null) {
            holder.itemView.setOnLongClickListener {
                long()
                true
            }
        } else {
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
        }
    }
}

class VocabAdapter(
    private val onClick: (VocabEntry) -> Unit,
    private val onLongClick: (VocabEntry) -> Unit
) : RecyclerView.Adapter<VocabAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val word: TextView = v.findViewById(R.id.vWord)
        val phonetic: TextView = v.findViewById(R.id.vPhonetic)
        val count: TextView = v.findViewById(R.id.vCount)
        val translation: TextView = v.findViewById(R.id.vTranslation)
        val meanings: TextView = v.findViewById(R.id.vMeanings)
        val definition: TextView = v.findViewById(R.id.vDefinition)
        val context: TextView = v.findViewById(R.id.vContext)
        val meta: TextView = v.findViewById(R.id.vMeta)
    }

    private val df = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY)

    var items: List<VocabEntry> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vocab, parent, false))

    private fun TextView.setOrHide(s: String) {
        text = s
        visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.word.text = e.word
        holder.phonetic.setOrHide(e.phonetic)
        holder.count.setOrHide(if (e.count > 1) "${e.count}×" else "")
        holder.translation.setOrHide(e.translation)
        holder.meanings.setOrHide(e.meanings)
        holder.definition.setOrHide(e.definition)
        holder.context.setOrHide(if (e.context.isEmpty()) "" else "„${e.context}“")
        holder.meta.text = listOf(e.bookTitle, df.format(java.util.Date(e.updated))).filter { it.isNotEmpty() }.joinToString("  ·  ")
        holder.itemView.setOnClickListener { onClick(e) }
        holder.itemView.setOnLongClickListener {
            onLongClick(e)
            true
        }
    }
}
