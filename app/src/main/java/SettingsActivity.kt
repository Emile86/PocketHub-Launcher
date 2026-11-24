package com.pockethub.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * Launcher settings in PocketHub style: glowing background, glass cards, gradient accents.
 * Sections: Top screen, Bottom screen (with gestures), General.
 */
class SettingsActivity : Activity() {

    companion object {
        private const val AYANEO_HOME = "com.ayaneo.home"
        private const val LAUNCHER3_HOME = "com.android.launcher3/.uioverrides.QuickstepLauncher"
        private const val GITHUB_URL = "https://github.com/Emile86/PocketHub-Launcher"
        private const val REQ_WP_TOP = 201
        private const val REQ_WP_BOTTOM = 202
        private const val REQ_WP_LOCK = 203
        private const val REQ_ANIM_TOP = 204
        private const val REQ_ANIM_BOTTOM = 205
        private const val REQ_VIDEO_TOP = 206
        private const val REQ_VIDEO_BOTTOM = 207
    }

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    private val layoutListener: (String) -> Unit = { content.post { build() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(applicationContext)
        Apps.init(applicationContext)
        window.setDecorFitsSystemWindows(false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AuroraDrawable()
        }

        // ---- header: title on the left, close button on the right ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(18), dp(14))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            letterSpacing = -0.01f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        subtitleView = TextView(this).apply {
            setTextColor(Look.TEXT_2)
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        }
        titles.addView(titleView)
        titles.addView(subtitleView)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "✕"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            background = pressBg(roundedBg(Look.CARD_TOP, dp(24).toFloat(), Look.CARD_STROKE, dp(1)))
            contentDescription = I18n.t("back")
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, 0)
            scroll.setPadding(0, 0, 0, i.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        LayoutStore.addListener(layoutListener)
    }

    override fun onResume() {
        super.onResume()
        build() // e.g. returning from the permission or default home screen
    }

    override fun onDestroy() {
        LayoutStore.removeListener(layoutListener)
        super.onDestroy()
    }

    private fun build() {
        titleView.text = I18n.t("settings")
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        subtitleView.text = "PocketHub Launcher · " + I18n.t("version", versionName)

        // Rebuilding replaces every view; keep the list where the user is instead of jumping to the top.
        val keepY = scroll.scrollY
        content.removeAllViews()
        content.post { makeFocusable(content) }
        restoreScroll(keepY)
        val hasBottom = Displays.bottomId(this) != null
        val gesturesOn = Prefs.handleEnabled(this) || Prefs.swipeEnabled(this)

        if (hasBottom && gesturesOn && !Settings.canDrawOverlays(this)) warningCard()

        generalSection()
        screenSection("top")
        if (hasBottom) screenSection("bottom")
        uninstallCard()
        aboutButton()
    }

    /**
     * Put the list back where the user was. The content is rebuilt and laid out over a few
     * frames (previews, insets), so re-apply the position on every layout for a short while.
     */
    private fun restoreScroll(y: Int) {
        if (y <= 0) return
        val until = android.os.SystemClock.uptimeMillis() + 400
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (android.os.SystemClock.uptimeMillis() > until) { content.removeOnLayoutChangeListener(this); return }
                scroll.scrollTo(0, y)
            }
        }
        content.addOnLayoutChangeListener(listener)
        scroll.scrollTo(0, y)
        scroll.post { scroll.scrollTo(0, y) }
        scroll.postDelayed({ scroll.scrollTo(0, y) }, 60)
        scroll.postDelayed({ scroll.scrollTo(0, y); content.removeOnLayoutChangeListener(listener) }, 420)
    }

    // ---------- sections ----------

    private fun warningCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xFF17110A.toInt(), dp(24).toFloat(), 0x59FFA94D, dp(1))
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        card.addView(TextView(this).apply {
            text = "⚠  " + I18n.t("overlay_missing_title")
            setTextColor(Look.WARN_TEXT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = I18n.t("overlay_missing_msg")
            setTextColor(Look.TEXT_3)
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, dp(14))
        })
        card.addView(button(I18n.t("allow"), Look.WARN_A, Look.WARN_B) { requestOverlay() })
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    private fun screenSection(s: String) {
        val l = LayoutStore.get(this, s)
        val card = section(I18n.t(if (s == "top") "top_screen" else "bottom_screen"), if (s == "top") "▲" else "▼")
        val isList = Prefs.homeIsList(this, s)

        // Screen content: desktop or the full app list.
        card.addView(chipRow(I18n.t("home_content"), listOf(
            I18n.t("mode_desktop") to 0,
            I18n.t("drawer_grid") to 1,
        ), if (isList) 1 else 0) {
            Prefs.putBool(this, Prefs.homeListKey(s), it == 1)
            build()
        })

        if (isList) {
            // App list instead of the desktop: scrolling direction and columns.
            val horizontal = Prefs.listHorizontal(this, s)
            card.addView(chipRow(I18n.t("scrolling"), listOf(
                "↕  " + I18n.t("vertical") to 0,
                "↔  " + I18n.t("horizontal") to 1,
            ), if (horizontal) 1 else 0) {
                Prefs.putBool(this, Prefs.listHorizontalKey(s), it == 1)
                build()
            })
            if (!horizontal) {
                card.addView(autoStepper(I18n.t("columns"), Prefs.drawerCols(this, s), Prefs.drawerLastCols(this, s), 3, 10) {
                    Prefs.putInt(this, Prefs.drawerColsKey(s), it)
                    build()
                })
            }
            card.addView(switchRow(I18n.t("search_bar"), Prefs.drawerSearch(this, s)) {
                Prefs.putBool(this, Prefs.drawerSearchKey(s), it)
            })
        } else {
            // Desktops: count and main one.
            card.addView(divider())
            card.addView(subheader(I18n.t("desktops")))
            card.addView(stepperRow(I18n.t("pages"), l.pages.toString(), l.pages > 1, l.pages < ScreenLayout.MAX_PAGES,
                onMinus = { removePage(s) },
                onPlus = { if (l.addPage()) LayoutStore.save(this, s) }))
            if (l.pages > 1) {
                card.addView(chipRow(I18n.t("main_page"), (0 until l.pages).map {
                    (if (it == l.homePage) "⌂ " else "") + (it + 1) to it
                }, l.homePage) {
                    l.homePage = it
                    LayoutStore.save(this, s)
                })
                card.addView(hint(I18n.t("main_page_hint") + "\n" + I18n.t("pages_hint")))
            } else {
                card.addView(hint(I18n.t("main_single_hint")))
            }

            // Desktop grid.
            card.addView(divider())
            card.addView(subheader(I18n.t("desktop_grid")))
            card.addView(autoStepper(I18n.t("columns"), l.cols, l.lastCols, 3, 12) { l.cols = it; LayoutStore.save(this, s) })
            card.addView(autoStepper(I18n.t("rows"), l.rows, l.lastRows, 2, 10) { l.rows = it; LayoutStore.save(this, s) })

            // All-apps list (swipe up).
            card.addView(divider())
            card.addView(subheader(I18n.t("drawer_grid")))
            card.addView(autoStepper(I18n.t("columns"), Prefs.drawerCols(this, s), Prefs.drawerLastCols(this, s), 3, 10) {
                Prefs.putInt(this, Prefs.drawerColsKey(s), it)
                build()
            })
            card.addView(sliderRow(I18n.t("drawer_opacity"), Prefs.drawerAlpha(this, s)) {
                Prefs.putInt(this, Prefs.drawerAlphaKey(s), it)
            })
            card.addView(switchRow(I18n.t("search_bar"), Prefs.drawerSearch(this, s)) {
                Prefs.putBool(this, Prefs.drawerSearchKey(s), it)
            })
        }

        // Wallpaper: a still image or the system one.
        val type = Prefs.wallpaperType(this, s)
        val reqImage = if (s == "top") REQ_WP_TOP else REQ_WP_BOTTOM
        val reqAnim = if (s == "top") REQ_ANIM_TOP else REQ_ANIM_BOTTOM
        card.addView(divider())
        card.addView(subheader(I18n.t("wallpaper")))
        card.addView(stillTiles(s, type, reqImage))
        card.addView(LinearLayout(this).apply {
            setPadding(dp(18), dp(4), dp(18), dp(6))
            addView(button(I18n.t("choose_image")) { pickImage(reqImage) })
        })

        // Animated wallpaper: built-in scenes (with previews) or the user's own GIF / WebP.
        card.addView(divider())
        card.addView(subheader(I18n.t("anim_wallpaper")))
        card.addView(powerNote())
        card.addView(animTiles(s, type, reqAnim))
        // Two buttons side by side; scrolls sideways if the screen is narrow.
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(18), dp(4), dp(18), dp(6))
                addView(button(I18n.t("choose_anim")) { pickImage(reqAnim, animated = true) })
                addView(button(I18n.t("choose_video")) { pickVideo(if (s == "top") REQ_VIDEO_TOP else REQ_VIDEO_BOTTOM) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(10) })
            })
        })
        if (type.animated) {
            val extra = if (type == WallpaperType.VIDEO) " " + I18n.t("video_hint") else ""
            card.addView(hint(I18n.t("wp_anim_hint") + extra))
        }

        if (s == "top") {
            card.addView(switchRow(I18n.t("sync_wp"), Prefs.syncSystemWallpaper(this)) { on ->
                Prefs.putBool(this, Prefs.SYNC_SYSTEM_WP, on)
                if (on) {
                    Prefs.putSyncedWallpaperKey(this, "")
                    val app = applicationContext
                    Thread { Wallpapers.syncSystemIfNeeded(app) }.start()
                }
                content.post { build() }
            })
            card.addView(hint(I18n.t("sync_wp_hint")))
        }

        if (s == "bottom") gestures(card)
    }

    /** Amber note: animated wallpapers and videos cost a little CPU and battery. */
    private fun powerNote(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBg(0xFF17110A.toInt(), dp(12).toFloat(), 0x40FFA94D, dp(1))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(TextView(context).apply {
            text = "⚡"
            textSize = 15f
            setTextColor(Look.WARN_TEXT)
            setPadding(0, 0, dp(10), 0)
        })
        addView(TextView(context).apply {
            text = I18n.t("anim_power_hint")
            textSize = 12.5f
            setTextColor(Look.WARN_TEXT)
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(6), dp(16), dp(6))
        }
    }

    // ---------- about ----------

    private fun aboutButton() {
        content.addView(TextView(this).apply {
            text = "ⓘ   " + I18n.t("about")
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Look.TEXT_1)
            background = pressBg(roundedBg(Look.CARD_TOP, dp(18).toFloat(), Look.CARD_STROKE, dp(1)))
            setOnClickListener { showAbout() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(28) })
    }

    private fun showAbout() {
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(8))
        }
        box.addView(android.widget.ImageView(this).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        box.addView(TextView(this).apply {
            text = "PocketHub Launcher"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Look.TEXT_1)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(2))
        })
        box.addView(TextView(this).apply {
            text = I18n.t("version", versionName)
            textSize = 13f
            setTextColor(Look.TEXT_2)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = I18n.t("about_text")
            textSize = 14f
            setTextColor(Look.TEXT_3)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(14), 0, dp(14))
        })
        box.addView(TextView(this).apply {
            text = GITHUB_URL.removePrefix("https://")
            textSize = 13f
            setTextColor(Look.SUB)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        box.addView(button(I18n.t("open_github")) { openGithub() })

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(box)
            .setPositiveButton(I18n.t("close"), null)
            .create()
        dialog.window?.setBackgroundDrawable(roundedBg(Look.CARD_TOP, dp(24).toFloat(), Look.CARD_STROKE, dp(1)))
        dialog.show()
    }

    private fun openGithub() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast(I18n.t("cant_open"))
        }
    }

    /** Still previews of the built-in scenes (rendered once per settings screen). */
    private val previews = HashMap<String, android.graphics.Bitmap>()

    /** Still wallpapers: built-in retro pictures, the user's own image and the system wallpaper. */
    private fun stillTiles(s: String, current: WallpaperType, reqImage: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        val tw = dp(144)
        val th = dp(81)
        val stills = listOf(
            WallpaperType.PIXEL_DUSK to "wp_pixel_dusk",
            WallpaperType.GAMEPADS to "wp_gamepads",
            WallpaperType.PIXEL_CASTLE to "wp_pixel_castle",
            WallpaperType.WIRE_HORIZON to "wp_wire_horizon",
            WallpaperType.MEMPHIS to "wp_memphis",
        )
        for ((t, key) in stills) {
            val bmp = previews.getOrPut(t.name) { StillRenderer.render(this, t, tw, th) }
            row.addView(tile(I18n.t(key), bmp, null, current == t) { setWallpaperType(s, t) })
        }
        // Own image: a thumbnail of it, or a "+" until one is chosen.
        val own = if (Wallpapers.has(this, s)) {
            val k = "image_" + s + "_" + Wallpapers.imageMtime(this, s)
            previews[k] ?: Wallpapers.loadThumb(this, s, tw)?.also { previews[k] = it }
        } else null
        row.addView(tile(I18n.t("wp_image"), own, if (own == null) "+" else null, current == WallpaperType.IMAGE) {
            if (Wallpapers.has(this, s)) setWallpaperType(s, WallpaperType.IMAGE) else pickImage(reqImage)
        })
        row.addView(tile(I18n.t("system"), null, "◐", current == WallpaperType.SYSTEM) {
            setWallpaperType(s, WallpaperType.SYSTEM)
        })
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    private fun animTiles(s: String, current: WallpaperType, reqAnim: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        val tw = dp(144)
        val th = dp(81)
        val scenes = listOf(
            WallpaperType.SYNTHWAVE to "wp_synthwave",
            WallpaperType.WAVES to "wp_waves",
            WallpaperType.ARCADE to "wp_arcade",
            WallpaperType.NIGHT_CITY to "wp_night_city",
            WallpaperType.AURORA to "wp_aurora",
            WallpaperType.PLEXUS to "wp_plexus",
            WallpaperType.BOKEH to "wp_bokeh",
        )
        for ((t, key) in scenes) {
            val bmp = previews.getOrPut(t.name) { SceneRenderer.renderStill(this, t, tw, th) }
            row.addView(tile(I18n.t(key), bmp, null, current == t) { setWallpaperType(s, t) })
        }
        // Own GIF / WebP: its first frame, or a "+" until one is chosen.
        val own = if (Wallpapers.hasAnimated(this, s)) {
            val k = "own_" + s + "_" + Wallpapers.animatedMtime(this, s)
            previews[k] ?: Wallpapers.animatedFirstFrame(this, s, tw)?.also { previews[k] = it }
        } else null
        row.addView(tile(I18n.t("wp_custom_anim"), own, if (own == null) "+" else null, current == WallpaperType.CUSTOM_ANIM) {
            if (Wallpapers.hasAnimated(this, s)) setWallpaperType(s, WallpaperType.CUSTOM_ANIM)
            else pickImage(reqAnim, animated = true)
        })
        // Own video (MP4): a frame from it, or a "▶" until one is chosen.
        val reqVideo = if (s == "top") REQ_VIDEO_TOP else REQ_VIDEO_BOTTOM
        val vid = if (Wallpapers.hasVideo(this, s)) {
            val k = "video_" + s + "_" + Wallpapers.videoMtime(this, s)
            previews[k] ?: Wallpapers.videoFrame(this, s, tw)?.also { previews[k] = it }
        } else null
        row.addView(tile(I18n.t("wp_custom_video"), vid, if (vid == null) "▶" else null, current == WallpaperType.VIDEO) {
            if (Wallpapers.hasVideo(this, s)) setWallpaperType(s, WallpaperType.VIDEO)
            else pickVideo(reqVideo)
        })
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    /** Preview tile: picture with a gradient frame when selected, caption below. */
    private fun tile(caption: String, bmp: android.graphics.Bitmap?, glyph: String?, selected: Boolean, onClick: () -> Unit): View {
        val radius = dp(14).toFloat()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = if (selected) gradientBg(radius + dp(2)) else roundedBg(0, radius + dp(2), Look.TRACK_STROKE, dp(1))
        }
        val pic = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = roundedBg(Look.TRACK, radius)
            clipToOutline = true
            if (bmp != null) setImageBitmap(bmp)
        }
        frame.addView(pic, android.widget.FrameLayout.LayoutParams(dp(144), dp(81)))
        if (glyph != null) {
            frame.addView(TextView(this).apply {
                text = glyph
                gravity = Gravity.CENTER
                textSize = 28f
                setTextColor(Look.TEXT_3)
            }, android.widget.FrameLayout.LayoutParams(dp(144), dp(81)))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rippleBg(roundedBg(Color.WHITE, radius))
            setOnClickListener { onClick() }
            addView(frame)
            addView(TextView(context).apply {
                text = caption
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else Look.TEXT_3)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun setWallpaperType(s: String, t: WallpaperType) {
        Prefs.putWallpaperType(this, s, t)
        if (s == "top") {
            val app = applicationContext
            Thread { Wallpapers.syncSystemIfNeeded(app) }.start()
        }
        build()
    }

    private fun pickVideo(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4")
        try {
            startActivityForResult(i, req)
        } catch (_: Exception) {
            toast(I18n.t("no_picker"))
        }
    }

    private fun pickImage(req: Int, animated: Boolean = false) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        if (animated) i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/gif", "image/webp"))
        try {
            startActivityForResult(i, req)
        } catch (_: Exception) {
            toast(I18n.t("no_picker"))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val app = applicationContext
        val side = 2560
        when (requestCode) {
            REQ_WP_LOCK -> Thread {
                val ok = Wallpapers.setLock(app, uri, side)
                runOnUiThread { toast(I18n.t(if (ok) "lock_set" else "wallpaper_failed")) }
            }.start()
            REQ_VIDEO_TOP, REQ_VIDEO_BOTTOM -> {
                val s = if (requestCode == REQ_VIDEO_TOP) "top" else "bottom"
                Thread {
                    val ok = Wallpapers.saveVideo(app, s, uri)
                    runOnUiThread {
                        if (ok) {
                            Prefs.putWallpaperType(this, s, WallpaperType.VIDEO)
                            Wallpapers.notifyChanged(s)
                            if (s == "top") Thread { Wallpapers.syncSystemIfNeeded(app) }.start()
                            build()
                        } else toast(I18n.t("wallpaper_failed"))
                    }
                }.start()
            }
            REQ_ANIM_TOP, REQ_ANIM_BOTTOM -> {
                val s = if (requestCode == REQ_ANIM_TOP) "top" else "bottom"
                Thread {
                    val ok = Wallpapers.saveAnimated(app, s, uri)
                    runOnUiThread {
                        if (ok) {
                            Prefs.putWallpaperType(this, s, WallpaperType.CUSTOM_ANIM)
                            Wallpapers.notifyChanged(s)
                            if (s == "top") Thread { Wallpapers.syncSystemIfNeeded(app) }.start()
                            build()
                        } else toast(I18n.t("wallpaper_failed"))
                    }
                }.start()
            }
            REQ_WP_TOP, REQ_WP_BOTTOM -> {
                val s = if (requestCode == REQ_WP_TOP) "top" else "bottom"
                Thread {
                    val ok = Wallpapers.save(app, s, uri, side)
                    runOnUiThread {
                        if (ok) {
                            Prefs.putWallpaperType(this, s, WallpaperType.IMAGE)
                            Wallpapers.notifyChanged(s)
                            if (s == "top") Thread { Wallpapers.syncSystemIfNeeded(app) }.start()
                            build()
                        } else toast(I18n.t("wallpaper_failed"))
                    }
                }.start()
            }
        }
    }

    private fun gestures(card: LinearLayout) {
        card.addView(divider())
        card.addView(subheader(I18n.t("gestures")))

        card.addView(switchRow(I18n.t("handle_enable"), Prefs.handleEnabled(this)) {
            Prefs.putBool(this, Prefs.HANDLE, it); gesturesChanged()
        })
        if (Prefs.handleEnabled(this)) {
            card.addView(chipRow(I18n.t("handle_width"), listOf("15%" to 15, "20%" to 20, "25%" to 25, "33%" to 33, "50%" to 50), Prefs.handleWidthPct(this)) {
                Prefs.putInt(this, Prefs.HANDLE_W_PCT, it); gesturesChanged()
            })
            card.addView(chipRow(I18n.t("handle_height"), listOf("16 dp" to 16, "24 dp" to 24, "32 dp" to 32, "48 dp" to 48), Prefs.handleHeightDp(this)) {
                Prefs.putInt(this, Prefs.HANDLE_H, it); gesturesChanged()
            })
            card.addView(chipRow(I18n.t("hold_time"), listOf("250 ms" to 250, "400 ms" to 400, "600 ms" to 600, "800 ms" to 800), Prefs.holdMs(this)) {
                Prefs.putInt(this, Prefs.HOLD_MS, it); gesturesChanged()
            })
        }
        card.addView(switchRow(I18n.t("swipe_enable"), Prefs.swipeEnabled(this)) {
            Prefs.putBool(this, Prefs.SWIPE, it); gesturesChanged()
        })
        if (Prefs.swipeEnabled(this)) {
            card.addView(chipRow(I18n.t("edge_height"), listOf("8 dp" to 8, "12 dp" to 12, "16 dp" to 16, "24 dp" to 24), Prefs.edgeHeightDp(this)) {
                Prefs.putInt(this, Prefs.EDGE_H, it); gesturesChanged()
            })
            card.addView(switchRow(I18n.t("center_only"), Prefs.centerOnly(this)) {
                Prefs.putBool(this, Prefs.CENTER_ONLY, it); gesturesChanged()
            })
            card.addView(switchRow(I18n.t("swipe_hold_menu"), Prefs.swipeHoldMenu(this)) {
                Prefs.putBool(this, Prefs.SWIPE_HOLD_MENU, it); gesturesChanged()
            })
        }
        card.addView(hint(I18n.t("gestures_hint")))
    }

    private fun generalSection() {
        val card = section(I18n.t("general"), "⚙")

        // Language — dropdown.
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(14), dp(10))
        }
        langRow.addView(label(I18n.t("language")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        langRow.addView(languageSpinner())
        card.addView(langRow)

        // Icon shape.
        card.addView(chipRow(I18n.t("icon_shape"), listOf(
            "●  " + I18n.t("shape_round") to 0,
            "■  " + I18n.t("shape_square") to 1,
        ), if (RoundIcon.shape == IconShape.SQUARE) 1 else 0) {
            Apps.setIconShape(this, if (it == 1) IconShape.SQUARE else IconShape.ROUND)
            build()
        })

        // Lock layout.
        card.addView(switchRow(I18n.t("lock_layout"), Prefs.layoutLocked(this)) {
            Prefs.putBool(this, Prefs.LAYOUT_LOCKED, it)
            content.post { build() }
        })
        card.addView(hint(I18n.t("lock_layout_hint")))

        // Controller navigation.
        card.addView(switchRow(I18n.t("controller"), Prefs.controllerEnabled(this)) {
            Prefs.putBool(this, Prefs.CONTROLLER, it)
            content.post { build() }
        })
        if (Prefs.controllerEnabled(this)) card.addView(hint(I18n.t("controller_hint")))

        // Lock screen wallpaper.
        card.addView(divider())
        card.addView(subheader(I18n.t("lock_wallpaper")))
        card.addView(LinearLayout(this).apply {
            setPadding(dp(18), dp(6), dp(18), dp(10))
            addView(button(I18n.t("choose_image")) { pickImage(REQ_WP_LOCK) })
        })

        // Stock AYANEO launcher (enable/disable via root).
        val ayaneo = ayaneoHomeEnabled()
        if (ayaneo != null) {
            card.addView(divider())
            card.addView(switchRow(I18n.t("ayaneo_home"), ayaneo) { on -> setAyaneoHome(on) })
            card.addView(hint(I18n.t("ayaneo_home_hint")))
        }

        // Setup assistant again.
        card.addView(divider())
        card.addView(LinearLayout(this).apply {
            setPadding(dp(18), dp(8), dp(18), dp(6))
            addView(button(I18n.t("setup_again")) {
                startActivity(Intent(this@SettingsActivity, SetupActivity::class.java))
            })
        })

        // System default home app.
        card.addView(divider())
        val isHome = isDefaultHome()
        card.addView(TextView(this).apply {
            text = (if (isHome) "✓  " else "⚠  ") + I18n.t(if (isHome) "home_is_default" else "home_not_default")
            setTextColor(if (isHome) Look.SUB else Look.WARN_TEXT)
            textSize = 15f
            setPadding(dp(18), dp(8), dp(18), dp(12))
        })
        card.addView(LinearLayout(this).apply {
            setPadding(dp(18), 0, dp(18), dp(14))
            addView(button(I18n.t("choose_home")) {
                try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) { toast(I18n.t("cant_open")) }
            })
        })
    }

    // ---------- controller ----------

    /** A → select, B → back (in case the firmware doesn't do it itself). */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (ModernDialog.closeTop(this)) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean = when (e.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> super.dispatchKeyEvent(remapKey(e, KeyEvent.KEYCODE_DPAD_CENTER))
        KeyEvent.KEYCODE_BUTTON_B -> super.dispatchKeyEvent(remapKey(e, KeyEvent.KEYCODE_BACK))
        else -> super.dispatchKeyEvent(e)
    }

    /** Everything clickable must be reachable with the D-pad. */
    private fun makeFocusable(v: View) {
        if (v.hasOnClickListeners()) v.isFocusable = true
        if (v is ViewGroup) for (i in 0 until v.childCount) makeFocusable(v.getChildAt(i))
    }

    // ---------- actions ----------

    /** Uninstall button at the end of General: brings AYANEO Home back before removing the launcher. */
    private fun uninstallCard() {
        content.addView(TextView(this).apply {
            text = I18n.t("uninstall_launcher")
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFF8787.toInt())
            background = pressBg(roundedBg(0xFF1A0D0F.toInt(), dp(18).toFloat(), 0x59FF6B6B, dp(1)))
            setOnClickListener { confirmUninstall() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
    }

    private fun confirmUninstall() {
        ModernDialog.show(this, I18n.t("uninstall_launcher"), message = I18n.t("uninstall_launcher_msg"),
            positive = I18n.t("uninstall"), danger = true) { uninstallLauncher(); true }
    }

    /**
     * 1) turn AYANEO Home back on (root), 2) make it the home app (or Launcher3 if it isn't there),
     * 3) stop the gesture overlays, 4) open the system uninstall dialog for this app.
     */
    private fun uninstallLauncher() {
        AppLauncher.background {
            var rootOk = true
            if (ayaneoHomeEnabled() != null) rootOk = Root.exec("pm enable $AYANEO_HOME") != null
            val home = homeActivityOf(AYANEO_HOME) ?: LAUNCHER3_HOME
            if (rootOk) Root.exec("cmd package set-home-activity $home")
            runOnUiThread {
                if (!rootOk) toast(I18n.t("root_needed"))
                try { stopService(Intent(this, EdgeGestureService::class.java)) } catch (_: Exception) {}
                try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null)))
                } catch (_: Exception) {
                    toast(I18n.t("cant_open"))
                }
            }
        }
    }

    /** "package/activity" of a package's home screen activity, if it has one. */
    private fun homeActivityOf(pkg: String): String? = try {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg), 0
        ).firstOrNull()?.activityInfo?.let { it.packageName + "/" + it.name }
    } catch (_: Exception) {
        null
    }

    private fun gesturesChanged() {
        EdgeGestureService.refresh(this)
        content.post { build() }
    }

    private fun requestOverlay() {
        AppLauncher.background {
            Root.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            runOnUiThread {
                if (Settings.canDrawOverlays(this)) {
                    gesturesChanged()
                } else {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        toast(I18n.t("cant_open"))
                    }
                }
            }
        }
    }

    /** null — AYANEO Home is not installed. */
    private fun ayaneoHomeEnabled(): Boolean? = try {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(AYANEO_HOME, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
    } catch (_: Exception) {
        null
    }

    private fun setAyaneoHome(on: Boolean) {
        AppLauncher.background {
            val out = Root.exec(if (on) "pm enable $AYANEO_HOME" else "pm disable-user --user 0 $AYANEO_HOME")
            runOnUiThread {
                when {
                    out == null -> toast(I18n.t("root_needed"))
                    ayaneoHomeEnabled() != on -> toast(I18n.t("change_failed"))
                }
                build()
            }
        }
    }

    private fun isDefaultHome(): Boolean {
        val ri = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun removePage(s: String) {
        val l = LayoutStore.get(this, s)
        if (l.pages <= 1) return
        val last = l.pages - 1
        val doRemove = {
            l.removeLastPage()
            LayoutStore.save(this, s)
        }
        if (l.pageItemCount(last) == 0) {
            doRemove()
            return
        }
        ModernDialog.show(this, I18n.t("remove_page_title", last + 1), message = I18n.t("remove_page_msg"),
            positive = I18n.t("remove"), danger = true) { doRemove(); true }
    }

    // ---------- widgets ----------

    private fun section(title: String, glyph: String): LinearLayout {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(28), dp(6), dp(12))
        }
        head.addView(TextView(this).apply {
            text = glyph
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            background = gradientBg(dp(10).toFloat())
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        head.addView(TextView(this).apply {
            text = title
            setTextColor(Look.TEXT_1)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        })
        content.addView(head)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Look.CARD_TOP, Look.CARD_BOTTOM),
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Look.CARD_STROKE)
            }
            setPadding(0, dp(8), 0, dp(10))
            clipToOutline = true
            elevation = dp(2).toFloat()
        }
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun subheader(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(Look.SUB)
        textSize = 11f
        letterSpacing = 0.14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(18), dp(14), dp(18), dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Look.TEXT_2)
        textSize = 12.5f
        setLineSpacing(0f, 1.18f)
        setPadding(dp(18), dp(4), dp(18), dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Look.TEXT_1)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x0FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(18), dp(12), dp(18), dp(4))
        }
    }

    private fun track() = roundedBg(Look.TRACK, dp(14).toFloat(), Look.TRACK_STROKE, dp(1))

    /** Gradient button with white text. */
    private fun button(text: String, a: Int = Look.GRAD_A, b: Int = Look.GRAD_B, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x40000000)
        setPadding(dp(22), 0, dp(22), 0)
        background = pressBg(gradientBg(dp(14).toFloat(), a, b))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
    }

    private fun stepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = symbol
        gravity = Gravity.CENTER
        textSize = 19f
        setTextColor(if (enabled) Color.WHITE else 0x40FFFFFF)
        background = rippleBg(roundedBg(Color.WHITE, dp(11).toFloat()))
        isEnabled = enabled
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(38))
    }

    /** Row "label … [ − value + ]" on a dark track. */
    private fun stepperRow(
        title: String, value: String, minusOn: Boolean, plusOn: Boolean,
        onMinus: () -> Unit, onPlus: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(6), dp(14), dp(6))
        addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = track()
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(stepButton("−", minusOn, onMinus))
            addView(TextView(context).apply {
                text = value
                setTextColor(Color.WHITE)
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = dp(70)
                setPadding(dp(4), 0, dp(4), 0)
            })
            addView(stepButton("+", plusOn, onPlus))
        })
    }

    /**
     * Stepper with an Auto mode (value 0).
     * From Auto, ± start from the current automatic value (autoHint);
     * "−" at the minimum returns to Auto.
     */
    private fun autoStepper(title: String, value: Int, autoHint: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val shown = if (value == 0) {
            if (autoHint > 0) I18n.t("auto") + " · " + autoHint else I18n.t("auto")
        } else value.toString()
        val base = if (autoHint > 0) autoHint.coerceIn(min, max) else min
        return stepperRow(title, shown, value != 0 || base > min, value < max,
            onMinus = { onChange(if (value == 0) (base - 1).coerceAtLeast(min) else if (value <= min) 0 else value - 1) },
            onPlus = { onChange(if (value == 0) (base + 1).coerceAtMost(max) else value + 1) })
    }

    /** Label with a live percent value and a teal slider (0–100); saves when released. */
    private fun sliderRow(title: String, value: Int, onSet: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(14), dp(6))
        }
        val valueText = TextView(this).apply {
            text = "$value%"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Look.SUB)
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueText)
        })
        box.addView(android.widget.SeekBar(this).apply {
            max = 100
            progress = value
            progressTintList = android.content.res.ColorStateList.valueOf(Look.GRAD_A)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
            setPadding(dp(6), dp(10), dp(6), dp(6))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    valueText.text = "$p%"
                    if (!fromUser) onSet(p) // controller / keyboard steps
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) = onSet(sb.progress)
            })
        })
        return box
    }

    private fun switchRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
            accentSwitchTints(this)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(14), dp(12))
            background = rippleBg()
            setOnClickListener {
                sw.isChecked = !sw.isChecked
                onToggle(sw.isChecked)
            }
            addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
            addView(sw)
        }
    }

    /** Label and a segmented control on a dark track; the selected segment has a gradient. */
    private fun chipRow(title: String, options: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (title.isNotEmpty()) box.addView(label(title).apply { setPadding(dp(18), dp(10), dp(18), dp(6)) })
        val segments = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = track()
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        for ((text, value) in options) {
            val on = value == selected
            segments.addView(TextView(this).apply {
                this.text = text
                gravity = Gravity.CENTER
                textSize = 13.5f
                setTextColor(if (on) Color.WHITE else Look.TEXT_3)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                minWidth = dp(46)
                setPadding(dp(14), 0, dp(14), 0)
                background = if (on) pressBg(gradientBg(dp(11).toFloat()))
                else rippleBg(roundedBg(Color.WHITE, dp(11).toFloat()))
                setOnClickListener { if (!on) onSelect(value) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        }
        val holder = LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
            addView(segments)
        }
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(holder)
        })
        return box
    }

    private fun languageSpinner(): Spinner {
        val names = I18n.LANGS.map { it.second }
        val spinner = Spinner(this, Spinner.MODE_DROPDOWN)
        spinner.adapter = object : ArrayAdapter<String>(this, 0, names) {
            private fun cell(pos: Int, dropDown: Boolean) = TextView(context).apply {
                val name = getItem(pos) ?: ""
                text = if (dropDown) name else "$name   ▾"
                textSize = 15f
                val selected = I18n.LANGS[pos].first == I18n.lang
                setTextColor(if (dropDown && selected) Look.SUB else Look.TEXT_1)
                typeface = if (dropDown && selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(18), dp(11), dp(18), dp(11))
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = cell(position, false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = cell(position, true)
        }
        spinner.background = pressBg(track())
        spinner.setPopupBackgroundDrawable(roundedBg(0xFF0E141A.toInt(), dp(16).toFloat(), Look.CARD_STROKE, dp(1)))
        spinner.setSelection(I18n.LANGS.indexOfFirst { it.first == I18n.lang }.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val code = I18n.LANGS[position].first
                if (code != I18n.lang) {
                    I18n.setLang(this@SettingsActivity, code)
                    content.post { build() }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return spinner
    }
}
