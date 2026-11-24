package com.pockethub.launcher

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Bottom screen gestures — invisible overlay windows on display 2:
 *  - top-center handle: hold → three dots → tap → menu;
 *  - bottom edge strip: swipe up → minimize; swipe up and hold → menu.
 * Menu: close app, send to top screen, minimize.
 * While our home screen is shown on the bottom screen, the windows let touches through.
 */
class EdgeGestureService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var wctx: Context? = null
    private var wm: WindowManager? = null
    private var displayId = -1

    private var handle: HandleView? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var swipe: SwipeZoneView? = null
    private var swipeParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var handleH = 0
    private var handleW = 0
    private var screenW = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        I18n.init(applicationContext)
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        rebuild()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild()
    }

    override fun onDestroy() {
        removeAll()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------- windows ----------

    private fun rebuild() {
        removeAll()
        val handleOn = Prefs.handleEnabled(this)
        val swipeOn = Prefs.swipeEnabled(this)
        val bottom = Displays.bottomId(this)
        if ((!handleOn && !swipeOn) || bottom == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission")
            return
        }
        val display = getSystemService(DisplayManager::class.java).getDisplay(bottom) ?: return
        val c = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val w = c.getSystemService(WindowManager::class.java)
        wctx = c
        wm = w
        displayId = bottom

        try {
            if (handleOn) {
                val v = HandleView(c, this, Prefs.holdMs(this).toLong())
                screenW = c.resources.displayMetrics.widthPixels
                handleW = screenW * Prefs.handleWidthPct(this) / 100
                handleH = c.dp(Prefs.handleHeightDp(this))
                val p = params(handleW, handleH, Gravity.TOP or Gravity.CENTER_HORIZONTAL, "PHL handle")
                w.addView(v, p)
                handle = v; handleParams = p
            }
            if (swipeOn) {
                val width = if (Prefs.centerOnly(this)) c.resources.displayMetrics.widthPixels / 2
                else WindowManager.LayoutParams.MATCH_PARENT
                val v = SwipeZoneView(c, this, Prefs.swipeHoldMenu(this))
                val p = params(width, c.dp(Prefs.edgeHeightDp(this)), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, "PHL swipe")
                w.addView(v, p)
                swipe = v; swipeParams = p
            }
        } catch (e: Exception) {
            Log.w(TAG, "addView failed", e)
        }
        updateVisibility()
    }

    private fun params(w: Int, h: Int, gravity: Int, title: String) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        setTitle(title)
    }

    private fun removeAll() {
        dismissMenu()
        val w = wm
        listOfNotNull(handle, swipe).forEach { v -> try { w?.removeView(v) } catch (_: Exception) {} }
        handle = null; swipe = null; handleParams = null; swipeParams = null
        wm = null; wctx = null
    }

    fun setHandleExpanded(expanded: Boolean) {
        val v = handle ?: return
        val p = handleParams ?: return
        val c = wctx ?: return
        // The expanded dots zone is 15% of the screen wider and taller, so it is easy to hit.
        p.width = if (expanded) minOf(screenW, handleW + screenW * 15 / 100) else handleW
        p.height = if (expanded) maxOf(handleH, c.dp(HANDLE_EXPANDED_H)) else handleH
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    /** Our home screen is on the bottom screen — the windows pass touches through. */
    fun updateVisibility() {
        val hide = bottomHomeVisible
        if (hide) {
            handle?.collapse()
            dismissMenu()
        }
        for ((v, p) in listOf(handle to handleParams, swipe to swipeParams)) {
            if (v == null || p == null) continue
            p.flags = if (hide) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    // ---------- menu ----------

    @SuppressLint("ClickableViewAccessibility")
    fun showMenu() {
        val c = wctx ?: return
        val w = wm ?: return
        dismissMenu()
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Palette.PANEL, c.dp(18).toFloat(), Palette.PANEL_STROKE, c.dp(1))
            setPadding(0, c.dp(6), 0, c.dp(6))
            minimumWidth = c.dp(230)
            clipToOutline = true
        }
        fun entry(title: String, action: () -> Unit) = TextView(c).apply {
            text = title
            setTextColor(Palette.TEXT)
            textSize = 15f
            setPadding(c.dp(18), c.dp(12), c.dp(18), c.dp(12))
            background = rippleBg()
            setOnClickListener { dismissMenu(); action() }
        }
        panel.addView(entry(I18n.t("close_app")) { closeApp() })
        panel.addView(entry(I18n.t("send_top")) { sendToTop() })
        panel.addView(entry(I18n.t("minimize")) { minimize() })

        val frame = FrameLayout(c).apply {
            setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
            addView(panel)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismissMenu()
                false
            }
        }
        val p = params(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL, "PHL menu",
        ).apply { y = c.dp(30) }
        try {
            w.addView(frame, p)
            menuView = frame
            frame.alpha = 0f
            frame.animate().alpha(1f).setDuration(120).start()
        } catch (e: Exception) {
            Log.w(TAG, "menu addView failed", e)
        }
    }

    fun dismissMenu() {
        val v = menuView ?: return
        menuView = null
        try { wm?.removeView(v) } catch (_: Exception) {}
    }

    // ---------- actions ----------

    /** Minimize: show our home screen on the bottom screen (no root needed). */
    fun minimize() {
        dismissMenu()
        val id = displayId.takeIf { it >= 0 } ?: return
        try {
            val i = Intent(this, SecondaryHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "minimize failed", e)
        }
    }

    private fun closeApp() = withTopTask { t ->
        val out = Root.exec("cmd activity stack remove ${t.rootId}")
        Log.i(TAG, "close ${t.pkg} root=${t.rootId}: ${out?.trim()}")
    }

    private fun sendToTop() = withTopTask { t ->
        val out = Root.exec("cmd activity display move-stack ${t.rootId} ${Displays.TOP}")
        Log.i(TAG, "send ${t.pkg} root=${t.rootId} -> top: ${out?.trim()}")
    }

    private fun withTopTask(block: (TaskRef) -> Unit) {
        val id = displayId
        AppLauncher.background {
            if (Root.exec("true") == null) {
                toastMain(I18n.t("root_needed")); return@background
            }
            val t = TaskMover.topTaskOn(id, skipPackages())
            if (t == null) toastMain(I18n.t("no_app")) else block(t)
        }
    }

    private fun skipPackages() = setOf(
        packageName, "com.android.launcher3", "com.ayaneo.home", "com.android.systemui",
    )

    private fun toastMain(text: String) = main.post { toast(text) }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, I18n.t("gestures"), NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gestures)
            .setContentTitle(I18n.t("gestures"))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PHLauncher"
        private const val CHANNEL = "gestures"
        private const val NOTIF_ID = 7
        private const val HANDLE_EXPANDED_H = 60

        @Volatile private var instance: EdgeGestureService? = null
        @Volatile var bottomHomeVisible = false
            private set

        /** Start the service / rebuild the windows from the current settings. */
        fun refresh(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, EdgeGestureService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start service failed", e)
            }
        }

        /** Without the overlay permission, grant it to ourselves via root, then start. */
        fun start(ctx: Context) {
            val app = ctx.applicationContext
            if (!Prefs.handleEnabled(app) && !Prefs.swipeEnabled(app)) {
                refresh(app) // the service stops itself
                return
            }
            if (Settings.canDrawOverlays(app)) {
                refresh(app)
                return
            }
            AppLauncher.background {
                Root.exec("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
                Handler(Looper.getMainLooper()).post { refresh(app) }
            }
        }

        fun setBottomHomeVisible(visible: Boolean) {
            bottomHomeVisible = visible
            Handler(Looper.getMainLooper()).post { instance?.updateVisibility() }
        }
    }
}

/** Invisible top handle. Hold → three dots, tap the dots → menu. */
@SuppressLint("ViewConstructor")
class HandleView(ctx: Context, private val svc: EdgeGestureService, private val holdMs: Long) : View(ctx) {
    var expanded = false
        private set
    private var downInDots = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6101C26.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x662CFFB1; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT }

    private val show = Runnable { expand() }
    private val autoHide = Runnable { collapse() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                downInDots = expanded
                if (expanded) removeCallbacks(autoHide) else postDelayed(show, holdMs)
            }
            MotionEvent.ACTION_MOVE ->
                if (!expanded && hypot(e.x - downX, e.y - downY) > slop) removeCallbacks(show)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(show)
                if (downInDots && expanded) {
                    collapse()
                    svc.showMenu()
                } else if (expanded) {
                    postDelayed(autoHide, 3000)
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(show)
            MotionEvent.ACTION_OUTSIDE -> if (expanded) collapse()
        }
        return true
    }

    private fun expand() {
        expanded = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        svc.setHandleExpanded(true)
        invalidate()
        postDelayed(autoHide, 3000)
    }

    fun collapse() {
        removeCallbacks(autoHide)
        removeCallbacks(show)
        if (!expanded) return
        expanded = false
        svc.setHandleExpanded(false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!expanded) return
        val w = dp(96).toFloat()
        val h = dp(36).toFloat()
        val left = (width - w) / 2f
        val top = dp(10).toFloat()
        val r = RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(r, h / 2, h / 2, pill)
        canvas.drawRoundRect(r, h / 2, h / 2, stroke)
        val cy = r.centerY()
        val gap = dp(16).toFloat()
        val rad = dp(4).toFloat()
        for (i in -1..1) canvas.drawCircle(r.centerX() + i * gap, cy, rad, dot)
    }
}

/** Bottom edge strip. Swipe up → minimize; swipe up and hold the finger → menu. */
@SuppressLint("ViewConstructor")
class SwipeZoneView(ctx: Context, private val svc: EdgeGestureService, private val holdMenu: Boolean) : View(ctx) {
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var armed = false
    private var menuShown = false
    private var touching = false
    private val armDist = dp(48)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99F1FFF9.toInt() }

    private val holdCheck = Runnable {
        if (armed && !menuShown) {
            menuShown = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            svc.showMenu()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = e.rawY; lastX = e.rawX; lastY = e.rawY
                armed = false; menuShown = false; touching = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!armed && startY - e.rawY > armDist) {
                    armed = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if (holdMenu) postDelayed(holdCheck, HOLD_MS)
                }
                if (armed && holdMenu && !menuShown && hypot(e.rawX - lastX, e.rawY - lastY) > slop) {
                    // The finger is still moving — postpone the menu.
                    lastX = e.rawX; lastY = e.rawY
                    removeCallbacks(holdCheck)
                    postDelayed(holdCheck, HOLD_MS)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdCheck)
                touching = false
                invalidate()
                if (armed && !menuShown) svc.minimize()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdCheck)
                touching = false
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!touching) return
        val w = dp(48).toFloat()
        val h = dp(4).toFloat()
        val left = (width - w) / 2f
        val top = height - h - dp(3)
        canvas.drawRoundRect(left, top, left + w, top + h, h / 2, h / 2, bar)
    }

    companion object { private const val HOLD_MS = 350L }
}
