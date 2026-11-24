package com.pockethub.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Per-screen wallpapers. A custom image is copied into app storage and drawn under the grid;
 * animated ones are drawn by AnimatedWallpaperView. Also sets the system and lock screen wallpapers.
 */
object Wallpapers {
    private const val TAG = "PHLauncher"

    private val listeners = mutableListOf<(String) -> Unit>()

    private fun file(ctx: Context, screen: String) = File(ctx.filesDir, "wallpaper_$screen.jpg")

    fun has(ctx: Context, screen: String) = file(ctx, screen).exists()

    fun imageMtime(ctx: Context, screen: String) = file(ctx, screen).lastModified()

    /** Small version of the custom image (settings preview). */
    fun loadThumb(ctx: Context, screen: String, maxSide: Int): Bitmap? = try {
        val f = file(ctx, screen)
        if (!f.exists()) null else ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = min(1f, maxSide.toFloat() / max(info.size.width, info.size.height))
            if (scale < 1f) decoder.setTargetSize(
                max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        }
    } catch (e: Exception) {
        null
    }

    // ---------- user's own video wallpaper (MP4) ----------

    fun videoFile(ctx: Context, screen: String) = File(ctx.filesDir, "video_$screen.mp4")

    fun hasVideo(ctx: Context, screen: String) = videoFile(ctx, screen).exists()

    fun videoMtime(ctx: Context, screen: String) = videoFile(ctx, screen).lastModified()

    /** Copies the video as is and checks it has a video track. Do NOT call on the main thread. */
    fun saveVideo(ctx: Context, screen: String, uri: Uri): Boolean = try {
        val tmp = File(ctx.filesDir, "video_$screen.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
        val r = android.media.MediaMetadataRetriever()
        val hasVideo = try {
            r.setDataSource(tmp.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } finally {
            r.release()
        }
        if (!hasVideo) { tmp.delete(); false } else tmp.renameTo(videoFile(ctx, screen))
    } catch (e: Exception) {
        Log.w(TAG, "video save failed", e)
        false
    }

    /** A frame from the start of the video, scaled to fit [maxSide] (previews and the system wallpaper). */
    fun videoFrame(ctx: Context, screen: String, maxSide: Int): Bitmap? {
        val f = videoFile(ctx, screen)
        if (!f.exists()) return null
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val frame = r.getFrameAtTime(500_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = min(1f, maxSide.toFloat() / max(frame.width, frame.height))
            if (scale >= 1f) frame else Bitmap.createScaledBitmap(
                frame, max(1, (frame.width * scale).roundToInt()), max(1, (frame.height * scale).roundToInt()), true)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    // ---------- user's own animated wallpaper (GIF / animated WebP) ----------

    private fun animFile(ctx: Context, screen: String) = File(ctx.filesDir, "anim_$screen")

    fun hasAnimated(ctx: Context, screen: String) = animFile(ctx, screen).exists()

    fun animatedMtime(ctx: Context, screen: String) = animFile(ctx, screen).lastModified()

    /** Copies the file as is (re-encoding would lose the animation). Do NOT call on the main thread. */
    fun saveAnimated(ctx: Context, screen: String, uri: Uri): Boolean = try {
        val tmp = File(ctx.filesDir, "anim_$screen.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(tmp)) // must be a readable image
        tmp.renameTo(animFile(ctx, screen))
    } catch (e: Exception) {
        Log.w(TAG, "animated wallpaper save failed", e)
        false
    }

    /** AnimatedImageDrawable for GIF / animated WebP (a plain drawable for still images). */
    fun loadAnimated(ctx: Context, screen: String): android.graphics.drawable.Drawable? = try {
        val f = animFile(ctx, screen)
        if (!f.exists()) null else ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { decoder, info, _ ->
            if (max(info.size.width, info.size.height) > 2560) decoder.setTargetSampleSize(2)
        }
    } catch (e: Exception) {
        Log.w(TAG, "animated wallpaper load failed", e)
        null
    }

    /** First frame, scaled to fit [maxSide] (previews and the system wallpaper). */
    fun animatedFirstFrame(ctx: Context, screen: String, maxSide: Int): Bitmap? = try {
        val f = animFile(ctx, screen)
        if (!f.exists()) null else ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = min(1f, maxSide.toFloat() / max(info.size.width, info.size.height))
            if (scale < 1f) decoder.setTargetSize(
                max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        }
    } catch (e: Exception) {
        null
    }

    fun load(ctx: Context, screen: String): Bitmap? {
        val f = file(ctx, screen)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    // ---------- change notifications (main thread) ----------

    fun addListener(l: (String) -> Unit) { listeners += l }
    fun removeListener(l: (String) -> Unit) { listeners -= l }

    /** The image of a screen changed (the type may stay IMAGE, so preferences alone don't tell). */
    fun notifyChanged(screen: String) = ArrayList(listeners).forEach { it(screen) }

    // ---------- custom image ----------

    /** targetSide — the long side of the screen; the image's short side is scaled down to it. */
    fun save(ctx: Context, screen: String, uri: Uri, targetSide: Int): Boolean = try {
        val bmp = decodeScaled(ctx, uri, targetSide)
        val tmp = File(ctx.filesDir, "wallpaper_$screen.tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val ok = tmp.renameTo(file(ctx, screen))
        bmp.recycle()
        ok
    } catch (e: Exception) {
        Log.w(TAG, "wallpaper save failed", e)
        false
    }

    private fun decodeScaled(ctx: Context, uri: Uri, targetSide: Int): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, targetSide.toFloat() / min(w, h))
            if (scale < 1f) decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
        }
    }

    // ---------- lock screen ----------

    /** Sets the lock screen wallpaper from an image. Do NOT call on the main thread. */
    fun setLock(ctx: Context, uri: Uri, targetSide: Int): Boolean = try {
        val bmp = decodeScaled(ctx, uri, targetSide)
        WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
        bmp.recycle()
        true
    } catch (e: Exception) {
        Log.w(TAG, "lock wallpaper failed", e)
        false
    }

    // ---------- system wallpaper sync ----------

    /**
     * Keep the system wallpaper (home only, not the lock screen) in line with the top screen:
     * it is briefly visible while Launcher3 Quickstep opens Recents. A custom image is copied as is;
     * an animated wallpaper is applied as a still frame. Only runs when something changed.
     * Do NOT call on the main thread.
     */
    fun syncSystemIfNeeded(ctx: Context, alsoLock: Boolean = false) {
        if (!Prefs.syncSystemWallpaper(ctx)) return
        val type = Prefs.wallpaperType(ctx, "top")
        val f = file(ctx, "top")
        val key = when (type) {
            WallpaperType.IMAGE -> if (f.exists()) "image:" + f.lastModified() else return
            WallpaperType.SYNTHWAVE, WallpaperType.WAVES, WallpaperType.ARCADE, WallpaperType.NIGHT_CITY,
            WallpaperType.AURORA, WallpaperType.PLEXUS, WallpaperType.BOKEH ->
                "anim:" + type.name + ":v1"
            WallpaperType.CUSTOM_ANIM ->
                if (hasAnimated(ctx, "top")) "gif:" + animatedMtime(ctx, "top") else return
            WallpaperType.VIDEO ->
                if (hasVideo(ctx, "top")) "video:" + videoMtime(ctx, "top") else return
            WallpaperType.PIXEL_DUSK, WallpaperType.GAMEPADS, WallpaperType.PIXEL_CASTLE,
            WallpaperType.WIRE_HORIZON, WallpaperType.MEMPHIS ->
                "still:" + type.name + ":v1"
            WallpaperType.SYSTEM -> return
        }
        if (key == Prefs.syncedWallpaperKey(ctx)) return
        try {
            val bmp = when (type) {
                WallpaperType.IMAGE -> BitmapFactory.decodeFile(f.path) ?: return
                WallpaperType.CUSTOM_ANIM -> animatedFirstFrame(ctx, "top", 2560) ?: return
                WallpaperType.VIDEO -> videoFrame(ctx, "top", 2560) ?: return
                WallpaperType.PIXEL_DUSK, WallpaperType.GAMEPADS, WallpaperType.PIXEL_CASTLE,
                WallpaperType.WIRE_HORIZON, WallpaperType.MEMPHIS -> {
                    val (w, h) = topScreenSize(ctx)
                    StillRenderer.render(ctx, type, w, h)
                }
                else -> {
                    val (w, h) = topScreenSize(ctx)
                    SceneRenderer.renderStill(ctx, type, w, h)
                }
            }
            val flags = if (alsoLock) WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, flags)
            bmp.recycle()
            Prefs.putSyncedWallpaperKey(ctx, key)
        } catch (e: Exception) {
            Log.w(TAG, "system wallpaper failed", e)
        }
    }

    /** Top screen size in landscape (it is used horizontally). */
    @Suppress("DEPRECATION")
    private fun topScreenSize(ctx: Context): Pair<Int, Int> {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        d?.getRealMetrics(m)
        val a = if (m.widthPixels > 0) m.widthPixels else 1920
        val b = if (m.heightPixels > 0) m.heightPixels else 1080
        return max(a, b) to min(a, b)
    }
}
