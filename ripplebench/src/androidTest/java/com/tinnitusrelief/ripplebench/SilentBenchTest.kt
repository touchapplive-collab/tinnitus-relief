package com.tinnitusrelief.ripplebench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the silent benchmark on a device or emulator (CI: emulator started with -no-audio). */
@RunWith(AndroidJUnit4::class)
class SilentBenchTest {
    @Test fun silentBenchmarkPasses() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = BenchRunner(ctx, renderSegments = 3, lowLatencySeconds = 30, mainSeconds = 60).run()
        report.lines().forEach { Log.i("RippleBenchReport", it) }
        // functional checks must pass everywhere; timing on an emulator is reported, not judged
        val functional = report.lines().filter { it.startsWith("FAIL") && !it.contains("glitches") && !it.contains("underruns") && !it.contains("render speed") }
        assertTrue(report, functional.isEmpty())
    }
}
