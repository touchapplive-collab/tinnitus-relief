package com.tinnitusrelief.app.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Decoded interleaved stereo 16-bit PCM. */
class Pcm(val data: ShortArray, val sampleRate: Int) {
    val frames: Int get() = data.size / 2
}

/**
 * Decodes any audio format the device supports (mp3, m4a/aac, ogg, opus, flac, wav…) into memory
 * using the platform MediaExtractor + MediaCodec. Long files are capped to keep memory bounded.
 */
object PcmDecoder {

    /** Longest stretch decoded from one file. 3 minutes of 44.1 kHz stereo = ~32 MB. */
    const val MAX_SECONDS = 180

    fun decodeRaw(context: Context, resId: Int): Pcm {
        val afd: AssetFileDescriptor = context.resources.openRawResourceFd(resId)
        return afd.use {
            val ex = MediaExtractor()
            ex.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            decode(ex)
        }
    }

    fun decodeFile(file: File): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        return decode(ex)
    }

    private fun decode(ex: MediaExtractor): Pcm {
        var codec: MediaCodec? = null
        try {
            val trackIndex = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            ex.selectTrack(trackIndex)
            val inFormat = ex.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val out = ShortBuilder(rate * 2 * 30)
            var maxFrames = rate * MAX_SECONDS
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(ib, 0)
                        if (size < 0 || out.size / 2 >= maxFrames) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        maxFrames = rate * MAX_SECONDS
                    }
                    outIdx >= 0 -> {
                        val ob = codec.getOutputBuffer(outIdx)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.nativeOrder())
                            val ch = channels.coerceAtLeast(1)
                            if (floatPcm) {
                                val fb = ob.asFloatBuffer()
                                val frames = fb.remaining() / ch
                                for (i in 0 until frames) {
                                    val l = fb.get(i * ch)
                                    val r = if (ch > 1) fb.get(i * ch + 1) else l
                                    out.add(toShort(l)); out.add(toShort(r))
                                }
                            } else {
                                val sb = ob.asShortBuffer()
                                val frames = sb.remaining() / ch
                                for (i in 0 until frames) {
                                    val l = sb.get(i * ch)
                                    val r = if (ch > 1) sb.get(i * ch + 1) else l
                                    out.add(l); out.add(r)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val pcm = Pcm(out.toArray(maxFrames * 2), rate)
            if (pcm.frames < rate / 2) throw IOException("Audio is too short (min. 0.5 s)")
            return pcm
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun toShort(f: Float): Short = (f.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    /**
     * Makes a buffer loop without a click or audible seam: the last [crossfadeSeconds] are blended
     * into the start with an equal-power curve and then dropped, so the end flows into the beginning.
     * Also normalises loudness so imported files land at a consistent, conservative level.
     */
    fun makeSeamless(pcm: Pcm, crossfadeSeconds: Float = 1.5f, targetRmsDb: Float = -20f): Pcm {
        val frames = pcm.frames
        val x = min((pcm.sampleRate * crossfadeSeconds).toInt(), frames / 4)
        val src = pcm.data
        val outFrames = frames - x
        val out = ShortArray(outFrames * 2)
        // RMS over the whole file for gain normalisation (never boosts more than +12 dB)
        var sum = 0.0
        for (s in src) sum += s.toDouble() * s
        val rms = kotlin.math.sqrt(sum / src.size.coerceAtLeast(1)) / 32768.0
        val target = Math.pow(10.0, targetRmsDb / 20.0)
        var gain = if (rms > 1e-6) (target / rms) else 1.0
        gain = gain.coerceIn(0.05, 4.0)
        for (i in 0 until outFrames) {
            for (c in 0..1) {
                var v = src[2 * i + c].toDouble()
                if (i < x) {
                    val t = i.toDouble() / x
                    val tail = src[2 * (frames - x + i) + c].toDouble()
                    v = v * sin(t * Math.PI / 2) + tail * cos(t * Math.PI / 2)
                }
                v *= gain
                out[2 * i + c] = v.coerceIn(-32767.0, 32767.0).toInt().toShort()
            }
        }
        return Pcm(out, pcm.sampleRate)
    }

    private class ShortBuilder(initial: Int) {
        private var a = ShortArray(initial.coerceAtLeast(1024))
        var size = 0
            private set

        fun add(v: Short) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = v
        }

        fun toArray(max: Int): ShortArray = a.copyOf(min(size, max))
    }
}
