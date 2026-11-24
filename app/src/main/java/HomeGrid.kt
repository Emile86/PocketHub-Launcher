package com.pockethub.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Paged home screen grid. The grid handles all touches itself:
 * tap — launch, hold — menu, hold + move — drag,
 * swipe left/right — pages, swipe up — all apps.
 * Page p spans [p * width, (p + 1) * width) horizontally; paging is done with scrollX.
 */
@SuppressLint("ViewConstructor")
class HomeGrid(private val host: HomeActivity) : ViewGroup(host) {

    var cols = 0
        private set
    var rows = 0
        private set
    var pages = 1
        private set
    var currentPage = 0
        private set

    private val minCellW = dp(84)
    private val minCellH = dp(100)
    val iconSize = dp(54)
    private val indicatorH = dp(18)
    private val edgeZone = dp(28)

    private val slop = ViewConfiguration.get(host).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(host).scaledMinimumFlingVelocity.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    // ---- gesture state ----
    private var downX = 0f
    private var downY = 0f
    private var downScrollX = 0
    private var downView: IconView? = null
    private var longPressed = false
    private var swipeDone = false
    private var paging = false
    private var velocity: VelocityTracker? = null
    private var lockedToastShown = false

    // ---- drop animations ----
    private enum class DropAnim { MOVED, FOLDER_CREATED, ADDED_TO_FOLDER }
    /** Played on the icon at [pendingCell] after the next rebind (the drop rebuilds the views). */
    private var pendingAnim: DropAnim? = null
    private var pendingCell: Cell? = null
    private var pendingFromX = 0f   // content coordinates of the dragged icon's center
    private var pendingFromY = 0f
    /** Icon shrinking into the remove target after a drop there. */
    private var ghost: Bitmap? = null
    private var ghostX = 0f
    private var ghostY = 0f
    private var ghostT = 0f

    // ---- "remove from screen" drop zone (top center, only while dragging) ----
    private var removeAllowed = false
    private var removeHot = false
    private val removeR = dp(24).toFloat()
    private val removeHitR = dp(46).toFloat()
    private val removeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val removeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val removeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
    }

    // ---- dragging ----
    private var dragging = false
    private var dragItem: HomeItem? = null
    private var dragIsNew = false
    private var dragSource: IconView? = null
    private var dragBitmap: Bitmap? = null
    private var dragX = 0f
    private var dragY = 0f
    private var target: Cell? = null
    private var edgeDir = 0

    // ---- controller cursor ----
    private var keyCursor = false
    private var focusX = 0
    private var focusY = 0

    private var pageAnim: ValueAnimator? = null
    private var firstBind = true

    private val longPress = Runnable { onLongPress() }
    private val edgeFlip = Runnable { onEdgeFlip() }

    private val targetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332CFFB1 }
    private val targetStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA2CFFB1.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
    }
    private val dotOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT }
    private val dotOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66F1FFF9 }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x262CFFB1 }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ACCENT; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat()
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    // ---------- sizes ----------

    private fun bottomReserve() = if (pages > 1) indicatorH else 0

    private fun dimsFor(w: Int, h: Int): Pair<Int, Int> {
        val l = host.layout
        val aw = max(minCellW, w - paddingLeft - paddingRight)
        val ah = max(minCellH, h - paddingTop - paddingBottom - (if (l.pages > 1) indicatorH else 0))
        val c = if (l.cols > 0) l.cols else (aw / minCellW).coerceIn(3, 12)
        val r = if (l.rows > 0) l.rows else (ah / minCellH).coerceIn(2, 10)
        return c to r
    }

    private fun cellW(w: Int = measuredWidth) = (w - paddingLeft - paddingRight) / max(1, cols)
    private fun cellH(h: Int = measuredHeight) = (h - paddingTop - paddingBottom - bottomReserve()) / max(1, rows)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val (c, r) = dimsFor(w, h)
        if (c != cols || r != rows || host.layout.pages != pages) {
            cols = c; rows = r
            post { rebind() }
        }
        val cw = MeasureSpec.makeMeasureSpec(cellW(w), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(cellH(h), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellW()
        val ch = cellH()
        val pw = width
        for (i in 0 until childCount) {
            val v = getChildAt(i) as IconView
            val x = v.item.page * pw + paddingLeft + v.item.x * cw
            val y = paddingTop + v.item.y * ch
            v.layout(x, y, x + cw, y + ch)
        }
        if (!paging && pageAnim == null) scrollTo(currentPage * pw, 0)
        // A drop just rebuilt the views: animate the icon in the drop cell now that it has its place.
        if (pendingAnim != null && childCount > 0) post { playPendingAnim() }
    }

    // ---------- data binding ----------

    fun rebind() {
        if (dragging) cancelDrag()
        removeCallbacks(longPress)
        removeAllViews()
        val layout = host.layout
        pages = layout.pages
        if (measuredWidth == 0 || cols == 0) return

        var changed = false
        if (layout.lastCols != cols || layout.lastRows != rows) {
            layout.lastCols = cols; layout.lastRows = rows; changed = true
        }
        val perPage = cols * rows
        val visible = layout.items.filter { Apps.isVisible(it) }
        val taken = HashSet<Int>()
        val misplaced = ArrayList<HomeItem>()
        for (item in visible) {
            val ok = item.page in 0 until pages && item.x in 0 until cols && item.y in 0 until rows &&
                taken.add(item.page * perPage + item.y * cols + item.x)
            if (!ok) misplaced += item
        }
        val placed = visible.toMutableList()
        for (item in misplaced) {
            val start = item.page.coerceIn(0, pages - 1)
            val order = listOf(start) + (0 until pages).filter { it != start }
            var free: Int? = null
            for (p in order) {
                free = (p * perPage until (p + 1) * perPage).firstOrNull { it !in taken }
                if (free != null) break
            }
            if (free == null) { placed -= item; continue }
            taken += free
            item.page = free / perPage
            item.x = (free % perPage) % cols
            item.y = (free % perPage) / cols
            changed = true
        }
        for (item in placed) addView(createView(item))
        if (changed) LayoutStore.save(host, host.screen, notify = false)

        if (firstBind) {
            firstBind = false
            currentPage = layout.homePage.coerceIn(0, pages - 1)
        }
        if (currentPage >= pages) currentPage = pages - 1
        focusX = focusX.coerceIn(0, cols - 1)
        focusY = focusY.coerceIn(0, rows - 1)
        requestLayout()
        invalidate()
    }

    private fun createView(item: HomeItem): IconView = when (item) {
        is AppItem -> IconView(host, item, Apps.icon(item.app, iconSize), Apps.get(item.app)?.label ?: "", iconSize)
        is FolderItem -> {
            val mini = max(8, (iconSize * 0.31f).toInt())
            val icons = item.apps.asSequence().mapNotNull { Apps.icon(it, mini) }.take(4).toList()
            IconView(host, item, RoundIcon.folder(icons, iconSize), item.title, iconSize)
        }
    }

    // ---------- pages ----------

    fun snapTo(page: Int, animate: Boolean = true) {
        val p = page.coerceIn(0, max(0, pages - 1))
        currentPage = p
        pageAnim?.cancel()
        pageAnim = null
        val targetX = p * width
        if (!animate || width == 0) {
            scrollTo(targetX, 0)
            invalidate()
            return
        }
        pageAnim = ValueAnimator.ofInt(scrollX, targetX).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (pageAnim === animation) pageAnim = null
                }
            })
            start()
        }
    }

    // ---------- touch ----------

    private fun childAt(x: Float, y: Float): IconView? {
        val cx = x + scrollX
        for (i in childCount - 1 downTo 0) {
            val v = getChildAt(i)
            if (cx >= v.left && cx < v.right && y >= v.top && y < v.bottom) return v as IconView
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hideKeyCursor()
                pageAnim?.cancel(); pageAnim = null
                downX = e.x; downY = e.y; downScrollX = scrollX
                longPressed = false; swipeDone = false; paging = false; dragging = false
                lockedToastShown = false
                downView = childAt(e.x, e.y)?.also {
                    it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                }
                postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                val dist = hypot(dx, dy)
                when {
                    dragging -> updateDrag(e.x, e.y)
                    longPressed -> if (downView != null && dist > slop * 2) {
                        if (Prefs.layoutLocked(context)) {
                            if (!lockedToastShown) {
                                lockedToastShown = true
                                host.closeMenu()
                                context.toast(I18n.t("layout_locked"))
                            }
                        } else {
                            startDragFromGrid()
                            updateDrag(e.x, e.y)
                        }
                    }
                    paging -> scrollTo(resist(downScrollX - dx.roundToInt()), 0)
                    dist > slop -> {
                        removeCallbacks(longPress)
                        releasePressed()
                        if (pages > 1 && abs(dx) > abs(dy)) {
                            paging = true
                            downX = e.x
                            downScrollX = scrollX
                        } else if (!swipeDone && -dy > dp(56) && abs(dy) > abs(dx) * 1.3f) {
                            swipeDone = true
                            host.openDrawer()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val dist = hypot(e.x - downX, e.y - downY)
                val tapped = downView
                when {
                    dragging -> drop()
                    paging -> finishPaging()
                    !longPressed && !swipeDone && dist <= slop && tapped != null -> host.onItemClick(tapped)
                }
                finishGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) cancelDrag()
                if (paging) snapTo(currentPage)
                finishGesture()
            }
        }
        return true
    }

    /** Past the edges, scroll with resistance. */
    private fun resist(x: Int): Int {
        val maxX = (pages - 1) * width
        return when {
            x < 0 -> x / 3
            x > maxX -> maxX + (x - maxX) / 3
            else -> x
        }
    }

    private fun finishPaging() {
        val vt = velocity
        vt?.computeCurrentVelocity(1000)
        val vx = vt?.xVelocity ?: 0f
        val page = when {
            vx < -minFling * 4 -> currentPage + 1
            vx > minFling * 4 -> currentPage - 1
            else -> ((scrollX.toFloat() / max(1, width))).roundToInt()
        }
        paging = false
        snapTo(page)
    }

    private fun onLongPress() {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val v = downView
        if (v != null) {
            releasePressed()
            host.showItemMenu(v)
        } else {
            host.showHomeMenu(downX, downY)
        }
    }

    private fun releasePressed() {
        downView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(90)?.start()
    }

    private fun finishGesture() {
        releasePressed()
        downView = null
        longPressed = false
        paging = false
        velocity?.recycle()
        velocity = null
        // A touch stopped the page animation midway — settle on a page.
        if (!dragging && pageAnim == null && width > 0 && scrollX != currentPage * width) snapTo(currentPage)
        invalidate()
    }

    // ---------- dragging ----------

    private fun startDragFromGrid() {
        val v = downView ?: return
        beginDrag(v.item, v.icon, isNew = false)
        dragSource = v
        v.alpha = 0.25f
    }

    /** Drag an icon that is not on the screen yet (from the all-apps list). */
    fun startExternalDrag(item: HomeItem, icon: Bitmap?, rawX: Float, rawY: Float) {
        removeCallbacks(longPress)
        beginDrag(item, icon, isNew = true)
        externalMove(rawX, rawY)
    }

    fun externalMove(rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        updateDrag(rawX - loc[0], rawY - loc[1])
    }

    fun externalDrop() {
        if (dragging) drop()
    }

    fun externalCancel() {
        if (dragging) cancelDrag()
    }

    private fun beginDrag(item: HomeItem, icon: Bitmap?, isNew: Boolean) {
        dragging = true
        dragItem = item
        dragIsNew = isNew
        dragBitmap = icon
        removeAllowed = host.dragRemovable(isNew)
        removeHot = false
        host.closeMenu()
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun removeCy() = paddingTop + dp(30).toFloat()

    private fun updateDrag(x: Float, y: Float) {
        dragX = x; dragY = y
        val hot = removeAllowed && hypot(x - width / 2f, y - removeCy()) < removeHitR
        if (hot != removeHot) {
            removeHot = hot
            if (hot) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        target = if (hot) null else cellAt(x, y)
        val dir = when {
            x < edgeZone && currentPage > 0 -> -1
            x > width - edgeZone && currentPage < pages - 1 -> 1
            else -> 0
        }
        if (dir != edgeDir) {
            edgeDir = dir
            removeCallbacks(edgeFlip)
            if (dir != 0) postDelayed(edgeFlip, 600)
        }
        invalidate()
    }

    private fun onEdgeFlip() {
        if (!dragging || edgeDir == 0) return
        snapTo(currentPage + edgeDir)
        target = cellAt(dragX, dragY)
        // The finger is still at the edge — keep paging.
        if ((edgeDir < 0 && currentPage > 0) || (edgeDir > 0 && currentPage < pages - 1)) {
            postDelayed(edgeFlip, 800)
        }
        invalidate()
    }

    /** x, y are relative to the visible part of the grid. */
    /** Cell under a point of the visible grid (e.g. where empty space was long-pressed). */
    fun cellAtPoint(x: Float, y: Float): Cell? = cellAt(x, y)

    private fun cellAt(x: Float, y: Float): Cell? {
        val cw = cellW()
        val ch = cellH()
        if (cw <= 0 || ch <= 0) return null
        val cx = floor((x - paddingLeft) / cw).toInt()
        val cy = floor((y - paddingTop) / ch).toInt()
        return if (cx in 0 until cols && cy in 0 until rows) Cell(currentPage, cx, cy) else null
    }

    private fun drop() {
        val item = dragItem
        val cell = target
        val isNew = dragIsNew
        val remove = removeHot && removeAllowed
        val bmp = dragBitmap
        val fromX = scrollX + dragX
        val fromY = dragY - iconSize * 0.4f
        val source = dragSource
        endDrag()
        when {
            item == null -> {}
            remove -> playRemove(bmp, fromX, fromY) { host.removeDraggedItem(item, isNew) }
            cell != null -> {
                val occupant = viewAtCell(cell)?.takeIf { it !== source }
                pendingAnim = when {
                    item is AppItem && occupant?.item is AppItem -> DropAnim.FOLDER_CREATED
                    item is AppItem && occupant?.item is FolderItem -> DropAnim.ADDED_TO_FOLDER
                    else -> DropAnim.MOVED
                }
                pendingCell = cell
                pendingFromX = fromX
                pendingFromY = fromY
                host.dropItem(item, cell, isNew)
            }
        }
    }

    private fun viewAtCell(c: Cell): IconView? {
        for (i in 0 until childCount) {
            val v = getChildAt(i) as IconView
            if (v.item.page == c.page && v.item.x == c.x && v.item.y == c.y) return v
        }
        return null
    }

    /** Dropped on the remove target: the icon shrinks and fades into the ✕, then [then] runs. */
    private fun playRemove(bmp: Bitmap?, fromX: Float, fromY: Float, then: () -> Unit) {
        if (bmp == null) { then(); return }
        ghost = bmp
        ghostX = fromX
        ghostY = fromY
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = android.view.animation.AccelerateInterpolator(1.5f)
            addUpdateListener { ghostT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    ghost = null
                    invalidate()
                    then()
                }
            })
            start()
        }
    }

    /** After a drop: animate the icon that ended up in the drop cell. */
    private fun playPendingAnim() {
        val kind = pendingAnim ?: return
        val cell = pendingCell
        pendingAnim = null
        pendingCell = null
        val v = cell?.let { viewAtCell(it) } ?: return
        v.animate().cancel()
        when (kind) {
            DropAnim.MOVED -> {
                // Glide from where the finger let go into the cell.
                v.translationX = pendingFromX - (v.left + v.width / 2f)
                v.translationY = pendingFromY - (v.top + v.height / 2f) + v.height * 0.1f
                v.scaleX = 1.1f; v.scaleY = 1.1f
                v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            }
            DropAnim.FOLDER_CREATED -> {
                // New folder pops in.
                v.scaleX = 0.5f; v.scaleY = 0.5f; v.alpha = 0.4f
                v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(340).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
            }
            DropAnim.ADDED_TO_FOLDER -> {
                // The folder "swallows" the icon: quick swell, then back.
                v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(110)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                    }.start()
            }
        }
    }

    private fun cancelDrag() = endDrag()

    private fun endDrag() {
        removeCallbacks(edgeFlip)
        edgeDir = 0
        dragSource?.alpha = 1f
        dragSource = null
        dragItem = null
        dragBitmap = null
        target = null
        dragging = false
        removeHot = false
        removeAllowed = false
        invalidate()
    }

    // ---------- controller ----------

    fun hideKeyCursor() {
        if (!keyCursor) return
        keyCursor = false
        invalidate()
    }

    private fun focusedView(): IconView? {
        for (i in 0 until childCount) {
            val v = getChildAt(i) as IconView
            if (v.item.page == currentPage && v.item.x == focusX && v.item.y == focusY) return v
        }
        return null
    }

    private fun pageViews(p: Int): List<IconView> =
        (0 until childCount).map { getChildAt(it) as IconView }.filter { it.item.page == p }

    /** The icon nearest to cell (x, y). */
    private fun nearest(views: List<IconView>, x: Int, y: Int): IconView? =
        views.minByOrNull { abs(it.item.x - x) * 2 + abs(it.item.y - y) * 3 }

    private fun focusOn(v: IconView) {
        focusX = v.item.x
        focusY = v.item.y
    }

    /** Nearest desktop in direction dir (−1/+1) that has icons; null if there is none. */
    private fun pageWithItems(from: Int, dir: Int): Int? {
        var p = from + dir
        while (p in 0 until pages) {
            if (pageViews(p).isNotEmpty()) return p
            p += dir
        }
        return null
    }

    /** After a page change, select the nearest icon on it (if any). */
    private fun refocusOnPage(page: Int, x: Int, y: Int) {
        nearest(pageViews(page), x, y)?.let { focusOn(it) }
    }

    /**
     * Move the cursor in direction (dx, dy): straight to the nearest icon that way, empty cells
     * are skipped. If there is no icon that way: left/right — next desktop with icons, up/down — stay.
     */
    private fun step(dx: Int, dy: Int) {
        val cur = focusedView()
        val candidates = pageViews(currentPage).filter { v ->
            v !== cur && when {
                dx < 0 -> v.item.x < focusX
                dx > 0 -> v.item.x > focusX
                dy < 0 -> v.item.y < focusY
                else -> v.item.y > focusY
            }
        }
        val best = candidates.minByOrNull { v ->
            val along = if (dx != 0) abs(v.item.x - focusX) else abs(v.item.y - focusY)
            val across = if (dx != 0) abs(v.item.y - focusY) else abs(v.item.x - focusX)
            along + across * 2
        }
        if (best != null) {
            focusOn(best)
            return
        }
        // No icons that way: left/right — go to the nearest desktop with icons
        // (empty ones are skipped); if there is none, stay put.
        if (dx != 0) {
            val np = pageWithItems(currentPage, dx) ?: return
            snapTo(np)
            refocusOnPage(np, if (dx > 0) 0 else cols - 1, focusY)
        }
    }

    /**
     * Controller buttons on the desktop. Returns true if the key was handled.
     * The cursor only moves between icons; LB/RB page, A — open, Y — menu, X — all apps.
     */
    fun handleKey(code: Int): Boolean {
        if (cols == 0 || rows == 0 || dragging) return false
        when (code) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                val np = currentPage + if (code == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1
                if (np in 0 until pages) {
                    snapTo(np)
                    if (keyCursor) refocusOnPage(np, focusX, focusY)
                    invalidate()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_X -> { host.openDrawer(); return true }
        }
        val isDpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (!keyCursor || focusedView() == null) {
            // First press (or the selected icon is gone): select the nearest icon.
            keyCursor = true
            refocusOnPage(currentPage, focusX, focusY)
            if (focusedView() == null && isDpad) {
                // Empty desktop (reached via LB/RB or by finger): the D-pad goes
                // to the nearest desktop with icons — the pressed direction first, then the other.
                val dir = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> 0
                }
                val np = (if (dir != 0) pageWithItems(currentPage, dir) else null)
                    ?: pageWithItems(currentPage, -1)
                    ?: pageWithItems(currentPage, 1)
                if (np != null) {
                    val forward = np > currentPage
                    snapTo(np)
                    refocusOnPage(np, if (forward) 0 else cols - 1, focusY)
                }
            }
            invalidate()
            if (isDpad) return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> step(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> step(0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                focusedView()?.let { host.onItemClick(it) }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU -> {
                val v = focusedView()
                // No selected icon (empty desktop) — show the desktop menu in the center.
                if (v != null) host.showItemMenu(v) else host.showHomeMenu(width / 2f, height / 2f)
            }
            else -> return false
        }
        invalidate()
        return true
    }

    // ---------- drawing ----------

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // The canvas is already translated by -scrollX: draw in content coordinates.
        val sx = scrollX.toFloat()
        if (pages > 1 && width > 0) {
            val r = dp(3).toFloat()
            val gap = dp(10).toFloat()
            val total = (pages - 1) * gap
            val cy = height - paddingBottom - indicatorH / 2f
            val start = sx + width / 2f - total / 2f
            for (i in 0 until pages) canvas.drawCircle(start + i * gap, cy, r, if (i == currentPage) dotOn else dotOff)
        }
        if (keyCursor && !dragging && focusedView() != null) {
            val cw = cellW()
            val ch = cellH()
            val inset = dp(4).toFloat()
            val l = currentPage * width + paddingLeft + focusX * cw + inset
            val t = paddingTop + focusY * ch + inset
            val r = RectF(l, t, l + cw - inset * 2, t + ch - inset * 2)
            val rad = dp(20).toFloat()
            canvas.drawRoundRect(r, rad, rad, cursorFill)
            canvas.drawRoundRect(r, rad, rad, cursorStroke)
        }
        ghost?.let { b ->
            // Shrink and fade toward the ✕ target.
            val tx = sx + width / 2f
            val ty = removeCy()
            val x = ghostX + (tx - ghostX) * ghostT
            val y = ghostY + (ty - ghostY) * ghostT
            val s = iconSize * (0.9f - 0.8f * ghostT)
            bmpPaint.alpha = (255 * (1f - ghostT)).toInt()
            canvas.drawBitmap(b, null, RectF(x - s / 2, y - s / 2, x + s / 2, y + s / 2), bmpPaint)
            bmpPaint.alpha = 255
        }
        if (!dragging) return
        if (removeAllowed) drawRemoveZone(canvas, sx + width / 2f, removeCy())
        val t = target
        if (t != null) {
            val cw = cellW()
            val ch = cellH()
            val cx = t.page * width + paddingLeft + t.x * cw + cw / 2f
            val cy = paddingTop + t.y * ch + iconTopOffset(ch) + iconSize / 2f
            val r = iconSize * 0.62f
            if (RoundIcon.shape == IconShape.SQUARE) {
                // Same rounded square as the icons (corner radius 24% of the side).
                val corner = r * 2 * 0.24f
                canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, corner, corner, targetFill)
                canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, corner, corner, targetStroke)
            } else {
                canvas.drawCircle(cx, cy, r, targetFill)
                canvas.drawCircle(cx, cy, r, targetStroke)
            }
        }
        dragBitmap?.let { b ->
            val s = iconSize * if (removeHot) 0.9f else 1.12f
            val x = sx + dragX
            bmpPaint.alpha = if (removeHot) 150 else 255
            canvas.drawBitmap(b, null, RectF(x - s / 2, dragY - s * 0.9f, x + s / 2, dragY + s * 0.1f), bmpPaint)
            bmpPaint.alpha = 255
        }
    }

    /** Round "✕" target with a caption; turns red while the icon is over it. */
    private fun drawRemoveZone(canvas: Canvas, cx: Float, cy: Float) {
        val r = if (removeHot) removeR * 1.2f else removeR
        removeFill.color = if (removeHot) 0xE6E03131.toInt() else 0x99000000.toInt()
        removeStroke.color = if (removeHot) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        canvas.drawCircle(cx, cy, r, removeFill)
        canvas.drawCircle(cx, cy, r, removeStroke)
        val a = r * 0.38f
        canvas.drawLine(cx - a, cy - a, cx + a, cy + a, removeStroke)
        canvas.drawLine(cx + a, cy - a, cx - a, cy + a, removeStroke)
        removeText.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        canvas.drawText(I18n.t("remove_from_screen"), cx, cy + r + dp(16), removeText)
    }

    private fun iconTopOffset(cellHeight: Int): Float {
        val textH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics) * 1.2f
        return max(0f, (cellHeight - iconSize - dp(6) - textH) / 2f)
    }
}

/** A cell icon: icon + label. */
@SuppressLint("ViewConstructor")
class IconView(
    ctx: Context,
    val item: HomeItem,
    val icon: Bitmap?,
    private val label: String,
    private val iconSize: Int,
) : View(ctx) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, ctx.resources.displayMetrics)
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gap = dp(6)

    init {
        contentDescription = label
    }

    private fun iconTopOffset(): Float {
        val textH = textPaint.descent() - textPaint.ascent()
        return max(0f, (height - iconSize - gap - textH) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val top = iconTopOffset()
        val left = (width - iconSize) / 2f
        icon?.let { canvas.drawBitmap(it, null, RectF(left, top, left + iconSize, top + iconSize), bmpPaint) }
        val text = TextUtils.ellipsize(label, textPaint, max(0f, (width - dp(8)).toFloat()), TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, width / 2f, top + iconSize + gap - textPaint.ascent(), textPaint)
    }
}
