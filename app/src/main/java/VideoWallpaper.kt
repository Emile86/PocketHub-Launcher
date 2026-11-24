package com.pockethub.launcher

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.io.File
import kotlin.math.max

/**
 * Video wallpaper: plays an MP4 muted and looping, scaled to fill the screen (center crop).
 * Plays only between resume() and pause(); the player is released when the surface goes away.
 */
class VideoWallpaperView(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var file: File? = null
    private var playing = false
    private var prepared = false
    private var videoW = 0
    private var videoH = 0

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    /** Video to show (null = nothing). Restarts the player if the file changed. */
    fun setSource(f: File?) {
        if (f?.path == file?.path && f?.lastModified() == lastMtime) return
        file = f
        lastMtime = f?.lastModified() ?: 0L
        releasePlayer()
        if (surface != null) startPlayer()
    }
    private var lastMtime = 0L

    fun resume() {
        playing = true
        if (prepared) player?.start()
    }

    fun pause() {
        playing = false
        try { if (prepared) player?.pause() } catch (_: Exception) {}
    }

    fun release() {
        playing = false
        file = null
        releasePlayer()
    }

    private fun startPlayer() {
        val f = file ?: return
        val s = surface ?: return
        if (!f.exists()) return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(f.path)
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                applyCenterCrop()
            }
            mp.setOnPreparedListener {
                prepared = true
                if (playing) it.start()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "video error $what/$extra")
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            Log.w(TAG, "video start failed", e)
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        prepared = false
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    /** Scale the video so it covers the whole view, cropping the overflow around the center. */
    private fun applyCenterCrop() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return
        val scale = max(vw / videoW, vh / videoH)
        val m = Matrix()
        m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f)
        setTransform(m)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        startPlayer()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCenterCrop()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    companion object { private const val TAG = "PHLauncher" }
}
