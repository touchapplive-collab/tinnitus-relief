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
import com.tinnitusrelief.app.dsp.FocusAction
import com.tinnitusrelief.app.dsp.FocusEvent
import com.tinnitusrelief.app.dsp.FocusPolicy

/**
 * A Media3 [Player] facade over the custom AudioTrack engine, so MediaSession, the media notification,
 * lock-screen controls and headset/Bluetooth buttons all work with real-time synthesis.
 *
 * Platform duties:
 *  - audio focus via the unit-tested [FocusPolicy] (pause for calls, resume afterwards, duck on request)
 *  - "becoming noisy" (headphones unplugged / Bluetooth lost) → pause
 *  - partial wake lock only while playing, so the render thread keeps running with the screen off and
 *    the CPU can sleep as soon as playback stops
 */
@OptIn(UnstableApi::class)
class ReliefPlayer(
    private val context: Context,
    looper: Looper,
    private val graph: AppGraph,
) : SimpleBasePlayer(looper) {

    private val policy = FocusPolicy()
    private var title = ""
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
            .setPlayWhenReady(policy.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) startPlayback() else {
            stopPlayback(USER_FADE_MS)
            policy.onUserPause()
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        stopPlayback(USER_FADE_MS)
        policy.onUserPause()
        audioManager.abandonAudioFocusRequest(focusRequest)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        stopPlayback(100)
        policy.onUserPause()
        audioManager.abandonAudioFocusRequest(focusRequest)
        return Futures.immediateVoidFuture()
    }

    fun setTitle(newTitle: String) {
        if (newTitle == title) return
        title = newTitle
        invalidateState()
    }

    /** Pause from outside the controller (sleep timer, end of a Lab session, unplugged headphones). */
    fun pauseExternal(fadeMs: Int) {
        if (!policy.playing) return
        stopPlayback(fadeMs)
        policy.onUserPause()
        audioManager.abandonAudioFocusRequest(focusRequest)
        invalidateState()
    }

    private fun onFocusChange(change: Int) {
        val event = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusEvent.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FocusEvent.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusEvent.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusEvent.LOSS_TRANSIENT_CAN_DUCK
            else -> return
        }
        var changed = false
        for (a in policy.onFocus(event)) when (a) {
            is FocusAction.Duck -> graph.engine.setDucked(a.ducked)
            is FocusAction.Pause -> {
                stopPlayback(a.fadeMs)
                if (a.abandonFocus) audioManager.abandonAudioFocusRequest(focusRequest)
                changed = true
            }
            FocusAction.Resume -> { resumeEngine(); changed = true }
        }
        if (changed) invalidateState()
    }

    private fun startPlayback() {
        if (policy.playing) return
        val granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return
        policy.onUserPlay()
        resumeEngine()
    }

    private fun resumeEngine() {
        graph.engine.play()
        wakeLock.acquire(WAKE_LOCK_MAX_MS)
        registerNoisy()
        graph.onPlaybackChanged(true)
    }

    private fun stopPlayback(fadeMs: Int) {
        graph.engine.pause(fadeMs)
        if (wakeLock.isHeld) wakeLock.release()
        unregisterNoisy()
        graph.onPlaybackChanged(false)
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
        /** Safety net only; the lock is released on every pause. */
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
