package com.vlogcam.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** External microphone discovery and Bluetooth (helmet/headset) mic routing. */
object Mics {

    data class Mic(val key: String, val label: String, val device: AudioDeviceInfo?) {
        val bluetooth: Boolean get() = key == "bt"
    }

    private fun audio(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Phone mic first, then any connected external inputs (one per kind). */
    fun list(context: Context): List<Mic> {
        val result = mutableListOf(Mic("phone", "Phone mic", null))
        val inputs = audio(context).getDevices(AudioManager.GET_DEVICES_INPUTS)
        fun name(d: AudioDeviceInfo, fallback: String) =
            d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: fallback
        inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }?.let { result += Mic("usb", "USB: " + name(it, "mic"), it) }
        inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?.let { result += Mic("wired", "Wired mic", it) }
        inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }?.let { result += Mic("bt", "Bluetooth: " + name(it, "headset"), it) }
        return result
    }

    fun find(context: Context, key: String): Mic = list(context).firstOrNull { it.key == key }
        ?: Mic("phone", "Phone mic", null)

    /** Opens the Bluetooth headset microphone link (needed for helmet/headset mics). */
    fun startBluetooth(context: Context) {
        val am = audio(context)
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (dev != null) runCatching { am.setCommunicationDevice(dev) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
        }
    }

    fun stopBluetooth(context: Context) {
        val am = audio(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
        }
        runCatching { am.mode = AudioManager.MODE_NORMAL }
    }
}
