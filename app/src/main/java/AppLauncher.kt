package com.pockethub.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors

object Displays {
    const val TOP = Display.DEFAULT_DISPLAY

    /** Bottom screen: display 2, otherwise the first non-default display. */
    fun bottomId(ctx: Context): Int? {
        val ids = ctx.getSystemService(DisplayManager::class.java).displays.map { it.displayId }
        return ids.firstOrNull { it == 2 } ?: ids.firstOrNull { it != TOP }
    }

    fun screenKey(displayId: Int) = if (displayId == TOP) "top" else "bottom"
}

/**
 * Launches an app on a specific screen.
 * If the app already has a task on another screen, a normal launch would bring it up there,
 * so first (via root) we move it: cmd activity display move-stack.
 */
object AppLauncher {
    private const val TAG = "PHLauncher"
    private val exec = Executors.newSingleThreadExecutor()

    fun warmUp() = exec.execute { Root.exec("id") }

    /** Background job on the same thread as the root commands. */
    fun background(block: () -> Unit) = exec.execute { block() }

    fun launch(activity: Activity, app: AppInfo, displayId: Int, source: Rect?) {
        exec.execute {
            try {
                TaskMover.moveIfElsewhere(app.key.packageName, displayId)
            } catch (e: Exception) {
                Log.w(TAG, "move failed", e)
            }
            activity.runOnUiThread { start(activity, app, displayId, source) }
        }
    }

    private fun start(ctx: Context, app: AppInfo, displayId: Int, source: Rect?) {
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        try {
            ctx.getSystemService(LauncherApps::class.java)
                .startMainActivity(app.info.componentName, app.user, source, opts)
        } catch (e: Exception) {
            Log.w(TAG, "startMainActivity failed, trying root", e)
            // UserHandle.hashCode() == user id.
            val cmd = "cmd activity start-activity --display $displayId --user ${app.user.hashCode()} " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 " +
                "-n ${app.info.componentName.flattenToShortString()}"
            exec.execute { Log.i(TAG, "root start: " + Root.exec(cmd)) }
        }
    }
}

data class TaskRef(val rootId: Int, val taskId: Int, val pkg: String)

object TaskMover {
    private const val TAG = "PHLauncher"
    private val rootRe = Regex("""RootTask id=(\d+).*?displayId=(\d+)""")
    private val taskRe = Regex("""taskId=(\d+): ([^/\s]+)/""")

    /**
     * Top visible app task on a display (the list is ordered top to bottom).
     * Returns null if a home screen (a package from skip) is on top. Do NOT call on the main thread.
     */
    fun topTaskOn(displayId: Int, skip: Set<String>): TaskRef? {
        val out = Root.exec("cmd activity stack list") ?: return null
        var rootId = -1
        var rootDisplay = -1
        for (line in out.lineSequence()) {
            val r = rootRe.find(line)
            if (r != null) {
                rootId = r.groupValues[1].toInt()
                rootDisplay = r.groupValues[2].toInt()
                continue
            }
            val t = taskRe.find(line) ?: continue
            if (rootDisplay != displayId || !line.contains("visible=true")) continue
            val pkg = t.groupValues[2]
            if (pkg in skip) return null
            return TaskRef(rootId, t.groupValues[1].toInt(), pkg)
        }
        return null
    }

    /** true if the task was moved. Do NOT call on the main thread. */
    fun moveIfElsewhere(pkg: String, displayId: Int): Boolean {
        val out = Root.exec("cmd activity stack list") ?: return false
        var rootId = -1
        var rootDisplay = -1
        for (line in out.lineSequence()) {
            val r = rootRe.find(line)
            if (r != null) {
                rootId = r.groupValues[1].toInt()
                rootDisplay = r.groupValues[2].toInt()
                continue
            }
            val t = taskRe.find(line) ?: continue
            if (t.groupValues[2] != pkg || rootId < 0) continue
            if (rootDisplay == displayId) return false
            val res = Root.exec("cmd activity display move-stack $rootId $displayId")
            Log.i(TAG, "move-stack $pkg root=$rootId $rootDisplay -> $displayId: ${res?.trim()}")
            return true
        }
        return false
    }
}

/** One persistent su process, so we don't pay for starting su on every command. */
object Root {
    private const val TAG = "PHLauncher"
    @Volatile var available: Boolean? = null
        private set
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /**
     * Ask for root again, even if it was refused earlier in this session: Magisk shows its
     * pop-up if no decision was saved. True if we really are uid 0. Do NOT call on the main thread.
     */
    @Synchronized
    fun check(): Boolean {
        if (available == false) available = null
        val out = exec("id") ?: return false
        return out.contains("uid=0")
    }

    @Synchronized
    fun exec(cmd: String): String? {
        if (available == false) return null
        return try {
            if (proc == null) {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                proc = p
                writer = p.outputStream.bufferedWriter()
                reader = p.inputStream.bufferedReader()
            }
            val marker = "__PHL_END_${System.nanoTime()}__"
            writer!!.apply {
                write(cmd)
                write(" 2>&1; echo; echo $marker\n")
                flush()
            }
            val sb = StringBuilder()
            while (true) {
                val line = reader!!.readLine()
                if (line == null) {
                    // su exited: access was not granted.
                    reset()
                    available = false
                    return null
                }
                if (line == marker) break
                sb.append(line).append('\n')
            }
            available = true
            sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "root exec failed: $cmd", e)
            reset()
            null
        }
    }

    private fun reset() {
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null; writer = null; reader = null
    }
}
