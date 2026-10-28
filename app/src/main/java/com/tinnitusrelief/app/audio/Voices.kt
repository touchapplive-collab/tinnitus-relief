package com.tinnitusrelief.app.audio

/**
 * A sound generator that adds interleaved stereo float samples into [out].
 * Implementations are called only from the audio thread.
 */
interface Voice {
    /** Adds [frames] stereo frames into [out] (interleaved L,R). Must not allocate. */
    fun render(out: FloatArray, frames: Int)
}

/** Fast, allocation-free pseudo random source (xorshift32), returns values in [-1, 1). */
class FastRandom(seed: Int) {
    private var s = if (seed == 0) 0x9E3779B9.toInt() else seed
    fun next(): Float {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return (x ushr 8) * (1f / 8388608f) - 1f
    }
}

/**
 * Loops decoded PCM forever. The buffer is expected to be pre-processed to be seamless
 * (see [PcmDecoder.makeSeamless]). Uses linear interpolation to convert sample rates.
 */
class PcmLoopVoice(
    private val data: ShortArray,
    sourceRate: Int,
    outputRate: Int,
) : Voice {
    private val frames = data.size / 2
    private val step = sourceRate.toDouble() / outputRate
    private var pos = 0.0

    override fun render(out: FloatArray, frames: Int) {
        if (this.frames < 2) return
        val scale = 1f / 32768f
        var p = pos
        var o = 0
        for (n in 0 until frames) {
            val i = p.toInt()
            val f = (p - i).toFloat()
            val j = if (i + 1 >= this.frames) 0 else i + 1
            val l0 = data[2 * i] * scale
            val r0 = data[2 * i + 1] * scale
            val l1 = data[2 * j] * scale
            val r1 = data[2 * j + 1] * scale
            out[o] += l0 + (l1 - l0) * f
            out[o + 1] += r0 + (r1 - r0) * f
            o += 2
            p += step
            if (p >= this.frames) p -= this.frames
        }
        pos = p
    }
}

enum class NoiseColor { WHITE, PINK, BROWN }

/**
 * Real-time broadband noise with independent left/right channels.
 * Levels are calibrated so all colours sit at roughly the same RMS (about -20 dBFS).
 */
class NoiseVoice(private val color: NoiseColor) : Voice {
    private val rl = FastRandom(0x1234567)
    private val rr = FastRandom(0x7654321)
    private val pl = FloatArray(7)
    private val pr = FloatArray(7)
    private var bl = 0f
    private var br = 0f

    override fun render(out: FloatArray, frames: Int) {
        var o = 0
        for (n in 0 until frames) {
            out[o] += sample(rl, pl, true)
            out[o + 1] += sample(rr, pr, false)
            o += 2
        }
    }

    private fun sample(r: FastRandom, b: FloatArray, left: Boolean): Float {
        val w = r.next()
        return when (color) {
            NoiseColor.WHITE -> w * 0.17f
            NoiseColor.PINK -> {
                // Paul Kellet's refined pink filter
                b[0] = 0.99886f * b[0] + w * 0.0555179f
                b[1] = 0.99332f * b[1] + w * 0.0750759f
                b[2] = 0.96900f * b[2] + w * 0.1538520f
                b[3] = 0.86650f * b[3] + w * 0.3104856f
                b[4] = 0.55000f * b[4] + w * 0.5329522f
                b[5] = -0.7616f * b[5] - w * 0.0168980f
                val y = b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + w * 0.5362f
                b[6] = w * 0.115926f
                y * 0.057f
            }
            NoiseColor.BROWN -> {
                // Leaky integrator keeps the random walk bounded (no DC drift)
                var y = if (left) bl else br
                y = (y + 0.02f * w) / 1.02f
                if (left) bl = y else br = y
                y * 1.6f
            }
        }
    }
}
