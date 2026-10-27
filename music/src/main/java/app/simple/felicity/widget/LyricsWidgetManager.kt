package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import app.simple.felicity.R
import app.simple.felicity.decorations.lrc.model.LrcData
import app.simple.felicity.decorations.lrc.model.LrcEntry
import app.simple.felicity.decorations.lrc.parser.LrcParser
import app.simple.felicity.decorations.lrc.parser.TxtParser
import app.simple.felicity.decorations.lrc.parser.WordLrcParser
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.repositories.LrcRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates real-time synced lyrics updates for [LyricsWidgetProvider].
 *
 * Keeps track of the current song's parsed [LrcData] and seeks along with playback,
 * partially updating the widget only when the active lyric line actually changes.
 */
object LyricsWidgetManager {

    private const val TAG = "LyricsWidgetManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingJob: Job? = null

    @Volatile
    private var cachedLrcData: LrcData? = null

    @Volatile
    private var cachedSongId: Long = -1L

    @Volatile
    private var lastHighlightedIndex: Int = -2

    fun init(context: Context) {
        val appContext = context.applicationContext
        if (trackingJob?.isActive == true) return

        trackingJob = scope.launch {
            // Track song changes
            launch {
                MediaPlaybackManager.songPositionFlow.collect {
                    val song = MediaPlaybackManager.getCurrentSong()
                    handleSongChanged(appContext, song)
                }
            }

            // Track seek position
            launch {
                MediaPlaybackManager.songSeekPositionFlow.collect { positionMs ->
                    handleSeekPosition(appContext, positionMs)
                }
            }
        }
    }

    private suspend fun handleSongChanged(context: Context, song: Audio?) {
        if (song == null) {
            cachedLrcData = null
            cachedSongId = -1L
            lastHighlightedIndex = -2
            pushLyricsUpdate(context, "", context.getString(R.string.no_song_playing), "")
            return
        }

        if (song.id != cachedSongId) {
            cachedSongId = song.id
            lastHighlightedIndex = -2
            cachedLrcData = loadLrc(context, song)
            handleSeekPosition(context, MediaPlaybackManager.getSeekPosition())
        }
    }

    fun handleSeekPosition(context: Context, positionMs: Long) {
        val lrc = cachedLrcData
        if (lrc == null || lrc.isEmpty) {
            if (lastHighlightedIndex != -1) {
                lastHighlightedIndex = -1
                pushLyricsUpdate(context, "", context.getString(R.string.no_synced_lyrics), "")
            }
            return
        }

        val entries = lrc.entries
        val lineIndex = findLineIndex(entries, positionMs + lrc.offset)
        if (lineIndex == lastHighlightedIndex) return

        lastHighlightedIndex = lineIndex

        val prev = if (lineIndex > 0 && lineIndex - 1 < entries.size) entries[lineIndex - 1].text.trim() else ""
        val curr = if (lineIndex in 0 until entries.size) entries[lineIndex].text.trim() else ""
        val next = if (lineIndex + 1 < entries.size) entries[lineIndex + 1].text.trim() else ""

        pushLyricsUpdate(context, prev, curr, next)
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

    suspend fun loadLrc(context: Context, song: Audio): LrcData? = withContext(Dispatchers.IO) {
        runCatching {
            val repository = LrcRepository(context)
            val rawLrc = repository.loadLrcFromFile(song.uri).getOrNull()
            if (!rawLrc.isNullOrBlank()) {
                val parser = when {
                    WordLrcParser().canParse(rawLrc) -> WordLrcParser()
                    LrcParser().canParse(rawLrc) -> LrcParser()
                    else -> TxtParser()
                }
                val data = parser.parse(rawLrc)
                data.sort()
                return@withContext data
            }

            val rawTxt = repository.loadTxtFromFile(song.uri).getOrNull()
            if (!rawTxt.isNullOrBlank()) {
                return@withContext TxtParser().parse(rawTxt)
            }
            null
        }.onFailure {
            Log.e(TAG, "Failed to load lyrics for song: ${song.title}", it)
        }.getOrNull()
    }

    fun pushLyricsUpdate(context: Context, prev: String, current: String, next: String) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, LyricsWidgetProvider::class.java))
        if (ids.isEmpty()) return

        WidgetStatePrefs.saveLyrics(context, prev, current, next)

        val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
        views.setTextViewText(R.id.widget_lyric_prev, prev)
        views.setTextViewText(R.id.widget_lyric_current, current)
        views.setTextViewText(R.id.widget_lyric_next, next)

        views.setViewVisibility(R.id.widget_lyric_prev, if (prev.isNotBlank()) View.VISIBLE else View.INVISIBLE)
        views.setViewVisibility(R.id.widget_lyric_next, if (next.isNotBlank()) View.VISIBLE else View.INVISIBLE)

        for (id in ids) {
            manager.partiallyUpdateAppWidget(id, views)
        }
    }
}
