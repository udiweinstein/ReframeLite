package com.udiweinstein.reframelite

import android.content.Context
import android.net.Uri
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Render + playback thread for the on-screen preview (video only, no sound). */
class PreviewEngine(
    private val ctx: Context,
    private val uri: Uri,
    private val info: MediaInfo,
    private val callback: Callback,
) : Thread("PreviewEngine") {

    interface Callback {
        fun onPosition(us: Long)
        fun onPlayState(playing: Boolean)
        fun onError(message: String)
    }

    @Volatile var view = ViewParams()
    @Volatile var track: List<TrackPoint> = emptyList()
    @Volatile var interacting = false
    @Volatile var endUs = Long.MAX_VALUE

    private val queue = LinkedBlockingQueue<Runnable>()
    private val pendingSeek = AtomicLong(-1)
    @Volatile private var quit = false

    private var playing = false
    private var dirty = true
    private var posUs = 0L
    private var clockWallNs = 0L
    private var clockPtsUs = 0L

    private lateinit var egl: EglCore
    private lateinit var pbuffer: EGLSurface
    private var window: EGLSurface? = null
    private var surfW = 1
    private var surfH = 1

    fun setSurface(surface: Surface?, w: Int, h: Int) {
        val done = CountDownLatch(1)
        queue.offer(Runnable {
            window?.let { egl.makeCurrent(pbuffer); egl.destroySurface(it) }
            window = null
            if (surface != null && surface.isValid) {
                window = egl.windowSurface(surface)
                surfW = w; surfH = h
                dirty = true
            }
            egl.makeCurrent(window ?: pbuffer)
            done.countDown()
        })
        if (surface == null) done.await(1, TimeUnit.SECONDS)   // surfaceDestroyed must wait
    }

    fun play() = queue.offer(Runnable {
        if (!playing) {
            playing = true
            clockWallNs = System.nanoTime(); clockPtsUs = posUs
            callback.onPlayState(true)
        }
    })

    fun pause() = queue.offer(Runnable {
        if (playing) { playing = false; callback.onPlayState(false) }
    })

    fun seek(us: Long) {
        pendingSeek.set(us)
        queue.offer(Runnable { doSeek() })
    }

    fun redraw() = queue.offer(Runnable { dirty = true })

    fun shutdown() {
        quit = true
        queue.offer(Runnable { })
        try { join(3000) } catch (_: InterruptedException) {}
    }

    override fun run() {
        val callbacks = HandlerThread("frames").apply { start() }
        var renderer: GlRenderer? = null
        var frames: VideoFrames? = null
        try {
            egl = EglCore()
            pbuffer = egl.pbuffer(16, 16)
            egl.makeCurrent(pbuffer)
            renderer = GlRenderer()
            frames = VideoFrames(ctx, uri, info, Handler(callbacks.looper))
            currentFrames = frames
            val first = frames.seekTo(0)
            if (first >= 0) posUs = first

            while (!quit) {
                var r: Runnable? = if (playing) queue.poll() else queue.poll(30, TimeUnit.MILLISECONDS)
                while (r != null) {
                    r.run()
                    r = queue.poll()
                }
                if (quit) break
                if (playing) {
                    val p = frames.next()
                    if (p < 0 || p > endUs) {
                        playing = false
                        callback.onPlayState(false)
                        continue
                    }
                    posUs = p
                    val target = clockWallNs + (p - clockPtsUs) * 1000
                    val wait = target - System.nanoTime()
                    if (wait > 0) sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    else if (wait < -250_000_000) { clockWallNs = System.nanoTime(); clockPtsUs = p }
                    follow(p)
                    draw(renderer, frames)
                    callback.onPosition(p)
                } else if (dirty) {
                    draw(renderer, frames)
                    dirty = false
                }
            }
        } catch (e: Exception) {
            Log.e("PreviewEngine", "failed", e)
            callback.onError(e.message ?: e.toString())
        } finally {
            try {
                frames?.release()
                renderer?.release()
                window?.let { egl.destroySurface(it) }
                if (::egl.isInitialized) egl.release()
            } catch (_: Exception) {}
            callbacks.quitSafely()
        }
    }

    private var currentFrames: VideoFrames? = null

    private fun doSeek() {
        val frames = currentFrames ?: return
        val us = pendingSeek.getAndSet(-1)
        if (us < 0) return
        val p = frames.seekTo(us)
        if (p >= 0) {
            posUs = p
            clockWallNs = System.nanoTime(); clockPtsUs = p
            follow(p)
            dirty = true
            callback.onPosition(p)
        }
    }

    /** Deep Track drives the camera unless the user is dragging it. */
    private fun follow(us: Long) {
        if (!interacting && track.isNotEmpty()) view = TrackPath.viewAt(track, us, view)
    }

    private fun draw(renderer: GlRenderer, frames: VideoFrames) {
        val w = window ?: return
        egl.makeCurrent(w)
        renderer.draw(surfW, surfH, view, info, frames)
        egl.swap(w)
    }
}
