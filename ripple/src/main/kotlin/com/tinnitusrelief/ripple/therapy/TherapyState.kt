package com.tinnitusrelief.ripple.therapy

import com.tinnitusrelief.ripple.RippleBandTable
import com.tinnitusrelief.ripple.engine.Arm
import com.tinnitusrelief.ripple.engine.OutputCalibration
import com.tinnitusrelief.ripple.engine.ScreeningAnswers

/** Everything the therapy layer stores besides the safety ledger. Text codec below (one key per line). */
data class TherapyState(
    val screening: ScreeningAnswers = ScreeningAnswers(),
    val screenedAtMs: Long? = null,
    /** 0, 15, 30 or 45 dB ([P]); only values allowed by the safety limits can be used. */
    val hearingProfileDb: Double = 0.0,
    val hearingProfileChosen: Boolean = false,
    /** Result of the band audibility check and the match tone it was done for. */
    val a1Audible: Boolean? = null,
    val a1CheckedMatchHz: Double? = null,
    val plan: StudyPlan? = null,
    /** Acoustic calibration records (spec 4.1). None exist until a laboratory measurement is imported. */
    val calibrations: List<OutputCalibration> = emptyList(),
) {
    /** The audibility answer only counts for the match tone it was made for. */
    fun a1AudibleFor(pitchHz: Double?): Boolean? {
        if (pitchHz == null || a1CheckedMatchHz == null) return null
        return if (kotlin.math.abs(RippleBandTable.forPitch(pitchHz).matchHz - a1CheckedMatchHz) < 0.5) a1Audible else null
    }

    fun calibrationFor(route: String?): OutputCalibration? = calibrations.lastOrNull { it.route == route }

    fun encode(): String = buildString {
        fun put(k: String, v: Any?) { if (v != null) append(k).append('=').append(v).append('\n') }
        val s = screening
        put("adult", s.adult); put("worseWithModerateSounds", s.worseWithModerateSounds); put("soundSensitivityOrPain", s.soundSensitivityOrPain)
        put("pulsatile", s.pulsatile); put("suddenHearingChange", s.suddenHearingChange); put("newOneSidedTinnitus", s.newOneSidedTinnitus)
        put("earPainDischargeOrDizziness", s.earPainDischargeOrDizziness); put("screenedAtMs", screenedAtMs)
        put("hearingProfileDb", hearingProfileDb); put("hearingProfileChosen", hearingProfileChosen)
        put("a1Audible", a1Audible); put("a1CheckedMatchHz", a1CheckedMatchHz)
        plan?.let { put("plan", "${it.startEpochDay},${it.firstArm},${it.createdAtMs},${it.unblindedAtMs ?: "-"},${it.listenDays},${it.washoutDays}") }
        for (c in calibrations) put("calibration", CalibrationCodec.encode(c))
    }

    companion object {
        fun decode(text: String): TherapyState {
            var st = TherapyState()
            var sc = ScreeningAnswers()
            val cals = ArrayList<OutputCalibration>()
            for (line in text.lines()) {
                val i = line.indexOf('='); if (i <= 0) continue
                val k = line.substring(0, i); val v = line.substring(i + 1)
                runCatching {
                    when (k) {
                        "adult" -> sc = sc.copy(adult = v.toBooleanStrict())
                        "worseWithModerateSounds" -> sc = sc.copy(worseWithModerateSounds = v.toBooleanStrict())
                        "soundSensitivityOrPain" -> sc = sc.copy(soundSensitivityOrPain = v.toBooleanStrict())
                        "pulsatile" -> sc = sc.copy(pulsatile = v.toBooleanStrict())
                        "suddenHearingChange" -> sc = sc.copy(suddenHearingChange = v.toBooleanStrict())
                        "newOneSidedTinnitus" -> sc = sc.copy(newOneSidedTinnitus = v.toBooleanStrict())
                        "earPainDischargeOrDizziness" -> sc = sc.copy(earPainDischargeOrDizziness = v.toBooleanStrict())
                        "screenedAtMs" -> st = st.copy(screenedAtMs = v.toLong())
                        "hearingProfileDb" -> st = st.copy(hearingProfileDb = v.toDouble())
                        "hearingProfileChosen" -> st = st.copy(hearingProfileChosen = v.toBooleanStrict())
                        "a1Audible" -> st = st.copy(a1Audible = v.toBooleanStrict())
                        "a1CheckedMatchHz" -> st = st.copy(a1CheckedMatchHz = v.toDouble())
                        "plan" -> {
                            val f = v.split(",")
                            st = st.copy(plan = StudyPlan(f[0].toLong(), Arm.valueOf(f[1]), f[2].toLong(), f[3].toLongOrNull(), f[4].toInt(), f[5].toInt()))
                        }
                        "calibration" -> CalibrationCodec.decode(v)?.let { cals += it }
                    }
                }
            }
            return st.copy(screening = sc, calibrations = cals)
        }
    }
}

/**
 * Calibration records as one line of `key:value` pairs separated by `;` (the same format a laboratory file uses,
 * one record per line). A record is accepted only if every field is present and the measured level is within
 * the limit; acceptance alone does not enable sound (see OutputAuthorization).
 */
object CalibrationCodec {
    private val KEYS = listOf("device", "headphone", "route", "measuredAtMs", "measuredBy", "maxGainDb", "laeqDbA")

    fun encode(c: OutputCalibration): String =
        "device:${c.deviceModel};headphone:${c.headphoneModel};route:${c.route};measuredAtMs:${c.measuredAtMs};measuredBy:${c.measuredBy};maxGainDb:${c.maxGainDb};laeqDbA:${c.laeqAtMaxGainDbA}"

    fun decode(line: String): OutputCalibration? {
        val m = line.split(";").mapNotNull { p -> p.indexOf(':').takeIf { it > 0 }?.let { p.substring(0, it).trim() to p.substring(it + 1).trim() } }.toMap()
        if (KEYS.any { m[it].isNullOrBlank() }) return null
        return runCatching {
            OutputCalibration(m["device"]!!, m["headphone"]!!, m["route"]!!, m["measuredAtMs"]!!.toLong(), m["measuredBy"]!!,
                m["maxGainDb"]!!.toDouble(), m["laeqDbA"]!!.toDouble())
        }.getOrNull()?.takeIf { it.maxGainDb.isFinite() && it.laeqAtMaxGainDbA.isFinite() && it.maxGainDb <= 0.0 }
    }
}
