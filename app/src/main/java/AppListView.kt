package com.pockethub.launcher

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Insets
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * Screen content in "app list" mode: all apps instead of the desktop.
 * Search bar with a settings button on top; the list scrolls vertically (GridView)
 * or horizontally (columns filled top to bottom).
 */
@SuppressLint("ViewConstructor")
class AppListView(private val host: HomeActivity) : LinearLayout(host) {
    private val search = EditText(host)
    private val body = FrameLayout(host)
    private val spacer = View(host).apply { visibility = GONE }
    private var horizontal = false
    private var cols = 0
    private var built = false
    private var shown: List<AppInfo> = emptyList()

    private var gridView: GridView? = null
    private var hScroll: HorizontalScrollView? = null
    private var hGrid: GridLayout? = null
    private var lastRows = 0

    private val cellW = dp(92)
    private val cellH = dp(104)

    private val listAdapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? AppCellView ?: AppCellView(host)
            cell.bind(shown[position])
            return cell
        }
    }

    init {
        orientation = VERTICAL
        val bar = LinearLayout(host).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(6))
        }
        search.apply {
            hint = I18n.t("search_hint")
            setSingleLine(true)
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_DIM)
            textSize = 15f
            background = roundedBg(0x40000000, dp(22).toFloat(), Look.GLASS_STROKE, dp(1))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { filter() }
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) {
                    shown.firstOrNull()?.let { host.launch(it.key, host.displayIdHere, null) }
                    true
                } else false
            }
        }
        bar.addView(search, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // Spacer that takes the search field's place when the search bar is turned off.
        bar.addView(spacer, LayoutParams(0, 0, 1f))
        bar.addView(TextView(host).apply {
            text = "⚙"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            background = pressBg(roundedBg(0x40000000, dp(22).toFloat(), Look.GLASS_STROKE, dp(1)))
            contentDescription = I18n.t("settings")
            isFocusable = true
            setOnClickListener { host.openSettings() }
        }, LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(10) })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Scrolling direction and columns (vertical mode; 0 = automatic). */
    fun configure(horizontal: Boolean, cols: Int) {
        if (built && horizontal == this.horizontal && cols == this.cols) return
        this.horizontal = horizontal
        this.cols = cols
        built = true
        applyStyle()
        rebuild()
    }

    private fun rebuild() {
        body.removeAllViews()
        gridView = null; hScroll = null; hGrid = null; lastRows = 0
        if (!horizontal) {
            val g = GridView(host).apply {
                numColumns = if (cols > 0) cols else GridView.AUTO_FIT
                columnWidth = if (cols > 0) dp(56) else dp(88)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                verticalSpacing = dp(2)
                selector = roundedBg(0x262CFFB1, dp(18).toFloat(), Palette.ACCENT, dp(2))
                setDrawSelectorOnTop(true)
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(8), dp(8), dp(8), dp(16))
                adapter = listAdapter
                setOnItemClickListener { _, v, p, _ -> host.launch(shown[p].key, host.displayIdHere, v) }
                setOnItemLongClickListener { _, v, p, _ -> host.showListAppMenu(shown[p], v); true }
            }
            gridView = g
            body.addView(g, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } else {
            val g = GridLayout(host).apply {
                orientation = GridLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            val sc = HorizontalScrollView(host).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(g)
            }
            hGrid = g
            hScroll = sc
            body.addView(sc, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        filter()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (horizontal) post { fillHorizontal(force = true) }
    }

    /** Horizontal mode: as many rows as fit the height, columns filled top to bottom. */
    private fun fillHorizontal(force: Boolean = false) {
        val g = hGrid ?: return
        val avail = body.height - dp(8)
        if (avail <= 0) {
            post { fillHorizontal() }
            return
        }
        val rows = max(1, avail / cellH)
        lastRows = rows
        g.removeAllViews()
        g.rowCount = rows
        for (a in shown) {
            val cell = AppCellView(host).apply {
                bind(a)
                isClickable = true
                isFocusable = true
                isLongClickable = true
                background = rippleBg(roundedBg(Color.WHITE, dp(16).toFloat()))
                setOnClickListener { host.launch(a.key, host.displayIdHere, this) }
                setOnLongClickListener { host.showListAppMenu(a, this); true }
            }
            g.addView(cell, GridLayout.LayoutParams().apply { width = cellW; height = cellH })
        }
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) Apps.all() else Apps.all().filter {
            it.label.lowercase().contains(q) || it.key.packageName.lowercase().contains(q)
        }
        if (horizontal) fillHorizontal() else listAdapter.notifyDataSetChanged()
    }

    fun refresh() = filter()

    /** Search bar on/off from settings (per screen). */
    fun applyStyle() {
        val on = Prefs.drawerSearch(host, host.screen)
        search.visibility = if (on) VISIBLE else GONE
        spacer.visibility = if (on) GONE else VISIBLE
        if (!on && search.text.isNotEmpty()) search.setText("")
    }

    fun updateTexts() {
        search.hint = I18n.t("search_hint")
    }

    fun clearSearch() {
        if (search.text.isNotEmpty()) search.setText("")
        search.clearFocus()
        gridView?.setSelection(0)
        hScroll?.scrollTo(0, 0)
    }

    fun applyInsets(i: Insets) {
        setPadding(i.left, i.top, i.right, i.bottom)
    }

    // ---------- controller ----------

    fun focusFirst() {
        val g = gridView
        if (g != null) {
            g.requestFocus()
            if (g.selectedItemPosition < 0 && shown.isNotEmpty()) g.setSelection(0)
        } else {
            hGrid?.let { if (it.childCount > 0) it.getChildAt(0).requestFocus() }
        }
    }

    fun selected(): Pair<AppInfo, View>? {
        gridView?.let { g ->
            val pos = g.selectedItemPosition
            return if (pos in shown.indices) shown[pos] to (g.selectedView ?: g) else null
        }
        val g = hGrid ?: return null
        val f = host.currentFocus ?: return null
        val idx = g.indexOfChild(f)
        return if (idx in shown.indices) shown[idx] to f else null
    }

    /** LB/RB: scroll one page. */
    fun pageScroll(dir: Int) {
        hScroll?.let { it.smoothScrollBy(dir * it.width, 0); return }
        gridView?.let { it.smoothScrollBy(dir * it.height, 300) }
    }
}
