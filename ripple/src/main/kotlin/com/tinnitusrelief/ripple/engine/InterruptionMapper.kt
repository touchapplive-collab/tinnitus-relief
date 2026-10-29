package com.tinnitusrelief.ripple.engine

/**
 * Maps Android audio-focus changes to engine interruptions. Values are the documented AudioManager constants,
 * repeated here so the rule stays testable on the JVM. Every loss stops the session; a regained focus never
 * restarts it (spec S7: no automatic resume).
 */
object InterruptionMapper {
    const val AUDIOFOCUS_GAIN = 1
    const val AUDIOFOCUS_LOSS = -1
    const val AUDIOFOCUS_LOSS_TRANSIENT = -2
    const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3

    fun fromFocusChange(change: Int): Interruption? = when (change) {
        AUDIOFOCUS_LOSS -> Interruption.FOCUS_LOSS
        AUDIOFOCUS_LOSS_TRANSIENT -> Interruption.FOCUS_LOSS_TRANSIENT
        // ducking is not allowed for this stimulus: a quieter ripple is a different stimulus, so it stops too
        AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Interruption.FOCUS_DUCK
        else -> null
    }
}
