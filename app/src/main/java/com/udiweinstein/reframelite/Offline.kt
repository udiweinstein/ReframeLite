package com.udiweinstein.reframelite

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.math.tan

/** Decodes frames as fast as possible and renders them off-screen (tracking / export). */
class OfflinePass(ctx: Context, uri: Uri, private val info: MediaInfo, output: Surface?, val width: Int, val height: Int) {
    private val callbacks = HandlerThread("offline-frames").apply { start() }
    private val egl = EglCore(recordable = output != null)
    private val surface: EGLSurface = if (output != null) egl.windowSurface(output) else egl.pbuffer(width, height)
    private val renderer: GlRenderer
    private val frames: VideoFrames
    private val pixels: ByteBuffer by lazy { ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()) }

    init {
        egl.makeCurrent(surface)
        renderer = GlRenderer()
        frames = VideoFrames(ctx, uri, info, Handler(callbacks.looper))
    }

    fun seek(us: Long): Long = frames.seekTo(us)
    fun next(): Long = frames.next()

    fun draw(view: ViewParams) = renderer.draw(width, height, view, info, frames)

    fun present(ptsNs: Long) {
        egl.setPresentationTime(surface, ptsNs)
        egl.swap(surface)
    }

    /** Luma of the rendered image, row 0 = top. */
    fun readGray(out: FloatArray) {
        pixels.clear()
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        for (row in 0 until height) {
            val src = row * width * 4
            val dst = (height - 1 - row) * width
            for (x in 0 until width) {
                val i = src + x * 4
                val r = pixels.get(i).toInt() and 0xff
                val g = pixels.get(i + 1).toInt() and 0xff
                val b = pixels.get(i + 2).toInt() and 0xff
                out[dst + x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
    }

    fun release() {
        try { frames.release() } catch (_: Exception) {}
        try { renderer.release() } catch (_: Exception) {}
        egl.destroySurface(surface)
        egl.release()
        callbacks.quitSafely()
    }
}

/** What the user boxed: direction and angular half size (radians). */
data class Subject(val yaw: Float, val pitch: Float, val halfW: Float, val halfH: Float)

/**
 * Deep Track: renders a small "tracking camera" around the subject, follows it with
 * normalised cross-correlation template matching, and re-centres the camera every frame.
 */
object DeepTrack {
    private const val W = 320
    private const val H = 180

    fun run(
        ctx: Context, uri: Uri, info: MediaInfo, startUs: Long, endUs: Long, subject: Subject,
        cancel: AtomicBoolean, progress: (Long) -> Unit,
    ): List<TrackPoint> {
        val pass = OfflinePass(ctx, uri, info, null, W, H)
        val out = mutableListOf<TrackPoint>()
        try {
            val aspect = W.toFloat() / H
            val spanDeg = Math.toDegrees(2.0 * maxOf(subject.halfW, subject.halfH)).toFloat()
            var cam = ViewParams(subject.yaw, subject.pitch, (spanDeg * 3.5f).coerceIn(40f, 100f))
            val tCam = tan(Math.toRadians(cam.fov / 2.0)).toFloat()
            val bw = (tan(subject.halfW) / (tCam * aspect) * W).coerceIn(10f, W * 0.6f)
            val bh = (tan(subject.halfH) / tCam * H).coerceIn(10f, H * 0.8f)

            val gray = FloatArray(W * H)
            val tracker = TemplateTracker(W, H)
            val step = if (info.fps > 40f) 2 else 1
            var n = 0
            var lost = 0

            var p = pass.seek(startUs)
            while (p in 0..endUs && !cancel.get()) {
                if (n++ % step == 0) {
                    pass.draw(cam)
                    pass.readGray(gray)
                    if (out.isEmpty()) {
                        tracker.init(gray, W / 2f, H / 2f, bw, bh)
                        out.add(TrackPoint(p, cam.yaw, cam.pitch))
                    } else {
                        val r = tracker.update(gray)
                        if (r.score < 0.4f) {
                            if (++lost > 15) break                 // subject lost
                        } else {
                            lost = 0
                            val nx = r.x / W * 2f - 1f
                            val ny = 1f - r.y / H * 2f
                            val (yaw, pitch) = yawPitchOf(cam.direction(nx, ny, aspect))
                            out.add(TrackPoint(p, yaw, pitch))
                            cam = cam.copy(yaw = yaw, pitch = pitch)   // re-centre: subject back in the middle
                            tracker.recentre()
                        }
                    }
                    progress(p)
                }
                p = pass.next()
            }
        } finally {
            pass.release()
        }
        return TrackPath.smooth(out)
    }
}

/** Zero-mean normalised cross-correlation tracker on a grey image. */
class TemplateTracker(private val w: Int, private val h: Int) {
    data class Result(val x: Float, val y: Float, val score: Float)

    private var tw = 0
    private var th = 0
    private var stride = 1
    private var tmpl = FloatArray(0)
    private var cx = 0f
    private var cy = 0f

    fun init(img: FloatArray, centreX: Float, centreY: Float, boxW: Float, boxH: Float) {
        stride = maxOf(1, (maxOf(boxW, boxH) / 40f).toInt())
        tw = maxOf(6, (boxW / stride).toInt())
        th = maxOf(6, (boxH / stride).toInt())
        cx = centreX; cy = centreY
        tmpl = patch(img, cx, cy)
    }

    /** The camera was re-centred on the subject, so it is expected in the middle next time. */
    fun recentre() { cx = w / 2f; cy = h / 2f }

    fun update(img: FloatArray): Result {
        val radius = 40
        var best = -2f
        var bx = cx
        var by = cy
        // coarse search
        var dy = -radius
        while (dy <= radius) {
            var dx = -radius
            while (dx <= radius) {
                val s = score(img, cx + dx, cy + dy)
                if (s > best) { best = s; bx = cx + dx; by = cy + dy }
                dx += 3
            }
            dy += 3
        }
        // refine
        val ox = bx
        val oy = by
        for (ry in -2..2) for (rx in -2..2) {
            val s = score(img, ox + rx, oy + ry)
            if (s > best) { best = s; bx = ox + rx; by = oy + ry }
        }
        if (best > 0.7f) {                       // slowly adapt to appearance changes
            val p = patch(img, bx, by)
            for (i in tmpl.indices) tmpl[i] = tmpl[i] * 0.9f + p[i] * 0.1f
        }
        cx = bx; cy = by
        return Result(bx, by, best)
    }

    private fun patch(img: FloatArray, x: Float, y: Float): FloatArray {
        val out = FloatArray(tw * th)
        val x0 = (x - tw * stride / 2f).toInt()
        val y0 = (y - th * stride / 2f).toInt()
        for (j in 0 until th) for (i in 0 until tw) {
            val px = (x0 + i * stride).coerceIn(0, w - 1)
            val py = (y0 + j * stride).coerceIn(0, h - 1)
            out[j * tw + i] = img[py * w + px]
        }
        return out
    }

    private fun score(img: FloatArray, x: Float, y: Float): Float {
        val x0 = (x - tw * stride / 2f).toInt()
        val y0 = (y - th * stride / 2f).toInt()
        if (x0 < 0 || y0 < 0 || x0 + (tw - 1) * stride >= w || y0 + (th - 1) * stride >= h) return -2f
        val n = tw * th
        var st = 0f; var si = 0f; var stt = 0f; var sii = 0f; var sti = 0f
        var k = 0
        for (j in 0 until th) {
            var idx = (y0 + j * stride) * w + x0
            for (i in 0 until tw) {
                val t = tmpl[k++]
                val v = img[idx]
                st += t; si += v; stt += t * t; sii += v * v; sti += t * v
                idx += stride
            }
        }
        val cov = sti - st * si / n
        val vt = stt - st * st / n
        val vi = sii - si * si / n
        val d = sqrt(maxOf(vt, 1e-3f) * maxOf(vi, 1e-3f))
        return cov / d
    }
}

/** Renders the reframed vertical video (9:16) and writes it, with the original AAC audio, to Movies/Reframe. */
object Exporter {
    const val OUT_W = 1080
    const val OUT_H = 1920

    fun run(
        ctx: Context, uri: Uri, info: MediaInfo, startUs: Long, endUs: Long,
        baseView: ViewParams, track: List<TrackPoint>,
        cancel: AtomicBoolean, progress: (Long) -> Unit,
    ): Uri? {
        val name = info.name.substringBeforeLast('.') + "_reframe_" + System.currentTimeMillis() / 1000 + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Reframe")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val pfd = resolver.openFileDescriptor(outUri, "rw") ?: return null
        var ok = false

        val fps = info.fps.toInt().coerceIn(15, 60)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, OUT_W, OUT_H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var audioFormat: MediaFormat? = null
        info.audioTrack?.let { t ->
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            audioFormat = ex.getTrackFormat(t)
            ex.release()
        }
        var videoIndex = -1
        var audioIndex = -1
        var started = false
        val bi = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            var idle = 0
            while (true) {
                val idx = encoder.dequeueOutputBuffer(bi, if (endOfStream) 10_000L else 0L)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream || ++idle > 300) return
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoIndex = muxer.addTrack(encoder.outputFormat)
                        audioFormat?.let { audioIndex = muxer.addTrack(it) }
                        muxer.start()
                        started = true
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)
                        if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                        if (buf != null && bi.size > 0 && started) {
                            buf.position(bi.offset)
                            buf.limit(bi.offset + bi.size)
                            muxer.writeSampleData(videoIndex, buf, bi)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        var pass: OfflinePass? = null
        try {
            pass = OfflinePass(ctx, uri, info, input, OUT_W, OUT_H)
            var p = pass.seek(startUs)
            while (p in 0..endUs && !cancel.get()) {
                pass.draw(TrackPath.viewAt(track, p, baseView))
                pass.present((p - startUs) * 1000)
                drain(false)
                progress(p)
                p = pass.next()
            }
            drain(true)

            // Copy the original AAC audio of the same range.
            val aFmt = audioFormat
            if (started && aFmt != null && audioIndex >= 0 && !cancel.get()) {
                val ex = MediaExtractor()
                ex.setDataSource(ctx, uri, null)
                ex.selectTrack(info.audioTrack!!)
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val buf = ByteBuffer.allocateDirect(1 shl 20)
                val abi = MediaCodec.BufferInfo()
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val t = ex.sampleTime
                    if (t > endUs) break
                    if (t >= startUs) {
                        abi.set(0, n, t - startUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(audioIndex, buf, abi)
                    }
                    ex.advance()
                }
                ex.release()
            }
            ok = started && !cancel.get()
        } finally {
            pass?.release()
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            input.release()
            try { if (started) muxer.stop() } catch (_: Exception) { ok = false }
            muxer.release()
            pfd.close()
            if (ok) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            } else {
                resolver.delete(outUri, null, null)
            }
        }
        return if (ok) outUri else null
    }
}
