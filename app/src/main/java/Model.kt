package com.pockethub.launcher

import android.content.ComponentName
import org.json.JSONArray
import org.json.JSONObject

/** App reference: component + profile serial number (for work profiles). */
data class AppKey(val component: ComponentName, val userSerial: Long) {
    val packageName: String get() = component.packageName
    val id: String get() = component.flattenToShortString() + "#" + userSerial

    fun toJson(): JSONObject = JSONObject()
        .put("c", component.flattenToString())
        .put("u", userSerial)

    companion object {
        fun fromJson(o: JSONObject?): AppKey? {
            if (o == null) return null
            val c = ComponentName.unflattenFromString(o.optString("c")) ?: return null
            return AppKey(c, o.optLong("u", 0L))
        }
    }
}

/** Cell: page + grid coordinates. */
data class Cell(val page: Int, val x: Int, val y: Int)

sealed class HomeItem {
    var page = 0
    var x = 0
    var y = 0
    abstract fun toJson(): JSONObject
}

class AppItem(val app: AppKey) : HomeItem() {
    override fun toJson(): JSONObject = JSONObject()
        .put("t", "app").put("p", page).put("x", x).put("y", y)
        .put("app", app.toJson())
}

class FolderItem(var title: String, val apps: MutableList<AppKey>) : HomeItem() {
    override fun toJson(): JSONObject {
        val arr = JSONArray()
        apps.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("t", "folder").put("p", page).put("x", x).put("y", y)
            .put("title", title).put("apps", arr)
    }
}

/** Layout of one screen ("top" or "bottom"). */
class ScreenLayout(val screen: String) {
    val items = mutableListOf<HomeItem>()

    /** Page count and the main page (shown first; the Home button returns to it). */
    var pages = 1
    var homePage = 0

    /** User-defined grid size; 0 = automatic. */
    var cols = 0
    var rows = 0

    /** Last actual grid size (needed to add icons from the other screen). */
    var lastCols = 0
    var lastRows = 0

    private val gridCols get() = if (lastCols > 0) lastCols else 5
    private val gridRows get() = if (lastRows > 0) lastRows else 4

    fun itemAt(page: Int, x: Int, y: Int): HomeItem? =
        items.firstOrNull { it.page == page && it.x == x && it.y == y }

    fun contains(key: AppKey): Boolean = items.any {
        (it is AppItem && it.app == key) || (it is FolderItem && key in it.apps)
    }

    /** Free cells; on preferPage first, then on the other pages in order. */
    fun freeCells(preferPage: Int = homePage): List<Cell> {
        val pp = preferPage.coerceIn(0, pages - 1)
        val order = listOf(pp) + (0 until pages).filter { it != pp }
        val out = ArrayList<Cell>()
        for (p in order) for (y in 0 until gridRows) for (x in 0 until gridCols) {
            if (itemAt(p, x, y) == null) out += Cell(p, x, y)
        }
        return out
    }

    fun findFree(preferPage: Int = homePage): Cell? = freeCells(preferPage).firstOrNull()

    fun pageItemCount(p: Int) = items.count { it.page == p }

    fun addPage(): Boolean {
        if (pages >= MAX_PAGES) return false
        pages++
        return true
    }

    /**
     * Removes page p; later pages shift left and its icons move to free cells on the other pages.
     * Returns the number of icons that didn't fit and were removed from the desktop.
     */
    fun removePage(p: Int): Int {
        if (pages <= 1 || p !in 0 until pages) return 0
        val moving = items.filter { it.page == p }
        moving.forEach { it.page = -1 } // so they don't occupy cells while we look for free ones
        for (item in items) if (item.page > p) item.page -= 1
        pages--
        homePage = when {
            homePage == p -> p - 1
            homePage > p -> homePage - 1
            else -> homePage
        }.coerceIn(0, pages - 1)
        var dropped = 0
        for (item in moving) {
            val c = findFree((p - 1).coerceAtLeast(0))
            if (c == null) {
                items.remove(item); dropped++
            } else {
                item.page = c.page; item.x = c.x; item.y = c.y
            }
        }
        return dropped
    }

    fun removeLastPage(): Int = removePage(pages - 1)

    /** A folder with one app becomes an icon; an empty folder is removed. */
    fun normalizeFolders() {
        val it = items.listIterator()
        while (it.hasNext()) {
            val item = it.next()
            if (item is FolderItem) {
                when (item.apps.size) {
                    0 -> it.remove()
                    1 -> it.set(AppItem(item.apps[0]).also { a -> a.page = item.page; a.x = item.x; a.y = item.y })
                }
            }
        }
    }

    fun removePackage(pkg: String, serial: Long): Boolean {
        var changed = false
        val iter = items.iterator()
        while (iter.hasNext()) {
            val item = iter.next()
            if (item is AppItem && item.app.packageName == pkg && item.app.userSerial == serial) {
                iter.remove(); changed = true
            } else if (item is FolderItem) {
                if (item.apps.removeAll { it.packageName == pkg && it.userSerial == serial }) changed = true
            }
        }
        if (changed) normalizeFolders()
        return changed
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("version", 2)
            .put("pages", pages).put("homePage", homePage)
            .put("cols", cols).put("rows", rows)
            .put("lastCols", lastCols).put("lastRows", lastRows)
            .put("items", arr)
    }

    companion object {
        const val MAX_PAGES = 9

        fun fromJson(screen: String, o: JSONObject): ScreenLayout {
            val l = ScreenLayout(screen)
            l.pages = o.optInt("pages", 1).coerceIn(1, MAX_PAGES)
            l.homePage = o.optInt("homePage", 0).coerceIn(0, l.pages - 1)
            l.cols = o.optInt("cols", 0)
            l.rows = o.optInt("rows", 0)
            l.lastCols = o.optInt("lastCols", 0)
            l.lastRows = o.optInt("lastRows", 0)
            val arr = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val io = arr.optJSONObject(i) ?: continue
                val item: HomeItem? = when (io.optString("t")) {
                    "app" -> AppKey.fromJson(io.optJSONObject("app"))?.let { AppItem(it) }
                    "folder" -> {
                        val apps = mutableListOf<AppKey>()
                        val a = io.optJSONArray("apps") ?: JSONArray()
                        for (j in 0 until a.length()) AppKey.fromJson(a.optJSONObject(j))?.let { apps += it }
                        FolderItem(io.optString("title", "Folder"), apps)
                    }
                    else -> null
                }
                if (item != null) {
                    item.page = io.optInt("p", 0)
                    item.x = io.optInt("x", 0)
                    item.y = io.optInt("y", 0)
                    l.items += item
                }
            }
            l.normalizeFolders()
            return l
        }
    }
}
