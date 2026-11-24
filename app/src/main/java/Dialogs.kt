package com.pockethub.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.min

/** Dark dialogs in the launcher's own style (instead of the stock AlertDialog). */
object ModernDialog {

    /** Room kept free at the bottom while typing (keyboard toolbar on the top screen). */
    private const val KEYBOARD_BAR_DP = 64

    /** Open overlays per activity (newest last). */
    private val open = java.util.WeakHashMap<Activity, MutableList<() -> Unit>>()

    fun isOpen(act: Activity) = open[act]?.isNotEmpty() == true

    /** Closes the newest overlay; true if one was open (call from onBackPressed). */
    fun closeTop(act: Activity): Boolean {
        val l = open[act] ?: return false
        val close = l.lastOrNull() ?: return false
        close()
        return true
    }

    /**
     * A card with a title, optional message and custom content, and buttons on the right.
     * Drawn inside the activity's own window (not a separate Dialog window): on the dual-screen
     * device a Dialog window kept the input focus and blocked taps while the keyboard was up.
     * [onPositive] returns true to close (false keeps it open, e.g. on invalid input).
     * Returns a function that closes it.
     */
    fun show(
        act: Activity,
        title: String,
        message: String? = null,
        content: View? = null,
        positive: String,
        negative: String? = I18n.t("cancel"),
        danger: Boolean = false,
        onPositive: () -> Boolean = { true },
    ): () -> Unit {
        val host = act.findViewById<ViewGroup>(android.R.id.content)
        val prevFocus = act.currentFocus
        val dm = act.resources.displayMetrics
        val scrim = FrameLayout(act).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            isFocusable = false
            alpha = 0f
        }
        var closed = false
        lateinit var close: () -> Unit
        close = {
            if (!closed) {
                closed = true
                open[act]?.remove(close)
                hideKeyboard(act, scrim)
                scrim.animate().alpha(0f).setDuration(140).withEndAction { host.removeView(scrim) }.start()
                prevFocus?.requestFocus()
            }
        }
        // Tap outside the card = cancel.
        scrim.setOnClickListener { close() }

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Look.CARD_TOP, Look.CARD_BOTTOM)).apply {
                cornerRadius = act.dp(26).toFloat()
                setStroke(act.dp(1), Look.CARD_STROKE)
            }
            setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(12))
            isClickable = true // taps on the card itself don't close it
        }
        card.addView(TextView(act).apply {
            text = title
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Look.TEXT_1)
        })
        if (message != null) {
            card.addView(TextView(act).apply {
                text = message
                textSize = 14f
                setTextColor(Look.TEXT_3)
                setLineSpacing(0f, 1.18f)
                setPadding(0, act.dp(8), 0, 0)
            })
        }
        if (content != null) {
            card.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = act.dp(6)
            })
        }
        val buttons = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, act.dp(10), 0, 0)
        }
        if (negative != null) {
            buttons.addView(TextView(act).apply {
                text = negative
                gravity = Gravity.CENTER
                textSize = 14.5f
                setTextColor(Look.TEXT_1)
                setPadding(act.dp(20), 0, act.dp(20), 0)
                background = pressBg(roundedBg(Look.TRACK, act.dp(14).toFloat(), Look.TRACK_STROKE, act.dp(1)))
                isFocusable = true
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(42)).apply { rightMargin = act.dp(10) })
        }
        buttons.addView(TextView(act).apply {
            text = positive
            gravity = Gravity.CENTER
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(act.dp(22), 0, act.dp(22), 0)
            background = pressBg(if (danger) gradientBg(act.dp(14).toFloat(), 0xFFE03131.toInt(), 0xFFF06595.toInt())
                else gradientBg(act.dp(14).toFloat()))
            isFocusable = true
            setOnClickListener { if (onPositive()) close() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(42)))
        card.addView(buttons)

        val w = min((dm.widthPixels * 0.92f).toInt(), act.dp(480))
        scrim.addView(card, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        // Keep the card ~5% above the center, and never under the keyboard bar at the bottom.
        val frame = Rect()
        scrim.viewTreeObserver.addOnGlobalLayoutListener {
            if (closed || card.height == 0) return@addOnGlobalLayoutListener
            scrim.getWindowVisibleDisplayFrame(frame)
            val loc = IntArray(2).also { scrim.getLocationOnScreen(it) }
            // The device may show a keyboard bar on this screen that is not reported as an inset,
            // so while a text field in the card has focus keep some room at the bottom anyway.
            val reserve = if (card.findFocus() is EditText) act.dp(KEYBOARD_BAR_DP) else 0
            val visibleBottom = (frame.bottom - loc[1]).coerceAtMost(scrim.height - reserve)
            val centered = (scrim.height - card.height) / 2 - (scrim.height * 0.05f).toInt()
            val maxTop = visibleBottom - card.height - act.dp(8)
            val top = minOf(centered, maxTop).coerceAtLeast(act.dp(8))
            val lp = card.layoutParams as FrameLayout.LayoutParams
            if (kotlin.math.abs(lp.topMargin - top) > 2) {
                lp.topMargin = top
                card.layoutParams = lp
            }
        }
        scrim.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> if (!closed) scrim.requestLayout() }
        host.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scrim.animate().alpha(1f).setDuration(160).start()
        open.getOrPut(act) { mutableListOf() } += close
        return close
    }

    private fun hideKeyboard(act: Activity, v: View) {
        act.currentFocus?.clearFocus()
        (act.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Text field in the dark style. */
    fun field(act: Activity, value: String, hint: String? = null): EditText = EditText(act).apply {
        setText(value)
        if (hint != null) this.hint = hint
        setSingleLine(true)
        // Without this, landscape opens the full-screen keyboard that hides and blocks the dialog.
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        textSize = 15f
        setTextColor(Look.TEXT_1)
        setHintTextColor(Look.TEXT_2)
        background = roundedBg(Look.TRACK, act.dp(14).toFloat(), Look.TRACK_STROKE, act.dp(1))
        setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10))
    }

    /**
     * App picker for folders: name field, search, and a list of apps with round check marks.
     * Returns the view, the name field and a getter for the chosen apps.
     */
    fun appPicker(act: Activity, name: String, preselect: AppKey?, listHeight: Int): Triple<View, EditText, () -> List<AppKey>> {
        val all = Apps.all()
        val chosen = LinkedHashSet<AppKey>()
        if (preselect != null) chosen += preselect
        var shown = all
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val nameField = field(act, name)
        box.addView(nameField)
        val search = field(act, "", I18n.t("search_hint"))
        box.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = act.dp(8)
        })
        val count = TextView(act).apply {
            textSize = 12.5f
            setTextColor(Look.SUB)
            setPadding(act.dp(4), act.dp(6), 0, act.dp(2))
        }
        box.addView(count)
        fun updateCount() { count.text = I18n.t("selected_n", chosen.size) }
        updateCount()

        val iconPx = act.dp(36)
        val pickerAdapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int): Any = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val row = (convert as? LinearLayout) ?: LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(act.dp(8), act.dp(7), act.dp(8), act.dp(7))
                    addView(ImageView(act), LinearLayout.LayoutParams(iconPx, iconPx))
                    addView(TextView(act).apply {
                        textSize = 15f
                        setTextColor(Look.TEXT_1)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(act.dp(12), 0, act.dp(8), 0)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(act).apply {
                        gravity = Gravity.CENTER
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(act.dp(26), act.dp(26)))
                }
                val app = shown[p]
                val on = app.key in chosen
                (row.getChildAt(0) as ImageView).setImageBitmap(Apps.icon(app.key, iconPx))
                (row.getChildAt(1) as TextView).text = app.label
                (row.getChildAt(2) as TextView).apply {
                    text = if (on) "✓" else ""
                    background = if (on) gradientBg(act.dp(13).toFloat())
                    else roundedBg(0, act.dp(13).toFloat(), 0x55FFFFFF, act.dp(2))
                }
                row.background = if (on) roundedBg(0x1412C99B, act.dp(12).toFloat()) else null
                return row
            }
        }
        val list = ListView(act).apply {
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            adapter = pickerAdapter
            isFocusable = false
            setOnItemClickListener { _, _, p, _ ->
                val k = shown[p].key
                if (!chosen.remove(k)) chosen += k
                updateCount()
                pickerAdapter.notifyDataSetChanged()
            }
        }
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight))
        // With the keyboard bar up, the list shrinks so the whole card (and its buttons) still fits.
        val frame = Rect()
        list.viewTreeObserver.addOnGlobalLayoutListener {
            val card = box.parent as? View ?: return@addOnGlobalLayoutListener
            val screenArea = card.parent as? View ?: return@addOnGlobalLayoutListener
            card.getWindowVisibleDisplayFrame(frame)
            val loc = IntArray(2).also { screenArea.getLocationOnScreen(it) }
            val reserve = if (card.findFocus() is EditText) act.dp(KEYBOARD_BAR_DP) else 0
            val visibleH = (frame.bottom - loc[1]).coerceAtMost(screenArea.height - reserve)
            val other = card.height - list.height
            val want = (visibleH - other - act.dp(16)).coerceIn(act.dp(64), listHeight)
            val lp = list.layoutParams
            if (kotlin.math.abs(lp.height - want) > act.dp(4)) {
                lp.height = want
                list.layoutParams = lp
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                shown = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
                pickerAdapter.notifyDataSetChanged()
            }
        })
        return Triple(box, nameField) { chosen.toList() }
    }
}
