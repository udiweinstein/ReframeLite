package com.udiweinstein.reframelite

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.view.Surface
import kotlin.math.abs

/** One video stream: MediaExtractor -> MediaCodec -> Surface. */
class StreamDecoder(ctx: Context, uri: Uri, track: Int, surface: Surface) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private var inputDone = false
    val info = MediaCodec.BufferInfo()

    init {
        extractor.setDataSource(ctx, uri, null)
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    private fun feed() {
        if (inputDone) return
        val i = codec.dequeueInputBuffer(0)
        if (i < 0) return
        val buf = codec.getInputBuffer(i) ?: return
        val n = extractor.readSampleData(buf, 0)
        if (n < 0) {
            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** Index of the next decoded output buffer (not yet released), or -1 at end of stream. */
    fun nextFrame(): Int {
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            feed()
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    codec.releaseOutputBuffer(o, false)
                    return -1
                }
                return o
            }
        }
        return -1
    }

    fun release(index: Int, render: Boolean) = codec.releaseOutputBuffer(index, render)

    fun seek(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
    }

    fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        extractor.release()
    }
}

/** Waits for SurfaceTexture.onFrameAvailable. */
class FrameWaiter : SurfaceTexture.OnFrameAvailableListener {
    private val lock = java.lang.Object()
    private var available = false

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(lock) { available = true; lock.notifyAll() }
    }

    fun await(timeoutMs: Long = 1000): Boolean {
        synchronized(lock) {
            val end = System.currentTimeMillis() + timeoutMs
            while (!available) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            available = false
            return true
        }
    }
}

/**
 * Decodes the one or two video streams of a file in lock-step into OES textures.
 * Create and use on a thread with a current GL context.
 */
class VideoFrames(ctx: Context, uri: Uri, info: MediaInfo, callbackHandler: Handler) {
    val tex0 = GlRenderer.createOesTexture()
    val tex1 = if (info.kind.twoStreams && info.videoTracks.size > 1) GlRenderer.createOesTexture() else 0
    val matrix0 = FloatArray(16)
    val matrix1 = FloatArray(16)

    private val st0 = SurfaceTexture(tex0)
    private val st1 = if (tex1 != 0) SurfaceTexture(tex1) else null
    private val w0 = FrameWaiter()
    private val w1 = FrameWaiter()
    private val s0 = Surface(st0)
    private val s1 = st1?.let { Surface(it) }
    private val d0: StreamDecoder
    private val d1: StreamDecoder?
    private val frameUs = (1_000_000 / info.fps).toLong()

    init {
        st0.setOnFrameAvailableListener(w0, callbackHandler)
        st1?.setOnFrameAvailableListener(w1, callbackHandler)
        d0 = StreamDecoder(ctx, uri, info.videoTracks[0], s0)
        d1 = if (s1 != null) StreamDecoder(ctx, uri, info.videoTracks[1], s1) else null
    }

    /**
     * Decodes the next frame (both streams, matched by timestamp). It is sent to the
     * textures only if its time >= renderFrom. Returns its time in µs, or -1 at the end.
     */
    fun next(renderFrom: Long = Long.MIN_VALUE): Long {
        var i0 = d0.nextFrame()
        if (i0 < 0) return -1
        var p0 = d0.info.presentationTimeUs
        val dec1 = d1
        if (dec1 == null) {
            val render = p0 >= renderFrom
            d0.release(i0, render)
            if (render) latch()
            return p0
        }
        var i1 = dec1.nextFrame()
        if (i1 < 0) { d0.release(i0, false); return -1 }
        var p1 = dec1.info.presentationTimeUs
        var guard = 0
        while (abs(p0 - p1) > 2_000 && guard++ < 30) {          // re-align the two streams
            if (p0 < p1) {
                d0.release(i0, false)
                i0 = d0.nextFrame()
                if (i0 < 0) { dec1.release(i1, false); return -1 }
                p0 = d0.info.presentationTimeUs
            } else {
                dec1.release(i1, false)
                i1 = dec1.nextFrame()
                if (i1 < 0) { d0.release(i0, false); return -1 }
                p1 = dec1.info.presentationTimeUs
            }
        }
        val render = p0 >= renderFrom
        d0.release(i0, render)
        dec1.release(i1, render)
        if (render) latch()
        return p0
    }

    /** Jumps to `us`; the frame at (or just after) it ends up in the textures. */
    fun seekTo(us: Long): Long {
        d0.seek(us)
        d1?.seek(us)
        val threshold = us - frameUs / 2
        while (true) {
            val p = next(renderFrom = threshold)
            if (p < 0 || p >= threshold) return p
        }
    }

    private fun latch() {
        w0.await()
        st0.updateTexImage()
        st0.getTransformMatrix(matrix0)
        if (st1 != null) {
            w1.await()
            st1.updateTexImage()
            st1.getTransformMatrix(matrix1)
        }
    }

    fun release() {
        d0.close()
        d1?.close()
        s0.release(); s1?.release()
        st0.release(); st1?.release()
    }
}
