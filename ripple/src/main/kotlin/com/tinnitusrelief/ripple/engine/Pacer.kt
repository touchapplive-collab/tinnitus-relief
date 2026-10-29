package com.tinnitusrelief.ripple.engine

import java.util.concurrent.locks.LockSupport

/**
 * Drives [RippleEngine.process] in real time the way an audio output would: a device buffer of
 * [bufferBursts] × [burstFrames] frames is drained at [sampleRate]; the driver refills one burst whenever there is
 * room. If the buffer runs dry before the next burst is written, that is a glitch (an audible dropout on a real
 * output). No audio device is used.
 */
class PacedDriver(
    private val engine: RippleEngine,
    private val sampleRate: Int,
    private val burstFrames: Int,
    private val bufferBursts: Int,
) {
    data class Stats(
        val sampleRate: Int,
        val burstFrames: Int,
        val bufferMs: Double,
        val durationS: Double,
        val callbacks: Int,
        val glitches: Int,
        val meanProcessUs: Double,
        val p99ProcessUs: Double,
        val maxProcessUs: Double,
        val burstPeriodUs: Double,
        val glitchSeconds: List<Int>,
    ) {
        val loadPercent: Double get() = 100 * meanProcessUs / burstPeriodUs
        fun summary(): String = "rate $sampleRate Hz, burst $burstFrames, buffer ${"%.1f".format(bufferMs)} ms, " +
            "${"%.0f".format(durationS)} s: $glitches glitches; process time mean ${"%.0f".format(meanProcessUs)} µs, " +
            "p99 ${"%.0f".format(p99ProcessUs)} µs, max ${"%.0f".format(maxProcessUs)} µs of ${"%.0f".format(burstPeriodUs)} µs (load ${"%.2f".format(loadPercent)}%)"
    }

    fun run(durationMs: Long, shouldStop: () -> Boolean = { false }): Stats {
        val totalFrames = durationMs * sampleRate / 1000
        val bufferFrames = bufferBursts.toLong() * burstFrames
        val times = LongArray((totalFrames / burstFrames + 2).toInt())
        var n = 0
        var glitches = 0
        val glitchSeconds = ArrayList<Int>()
        val t0 = System.nanoTime()
        var written = 0L
        var offset = 0L // frames "lost" to glitches, so consumption restarts after a dropout
        while (written < totalFrames && !shouldStop()) {
            val now = System.nanoTime()
            val consumed = (now - t0) * sampleRate / 1_000_000_000L - offset
            val level = written - consumed
            if (level < 0) { glitches++; glitchSeconds += ((now - t0) / 1_000_000_000L).toInt(); offset += -level; continue }
            val room = bufferFrames - level
            if (room < burstFrames) {
                val waitNs = (burstFrames - room) * 1_000_000_000L / sampleRate
                LockSupport.parkNanos(waitNs)
                continue
            }
            val a = System.nanoTime()
            engine.process(burstFrames)
            val dt = System.nanoTime() - a
            if (n < times.size) times[n++] = dt
            written += burstFrames
        }
        val used = times.copyOf(n).sortedArray()
        val elapsed = (System.nanoTime() - t0) / 1e9
        return Stats(
            sampleRate, burstFrames, bufferFrames * 1000.0 / sampleRate, elapsed, n, glitches,
            if (n == 0) 0.0 else used.average() / 1000, if (n == 0) 0.0 else used[(n * 0.99).toInt().coerceAtMost(n - 1)] / 1000.0,
            if (n == 0) 0.0 else used.last() / 1000.0, burstFrames * 1e6 / sampleRate, glitchSeconds,
        )
    }
}
