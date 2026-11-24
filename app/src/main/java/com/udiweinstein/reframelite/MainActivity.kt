package com.udiweinstein.reframelite

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.tan

class MainActivity : Activity(), SurfaceHolder.Callback, PreviewEngine.Callback, TouchView.Listener {

    // ---- state ----
    private var uri: Uri? = null
    private var info: MediaInfo? = null
    private var engine: PreviewEngine? = null
    private var surface: android.view.Surface? = null
    private var surfW = 0
    private var surfH = 0
    private var view = ViewParams()
    private var startUs = 0L
    private var endUs = 0L
    private var posUs = 0L
    private var playing = false
    private var track: List<TrackPoint> = emptyList()
    private var busy = false
    private val cancel = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()

    // ---- views ----
    private lateinit var title: TextView
    private lateinit var timeText: TextView
    private lateinit var hint: TextView
    private lateinit var timeline: TimelineView
    private lateinit var touch: TouchView
    private lateinit var playBtn: Button
    private lateinit var trackBtn: Button
    private lateinit var busyBox: LinearLayout
    private lateinit var busyText: TextView
    private lateinit var busyBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        intent?.data?.let { load(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { load(it) }
    }

    override fun onPause() {
        super.onPause()
        engine?.pause()
    }

    override fun onDestroy() {
        cancel.set(true)
        engine?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(4), dp(12), dp(4), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        title = TextView(this).apply {
            text = "Reframe Lite"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("Open", GRAY) { openFile() }.apply { setPadding(dp(16), dp(8), dp(16), dp(8)) })
        root.addView(top)

        // preview (always 9:16)
        val stage = FrameLayout(this)
        val frame = AspectFrame(this, 9f / 16f)
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        frame.addView(sv, FrameLayout.LayoutParams(-1, -1))
        touch = TouchView(this, this)
        frame.addView(touch, FrameLayout.LayoutParams(-1, -1))
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            text = "Open a GoPro MAX .360, Insta360 .insv\nor 360 video"
        }
        frame.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        stage.addView(frame, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        busyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.argb(230, 30, 32, 38)) }
            visibility = View.GONE
        }
        busyText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER }
        busyBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        busyBox.addView(busyText)
        busyBox.addView(busyBar, LinearLayout.LayoutParams(dp(240), dp(24)).apply { topMargin = dp(10) })
        busyBox.addView(pill("Stop", GRAY) { cancel.set(true) }, LinearLayout.LayoutParams(dp(120), -2).apply { topMargin = dp(10) })
        stage.addView(busyBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // time + timeline
        timeText = TextView(this).apply {
            setTextColor(Color.rgb(200, 200, 205))
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        root.addView(timeText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        timeline = TimelineView(this)
        timeline.onScrub = { us, _ -> scrub(us) }
        root.addView(timeline, LinearLayout.LayoutParams(-1, dp(64)))

        // controls
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        fun add(b: Button) = row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        add(pill("⟦ Start", Color.rgb(36, 120, 60)) { markStart() }.apply {
            setOnLongClickListener { startUs = 0; refreshMarks(); true }
        })
        trackBtn = pill("◎ Track", Color.rgb(40, 140, 70)) { startSelection() }.apply {
            setOnLongClickListener { clearTrack(); true }
        }
        add(trackBtn)
        playBtn = pill("▶", BLUE) { togglePlay() }
        add(playBtn)
        add(pill("End ⟧", Color.rgb(170, 50, 45)) { markEnd() }.apply {
            setOnLongClickListener { endUs = info?.durationUs ?: 0; refreshMarks(); true }
        })
        add(pill("Export", BLUE) { export() })
        root.addView(row)
        return root
    }

    // ---------------------------------------------------------------- file

    private fun openFile() {
        if (busy) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { load(it) }
    }

    private fun load(u: Uri) {
        worker.execute {
            try {
                val mi = Probe.probe(this, u)
                runOnUiThread {
                    uri = u
                    info = mi
                    track = emptyList()
                    view = ViewParams()
                    startUs = 0
                    endUs = mi.durationUs
                    posUs = 0
                    title.text = mi.name
                    hint.visibility = View.GONE
                    timeline.durationUs = mi.durationUs
                    refreshMarks()
                    startEngine(0)
                    if (!mi.kind.is360) toast("Not a 360 video – shown as is")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Can't open: ${e.message}") }
            }
        }
    }

    private fun startEngine(at: Long) {
        engine?.shutdown()
        val u = uri ?: return
        val mi = info ?: return
        val e = PreviewEngine(applicationContext, u, mi, this)
        e.view = view
        e.track = track
        e.endUs = endUs
        e.start()
        surface?.let { e.setSurface(it, surfW, surfH) }
        if (at > 0) e.seek(at)
        engine = e
        setPlaying(false)
    }

    private fun stopEngine() {
        engine?.let { view = it.view; it.shutdown() }
        engine = null
    }

    // ---------------------------------------------------------------- surface

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
        surfW = width; surfH = height
        engine?.setSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        engine?.setSurface(null, 0, 0)
    }

    // ---------------------------------------------------------------- engine callbacks

    override fun onPosition(us: Long) = runOnUiThread {
        posUs = us
        timeline.positionUs = us
        updateTime()
    }

    override fun onPlayState(playing: Boolean) = runOnUiThread { setPlaying(playing) }

    override fun onError(message: String) = runOnUiThread { toast("Playback error: $message") }

    // ---------------------------------------------------------------- touch

    override fun onPan(dx: Float, dy: Float, viewHeight: Float) {
        val e = engine ?: return
        if (info?.kind?.is360 != true) return
        val k = e.view.fov / viewHeight
        e.view = e.view.copy(yaw = wrapDeg(e.view.yaw - dx * k), pitch = (e.view.pitch + dy * k).coerceIn(-90f, 90f))
        e.redraw()
    }

    override fun onZoom(scale: Float) {
        val e = engine ?: return
        if (info?.kind?.is360 != true) return
        e.view = e.view.copy(fov = (e.view.fov / scale).coerceIn(30f, 140f))
        e.redraw()
    }

    override fun onInteract(active: Boolean) {
        engine?.interacting = active
    }

    // ---------------------------------------------------------------- transport + marks

    private fun togglePlay() {
        val e = engine ?: return
        if (playing) { e.pause(); return }
        if (posUs >= endUs - 50_000 || posUs < startUs) e.seek(startUs)
        e.play()
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        playBtn.text = if (p) "❚❚" else "▶"
    }

    private fun scrub(us: Long) {
        val e = engine ?: return
        if (playing) e.pause()
        posUs = us
        timeline.positionUs = us
        updateTime()
        e.seek(us)
    }

    private fun markStart() {
        if (info == null) return
        startUs = posUs
        if (endUs <= startUs) endUs = info!!.durationUs
        refreshMarks()
    }

    private fun markEnd() {
        if (info == null) return
        endUs = posUs
        if (endUs <= startUs) startUs = 0
        refreshMarks()
    }

    private fun refreshMarks() {
        timeline.startUs = startUs
        timeline.endUs = endUs
        timeline.tracked = TrackPath.segments(track)
        engine?.endUs = endUs
        trackBtn.text = if (track.isEmpty()) "◎ Track" else "◎ Track ✓"
        updateTime()
    }

    private fun updateTime() {
        val mi = info
        timeText.text = if (mi == null) "" else
            "${fmt(posUs)} / ${fmt(mi.durationUs)}    ⟦ ${fmt(startUs)} – ${fmt(endUs)} ⟧  (${fmt(endUs - startUs)})"
    }

    // ---------------------------------------------------------------- Deep Track

    private fun startSelection() {
        val mi = info ?: return
        if (busy) return
        if (!mi.kind.is360) { toast("Deep Track needs a 360 video"); return }
        engine?.pause()
        touch.selecting = true
        toast("Draw a box around the subject")
    }

    private fun clearTrack() {
        track = emptyList()
        engine?.track = track
        refreshMarks()
        toast("Track cleared")
    }

    override fun onSubjectSelected(rect: RectF, width: Int, height: Int) {
        val u = uri ?: return
        val mi = info ?: return
        val v = engine?.view ?: view
        val aspect = width.toFloat() / height
        fun dir(x: Float, y: Float) = v.direction(x / width * 2f - 1f, 1f - y / height * 2f, aspect)
        val centre = dir(rect.centerX(), rect.centerY())
        val halfW = angleBetween(dir(rect.left, rect.centerY()), dir(rect.right, rect.centerY())) / 2f
        val halfH = angleBetween(dir(rect.centerX(), rect.top), dir(rect.centerX(), rect.bottom)) / 2f
        val (yaw, pitch) = yawPitchOf(centre)
        val subject = Subject(yaw, pitch, halfW, halfH)
        view = v.copy(yaw = yaw, pitch = pitch)
        val from = posUs
        val to = if (endUs > from) endUs else mi.durationUs

        stopEngine()
        view = view.copy(yaw = yaw, pitch = pitch)
        showBusy("Deep Track…")
        worker.execute {
            val result = try {
                DeepTrack.run(applicationContext, u, mi, from, to, subject, cancel) { p -> progress(p, from, to) }
            } catch (e: Exception) {
                runOnUiThread { toast("Deep Track failed: ${e.message}") }
                emptyList()
            }
            runOnUiThread {
                hideBusy()
                if (result.size > 1) {
                    val last = result.last().timeUs
                    track = (track.filter { it.timeUs < from || it.timeUs > last } + result).sortedBy { it.timeUs }
                    toast("Subject followed for %.1f s".format((last - from) / 1e6))
                } else {
                    toast("Couldn't lock on – try a tighter box")
                }
                refreshMarks()
                startEngine(from)
            }
        }
    }

    // ---------------------------------------------------------------- export

    private fun export() {
        val u = uri ?: return
        val mi = info ?: return
        if (busy) return
        stopEngine()
        val from = startUs
        val to = endUs
        val base = view
        val path = track
        showBusy("Exporting 1080×1920…")
        worker.execute {
            val out = try {
                Exporter.run(applicationContext, u, mi, from, to, base, path, cancel) { p -> progress(p, from, to) }
            } catch (e: Exception) {
                runOnUiThread { toast("Export failed: ${e.message}") }
                null
            }
            runOnUiThread {
                hideBusy()
                if (out != null) toast("Saved to Movies/Reframe")
                startEngine(posUs)
            }
        }
    }

    // ---------------------------------------------------------------- busy overlay

    private fun progress(p: Long, from: Long, to: Long) {
        val f = if (to > from) ((p - from).toFloat() / (to - from)).coerceIn(0f, 1f) else 0f
        runOnUiThread {
            busyBar.progress = (f * 1000).toInt()
            timeline.positionUs = p
        }
    }

    private fun showBusy(text: String) {
        busy = true
        cancel.set(false)
        busyText.text = text
        busyBar.progress = 0
        busyBox.visibility = View.VISIBLE
    }

    private fun hideBusy() {
        busy = false
        busyBox.visibility = View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmt(us: Long): String {
        val s = (us / 1_000_000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val REQ_OPEN = 1
        private val BG = Color.rgb(14, 15, 18)
        private val GRAY = Color.rgb(52, 54, 60)
        private val BLUE = Color.rgb(0, 140, 220)

        @Suppress("unused")
        private fun tanHalf(fov: Float) = tan(Math.toRadians(fov / 2.0)).toFloat()
    }
}
