package com.tinnitusrelief.app.dsp

import com.tinnitusrelief.app.audio.Voice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

// =====================================================================================================
//  Sound personalization ("Find your perfect sound").
//
//  A deliberately small, broad equaliser: four fixed, gentle regions that together describe the overall
//  balance of a sound (deep, body, presence, air). The adaptive search moves only along a few broad
//  directions and every result is loudness-matched to the original, so the listener compares balance,
//  not level. Nothing here is a treatment or a diagnosis; it optimises a subjective preference.
// =====================================================================================================

/** The four broad regions. Fixed centres and wide filters (≈2 octaves) avoid narrow, harsh peaks. */
object EqBands {
    const val COUNT = 4
    /** 0 = low shelf (depth), 1 = low-mid peak (body), 2 = presence peak, 3 = high shelf (air). */
    val centersHz = doubleArrayOf(200.0, 700.0, 2500.0, 7000.0)
    /** Region edges used by the analysis (Hz). */
    val edgesHz = doubleArrayOf(20.0, 400.0, 1400.0, 4500.0, 20000.0)
    const val PEAK_Q = 0.7
    const val SHELF_SLOPE = 0.8
    /** Hard limits per region; the algorithm never goes beyond them. */
    const val MIN_DB = -8.0
    const val MAX_DB = 6.0
    /** Make-up gain limits for loudness matching. */
    const val MAKEUP_MIN_DB = -12.0
    const val MAKEUP_MAX_DB = 6.0

    fun configure(b: Array<Biquad>, sampleRate: Int, gainsDb: DoubleArray) {
        b[0].set(Biquad.Type.LOWSHELF, sampleRate, centersHz[0], SHELF_SLOPE, gainsDb[0])
        b[1].set(Biquad.Type.PEAK, sampleRate, centersHz[1], PEAK_Q, gainsDb[1])
        b[2].set(Biquad.Type.PEAK, sampleRate, centersHz[2], PEAK_Q, gainsDb[2])
        b[3].set(Biquad.Type.HIGHSHELF, sampleRate, centersHz[3], SHELF_SLOPE, gainsDb[3])
    }
}

/** A personalised balance: per-region gains in dB plus the loudness-matching make-up gain. */
data class EqProfile(val gainsDb: List<Double>, val makeupDb: Double = 0.0) {
    init { require(gainsDb.size == EqBands.COUNT) }

    val isFlat: Boolean get() = gainsDb.all { abs(it) < 1e-9 } && abs(makeupDb) < 1e-9

    fun encode(): String = (gainsDb + makeupDb).joinToString(",") { "%.3f".format(java.util.Locale.US, it) }

    companion object {
        val FLAT = EqProfile(List(EqBands.COUNT) { 0.0 })

        fun decode(s: String?): EqProfile? = runCatching {
            val v = s!!.split(',').map { it.trim().toDouble() }
            if (v.size != EqBands.COUNT + 1 || v.any { it.isNaN() || it.isInfinite() }) null
            else EqProfile(
                v.take(EqBands.COUNT).map { it.coerceIn(EqBands.MIN_DB, EqBands.MAX_DB) },
                v.last().coerceIn(EqBands.MAKEUP_MIN_DB, EqBands.MAKEUP_MAX_DB),
            )
        }.getOrNull()
    }
}

/** A sound's average spectrum in 1/6-octave bands. */
class Spectrum(val centersHz: DoubleArray, val power: DoubleArray) {

    /** Total power (dB, arbitrary reference) between [lo] and [hi] Hz. */
    fun regionDb(lo: Double, hi: Double): Double {
        var p = 0.0
        for (i in centersHz.indices) if (centersHz[i] >= lo && centersHz[i] < hi) p += power[i]
        return 10 * log10(p + 1e-30)
    }

    /** Centre of the 1/6-octave band with the most energy (the "dominant" region). */
    val dominantHz: Double get() = centersHz[power.indices.maxBy { power[it] }]

    /** Energy of each of the four regions, dB relative to the strongest region (≤ 0). */
    fun regionLevelsDb(): DoubleArray {
        val e = EqBands.edgesHz
        val r = DoubleArray(EqBands.COUNT) { regionDb(e[it], e[it + 1]) }
        val top = r.max()
        return DoubleArray(r.size) { r[it] - top }
    }

    /**
     * Regions that carry enough energy for a change there to be audible. A region more than
     * [floorDb] below the strongest one is left alone (boosting near-silence only adds hiss or nothing).
     */
    fun adjustable(floorDb: Double = 30.0): BooleanArray = regionLevelsDb().map { it > -floorDb }.toBooleanArray()
}

/** FFT analysis of a mono signal (Welch average, Hann window, 50 % overlap). Runs fully on the device. */
object SpectrumAnalyzer {
    fun analyze(mono: FloatArray, sampleRate: Int, fftSize: Int = 4096): Spectrum {
        require(mono.size >= fftSize)
        val fft = Fft(fftSize)
        val win = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / fftSize) }
        val bins = fftSize / 2 + 1
        val acc = DoubleArray(bins)
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        var frames = 0
        var start = 0
        while (start + fftSize <= mono.size) {
            for (i in 0 until fftSize) { re[i] = (mono[start + i] * win[i]).toFloat(); im[i] = 0f }
            fft.forward(re, im)
            for (k in 0 until bins) acc[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            frames++
            start += fftSize / 2
        }
        for (k in 0 until bins) acc[k] /= frames
        // group into 1/6-octave bands from 31.5 Hz up to just below Nyquist
        val centers = ArrayList<Double>()
        val powers = ArrayList<Double>()
        val nyq = sampleRate / 2.0
        var c = 31.5
        val half = 2.0.pow(1.0 / 12)
        while (c * half < nyq * 0.98) {
            val lo = c / half
            val hi = c * half
            var p = 0.0
            val k0 = (lo * fftSize / sampleRate).toInt().coerceAtLeast(1)
            val k1 = (hi * fftSize / sampleRate).toInt().coerceAtMost(bins - 1)
            for (k in k0..k1) p += acc[k]
            centers += c; powers += p
            c *= 2.0.pow(1.0 / 6)
        }
        return Spectrum(centers.toDoubleArray(), powers.toDoubleArray())
    }
}

/** Frequency response and loudness matching. */
object EqMath {

    /** Magnitude response (dB) of [gainsDb] at [f], without make-up. */
    fun responseDb(gainsDb: DoubleArray, f: Double, sampleRate: Int = 48000): Double {
        val b = Array(EqBands.COUNT) { Biquad() }
        EqBands.configure(b, sampleRate, gainsDb)
        var db = 0.0
        for (q in b) db += 20 * log10(q.magnitude(f, sampleRate))
        return db
    }

    /** IEC 61672 A-weighting in dB: an approximation of how loud each frequency sounds at moderate levels. */
    fun aWeightDb(f: Double): Double {
        val f2 = f * f
        val ra = (12194.0 * 12194.0 * f2 * f2) /
            ((f2 + 20.6 * 20.6) * sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194.0))
        return 20 * log10(ra) + 2.0
    }

    /**
     * Make-up gain (dB) that gives the equalised sound the same A-weighted loudness as the original.
     * Clamped to [EqBands.MAKEUP_MIN_DB, EqBands.MAKEUP_MAX_DB].
     */
    fun matchMakeupDb(spectrum: Spectrum, gainsDb: DoubleArray, sampleRate: Int = 48000): Double {
        val b = Array(EqBands.COUNT) { Biquad() }
        EqBands.configure(b, sampleRate, gainsDb)
        var before = 0.0
        var after = 0.0
        for (i in spectrum.centersHz.indices) {
            val f = spectrum.centersHz[i]
            if (f >= sampleRate * 0.49) continue
            val w = 10.0.pow(aWeightDb(f) / 10)
            var h2 = 1.0
            for (q in b) { val m = q.magnitude(f, sampleRate); h2 *= m * m }
            before += spectrum.power[i] * w
            after += spectrum.power[i] * w * h2
        }
        if (before <= 0 || after <= 0) return 0.0
        return (-10 * log10(after / before)).coerceIn(EqBands.MAKEUP_MIN_DB, EqBands.MAKEUP_MAX_DB)
    }

    fun profile(spectrum: Spectrum, gainsDb: DoubleArray, sampleRate: Int = 48000) =
        EqProfile(gainsDb.toList(), matchMakeupDb(spectrum, gainsDb, sampleRate))
}

/**
 * Deterministic adaptive search for the preferred balance.
 *
 * - Directions are broad and few: overall tilt (warmer ↔ brighter), air, depth and presence, each used only
 *   when the analysis found energy in the regions it touches.
 * - Each comparison plays the current balance shifted a step one way (A or B) and the other way, around
 *   the current point; the order is pseudo-randomised per step (fixed seed, so reproducible).
 * - Preferred version becomes the new current point. "No difference" retires that direction: smaller steps
 *   on it would be even harder to hear. "Skip" changes nothing.
 * - Step sizes halve each round (4 → 2 → 1 dB). The plan has 5–8 comparisons in total.
 * - All gains stay within [EqBands.MIN_DB, EqBands.MAX_DB].
 */
class Personalizer private constructor(
    private val adjustable: BooleanArray,
    val seed: Long,
    private var current: DoubleArray,
    private var plan: MutableList<Step>,
    private var index: Int,
    private var decisive: Int,
    private var noDifference: Int,
) {
    data class Step(val direction: Int, val stepDb: Double)

    enum class Answer { A_BETTER, B_BETTER, NO_DIFFERENCE, SKIP }

    data class Comparison(val step: Int, val total: Int, val a: DoubleArray, val b: DoubleArray)

    private data class Snapshot(val current: DoubleArray, val plan: List<Step>, val index: Int, val decisive: Int, val noDifference: Int)

    private val history = ArrayDeque<Snapshot>()

    val directions: List<DoubleArray> = directionsFor(adjustable)

    /** Current preferred gains. */
    val gainsDb: DoubleArray get() = current.copyOf()

    val done: Boolean get() = index >= plan.size || directions.isEmpty()

    /** Number of comparisons in the (remaining) plan, for "Step 3 of 6". */
    val total: Int get() = plan.size

    val canGoBack: Boolean get() = history.isNotEmpty()

    /**
     * True when no consistent preference emerged: fewer than two decisive answers, or the result differs
     * from where the session started by less than 1.5 dB everywhere. The UI then recommends the original.
     */
    fun noClearPreference(start: DoubleArray = DoubleArray(EqBands.COUNT)): Boolean =
        decisive < 2 || current.indices.all { abs(current[it] - start[it]) < 1.5 }

    fun comparison(): Comparison? {
        if (done) return null
        val s = plan[index]
        val d = directions[s.direction]
        val plus = DoubleArray(EqBands.COUNT) { clamp(current[it] + s.stepDb * d[it]) }
        val minus = DoubleArray(EqBands.COUNT) { clamp(current[it] - s.stepDb * d[it]) }
        val swap = ((seed * 6364136223846793005L + index * 1442695040888963407L) ushr 33) and 1L == 1L
        return if (swap) Comparison(index + 1, plan.size, minus, plus) else Comparison(index + 1, plan.size, plus, minus)
    }

    fun answer(a: Answer) {
        val c = comparison() ?: return
        history.addLast(Snapshot(current.copyOf(), plan.toList(), index, decisive, noDifference))
        when (a) {
            Answer.A_BETTER -> { current = c.a; decisive++ }
            Answer.B_BETTER -> { current = c.b; decisive++ }
            Answer.NO_DIFFERENCE -> {
                noDifference++
                val dir = plan[index].direction
                val keep = plan.subList(0, index + 1).toMutableList()
                keep += plan.subList(index + 1, plan.size).filter { it.direction != dir }
                plan = keep
            }
            Answer.SKIP -> Unit
        }
        index++
        skipDegenerate()
    }

    fun back() {
        val s = history.removeLastOrNull() ?: return
        current = s.current; plan = s.plan.toMutableList(); index = s.index; decisive = s.decisive; noDifference = s.noDifference
    }

    /** Steps whose two versions would be identical because of the limits are not worth a comparison. */
    private fun skipDegenerate() {
        while (!done) {
            val c = comparison() ?: return
            if (c.a.indices.any { abs(c.a[it] - c.b[it]) > 0.25 }) return
            plan.removeAt(index)
        }
    }

    fun encode(): String = listOf(
        "adj=" + adjustable.joinToString("") { if (it) "1" else "0" },
        "seed=$seed",
        "c=" + current.joinToString(",") { "%.3f".format(java.util.Locale.US, it) },
        "plan=" + plan.joinToString(",") { "${it.direction}:${it.stepDb}" },
        "i=$index", "dec=$decisive", "nd=$noDifference",
    ).joinToString(";")

    companion object {
        val STEPS_DB = doubleArrayOf(4.0, 2.0, 1.0, 1.0)
        const val MIN_COMPARISONS = 5
        const val MAX_COMPARISONS = 8

        private fun clamp(v: Double) = v.coerceIn(EqBands.MIN_DB, EqBands.MAX_DB)

        /** Broad directions in region space, masked to regions with energy; each scaled to max |w| = 1. */
        fun directionsFor(adjustable: BooleanArray): List<DoubleArray> {
            val raw = listOf(
                doubleArrayOf(1.0, 0.5, -0.5, -1.0), // tilt: warmer (+) / brighter (−)
                doubleArrayOf(0.0, 0.0, 0.0, 1.0),   // air
                doubleArrayOf(1.0, 0.0, 0.0, 0.0),   // depth
                doubleArrayOf(0.0, 0.0, 1.0, 0.0),   // presence
                doubleArrayOf(0.0, 1.0, 0.0, 0.0),   // body (used only when nothing else is available)
            )
            val out = ArrayList<DoubleArray>()
            for ((n, r) in raw.withIndex()) {
                val m = DoubleArray(r.size) { if (adjustable[it]) r[it] else 0.0 }
                val active = m.count { it != 0.0 }
                val needed = if (n == 0) 2 else 1
                if (n == 4 && out.isNotEmpty()) continue
                if (active < needed) continue
                val peak = m.maxOf { abs(it) }
                out += DoubleArray(m.size) { m[it] / peak }
                if (out.size == 4) break
            }
            return out
        }

        fun planFor(directionCount: Int): MutableList<Step> {
            if (directionCount == 0) return mutableListOf()
            val total = (2 * directionCount).coerceIn(MIN_COMPARISONS, MAX_COMPARISONS)
            val plan = ArrayList<Step>()
            var round = 0
            while (plan.size < total) {
                val step = STEPS_DB[round.coerceAtMost(STEPS_DB.size - 1)]
                for (d in 0 until directionCount) if (plan.size < total) plan += Step(d, step)
                round++
            }
            return plan
        }

        /** New session. [start] lets "Refine further" continue from a saved result with smaller steps. */
        fun create(adjustable: BooleanArray, seed: Long, start: DoubleArray? = null, refine: Boolean = false): Personalizer {
            val dirs = directionsFor(adjustable)
            var plan = planFor(dirs.size)
            if (refine) plan = plan.map { it.copy(stepDb = (it.stepDb / 2).coerceAtLeast(1.0)) }.toMutableList()
            val p = Personalizer(adjustable, seed, (start ?: DoubleArray(EqBands.COUNT)).map { clamp(it) }.toDoubleArray(), plan, 0, 0, 0)
            p.skipDegenerate()
            return p
        }

        fun decode(s: String?): Personalizer? = runCatching {
            val m = s!!.split(';').associate { val i = it.indexOf('='); it.substring(0, i) to it.substring(i + 1) }
            val adj = m.getValue("adj").map { it == '1' }.toBooleanArray()
            require(adj.size == EqBands.COUNT)
            val c = m.getValue("c").split(',').map { clamp(it.toDouble()) }.toDoubleArray()
            require(c.size == EqBands.COUNT)
            val dirCount = directionsFor(adj).size
            val plan = m.getValue("plan").split(',').filter { it.isNotBlank() }.map {
                val (d, st) = it.split(':'); Step(d.toInt(), st.toDouble())
            }.filter { it.direction in 0 until dirCount }.toMutableList()
            Personalizer(adj, m.getValue("seed").toLong(), c, plan, m.getValue("i").toInt().coerceIn(0, plan.size),
                m.getValue("dec").toInt(), m.getValue("nd").toInt())
        }.getOrNull()
    }
}

/**
 * Real-time equaliser for one stereo stream. Gains glide (≤ 0.05 dB per 32 samples, so a 10 dB change
 * takes ~130 ms) and the make-up gain is interpolated per sample: changing the balance never clicks or
 * jumps in level. Allocation-free on the audio thread.
 */
class EqChain(private val sampleRate: Int, initial: EqProfile) {
    @Volatile var target: EqProfile = initial
    private val bq = Array(2) { Array(EqBands.COUNT) { Biquad() } }
    private val cur = initial.gainsDb.toDoubleArray()
    private var makeup = 10.0.pow(initial.makeupDb / 20).toFloat()

    init { for (c in 0..1) EqBands.configure(bq[c], sampleRate, cur) }

    fun process(buf: FloatArray, frames: Int) {
        val t = target
        val tMakeup = 10.0.pow(t.makeupDb / 20).toFloat()
        val mStep = (tMakeup - makeup) / frames.coerceAtLeast(1)
        var n = 0
        while (n < frames) {
            val len = minOf(SUB_BLOCK, frames - n)
            var changed = false
            for (k in 0 until EqBands.COUNT) {
                val d = t.gainsDb[k] - cur[k]
                if (d != 0.0) {
                    cur[k] += d.coerceIn(-MAX_STEP_DB, MAX_STEP_DB)
                    changed = true
                }
            }
            if (changed) for (c in 0..1) EqBands.configure(bq[c], sampleRate, cur)
            for (i in n until n + len) {
                makeup += mStep
                var l = buf[2 * i]
                var r = buf[2 * i + 1]
                for (k in 0 until EqBands.COUNT) { l = bq[0][k].process(l); r = bq[1][k].process(r) }
                buf[2 * i] = l * makeup
                buf[2 * i + 1] = r * makeup
            }
            n += len
        }
        makeup = tMakeup
    }

    companion object {
        private const val SUB_BLOCK = 32
        private const val MAX_STEP_DB = 0.05
    }
}

/** Plays a source through a fixed personalised balance (used by saved personalised sounds in Relief). */
class EqVoice(private val source: Voice, sampleRate: Int, profile: EqProfile) : Voice {
    private val chain = EqChain(sampleRate, profile)
    private var buf = FloatArray(0)
    override fun render(out: FloatArray, frames: Int) {
        val n = frames * 2
        if (buf.size < n) buf = FloatArray(n)
        java.util.Arrays.fill(buf, 0, n, 0f)
        source.render(buf, frames)
        chain.process(buf, frames)
        for (i in 0 until n) out[i] += buf[i]
    }
}

/**
 * A/B comparison of two balances of the SAME sound (same moment in the loop), for seamless switching:
 * the source is rendered once and fed through two equaliser chains; switching is a 150 ms linear
 * crossfade (the two versions are highly correlated, so linear keeps the level constant). Playback stops
 * by itself after [maxMs]. Both versions are loudness-matched by their make-up gains.
 */
class AbCompareVoice(
    private val source: Voice,
    private val sampleRate: Int,
    a: EqProfile,
    b: EqProfile,
    maxMs: Int = DEFAULT_MAX_MS,
    fadeMs: Int = 150,
) : Voice {
    private val chainA = EqChain(sampleRate, a)
    private val chainB = EqChain(sampleRate, b)
    private var src = FloatArray(0)
    private var bufA = FloatArray(0)
    private var bufB = FloatArray(0)
    private val step = 1f / (fadeMs * sampleRate / 1000f).coerceAtLeast(1f)
    private val maxFrames = maxMs.toLong() * sampleRate / 1000
    private var wA = 0f
    private var wB = 0f
    private var playedFrames = 0L

    /** −1 = silent, 0 = A, 1 = B. */
    @Volatile var selected: Int = -1
        private set
    @Volatile private var restart = false

    /** 0..1 of the maximum playing time. */
    @Volatile var progress: Float = 0f
        private set

    fun select(which: Int) { selected = which; restart = true }
    fun stop() { selected = -1 }
    fun setProfiles(a: EqProfile, b: EqProfile) { chainA.target = a; chainB.target = b }

    override fun render(out: FloatArray, frames: Int) {
        val n = frames * 2
        if (src.size < n) { src = FloatArray(n); bufA = FloatArray(n); bufB = FloatArray(n) }
        if (restart) { restart = false; playedFrames = 0 }
        java.util.Arrays.fill(src, 0, n, 0f)
        source.render(src, frames) // always advances, so A and B stay in step with each other
        if (selected >= 0 && playedFrames >= maxFrames) selected = -1
        val sel = selected
        // keep both filters' state warm so a switch never starts from a cold filter
        System.arraycopy(src, 0, bufA, 0, n); chainA.process(bufA, frames)
        System.arraycopy(src, 0, bufB, 0, n); chainB.process(bufB, frames)
        var o = 0
        for (i in 0 until frames) {
            val ta = if (sel == 0) 1f else 0f
            val tb = if (sel == 1) 1f else 0f
            wA = if (wA < ta) minOf(ta, wA + step) else maxOf(ta, wA - step)
            wB = if (wB < tb) minOf(tb, wB + step) else maxOf(tb, wB - step)
            out[o] += bufA[o] * wA + bufB[o] * wB
            out[o + 1] += bufA[o + 1] * wA + bufB[o + 1] * wB
            o += 2
        }
        if (sel >= 0) playedFrames += frames
        progress = if (sel >= 0) (playedFrames.toFloat() / maxFrames).coerceIn(0f, 1f) else 0f
    }

    companion object {
        /** Maximum continuous playing time per tap. Configurable per instance. */
        const val DEFAULT_MAX_MS = 45_000
    }
}

/** Renders [seconds] of any voice into a mono buffer (for analysis). */
fun renderMono(v: Voice, sampleRate: Int, seconds: Double): FloatArray {
    val frames = (sampleRate * seconds).toInt()
    val out = FloatArray(frames)
    val block = 1024
    val buf = FloatArray(block * 2)
    var pos = 0
    while (pos < frames) {
        val n = minOf(block, frames - pos)
        java.util.Arrays.fill(buf, 0f)
        v.render(buf, n)
        for (i in 0 until n) out[pos + i] = 0.5f * (buf[2 * i] + buf[2 * i + 1])
        pos += n
    }
    return out
}
