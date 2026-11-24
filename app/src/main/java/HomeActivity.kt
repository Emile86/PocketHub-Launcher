package com.pockethub.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Insets
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max

/** Home screen. The same code runs on the top (display 0) and bottom screens. */
open class HomeActivity : Activity() {

    var displayIdHere = Display.DEFAULT_DISPLAY
        private set
    lateinit var screen: String
        private set

    private lateinit var root: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var animView: AnimatedWallpaperView
    private lateinit var videoView: VideoWallpaperView
    private lateinit var grid: HomeGrid
    private var appList: AppListView? = null
    private var swipeHint: SwipeHintView? = null
    /** The user's own GIF / WebP wallpaper while it is shown. */
    private var animDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
    private var resumed = false
    private var drawer: AllAppsPanel? = null
    private var folderPanel: FolderPanel? = null
    private var menuOverlay: MenuOverlay? = null
    private var lastInsets: Insets = Insets.NONE

    /** The last input came from the controller (cursor/focus are visible). */
    private var keyNav = false

    val layout: ScreenLayout get() = LayoutStore.get(this, screen)

    private val layoutListener: (String) -> Unit = { s ->
        if (s == screen) {
            grid.rebind()
            folderPanel?.refresh()
        }
    }
    private val appsListener: () -> Unit = {
        grid.rebind()
        drawer?.refresh()
        folderPanel?.refresh()
        appList?.refresh()
    }
    private val langListener: () -> Unit = {
        closeMenu()
        drawer?.updateTexts()
        appList?.updateTexts()
    }
    private val wallpaperListener: (String) -> Unit = { s -> if (s == screen) applyWallpaper() }

    /** Settings changed (possibly from SettingsActivity): screen mode, list scrolling, wallpaper. */
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.homeListKey(screen), Prefs.listHorizontalKey(screen), Prefs.drawerColsKey(screen) -> applyMode()
            Prefs.wallpaperTypeKey(screen) -> applyWallpaper()
            Prefs.drawerAlphaKey(screen), Prefs.drawerSearchKey(screen) -> {
                drawer?.applyStyle()
                appList?.applyStyle()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(applicationContext)
        displayIdHere = display?.displayId ?: Display.DEFAULT_DISPLAY
        screen = Displays.screenKey(displayIdHere)
        Apps.init(applicationContext)
        window.setDecorFitsSystemWindows(false)

        root = FrameLayout(this)
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wallpaperView, matchParent())
        videoView = VideoWallpaperView(this).apply { visibility = View.GONE }
        root.addView(videoView, matchParent())
        animView = AnimatedWallpaperView(this).apply { visibility = View.GONE }
        root.addView(animView, matchParent())
        grid = HomeGrid(this)
        root.addView(grid, matchParent())
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val withIme = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            lastInsets = withIme
            grid.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            folderPanel?.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            drawer?.applyInsets(withIme)
            appList?.applyInsets(withIme)
            WindowInsets.CONSUMED
        }

        applyWallpaper()
        applyMode()
        LayoutStore.addListener(layoutListener)
        Apps.addListener(appsListener)
        I18n.addListener(langListener)
        Wallpapers.addListener(wallpaperListener)
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        AppLauncher.warmUp()
        EdgeGestureService.start(this)
        // First start: the setup assistant (on the top screen).
        // Only once per process: choosing our launcher as the default home recreates this activity,
        // and a second launch would stack a fresh assistant on top of the open one.
        if (screen == "top" && !Prefs.setupDone(this) && !setupLaunched) {
            setupLaunched = true
            try { startActivity(Intent(this, SetupActivity::class.java), displayOptions()) } catch (_: Exception) {}
        }
        // Top screen wallpaper was chosen earlier — apply it once as the system wallpaper too.
        // First install (setup not done yet): the lock screen gets the same picture once too.
        val firstRun = !Prefs.setupDone(this)
        if (screen == "top") Thread { Wallpapers.syncSystemIfNeeded(applicationContext, alsoLock = firstRun) }.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (animView.visibility == View.VISIBLE) animView.resume()
        if (videoView.visibility == View.VISIBLE) videoView.resume()
        animDrawable?.start()
        if (screen == "bottom") EdgeGestureService.setBottomHomeVisible(true)
        maybeShowSwipeHint()
    }

    /** First time on the desktop after setup: show "swipe up for all apps" once per screen. */
    private fun maybeShowSwipeHint() {
        if (swipeHint != null || !Prefs.setupDone(this) || Prefs.swipeHintShown(this, screen) || isListMode()) return
        val v = SwipeHintView(this)
        swipeHint = v
        root.addView(v, root.indexOfChild(grid) + 1, matchParent())
        v.alpha = 0f
        v.animate().alpha(1f).setStartDelay(500).setDuration(250).start()
    }

    /** The user started a gesture: hide the hint for good. */
    private fun dismissSwipeHint() {
        val v = swipeHint ?: return
        swipeHint = null
        Prefs.putBool(this, Prefs.swipeHintShownKey(screen), true)
        v.animate().cancel()
        v.animate().alpha(0f).setStartDelay(0).setDuration(180).withEndAction { root.removeView(v) }.start()
    }

    override fun onDestroy() {
        videoView.release()
        LayoutStore.removeListener(layoutListener)
        Apps.removeListener(appsListener)
        I18n.removeListener(langListener)
        Wallpapers.removeListener(wallpaperListener)
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while on the home screen: close everything and go to the main desktop.
        val hadOverlay = closeMenu() or closeFolder() or closeDrawer()
        if (!hadOverlay) {
            grid.snapTo(layout.homePage)
            appList?.clearSearch()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Never close the home screen.
        if (ModernDialog.closeTop(this)) return
        if (closeMenu()) restoreFocus() else closeFolder() || closeDrawer()
    }

    // ---------- controller ----------

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            dismissSwipeHint()
            keyNav = false
            grid.hideKeyCursor()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isNavKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_MENU -> true
        else -> false
    }

    /**
     * Built-in controller. A = select, B = back, Y = menu, X = all apps,
     * LB/RB = adjacent desktop. Keys go to the screen that currently has input focus.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BUTTON_A && Prefs.controllerEnabled(this)) {
            return dispatchKeyEvent(remapKey(e, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B) return super.dispatchKeyEvent(remapKey(e, KeyEvent.KEYCODE_BACK))
        if (!isNavKey(code) || !Prefs.controllerEnabled(this) || ModernDialog.isOpen(this)) return super.dispatchKeyEvent(e)
        keyNav = true
        val down = e.action == KeyEvent.ACTION_DOWN
        val pageKey = code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1
        val d = drawer
        val f = folderPanel
        return when {
            menuOverlay?.isShowing == true -> {
                if (code == KeyEvent.KEYCODE_BUTTON_Y) {
                    if (down) { closeMenu(); restoreFocus() }
                    true
                } else super.dispatchKeyEvent(e)
            }
            f != null -> when {
                pageKey -> true
                code == KeyEvent.KEYCODE_BUTTON_Y -> { if (down) currentFocus?.performLongClick(); true }
                else -> {
                    if (down && currentFocus == null) f.focusFirst()
                    super.dispatchKeyEvent(e)
                }
            }
            d != null && d.isOpen -> when {
                pageKey -> true
                code == KeyEvent.KEYCODE_BUTTON_X -> { if (down) closeDrawer(); true }
                code == KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (down) d.selected()?.let { (app, v) -> showDrawerAppMenu(app, v) }
                    true
                }
                else -> {
                    if (down && currentFocus == null) d.focusGrid()
                    super.dispatchKeyEvent(e)
                }
            }
            isListMode() -> {
                val list = appList!!
                when {
                    pageKey -> { if (down) list.pageScroll(if (code == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1); true }
                    code == KeyEvent.KEYCODE_BUTTON_X -> true
                    code == KeyEvent.KEYCODE_BUTTON_Y -> {
                        if (down) list.selected()?.let { (app, v) -> showListAppMenu(app, v) }
                        true
                    }
                    else -> {
                        if (down && currentFocus == null) list.focusFirst()
                        super.dispatchKeyEvent(e)
                    }
                }
            }
            else -> if (down) grid.handleKey(code) || super.dispatchKeyEvent(e) else true
        }
    }

    private fun isListMode() = appList?.visibility == View.VISIBLE

    /** After the menu closes, return focus to where it was (folder or app list). */
    private fun restoreFocus() {
        if (!keyNav) return
        val f = folderPanel
        val d = drawer
        when {
            f != null -> f.focusFirst()
            d != null && d.isOpen -> d.focusGrid()
            isListMode() -> appList?.focusFirst()
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        animView.pause()
        videoView.pause()
        animDrawable?.stop()
        closeMenu()
        if (screen == "bottom") EdgeGestureService.setBottomHomeVisible(false)
    }

    // ---------- launching ----------

    fun onItemClick(v: IconView) {
        when (val item = v.item) {
            is AppItem -> launch(item.app, displayIdHere, v)
            is FolderItem -> openFolder(item, v)
        }
    }

    fun launch(key: AppKey, displayId: Int, from: View?) {
        val app = Apps.get(key) ?: run { toast(I18n.t("app_not_found")); return }
        val bounds = if (from != null && from.isAttachedToWindow && displayId == displayIdHere) {
            val loc = IntArray(2)
            from.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + from.width, loc[1] + from.height)
        } else null
        AppLauncher.launch(this, app, displayId, bounds)
    }

    private fun launchEntries(key: AppKey): List<MenuEntry> {
        val list = mutableListOf(MenuEntry(I18n.t("launch_top")) { launch(key, Displays.TOP, null) })
        Displays.bottomId(this)?.let { b ->
            list += MenuEntry(I18n.t("launch_bottom")) { launch(key, b, null) }
        }
        return list
    }

    private fun appDetails(app: AppInfo) {
        try {
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(
                app.info.componentName, app.user, null, displayOptions())
        } catch (e: Exception) {
            toast(I18n.t("cant_open"))
        }
    }

    private fun uninstall(app: AppInfo) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.key.packageName, null)), displayOptions())
        } catch (e: Exception) {
            toast(I18n.t("cant_open"))
        }
    }

    fun openSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java), displayOptions())
        } catch (e: Exception) {
            toast(I18n.t("cant_open"))
        }
    }

    private fun displayOptions(): Bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayIdHere).toBundle()

    // ---------- screens ----------

    private fun otherScreen(): String? = when {
        screen == "bottom" -> "top"
        Displays.bottomId(this) != null -> "bottom"
        else -> null
    }

    private fun screenTitle(s: String) = I18n.t(if (s == "top") "top_screen" else "bottom_screen")

    private fun save() = LayoutStore.save(this, screen)

    /** For this screen — the current page; for the other one — its main page. */
    private fun preferredPage(target: String) =
        if (target == screen) grid.currentPage else LayoutStore.get(this, target).homePage

    private fun addToScreen(key: AppKey, target: String) {
        val l = LayoutStore.get(this, target)
        if (l.contains(key)) { toast(I18n.t("already_here")); return }
        val free = l.findFree(preferredPage(target)) ?: run { toast(I18n.t("no_space")); return }
        l.items += AppItem(key).apply { page = free.page; x = free.x; y = free.y }
        LayoutStore.save(this, target)
        toast(I18n.t("added_to", screenTitle(target)))
    }

    private fun moveToOtherScreen(item: HomeItem) {
        val other = otherScreen() ?: return
        val ol = LayoutStore.get(this, other)
        val free = ol.findFree(ol.homePage) ?: run { toast(I18n.t("no_space")); return }
        layout.items.remove(item)
        item.page = free.page
        item.x = free.x
        item.y = free.y
        ol.items += item
        save()
        LayoutStore.save(this, other)
    }

    /** An icon was dropped on a cell. isNew — the icon came from the all-apps list. */
    fun dropItem(item: HomeItem, cell: Cell, isNew: Boolean) {
        if (locked()) return
        val l = layout
        // Dragged out of a folder: remove it there (dropping back on the same folder re-adds it).
        folderDragSource?.let { (f, key) -> f.apps.remove(key) }
        val target = l.items.firstOrNull {
            it !== item && it.page == cell.page && it.x == cell.x && it.y == cell.y && Apps.isVisible(it)
        }
        when {
            target == null -> {
                item.page = cell.page; item.x = cell.x; item.y = cell.y
                if (isNew) l.items += item
            }
            item is AppItem && target is AppItem -> {
                if (!isNew) l.items.remove(item)
                l.items.remove(target)
                val f = FolderItem(I18n.t("folder_default"), mutableListOf(target.app))
                if (item.app != target.app) f.apps += item.app
                f.page = cell.page; f.x = cell.x; f.y = cell.y
                l.items += f
                l.normalizeFolders()
            }
            item is AppItem && target is FolderItem -> {
                if (!isNew) l.items.remove(item)
                if (item.app !in target.apps) target.apps += item.app
            }
            isNew -> {
                // New icon dropped on an occupied cell — put it on the nearest free one.
                val free = l.findFree(cell.page) ?: run { toast(I18n.t("no_space")); return }
                item.page = free.page; item.x = free.x; item.y = free.y
                l.items += item
            }
            else -> {
                // Folder dropped on something — swap them.
                target.page = item.page; target.x = item.x; target.y = item.y
                item.page = cell.page; item.x = cell.x; item.y = cell.y
            }
        }
        l.normalizeFolders()
        save()
    }

    // ---------- dragging from the app list ----------

    fun startDrawerDrag(app: AppInfo, rawX: Float, rawY: Float) {
        closeMenu()
        grid.startExternalDrag(AppItem(app.key), Apps.icon(app.key, grid.iconSize), rawX, rawY)
    }

    fun drawerDragMove(rawX: Float, rawY: Float) = grid.externalMove(rawX, rawY)
    fun drawerDragDrop() = grid.externalDrop()
    fun drawerDragCancel() = grid.externalCancel()

    // ---------- menu ----------

    private fun menu(): MenuOverlay = menuOverlay ?: MenuOverlay(this, root).also { menuOverlay = it }

    fun closeMenu(): Boolean = menuOverlay?.dismiss() ?: false

    private fun rectInRoot(v: View): Rect {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val x = a[0] - b[0]
        val y = a[1] - b[1]
        return Rect(x, y, x + v.width, y + v.height)
    }

    private fun moveToOtherEntry(item: HomeItem): MenuEntry? = otherScreen()?.let { o ->
        MenuEntry(I18n.t(if (o == "top") "move_to_top" else "move_to_bottom")) { moveToOtherScreen(item) }
    }

    fun showItemMenu(v: IconView) {
        val anchor = rectInRoot(v)
        when (val item = v.item) {
            is AppItem -> {
                val app = Apps.get(item.app) ?: return
                val e = launchEntries(item.app).toMutableList()
                if (!locked()) {
                    moveToOtherEntry(item)?.let { e += it }
                    e += MenuEntry(I18n.t("remove_from_screen")) { layout.items.remove(item); save() }
                }
                e += MenuEntry(I18n.t("app_info")) { appDetails(app) }
                e += MenuEntry(I18n.t("uninstall")) { uninstall(app) }
                menu().show(anchor, app.label, e, keyNav)
            }
            is FolderItem -> {
                val e = mutableListOf(MenuEntry(I18n.t("open")) { openFolder(item, v) })
                if (!locked()) {
                    e += MenuEntry(I18n.t("rename")) { renameFolder(item) }
                    e += MenuEntry(I18n.t("ungroup")) { ungroup(item) }
                    moveToOtherEntry(item)?.let { e += it }
                    e += MenuEntry(I18n.t("remove_folder")) { layout.items.remove(item); save() }
                }
                menu().show(anchor, item.title, e, keyNav)
            }
        }
    }

    fun showHomeMenu(x: Float, y: Float) {
        val locked = locked()
        val page = grid.currentPage
        val cell = grid.cellAtPoint(x, y)
        val e = mutableListOf<MenuEntry>()
        if (!locked) {
            e += MenuEntry(I18n.t("new_folder")) { newFolderAt(cell) }
            if (layout.pages < ScreenLayout.MAX_PAGES) e += MenuEntry(I18n.t("add_desktop")) { addDesktop() }
            if (layout.pages > 1) e += MenuEntry(I18n.t("remove_desktop")) { removeDesktop(page) }
        }
        if (layout.pages > 1 && page != layout.homePage) {
            e += MenuEntry(I18n.t("set_main_here")) { layout.homePage = page; save() }
        }
        e += MenuEntry(I18n.t(if (locked) "unlock_layout" else "lock_layout")) {
            Prefs.putBool(this, Prefs.LAYOUT_LOCKED, !locked)
            if (!locked) toast(I18n.t("layout_locked"))
        }
        e += MenuEntry(I18n.t("settings")) { openSettings() }
        val px = x.toInt()
        val py = y.toInt()
        menu().show(Rect(px, py, px, py), null, e, keyNav)
    }

    fun showDrawerAppMenu(app: AppInfo, v: View) {
        val e = mutableListOf<MenuEntry>()
        if (!locked()) {
            e += MenuEntry(I18n.t("add_here")) { addToScreen(app.key, screen) }
            otherScreen()?.let { o ->
                e += MenuEntry(I18n.t(if (o == "top") "add_to_top" else "add_to_bottom")) { addToScreen(app.key, o) }
            }
        }
        if (!locked()) {
            e += MenuEntry(I18n.t("new_folder_with")) { newFolderAt(null, app.key, closeDrawerAfter = true) }
        }
        e += launchEntries(app.key)
        e += MenuEntry(I18n.t("app_info")) { appDetails(app) }
        e += MenuEntry(I18n.t("uninstall")) { uninstall(app) }
        menu().show(rectInRoot(v), app.label, e, keyNav)
    }

    /** Long-press in app list mode. */
    fun showListAppMenu(app: AppInfo, v: View) {
        val e = launchEntries(app.key).toMutableList()
        otherScreen()?.let { o ->
            if (!Prefs.homeIsList(this, o) && !locked()) {
                e += MenuEntry(I18n.t(if (o == "top") "add_to_top" else "add_to_bottom")) { addToScreen(app.key, o) }
            }
        }
        e += MenuEntry(I18n.t("app_info")) { appDetails(app) }
        e += MenuEntry(I18n.t("uninstall")) { uninstall(app) }
        menu().show(rectInRoot(v), app.label, e, keyNav)
    }

    fun showFolderAppMenu(f: FolderItem, app: AppInfo, v: View) {
        val e = launchEntries(app.key).toMutableList()
        if (!locked()) e += MenuEntry(I18n.t("move_out")) {
            val free = layout.findFree(grid.currentPage)
            if (free == null) {
                toast(I18n.t("no_space"))
            } else {
                f.apps.remove(app.key)
                layout.items += AppItem(app.key).apply { page = free.page; x = free.x; y = free.y }
                layout.normalizeFolders()
                save()
            }
        }
        if (!locked()) e += MenuEntry(I18n.t("remove_from_folder")) {
            f.apps.remove(app.key)
            layout.normalizeFolders()
            save()
        }
        e += MenuEntry(I18n.t("app_info")) { appDetails(app) }
        menu().show(rectInRoot(v), app.label, e, keyNav)
    }

    private fun locked() = Prefs.layoutLocked(this)

    // ---------- desktops ----------

    private fun addDesktop() {
        if (!layout.addPage()) return
        save()
        grid.post { grid.snapTo(layout.pages - 1) }
    }

    /** Remove desktop p; asks first if it has icons. */
    private fun removeDesktop(p: Int) {
        val l = layout
        if (l.pages <= 1) return
        val doRemove = {
            l.removePage(p)
            save()
            grid.post { grid.snapTo(minOf(p, l.pages - 1)) }
        }
        if (l.pageItemCount(p) == 0) {
            doRemove()
            return
        }
        ModernDialog.show(this, I18n.t("remove_page_title", p + 1), message = I18n.t("remove_page_msg"),
            positive = I18n.t("remove"), danger = true) { doRemove(); true }
    }

    // ---------- folders ----------

    /** New folder in the long-pressed cell (or the nearest free one): name + apps picked with checkboxes. */
    private fun newFolderAt(cell: Cell?, preselect: AppKey? = null, closeDrawerAfter: Boolean = false) {
        val l = layout
        val target = cell?.takeIf { c ->
            l.items.none { it.page == c.page && it.x == c.x && it.y == c.y && Apps.isVisible(it) }
        } ?: l.findFree(grid.currentPage) ?: run { toast(I18n.t("no_space")); return }

        // Modern picker: name, search and apps with round check marks.
        val listH = (window.decorView.height * 0.30f).toInt().coerceAtLeast(dp(120))
        val (content, nameField, chosen) = ModernDialog.appPicker(this, I18n.t("folder_default"), preselect, listH)
        ModernDialog.show(this, I18n.t("new_folder_title"), content = content, positive = I18n.t("create")) {
            val keys = chosen()
            if (keys.isEmpty()) {
                toast(I18n.t("folder_need_apps"))
                false
            } else {
                val title = nameField.text.toString().trim().ifEmpty { I18n.t("folder_default") }
                l.items += FolderItem(title, keys.toMutableList()).apply {
                    page = target.page; x = target.x; y = target.y
                }
                l.normalizeFolders()
                save()
                if (closeDrawerAfter) {
                    closeDrawer()
                    grid.snapTo(target.page)
                }
                true
            }
        }
    }

    /** Open a folder; it grows out of its icon [from] (or from the center). */
    fun openFolder(f: FolderItem, from: View? = null) {
        closeMenu()
        closeFolder(animate = false)
        val p = FolderPanel(this, f)
        folderPanel = p
        p.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, grid.paddingBottom)
        root.addView(p, matchParent())
        val r = from?.takeIf { it.isAttachedToWindow }?.let { rectInRoot(it) }
        p.animateIn(r?.exactCenterX() ?: -1f, r?.exactCenterY() ?: -1f)
        if (keyNav) p.post { p.focusFirst() }
    }

    fun closeFolder(animate: Boolean = true): Boolean {
        val p = folderPanel ?: return false
        folderPanel = null
        if (animate) p.animateOut { root.removeView(p) } else root.removeView(p)
        return true
    }

    // ---------- dragging an app out of a folder ----------

    /** App being dragged out of a folder: it leaves the folder only when dropped on the desktop. */
    private var folderDragSource: Pair<FolderItem, AppKey>? = null

    fun startFolderDrag(folder: FolderItem, app: AppInfo, rawX: Float, rawY: Float) {
        closeMenu()
        folderDragSource = folder to app.key
        grid.startExternalDrag(AppItem(app.key), Apps.icon(app.key, grid.iconSize), rawX, rawY)
    }

    /** Whether the dragged icon can be dropped on the top "remove from screen" target. */
    fun dragRemovable(isNew: Boolean) = !locked() && (!isNew || folderDragSource != null)

    /** Icon dropped on the remove target: off the desktop (or out of its folder); the app stays installed. */
    fun removeDraggedItem(item: HomeItem, isNew: Boolean) {
        if (locked()) return
        val src = folderDragSource
        when {
            src != null -> src.first.apps.remove(src.second)
            !isNew -> layout.items.remove(item)
            else -> return
        }
        layout.normalizeFolders()
        save()
    }

    fun folderDragDrop() {
        grid.externalDrop()
        folderDragSource = null
        // The panel is still dispatching this touch — remove it right after.
        root.post { closeFolder(animate = false) }
    }

    fun folderDragCancel() {
        grid.externalCancel()
        folderDragSource = null
        // The panel is still dispatching this touch — remove it right after.
        root.post { closeFolder(animate = false) }
    }

    fun renameFolder(f: FolderItem) {
        if (locked()) { toast(I18n.t("layout_locked")); return }
        val input = ModernDialog.field(this, f.title)
        input.setSelectAllOnFocus(true)
        ModernDialog.show(this, I18n.t("folder_name"), content = input, positive = I18n.t("ok")) {
            f.title = input.text.toString().trim().ifEmpty { I18n.t("folder_default") }
            save()
            true
        }
    }

    private fun ungroup(f: FolderItem) {
        val l = layout
        if (!l.items.contains(f)) return
        l.items.remove(f)
        if (l.freeCells(f.page).size < f.apps.size - 1) {
            l.items += f
            toast(I18n.t("not_enough_space"))
            return
        }
        f.apps.forEachIndexed { i, key ->
            val c = if (i == 0) Cell(f.page, f.x, f.y) else l.findFree(f.page)
            if (c != null) l.items += AppItem(key).apply { page = c.page; x = c.x; y = c.y }
        }
        save()
    }

    // ---------- app list ----------

    fun openDrawer() {
        dismissSwipeHint()
        closeMenu()
        closeFolder()
        val d = drawer ?: AllAppsPanel(this).also {
            drawer = it
            // Added above the grid but below the menu.
            root.addView(it, matchParent())
            it.applyInsets(lastInsets)
            root.requestApplyInsets()
        }
        d.open()
        if (keyNav) d.post { d.focusGrid() }
    }

    fun closeDrawer(): Boolean {
        val d = drawer ?: return false
        if (!d.isOpen) return false
        d.close()
        return true
    }

    // ---------- wallpaper ----------

    private fun pickWallpaper() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            startActivityForResult(i, REQ_WALLPAPER)
        } catch (e: Exception) {
            toast(I18n.t("no_picker"))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_WALLPAPER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val b = windowManager.maximumWindowMetrics.bounds
        val side = max(b.width(), b.height())
        Thread {
            val ok = Wallpapers.save(this, screen, uri, side)
            runOnUiThread {
                if (ok) {
                    Prefs.putWallpaperType(this, screen, WallpaperType.IMAGE)
                    Wallpapers.notifyChanged(screen)
                    if (screen == "top") {
                        val app = applicationContext
                        Thread { Wallpapers.syncSystemIfNeeded(app) }.start()
                    }
                } else toast(I18n.t("wallpaper_failed"))
            }
        }.start()
    }

    /** Show the wallpaper chosen for this screen: built-in scene, own GIF, custom image or the system one. */
    private fun applyWallpaper() {
        val type = Prefs.wallpaperType(this, screen)
        animDrawable?.stop()
        animDrawable = null
        if (type == WallpaperType.VIDEO) {
            animView.pause()
            animView.visibility = View.GONE
            wallpaperView.setImageDrawable(null)
            videoView.visibility = View.VISIBLE
            videoView.setSource(Wallpapers.videoFile(this, screen))
            if (resumed) videoView.resume()
            return
        }
        videoView.release()
        videoView.visibility = View.GONE
        if (type.scene) {
            animView.type = type
            animView.visibility = View.VISIBLE
            if (resumed) animView.resume()
            wallpaperView.setImageDrawable(null)
            return
        }
        animView.pause()
        animView.visibility = View.GONE
        when (type) {
            WallpaperType.PIXEL_DUSK, WallpaperType.GAMEPADS, WallpaperType.PIXEL_CASTLE,
            WallpaperType.WIRE_HORIZON, WallpaperType.MEMPHIS -> {
                // Drawn in code at this screen's size.
                val b = windowManager.currentWindowMetrics.bounds
                val w = if (root.width > 0) root.width else b.width()
                val h = if (root.height > 0) root.height else b.height()
                Thread {
                    val bmp = StillRenderer.render(this, type, w, h)
                    runOnUiThread {
                        if (Prefs.wallpaperType(this, screen) == type) wallpaperView.setImageBitmap(bmp)
                    }
                }.start()
            }
            WallpaperType.IMAGE -> loadWallpaper()
            WallpaperType.CUSTOM_ANIM -> Thread {
                val d = Wallpapers.loadAnimated(this, screen)
                runOnUiThread {
                    if (Prefs.wallpaperType(this, screen) != WallpaperType.CUSTOM_ANIM) return@runOnUiThread
                    wallpaperView.setImageDrawable(d)
                    val a = d as? android.graphics.drawable.AnimatedImageDrawable
                    a?.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                    animDrawable = a
                    if (resumed) a?.start()
                }
            }.start()
            else -> wallpaperView.setImageDrawable(null)
        }
    }

    /** Desktop or the full app list, as chosen in settings for this screen. */
    private fun applyMode() {
        if (Prefs.homeIsList(this, screen)) {
            closeMenu(); closeFolder(); closeDrawer()
            grid.visibility = View.GONE
            val list = appList ?: AppListView(this).also {
                appList = it
                // Right above the grid, below the drawer and menus.
                root.addView(it, root.indexOfChild(grid) + 1, matchParent())
                it.applyInsets(lastInsets)
                root.requestApplyInsets()
            }
            list.visibility = View.VISIBLE
            list.configure(Prefs.listHorizontal(this, screen), Prefs.drawerCols(this, screen))
        } else {
            appList?.visibility = View.GONE
            grid.visibility = View.VISIBLE
        }
    }

    private fun loadWallpaper() {
        Thread {
            val bmp = Wallpapers.load(this, screen)
            runOnUiThread { wallpaperView.setImageBitmap(bmp) }
        }.start()
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    companion object {
        private const val REQ_WALLPAPER = 41
        private var setupLaunched = false
    }
}

/** Same logic for the bottom screen; the system starts it via SECONDARY_HOME. */
class SecondaryHomeActivity : HomeActivity()
