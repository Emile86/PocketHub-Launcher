package com.pockethub.launcher

import android.content.Context
import android.content.SharedPreferences

/** Launcher settings (language is stored in I18n, desktop layouts in LayoutStore). */
object Prefs {
    const val HANDLE = "handle_enabled"
    const val HOLD_MS = "handle_hold_ms"
    const val HANDLE_W_PCT = "handle_width_pct"
    const val HANDLE_H = "handle_height_dp"
    const val SWIPE = "swipe_enabled"
    const val EDGE_H = "edge_height_dp"
    const val CENTER_ONLY = "swipe_center_only"
    const val SWIPE_HOLD_MENU = "swipe_hold_menu"
    const val ICON_SHAPE = "icon_shape"
    const val CONTROLLER = "controller_enabled"
    const val LAYOUT_LOCKED = "layout_locked"
    const val SETUP_DONE = "setup_done"

    /** "Swipe up for all apps" hint was shown on this screen. */
    fun swipeHintShownKey(screen: String) = "swipe_hint_shown_$screen"
    fun swipeHintShown(ctx: Context, screen: String) = prefs(ctx).getBoolean(swipeHintShownKey(screen), false)

    /** The first-run setup assistant was finished or skipped. */
    fun setupDone(ctx: Context) = prefs(ctx).getBoolean(SETUP_DONE, false)

    /** Current setup step, so the assistant continues where it was (e.g. after picking the home app). */
    private const val SETUP_STEP = "setup_step"
    fun setupStep(ctx: Context) = prefs(ctx).getInt(SETUP_STEP, 0)
    fun putSetupStep(ctx: Context, step: Int) = prefs(ctx).edit().putInt(SETUP_STEP, step).apply()

    /** Locked layout: icons can't be moved, removed or added (both screens). */
    fun layoutLocked(ctx: Context) = prefs(ctx).getBoolean(LAYOUT_LOCKED, false)
    const val SYNC_SYSTEM_WP = "sync_system_wallpaper"
    private const val SYNCED_WP_KEY = "synced_system_wallpaper_key"

    /** Also apply the top screen wallpaper as the system wallpaper (visible during the Recents gesture). */
    fun syncSystemWallpaper(ctx: Context) = prefs(ctx).getBoolean(SYNC_SYSTEM_WP, true)
    /** What was last applied as the system wallpaper (so it is only set again when it changes). */
    fun syncedWallpaperKey(ctx: Context) = prefs(ctx).getString(SYNCED_WP_KEY, "") ?: ""
    fun putSyncedWallpaperKey(ctx: Context, v: String) = prefs(ctx).edit().putString(SYNCED_WP_KEY, v).apply()

    /** Screen content: desktop (false) or the full app list (true). */
    fun homeListKey(screen: String) = "home_list_$screen"
    fun homeIsList(ctx: Context, screen: String) = prefs(ctx).getBoolean(homeListKey(screen), false)

    /** App list scrolling when the screen shows the list: vertical (false) or horizontal (true). */
    fun listHorizontalKey(screen: String) = "list_horizontal_$screen"
    fun listHorizontal(ctx: Context, screen: String) = prefs(ctx).getBoolean(listHorizontalKey(screen), false)

    /** Wallpaper kind per screen. Default: an existing custom image, else Wire horizon (top) / Pixel dusk (bottom). */
    fun wallpaperTypeKey(screen: String) = "wallpaper_type_$screen"
    fun wallpaperType(ctx: Context, screen: String): WallpaperType {
        val saved = prefs(ctx).getString(wallpaperTypeKey(screen), null)
        val parsed = WallpaperType.values().firstOrNull { it.name == saved }
        return parsed ?: when {
            Wallpapers.has(ctx, screen) -> WallpaperType.IMAGE
            screen == "top" -> WallpaperType.WIRE_HORIZON
            else -> WallpaperType.PIXEL_DUSK
        }
    }
    fun putWallpaperType(ctx: Context, screen: String, t: WallpaperType) =
        prefs(ctx).edit().putString(wallpaperTypeKey(screen), t.name).apply()

    fun controllerEnabled(ctx: Context) = prefs(ctx).getBoolean(CONTROLLER, true)

    fun iconShape(ctx: Context) =
        if (prefs(ctx).getString(ICON_SHAPE, "round") == "square") IconShape.SQUARE else IconShape.ROUND
    fun putIconShape(ctx: Context, shape: IconShape) =
        prefs(ctx).edit().putString(ICON_SHAPE, if (shape == IconShape.SQUARE) "square" else "round").apply()

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun handleEnabled(ctx: Context) = prefs(ctx).getBoolean(HANDLE, true)
    fun holdMs(ctx: Context) = prefs(ctx).getInt(HOLD_MS, 400)
    /** Top handle zone width, in percent of the bottom screen width. */
    fun handleWidthPct(ctx: Context) = prefs(ctx).getInt(HANDLE_W_PCT, 25)
    fun handleHeightDp(ctx: Context) = prefs(ctx).getInt(HANDLE_H, 32)
    fun swipeEnabled(ctx: Context) = prefs(ctx).getBoolean(SWIPE, true)
    fun edgeHeightDp(ctx: Context) = prefs(ctx).getInt(EDGE_H, 12)
    fun centerOnly(ctx: Context) = prefs(ctx).getBoolean(CENTER_ONLY, true)
    fun swipeHoldMenu(ctx: Context) = prefs(ctx).getBoolean(SWIPE_HOLD_MENU, true)

    /** All-apps list columns for a screen; 0 = automatic. */
    fun drawerColsKey(screen: String) = "drawer_cols_$screen"

    /** All-apps list background opacity for a screen, percent (0 = transparent, 100 = opaque). */
    fun drawerAlphaKey(screen: String) = "drawer_alpha_$screen"
    fun drawerAlpha(ctx: Context, screen: String) = prefs(ctx).getInt(drawerAlphaKey(screen), 92)

    /** Search bar in the app list (swipe-up list and list mode) for a screen. */
    fun drawerSearchKey(screen: String) = "drawer_search_$screen"
    fun drawerSearch(ctx: Context, screen: String) = prefs(ctx).getBoolean(drawerSearchKey(screen), true)
    fun drawerCols(ctx: Context, screen: String) = prefs(ctx).getInt(drawerColsKey(screen), 0)

    /** How many columns automatic mode used (a hint for settings). */
    fun drawerLastColsKey(screen: String) = "drawer_last_cols_$screen"
    fun drawerLastCols(ctx: Context, screen: String) = prefs(ctx).getInt(drawerLastColsKey(screen), 0)

    fun putBool(ctx: Context, key: String, v: Boolean) = prefs(ctx).edit().putBoolean(key, v).apply()
    fun putInt(ctx: Context, key: String, v: Int) = prefs(ctx).edit().putInt(key, v).apply()
}
