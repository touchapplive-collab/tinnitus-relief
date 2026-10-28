package com.tinnitusrelief.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the single AudioTrack and the real-time render thread.
 *
 * The thread renders [MixerCore] blocks and writes them with WRITE_BLOCKING, which paces it to the
 * hardware clock. When the transport is paused and the fade-out has finished, the thread pauses the
 * track and sleeps on a monitor, so a paused app uses no CPU.
 */
class AudioEngine {

    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        .takeIf { it in 8000..192000 } ?: 48000

    val mixer = MixerCore(sampleRate)

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private var wantPlaying = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile private var failures = 0

    fun play() {
        lock.withLock {
            wantPlaying = true
            mixer.play()
            ensureThread()
            wake.signalAll()
        }
    }

    fun pause(fadeOutMs: Int = 800) {
        lock.withLock {
            wantPlaying = false
            mixer.pause(fadeOutMs)
            wake.signalAll()
        }
    }

    fun setMasterVolume(slider: Float) = mixer.setLayerAGain(MixerCore.sliderToGain(slider))

    fun setMaskerVoice(voice: Voice?) = mixer.setMaskerVoice(voice)

    fun setDucked(ducked: Boolean) = mixer.setDuck(ducked)

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread(::runLoop, "relief-audio").apply { start() }
    }

    private fun createTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        // Generous buffer (~200 ms): we favour glitch-free long playback and low wakeups over latency.
        val bytes = maxOf(minBytes * 4, sampleRate / 5 * 8)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frames = 1024
        val buf = FloatArray(frames * 2)
        try {
            val t = createTrack()
            track = t
            while (true) {
                lock.withLock {
                    while (!wantPlaying && mixer.isSilent) {
                        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            t.pause()
                            t.flush()
                        }
                        wake.await()
                    }
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                mixer.render(buf, frames)
                failures = 0
                var off = 0
                while (off < buf.size) {
                    val w = t.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) {
                        Log.w(TAG, "AudioTrack write error $w, recreating track")
                        throw IllegalStateException("write $w")
                    }
                    off += w
                }
            }
        } catch (e: InterruptedException) {
            // shutting down
        } catch (e: Exception) {
            Log.e(TAG, "Audio thread stopped", e)
        } finally {
            track?.let { runCatching { it.stop() }; it.release() }
            track = null
            lock.withLock {
                thread = null
                // If the track failed while we should be playing (e.g. audio device change),
                // retry with a fresh track, but give up after a few consecutive failures.
                if (wantPlaying && ++failures <= 3) ensureThread()
            }
        }
    }

    companion object { private const val TAG = "AudioEngine" }
}
