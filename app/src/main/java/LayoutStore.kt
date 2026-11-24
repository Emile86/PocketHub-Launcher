package com.pockethub.launcher

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Layouts of both screens. Both activities live in one process,
 * so a shared in-memory object plus listeners is enough.
 * Call on the main thread only.
 */
object LayoutStore {
    private const val TAG = "PHLauncher"
    val SCREENS = listOf("top", "bottom")

    private val cache = HashMap<String, ScreenLayout>()
    private val listeners = mutableListOf<(String) -> Unit>()

    private fun file(ctx: Context, screen: String) =
        AtomicFile(File(ctx.filesDir, "layout_$screen.json"))

    fun get(ctx: Context, screen: String): ScreenLayout =
        cache.getOrPut(screen) { load(ctx, screen) }

    private fun load(ctx: Context, screen: String): ScreenLayout = try {
        val bytes = file(ctx, screen).readFully()
        ScreenLayout.fromJson(screen, JSONObject(String(bytes, Charsets.UTF_8)))
    } catch (e: FileNotFoundException) {
        ScreenLayout(screen)
    } catch (e: Exception) {
        Log.w(TAG, "layout $screen damaged, starting empty", e)
        ScreenLayout(screen)
    }

    fun save(ctx: Context, screen: String, notify: Boolean = true) {
        val l = get(ctx, screen)
        val f = file(ctx, screen)
        var out: FileOutputStream? = null
        try {
            out = f.startWrite()
            out.write(l.toJson().toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            Log.w(TAG, "save $screen failed", e)
            if (out != null) f.failWrite(out)
        }
        if (notify) ArrayList(listeners).forEach { it(screen) }
    }

    fun addListener(l: (String) -> Unit) { listeners += l }
    fun removeListener(l: (String) -> Unit) { listeners -= l }

    /** An app was uninstalled — remove it from all screens. */
    fun removePackage(ctx: Context, pkg: String, serial: Long) {
        for (s in SCREENS) if (get(ctx, s).removePackage(pkg, serial)) save(ctx, s)
    }
}
