package app.simple.felicity.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.R
import app.simple.felicity.databinding.ActivityAodLyricsBinding
import app.simple.felicity.decorations.lrc.model.LrcData
import app.simple.felicity.decorations.lrc.model.LrcEntry
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.managers.LyricsManager
import app.simple.felicity.preferences.LyricsPreferences
import app.simple.felicity.repository.constants.MediaConstants
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import app.simple.felicity.widget.LyricsWidgetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Always-On Display (AOD) / Ambient Mode Activity.
 *
 * Designed specifically for OLED screens:
 * - Pure pitch black (#000000) background to minimize power draw.
 * - Periodic sub-pixel shifting (anti-burn-in protection).
 * - Fullscreen immersive sticky window with lock-screen bypass.
 * - Live synchronized lyrics preview with minimal ambient clock and battery stats.
 * - Gentle swipe gestures for track navigation and double-tap to exit.
 *
 * @author Eloxxn
 */
class AodLyricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAodLyricsBinding
    private var currentLrcData: LrcData? = null
    private var currentSongId: Long = -1L
    private var lastLineIndex: Int = -2

    private val burnInHandler = Handler(Looper.getMainLooper())
    private var burnInRunnable: Runnable? = null
    private val controlsHandler = Handler(Looper.getMainLooper())
    private var hideControlsRunnable = Runnable {
        binding.aodControls.animate().alpha(0f).setDuration(400).withEndAction {
            binding.aodControls.visibility = View.GONE
        }.start()
    }

    private lateinit var gestureDetector: GestureDetector

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                if (level >= 0 && scale > 0) {
                    val pct = (level * 100) / scale
                    binding.aodBattery.text = "$pct%${if (isCharging) " ⚡" else ""}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowFlags()

        binding = ActivityAodLyricsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestures()
        setupControls()
        observePlaybackAndLyrics()
        startAntiBurnInProtection()
    }

    private fun setupWindowFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Low ambient brightness if enabled in preferences
        if (LyricsPreferences.isAodDimBrightness()) {
            val lp = window.attributes
            lp.screenBrightness = 0.04f
            window.attributes = lp
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                finish()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 120 && Math.abs(velocityX) > 200) {
                    if (diffX > 0) {
                        MediaPlaybackManager.previous()
                    } else {
                        MediaPlaybackManager.next()
                    }
                    showControlsBriefly()
                    return true
                }
                return false
            }
        })

        binding.aodRoot.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupControls() {
        binding.aodControls.visibility = View.GONE
        binding.aodControls.alpha = 0f

        binding.aodPlayPauseBtn.setOnClickListener {
            MediaPlaybackManager.flipState()
            showControlsBriefly()
        }

        binding.aodPrevBtn.setOnClickListener {
            MediaPlaybackManager.previous()
            showControlsBriefly()
        }

        binding.aodNextBtn.setOnClickListener {
            MediaPlaybackManager.next()
            showControlsBriefly()
        }

        binding.aodClose.setOnClickListener {
            finish()
        }
    }

    private fun toggleControls() {
        if (binding.aodControls.visibility == View.VISIBLE) {
            controlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsRunnable.run()
        } else {
            showControlsBriefly()
        }
    }

    private fun showControlsBriefly() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        binding.aodControls.visibility = View.VISIBLE
        binding.aodControls.animate().alpha(1f).setDuration(250).start()
        controlsHandler.postDelayed(hideControlsRunnable, 4000L)
    }

    private fun observePlaybackAndLyrics() {
        // 1. Song changes
        lifecycleScope.launch {
            MediaPlaybackManager.songPositionFlow.collectLatest {
                val song = MediaPlaybackManager.getCurrentSong()
                handleSongChanged(song)
            }
        }

        // 2. Lyrics updates
        lifecycleScope.launch {
            LyricsManager.activeLrcDataFlow.collectLatest { data ->
                if (data != null && !data.isEmpty) {
                    currentLrcData = data
                    lastLineIndex = -2
                    updateLyricForPosition(MediaPlaybackManager.getSeekPosition())
                }
            }
        }

        // 3. Millisecond playback progress
        lifecycleScope.launch {
            MediaPlaybackManager.songSeekPositionFlow.collectLatest { posMs ->
                updateLyricForPosition(posMs)
            }
        }

        // 4. Playback state
        lifecycleScope.launch {
            MediaPlaybackManager.playbackStateFlow.collectLatest { state ->
                when (state) {
                    MediaConstants.PLAYBACK_PLAYING -> {
                        binding.aodPlayPauseBtn.setImageResource(R.drawable.ic_pause)
                    }
                    else -> {
                        binding.aodPlayPauseBtn.setImageResource(R.drawable.ic_play)
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
            binding.aodSongTitle.text = "Soul Music"
            binding.aodSongArtist.text = ""
            binding.aodLyricPrev.text = ""
            binding.aodLyricCurr.text = "♪ No song playing"
            binding.aodLyricNext.text = ""
            return
        }

        binding.aodSongTitle.text = song.getProperTitle()
        binding.aodSongArtist.text = song.getProperArtists()

        if (song.id != currentSongId) {
            currentSongId = song.id
            lastLineIndex = -2
            currentLrcData = withContext(Dispatchers.IO) {
                LyricsWidgetManager.loadLrc(this@AodLyricsActivity, song)
            } ?: LyricsManager.activeLrcDataFlow.value
            updateLyricForPosition(MediaPlaybackManager.getSeekPosition())
        }
    }

    private fun updateLyricForPosition(positionMs: Long) {
        val lrc = currentLrcData
        if (lrc == null || lrc.isEmpty) {
            if (lastLineIndex != -1) {
                lastLineIndex = -1
                binding.aodLyricPrev.text = ""
                binding.aodLyricCurr.text = "♪ No synced lyrics"
                binding.aodLyricNext.text = ""
            }
            return
        }

        val entries = lrc.entries
        val lineIndex = findLineIndex(entries, positionMs + lrc.offset)
        if (lineIndex == lastLineIndex) return
        lastLineIndex = lineIndex

        val prev = if (lineIndex > 0 && lineIndex - 1 < entries.size) entries[lineIndex - 1].text.trim() else ""
        val curr = if (lineIndex in 0 until entries.size) entries[lineIndex].text.trim() else ""
        val next = if (lineIndex + 1 < entries.size) entries[lineIndex + 1].text.trim() else ""

        binding.aodLyricPrev.text = prev
        binding.aodLyricCurr.text = if (curr.isNotBlank()) curr else "♪"
        binding.aodLyricNext.text = next
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

    /**
     * Anti-Burn-In Protection: Shifts UI elements by ±4 pixels every 40 seconds
     * so stationary pixels never burn in on OLED displays.
     */
    private fun startAntiBurnInProtection() {
        if (!LyricsPreferences.isAodAntiBurnIn()) return
        var shiftStep = 0
        val shifts = arrayOf(
                Pair(0f, 0f),
                Pair(3f, -2f),
                Pair(-2f, 3f),
                Pair(3f, 2f),
                Pair(-3f, -2f)
        )

        burnInRunnable = object : Runnable {
            override fun run() {
                shiftStep = (shiftStep + 1) % shifts.size
                val (dx, dy) = shifts[shiftStep]
                binding.aodContentContainer.animate()
                    .translationX(dx)
                    .translationY(dy)
                    .setDuration(1200)
                    .start()
                burnInHandler.postDelayed(this, 40000L)
            }
        }
        burnInHandler.postDelayed(burnInRunnable!!, 40000L)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        burnInRunnable?.let { burnInHandler.removeCallbacks(it) }
        controlsHandler.removeCallbacks(hideControlsRunnable)
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, AodLyricsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

