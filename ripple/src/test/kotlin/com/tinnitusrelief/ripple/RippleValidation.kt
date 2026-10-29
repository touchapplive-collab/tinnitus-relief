package com.tinnitusrelief.ripple

import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Validation checks V1–V13 and V15 (signal part) of docs/PHASE1-RIPPLE-SPEC.md, section 6.
 * `full = true` uses the counts stated in the spec (used for the G1 report); `full = false` is a faster
 * subset for every CI build.
 */
class RippleValidation(private val full: Boolean, private val sampleRate: Int = 48_000) {

    data class Check(val id: String, val title: String, val pass: Boolean, val details: List<Pair<String, String>>)

    /** Optional sink for report data (plots); null in unit tests. */
    var dump: ((String, String) -> Unit)? = null

    private fun f(v: Double, d: Int = 3) = "%.${d}f".format(v)
    private val mid = RippleMetrics.times(1.05, 2.95, 0.01)

    /** Reference for [P] eq. 2 and 5, written independently of RippleGenerator (literal values from [P]). */
    private fun aRef(t: Double, fHz: Double, band: RippleBand, q: Double, p: Double): Double {
        val c = sqrt(band.lowHz * band.highHz)
        val s = 4.5 + 3.0 * sin(p + 2 * PI * 0.125 * t)
        return 1 + 1.0 * sin(2 * PI * (1.0 * t + log2(fHz / c) * s) + q)
    }

    private fun paramsFor(f0: Double, seed: Long): SegmentParams {
        val rng = SplittableRandom(seed)
        val range = RippleGenerator.harmonicRange(f0)
        return SegmentParams(f0, rng.nextDouble() * 2 * PI, rng.nextDouble() * 2 * PI, range.first,
            DoubleArray(range.last - range.first + 1) { rng.nextDouble() * 2 * PI })
    }

    private fun cfg(band: RippleBand, mod: RippleModulation = RippleModulation.AMPLITUDE, m: Double = 0.0, phaseRandom: Boolean = true) =
        RippleConfig(band, mod, m, sampleRate, randomStartPhase = phaseRandom)

    // ---------------------------------------------------------------- V1
    fun v1(): Check {
        val n = 1 shl 18
        val det = mutableListOf<Pair<String, String>>()
        var ok = true
        for (f0 in listOf(96.0, 176.0, 256.0)) {
            val seg = RippleGenerator.render(cfg(RippleBand.B5, RippleModulation.NONE), paramsFor(f0, 11))
            val ps = RippleMetrics.powerSpectrum(seg.samples, n)
            val total = ps.sum()
            val range = RippleGenerator.harmonicRange(f0)
            var near = 0.0; var worstBin = 0
            for (h in range) {
                val fh = h * f0
                val bExp = (fh * n / sampleRate).roundToInt()
                var bMax = bExp
                for (b in bExp - 3..bExp + 3) if (ps[b] > ps[bMax]) bMax = b
                worstBin = max(worstBin, abs(bMax - bExp))
                near += RippleMetrics.bandPower(ps, n, sampleRate, fh - 5, fh + 5)
            }
            val outside = RippleMetrics.bandPower(ps, n, sampleRate, 0.0, 950.0) + RippleMetrics.bandPower(ps, n, sampleRate, 16050.0, sampleRate / 2.0)
            val outsideDb = 10 * log10(outside / total)
            val otherDb = 10 * log10(max(1e-30, (total - near) / total))
            val expectedCount = (16000.0 / f0).toInt() - kotlin.math.ceil(1000.0 / f0 - 1e-9).toInt() + 1
            val pass = worstBin <= 1 && outsideDb < -60 && otherDb < -40 && range.count() == expectedCount
            ok = ok && pass
            det += "f0 ${f0.toInt()} Hz" to "harmonics ${range.first}–${range.last} (${range.count()}, expected $expectedCount); worst peak offset $worstBin bin; energy outside 1–16 kHz ${f(outsideDb, 1)} dB; energy not at harmonics ${f(otherDb, 1)} dB"
        }
        return Check("V1", "Harmonic content", ok, det)
    }

    // ---------------------------------------------------------------- V2
    fun v2(): Check {
        val det = mutableListOf<Pair<String, String>>(); var ok = true
        for (f0 in listOf(96.0, 176.0, 256.0)) {
            val c = cfg(RippleBand.B5, RippleModulation.NONE)
            val seg = RippleGenerator.render(c, paramsFor(f0, 12))
            val db = RippleGenerator.harmonicRange(f0).map { h -> 20 * log10(RippleMetrics.relativeEnvelope(seg, c, h, doubleArrayOf(2.0))[0]) }
            val worst = db.maxOf { abs(it) }
            ok = ok && worst <= 0.25
            det += "f0 ${f0.toInt()} Hz" to "harmonic levels within ±${f(worst, 4)} dB"
        }
        return Check("V2", "Flat spectrum (M = 0)", ok, det)
    }

    // ---------------------------------------------------------------- V3
    /** Reference curve re-typed from [P]: 0 ≤ 2 kHz, 1/9 at 2.8 kHz, 8/9 at 8 kHz, maximum above 8 kHz. */
    private fun refWeight(fHz: Double): Double {
        if (fHz <= 2000) return 0.0
        if (fHz > 8000) return 1.0
        val pts = listOf(2000.0 to 0.0, 2828.4271247 to 1.0 / 9, 8000.0 to 8.0 / 9)
        for (i in 0 until pts.size - 1) if (fHz <= pts[i + 1].first) {
            val (a, wa) = pts[i]; val (b, wb) = pts[i + 1]
            return wa + (wb - wa) * log2(fHz / a) / log2(b / a)
        }
        return 1.0
    }

    fun v3(): Check {
        val det = mutableListOf<Pair<String, String>>(); var ok = true
        val f0 = 128.0
        val probes = listOf(1000.0, 2000.0, 2800.0, 4000.0, 8000.0, 11000.0, 16000.0)
        for (m in listOf(15.0, 30.0, 45.0)) {
            val c = cfg(RippleBand.B5, RippleModulation.NONE, m)
            val seg = RippleGenerator.render(c, paramsFor(f0, 13))
            val raw = { h: Int -> RippleMetrics.envelope(seg.samples, sampleRate, h * f0, f0, doubleArrayOf(2.0))[0] / seg.gain }
            val ref1k = raw((1000.0 / f0).roundToInt().coerceAtLeast(8))
            var worst = 0.0
            val parts = mutableListOf<String>()
            for (pf in probes) {
                val h = (pf / f0).roundToInt().coerceIn(RippleGenerator.harmonicRange(f0))
                val meas = 20 * log10(raw(h) / ref1k)
                val exp = m * refWeight(h * f0) - m * refWeight((1000.0 / f0).roundToInt().coerceAtLeast(8) * f0)
                worst = max(worst, abs(meas - exp))
                parts += "${f(h * f0 / 1000, 2)} kHz ${f(meas, 2)}/${f(exp, 2)}"
            }
            val rmsErr = abs(RippleMetrics.rmsDb(seg.samples) - RippleSpec.TARGET_RMS_DBFS)
            ok = ok && worst <= 0.5 && rmsErr <= 0.1
            det += "M = ${m.toInt()} dB" to "measured/expected dB re 1 kHz: ${parts.joinToString("; ")}; worst error ${f(worst, 3)} dB; total RMS error ${f(rmsErr, 4)} dB"
        }
        val curveOk = listOf(1000.0 to 0.0, 2000.0 to 0.0, 2828.4271247 to 1.0 / 9, 8000.0 to 8.0 / 9, 8000.5 to 1.0, 9000.0 to 1.0, 16000.0 to 1.0)
            .all { (fh, w) -> abs(HearingSlope.weight(fh) - w) < 1e-6 }
        ok = ok && curveOk
        det += "curve anchor points" to if (curveOk) "0 at ≤2 kHz, 1/9 at 2.83 kHz, 8/9 at 8 kHz, 1 above 8 kHz: exact" else "MISMATCH"
        return Check("V3", "Hearing correction", ok, det)
    }

    // ---------------------------------------------------------------- V4
    fun v4(): Check {
        // [P] Table 1, typed here independently of RippleBandTable: A1, A2, C1, C2 per match tone (0 = none)
        val expected = listOf(
            intArrayOf(1, 0, 3, 0), intArrayOf(1, 0, 3, 0), intArrayOf(1, 0, 3, 0), intArrayOf(2, 1, 4, 0),
            intArrayOf(2, 0, 4, 0), intArrayOf(3, 2, 5, 0), intArrayOf(3, 0, 5, 0), intArrayOf(4, 3, 6, 0),
            intArrayOf(4, 0, 6, 0), intArrayOf(5, 4, 3, 2), intArrayOf(5, 0, 3, 0), intArrayOf(6, 5, 4, 3),
            intArrayOf(6, 0, 4, 0), intArrayOf(6, 0, 4, 0), intArrayOf(7, 0, 5, 0), intArrayOf(7, 0, 5, 0), intArrayOf(7, 0, 5, 0),
        )
        val labels = listOf(1.0, 1.2, 1.4, 1.7, 2.0, 2.4, 2.8, 3.4, 4.0, 4.8, 5.7, 6.7, 8.0, 9.5, 11.0, 13.0, 16.0)
        var mismatches = 0
        RippleBandTable.ROWS.forEachIndexed { i, r ->
            val got = intArrayOf(r.active.number, r.activeAlt?.number ?: 0, r.sham.number, r.shamAlt?.number ?: 0)
            if (!got.contentEquals(expected[i])) mismatches++
            if (abs(r.matchHz / 1000 - labels[i]) / labels[i] > 0.04) mismatches++ // printed labels are rounded (13.45 kHz → "13")
        }
        val edgesOk = RippleBand.entries.all { abs(it.lowHz - 1000 * 2.0.pow((it.number - 1) / 2.0)) < 1e-9 && abs(it.highHz / it.lowHz - 2) < 1e-12 }
        val nearestOk = RippleBandTable.forPitch(4100.0).active == RippleBand.B4 && RippleBandTable.forPitch(15000.0).active == RippleBand.B7
        val ok = mismatches == 0 && RippleBandTable.ROWS.size == 17 && edgesOk && nearestOk
        return Check("V4", "Band table", ok, listOf(
            "rows" to "${RippleBandTable.ROWS.size} rows, $mismatches mismatches against [P] Table 1",
            "band edges" to if (edgesOk) "1000·2^(k/2) Hz, octave wide: exact" else "MISMATCH",
            "nearest-tone lookup" to if (nearestOk) "4.1 kHz → B4, 15 kHz → B7" else "MISMATCH",
        ))
    }

    // ---------------------------------------------------------------- V5 + V6a
    fun v5v6a(): List<Check> {
        val segs = if (full) 20 else 6
        val seq = RippleSequence(cfg(RippleBand.B5), 21)
        val seq7 = RippleSequence(cfg(RippleBand.B7), 22)
        var gMax = 0.0; var gMin = 9.0; var sum = 0.0; var count = 0; var fullRange = 0; var harmonics = 0
        var worstRms = 0.0
        for (k in 0 until segs) {
            for ((seg, band) in listOf(seq.next() to RippleBand.B5, seq7.next() to RippleBand.B7)) {
                val c = cfg(band)
                for (h in RippleGenerator.harmonicRange(seg.params.f0)) {
                    val fh = h * seg.params.f0
                    if (!band.contains(fh)) continue
                    val e = RippleMetrics.relativeEnvelope(seg, c, h, mid)
                    var se = 0.0
                    for (i in mid.indices) {
                        val r = aRef(mid[i], fh, band, seg.params.q, seg.params.p)
                        se += (e[i] - r) * (e[i] - r)
                        sum += e[i]; count++
                    }
                    worstRms = max(worstRms, sqrt(se / mid.size))
                    gMax = max(gMax, e.max()); gMin = min(gMin, e.min())
                    harmonics++; if (e.max() > 1.95 && e.min() < 0.05) fullRange++
                }
            }
        }
        val mean = sum / count
        val v5 = Check("V5", "AM depth (in band)", gMax in 1.98..2.02 && gMin <= 0.02 && abs(mean - 1) <= 0.02, listOf(
            "segments" to "${segs * 2} (bands B5 and B7), $harmonics in-band harmonic tracks",
            "envelope range" to "min ${f(gMin, 4)}, max ${f(gMax, 4)} (expected 0 and 2, d = 1)",
            "pooled mean" to "${f(mean, 4)} (expected 1 ± 0.02)",
            "tracks spanning the full 0–2 range in the 1.9 s window" to "$fullRange of $harmonics (not required: near band edges F·S(t) can cancel ωt for a while)",
            "1 Hz temporal rate" to "verified in V6b from the ripple phase",
        ))
        val v6a = Check("V6a", "Envelopes follow [P] eq. 2 and 5", worstRms <= 0.01, listOf(
            "worst RMS error" to "${f(worstRms, 5)} (limit 0.01) over $harmonics in-band harmonic tracks",
        ))
        return listOf(v5, v6a)
    }

    // ---------------------------------------------------------------- V6b + V6c
    fun v6bc(): List<Check> {
        val segs = if (full) 200 else 24
        val band = RippleBand.B7
        val c = cfg(band)
        val seq = RippleSequence(c, 31)
        val tt = RippleMetrics.times(1.05, 2.95, 0.05)
        var minExpl = 1.0; var worstOmega = 0.0; var sLo = 99.0; var sHi = -99.0; var maxRate = 0.0
        val foldPhi = ArrayList<Double>(); val foldS = ArrayList<Double>()
        val sb = StringBuilder("segment,t,cycle_phase,S_hat,S_true\n")
        repeat(segs) { k ->
            val seg = seq.next()
            val hs = RippleGenerator.harmonicRange(seg.params.f0).filter { band.contains(it * seg.params.f0) }
            check(hs.first() >= 22) { "undersampled band" }
            val fs = DoubleArray(hs.size) { log2(hs[it] * seg.params.f0 / band.centreHz) }
            val envs = hs.map { RippleMetrics.relativeEnvelope(seg, c, it, tt) }
            val sHat = DoubleArray(tt.size); val th = DoubleArray(tt.size)
            for (i in tt.indices) {
                val ys = DoubleArray(hs.size) { envs[it][i] - 1 }
                val fit = RippleMetrics.fitRipple(fs, ys)
                sHat[i] = fit.density; th[i] = fit.phase
                minExpl = min(minExpl, fit.explained)
                val phi = seg.params.p + 2 * PI * 0.125 * tt[i]
                foldPhi += phi; foldS += fit.density
                if (k < 40) sb.append("$k,${tt[i]},${phi % (2 * PI)},${fit.density},${4.5 + 3 * sin(phi)}\n")
            }
            val omega = RippleMetrics.slope(tt, RippleMetrics.unwrap(th)) / (2 * PI)
            worstOmega = max(worstOmega, abs(omega - 1))
            sLo = min(sLo, sHat.min()); sHi = max(sHi, sHat.max())
            for (i in 1 until tt.size) maxRate = max(maxRate, abs(sHat[i] - sHat[i - 1]) / (tt[i] - tt[i - 1]))
        }
        dump?.invoke("v6_fold.csv", sb.toString())
        val (mu, amp, r2) = RippleMetrics.fitSinusoid(foldPhi.toDoubleArray(), foldS.toDoubleArray())
        val limitRate = 2 * PI * 0.125 * 3 * 1.1
        val b = Check("V6b", "Blind ripple density and temporal rate", minExpl >= 0.95 && worstOmega <= 0.05 && sLo >= 1.4 && sHi <= 7.6 && maxRate <= limitRate, listOf(
            "segments" to "$segs (band B7, 8–16 kHz, ≥ 31 harmonics per octave), fits every 50 ms in 1.05–2.95 s",
            "worst explained variance" to f(minExpl, 4) + " (limit 0.95)",
            "temporal rate ω" to "worst |ω̂ − 1 Hz| = ${f(worstOmega, 4)} Hz (limit 0.05)",
            "measured density range" to "${f(sLo, 3)} – ${f(sHi, 3)} cyc/oct (published 1.5–7.5)",
            "max rate of change" to "${f(maxRate, 3)} cyc/oct/s (limit ${f(limitRate, 2)})",
        ))
        val cc = Check("V6c", "8 s spectral-rate cycle (folded across segments)", abs(mu - 4.5) <= 0.1 && abs(amp - 3) <= 0.1 && r2 >= 0.98, listOf(
            "pooled points" to "${foldS.size} from $segs half-cycle segments, placed at cycle phase p + 2πνt (recorded p)",
            "fitted mean μ" to "${f(mu, 4)} (published 4.5)",
            "fitted extent r" to "${f(amp, 4)} (published 3)",
            "r²" to f(r2, 5),
        ))
        return listOf(b, cc)
    }

    // ---------------------------------------------------------------- V7
    fun v7(): Check {
        val det = mutableListOf<Pair<String, String>>(); var ok = true
        val tModu = RippleMetrics.times(1.0, 3.0, 0.01) // 100 Hz envelope rate, 2 s
        for ((f0, band) in listOf(96.0 to RippleBand.B3, 176.0 to RippleBand.B5, 256.0 to RippleBand.B1, 143.0 to RippleBand.B7)) {
            val c = cfg(band)
            val seg = RippleGenerator.render(c, paramsFor(f0, 41))
            var worstIdx = 0.0; var worstSpec = -999.0; var tracks = 0
            var edgeOk = true
            val range = RippleGenerator.harmonicRange(f0)
            for (h in range) {
                val fh = h * f0
                val e = RippleMetrics.relativeEnvelope(seg, c, h, tModu)
                if (band.contains(fh)) continue
                tracks++
                worstIdx = max(worstIdx, (e.max() - e.min()) / (e.max() + e.min()))
                val m = e.average()
                // modulation spectrum 0.5–20 Hz (2 s window → 0.5 Hz resolution)
                var k = 1
                while (k * 0.5 <= 20.0) {
                    var re = 0.0; var im = 0.0
                    for (i in e.indices) { val a = 2 * PI * k * 0.5 * tModu[i]; re += (e[i] - m) * cos(a); im += (e[i] - m) * sin(a) }
                    val amp = 2 * sqrt(re * re + im * im) / e.size
                    worstSpec = max(worstSpec, 20 * log10(max(amp, 1e-15) / m))
                    k++
                }
            }
            // boundary harmonics
            val inside = range.filter { band.contains(it * f0) }
            val firstIn = inside.first(); val lastIn = inside.last()
            val below = firstIn - 1; val above = lastIn + 1
            fun span(h: Int): Double { val e = RippleMetrics.relativeEnvelope(seg, c, h, mid); return e.max() - e.min() }
            if (span(firstIn) < 0.5 || span(lastIn) < 0.5) edgeOk = false
            if (below in range && span(below) > 0.004) edgeOk = false
            if (above in range && span(above) > 0.004) edgeOk = false
            val pass = worstIdx < 0.002 && worstSpec <= -60 && edgeOk
            ok = ok && pass
            det += "f0 ${f0.toInt()} Hz, band ${band.name}" to "$tracks out-of-band harmonics: worst modulation index ${"%.1e".format(worstIdx)} (limit 2e-3), worst modulation-spectrum line ${f(worstSpec, 1)} dB re mean (limit −60); edge harmonics ${firstIn}/${lastIn} modulated, ${below}/${above} not: ${if (edgeOk) "yes" else "NO"}"
        }
        return Check("V7", "No modulation outside the band; band edges", ok, det)
    }

    // ---------------------------------------------------------------- V8
    fun v8(): Check {
        val segs = if (full) 100 else 12
        val pitch = 4000.0
        val row = RippleBandTable.forPitch(pitch)
        val ca = cfg(row.active); val cs = cfg(row.sham)
        val ra = SplittableRandom(51); val rs = SplittableRandom(51)
        val n = 1 shl 18
        val centres = (0..12).map { 1000.0 * 2.0.pow(it / 3.0) }
        val measA = DoubleArray(13); val measS = DoubleArray(13); val predA = DoubleArray(13); val predS = DoubleArray(13)
        var worstRms = 0.0
        val tGrid = RippleMetrics.times(0.005, 3.995, 0.01)
        repeat(segs) {
            val pa = RippleGenerator.drawParams(ra); val pz = RippleGenerator.drawParams(rs)
            check(pa == pz || pa.f0 == pz.f0)
            val a = RippleGenerator.render(ca, pa); val s = RippleGenerator.render(cs, pz)
            worstRms = max(worstRms, abs(RippleMetrics.rmsDb(a.samples) - RippleMetrics.rmsDb(s.samples)))
            val psa = RippleMetrics.powerSpectrum(a.samples, n); val pss = RippleMetrics.powerSpectrum(s.samples, n)
            for (b in 0..12) {
                val lo = centres[b] * 2.0.pow(-1.0 / 6); val hi = centres[b] * 2.0.pow(1.0 / 6)
                measA[b] += RippleMetrics.bandPower(psa, n, sampleRate, lo, hi)
                measS[b] += RippleMetrics.bandPower(pss, n, sampleRate, lo, hi)
                for (h in RippleGenerator.harmonicRange(pa.f0)) {
                    val fh = h * pa.f0
                    if (fh < lo || fh >= hi) continue
                    fun pw(cfgBand: RippleBand, seg: RippleSegment): Double {
                        var e = 0.0
                        for (t in tGrid) {
                            val am = if (cfgBand.contains(fh)) aRef(t, fh, cfgBand, pa.q, pa.p) else 1.0
                            val r = RippleGenerator.ramp(t); e += am * am * r * r
                        }
                        return seg.gain * seg.gain * e
                    }
                    predA[b] += pw(row.active, a); predS[b] += pw(row.sham, s)
                }
            }
        }
        val sb = StringBuilder("centre_hz,active_minus_sham_measured_db,active_minus_sham_predicted_db\n")
        var worst = 0.0; var maxIn = -99.0; var maxOut = 0.0
        for (b in 0..12) {
            val md = 10 * log10(measA[b] / measS[b]); val pd = 10 * log10(predA[b] / predS[b])
            worst = max(worst, abs(md - pd)); sb.append("${centres[b]},$md,$pd\n")
            val inEither = row.active.contains(centres[b]) || row.sham.contains(centres[b])
            if (inEither) maxIn = max(maxIn, abs(md)) else maxOut = max(maxOut, abs(md))
        }
        dump?.invoke("v8_third_octave.csv", sb.toString())
        return Check("V8", "Active vs sham equivalence", worst <= 0.25 && worstRms <= 0.05, listOf(
            "configuration" to "pitch 4 kHz → active ${row.active.name}, sham ${row.sham.name}; $segs segments, identical random draws",
            "total RMS difference" to "${f(worstRms, 4)} dB (limit 0.05)",
            "⅓-octave difference, measured vs predicted" to "worst deviation ${f(worst, 3)} dB (limit 0.25)",
            "largest |active − sham| outside both bands" to "${f(maxOut, 2)} dB (renormalisation to equal total RMS; predicted)",
            "largest |active − sham| in a modulated band" to "${f(maxIn, 2)} dB",
        ))
    }

    // ---------------------------------------------------------------- V9
    fun v9(): Check {
        val c = cfg(RippleBand.B5, RippleModulation.NONE)
        val f0 = 150.0
        val seg = RippleGenerator.render(c, paramsFor(f0, 61))
        val h = 10 // 1.5 kHz, outside B5
        val plateau = RippleMetrics.envelope(seg.samples, sampleRate, h * f0, f0, doubleArrayOf(2.0))[0]
        val tOn = RippleMetrics.times(0.02, 0.98, 0.01); val tOff = RippleMetrics.times(3.02, 3.98, 0.01)
        var worst = 0.0
        for (tt in listOf(tOn, tOff)) {
            val e = RippleMetrics.envelope(seg.samples, sampleRate, h * f0, f0, tt)
            for (i in tt.indices) worst = max(worst, abs(e[i] / plateau - RippleGenerator.ramp(tt[i])))
        }
        // joins
        val seq = RippleSequence(cfg(RippleBand.B5), 62)
        val a = seq.next().samples; val b = seq.next().samples
        val joined = a + b
        var jump = 0.0
        for (i in a.size - 100 until a.size + 100) jump = max(jump, abs(joined[i + 1] - joined[i]).toDouble())
        var within = 0.0
        for (i in sampleRate until 3 * sampleRate) within = max(within, abs(a[i + 1] - a[i]).toDouble())
        val ends = max(abs(a.first().toDouble()), abs(a.last().toDouble()))
        val ok = worst < 0.01 && jump <= within
        return Check("V9", "Ramps and joins", ok, listOf(
            "ramp shape" to "worst deviation from 1 s raised cosine ${"%.2e".format(worst)} of full scale (limit 1%)",
            "segment join" to "largest sample step at the join ${"%.2e".format(jump)} vs ${"%.2e".format(within)} inside a segment; end samples ${"%.1e".format(ends)}",
        ))
    }

    // ---------------------------------------------------------------- V10 + V11 + real-time
    fun v10v11(): List<Check> {
        val segs = if (full) RippleSpec.SEGMENTS_PER_HOUR else 30
        val det10 = mutableListOf<Pair<String, String>>(); var ok10 = true
        var worstRmsDev = 0.0
        val crestSb = StringBuilder("config,segment,peak_dbfs,crest_db\n")
        var renderMs = 0L; var audioS = 0.0
        for ((name, c) in listOf("B5, M = 0" to cfg(RippleBand.B5), "B7, M = 45 dB" to cfg(RippleBand.B7, m = 45.0))) {
            val seq = RippleSequence(c, 71)
            var peak = -999.0; var clipped = 0; var nonFinite = 0; var crestMax = 0.0; var crestSum = 0.0
            repeat(segs) { k ->
                val t0 = System.nanoTime(); val seg = seq.next(); renderMs += (System.nanoTime() - t0) / 1_000_000; audioS += 4.0
                val p = RippleMetrics.peakDb(seg.samples); val r = RippleMetrics.rmsDb(seg.samples)
                for (v in seg.samples) { if (!v.isFinite()) nonFinite++ else if (abs(v) >= 1f) clipped++ }
                peak = max(peak, p); crestMax = max(crestMax, p - r); crestSum += p - r
                worstRmsDev = max(worstRmsDev, abs(r - RippleSpec.TARGET_RMS_DBFS))
                crestSb.append("$name,$k,$p,${p - r}\n")
            }
            val pass = peak <= -6.0 && clipped == 0 && nonFinite == 0
            ok10 = ok10 && pass
            det10 += name to "${segs} segments (${f(segs * 4 / 60.0, 1)} min): max peak ${f(peak, 2)} dBFS (limit −6), clipped $clipped, NaN/Inf $nonFinite, crest factor mean ${f(crestSum / segs, 2)} dB / max ${f(crestMax, 2)} dB"
        }
        // alternative discussed in spec uncertainty 1: all harmonics starting in phase (report only)
        val alt = RippleSequence(cfg(RippleBand.B5, phaseRandom = false), 72)
        var altMax = -999.0; var altCrest = 0.0
        repeat(20) { val s = alt.next(); val p = RippleMetrics.peakDb(s.samples); altMax = max(altMax, p); altCrest = max(altCrest, p - RippleMetrics.rmsDb(s.samples)) }
        det10 += "for comparison only: zero starting phases (not [P])" to "20 segments: max peak ${f(altMax, 2)} dBFS, crest factor max ${f(altCrest, 2)} dB at the same RMS"
        det10 += "limiter" to "Layer B gain ≤ −6 dB and the limiter ceiling is −1 dBFS, so with peaks ≤ −6 dBFS the limiter cannot engage"
        dump?.invoke("v10_crest.csv", crestSb.toString())
        val v11 = Check("V11", "Level stability", worstRmsDev <= 0.5, listOf("segment RMS" to "worst deviation from −30 dBFS: ${"%.1e".format(worstRmsDev)} dB over ${2 * segs} segments (limit 0.5)"))
        val rt = renderMs / 1000.0 / audioS
        det10 += "JVM render speed (not V14)" to "${f(rt * 100, 1)}% of real time on this build machine; the phone measurement is V14 (G3)"
        return listOf(Check("V10", "Peaks and clipping", ok10, det10), v11)
    }

    // ---------------------------------------------------------------- V12
    fun v12(): Check {
        val rng = SplittableRandom(81)
        val draws = 10_000
        val f0 = DoubleArray(draws); val q = DoubleArray(draws); val p = DoubleArray(draws); val ph = DoubleArray(draws)
        for (i in 0 until draws) { val s = RippleGenerator.drawParams(rng); f0[i] = s.f0; q[i] = s.q; p[i] = s.p; ph[i] = s.startPhases[0] }
        val tests = listOf("f0" to RippleMetrics.ksUniform(f0, 96.0, 256.0), "q" to RippleMetrics.ksUniform(q, 0.0, 2 * PI),
            "p" to RippleMetrics.ksUniform(p, 0.0, 2 * PI), "φ (first harmonic)" to RippleMetrics.ksUniform(ph, 0.0, 2 * PI))
        val c = cfg(RippleBand.B4)
        val s1 = RippleSequence(c, 99); val s2 = RippleSequence(c, 99); val s3 = RippleSequence(c, 100)
        var same = true; var differs = false
        repeat(3) { val a = s1.next().samples; val b = s2.next().samples; val d = s3.next().samples; if (!a.contentEquals(b)) same = false; if (!a.contentEquals(d)) differs = true }
        val ok = tests.all { it.second.second > 0.01 } && same && differs
        return Check("V12", "Randomness and determinism", ok, tests.map { (n, r) -> n to "KS D = ${f(r.first, 4)}, p = ${f(r.second, 3)} ($draws draws)" } +
            listOf("determinism" to "same seed → identical samples: $same; different seed → different: $differs"))
    }

    // ---------------------------------------------------------------- negative controls
    /**
     * Shows that the measurements can fail: each control injects a known fault and the corresponding criterion
     * must detect it. A control "passes" when the fault is detected.
     */
    fun negativeControls(): Check {
        val det = mutableListOf<Pair<String, String>>(); var ok = true
        val f0 = 176.0
        val c = cfg(RippleBand.B5)
        val seg = RippleGenerator.render(c, paramsFor(f0, 91))
        // NC1: 0.5 % amplitude modulation (1 Hz) added to one out-of-band harmonic (h = 6, 1056 Hz)
        run {
            val x = seg.samples.copyOf(); val h = 6; val amp = seg.gain * 1.0
            for (i in x.indices) { val t = i.toDouble() / sampleRate
                x[i] += (amp * 0.005 * sin(2 * PI * 1.0 * t) * sin(2 * PI * h * f0 * t + seg.params.startPhases[h - seg.params.firstHarmonic]) * RippleGenerator.ramp(t)).toFloat() }
            val e = RippleMetrics.envelope(x, sampleRate, h * f0, f0, RippleMetrics.times(1.0, 3.0, 0.01))
            val idx = (e.max() - e.min()) / (e.max() + e.min())
            val detected = idx >= 0.002; ok = ok && detected
            det += "NC1 0.5% AM on an out-of-band harmonic" to "V7 index ${"%.1e".format(idx)} → ${if (detected) "detected" else "MISSED"}"
        }
        // NC2: −50 dB tone at 500 Hz (outside 1–16 kHz)
        run {
            val x = seg.samples.copyOf(); val a = seg.gain * sqrt(seg.params.startPhases.size.toDouble()) * 10.0.pow(-50 / 20.0)
            for (i in x.indices) x[i] += (a * sin(2 * PI * 500.0 * i / sampleRate)).toFloat()
            val n = 1 shl 18; val ps = RippleMetrics.powerSpectrum(x, n)
            val outDb = 10 * log10(RippleMetrics.bandPower(ps, n, sampleRate, 0.0, 950.0) / ps.sum())
            val detected = outDb >= -60; ok = ok && detected
            det += "NC2 −50 dB tone at 500 Hz" to "V1 out-of-range energy ${f(outDb, 1)} dB → ${if (detected) "detected" else "MISSED"}"
        }
        // NC3: reference with a wrong mean spectral rate (μ = 4.6 instead of 4.5)
        run {
            var worst = 0.0
            for (h in RippleGenerator.harmonicRange(f0)) {
                val fh = h * f0; if (!c.band.contains(fh)) continue
                val e = RippleMetrics.relativeEnvelope(seg, c, h, mid)
                var se = 0.0
                for (i in mid.indices) {
                    val cc = sqrt(c.band.lowHz * c.band.highHz); val sv = 4.6 + 3.0 * sin(seg.params.p + 2 * PI * 0.125 * mid[i])
                    val r = 1 + sin(2 * PI * (mid[i] + log2(fh / cc) * sv) + seg.params.q); se += (e[i] - r) * (e[i] - r)
                }
                worst = max(worst, sqrt(se / mid.size))
            }
            val detected = worst > 0.01; ok = ok && detected
            det += "NC3 μ off by 0.1 cyc/oct" to "V6a worst RMS error ${f(worst, 4)} → ${if (detected) "detected" else "MISSED"}"
        }
        // NC4: depth 0.9 instead of 1 (simulated by scaling the measured modulation)
        run {
            val h = RippleGenerator.harmonicRange(f0).first { c.band.contains(it * f0) } + 3
            val e = RippleMetrics.relativeEnvelope(seg, c, h, mid).map { 1 + 0.9 * (it - 1) }
            var se = 0.0
            for (i in mid.indices) { val r = aRef(mid[i], h * f0, c.band, seg.params.q, seg.params.p); se += (e[i] - r) * (e[i] - r) }
            val err = sqrt(se / mid.size); val detected = err > 0.01; ok = ok && detected
            det += "NC4 modulation depth 0.9" to "V6a RMS error ${f(err, 4)} → ${if (detected) "detected" else "MISSED"}"
        }
        return Check("NC", "Negative controls (faults must be detected)", ok, det)
    }
}
