package app.simple.felicity.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import app.simple.felicity.decorations.lrc.model.LrcData
import app.simple.felicity.decorations.lrc.model.LrcEntry
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.managers.LyricsManager
import app.simple.felicity.repository.constants.MediaConstants
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import app.simple.felicity.widget.LyricsWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * System overlay window service that renders a compact, draggable, semi-transparent
 * floating lyrics pill over other applications and the home screen.
 *
 * Uses [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] and requires the
 * [android.Manifest.permission.SYSTEM_ALERT_WINDOW] permission.
 *
 * @author Hamza417
 */
class FloatingLyricsService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentLrcData: LrcData? = null
    private var currentSongId: Long = -1L
    private var lastLineIndex: Int = -2

    private var titleTextView: TextView? = null
    private var lyricsTextView: TextView? = null
    private var playPauseButton: ImageButton? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 200
        }

        val pillLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dpToPx(16)
            val padV = dpToPx(10)
            setPadding(padH, padV, padH, padV)
            val bg = GradientDrawable().apply {
                setColor(0xE6141418.toInt()) // Sleek frosted dark pill
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(1), 0x33FFFFFF.toInt())
            }
            background = bg
        }

        // Top Row: Title + Controls
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = p
        }

        titleTextView = TextView(this).apply {
            text = "Soul Lyrics"
            setTextColor(0xFF9E9E9E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.marginEnd = dpToPx(8)
            layoutParams = p
        }

        playPauseButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(0xFFCCCCCC.toInt())
            setOnClickListener {
                MediaPlaybackManager.flipState()
            }
            val p = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
            p.marginEnd = dpToPx(8)
            layoutParams = p
        }

        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(0xFFAAAAAA.toInt())
            setOnClickListener { stopSelf() }
            val p = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
            layoutParams = p
        }

        topRow.addView(titleTextView)
        topRow.addView(playPauseButton)
        topRow.addView(closeButton)
        pillLayout.addView(topRow)

        // Bottom Row: Active Lyric Text
        lyricsTextView = TextView(this).apply {
            text = "♪ ..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dpToPx(4), 0, 0)
        }
        pillLayout.addView(lyricsTextView)

        // Drag touch listener
        pillLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager?.updateViewLayout(pillLayout, params)
                            return true
                        }
                    }
                }
                return false
            }
        })

        overlayView = pillLayout
        windowManager?.addView(overlayView, params)

        observePlaybackAndLyrics()
    }

    private fun observePlaybackAndLyrics() {
        // 1. Observe song changes
        serviceScope.launch {
            MediaPlaybackManager.songPositionFlow.collectLatest {
                val song = MediaPlaybackManager.getCurrentSong()
                handleSongChanged(song)
            }
        }

        // 2. Observe lyrics data updates
        serviceScope.launch {
            LyricsManager.activeLrcDataFlow.collectLatest { data ->
                if (data != null && !data.isEmpty) {
                    currentLrcData = data
                    lastLineIndex = -2
                    updateLyricForPosition(MediaPlaybackManager.getSeekPosition())
                }
            }
        }

        // 3. Observe real-time seek progress in milliseconds
        serviceScope.launch {
            MediaPlaybackManager.songSeekPositionFlow.collectLatest { positionMs ->
                updateLyricForPosition(positionMs)
            }
        }

        // 4. Observe playback state (playing vs paused)
        serviceScope.launch {
            MediaPlaybackManager.playbackStateFlow.collectLatest { state ->
                when (state) {
                    MediaConstants.PLAYBACK_PLAYING -> {
                        playPauseButton?.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    else -> {
                        playPauseButton?.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            }
        }
    }

    private suspend fun handleSongChanged(song: Audio?) {
        if (song == null) {
            currentLrcData = null
            currentSongId = -1L
            lastLineIndex = -2
            titleTextView?.text = "Soul"
            lyricsTextView?.text = "♪ No song playing"
            return
        }

        titleTextView?.text = "${song.getProperTitle()} • ${song.getProperArtists()}"

        if (song.id != currentSongId) {
            currentSongId = song.id
            lastLineIndex = -2
            currentLrcData = LyricsWidgetManager.loadLrc(this@FloatingLyricsService, song)
                    ?: LyricsManager.activeLrcDataFlow.value
            updateLyricForPosition(MediaPlaybackManager.getSeekPosition())
        }
    }

    private fun updateLyricForPosition(positionMs: Long) {
        val lrc = currentLrcData
        if (lrc == null || lrc.isEmpty) {
            if (lastLineIndex != -1) {
                lastLineIndex = -1
                lyricsTextView?.text = "♪ No synced lyrics"
            }
            return
        }

        val entries = lrc.entries
        val lineIndex = findLineIndex(entries, positionMs + lrc.offset)
        if (lineIndex == lastLineIndex) return
        lastLineIndex = lineIndex

        if (lineIndex in 0 until entries.size) {
            val entry = entries[lineIndex]
            val text = entry.text.trim()
            lyricsTextView?.text = if (text.isNotBlank()) text else "♪"
        } else {
            lyricsTextView?.text = "♪ ..."
        }
    }

    private fun findLineIndex(entries: List<LrcEntry>, timeMs: Long): Int {
        var index = -1
        for (i in entries.indices) {
            if (timeMs >= entries[i].timeInMillis) {
                index = i
            } else {
                break
            }
        }
        return index
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FloatingLyricsService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingLyricsService::class.java)
            context.stopService(intent)
        }
    }
}
