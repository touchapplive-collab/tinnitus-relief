package com.tinnitusrelief.app.ripple

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import com.tinnitusrelief.app.dsp.PitchProfile
import com.tinnitusrelief.app.dsp.ResponseRecord
import com.tinnitusrelief.app.engine.LocalResponseTracker
import com.tinnitusrelief.ripple.engine.AdverseSymptom
import com.tinnitusrelief.ripple.engine.EndReason
import com.tinnitusrelief.ripple.engine.GateResult
import com.tinnitusrelief.ripple.engine.Interruption
import com.tinnitusrelief.ripple.engine.NullSink
import com.tinnitusrelief.ripple.engine.OutputAuthorization
import com.tinnitusrelief.ripple.engine.PacedDriver
import com.tinnitusrelief.ripple.engine.PitchConfidence
import com.tinnitusrelief.ripple.engine.PitchEstimate
import com.tinnitusrelief.ripple.engine.RenderAheadSource
import com.tinnitusrelief.ripple.engine.RippleEngine
import com.tinnitusrelief.ripple.engine.RippleSafetyLimits
import com.tinnitusrelief.ripple.engine.SafetyLedger
import com.tinnitusrelief.ripple.engine.ScreeningAnswers
import com.tinnitusrelief.ripple.engine.SessionReport
import com.tinnitusrelief.ripple.therapy.CalibrationCodec
import com.tinnitusrelief.ripple.therapy.PeriodSummary
import com.tinnitusrelief.ripple.therapy.PlanOutcome
import com.tinnitusrelief.ripple.therapy.StudyPlan
import com.tinnitusrelief.ripple.therapy.TherapyGate
import com.tinnitusrelief.ripple.therapy.TherapyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class SessionPhase { PRE, RUNNING, POST }

/** What the session screen shows. The arm (active/sham) is deliberately not part of it. */
data class SessionUi(
    val phase: SessionPhase,
    val rehearsal: Boolean,
    val allowedMs: Long,
    val elapsedMs: Long = 0,
    val levelDb: Double = -120.0,
    val levelConfirmed: Boolean = false,
    val preRating: Int? = null,
    val endReason: EndReason? = null,
    val interruption: Interruption? = null,
)

/**
 * Spectral-ripple therapy layer: screening, hearing profile, study plan, sessions, reports, locks, outcomes.
 * Therapy sessions are audible and stay blocked until audible playback is authorised and calibrated
 * (see [OutputAuthorization]); a silent rehearsal runs the identical engine into a discarding sink.
 */
class RippleController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val tracker: LocalResponseTracker,
    private val pitchHz: () -> Double?,
    private val isPlaying: () -> Boolean,
    val limits: RippleSafetyLimits = RippleSafetyLimits(),
) {
    private val store = RippleStore(context)
    private val routes = OutputRoute(context)

    private val _state = MutableStateFlow(TherapyState())
    val state: StateFlow<TherapyState> = _state
    private val _ledger = MutableStateFlow(SafetyLedger())
    val ledger: StateFlow<SafetyLedger> = _ledger
    private val _rehearsalLedger = MutableStateFlow(SafetyLedger())
    val rehearsalLedger: StateFlow<SafetyLedger> = _rehearsalLedger
    private val _session = MutableStateFlow<SessionUi?>(null)
    val session: StateFlow<SessionUi?> = _session
    private val _route = MutableStateFlow<OutputRoute.Route?>(null)
    val route: StateFlow<OutputRoute.Route?> = _route

    private var engine: RippleEngine? = null
    private var pump: Thread? = null
    private var ticker: Job? = null
    private var gate: GateResult? = null
    @Volatile private var cancelled = false
    private var pendingResume = false

    val audibleAuthorized: Boolean get() = OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED

    suspend fun load() = withContext(Dispatchers.IO) {
        _state.value = store.loadState()
        _ledger.value = store.loadLedger()
        _rehearsalLedger.value = store.loadRehearsalLedger()
        _route.value = runCatching { routes.current() }.getOrNull()
    }

    fun refreshRoute() { _route.value = runCatching { routes.current() }.getOrNull() }

    // ------------------------------------------------------------ inputs

    fun pitchEstimate(): PitchEstimate? {
        val hz = pitchHz() ?: return null
        val profile = PitchProfile.of(tracker.records.value, System.currentTimeMillis())
        val conf = when (profile?.confidence) {
            PitchProfile.Confidence.HIGH -> PitchConfidence.HIGH
            PitchProfile.Confidence.MEDIUM -> PitchConfidence.MEDIUM
            else -> PitchConfidence.LOW
        }
        return PitchEstimate(hz, conf)
    }

    fun today(): Long = LocalDate.now().toEpochDay()
    private fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun update(t: (TherapyState) -> TherapyState) {
        _state.value = t(_state.value)
        val s = _state.value
        scope.launch(Dispatchers.IO) { store.save(s) }
    }

    private fun setLedger(l: SafetyLedger, rehearsal: Boolean) {
        if (rehearsal) { _rehearsalLedger.value = l; scope.launch(Dispatchers.IO) { store.saveRehearsalLedger(l) } }
        else { _ledger.value = l; scope.launch(Dispatchers.IO) { store.saveLedger(l) } }
    }

    fun saveScreening(a: ScreeningAnswers) = update { it.copy(screening = a, screenedAtMs = System.currentTimeMillis()) }

    /** Only profiles allowed by the safety limits can be chosen (30/45 dB stay locked). */
    fun setHearingProfile(db: Double): Boolean {
        if (db !in limits.allowedHearingProfilesDb) return false
        update { it.copy(hearingProfileDb = db, hearingProfileChosen = true) }
        return true
    }

    fun startPlan(startTomorrow: Boolean) = update {
        it.copy(plan = StudyPlan.create(today() + if (startTomorrow) 1 else 0, SecureRandom(), System.currentTimeMillis()))
    }
    fun unblind() = update { s -> s.copy(plan = s.plan?.let { p -> p.copy(unblindedAtMs = p.unblindedAtMs ?: System.currentTimeMillis()) }) }
    fun endPlan() = update { it.copy(plan = null) }

    /** Imports calibration records (one per line). Returns how many valid records were added. */
    suspend fun importCalibration(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() ?: return@withContext 0
        val recs = text.lines().mapNotNull { CalibrationCodec.decode(it) }
        if (recs.isNotEmpty()) withContext(Dispatchers.Main) { update { it.copy(calibrations = it.calibrations + recs) } }
        recs.size
    }

    fun clearLock(adviceAcknowledged: Boolean, clinicianCleared: Boolean): Boolean {
        val before = _ledger.value
        val after = before.clearLock(System.currentTimeMillis(), adviceAcknowledged, clinicianCleared, limits)
        if (after != before) setLedger(after, false)
        return after.lock == null
    }

    // ------------------------------------------------------------ listening checks (audible: gated)

    /**
     * Plays check tones through the experimental layer. Returns false without playing anything unless audible
     * playback is authorised and the current output has a calibration record (never true in this build).
     */
    fun playCheck(tones: List<com.tinnitusrelief.ripple.therapy.CheckTone>, toneMs: Int, gapMs: Int): Boolean {
        refreshRoute()
        val voice = CheckVoice.open(tones, sampleRate, toneMs, gapMs, _state.value.calibrationFor(_route.value?.id)) ?: return false
        val s = sessions ?: return false
        scope.launch {
            if (!s.acquire(com.tinnitusrelief.app.engine.LabActivity.RIPPLE, "check", muteMasker = true)) return@launch
            s.setStimulus(voice, 1f, instant = true)
            while (!voice.finished) delay(100)
            s.stop(150)
        }
        return true
    }

    /** Stores the band audibility answers for the current match tone ([P]: any tone not heard → A2). */
    fun recordAudibility(heard: Map<Double, Boolean>): Boolean? {
        val pitch = pitchEstimate() ?: return null
        val row = com.tinnitusrelief.ripple.RippleBandTable.forPitch(pitch.hz)
        val tones = com.tinnitusrelief.ripple.therapy.AudibilityCheck.tonesFor(row.active, _state.value.hearingProfileDb)
        val result = com.tinnitusrelief.ripple.therapy.AudibilityCheck.result(tones, heard) ?: return null
        update { it.copy(a1Audible = result, a1CheckedMatchHz = row.matchHz) }
        return result
    }

    /** Set by the app graph: the shared experimental-layer session owner, used by the audible paths. */
    var sessions: com.tinnitusrelief.app.engine.SessionManager? = null

    // ------------------------------------------------------------ gate and outcomes

    fun evaluate(rehearsal: Boolean, resume: Boolean = false): GateResult = TherapyGate.evaluate(
        _state.value, pitchEstimate(), if (rehearsal) _rehearsalLedger.value else _ledger.value, limits,
        System.currentTimeMillis(), today(), rehearsal, _route.value?.id, resume,
    )

    fun outcomes(): List<PeriodSummary> {
        val plan = _state.value.plan ?: return emptyList()
        val ratings = tracker.records.value.filterIsInstance<ResponseRecord.Daily>().filter { it.unmasked == true }
            .mapNotNull { d -> runCatching { LocalDate.parse(d.day).toEpochDay() to d.rating }.getOrNull() }
        return PlanOutcome.summarize(plan, ratings, _ledger.value, today(), ::dayOf)
    }

    // ------------------------------------------------------------ session

    /** True if the last session was interrupted recently and may be continued by the user. */
    fun canResume(rehearsal: Boolean): Boolean = evaluate(rehearsal, resume = true).allowed

    /** Opens the pre-session step if the gate allows it. Returns the gate result either way. */
    fun prepare(rehearsal: Boolean, resume: Boolean = false): GateResult {
        refreshRoute()
        val g = evaluate(rehearsal, resume)
        if (g.allowed && _session.value == null) { pendingResume = resume; _session.value = SessionUi(SessionPhase.PRE, rehearsal, g.allowedMs) }
        return g
    }

    fun cancel() { if (_session.value?.phase == SessionPhase.PRE) _session.value = null }

    /** Starts the engine after the pre-session rating. Returns false if anything blocks it. */
    fun begin(preRating: Int?): Boolean {
        val ui = _session.value ?: return false
        if (ui.phase != SessionPhase.PRE) return false
        val g = evaluate(ui.rehearsal, pendingResume)
        if (!g.allowed) { _session.value = null; return false }
        // a therapy pre-session rating taken with no sound playing also counts as today's unmasked rating
        if (!ui.rehearsal && preRating != null && !isPlaying()) {
            val day = LocalDate.now().toString()
            if (tracker.records.value.none { it is ResponseRecord.Daily && it.day == day }) {
                scope.launch { tracker.add(ResponseRecord.Daily(System.currentTimeMillis(), day, preRating, true, null)) }
            }
        }
        val e: RippleEngine = if (g.rehearsal) {
            RippleEngine(sampleRate, limits, NullSink()) { c, s -> RenderAheadSource(c, s) }
        } else {
            // Audible path. Not reachable in this build: the gate blocks first, and the voice cannot be created.
            val voice = RippleVoice.open(sampleRate, limits, _state.value.calibrationFor(_route.value?.id)) ?: run { _session.value = null; return false }
            val sm = sessions ?: run { _session.value = null; return false }
            scope.launch {
                if (sm.acquire(com.tinnitusrelief.app.engine.LabActivity.RIPPLE, "Spectral ripple", muteMasker = true)) sm.setStimulus(voice, 1f, instant = true)
                else voice.engine.interrupt(Interruption.SERVICE_STOPPED)
            }
            voice.engine
        }
        if (!e.start(g, SecureRandom().nextLong())) { e.release(); _session.value = null; return false }
        engine = e; gate = g; cancelled = false
        e.onSessionEnded = { rec -> scope.launch(Dispatchers.Main) { onEnded(rec, g.rehearsal) } }
        _session.value = ui.copy(phase = SessionPhase.RUNNING, preRating = preRating, levelDb = e.levelDb, allowedMs = g.allowedMs)
        if (g.rehearsal) startPump(e, g.allowedMs)
        watchInterruptions()
        ticker = scope.launch {
            while (isActive) {
                val s = _session.value ?: break
                if (s.phase != SessionPhase.RUNNING) break
                _session.value = s.copy(elapsedMs = e.framesOut * 1000 / sampleRate, levelDb = e.levelDb)
                delay(250)
            }
        }
        return true
    }

    private fun startPump(e: RippleEngine, allowedMs: Long) {
        pump = Thread({
            PacedDriver(e, sampleRate, PUMP_BURST, PUMP_BUFFER_BURSTS).run(allowedMs + 15_000) { cancelled || e.state == RippleEngine.State.STOPPED }
        }, "ripple-rehearsal").apply { isDaemon = true; start() }
    }

    /** User changes the level. Only user actions may raise it; the engine caps it. */
    fun setLevel(db: Double) {
        val e = engine ?: return
        val applied = e.setLevel(db, userInitiated = true)
        _session.value = _session.value?.copy(levelDb = applied, levelConfirmed = false)
    }

    fun confirmLevel() {
        val s = _session.value ?: return
        _session.value = s.copy(levelConfirmed = true)
        if (!s.rehearsal) setLedger(_ledger.value.confirmLevel(s.levelDb), false)
    }

    fun stop() { engine?.stop() }

    fun interrupt(i: Interruption) { engine?.interrupt(i) }

    /** Playback stopped elsewhere (call, focus loss, headphones, sleep timer): end the session. */
    fun onTransportStopped() { if (_session.value?.rehearsal == false) interrupt(Interruption.FOCUS_LOSS) }

    private fun onEnded(rec: com.tinnitusrelief.ripple.engine.SessionRecord, rehearsal: Boolean) {
        unwatchInterruptions()
        ticker?.cancel()
        val l = if (rehearsal) _rehearsalLedger.value else _ledger.value
        setLedger(l.withSession(rec), rehearsal)
        _session.value = _session.value?.copy(phase = SessionPhase.POST, elapsedMs = rec.audioMs, endReason = rec.reason, interruption = rec.interruption)
        engine?.release(); engine = null
        cancelled = true
        if (!rehearsal) sessions?.let { if (it.state.value?.activity == com.tinnitusrelief.app.engine.LabActivity.RIPPLE) it.stop(150) }
    }

    /**
     * Continues an interrupted session (explicit user action, within the resume window, inside the same 60 min)
     * instead of closing it with a report. The original before-rating is kept for the stop rule.
     */
    fun resume(): Boolean {
        val s = _session.value ?: return false
        if (s.phase != SessionPhase.POST || s.endReason != EndReason.INTERRUPTED) return false
        if (!canResume(s.rehearsal)) return false
        _session.value = null
        if (!prepare(s.rehearsal, resume = true).allowed) return false
        return begin(s.preRating)
    }

    /** After-session ratings and symptoms; applies the stop rules. */
    fun submitReport(after: Int?, symptoms: Set<AdverseSymptom>) {
        val s = _session.value ?: return
        if (s.phase != SessionPhase.POST) return
        val l = (if (s.rehearsal) _rehearsalLedger.value else _ledger.value).withReport(SessionReport(s.preRating, after, symptoms), System.currentTimeMillis(), limits)
        setLedger(l, s.rehearsal)
        _session.value = null
        gate = null
    }

    // ------------------------------------------------------------ interruptions observed without taking audio focus

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { interrupt(Interruption.BECOMING_NOISY) }
    }
    private var watching = false

    private fun watchInterruptions() {
        if (watching) return
        watching = true
        androidx.core.content.ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        routes.watch { refreshRoute(); interrupt(Interruption.OUTPUT_ROUTE_CHANGED) }
    }

    private fun unwatchInterruptions() {
        if (!watching) return
        watching = false
        runCatching { context.unregisterReceiver(noisy) }
        routes.unwatch()
    }

    /** Host is going away: end any session as interrupted. */
    fun release() {
        engine?.release(); engine = null
        cancelled = true
        unwatchInterruptions()
    }

    companion object {
        const val PUMP_BURST = 480
        const val PUMP_BUFFER_BURSTS = 10
    }
}
