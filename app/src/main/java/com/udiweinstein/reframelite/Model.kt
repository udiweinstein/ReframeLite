package com.udiweinstein.reframelite

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** What kind of frames the file contains. `id` is passed to the shader. */
enum class Kind(val id: Int, val label: String) {
    GOPRO_EAC(0, "GoPro MAX 360"),
    EQUIRECT(1, "360 equirectangular"),
    FLAT(2, "Regular video"),
    FISHEYE_2STREAMS(3, "Insta360 (2 lenses)"),
    FISHEYE_SBS(4, "Insta360 (dual fisheye)");

    val is360 get() = this != FLAT
    val twoStreams get() = this == GOPRO_EAC || this == FISHEYE_2STREAMS
}

data class MediaInfo(
    val name: String,
    val kind: Kind,
    val videoTracks: List<Int>,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val fps: Float,
    val audioTrack: Int?,
)

object Probe {
    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "video"
        }
        return uri.lastPathSegment ?: "video"
    }

    fun probe(ctx: Context, uri: Uri): MediaInfo {
        val name = displayName(ctx, uri)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val video = mutableListOf<Int>()
            var audio: Int? = null
            var w = 0
            var h = 0
            var dur = 0L
            var fps = 30f
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    if (video.isEmpty()) {
                        w = f.getInteger(MediaFormat.KEY_WIDTH)
                        h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION)
                        if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            fps = try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (e: Exception) {
                                try { f.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (e2: Exception) { 30f }
                            }
                        }
                    }
                    video.add(i)
                } else if (mime == MediaFormat.MIMETYPE_AUDIO_AAC && audio == null) {
                    val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                    if (ch <= 2) audio = i
                }
            }
            require(video.isNotEmpty()) { "No video track" }
            val ratio = if (h > 0) w.toFloat() / h else 0f
            val insv = name.lowercase().endsWith(".insv")
            val kind = when {
                video.size >= 2 && ratio in 2.8f..3.3f -> Kind.GOPRO_EAC
                video.size >= 2 && abs(ratio - 1f) < 0.05f -> Kind.FISHEYE_2STREAMS
                insv && abs(ratio - 2f) < 0.05f -> Kind.FISHEYE_SBS
                abs(ratio - 2f) < 0.05f -> Kind.EQUIRECT
                else -> Kind.FLAT
            }
            if (fps < 1f) fps = 30f
            return MediaInfo(name, kind, video, w, h, dur, fps, audio)
        } finally {
            ex.release()
        }
    }
}

/** Virtual camera. yaw + = look right, pitch + = look up, fov = vertical, degrees. */
data class ViewParams(val yaw: Float = 0f, val pitch: Float = 0f, val fov: Float = 85f) {

    /** Column-major 3x3 camera->world rotation (Ry(-yaw) * Rx(pitch)) for the shader. */
    fun rotation(): FloatArray {
        val a = Math.toRadians(-yaw.toDouble())
        val b = Math.toRadians(pitch.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val cb = cos(b).toFloat(); val sb = sin(b).toFloat()
        return floatArrayOf(
            ca, 0f, -sa,
            sa * sb, cb, ca * sb,
            sa * cb, -sb, ca * cb,
        )
    }

    /** World direction through a point of the view (ndc -1..1, y up). */
    fun direction(ndcX: Float, ndcY: Float, aspect: Float): FloatArray {
        val t = tan(Math.toRadians(fov / 2.0)).toFloat()
        var x = ndcX * t * aspect
        var y = ndcY * t
        var z = -1f
        val n = sqrt(x * x + y * y + z * z)
        x /= n; y /= n; z /= n
        val m = rotation()
        return normalize(floatArrayOf(
            m[0] * x + m[3] * y + m[6] * z,
            m[1] * x + m[4] * y + m[7] * z,
            m[2] * x + m[5] * y + m[8] * z,
        ))
    }
}

fun normalize(v: FloatArray): FloatArray {
    val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
    return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
}

fun yawPitchOf(d: FloatArray): Pair<Float, Float> =
    Math.toDegrees(atan2(d[0], -d[2]).toDouble()).toFloat() to
        Math.toDegrees(asin(d[1].coerceIn(-1f, 1f)).toDouble()).toFloat()

fun angleBetween(a: FloatArray, b: FloatArray): Float {
    val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
    return kotlin.math.acos(dot)
}

fun wrapDeg(a: Float): Float {
    var x = a % 360f
    if (x > 180f) x -= 360f
    if (x < -180f) x += 360f
    return x
}

/** One sample of the Deep Track path. */
data class TrackPoint(val timeUs: Long, val yaw: Float, val pitch: Float)

object TrackPath {
    private const val MAX_GAP_US = 600_000L

    /** Camera for time `us`: follows the track where there is one, otherwise `base`. */
    fun viewAt(track: List<TrackPoint>, us: Long, base: ViewParams): ViewParams {
        if (track.isEmpty() || us < track.first().timeUs || us > track.last().timeUs) return base
        var lo = 0
        var hi = track.size - 1
        while (lo < hi) {                       // last index with time <= us
            val mid = (lo + hi + 1) / 2
            if (track[mid].timeUs <= us) lo = mid else hi = mid - 1
        }
        val a = track[lo]
        if (lo + 1 >= track.size) return base.copy(yaw = a.yaw, pitch = a.pitch)
        val b = track[lo + 1]
        val span = b.timeUs - a.timeUs
        if (span > MAX_GAP_US) return base
        val s = if (span > 0) (us - a.timeUs).toFloat() / span else 0f
        return base.copy(
            yaw = wrapDeg(a.yaw + wrapDeg(b.yaw - a.yaw) * s),
            pitch = a.pitch + (b.pitch - a.pitch) * s,
        )
    }

    fun segments(track: List<TrackPoint>): List<LongRange> {
        if (track.isEmpty()) return emptyList()
        val out = mutableListOf<LongRange>()
        var start = track[0].timeUs
        var prev = start
        for (p in track.drop(1)) {
            if (p.timeUs - prev > MAX_GAP_US) { out.add(start..prev); start = p.timeUs }
            prev = p.timeUs
        }
        out.add(start..prev)
        return out
    }

    /** Moving average (yaw unwrapped first) to remove jitter. */
    fun smooth(pts: List<TrackPoint>, radius: Int = 4): List<TrackPoint> {
        if (pts.size < 3) return pts
        val unwrapped = FloatArray(pts.size)
        unwrapped[0] = pts[0].yaw
        for (i in 1 until pts.size) unwrapped[i] = unwrapped[i - 1] + wrapDeg(pts[i].yaw - unwrapped[i - 1])
        return pts.indices.map { i ->
            val lo = maxOf(0, i - radius)
            val hi = minOf(pts.size - 1, i + radius)
            var sy = 0f
            var sp = 0f
            for (j in lo..hi) { sy += unwrapped[j]; sp += pts[j].pitch }
            val n = (hi - lo + 1).toFloat()
            pts[i].copy(yaw = wrapDeg(sy / n), pitch = sp / n)
        }
    }
}
