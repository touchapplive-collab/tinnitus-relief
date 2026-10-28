package com.tinnitusrelief.app

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tinnitusrelief.app.engine.Transport
import com.tinnitusrelief.app.playback.PlaybackService
import com.tinnitusrelief.app.ui.AppRoot
import com.tinnitusrelief.app.ui.theme.ReliefTheme

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val g = graph
        setContent {
            ReliefTheme {
                val c = controller.value
                AppRoot(
                    graph = g,
                    controlsReady = c != null,
                    onPlayPause = {
                        val mc = controller.value
                        if (mc != null) {
                            if (mc.playWhenReady) mc.pause() else mc.play()
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val c = runCatching { future.get() }.getOrNull()
                controller.value = c
                graph.transport = c?.let { mc ->
                    object : Transport {
                        override fun play() = mc.play()
                        override fun pause() = mc.pause()
                    }
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onStop() {
        graph.transport = null
        controller.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}
