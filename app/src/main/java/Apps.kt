package com.pockethub.launcher

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import java.text.Collator

class AppInfo(
    val key: AppKey,
    val label: String,
    val user: UserHandle,
    val info: LauncherActivityInfo,
)

/** Installed apps and the icon cache. Main thread only. */
object Apps {
    private const val TAG = "PHLauncher"

    private lateinit var ctx: Context
    private lateinit var la: LauncherApps
    private lateinit var um: UserManager
    private var initialized = false

    private var map: Map<AppKey, AppInfo> = emptyMap()
    private var sorted: List<AppInfo> = emptyList()
    private val listeners = mutableListOf<() -> Unit>()

    private val iconCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun init(c: Context) {
        if (initialized) return
        initialized = true
        ctx = c.applicationContext
        la = ctx.getSystemService(LauncherApps::class.java)
        um = ctx.getSystemService(UserManager::class.java)
        RoundIcon.shape = Prefs.iconShape(ctx)
        reload()
        la.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun reload() {
        val m = LinkedHashMap<AppKey, AppInfo>()
        for (user in la.profiles) {
            val serial = um.getSerialNumberForUser(user)
            val list = try { la.getActivityList(null, user) } catch (e: Exception) { emptyList() }
            for (info in list) {
                val key = AppKey(info.componentName, serial)
                m[key] = AppInfo(key, info.label?.toString() ?: info.componentName.packageName, user, info)
            }
        }
        map = m
        val collator = Collator.getInstance()
        sorted = m.values
            .filter { it.key.packageName != ctx.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun all(): List<AppInfo> = sorted
    fun get(key: AppKey): AppInfo? = map[key]

    fun isVisible(item: HomeItem): Boolean = when (item) {
        is AppItem -> map.containsKey(item.app)
        is FolderItem -> item.apps.any { map.containsKey(it) }
    }

    fun icon(key: AppKey, sizePx: Int): Bitmap? {
        val app = map[key] ?: return null
        val ck = key.id + "@" + sizePx + "@" + RoundIcon.shape.name
        iconCache.get(ck)?.let { return it }
        val d = try {
            app.info.getIcon(ctx.resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            Log.w(TAG, "icon ${key.id}", e); null
        } ?: return null
        val b = RoundIcon.render(d, sizePx)
        iconCache.put(ck, b)
        return b
    }

    /** Change the icon shape: clear the cache and redraw the screens. */
    fun setIconShape(c: Context, shape: IconShape) {
        if (RoundIcon.shape == shape) return
        Prefs.putIconShape(c, shape)
        RoundIcon.shape = shape
        iconCache.evictAll()
        ArrayList(listeners).forEach { it() }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun evict(pkg: String) {
        for (k in iconCache.snapshot().keys) if (k.startsWith("$pkg/")) iconCache.remove(k)
    }

    private fun changed(pkgs: Array<out String>) {
        pkgs.forEach { evict(it) }
        reload()
        ArrayList(listeners).forEach { it() }
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            evict(packageName)
            reload()
            LayoutStore.removePackage(ctx, packageName, um.getSerialNumberForUser(user))
            ArrayList(listeners).forEach { it() }
        }
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(arrayOf(packageName))
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(arrayOf(packageName))
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(packageNames)
    }
}
