package com.tinnitusrelief.ripplebench

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * One screen: explanation, "Run silent validation", progress and results. There is no playback control and no
 * audio output anywhere in this app.
 */
class BenchActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            status.text = if (BenchState.running) "Running… ${BenchState.progress}" else (BenchState.lastReport ?: saved() ?: "Not run yet.")
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        col.addView(TextView(this).apply {
            text = "Silent validation of the experimental spectral-ripple engine (Gate G3).\n\n" +
                "This app never plays sound: all audio is discarded inside the app. It measures whether the engine keeps up in real time on this phone, and checks the safety rules.\n\n" +
                "The full run takes about 8 minutes. During the second paced run, press Home and turn the screen off for a while, then come back."
            textSize = 15f
        })
        col.addView(Button(this).apply { text = "Run silent validation (≈ 8 min)"; setOnClickListener { start(false) } })
        col.addView(Button(this).apply { text = "Quick run (≈ 2 min)"; setOnClickListener { start(true) } })
        col.addView(Button(this).apply {
            text = "Copy results"
            setOnClickListener {
                val txt = BenchState.lastReport ?: saved() ?: ""
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ripple bench", txt))
            }
        })
        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); setPadding(0, pad, 0, 0) }
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun saved(): String? = File(filesDir, "bench-results.txt").takeIf { it.exists() }?.readText()

    private fun start(quick: Boolean) {
        if (BenchState.running) return
        startForegroundService(Intent(this, BenchService::class.java).putExtra(BenchService.EXTRA_QUICK, quick))
    }

    override fun onStart() { super.onStart(); BenchState.foreground = true; handler.post(tick) }
    override fun onStop() { super.onStop(); BenchState.foreground = false; handler.removeCallbacks(tick) }
}
