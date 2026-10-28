package com.tinnitusrelief.app.dsp

/** Locally stored subjective responses. Ratings are 0–10 ("How noticeable is your tinnitus right now?"). */
sealed class ResponseRecord {
    abstract val atMs: Long

    /** Estimated tinnitus pitch. Subjective estimate, not a clinical measurement. */
    data class Pitch(override val atMs: Long, val freqHz: Double, val method: String, val loudnessDbSl: Double?) : ResponseRecord()

    /** Before / after / later ratings around one listening session. */
    data class Relief(
        override val atMs: Long,
        val stimulus: String,
        val minutes: Int,
        val before: Int,
        val after: Int?,
        val later: Int?,
        val laterMinutes: Int?,
    ) : ResponseRecord()

    /** Residual inhibition test. riSeconds = time until the tinnitus returned to its usual level. */
    data class Ri(
        override val atMs: Long,
        val freqHz: Double,
        val stimulusSeconds: Int,
        val extraDb: Int,
        val before: Int,
        val after: Int?,
        val outcome: String,
        val riSeconds: Int?,
    ) : ResponseRecord()

    /** One optional check-in per day, for long-term tracking. */
    data class Daily(override val atMs: Long, val day: String, val rating: Int) : ResponseRecord()
}

/**
 * Line-based storage format: one record per line, TAB-separated key=value pairs, first field is the type.
 * Plain text so it stays readable and exportable; unknown or damaged lines are skipped, never crash.
 */
object ResponseCodec {

    fun encode(r: ResponseRecord): String {
        val m = linkedMapOf<String, Any?>()
        val type = when (r) {
            is ResponseRecord.Pitch -> { m["f"] = r.freqHz; m["method"] = r.method; m["sl"] = r.loudnessDbSl; "PITCH" }
            is ResponseRecord.Relief -> { m["stim"] = r.stimulus; m["min"] = r.minutes; m["before"] = r.before; m["after"] = r.after; m["later"] = r.later; m["laterMin"] = r.laterMinutes; "RELIEF" }
            is ResponseRecord.Ri -> { m["f"] = r.freqHz; m["sec"] = r.stimulusSeconds; m["extra"] = r.extraDb; m["before"] = r.before; m["after"] = r.after; m["outcome"] = r.outcome; m["ri"] = r.riSeconds; "RI" }
            is ResponseRecord.Daily -> { m["day"] = r.day; m["rating"] = r.rating; "DAILY" }
        }
        val sb = StringBuilder(type).append("\tat=").append(r.atMs)
        for ((k, v) in m) if (v != null) sb.append('\t').append(k).append('=').append(escape(v.toString()))
        return sb.toString()
    }

    fun decode(line: String): ResponseRecord? = try {
        val parts = line.split('\t')
        val m = parts.drop(1).mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to unescape(it.substring(i + 1))
        }.toMap()
        val at = m.getValue("at").toLong()
        when (parts[0]) {
            "PITCH" -> ResponseRecord.Pitch(at, m.getValue("f").toDouble(), m["method"] ?: "", m["sl"]?.toDouble())
            "RELIEF" -> ResponseRecord.Relief(
                at, m["stim"] ?: "", m.getValue("min").toInt(), m.getValue("before").toInt(),
                m["after"]?.toInt(), m["later"]?.toInt(), m["laterMin"]?.toInt(),
            )
            "RI" -> ResponseRecord.Ri(
                at, m.getValue("f").toDouble(), m.getValue("sec").toInt(), m["extra"]?.toInt() ?: 0,
                m.getValue("before").toInt(), m["after"]?.toInt(), m["outcome"] ?: "", m["ri"]?.toInt(),
            )
            "DAILY" -> ResponseRecord.Daily(at, m.getValue("day"), m.getValue("rating").toInt())
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    fun decodeAll(text: String): List<ResponseRecord> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull(::decode).toList()

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
