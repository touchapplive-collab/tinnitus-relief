package com.tinnitusrelief.app.engine

import android.content.Context
import com.tinnitusrelief.app.dsp.ResponseCodec
import com.tinnitusrelief.app.dsp.ResponseRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stores subjective responses (pitch estimates, relief tests, residual inhibition tests, daily check-ins)
 * in a private text file. No analytics, no network, no export without the user's action.
 */
class LocalResponseTracker(context: Context) {

    private val file = File(context.filesDir, "responses.tsv")
    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<ResponseRecord>>(emptyList())
    val records: StateFlow<List<ResponseRecord>> = _records

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _records.value = if (file.exists()) ResponseCodec.decodeAll(file.readText()) else emptyList()
        }
    }

    suspend fun add(r: ResponseRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = when (r) {
                // one daily check-in per day: a new rating replaces the earlier one
                is ResponseRecord.Daily -> _records.value.filterNot { it is ResponseRecord.Daily && it.day == r.day } + r
                else -> _records.value + r
            }
            persist(next)
        }
    }

    /** Replaces the record stored at [atMs] (used to add a confidence rating to a pitch estimate). */
    suspend fun replace(atMs: Long, r: ResponseRecord) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(_records.value.map { if (it.atMs == atMs) r else it }) }
    }

    suspend fun delete(atMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(_records.value.filterNot { it.atMs == atMs }) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { persist(emptyList()) }
    }

    /** The data as readable text (the same format as the file), for the user to copy. */
    fun asText(): String = _records.value.joinToString("\n") { ResponseCodec.encode(it) }

    private fun persist(list: List<ResponseRecord>) {
        val sorted = list.sortedBy { it.atMs }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(sorted.joinToString("\n") { ResponseCodec.encode(it) })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _records.value = sorted
    }
}
