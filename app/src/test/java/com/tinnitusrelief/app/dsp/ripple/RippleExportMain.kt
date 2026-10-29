package com.tinnitusrelief.app.dsp.ripple

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.SplittableRandom

/**
 * G2: exports rendered segments with every random draw, so an independent implementation of [P] can be
 * compared sample by sample. Output: <dir>/<case>.f32 (little-endian float32, mono) and <dir>/cases.json.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "ripple-export" }).apply { mkdirs() }
    data class Case(val name: String, val cfg: RippleConfig, val seed: Long)
    val cases = listOf(
        Case("b5_am_m0_48k", RippleConfig(RippleBand.B5), 1),
        Case("b5_am_m45_48k", RippleConfig(RippleBand.B5, hearingMaxDb = 45.0), 2),
        Case("b3_am_m15_48k", RippleConfig(RippleBand.B3, hearingMaxDb = 15.0), 3),
        Case("b1_am_m30_48k", RippleConfig(RippleBand.B1, hearingMaxDb = 30.0), 4),
        Case("b7_am_m0_48k", RippleConfig(RippleBand.B7), 5),
        Case("b4_none_m0_48k", RippleConfig(RippleBand.B4, RippleModulation.NONE), 6),
        Case("b6_am_m0_44k", RippleConfig(RippleBand.B6, sampleRate = 44_100), 7),
        Case("b2_am_m45_44k", RippleConfig(RippleBand.B2, hearingMaxDb = 45.0, sampleRate = 44_100), 8),
    )
    val json = StringBuilder("[\n")
    cases.forEachIndexed { i, c ->
        val p = RippleGenerator.drawParams(SplittableRandom(c.seed))
        val seg = RippleGenerator.render(c.cfg, p)
        val buf = ByteBuffer.allocate(seg.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in seg.samples) buf.putFloat(v)
        File(out, "${c.name}.f32").writeBytes(buf.array())
        json.append("  {\"name\": \"${c.name}\", \"sample_rate\": ${c.cfg.sampleRate}, \"samples\": ${seg.samples.size}, ")
            .append("\"modulated_band_hz\": [${c.cfg.band.lowHz}, ${c.cfg.band.highHz}], \"amplitude_modulation\": ${c.cfg.modulation == RippleModulation.AMPLITUDE}, ")
            .append("\"hearing_max_db\": ${c.cfg.hearingMaxDb}, \"target_rms_dbfs\": ${c.cfg.targetRmsDbfs}, ")
            .append("\"f0\": ${p.f0}, \"q\": ${p.q}, \"p\": ${p.p}, \"first_harmonic\": ${p.firstHarmonic}, ")
            .append("\"start_phases\": [${p.startPhases.joinToString(", ")}]}")
            .append(if (i < cases.size - 1) ",\n" else "\n")
    }
    json.append("]\n")
    File(out, "cases.json").writeText(json.toString())
    println("exported ${cases.size} cases")
}
