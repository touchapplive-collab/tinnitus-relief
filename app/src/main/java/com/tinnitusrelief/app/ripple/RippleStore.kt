package com.tinnitusrelief.app.ripple

import android.content.Context
import com.tinnitusrelief.ripple.engine.SafetyLedger
import com.tinnitusrelief.ripple.therapy.TherapyState
import java.io.File

/** Private files for the spectral-ripple therapy layer. Existing app data is not touched. */
class RippleStore(context: Context) {
    private val dir = File(context.filesDir, "ripple").apply { mkdirs() }
    private val stateFile = File(dir, "state.txt")
    private val ledgerFile = File(dir, "ledger.tsv")
    private val rehearsalFile = File(dir, "rehearsal-ledger.tsv")

    fun loadState(): TherapyState = read(stateFile)?.let { TherapyState.decode(it) } ?: TherapyState()
    fun loadLedger(): SafetyLedger = read(ledgerFile)?.let { SafetyLedger.decode(it) } ?: SafetyLedger()
    fun loadRehearsalLedger(): SafetyLedger = read(rehearsalFile)?.let { SafetyLedger.decode(it) } ?: SafetyLedger()

    fun save(state: TherapyState) = write(stateFile, state.encode())
    fun saveLedger(l: SafetyLedger) = write(ledgerFile, l.encode())
    fun saveRehearsalLedger(l: SafetyLedger) = write(rehearsalFile, l.encode())

    private fun read(f: File): String? = if (f.exists()) runCatching { f.readText() }.getOrNull() else null

    @Synchronized private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
