package com.tinnitusrelief.ripplebench

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.tinnitusrelief.ripple.RippleBand
import com.tinnitusrelief.ripple.RippleConfig
import com.tinnitusrelief.ripple.RippleModulation
import com.tinnitusrelief.ripple.RippleSequence
import com.tinnitusrelief.ripple.engine.Arm
import com.tinnitusrelief.ripple.engine.Blocker
import com.tinnitusrelief.ripple.engine.CaptureSink
import com.tinnitusrelief.ripple.engine.Interruption
import com.tinnitusrelief.ripple.engine.NullSink
import com.tinnitusrelief.ripple.engine.OutputAuthorization
import com.tinnitusrelief.ripple.engine.PacedDriver
import com.tinnitusrelief.ripple.engine.PitchConfidence
import com.tinnitusrelief.ripple.engine.PitchEstimate
import com.tinnitusrelief.ripple.engine.RenderAheadSource
import com.tinnitusrelief.ripple.engine.RippleEngine
import com.tinnitusrelief.ripple.engine.RippleGate
import com.tinnitusrelief.ripple.engine.RippleSafetyLimits
import com.tinnitusrelief.ripple.engine.SafetyLedger
import com.tinnitusrelief.ripple.engine.ScreeningAnswers
import com.tinnitusrelief.ripple.engine.StartRequest

/** Shared, thread-safe run state (progress text, foreground/background timeline). */
object BenchState {
    @Volatile var running = false
    @Volatile var progress = ""
    @Volatile var foreground = false
    @Volatile var lastReport: String? = null
}

/**
 * Silent validation of the engine on this device (spec V14 and G3 requirement 5). Every sample goes to a
 * [NullSink] or [CaptureSink]; nothing is sent to an audio output. Device audio properties are only read.
 */
class BenchRunner(
    private val context: Context,
    private val renderSegments: Int = 5,
    private val lowLatencySeconds: Long = 120,
    private val mainSeconds: Long = 300,
) {
    private val out = StringBuilder()
    private var failures = 0
    private fun line(s: String) { out.append(s).append('\n'); BenchState.progress = s; android.util.Log.i("RippleBench", s) }
    private fun check(name: String, ok: Boolean, detail: String = "") { if (!ok) failures++; line("${if (ok) "PASS" else "FAIL"}  $name${if (detail.isNotEmpty()) " — $detail" else ""}") }

    fun run(): String {
        line("Ripple bench (silent) — G3 non-listening validation")
        val am = context.getSystemService(AudioManager::class.java)
        val nativeRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it > 0 } ?: 192
        val pm = context.getSystemService(PowerManager::class.java)
        fun thermal() = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus.toString() else "n/a"
        line("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Runtime.getRuntime().availableProcessors()} cores")
        line("Output properties (read only): native rate $nativeRate Hz, burst $burst frames; thermal status ${thermal()}")
        check("audible playback not authorised in this build", !OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED)

        // 1. safety gate sanity on the device
        val limits = RippleSafetyLimits()
        val okScreen = ScreeningAnswers(true, false, false, false, false, false, false)
        val base = StartRequest(okScreen, PitchEstimate(5040.0, PitchConfidence.MEDIUM), true, Arm.ACTIVE, 0.0, false)
        val now = System.currentTimeMillis()
        val g = RippleGate.evaluate(base, SafetyLedger(), limits, now)
        check("gate allows a valid silent request (5.04 kHz → ${g.prescription?.band})", g.allowed && g.prescription?.band == RippleBand.B5)
        check("audible request blocked", Blocker.AUDIBLE_PLAYBACK_NOT_AUTHORIZED in RippleGate.evaluate(base.copy(audibleOutput = true), SafetyLedger(), limits, now).blockers)
        check("30 dB and 45 dB hearing profiles locked", listOf(30.0, 45.0).all { Blocker.HEARING_PROFILE_LOCKED in RippleGate.evaluate(base.copy(hearingProfileDb = it), SafetyLedger(), limits, now).blockers })

        // 2. render speed (V14 part 1): one 4 s segment must take well under 4 s
        for (sr in listOf(nativeRate, 44_100, 48_000).distinct()) {
            val seq = RippleSequence(RippleConfig(RippleBand.B7, RippleModulation.AMPLITUDE, 15.0, sr), 1)
            val times = (0 until renderSegments).map { val t = System.nanoTime(); seq.next(); (System.nanoTime() - t) / 1e6 }
            val worst = times.max()
            check("render speed at $sr Hz", worst < 1_000, "mean ${"%.0f".format(times.average())} ms, worst ${"%.0f".format(worst)} ms per 4 s segment (${"%.1f".format(worst / 40)}% of real time)")
        }

        // 3. real-time pacing with a small (low-latency) and a large (power-saving) device buffer
        val bigBursts = maxOf(4, (0.1 * nativeRate / burst).toInt())
        for ((label, bursts, seconds) in listOf(Triple("low-latency buffer", 4, lowLatencySeconds), Triple("100 ms buffer", bigBursts, mainSeconds))) {
            if (seconds <= 0) continue
            line("Paced run ($label, $seconds s) — you may press Home and turn the screen off during this part")
            val sink = NullSink()
            var src: RenderAheadSource? = null
            val e = RippleEngine(nativeRate, limits, sink) { c, s -> RenderAheadSource(c, s).also { src = it } }
            e.start(RippleGate.evaluate(base, SafetyLedger(), limits, System.currentTimeMillis()), 42, limits.maxLevelDb)
            e.setLevel(limits.maxLevelDb, userInitiated = true)
            val fgSeconds = IntArray(1); val bgSeconds = IntArray(1)
            val watcher = Thread { while (!Thread.currentThread().isInterrupted) { if (BenchState.foreground) fgSeconds[0]++ else bgSeconds[0]++; try { Thread.sleep(1000) } catch (_: InterruptedException) { break } } }.apply { isDaemon = true; start() }
            val st = PacedDriver(e, nativeRate, burst, bursts).run(seconds * 1000)
            watcher.interrupt()
            e.stop(); repeat(200) { e.process(burst) }; e.release()
            val renderMax = src?.renderTimesMs?.maxOrNull() ?: 0.0
            check("$label: no output glitches", st.glitches == 0, st.summary())
            check("$label: no segment underruns", e.underrunEvents == 0, "underruns ${e.underrunEvents}, pre-roll ${e.preRollFrames * 1000 / nativeRate} ms, slowest segment render ${"%.0f".format(renderMax)} ms")
            check("$label: output finite and below guard", sink.nonFinite == 0L && e.guardClamps == 0L, "peak ${"%.4f".format(sink.peak)}")
            line("      time in foreground ${fgSeconds[0]} s, in background/screen off ${bgSeconds[0]} s; glitch seconds ${st.glitchSeconds.take(20)}; thermal ${thermal()}")
        }

        // 4. interruptions on the device: every type stops within the fade and stays silent
        for (i in listOf(Interruption.PHONE_CALL, Interruption.BECOMING_NOISY, Interruption.FOCUS_LOSS_TRANSIENT, Interruption.OUTPUT_ROUTE_CHANGED)) {
            val cap = CaptureSink(nativeRate * 12)
            val e = RippleEngine(nativeRate, limits, cap) { c, s -> RenderAheadSource(c, s) }
            e.start(RippleGate.evaluate(base, SafetyLedger(), limits, System.currentTimeMillis()), 7, -20.0)
            val st = PacedDriver(e, nativeRate, burst, bigBursts)
            st.run(6_000)
            val at = cap.size
            e.interrupt(i)
            st.run(1_000)
            val fade = (limits.interruptFadeMs * nativeRate / 1000).toInt() + burst
            val silent = (at + fade until cap.size).all { cap.data[it] == 0f }
            check("interruption $i stops and stays silent", e.state == RippleEngine.State.STOPPED && silent && e.lastRecord?.interruption == i)
            e.release()
        }

        line(if (failures == 0) "RESULT: all checks passed" else "RESULT: $failures check(s) failed")
        return out.toString()
    }

}
