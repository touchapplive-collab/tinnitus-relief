package com.tinnitusrelief.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R

/**
 * A Media3 [Player] facade over the custom AudioTrack engine. It exists so that MediaSession,
 * the media notification, lock-screen controls, Bluetooth buttons and Android Auto all work with
 * our own real-time synthesis.
 *
 * It also owns the platform duties of a well-behaved media app:
 *  - audio focus (pause for calls/other media, resume after transient loss, duck when asked)
 *  - "becoming noisy" (headphones unplugged / Bluetooth disconnected → pause)
 *  - a partial wake lock while playing so the render thread keeps running with the screen off
 */
@OptIn(UnstableApi::class)
class ReliefPlayer(
    private val context: Context,
    looper: Looper,
    private val graph: AppGraph,
) : SimpleBasePlayer(looper) {

    private var playing = false
    private var title = ""
    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ onFocusChange(it) }, Handler(looper))
        .build()

    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TinnitusRelief:playback")
        .apply { setReferenceCounted(false) }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseExternal(250)
        }
    }

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(context.getString(R.string.app_name))
            .build()
        val item = MediaItemData.Builder(MEDIA_ID)
            .setMediaItem(MediaItem.Builder().setMediaId(MEDIA_ID).setMediaMetadata(metadata).build())
            .build()
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(listOf(item))
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        resumeOnFocusGain = false
        applyPlaying(playWhenReady, if (playWhenReady) 0 else USER_FADE_MS)
        if (!playWhenReady) audioManager.abandonAudioFocusRequest(focusRequest)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        resumeOnFocusGain = false
        applyPlaying(false, USER_FADE_MS)
        audioManager.abandonAudioFocusRequest(focusRequest)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        applyPlaying(false, 100)
        audioManager.abandonAudioFocusRequest(focusRequest)
        return Futures.immediateVoidFuture()
    }

    fun setTitle(newTitle: String) {
        if (newTitle == title) return
        title = newTitle
        invalidateState()
    }

    /** Pause that originates outside the controller (sleep timer, focus loss, unplugged headphones). */
    fun pauseExternal(fadeMs: Int) {
        resumeOnFocusGain = false
        if (!playing) return
        applyPlaying(false, fadeMs)
        audioManager.abandonAudioFocusRequest(focusRequest)
        invalidateState()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                graph.engine.setDucked(false)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    if (applyPlaying(true, 0)) invalidateState()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> graph.engine.setDucked(true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) {
                // e.g. a phone call or navigation prompt: pause, keep focus request, resume afterwards
                applyPlaying(false, 300)
                resumeOnFocusGain = true
                invalidateState()
            }
            AudioManager.AUDIOFOCUS_LOSS -> pauseExternal(300)
        }
    }

    /** Returns true when the state actually changed. */
    private fun applyPlaying(play: Boolean, fadeMs: Int): Boolean {
        if (play == playing) return false
        if (play) {
            val granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!granted) return false
            graph.engine.play()
            wakeLock.acquire(WAKE_LOCK_MAX_MS)
            registerNoisy()
        } else {
            graph.engine.pause(fadeMs)
            if (wakeLock.isHeld) wakeLock.release()
            unregisterNoisy()
        }
        playing = play
        graph.onPlaybackChanged(play)
        return true
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    companion object {
        private const val MEDIA_ID = "relief"
        private const val USER_FADE_MS = 700
        /** Safety net only; the lock is released on every pause. 12 h covers any real session. */
        private const val WAKE_LOCK_MAX_MS = 12L * 60 * 60 * 1000

        private val COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .build()
    }
}
