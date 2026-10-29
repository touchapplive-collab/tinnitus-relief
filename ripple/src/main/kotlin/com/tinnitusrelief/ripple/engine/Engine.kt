package com.tinnitusrelief.ripple.engine

import com.tinnitusrelief.ripple.RippleConfig
import com.tinnitusrelief.ripple.RippleModulation
import com.tinnitusrelief.ripple.RippleSequence
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ------------------------------------------------------------------ output sinks (G3: silent only)

/** Where rendered samples go. G3 has no audible implementation; the engine refuses audible sinks. */
interface RippleSink {
    val audible: Boolean
    /** Mono float samples. Called from the processing thread; must not block. */
    fun write(buffer: FloatArray, frames: Int)
}

/** Discards samples but keeps statistics. */
class NullSink : RippleSink {
    override val audible = false
    var frames = 0L; private set
    var peak = 0f; private set
    var nonFinite = 0L; private set
    override fun write(buffer: FloatArray, frames: Int) {
        for (i in 0 until frames) { val v = buffer[i]; if (!v.isFinite()) nonFinite++ else if (abs(v) > peak) peak = abs(v) }
        this.frames += frames
    }
}

/** Records samples (for validation). */
class CaptureSink(capacityFrames: Int) : RippleSink {
    override val audible = false
    val data = FloatArray(capacityFrames)
    var size = 0; private set
    override fun write(buffer: FloatArray, frames: Int) {
        val n = min(frames, data.size - size)
        System.arraycopy(buffer, 0, data, size, n); size += n
    }
}

// ------------------------------------------------------------------ segment sources

interface SegmentSource {
    /** Next rendered segment, or null if none is ready (never blocks). */
    fun poll(): FloatArray?
    fun close()
}

/** Renders on demand on the calling thread. For tests and offline use only. */
class InlineSource(config: RippleConfig, seed: Long) : SegmentSource {
    private val seq = RippleSequence(config, seed)
    override fun poll(): FloatArray = seq.next().samples
    override fun close() {}
}

/**
 * Renders segments ahead on a background thread (spec section 3). The processing thread only copies samples.
 * Capacity 3 = up to 12 s of rendered audio waits ahead of playback, so a segment may take longer than its
 * own 4 s to render (e.g. a busy or throttled phone) without a gap, as long as the average keeps up.
 */
class RenderAheadSource(
    config: RippleConfig,
    seed: Long,
    capacity: Int = 3,
    /** Test hook: extra delay (ms) before each segment is published, to simulate a slow device. */
    private val testDelayMs: (Int) -> Long = { 0L },
) : SegmentSource {
    private val queue = ArrayBlockingQueue<FloatArray>(capacity)
    private val seq = RippleSequence(config, seed)
    @Volatile private var closed = false
    val renderTimesMs = java.util.Collections.synchronizedList(ArrayList<Double>())
    private val thread = Thread({
        var k = 0
        try {
            while (!closed) {
                val t0 = System.nanoTime()
                val s = seq.next().samples
                renderTimesMs += (System.nanoTime() - t0) / 1e6
                val d = testDelayMs(k++)
                if (d > 0) Thread.sleep(d)
                while (!closed && !queue.offer(s, 50, TimeUnit.MILLISECONDS)) { /* wait for room */ }
            }
        } catch (_: InterruptedException) {
        }
    }, "ripple-render").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }

    override fun poll(): FloatArray? = queue.poll()
    override fun close() { closed = true; thread.interrupt(); thread.join(2_000) }
    val alive: Boolean get() = thread.isAlive
}

// ------------------------------------------------------------------ engine

/**
 * EXPERIMENTAL spectral-ripple engine (Gate G3). Starts only from an allowed [GateResult], plays only into a
 * silent sink, and applies the session rules of spec section 4: fade-in from silence, level that never rises
 * by itself, session time limit, fast fade-out and stop on any interruption, no automatic resume.
 *
 * [process] is the audio-thread equivalent: allocation-free after the first call of a given size.
 */
class RippleEngine(
    val sampleRate: Int,
    private val limits: RippleSafetyLimits,
    private val sink: RippleSink,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sourceFactory: (RippleConfig, Long) -> SegmentSource = { c, s -> RenderAheadSource(c, s) },
) {
    init {
        require(!sink.audible || OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED) { "Audible playback is not authorised (G3)" }
    }

    enum class State { IDLE, FADING_IN, PLAYING, FADING_OUT, STOPPED }

    @Volatile var state = State.IDLE; private set
    var onSessionEnded: ((SessionRecord) -> Unit)? = null

    // statistics
    var framesOut = 0L; private set
    var underrunEvents = 0; private set
    var underrunFrames = 0L; private set
    var guardClamps = 0L; private set
    /** Silence written before the first segment was ready (not counted as session time or underrun). */
    var preRollFrames = 0L; private set
    var lastRecord: SessionRecord? = null; private set

    private var gate: GateResult? = null
    private var source: SegmentSource? = null
    private var cur: FloatArray? = null
    private var pos = 0
    private var inGap = false
    private var buf = FloatArray(0)

    private var startMs = 0L
    private var allowedFrames = 0L
    private var fadeInFrames = 1L
    private var fadeOutFrames = 1L
    private var fadeOutStartEnv = 0.0
    private var fadeOutPos = 0L
    private var env = 0.0
    private var endReason = EndReason.USER_STOP
    private var interruption: Interruption? = null

    /** Level in dB relative to the calibrated cap; [targetLevel] is where [levelLin] is heading. */
    var levelDb = -120.0; private set
    private var targetLin = 0.0
    private var levelLin = 0.0
    private var levelStep = 0.0
    private var maxLevelSeenDb = -120.0


    /** Starts a session. Returns false (and does nothing) unless the gate allowed it and the engine is idle. */
    fun start(result: GateResult, seed: Long, startLevelDb: Double = result.startLevelCapDb): Boolean {
        if (!result.allowed || state != State.IDLE) return false
        if (result.request.audibleOutput != sink.audible) return false
        val p = result.prescription ?: return false
        gate = result
        val config = RippleConfig(p.band, RippleModulation.AMPLITUDE, result.request.hearingProfileDb, sampleRate)
        source = sourceFactory(config, seed)
        startMs = clockMs()
        allowedFrames = result.allowedMs * sampleRate / 1000
        fadeInFrames = max(1L, limits.fadeInMs * sampleRate / 1000)
        framesOut = 0; preRollFrames = 0; env = 0.0; cur = null; pos = 0; inGap = false
        levelDb = min(startLevelDb, result.startLevelCapDb).coerceAtMost(limits.maxLevelDb)
        targetLin = dbToLin(levelDb); levelLin = targetLin; levelStep = 0.0
        maxLevelSeenDb = levelDb
        state = State.FADING_IN
        return true
    }

    /**
     * Changes the level. Only a user action may raise it, and never above [RippleSafetyLimits.maxLevelDb].
     * Returns the level now in effect.
     */
    fun setLevel(db: Double, userInitiated: Boolean): Double {
        if (state != State.FADING_IN && state != State.PLAYING) return levelDb
        val clamped = db.coerceAtMost(limits.maxLevelDb)
        if (!userInitiated && clamped > levelDb) return levelDb
        levelDb = clamped
        maxLevelSeenDb = max(maxLevelSeenDb, levelDb)
        targetLin = dbToLin(levelDb)
        val n = max(1L, limits.gainSmoothingMs * sampleRate / 1000)
        levelStep = (targetLin - levelLin) / n
        return levelDb
    }

    fun stop() = beginFadeOut(EndReason.USER_STOP, null, limits.userStopFadeMs)

    /** Any interruption: fast fade-out, stop, no automatic resume. */
    fun interrupt(i: Interruption) = beginFadeOut(EndReason.INTERRUPTED, i, limits.interruptFadeMs)

    private fun beginFadeOut(reason: EndReason, i: Interruption?, ms: Long) {
        if (state == State.FADING_IN || state == State.PLAYING) {
            state = State.FADING_OUT
            endReason = reason; interruption = i
            fadeOutFrames = max(1L, ms * sampleRate / 1000)
            fadeOutStartEnv = env; fadeOutPos = 0
        } else if (state == State.FADING_OUT && reason == EndReason.INTERRUPTED) {
            // an interruption during a slower fade shortens it
            val n = max(1L, ms * sampleRate / 1000)
            if (n < fadeOutFrames - fadeOutPos) { fadeOutStartEnv = env; fadeOutPos = 0; fadeOutFrames = n }
            if (endReason != EndReason.LIMIT_REACHED) { endReason = reason; interruption = i }
        }
    }

    /** Renders [frames] samples into the sink. Silence before start and after stop. */
    fun process(frames: Int) {
        if (buf.size < frames) buf = FloatArray(frames)
        val out = buf
        if (state == State.IDLE || state == State.STOPPED) {
            java.util.Arrays.fill(out, 0, frames, 0f); sink.write(out, frames); return
        }
        var i = 0
        // pre-roll: until the first segment exists, output silence without starting the session clock
        if (state == State.FADING_IN && framesOut == 0L && cur == null) {
            val first = source?.poll()
            if (first == null) { java.util.Arrays.fill(out, 0, frames, 0f); sink.write(out, frames); preRollFrames += frames; return }
            cur = first; pos = 0
        }
        while (i < frames) {
            // session limit: start the end fade so that the session ends exactly at the allowed time
            if ((state == State.FADING_IN || state == State.PLAYING) && framesOut >= allowedFrames - limits.limitFadeMs * sampleRate / 1000) {
                beginFadeOut(EndReason.LIMIT_REACHED, null, limits.limitFadeMs)
            }
            // envelope
            when (state) {
                State.FADING_IN -> {
                    env = if (framesOut >= fadeInFrames) 1.0 else 0.5 * (1 - cos(PI * framesOut / fadeInFrames))
                    if (framesOut >= fadeInFrames) state = State.PLAYING
                }
                State.PLAYING -> env = 1.0
                State.FADING_OUT -> {
                    env = fadeOutStartEnv * (1.0 - fadeOutPos.toDouble() / fadeOutFrames)
                    fadeOutPos++
                    if (fadeOutPos >= fadeOutFrames) { env = 0.0; finish(); java.util.Arrays.fill(out, i, frames, 0f); break }
                }
                else -> { java.util.Arrays.fill(out, i, frames, 0f); break }
            }
            // level smoothing
            if (levelStep != 0.0) {
                levelLin += levelStep
                if ((levelStep > 0 && levelLin >= targetLin) || (levelStep < 0 && levelLin <= targetLin)) { levelLin = targetLin; levelStep = 0.0 }
            }
            // sample
            var s = 0.0
            val seg = cur
            if (seg == null || pos >= seg.size) {
                val next = source?.poll()
                if (next == null) {
                    if (!inGap) { underrunEvents++; inGap = true }
                    underrunFrames++
                } else { cur = next; pos = 0; inGap = false; s = next[pos++].toDouble() }
            } else s = seg[pos++].toDouble()
            var v = s * env * levelLin
            if (!v.isFinite()) { v = 0.0; endReason = EndReason.ERROR; state = State.FADING_OUT; fadeOutStartEnv = 0.0; fadeOutFrames = 1; fadeOutPos = 0 }
            if (v > GUARD) { v = GUARD; guardClamps++ } else if (v < -GUARD) { v = -GUARD; guardClamps++ }
            out[i] = v.toFloat()
            framesOut++
            i++
        }
        sink.write(out, frames)
    }

    private fun finish() {
        state = State.STOPPED
        source?.close(); source = null
        val g = gate ?: return
        val p = g.prescription!!
        val rec = SessionRecord(
            startMs = startMs, endMs = clockMs(), audioMs = framesOut * 1000 / sampleRate,
            reason = endReason, interruption = interruption, band = p.band, arm = p.arm,
            hearingProfileDb = g.request.hearingProfileDb, maxLevelDb = maxLevelSeenDb, resumedFromStartMs = g.resumedFromStartMs,
        )
        lastRecord = rec
        onSessionEnded?.invoke(rec)
    }

    /** Releases the render thread if a session is still running (e.g. the host is destroyed). */
    fun release() {
        if (state == State.FADING_IN || state == State.PLAYING || state == State.FADING_OUT) {
            endReason = EndReason.INTERRUPTED; interruption = Interruption.SERVICE_STOPPED; finish()
        }
        source?.close(); source = null
    }

    companion object {
        /** Last-resort sample guard (−6 dBFS). With the −30 dBFS generator and level ≤ −6 dB it is never reached. */
        const val GUARD = 0.5
        fun dbToLin(db: Double) = if (db <= -120) 0.0 else 10.0.pow(db / 20)
    }
}
