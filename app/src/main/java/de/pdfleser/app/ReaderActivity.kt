package de.pdfleser.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ReaderActivity : AppCompatActivity(), ReaderPageView.Host, OriginalPageView.Host {

    companion object {
        const val EXTRA_ID = "book_id"
        private val RATES = floatArrayOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f)
    }

    private val vm: ReaderViewModel by viewModels()

    // --- Host-Schnittstellen ---
    override var ui: UiLayouts? = null
    override var pageSet: PageSet = PageSet.EMPTY
    override var colors: ThemeColors = ThemeColors.of(0)
    override val originalInsets = Rect()

    // --- Views ---
    private lateinit var root: FrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var footer: TextView
    private lateinit var loading: ProgressBar
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var bookTitle: TextView
    private lateinit var btnMode: MaterialButton
    private lateinit var btnBookmark: ImageButton
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var player: View
    private lateinit var btnPlay: ImageButton
    private lateinit var btnRate: MaterialButton
    private lateinit var settingsPanel: View
    private lateinit var settingsInner: View
    private lateinit var fontSizeValue: TextView
    private lateinit var groupFont: MaterialButtonToggleGroup
    private lateinit var groupSpacing: MaterialButtonToggleGroup
    private lateinit var groupMargin: MaterialButtonToggleGroup
    private lateinit var groupTheme: MaterialButtonToggleGroup
    private lateinit var groupPara: MaterialButtonToggleGroup
    private lateinit var swJustify: MaterialSwitch
    private lateinit var swFullscreen: MaterialSwitch
    private lateinit var swVolume: MaterialSwitch
    private lateinit var swKeepOn: MaterialSwitch
    private lateinit var swAutoVocab: MaterialSwitch
    private lateinit var swDefinition: MaterialSwitch
    private lateinit var swAutoBrightness: MaterialSwitch
    private lateinit var seekBrightness: SeekBar
    private lateinit var transCard: MaterialCardView
    private lateinit var transScroll: MaxHeightScrollView
    private lateinit var transSource: TextView
    private lateinit var transResult: TextView
    private lateinit var transDict: TextView
    private lateinit var transProgress: ProgressBar
    private lateinit var defSection: View
    private lateinit var defProgress: ProgressBar
    private lateinit var defText: TextView
    private lateinit var btnStar: ImageButton
    private lateinit var btnHighlight: MaterialButton

    private val reflowAdapter by lazy { ReflowAdapter(this) }
    private val origAdapter by lazy { OriginalAdapter(this) }

    private val fontIds by lazy { intArrayOf(R.id.fontSerif, R.id.fontSans, R.id.fontCond) }
    private val spacingIds by lazy { intArrayOf(R.id.spacing0, R.id.spacing1, R.id.spacing2) }
    private val marginIds by lazy { intArrayOf(R.id.margin0, R.id.margin1, R.id.margin2) }
    private val themeIds by lazy { intArrayOf(R.id.theme0, R.id.theme1, R.id.theme2, R.id.theme3) }

    // --- Zustand ---
    private var modeApplied = -1
    private var pendingJump = true
    private var pendingPdfPage = -1
    private var expected = -1
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var menuVisible = false
    private var binding = false
    private var cardAtTop = false
    private var selectedView: ReaderPageView? = null
    private var speaking: TextRange? = null
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

    override fun onCreate(savedInstanceState: Bundle?) {
        val initial = ReaderSettings.load(this)
        delegate.localNightMode =
            if (initial.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_reader)

        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        bindViews()
        vm.open(id)
        lifecycleScope.launch(Dispatchers.IO) { Library.touch(applicationContext, id) }

        bookTitle.text = vm.book?.title.orEmpty()
        colors = ThemeColors.of(vm.settings.theme)
        applyTheme()
        applyWindowSettings()
        setupPager()
        setupMenus()
        setupPlayer()
        setupSettingsPanel()
        setupLookupCard()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            insetTop = max(bars.top, cut.top)
            insetBottom = max(bars.bottom, cut.bottom)
            insetLeft = max(bars.left, cut.left)
            insetRight = max(bars.right, cut.right)
            originalInsets.set(insetLeft, insetTop, insetRight, insetBottom + dp(22))
            applyInsetsToBars()
            updateSpec()
            insets
        }
        pager.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) pager.post { updateSpec() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (transCard.isVisible || menuVisible || settingsPanel.isVisible) {
                    closeTranslation()
                    hideMenus()
                } else {
                    finish()
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.ready.collect { if (it && modeApplied < 0) onReady() } }
                launch { vm.pageSet.collect { onPageSet(it) } }
                launch { vm.progress.collect { checkContentState() } }
                launch { vm.lookup.collect { showLookup(it) } }
                launch { vm.annotationsVersion.collect { invalidatePages(); updateBookmarkButton() } }
                launch { vm.readAloud.state.collect { onTtsState(it) } }
                launch { vm.readAloud.errors.collect { toast(it) } }
            }
        }
        if (vm.panelOpen) openSettings()
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        pager = findViewById(R.id.pager)
        footer = findViewById(R.id.footer)
        loading = findViewById(R.id.loading)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        bookTitle = findViewById(R.id.bookTitle)
        btnMode = findViewById(R.id.btnMode)
        btnBookmark = findViewById(R.id.btnBookmark)
        pageLabel = findViewById(R.id.pageLabel)
        seek = findViewById(R.id.seek)
        player = findViewById(R.id.player)
        btnPlay = findViewById(R.id.btnPlay)
        btnRate = findViewById(R.id.btnRate)
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsInner = findViewById(R.id.settingsInner)
        fontSizeValue = findViewById(R.id.fontSizeValue)
        groupFont = findViewById(R.id.groupFont)
        groupSpacing = findViewById(R.id.groupSpacing)
        groupMargin = findViewById(R.id.groupMargin)
        groupTheme = findViewById(R.id.groupTheme)
        groupPara = findViewById(R.id.groupPara)
        swJustify = findViewById(R.id.swJustify)
        swFullscreen = findViewById(R.id.swFullscreen)
        swVolume = findViewById(R.id.swVolume)
        swKeepOn = findViewById(R.id.swKeepOn)
        swAutoVocab = findViewById(R.id.swAutoVocab)
        swDefinition = findViewById(R.id.swDefinition)
        swAutoBrightness = findViewById(R.id.swAutoBrightness)
        seekBrightness = findViewById(R.id.seekBrightness)
        transCard = findViewById(R.id.transCard)
        transScroll = findViewById(R.id.transScroll)
        transSource = findViewById(R.id.transSource)
        transResult = findViewById(R.id.transResult)
        transDict = findViewById(R.id.transDict)
        transProgress = findViewById(R.id.transProgress)
        defSection = findViewById(R.id.defSection)
        defProgress = findViewById(R.id.defProgress)
        defText = findViewById(R.id.defText)
        btnStar = findViewById(R.id.btnStar)
        btnHighlight = findViewById(R.id.btnHighlight)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /* ------------------------------------------------------------------ */
    /*  Pager, Modus, Sprünge                                              */
    /* ------------------------------------------------------------------ */

    private fun setupPager() {
        pager.offscreenPageLimit = 1
        (pager.getChildAt(0) as? RecyclerView)?.itemAnimator = null
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPageSelectedInternal(position)
            }
        })
    }

    private fun onReady() {
        origAdapter.count = vm.pdf?.pageCount ?: 0
        if (vm.resumePdfPage >= 0) {
            if (vm.mode == 0) pendingPdfPage = vm.resumePdfPage
            vm.resumePdfPage = -1
        }
        if (vm.pdf == null && vm.content?.error != null) {
            toast("Das PDF konnte nicht geöffnet werden.")
            finish()
            return
        }
        applyMode()
    }

    private fun applyMode() {
        closeTranslation()
        if (vm.mode == 1 && vm.pdf == null) vm.mode = 0
        modeApplied = vm.mode
        if (vm.mode == 0) {
            pendingJump = true
            pager.adapter = reflowAdapter
            reflowAdapter.replace(pageSet.pages)
            tryJump()
        } else {
            pendingJump = false
            showLoading(false)
            val target = vm.origPage
            pager.adapter = origAdapter
            jumpTo(target)
        }
        btnMode.text = if (vm.mode == 0) "Original" else "Lesemodus"
        updateFooter()
        updateBookmarkButton()
    }

    private fun onPageSet(ps: PageSet) {
        val spec = ps.spec
        var specChanged = false
        if (spec != null && ui?.spec != spec) {
            ui = UiLayouts(spec).also { it.setColor(colors.fg) }
            specChanged = true
            if (modeApplied == 0) pendingJump = true
        }
        pageSet = ps
        if (modeApplied == 0) {
            if (specChanged) reflowAdapter.replace(ps.pages) else reflowAdapter.submit(ps.pages)
            invalidatePages()
            if (pendingJump) tryJump()
        }
        updateFooter()
        updateBookmarkButton()
        if (menuVisible) updateSeek()
    }

    private fun tryJump() {
        if (modeApplied != 0) return
        val ps = pageSet
        val c = vm.content
        if (pendingPdfPage >= 0) {
            val idx = ps.blocks.indexOfFirst { it.pdfPage >= pendingPdfPage }
            if (idx >= 0) {
                vm.pos = Pos(idx, 0)
                pendingPdfPage = -1
            } else if (c == null || !ps.complete) {
                showLoading(true)
                return
            } else {
                vm.pos = Pos(max(0, ps.blocks.size - 1), 0)
                pendingPdfPage = -1
            }
        }
        val pages = ps.pages
        if (pages.isEmpty()) {
            showLoading(!ps.complete)
            return
        }
        val covered = ps.complete || pages.last().start > vm.pos
        if (!covered) {
            showLoading(true)
            return
        }
        showLoading(false)
        pendingJump = false
        jumpTo(pageIndexOf(pages, vm.pos))
    }

    private fun jumpTo(idx: Int) {
        val count = pager.adapter?.itemCount ?: 0
        if (count == 0) return
        val i = idx.coerceIn(0, count - 1)
        expected = i
        pager.setCurrentItem(i, false)
        if (expected == i && pager.currentItem == i) {
            expected = -1
            onSettled(i)
        }
    }

    /** Zu einer gespeicherten Stelle springen (Lesezeichen, Markierung). */
    private fun jumpToPos(pos: Pos, pdfPage: Int) {
        if (modeApplied == 0) {
            if (pageSet.blocks.getOrNull(pos.block)?.pdfPage == pdfPage) {
                vm.pos = pos
                pendingJump = true
                tryJump()
            } else {
                goToPdfPage(pdfPage)
            }
        } else {
            jumpTo(pdfPage)
        }
    }

    private fun onPageSelectedInternal(position: Int) {
        if (modeApplied == 0 && pendingJump) return
        if (expected >= 0) {
            if (position != expected) return
            expected = -1
        }
        onSettled(position)
    }

    private fun onSettled(position: Int) {
        if (modeApplied == 0) {
            pageSet.pages.getOrNull(position)?.let { vm.pos = it.start }
        } else if (modeApplied == 1) {
            vm.origPage = position
        }
        vm.savePosition()
        if (selectedView != null || transCard.isVisible) closeTranslation()
        updateFooter()
        updateBookmarkButton()
        if (menuVisible) updateSeek()
    }

    private fun turn(dir: Int) {
        if (modeApplied == 0 && pendingJump) return
        val n = pager.currentItem + dir
        val count = pager.adapter?.itemCount ?: 0
        if (n in 0 until count) {
            pager.setCurrentItem(n, true)
        } else if (dir > 0 && modeApplied == 0 && vm.content?.finished == false) {
            toast("Das Buch wird noch aufbereitet …")
        }
    }

    private fun toggleMode() {
        if (vm.mode == 0) {
            vm.origPage = pageSet.blocks.getOrNull(vm.pos.block)?.pdfPage ?: vm.origPage
            vm.mode = 1
        } else {
            pendingPdfPage = vm.origPage
            vm.mode = 0
        }
        vm.savePosition()
        hideMenus()
        applyMode()
    }

    private fun goToPdfPage(p: Int) {
        hideMenus()
        if (modeApplied == 0) {
            pendingPdfPage = p
            pendingJump = true
            tryJump()
        } else {
            jumpTo(p)
        }
    }

    private fun checkContentState() {
        val c = vm.content ?: return
        if (modeApplied == 0 && vm.pdf != null && !vm.autoSwitched) {
            val threshold = min(8, max(1, c.totalPages))
            val noText = c.textChars == 0L && (c.finished || (c.totalPages > 0 && c.pagesDone >= threshold))
            if (c.error != null || noText) {
                vm.autoSwitched = true
                toast(
                    if (c.error != null) "Der Text dieses PDFs lässt sich nicht auslesen – es wird im Original angezeigt."
                    else "Dieses PDF enthält keinen auswählbaren Text (vermutlich ein Scan) – es wird im Original angezeigt."
                )
                vm.mode = 1
                vm.savePosition()
                applyMode()
            }
        }
        updateFooter()
    }

    private fun showLoading(show: Boolean) {
        loading.isVisible = show
    }

    private fun invalidatePages() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) rv.getChildAt(i).invalidate()
    }

    private fun updateFooter() {
        val count = pager.adapter?.itemCount ?: 0
        val sb = StringBuilder()
        if (count > 0) sb.append("Seite ").append(pager.currentItem + 1).append(" von ").append(count)
        val c = vm.content
        if (modeApplied == 0 && c != null && !c.finished && c.error == null && c.totalPages > 0) {
            if (sb.isNotEmpty()) sb.append("   ·   ")
            sb.append("wird aufbereitet: ").append(c.pagesDone * 100 / c.totalPages).append(" %")
        }
        footer.text = sb
    }

    /* ------------------------------------------------------------------ */
    /*  Layout & Fenster                                                   */
    /* ------------------------------------------------------------------ */

    private fun updateSpec() {
        val w = pager.width
        val h = pager.height
        if (w <= 0 || h <= 0) return
        val s = vm.settings
        val margin = dp(ReaderSettings.MARGINS_DP[s.margin.coerceIn(0, 2)])
        val left = insetLeft + margin
        val right = insetRight + margin
        val top = insetTop + dp(16)
        val bottom = insetBottom + dp(30)
        val spec = LayoutSpec(
            width = max(50, w - left - right),
            height = max(50, h - top - bottom),
            left = left,
            top = top,
            textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s.fontSizeSp.toFloat(), resources.displayMetrics),
            lineMult = ReaderSettings.SPACINGS[s.lineSpacing.coerceIn(0, 2)],
            font = s.font,
            justify = s.justify,
            lang = ""
        )
        vm.setSpec(spec)
    }

    private fun applyInsetsToBars() {
        topBar.setPadding(dp(4) + insetLeft, insetTop, dp(4) + insetRight, 0)
        bottomBar.setPadding(dp(16) + insetLeft, dp(6), dp(8) + insetRight, dp(10) + insetBottom)
        settingsInner.setPadding(dp(20) + insetLeft, dp(16), dp(20) + insetRight, dp(16) + insetBottom)
        val flp = footer.layoutParams as FrameLayout.LayoutParams
        flp.bottomMargin = insetBottom + dp(8)
        footer.layoutParams = flp
        val plp = player.layoutParams as FrameLayout.LayoutParams
        plp.bottomMargin = insetBottom + dp(32)
        player.layoutParams = plp
        placeCard(cardAtTop)
    }

    private fun applyTheme() {
        colors = ThemeColors.of(vm.settings.theme)
        ui?.setColor(colors.fg)
        root.setBackgroundColor(colors.bg)
        window.decorView.setBackgroundColor(colors.bg)
        footer.setTextColor(colors.footer)
        invalidatePages()
        applySystemBars()
    }

    private fun applyWindowSettings() {
        val s = vm.settings
        if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = if (s.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        else s.brightness.coerceIn(0.01f, 1f)
        window.attributes = lp
        applySystemBars()
    }

    private fun applySystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.isAppearanceLightStatusBars = !vm.settings.isDark
        c.isAppearanceLightNavigationBars = !vm.settings.isDark
        val show = !vm.settings.fullscreen || menuVisible || settingsPanel.isVisible
        if (show) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    override fun onPause() {
        super.onPause()
        vm.savePosition()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeTurns() && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (transCard.isVisible) closeTranslation()
            turn(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeTurns() && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Beim Vorlesen regeln die Lautstärketasten wieder die Lautstärke. */
    private fun volumeTurns(): Boolean = vm.settings.volumeKeys && !vm.readAloud.state.value.active

    /* ------------------------------------------------------------------ */
    /*  Menüs                                                              */
    /* ------------------------------------------------------------------ */

    private fun setupMenus() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnToc).setOnClickListener { showNavSheet() }
        btnBookmark.setOnClickListener {
            val now = vm.toggleBookmark(pageSet, pager.currentItem) ?: return@setOnClickListener
            toast(if (now) "Lesezeichen gesetzt" else "Lesezeichen entfernt")
        }
        btnMode.setOnClickListener { toggleMode() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<View>(R.id.btnVocab).setOnClickListener {
            hideMenus()
            startActivity(Intent(this, VocabActivity::class.java))
        }
        findViewById<View>(R.id.btnRead).setOnClickListener {
            hideMenus()
            if (vm.readAloud.state.value.active) vm.readAloud.toggle() else vm.startReading()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) pageLabel.text = "Seite ${p + 1} von ${pager.adapter?.itemCount ?: 0}"
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {
                if (!(modeApplied == 0 && pendingJump)) jumpTo(sb.progress)
            }
        })
    }

    private fun updateBookmarkButton() {
        if (modeApplied < 0) return
        val on = if (modeApplied == 0) vm.bookmarkOnPage(pageSet, pager.currentItem) != null
        else vm.bookmarkOnPdfPage(pager.currentItem) != null
        btnBookmark.setImageResource(if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        btnBookmark.contentDescription = if (on) "Lesezeichen entfernen" else "Lesezeichen setzen"
    }

    private fun toggleMenu() {
        if (menuVisible || settingsPanel.isVisible) hideMenus() else showMenu()
    }

    private fun showMenu() {
        menuVisible = true
        topBar.isVisible = true
        bottomBar.isVisible = true
        updateSeek()
        updateBookmarkButton()
        updatePlayer()
        applySystemBars()
    }

    private fun hideMenus() {
        menuVisible = false
        topBar.isVisible = false
        bottomBar.isVisible = false
        settingsPanel.isVisible = false
        vm.panelOpen = false
        updatePlayer()
        applySystemBars()
    }

    private fun openSettings() {
        menuVisible = false
        topBar.isVisible = false
        bottomBar.isVisible = false
        settingsPanel.isVisible = true
        vm.panelOpen = true
        bindSettings()
        updatePlayer()
        applySystemBars()
    }

    private fun updateSeek() {
        val count = pager.adapter?.itemCount ?: 0
        seek.max = max(0, count - 1)
        seek.progress = pager.currentItem.coerceIn(0, max(0, count - 1))
        pageLabel.text = if (count == 0) "" else "Seite ${pager.currentItem + 1} von $count"
    }

    /* ------------------------------------------------------------------ */
    /*  Inhalt · Lesezeichen · Markierungen                                */
    /* ------------------------------------------------------------------ */

    private fun showNavSheet() {
        hideMenus()
        val dlg = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_nav, root, false)
        dlg.setContentView(v)
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
        val tabs = v.findViewById<TabLayout>(R.id.navTabs)
        val list = v.findViewById<RecyclerView>(R.id.navList)
        val empty = v.findViewById<TextView>(R.id.navEmpty)
        list.layoutManager = LinearLayoutManager(this)
        list.layoutParams.height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        val adapter = NavAdapter()
        list.adapter = adapter

        fun show(tab: Int) {
            val items: List<NavItem> = when (tab) {
                0 -> vm.content?.toc.orEmpty().map { t ->
                    NavItem(t.title, "Seite ${t.pdfPage + 1}", t.level, {
                        dlg.dismiss()
                        goToPdfPage(t.pdfPage)
                    })
                }
                1 -> vm.annotations?.bookmarks.orEmpty().sortedBy { it.pdfPage }.map { b ->
                    NavItem(
                        "„${b.snippet}…“", "Seite ${b.pdfPage + 1}  ·  ${dateFormat.format(Date(b.created))}", 0,
                        {
                            dlg.dismiss()
                            jumpToPos(b.pos, b.pdfPage)
                        },
                        {
                            confirm("Lesezeichen entfernen?") {
                                vm.removeBookmark(b.id)
                                show(1)
                            }
                        }
                    )
                }
                else -> vm.annotations?.highlights.orEmpty().sortedWith(compareBy({ it.block }, { it.start })).map { h ->
                    NavItem(
                        "„${h.text}“", "Seite ${h.pdfPage + 1}  ·  ${dateFormat.format(Date(h.created))}", 0,
                        {
                            dlg.dismiss()
                            jumpToPos(Pos(h.block, h.start), h.pdfPage)
                        },
                        {
                            confirm("Markierung entfernen?") {
                                vm.removeHighlight(h.id)
                                show(2)
                            }
                        }
                    )
                }
            }
            adapter.items = items
            empty.isVisible = items.isEmpty()
            empty.text = when (tab) {
                0 -> "Dieses PDF hat kein Inhaltsverzeichnis."
                1 -> "Noch keine Lesezeichen.\nTippe oben im Menü auf das Lesezeichen-Symbol, um die aktuelle Seite zu merken."
                else -> "Noch keine Markierungen.\nWort lange drücken, ziehen und in der Karte auf „Markieren“ tippen."
            }
        }

        tabs.addTab(tabs.newTab().setText("Inhalt"))
        tabs.addTab(tabs.newTab().setText("Lesezeichen"))
        tabs.addTab(tabs.newTab().setText("Markierungen"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                show(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        val start = if (vm.content?.toc.isNullOrEmpty() && !vm.annotations?.bookmarks.isNullOrEmpty()) 1 else 0
        tabs.getTabAt(start)?.select()
        show(start)
        dlg.show()
    }

    private fun confirm(title: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Entfernen") { _, _ -> action() }
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  Vorlesen                                                           */
    /* ------------------------------------------------------------------ */

    private fun setupPlayer() {
        btnPlay.setOnClickListener { vm.readAloud.toggle() }
        findViewById<View>(R.id.btnStop).setOnClickListener { vm.readAloud.stop() }
        btnRate.setOnClickListener {
            val cur = vm.settings.ttsRate
            var i = RATES.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
            i = if (i < 0) 1 else (i + 1) % RATES.size
            val r = RATES[i]
            changeSettings { it.copy(ttsRate = r) }
            vm.readAloud.setRate(r)
        }
    }

    private fun formatRate(r: Float): String = String.format(Locale.GERMANY, "%.1f×", r)

    private fun updatePlayer() {
        val st = vm.readAloud.state.value
        player.isVisible = st.active && !menuVisible && !settingsPanel.isVisible
        btnPlay.setImageResource(if (st.playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = if (st.playing) "Pause" else "Weiterlesen"
        btnRate.text = formatRate(vm.settings.ttsRate)
    }

    private fun onTtsState(st: ReadAloud.State) {
        val range = if (st.active && st.block >= 0) TextRange(st.block, st.start, st.end) else null
        if (range != speaking) {
            speaking = range
            invalidatePages()
        }
        updatePlayer()
        if (st.active && st.playing && st.block >= 0) follow(st.block, st.start)
    }

    /** Beim Vorlesen automatisch mitblättern. */
    private fun follow(block: Int, start: Int) {
        if (modeApplied == 0) {
            if (pendingJump || pageSet.pages.isEmpty()) return
            val idx = pageIndexOf(pageSet.pages, Pos(block, start))
            if (idx != pager.currentItem) pager.setCurrentItem(idx, true)
        } else if (modeApplied == 1) {
            val p = pageSet.blocks.getOrNull(block)?.pdfPage ?: return
            if (p != pager.currentItem && p < (pager.adapter?.itemCount ?: 0)) pager.setCurrentItem(p, true)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Darstellung-Einstellungen                                          */
    /* ------------------------------------------------------------------ */

    private fun setupSettingsPanel() {
        findViewById<View>(R.id.btnSmaller).setOnClickListener {
            changeSettings { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceAtLeast(12)) }
        }
        findViewById<View>(R.id.btnBigger).setOnClickListener {
            changeSettings { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceAtMost(40)) }
        }
        groupFont.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !binding) changeSettings { it.copy(font = fontIds.indexOf(id).coerceAtLeast(0)) }
        }
        groupSpacing.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !binding) changeSettings { it.copy(lineSpacing = spacingIds.indexOf(id).coerceAtLeast(0)) }
        }
        groupMargin.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !binding) changeSettings { it.copy(margin = marginIds.indexOf(id).coerceAtLeast(0)) }
        }
        groupTheme.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !binding) changeSettings { it.copy(theme = themeIds.indexOf(id).coerceAtLeast(0)) }
        }
        groupPara.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || binding) return@addOnButtonCheckedListener
            val m = if (id == R.id.paraOff) 1 else 0
            if (m != vm.paraMode) confirmParagraphMode(m)
        }
        swJustify.setOnCheckedChangeListener { _, c -> if (!binding) changeSettings { it.copy(justify = c) } }
        swFullscreen.setOnCheckedChangeListener { _, c -> if (!binding) changeSettings { it.copy(fullscreen = c) } }
        swVolume.setOnCheckedChangeListener { _, c -> if (!binding) changeSettings { it.copy(volumeKeys = c) } }
        swKeepOn.setOnCheckedChangeListener { _, c -> if (!binding) changeSettings { it.copy(keepScreenOn = c) } }
        swAutoVocab.setOnCheckedChangeListener { _, c -> if (!binding) changeSettings { it.copy(autoVocab = c) } }
        swDefinition.setOnCheckedChangeListener { _, c -> if (!binding) changeSettings { it.copy(showDefinition = c) } }
        swAutoBrightness.setOnCheckedChangeListener { _, c ->
            if (!binding) changeSettings { it.copy(brightness = if (c) -1f else max(0.05f, seekBrightness.progress / 100f)) }
        }
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && !binding) changeSettings { it.copy(brightness = max(0.01f, p / 100f)) }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        bindSettings()
    }

    private fun bindSettings() {
        binding = true
        val s = vm.settings
        fontSizeValue.text = s.fontSizeSp.toString()
        groupFont.check(fontIds[s.font.coerceIn(0, 2)])
        groupSpacing.check(spacingIds[s.lineSpacing.coerceIn(0, 2)])
        groupMargin.check(marginIds[s.margin.coerceIn(0, 2)])
        groupTheme.check(themeIds[s.theme.coerceIn(0, 3)])
        groupPara.check(if (vm.paraMode == 1) R.id.paraOff else R.id.paraAuto)
        swJustify.isChecked = s.justify
        swFullscreen.isChecked = s.fullscreen
        swVolume.isChecked = s.volumeKeys
        swKeepOn.isChecked = s.keepScreenOn
        swAutoVocab.isChecked = s.autoVocab
        swDefinition.isChecked = s.showDefinition
        swAutoBrightness.isChecked = s.brightness < 0f
        if (s.brightness >= 0f) seekBrightness.progress = (s.brightness * 100).toInt()
        binding = false
    }

    private fun confirmParagraphMode(m: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (m == 1) "Absätze ausschalten?" else "Absätze automatisch erkennen?")
            .setMessage(
                (if (m == 1) "Der Text läuft dann ohne Absätze durch – nur Überschriften und Szenenwechsel bleiben getrennt. "
                else "Die App erkennt Absätze wieder selbst. ") +
                    "Dafür wird das Buch neu aufbereitet; deine Stelle, Lesezeichen und Markierungen bleiben erhalten."
            )
            .setNegativeButton("Abbrechen") { _, _ -> bindSettings() }
            .setOnCancelListener { bindSettings() }
            .setPositiveButton("Neu aufbereiten") { _, _ ->
                val id = vm.bookId ?: return@setPositiveButton
                vm.setParagraphMode(m) {
                    vm.panelOpen = false
                    finish()
                    startActivity(Intent(this, ReaderActivity::class.java).putExtra(EXTRA_ID, id))
                }
            }
            .show()
    }

    private fun changeSettings(f: (ReaderSettings) -> ReaderSettings) {
        val old = vm.settings
        val new = f(old)
        if (new == old) return
        vm.settings = new
        ReaderSettings.save(this, new)
        if (old.isDark != new.isDark) {
            // Hell ↔ Dunkel: Activity wird mit passendem Systemthema neu aufgebaut (Position bleibt erhalten).
            vm.panelOpen = true
            delegate.localNightMode =
                if (new.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            return
        }
        if (old.theme != new.theme) applyTheme()
        if (old.fontSizeSp != new.fontSizeSp || old.font != new.font || old.lineSpacing != new.lineSpacing ||
            old.margin != new.margin || old.justify != new.justify
        ) {
            updateSpec()
        }
        applyWindowSettings()
        bindSettings()
        updatePlayer()
    }

    /* ------------------------------------------------------------------ */
    /*  Nachschlage-Karte                                                  */
    /* ------------------------------------------------------------------ */

    private fun setupLookupCard() {
        findViewById<View>(R.id.transClose).setOnClickListener { closeTranslation() }
        findViewById<View>(R.id.btnSpeak).setOnClickListener {
            val s = vm.lookup.value?.sel?.text ?: return@setOnClickListener
            vm.readAloud.speakWord(s)
        }
        btnStar.setOnClickListener { vm.toggleVocab() }
        btnHighlight.setOnClickListener {
            val st = vm.lookup.value ?: return@setOnClickListener
            val h = st.highlight
            if (h != null) vm.removeHighlight(h.id) else vm.addHighlight()
            closeTranslation()
        }
        findViewById<View>(R.id.transCopy).setOnClickListener {
            val st = vm.lookup.value ?: return@setOnClickListener
            val t = st.translation?.text?.takeIf { it.isNotEmpty() } ?: st.sel.text
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Übersetzung", t))
            if (Build.VERSION.SDK_INT < 33) toast("Kopiert")
        }
        findViewById<View>(R.id.transGoogle).setOnClickListener {
            val s = vm.lookup.value?.sel?.text ?: return@setOnClickListener
            Translator.openExternal(this, s)
        }
    }

    private fun placeCard(atTop: Boolean) {
        cardAtTop = atTop
        val lp = transCard.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (atTop) Gravity.TOP else Gravity.BOTTOM
        lp.topMargin = insetTop + dp(10)
        lp.bottomMargin = insetBottom + dp(10)
        lp.leftMargin = insetLeft + dp(12)
        lp.rightMargin = insetRight + dp(12)
        transCard.layoutParams = lp
        val h = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        transScroll.maxHeight = (h * 0.34f).toInt()
    }

    private fun showLookup(st: LookupState?) {
        if (st == null) {
            transCard.isVisible = false
            return
        }
        transCard.isVisible = true
        transSource.text = st.sel.text
        transProgress.isVisible = st.transLoading
        transResult.text = when {
            st.transLoading -> ""
            st.transError != null -> st.transError
            else -> st.translation?.text.orEmpty()
        }
        val dict = st.translation?.dict.orEmpty().joinToString("\n") { "${it.first}: ${it.second}" }
        transDict.text = dict
        transDict.isVisible = dict.isNotEmpty()

        defSection.isVisible = st.defWanted
        defProgress.isVisible = st.defLoading
        defText.text = when {
            st.defLoading -> ""
            st.definition != null -> formatDefinition(st.definition)
            else -> st.defError.orEmpty()
        }

        btnStar.setImageResource(if (st.saved) R.drawable.ic_star else R.drawable.ic_star_border)
        if (st.saved) btnStar.setColorFilter(0xFFF5B400.toInt()) else btnStar.clearColorFilter()
        btnStar.contentDescription = if (st.saved) "Aus Vokabelliste entfernen" else "In Vokabelliste speichern"
        btnHighlight.isVisible = modeApplied == 0
        btnHighlight.text = if (st.highlight != null) "Markierung entfernen" else "Markieren"
    }

    /** Englische Definition hübsch setzen: Aussprache, Wortart, nummerierte Bedeutungen, Beispiele. */
    private fun formatDefinition(d: DictEntry): CharSequence {
        val gray = transDict.currentTextColor
        val accent = MaterialColors.getColor(transCard, com.google.android.material.R.attr.colorPrimary)
        val sb = SpannableStringBuilder()
        if (d.phonetic.isNotEmpty()) {
            val s0 = sb.length
            sb.append(d.phonetic)
            sb.setSpan(ForegroundColorSpan(gray), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n")
        }
        var lastPos: String? = null
        var n = 0
        for (s in d.senses.take(6)) {
            if (s.pos != lastPos) {
                if (sb.isNotEmpty() && lastPos != null) sb.append("\n")
                val s0 = sb.length
                sb.append(s.pos.ifEmpty { "–" })
                sb.setSpan(StyleSpan(Typeface.ITALIC), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(accent), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
                lastPos = s.pos
                n = 0
            }
            n++
            sb.append("$n. ").append(s.definition).append("\n")
            if (s.example.isNotEmpty()) {
                val s0 = sb.length
                sb.append("    „").append(s.example).append("“\n")
                sb.setSpan(StyleSpan(Typeface.ITALIC), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(gray), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun closeTranslation() {
        vm.closeLookup()
        selectedView?.clearSelection()
        selectedView = null
    }

    /* ------------------------------------------------------------------ */
    /*  Host-Callbacks der Seitenansichten                                 */
    /* ------------------------------------------------------------------ */

    override fun onTapZone(zone: Int) {
        if (zone == 0) toggleMenu() else turn(zone)
    }

    override fun consumeTapForPopup(): Boolean {
        var consumed = false
        if (transCard.isVisible) {
            closeTranslation()
            consumed = true
        }
        if (menuVisible || settingsPanel.isVisible) {
            hideMenus()
            consumed = true
        }
        return consumed
    }

    override fun onSelectionStarted(view: ReaderPageView) {
        if (selectedView != null && selectedView !== view) selectedView?.clearSelection()
        selectedView = view
        if (menuVisible || settingsPanel.isVisible) hideMenus()
    }

    override fun onSelectionCleared() {
        selectedView = null
        vm.closeLookup()
    }

    override fun onWordSelected(view: ReaderPageView, sel: Selection, top: Float, bottom: Float) {
        selectedView = view
        placeCard(atTop = bottom > view.height * 0.55f)
        vm.startLookup(sel)
    }

    override fun highlightsFor(block: Int): List<Highlight> = vm.annotations?.highlightsFor(block) ?: emptyList()

    override fun speakingRange(): TextRange? = speaking

    override fun pageHasBookmark(pageIndex: Int): Boolean = vm.bookmarkOnPage(pageSet, pageIndex) != null

    override fun originalHasBookmark(index: Int): Boolean = vm.bookmarkOnPdfPage(index) != null

    private fun imageKey(frag: ImageFrag) = "r:${frag.block}:${frag.w.toInt()}x${frag.h.toInt()}"

    override fun cachedImage(frag: ImageFrag, block: ImageBlock): Bitmap? = vm.bitmaps.get(imageKey(frag))

    override fun requestImage(view: ReaderPageView, frag: ImageFrag, block: ImageBlock) {
        val key = imageKey(frag)
        if (key in failed || !pending.add(key)) return
        val w = frag.w.toInt().coerceIn(1, 4000)
        val h = frag.h.toInt().coerceIn(1, 4000)
        lifecycleScope.launch {
            val bmp = try { vm.loadRegion(key, block, w, h) } finally { pending.remove(key) }
            if (bmp == null) failed.add(key)
            view.invalidate()
        }
    }

    override fun cachedOriginal(index: Int, w: Int, h: Int): Bitmap? = vm.bitmaps.get("o:$index:${w}x$h")

    override fun requestOriginal(view: OriginalPageView, index: Int, w: Int, h: Int) {
        val key = "o:$index:${w}x$h"
        if (key in failed || !pending.add(key)) return
        lifecycleScope.launch {
            val bmp = try { vm.loadOriginal(key, index, w, h) } finally { pending.remove(key) }
            if (bmp == null) failed.add(key)
            view.invalidate()
        }
    }

    override fun onOriginalLongPress() {
        toast("Zum Markieren und Übersetzen oben auf „Lesemodus“ wechseln.")
    }
}
