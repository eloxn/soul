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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import app.simple.felicity.decorations.lrc.model.LrcData
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.managers.LyricsManager
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
    private var lyricsTextView: TextView? = null
    private var titleTextView: TextView? = null

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
            x = 80
            y = 200
        }

        val pillLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(12)
            setPadding(pad, dpToPx(8), pad, dpToPx(8))
            val bg = GradientDrawable().apply {
                setColor(0xCC1A1A1A.toInt()) // Semi-transparent sleek dark pill
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), 0x33FFFFFF.toInt())
            }
            background = bg
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleTextView = TextView(this).apply {
            text = "Soul Lyrics"
            setTextColor(0xFFCCCCCC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = p
            maxLines = 1
        }

        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(0xFFAAAAAA.toInt())
            setOnClickListener { stopSelf() }
            val p = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
            layoutParams = p
        }

        topRow.addView(titleTextView)
        topRow.addView(closeButton)
        pillLayout.addView(topRow)

        lyricsTextView = TextView(this).apply {
            text = "♪ ..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            setPadding(0, dpToPx(4), 0, 0)
        }
        pillLayout.addView(lyricsTextView)

        // Drag touch listener
        pillLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(pillLayout, params)
                        return true
                    }
                }
                return false
            }
        })

        overlayView = pillLayout
        windowManager?.addView(overlayView, params)

        observeLyrics()
    }

    private fun observeLyrics() {
        serviceScope.launch {
            LyricsManager.activeLrcDataFlow.collectLatest { data ->
                currentLrcData = data
                val song = MediaPlaybackManager.getCurrentSong()
                titleTextView?.text = song?.title ?: "Soul"
            }
        }

        serviceScope.launch {
            MediaPlaybackManager.songPositionFlow.collectLatest { pos ->
                val lrc = currentLrcData
                if (lrc != null && lrc.entries.isNotEmpty()) {
                    var currentLine: String? = null
                    for (entry in lrc.entries) {
                        if (entry.timeInMillis <= pos) {
                            currentLine = entry.text
                        } else {
                            break
                        }
                    }
                    lyricsTextView?.text = currentLine ?: "♪"
                } else {
                    lyricsTextView?.text = "♪ No lyrics"
                }
            }
        }
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

