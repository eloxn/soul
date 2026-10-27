package app.simple.felicity.engine.managers

import android.content.Context
import android.content.Intent
import android.util.Log
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.utils.AudioUtils.getProperAlbum
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle

/**
 * Dispatches standard Android audio scrobbling intents for local, offline, and online
 * scrobbler applications such as Simple Last.fm Scrobbler, Pano Scrobbler, and ScrobbleDroid.
 *
 * Implements the widely supported `com.adam.asb.action.PLAYSTATE_CHANGED` protocol alongside
 * traditional Android media state broadcast intents.
 */
object ScrobblerManager {

    private const val TAG = "ScrobblerManager"

    // Standard Simple Last.fm / Pano Scrobbler action
    private const val ACTION_SCROBBLE = "com.adam.asb.action.PLAYSTATE_CHANGED"

    // Legacy standard Android music actions
    private const val ACTION_ANDROID_METACHANGED = "com.android.music.metachanged"
    private const val ACTION_ANDROID_PLAYSTATECHANGED = "com.android.music.playstatechanged"
    private const val ACTION_ANDROID_PLAYBACKCOMPLETE = "com.android.music.playbackcomplete"

    const val STATE_START = 0
    const val STATE_RESUME = 1
    const val STATE_PAUSE = 2
    const val STATE_COMPLETE = 3

    /**
     * Broadcasts a play-state transition for [audio] to any listening scrobbler services.
     */
    fun broadcastState(context: Context, audio: Audio?, state: Int, positionMs: Long = 0L) {
        if (audio == null) return

        try {
            val title = audio.getProperTitle()
            val artist = audio.getProperArtists()
            val album = audio.getProperAlbum()
            val durationSec = (audio.duration / 1000).toInt()
            val positionSec = (positionMs / 1000).toInt()

            // 1. Dispatch Simple Last.fm / Pano Scrobbler intent
            val scrobbleIntent = Intent(ACTION_SCROBBLE).apply {
                putExtra("state", state)
                putExtra("app-name", "Soul")
                putExtra("app-package", context.packageName)
                putExtra("artist", artist)
                putExtra("album", album)
                putExtra("track", title)
                putExtra("duration", durationSec)
                putExtra("position", positionSec)
            }
            context.sendBroadcast(scrobbleIntent)

            // 2. Dispatch legacy standard music action
            val legacyAction = when (state) {
                STATE_COMPLETE -> ACTION_ANDROID_PLAYBACKCOMPLETE
                STATE_START -> ACTION_ANDROID_METACHANGED
                else -> ACTION_ANDROID_PLAYSTATECHANGED
            }

            val legacyIntent = Intent(legacyAction).apply {
                putExtra("id", audio.id)
                putExtra("artist", artist)
                putExtra("album", album)
                putExtra("track", title)
                putExtra("playing", state == STATE_START || state == STATE_RESUME)
                putExtra("duration", audio.duration)
                putExtra("position", positionMs)
                putExtra("package", context.packageName)
            }
            context.sendBroadcast(legacyIntent)

            Log.d(TAG, "Scrobble broadcast sent (state=$state, track='$title', artist='$artist')")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast scrobbler intent", e)
        }
    }
}

