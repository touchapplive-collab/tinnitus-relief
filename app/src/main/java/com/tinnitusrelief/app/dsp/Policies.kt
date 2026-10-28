package com.tinnitusrelief.app.dsp

/**
 * Safety limits for experimental (Acoustic Lab) sessions. These are ENGINEERING SAFETY choices, not
 * therapeutic parameters: they cap exposure per session; they do not claim an effective dose.
 */
object SessionLimits {
    val OPTIONS_MINUTES = listOf(5, 10, 15, 20, 30, 45, 60)
    const val DEFAULT_MINUTES = 15
    const val MAX_MINUTES = 60
    const val FADE_IN_MS = 3000
    const val FADE_OUT_MS = 3000
}

/** Pure session clock: knows when an experimental session must end. */
class SessionTimer(minutes: Int, private val startedAtMs: Long) {
    val limitMs: Long = minutes.coerceIn(1, SessionLimits.MAX_MINUTES) * 60_000L
    fun remainingMs(nowMs: Long): Long = (startedAtMs + limitMs - nowMs).coerceAtLeast(0)
    fun expired(nowMs: Long): Boolean = remainingMs(nowMs) == 0L
    fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceIn(0, limitMs)
}

/** Audio focus events as delivered by Android, decoupled from the framework for testing. */
enum class FocusEvent { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

sealed class FocusAction {
    data class Pause(val fadeMs: Int, val abandonFocus: Boolean) : FocusAction()
    object Resume : FocusAction()
    data class Duck(val ducked: Boolean) : FocusAction()
}

/**
 * Audio-focus behaviour, following Android's guidance:
 *  - permanent loss (another media app) → pause and give up focus, never auto-resume;
 *  - transient loss (call, alarm, navigation) → pause, resume automatically when focus returns;
 *  - "can duck" → lower our level, restore on gain.
 */
class FocusPolicy {
    var playing = false
        private set
    private var resumeOnGain = false

    fun onUserPlay() { playing = true; resumeOnGain = false }
    fun onUserPause() { playing = false; resumeOnGain = false }

    fun onFocus(event: FocusEvent): List<FocusAction> = when (event) {
        FocusEvent.GAIN -> {
            val actions = mutableListOf<FocusAction>(FocusAction.Duck(false))
            if (resumeOnGain && !playing) { playing = true; actions += FocusAction.Resume }
            resumeOnGain = false
            actions
        }
        FocusEvent.LOSS_TRANSIENT_CAN_DUCK -> listOf(FocusAction.Duck(true))
        FocusEvent.LOSS_TRANSIENT -> if (playing) {
            playing = false; resumeOnGain = true
            listOf(FocusAction.Pause(300, abandonFocus = false))
        } else emptyList()
        FocusEvent.LOSS -> {
            val wasPlaying = playing
            playing = false; resumeOnGain = false
            if (wasPlaying) listOf(FocusAction.Pause(300, abandonFocus = true)) else emptyList()
        }
    }
}
