package com.pockethub.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MenuEntry(val title: String, val action: () -> Unit)

/** In-window context menu (no separate PopupWindow, so the drag gesture keeps working). */
class MenuOverlay(private val ctx: Context, private val root: FrameLayout) {
    private val catcher = View(ctx)
    private val list = LinearLayout(ctx)
    private val panel = ScrollView(ctx)
    var isShowing = false
        private set

    init {
        catcher.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) dismiss()
            true
        }
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, ctx.dp(6), 0, ctx.dp(6))
        list.minimumWidth = ctx.dp(230)
        panel.addView(list)
        panel.background = roundedBg(Palette.PANEL, ctx.dp(18).toFloat(), Palette.PANEL_STROKE, ctx.dp(1))
        panel.clipToOutline = true
        panel.elevation = ctx.dp(10).toFloat()
        panel.isVerticalScrollBarEnabled = false
    }

    fun show(anchor: Rect, title: String?, entries: List<MenuEntry>, focusFirst: Boolean = false) {
        dismiss()
        list.removeAllViews()
        if (title != null) {
            list.addView(TextView(ctx).apply {
                text = title
                setTextColor(Palette.TEXT_DIM)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(18), dp(8), dp(18), dp(6))
            })
        }
        for (e in entries) {
            list.addView(TextView(ctx).apply {
                text = e.title
                setTextColor(Palette.TEXT)
                textSize = 15f
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = rippleBg()
                isFocusable = true
                setOnClickListener { dismiss(); e.action() }
            })
        }
        root.addView(catcher, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val margin = ctx.dp(8)
        val rw = root.width
        val rh = root.height
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(max(0, rw - margin * 2), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(max(0, rh - margin * 2), View.MeasureSpec.AT_MOST),
        )
        val w = panel.measuredWidth
        val h = panel.measuredHeight
        val x = (anchor.centerX() - w / 2).coerceIn(margin, max(margin, rw - w - margin))
        val y = when {
            anchor.bottom + h + margin <= rh -> anchor.bottom
            anchor.top - h - margin >= 0 -> anchor.top - h
            else -> max(margin, rh - h - margin)
        }
        val lp = FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = x
            topMargin = y
        }
        root.addView(panel, lp)
        panel.alpha = 0f
        panel.scaleX = 0.95f
        panel.scaleY = 0.95f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start()
        isShowing = true
        if (focusFirst) {
            val first = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }
            first?.requestFocus()
        }
    }

    fun dismiss(): Boolean {
        if (!isShowing) return false
        isShowing = false
        root.removeView(catcher)
        root.removeView(panel)
        return true
    }
}

/** App cell for the all-apps list and folders. */
@SuppressLint("ViewConstructor")
class AppCellView(ctx: Context) : LinearLayout(ctx) {
    private val image = ImageView(ctx)
    private val text = TextView(ctx)
    private val iconPx = dp(ICON_DP)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(image, LayoutParams(iconPx, iconPx))
        text.apply {
            setTextColor(Palette.TEXT)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
        }
        addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    fun bind(a: AppInfo) {
        text.text = a.label
        image.setImageBitmap(Apps.icon(a.key, iconPx))
        contentDescription = a.label
    }

    companion object { const val ICON_DP = 54 }
}

class MaxHeightScrollView(ctx: Context, private val maxH: () -> Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = maxH()
        super.onMeasure(widthMeasureSpec, if (m > 0) MeasureSpec.makeMeasureSpec(m, MeasureSpec.AT_MOST) else heightMeasureSpec)
    }
}

/**
 * An open folder. Opens growing out of its icon and shrinks back on close.
 * Hold an app and move the finger to drag it out onto the desktop.
 */
@SuppressLint("ViewConstructor")
class FolderPanel(private val host: HomeActivity, val folder: FolderItem) : FrameLayout(host) {
    private val card = LinearLayout(host)
    private val title = TextView(host)
    private val grid = GridLayout(host)
    private val scroll = MaxHeightScrollView(host) { (host.window.decorView.height * 0.62f).toInt() }
    private val scrim = ColorDrawable(Palette.SCRIM)

    /** Where the folder icon is (root coordinates), for the open/close animation. */
    private var anchorX = -1f
    private var anchorY = -1f
    private var closing = false

    // ---- dragging an app out of the folder ----
    private val slop = android.view.ViewConfiguration.get(host).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var armedApp: AppInfo? = null
    private var dragging = false

    init {
        background = scrim
        setOnClickListener { host.closeFolder() }
        card.orientation = LinearLayout.VERTICAL
        card.background = roundedBg(Palette.PANEL, dp(28).toFloat(), Palette.PANEL_STROKE, dp(1))
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.isClickable = true
        // The scrim and the card catch touches but must not take controller focus.
        isFocusable = false
        card.isFocusable = false
        title.apply {
            setTextColor(Palette.TEXT)
            textSize = 18f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(4), dp(8), dp(10))
            setOnClickListener { host.renameFolder(folder) }
        }
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(grid)
        card.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        refresh()
    }

    fun refresh() {
        if (closing) return
        if (!host.layout.items.contains(folder)) { host.closeFolder(); return }
        val apps = folder.apps.mapNotNull { Apps.get(it) }
        if (apps.isEmpty()) { host.closeFolder(); return }
        title.text = folder.title
        grid.removeAllViews()
        val n = apps.size
        grid.columnCount = min(n, max(2, ceil(sqrt(n.toDouble())).toInt())).coerceAtMost(5)
        for (a in apps) {
            val cell = AppCellView(host)
            cell.bind(a)
            cell.background = rippleBg(roundedBg(Color.WHITE, dp(16).toFloat()))
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener {
                host.closeFolder()
                host.launch(a.key, host.displayIdHere, null)
            }
            cell.setOnLongClickListener {
                // Hold: menu; keep moving the finger — drag the app out onto the desktop.
                armedApp = if (Prefs.layoutLocked(host)) null else a
                host.showFolderAppMenu(folder, a, it)
                true
            }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = dp(88) })
        }
    }

    fun focusFirst() {
        if (grid.childCount > 0) grid.getChildAt(0).requestFocus()
    }

    // ---------- animation ----------

    private fun setPivotFromAnchor() {
        if (anchorX < 0 || card.width == 0) {
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
        } else {
            card.pivotX = (anchorX - card.left).coerceIn(0f, card.width.toFloat())
            card.pivotY = (anchorY - card.top).coerceIn(0f, card.height.toFloat())
        }
    }

    /** Grow out of the folder icon (ax, ay in root coordinates; negative = from the center). */
    fun animateIn(ax: Float, ay: Float) {
        anchorX = ax
        anchorY = ay
        scrim.alpha = 0
        card.alpha = 0f
        card.post {
            setPivotFromAnchor()
            card.scaleX = 0.4f
            card.scaleY = 0.4f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
            fadeScrim(0, 255, 220)
        }
    }

    /** Shrink back into the folder icon, then run [end] (removes the panel). */
    fun animateOut(end: () -> Unit) {
        if (closing) return
        closing = true
        setPivotFromAnchor()
        card.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(170)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction { end() }.start()
        fadeScrim(scrim.alpha, 0, 170)
    }

    private fun fadeScrim(from: Int, to: Int, ms: Long) {
        android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = ms
            addUpdateListener { scrim.alpha = it.animatedValue as Int }
            start()
        }
    }

    // ---------- touch: drag out ----------

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (closing && !dragging) return true
        if (dragging) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> host.drawerDragMove(e.rawX, e.rawY)
                MotionEvent.ACTION_UP -> { dragging = false; host.folderDragDrop() }
                MotionEvent.ACTION_CANCEL -> { dragging = false; host.folderDragCancel() }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                armedApp = null
            }
            MotionEvent.ACTION_MOVE -> {
                val app = armedApp
                if (app != null && kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > slop * 2) {
                    armedApp = null
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    dragging = true
                    closing = true
                    animate().alpha(0f).setDuration(120).start()
                    host.startFolderDrag(folder, app, e.rawX, e.rawY)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }
}

/** All-apps list (swipe up on the desktop). */
@SuppressLint("ViewConstructor")
class AllAppsPanel(private val host: HomeActivity) : LinearLayout(host) {
    private val search = EditText(host)
    private val gridView = GridView(host)
    private var shown: List<AppInfo> = emptyList()
    private var downY = 0f
    private var downX = 0f
    private var atTop = false
    private var armedApp: AppInfo? = null
    private var externalDragging = false
    private val slop = android.view.ViewConfiguration.get(host).scaledTouchSlop.toFloat()
    var isOpen = false
        private set

    private val appsAdapter = object : BaseAdapter() {
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
        setBackgroundColor(Palette.DRAWER_BG)
        isClickable = true
        isFocusable = false
        visibility = GONE

        val handle = View(host).apply { background = roundedBg(0x66FFFFFF, dp(3).toFloat()) }
        addView(handle, LayoutParams(dp(40), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4); bottomMargin = dp(10)
        })

        search.apply {
            hint = I18n.t("search_hint")
            setSingleLine(true)
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_DIM)
            textSize = 15f
            background = roundedBg(0x26FFFFFF, dp(22).toFloat())
            setPadding(dp(18), dp(10), dp(18), dp(10))
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { filter() }
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) {
                    shown.firstOrNull()?.let { a ->
                        host.closeDrawer()
                        host.launch(a.key, host.displayIdHere, null)
                    }
                    true
                } else false
            }
        }
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16)
        })

        gridView.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(88)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(2)
            selector = roundedBg(0x262CFFB1, dp(18).toFloat(), Palette.ACCENT, dp(2))
            setDrawSelectorOnTop(true)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), dp(12), dp(8), dp(12))
            adapter = appsAdapter
            setOnItemClickListener { _, v, p, _ ->
                val a = shown[p]
                host.launch(a.key, host.displayIdHere, v)
                host.closeDrawer()
            }
            setOnItemLongClickListener { _, v, p, _ ->
                // Hold: menu; keep moving the finger — drag to the desktop.
                armedApp = if (Prefs.layoutLocked(host)) null else shown[p]
                host.showDrawerAppMenu(shown[p], v)
                true
            }
        }
        addView(gridView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Swipe down while the list is scrolled to the top — close.
     * Hold an app and move the finger — drag it to the desktop.
     */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (externalDragging) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> host.drawerDragMove(e.rawX, e.rawY)
                MotionEvent.ACTION_UP -> { externalDragging = false; host.drawerDragDrop(); finishHidden() }
                MotionEvent.ACTION_CANCEL -> { externalDragging = false; host.drawerDragCancel(); finishHidden() }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                armedApp = null
                atTop = !gridView.canScrollVertically(-1)
            }
            MotionEvent.ACTION_MOVE -> {
                val app = armedApp
                if (app != null && kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > slop * 2) {
                    armedApp = null
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    externalDragging = true
                    hideForDrag()
                    host.startDrawerDrag(app, e.rawX, e.rawY)
                    return true
                }
                if (app == null && atTop && isOpen && e.rawY - downY > dp(90)) {
                    atTop = false
                    host.closeDrawer()
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun hideForDrag() {
        isOpen = false
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
        animate().alpha(0f).setDuration(120).start()
    }

    private fun finishHidden() {
        animate().cancel()
        visibility = GONE
        search.setText("")
    }

    /** Focus the app grid (for the controller). */
    fun focusGrid() {
        gridView.requestFocus()
        if (gridView.selectedItemPosition < 0 && shown.isNotEmpty()) gridView.setSelection(0)
    }

    fun selected(): Pair<AppInfo, View>? {
        val pos = gridView.selectedItemPosition
        if (pos !in shown.indices) return null
        return shown[pos] to (gridView.selectedView ?: gridView)
    }

    fun updateTexts() {
        search.hint = I18n.t("search_hint")
    }

    fun applyInsets(i: Insets) {
        setPadding(i.left, i.top + dp(8), i.right, i.bottom)
    }

    /** List columns from settings (0 = automatic by width). */
    private fun applyColumns() {
        val c = Prefs.drawerCols(host, host.screen)
        if (c > 0) {
            gridView.numColumns = c
            gridView.columnWidth = dp(56)
        } else {
            gridView.numColumns = GridView.AUTO_FIT
            gridView.columnWidth = dp(88)
        }
        gridView.post {
            val n = gridView.numColumns
            if (c == 0 && n > 0) Prefs.putInt(host, Prefs.drawerLastColsKey(host.screen), n)
        }
    }

    /** Background opacity and search bar from settings (per screen). */
    fun applyStyle() {
        val alpha = (Prefs.drawerAlpha(host, host.screen).coerceIn(0, 100) * 255 / 100)
        setBackgroundColor((alpha shl 24) or (Palette.DRAWER_BG and 0x00FFFFFF))
        search.visibility = if (Prefs.drawerSearch(host, host.screen)) VISIBLE else GONE
        if (search.visibility == GONE && search.text.isNotEmpty()) search.setText("")
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        applyStyle()
        applyColumns()
        refresh()
        gridView.setSelection(0)
        visibility = VISIBLE
        alpha = 0f
        translationY = dp(60).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
        animate().alpha(0f).translationY(dp(60).toFloat()).setDuration(150).withEndAction {
            if (!isOpen) {
                visibility = GONE
                search.setText("")
            }
        }.start()
    }

    fun refresh() = filter()

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) Apps.all() else Apps.all().filter {
            it.label.lowercase().contains(q) || it.key.packageName.lowercase().contains(q)
        }
        appsAdapter.notifyDataSetChanged()
    }
}

/**
 * One-time hint on the desktop: a finger swiping up from the bottom with "Swipe up to open all apps".
 * Does not take touches (the swipe goes to the grid underneath); the home screen removes it on the first touch.
 */
class SwipeHintView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val start = android.os.SystemClock.uptimeMillis()
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val trail = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val text = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = 14f * ctx.resources.displayMetrics.scaledDensity
    }
    private val path = android.graphics.Path()
    private val label = I18n.t("swipe_hint")

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val bottomInset = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
        val cx = width / 2f
        val y0 = height - bottomInset - 36 * d
        val travel = 110 * d
        val p = ((android.os.SystemClock.uptimeMillis() - start) / 1000f) % 1.8f
        val k = (p / 1.1f).coerceIn(0f, 1f)
        val ease = 1f - (1f - k) * (1f - k)
        val y = y0 - travel * ease
        val alpha = when {
            p < 0.15f -> p / 0.15f
            p > 1.1f -> (1f - (p - 1.1f) / 0.4f).coerceIn(0f, 1f)
            else -> 1f
        }

        // trail
        trail.strokeWidth = 8 * d
        trail.color = ((alpha * 0x66).toInt() shl 24) or 0x2CFFB1
        c.drawLine(cx, y0, cx, y, trail)
        // chevron above the finger
        path.reset()
        val a = 8 * d
        path.moveTo(cx - a, y - 12 * d + a * 0.4f)
        path.lineTo(cx, y - 12 * d - a * 0.6f)
        path.lineTo(cx + a, y - 12 * d + a * 0.4f)
        trail.strokeWidth = 3 * d
        trail.color = ((alpha * 0xFF).toInt() shl 24) or 0x2CFFB1
        c.drawPath(path, trail)
        // finger (touch point)
        fill.color = ((alpha * 0x55).toInt() shl 24)
        c.drawCircle(cx, y + 18 * d, 13 * d, fill)
        fill.color = ((alpha * 0xF0).toInt() shl 24) or 0xFFFFFF
        c.drawCircle(cx, y + 16 * d, 12 * d, fill)

        // label pill
        val ty = y0 - travel - 40 * d
        val tw = text.measureText(label) + 36 * d
        fill.color = 0xD90B1015.toInt()
        c.drawRoundRect(cx - tw / 2, ty - 22 * d, cx + tw / 2, ty + 14 * d, 18 * d, 18 * d, fill)
        c.drawText(label, cx, ty + 1 * d, text)

        if (isAttachedToWindow) postInvalidateDelayed(33)
    }
}
