package com.tinnitusrelief.app.ripple

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Identifies the current audio output (device type + product name), so a session can be tied to the
 * calibration record measured for exactly that output. Reading only; nothing is played.
 */
class OutputRoute(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)

    data class Route(val id: String, val headphones: Boolean, val label: String)

    fun current(): Route {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val pick = PRIORITY.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } }
            ?: return Route("speaker", false, "Phone speaker")
        val name = pick.productName?.toString()?.trim().orEmpty()
        return Route("${typeName(pick.type)}:$name", pick.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, if (name.isEmpty()) typeName(pick.type) else name)
    }

    private var callback: AudioDeviceCallback? = null

    /** Calls [onChange] whenever an output appears or disappears (route change). */
    fun watch(onChange: () -> Unit) {
        unwatch()
        callback = object : AudioDeviceCallback() {
            private var armed = false
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { if (armed) onChange() else armed = true }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { onChange() }
        }.also { am.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
    }

    fun unwatch() { callback?.let { am.unregisterAudioDeviceCallback(it) }; callback = null }

    companion object {
        private val PRIORITY = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* TYPE_BLE_HEADSET (API 31) */, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        fun typeName(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
            26 -> "ble-headset"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            else -> "type-$t"
        }
    }
}
