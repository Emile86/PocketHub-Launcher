package com.pockethub.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * First-run setup assistant:
 *  1. welcome + language,
 *  2. what the launcher does, turning AYANEO Home off, how to uninstall safely,
 *  3. bottom screen gestures (with animated drawings of the device),
 *  4. done — everything else is in Settings — and the default home app button (choosing it
 *     sends the user to the home screen, so it finishes the assistant).
 * Root status and the AYANEO Home state are re-checked whenever the user comes back.
 */
class SetupActivity : Activity() {

    private lateinit var dots: LinearLayout
    private lateinit var skip: TextView
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var backBtn: TextView
    private lateinit var nextBtn: TextView
    private var step = 0

    /** Root state for the intro page: null = not known yet / checking. */
    @Volatile private var rootState: Boolean? = null
    private var rootChecking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(applicationContext)
        window.setDecorFitsSystemWindows(false)
        step = (savedInstanceState?.getInt("step") ?: Prefs.setupStep(this)).coerceIn(0, LAST)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AuroraDrawable()
        }
        // top bar: step dots + skip
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(16), dp(6))
        }
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(dots, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        skip = TextView(this).apply {
            textSize = 14f
            setTextColor(Look.TEXT_2)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rippleBg(roundedBg(Color.WHITE, dp(14).toFloat()))
            setOnClickListener { finishSetup() }
        }
        top.addView(skip)
        root.addView(top)

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(8), dp(28), dp(16))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom bar: back + next
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(16))
        }
        backBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Look.TEXT_1)
            setPadding(dp(22), 0, dp(22), 0)
            background = pressBg(roundedBg(Look.CARD_TOP, dp(14).toFloat(), Look.CARD_STROKE, dp(1)))
            setOnClickListener { go(step - 1) }
        }
        bottom.addView(backBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nextBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(30), 0, dp(30), 0)
            background = pressBg(gradientBg(dp(14).toFloat()))
            setOnClickListener { if (step >= LAST) finishSetup() else go(step + 1) }
        }
        bottom.addView(nextBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(bottom)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Back from Magisk or system settings: re-check root (it may have just been allowed).
        if (step == 1 && rootState != true && !rootChecking) rootState = null
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (step > 0) go(step - 1)
    }

    /** A → select, B → back (in case the firmware doesn't do it itself). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean = when (e.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> super.dispatchKeyEvent(remapKey(e, KeyEvent.KEYCODE_DPAD_CENTER))
        KeyEvent.KEYCODE_BUTTON_B -> super.dispatchKeyEvent(remapKey(e, KeyEvent.KEYCODE_BACK))
        else -> super.dispatchKeyEvent(e)
    }

    private fun go(s: Int) {
        step = s.coerceIn(0, LAST)
        Prefs.putSetupStep(this, step)
        render()
        scroll.scrollTo(0, 0)
    }

    private fun finishSetup() {
        Prefs.putBool(this, Prefs.SETUP_DONE, true)
        Prefs.putSetupStep(this, 0)
        EdgeGestureService.start(this)
        finish()
    }

    // ---------- pages ----------

    private fun render() {
        dots.removeAllViews()
        for (i in 0..LAST) {
            dots.addView(View(this).apply {
                background = if (i == step) gradientBg(dp(4).toFloat()) else roundedBg(0x33FFFFFF, dp(4).toFloat())
            }, LinearLayout.LayoutParams(if (i == step) dp(22) else dp(8), dp(8)).apply { rightMargin = dp(6) })
        }
        skip.text = I18n.t("skip")
        skip.visibility = if (step == LAST) View.INVISIBLE else View.VISIBLE
        backBtn.text = I18n.t("back")
        backBtn.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        nextBtn.text = I18n.t(if (step == LAST) "start" else "next")

        body.removeAllViews()
        when (step) {
            0 -> pageLanguage()
            1 -> pageIntro()
            2 -> pageGestures()
            else -> pageDone()
        }
        body.post { makeFocusable(body) }
    }

    private fun pageLanguage() {
        heading(I18n.t("setup_welcome"))
        para(I18n.t("setup_lang_title"))
        val grid = GridLayout(this).apply { columnCount = 4 }
        for ((code, name) in I18n.LANGS) {
            val on = code == I18n.lang
            grid.addView(TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(if (on) Color.WHITE else Look.TEXT_3)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (on) pressBg(gradientBg(dp(14).toFloat()))
                else pressBg(roundedBg(Look.CARD_TOP, dp(14).toFloat(), Look.CARD_STROKE, dp(1)))
                setOnClickListener {
                    I18n.setLang(this@SetupActivity, code)
                    render()
                }
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(52)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
    }

    private fun pageIntro() {
        heading(I18n.t("setup_intro_title"))
        para(I18n.t("setup_intro_text"))
        rootCard()
        ayaneoHomeEnabled()?.let { enabled ->
            body.addView(card().apply {
                addView(switchRow(I18n.t("ayaneo_home"), enabled) { on -> setAyaneoHome(on) })
                addView(TextView(context).apply {
                    text = (if (enabled) "● " + I18n.t("ayaneo_state_on") else "○ " + I18n.t("ayaneo_state_off")) +
                        "\n" + I18n.t("setup_ayaneo_recommend")
                    textSize = 13f
                    setTextColor(if (enabled) Look.WARN_TEXT else Look.SUB)
                    setLineSpacing(0f, 1.2f)
                    setPadding(dp(18), 0, dp(18), dp(12))
                })
            })
        }
        // warning: how to uninstall without losing the stock launcher
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xFF17110A.toInt(), dp(18).toFloat(), 0x59FFA94D, dp(1))
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(TextView(context).apply {
                text = "⚠  " + I18n.t("setup_uninstall_warn_title")
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Look.WARN_TEXT)
            })
            addView(TextView(context).apply {
                text = I18n.t("setup_uninstall_warn")
                textSize = 13.5f
                setTextColor(Look.TEXT_3)
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, 0)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
    }

    /** Root status (asks Magisk automatically on the first visit) with a retry button. */
    private fun rootCard() {
        if (rootState == null && !rootChecking) checkRoot()
        val card = card()
        val (icon, label, color) = when {
            rootChecking || rootState == null -> Triple("…", I18n.t("root_checking"), Look.TEXT_2)
            rootState == true -> Triple("✓", I18n.t("root_granted"), Look.SUB)
            else -> Triple("✕", I18n.t("root_denied"), Look.WARN_TEXT)
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(TextView(context).apply {
                text = icon
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color)
                background = roundedBg((color and 0x00FFFFFF) or 0x22000000, dp(14).toFloat())
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(context).apply {
                text = label
                textSize = 15.5f
                setTextColor(color)
                setPadding(dp(12), 0, 0, 0)
            })
        })
        if (rootState == false && !rootChecking) {
            card.addView(TextView(this).apply {
                text = I18n.t("root_denied_hint")
                textSize = 13f
                setTextColor(Look.TEXT_2)
                setLineSpacing(0f, 1.15f)
                setPadding(dp(18), 0, dp(18), dp(10))
            })
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(18), 0, dp(18), dp(12))
                addView(button(I18n.t("root_request")) { checkRoot() })
                packageManager.getLaunchIntentForPackage(MAGISK)?.let { launch ->
                    addView(TextView(context).apply {
                        text = I18n.t("open_magisk")
                        gravity = Gravity.CENTER
                        textSize = 15f
                        setTextColor(Look.TEXT_1)
                        setPadding(dp(20), 0, dp(20), 0)
                        background = pressBg(roundedBg(Look.TRACK, dp(14).toFloat(), Look.TRACK_STROKE, dp(1)))
                        setOnClickListener { openMagisk(launch) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { leftMargin = dp(10) })
                }
            })
        }
        body.addView(card)
    }

    /**
     * Open Magisk in this task (no NEW_TASK), so Back returns to the assistant, and keep checking
     * in the background: as soon as root is allowed the status turns green.
     */
    private fun openMagisk(launch: Intent) {
        try {
            startActivity(Intent(launch).setFlags(0))
        } catch (_: Exception) {
            try { startActivity(launch) } catch (_: Exception) {}
        }
        watchRoot()
    }

    @Volatile private var watching = false

    private fun watchRoot() {
        if (watching) return
        watching = true
        // Own thread: the shared root/launch thread must not be blocked by this waiting loop.
        Thread {
            var ok = false
            for (i in 0 until 30) {
                Thread.sleep(3000)
                if (isFinishing || isDestroyed || rootState == true) break
                ok = Root.check()
                if (ok) break
            }
            runOnUiThread {
                watching = false
                if (ok) {
                    rootState = true
                    rootChecking = false
                    if (step == 1) render()
                }
            }
        }.start()
    }

    private fun checkRoot() {
        rootChecking = true
        AppLauncher.background {
            val ok = Root.check()
            runOnUiThread {
                rootChecking = false
                rootState = ok
                if (step == 1) render()
            }
        }
    }

    private fun pageGestures() {
        heading(I18n.t("gestures"))
        para(I18n.t("setup_gestures_text"))
        val wide = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val row = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        row.addView(gestureCard(DeviceIllustrationView.Mode.HANDLE, "gesture_handle_name", "gesture_handle_desc",
            Prefs.handleEnabled(this)) { Prefs.putBool(this, Prefs.HANDLE, it); gesturesChanged() },
            gestureLp(wide, first = true))
        row.addView(gestureCard(DeviceIllustrationView.Mode.SWIPE, "gesture_swipe_name", "gesture_swipe_desc",
            Prefs.swipeEnabled(this)) { Prefs.putBool(this, Prefs.SWIPE, it); gesturesChanged() },
            gestureLp(wide, first = false))
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
    }

    private fun gestureLp(wide: Boolean, first: Boolean) =
        if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (!first) leftMargin = dp(14) }
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (!first) topMargin = dp(14) }

    private fun gestureCard(mode: DeviceIllustrationView.Mode, nameKey: String, descKey: String,
                            on: Boolean, onToggle: (Boolean) -> Unit): View = card().apply {
        addView(DeviceIllustrationView(context, mode), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })
        addView(switchRow(I18n.t(nameKey), on, onToggle))
        addView(TextView(context).apply {
            text = I18n.t(descKey)
            textSize = 13f
            setTextColor(Look.TEXT_2)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(18), 0, dp(18), dp(12))
        })
    }

    private fun pageDone() {
        heading(I18n.t("setup_done_title"))
        para(I18n.t("setup_done_text"))
        // Default home app. Picking it returns to the home screen, so this also finishes the assistant.
        val isHome = isDefaultHome()
        body.addView(card().apply {
            addView(TextView(context).apply {
                text = I18n.t("setup_home_title")
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Look.TEXT_1)
                setPadding(dp(18), dp(12), dp(18), dp(4))
            })
            addView(TextView(context).apply {
                text = I18n.t("setup_home_text")
                textSize = 13.5f
                setTextColor(Look.TEXT_2)
                setLineSpacing(0f, 1.15f)
                setPadding(dp(18), 0, dp(18), dp(8))
            })
            addView(TextView(context).apply {
                text = (if (isHome) "✓  " else "⚠  ") + I18n.t(if (isHome) "home_is_default" else "home_not_default")
                textSize = 14.5f
                setTextColor(if (isHome) Look.SUB else Look.WARN_TEXT)
                setPadding(dp(18), 0, dp(18), dp(10))
            })
            if (!isHome) addView(LinearLayout(context).apply {
                setPadding(dp(18), 0, dp(18), dp(14))
                addView(button(I18n.t("choose_home")) { chooseHomeAndFinish() })
            })
        })
    }

    private fun chooseHomeAndFinish() {
        Prefs.putBool(this, Prefs.SETUP_DONE, true)
        Prefs.putSetupStep(this, 0)
        EdgeGestureService.start(this)
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) { toast(I18n.t("cant_open")) }
        finish()
    }

    // ---------- actions ----------

    private fun gesturesChanged() {
        EdgeGestureService.start(this)
        render()
    }

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
                if (out == null) {
                    rootState = false
                    toast(I18n.t("root_needed"))
                } else {
                    rootState = true
                }
                render()
            }
        }
    }

    private fun isDefaultHome(): Boolean {
        val ri = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun makeFocusable(v: View) {
        if (v.hasOnClickListeners()) v.isFocusable = true
        if (v is ViewGroup) for (i in 0 until v.childCount) makeFocusable(v.getChildAt(i))
    }

    // ---------- widgets ----------

    private fun heading(s: String) = body.addView(TextView(this).apply {
        text = s
        textSize = 30f
        letterSpacing = -0.01f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Look.TEXT_1)
        setPadding(0, dp(8), 0, dp(8))
    })

    private fun para(s: String) = body.addView(TextView(this).apply {
        text = s
        textSize = 15f
        setTextColor(Look.TEXT_3)
        setLineSpacing(0f, 1.2f)
    })

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(Look.CARD_TOP, dp(22).toFloat(), Look.CARD_STROKE, dp(1))
        setPadding(0, dp(6), 0, dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        }
    }

    private fun button(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(dp(24), 0, dp(24), 0)
        background = pressBg(gradientBg(dp(14).toFloat()))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
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
            addView(TextView(context).apply {
                text = title
                textSize = 15.5f
                setTextColor(Look.TEXT_1)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
            addView(sw)
        }
    }

    companion object {
        private const val LAST = 3
        private const val AYANEO_HOME = "com.ayaneo.home"
        private const val MAGISK = "com.topjohnwu.magisk"
    }
}
