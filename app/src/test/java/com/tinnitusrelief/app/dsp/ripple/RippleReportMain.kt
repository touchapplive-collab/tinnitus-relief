package com.tinnitusrelief.app.dsp.ripple

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log2

/**
 * Runs the full G1 validation (spec counts) at 48 and 44.1 kHz and writes results plus plot data to args[0].
 * Offline only: WAV files written here are analysis inputs for the report's spectrograms, not for listening.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "ripple-report" }).apply { mkdirs() }
    val md = StringBuilder()
    var failures = 0
    for (sr in listOf(48_000, 44_100)) {
        val v = RippleValidation(full = true, sampleRate = sr)
        v.dump = { name, text -> File(out, "${sr}_$name").writeText(text) }
        val t0 = System.currentTimeMillis()
        val checks = listOf(v.v1(), v.v2(), v.v3(), v.v4()) + v.v5v6a() + v.v6bc() + listOf(v.v7(), v.v8(), v.v9()) + v.v10v11() + listOf(v.v12(), v.negativeControls())
        md.append("## Sample rate $sr Hz (${(System.currentTimeMillis() - t0) / 1000} s)\n\n")
        for (c in checks) {
            if (!c.pass) failures++
            md.append("### ${c.id} — ${c.title}: ${if (c.pass) "PASS" else "**FAIL**"}\n\n")
            for ((k, d) in c.details) md.append("- $k: $d\n")
            md.append("\n")
            println("${sr} ${c.id} ${if (c.pass) "PASS" else "FAIL"} ${c.title}")
        }
    }
    md.insert(0, "# G1 validation results (full counts)\n\nFailures: $failures\n\n")
    File(out, "results.md").writeText(md.toString())

    // ---- example signals for the figures (pitch 4 kHz: active B4, sham B6; same random draws) ----
    val row = RippleBandTable.forPitch(4000.0)
    for ((tag, cfg) in listOf(
        "active" to RippleConfig(row.active),
        "sham" to RippleConfig(row.sham),
        "active_M45" to RippleConfig(row.active, hearingMaxDb = 45.0),
    )) {
        val seq = RippleSequence(cfg, 2026)
        val segs = List(3) { seq.next() }
        writeWav(File(out, "$tag.wav"), segs.flatMap { it.samples.asList() }.toFloatArray(), cfg.sampleRate)
        // envelope matrix of the first segment: every harmonic, 20 ms steps
        val s = segs[0]
        val t = RippleMetrics.times(0.0, 4.0, 0.02)
        val sb = StringBuilder("harmonic_hz,in_band," + t.joinToString(",") { "%.2f".format(it) } + "\n")
        for (h in RippleGenerator.harmonicRange(s.params.f0)) {
            val e = RippleMetrics.envelope(s.samples, s.sampleRate, h * s.params.f0, s.params.f0, t)
            sb.append("${h * s.params.f0},${cfg.band.contains(h * s.params.f0)},").append(e.joinToString(",") { "%.6g".format(it / s.gain) }).append("\n")
        }
        File(out, "${tag}_envelopes.csv").writeText(sb.toString())
        File(out, "${tag}_params.txt").writeText(segs.joinToString("\n") { "f0=${it.params.f0} q=${it.params.q} p=${it.params.p} harmonics=${it.params.firstHarmonic}-${it.params.lastHarmonic}" } +
            "\nband=${cfg.band} ${cfg.band.lowHz}-${cfg.band.highHz} centre=${cfg.band.centreHz} log2c=${log2(cfg.band.centreHz)}\n")
    }
    println("failures=$failures")
}

private fun writeWav(f: File, x: FloatArray, sr: Int) {
    val data = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in x) data.putFloat(v)
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()); h.putInt(36 + x.size * 4); h.put("WAVE".toByteArray())
    h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(3); h.putShort(1); h.putInt(sr); h.putInt(sr * 4); h.putShort(4); h.putShort(32)
    h.put("data".toByteArray()); h.putInt(x.size * 4)
    f.writeBytes(h.array() + data.array())
}
