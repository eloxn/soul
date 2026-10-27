package app.simple.felicity.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * An interactive home screen widget for Soul that displays the currently playing
 * song alongside synchronized real-time lyrics and media playback controls.
 */
class LyricsWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_lyrics

    override fun applyStaticViews(scope: WidgetViewScope) {
        val views = scope.views
        val context = scope.context

        // Initialize lyrics tracking if needed
        LyricsWidgetManager.init(context)

        // Metadata
        views.setTextViewText(R.id.widget_title, scope.title)
        views.setTextViewText(R.id.widget_artist, scope.artist)
        views.setTextColor(R.id.widget_title, Color.WHITE)
        views.setTextColor(R.id.widget_artist, Color.LTGRAY)

        // Play/Pause icon swap
        val playPauseRes = if (scope.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        views.setImageViewResource(R.id.widget_play_pause, playPauseRes)

        // Tint control buttons white
        views.setInt(R.id.widget_prev, "setColorFilter", Color.WHITE)
        views.setInt(R.id.widget_play_pause, "setColorFilter", Color.WHITE)
        views.setInt(R.id.widget_next, "setColorFilter", Color.WHITE)

        // Clear button backgrounds
        views.setInt(R.id.widget_prev, "setBackgroundColor", Color.TRANSPARENT)
        views.setInt(R.id.widget_play_pause, "setBackgroundColor", Color.TRANSPARENT)
        views.setInt(R.id.widget_next, "setBackgroundColor", Color.TRANSPARENT)

        // Attach playback control click intents
        views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PLAY_PAUSE)
        )
        views.setOnClickPendingIntent(
                R.id.widget_prev,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PREV)
        )
        views.setOnClickPendingIntent(
                R.id.widget_next,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_NEXT)
        )

        // Open app on clicking lyrics or background
        val openAppIntent = scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_OPEN_APP)
        views.setOnClickPendingIntent(R.id.widget_lyrics_container, openAppIntent)
        views.setOnClickPendingIntent(R.id.widget_background_view, openAppIntent)

        // Restore cached lyrics
        val prevLyric = WidgetStatePrefs.getLyricPrev(context)
        val currentLyric = WidgetStatePrefs.getLyricCurrent(context).ifEmpty {
            context.getString(R.string.no_synced_lyrics)
        }
        val nextLyric = WidgetStatePrefs.getLyricNext(context)

        views.setTextViewText(R.id.widget_lyric_prev, prevLyric)
        views.setTextViewText(R.id.widget_lyric_current, currentLyric)
        views.setTextViewText(R.id.widget_lyric_next, nextLyric)

        views.setViewVisibility(
                R.id.widget_lyric_prev,
                if (prevLyric.isNotBlank()) View.VISIBLE else View.INVISIBLE
        )
        views.setViewVisibility(
                R.id.widget_lyric_next,
                if (nextLyric.isNotBlank()) View.VISIBLE else View.INVISIBLE
        )
    }

    override fun applyAlbumArt(views: RemoteViews, bitmap: Bitmap?) {
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_album_art, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher)
        }
    }

    override fun applyBackgroundArt(views: RemoteViews, bitmap: Bitmap?) {
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_background_view, bitmap)
        }
    }
}
